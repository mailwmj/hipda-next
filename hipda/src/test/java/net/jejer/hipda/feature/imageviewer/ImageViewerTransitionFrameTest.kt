package net.jejer.hipda.feature.imageviewer

import android.app.Application
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
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
 * E14 验收项 1：进退双分支逐帧几何测试（`autoAdvance = false`）+ 减少动态效果 0ms 一帧到位。
 *
 * - 分支 (a)：缩略图完整可见时，进入起点与退出终点对齐缩略图 `Rect`（误差 `≤ 1dp`）；系统返回走同一出口；
 * - 分支 (b)：横滑到 `resolveBoundsForIndex == null` 的图后关闭，原地缩至 `0.85f` 并淡出，不飞向屏幕外、不闪旧图；
 * - 减少动态效果：0ms 一帧到位。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ImageViewerTransitionFrameTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun imageRoleMatcher(pageIndicator: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(pageIndicator))

    private fun assertRectWithin1Dp(label: String, expected: Rect, actual: Rect, tolerancePx: Float) {
        assertTrue(
            "$label left 误差超限：期望 ${expected.left}，实际 ${actual.left}（允许 ±$tolerancePx px）",
            abs(actual.left - expected.left) <= tolerancePx,
        )
        assertTrue(
            "$label top 误差超限：期望 ${expected.top}，实际 ${actual.top}（允许 ±$tolerancePx px）",
            abs(actual.top - expected.top) <= tolerancePx,
        )
        assertTrue(
            "$label width 误差超限：期望 ${expected.width}，实际 ${actual.width}（允许 ±$tolerancePx px）",
            abs(actual.width - expected.width) <= tolerancePx,
        )
        assertTrue(
            "$label height 误差超限：期望 ${expected.height}，实际 ${actual.height}（允许 ±$tolerancePx px）",
            abs(actual.height - expected.height) <= tolerancePx,
        )
    }

    @Test
    fun branchA_visibleThumbnail_enterStartAndExitEndAlignThumbnailRectWithin1Dp() {
        val density = composeRule.density.density
        val oneDpPx = with(composeRule.density) { 1.dp.toPx() }
        // 4:3 缩略图 Rect（与 800×600 图同比例），位于屏幕中上部
        val thumbRect = Rect(
            left = 48f * density,
            top = 140f * density,
            right = 248f * density,
            bottom = 290f * density,
        )
        val loader = ViewerTestImageLoader.solid(width = 800, height = 600)
        var dismissed = false
        var overlayVisible by mutableStateOf(true)

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        if (overlayVisible) {
                            ImageViewerOverlay(
                                args = ImageViewerLaunchArgs(
                                    urls = listOf("https://img.example.invalid/a.jpg"),
                                    initialIndex = 0,
                                    sourceBoundsInWindow = thumbRect,
                                ),
                                onDismiss = {
                                    dismissed = true
                                    overlayVisible = false
                                },
                                originalDownloader = FakeOriginalImageDownloader(),
                                mediaActions = FakeImageViewerMediaActions(),
                                resolveBoundsForIndex = { idx -> if (idx == 0) thumbRect else null },
                            )
                        }
                    }
                }
            }
        }

        // Frame 0（进入起点）：必须严格对齐缩略图 Rect（≤ 1dp）
        val enterStartBounds = composeRule.onNode(imageRoleMatcher("1 / 1")).fetchSemanticsNode().boundsInWindow
        assertRectWithin1Dp("进入起点", thumbRect, enterStartBounds, oneDpPx)

        // 逐帧推进进入动画，确认经历平滑放大并落到全屏静止位
        val enterWidths = mutableListOf(enterStartBounds.width)
        repeat(80) {
            composeRule.mainClock.advanceTimeByFrame()
            enterWidths += composeRule.onNode(imageRoleMatcher("1 / 1")).fetchSemanticsNode().boundsInWindow.width
        }
        val settledBounds = composeRule.onNode(imageRoleMatcher("1 / 1")).fetchSemanticsNode().boundsInWindow
        assertTrue("进入完成后应放大至铺满屏宽（≈ ${411f * density}），实际 ${settledBounds.width}", settledBounds.width > thumbRect.width * 1.8f)
        assertTrue("进入动画必须有中间过渡帧", enterWidths.any { it > thumbRect.width + 20f && it < settledBounds.width - 20f })

        // 点击关闭，逐帧跟踪退出动画直至 onDismiss，断言最后渲染帧缩回 thumbRect（≤ 1dp）
        composeRule.onNodeWithContentDescription("关闭").performClick()
        var lastVisibleBounds = settledBounds
        var exitFrames = 0
        while (!dismissed && exitFrames < 100) {
            composeRule.mainClock.advanceTimeByFrame()
            exitFrames++
            if (overlayVisible) {
                val nodes = composeRule.onAllNodes(imageRoleMatcher("1 / 1")).fetchSemanticsNodes()
                if (nodes.isNotEmpty()) lastVisibleBounds = nodes.first().boundsInWindow
            }
        }

        assertTrue("退出动画完成后应触发 onDismiss", dismissed)
        assertTrue("退出动画应经历多帧平滑收缩，实际 $exitFrames 帧", exitFrames >= 3)
        assertRectWithin1Dp("分支 (a) 退出终点", thumbRect, lastVisibleBounds, oneDpPx)
    }

    @Test
    fun branchA_systemBackTakesSameShrinkToThumbnailExitPath() {
        val density = composeRule.density.density
        val oneDpPx = with(composeRule.density) { 1.dp.toPx() }
        val thumbRect = Rect(80f * density, 200f * density, 280f * density, 350f * density)
        val loader = ViewerTestImageLoader.solid(width = 800, height = 600)
        var dismissed = false
        var overlayVisible by mutableStateOf(true)
        var triggerBack: (() -> Unit)? = null

        composeRule.setContent {
            val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            triggerBack = { backDispatcher?.onBackPressed() }
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    if (overlayVisible) {
                        ImageViewerOverlay(
                            args = ImageViewerLaunchArgs(
                                urls = listOf("https://img.example.invalid/back.jpg"),
                                initialIndex = 0,
                                sourceBoundsInWindow = thumbRect,
                            ),
                            onDismiss = {
                                dismissed = true
                                overlayVisible = false
                            },
                            originalDownloader = FakeOriginalImageDownloader(),
                            mediaActions = FakeImageViewerMediaActions(),
                            resolveBoundsForIndex = { thumbRect },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { triggerBack?.invoke() }
        var lastVisibleBounds: Rect? = null
        var frames = 0
        while (!dismissed && frames < 100) {
            composeRule.mainClock.advanceTimeByFrame()
            frames++
            if (overlayVisible) {
                val nodes = composeRule.onAllNodes(imageRoleMatcher("1 / 1")).fetchSemanticsNodes()
                if (nodes.isNotEmpty()) lastVisibleBounds = nodes.first().boundsInWindow
            }
        }
        assertTrue("系统返回应关闭看图", dismissed)
        assertTrue("系统返回必须走退出动画而非瞬断", frames >= 3)
        assertRectWithin1Dp("系统返回退出终点", thumbRect, lastVisibleBounds!!, oneDpPx)
    }

    @Test
    fun branchB_swipedToOffscreenThumbnail_shrinksInPlaceTo085AndFadesOutWithoutFlashingPage0() {
        val density = composeRule.density.density
        val oneDpPx = with(composeRule.density) { 1.dp.toPx() }
        val page0ThumbRect = Rect(20f * density, 80f * density, 180f * density, 200f * density)
        val loader = ViewerTestImageLoader.solid(width = 800, height = 600, color = 0xFF1E88E5.toInt())
        var dismissed = false
        var overlayVisible by mutableStateOf(true)

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        if (overlayVisible) {
                            ImageViewerOverlay(
                                args = ImageViewerLaunchArgs(
                                    urls = listOf(
                                        "https://img.example.invalid/page0.jpg",
                                        "https://img.example.invalid/page1.jpg",
                                    ),
                                    initialIndex = 0,
                                    sourceBoundsInWindow = page0ThumbRect,
                                ),
                                onDismiss = {
                                    dismissed = true
                                    overlayVisible = false
                                },
                                originalDownloader = FakeOriginalImageDownloader(),
                                mediaActions = FakeImageViewerMediaActions(),
                                // 第 1 张图可见，第 2 张图在屏幕外 / 被遮挡 -> 返回 null
                                resolveBoundsForIndex = { idx -> if (idx == 0) page0ThumbRect else null },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 横滑到第 2 张图（缩略图不可见）
        composeRule.onRoot().performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 / 2").assertIsDisplayed()

        val restBounds = composeRule.onNode(imageRoleMatcher("2 / 2")).fetchSemanticsNode().boundsInWindow
        val restCenter = restBounds.center

        // 切逐帧控制，点击关闭，记录每一帧第 2 张图的几何位置与缩放
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription("关闭").performClick()

        val frameBounds = mutableListOf<Rect>()
        var sawPage0Flash = false
        var lastScrimCornerRed = 0f
        var frames = 0
        while (!dismissed && frames < 100) {
            composeRule.mainClock.advanceTimeByFrame()
            frames++
            if (overlayVisible) {
                if (composeRule.onAllNodes(imageRoleMatcher("1 / 2")).fetchSemanticsNodes().isNotEmpty()) {
                    // 检查第 1 张图是否跑到视口内（闪旧图）
                    val p0Bounds = composeRule.onNode(imageRoleMatcher("1 / 2")).fetchSemanticsNode().boundsInWindow
                    if (p0Bounds.right > 1f) sawPage0Flash = true
                }
                val p1Nodes = composeRule.onAllNodes(imageRoleMatcher("2 / 2")).fetchSemanticsNodes()
                if (p1Nodes.isNotEmpty()) {
                    frameBounds += p1Nodes.first().boundsInWindow
                }
                if (frames % 3 == 0 || frames == 1) {
                    val pixels = composeRule.onRoot().captureToImage().toPixelMap()
                    lastScrimCornerRed = pixels[4, 4].red
                }
            }
        }

        assertTrue("分支 (b) 退出完成后应触发 onDismiss", dismissed)
        assertFalse("分支 (b) 退场全程严禁闪现第 1 张图", sawPage0Flash)
        assertTrue("分支 (b) 应有逐帧采样", frameBounds.size >= 3)

        // 1. 全程中心原地不动（绝不飞向 page0ThumbRect 或屏幕外）
        for ((idx, b) in frameBounds.withIndex()) {
            assertTrue(
                "第 $idx 帧中心偏移超限：rest=$restCenter，actual=${b.center}",
                abs(b.center.x - restCenter.x) <= oneDpPx && abs(b.center.y - restCenter.y) <= oneDpPx,
            )
        }
        // 2. 最终缩放严格收敛到 0.85f
        val endBounds = frameBounds.last()
        val endScaleW = endBounds.width / restBounds.width
        val endScaleH = endBounds.height / restBounds.height
        assertEquals("最终宽缩比应为 0.85f", 0.85f, endScaleW, 0.02f)
        assertEquals("最终高缩比应为 0.85f", 0.85f, endScaleH, 0.02f)
        // 3. 背景遮罩随淡出逐渐露出底部白色背景（角像素亮度上升）
        assertTrue("退出淡出过程应可见背景亮度恢复，实际 corner red=$lastScrimCornerRed", lastScrimCornerRed > 0.3f)
    }

    @Test
    fun reducedMotion_enterAndBothExitBranchesCompleteImmediatelyInOneFrame() = withAnimatorDurationScale(0f) {
        val density = density.density
        val thumbRect = Rect(40f * density, 120f * density, 240f * density, 270f * density)
        val loader = ViewerTestImageLoader.solid(width = 800, height = 600)
        var dismissed = false
        var overlayVisible by mutableStateOf(true)

        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    if (overlayVisible) {
                        ImageViewerOverlay(
                            args = ImageViewerLaunchArgs(
                                urls = listOf("https://img.example.invalid/rm.jpg"),
                                initialIndex = 0,
                                sourceBoundsInWindow = thumbRect,
                            ),
                            onDismiss = {
                                dismissed = true
                                overlayVisible = false
                            },
                            originalDownloader = FakeOriginalImageDownloader(),
                            mediaActions = FakeImageViewerMediaActions(),
                            resolveBoundsForIndex = { thumbRect },
                        )
                    }
                }
            }
        }
        mainClock.advanceTimeByFrame()

        // 减动效下 0ms 一帧到位：首帧即处于全屏静止态且工具栏可见
        val bounds = onNode(imageRoleMatcher("1 / 1")).fetchSemanticsNode().boundsInWindow
        assertTrue("减动效进入应一帧铺满屏宽，实际 ${bounds.width}", bounds.width > thumbRect.width * 1.8f)
        onNodeWithContentDescription("关闭").assertIsDisplayed()

        // 减动效退出：点击关闭一帧完成 onDismiss
        onNodeWithContentDescription("关闭").performClick()
        mainClock.advanceTimeByFrame()
        assertTrue("减动效退出必须 0ms 一帧完成 onDismiss", dismissed)
    }
}
