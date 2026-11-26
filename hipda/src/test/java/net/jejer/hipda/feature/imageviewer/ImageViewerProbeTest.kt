package net.jejer.hipda.feature.imageviewer

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ComponentRegistry
import coil3.Image as CoilImage
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Disposable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Size as CoilSize
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.SingleHostPaneSnackbarController
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.designsystem.motion.HiSystemMotionDurationScale
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * V-E14 独立探针测试（对照 `docs/design/product-design.md` §6.3/§7.1/§10.2 与 `V-E14.md` 编写）：
 * 1. 探针 1：进退双分支（可见缩略图 `Rect` ≤ 1dp 对齐 / 不可见图原地缩至 `0.85` 淡出且不闪旧图 / 减动效 0ms 到位）；
 * 2. 探针 2：下拉 `0→240→320dp` 逐帧缩放/遮罩钳制、距离/速度阈值与弹回、单/长/双击、以及负责人加测手势仲裁
 *    （放大平移至边缘再续滑翻页；45° 斜拖不并发触发下拉关闭与翻页）；
 * 3. 探针 3：`MockWebServer` 节流确定进度与二次点击取消、长图 `AR>2.5` 贴宽顶对齐无自动缩放、GIF 切播停、
 *    保存相册提示及全套危险 scheme 0 请求拦截。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ImageViewerProbeTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ==================== 探针 1：进退双分支逐帧几何（autoAdvance = false） ====================

    @Test
    fun probe1a_enterAndExitVisibleThumbnail_frameByFrameMatchesRectWithin1Dp_closeButton() {
        runExitPathVerification(ExitTrigger.CLOSE_BUTTON)
    }

    @Test
    fun probe1a_enterAndExitVisibleThumbnail_frameByFrameMatchesRectWithin1Dp_systemBack() {
        runExitPathVerification(ExitTrigger.SYSTEM_BACK)
    }

    @Test
    fun probe1a_enterAndExitVisibleThumbnail_frameByFrameMatchesRectWithin1Dp_dragDismiss() {
        runExitPathVerification(ExitTrigger.DRAG_DISMISS)
    }

    private enum class ExitTrigger { CLOSE_BUTTON, SYSTEM_BACK, DRAG_DISMISS }

    private fun runExitPathVerification(
        trigger: ExitTrigger,
    ) {
        val url = "https://img.example.invalid/probe1a.jpg"
        val loader = ProbeImageLoader { req ->
            SuccessResult(ColorImage(0xFF225588.toInt(), 800, 600), req, DataSource.MEMORY)
        }
        val sourceBoundsPx = Rect(left = 64f, top = 360f, right = 364f, bottom = 585f) // 150x112.5dp (4:3)
        var dismissCount = 0
        var openViewer by mutableStateOf(true)
        var backDispatcher: OnBackPressedDispatcher? = null
        val launchArgs = ImageViewerLaunchArgs(
            urls = listOf(url),
            initialIndex = 0,
            sourceBoundsInWindow = sourceBoundsPx,
            floorNumber = 1,
            author = "probe_user",
        )

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        if (openViewer) {
                            ImageViewerOverlay(
                                args = launchArgs,
                                onDismiss = {
                                    dismissCount++
                                    openViewer = false
                                },
                                originalDownloader = IdleProbeDownloader(),
                                mediaActions = RecordingMediaActions(),
                                resolveBoundsForIndex = { idx -> if (idx == 0) sourceBoundsPx else null },
                            )
                        }
                    }
                }
            }
        }

        val density = composeRule.density.density
        val oneDpPx = density
        composeRule.mainClock.advanceTimeByFrame()

        // 1) 进入首帧起点对齐 sourceBoundsInWindow（误差 ≤ 1dp），遮罩从透明渐暗
        val enterFrame0Rect = findViewerImageRect("1 / 1")
        assertRectNear("[$trigger] 进入起点必须对齐缩略图 Rect", sourceBoundsPx, enterFrame0Rect, oneDpPx)
        val initialScrim = sampleCornerScrimAlpha()
        assertTrue("[$trigger] 进入首帧遮罩应未全黑（渐入中），实际 $initialScrim", initialScrim < 0.95f)

        // 2) 逐帧推进至静止全屏 fit 态（411dp x 891dp 下 4:3 图居中：宽 822px，高 616.5px）
        val viewportPx = with(composeRule.density) { Size(411f * density, 891f * density) }
        val expectedRest = computeRestImageRect(viewportPx, 800, 600, sourceBoundsPx)
        repeat(90) { composeRule.mainClock.advanceTimeByFrame() }
        val settledRect = findViewerImageRect("1 / 1")
        assertRectNear("[$trigger] 进入终态必须落到全屏居中 fit Rect", expectedRest, settledRect, oneDpPx)
        assertTrue("[$trigger] 进入终态遮罩应接近 1.0，实际 ${sampleCornerScrimAlpha()}", sampleCornerScrimAlpha() > 0.96f)

        // 3) 触发退出（分别验证关闭按钮、系统返回、下拉超过阈值）
        when (trigger) {
            ExitTrigger.CLOSE_BUTTON -> {
                composeRule.onNodeWithContentDescription("关闭").performClick()
            }
            ExitTrigger.SYSTEM_BACK -> {
                composeRule.runOnIdle { backDispatcher!!.onBackPressed() }
            }
            ExitTrigger.DRAG_DISMISS -> {
                val center = composeRule.onRoot().fetchSemanticsNode().boundsInWindow.center
                val dragPx = 150f * density
                composeRule.onRoot().performTouchInput {
                    down(center)
                    repeat(6) { moveBy(Offset(0f, dragPx / 6f), delayMillis = 16L) }
                    up()
                }
            }
        }

        val exitRects = mutableListOf<Rect>()
        var lastRectBeforeDismiss: Rect? = null
        var lastScrimBeforeDismiss = 1f
        for (frame in 0..150) {
            if (dismissCount > 0) break
            val nodes = composeRule.onAllNodes(viewerImageMatcher("1 / 1"), useUnmergedTree = true).fetchSemanticsNodes()
            if (nodes.isNotEmpty()) {
                val r = nodes.first().boundsInWindow
                exitRects.add(r)
                lastRectBeforeDismiss = r
                lastScrimBeforeDismiss = sampleCornerScrimAlpha()
            }
            composeRule.mainClock.advanceTimeByFrame()
        }

        assertEquals("[$trigger] 退出动画结束后必须精确触发 1 次 onDismiss", 1, dismissCount)
        val endRect = requireNotNull(lastRectBeforeDismiss) { "[$trigger] 未采集到退出末帧几何" }
        assertTrue("[$trigger] 退出过程应有多帧逐帧变化，实际帧数=${exitRects.size}", exitRects.size >= 4)
        assertRectNear("[$trigger] 退出终点必须对齐缩略图 Rect", sourceBoundsPx, endRect, oneDpPx)
        assertTrue("[$trigger] 退出末帧遮罩应淡出接近 0，实际 $lastScrimBeforeDismiss", lastScrimBeforeDismiss < 0.08f)
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun probe1b_swipeToInvisibleThumbnail_exitShrinksTo085FadesOutWithoutFlashingOldImage() {
        val url0 = "https://img.example.invalid/p0.jpg"
        val url1 = "https://img.example.invalid/p1.jpg"
        val url2 = "https://img.example.invalid/p2.jpg"
        // p0 纯蓝、p1 纯红，用于检测退场期是否闪现 p0 旧图
        val loader = ProbeImageLoader { req ->
            val color = when (req.data.toString()) {
                url0 -> 0xFF0044FF.toInt()
                url1 -> 0xFFFF2222.toInt()
                else -> 0xFF22AA44.toInt()
            }
            SuccessResult(ColorImage(color, 800, 600), req, DataSource.MEMORY)
        }
        val thumb0 = Rect(40f, 120f, 280f, 300f)
        var dismissCount = 0
        var openViewer by mutableStateOf(true)

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        if (openViewer) {
                            ImageViewerOverlay(
                                args = ImageViewerLaunchArgs(listOf(url0, url1, url2), initialIndex = 0, sourceBoundsInWindow = thumb0),
                                onDismiss = {
                                    dismissCount++
                                    openViewer = false
                                },
                                originalDownloader = IdleProbeDownloader(),
                                mediaActions = RecordingMediaActions(),
                                // 横滑后第 1、2 张缩略图不在可视区内（返回 null）
                                resolveBoundsForIndex = { idx -> if (idx == 0) thumb0 else null },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 3").assertIsDisplayed()

        // 横滑翻到第 2 张（索引 1）
        composeRule.onRoot().performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 / 3").assertIsDisplayed()

        val density = composeRule.density.density
        val oneDpPx = density
        val restRect1 = findViewerImageRect("2 / 3")

        // 关掉自动时钟，点击关闭，逐帧观测第 2 张退场动画
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription("关闭").performClick()

        val centers = mutableListOf<Offset>()
        val widths = mutableListOf<Float>()
        val cornerReds = mutableListOf<Float>()
        for (frame in 0..150) {
            if (dismissCount > 0) break
            val nodes1 = composeRule.onAllNodes(viewerImageMatcher("2 / 3"), useUnmergedTree = true).fetchSemanticsNodes()
            if (nodes1.isNotEmpty()) {
                val r = nodes1.first().boundsInWindow
                centers.add(r.center)
                widths.add(r.width)
                val pixels = composeRule.onRoot().captureToImage().toPixelMap()
                cornerReds.add(pixels[4, 4].red)
                // 中心采样绝不能闪现第 0 张纯蓝旧图（blue > red + 0.1）
                val centerPx = pixels[(r.center.x.roundToInt()).coerceIn(0, pixels.width - 1), (r.center.y.roundToInt()).coerceIn(0, pixels.height - 1)]
                assertFalse(
                    "退场第 $frame 帧绝不能闪现第 0 张蓝色旧图（centerPx=$centerPx）",
                    centerPx.blue > centerPx.red + 0.1f,
                )
            }
            // 同时确保第 0 张 Semantic 节点未出现在视口中心
            val nodes0 = composeRule.onAllNodes(viewerImageMatcher("1 / 3"), useUnmergedTree = true).fetchSemanticsNodes()
            for (n0 in nodes0) {
                assertTrue(
                    "退场期旧图 1/3 不应回到可视区中心：${n0.boundsInWindow}",
                    n0.boundsInWindow.right <= 1f || n0.boundsInWindow.left >= restRect1.right,
                )
            }
            composeRule.mainClock.advanceTimeByFrame()
        }

        assertEquals(1, dismissCount)
        assertTrue("退场应持续多帧，实际 ${widths.size}", widths.size >= 3)
        // 中心保持原地（不飞向 thumb0，也不飞向屏幕外）
        centers.forEach { c ->
            assertTrue("不可见图退出必须保持原地居中，不能飞向缩略图/屏外：$c vs ${restRect1.center}", (c - restRect1.center).getDistance() <= oneDpPx)
        }
        val expectedFinalWidth = restRect1.width * 0.85f
        assertEquals("末帧宽度应原地缩至 0.85 倍", expectedFinalWidth, widths.last(), oneDpPx)
        assertTrue("退场末帧背景遮罩应淡出（white 角像素 red=${cornerReds.last()}）", cornerReds.last() > 0.85f)
        composeRule.mainClock.autoAdvance = true
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun probe1c_reducedMotion_zeroMsSingleFrameEnterAndExit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val url = "https://img.example.invalid/reduced.jpg"
        val loader = ProbeImageLoader { req ->
            SuccessResult(ColorImage(0xFF3377AA.toInt(), 800, 600), req, DataSource.MEMORY)
        }
        val thumb = Rect(40f, 200f, 240f, 350f)

        withAnimatorDurationScale(0f, context) {
            var dismissCount = 0
            var open by mutableStateOf(true)
            mainClock.autoAdvance = false

            setContent {
                val scale = remember { HiSystemMotionDurationScale(context) }
                CompositionLocalProvider(LocalHiImageLoader provides loader) {
                    HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = scale) {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            if (open) {
                                ImageViewerOverlay(
                                    args = ImageViewerLaunchArgs(listOf(url), 0, thumb),
                                    onDismiss = {
                                        dismissCount++
                                        open = false
                                    },
                                    originalDownloader = IdleProbeDownloader(),
                                    mediaActions = RecordingMediaActions(),
                                    resolveBoundsForIndex = { thumb },
                                )
                            }
                        }
                    }
                }
            }

            // 仅推进 1 帧（0ms 动效应直接到位，不留中间态）
            mainClock.advanceTimeByFrame()
            val expectedRest = computeRestImageRect(
                Size(411f * density.density, 891f * density.density),
                800,
                600,
                thumb,
            )
            val rect = onNode(viewerImageMatcher("1 / 1"), useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            assertRectNear("减动效下首帧即应直接到位全屏居中", expectedRest, rect, density.density)

            // 点击关闭，仅推进 1 帧即完成退出回调
            onNodeWithContentDescription("关闭").performClick()
            mainClock.advanceTimeByFrame()
            assertEquals("减动效下退出必须在 0ms（1 帧内）完成回调", 1, dismissCount)
            mainClock.autoAdvance = true
        }
    }

    // ==================== 探针 2：下拉关闭与手势仲裁 ====================

    @Test
    fun probe2a_pullDown0To240To320Dp_frameByFrameScaleAndScrimClamp() {
        val url = "https://img.example.invalid/pull.jpg"
        val loader = ProbeImageLoader { req ->
            SuccessResult(ColorImage(0xFF446688.toInt(), 800, 600), req, DataSource.MEMORY)
        }
        var dismissCount = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        ImageViewerOverlay(
                            args = ImageViewerLaunchArgs(listOf(url)),
                            onDismiss = { dismissCount++ },
                            originalDownloader = IdleProbeDownloader(),
                            mediaActions = RecordingMediaActions(),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density.density
        val restRect = findViewerImageRect("1 / 1")
        val center = composeRule.onRoot().fetchSemanticsNode().boundsInWindow.center

        composeRule.mainClock.autoAdvance = false
        composeRule.onRoot().performTouchInput { down(center) }
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(1.0f, sampleCornerScrimAlpha(), 0.03f)

        // 拖到 120dp（6 步 x 20dp）：每步内以多帧子步发送以便精确落位到 120dp / 240dp / 320dp
        val step20 = 20f * density
        repeat(6) {
            composeRule.onRoot().performTouchInput { moveBy(Offset(0f, step20), delayMillis = 16L) }
            composeRule.mainClock.advanceTimeByFrame()
        }
        composeRule.mainClock.advanceTimeByFrame()
        val rect120 = findViewerImageRect("1 / 1")
        val dy120 = rect120.center.y - restRect.center.y
        assertEquals("120dp 下拉位移($dy120 px)下缩放应为 0.90", 0.90f, rect120.width / restRect.width, 0.03f)
        assertEquals("120dp 下拉位移($dy120 px)下遮罩应为 0.65（实测 offsetY=${dy120 / density}dp）", 0.65f, sampleCornerScrimAlpha(), 0.08f)

        // 续拖到 240dp（再 6 步 x 20dp）：scale=0.80, scrimAlpha=0.30
        repeat(6) {
            composeRule.onRoot().performTouchInput { moveBy(Offset(0f, step20), delayMillis = 16L) }
            composeRule.mainClock.advanceTimeByFrame()
        }
        composeRule.mainClock.advanceTimeByFrame()
        val rect240 = findViewerImageRect("1 / 1")
        val dy240 = rect240.center.y - restRect.center.y
        assertEquals("240dp 下拉位移($dy240 px)下缩放应为 0.80", 0.80f, rect240.width / restRect.width, 0.03f)
        assertEquals("240dp 下拉位移($dy240 px)下遮罩应为 0.30（实测 offsetY=${dy240 / density}dp）", 0.30f, sampleCornerScrimAlpha(), 0.08f)

        // 超过 240dp 拖到 320dp（再 4 步 x 20dp）：缩放仍 0.80、遮罩下限钳制 0.30（绝不更低）
        repeat(4) {
            composeRule.onRoot().performTouchInput { moveBy(Offset(0f, step20), delayMillis = 16L) }
            composeRule.mainClock.advanceTimeByFrame()
        }
        composeRule.mainClock.advanceTimeByFrame()
        val rect320 = findViewerImageRect("1 / 1")
        val scrim320 = sampleCornerScrimAlpha()
        assertEquals("超 240dp 缩放仍钳制在 0.80", 0.80f, rect320.width / restRect.width, 0.03f)
        assertEquals("超 240dp 遮罩下限钳制在 0.30", 0.30f, scrim320, 0.05f)
        assertTrue("遮罩绝不能低于 0.30，实际 $scrim320", scrim320 >= 0.27f)

        composeRule.onRoot().performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(1, dismissCount)
    }

    @Test
    fun probe2b_releaseThresholds_distanceOrVelocityDismisses_belowThresholdBouncesBack() {
        val url = "https://img.example.invalid/thresh.jpg"
        val loader = ProbeImageLoader { req ->
            SuccessResult(ColorImage(0xFF446688.toInt(), 800, 600), req, DataSource.MEMORY)
        }
        var dismissCount by mutableIntStateOf(0)
        var show by mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        if (show) {
                            ImageViewerOverlay(
                                args = ImageViewerLaunchArgs(listOf(url)),
                                onDismiss = {
                                    dismissCount++
                                    show = false
                                },
                                originalDownloader = IdleProbeDownloader(),
                                mediaActions = RecordingMediaActions(),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density.density
        val restRect = findViewerImageRect("1 / 1")
        val center = composeRule.onRoot().fetchSemanticsNode().boundsInWindow.center

        // 1) 未达标（90dp <= 120dp 且慢速 <= 800dp/s）-> 弹回原位且不关闭
        composeRule.onRoot().performTouchInput {
            down(center)
            repeat(9) { moveBy(Offset(0f, 10f * density), delayMillis = 50L) } // 200dp/s
            up()
        }
        composeRule.waitForIdle()
        assertEquals("未达阈值不应关闭", 0, dismissCount)
        assertRectNear("未达阈值松手必须弹回原位", restRect, findViewerImageRect("1 / 1"), density)
        assertEquals(1.0f, sampleCornerScrimAlpha(), 0.03f)

        // 2) 距离虽 < 120dp（75dp），但向下速度 > 800dp/s（75dp / 40ms = 1875dp/s）-> 关闭
        composeRule.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(0f, 25f * density), delayMillis = 12L)
            moveBy(Offset(0f, 25f * density), delayMillis = 12L)
            moveBy(Offset(0f, 25f * density), delayMillis = 12L)
            up()
        }
        composeRule.waitForIdle()
        assertEquals("速度 > 800dp/s 应触发关闭", 1, dismissCount)

        // 3) 重开测试慢速位移 > 120dp（150dp，150dp/s）-> 关闭
        show = true
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, 15f * density), delayMillis = 100L) } // 150dp/s
            up()
        }
        composeRule.waitForIdle()
        assertEquals("位移 > 120dp 即使慢速也应关闭", 2, dismissCount)
    }

    @Test
    fun probe2c_tapChromeToggle_longPressMenu_doubleTapZoom_zoomedSuppressesPullDown() {
        val url = "https://img.example.invalid/interact.jpg"
        // 16:9 横图（800x450）在 411x891dp 下静止高度 ~231dp，2.5x 后高度 ~578dp < 891dp，不被视口裁剪可直接测量 2.5x 几何高度
        val loader = ProbeImageLoader { req ->
            SuccessResult(ColorImage(0xFF335577.toInt(), 800, 450), req, DataSource.MEMORY)
        }
        var dismissCount = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        ImageViewerOverlay(
                            args = ImageViewerLaunchArgs(listOf(url), floorNumber = 2, author = "tester"),
                            onDismiss = { dismissCount++ },
                            originalDownloader = IdleProbeDownloader(),
                            mediaActions = RecordingMediaActions(),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density.density
        val center = composeRule.onRoot().fetchSemanticsNode().boundsInWindow.center
        val restRect = findViewerImageRect("1 / 1")

        // 单击切换工具栏显隐，绝不关闭（detectTapGestures 带 onDoubleTap 时需等待 doubleTapTimeout 才触发 onTap）
        composeRule.onNodeWithText("查看原图").assertIsDisplayed()
        composeRule.onRoot().performTouchInput { click(center) }
        composeRule.mainClock.advanceTimeBy(400L)
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("查看原图").fetchSemanticsNodes().isEmpty())
        assertEquals(0, dismissCount)
        composeRule.onRoot().performTouchInput { click(center) }
        composeRule.mainClock.advanceTimeBy(400L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("查看原图").assertIsDisplayed()

        // 长按弹出保存/分享菜单
        composeRule.onRoot().performTouchInput { longClick(center) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("取消").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // 双击放大到 2.5 倍
        composeRule.onRoot().performTouchInput { doubleClick(center) }
        composeRule.mainClock.advanceTimeBy(800L)
        composeRule.waitForIdle()
        val zoomedRect = findViewerImageRect("1 / 1")
        assertEquals("双击应放大到 2.5x（按未裁剪高度比验证）", 2.5f, zoomedRect.height / restRect.height, 0.08f)

        // 放大态竖向下拉 220dp 禁止触发下拉关闭
        composeRule.onRoot().performTouchInput {
            down(center)
            repeat(11) { moveBy(Offset(0f, 20f * density), delayMillis = 16L) }
            up()
        }
        composeRule.waitForIdle()
        assertEquals("放大态下拉不得关闭看图", 0, dismissCount)
        assertEquals("放大态下拉遮罩必须保持 1.0", 1.0f, sampleCornerScrimAlpha(), 0.03f)

        // 再次双击还原 1.0
        composeRule.onRoot().performTouchInput { doubleClick(center) }
        composeRule.mainClock.advanceTimeBy(800L)
        composeRule.waitForIdle()
        assertRectNear("再次双击应还原 1.0", restRect, findViewerImageRect("1 / 1"), density)
    }

    @Test
    fun probe2d_leadExtra2_zoomPanToEdgeThenContinueSwipeFlips_and45DegDiagonalNeverTriggersBoth() {
        val urls = listOf(
            "https://img.example.invalid/g0.jpg",
            "https://img.example.invalid/g1.jpg",
            "https://img.example.invalid/g2.jpg",
        )
        // 三色横向分区位图（左1/4 红、中1/2 绿、右1/4 蓝，800x450），在 411x891dp 下：
        // - 静止高度 ~231dp，2.5x 后高度 ~578dp < 891dp（可直接观测 height 2.5x 缩放）；
        // - 放大后水平平移可通过视口中心像素颜色精确判别平移到了右半蓝区或左半红区！
        val stripedBmp = createThreeStripeHorizontalBitmap(800, 450, 0xFFFF2222.toInt(), 0xFF11AA44.toInt(), 0xFF1144FF.toInt())
        val loader = ProbeImageLoader { req ->
            SuccessResult(stripedBmp.asImage(), req, DataSource.MEMORY)
        }
        var dismissCount = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        ImageViewerOverlay(
                            args = ImageViewerLaunchArgs(urls),
                            onDismiss = { dismissCount++ },
                            originalDownloader = IdleProbeDownloader(),
                            mediaActions = RecordingMediaActions(),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density.density
        val center = composeRule.onRoot().fetchSemanticsNode().boundsInWindow.center
        val centerPxX = center.x.roundToInt()
        val centerPxY = center.y.roundToInt()

        // 1) 双击放大到 2.5x（验证确实已进入放大态且中心仍落在中间绿区）
        val restBeforeZoom = findViewerImageRect("1 / 3")
        composeRule.onRoot().performTouchInput { doubleClick(center) }
        composeRule.mainClock.advanceTimeBy(800L)
        composeRule.waitForIdle()
        val zoomedCheck = findViewerImageRect("1 / 3")
        assertEquals("双击后应先进入 2.5x 放大态", 2.5f, zoomedCheck.height / restBeforeZoom.height, 0.1f)
        val pxAtCenterBeforePan = composeRule.onRoot().captureToImage().toPixelMap()[centerPxX, centerPxY]
        assertTrue("放大后初始中心应为绿色区域：$pxAtCenterBeforePan", pxAtCenterBeforePan.green > pxAtCenterBeforePan.blue + 0.2f)

        // 2) 未到边缘前横滑 280dp（560px，处于 513.75px 蓝区起始与 616.5px 右边缘之间）：仅平移进右半蓝区，绝不翻页
        composeRule.onRoot().performTouchInput {
            down(center)
            repeat(14) { moveBy(Offset(-20f * density, 0f), delayMillis = 16L) }
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 3").assertIsDisplayed()
        val pxAfterMidPan = composeRule.onRoot().captureToImage().toPixelMap()[centerPxX, centerPxY]
        assertTrue("未达边缘左滑平移后中心应落入右半蓝区（$pxAfterMidPan）", pxAfterMidPan.blue > pxAfterMidPan.green + 0.2f)

        // 3) 从当前接近右边缘继续左滑越过边缘 -> 翻页到 2 / 3
        composeRule.onRoot().performTouchInput {
            down(center)
            repeat(15) { moveBy(Offset(-20f * density, 0f), delayMillis = 16L) }
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 / 3").assertIsDisplayed()

        // 4) 在第 2 页放大（pan=0）：先拖 280dp 到左边缘红区但不翻页，再从左边缘继续右滑 -> 翻回第 1 页
        composeRule.onRoot().performTouchInput { doubleClick(center) }
        composeRule.mainClock.advanceTimeBy(800L)
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput {
            down(center)
            repeat(14) { moveBy(Offset(20f * density, 0f), delayMillis = 16L) } // 280dp < 308.25dp -> 进入左红区不翻页
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 / 3").assertIsDisplayed()
        val pxAtLeftEdge = composeRule.onRoot().captureToImage().toPixelMap()[centerPxX, centerPxY]
        assertTrue("抵左边缘前应落入左半红区（$pxAtLeftEdge）", pxAtLeftEdge.red > pxAtLeftEdge.green + 0.2f)
        composeRule.onRoot().performTouchInput {
            down(center)
            repeat(14) { moveBy(Offset(20f * density, 0f), delayMillis = 16L) } // 从接近左边缘继续右滑越边翻页
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 3").assertIsDisplayed()

        // 5) 45° 斜向拖动仲裁：在 1.0x 态下对 4 个斜向角逐帧检查（位移 ≤100dp < 120dp 阈值且慢速 600ms ≈150dp/s < 800dp/s），
        // 绝不能同时触发下拉关闭（scrim<0.96 或 heightScale<0.96）和翻页（次页入场或页码变更），且松手不应误退场
        val diagonals = listOf(
            Triple(0, Offset(-90f * density, 90f * density), "45°左下"),
            Triple(1, Offset(90f * density, 90f * density), "45°右下(从第2页可回翻第1页)"),
            Triple(0, Offset(-100f * density, 80f * density), "偏水平左下"),
            Triple(0, Offset(-80f * density, 100f * density), "偏竖直左下"),
        )
        for ((idx, spec) in diagonals.withIndex()) {
            val (startPage, totalDelta, label) = spec
            val wantText = "${startPage + 1} / 3"
            if (composeRule.onAllNodesWithText(wantText).fetchSemanticsNodes().isEmpty()) {
                val swipeDelta = if (startPage == 1) Offset(-300f * density, 0f) else Offset(300f * density, 0f)
                composeRule.onRoot().performTouchInput {
                    down(center)
                    repeat(10) { moveBy(swipeDelta / 10f, delayMillis = 16L) }
                    up()
                }
                composeRule.waitForIdle()
            }
            composeRule.onNodeWithText(wantText).assertIsDisplayed()
            val otherText = if (startPage == 0) "2 / 3" else "1 / 3"
            val restBounds = findViewerImageRect(wantText)
            var sawDismissDrag = false
            var sawPagerScrollOrFlip = false
            var minObservedScrim = 1f
            var minObservedHeightScale = 1f

            composeRule.mainClock.autoAdvance = false
            composeRule.onRoot().performTouchInput { down(center) }
            val steps = 15
            val step = totalDelta / steps.toFloat()
            repeat(steps) {
                composeRule.onRoot().performTouchInput { moveBy(step, delayMillis = 40L) }
                composeRule.mainClock.advanceTimeByFrame()
                val scrim = sampleCornerScrimAlpha()
                val curNodes = composeRule.onAllNodes(viewerImageMatcher(wantText), useUnmergedTree = true).fetchSemanticsNodes()
                // 用未被水平边界裁剪的 height 比值判定是否发生缩放（避免 pager 左滑出屏导致 boundsInWindow.width 变窄误判）
                val heightScale = curNodes.firstOrNull()?.boundsInWindow?.let { it.height / restBounds.height } ?: 1f
                if (scrim < minObservedScrim) minObservedScrim = scrim
                if (heightScale < minObservedHeightScale) minObservedHeightScale = heightScale
                if (scrim < 0.96f || heightScale < 0.96f) sawDismissDrag = true
                val otherNodes = composeRule.onAllNodes(viewerImageMatcher(otherText), useUnmergedTree = true).fetchSemanticsNodes()
                val otherInViewport = otherNodes.any {
                    val b = it.boundsInWindow
                    b.right > 2f * density && b.left < 411f * density - 2f * density && b.width > 1f
                }
                if (otherInViewport) sawPagerScrollOrFlip = true
            }
            composeRule.onRoot().performTouchInput { up() }
            composeRule.mainClock.autoAdvance = true
            composeRule.mainClock.advanceTimeBy(600L)
            composeRule.waitForIdle()
            val pageChanged = composeRule.onAllNodesWithText(wantText).fetchSemanticsNodes().isEmpty()
            if (pageChanged) sawPagerScrollOrFlip = true

            assertFalse(
                "斜向拖动 #$idx [$label] ($totalDelta) 绝不能同时触发下拉关闭(sawDismiss=$sawDismissDrag, minScrim=$minObservedScrim, minHeightScale=$minObservedHeightScale)和翻页(sawPager=$sawPagerScrollOrFlip)",
                sawDismissDrag && sawPagerScrollOrFlip,
            )
            assertEquals("斜向拖动 #$idx [$label] 未达关闭阈值时松手不应退掉浮层", 0, dismissCount)
        }
    }

    @Test
    fun probe2e_leadExtra2_singleContinuousSwipeFromCenterPastEdge_yieldsToPagerAndFlips() {
        val urls = listOf(
            "https://img.example.invalid/c0.jpg",
            "https://img.example.invalid/c1.jpg",
        )
        val stripedBmp = createThreeStripeHorizontalBitmap(800, 450, 0xFFFF2222.toInt(), 0xFF11AA44.toInt(), 0xFF1144FF.toInt())
        val loader = ProbeImageLoader { req -> SuccessResult(stripedBmp.asImage(), req, DataSource.MEMORY) }
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        ImageViewerOverlay(
                            args = ImageViewerLaunchArgs(urls),
                            onDismiss = {},
                            originalDownloader = IdleProbeDownloader(),
                            mediaActions = RecordingMediaActions(),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density.density
        val center = composeRule.onRoot().fetchSemanticsNode().boundsInWindow.center
        val restRect = findViewerImageRect("1 / 2")

        // 双击放大 2.5x（maxPanX = 308.25dp）
        composeRule.onRoot().performTouchInput { doubleClick(center) }
        composeRule.mainClock.advanceTimeBy(800L)
        composeRule.waitForIdle()
        val zoomedRect = findViewerImageRect("1 / 2")
        assertEquals("应处于 2.5x 放大态", 2.5f, zoomedRect.height / restRect.height, 0.1f)

        // 单次连续手势（不松手）：从中心一次左滑 560dp（前 308.25dp 平移到右边缘，后 251.75dp 过边续滑）
        composeRule.onRoot().performTouchInput {
            down(center)
            repeat(28) { moveBy(Offset(-20f * density, 0f), delayMillis = 16L) }
            up()
        }
        composeRule.waitForIdle()
        val centerPxAfter = composeRule.onRoot().captureToImage().toPixelMap()[center.x.roundToInt(), center.y.roundToInt()]
        assertTrue(
            "单次连续左滑至边缘并继续横滑 251dp 应触发翻页到 2 / 2（当前中心像素=$centerPxAfter，可见页码=${
                composeRule.onAllNodesWithText("1 / 2").fetchSemanticsNodes().size
            }张1/2 vs ${composeRule.onAllNodesWithText("2 / 2").fetchSemanticsNodes().size}张2/2）",
            composeRule.onAllNodesWithText("2 / 2").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    // ==================== 探针 3：原图确定进度取消、长图、GIF、保存与白名单 ====================

    @Test
    fun probe3a_throttledMockWebServer_determinateProgressAndSecondClickCancel_plusUnknownLength() {
        val server = MockWebServer()
        server.start()
        try {
            val validPng = createSyntheticPngBytes(320, 240)
            // 先扩大到一个约 24KB 的有效 PNG（含尾部 padding chunk 或多字节），用于观测分块流式进度
            val bigPayload = buildLargeValidPng(320, 240, minBytes = 24 * 1024)
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "image/png")
                    .addHeader("Content-Length", bigPayload.size.toString())
                    .body(Buffer().write(bigPayload))
                    .throttleBody(4096L, 40L, TimeUnit.MILLISECONDS)
                    .build(),
            )
            val cacheDir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "probe_orig_1").apply { deleteRecursively() }
            val downloader = OkHttpOriginalImageDownloader(OkHttpClient(), cacheDir, Dispatchers.IO)
            val states = runBlocking { downloader.download(server.url("/big.png").toString()).toList() }
            val downloading = states.filterIsInstance<OriginalDownloadState.Downloading>()
            val intermediate = downloading.mapNotNull { it.progress }.filter { it in 0.01f..0.99f }
            assertTrue("节流响应应产生多个 0<p<1 确定进度，实际 $downloading", intermediate.size >= 2)
            for (i in 1 until intermediate.size) {
                assertTrue("确定进度必须单调递增：${intermediate[i - 1]} -> ${intermediate[i]}", intermediate[i] >= intermediate[i - 1])
            }
            assertTrue("最终应 Completed", states.last() is OriginalDownloadState.Completed)

            // 未知 Content-Length（chunkedBody）回退不确定态 progress == null
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "image/png")
                    .chunkedBody(Buffer().write(validPng), maxChunkSize = 256)
                    .build(),
            )
            val unknownStates = runBlocking { downloader.download(server.url("/chunked.png").toString()).toList() }
            val unknownDownloading = unknownStates.filterIsInstance<OriginalDownloadState.Downloading>()
            assertTrue(unknownDownloading.isNotEmpty() && unknownDownloading.all { it.totalBytes == null && it.progress == null })
            assertTrue(unknownStates.last() is OriginalDownloadState.Completed)

            // 用真实 OkHttpOriginalImageDownloader + 慢节流 MockResponse 验证「点击查看原图 -> 中间确定进度取消并中止 HTTP Call -> 再次点击完成并弹 Snackbar」
            val activeCalls = CopyOnWriteArrayList<Call>()
            val baseClient = OkHttpClient()
            val gatedFactory = Call.Factory { req ->
                val call = baseClient.newCall(req)
                activeCalls.add(call)
                call
            }
            val slowPayload = buildLargeValidPng(320, 240, minBytes = 48 * 1024)
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "image/png")
                    .addHeader("Content-Length", slowPayload.size.toString())
                    .body(Buffer().write(slowPayload))
                    .throttleBody(4096L, 250L, TimeUnit.MILLISECONDS)
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "image/png")
                    .addHeader("Content-Length", validPng.size.toString())
                    .body(Buffer().write(validPng))
                    .build(),
            )
            val snackbarHostState = SnackbarHostState()
            val loader = ProbeImageLoader { req ->
                SuccessResult(ColorImage(0xFF224466.toInt(), 320, 240), req, DataSource.MEMORY)
            }
            val uiUrl = server.url("/ui_orig.png").toString()
            val realDownloader = OkHttpOriginalImageDownloader(gatedFactory, cacheDir, Dispatchers.IO)

            composeRule.setContent {
                val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                CompositionLocalProvider(LocalHiImageLoader provides loader, LocalPaneSnackbarController provides controller) {
                    HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                        Box(Modifier.fillMaxSize()) {
                            ImageViewerOverlay(
                                args = ImageViewerLaunchArgs(listOf(uiUrl)),
                                onDismiss = {},
                                originalDownloader = realDownloader,
                                mediaActions = RecordingMediaActions(),
                            )
                            SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("查看原图").performClick()
            composeRule.waitUntil(5_000L) {
                composeRule.onAllNodes(hasContentDescription("正在加载图片，点击取消"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("正在加载图片，点击取消").assertIsDisplayed().performClick()
            composeRule.waitUntil(5_000L) { activeCalls.firstOrNull()?.isCanceled() == true }
            composeRule.waitForIdle()

            assertTrue("二次点击取消必须同步取消底层 OkHttp Call", activeCalls.firstOrNull()?.isCanceled() == true)
            composeRule.onNodeWithText("查看原图").assertIsDisplayed()
            assertTrue("取消后不得弹出已加载原图提示", composeRule.onAllNodesWithText("已加载原图").fetchSemanticsNodes().isEmpty())

            // 再次点击完成下载
            composeRule.onNodeWithText("查看原图").performClick()
            composeRule.waitUntil(5_000L) {
                composeRule.onAllNodesWithText("已加载原图").fetchSemanticsNodes().size >= 2
            }
            assertTrue(composeRule.onAllNodesWithText("已加载原图").fetchSemanticsNodes().size >= 2)
        } finally {
            server.close()
        }
    }

    @Test
    fun probe3b_longImageTopAlignedNoAutoZoom_gifBadgeToggle_andSaveSnackbars() {
        val longUrl = "https://img.example.invalid/long.jpg"
        val gifUrl = "https://img.example.invalid/anim.gif"
        val animDrawable = ProbeAnimatableDrawable(intrinsicW = 480, intrinsicH = 360)
        // 上半纯绿、下半纯紫的长图（400x1600, AR = 4.0 > 2.5）
        val longBitmap = createTopBottomSplitBitmap(400, 1600, 0xFF11AA44.toInt(), 0xFFAA22CC.toInt())

        val loader = ProbeImageLoader { req ->
            when (req.data.toString()) {
                longUrl -> SuccessResult(longBitmap.asImage(), req, DataSource.NETWORK)
                gifUrl -> SuccessResult(animDrawable.asImage(shareable = false), req, DataSource.NETWORK)
                else -> ErrorResult(null, req, IllegalStateException("unexpected"))
            }
        }
        val mediaActions = RecordingMediaActions().apply { saveResult = true }
        val snackbarHostState = SnackbarHostState()
        var currentUrls by mutableStateOf(listOf(longUrl))

        composeRule.setContent {
            val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
            CompositionLocalProvider(LocalHiImageLoader provides loader, LocalPaneSnackbarController provides controller) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize()) {
                        ImageViewerOverlay(
                            args = ImageViewerLaunchArgs(currentUrls),
                            onDismiss = {},
                            originalDownloader = IdleProbeDownloader(),
                            mediaActions = mediaActions,
                        )
                        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density.density

        // 1) 长图贴宽（411dp）、顶对齐（top=0）、无自动缩放且可竖向滚动浏览到底部紫色区
        val longRect = findViewerImageRect("1 / 1")
        assertEquals("长图必须贴合视口宽度", 411f * density, longRect.width, density)
        assertEquals("长图必须顶对齐（top=0）", 0f, longRect.top, density)
        val topCenterBefore = composeRule.onRoot().captureToImage().toPixelMap()[(205f * density).roundToInt(), (350f * density).roundToInt()]
        assertTrue("长图滚动前中部应看到上半绿区", topCenterBefore.green > topCenterBefore.red + 0.2f)

        composeRule.onRoot().performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        val longRectAfterScroll = findViewerImageRect("1 / 1")
        assertEquals("长图滚动后仍保持贴宽无自动缩放", 411f * density, longRectAfterScroll.width, density)
        val topCenterAfter = composeRule.onRoot().captureToImage().toPixelMap()[(205f * density).roundToInt(), (500f * density).roundToInt()]
        assertTrue("长图上划滚动后应滚入下半紫区（before=$topCenterBefore, after=$topCenterAfter）", topCenterAfter.blue > topCenterAfter.green + 0.1f)

        // 2) 保存图片成功 -> 「已保存到相册」；失败 -> 「保存失败」
        composeRule.onNodeWithText("保存图片").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已保存到相册").assertIsDisplayed()
        snackbarHostState.currentSnackbarData?.dismiss()
        composeRule.waitForIdle()

        mediaActions.saveResult = false
        composeRule.onNodeWithText("保存图片").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("保存失败").assertIsDisplayed()

        // 3) GIF 角标点按切播停
        currentUrls = listOf(gifUrl)
        composeRule.waitForIdle()
        assertTrue("GIF 默认在非减动效下自动播放", animDrawable.isRunning)
        composeRule.onNodeWithText("GIF").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertFalse("点击 GIF 角标应暂停播放", animDrawable.isRunning)
        composeRule.onNodeWithText("GIF").performClick()
        composeRule.waitForIdle()
        assertTrue("再次点击 GIF 角标应恢复播放", animDrawable.isRunning)
    }

    @Test
    fun probe3c_schemeWhitelist_blocksAllDangerousSchemesZeroRequests() {
        val dangerousUrls = listOf(
            "file:///etc/passwd",
            "FILE:///storage/emulated/0/secret.jpg",
            "content://media/external/images/media/1",
            "Content://com.android.providers.media/1",
            "android.resource://net.jejer.hipda/drawable/ic",
            "javascript:alert(1)",
            "JavaScript:document.cookie",
            "intent://scan/#Intent;scheme=zxing;end",
            "data:image/png;base64,iVBORw0KGgo=",
            "mailto:user@example.com",
            " https://img.example.invalid/space.jpg",
            "https://img.example.invalid/space.jpg ",
            "\u200Bhttps://img.example.invalid/zwsp.jpg",
            "\uFEFFhttps://img.example.invalid/bom.jpg",
            "https://img.example.invalid/\u0000null.jpg",
            "https://img.example.invalid/line\nbreak.jpg",
            "https:///empty_host.jpg",
            "//img.example.invalid/protocol_rel.jpg",
            "img.example.invalid/no_scheme.jpg",
        )

        val server = MockWebServer()
        server.start()
        try {
            val cacheDir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "probe_sec").apply { deleteRecursively() }
            val downloader = OkHttpOriginalImageDownloader(OkHttpClient(), cacheDir, Dispatchers.IO)
            val mediaActions = AndroidImageViewerMediaActions(
                ApplicationProvider.getApplicationContext(),
                downloader,
                Dispatchers.IO,
                Dispatchers.Main,
            )
            val coilCount = AtomicInteger(0)
            val loader = ProbeImageLoader { req ->
                coilCount.incrementAndGet()
                SuccessResult(ColorImage(0xFF000000.toInt(), 100, 100), req, DataSource.MEMORY)
            }
            var testUrl by mutableStateOf(dangerousUrls.first())

            composeRule.setContent {
                CompositionLocalProvider(LocalHiImageLoader provides loader) {
                    HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                        ImageViewerOverlay(
                            args = ImageViewerLaunchArgs(listOf(testUrl)),
                            onDismiss = {},
                            originalDownloader = downloader,
                            mediaActions = mediaActions,
                        )
                    }
                }
            }

            for (bad in dangerousUrls) {
                testUrl = bad
                composeRule.waitForIdle()
                composeRule.onNodeWithText("图片加载失败").assertIsDisplayed()
                assertTrue("非法 scheme 不应显示重试按钮：$bad", composeRule.onAllNodesWithText("重试").fetchSemanticsNodes().isEmpty())
                val dlStates = runBlocking { downloader.download(bad).toList() }
                assertEquals("非法 URL 下载必须立即返回 Failed：$bad", listOf(OriginalDownloadState.Failed), dlStates)
                assertFalse("非法 URL saveImage 必须返回 false：$bad", runBlocking { mediaActions.saveImage(bad) })
                assertFalse("非法 URL shareImage 必须返回 false：$bad", runBlocking { mediaActions.shareImage(bad) })
            }

            assertEquals("所有危险 scheme 发起的 Coil 请求数必须严格为 0", 0, coilCount.get())
            assertEquals("所有危险 scheme 发起的 HTTP 请求数必须严格为 0", 0, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun probe3d_oversized12000x12000Image_opensWithoutCrashAndDecodesWithinScreenBounds() {
        val hugePng = buildOversizedSolidPng(12_000, 12_000)
        val rawBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
            BitmapFactory.decodeByteArray(hugePng, 0, hugePng.size, it)
        }
        assertEquals("构造测试图原始宽度必须为 12000", 12_000, rawBounds.outWidth)
        assertEquals("构造测试图原始高度必须为 12000", 12_000, rawBounds.outHeight)

        val server = MockWebServer()
        server.start()
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val realCoil = ImageLoader.Builder(appContext)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { OkHttpClient() }))
            }
            .build()
        val recordedRequests = CopyOnWriteArrayList<RecordedCoilExecution>()
        val spyCoil = SpyRealImageLoader(realCoil, recordedRequests)
        try {
            // 1) HD 直开 12000x12000 大图；2) 查看原图下载同一 12000x12000 PNG 经真实 Coil 解码
            repeat(2) {
                server.enqueue(
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "image/png")
                        .addHeader("Content-Length", hugePng.size.toString())
                        .body(Buffer().write(hugePng))
                        .build(),
                )
            }
            val cacheDir = File(appContext.cacheDir, "probe_12000").apply { deleteRecursively() }
            val downloader = OkHttpOriginalImageDownloader(OkHttpClient(), cacheDir, Dispatchers.IO)
            val url12k = server.url("/huge_12000x12000.png").toString()

            composeRule.setContent {
                CompositionLocalProvider(LocalHiImageLoader provides spyCoil) {
                    HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                        ImageViewerOverlay(
                            args = ImageViewerLaunchArgs(listOf(url12k)),
                            onDismiss = {},
                            originalDownloader = downloader,
                            mediaActions = RecordingMediaActions(),
                        )
                    }
                }
            }

            // 等待真实 Coil 完成 12000x12000 HD 解码且画面稳定无崩溃
            composeRule.waitUntil(10_000L) {
                recordedRequests.any { it.data == url12k && it.decodedWidth > 0 }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("1 / 1").assertIsDisplayed()

            val density = composeRule.density.density
            val viewportW = (411f * density).roundToInt() // 822px
            val viewportH = (891f * density).roundToInt() // 1782px
            val maxOrigBounds = computeOriginalDecodeBounds(viewportW, viewportH) // 1644 x 3564

            val hdExec = recordedRequests.last { it.data == url12k }
            assertFalse("直开 12000×12000 绝不能使用 Size.ORIGINAL", hdExec.resolvedSize == CoilSize.ORIGINAL)
            assertTrue(
                "直开请求的宽度上限必须受屏幕限制(${viewportW}px)，实际=${hdExec.resolvedSize}",
                (hdExec.resolvedSize.width as? Dimension.Pixels)?.px?.let { it <= viewportW } == true,
            )
            assertTrue(
                "真实 Coil 解码后的 HD 位图必须大幅低于 12000px 并受屏幕宽度约束，实际=${hdExec.decodedWidth}x${hdExec.decodedHeight}",
                hdExec.decodedWidth in 1..maxOrigBounds.width && hdExec.decodedHeight in 1..maxOrigBounds.height &&
                    hdExec.decodedWidth < 12_000 && hdExec.decodedHeight < 12_000,
            )

            // 点击「查看原图」，从 MockWebServer 下载 12000x12000 原图并通过真实 Coil 解码
            composeRule.onNodeWithText("查看原图").performClick()
            composeRule.waitUntil(10_000L) {
                composeRule.onAllNodesWithText("已加载原图").fetchSemanticsNodes().isNotEmpty() &&
                    recordedRequests.any { it.data is File && it.decodedWidth > 0 }
            }
            composeRule.waitForIdle()

            val origExec = recordedRequests.last { it.data is File }
            assertFalse("原图解码请求绝不能使用 Size.ORIGINAL", origExec.resolvedSize == CoilSize.ORIGINAL)
            val reqW = (origExec.resolvedSize.width as? Dimension.Pixels)?.px ?: error("原图宽度非像素上限: ${origExec.resolvedSize}")
            val reqH = (origExec.resolvedSize.height as? Dimension.Pixels)?.px ?: error("原图高度非像素上限: ${origExec.resolvedSize}")
            assertTrue("原图请求尺寸必须受屏幕 2× 与 4096 上限约束：$reqW x $reqH vs $maxOrigBounds", reqW <= maxOrigBounds.width && reqH <= maxOrigBounds.height)
            assertTrue(
                "真实解码原图位图尺寸必须受屏幕尺寸限制（预期 <= ${maxOrigBounds.width}x${maxOrigBounds.height}，且远小于 12000）：实际 ${origExec.decodedWidth}x${origExec.decodedHeight}",
                origExec.decodedWidth in 1..maxOrigBounds.width &&
                    origExec.decodedHeight in 1..maxOrigBounds.height &&
                    origExec.decodedWidth < 12_000 && origExec.decodedHeight < 12_000,
            )

            // 真实渲染采样与双击放大确认 Canvas 不崩溃
            composeRule.onRoot().captureToImage()
            composeRule.onRoot().performTouchInput { doubleClick(center) }
            composeRule.waitForIdle()
            composeRule.onRoot().captureToImage()
            composeRule.onNodeWithText("已加载原图").assertIsDisplayed()
        } finally {
            realCoil.shutdown()
            server.close()
        }
    }

    // ==================== 辅助函数 ====================

    private fun viewerImageMatcher(desc: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and
            hasContentDescription(desc) and
            hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "看图"))

    private fun findViewerImageRect(desc: String): Rect =
        composeRule.onNode(viewerImageMatcher(desc), useUnmergedTree = true).fetchSemanticsNode().boundsInWindow

    private fun sampleCornerScrimAlpha(): Float {
        // 采样 y=180px（避开顶部 56dp=112px 顶栏的半透明灰底 #121316，直接测纯黑遮罩区域）
        val px = composeRule.onRoot().captureToImage().toPixelMap()[4, 180]
        return (1f - px.red).coerceIn(0f, 1f)
    }

    private fun assertRectNear(msg: String, expected: Rect, actual: Rect, tolerancePx: Float) {
        assertTrue(
            "$msg: expected=$expected, actual=$actual, tol=${tolerancePx}px",
            abs(expected.left - actual.left) <= tolerancePx &&
                abs(expected.top - actual.top) <= tolerancePx &&
                abs(expected.right - actual.right) <= tolerancePx &&
                abs(expected.bottom - actual.bottom) <= tolerancePx,
        )
    }
}

internal class ProbeImageLoader(
    private val onExecute: suspend (ImageRequest) -> ImageResult,
) : ImageLoader {
    override val defaults: ImageRequest.Defaults = ImageRequest.Defaults.DEFAULT
    override val components: ComponentRegistry = ComponentRegistry()
    override val memoryCache: MemoryCache? = null
    override val diskCache: DiskCache? = null
    override fun enqueue(request: ImageRequest): Disposable {
        val d = CompletableDeferred<ImageResult>()
        return object : Disposable {
            override val job: Deferred<ImageResult> = d
            override val isDisposed: Boolean get() = d.isCancelled
            override fun dispose() { d.cancel() }
        }
    }
    override suspend fun execute(request: ImageRequest): ImageResult = onExecute(request)
    override fun shutdown() = Unit
    override fun newBuilder(): ImageLoader.Builder = throw UnsupportedOperationException()
}

internal class IdleProbeDownloader : OriginalImageDownloader {
    override fun download(url: String): Flow<OriginalDownloadState> = MutableStateFlow(OriginalDownloadState.Idle)
}

internal class RecordingMediaActions : ImageViewerMediaActions {
    var saveResult: Boolean = true
    var shareResult: Boolean = true
    override suspend fun saveImage(url: String, cachedFile: File?): Boolean = saveResult
    override suspend fun shareImage(url: String, cachedFile: File?): Boolean = shareResult
}

internal class ProbeAnimatableDrawable(
    private val intrinsicW: Int,
    private val intrinsicH: Int,
) : ColorDrawable(0xFF228866.toInt()), Animatable {
    private var running = false
    override fun getIntrinsicWidth(): Int = intrinsicW
    override fun getIntrinsicHeight(): Int = intrinsicH
    override fun start() { running = true }
    override fun stop() { running = false }
    override fun isRunning(): Boolean = running
}

internal fun createThreeStripeHorizontalBitmap(
    width: Int,
    height: Int,
    leftColor: Int,
    midColor: Int,
    rightColor: Int,
): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
    val c = Canvas(this)
    val p = Paint()
    p.color = leftColor
    c.drawRect(0f, 0f, width * 0.25f, height.toFloat(), p)
    p.color = midColor
    c.drawRect(width * 0.25f, 0f, width * 0.75f, height.toFloat(), p)
    p.color = rightColor
    c.drawRect(width * 0.75f, 0f, width.toFloat(), height.toFloat(), p)
}

internal fun createTopBottomSplitBitmap(width: Int, height: Int, topColor: Int, bottomColor: Int): Bitmap =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        val p = Paint()
        p.color = topColor
        c.drawRect(0f, 0f, width.toFloat(), height * 0.5f, p)
        p.color = bottomColor
        c.drawRect(0f, height * 0.5f, width.toFloat(), height.toFloat(), p)
    }

