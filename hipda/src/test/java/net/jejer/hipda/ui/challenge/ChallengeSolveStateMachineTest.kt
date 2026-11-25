package net.jejer.hipda.ui.challenge

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.network.webview.CF_CLEARANCE_COOKIE_NAME
import net.jejer.hipda.data.network.webview.WebViewCookieBridge
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class ChallengeSolveStateMachineTest {

    private val forumUrl: HttpUrl = "https://www.4d4y.com/forum/".toHttpUrl()
    private val unifiedUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
    private val httpDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC)

    private lateinit var store: InMemorySiteCookieStore
    private lateinit var fakeCookieAccess: TestWebViewCookieAccess

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        store = InMemorySiteCookieStore()
        fakeCookieAccess = TestWebViewCookieAccess()
    }

    private fun futureHttpDate(offsetSeconds: Long = 1800): String {
        val epochSeconds = (System.currentTimeMillis() / 1_000L) + offsetSeconds
        return httpDateFormatter.format(Instant.ofEpochSecond(epochSeconds))
    }

    private fun clearanceHeader(value: String, offsetSeconds: Long = 1800): String =
        "$CF_CLEARANCE_COOKIE_NAME=$value; Domain=.4d4y.com; Path=/; Expires=${futureHttpDate(offsetSeconds)}; Secure; HttpOnly; SameSite=None"

    private fun parseCookie(raw: String, url: HttpUrl = forumUrl): Cookie =
        requireNotNull(Cookie.parse(url, raw)) { "Failed to parse cookie: $raw" }

    private fun seedOldClearanceAndLogin(oldValue: String = "old_clearance_tok") {
        val oldHeader = clearanceHeader(oldValue)
        val authHeader = "cdb_auth=login_token; Domain=.4d4y.com; Path=/; HttpOnly"
        store.saveFromResponse(forumUrl, listOf(parseCookie(oldHeader), parseCookie(authHeader)))
        fakeCookieAccess.seedCookie(forumUrl, oldHeader)
    }

    private fun TestScope.createSolver(
        host: ChallengeWebViewHost,
        presenter: ChallengeInteractionPresenter = DefaultChallengeInteractionPresenter(),
        backgroundTimeout: Duration = 8.seconds,
        pollInterval: Duration = 500.milliseconds,
        hostAcquireTimeout: Duration = 5.seconds,
    ): WebViewChallengeSolver {
        val bridge = WebViewCookieBridge(
            siteCookieStore = store,
            webViewCookieAccess = fakeCookieAccess,
            cookieDispatcher = StandardTestDispatcher(testScheduler),
        )
        return WebViewChallengeSolver(
            cookieBridge = bridge,
            userAgentProvider = UserAgentProvider { unifiedUa },
            webViewHost = host,
            interactionPresenter = presenter,
            backgroundTimeout = backgroundTimeout,
            pollInterval = pollInterval,
            hostAcquireTimeout = hostAcquireTimeout,
        )
    }

    // --- 2a. Auto-clear happy path ---

    @Test
    fun autoClear_snapshotsClearsPrimesLoadsAndSyncsFreshClearanceWithoutRestoringBackup() = runTest {
        seedOldClearanceAndLogin(oldValue = "old_tok")
        val handle = FakeChallengeWebViewHandle()
        val acquiredUa = AtomicReference<String>()
        var cookiesInStoreAtLoad: List<Cookie> = emptyList()
        var cookiesInWebViewAtLoad: List<String> = emptyList()

        handle.onLoadUrl = { url ->
            cookiesInStoreAtLoad = store.loadForRequest(forumUrl)
            cookiesInWebViewAtLoad = fakeCookieAccess.storedRawHeaders(forumUrl)
            // Simulate WebView receiving a fresh cf_clearance and reaching the real page
            fakeCookieAccess.seedCookie(forumUrl, clearanceHeader("fresh_auto_tok"))
            handle.currentMainFrameState = MainFrameState(url = url, pageFinished = true)
            handle.domInChallengeState = false
            handle.emit(ChallengeWebViewEvent.PageFinished(url))
        }

        val presenterCalls = AtomicInteger(0)
        val solver = createSolver(
            host = ChallengeWebViewHost { _, ua ->
                acquiredUa.set(ua)
                handle
            },
            presenter = ChallengeInteractionPresenter {
                presenterCalls.incrementAndGet()
                SolveResult.Cancelled
            },
        )

        val result = solver.solve(forumUrl)

        assertEquals(SolveResult.Cleared, result)
        assertEquals(unifiedUa, acquiredUa.get())
        assertEquals(0, presenterCalls.get())
        assertTrue("Listener must be installed before loadUrl", handle.actions.indexOf("installListener") < handle.actions.indexOf("loadUrl:$forumUrl"))
        assertTrue("Host handle must be closed on auto-clear", handle.closed)

        // Verify pre-load state: old cf_clearance cleared on both sides, login cookie primed into WebView
        assertTrue(cookiesInStoreAtLoad.none { it.name == CF_CLEARANCE_COOKIE_NAME })
        assertTrue(cookiesInStoreAtLoad.any { it.name == "cdb_auth" && it.value == "login_token" })
        assertTrue(cookiesInWebViewAtLoad.none { it.startsWith("$CF_CLEARANCE_COOKIE_NAME=") })
        assertTrue(cookiesInWebViewAtLoad.any { it.startsWith("cdb_auth=login_token") })

        // Verify fresh cf_clearance synced into Store and old backup NOT restored
        val finalClearances = store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }
        assertEquals(listOf("fresh_auto_tok"), finalClearances.map { it.value })
    }

    // --- 2b. Old snapshot value does not false-clear ---

    @Test
    fun staleClearanceInSnapshot_doesNotFalseClear_waitsUntilFreshClearanceArrives() = runTest {
        seedOldClearanceAndLogin(oldValue = "stale_snapshot_tok")
        val handle = FakeChallengeWebViewHandle()

        handle.onLoadUrl = { url ->
            // WebView somehow still returns the old snapshot cf_clearance even after page finished
            fakeCookieAccess.seedCookie(forumUrl, clearanceHeader("stale_snapshot_tok"))
            handle.currentMainFrameState = MainFrameState(url = url, pageFinished = true)
            handle.domInChallengeState = false
            handle.emit(ChallengeWebViewEvent.PageFinished(url))
        }

        val solver = createSolver(host = ChallengeWebViewHost { _, _ -> handle })
        val solveDeferred = async { solver.solve(forumUrl) }

        // Advance 2 seconds: only stale cookie exists -> must still be pending, NOT cleared
        advanceTimeBy(2_000L)
        runCurrent()
        assertFalse("Must not clear with stale cf_clearance from snapshot", solveDeferred.isCompleted)
        assertFalse(handle.closed)

        // Now Cloudflare finishes writing a truly fresh cf_clearance
        fakeCookieAccess.seedCookie(forumUrl, clearanceHeader("brand_new_tok"))
        advanceTimeBy(600L)
        runCurrent()

        assertTrue("Should clear once fresh cf_clearance appears", solveDeferred.isCompleted)
        assertEquals(SolveResult.Cleared, solveDeferred.await())
        assertTrue(handle.closed)
        assertEquals(
            listOf("brand_new_tok"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
    }

    @Test
    fun challengedMainFrameOrActiveChallengeDom_blocksClearanceUntilChallengeResolves() = runTest {
        seedOldClearanceAndLogin(oldValue = "old_tok")
        val handle = FakeChallengeWebViewHandle()

        handle.onLoadUrl = { url ->
            // Fresh cookie already written, but main frame is still 403 + cf-mitigated: challenge
            fakeCookieAccess.seedCookie(forumUrl, clearanceHeader("early_fresh_tok"))
            handle.currentMainFrameState = MainFrameState(
                url = url,
                pageFinished = true,
                hasCfMitigatedChallengeHeader = true,
                httpErrorStatusCode = 403,
            )
            handle.domInChallengeState = true
            handle.emit(ChallengeWebViewEvent.PageFinished(url))
        }

        val solver = createSolver(host = ChallengeWebViewHost { _, _ -> handle })
        val solveDeferred = async { solver.solve(forumUrl) }

        advanceTimeBy(1_500L)
        runCurrent()
        assertFalse("Must not clear while main frame is challenged and DOM has markers", solveDeferred.isCompleted)

        // Main frame clears, but DOM still reports challenge markers
        handle.currentMainFrameState = MainFrameState(url = forumUrl.toString(), pageFinished = true)
        handle.emit(ChallengeWebViewEvent.PageFinished(forumUrl.toString()))
        advanceTimeBy(1_000L)
        runCurrent()
        assertFalse("Must not clear while DOM is still in challenge state", solveDeferred.isCompleted)

        // DOM leaves challenge state
        handle.domInChallengeState = false
        advanceTimeBy(600L)
        runCurrent()

        assertEquals(SolveResult.Cleared, solveDeferred.await())
        assertTrue(handle.closed)
    }

    // --- 2c. 8-second background timeout ---

    @Test
    fun backgroundTimeout_afterEightSeconds_escalatesToDefaultPresenterRestoresBackupAndReturnsCancelled() = runTest {
        seedOldClearanceAndLogin(oldValue = "backup_tok")
        val handle = FakeChallengeWebViewHandle()
        val capturedReason = AtomicReference<ChallengeInteractionReason>()
        val escalationTimeMs = AtomicReference<Long>()

        val presenter = ChallengeInteractionPresenter { session ->
            capturedReason.set(session.reason)
            escalationTimeMs.set(currentTime)
            DefaultChallengeInteractionPresenter().present(session)
        }
        val solver = createSolver(
            host = ChallengeWebViewHost { _, _ -> handle },
            presenter = presenter,
        )

        val solveDeferred = async { solver.solve(forumUrl) }

        advanceTimeBy(7_999L)
        runCurrent()
        assertFalse("Must not time out before 8 seconds", solveDeferred.isCompleted)
        assertNull(capturedReason.get())

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(SolveResult.Cancelled, solveDeferred.await())
        assertEquals(ChallengeInteractionReason.BackgroundTimeout, capturedReason.get())
        assertEquals(8_000L, escalationTimeMs.get())
        assertTrue("Host handle must be closed after timeout cancellation", handle.closed)

        // Both Store and WebView must have backup restored
        assertEquals(
            listOf("backup_tok"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
        assertTrue(
            "WebView backup must be restored",
            fakeCookieAccess.storedRawHeaders(forumUrl).any { it.startsWith("$CF_CLEARANCE_COOKIE_NAME=backup_tok") },
        )
    }

    // --- 2d. interactiveBegin & interactiveTimeout ---

    @Test
    fun interactiveBegin_escalatesImmediatelyBeforeEightSeconds_defaultCancelsAndRestoresBackup() = runTest {
        seedOldClearanceAndLogin(oldValue = "backup_interactive_tok")
        val handle = FakeChallengeWebViewHandle()
        val capturedSession = AtomicReference<ChallengeInteractionSession>()
        val escalatedAtMs = AtomicReference<Long>()

        val presenter = ChallengeInteractionPresenter { session ->
            capturedSession.set(session)
            escalatedAtMs.set(currentTime)
            DefaultChallengeInteractionPresenter().present(session)
        }
        val solver = createSolver(
            host = ChallengeWebViewHost { _, _ -> handle },
            presenter = presenter,
        )

        val solveDeferred = async { solver.solve(forumUrl) }
        advanceTimeBy(400L)
        runCurrent()

        handle.emit(ChallengeWebViewEvent.InteractiveBegin)
        runCurrent()

        assertEquals(SolveResult.Cancelled, solveDeferred.await())
        assertEquals(400L, escalatedAtMs.get())
        assertEquals(ChallengeInteractionReason.InteractiveBegin, capturedSession.get().reason)
        assertSame(handle, capturedSession.get().webViewHandle)
        assertTrue(handle.closed)

        // Backup restored on both sides
        assertEquals(
            listOf("backup_interactive_tok"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
        assertTrue(
            fakeCookieAccess.storedRawHeaders(forumUrl).any {
                it.startsWith("$CF_CLEARANCE_COOKIE_NAME=backup_interactive_tok")
            },
        )
    }

    @Test
    fun interactiveTimeout_escalatesImmediatelyAndCustomPresenterClearedSyncsFreshCookie() = runTest {
        seedOldClearanceAndLogin(oldValue = "old_before_interactive")
        val handle = FakeChallengeWebViewHandle()
        val capturedReason = AtomicReference<ChallengeInteractionReason>()
        val escalatedAtMs = AtomicReference<Long>()

        val customPresenter = ChallengeInteractionPresenter { session ->
            capturedReason.set(session.reason)
            escalatedAtMs.set(currentTime)
            assertSame(handle, session.webViewHandle)
            assertEquals(forumUrl, session.triggerUrl)
            // Simulate user solving Turnstile in the reused WebView without reloading
            fakeCookieAccess.seedCookie(forumUrl, clearanceHeader("user_solved_tok"))
            handle.currentMainFrameState = MainFrameState(url = forumUrl.toString(), pageFinished = true)
            handle.domInChallengeState = false
            assertTrue(session.checkAndSyncClearance())
            SolveResult.Cleared
        }
        val solver = createSolver(
            host = ChallengeWebViewHost { _, _ -> handle },
            presenter = customPresenter,
        )

        val solveDeferred = async { solver.solve(forumUrl) }
        advanceTimeBy(1_200L)
        runCurrent()

        handle.emit(ChallengeWebViewEvent.InteractiveTimeout)
        runCurrent()

        assertEquals(SolveResult.Cleared, solveDeferred.await())
        assertEquals(1_200L, escalatedAtMs.get())
        assertEquals(ChallengeInteractionReason.InteractiveTimeout, capturedReason.get())
        assertTrue(handle.closed)
        assertEquals(
            listOf("user_solved_tok"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
    }

    @Test
    fun customPresenterReturningClearedWithoutFreshCookie_restoresBackupAndReturnsFailed() = runTest {
        seedOldClearanceAndLogin(oldValue = "backup_tok")
        val handle = FakeChallengeWebViewHandle()
        val solver = createSolver(
            host = ChallengeWebViewHost { _, _ -> handle },
            presenter = ChallengeInteractionPresenter {
                // Only stale cookie or no cookie present
                fakeCookieAccess.seedCookie(forumUrl, clearanceHeader("backup_tok"))
                SolveResult.Cleared
            },
        )

        val solveDeferred = async { solver.solve(forumUrl) }
        runCurrent()
        handle.emit(ChallengeWebViewEvent.InteractiveBegin)
        runCurrent()

        val result = solveDeferred.await()
        assertTrue("Expected Failed when no fresh clearance exists, got: $result", result is SolveResult.Failed)
        assertTrue(handle.closed)
        assertEquals(
            listOf("backup_tok"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
    }

    @Test
    fun selfCancellingPresenter_convergesToCancelledAndRestoresBackupWithoutHanging() = runTest {
        seedOldClearanceAndLogin(oldValue = "backup_self_cancel")
        val handle = FakeChallengeWebViewHandle()
        val solver = createSolver(
            host = ChallengeWebViewHost { _, _ -> handle },
            presenter = ChallengeInteractionPresenter {
                throw CancellationException("Presenter dismissed internally")
            },
        )

        val solveDeferred = async { solver.solve(forumUrl) }
        runCurrent()
        handle.emit(ChallengeWebViewEvent.InteractiveBegin)
        runCurrent()

        assertEquals(SolveResult.Cancelled, solveDeferred.await())
        assertTrue(handle.closed)
        assertEquals(
            listOf("backup_self_cancel"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
    }

    // --- 2e. onRenderProcessGone ---

    @Test
    fun renderProcessGone_duringBackgroundPhase_restoresBackupClosesAndReturnsFailed() = runTest {
        seedOldClearanceAndLogin(oldValue = "backup_crash_tok")
        val handle = FakeChallengeWebViewHandle()
        val solver = createSolver(host = ChallengeWebViewHost { _, _ -> handle })

        val solveDeferred = async { solver.solve(forumUrl) }
        advanceTimeBy(300L)
        runCurrent()

        handle.emit(ChallengeWebViewEvent.RenderProcessGone(didCrash = true, rendererPriorityAtExit = 2))
        runCurrent()

        val result = solveDeferred.await()
        assertTrue("Expected Failed result, got $result", result is SolveResult.Failed)
        val cause = (result as SolveResult.Failed).cause
        assertTrue("Expected ChallengeRenderProcessGoneException, got $cause", cause is ChallengeRenderProcessGoneException)
        val renderEx = cause as ChallengeRenderProcessGoneException
        assertTrue(renderEx.didCrash)
        assertEquals(2, renderEx.rendererPriorityAtExit)
        assertTrue(handle.closed)

        assertEquals(
            listOf("backup_crash_tok"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
        assertTrue(
            fakeCookieAccess.storedRawHeaders(forumUrl).any {
                it.startsWith("$CF_CLEARANCE_COOKIE_NAME=backup_crash_tok")
            },
        )
    }

    @Test
    fun renderProcessGone_duringInteractivePresentation_cancelsPresenterRestoresBackupAndReturnsFailed() = runTest {
        seedOldClearanceAndLogin(oldValue = "backup_interactive_crash")
        val handle = FakeChallengeWebViewHandle()
        val presenterStarted = CompletableDeferred<Unit>()
        val presenterCancelled = CompletableDeferred<Unit>()

        val solver = createSolver(
            host = ChallengeWebViewHost { _, _ -> handle },
            presenter = ChallengeInteractionPresenter {
                presenterStarted.complete(Unit)
                try {
                    CompletableDeferred<SolveResult>().await()
                } catch (e: CancellationException) {
                    presenterCancelled.complete(Unit)
                    throw e
                }
            },
        )

        val solveDeferred = async { solver.solve(forumUrl) }
        runCurrent()
        handle.emit(ChallengeWebViewEvent.InteractiveBegin)
        runCurrent()
        presenterStarted.await()

        handle.emit(ChallengeWebViewEvent.RenderProcessGone(didCrash = false, rendererPriorityAtExit = 1))
        runCurrent()

        val result = solveDeferred.await()
        assertTrue(result is SolveResult.Failed)
        assertTrue((result as SolveResult.Failed).cause is ChallengeRenderProcessGoneException)
        assertTrue("Presenter coroutine must be cancelled on render process death", presenterCancelled.isCompleted)
        assertTrue(handle.closed)
        assertEquals(
            listOf("backup_interactive_crash"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
    }

    // --- 2f. Caller coroutine cancellation + session reset ---

    @Test
    fun callerCancellation_restoresBackupClosesHandleRethrowsCancellationAndAllowsSubsequentSolve() = runTest {
        seedOldClearanceAndLogin(oldValue = "backup_cancel_tok")
        val firstHandle = FakeChallengeWebViewHandle()
        val secondHandle = FakeChallengeWebViewHandle()
        val callCount = AtomicInteger(0)

        secondHandle.onLoadUrl = { url ->
            fakeCookieAccess.seedCookie(forumUrl, clearanceHeader("fresh_after_cancel"))
            secondHandle.currentMainFrameState = MainFrameState(url = url, pageFinished = true)
            secondHandle.domInChallengeState = false
            secondHandle.emit(ChallengeWebViewEvent.PageFinished(url))
        }

        val solver = createSolver(
            host = ChallengeWebViewHost { _, _ ->
                if (callCount.getAndIncrement() == 0) firstHandle else secondHandle
            },
        )

        var caughtCancellation = false
        val firstJob = async {
            try {
                solver.solve(forumUrl)
                fail("Expected CancellationException to propagate")
            } catch (e: CancellationException) {
                caughtCancellation = true
                throw e
            }
        }

        advanceTimeBy(1_000L)
        runCurrent()
        assertFalse(firstHandle.closed)

        firstJob.cancel()
        runCurrent()
        try {
            firstJob.await()
            fail("Cancelled job should throw CancellationException")
        } catch (_: CancellationException) {
            // expected
        }

        assertTrue("CancellationException must be rethrown", caughtCancellation)
        assertTrue("First handle must be closed on cancellation", firstHandle.closed)
        assertEquals(
            listOf("backup_cancel_tok"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
        assertTrue(
            fakeCookieAccess.storedRawHeaders(forumUrl).any {
                it.startsWith("$CF_CLEARANCE_COOKIE_NAME=backup_cancel_tok")
            },
        )

        // Next call must not deadlock and must succeed cleanly
        val secondResult = solver.solve(forumUrl)
        assertEquals(SolveResult.Cleared, secondResult)
        assertTrue(secondHandle.closed)
        assertEquals(
            listOf("fresh_after_cancel"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
    }

    @Test
    fun concurrentSolve_waitsForPreviousCancellationCleanupBeforeStartingNext() = runTest {
        seedOldClearanceAndLogin(oldValue = "old_serialized_tok")
        val firstHandle = FakeChallengeWebViewHandle()
        val secondHandle = FakeChallengeWebViewHandle()
        val acquireOrder = CopyOnWriteArrayList<String>()
        val callCount = AtomicInteger(0)

        secondHandle.onLoadUrl = { url ->
            assertTrue("First handle must already be closed before second solve loads", firstHandle.closed)
            fakeCookieAccess.seedCookie(forumUrl, clearanceHeader("second_fresh_tok"))
            secondHandle.currentMainFrameState = MainFrameState(url = url, pageFinished = true)
            secondHandle.domInChallengeState = false
            secondHandle.emit(ChallengeWebViewEvent.PageFinished(url))
        }

        val solver = createSolver(
            host = ChallengeWebViewHost { _, _ ->
                if (callCount.getAndIncrement() == 0) {
                    acquireOrder.add("first")
                    firstHandle
                } else {
                    acquireOrder.add("second")
                    secondHandle
                }
            },
        )

        val firstJob = async { solver.solve(forumUrl) }
        runCurrent()
        assertEquals(listOf("first"), acquireOrder)

        // Start second solve while first is still active, then cancel first
        val secondJob = async { solver.solve(forumUrl) }
        runCurrent()
        assertEquals("Second solve must wait on mutex while first is active", listOf("first"), acquireOrder)

        firstJob.cancel()
        runCurrent()

        assertEquals(SolveResult.Cleared, secondJob.await())
        assertEquals(listOf("first", "second"), acquireOrder)
        assertEquals(
            listOf("second_fresh_tok"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
    }

    @Test
    fun hostDestroyedExternally_wakesPendingSolveRestoresBackupAndReturnsCancelled() = runTest {
        seedOldClearanceAndLogin(oldValue = "backup_destroy_tok")
        val handle = FakeChallengeWebViewHandle()
        val solver = createSolver(host = ChallengeWebViewHost { _, _ -> handle })

        val solveDeferred = async { solver.solve(forumUrl) }
        advanceTimeBy(500L)
        runCurrent()

        handle.emit(ChallengeWebViewEvent.HostDestroyed)
        runCurrent()

        assertEquals(SolveResult.Cancelled, solveDeferred.await())
        assertTrue(handle.closed)
        assertEquals(
            listOf("backup_destroy_tok"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
    }

    @Test
    fun hostAcquireTimeout_restoresBackupAndReturnsFailedWithoutHanging() = runTest {
        seedOldClearanceAndLogin(oldValue = "backup_acquire_timeout")
        val neverStartingHost = ChallengeWebViewHost { _, _ ->
            CompletableDeferred<ChallengeWebViewHandle>().await()
        }
        val solver = createSolver(
            host = neverStartingHost,
            hostAcquireTimeout = 3.seconds,
        )

        val solveDeferred = async { solver.solve(forumUrl) }
        advanceTimeBy(2_999L)
        runCurrent()
        assertFalse(solveDeferred.isCompleted)

        advanceTimeBy(1L)
        runCurrent()

        val result = solveDeferred.await()
        assertTrue("Expected Failed on acquire timeout, got $result", result is SolveResult.Failed)
        assertTrue((result as SolveResult.Failed).cause is TimeoutException)
        assertEquals(
            listOf("backup_acquire_timeout"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
    }

    @Test
    fun mainFrameTrackerAndHelpers_handleCallbackOrderingsProbeHostsAndHeaders() {
        // Probe host filtering
        assertTrue(isIgnoredChallengeProbeHost("foo.challenges.cloudflare.com"))
        assertTrue(isIgnoredChallengeProbeHost("challenges.cloudflare.com"))
        assertTrue(isIgnoredChallengeProbeHost("CHALLENGES.CLOUDFLARE.COM."))
        assertTrue(isIgnoredChallengeProbeHost("probe.dnstest.dev"))
        assertTrue(isIgnoredChallengeProbeHost("dnstest.dev"))
        assertFalse(isIgnoredChallengeProbeHost("www.4d4y.com"))
        assertFalse(isIgnoredChallengeProbeHost("notdnstest.dev"))
        assertFalse(isIgnoredChallengeProbeHost("evilchallenges.cloudflare.com"))
        assertFalse(isIgnoredChallengeProbeHost(null))

        // Header matching
        assertTrue(hasCfMitigatedChallengeHeader(mapOf("CF-Mitigated" to "challenge")))
        assertTrue(hasCfMitigatedChallengeHeader(mapOf("cf-mitigated" to "managed-challenge")))
        assertFalse(hasCfMitigatedChallengeHeader(mapOf("server" to "cloudflare")))
        assertFalse(hasCfMitigatedChallengeHeader(null))

        // Turnstile event parsing
        assertEquals(ChallengeWebViewEvent.InteractiveBegin, parseTurnstileChallengeEvent("interactiveBegin"))
        assertEquals(ChallengeWebViewEvent.InteractiveTimeout, parseTurnstileChallengeEvent(" interactiveTimeout "))
        assertNull(parseTurnstileChallengeEvent("complete"))
        assertNull(parseTurnstileChallengeEvent(null))

        // Sequence 1: onReceivedHttpError BEFORE onPageStarted, then reload to 200
        val tracker = MainFrameStateTracker()
        val url = forumUrl.toString()
        tracker.onLoadRequested(url)
        tracker.onMainFrameHttpError(url, 403, mapOf("cf-mitigated" to "challenge"))
        tracker.onPageStarted(url)
        tracker.onPageFinished(url)
        val challengedState = tracker.snapshot()
        assertTrue(challengedState.pageFinished)
        assertTrue(challengedState.hasCfMitigatedChallengeHeader)
        assertEquals(403, challengedState.httpErrorStatusCode)
        assertTrue(challengedState.isChallengedResponse)
        assertFalse(challengedState.isReadyNonChallengePage)

        // Reload gets HTTP 200 (no onReceivedHttpError)
        tracker.onPageStarted(url)
        tracker.onPageFinished(url)
        val clearedState = tracker.snapshot()
        assertTrue(clearedState.isReadyNonChallengePage)
        assertFalse(clearedState.isChallengedResponse)

        // Sequence 2: second 403 fires BEFORE onPageStarted on a reload
        tracker.onMainFrameHttpError(url, 429, mapOf("CF-Mitigated" to "challenge"))
        tracker.onPageStarted(url)
        tracker.onPageFinished(url)
        assertTrue(tracker.snapshot().isChallengedResponse)
        assertFalse(tracker.snapshot().isReadyNonChallengePage)

        // Sequence 3: normal order (onPageStarted -> onReceivedHttpError -> onPageFinished)
        tracker.onPageStarted(url)
        tracker.onMainFrameHttpError(url, 503, emptyMap())
        tracker.onPageFinished(url)
        assertTrue(tracker.snapshot().isChallengedResponse)
        assertFalse(tracker.snapshot().isReadyNonChallengePage)

        // Sequence 4: main-frame load error prevents false clear
        tracker.onPageStarted(url)
        tracker.onMainFrameLoadError(url)
        tracker.onPageFinished(url)
        assertTrue(tracker.snapshot().hasMainFrameLoadError)
        assertFalse(tracker.snapshot().isReadyNonChallengePage)
    }

    @Test
    fun closeCompletesBeforeRestoreClearanceAndBeforeNextSolveAcquiresMutex() = runTest {
        seedOldClearanceAndLogin(oldValue = "backup_ordered_close")
        val closeGate = CompletableDeferred<Unit>()
        val firstHandle = FakeChallengeWebViewHandle(closeGate = closeGate)
        val secondHandle = FakeChallengeWebViewHandle()
        val callCount = AtomicInteger(0)
        val secondAcquired = CompletableDeferred<Unit>()

        secondHandle.onLoadUrl = { url ->
            fakeCookieAccess.seedCookie(forumUrl, clearanceHeader("second_ordered_tok"))
            secondHandle.currentMainFrameState = MainFrameState(url = url, pageFinished = true)
            secondHandle.domInChallengeState = false
            secondHandle.emit(ChallengeWebViewEvent.PageFinished(url))
        }

        val solver = createSolver(
            host = ChallengeWebViewHost { _, _ ->
                if (callCount.getAndIncrement() == 0) {
                    firstHandle
                } else {
                    secondAcquired.complete(Unit)
                    secondHandle
                }
            },
        )

        val firstJob = async { solver.solve(forumUrl) }
        runCurrent()

        val secondJob = async { solver.solve(forumUrl) }
        firstJob.cancel()
        runCurrent()

        // While firstHandle.close() is still awaiting main-thread teardown:
        // 1) restoreClearance must NOT have run yet;
        // 2) solveMutex must NOT have been released to the second solve yet.
        assertTrue("First handle close must have started", firstHandle.closeStarted)
        assertFalse("First handle must still be awaiting closeGate", firstHandle.closed)
        assertTrue(
            "restoreClearance must wait until handle.close() completes",
            store.loadForRequest(forumUrl).none { it.name == CF_CLEARANCE_COOKIE_NAME },
        )
        assertFalse("Second solve must wait for first handle close + restore", secondAcquired.isCompleted)

        closeGate.complete(Unit)
        runCurrent()

        assertTrue(firstHandle.closed)
        assertTrue(secondAcquired.isCompleted)
        assertEquals(SolveResult.Cleared, secondJob.await())
        assertEquals(
            listOf("second_ordered_tok"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
    }

    @Test
    fun unsuccessfulPollingWithoutFreshClearance_doesNotWriteUnrelatedCookiesToStore() = runTest {
        seedOldClearanceAndLogin(oldValue = "stale_only_tok")
        val handle = FakeChallengeWebViewHandle()

        handle.onLoadUrl = { url ->
            // WebView reached a 200 page with clean DOM, has an unrelated guest cookie and only the stale clearance.
            fakeCookieAccess.seedCookie(forumUrl, clearanceHeader("stale_only_tok"))
            fakeCookieAccess.seedCookie(forumUrl, "cdb_sid=guest_sid_must_not_sync; Path=/")
            handle.currentMainFrameState = MainFrameState(url = url, pageFinished = true)
            handle.domInChallengeState = false
            handle.emit(ChallengeWebViewEvent.PageFinished(url))
        }

        val solver = createSolver(host = ChallengeWebViewHost { _, _ -> handle })
        val solveDeferred = async { solver.solve(forumUrl) }

        advanceTimeBy(8_000L)
        runCurrent()

        assertEquals(SolveResult.Cancelled, solveDeferred.await())
        val finalCookies = store.loadForRequest(forumUrl)
        assertTrue(
            "Unsuccessful polling must never write unrelated WebView cookies into SiteCookieStore",
            finalCookies.none { it.name == "cdb_sid" && it.value == "guest_sid_must_not_sync" },
        )
        assertEquals(
            listOf("stale_only_tok"),
            finalCookies.filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
    }

    @Test
    fun allowedChallengeOriginRulesAndValidation_restrictToForumCloudflareAndTriggerOrigin() {
        val defaultRules = buildAllowedChallengeOriginRules(forumUrl)
        assertFalse("Wildcard '*' must never be used in WebMessage/Script origin rules", "*" in defaultRules)
        assertEquals(
            setOf(
                "https://4d4y.com",
                "https://*.4d4y.com",
                "https://challenges.cloudflare.com",
                "https://*.challenges.cloudflare.com",
                "https://www.4d4y.com",
            ),
            defaultRules,
        )

        val customPortUrl = "http://127.0.0.1:8443/forum/".toHttpUrl()
        val customRules = buildAllowedChallengeOriginRules(customPortUrl)
        assertTrue("http://127.0.0.1:8443" in customRules)
        assertFalse("*" in customRules)

        // Allowed origins
        assertTrue(isAllowedChallengeOrigin("https://4d4y.com", forumUrl))
        assertTrue(isAllowedChallengeOrigin("https://www.4d4y.com/forum/", forumUrl))
        assertTrue(isAllowedChallengeOrigin("https://challenges.cloudflare.com", forumUrl))
        assertTrue(isAllowedChallengeOrigin("https://widget.challenges.cloudflare.com/turnstile", forumUrl))
        assertTrue(isAllowedChallengeOrigin("http://127.0.0.1:8443/forum/", customPortUrl))

        // Rejected origins
        assertFalse(isAllowedChallengeOrigin("https://evil.example.com", forumUrl))
        assertFalse(isAllowedChallengeOrigin("https://evil4d4y.com", forumUrl))
        assertFalse(isAllowedChallengeOrigin("https://www.4d4y.com.evil.com", forumUrl))
        assertFalse(isAllowedChallengeOrigin("http://www.4d4y.com", forumUrl))
        assertFalse(isAllowedChallengeOrigin("https://www.4d4y.com:8443", forumUrl))
        assertFalse(isAllowedChallengeOrigin("http://127.0.0.1:9999", customPortUrl))
        assertFalse(isAllowedChallengeOrigin(null, forumUrl))
        assertFalse(isAllowedChallengeOrigin("", forumUrl))
    }

    @Test
    fun delayedClearanceAfterNormalPageFinished_invokesSyncFromWebViewExactlyOnce() = runTest {
        seedOldClearanceAndLogin(oldValue = "old_delayed_tok")
        val handle = FakeChallengeWebViewHandle()
        val saveCounts = AtomicInteger(0)
        val countingStore = object : net.jejer.hipda.data.network.cookie.SiteCookieStore by store {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                saveCounts.incrementAndGet()
                store.saveFromResponse(url, cookies)
            }
        }
        val bridge = WebViewCookieBridge(
            siteCookieStore = countingStore,
            webViewCookieAccess = fakeCookieAccess,
            cookieDispatcher = StandardTestDispatcher(testScheduler),
        )
        var savesBeforeLoop = 0
        val solver = WebViewChallengeSolver(
            cookieBridge = bridge,
            userAgentProvider = UserAgentProvider { unifiedUa },
            webViewHost = ChallengeWebViewHost { _, _ ->
                savesBeforeLoop = saveCounts.get()
                handle
            },
            interactionPresenter = DefaultChallengeInteractionPresenter(),
        )

        val solveDeferred = async { solver.solve(forumUrl) }
        runCurrent()

        // First page: 403 challenge page
        handle.currentMainFrameState = MainFrameState(
            url = forumUrl.toString(),
            pageFinished = true,
            hasCfMitigatedChallengeHeader = true,
            httpErrorStatusCode = 403,
        )
        handle.domInChallengeState = true
        handle.emit(ChallengeWebViewEvent.PageFinished(forumUrl.toString()))
        advanceTimeBy(500L)
        runCurrent()

        // Second page: normal 200 page, but fresh cf_clearance arrives 1.5s later
        handle.currentMainFrameState = MainFrameState(url = forumUrl.toString(), pageFinished = true)
        handle.domInChallengeState = false
        handle.emit(ChallengeWebViewEvent.PageFinished(forumUrl.toString()))
        advanceTimeBy(1_500L)
        runCurrent()
        assertFalse(solveDeferred.isCompleted)
        assertEquals("No store write should occur before fresh cf_clearance arrives", 0, saveCounts.get() - savesBeforeLoop)

        // Fresh clearance finally arrives
        fakeCookieAccess.seedCookie(forumUrl, clearanceHeader("delayed_fresh_tok"))
        advanceTimeBy(600L)
        runCurrent()

        assertEquals(SolveResult.Cleared, solveDeferred.await())
        assertEquals(
            "syncFromWebView must write into SiteCookieStore exactly once per solve",
            1,
            saveCounts.get() - savesBeforeLoop,
        )
        assertEquals(
            listOf("delayed_fresh_tok"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
    }

    @Test
    fun callerCancellationBeforeAcquire_neverLaunchesHostAndRestoresBackup() = runTest {
        seedOldClearanceAndLogin(oldValue = "backup_cancel_before_acquire")
        val acquireCalls = AtomicInteger(0)
        val jobRef = AtomicReference<kotlinx.coroutines.Job?>()

        val bridge = WebViewCookieBridge(
            siteCookieStore = store,
            webViewCookieAccess = fakeCookieAccess,
            cookieDispatcher = StandardTestDispatcher(testScheduler),
        )
        val solver = WebViewChallengeSolver(
            cookieBridge = bridge,
            userAgentProvider = UserAgentProvider {
                jobRef.get()?.cancel()
                unifiedUa
            },
            webViewHost = ChallengeWebViewHost { _, _ ->
                acquireCalls.incrementAndGet()
                FakeChallengeWebViewHandle()
            },
            interactionPresenter = DefaultChallengeInteractionPresenter(),
        )

        val job = async(start = kotlinx.coroutines.CoroutineStart.LAZY) { solver.solve(forumUrl) }
        jobRef.set(job)
        job.start()
        runCurrent()

        assertTrue("Solve job must be cancelled", job.isCancelled)
        assertEquals("Host acquire must never be invoked after cancellation", 0, acquireCalls.get())
        assertEquals(
            listOf("backup_cancel_before_acquire"),
            store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
        )
        assertTrue(
            fakeCookieAccess.storedRawHeaders(forumUrl).any {
                it.startsWith("$CF_CLEARANCE_COOKIE_NAME=backup_cancel_before_acquire")
            },
        )
    }

    private class FakeChallengeWebViewHandle(
        private val closeGate: CompletableDeferred<Unit>? = null,
    ) : ChallengeWebViewHandle {
        private val eventChannel = Channel<ChallengeWebViewEvent>(Channel.UNLIMITED)
        override val events: Flow<ChallengeWebViewEvent> = eventChannel.receiveAsFlow()

        val actions: MutableList<String> = CopyOnWriteArrayList()

        @Volatile
        var currentMainFrameState: MainFrameState = MainFrameState()

        @Volatile
        var domInChallengeState: Boolean = true

        @Volatile
        var closeStarted: Boolean = false
            private set

        @Volatile
        var closed: Boolean = false
            private set

        var onLoadUrl: ((String) -> Unit)? = null

        fun emit(event: ChallengeWebViewEvent) {
            eventChannel.trySend(event)
        }

        override fun installChallengeMessageListener() {
            actions.add("installListener")
        }

        override fun loadUrl(url: String) {
            actions.add("loadUrl:$url")
            onLoadUrl?.invoke(url)
        }

        override fun reload() {
            actions.add("reload")
        }

        override fun mainFrameState(): MainFrameState = currentMainFrameState

        override suspend fun isDomInChallengeState(): Boolean = domInChallengeState

        override fun close() {
            closeStarted = true
            if (closeGate == null) {
                closed = true
                actions.add("close")
                eventChannel.close()
            }
        }

        override suspend fun closeAndAwait() {
            closeStarted = true
            closeGate?.await()
            closed = true
            actions.add("close")
            eventChannel.close()
        }
    }
}
