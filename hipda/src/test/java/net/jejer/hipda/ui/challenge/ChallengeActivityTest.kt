package net.jejer.hipda.ui.challenge

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.util.AndroidRuntimeException
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import dagger.Binds
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.R
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.ChallengeSolverModule
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.network.webview.CF_CLEARANCE_COOKIE_NAME
import net.jejer.hipda.data.network.webview.WebViewCookieBridge
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [34],
    shadows = [ChallengeActivityTest.RecordingShadowCookieManager::class],
)
class ChallengeActivityTest {

    private val forumUrl: HttpUrl = "https://www.4d4y.com/forum/".toHttpUrl()
    private val unifiedUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
    private val httpDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneOffset.UTC)

    private lateinit var application: Application
    private lateinit var store: InMemorySiteCookieStore
    private lateinit var fakeCookieAccess: TestWebViewCookieAccess
    private lateinit var cookieBridge: WebViewCookieBridge

    @Before
    fun setUp() {
        RecordingShadowCookieManager.reset()
        application = ApplicationProvider.getApplicationContext()
        OkHttp.initialize(application)
        store = InMemorySiteCookieStore()
        fakeCookieAccess = TestWebViewCookieAccess()
        cookieBridge = WebViewCookieBridge(
            siteCookieStore = store,
            webViewCookieAccess = fakeCookieAccess,
            cookieDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        RecordingShadowCookieManager.reset()
    }

    private fun futureHttpDate(offsetSeconds: Long = 1800): String {
        val epochSeconds = (System.currentTimeMillis() / 1_000L) + offsetSeconds
        return httpDateFormatter.format(Instant.ofEpochSecond(epochSeconds))
    }

    private fun clearanceHeader(value: String): String =
        "$CF_CLEARANCE_COOKIE_NAME=$value; Domain=.4d4y.com; Path=/; Expires=${futureHttpDate()}; Secure; HttpOnly; SameSite=None"

    private fun seedOldClearance(value: String = "old_activity_tok") {
        val header = clearanceHeader(value)
        store.saveFromResponse(forumUrl, listOf(requireNotNull(Cookie.parse(forumUrl, header))))
        fakeCookieAccess.seedCookie(forumUrl, header)
    }

    private suspend fun awaitStartedActivityIntent(): Intent {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            val next = shadowOf(application).nextStartedActivity
            if (next != null) return next
            delay(10)
        }
        error("ChallengeActivity launch intent was not dispatched")
    }

    private suspend fun <T> awaitWithMainLooper(deferred: Deferred<T>): T {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (deferred.isCompleted) return deferred.await()
            delay(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return deferred.await()
    }

    @Test
    fun launchFromApplicationContext_configuresTransparentNonInteractiveSilentHostAndDestroysWebViewOnClose() {
        runBlocking {
            val host = AndroidChallengeWebViewHost(application)
            val acquireDeferred = async(Dispatchers.Default) {
                host.acquire(forumUrl, unifiedUa)
            }

            val intent = awaitStartedActivityIntent()
            assertEquals(ChallengeActivity::class.java.name, intent.component?.className)
            assertTrue(
                "Launch intent from Application Context must include FLAG_ACTIVITY_NEW_TASK",
                (intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0,
            )
            assertFalse(intent.getStringExtra(ChallengeActivity.EXTRA_SESSION_ID).isNullOrBlank())

            val controller = Robolectric.buildActivity(ChallengeActivity::class.java, intent).setup()
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()

            val handle = awaitWithMainLooper(acquireDeferred)
            val binding = requireNotNull(handle.activityBinding)
            assertSame(activity, binding.activity)

            // Window must be transparent, non-touchable, and non-focusable in silent mode
            val flags = activity.window.attributes.flags
            assertTrue(
                "Silent mode must set FLAG_NOT_TOUCHABLE",
                (flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0,
            )
            assertTrue(
                "Silent mode must set FLAG_NOT_FOCUSABLE",
                (flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0,
            )

            // WebView must have real viewport dimensions (>= 360dp x 480dp), alpha = 0f, VISIBLE, non-focusable, non-clickable
            val webView = binding.webView
            val density = activity.resources.displayMetrics.density
            assertEquals(View.VISIBLE, webView.visibility)
            assertEquals(0f, webView.alpha, 0.0001f)
            assertFalse(webView.isFocusable)
            assertFalse(webView.isClickable)
            assertTrue(webView.layoutParams.width >= (ChallengeActivity.SILENT_WEBVIEW_WIDTH_DP * density).toInt())
            assertTrue(webView.layoutParams.height >= (ChallengeActivity.SILENT_WEBVIEW_HEIGHT_DP * density).toInt())

            // WebView settings and both first-party & third-party cookies must be enabled with unified UA
            assertTrue(webView.settings.javaScriptEnabled)
            assertTrue(webView.settings.domStorageEnabled)
            assertEquals(unifiedUa, webView.settings.userAgentString)
            assertTrue(CookieManager.getInstance().acceptCookie())
            assertTrue(
                "Third-party cookies must be enabled for Turnstile iframe challenges.cloudflare.com",
                CookieManager.getInstance().acceptThirdPartyCookies(webView),
            )

            // Verify A6 interactive mode handoff reuses the same WebView and clears silent window flags
            binding.setInteractiveMode(true)
            shadowOf(Looper.getMainLooper()).idle()
            val interactiveFlags = activity.window.attributes.flags
            assertEquals(0, interactiveFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertEquals(0, interactiveFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            assertEquals(1f, webView.alpha, 0.0001f)
            assertTrue(webView.isFocusable)
            assertTrue(webView.isClickable)

            // Closing handle must finish the Activity, detach WebView, and call WebView.destroy()
            handle.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Activity must be finishing after handle.close()", activity.isFinishing)
            assertEquals(0, binding.rootContainer.childCount)
            assertTrue("WebView.destroy() must be called on close", shadowOf(webView).wasDestroyCalled())
            controller.destroy()
        }
    }

    @Test
    fun installChallengeMessageListener_registersFallbackBridgeInjectsRestrictedScriptAndDispatchesEvents() {
        runBlocking {
            val host = AndroidChallengeWebViewHost(application)
            val acquireDeferred = async(Dispatchers.Default) {
                host.acquire(forumUrl, unifiedUa)
            }

            val intent = awaitStartedActivityIntent()
            val controller = Robolectric.buildActivity(ChallengeActivity::class.java, intent).setup()
            shadowOf(Looper.getMainLooper()).idle()

            val handle = awaitWithMainLooper(acquireDeferred)
            val webView = requireNotNull(handle.activityBinding).webView
            val shadowWebView = shadowOf(webView)
            val client = webView.webViewClient

            handle.installChallengeMessageListener()
            shadowOf(Looper.getMainLooper()).idle()

            val jsBridge = shadowWebView.getJavascriptInterface(ChallengeActivity.FALLBACK_JS_BRIDGE_NAME)
            assertNotNull("Fallback @JavascriptInterface must be registered", jsBridge)

            // Untrusted origin must NOT trigger fallback listener injection AND direct JS bridge call must be ignored
            client.onPageStarted(webView, "https://evil.example.com/ad", null)
            assertNull("Listener script must not be injected on untrusted origins", shadowWebView.lastEvaluatedJavascript)
            val onEventMethod = requireNotNull(jsBridge)::class.java.getMethod("onChallengeEvent", String::class.java)
            onEventMethod.invoke(jsBridge, "interactiveBegin")

            // Trusted forum origin MUST inject listener script with cloudflare-challenge & origin checks
            client.onPageStarted(webView, forumUrl.toString(), null)
            val injectedScript = shadowWebView.lastEvaluatedJavascript
            assertNotNull("Listener script must be injected on allowed origin", injectedScript)
            assertTrue(injectedScript.contains("cloudflare-challenge"))
            assertTrue(injectedScript.contains("interactiveBegin"))
            assertTrue(injectedScript.contains("interactiveTimeout"))
            assertTrue(injectedScript.contains("isAllowedOrigin"))
            assertTrue(injectedScript.contains(ChallengeActivity.FALLBACK_JS_BRIDGE_NAME))

            // Invoking the registered JS bridge on trusted origin dispatches InteractiveTimeout (proving forged InteractiveBegin above was dropped!)
            onEventMethod.invoke(jsBridge, "interactiveTimeout")
            assertEquals(ChallengeWebViewEvent.InteractiveTimeout, handle.events.first())

            onEventMethod.invoke(jsBridge, "interactiveBegin")
            assertEquals(ChallengeWebViewEvent.InteractiveBegin, handle.events.first())

            handle.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowWebView.wasDestroyCalled())
            controller.destroy()
        }
    }

    @Test
    fun externalFinishAndDestroy_wakesSuspendedSolverReturningCancelledAndRestoresBackup() {
        runBlocking {
            seedOldClearance("backup_external_destroy")
            val solver = WebViewChallengeSolver(
                cookieBridge = cookieBridge,
                userAgentProvider = UserAgentProvider { unifiedUa },
                webViewHost = AndroidChallengeWebViewHost(application),
                interactionPresenter = DefaultChallengeInteractionPresenter(),
            )

            val solveDeferred = async(Dispatchers.Default) {
                solver.solve(forumUrl)
            }

            val intent = awaitStartedActivityIntent()
            val controller = Robolectric.buildActivity(ChallengeActivity::class.java, intent).setup()
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()
            val webView = requireNotNull(findWebView(activity.findViewById(android.R.id.content)))

            // Simulate external termination of ChallengeActivity
            activity.finish()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()

            val result = awaitWithMainLooper(solveDeferred)
            assertEquals(SolveResult.Cancelled, result)
            assertTrue("WebView.destroy() must be called on Activity destroy", shadowOf(webView).wasDestroyCalled())

            // Backup must be restored on both sides
            assertEquals(
                listOf("backup_external_destroy"),
                store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
            )
            assertTrue(
                fakeCookieAccess.storedRawHeaders(forumUrl).any {
                    it.startsWith("$CF_CLEARANCE_COOKIE_NAME=backup_external_destroy")
                },
            )
        }
    }

    @Test
    fun renderProcessGoneOnRealWebViewClient_returnsTrueDestroysWebViewAndWakesSolverAsFailed() {
        runBlocking {
            seedOldClearance("backup_real_render_crash")
            val solver = WebViewChallengeSolver(
                cookieBridge = cookieBridge,
                userAgentProvider = UserAgentProvider { unifiedUa },
                webViewHost = AndroidChallengeWebViewHost(application),
                interactionPresenter = DefaultChallengeInteractionPresenter(),
            )

            val solveDeferred = async(Dispatchers.Default) {
                solver.solve(forumUrl)
            }

            val intent = awaitStartedActivityIntent()
            val controller = Robolectric.buildActivity(ChallengeActivity::class.java, intent).setup()
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()

            val webView = requireNotNull(findWebView(activity.findViewById(android.R.id.content)))
            val handled = webView.webViewClient.onRenderProcessGone(
                webView,
                @Suppress("DEPRECATION")
                object : RenderProcessGoneDetail() {
                    override fun didCrash(): Boolean = true
                    override fun rendererPriorityAtExit(): Int = 1
                },
            )
            assertTrue("onRenderProcessGone must return true to protect app process", handled)
            assertTrue("WebView.destroy() must be called on render process gone", shadowOf(webView).wasDestroyCalled())

            val result = awaitWithMainLooper(solveDeferred)
            assertTrue("Expected Failed on render process gone, got $result", result is SolveResult.Failed)
            assertTrue((result as SolveResult.Failed).cause is ChallengeRenderProcessGoneException)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Activity must finish after render process crash", activity.isFinishing)
            assertEquals(
                listOf("backup_real_render_crash"),
                store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
            )
            controller.destroy()
        }
    }

    @Test
    fun userExplicitReentry_destroysOldWebViewAndRelaunchesOnDyingOnNewIntentWithoutCancellingSecondSolve() {
        runBlocking {
            seedOldClearance("old_reentry_tok")
            val secondSessionReady = AtomicReference<ChallengeInteractionSession>()
            val solver = WebViewChallengeSolver(
                cookieBridge = cookieBridge,
                userAgentProvider = UserAgentProvider { unifiedUa },
                webViewHost = AndroidChallengeWebViewHost(application),
                interactionPresenter = ChallengeInteractionPresenter { session ->
                    secondSessionReady.set(session)
                    fakeCookieAccess.seedCookie(forumUrl, clearanceHeader("fresh_second_round_tok"))
                    SolveResult.Cleared
                },
            )

            // Round 1 starts and attaches ChallengeActivity #1
            val firstSolve = async(Dispatchers.Default) { solver.solve(forumUrl) }
            val firstIntent = awaitStartedActivityIntent()
            val firstController = Robolectric.buildActivity(ChallengeActivity::class.java, firstIntent).setup()
            val firstActivity = firstController.get()
            shadowOf(Looper.getMainLooper()).idle()
            val firstWebView = requireNotNull(findWebView(firstActivity.findViewById(android.R.id.content)))

            // Simulate UserExplicit superseding round 1: cancel round 1 and immediately launch round 2
            firstSolve.cancel(CancellationException("UserExplicit retry"))
            val secondSolve = async(Dispatchers.Default) { solver.solve(forumUrl) }

            // Round 2's intent must arrive only after round 1 destroyed old WebView and marked Activity #1 finishing
            val secondIntent = awaitStartedActivityIntent()
            assertTrue("Old WebView must be destroyed before round 2 starts", shadowOf(firstWebView).wasDestroyCalled())
            assertTrue("Old ChallengeActivity must already be finishing", firstActivity.isFinishing)

            // Simulate AMS delivering round 2's intent to dying Activity #1 via singleTop onNewIntent,
            // followed immediately by Activity #1's onDestroy().
            firstController.newIntent(secondIntent)
            shadowOf(Looper.getMainLooper()).idle()
            firstController.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()

            // Dying Activity #1 must NOT swallow round 2; it must relaunch a fresh Activity #2 instance
            val relaunchedIntent = awaitStartedActivityIntent()
            assertEquals(
                secondIntent.getStringExtra(ChallengeActivity.EXTRA_SESSION_ID),
                relaunchedIntent.getStringExtra(ChallengeActivity.EXTRA_SESSION_ID),
            )
            assertFalse("Round 2 must NOT be cancelled by dying Activity #1 HostDestroyed", secondSolve.isCompleted)

            // Start fresh Activity #2 for round 2 and trigger escalation -> Cleared
            val secondController = Robolectric.buildActivity(ChallengeActivity::class.java, relaunchedIntent).setup()
            val secondActivity = secondController.get()
            shadowOf(Looper.getMainLooper()).idle()
            val secondWebView = requireNotNull(findWebView(secondActivity.findViewById(android.R.id.content)))
            val jsBridge = requireNotNull(
                shadowOf(secondWebView).getJavascriptInterface(ChallengeActivity.FALLBACK_JS_BRIDGE_NAME),
            )
            jsBridge::class.java.getMethod("onChallengeEvent", String::class.java)
                .invoke(jsBridge, "interactiveBegin")

            val secondResult = awaitWithMainLooper(secondSolve)
            assertEquals(SolveResult.Cleared, secondResult)
            assertNotNull(secondSessionReady.get())
            assertTrue(shadowOf(secondWebView).wasDestroyCalled())
            assertTrue(secondActivity.isFinishing)
            assertEquals(
                listOf("fresh_second_round_tok"),
                store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
            )
            secondController.destroy()
        }
    }

    @Test
    fun unavailableWebViewPackage_finishesWithoutCrashingAndReturnsFailedImmediately() {
        runBlocking {
            seedOldClearance("backup_missing_wv")
            val missingWebViewError = AndroidRuntimeException("WebViewFactory\$MissingWebViewPackageException")
            FailingWebViewChallengeActivity.failureToThrow = missingWebViewError
            try {
                val solver = WebViewChallengeSolver(
                    cookieBridge = cookieBridge,
                    userAgentProvider = UserAgentProvider { unifiedUa },
                    webViewHost = AndroidChallengeWebViewHost(application),
                    interactionPresenter = DefaultChallengeInteractionPresenter(),
                    hostAcquireTimeout = 30.seconds,
                )

                val solveDeferred = async(Dispatchers.Default) { solver.solve(forumUrl) }
                val intent = awaitStartedActivityIntent()

                // Launch failing subclass with the exact session intent; onCreate must NOT crash
                val controller = Robolectric.buildActivity(FailingWebViewChallengeActivity::class.java, intent).setup()
                val activity = controller.get()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue("Activity must finish when WebView creation fails", activity.isFinishing)

                val result = awaitWithMainLooper(solveDeferred)
                assertTrue("Unavailable WebView must return Failed (not Cancelled), got: $result", result is SolveResult.Failed)
                val cause = (result as SolveResult.Failed).cause
                assertTrue("Expected AndroidRuntimeException cause, got: $cause", cause is AndroidRuntimeException)
                assertEquals(missingWebViewError.message, cause?.message)
                assertEquals(
                    listOf("backup_missing_wv"),
                    store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
                )
                assertTrue(
                    fakeCookieAccess.storedRawHeaders(forumUrl).any {
                        it.startsWith("$CF_CLEARANCE_COOKIE_NAME=backup_missing_wv")
                    },
                )
                controller.destroy()
            } finally {
                FailingWebViewChallengeActivity.failureToThrow = null
            }
        }
    }

    @Test
    fun webViewNativeLinkError_finishesWithoutCrashingAndReturnsFailedImmediately() {
        runBlocking {
            seedOldClearance("backup_unsatisfied_link")
            val linkError = UnsatisfiedLinkError("libwebviewchromium.so not found")
            FailingWebViewChallengeActivity.failureToThrow = linkError
            try {
                val solver = WebViewChallengeSolver(
                    cookieBridge = cookieBridge,
                    userAgentProvider = UserAgentProvider { unifiedUa },
                    webViewHost = AndroidChallengeWebViewHost(application),
                    interactionPresenter = DefaultChallengeInteractionPresenter(),
                    hostAcquireTimeout = 30.seconds,
                )

                val solveDeferred = async(Dispatchers.Default) { solver.solve(forumUrl) }
                val intent = awaitStartedActivityIntent()

                val controller = Robolectric.buildActivity(FailingWebViewChallengeActivity::class.java, intent).setup()
                assertTrue(controller.get().isFinishing)

                val result = awaitWithMainLooper(solveDeferred)
                assertTrue("UnsatisfiedLinkError must return Failed, got: $result", result is SolveResult.Failed)
                val cause = (result as SolveResult.Failed).cause
                assertTrue("Expected UnsatisfiedLinkError cause, got: $cause", cause is UnsatisfiedLinkError)
                assertEquals(linkError.message, cause?.message)
                assertEquals(
                    listOf("backup_unsatisfied_link"),
                    store.loadForRequest(forumUrl).filter { it.name == CF_CLEARANCE_COOKIE_NAME }.map { it.value },
                )
                controller.destroy()
            } finally {
                FailingWebViewChallengeActivity.failureToThrow = null
            }
        }
    }

    @Test
    fun orphanLaunchWithoutActiveSession_finishesImmediately() {
        val intent = ChallengeActivity.createLaunchIntent(application, "non-existent-session")
        val controller = Robolectric.buildActivity(ChallengeActivity::class.java, intent).create()
        val activity = controller.get()

        assertTrue("Orphan ChallengeActivity without active session must finish immediately", activity.isFinishing)
        controller.destroy()
    }

    @Test
    fun realWebViewClient_filtersNavigationSchemesIgnoresProbeErrorsAndTracksMainFrameChallengeHeader() {
        runBlocking {
            val host = AndroidChallengeWebViewHost(application)
            val acquireDeferred = async(Dispatchers.Default) {
                host.acquire(forumUrl, unifiedUa)
            }

            val intent = awaitStartedActivityIntent()
            val controller = Robolectric.buildActivity(ChallengeActivity::class.java, intent).setup()
            shadowOf(Looper.getMainLooper()).idle()

            val handle = awaitWithMainLooper(acquireDeferred)
            val webView = requireNotNull(handle.activityBinding).webView
            val client = webView.webViewClient

            // Navigation scheme policy: allow http/https/about inside WebView, block external/file/js schemes
            assertFalse(client.shouldOverrideUrlLoading(webView, fakeRequest("https://www.4d4y.com/forum/", isMainFrame = true)))
            assertFalse(client.shouldOverrideUrlLoading(webView, fakeRequest("http://www.4d4y.com/forum/", isMainFrame = true)))
            assertFalse(client.shouldOverrideUrlLoading(webView, fakeRequest("about:blank", isMainFrame = true)))
            assertTrue(client.shouldOverrideUrlLoading(webView, fakeRequest("intent://evil/#Intent;end", isMainFrame = true)))
            assertTrue(client.shouldOverrideUrlLoading(webView, fakeRequest("file:///etc/passwd", isMainFrame = true)))
            assertTrue(client.shouldOverrideUrlLoading(webView, fakeRequest("javascript:alert(1)", isMainFrame = true)))

            handle.loadUrl(forumUrl.toString())
            shadowOf(Looper.getMainLooper()).idle()
            client.onPageStarted(webView, forumUrl.toString(), null)

            // Non-fatal probe errors on *.challenges.cloudflare.com and *.dnstest.dev must be ignored
            client.onReceivedHttpError(
                webView,
                fakeRequest("https://probe.challenges.cloudflare.com/cdn-cgi/trace", isMainFrame = true),
                WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    403,
                    "Forbidden",
                    mapOf("cf-mitigated" to "challenge"),
                    ByteArrayInputStream(ByteArray(0)),
                ),
            )
            client.onReceivedError(
                webView,
                fakeRequest("https://check.dnstest.dev/ping", isMainFrame = true),
                null,
            )
            client.onPageFinished(webView, forumUrl.toString())
            assertTrue("Probe errors must not mark main frame challenged or failed", handle.mainFrameState().isReadyNonChallengePage)

            // Main-frame 403 + cf-mitigated: challenge must mark mainFrameState as challenged
            client.onReceivedHttpError(
                webView,
                fakeRequest(forumUrl.toString(), isMainFrame = true),
                WebResourceResponse(
                    "text/html",
                    "utf-8",
                    403,
                    "Forbidden",
                    mapOf("cf-mitigated" to "challenge"),
                    ByteArrayInputStream(ByteArray(0)),
                ),
            )
            client.onPageStarted(webView, forumUrl.toString(), null)
            client.onPageFinished(webView, forumUrl.toString())
            assertTrue(handle.mainFrameState().hasCfMitigatedChallengeHeader)
            assertEquals(403, handle.mainFrameState().httpErrorStatusCode)
            assertTrue(handle.mainFrameState().isChallengedResponse)
            assertFalse(handle.mainFrameState().isReadyNonChallengePage)

            handle.close()
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
    }

    @Test
    fun singleTopOnNewIntent_detachesStaleSessionAndAttachesReplacementCleanly() {
        runBlocking {
            val host = AndroidChallengeWebViewHost(application)
            val firstAcquire = async(Dispatchers.Default) { host.acquire(forumUrl, unifiedUa) }
            val firstIntent = awaitStartedActivityIntent()
            val controller = Robolectric.buildActivity(ChallengeActivity::class.java, firstIntent).setup()
            shadowOf(Looper.getMainLooper()).idle()
            val firstHandle = awaitWithMainLooper(firstAcquire)

            val secondAcquire = async(Dispatchers.Default) { host.acquire(forumUrl, unifiedUa) }
            val secondIntent = awaitStartedActivityIntent()
            controller.newIntent(secondIntent)
            shadowOf(Looper.getMainLooper()).idle()
            val secondHandle = awaitWithMainLooper(secondAcquire)

            // Calling close() on stale first handle must NOT finish the Activity hosting the second session
            firstHandle.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("Stale handle close must not finish replacement session", controller.get().isFinishing)

            // Closing the replacement handle finishes the Activity cleanly
            secondHandle.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(controller.get().isFinishing)
            controller.destroy()
        }
    }

    @Test
    fun manifestRegistrationAndHiltBindings_areConfiguredCorrectly() {
        // Verify Manifest attributes including foldable/theme configChanges
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.packageManager.getPackageInfo(
                application.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            application.packageManager.getPackageInfo(application.packageName, PackageManager.GET_ACTIVITIES)
        }
        val activityInfo = packageInfo.activities
            ?.firstOrNull { it.name == ChallengeActivity::class.java.name }
        assertNotNull("ChallengeActivity must be registered in AndroidManifest", activityInfo)
        val info = requireNotNull(activityInfo)
        assertFalse("ChallengeActivity must not be exported", info.exported)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
        assertEquals(R.style.Theme_HiPda_ChallengeHost, info.theme)
        assertTrue(
            "ChallengeActivity must be excluded from recents",
            (info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0,
        )
        val requiredConfigChanges = ActivityInfo.CONFIG_ORIENTATION or
            ActivityInfo.CONFIG_SCREEN_SIZE or
            ActivityInfo.CONFIG_SCREEN_LAYOUT or
            ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or
            ActivityInfo.CONFIG_DENSITY or
            ActivityInfo.CONFIG_UI_MODE or
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN
        assertEquals(
            "ChallengeActivity configChanges must cover foldable density/uiMode/screen changes",
            requiredConfigChanges,
            info.configChanges and requiredConfigChanges,
        )

        // Verify Hilt bindings for ChallengeSolverModule, ChallengeInteractionModule, and ChallengeHostModule
        assertTrue(WebViewChallengeSolver::class.java.isAnnotationPresent(Singleton::class.java))
        val solverCtor = WebViewChallengeSolver::class.java.getDeclaredConstructor(
            WebViewCookieBridge::class.java,
            UserAgentProvider::class.java,
            ChallengeWebViewHost::class.java,
            ChallengeInteractionPresenter::class.java,
        )
        assertTrue(solverCtor.isAnnotationPresent(Inject::class.java))

        val bindSolver = ChallengeSolverModule::class.java.getDeclaredMethod(
            "bindChallengeSolver",
            WebViewChallengeSolver::class.java,
        )
        assertTrue(bindSolver.isAnnotationPresent(Binds::class.java))
        assertTrue(bindSolver.isAnnotationPresent(Singleton::class.java))

        val bindPresenter = ChallengeInteractionModule::class.java.getDeclaredMethod(
            "bindChallengeInteractionPresenter",
            ComposeChallengeInteractionPresenter::class.java,
        )
        assertTrue(bindPresenter.isAnnotationPresent(Binds::class.java))
        assertTrue(bindPresenter.isAnnotationPresent(Singleton::class.java))

        val bindHost = ChallengeHostModule::class.java.getDeclaredMethod(
            "bindChallengeWebViewHost",
            AndroidChallengeWebViewHost::class.java,
        )
        assertTrue(bindHost.isAnnotationPresent(Binds::class.java))
        assertTrue(bindHost.isAnnotationPresent(Singleton::class.java))
    }

    private fun fakeRequest(url: String, isMainFrame: Boolean): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = isMainFrame
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }

    private fun findWebView(view: View?): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private class FailingWebViewChallengeActivity : ChallengeActivity() {
        override fun createWebView(): WebView {
            throw requireNotNull(failureToThrow) { "failureToThrow not configured" }
        }

        companion object {
            @Volatile
            var failureToThrow: Throwable? = null
        }
    }

    /**
     * Robolectric's default `RoboCookieManager` no-ops `setAcceptThirdPartyCookies` and always
     * returns `false` from `acceptThirdPartyCookies`. This shadow records per-WebView third-party
     * cookie policy so `ChallengeActivityTest` catches regressions if third-party cookies break.
     */
    @Implements(CookieManager::class)
    class RecordingShadowCookieManager {
        companion object {
            @Volatile
            private var instance: TrackingCookieManager? = null

            fun reset() {
                instance = null
            }

            @Implementation
            @JvmStatic
            fun getInstance(): CookieManager =
                instance ?: TrackingCookieManager().also { instance = it }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        private class TrackingCookieManager : CookieManager() {
            private var acceptCookies = false
            private val thirdPartyByWebView = IdentityHashMap<WebView, Boolean>()

            override fun setAcceptCookie(accept: Boolean) {
                acceptCookies = accept
            }

            override fun acceptCookie(): Boolean = acceptCookies

            override fun setAcceptThirdPartyCookies(webview: WebView, accept: Boolean) {
                thirdPartyByWebView[webview] = accept
            }

            override fun acceptThirdPartyCookies(webview: WebView): Boolean =
                thirdPartyByWebView[webview] == true

            override fun setCookie(url: String?, value: String?) {}
            override fun setCookie(url: String?, value: String?, callback: ValueCallback<Boolean>?) {
                callback?.onReceiveValue(true)
            }

            override fun getCookie(url: String?): String? = null
            override fun removeSessionCookie() {}
            override fun removeSessionCookies(callback: ValueCallback<Boolean>?) {
                callback?.onReceiveValue(true)
            }

            override fun removeAllCookie() {}
            override fun removeAllCookies(callback: ValueCallback<Boolean>?) {
                callback?.onReceiveValue(true)
            }

            override fun hasCookies(): Boolean = false
            override fun removeExpiredCookie() {}
            override fun flush() {}
        }
    }
}
