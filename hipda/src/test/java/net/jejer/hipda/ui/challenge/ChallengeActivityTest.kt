package net.jejer.hipda.ui.challenge

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.net.Uri
import java.io.ByteArrayInputStream
import androidx.test.core.app.ApplicationProvider
import dagger.Binds
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
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

    private fun futureHttpDate(offsetSeconds: Long = 1800): String {
        val epochSeconds = (System.currentTimeMillis() / 1_000L) + offsetSeconds
        return httpDateFormatter.format(Instant.ofEpochSecond(epochSeconds))
    }

    private fun seedOldClearance(value: String = "old_activity_tok") {
        val header =
            "$CF_CLEARANCE_COOKIE_NAME=$value; Domain=.4d4y.com; Path=/; Expires=${futureHttpDate()}; Secure; HttpOnly; SameSite=None"
        store.saveFromResponse(forumUrl, listOf(requireNotNull(Cookie.parse(forumUrl, header))))
        fakeCookieAccess.seedCookie(forumUrl, header)
    }

    private suspend fun awaitStartedActivityIntent(): Intent {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            val next = shadowOf(application).nextStartedActivity
            if (next != null) return next
            delay(10)
        }
        error("ChallengeActivity launch intent was not dispatched")
    }

    @Test
    fun launchFromApplicationContext_configuresTransparentNonInteractiveSilentHostAndClosesCleanly() {
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

            val handle = acquireDeferred.await()
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

            // WebView settings and cookies must be configured with unified UA
            assertTrue(webView.settings.javaScriptEnabled)
            assertTrue(webView.settings.domStorageEnabled)
            assertEquals(unifiedUa, webView.settings.userAgentString)
            assertTrue(CookieManager.getInstance().acceptCookie())

            // Verify A6 interactive mode handoff reuses the same WebView and clears silent window flags
            binding.setInteractiveMode(true)
            shadowOf(Looper.getMainLooper()).idle()
            val interactiveFlags = activity.window.attributes.flags
            assertEquals(0, interactiveFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertEquals(0, interactiveFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            assertEquals(1f, webView.alpha, 0.0001f)
            assertTrue(webView.isFocusable)
            assertTrue(webView.isClickable)

            // Closing handle must finish the Activity and detach WebView
            handle.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Activity must be finishing after handle.close()", activity.isFinishing)
            assertEquals(0, binding.rootContainer.childCount)
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

            // Simulate external termination of ChallengeActivity
            activity.finish()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()

            val result = solveDeferred.await()
            assertEquals(SolveResult.Cancelled, result)

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
    fun renderProcessGoneOnRealWebViewClient_returnsTrueAndWakesSolverAsFailed() {
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
            shadowOf(Looper.getMainLooper()).idle()

            val result = solveDeferred.await()
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
    fun orphanLaunchWithoutActiveSession_finishesImmediately() {
        val intent = ChallengeActivity.createLaunchIntent(application, "non-existent-session")
        val controller = Robolectric.buildActivity(ChallengeActivity::class.java, intent).create()
        val activity = controller.get()

        assertTrue("Orphan ChallengeActivity without active session must finish immediately", activity.isFinishing)
        controller.destroy()
    }

    @Test
    fun realWebViewClient_ignoresProbeDomainErrorsAndTracksMainFrameChallengeHeader() {
        runBlocking {
            val host = AndroidChallengeWebViewHost(application)
            val acquireDeferred = async(Dispatchers.Default) {
                host.acquire(forumUrl, unifiedUa)
            }

            val intent = awaitStartedActivityIntent()
            val controller = Robolectric.buildActivity(ChallengeActivity::class.java, intent).setup()
            shadowOf(Looper.getMainLooper()).idle()

            val handle = acquireDeferred.await()
            val webView = requireNotNull(handle.activityBinding).webView
            val client = webView.webViewClient

            handle.loadUrl(forumUrl.toString())
            shadowOf(Looper.getMainLooper()).idle()
            client.onPageStarted(webView, forumUrl.toString(), null)

            // Non-fatal probe errors on *.challenges.cloudflare.com and *.dnstest.dev must be ignored
            client.onReceivedHttpError(
                webView,
                fakeRequest("https://probe.challenges.cloudflare.com/cdn-cgi/trace", isMainFrame = true),
                WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", mapOf("cf-mitigated" to "challenge"), ByteArrayInputStream(ByteArray(0))),
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
                WebResourceResponse("text/html", "utf-8", 403, "Forbidden", mapOf("cf-mitigated" to "challenge"), ByteArrayInputStream(ByteArray(0))),
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
            val firstHandle = firstAcquire.await()

            val secondAcquire = async(Dispatchers.Default) { host.acquire(forumUrl, unifiedUa) }
            val secondIntent = awaitStartedActivityIntent()
            controller.newIntent(secondIntent)
            shadowOf(Looper.getMainLooper()).idle()
            val secondHandle = secondAcquire.await()

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

    private fun fakeRequest(url: String, isMainFrame: Boolean): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = isMainFrame
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }

    @Test
    fun manifestRegistrationAndHiltBindings_areConfiguredCorrectly() {
        // Verify Manifest attributes
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

    private fun findWebView(view: View?): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