internal fun createSyntheticPngBytes(width: Int, height: Int): ByteArray {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
    return ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}

internal fun buildLargeValidPng(width: Int, height: Int, minBytes: Int): ByteArray {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint()
    for (y in 0 until height) {
        p.color = (0xFF000000.toInt()) or (((y * 37) and 0xFF) shl 16) or (((y * 73) and 0xFF) shl 8) or ((y * 19) and 0xFF)
        c.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), p)
    }
    var bytes = ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
    if (bytes.size >= minBytes) return bytes
    // 在 IEND 前追加标准 PNG zTXt/tEXt 辅助块以稳定撑大字节数，同时保持 100% 合法 PNG 可解码
    val extra = minBytes - bytes.size + 64
    val bmp2 = Bitmap.createBitmap(width * 2, height * 2, Bitmap.Config.ARGB_8888)
    val c2 = Canvas(bmp2)
    for (y in 0 until bmp2.height) {
        for (x in 0 until bmp2.width) {
            p.color = (0xFF000000.toInt()) or (((x * 13 + y * 37) and 0xFF) shl 16) or (((x * 29 + y * 53) and 0xFF) shl 8) or ((x * 7 + y * 19) and 0xFF)
            c2.drawPoint(x.toFloat(), y.toFloat(), p)
        }
    }
    bytes = ByteArrayOutputStream().use { out -> bmp2.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
    return bytes
}

