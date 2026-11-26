package net.jejer.hipda.feature.routing

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.decode.DataSource
import coil3.request.SuccessResult
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.AttachmentDownloadHandler
import net.jejer.hipda.feature.common.LocalAttachmentDownloadHandler
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.PaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.feature.imageviewer.AndroidImageViewerMediaActions
import net.jejer.hipda.feature.imageviewer.FakeOriginalImageDownloader
import net.jejer.hipda.feature.imageviewer.ImageViewerOverlay
import net.jejer.hipda.feature.imageviewer.ViewerTestImageLoader
import net.jejer.hipda.feature.imageviewer.buildShareChooserIntent
import net.jejer.hipda.feature.imageviewer.requiresLegacyStoragePermission
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

/**
 * 独立验证探针 2（M7-3 MediaStore 与 FileProvider 矩阵 28 / 33 / 35 / 36）：
 * - NextAppRoot 附件下载 + OkHttpAttachmentDownloader + AndroidAttachmentMediaStoreSaver：
 *   API 29+ 免权限直写 MediaStore.Downloads（IS_PENDING 1 -> 0），流失败删除 pending URI；
 *   API 28 申请 WRITE_EXTERNAL_STORAGE，拒绝零 HTTP 请求零写入，授予写兼容 Downloads 目录；
 * - ImageViewerOverlay「保存图片」+ AndroidImageViewerMediaActions：
 *   API 29+ 免权限写入 MediaStore.Images.Media（IS_PENDING 1 -> 0），失败删除 pending URI；
 *   API 28 申请 WRITE_EXTERNAL_STORAGE，拒绝零写入，授予写 Pictures/HiPDA；
 * - FileProvider 分享在 Target SDK 36 下生成 content://net.jejer.hipda.next.provider/... 与 ClipData + READ_URI 授权。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [28, 33, 35, 36], qualifiers = "w411dp-h891dp-xhdpi")
class M73MediaStoreProbeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var tempDir: File
    private lateinit var legacyDownloadsDir: File
    private lateinit var mediaBackingDir: File
    private lateinit var spyMediaProvider: SpyMediaStoreContentProvider
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        tempDir = createTempDirectory("m73-mediastore-probe").toFile()
        legacyDownloadsDir = File(tempDir, "legacy_downloads").apply { mkdirs() }
        mediaBackingDir = File(tempDir, "media_backing").apply { mkdirs() }
        spyMediaProvider = SpyMediaStoreContentProvider(mediaBackingDir).apply {
            attachInfo(
                app,
                ProviderInfo().apply {
                    authority = MediaStore.AUTHORITY
                    exported = true
                },
            )
        }
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, spyMediaProvider)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        // Robolectric 每个测试方法都会重新分配 ShadowEnvironment 外部存储临时目录；
        // 清理 androidx.core.content.FileProvider.sCache，避免同进程后续测试命中旧 temp 路径。
        runCatching {
            val cacheField = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
            cacheField.isAccessible = true
            (cacheField.get(null) as? MutableMap<*, *>)?.clear()
        }
        tempDir.deleteRecursively()
        @Suppress("DEPRECATION")
        val picturesHiPda = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "HiPDA",
        )
        if (picturesHiPda.exists()) {
            picturesHiPda.deleteRecursively()
        }
    }

    @Test
    fun attachmentDownload_nextAppRoot_and_downloader_respectApi28Vs29PlusMatrix() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P, requiresLegacyDownloadPermission())
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        // Android 9 及以下 ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED) 要求宿主拥有签名级内部权限；
        // 构建产物 merged manifest 已声明 <permission>/<uses-permission>，Robolectric 默认不自动授予签名权限。
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")

        val payload = "probe-attachment-payload-2026".toByteArray()
        val saver = AndroidAttachmentMediaStoreSaver(
            context = app,
            sdkInt = Build.VERSION.SDK_INT,
            legacyDownloadsDirProvider = { legacyDownloadsDir },
        )
        val downloader = OkHttpAttachmentDownloader(
            callFactory = OkHttpClient(),
            saver = saver,
            hasLegacyStoragePermission = {
                app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            },
            sdkInt = Build.VERSION.SDK_INT,
        )

        val launchedPermissions = CopyOnWriteArrayList<String>()
        var nextGrantResult = false
        var capturedHandler: AttachmentDownloadHandler? = null

        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                if (contract is ActivityResultContracts.RequestPermission) {
                    val perm = input as String
                    launchedPermissions += perm
                    if (nextGrantResult) shadowOf(app).grantPermissions(perm)
                    else shadowOf(app).denyPermissions(perm)
                    @Suppress("UNCHECKED_CAST")
                    dispatchResult(requestCode, nextGrantResult)
                }
            }
        }
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val harness = MultiPaneTestHarness()
        val captureRenderer = object : LoginOverlayRenderer {
            @Composable
            override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
                capturedHandler = LocalAttachmentDownloadHandler.current
                Box(Modifier.fillMaxSize()) { Text("附件捕获占位") }
            }
        }

        composeRule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides registryOwner,
                LocalViewModelStoreOwner provides harness.parentOwner,
            ) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread,
                    notifyUnreadCountFlow = harness.notifyUnread,
                    loginOverlayRenderer = captureRenderer,
                    attachmentDownloader = downloader,
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay(LoginOverlayMode.Login) }
        composeRule.waitForIdle()
        val handler = requireNotNull(capturedHandler) { "NextAppRoot 必须提供 LocalAttachmentDownloadHandler" }
        composeRule.runOnIdle { harness.hostViewModel.dismissLoginOverlay() }
        composeRule.waitForIdle()

        val downloadUrl = server.url("/forum/attachment.php?aid=42").toString()

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // API 28 拒绝 WRITE_EXTERNAL_STORAGE -> 发起了权限申请、0 HTTP 请求、0 文件写入
            nextGrantResult = false
            composeRule.runOnIdle { handler.download(downloadUrl, "manual.pdf") }
            composeRule.waitForIdle()
            assertEquals(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), launchedPermissions.toList())
            assertEquals("API 28 拒绝权限必须保证 0 HTTP 请求", 0, server.requestCount)
            assertTrue("API 28 拒绝权限后下载目录必须保持空", legacyDownloadsDir.listFiles().orEmpty().isEmpty())

            // 直接调用 Downloader 未授权同样必须拦截在网络前
            val directDenied = runBlocking { downloader.download(downloadUrl, "manual.pdf") }
            assertEquals(AttachmentDownloadResult.PermissionDenied, directDenied)
            assertEquals(0, server.requestCount)

            // API 28 授予 WRITE_EXTERNAL_STORAGE 后重试 -> 成功写入兼容目录
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/pdf").body(Buffer().write(payload)).build())
            nextGrantResult = true
            composeRule.runOnIdle { handler.download(downloadUrl, "manual.pdf") }
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("已保存到下载").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, server.requestCount)
            val savedFile = legacyDownloadsDir.listFiles().orEmpty().singleOrNull()
            assertNotNull("API 28 授权后必须在 Downloads 写入附件文件", savedFile)
            assertEquals(String(payload), savedFile!!.readText())
        } else {
            // API 33 / 35 / 36 即使拒绝 WRITE_EXTERNAL_STORAGE：0 权限弹窗，直写 MediaStore.Downloads
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/pdf").body(Buffer().write(payload)).build())
            composeRule.runOnIdle { handler.download(downloadUrl, "manual.pdf") }
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("已保存到下载").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue("API ${Build.VERSION.SDK_INT} 不得申请 WRITE_EXTERNAL_STORAGE", launchedPermissions.isEmpty())
            assertEquals(1, server.requestCount)
            assertEquals("MediaStore.Downloads 应插入 1 条记录", 1, spyMediaProvider.insertRecords.size)
            val record = spyMediaProvider.insertRecords.single()
            assertEquals(1, record.initialValues.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
            assertTrue(record.backingFile.exists())
            assertEquals(String(payload), record.backingFile.readText())
            assertEquals("IS_PENDING 必须在写入后翻为 0", 0, spyMediaProvider.lastUpdatedIsPending(record.uri))

            // 模拟 MediaStore 打开/写入异常（如 IO failure / disk error）-> 自动删除 pending URI 零孤儿残留
            spyMediaProvider.failOnOpen = true
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/pdf")
                    .body(Buffer().write("broken-stream".toByteArray()))
                    .build(),
            )
            val failed = runBlocking {
                downloader.download(server.url("/forum/attachment.php?aid=99").toString(), "broken.pdf")
            }
            assertEquals(AttachmentDownloadResult.Failed, failed)
            assertEquals("失败附件必须 delete 对应 pending URI", 1, spyMediaProvider.deletedUris.size)
            assertEquals(1, spyMediaProvider.liveRowCount) // 仅首条成功的有效记录存在

            // HTTP 错误码（500）同样返回 Failed 且 0 写入 MediaStore
            spyMediaProvider.failOnOpen = false
            server.enqueue(MockResponse.Builder().code(500).build())
            val httpFailed = runBlocking {
                downloader.download(server.url("/forum/attachment.php?aid=100").toString(), "err.pdf")
            }
            assertEquals(AttachmentDownloadResult.Failed, httpFailed)
            assertEquals(1, spyMediaProvider.liveRowCount)
        }
    }

    @Test
    fun imageViewerSave_respectsApi28Vs29PlusMatrix_andCleansPendingUriOnFailure() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(Build.VERSION.SDK_INT <= Build.VERSION_CODES.P, requiresLegacyStoragePermission())
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        val pngBytes = ByteArray(128) { (it + 1).toByte() }
        val cachedFile = File(tempDir, "cached_viewer.png").apply { writeBytes(pngBytes) }
        val origDownloader = FakeOriginalImageDownloader { emptyFlow() }
        val realActions = AndroidImageViewerMediaActions(
            context = composeRule.activity,
            originalDownloader = origDownloader,
        )
        val snackbars = ProbeSnackbarController()
        val launchedPermissions = CopyOnWriteArrayList<String>()
        var nextGrantResult = false

        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                if (contract is ActivityResultContracts.RequestPermission) {
                    val perm = input as String
                    launchedPermissions += perm
                    if (nextGrantResult) shadowOf(composeRule.activity).grantPermissions(perm)
                    else shadowOf(composeRule.activity).denyPermissions(perm)
                    @Suppress("UNCHECKED_CAST")
                    dispatchResult(requestCode, nextGrantResult)
                }
            }
        }
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        val imageUrl = "https://img.example.invalid/photo_36.png"
        val loader = ViewerTestImageLoader { req ->
            SuccessResult(ColorImage(0xFF123456.toInt(), 300, 200), req, DataSource.MEMORY_CACHE)
        }

        // 先通过 cachedFile 直接预存到 cacheDir 或让 ImageViewerOverlay / Actions 都走真实链路：
        // 这里先把 cachedFile 内容拷贝到 OkHttpOriginalImageDownloader 缓存约定路径或直接用 FakeOriginalImageDownloader 返回？
        // 注：ImageViewerOverlay 点击「保存图片」时默认 cachedFile=null，若未点原图则会拉取 originalDownloader！
        // 让 fake originalDownloader 直接产出 Completed(cachedFile, "image/png")。
        val completingOriginalDownloader = object : net.jejer.hipda.feature.imageviewer.OriginalImageDownloader {
            override fun download(url: String): Flow<net.jejer.hipda.feature.imageviewer.OriginalDownloadState> =
                kotlinx.coroutines.flow.flowOf(
                    net.jejer.hipda.feature.imageviewer.OriginalDownloadState.Completed(cachedFile, "image/png"),
                )
        }
        val wiredActions = AndroidImageViewerMediaActions(
            context = composeRule.activity,
            originalDownloader = completingOriginalDownloader,
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalHiImageLoader provides loader,
                LocalPaneSnackbarController provides snackbars,
                LocalActivityResultRegistryOwner provides registryOwner,
            ) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = net.jejer.hipda.feature.common.ImageViewerLaunchArgs(urls = listOf(imageUrl)),
                        onDismiss = {},
                        originalDownloader = completingOriginalDownloader,
                        mediaActions = wiredActions,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val hiPdaPictures = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "HiPDA")

            nextGrantResult = false
            composeRule.onNodeWithText("保存图片").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) { "保存失败" in snackbars.messages }
            assertEquals(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), launchedPermissions.toList())
            assertTrue("API 28 拒绝权限零文件写入", hiPdaPictures.listFiles().orEmpty().isEmpty())

            nextGrantResult = true
            composeRule.onNodeWithText("保存图片").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) { "已保存到相册" in snackbars.messages }
            assertTrue("API 28 授权后应写入 Pictures/HiPDA", hiPdaPictures.listFiles().orEmpty().size == 1)
            assertTrue("API 28 不应写 MediaStore", spyMediaProvider.insertRecords.isEmpty())
        } else {
            composeRule.onNodeWithText("保存图片").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) { "已保存到相册" in snackbars.messages }
            assertTrue("API ${Build.VERSION.SDK_INT} 保存图片不得请求 WRITE_EXTERNAL_STORAGE", launchedPermissions.isEmpty())
            assertEquals(1, spyMediaProvider.insertRecords.size)
            val record = spyMediaProvider.insertRecords.single()
            assertEquals(1, record.initialValues.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
            assertEquals(0, spyMediaProvider.lastUpdatedIsPending(record.uri))
            assertEquals(pngBytes.size.toLong(), record.backingFile.length())

            // 失败分支：MediaStore openFile 抛错 -> 必须 delete pending URI 零残留
            spyMediaProvider.failOnOpen = true
            composeRule.onNodeWithText("保存图片").performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) { "保存失败" in snackbars.messages }
            assertEquals("保存失败时必须删除待处理 URI", 1, spyMediaProvider.deletedUris.size)
            assertEquals(1, spyMediaProvider.liveRowCount)
        }
    }

    @Test
    @Config(sdk = [36])
    fun fileProviderShare_api36_usesAppAuthorityWithReadGrantAndClipData() {
        val activity = composeRule.activity
        val pngBytes = ByteArray(64) { 0x3C }
        val cachedFile = File(tempDir, "share_source.png").apply { writeBytes(pngBytes) }
        val shareDir = activity.externalCacheDir ?: File(tempDir, "ext_cache").apply { mkdirs() }
        val shareFile = File(shareDir, "share_image_probe.png").apply {
            parentFile?.mkdirs()
            writeBytes(pngBytes)
        }

        val uri = androidx.core.content.FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.provider",
            shareFile,
        )
        assertEquals("content", uri.scheme)
        assertEquals("net.jejer.hipda.next.provider", uri.authority)

        val chooser = buildShareChooserIntent(activity, uri, "image/png")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue((chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        @Suppress("DEPRECATION")
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(target)
        assertEquals(Intent.ACTION_SEND, target!!.action)
        assertEquals("image/png", target.type)
        assertTrue((target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertNotNull(target.clipData)
        assertEquals(uri, target.clipData!!.getItemAt(0).uri)
        @Suppress("DEPRECATION")
        assertEquals(uri, target.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))

        // 真实 AndroidImageViewerMediaActions.shareImage 调用检查（主线程测试用 Main.immediate 避免 runBlocking 阻塞 Looper）
        val originalDownloader = object : net.jejer.hipda.feature.imageviewer.OriginalImageDownloader {
            override fun download(url: String): Flow<net.jejer.hipda.feature.imageviewer.OriginalDownloadState> =
                kotlinx.coroutines.flow.flowOf(
                    net.jejer.hipda.feature.imageviewer.OriginalDownloadState.Completed(cachedFile, "image/png"),
                )
        }
        val mediaActions = AndroidImageViewerMediaActions(
            context = activity,
            originalDownloader = originalDownloader,
            ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
            mainDispatcher = kotlinx.coroutines.Dispatchers.Main.immediate,
        )
        val shared = runBlocking { mediaActions.shareImage("https://img.example.invalid/pic.png") }
        assertTrue(shared)
        val startedChooser = shadowOf(activity).nextStartedActivity
        assertNotNull(startedChooser)
        assertEquals(Intent.ACTION_CHOOSER, startedChooser.action)
        @Suppress("DEPRECATION")
        val wrapped = startedChooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(wrapped)
        @Suppress("DEPRECATION")
        val streamUri = wrapped!!.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals("content", streamUri?.scheme)
        assertEquals("net.jejer.hipda.next.provider", streamUri?.authority)
        assertFalse(streamUri.toString().startsWith("file://"))
    }

    private class ProbeSnackbarController : PaneSnackbarController {
        val messages = CopyOnWriteArrayList<String>()
        override suspend fun show(
            message: String,
            target: PaneSnackbarTarget,
            actionLabel: String?,
            onAction: (() -> Unit)?,
        ) {
            messages += message
        }
    }

    /** 基于真实临时文件的 Spy MediaStore ContentProvider：追踪 IS_PENDING 生命周期、写入与 delete 清理。 */
    private class SpyMediaStoreContentProvider(
        private val rootDir: File,
    ) : ContentProvider() {
        data class InsertRecord(
            val uri: Uri,
            val initialValues: ContentValues,
            val backingFile: File,
        )

        private val nextId = AtomicInteger(1)
        val insertRecords = CopyOnWriteArrayList<InsertRecord>()
        val deletedUris = CopyOnWriteArrayList<Uri>()
        private val rows = mutableMapOf<Uri, Pair<ContentValues, File>>()
        @Volatile
        var failOnOpen: Boolean = false

        val liveRowCount: Int
            @Synchronized get() = rows.size

        @Synchronized
        fun lastUpdatedIsPending(uri: Uri): Int? = rows[uri]?.first?.getAsInteger(MediaStore.MediaColumns.IS_PENDING)

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? = null

        @Synchronized
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = nextId.getAndIncrement()
            val itemUri = Uri.withAppendedPath(uri, id.toString())
            val copied = ContentValues(values ?: ContentValues())
            val file = File(rootDir, "media_$id.bin")
            rows[itemUri] = copied to file
            insertRecords += InsertRecord(itemUri, ContentValues(copied), file)
            return itemUri
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (failOnOpen) {
                throw FileNotFoundException("simulated MediaStore open failure for $uri")
            }
            val file = synchronized(this) { rows[uri]?.second }
                ?: throw FileNotFoundException("Unknown URI $uri")
            return ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
            )
        }

        @Synchronized
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val existing = rows[uri] ?: return 0
            if (values != null) {
                existing.first.putAll(values)
            }
            return 1
        }

        @Synchronized
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val removed = rows.remove(uri) ?: return 0
            removed.second.delete()
            deletedUris += uri
            return 1
        }
    }
}
