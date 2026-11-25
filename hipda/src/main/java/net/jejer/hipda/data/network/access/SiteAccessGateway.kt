package net.jejer.hipda.data.network.access

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.ChallengeSignal
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Coroutine-layer site access gateway coordinating single-flight Cloudflare challenge solving,
 * bounded retries, failure/rejected cooldowns, background abandonment, and per-call timeouts.
 */
@Singleton
class SiteAccessGateway internal constructor(
    private val client: OkHttpClient,
    private val detector: ChallengeDetector,
    private val solver: ChallengeSolver,
    private val clock: () -> Long,
    private val gatewayScope: CoroutineScope,
) {
    @Inject
    constructor(
        client: OkHttpClient,
        detector: ChallengeDetector,
        solver: ChallengeSolver,
    ) : this(client, detector, solver, System::currentTimeMillis, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private class ActiveSolve {
        val deferred = CompletableDeferred<SolveOutcome>()
        var completed = false
    }

    private sealed interface SolveOutcome {
        data class Cleared(val newEpoch: Long) : SolveOutcome
        data class Incomplete(val cooldownUntilMs: Long?, val cause: Throwable?) : SolveOutcome
    }

    private val stateLock = Any()
    private var clearanceEpoch = 0L
    private var consecutiveFailures = 0
    private var cooldownUntilMs = 0L
    private var cooldownRejected = false
    private var activeSolve: ActiveSolve? = null

    private val _challengeState = MutableStateFlow<ChallengeState>(ChallengeState.Idle)
    val challengeState: StateFlow<ChallengeState> = _challengeState.asStateFlow()

    suspend fun execute(
        request: Request,
        priority: AccessPriority = AccessPriority.UserBlocking,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): Response {
        require(timeout > Duration.ZERO) { "timeout must be positive: $timeout" }

        var epochAtSend = 0L
        val pendingSolve = synchronized(stateLock) {
            checkCooldownLocked(priority)
            epochAtSend = clearanceEpoch
            activeSolve?.also {
                if (priority == AccessPriority.BackgroundSilent) throw ChallengeAbandonedException()
            }
        }
        var alreadySolved = pendingSolve != null
        if (pendingSolve != null) epochAtSend = awaitOutcome(pendingSolve)

        while (true) {
            val response = executeCall(request, timeout)
            val signal = try {
                detector.inspect(response)
            } catch (t: Throwable) {
                response.close()
                throw t
            }
            if (signal is ChallengeSignal.None) return response
            response.close()

            if (alreadySolved) throw ChallengeRejectedException(enterRejectedCooldown(epochAtSend))
            if (priority == AccessPriority.BackgroundSilent) throw ChallengeAbandonedException()

            epochAtSend = resolveChallenge(epochAtSend, buildTriggerUrl(request), priority)
            alreadySolved = true
        }
    }

    fun resetCooldownForUserRetry() = synchronized(stateLock) {
        clearCooldownLocked()
        consecutiveFailures = 0
    }

    private suspend fun resolveChallenge(epochAtSend: Long, triggerUrl: HttpUrl, priority: AccessPriority): Long {
        var shouldLaunch = false
        var retryEpoch = epochAtSend
        val targetSolve = synchronized(stateLock) {
            checkCooldownLocked(priority)
            val running = activeSolve
            when {
                running != null -> running
                clearanceEpoch > epochAtSend -> {
                    retryEpoch = clearanceEpoch
                    null
                }
                else -> ActiveSolve().also { created ->
                    activeSolve = created
                    shouldLaunch = true
                    _challengeState.value = ChallengeState.Solving(startedAtMs = clock())
                }
            }
        } ?: return retryEpoch

        if (shouldLaunch) {
            val job = gatewayScope.launch { runSolve(targetSolve, triggerUrl) }
            if (job.isCancelled && !targetSolve.deferred.isCompleted) {
                completeSolve(targetSolve, SolveResult.Failed(CancellationException("Gateway scope inactive")))
            }
        }
        return awaitOutcome(targetSolve)
    }

    private suspend fun runSolve(solve: ActiveSolve, triggerUrl: HttpUrl) {
        var result: SolveResult = SolveResult.Failed(null)
        try {
            result = solver.solve(triggerUrl)
        } catch (_: CancellationException) {
            result = SolveResult.Cancelled
        } catch (e: Exception) {
            result = SolveResult.Failed(e)
        } finally {
            completeSolve(solve, result)
        }
    }

    private fun completeSolve(solve: ActiveSolve, result: SolveResult) {
        val outcome = synchronized(stateLock) {
            if (solve.completed) return
            solve.completed = true
            if (activeSolve === solve) activeSolve = null
            when (result) {
                SolveResult.Cleared -> {
                    clearanceEpoch++
                    consecutiveFailures = 0
                    clearCooldownLocked()
                    _challengeState.value = ChallengeState.Idle
                    SolveOutcome.Cleared(clearanceEpoch)
                }
                SolveResult.Cancelled, is SolveResult.Failed -> {
                    consecutiveFailures++
                    val cause = (result as? SolveResult.Failed)?.cause
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        val untilMs = clock() + FAILURE_COOLDOWN_MS
                        consecutiveFailures = 0
                        cooldownUntilMs = untilMs
                        cooldownRejected = false
                        _challengeState.value = ChallengeState.Cooldown(untilMs = untilMs, rejected = false)
                        SolveOutcome.Incomplete(cooldownUntilMs = untilMs, cause = cause)
                    } else {
                        _challengeState.value = ChallengeState.Idle
                        SolveOutcome.Incomplete(cooldownUntilMs = null, cause = cause)
                    }
                }
            }
        }
        solve.deferred.complete(outcome)
    }

    private suspend fun awaitOutcome(solve: ActiveSolve): Long =
        when (val outcome = solve.deferred.await()) {
            is SolveOutcome.Cleared -> outcome.newEpoch
            is SolveOutcome.Incomplete -> throw ChallengeIncompleteException(outcome.cooldownUntilMs, outcome.cause)
        }

    private fun enterRejectedCooldown(epochAtSend: Long): Long = synchronized(stateLock) {
        val now = clock()
        val untilMs = if (cooldownUntilMs > now && cooldownRejected) cooldownUntilMs else now + REJECTED_COOLDOWN_MS
        if (clearanceEpoch == epochAtSend && activeSolve == null) {
            cooldownUntilMs = untilMs
            cooldownRejected = true
            _challengeState.value = ChallengeState.Cooldown(untilMs = untilMs, rejected = true)
        }
        untilMs
    }

    private fun checkCooldownLocked(priority: AccessPriority) {
        val now = clock()
        if (cooldownUntilMs > 0L && now >= cooldownUntilMs) clearCooldownLocked()
        if (priority == AccessPriority.UserExplicit) {
            clearCooldownLocked()
            consecutiveFailures = 0
        } else if (cooldownUntilMs > now) {
            throw if (cooldownRejected) ChallengeRejectedException(cooldownUntilMs)
            else ChallengeIncompleteException(cooldownUntilMs = cooldownUntilMs)
        }
    }

    private fun clearCooldownLocked() {
        cooldownUntilMs = 0L
        cooldownRejected = false
        if (activeSolve == null && _challengeState.value is ChallengeState.Cooldown) {
            _challengeState.value = ChallengeState.Idle
        }
    }

    private suspend fun executeCall(request: Request, timeout: Duration): Response {
        val call = client.newCall(request)
        if (timeout.isFinite()) {
            call.timeout().timeout(timeout.inWholeMilliseconds.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } else {
            call.timeout().clearTimeout()
        }
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        cont.resume(response) { _, value, _ -> value.close() }
                    }
                },
            )
        }
    }

    private fun buildTriggerUrl(request: Request): HttpUrl =
        if (request.method.equals("GET", ignoreCase = true)) {
            request.url
        } else {
            HttpUrl.Builder()
                .scheme(request.url.scheme)
                .host(request.url.host)
                .port(request.url.port)
                .encodedPath(FALLBACK_CHALLENGE_PATH)
                .build()
        }

    companion object {
        val DEFAULT_TIMEOUT: Duration = 30.seconds
        const val REJECTED_COOLDOWN_MS: Long = 60_000L
        const val FAILURE_COOLDOWN_MS: Long = 30_000L
        const val MAX_CONSECUTIVE_FAILURES: Int = 3
        const val FALLBACK_CHALLENGE_PATH: String = "/forum/"
    }
}
