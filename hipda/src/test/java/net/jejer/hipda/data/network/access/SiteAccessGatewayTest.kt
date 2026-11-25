package net.jejer.hipda.data.network.access

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dagger.Binds
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.DefaultUserAgentProvider
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.gbkFormBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SiteAccessGatewayTest {

    private lateinit var nowMs: AtomicLong
    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var server: MockWebServer
    private lateinit var gatewayScope: CoroutineScope
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        nowMs = AtomicLong(1_000_000L)
        cookieStore = InMemorySiteCookieStore(clock = { nowMs.get() })
        server = MockWebServer()
        server.start()
        gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        gatewayScope.cancel()
        clientsToClose.forEach { client ->
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        clientsToClose.clear()
        server.close()
    }

    private fun createClient(
        maxRequests: Int? = null,
        maxRequestsPerHost: Int? = null,
        eventListener: EventListener? = null,
    ): OkHttpClient {
        val base = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = TransportInterceptor(
                delegateFactory = { null },
                isMainThread = { false },
            ),
        )
        val client = base.newBuilder().apply {
            if (maxRequests != null || maxRequestsPerHost != null) {
                dispatcher(
                    okhttp3.Dispatcher().apply {
                        if (maxRequests != null) this.maxRequests = maxRequests
                        if (maxRequestsPerHost != null) this.maxRequestsPerHost = maxRequestsPerHost
                    },
                )
            }
            if (eventListener != null) {
                eventListener(eventListener)
            }
        }.build()
        clientsToClose += client
        return client
    }

    private fun createGateway(
        solver: ChallengeSolver,
        client: OkHttpClient = createClient(),
    ): SiteAccessGateway = SiteAccessGateway(
        client = client,
        detector = ChallengeDetector(),
        solver = solver,
        clock = { nowMs.get() },
        gatewayScope = gatewayScope,
    )

    // (a) Single-flight: 10 concurrent requests -> solve called once, all 10 succeed with fresh cookie
    @Test
    fun singleFlight_tenConcurrentRequests_solvesOnceAndAllRetryWithFreshCookie() = runTest {
        val okCookies = CopyOnWriteArrayList<List<String>>()
        val okUserAgents = CopyOnWriteArrayList<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (hasFreshClearance(request)) {
                    okCookies += request.headers.values("Cookie")
                    okUserAgents += request.headers["User-Agent"]
                    MockResponse.Builder()
                        .code(200)
                        .body("ok:${request.url.encodedPath}")
                        .build()
                } else {
                    challenge403Response()
                }
            }
        }

        val solveInvocations = AtomicInteger(0)
        val solveStarted = CompletableDeferred<HttpUrl>()
        val releaseSolve = CompletableDeferred<Unit>()
        val gateway = createGateway(
            solver = ChallengeSolver { triggerUrl ->
                solveInvocations.incrementAndGet()
                solveStarted.complete(triggerUrl)
                releaseSolve.await()
                writeFreshClearanceCookie(triggerUrl)
                SolveResult.Cleared
            },
        )

        val jobs = (1..10).map { index ->
            async {
                val request = Request.Builder().url(server.url("/forum/thread-$index")).build()
                gateway.execute(request).use { response ->
                    response.code to response.body.string()
                }
            }
        }

        runCurrent()
        solveStarted.await()
        runCurrent()
        releaseSolve.complete(Unit)

        val results = jobs.awaitAll()
        assertEquals(1, solveInvocations.get())
        assertEquals(10, results.size)
        results.forEachIndexed { idx, (code, body) ->
            assertEquals(200, code)
            assertEquals("ok:/forum/thread-${idx + 1}", body)
        }
        assertEquals(10, okCookies.size)
        assertTrue(okCookies.all { headers -> headers.any { it.contains("cf_clearance=fresh") } })
        assertTrue(okUserAgents.all { it == TEST_UA })
    }

    // (b) Cancellation isolation: cancelling 1 of 5 waiters does not abort shared solve; remaining 4 succeed
    @Test
    fun cancellationIsolation_cancellingOneOfFiveWaiters_keepsSharedSolveAliveForRemainingFour() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (hasFreshClearance(request)) {
                    MockResponse.Builder().code(200).body("ok:${request.url.encodedPath}").build()
                } else {
                    challenge403Response()
                }
        }

        val solveInvocations = AtomicInteger(0)
        val solveStarted = CompletableDeferred<HttpUrl>()
        val releaseSolve = CompletableDeferred<Unit>()
        val solverCancelled = AtomicBoolean(false)
        val gateway = createGateway(
            solver = ChallengeSolver { triggerUrl ->
                solveInvocations.incrementAndGet()
                try {
                    solveStarted.complete(triggerUrl)
                    releaseSolve.await()
                    writeFreshClearanceCookie(triggerUrl)
                    SolveResult.Cleared
                } catch (e: CancellationException) {
                    solverCancelled.set(true)
                    throw e
                }
            },
        )

        val initiator = async {
            gateway.execute(Request.Builder().url(server.url("/forum/item-1")).build()).use { it.code }
        }
        runCurrent()
        solveStarted.await()

        val remainingWaiters = (2..5).map { index ->
            async {
                gateway.execute(Request.Builder().url(server.url("/forum/item-$index")).build()).use { response ->
                    response.code to response.body.string()
                }
            }
        }
        runCurrent()

        initiator.cancel()
        runCurrent()

        assertFalse("Shared solve must not be cancelled when caller cancels", solverCancelled.get())
        assertTrue(gateway.challengeState.value is ChallengeState.Solving)

        releaseSolve.complete(Unit)

        val remainingResults = remainingWaiters.awaitAll()
        assertEquals(1, solveInvocations.get())
        remainingResults.forEachIndexed { idx, (code, body) ->
            assertEquals(200, code)
            assertEquals("ok:/forum/item-${idx + 2}", body)
        }
        try {
            initiator.await()
            fail("Cancelled waiter should throw CancellationException")
        } catch (_: CancellationException) {
            // Expected
        }
    }

    // (c) Post-solve retry still challenged: <=2 network calls, 60s rejected cooldown, +61s recovers, UserExplicit bypasses
    @Test
    fun retryStillChallenged_limitsToTwoCalls_enforces60sCooldown_recoversAfter61s_andAllowsUserExplicit() = runTest {
        val networkCalls = AtomicInteger(0)
        val acceptRequests = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                networkCalls.incrementAndGet()
                return if (acceptRequests.get()) {
                    MockResponse.Builder().code(200).body("recovered").build()
                } else {
                    challenge403Response()
                }
            }
        }

        val solveInvocations = AtomicInteger(0)
        val gateway = createGateway(
            solver = ChallengeSolver {
                solveInvocations.incrementAndGet()
                SolveResult.Cleared
            },
        )

        val request = Request.Builder().url(server.url("/forum/")).build()
        val t0 = nowMs.get()

        val firstFailure = assertThrowsSuspend<ChallengeRejectedException> {
            gateway.execute(request)
        }
        assertEquals(t0 + SiteAccessGateway.REJECTED_COOLDOWN_MS, firstFailure.cooldownUntilMs)
        assertEquals(2, networkCalls.get())
        assertEquals(1, solveInvocations.get())
        assertEquals(
            ChallengeState.Cooldown(untilMs = t0 + SiteAccessGateway.REJECTED_COOLDOWN_MS, rejected = true),
            gateway.challengeState.value,
        )

        // During 60s cooldown, UserBlocking fails fast with zero network or solver calls.
        nowMs.addAndGet(30_000L)
        val duringCooldown = assertThrowsSuspend<ChallengeRejectedException> {
            gateway.execute(request, priority = AccessPriority.UserBlocking)
        }
        assertEquals(t0 + SiteAccessGateway.REJECTED_COOLDOWN_MS, duringCooldown.cooldownUntilMs)
        assertEquals(2, networkCalls.get())
        assertEquals(1, solveInvocations.get())

        // UserExplicit is allowed during the 60s cooldown and triggers a fresh solve attempt.
        nowMs.addAndGet(10_000L)
        val tExplicit = nowMs.get()
        val explicitFailure = assertThrowsSuspend<ChallengeRejectedException> {
            gateway.execute(request, priority = AccessPriority.UserExplicit)
        }
        assertEquals(tExplicit + SiteAccessGateway.REJECTED_COOLDOWN_MS, explicitFailure.cooldownUntilMs)
        assertEquals(4, networkCalls.get())
        assertEquals(2, solveInvocations.get())

        // Advancing virtual clock by 61s clears the rejected cooldown for normal requests.
        nowMs.addAndGet(61_000L)
        acceptRequests.set(true)
        gateway.execute(request, priority = AccessPriority.UserBlocking).use { response ->
            assertEquals(200, response.code)
            assertEquals("recovered", response.body.string())
        }
        assertEquals(5, networkCalls.get())
        assertEquals(ChallengeState.Idle, gateway.challengeState.value)
    }

    // (d) Failure rounds: 5 concurrent requests coalesce into 1 round; 3 rounds -> 30s cooldown; +31s recovers
    @Test
    fun failureRounds_coalescesFiveRequestsAsOneRound_threeRoundsTrigger30sCooldown_andRecoversAfter31s() = runTest {
        val networkCalls = AtomicInteger(0)
        val serverAllowsFresh = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                networkCalls.incrementAndGet()
                return if (serverAllowsFresh.get() && hasFreshClearance(request)) {
                    MockResponse.Builder().code(200).body("after-cooldown").build()
                } else {
                    challenge403Response()
                }
            }
        }

        val solveInvocations = AtomicInteger(0)
        val round1Started = CompletableDeferred<Unit>()
        val round1Release = CompletableDeferred<SolveResult>()
        val nextResult = AtomicReference<SolveResult>(SolveResult.Cancelled)
        val gateway = createGateway(
            solver = ChallengeSolver { triggerUrl ->
                val count = solveInvocations.incrementAndGet()
                if (count == 1) {
                    round1Started.complete(Unit)
                    round1Release.await()
                } else {
                    val result = nextResult.get()
                    if (result == SolveResult.Cleared) {
                        writeFreshClearanceCookie(triggerUrl)
                    }
                    result
                }
            },
        )

        // Round 1: 5 concurrent requests coalesce into a single Cancelled solve round.
        val first = async {
            assertThrowsSuspend<ChallengeIncompleteException> {
                gateway.execute(Request.Builder().url(server.url("/forum/r1-1")).build())
            }
        }
        runCurrent()
        round1Started.await()
        val others = (2..5).map { idx ->
            async {
                assertThrowsSuspend<ChallengeIncompleteException> {
                    gateway.execute(Request.Builder().url(server.url("/forum/r1-$idx")).build())
                }
            }
        }
        runCurrent()
        round1Release.complete(SolveResult.Cancelled)

        val round1Errors = listOf(first.await()) + others.awaitAll()
        assertEquals(1, solveInvocations.get())
        assertTrue(round1Errors.all { it.cooldownUntilMs == null })
        assertEquals(ChallengeState.Idle, gateway.challengeState.value)

        // Round 2: still below threshold (2/3), no cooldown yet.
        val round2Error = assertThrowsSuspend<ChallengeIncompleteException> {
            gateway.execute(Request.Builder().url(server.url("/forum/r2")).build())
        }
        assertEquals(2, solveInvocations.get())
        assertEquals(null, round2Error.cooldownUntilMs)
        assertEquals(ChallengeState.Idle, gateway.challengeState.value)

        // Round 3: hits 3 consecutive failed rounds -> enters 30s cooldown (rejected = false).
        val tRound3 = nowMs.get()
        val round3Error = assertThrowsSuspend<ChallengeIncompleteException> {
            gateway.execute(Request.Builder().url(server.url("/forum/r3")).build())
        }
        val expectedUntil = tRound3 + SiteAccessGateway.FAILURE_COOLDOWN_MS
        assertEquals(3, solveInvocations.get())
        assertEquals(expectedUntil, round3Error.cooldownUntilMs)
        assertEquals(
            ChallengeState.Cooldown(untilMs = expectedUntil, rejected = false),
            gateway.challengeState.value,
        )

        // Inside 30s cooldown: immediate ChallengeIncompleteException, no network or solver call.
        val callsAtCooldownStart = networkCalls.get()
        nowMs.addAndGet(15_000L)
        val blockedError = assertThrowsSuspend<ChallengeIncompleteException> {
            gateway.execute(Request.Builder().url(server.url("/forum/blocked")).build())
        }
        assertEquals(expectedUntil, blockedError.cooldownUntilMs)
        assertEquals(callsAtCooldownStart, networkCalls.get())
        assertEquals(3, solveInvocations.get())

        // Advance total 31s from Round 3: cooldown expires and next request can solve and succeed.
        nowMs.addAndGet(16_000L)
        serverAllowsFresh.set(true)
        nextResult.set(SolveResult.Cleared)
        gateway.execute(Request.Builder().url(server.url("/forum/recovered")).build()).use { response ->
            assertEquals(200, response.code)
            assertEquals("after-cooldown", response.body.string())
        }
        assertEquals(4, solveInvocations.get())
        assertEquals(ChallengeState.Idle, gateway.challengeState.value)
    }

    // (e) BackgroundSilent abandons on challenge without solving/counting and skips network during active solve
    @Test
    fun backgroundSilent_abandonsWithoutSolvingOrCounting_andSkipsNetworkWhileSolveActive() = runTest {
        val networkCalls = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                networkCalls.incrementAndGet()
                return if (hasFreshClearance(request)) {
                    MockResponse.Builder().code(200).body("ok").build()
                } else {
                    challenge403Response()
                }
            }
        }

        val solveInvocations = AtomicInteger(0)
        val solveStarted = CompletableDeferred<HttpUrl>()
        val releaseSolve = CompletableDeferred<Unit>()
        val gateway = createGateway(
            solver = ChallengeSolver { triggerUrl ->
                solveInvocations.incrementAndGet()
                solveStarted.complete(triggerUrl)
                releaseSolve.await()
                writeFreshClearanceCookie(triggerUrl)
                SolveResult.Cleared
            },
        )

        // Three BackgroundSilent hits must not invoke solver or trigger failure cooldown.
        repeat(3) {
            assertThrowsSuspend<ChallengeAbandonedException> {
                gateway.execute(
                    Request.Builder().url(server.url("/forum/poll")).build(),
                    priority = AccessPriority.BackgroundSilent,
                )
            }
        }
        assertEquals(0, solveInvocations.get())
        assertEquals(ChallengeState.Idle, gateway.challengeState.value)

        // Start a foreground solve; while active, BackgroundSilent abandons before sending any network request.
        val foreground = async {
            gateway.execute(Request.Builder().url(server.url("/forum/front")).build()).use { it.code }
        }
        runCurrent()
        solveStarted.await()

        val callsDuringSolve = networkCalls.get()
        assertThrowsSuspend<ChallengeAbandonedException> {
            gateway.execute(
                Request.Builder().url(server.url("/forum/bg-during-solve")).build(),
                priority = AccessPriority.BackgroundSilent,
            )
        }
        assertEquals(
            "BackgroundSilent must not send a network packet while challenge is active",
            callsDuringSolve,
            networkCalls.get(),
        )

        releaseSolve.complete(Unit)
        assertEquals(200, foreground.await())
        assertEquals(1, solveInvocations.get())
    }

    // (f) GBK form POST -> 403 -> solve(/forum/) -> retry identical bytes -> 302 -> GET 200
    @Test
    fun gbkFormPostChallenge_triggersForumUrl_resendsExactBytes_andFollows302ToFinalUrl() = runTest {
        val gbk = Charset.forName("GBK")
        val fields = listOf(
            "formhash" to "1a2b3c4d",
            "subject" to "测试主题😀",
            "message" to "中文正文内容&特殊=字符%",
        )
        val expectedBodyBytes = Buffer().also { gbkFormBody(fields).writeTo(it) }.readByteArray()
        val receivedPostBodies = CopyOnWriteArrayList<ByteArray>()
        val redirectCookieSeen = AtomicBoolean(false)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.url.encodedPath) {
                    "/forum/post.php" -> {
                        receivedPostBodies += (request.body?.toByteArray() ?: ByteArray(0))
                        if (!hasFreshClearance(request)) {
                            challenge403Response()
                        } else {
                            MockResponse.Builder()
                                .code(302)
                                .addHeader("Location", "/forum/viewthread.php?tid=123")
                                .addHeader("Set-Cookie", "cdb_lastpost=456; Path=/")
                                .build()
                        }
                    }

                    "/forum/viewthread.php" -> {
                        val cookies = request.headers.values("Cookie")
                        redirectCookieSeen.set(
                            cookies.any { it.contains("cf_clearance=fresh") && it.contains("cdb_lastpost=456") },
                        )
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=GBK")
                            .body(Buffer().write("发帖成功".toByteArray(gbk)))
                            .build()
                    }

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val capturedTriggerUrl = AtomicReference<HttpUrl>()
        val gateway = createGateway(
            solver = ChallengeSolver { triggerUrl ->
                capturedTriggerUrl.set(triggerUrl)
                writeFreshClearanceCookie(triggerUrl)
                SolveResult.Cleared
            },
        )

        val postRequest = Request.Builder()
            .url(server.url("/forum/post.php?action=reply&fid=2"))
            .post(gbkFormBody(fields))
            .build()

        gateway.execute(postRequest).use { response ->
            assertEquals(200, response.code)
            assertEquals(server.url("/forum/viewthread.php?tid=123"), response.request.url)
            assertEquals("发帖成功", String(response.body.bytes(), gbk))
        }

        val triggerUrl = capturedTriggerUrl.get()
        assertEquals(postRequest.url.scheme, triggerUrl.scheme)
        assertEquals(postRequest.url.host, triggerUrl.host)
        assertEquals(postRequest.url.port, triggerUrl.port)
        assertEquals("/forum/", triggerUrl.encodedPath)
        assertEquals(null, triggerUrl.query)

        assertEquals(2, receivedPostBodies.size)
        assertArrayEquals(expectedBodyBytes, receivedPostBodies[0])
        assertArrayEquals(expectedBodyBytes, receivedPostBodies[1])
        assertTrue("Redirect target GET must carry both fresh clearance and 302 cookie", redirectCookieSeen.get())
    }

    // (g) Call timeout: server stalls before response headers -> ~2s InterruptedIOException and call canceled
    @Test
    fun executeTimeout_whenServerStallsBeforeHeaders_throwsInterruptedIOExceptionAndCancelsCall() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .onResponseStart(SocketEffect.Stall)
                .build(),
        )

        val capturedCall = AtomicReference<Call>()
        val client = createClient(
            eventListener = object : EventListener() {
                override fun callStart(call: Call) {
                    capturedCall.set(call)
                }
            },
        )
        val gateway = createGateway(
            solver = ChallengeSolver { SolveResult.Failed(AssertionError("Solver should not run")) },
            client = client,
        )

        val startNanos = System.nanoTime()
        val exception = assertThrowsSuspend<InterruptedIOException> {
            gateway.execute(
                request = Request.Builder().url(server.url("/forum/stall")).build(),
                timeout = 2.seconds,
            )
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

        assertTrue("Expected timeout exception, got: $exception", exception is IOException)
        assertTrue("Expected timeout around 2s (>= 1500ms), actual: ${elapsedMs}ms", elapsedMs >= 1_500L)
        assertTrue("Expected timeout well before 30s client readTimeout (< 8000ms), actual: ${elapsedMs}ms", elapsedMs < 8_000L)
        assertTrue("Underlying OkHttp call must be canceled on timeout", capturedCall.get().isCanceled())
    }

    // (h) Does not occupy OkHttp dispatcher slots while suspended on challenge solve
    @Test
    fun suspendedChallengeWait_doesNotOccupyOkHttpDispatcherSlot() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.url.encodedPath) {
                    "/forum/challenged" -> if (hasFreshClearance(request)) {
                        MockResponse.Builder().code(200).body("unblocked").build()
                    } else {
                        challenge403Response()
                    }

                    "/img/direct" -> MockResponse.Builder().code(200).body("image-ok").build()
                    else -> MockResponse.Builder().code(404).build()
                }
        }

        val singleSlotClient = createClient(maxRequests = 1, maxRequestsPerHost = 1)
        val solveStarted = CompletableDeferred<HttpUrl>()
        val releaseSolve = CompletableDeferred<Unit>()
        val gateway = createGateway(
            solver = ChallengeSolver { triggerUrl ->
                solveStarted.complete(triggerUrl)
                releaseSolve.await()
                writeFreshClearanceCookie(triggerUrl)
                SolveResult.Cleared
            },
            client = singleSlotClient,
        )

        val blockedJob = async {
            gateway.execute(Request.Builder().url(server.url("/forum/challenged")).build()).use { response ->
                response.code to response.body.string()
            }
        }
        runCurrent()
        solveStarted.await()

        assertEquals(0, singleSlotClient.dispatcher.runningCallsCount())
        assertEquals(0, singleSlotClient.dispatcher.queuedCallsCount())

        // With maxRequests=1, another coroutine's request on the same client must still complete immediately.
        val directResult = async {
            suspendCancellableCoroutine { cont ->
                val call = singleSlotClient.newCall(Request.Builder().url(server.url("/img/direct")).build())
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            cont.resumeWith(Result.failure(e))
                        }

                        override fun onResponse(call: Call, response: Response) {
                            response.use { res ->
                                cont.resumeWith(Result.success(res.code to res.body.string()))
                            }
                        }
                    },
                )
            }
        }.await()

        assertEquals(200 to "image-ok", directResult)

        releaseSolve.complete(Unit)
        assertEquals(200 to "unblocked", blockedJob.await())
    }

    // (j) challengeState transitions: Idle -> Solving -> Idle / Cooldown(rejected = true/false) -> Idle
    @Test
    fun challengeState_transitionsAcrossIdleSolvingIdleAndBothCooldownModes() = runTest {
        val alwaysChallenge = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (!alwaysChallenge.get() && hasFreshClearance(request)) {
                    MockResponse.Builder().code(200).body("ok").build()
                } else {
                    challenge403Response()
                }
        }

        val solveStarted = AtomicReference(CompletableDeferred<Unit>())
        val releaseSolve = AtomicReference(CompletableDeferred<SolveResult>())
        val gateway = createGateway(
            solver = ChallengeSolver { triggerUrl ->
                solveStarted.get().complete(Unit)
                val result = releaseSolve.get().await()
                if (result == SolveResult.Cleared) {
                    writeFreshClearanceCookie(triggerUrl)
                }
                result
            },
        )

        // 1. Initial state is Idle
        assertEquals(ChallengeState.Idle, gateway.challengeState.value)

        // 2. Solve starts -> Solving(startedAtMs); finishes Cleared -> Idle
        val solveStartMs = nowMs.get()
        val okJob = async {
            gateway.execute(Request.Builder().url(server.url("/forum/1")).build()).use { it.code }
        }
        runCurrent()
        solveStarted.get().await()
        assertEquals(ChallengeState.Solving(startedAtMs = solveStartMs), gateway.challengeState.value)

        releaseSolve.get().complete(SolveResult.Cleared)
        assertEquals(200, okJob.await())
        assertEquals(ChallengeState.Idle, gateway.challengeState.value)

        // 3. Post-solve retry still challenged -> Cooldown(rejected = true) -> reset -> Idle
        alwaysChallenge.set(true)
        nowMs.addAndGet(5_000L)
        val rejectedAtMs = nowMs.get()
        solveStarted.set(CompletableDeferred())
        releaseSolve.set(CompletableDeferred<SolveResult>().apply { complete(SolveResult.Cleared) })
        assertThrowsSuspend<ChallengeRejectedException> {
            gateway.execute(Request.Builder().url(server.url("/forum/2")).build())
        }
        assertEquals(
            ChallengeState.Cooldown(
                untilMs = rejectedAtMs + SiteAccessGateway.REJECTED_COOLDOWN_MS,
                rejected = true,
            ),
            gateway.challengeState.value,
        )
        gateway.resetCooldownForUserRetry()
        assertEquals(ChallengeState.Idle, gateway.challengeState.value)

        // 4. Three failed rounds -> Cooldown(rejected = false) -> reset -> Idle
        val cause = IllegalStateException("webview failed")
        repeat(3) { round ->
            nowMs.addAndGet(1_000L)
            solveStarted.set(CompletableDeferred())
            releaseSolve.set(CompletableDeferred<SolveResult>().apply { complete(SolveResult.Failed(cause)) })
            val err = assertThrowsSuspend<ChallengeIncompleteException> {
                gateway.execute(Request.Builder().url(server.url("/forum/fail-$round")).build())
            }
            assertSame(cause, err.cause)
            if (round < 2) {
                assertEquals(ChallengeState.Idle, gateway.challengeState.value)
            } else {
                assertEquals(
                    ChallengeState.Cooldown(
                        untilMs = nowMs.get() + SiteAccessGateway.FAILURE_COOLDOWN_MS,
                        rejected = false,
                    ),
                    gateway.challengeState.value,
                )
            }
        }
        gateway.resetCooldownForUserRetry()
        assertEquals(ChallengeState.Idle, gateway.challengeState.value)
    }

    @Test
    fun resetCooldownForUserRetry_clearsFailureStreakBeforeThreshold() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = challenge403Response()
        }
        val gateway = createGateway(solver = ChallengeSolver { SolveResult.Cancelled })

        // 2 failures -> reset -> 2 more failures should NOT enter cooldown
        repeat(2) {
            assertThrowsSuspend<ChallengeIncompleteException> {
                gateway.execute(Request.Builder().url(server.url("/forum/a")).build())
            }
        }
        gateway.resetCooldownForUserRetry()
        repeat(2) {
            val err = assertThrowsSuspend<ChallengeIncompleteException> {
                gateway.execute(Request.Builder().url(server.url("/forum/b")).build())
            }
            assertEquals(null, err.cooldownUntilMs)
        }
        assertEquals(ChallengeState.Idle, gateway.challengeState.value)
    }

    @Test
    fun callerCancellationDuringInFlightHttpCall_cancelsUnderlyingOkHttpCall() = runTest {
        val reachedServer = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                reachedServer.countDown()
                releaseServer.await(5, TimeUnit.SECONDS)
                return MockResponse.Builder().code(200).body("late").build()
            }
        }

        val capturedCall = AtomicReference<Call>()
        val client = createClient(
            eventListener = object : EventListener() {
                override fun callStart(call: Call) {
                    capturedCall.set(call)
                }
            },
        )
        val gateway = createGateway(
            solver = ChallengeSolver { SolveResult.Failed(null) },
            client = client,
        )

        val job = async {
            gateway.execute(Request.Builder().url(server.url("/forum/slow")).build())
        }
        runCurrent()
        assertTrue(
            "Request did not reach server in time",
            withContext(Dispatchers.IO) { reachedServer.await(5, TimeUnit.SECONDS) },
        )

        job.cancel()
        runCurrent()
        assertTrue("Cancelling caller coroutine must cancel OkHttp Call", capturedCall.get().isCanceled())
        releaseServer.countDown()
    }

    @Test
    fun defaultChallengeSolverAndHiltBindings_areConfigured() = runTest {
        val defaultSolver = DefaultChallengeSolver()
        val result = defaultSolver.solve(server.url("/forum/"))
        assertTrue(result is SolveResult.Failed)

        assertTrue(SiteAccessGateway::class.java.isAnnotationPresent(Singleton::class.java))
        val injectCtor = SiteAccessGateway::class.java.getDeclaredConstructor(
            OkHttpClient::class.java,
            ChallengeDetector::class.java,
            ChallengeSolver::class.java,
        )
        assertTrue(injectCtor.isAnnotationPresent(Inject::class.java))

        val bindMethod = ChallengeSolverModule::class.java.getDeclaredMethod(
            "bindChallengeSolver",
            DefaultChallengeSolver::class.java,
        )
        assertTrue(bindMethod.isAnnotationPresent(Binds::class.java))
        assertTrue(bindMethod.isAnnotationPresent(Singleton::class.java))
    }

    private fun hasFreshClearance(request: RecordedRequest): Boolean =
        request.headers.values("Cookie").any { it.contains("cf_clearance=fresh") }

    private fun writeFreshClearanceCookie(url: HttpUrl) {
        cookieStore.saveFromResponse(
            url,
            listOf(requireNotNull(Cookie.parse(url, "cf_clearance=fresh; Path=/"))),
        )
    }

    private fun challenge403Response(): MockResponse = MockResponse.Builder()
        .code(403)
        .addHeader("cf-mitigated", "challenge")
        .addHeader("server", "cloudflare")
        .addHeader("Content-Type", "text/html; charset=UTF-8")
        .body("<html><head><title>Just a moment...</title></head><body><script>window._cf_chl_opt={cType:'managed'};</script></body></html>")
        .build()

    private suspend inline fun <reified T : Throwable> assertThrowsSuspend(
        block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("Expected ${T::class.java.simpleName}, but got ${t::class.java.name}: ${t.message}", t)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}, but completed normally")
    }

    private companion object {
        const val TEST_UA = DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA
    }
}
