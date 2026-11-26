package net.jejer.hipda.feature.routing

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.PaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.feature.common.SettingsGroup
import net.jejer.hipda.feature.imageviewer.AndroidImageViewerMediaActions
import net.jejer.hipda.feature.imageviewer.ImageViewerOverlay
import net.jejer.hipda.feature.imageviewer.OriginalDownloadState
import net.jejer.hipda.feature.imageviewer.OriginalImageDownloader
import net.jejer.hipda.feature.settings.SettingsCacheManager
import net.jejer.hipda.feature.settings.SettingsDetailPane
import net.jejer.hipda.feature.settings.SettingsErrorLogStore
import net.jejer.hipda.feature.settings.SettingsViewModel
import net.jejer.hipda.feature.threaddetail.body.PostBodyRenderer
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.service.NotificationScheduler
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * M7-3 验收 3：覆盖 API 28 / 33 / 35 / 36 的分区存储 MediaStore 附件下载与看图保存、
 * API 28 运行时 `WRITE_EXTERNAL_STORAGE` 拒权零写入与授权落盘，以及 API 36 下真实
 * `FileProvider.getUriForFile` 分享图片与脱敏错误日志分享。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class TargetSdk36MediaStoreAndFileProviderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        OkHttp.initialize(app)
        tempDir = createTempDirectory("m7-3-mediastore-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    @Config(sdk = [33, 35, 36])
    fun api33To36_attachmentDownload_usesMediaStoreWithZeroPermissionPromptsAndCleanupOnFailure() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        assertFalse("API 29+ 附件下载不得要求旧版存储权限", requiresLegacyDownloadPermission())

        val mediaStoreProvider = RecordingMediaStoreProvider(File(tempDir, "mediastore_backing").apply { mkdirs() }).apply {
            attachInfo(app, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        }
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, mediaStoreProvider)

        val server = MockWebServer()
        server.start()
        val client = OkHttpClient()
        try {
            val payload = "M7-3-PDF-BYTES-0123456789".encodeToByteArray()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "application/pdf")
                    .addHeader("Content-Disposition", """attachment; filename="../../report.pdf"""")
                    .body(Buffer().write(payload))
                    .build(),
            )
            val downloadUrl = server.url("/forum/attachment.php?aid=7788").toString()
            val saver = AndroidAttachmentMediaStoreSaver(context = app, ioDispatcher = Dispatchers.Unconfined)
            val downloader = OkHttpAttachmentDownloader(
                callFactory = client,
                saver = saver,
                hasLegacyStoragePermission = { false },
                ioDispatcher = Dispatchers.IO,
            )

            // 1. 端到端 UI 附件下载 -> 0 次存储权限请求，经 MediaStore.Downloads IS_PENDING 1 -> 0 落盘
            val harness = MultiPaneTestHarness()
            val permOwner = RecordingPermissionOwner(nextGrantResult = false)
            composeRule.setContent {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides harness.parentOwner,
                    LocalActivityResultRegistryOwner provides permOwner,
                ) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        attachmentDownloader = downloader,
                        loginOverlayRenderer = object : LoginOverlayRenderer {
                            @androidx.compose.runtime.Composable
                            override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
                                Box(Modifier.fillMaxWidth()) {
                                    PostBodyRenderer(
                                        blocks = listOf(
                                            ContentBlock.Attachment(
                                                url = downloadUrl,
                                                title = "report.pdf",
                                                description = "25 B",
                                            ),
                                        ),
                                        floorPostId = "post_m73_1",
                                        listState = rememberLazyListState(),
                                        onOpenImageViewer = {},
                                        onLinkClick = null,
                                    )
                                }
                            }
                        },
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay(LoginOverlayMode.Login) }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("report.pdf").assertIsDisplayed().performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                mediaStoreProvider.updates.any { it.second.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0 }
            }
            composeRule.waitForIdle()

            assertTrue("API 33-36 下载附件必须 0 次存储权限弹窗", permOwner.requestedPermissions.isEmpty())
            val dlInsert = mediaStoreProvider.inserts.first()
            assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dlInsert.first)
            assertEquals("report.pdf", dlInsert.second.getAsString(MediaStore.Downloads.DISPLAY_NAME))
            assertEquals(Environment.DIRECTORY_DOWNLOADS, dlInsert.second.getAsString(MediaStore.Downloads.RELATIVE_PATH))
            assertEquals(1, dlInsert.second.getAsInteger(MediaStore.Downloads.IS_PENDING))
            val dlUpdate = mediaStoreProvider.updates.first()
            assertEquals(0, dlUpdate.second.getAsInteger(MediaStore.Downloads.IS_PENDING))
            assertArrayEquals(payload, mediaStoreProvider.backingFileFor(dlUpdate.first).readBytes())

            // 2. 写入中途异常 -> 必须 delete 清理残留 IS_PENDING=1 条目
            val failedSave = runBlocking {
                saver.save("broken.pdf", "application/pdf") {
                    throw IllegalStateException("Simulated stream interruption")
                }
            }
            assertNull("写入中断应返回 null", failedSave)
            assertEquals("写入中断后应清理 MediaStore 残留 pending 行", 1, mediaStoreProvider.deletes.size)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.close()
        }
    }

    @Test
    @Config(sdk = [33, 35, 36])
    fun api33To36_imageViewerSave_usesMediaStoreWithZeroPermissionPromptsAndCleanupOnFailure() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        val mediaStoreProvider = RecordingMediaStoreProvider(File(tempDir, "mediastore_images").apply { mkdirs() }).apply {
            attachInfo(app, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        }
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, mediaStoreProvider)

        val sourcePng = writeSamplePng(File(tempDir, "viewer_photo.png"))
        val emptyImageDownloader = object : OriginalImageDownloader {
            override fun download(url: String): Flow<OriginalDownloadState> = emptyFlow()
        }
        val mediaActions = AndroidImageViewerMediaActions(
            context = app,
            originalDownloader = emptyImageDownloader,
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )
        val imagePermOwner = RecordingPermissionOwner(nextGrantResult = false)
        val snackbars = RecordingSnackbars()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides imagePermOwner,
                LocalPaneSnackbarController provides snackbars,
            ) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(urls = listOf("https://img.example.invalid/m73.png")),
                        onDismiss = {},
                        originalDownloader = object : OriginalImageDownloader {
                            override fun download(url: String): Flow<OriginalDownloadState> =
                                kotlinx.coroutines.flow.flowOf(OriginalDownloadState.Completed(sourcePng, "image/png"))
                        },
                        mediaActions = object : net.jejer.hipda.feature.imageviewer.ImageViewerMediaActions {
                            override suspend fun saveImage(url: String, cachedFile: File?): Boolean =
                                mediaActions.saveImage(url, sourcePng)
                            override suspend fun shareImage(url: String, cachedFile: File?): Boolean = false
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("保存图片").performClick()
        composeRule.waitForIdle()

        assertTrue("API 33-36 保存图片必须 0 次存储权限弹窗", imagePermOwner.requestedPermissions.isEmpty())
        assertTrue("保存成功应提示已保存到相册：${snackbars.messages}", "已保存到相册" in snackbars.messages)
        val imgInsert = mediaStoreProvider.inserts.last()
        assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imgInsert.first)
        assertEquals("${Environment.DIRECTORY_PICTURES}/HiPDA", imgInsert.second.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals(1, imgInsert.second.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        val imgUpdate = mediaStoreProvider.updates.last()
        assertEquals(0, imgUpdate.second.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertTrue(mediaStoreProvider.backingFileFor(imgUpdate.first).length() > 0L)
    }

    @Test
    @Config(sdk = [28])
    fun api28_attachmentAndImageSave_deniedWritesZeroFilesAndZeroNetwork_grantedSavesNormally() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        assertTrue("API 28 附件下载必须走旧版存储权限门禁", requiresLegacyDownloadPermission())

        val legacyDownloadsDir = File(tempDir, "legacy_downloads").apply { mkdirs() }
        val legacyPicturesDir = File(
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "HiPDA",
        )
        legacyPicturesDir.deleteRecursively()

        val server = MockWebServer()
        server.start()
        val client = OkHttpClient()
        try {
            val payload = "API28-PAYLOAD".encodeToByteArray()
            val downloadUrl = server.url("/forum/attachment.php?aid=28").toString()
            val saver = AndroidAttachmentMediaStoreSaver(
                context = app,
                legacyDownloadsDirProvider = { legacyDownloadsDir },
                ioDispatcher = Dispatchers.Unconfined,
            )
            val downloader = OkHttpAttachmentDownloader(okHttpClient = client, saver = saver, context = app)

            // 1. 直接保存拒权兜底：附件 & 图片均 0 写入
            val directAttDenied = runBlocking {
                saver.save("denied.pdf", "application/pdf") { out ->
                    out.write(payload)
                    payload.size.toLong()
                }
            }
            assertNull("API 28 无存储权限直接保存附件必须返回 null", directAttDenied)
            assertTrue("API 28 无存储权限下载目录必须 0 写入", legacyDownloadsDir.listFiles().orEmpty().isEmpty())

            val sourcePng = writeSamplePng(File(tempDir, "api28_photo.png"))
            val emptyImageDownloader = object : OriginalImageDownloader {
                override fun download(url: String): Flow<OriginalDownloadState> = emptyFlow()
            }
            val mediaActions = AndroidImageViewerMediaActions(
                context = app,
                originalDownloader = emptyImageDownloader,
                ioDispatcher = Dispatchers.Unconfined,
                mainDispatcher = Dispatchers.Unconfined,
            )
            val directImgDenied = runBlocking { mediaActions.saveImage("https://img.example.invalid/a28.png", sourcePng) }
            assertFalse("API 28 无存储权限直接保存图片必须返回 false", directImgDenied)
            assertTrue("API 28 无存储权限相册目录必须 0 写入", legacyPicturesDir.listFiles().orEmpty().isEmpty())

            // 2. UI 层触发附件下载被拒 -> 发起 WRITE_EXTERNAL_STORAGE 请求、0 网络请求、0 文件写入、弹「下载失败」
            val harness = MultiPaneTestHarness()
            val permOwner = RecordingPermissionOwner(nextGrantResult = false)
            composeRule.setContent {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides harness.parentOwner,
                    LocalActivityResultRegistryOwner provides permOwner,
                ) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        attachmentDownloader = downloader,
                        loginOverlayRenderer = object : LoginOverlayRenderer {
                            @androidx.compose.runtime.Composable
                            override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
                                Box(Modifier.fillMaxWidth()) {
                                    PostBodyRenderer(
                                        blocks = listOf(ContentBlock.Attachment(downloadUrl, "legacy.pdf", "13 B")),
                                        floorPostId = "post_m73_28",
                                        listState = rememberLazyListState(),
                                        onOpenImageViewer = {},
                                        onLinkClick = null,
                                    )
                                }
                            }
                        },
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                    )
                }
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay(LoginOverlayMode.Login) }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("legacy.pdf").performClick()
            composeRule.waitForIdle()
            assertEquals(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), permOwner.requestedPermissions)
            assertEquals("API 28 拒权后不得发起网络请求", 0, server.requestCount)
            assertTrue(legacyDownloadsDir.listFiles().orEmpty().isEmpty())
            composeRule.onNodeWithText("下载失败").assertIsDisplayed()

            // 3. 授予 WRITE_EXTERNAL_STORAGE -> 附件与图片均正常落盘
            shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/pdf").body(Buffer().write(payload)).build())
            val grantedAtt = runBlocking { downloader.download(downloadUrl, "legacy.pdf") }
            assertTrue("API 28 授权后附件应保存成功：$grantedAtt", grantedAtt is AttachmentDownloadResult.Success)
            val savedFile = (grantedAtt as AttachmentDownloadResult.Success).saved.file
            assertNotNull(savedFile)
            assertArrayEquals(payload, savedFile!!.readBytes())

            val grantedImg = runBlocking { mediaActions.saveImage("https://img.example.invalid/a28.png", sourcePng) }
            assertTrue("API 28 授权后图片应保存成功", grantedImg)
            assertTrue("API 28 授权后应写入 Pictures/HiPDA", legacyPicturesDir.listFiles().orEmpty().isNotEmpty())
        } finally {
            legacyPicturesDir.deleteRecursively()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.close()
        }
    }

    @Test
    @Config(sdk = [36])
    fun api36_realFileProviderImageShareAndSanitizedErrorLogShare_workEndToEnd() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sourcePng = writeSamplePng(File(tempDir, "fp_share_api36.png"))

        // 1. 使用生产默认 FileProvider.getUriForFile（不注入假 uriProvider）验证真实 external-cache-path 授权
        shadowOf(app).clearNextStartedActivities()
        val mediaActions = AndroidImageViewerMediaActions(
            context = app,
            originalDownloader = object : OriginalImageDownloader {
                override fun download(url: String): Flow<OriginalDownloadState> = emptyFlow()
            },
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
        )
        val shared = runBlocking { mediaActions.shareImage("https://img.example.invalid/fp36.png", cachedFile = sourcePng) }
        assertTrue("API 36 真实 FileProvider 图片分享必须成功", shared)

        val chooser = requireNotNull(shadowOf(app).nextStartedActivity) { "必须发出系统分享 Chooser" }
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue((chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        val clipUri = requireNotNull(chooser.clipData?.getItemAt(0)?.uri)
        assertEquals("content", clipUri.scheme)
        assertEquals("${app.packageName}.provider", clipUri.authority)

        @Suppress("DEPRECATION")
        val sendIntent = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, sendIntent.action)
        assertEquals("image/png", sendIntent.type)
        assertTrue((sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        @Suppress("DEPRECATION")
        assertEquals(clipUri, sendIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))

        // 2. API 36 下错误日志分享必须走脱敏通道且不含任何凭据/UID 泄漏
        shadowOf(app).clearNextStartedActivities()
        val rawLog = "CrashOnApi36\nCookie: cdb_auth=API36_SECRET_TOKEN\nspace.php?uid=998877"
        val settingsVm = SettingsViewModel(
            settingsRepository = DummySettingsRepo(),
            notificationScheduler = object : NotificationScheduler {
                override fun syncSchedule(notificationsEnabled: Boolean) = Unit
                override suspend fun syncFromSettings() = Unit
                override fun schedule() = Unit
                override fun cancel() = Unit
            },
            cacheManager = object : SettingsCacheManager {
                override suspend fun getImageCacheSizeBytes(): Long = 0L
                override suspend fun clearImageCache(): Long = 0L
                override suspend fun clearWebCache(): Long = 0L
            },
            errorLogStore = SettingsErrorLogStore { rawLog },
        )
        composeRule.setContent {
            HiTheme {
                SettingsDetailPane(
                    group = SettingsGroup.About,
                    viewModel = settingsVm,
                    onOpenSystemNotificationSettings = { true },
                    showBack = false,
                    onBack = null,
                    onOpenThread = {},
                    blacklistContent = { _, _ -> },
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("分享").performClick()
        composeRule.waitForIdle()

        val logChooser = requireNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, logChooser.action)
        @Suppress("DEPRECATION")
        val logSend = requireNotNull(logChooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, logSend.action)
        assertEquals("text/plain", logSend.type)
        val sharedText = logSend.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(sharedText.contains("CrashOnApi36"))
        assertFalse("脱敏日志分享不得包含 Cookie", sharedText.contains("API36_SECRET_TOKEN"))
        assertFalse("脱敏日志分享不得包含 UID", sharedText.contains("998877"))
    }

    private fun writeSamplePng(target: File): File {
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
        FileOutputStream(target).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return target
    }

    private class RecordingMediaStoreProvider(private val backingDir: File) : ContentProvider() {
        private val nextId = AtomicLong(1L)
        private val filesByUri = java.util.concurrent.ConcurrentHashMap<Uri, File>()
        val inserts = CopyOnWriteArrayList<Pair<Uri, ContentValues>>()
        val updates = CopyOnWriteArrayList<Pair<Uri, ContentValues>>()
        val deletes = CopyOnWriteArrayList<Uri>()

        fun backingFileFor(uri: Uri): File = requireNotNull(filesByUri[uri])

        override fun onCreate(): Boolean = true

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = nextId.getAndIncrement()
            val rowUri = ContentUris.withAppendedId(uri, id)
            val copy = ContentValues(values ?: ContentValues())
            inserts += uri to copy
            val file = File(backingDir, "row_$id.bin").apply { createNewFile() }
            filesByUri[rowUri] = file
            return rowUri
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = requireNotNull(filesByUri[uri]) { "Unknown MediaStore URI: $uri" }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            updates += uri to ContentValues(values ?: ContentValues())
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deletes += uri
            filesByUri.remove(uri)?.delete()
            return 1
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
    }

    private class RecordingPermissionOwner(var nextGrantResult: Boolean) : ActivityResultRegistryOwner {
        val requestedPermissions = mutableListOf<String>()
        override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                if (input is String) requestedPermissions += input
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, nextGrantResult as O)
            }
        }
    }

    private class RecordingSnackbars : PaneSnackbarController {
        val messages = mutableListOf<String>()
        override suspend fun show(message: String, target: PaneSnackbarTarget, actionLabel: String?, onAction: (() -> Unit)?) {
            messages += message
        }
    }

    private class DummySettingsRepo : SettingsRepository {
        val state = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = state
        override suspend fun setThemeMode(themeMode: ThemeMode) = state.update { it.copy(themeMode = themeMode) }
        override suspend fun setPureBlack(enabled: Boolean) = state.update { it.copy(pureBlack = enabled) }
        override suspend fun setThemeSeed(themeSeed: String) = state.update { it.copy(themeSeed = themeSeed) }
        override suspend fun setFontStep(step: Int) = state.update { it.copy(fontStep = step) }
        override suspend fun setLineSpacing(lineSpacing: LineSpacing) = state.update { it.copy(lineSpacing = lineSpacing) }
        override suspend fun setFollowedForums(forumIds: List<Int>) = state.update { it.copy(followedForums = forumIds) }
        override suspend fun setShowStickyThreads(show: Boolean) = state.update { it.copy(showStickyThreads = show) }
        override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) = state.update { it.copy(sortByPostTime = it.sortByPostTime + (fid to sortByPostTime)) }
        override suspend fun setTailEnabled(enabled: Boolean) = state.update { it.copy(tailEnabled = enabled) }
        override suspend fun setTailText(text: String) = state.update { it.copy(tailText = text) }
        override suspend fun setTailUrl(url: String) = state.update { it.copy(tailUrl = url) }
        override suspend fun setNotificationsEnabled(enabled: Boolean) = state.update { it.copy(notificationsEnabled = enabled) }
        override suspend fun setSilentModeEnabled(enabled: Boolean) = state.update { it.copy(silentModeEnabled = enabled) }
        override suspend fun setSilentBeginMinutes(minutes: Int) = state.update { it.copy(silentBeginMinutes = minutes) }
        override suspend fun setSilentEndMinutes(minutes: Int) = state.update { it.copy(silentEndMinutes = minutes) }
        override suspend fun setForumServer(server: String) = state.update { it.copy(forumServer = server) }
        override suspend fun setImageCacheLimitMb(limitMb: Int) = state.update { it.copy(imageCacheLimitMb = limitMb) }
        override suspend fun setPaneSplitPortrait(split: PaneSplit) = state.update { it.copy(paneSplitPortrait = split) }
        override suspend fun setPaneSplitLandscape(split: PaneSplit) = state.update { it.copy(paneSplitLandscape = split) }
        override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) = state.update { it.copy(sidebarCollapsedPortrait = collapsed) }
        override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) = state.update { it.copy(sidebarCollapsedLandscape = collapsed) }
    }
}
