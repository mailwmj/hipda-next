package net.jejer.hipda.feature.routing

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.datastore.core.DataStoreFactory
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.R
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.cookie.CookieStorePayloadSerializer
import net.jejer.hipda.data.network.cookie.DataStoreSiteCookieStore
import net.jejer.hipda.data.network.cookie.SiteCookieStore
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppHostViewModel
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.app.offlinePageMeta
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.ui.designsystem.glass.GlassDegradationReason
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import okhttp3.Cookie
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * 独立验证卡 V-E16：
 * - 探针 3：鉴权附件下载（携带论坛 Cookie/cf_clearance、路径穿越与 NUL 文件名净化、API 28 拒权零写入、HTTP 失败、下载取消）；
 * - 补充探针 (5)：跨域附件 URL（直连与 302 跨站跳转）不携带论坛 Cookie / `cf_clearance`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class V_E16_AttachmentAndCrossOriginCookieProbeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var tempRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        OkHttp.initialize(context)
        tempRoot = File(context.cacheDir, "v_e16_att_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun buildProductionLikeClient(cookieStore: SiteCookieStore): OkHttpClient {
        val base = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { "Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0" },
            transportInterceptor = TransportInterceptor(delegateFactory = { null }),
        )
        return base.newBuilder()
            .dns(
                Dns { hostname ->
                    when (hostname) {
                        "www.4d4y.com", "cdn.other-host.invalid", "4d4y.com.evil.invalid" ->
                            listOf(InetAddress.getByName("127.0.0.1"))
                        else -> Dns.SYSTEM.lookup(hostname)
                    }
                },
            )
            .build()
    }

    @Test
    fun probe5_crossOriginAttachmentDownload_neverCarriesForumCookieOrCfClearance() = runBlocking {
        val cookieScopeJob = SupervisorJob()
        val cookieScope = CoroutineScope(Dispatchers.IO + cookieScopeJob)
        val ds = DataStoreFactory.create(
            serializer = CookieStorePayloadSerializer,
            scope = cookieScope,
            produceFile = { File(tempRoot, "cookies.json") },
        )
        val cookieStore = DataStoreSiteCookieStore(ds, cookieScope)
        val forumUrl = "http://www.4d4y.com/forum/".toHttpUrl()
        val httpsForumUrl = "https://www.4d4y.com/forum/".toHttpUrl()
        val authCookie = requireNotNull(Cookie.parse(forumUrl, "cdb_auth=SECRET_AUTH_TOKEN; Domain=.4d4y.com; Path=/"))
        val cfCookie = requireNotNull(Cookie.parse(forumUrl, "cf_clearance=SECRET_CF_TOKEN; Domain=.4d4y.com; Path=/"))
        cookieStore.saveFromResponse(forumUrl, listOf(authCookie, cfCookie))

        // 验证域匹配隔离：绝不匹配跨域或后缀伪造域名
        assertTrue(cookieStore.loadForRequest("https://www.4d4y.com/forum/attachment.php?aid=1".toHttpUrl()).any { it.name == "cf_clearance" })
        assertTrue(cookieStore.loadForRequest("https://cdn.other-host.invalid/files/a.zip".toHttpUrl()).isEmpty())
        assertTrue(cookieStore.loadForRequest("https://4d4y.com.evil.invalid/files/a.zip".toHttpUrl()).isEmpty())

        val client = buildProductionLikeClient(cookieStore)
        val forumRequests = mutableListOf<RecordedRequest>()
        val externalRequests = mutableListOf<RecordedRequest>()

        val forumServer = MockWebServer()
        val externalServer = MockWebServer()
        forumServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                forumRequests += request
                return when {
                    request.target.startsWith("/forum/attachment.php?aid=1") ->
                        MockResponse.Builder().code(200).addHeader("Content-Type", "application/pdf").body("forum-pdf").build()
                    request.target.startsWith("/forum/attachment.php?aid=redirect") ->
                        MockResponse.Builder()
                            .code(302)
                            .addHeader("Location", "http://cdn.other-host.invalid:${externalServer.port}/redirected/asset.bin")
                            .build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        externalServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                externalRequests += request
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/octet-stream").body("external-bin").build()
            }
        }
        forumServer.start(InetAddress.getByName("127.0.0.1"), 0)
        externalServer.start(InetAddress.getByName("127.0.0.1"), 0)

        try {
            val savedDir = File(tempRoot, "downloads").apply { mkdirs() }
            val saver = AndroidAttachmentMediaStoreSaver(
                context = context,
                sdkInt = 28,
                hasLegacyStoragePermission = { true },
                legacyDownloadsDirProvider = { savedDir },
            )
            val downloader = OkHttpAttachmentDownloader(
                callFactory = client,
                saver = saver,
                hasLegacyStoragePermission = { true },
                sdkInt = 28,
            )

            // 1. 同域论坛附件：必须携带 cdb_auth 与 cf_clearance
            val forumRes = downloader.download("http://www.4d4y.com:${forumServer.port}/forum/attachment.php?aid=1", "forum.pdf")
            assertTrue(forumRes is AttachmentDownloadResult.Success)
            assertEquals(1, forumRequests.size)
            val forumCookieHeader = forumRequests.last().headers["Cookie"].orEmpty()
            assertTrue("Forum download must send cdb_auth, got: $forumCookieHeader", "cdb_auth=SECRET_AUTH_TOKEN" in forumCookieHeader)
            assertTrue("Forum download must send cf_clearance, got: $forumCookieHeader", "cf_clearance=SECRET_CF_TOKEN" in forumCookieHeader)

            // 2. 跨域直连附件：绝不携带 Cookie / cf_clearance
            val crossRes = downloader.download("http://cdn.other-host.invalid:${externalServer.port}/direct/file.bin", "cross.bin")
            assertTrue(crossRes is AttachmentDownloadResult.Success)
            assertEquals(1, externalRequests.size)
            assertNull("Cross-origin direct download must have NO Cookie header", externalRequests.last().headers["Cookie"])

            // 3. 论坛 URL 302 重定向至跨域附件：Hop 1 携论坛 Cookie，Hop 2 跨域必须剥离、不带任何 Cookie/cf_clearance
            val redirectRes = downloader.download("http://www.4d4y.com:${forumServer.port}/forum/attachment.php?aid=redirect", "redir.bin")
            assertTrue(redirectRes is AttachmentDownloadResult.Success)
            assertEquals(2, forumRequests.size)
            assertTrue("Redirect hop 1 must send forum cookies", "cf_clearance=SECRET_CF_TOKEN" in forumRequests.last().headers["Cookie"].orEmpty())
            assertEquals(2, externalRequests.size)
            assertNull("Redirect hop 2 to non-forum host must carry NO Cookie/cf_clearance", externalRequests.last().headers["Cookie"])

            // 4. 补测 Secure 属性的 cf_clearance Cookie：HTTPS 同域命中、跨域与 HTTP 降级绝不泄露
            val secureCfCookie = requireNotNull(Cookie.parse(httpsForumUrl, "cf_clearance=SECRET_SECURE_CF; Domain=.4d4y.com; Path=/; Secure"))
            cookieStore.saveFromResponse(httpsForumUrl, listOf(secureCfCookie))
            assertTrue(cookieStore.loadForRequest("https://www.4d4y.com/forum/attachment.php?aid=1".toHttpUrl()).any { it.value == "SECRET_SECURE_CF" })
            assertTrue(cookieStore.loadForRequest("https://cdn.other-host.invalid/files/a.zip".toHttpUrl()).isEmpty())
            assertTrue(cookieStore.loadForRequest("http://www.4d4y.com/forum/attachment.php?aid=1".toHttpUrl()).none { it.name == "cf_clearance" })
        } finally {
            forumServer.close()
            externalServer.close()
            cookieScopeJob.cancel()
        }
    }

    @Test
    fun probe3_authenticatedDownloadTraversalSanitizationApi28PermissionAndCancel() = runBlocking {
        val traversalCases = listOf(
            "../../etc/passwd" to "passwd",
            "..\\..\\windows\\system32\\cmd.exe" to "cmd.exe",
            "a\\b\\evil\u0000.pdf" to "evil.pdf",
            "\u0000\u200B../..\u202E/.." to "attachment.pdf",
            "\u0000\u200B../..\u202E/..pdf" to "pdf.pdf",
            "..." to "attachment",
        )
        traversalCases.forEach { (raw, expected) ->
            val actual = sanitizeAttachmentFileName(raw, if (expected.endsWith(".pdf")) "application/pdf" else null)
            assertEquals("Sanitized filename mismatch for $raw", expected, actual)
            assertFalse("Must not contain '..'", ".." in actual)
            assertFalse("Must not contain '/'", '/' in actual)
            assertFalse("Must not contain '\\'", '\\' in actual)
            assertFalse("Must not contain NUL", '\u0000' in actual)
        }
        assertEquals(
            "../../evil\u0000.pdf",
            parseContentDispositionFileName("attachment; filename*=UTF-8''..%2F..%2Fevil%00.pdf"),
        )

        val server = MockWebServer()
        val saverEntered = CompletableDeferred<Unit>()
        val holdStream = CompletableDeferred<Unit>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.target) {
                    "/forum/attachment.php?aid=traversal" ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Disposition", "attachment; filename*=UTF-8''..%2F..%2Fevil%00name.pdf")
                            .addHeader("Content-Type", "application/pdf")
                            .body("pdf-payload-123")
                            .build()
                    "/forum/attachment.php?aid=fail403" ->
                        MockResponse.Builder().code(403).body("forbidden").build()
                    "/forum/attachment.php?aid=slow" ->
                        MockResponse.Builder()
                            .code(200)
                            .setHeader("Content-Type", "application/octet-stream")
                            .bodyDelay(5, TimeUnit.SECONDS)
                            .body("slow-payload")
                            .build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)

        try {
            val cookieStore = InMemorySiteCookieStore()
            val forumUrl = "http://www.4d4y.com:${server.port}/forum/".toHttpUrl()
            cookieStore.saveFromResponse(
                forumUrl,
                listOf(
                    requireNotNull(Cookie.parse(forumUrl, "cdb_auth=A1; Path=/")),
                    requireNotNull(Cookie.parse(forumUrl, "cf_clearance=C1; Path=/")),
                ),
            )
            val client = buildProductionLikeClient(cookieStore)

            // 1. API 28 拒权：零文件写入、零网络请求、返回 PermissionDenied
            val deniedDir = File(tempRoot, "api28_denied").apply { mkdirs() }
            val deniedDownloader = OkHttpAttachmentDownloader(
                callFactory = client,
                saver = AndroidAttachmentMediaStoreSaver(
                    context = context,
                    sdkInt = 28,
                    hasLegacyStoragePermission = { false },
                    legacyDownloadsDirProvider = { deniedDir },
                ),
                hasLegacyStoragePermission = { false },
                sdkInt = 28,
            )
            val deniedRes = deniedDownloader.download("http://www.4d4y.com:${server.port}/forum/attachment.php?aid=traversal")
            assertEquals(AttachmentDownloadResult.PermissionDenied, deniedRes)
            assertEquals(0, server.requestCount)
            assertEquals(0, deniedDir.listFiles()?.size ?: 0)

            // 2. API 28 授权：Content-Disposition 路径穿越净化后写入目标下载目录，且绝不越出目录边界
            val grantedDir = File(tempRoot, "api28_granted").apply { mkdirs() }
            val grantedDownloader = OkHttpAttachmentDownloader(
                callFactory = client,
                saver = AndroidAttachmentMediaStoreSaver(
                    context = context,
                    sdkInt = 28,
                    hasLegacyStoragePermission = { true },
                    legacyDownloadsDirProvider = { grantedDir },
                ),
                hasLegacyStoragePermission = { true },
                sdkInt = 28,
            )
            val okRes = grantedDownloader.download("http://www.4d4y.com:${server.port}/forum/attachment.php?aid=traversal")
            assertTrue(okRes is AttachmentDownloadResult.Success)
            val savedFile = (okRes as AttachmentDownloadResult.Success).saved.file!!
            assertEquals("evilname.pdf", savedFile.name)
            assertEquals(grantedDir.canonicalFile, savedFile.canonicalFile.parentFile)
            assertEquals("pdf-payload-123", savedFile.readText())

            // 3. HTTP 403 失败：不留存任何文件
            val failRes = grantedDownloader.download("http://www.4d4y.com:${server.port}/forum/attachment.php?aid=fail403", "blocked.pdf")
            assertEquals(AttachmentDownloadResult.Failed, failRes)
            assertEquals(listOf("evilname.pdf"), grantedDir.listFiles()?.map { it.name })

            // 4. 协程取消中止下载：抛出 CancellationException 并触发 call.cancel()
            val slowJob = async(Dispatchers.Default) {
                val cancelDownloader = OkHttpAttachmentDownloader(
                    callFactory = client,
                    saver = object : AttachmentSaver {
                        override suspend fun save(fileName: String, mimeType: String, writeTo: suspend (OutputStream) -> Long): SavedAttachment? {
                            saverEntered.complete(Unit)
                            holdStream.await()
                            return null
                        }
                    },
                    hasLegacyStoragePermission = { true },
                    sdkInt = 34,
                )
                cancelDownloader.download("http://www.4d4y.com:${server.port}/forum/attachment.php?aid=slow", "slow.bin")
            }
            delay(80L)
            slowJob.cancel(CancellationException("user cancelled"))
            val caughtCancel = runCatching { slowJob.await() }.exceptionOrNull()
            assertTrue("CancellationException must propagate", caughtCancel is CancellationException)
        } finally {
            server.close()
        }
    }

    @Test
    fun probe3_nextAppRootRealThreadAttachmentClick_showsDownloadingSavedAndFailedSnackbars() {
        val harness = MultiPaneTestHarness()
        val probeDetailRepo = object : ThreadDetailRepository by harness.threadDetailRepo {
            override suspend fun loadPage(
                tid: String,
                page: Int,
                filter: ThreadDetailFilter,
                recordHistory: Boolean,
                forumName: String?,
                priority: AccessPriority,
            ): ThreadDetailLoadResult = ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(
                    tid = tid,
                    fid = 2,
                    title = "附件下载集成测试帖",
                    page = 1,
                    lastPage = 1,
                    floors = listOf(
                        PostFloor(
                            postId = "6001",
                            floor = 1,
                            page = 1,
                            uid = "1",
                            author = "楼主",
                            avatarUrl = null,
                            timePost = "2026-11-26 10:00",
                            isWarned = false,
                            postStatus = null,
                            signature = null,
                            poll = null,
                            blocks = listOf(
                                ContentBlock.Attachment(title = "规范附件.pdf", url = "https://www.4d4y.com/forum/attachment.php?aid=7", description = "12 KB"),
                                ContentBlock.Attachment(title = "失败附件.pdf", url = "https://www.4d4y.com/forum/attachment.php?aid=8", description = "4 KB"),
                            ),
                        ),
                    ),
                    postsPerPageEstimate = null,
                    meta = offlinePageMeta(),
                    hasPostList = true,
                ),
                filter = filter,
                forumName = "Discovery",
                threadAuthorId = "1",
            )
        }
        val hostVm = NextAppHostViewModel(harness.settingsRepo, probeDetailRepo)
        val vmFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostVm as T
                modelClass.isAssignableFrom(ForumViewModel::class.java) -> harness.forumViewModel as T
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) ->
                    ThreadDetailViewModel(probeDetailRepo, harness.gateway) as T
                else -> error("Unexpected VM: $modelClass")
            }
        }
        val owner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore = ViewModelStore()
            override val defaultViewModelProviderFactory = vmFactory
        }

        val okGate = CompletableDeferred<AttachmentDownloadResult>()
        val requestedUrls = mutableListOf<Pair<String, String?>>()
        val fakeDownloader = object : AttachmentDownloader {
            override suspend fun download(rawUrl: String, suggestedFileName: String?): AttachmentDownloadResult {
                requestedUrls += rawUrl to suggestedFileName
                return if ("aid=7" in rawUrl) okGate.await() else AttachmentDownloadResult.Failed
            }
        }

        hostVm.openThread(ThreadNavArgs("6001", "附件下载集成测试帖"))
        composeRule.setContent {
            ProvideGlassEnvironment(GlassEnvironmentState(setOf(GlassDegradationReason.POWER_SAVE_MODE), refractionSupported = false)) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread.asStateFlow(),
                        notifyUnreadCountFlow = harness.notifyUnread.asStateFlow(),
                        attachmentDownloader = fakeDownloader,
                        hostViewModel = hostVm,
                        forumViewModel = harness.forumViewModel,
                    )
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("规范附件.pdf").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("规范附件.pdf").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("https://www.4d4y.com/forum/attachment.php?aid=7" to "规范附件.pdf"), requestedUrls)
        composeRule.onNodeWithText(context.getString(R.string.routing_attachment_downloading)).assertIsDisplayed()

        okGate.complete(
            AttachmentDownloadResult.Success(
                SavedAttachment("规范附件.pdf", "application/pdf", 128L, Uri.parse("content://downloads/1")),
            ),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.routing_attachment_saved)).assertIsDisplayed()

        composeRule.onNodeWithText("失败附件.pdf").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.routing_attachment_download_failed)).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [28])
    fun probe3_api28PermissionDeniedInNextAppRoot_writesZeroFilesZeroNetworkAndShowsFailureSnackbar() {
        shadowOf(context.applicationContext as Application).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val harness = MultiPaneTestHarness()
        val probeDetailRepo = object : ThreadDetailRepository by harness.threadDetailRepo {
            override suspend fun loadPage(
                tid: String,
                page: Int,
                filter: ThreadDetailFilter,
                recordHistory: Boolean,
                forumName: String?,
                priority: AccessPriority,
            ): ThreadDetailLoadResult = ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(
                    tid = tid,
                    fid = 2,
                    title = "API28 拒权帖",
                    page = 1,
                    lastPage = 1,
                    floors = listOf(
                        PostFloor(
                            postId = "6002",
                            floor = 1,
                            page = 1,
                            uid = "1",
                            author = "楼主",
                            avatarUrl = null,
                            timePost = "2026-11-26 10:00",
                            isWarned = false,
                            postStatus = null,
                            signature = null,
                            poll = null,
                            blocks = listOf(
                                ContentBlock.Attachment(title = "旧版拒权.pdf", url = "https://www.4d4y.com/forum/attachment.php?aid=99", description = "4 KB"),
                            ),
                        ),
                    ),
                    postsPerPageEstimate = null,
                    meta = offlinePageMeta(),
                    hasPostList = true,
                ),
                filter = filter,
                forumName = "Discovery",
                threadAuthorId = "1",
            )
        }
        val hostVm = NextAppHostViewModel(harness.settingsRepo, probeDetailRepo)
        val vmFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostVm as T
                modelClass.isAssignableFrom(ForumViewModel::class.java) -> harness.forumViewModel as T
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) ->
                    ThreadDetailViewModel(probeDetailRepo, harness.gateway) as T
                else -> error("Unexpected VM: $modelClass")
            }
        }
        val owner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore = ViewModelStore()
            override val defaultViewModelProviderFactory = vmFactory
        }

        val server = MockWebServer()
        server.start()
        val downloadCalls = mutableListOf<String>()
        val downloader = object : AttachmentDownloader {
            override suspend fun download(rawUrl: String, suggestedFileName: String?): AttachmentDownloadResult {
                downloadCalls += rawUrl
                return AttachmentDownloadResult.PermissionDenied
            }
        }
        val requestedPermissions = mutableListOf<Any?>()
        val fakeRegistryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    requestedPermissions += input
                    @Suppress("UNCHECKED_CAST")
                    dispatchResult(requestCode, false as O)
                }
            }
        }

        try {
            hostVm.openThread(ThreadNavArgs("6002", "API28 拒权帖"))
            composeRule.setContent {
                ProvideGlassEnvironment(GlassEnvironmentState(setOf(GlassDegradationReason.POWER_SAVE_MODE), refractionSupported = false)) {
                    CompositionLocalProvider(
                        LocalViewModelStoreOwner provides owner,
                        LocalActivityResultRegistryOwner provides fakeRegistryOwner,
                    ) {
                        NextAppRoot(
                            settingsFlow = harness.settingsRepo.settings,
                            currentAccountFlow = harness.authRepo.currentAccount,
                            smsUnreadCountFlow = harness.smsUnread.asStateFlow(),
                            notifyUnreadCountFlow = harness.notifyUnread.asStateFlow(),
                            attachmentDownloader = downloader,
                            hostViewModel = hostVm,
                            forumViewModel = harness.forumViewModel,
                        )
                    }
                }
            }
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("旧版拒权.pdf").fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithText("旧版拒权.pdf").performClick()
            composeRule.waitForIdle()

            assertEquals(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), requestedPermissions)
            assertTrue("Downloader must not even be invoked when permission is denied", downloadCalls.isEmpty())
            assertEquals(0, server.requestCount)
            composeRule.onNodeWithText(context.getString(R.string.routing_attachment_download_failed)).assertIsDisplayed()
        } finally {
            server.close()
        }
    }

    class FakeMediaDownloadsProvider : android.content.ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?) = MatrixCursor(emptyArray())
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            values?.let { lastInserted += ContentValues(it) }
            return fakeUri
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            lastDeleted += uri
            return 1
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            values?.let { lastUpdated += ContentValues(it) }
            return 1
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val outFile = requireNotNull(currentOutFile)
            return ParcelFileDescriptor.open(
                outFile,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_TRUNCATE,
            )
        }

        companion object {
            val fakeUri: Uri = Uri.parse("content://media/external/downloads/42")
            val lastInserted = mutableListOf<ContentValues>()
            val lastUpdated = mutableListOf<ContentValues>()
            val lastDeleted = mutableListOf<Uri>()
            var currentOutFile: File? = null
        }
    }

    @Test
    fun probe3_mediaStoreApi29_insertsClearsPendingAndDeletesOnFailure() = runBlocking {
        FakeMediaDownloadsProvider.lastInserted.clear()
        FakeMediaDownloadsProvider.lastUpdated.clear()
        FakeMediaDownloadsProvider.lastDeleted.clear()
        FakeMediaDownloadsProvider.currentOutFile = File(tempRoot, "mediastore_out.bin")
        org.robolectric.Robolectric.buildContentProvider(FakeMediaDownloadsProvider::class.java).create(MediaStore.AUTHORITY)

        val saver = AndroidAttachmentMediaStoreSaver(context = context, sdkInt = 34)
        val saved = saver.save("../../etc/passwd", "text/plain") { out ->
            val data = "hello-mediastore".toByteArray()
            out.write(data)
            data.size.toLong()
        }
        assertEquals("passwd.txt", saved?.fileName)
        assertEquals(1, FakeMediaDownloadsProvider.lastInserted.size)
        assertEquals("passwd.txt", FakeMediaDownloadsProvider.lastInserted.single().getAsString(MediaStore.Downloads.DISPLAY_NAME))
        assertEquals(1, FakeMediaDownloadsProvider.lastInserted.single().getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertEquals(0, FakeMediaDownloadsProvider.lastUpdated.single().getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertTrue(FakeMediaDownloadsProvider.lastDeleted.isEmpty())

        val failed = saver.save("broken.bin", "application/octet-stream") {
            throw IOException("stream reset")
        }
        assertNull(failed)
        assertEquals(listOf(FakeMediaDownloadsProvider.fakeUri), FakeMediaDownloadsProvider.lastDeleted)
    }
}