private data class RecordedCoilExecution(
    val data: Any,
    val resolvedSize: CoilSize,
    val decodedWidth: Int,
    val decodedHeight: Int,
)

private class SpyRealImageLoader(
    private val delegate: ImageLoader,
    private val recorded: CopyOnWriteArrayList<RecordedCoilExecution>,
) : ImageLoader {
    override val defaults: ImageRequest.Defaults get() = delegate.defaults
    override val components: ComponentRegistry get() = delegate.components
    override val memoryCache: MemoryCache? get() = delegate.memoryCache
    override val diskCache: DiskCache? get() = delegate.diskCache
    override fun enqueue(request: ImageRequest): Disposable = delegate.enqueue(request)
    override suspend fun execute(request: ImageRequest): ImageResult {
        val reqSize = request.sizeResolver.size()
        val result = delegate.execute(request)
        val success = result as? SuccessResult
        recorded.add(
            RecordedCoilExecution(
                data = request.data,
                resolvedSize = reqSize,
                decodedWidth = success?.image?.width ?: -1,
                decodedHeight = success?.image?.height ?: -1,
            ),
        )
        return result
    }
    override fun shutdown() = delegate.shutdown()
    override fun newBuilder(): ImageLoader.Builder = delegate.newBuilder()
}

/** 流式生成合法的 1-bit Grayscale 超大 PNG（占用极低堆内存），用于真实测试 12000×12000 解码与降采样。 */
internal fun buildOversizedSolidPng(width: Int, height: Int): ByteArray {
    fun writeChunk(dos: DataOutputStream, type: String, payload: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        dos.writeInt(payload.size)
        dos.write(typeBytes)
        dos.write(payload)
        val crc = CRC32().apply {
            update(typeBytes)
            update(payload)
        }.value.toInt()
        dos.writeInt(crc)
    }

    val ihdr = ByteArrayOutputStream(13).apply {
        DataOutputStream(this).use { d ->
            d.writeInt(width)
            d.writeInt(height)
            d.writeByte(1) // bitDepth = 1
            d.writeByte(0) // colorType = 0 (Grayscale)
            d.writeByte(0) // compression = 0
            d.writeByte(0) // filter = 0
            d.writeByte(0) // interlace = 0
        }
    }.toByteArray()

    val bytesPerRow = (width + 7) / 8
    val blackRow = ByteArray(1 + bytesPerRow) // filter=0 + all 0s
    val whiteRow = ByteArray(1 + bytesPerRow) { idx -> if (idx == 0) 0 else 0xFF.toByte() }
    val idatBytes = ByteArrayOutputStream().use { rawOut ->
        DeflaterOutputStream(rawOut, Deflater(Deflater.BEST_SPEED)).use { def ->
            val half = height / 2
            for (y in 0 until height) {
                def.write(if (y < half) whiteRow else blackRow)
            }
        }
        rawOut.toByteArray()
    }

    return ByteArrayOutputStream().use { out ->
        DataOutputStream(out).use { dos ->
            dos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            writeChunk(dos, "IHDR", ihdr)
            writeChunk(dos, "IDAT", idatBytes)
            writeChunk(dos, "IEND", ByteArray(0))
        }
        out.toByteArray()
    }
}
