package net.jejer.hipda.feature.imageviewer

import android.Manifest
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E14 修复轮 1 回归测试（覆盖 R-E14 S1 / S2 / S3 与 API ≤ 28 动态存储权限）：
 * - S1：超大原图经 Coil 按屏幕尺寸 2×（硬封顶 4096px）解码，防 Canvas too large 崩溃；
 * - S2：分享走 externalCacheDir、自动清理旧 share_* 缓存，并在 ACTION_SEND 与 Chooser 双设 ClipData + FLAG_GRANT_READ_URI_PERMISSION；
 * - S3：长图带源边界入场平滑重定目标无末帧跳变，以及旋转/尺寸变化后长图滚动与放大平移实时钳制；
 * - API ≤ 28：保存时动态申请 WRITE_EXTERNAL_STORAGE，拒绝提示“保存失败”不写相册，授予后正常保存；API 29+ 免申请。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ImageViewerHardeningRegressionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDirectory("e14-hardening-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun imageMatcher(pageIndicator: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(pageIndicator))

    // ==================== R-E14 S1：超大原图经 Coil 限尺寸解码 ====================

    @Test
    fun s1_oversizedOriginalImage_decodedViaCoilWithinScreenAnd4096Bounds() {
        // 1. 纯数学边界校验：常规视口 2×，超大视口硬封顶 4096px
        assertEquals(IntSize(1644, 3564), computeOriginalDecodeBounds(822, 1782))
        assertEquals(IntSize(4096, 4096), computeOriginalDecodeBounds(3000, 5000))

        // 2. 真实超大 PNG（4800×3200）落地磁盘，经真实 Coil 3 ImageLoader + computeOriginalDecodeBounds 解码必须降采样至安全上限内
        val context = ApplicationProvider.getApplicationContext<Application>()
        val hugeFile = File(tempDir, "huge_4800x3200.png")
        val hugeBitmap = Bitmap.createBitmap(4800, 3200, Bitmap.Config.RGB_565).apply { eraseColor(0xFF2A9D8F.toInt()) }
        FileOutputStream(hugeFile).use { hugeBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        hugeBitmap.recycle()

        val realLoader = ImageLoader.Builder(context).build()
        try {
            val bounds = computeOriginalDecodeBounds(822, 1782)
            val realResult = runBlocking {
                realLoader.execute(
                    ImageRequest.Builder(context)
                        .data(hugeFile)
                        .size(Dimension.Pixels(bounds.width), Dimension.Pixels(bounds.height))
                        .scale(Scale.FIT)
                        .precision(Precision.INEXACT)
                        .build(),
                )
            }
            assertTrue("真实 Coil 解码超大原图必须成功，实际 $realResult", realResult is SuccessResult)
            val decoded = (realResult as SuccessResult).image
            assertTrue(
                "超大 4800×3200 原图解码后必须受屏幕 2× 限制（<= 1644×3564 且 <= 4096），实际 ${decoded.width}×${decoded.height}",
                decoded.width in 1..bounds.width && decoded.height in 1..bounds.height &&
                    decoded.width <= ImageViewerMotionSpec.MAX_ORIGINAL_DECODE_DIMENSION_PX &&
                    decoded.height <= ImageViewerMotionSpec.MAX_ORIGINAL_DECODE_DIMENSION_PX,
            )
        } finally {
            realLoader.shutdown()
        }

        // 3. 端到端 Overlay 校验：原图下载完成后必须经 Coil ImageLoader 解码且携带有限 Pixels 尺寸约束（而非无限制 BitmapFactory）
        val hdUrl = "https://img.example.invalid/s1_huge.png"
        val loader = ViewerTestImageLoader { request ->
            SuccessResult(createViewerPhotoBitmap(800, 600).asImage(), request, DataSource.DISK)
        }
        val stream = MutableSharedFlow<OriginalDownloadState>(extraBufferCapacity = 4)
        val downloader = FakeOriginalImageDownloader { stream.asSharedFlow() }
        val snackbars = RecordingSnackbarController()

        composeRule.setContent {
            CompositionLocalProvider(
                LocalHiImageLoader provides loader,
                LocalPaneSnackbarController provides snackbars,
            ) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(urls = listOf(hdUrl)),
                        onDismiss = {},
                        originalDownloader = downloader,
                        mediaActions = FakeImageViewerMediaActions(),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("查看原图").performClick()
        composeRule.waitForIdle()
        stream.tryEmit(OriginalDownloadState.Completed(file = hugeFile, mimeType = "image/png"))
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("已加载原图").fetchSemanticsNodes().isNotEmpty()
        }

        val fileRequest = loader.requests.firstOrNull { it.data == hugeFile }
        assertNotNull("下载后的原图文件必须交由 Coil ImageLoader 解码", fileRequest)
        val resolvedSize = runBlocking { fileRequest!!.sizeResolver.size() }
        val reqW = (resolvedSize.width as? Dimension.Pixels)?.px ?: -1
        val reqH = (resolvedSize.height as? Dimension.Pixels)?.px ?: -1
        assertEquals("原图 Coil 解码宽上限应为视口 2×（822×2=1644）", 1644, reqW)
        assertEquals("原图 Coil 解码高上限应为视口 2×（1782×2=3564）", 3564, reqH)
        assertTrue("原图解码上限严禁超过 4096px", reqW <= 4096 && reqH <= 4096)
    }

    // ==================== R-E14 S2：分享走 externalCacheDir + 清理旧缓存 + ClipData 授权 ====================

    @Test
    fun s2_shareImage_usesExternalCacheDirCleansStaleFilesAndSetsClipDataGrantFlags() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val extCacheDir = requireNotNull(app.externalCacheDir) { "Robolectric 环境下 externalCacheDir 应可用" }
        extCacheDir.mkdirs()
        val staleFile = File(extCacheDir, "share_stale_old.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val sourcePng = writeTempPngFile(tempDir, "to_share.png")
        val fakeUri = Uri.parse("content://net.jejer.hipda.next.provider/external_storage_root/share.png")
        var capturedShareFile: File? = null

        val mediaActions = AndroidImageViewerMediaActions(
            context = app,
            originalDownloader = FakeOriginalImageDownloader(),
            ioDispatcher = Dispatchers.Unconfined,
            mainDispatcher = Dispatchers.Unconfined,
            uriProvider = { _, file ->
                capturedShareFile = file
                fakeUri
            },
        )

        val shared = runBlocking { mediaActions.shareImage("https://img.example.invalid/s2.png", cachedFile = sourcePng) }
        assertTrue("分享合法图片应返回 true", shared)
        assertFalse("旧 share_* 缓存文件必须在本次分享前清理", staleFile.exists())
        val shareFile = requireNotNull(capturedShareFile)
        assertEquals("分享文件必须落在 externalCacheDir 下", extCacheDir.canonicalFile, shareFile.parentFile?.canonicalFile)
        assertTrue("分享目标文件必须已写入且非空", shareFile.isFile && shareFile.length() > 0L)

        val chooser = requireNotNull(Shadows.shadowOf(app).nextStartedActivity) { "必须发起系统分享 Chooser Intent" }
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue("Chooser 必须携带 FLAG_GRANT_READ_URI_PERMISSION", (chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertTrue("非 Activity Context 启动 Chooser 必须携带 FLAG_ACTIVITY_NEW_TASK", (chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertNotNull("Chooser 必须设置 ClipData 以向系统分享面板与目标应用授权预览/读取", chooser.clipData)
        assertEquals(fakeUri, chooser.clipData!!.getItemAt(0).uri)

        @Suppress("DEPRECATION")
        val sendIntent = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals(Intent.ACTION_SEND, sendIntent.action)
        assertEquals("image/png", sendIntent.type)
        @Suppress("DEPRECATION")
        assertEquals(fakeUri, sendIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue("ACTION_SEND 必须携带 FLAG_GRANT_READ_URI_PERMISSION", (sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertNotNull("ACTION_SEND 必须设置 ClipData", sendIntent.clipData)
        assertEquals(fakeUri, sendIntent.clipData!!.getItemAt(0).uri)
    }

    // ==================== R-E14 S3：长图入场目标同步 + 旋转/尺寸变化钳制 ====================

    @Test
    fun s3_longImageEnterWithSourceBounds_syncsTargetDuringEnteringWithoutEndFrameJump() {
        val density = composeRule.density.density
        val oneDpPx = with(composeRule.density) { 1.dp.toPx() }
        val thumbRect = Rect(48f * density, 160f * density, 248f * density, 310f * density)
        val decodeGate = CompletableDeferred<Unit>()
        val loader = ViewerTestImageLoader { req ->
            decodeGate.await()
            SuccessResult(createLongPosterBitmap(400, 1600).asImage(), req, DataSource.NETWORK)
        }

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(
                            urls = listOf("https://img.example.invalid/s3_long.png"),
                            sourceBoundsInWindow = thumbRect,
                        ),
                        onDismiss = {},
                        originalDownloader = FakeOriginalImageDownloader(),
                        mediaActions = FakeImageViewerMediaActions(),
                    )
                }
            }
        }

        // Frame 0 对齐缩略图，推进 2 帧后释放长图解码（此时仍处于 Entering，初始目标为 4:3 Fit）
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        decodeGate.complete(Unit)

        var lastHeightBeforeSettled = 0f
        var settledHeight = 0f
        var settledFound = false
        for (f in 0..160) {
            composeRule.mainClock.advanceTimeByFrame()
            val curHeight = composeRule.onNode(imageMatcher("1 / 1")).fetchSemanticsNode().boundsInWindow.height
            val closeVisible = composeRule.onAllNodesWithText("1 / 1").fetchSemanticsNodes().isNotEmpty()
            if (!closeVisible) {
                lastHeightBeforeSettled = curHeight
            } else {
                settledHeight = curHeight
                settledFound = true
                break
            }
        }
        assertTrue("长图入场必须在 160 帧内进入 Settled", settledFound)
        val fullViewportH = 891f * density
        assertEquals("Settled 后长图容器高必须贴合全视口高", fullViewportH, settledHeight, oneDpPx * 2f)
        // 若未同步重定入场终点，Entering 末帧高会停在 4:3 Fit（≈616.5px），切 Settled 时突跳 > 1000px
        assertTrue(
            "Entering 末帧高度必须平滑逼近全视口高度，不得出现 > 60dp 末帧跳变：before=$lastHeightBeforeSettled, settled=$settledHeight",
            abs(settledHeight - lastHeightBeforeSettled) <= 60f * density,
        )
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    @Config(qualifiers = "w900dp-h900dp-xhdpi")
    fun s3_viewportResize_clampsLongImageScrollAndZoomedPanWithoutOutOfBoundsBlackGap() {
        val density = composeRule.density

        // Part A：长图滚到底部后缩窄视口（maxLongScrollY 骤降），必须即时钳制滚动偏移，底部不得露纯黑空隙
        var boxSize by mutableStateOf(DpSize(411.dp, 891.dp))
        val longLoader = ViewerTestImageLoader { req ->
            SuccessResult(createLongPosterBitmap(400, 1600).asImage(), req, DataSource.NETWORK)
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides longLoader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        Box(Modifier.size(boxSize)) {
                            ImageViewerOverlay(
                                args = ImageViewerLaunchArgs(urls = listOf("https://img.example.invalid/s3_long_resize.png")),
                                onDismiss = {},
                                originalDownloader = FakeOriginalImageDownloader(),
                                mediaActions = FakeImageViewerMediaActions(),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 向上拖拽滚到底部（411×891dp 下 maxLongScrollY = 753dp）
        repeat(3) {
            composeRule.onNode(imageMatcher("1 / 1")).performTouchInput {
                down(Offset(width * 0.5f, height * 0.8f))
                repeat(10) { moveBy(Offset(0f, -120f), delayMillis = 16L) }
                up()
            }
            composeRule.waitForIdle()
        }

        // 变更容器宽为 240dp×891dp（新 maxLongScrollY = 69dp << 753dp）
        boxSize = DpSize(240.dp, 891.dp)
        composeRule.waitForIdle()

        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        val sampleX = with(density) { 120.dp.toPx().toInt() }
        val sampleY = with(density) { 750.dp.toPx().toInt() }
        val bottomPixel = pixels[sampleX, sampleY]
        val luma = bottomPixel.red + bottomPixel.green + bottomPixel.blue
        assertTrue(
            "视口缩小后长图滚动必须实时钳制，底部 (120dp, 750dp) 应仍显示长图色带而非纯黑越界空隙，实际 $bottomPixel",
            luma > 0.35f,
        )

        // Part B：常规图放大 2.5× 并推至边缘后横竖屏旋转（411×891dp -> 891×411dp），平移必须实时钳制不露黑边
        val clamped = clampPanOffset(
            offset = Offset(616.5f, 0f),
            scale = 2.5f,
            baseContentSize = androidx.compose.ui.geometry.Size(548f * density.density, 411f * density.density),
            viewportSize = androidx.compose.ui.geometry.Size(891f * density.density, 411f * density.density),
        )
        assertTrue("横屏钳制后 maxPanX 应小于竖屏旧 panX", clamped.x < 616.5f)
    }

    @Test
    @Config(qualifiers = "w900dp-h900dp-xhdpi")
    fun s3_zoomedImageRotation_clampsLivePanOffsetInUi() {
        var boxSize by mutableStateOf(DpSize(411.dp, 891.dp))
        val loader = ViewerTestImageLoader.solid(width = 800, height = 600)
        val density = composeRule.density

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.size(boxSize)) {
                        ImageViewerOverlay(
                            args = ImageViewerLaunchArgs(urls = listOf("https://img.example.invalid/s3_zoom_rot.jpg")),
                            onDismiss = {},
                            originalDownloader = FakeOriginalImageDownloader(),
                            mediaActions = FakeImageViewerMediaActions(),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 双击放大至 2.5× 并向右拖到左缘极限（411dp 竖屏 maxPanX = 308.25dp）
        composeRule.onNode(imageMatcher("1 / 1")).performTouchInput { doubleClick(center) }
        composeRule.waitForIdle()
        composeRule.onNode(imageMatcher("1 / 1")).performTouchInput {
            down(center)
            repeat(12) { moveBy(Offset(80f, 0f), delayMillis = 16L) }
            up()
        }
        composeRule.waitForIdle()

        // 旋转至 891×411dp 横屏（新 maxPanX = 239.5dp < 308.25dp）：若未钳制，左缘将右移露出 ~68.75dp 黑边
        boxSize = DpSize(891.dp, 411.dp)
        composeRule.waitForIdle()

        val rotatedBounds = composeRule.onNode(imageMatcher("1 / 1")).fetchSemanticsNode().boundsInWindow
        assertTrue(
            "横屏旋转后放大图平移必须即时钳制，左缘不得右移露出黑边（期望 <= 2dp），实际 left=${rotatedBounds.left}",
            rotatedBounds.left <= with(density) { 2.dp.toPx() },
        )
    }

    // ==================== API ≤ 28 动态申请 WRITE_EXTERNAL_STORAGE ====================

    @Test
    @Config(application = Application::class, sdk = [28], qualifiers = "w411dp-h891dp-xhdpi")
    fun api28_saveImage_requestsWriteExternalStorage_deniedFailsWithoutSave_grantedSaves() {
        assertTrue(requiresLegacyStoragePermission(26))
        assertTrue(requiresLegacyStoragePermission(28))
        assertFalse(requiresLegacyStoragePermission(29))
        assertFalse(requiresLegacyStoragePermission(34))

        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        val permOwner = FakePermissionRegistryOwner(nextGrantResult = false)
        val mediaActions = FakeImageViewerMediaActions(saveResult = true)
        val snackbars = RecordingSnackbarController()
        val url = "https://img.example.invalid/api28_save.jpg"

        composeRule.setContent {
            CompositionLocalProvider(
                LocalHiImageLoader provides ViewerTestImageLoader.solid(800, 600),
                LocalPaneSnackbarController provides snackbars,
                LocalActivityResultRegistryOwner provides permOwner,
            ) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(urls = listOf(url)),
                        onDismiss = {},
                        originalDownloader = FakeOriginalImageDownloader(),
                        mediaActions = mediaActions,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 首次拒绝权限：必须申请 WRITE_EXTERNAL_STORAGE，不得调用 saveImage，并提示“保存失败”
        composeRule.onNodeWithText("保存图片").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), permOwner.requestedPermissions)
        assertTrue("权限被拒时绝不得调用 saveImage", mediaActions.savedUrls.isEmpty())
        assertTrue("权限被拒时必须提示“保存失败”，实际 ${snackbars.messages}", "保存失败" in snackbars.messages)

        // 2. 再次点击并授予权限：正常调用 saveImage 并提示“已保存到相册”
        permOwner.nextGrantResult = true
        composeRule.onNodeWithText("保存图片").performClick()
        composeRule.waitForIdle()
        assertEquals(2, permOwner.requestedPermissions.size)
        assertEquals(listOf(url), mediaActions.savedUrls)
        assertTrue("权限授予后应提示“已保存到相册”，实际 ${snackbars.messages}", "已保存到相册" in snackbars.messages)
    }

    @Test
    @Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
    fun api34_saveImage_skipsLegacyStoragePermissionAndSavesDirectly() {
        val permOwner = FakePermissionRegistryOwner(nextGrantResult = false)
        val mediaActions = FakeImageViewerMediaActions(saveResult = true)
        val snackbars = RecordingSnackbarController()
        val url = "https://img.example.invalid/api34_save.jpg"

        composeRule.setContent {
            CompositionLocalProvider(
                LocalHiImageLoader provides ViewerTestImageLoader.solid(800, 600),
                LocalPaneSnackbarController provides snackbars,
                LocalActivityResultRegistryOwner provides permOwner,
            ) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(urls = listOf(url)),
                        onDismiss = {},
                        originalDownloader = FakeOriginalImageDownloader(),
                        mediaActions = mediaActions,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("保存图片").performClick()
        composeRule.waitForIdle()
        assertTrue("API 29+ 走 MediaStore 不得申请 WRITE_EXTERNAL_STORAGE", permOwner.requestedPermissions.isEmpty())
        assertEquals(listOf(url), mediaActions.savedUrls)
        assertTrue("保存成功应提示“已保存到相册”", "已保存到相册" in snackbars.messages)
    }
}

private class FakePermissionRegistryOwner(
    var nextGrantResult: Boolean = false,
) : ActivityResultRegistryOwner {
    val requestedPermissions = mutableListOf<String>()

    override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            if (input is String) requestedPermissions += input
            @Suppress("UNCHECKED_CAST")
            dispatchResult(requestCode, nextGrantResult as O)
        }
    }
}
