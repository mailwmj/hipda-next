package net.jejer.hipda.data.network.access

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * bounded retries, failure/rejected cooldowns, background abandonment, and per-call/solve timeouts.
 */
@Singleton
class SiteAccessGateway internal constructor(
    private val client: OkHttpClient,
    private val detector: ChallengeDetector,
    private val solver: ChallengeSolver,
    private val clock: () -> Long,
    private val gatewayScope: CoroutineScope,
    private val solveTimeout: Duration = DEFAULT_SOLVE_TIMEOUT,
) {
    @Inject
    constructor(
        client: OkHttpClient,
        detector: ChallengeDetector,
        solver: ChallengeSolver,
    ) : this(
        client,
        detector,
        solver,
        System::currentTimeMillis,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        DEFAULT_SOLVE_TIMEOUT,
    )

    init {
        require(solveTimeout > Duration.ZERO) { "solveTimeout must be positive: $solveTimeout" }
    }

    private class ActiveSolve {
        val deferred = CompletableDeferred<SolveOutcome>()
        var completed = false
        var solverJob: Job? = null
        var timeoutJob: Job? = null
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
        var supersededSolve: ActiveSolve? = null
        val pendingSolve = synchronized(stateLock) {
            checkCooldownLocked(priority)
            epochAtSend = clearanceEpoch
            val running = activeSolve
            when {
                running == null -> null
                priority == AccessPriority.BackgroundSilent -> throw ChallengeAbandonedException()
                priority == AccessPriority.UserExplicit -> {
                    supersededSolve = detachActiveSolveLocked()
                    null
                }
                else -> running
            }
        }
        supersededSolve?.let(::cancelDetachedSolve)
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
            if (request.body?.isOneShot() == true) throw ChallengeIncompleteException()

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
        var supersededSolve: ActiveSolve? = null
        val targetSolve = synchronized(stateLock) {
            checkCooldownLocked(priority)
            val running = activeSolve
            when {
                running != null && priority == AccessPriority.UserExplicit -> {
                    supersededSolve = detachActiveSolveLocked()
                    startSolveLocked().also { shouldLaunch = true }
                }
                running != null -> running
                clearanceEpoch > epochAtSend -> {
                    retryEpoch = clearanceEpoch
                    null
                }
                else -> startSolveLocked().also { shouldLaunch = true }
            }
        }
        supersededSolve?.let(::cancelDetachedSolve)
        if (targetSolve == null) return retryEpoch

        if (shouldLaunch) launchSolve(targetSolve, triggerUrl)
        return awaitOutcome(targetSolve)
    }

    private fun startSolveLocked(): ActiveSolve = ActiveSolve().also { created ->
        activeSolve = created
        _challengeState.value = ChallengeState.Solving(startedAtMs = clock())
    }

    private fun detachActiveSolveLocked(): ActiveSolve? {
        val old = activeSolve ?: return null
        activeSolve = null
        old.completed = true
        _challengeState.value = ChallengeState.Idle
        return old
    }

    private fun cancelDetachedSolve(old: ActiveSolve) {
        old.timeoutJob?.cancel()
        old.solverJob?.cancel(CancellationException("Superseded by UserExplicit retry"))
        old.deferred.complete(SolveOutcome.Incomplete(cooldownUntilMs = null, cause = null))
    }

    private fun launchSolve(solve: ActiveSolve, triggerUrl: HttpUrl) {
        val solverJob = gatewayScope.launch(start = CoroutineStart.LAZY) { runSolve(solve, triggerUrl) }
        val timeoutJob = if (solveTimeout.isFinite()) {
            gatewayScope.launch(start = CoroutineStart.LAZY) {
                delay(solveTimeout)
                completeSolve(
                    solve = solve,
                    result = SolveResult.Failed(TimeoutException("Challenge solve timed out after $solveTimeout")),
                    cancelSolver = true,
                )
            }
        } else {
            null
        }
        val alreadyCompleted = synchronized(stateLock) {
            if (solve.completed) true else {
                solve.solverJob = solverJob
                solve.timeoutJob = timeoutJob
                false
            }
        }
        if (alreadyCompleted) {
            solverJob.cancel()
            timeoutJob?.cancel()
            return
        }
        if (!solverJob.start() && solverJob.isCancelled && !solve.deferred.isCompleted) {
            completeSolve(solve, SolveResult.Failed(CancellationException("Gateway scope inactive")))
            return
        }
        timeoutJob?.start()
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

    private fun completeSolve(solve: ActiveSolve, result: SolveResult, cancelSolver: Boolean = false) {
        var solverJob: Job? = null
        var timeoutJob: Job? = null
        val outcome = synchronized(stateLock) {
            if (solve.completed) return
            solve.completed = true
            solverJob = solve.solverJob
            timeoutJob = solve.timeoutJob
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
        if (cancelSolver) {
            val cause = (result as? SolveResult.Failed)?.cause
            solverJob?.cancel(CancellationException("Challenge solve timed out", cause))
        } else {
            timeoutJob?.cancel()
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
        val DEFAULT_SOLVE_TIMEOUT: Duration = 120.seconds
        const val REJECTED_COOLDOWN_MS: Long = 60_000L
        const val FAILURE_COOLDOWN_MS: Long = 30_000L
        const val MAX_CONSECUTIVE_FAILURES: Int = 3
        const val FALLBACK_CHALLENGE_PATH: String = "/forum/"
    }
}
