package net.jejer.hipda.feature.routing

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.threaddetail.body.PostBodyRenderer
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.ui.designsystem.glass.GlassDegradationReason
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 鉴权附件下载、文件名路径穿越净化、API 28 存储权限与协程取消中止测试（验收 3，100% 离线 MockWebServer）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AttachmentDownloaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sanitizeAttachmentFileName_neutralizesPathTraversalNullBytesAndInvalidNames() {
        assertEquals("passwd", sanitizeAttachmentFileName("../../etc/passwd"))
        assertEquals("b.pdf", sanitizeAttachmentFileName("a\\b.pdf"))
        assertEquals("secret.pdf", sanitizeAttachmentFileName("secret\u0000.pdf"))
        assertEquals("clean.txt", sanitizeAttachmentFileName("a/../b/clean\r\n.txt"))
        assertEquals("foo.bar.zip", sanitizeAttachmentFileName("foo..bar.zip"))
        assertEquals("attachment", sanitizeAttachmentFileName("../../.."))
        assertEquals("attachment", sanitizeAttachmentFileName(".."))
        assertEquals("attachment", sanitizeAttachmentFileName("."))
        assertEquals("attachment", sanitizeAttachmentFileName("   "))
        assertEquals("attachment.pdf", sanitizeAttachmentFileName("\u0000\r\n", mimeType = "application/pdf"))
        assertEquals("evil.sh", sanitizeAttachmentFileName(parseContentDispositionFileName("""attachment; filename="../../evil.sh"""")))
        assertEquals("测试文档.pdf", sanitizeAttachmentFileName(parseContentDispositionFileName("attachment; filename*=UTF-8''%E6%B5%8B%E8%AF%95%E6%96%87%E6%A1%A3.pdf")))

        listOf(
            "../../etc/passwd",
            "a\\b.pdf",
            "x\u0000y/../../z.pdf",
            "foo..bar..baz",
            "..",
            "/",
            "\\",
        ).forEach { raw ->
            val safe = sanitizeAttachmentFileName(raw)
            assertFalse("净化结果不得含 '..': $safe", ".." in safe)
            assertFalse("净化结果不得含 '/': $safe", '/' in safe)
            assertFalse("净化结果不得含 '\\': $safe", '\\' in safe)
            assertFalse("净化结果不得含 NUL: $safe", '\u0000' in safe)
        }
    }

    @Test
    fun authenticatedDownload_sendsCookiesAndUserAgent_sanitizesPathTraversal_andWritesToDownloadsDir() = runTest {
        val tempDownloads = createTempDirectory("e16-downloads").toFile()
        val server = MockWebServer()
        server.start()
        try {
            val payload = "PDF-ATTACHMENT-PAYLOAD-12345".encodeToByteArray()
            val capturedRequests = mutableListOf<RecordedRequest>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    capturedRequests += request
                    return MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "application/pdf")
                        .body(Buffer().write(payload))
                        .build()
                }
            }

            val authedClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("Cookie", "cdb_auth=TOKEN_XYZ; cf_clearance=CF_VALID")
                        .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0")
                        .build()
                    chain.proceed(req)
                }
                .build()

            val context = ApplicationProvider.getApplicationContext<Context>()
            val saver = AndroidAttachmentMediaStoreSaver(
                context = context,
                sdkInt = Build.VERSION_CODES.P,
                hasLegacyStoragePermission = { true },
                legacyDownloadsDirProvider = { tempDownloads },
            )
            val downloader = OkHttpAttachmentDownloader(
                callFactory = authedClient,
                saver = saver,
                hasLegacyStoragePermission = { true },
                sdkInt = Build.VERSION_CODES.P,
            )

            val url = server.url("/forum/attachment.php?aid=9001").toString()
            val result = downloader.download(url, "../../etc/passwd\u0000")
            assertTrue("预期下载成功，实际：$result", result is AttachmentDownloadResult.Success)

            val saved = (result as AttachmentDownloadResult.Success).saved
            assertEquals("passwd.pdf", saved.fileName)
            val savedFile = requireNotNull(saved.file)
            assertEquals("文件必须落在受控 Downloads 目录内", tempDownloads.canonicalFile, savedFile.canonicalFile.parentFile)
            assertArrayEquals(payload, savedFile.readBytes())

            val req = capturedRequests.single()
            assertEquals("cdb_auth=TOKEN_XYZ; cf_clearance=CF_VALID", req.headers["Cookie"])
            assertEquals("Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0", req.headers["User-Agent"])
        } finally {
            server.close()
            tempDownloads.deleteRecursively()
        }
    }

    @Test
    fun httpFailureAndApi28PermissionDenied_returnFailureAndWriteZeroFiles() = runTest {
        val tempDownloads = createTempDirectory("e16-downloads-fail").toFile()
        val server = MockWebServer()
        server.start()
        try {
            val requestCount = AtomicInteger(0)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestCount.incrementAndGet()
                    return MockResponse(code = 403, body = "Forbidden")
                }
            }

            val context = ApplicationProvider.getApplicationContext<Context>()
            val saverGranted = AndroidAttachmentMediaStoreSaver(
                context = context,
                sdkInt = Build.VERSION_CODES.P,
                hasLegacyStoragePermission = { true },
                legacyDownloadsDirProvider = { tempDownloads },
            )
            val downloaderHttpErr = OkHttpAttachmentDownloader(
                callFactory = OkHttpClient(),
                saver = saverGranted,
                hasLegacyStoragePermission = { true },
                sdkInt = Build.VERSION_CODES.P,
            )
            val failRes = downloaderHttpErr.download(server.url("/forum/attachment.php?aid=403").toString(), "spec.pdf")
            assertEquals(AttachmentDownloadResult.Failed, failRes)
            assertEquals("HTTP 失败不得写出任何文件", 0, tempDownloads.listFiles().orEmpty().size)

            // API 28 拒绝存储权限：0 网络请求，0 写入文件，返回 PermissionDenied
            val callsBeforeDenied = requestCount.get()
            val saverDenied = AndroidAttachmentMediaStoreSaver(
                context = context,
                sdkInt = Build.VERSION_CODES.P,
                hasLegacyStoragePermission = { false },
                legacyDownloadsDirProvider = { tempDownloads },
            )
            val downloaderDenied = OkHttpAttachmentDownloader(
                callFactory = OkHttpClient(),
                saver = saverDenied,
                hasLegacyStoragePermission = { false },
                sdkInt = Build.VERSION_CODES.P,
            )
            val deniedRes = downloaderDenied.download(server.url("/forum/attachment.php?aid=28").toString(), "spec.pdf")
            assertEquals(AttachmentDownloadResult.PermissionDenied, deniedRes)
            assertEquals("API 28 拒绝权限时不得发起网络请求", callsBeforeDenied, requestCount.get())
            assertEquals("API 28 拒绝权限时不得写出任何文件", 0, tempDownloads.listFiles().orEmpty().size)
        } finally {
            server.close()
            tempDownloads.deleteRecursively()
        }
    }

    @Test
    fun cancellingDownloadCoroutine_abortsHttpCallAndCleansUpPartialFile() = runBlocking {
        val tempDownloads = createTempDirectory("e16-downloads-cancel").toFile()
        val server = MockWebServer()
        server.start()
        try {
            val requestStarted = CountDownLatch(1)
            val releaseBody = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestStarted.countDown()
                    releaseBody.await(5, TimeUnit.SECONDS)
                    return MockResponse(code = 200, body = "late-data")
                }
            }

            val context = ApplicationProvider.getApplicationContext<Context>()
            val saver = AndroidAttachmentMediaStoreSaver(
                context = context,
                sdkInt = Build.VERSION_CODES.P,
                hasLegacyStoragePermission = { true },
                legacyDownloadsDirProvider = { tempDownloads },
            )
            val downloader = OkHttpAttachmentDownloader(
                callFactory = OkHttpClient(),
                saver = saver,
                hasLegacyStoragePermission = { true },
                sdkInt = Build.VERSION_CODES.P,
                ioDispatcher = Dispatchers.IO,
            )

            var caughtCancel = false
            val job = launch(Dispatchers.IO) {
                try {
                    downloader.download(server.url("/forum/attachment.php?aid=slow").toString(), "slow.zip")
                } catch (e: CancellationException) {
                    caughtCancel = true
                    throw e
                }
            }

            assertTrue("请求应已抵达 MockWebServer", requestStarted.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            releaseBody.countDown()

            assertTrue("取消下载协程必须抛出 CancellationException 中止下载", caughtCancel)
            assertEquals("取消后下载目录不得残留文件", 0, tempDownloads.listFiles().orEmpty().size)
        } finally {
            server.close()
            tempDownloads.deleteRecursively()
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun nextAppRootAttachmentClick_showsSavedSnackbarOnSuccessAndFailedSnackbarOnFailure() {
        val harness = MultiPaneTestHarness()
        val downloadCalls = mutableListOf<Pair<String, String?>>()
        var nextResult: AttachmentDownloadResult = AttachmentDownloadResult.Success(
            SavedAttachment("spec.pdf", "application/pdf", 128L, null),
        )
        val fakeDownloader = object : AttachmentDownloader {
            override suspend fun download(rawUrl: String, suggestedFileName: String?): AttachmentDownloadResult {
                downloadCalls += rawUrl to suggestedFileName
                return nextResult
            }
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides harness.parentOwner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread,
                    notifyUnreadCountFlow = harness.notifyUnread,
                    attachmentDownloader = fakeDownloader,
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                    loginOverlayRenderer = object : LoginOverlayRenderer {
                        @androidx.compose.runtime.Composable
                        override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
                            Box(Modifier.fillMaxWidth()) {
                                PostBodyRenderer(
                                    blocks = listOf(
                                        ContentBlock.Attachment(
                                            url = "https://www.4d4y.com/forum/attachment.php?aid=1001",
                                            title = "spec.pdf",
                                            description = "12 KB",
                                        ),
                                    ),
                                    floorPostId = "post_att_1",
                                    listState = rememberLazyListState(),
                                    onOpenImageViewer = {},
                                    onLinkClick = null,
                                )
                            }
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay() }
        composeRule.waitForIdle()

        // 1. 成功 -> 弹出「已保存到下载」
        composeRule.onNodeWithText("spec.pdf").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("https://www.4d4y.com/forum/attachment.php?aid=1001" to "spec.pdf"), downloadCalls)
        composeRule.onNodeWithText("已保存到下载").assertIsDisplayed()

        // 2. 失败 -> 弹出「下载失败」
        nextResult = AttachmentDownloadResult.Failed
        composeRule.onNodeWithText("spec.pdf").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("下载失败").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [28], qualifiers = "w411dp-h891dp-xhdpi")
    fun nextAppRootAttachmentClick_api28StoragePermissionDenied_doesNotDownloadAndShowsFailure() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val harness = MultiPaneTestHarness()
        val downloadCalls = AtomicInteger(0)
        val requestedPermissions = mutableListOf<String>()
        val fakeDownloader = object : AttachmentDownloader {
            override suspend fun download(rawUrl: String, suggestedFileName: String?): AttachmentDownloadResult {
                downloadCalls.incrementAndGet()
                return AttachmentDownloadResult.Success(SavedAttachment("should-not.pdf", "application/pdf", 1L, null))
            }
        }
        val denyingRegistryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    if (input is String) requestedPermissions += input
                    dispatchResult(requestCode, false)
                }
            }
        }

        composeRule.setContent {
            ProvideGlassEnvironment(
                GlassEnvironmentState(
                    reasons = setOf(GlassDegradationReason.UNSUPPORTED_API_LEVEL),
                    refractionSupported = false,
                ),
            ) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides harness.parentOwner,
                    LocalActivityResultRegistryOwner provides denyingRegistryOwner,
                ) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        attachmentDownloader = fakeDownloader,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                        loginOverlayRenderer = object : LoginOverlayRenderer {
                            @androidx.compose.runtime.Composable
                            override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
                                Box(Modifier.fillMaxWidth()) {
                                    PostBodyRenderer(
                                        blocks = listOf(
                                            ContentBlock.Attachment(
                                                url = "https://www.4d4y.com/forum/attachment.php?aid=2801",
                                                title = "legacy.pdf",
                                                description = "20 KB",
                                            ),
                                        ),
                                        floorPostId = "post_att_28",
                                        listState = rememberLazyListState(),
                                        onOpenImageViewer = {},
                                        onLinkClick = null,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("legacy.pdf").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), requestedPermissions)
        assertEquals("API 28 拒绝存储权限时不得调用下载器写文件", 0, downloadCalls.get())
        composeRule.onNodeWithText("下载失败").assertIsDisplayed()
    }
}
