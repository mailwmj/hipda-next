package net.jejer.hipda.ui.challenge

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.network.webview.WebViewCookieBridge
import okhttp3.HttpUrl

/**
 * Production [ChallengeSolver] backed by a transparent [ChallengeActivity] and hidden WebView:
 * 1. Snapshot existing `cf_clearance` values across Store and WebView.
 * 2. Back up and clear old `cf_clearance` from both sides.
 * 3. Prime WebView with non-clearance login cookies.
 * 4. Acquire a hosted [ChallengeWebViewHandle] configured with the unified User-Agent.
 * 5. Load [triggerUrl] and attempt silent auto-clearance within [backgroundTimeout].
 * 6. Escalate to [ChallengeInteractionPresenter] on timeout or Turnstile `interactiveBegin` /
 *    `interactiveTimeout`, competing against a single unified host-termination watcher.
 * 7. Confirm and sync fresh `cf_clearance` on success; on any cancel, failure, render-process
 *    death, or caller cancellation, close the host and restore the backup under [NonCancellable].
 */
@Singleton
class WebViewChallengeSolver internal constructor(
    private val cookieBridge: WebViewCookieBridge,
    private val userAgentProvider: UserAgentProvider,
    private val webViewHost: ChallengeWebViewHost,
    private val interactionPresenter: ChallengeInteractionPresenter,
    private val backgroundTimeout: Duration = DEFAULT_BACKGROUND_TIMEOUT,
    private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    private val hostAcquireTimeout: Duration = DEFAULT_HOST_ACQUIRE_TIMEOUT,
) : ChallengeSolver {

    @Inject
    constructor(
        cookieBridge: WebViewCookieBridge,
        userAgentProvider: UserAgentProvider,
        webViewHost: ChallengeWebViewHost,
        interactionPresenter: ChallengeInteractionPresenter,
    ) : this(
        cookieBridge = cookieBridge,
        userAgentProvider = userAgentProvider,
        webViewHost = webViewHost,
        interactionPresenter = interactionPresenter,
        backgroundTimeout = DEFAULT_BACKGROUND_TIMEOUT,
        pollInterval = DEFAULT_POLL_INTERVAL,
        hostAcquireTimeout = DEFAULT_HOST_ACQUIRE_TIMEOUT,
    )

    init {
        require(backgroundTimeout > Duration.ZERO) { "backgroundTimeout must be positive: $backgroundTimeout" }
        require(pollInterval > Duration.ZERO) { "pollInterval must be positive: $pollInterval" }
        require(hostAcquireTimeout > Duration.ZERO) { "hostAcquireTimeout must be positive: $hostAcquireTimeout" }
    }

    private val solveMutex = Mutex()

    private sealed interface TerminalEvent {
        data object HostDestroyed : TerminalEvent
        data class RenderProcessGone(val didCrash: Boolean, val priority: Int) : TerminalEvent

        fun toSolveResult(): SolveResult = when (this) {
            HostDestroyed -> SolveResult.Cancelled
            is RenderProcessGone -> SolveResult.Failed(
                ChallengeRenderProcessGoneException(
                    didCrash = didCrash,
                    rendererPriorityAtExit = priority,
                ),
            )
        }
    }

    private sealed interface BackgroundOutcome {
        data object Cleared : BackgroundOutcome
        data class Escalate(val reason: ChallengeInteractionReason) : BackgroundOutcome
    }

    override suspend fun solve(triggerUrl: HttpUrl): SolveResult = solveMutex.withLock {
        val snapshot = try {
            cookieBridge.snapshotClearances(triggerUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock SolveResult.Failed(e)
        }

        val backup = try {
            cookieBridge.clearClearance(triggerUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock SolveResult.Failed(e)
        }

        var handle: ChallengeWebViewHandle? = null
        var cleared = false

        try {
            cookieBridge.primeWebView(triggerUrl)

            val userAgent = userAgentProvider.userAgent()
            currentCoroutineContext().ensureActive()
            val acquired = withTimeoutOrNull(hostAcquireTimeout) {
                handle = webViewHost.acquire(triggerUrl, userAgent)
                Unit
            } != null
            val activeHandle = handle
            if (!acquired || activeHandle == null) {
                return@withLock SolveResult.Failed(
                    TimeoutException("Challenge host failed to start within $hostAcquireTimeout"),
                )
            }

            val result = runWithHandle(
                triggerUrl = triggerUrl,
                snapshot = snapshot,
                handle = activeHandle,
            )
            if (result == SolveResult.Cleared) {
                cleared = true
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            SolveResult.Failed(t)
        } finally {
            withContext(NonCancellable) {
                // Always finish tearing down the hosted WebView and Activity before touching
                // process-global CookieManager or releasing solveMutex for a subsequent solve.
                runCatching { handle?.closeAndAwait() }
                if (!cleared) {
                    runCatching { cookieBridge.restoreClearance(triggerUrl, backup) }
                }
            }
        }
    }

    private suspend fun runWithHandle(
        triggerUrl: HttpUrl,
        snapshot: Set<String>,
        handle: ChallengeWebViewHandle,
    ): SolveResult = coroutineScope {
        val terminalEvent = CompletableDeferred<TerminalEvent>()
        val interactiveReason = CompletableDeferred<ChallengeInteractionReason>()
        val pageChecks = Channel<Unit>(Channel.CONFLATED)

        val collectorJob = launch(start = CoroutineStart.UNDISPATCHED) {
            handle.events.collect { event ->
                when (event) {
                    is ChallengeWebViewEvent.PageFinished -> pageChecks.trySend(Unit)
                    ChallengeWebViewEvent.InteractiveBegin ->
                        interactiveReason.complete(ChallengeInteractionReason.InteractiveBegin)
                    ChallengeWebViewEvent.InteractiveTimeout ->
                        interactiveReason.complete(ChallengeInteractionReason.InteractiveTimeout)
                    is ChallengeWebViewEvent.RenderProcessGone ->
                        terminalEvent.complete(
                            TerminalEvent.RenderProcessGone(
                                didCrash = event.didCrash,
                                priority = event.rendererPriorityAtExit,
                            ),
                        )
                    ChallengeWebViewEvent.HostDestroyed ->
                        terminalEvent.complete(TerminalEvent.HostDestroyed)
                }
            }
        }

        val outcome = CompletableDeferred<SolveResult>()
        val terminalWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            outcome.complete(terminalEvent.await().toSolveResult())
        }
        var solveJob: kotlinx.coroutines.Job? = null

        try {
            handle.installChallengeMessageListener()
            handle.loadUrl(triggerUrl.toString())

            solveJob = launch {
                val phaseResult = try {
                    when (
                        val backgroundOutcome = awaitBackgroundOutcome(
                            triggerUrl = triggerUrl,
                            snapshot = snapshot,
                            handle = handle,
                            interactiveReason = interactiveReason,
                            pageChecks = pageChecks,
                        )
                    ) {
                        BackgroundOutcome.Cleared -> SolveResult.Cleared
                        is BackgroundOutcome.Escalate -> runInteractivePhase(
                            triggerUrl = triggerUrl,
                            snapshot = snapshot,
                            reason = backgroundOutcome.reason,
                            handle = handle,
                        )
                    }
                } catch (e: CancellationException) {
                    if (coroutineContext.isActive) {
                        outcome.complete(SolveResult.Cancelled)
                    }
                    throw e
                } catch (e: Exception) {
                    SolveResult.Failed(e)
                }
                outcome.complete(phaseResult)
            }

            val result = outcome.await()
            if (terminalEvent.isCompleted) {
                terminalEvent.await().toSolveResult()
            } else {
                result
            }
        } finally {
            terminalWatcher.cancel()
            solveJob?.cancel()
            collectorJob.cancel()
            pageChecks.close()
        }
    }

    private suspend fun awaitBackgroundOutcome(
        triggerUrl: HttpUrl,
        snapshot: Set<String>,
        handle: ChallengeWebViewHandle,
        interactiveReason: CompletableDeferred<ChallengeInteractionReason>,
        pageChecks: Channel<Unit>,
    ): BackgroundOutcome = coroutineScope {
        val result = CompletableDeferred<BackgroundOutcome>()

        val interactiveWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            result.complete(BackgroundOutcome.Escalate(interactiveReason.await()))
        }
        val budgetTimer = launch {
            delay(backgroundTimeout)
            result.complete(BackgroundOutcome.Escalate(ChallengeInteractionReason.BackgroundTimeout))
        }
        val poller = launch {
            while (true) {
                delay(pollInterval)
                pageChecks.trySend(Unit)
            }
        }
        val checker = launch {
            for (ignored in pageChecks) {
                if (result.isCompleted) break
                val isCleared = try {
                    tryCheckAndSyncClearance(triggerUrl, snapshot, handle)
                } catch (e: CancellationException) {
                    ensureActive()
                    false
                } catch (_: Exception) {
                    false
                }
                if (isCleared) {
                    result.complete(BackgroundOutcome.Cleared)
                    break
                }
            }
        }

        try {
            result.await()
        } finally {
            interactiveWatcher.cancel()
            budgetTimer.cancel()
            poller.cancel()
            checker.cancel()
        }
    }

    private suspend fun runInteractivePhase(
        triggerUrl: HttpUrl,
        snapshot: Set<String>,
        reason: ChallengeInteractionReason,
        handle: ChallengeWebViewHandle,
    ): SolveResult {
        val syncedInPresenter = AtomicBoolean(false)
        val session = ChallengeInteractionSession(
            triggerUrl = triggerUrl,
            reason = reason,
            webViewHandle = handle,
            activityBinding = handle.activityBinding,
            clearanceChecker = {
                tryCheckAndSyncClearance(triggerUrl, snapshot, handle).also { synced ->
                    if (synced) syncedInPresenter.set(true)
                }
            },
        )

        return when (val presentedResult = interactionPresenter.present(session)) {
            SolveResult.Cleared -> {
                if (syncedInPresenter.get()) {
                    SolveResult.Cleared
                } else {
                    val hasFreshClearance = (cookieBridge.snapshotClearances(triggerUrl) - snapshot).isNotEmpty()
                    val syncResult = if (hasFreshClearance) {
                        cookieBridge.syncFromWebView(
                            url = triggerUrl,
                            excludeClearanceValues = snapshot,
                        )
                    } else {
                        null
                    }
                    if (syncResult?.freshClearance != null) {
                        SolveResult.Cleared
                    } else {
                        SolveResult.Failed(
                            IllegalStateException("Interactive challenge returned Cleared without fresh cf_clearance"),
                        )
                    }
                }
            }
            SolveResult.Cancelled, is SolveResult.Failed -> presentedResult
        }
    }

    private suspend fun tryCheckAndSyncClearance(
        triggerUrl: HttpUrl,
        snapshot: Set<String>,
        handle: ChallengeWebViewHandle,
    ): Boolean {
        val mainFrame = handle.mainFrameState()
        if (!mainFrame.isReadyNonChallengePage) return false
        if (handle.isDomInChallengeState()) return false
        val currentClearances = cookieBridge.snapshotClearances(triggerUrl)
        if ((currentClearances - snapshot).isEmpty()) return false
        val syncResult = cookieBridge.syncFromWebView(
            url = triggerUrl,
            excludeClearanceValues = snapshot,
        )
        return syncResult.freshClearance != null
    }

    companion object {
        val DEFAULT_BACKGROUND_TIMEOUT: Duration = 8.seconds
        val DEFAULT_POLL_INTERVAL: Duration = 500.milliseconds
        val DEFAULT_HOST_ACQUIRE_TIMEOUT: Duration = 5.seconds
    }
}

/** Hilt module binding [ChallengeWebViewHost] to [AndroidChallengeWebViewHost]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChallengeHostModule {
    @Binds
    @Singleton
    abstract fun bindChallengeWebViewHost(impl: AndroidChallengeWebViewHost): ChallengeWebViewHost
}
