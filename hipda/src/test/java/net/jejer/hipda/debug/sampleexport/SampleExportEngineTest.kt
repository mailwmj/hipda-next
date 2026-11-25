package net.jejer.hipda.debug.sampleexport

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.network.webview.WebViewCookieBridge
import net.jejer.hipda.parser.sanitize.HtmlSanitizer
import net.jejer.hipda.parser.sanitize.LeakType
import net.jejer.hipda.parser.sanitize.PageType
import net.jejer.hipda.parser.sanitize.SampleLeakScanner
import net.jejer.hipda.ui.challenge.TestWebViewCookieAccess
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [34],
    shadows = [SampleExportEngineTest.RecordingShadowCookieManager::class],
)
class SampleExportEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val gbk: Charset = Charset.forName("GB18030")
    private val unifiedUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

    private lateinit var application: Application
    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var server: MockWebServer
    private lateinit var gatewayScope: CoroutineScope
    private lateinit var client: OkHttpClient
    private lateinit var gateway: SiteAccessGateway
    private lateinit var exportDir: File

    @Before
    fun setUp() {
        RecordingShadowCookieManager.reset()
        application = ApplicationProvider.getApplicationContext()
        OkHttp.initialize(application)
        cookieStore = InMemorySiteCookieStore()
        server = MockWebServer()
        server.start()
        gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { unifiedUa },
            transportInterceptor = TransportInterceptor(
                delegateFactory = { null },
                isMainThread = { false },
            ),
        )
        gateway = SiteAccessGateway(
            client = client,
            detector = ChallengeDetector(),
            solver = ChallengeSolver { SolveResult.Failed(AssertionError("Unexpected challenge solve in unit test")) },
            clock = System::currentTimeMillis,
            gatewayScope = gatewayScope,
        )
        exportDir = tempFolder.newFolder("sample_exports")
    }

    @After
    fun tearDown() {
        gatewayScope.cancel()
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.close()
        RecordingShadowCookieManager.reset()
    }

    // (a) Complete 16-entry read-only GET flow: GBK decoding, dynamic extraction, sanitization, 0 leaks, valid zip
    @Test
    fun fullManifest_executesReadOnlyGetsDecodesGbkExtractsIdsSanitizesAndZipsCleanly() = runBlocking {
        val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = createForumDispatcher(recorded = recordedRequests)

        val engine = SampleExportEngine(
            siteAccessGateway = gateway,
            exportDir = exportDir,
            baseUrl = server.url("/forum/"),
            clock = { 1_764_000_000_000L },
        )

        val result = engine.runAll(includePersonalHistory = true)

        assertEquals(16, recordedRequests.size)
        assertTrue(
            "Every single request must be read-only GET: ${recordedRequests.map { "${it.method} ${it.target}" }}",
            recordedRequests.all { it.method == "GET" },
        )

        val requestedTargets = recordedRequests.map { it.target }
        assertTrue(requestedTargets.contains("/forum/forumdisplay.php?fid=2&page=1"))
        assertTrue(requestedTargets.contains("/forum/forumdisplay.php?fid=2&page=2"))
        assertTrue(requestedTargets.contains("/forum/forumdisplay.php?fid=2&page=1&orderby=dateline"))
        // Must pick multi-page normal thread 88421 (skipping sticky 99001 and single-page 88420)
        assertEquals("88421", result.resolvedTid)
        assertTrue(requestedTargets.contains("/forum/viewthread.php?tid=88421&page=1"))
        assertTrue(requestedTargets.contains("/forum/viewthread.php?tid=88421&page=2"))
        assertTrue(requestedTargets.contains("/forum/viewthread.php?tid=88421&page=1&authorid=44556"))
        assertTrue(requestedTargets.contains("/forum/pm.php?filter=privatepm"))
        assertTrue(requestedTargets.contains("/forum/pm.php?daterange=5&uid=77889"))
        assertTrue(requestedTargets.contains("/forum/notice.php"))
        assertTrue(requestedTargets.contains("/forum/search.php?srchfrom=86400&searchsubmit=yes"))
        assertTrue(requestedTargets.contains("/forum/space.php?uid=54321"))
        assertTrue(requestedTargets.contains("/forum/my.php?item=threads&page=1"))
        assertTrue(requestedTargets.contains("/forum/my.php?item=posts&page=1"))
        assertTrue(requestedTargets.contains("/forum/my.php?item=favorites&type=thread&page=1"))
        assertTrue(requestedTargets.contains("/forum/post.php?action=newthread&fid=2"))
        assertTrue(requestedTargets.contains("/forum/logging.php?action=login"))

        assertEquals(16, result.items.size)
        assertTrue(
            "All 16 entries must succeed: ${result.items}",
            result.items.all { it.status is SampleExportStatus.Success },
        )

        val zipFile = requireNotNull(result.zipFile)
        assertTrue("Zip file must exist in exportDir", zipFile.exists())
        assertEquals(exportDir, zipFile.parentFile)
        assertTrue(zipFile.name.startsWith(SampleExportConstants.ZIP_FILE_PREFIX))
        assertTrue(zipFile.name.endsWith(SampleExportConstants.ZIP_FILE_SUFFIX))

        val unzippedDir = tempFolder.newFolder("unzipped_full")
        val extractedPaths = unzipTo(zipFile, unzippedDir)

        val expectedSamplePaths = SampleExportManifest.entries.map { it.relativePath }.toSet()
        assertEquals(expectedSamplePaths + SampleExportConstants.MANIFEST_FILE_NAME, extractedPaths)

        // Verify GBK Chinese content was decoded cleanly to UTF-8 and self identity/tokens were sanitized
        val threadListHtml = File(unzippedDir, "thread_list/forum_2_p1_lastpost.html").readText(Charsets.UTF_8)
        assertTrue("GBK Chinese title should decode to UTF-8", threadListHtml.contains("公开多页讨论帖标题"))
        assertFalse("Real username must be sanitized", threadListHtml.contains("SecretLoginUser"))
        assertFalse("Real uid must be sanitized", threadListHtml.contains("54321"))
        assertFalse("Real formhash must be sanitized", threadListHtml.contains("a1b2c3d4"))
        assertFalse("Real cookie must be sanitized", threadListHtml.contains("realAuthSecret"))

        val smsDetailHtml = File(unzippedDir, "sms/sms_detail.html").readText(Charsets.UTF_8)
        assertFalse("Private SMS body must be masked", smsDetailHtml.contains("这是一条私人短消息正文"))
        assertFalse("SMS peer username must be masked", smsDetailHtml.contains("PeerBob"))
        assertFalse("SMS peer uid must be masked", smsDetailHtml.contains("77889"))

        // manifest.json must contain no sensitive data
        val manifestJson = File(unzippedDir, SampleExportConstants.MANIFEST_FILE_NAME).readText(Charsets.UTF_8)
        assertFalse(manifestJson.contains("SecretLoginUser"))
        assertFalse(manifestJson.contains("54321"))
        assertFalse(manifestJson.contains("77889"))
        assertFalse(manifestJson.contains("a1b2c3d4"))

        // Entire unzipped directory must pass SampleLeakScanner.scanDirectory with 0 leaks
        val leakReports = SampleLeakScanner.scanDirectory(unzippedDir.toPath())
        assertTrue(
            "Unzipped samples must have 0 leaks: ${SampleLeakScanner.formatLeakReport(leakReports)}",
            leakReports.isEmpty(),
        )
    }

    @Test
    fun fullManifest_supportsCustomTidAndSkipsSmsDetailWhenInboxEmpty() = runBlocking {
        val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = createForumDispatcher(
            recorded = recordedRequests,
            emptySmsList = true,
        )

        val engine = SampleExportEngine(
            siteAccessGateway = gateway,
            exportDir = exportDir,
            baseUrl = server.url("/forum/"),
        )

        val result = engine.runAll(
            includePersonalHistory = true,
            customTid = "661234",
        )

        assertEquals("661234", result.resolvedTid)
        val targets = recordedRequests.map { it.target }
        assertTrue(targets.contains("/forum/viewthread.php?tid=661234&page=1"))
        assertTrue(targets.contains("/forum/viewthread.php?tid=661234&page=2"))
        assertTrue(targets.contains("/forum/viewthread.php?tid=661234&page=1&authorid=44556"))
        assertTrue("Must not request sms_detail when sms_list has no peer", targets.none { it.contains("daterange=5") })

        val smsDetailState = result.items.single { it.spec.id == SampleEntryId.SMS_DETAIL }
        assertTrue("sms_detail should be Skipped when inbox is empty: $smsDetailState", smsDetailState.status is SampleExportStatus.Skipped)

        val zipFile = requireNotNull(result.zipFile)
        val unzippedDir = tempFolder.newFolder("unzipped_empty_sms")
        val extractedPaths = unzipTo(zipFile, unzippedDir)
        assertFalse(extractedPaths.contains("sms/sms_detail.html"))
        assertEquals(15 + 1, extractedPaths.size)
        assertTrue(SampleLeakScanner.scanDirectory(unzippedDir.toPath()).isEmpty())
    }

    // (b) Leak gate: if sanitizer leaves unsanitized secret, item fails and is written neither to disk nor zip
    @Test
    fun leakGate_rejectsUnsanitizedOutputFromDiskAndZip() = runBlocking {
        server.dispatcher = createForumDispatcher()

        val engine = SampleExportEngine(
            siteAccessGateway = gateway,
            exportDir = exportDir,
            baseUrl = server.url("/forum/"),
            sanitizer = { html, pageType, identity ->
                if (pageType == PageType.POST_FORM) {
                    // Simulate broken sanitizer leaving real formhash & cookie intact
                    html
                } else {
                    HtmlSanitizer.sanitize(html, pageType, identity)
                }
            },
        )

        val result = engine.runAll(includePersonalHistory = true)

        val postFormItem = result.items.single { it.spec.id == SampleEntryId.POST_FORM_NEWTHREAD }
        assertTrue("Leaked entry must fail: $postFormItem", postFormItem.status is SampleExportStatus.Failed)
        val failedStatus = postFormItem.status as SampleExportStatus.Failed
        assertTrue(
            "Failure reason/detail must mention leak type FORMHASH: $failedStatus",
            failedStatus.reason.contains(LeakType.FORMHASH.name) ||
                failedStatus.detail.orEmpty().contains(LeakType.FORMHASH.name),
        )

        // Ensure no loose leaked HTML file exists anywhere under exportDir
        val filesInExportDir = exportDir.walkTopDown().filter { it.isFile }.toList()
        assertEquals("Only the single clean zip should exist in exportDir", 1, filesInExportDir.size)
        assertTrue(filesInExportDir.single().name.endsWith(".zip"))

        val zipFile = requireNotNull(result.zipFile)
        val unzippedDir = tempFolder.newFolder("unzipped_leak_gate")
        val extractedPaths = unzipTo(zipFile, unzippedDir)
        assertFalse(
            "Leaked post_form/newthread_fid2.html must NOT be inside zip",
            extractedPaths.contains("post_form/newthread_fid2.html"),
        )
        assertEquals(15 + 1, extractedPaths.size)
        assertTrue(SampleLeakScanner.scanDirectory(unzippedDir.toPath()).isEmpty())
    }

    // (c) Single-item failure does not block others; single-item retry succeeds and updates zip
    @Test
    fun singleItemFailureAndRetry_completesOthersThenRetriesFailedItemIntoUpdatedZip() = runBlocking {
        val failNotify = AtomicBoolean(true)
        val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = createForumDispatcher(
            recorded = recordedRequests,
            failPathPredicate = { path -> failNotify.get() && path == "/forum/notice.php" },
        )

        var now = 1_764_000_001_000L
        val engine = SampleExportEngine(
            siteAccessGateway = gateway,
            exportDir = exportDir,
            baseUrl = server.url("/forum/"),
            clock = { now },
        )

        val firstResult = engine.runAll(includePersonalHistory = true)
        val failedNotify = firstResult.items.single { it.spec.id == SampleEntryId.NOTIFY_LIST }
        assertTrue("notify_list should fail on HTTP 500", failedNotify.status is SampleExportStatus.Failed)
        assertEquals(15, firstResult.items.count { it.status is SampleExportStatus.Success })

        val firstZip = requireNotNull(firstResult.zipFile)
        val firstUnzipped = tempFolder.newFolder("unzipped_first")
        val firstPaths = unzipTo(firstZip, firstUnzipped)
        assertFalse(firstPaths.contains("notify/notify_list.html"))
        assertEquals(15 + 1, firstPaths.size)

        // Now recover notice.php and retry ONLY NOTIFY_LIST
        failNotify.set(false)
        recordedRequests.clear()
        now += 5_000L

        val retryResult = engine.retryEntry(SampleEntryId.NOTIFY_LIST, includePersonalHistory = true)

        assertEquals("Single retry must issue exactly 1 GET request", 1, recordedRequests.size)
        assertEquals("GET", recordedRequests.single().method)
        assertEquals("/forum/notice.php", recordedRequests.single().target)

        val retriedNotify = retryResult.items.single { it.spec.id == SampleEntryId.NOTIFY_LIST }
        assertTrue("notify_list must succeed after retry: $retriedNotify", retriedNotify.status is SampleExportStatus.Success)
        assertEquals(16, retryResult.items.count { it.status is SampleExportStatus.Success })

        val secondZip = requireNotNull(retryResult.zipFile)
        val secondUnzipped = tempFolder.newFolder("unzipped_retry")
        val secondPaths = unzipTo(secondZip, secondUnzipped)
        assertTrue("Updated zip must contain retried notify/notify_list.html", secondPaths.contains("notify/notify_list.html"))
        assertEquals(16 + 1, secondPaths.size)
        assertTrue(SampleLeakScanner.scanDirectory(secondUnzipped.toPath()).isEmpty())
    }

    @Test
    fun smsListFailure_marksSmsDetailFailedInsteadOfSkippedUntilRetried() = runBlocking {
        val failSmsList = AtomicBoolean(true)
        server.dispatcher = createForumDispatcher(
            failPathPredicate = { path -> failSmsList.get() && path == "/forum/pm.php" },
        )

        val engine = SampleExportEngine(
            siteAccessGateway = gateway,
            exportDir = exportDir,
            baseUrl = server.url("/forum/"),
        )

        val first = engine.runAll(includePersonalHistory = true)
        assertTrue(first.items.single { it.spec.id == SampleEntryId.SMS_LIST }.status is SampleExportStatus.Failed)
        assertTrue(first.items.single { it.spec.id == SampleEntryId.SMS_DETAIL }.status is SampleExportStatus.Failed)

        failSmsList.set(false)
        engine.retryEntry(SampleEntryId.SMS_LIST, includePersonalHistory = true)
        val afterDetailRetry = engine.retryEntry(SampleEntryId.SMS_DETAIL, includePersonalHistory = true)
        assertTrue(afterDetailRetry.items.single { it.spec.id == SampleEntryId.SMS_LIST }.status is SampleExportStatus.Success)
        assertTrue(afterDetailRetry.items.single { it.spec.id == SampleEntryId.SMS_DETAIL }.status is SampleExportStatus.Success)
    }

    // (d) Login state detection relies solely on non-blank cdb_auth, and login WebView sets unified UA & syncs cookies
    @Test
    fun loginStateCheck_dependsSolelyOnNonBlankCdbAuthCookieForForumUrl() {
        val forumUrl = SampleExportConstants.FORUM_BASE_URL
        assertFalse(isForumLoggedIn(cookieStore, forumUrl))

        // Only cdb_sid & cf_clearance -> still logged out
        cookieStore.saveFromResponse(
            forumUrl,
            listOf(
                requireNotNull(Cookie.parse(forumUrl, "cdb_sid=abc123; Domain=.4d4y.com; Path=/")),
                requireNotNull(Cookie.parse(forumUrl, "cf_clearance=cf_ok; Domain=.4d4y.com; Path=/; Secure")),
            ),
        )
        assertFalse(isForumLoggedIn(cookieStore, forumUrl))

        // Empty cdb_auth -> still logged out
        cookieStore.saveFromResponse(
            forumUrl,
            listOf(
                Cookie.Builder()
                    .name("cdb_auth")
                    .value("")
                    .domain("4d4y.com")
                    .path("/")
                    .build(),
            ),
        )
        assertFalse(isForumLoggedIn(cookieStore, forumUrl))

        // Unrelated domain cdb_auth -> still logged out
        val otherUrl = "https://other.example.com/forum/".toHttpUrl()
        cookieStore.saveFromResponse(
            otherUrl,
            listOf(requireNotNull(Cookie.parse(otherUrl, "cdb_auth=other_token; Path=/"))),
        )
        assertFalse(isForumLoggedIn(cookieStore, forumUrl))

        // Valid non-blank cdb_auth on 4d4y.com -> logged in
        cookieStore.saveFromResponse(
            forumUrl,
            listOf(requireNotNull(Cookie.parse(forumUrl, "cdb_auth=valid_auth_token; Domain=.4d4y.com; Path=/; HttpOnly"))),
        )
        assertTrue(isForumLoggedIn(cookieStore, forumUrl))
    }

    @Test
    fun loginWebViewConfiguration_setsUnifiedUserAgentEnablesCookiesAndSyncsViaBridge() = runBlocking {
        val fakeCookieAccess = TestWebViewCookieAccess()
        val cookieBridge = WebViewCookieBridge(
            siteCookieStore = cookieStore,
            webViewCookieAccess = fakeCookieAccess,
            cookieDispatcher = Dispatchers.Unconfined,
        )
        val viewModel = SampleExportViewModel(
            siteAccessGateway = gateway,
            siteCookieStore = cookieStore,
            webViewCookieBridge = cookieBridge,
            exportDir = exportDir,
        )

        assertFalse(viewModel.uiState.value.isLoggedIn)

        val webView = WebView(application)
        val navigationEvents = mutableListOf<String?>()
        configureSampleLoginWebView(
            webView = webView,
            userAgentProvider = UserAgentProvider { unifiedUa },
            onPageNavigationChanged = { url -> navigationEvents += url },
        )

        // Verify R-A5a unified UA and required WebView & CookieManager flags
        assertEquals(unifiedUa, webView.settings.userAgentString)
        assertTrue(webView.settings.javaScriptEnabled)
        assertTrue(webView.settings.domStorageEnabled)
        assertFalse(webView.settings.allowFileAccess)
        assertFalse(webView.settings.allowContentAccess)
        assertTrue(CookieManager.getInstance().acceptCookie())
        assertTrue(CookieManager.getInstance().acceptThirdPartyCookies(webView))

        // Navigation client must block dangerous schemes and report page navigation changes
        assertTrue(
            webView.webViewClient.shouldOverrideUrlLoading(
                webView,
                fakeWebRequest("javascript:alert(1)"),
            ),
        )
        assertFalse(
            webView.webViewClient.shouldOverrideUrlLoading(
                webView,
                fakeWebRequest(SampleExportConstants.LOGIN_URL_STRING),
            ),
        )

        // Simulate user logging in inside WebView: WebView now holds cdb_auth, cdb_sid, cf_clearance
        val loginUrl = SampleExportConstants.LOGIN_URL
        fakeCookieAccess.seedCookie(loginUrl, "cdb_auth=wv_auth_ok; Domain=.4d4y.com; Path=/; HttpOnly")
        fakeCookieAccess.seedCookie(loginUrl, "cdb_sid=wv_sid_ok; Domain=.4d4y.com; Path=/")
        fakeCookieAccess.seedCookie(loginUrl, "cf_clearance=wv_cf_ok; Domain=.4d4y.com; Path=/; Secure; HttpOnly")

        webView.webViewClient.onPageFinished(webView, "https://www.4d4y.com/forum/index.php")
        assertEquals(listOf("https://www.4d4y.com/forum/index.php"), navigationEvents)

        // Syncing cookies via bridge populates SiteCookieStore and updates ViewModel login state
        viewModel.syncCookiesFromWebViewNow()

        assertTrue(viewModel.uiState.value.isLoggedIn)
        val syncedNames = cookieStore.loadForRequest(SampleExportConstants.FORUM_BASE_URL).map { it.name }.toSet()
        assertEquals(setOf("cdb_auth", "cdb_sid", "cf_clearance"), syncedNames)
    }

    // (e) Privacy toggle: unchecking my_threads/my_posts/my_favorites sends no requests and excludes them from zip
    @Test
    fun privacyToggleOff_skipsPersonalHistoryRequestsAndExcludesFromZip() = runBlocking {
        val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = createForumDispatcher(recorded = recordedRequests)

        val engine = SampleExportEngine(
            siteAccessGateway = gateway,
            exportDir = exportDir,
            baseUrl = server.url("/forum/"),
        )

        val result = engine.runAll(includePersonalHistory = false)

        assertEquals(13, recordedRequests.size)
        assertTrue(
            "No request should hit my.php when personal history is unchecked: ${recordedRequests.map { it.target }}",
            recordedRequests.none { it.target.contains("my.php") },
        )

        val personalIds = setOf(
            SampleEntryId.MY_THREADS,
            SampleEntryId.MY_POSTS,
            SampleEntryId.MY_FAVORITES,
        )
        for (id in personalIds) {
            val state = result.items.single { it.spec.id == id }
            assertTrue("$id must be Skipped: $state", state.status is SampleExportStatus.Skipped)
        }
        assertEquals(13, result.items.count { it.status is SampleExportStatus.Success })

        val zipFile = requireNotNull(result.zipFile)
        val unzippedDir = tempFolder.newFolder("unzipped_privacy_off")
        val extractedPaths = unzipTo(zipFile, unzippedDir)
        assertFalse(extractedPaths.contains("simple_list/my_threads.html"))
        assertFalse(extractedPaths.contains("simple_list/my_posts.html"))
        assertFalse(extractedPaths.contains("simple_list/my_favorites.html"))
        assertEquals(13 + 1, extractedPaths.size)
        assertTrue(SampleLeakScanner.scanDirectory(unzippedDir.toPath()).isEmpty())
    }

    @Test
    fun debugManifestProviderShareIntentAndHiltEntryPoint_areWiredProperly() {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.packageManager.getPackageInfo(
                application.packageName,
                PackageManager.PackageInfoFlags.of((PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS).toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            application.packageManager.getPackageInfo(
                application.packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS,
            )
        }

        val activityInfo = packageInfo.activities?.firstOrNull { it.name == SampleExportActivity::class.java.name }
        assertNotNull("SampleExportActivity must be registered in debug manifest", activityInfo)
        assertFalse("SampleExportActivity must not be exported", requireNotNull(activityInfo).exported)

        val expectedAuthority = "${application.packageName}${SampleExportConstants.PROVIDER_AUTHORITY_SUFFIX}"
        val providerInfo = packageInfo.providers?.firstOrNull { it.authority == expectedAuthority }
        assertNotNull("Debug FileProvider must be registered with authority $expectedAuthority", providerInfo)
        assertFalse("Debug FileProvider must not be exported", requireNotNull(providerInfo).exported)
        assertTrue("Debug FileProvider must grantUriPermissions", providerInfo.grantUriPermissions)

        // Verify share intent creation with FileProvider URI and read permission flag
        val realExportDir = File(application.filesDir, SampleExportConstants.EXPORT_DIR_NAME).apply { mkdirs() }
        val dummyZip = File(realExportDir, "hipda-samples-test.zip").apply { writeBytes(byteArrayOf(0x50, 0x4B)) }
        try {
            val shareIntent = createSampleExportShareIntent(application, dummyZip)
            assertEquals(Intent.ACTION_SEND, shareIntent.action)
            assertEquals("application/zip", shareIntent.type)
            assertTrue((shareIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
            val uri = shareIntent.clipData?.getItemAt(0)?.uri
            assertNotNull("Share intent must attach FileProvider URI", uri)
            assertEquals(expectedAuthority, requireNotNull(uri).authority)
        } finally {
            Files.deleteIfExists(dummyZip.toPath())
        }

        // Verify SampleExportEntryPoint exposes all 4 Hilt SingletonComponent dependencies
        assertEquals(
            SiteAccessGateway::class.java,
            SampleExportEntryPoint::class.java.getMethod("siteAccessGateway").returnType,
        )
        assertEquals(
            net.jejer.hipda.data.network.cookie.SiteCookieStore::class.java,
            SampleExportEntryPoint::class.java.getMethod("siteCookieStore").returnType,
        )
        assertEquals(
            UserAgentProvider::class.java,
            SampleExportEntryPoint::class.java.getMethod("userAgentProvider").returnType,
        )
        assertEquals(
            WebViewCookieBridge::class.java,
            SampleExportEntryPoint::class.java.getMethod("webViewCookieBridge").returnType,
        )
    }

    // region Fixtures & Helpers

    private fun createForumDispatcher(
        recorded: CopyOnWriteArrayList<RecordedRequest>? = null,
        emptySmsList: Boolean = false,
        failPathPredicate: (String) -> Boolean = { false },
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            recorded?.add(request)
            val path = request.url.encodedPath
            if (failPathPredicate(path)) {
                return MockResponse.Builder().code(500).body("Internal Server Error").build()
            }
            val html = when (path) {
                "/forum/forumdisplay.php" -> sampleThreadListHtml()
                "/forum/viewthread.php" -> sampleThreadDetailHtml()
                "/forum/pm.php" -> if (request.url.queryParameter("filter") == "privatepm") {
                    if (emptySmsList) sampleEmptySmsListHtml() else sampleSmsListHtml()
                } else {
                    sampleSmsDetailHtml()
                }
                "/forum/notice.php" -> sampleNotifyHtml()
                "/forum/search.php" -> sampleSearchHtml()
                "/forum/space.php" -> sampleUserProfileHtml()
                "/forum/my.php" -> sampleSimpleListHtml(request.url.queryParameter("item").orEmpty())
                "/forum/post.php" -> samplePostFormHtml()
                "/forum/logging.php" -> sampleLoginHtml()
                else -> return MockResponse.Builder().code(404).body("Not Found").build()
            }
            return MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/html; charset=GBK")
                .body(Buffer().write(html.toByteArray(gbk)))
                .build()
        }
    }

    private fun sampleHeaderShell(title: String): String = """
        <!DOCTYPE html>
        <html>
        <head>
          <meta http-equiv="Content-Type" content="text/html; charset=gbk" />
          <title>$title - Hi!PDA</title>
          <script>
            var discuz_uid = '54321';
            var formhash = 'a1b2c3d4';
            document.cookie = 'cdb_auth=realAuthSecret; cf_clearance=realCfToken';
          </script>
        </head>
        <body>
          <div id="umenu">
            <cite><a href="space.php?uid=54321" class="noborder">SecretLoginUser</a></cite>
            <a href="logging.php?action=logout&amp;formhash=a1b2c3d4">退出</a>
          </div>
    """.trimIndent()

    private fun sampleThreadListHtml(): String = sampleHeaderShell("Discovery") + """
          <table class="datatable">
            <tbody id="stickthread_99001">
              <tr>
                <td class="folder"><img src="images/default/pin_1.gif" /></td>
                <th class="subject"><span><a href="viewthread.php?tid=99001&amp;extra=page%3D1">版规置顶帖</a></span>
                  <span class="threadpages"><a href="viewthread.php?tid=99001&amp;page=2">2</a></span></th>
                <td class="author"><cite><a href="space.php?uid=1">Admin</a></cite><em>2026-11-01</em></td>
                <td class="nums"><strong>200</strong><em>9000</em></td>
                <td class="lastpost"><cite>Admin</cite><em><a>2026-11-24</a></em></td>
              </tr>
            </tbody>
            <tbody id="normalthread_88420">
              <tr>
                <td class="folder"><img src="images/default/folder_common.gif" /></td>
                <th class="subject"><span><a href="viewthread.php?tid=88420&amp;extra=page%3D1">普通单页帖</a></span></th>
                <td class="author"><cite><a href="space.php?uid=33100">UserOne</a></cite><em>2026-11-23</em></td>
                <td class="nums"><strong>3</strong><em>120</em></td>
                <td class="lastpost"><cite>UserOne</cite><em><a>2026-11-24</a></em></td>
              </tr>
            </tbody>
            <tbody id="normalthread_88421">
              <tr>
                <td class="folder"><img src="images/default/folder_new.gif" /></td>
                <th class="subject"><span><a href="viewthread.php?tid=88421&amp;extra=page%3D1">公开多页讨论帖标题</a></span>
                  <span class="threadpages"><a href="viewthread.php?tid=88421&amp;page=2">2</a><a href="viewthread.php?tid=88421&amp;page=5">5</a></span></th>
                <td class="author"><cite><a href="space.php?uid=44556">PublicAuthor</a></cite><em>2026-11-20</em></td>
                <td class="nums"><strong>128</strong><em>3200</em></td>
                <td class="lastpost"><cite>SecretLoginUser</cite><em><a>2026-11-25</a></em></td>
              </tr>
            </tbody>
          </table>
        </body></html>
    """.trimIndent()

    private fun sampleThreadDetailHtml(): String = sampleHeaderShell("公开多页讨论帖标题") + """
          <div id="postlist">
            <div id="post_100001">
              <table><tbody><tr>
                <td class="postauthor"><div class="postinfo"><a href="space.php?uid=44556">PublicAuthor</a></div></td>
                <td class="postcontent"><div class="t_msgfont">这是公开讨论帖正文内容，包含中文与标点。</div></td>
              </tr></tbody></table>
            </div>
            <div id="post_100002">
              <table><tbody><tr>
                <td class="postauthor"><div class="postinfo"><a href="space.php?uid=54321">SecretLoginUser</a></div></td>
                <td class="postcontent"><div class="t_msgfont">楼层回复内容</div></td>
                <td class="postbottom"><div class="signatures">我的私人签名内容</div></td>
              </tr></tbody></table>
            </div>
          </div>
          <input type="hidden" name="formhash" value="a1b2c3d4" />
        </body></html>
    """.trimIndent()

    private fun sampleSmsListHtml(): String = sampleHeaderShell("短消息") + """
          <ul class="pm_list">
            <li class="s_clear">
              <a class="avatar" href="space.php?uid=77889"><img src="https://img02.4d4y.com/forum/uc_server/data/avatar/000/07/78/89_avatar_middle.jpg" /></a>
              <p class="cite"><cite><a href="space.php?uid=77889">PeerBob</a></cite> 2026-11-24</p>
              <div class="summary">这是一条私人短消息摘要</div>
            </li>
          </ul>
        </body></html>
    """.trimIndent()

    private fun sampleEmptySmsListHtml(): String = sampleHeaderShell("短消息") + """
          <div class="nodata">暂无短消息</div>
        </body></html>
    """.trimIndent()

    private fun sampleSmsDetailHtml(): String = sampleHeaderShell("与 PeerBob 的短消息") + """
          <div class="itemtitle"><h1>与 PeerBob 的短消息</h1></div>
          <ul class="pm_list">
            <li class="s_clear">
              <a class="avatar" href="space.php?uid=77889"><img src="https://img02.4d4y.com/forum/uc_server/data/avatar/000/07/78/89_avatar_middle.jpg" /></a>
              <p class="cite"><cite><a href="space.php?uid=77889">PeerBob</a></cite> 2026-11-24</p>
              <div class="summary">这是一条私人短消息正文</div>
            </li>
          </ul>
          <form action="pm.php?action=send&amp;uid=77889">
            <input type="hidden" name="formhash" value="a1b2c3d4" />
            <input type="text" name="msgto" value="PeerBob" />
          </form>
        </body></html>
    """.trimIndent()

    private fun sampleNotifyHtml(): String = sampleHeaderShell("提醒") + """
          <ul class="feed">
            <li><div class="f_buddy"><a href="space.php?uid=77889">PeerBob</a> 附言：加个好友聊聊</div></li>
          </ul>
        </body></html>
    """.trimIndent()

    private fun sampleSearchHtml(): String = sampleHeaderShell("搜索结果") + """
          <div class="searchlist"><table><tbody><tr><th><a href="viewthread.php?tid=88421">公开多页讨论帖标题</a></th></tr></tbody></table></div>
        </body></html>
    """.trimIndent()

    private fun sampleUserProfileHtml(): String = sampleHeaderShell("个人资料") + """
          <div id="profilecontent">
            <div class="itemtitle"><h1>SecretLoginUser</h1></div>
            <p>邮箱：real@private.org，登录 IP：203.0.113.42</p>
          </div>
        </body></html>
    """.trimIndent()

    private fun sampleSimpleListHtml(item: String): String = sampleHeaderShell("我的-$item") + """
          <div class="datalist"><table><tbody><tr><th><a href="viewthread.php?tid=88421">记录项-$item</a></th></tr></tbody></table></div>
        </body></html>
    """.trimIndent()

    private fun samplePostFormHtml(): String = sampleHeaderShell("发表新话题") + """
          <form action="post.php?action=newthread&amp;fid=2">
            <input type="hidden" name="formhash" value="a1b2c3d4" />
            <input type="hidden" name="hash" value="deadbeef99" />
          </form>
        </body></html>
    """.trimIndent()

    private fun sampleLoginHtml(): String = sampleHeaderShell("登录") + """
          <form action="logging.php?action=login">
            <input type="hidden" name="formhash" value="a1b2c3d4" />
          </form>
        </body></html>
    """.trimIndent()

    private fun unzipTo(zipFile: File, targetDir: File): Set<String> {
        val entries = linkedSetOf<String>()
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries.add(entry.name)
                    val outFile = File(targetDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return entries
    }

    private fun fakeWebRequest(url: String): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl() = android.net.Uri.parse(url)
            override fun isForMainFrame(): Boolean = true
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }

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

    // endregion
}
