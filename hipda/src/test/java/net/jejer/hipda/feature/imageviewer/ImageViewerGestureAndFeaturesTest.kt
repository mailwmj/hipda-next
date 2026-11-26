package net.jejer.hipda.feature.imageviewer

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.asImage
import coil3.decode.DataSource
import coil3.request.SuccessResult
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E14 验收项 2 & 3：
 * - 下拉关闭逐帧缩放 `1.0 → 0.8` 与遮罩 `1.0 → 0.3`（超 240dp 钳制 `0.3`）、位移/速度关闭与 `spatialSnap` 弹回；
 * - 横滑翻页不被下拉抢占、单击切工具栏不关闭、长按菜单、双击 `1.0 ↔ 2.5`、双指捏合、放大态不触发下拉关闭且边缘交给翻页；
 * - 长图（AR > 2.5）贴宽顶对齐且无加载后缩放、GIF 角标点按切播停、缩略图垫底高清淡入；
 * - 原图确定进度与二次点击取消及完成切换 `已加载原图`、保存图片成功/失败 Snackbar 与分享。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ImageViewerGestureAndFeaturesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDirectory("e14-gesture-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun imageMatcher(pageIndicator: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(pageIndicator))

    @Test
    fun pureGeometryMath_matchesDragThresholdsDoubleTapPinchAndEdgeHandoff() {
        val maxDrag = 480f // 240dp @ xhdpi
        assertEquals(DragDismissMetrics(1f, 1f), computeDragDismissMetrics(0f, maxDrag))
        val mid = computeDragDismissMetrics(240f, maxDrag)
        assertEquals(0.9f, mid.scale, 0.001f)
        assertEquals(0.65f, mid.scrimAlpha, 0.001f)
        val full = computeDragDismissMetrics(480f, maxDrag)
        assertEquals(0.8f, full.scale, 0.001f)
        assertEquals(0.3f, full.scrimAlpha, 0.001f)
        val beyond = computeDragDismissMetrics(720f, maxDrag)
        assertEquals(0.8f, beyond.scale, 0.001f)
        assertEquals(0.3f, beyond.scrimAlpha, 0.001f)

        assertFalse(shouldDismissOnRelease(240f, 1600f, 240f, 1600f))
        assertTrue(shouldDismissOnRelease(241f, 0f, 240f, 1600f))
        assertTrue(shouldDismissOnRelease(20f, 1601f, 240f, 1600f))

        // 双击放大围绕点击点并钳制边界，二次双击复位 1.0f
        val (s1, p1) = computeDoubleTapZoomTarget(
            currentScale = 1f,
            tapOffset = Offset(600f, 800f),
            contentCenter = Offset(411f, 891f),
            baseContentSize = Size(822f, 616f),
            viewportSize = Size(822f, 1782f),
        )
        assertEquals(2.5f, s1, 0.001f)
        assertTrue("右侧点击放大后应向左平移以保持点击点在手下: $p1", p1.x < 0f)
        val (s2, p2) = computeDoubleTapZoomTarget(s1, Offset(600f, 800f), Offset(411f, 891f), Size(822f, 616f), Size(822f, 1782f))
        assertEquals(1f, s2, 0.001f)
        assertEquals(Offset.Zero, p2)

        // 放大态边缘交给 HorizontalPager
        assertFalse(shouldYieldHorizontalEdgeToPager(0f, 822f, 2.5f, 822f, -30f, 0f))
        val maxPanX = (822f * 2.5f - 822f) * 0.5f
        assertTrue(shouldYieldHorizontalEdgeToPager(-maxPanX, 822f, 2.5f, 822f, -30f, 0f))
        assertFalse(shouldYieldHorizontalEdgeToPager(-maxPanX, 822f, 2.5f, 822f, 30f, 0f))
    }

    @Test
    fun verticalDrag0To240To300Dp_scales1To08AndClampsScrimAlphaAt03ThenDismisses() {
        val loader = ViewerTestImageLoader.solid(width = 800, height = 600)
        var dismissed = false
        var overlayVisible by mutableStateOf(true)
        val density = composeRule.density

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    // 白色背景衬底：使黑色遮罩的角像素亮度直接反映真实渲染的 scrimAlpha = 1 - red
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        if (overlayVisible) {
                            ImageViewerOverlay(
                                args = ImageViewerLaunchArgs(
                                    urls = listOf("https://img.example.invalid/drag.jpg"),
                                    initialIndex = 0,
                                ),
                                onDismiss = {
                                    dismissed = true
                                    overlayVisible = false
                                },
                                originalDownloader = FakeOriginalImageDownloader(),
                                mediaActions = FakeImageViewerMediaActions(),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val restWidth = composeRule.onNode(imageMatcher("1 / 1")).fetchSemanticsNode().boundsInWindow.width
        val step20dp = with(density) { 20.dp.toPx() }
        val start = with(density) { Offset(205.dp.toPx(), 260.dp.toPx()) }

        // 按住向下拖 120dp（6×20dp）-> 校验 scale ≈ 0.90, scrimAlpha ≈ 0.65
        composeRule.onRoot().performTouchInput {
            down(start)
            repeat(6) {
                moveBy(Offset(0f, step20dp), delayMillis = 16L)
            }
        }
        composeRule.waitForIdle()
        val widthAt120 = composeRule.onNode(imageMatcher("1 / 1")).fetchSemanticsNode().boundsInWindow.width
        val scrimAt120 = 1f - composeRule.onRoot().captureToImage().toPixelMap()[4, 4].red
        assertEquals("下拉 120dp 缩放应为 0.90f", 0.90f, widthAt120 / restWidth, 0.03f)
        assertEquals("下拉 120dp 遮罩应为 0.65f", 0.65f, scrimAt120, 0.05f)

        // 继续按住再拖 120dp（总 240dp）-> 校验 scale ≈ 0.80, scrimAlpha ≈ 0.30
        composeRule.onRoot().performTouchInput {
            repeat(6) {
                moveBy(Offset(0f, step20dp), delayMillis = 16L)
            }
        }
        composeRule.waitForIdle()
        val widthAt240 = composeRule.onNode(imageMatcher("1 / 1")).fetchSemanticsNode().boundsInWindow.width
        val scrimAt240 = 1f - composeRule.onRoot().captureToImage().toPixelMap()[4, 4].red
        assertEquals("下拉 240dp 缩放应为 0.80f", 0.80f, widthAt240 / restWidth, 0.03f)
        assertEquals("下拉 240dp 遮罩应为 0.30f", 0.30f, scrimAt240, 0.05f)

        // 再超拉 60dp（总 300dp > 240dp）-> 仍钳制在 0.80 与 0.30，不再更低
        composeRule.onRoot().performTouchInput {
            repeat(3) {
                moveBy(Offset(0f, step20dp), delayMillis = 16L)
            }
        }
        composeRule.waitForIdle()
        val widthAt300 = composeRule.onNode(imageMatcher("1 / 1")).fetchSemanticsNode().boundsInWindow.width
        val scrimAt300 = 1f - composeRule.onRoot().captureToImage().toPixelMap()[4, 4].red
        assertEquals("超过 240dp 缩放仍钳制在 0.80f", 0.80f, widthAt300 / restWidth, 0.03f)
        assertEquals("超过 240dp 遮罩仍钳制在 0.30f", 0.30f, scrimAt300, 0.05f)

        // 松手（位移 300dp > 120dp）-> 关闭
        composeRule.onRoot().performTouchInput { up() }
        composeRule.waitForIdle()
        assertTrue("位移 > 120dp 松手应关闭看图", dismissed)
    }

    @Test
    fun verticalDragBelowThresholdBouncesBack_andFastFlingAbove800DpPerSecDismisses() {
        val loader = ViewerTestImageLoader.solid(width = 800, height = 600)
        var dismissed = false
        val density = composeRule.density

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(urls = listOf("https://img.example.invalid/snap.jpg")),
                        onDismiss = { dismissed = true },
                        originalDownloader = FakeOriginalImageDownloader(),
                        mediaActions = FakeImageViewerMediaActions(),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val restBounds = composeRule.onNode(imageMatcher("1 / 1")).fetchSemanticsNode().boundsInWindow
        val start = with(density) { Offset(205.dp.toPx(), 300.dp.toPx()) }

        // 慢速下拉 80dp（<= 120dp 且速度 ≈ 250dp/s <= 800dp/s）-> 弹回原位，不关闭
        composeRule.onRoot().performTouchInput {
            down(start)
            val step = with(density) { 10.dp.toPx() }
            repeat(8) { moveBy(Offset(0f, step), delayMillis = 40L) }
            up()
        }
        composeRule.waitForIdle()
        assertFalse("未达位移与速度阈值不得关闭", dismissed)
        val afterBounce = composeRule.onNode(imageMatcher("1 / 1")).fetchSemanticsNode().boundsInWindow
        assertEquals("弹回后中心 Y 应恢复静止位", restBounds.center.y, afterBounce.center.y, with(density) { 2.dp.toPx() })
        assertEquals("弹回后宽应恢复 1.0f", restBounds.width, afterBounce.width, with(density) { 2.dp.toPx() })

        // 短距离高速向下甩（75dp in 48ms ≈ 1560dp/s > 800dp/s）-> 立即触发关闭
        composeRule.onRoot().performTouchInput {
            down(start)
            val step = with(density) { 25.dp.toPx() }
            repeat(3) { moveBy(Offset(0f, step), delayMillis = 16L) }
            up()
        }
        composeRule.waitForIdle()
        assertTrue("向下速度 > 800dp/s 即使位移 <= 120dp 也必须关闭", dismissed)
    }

    @Test
    fun horizontalPaging_tapChromeToggle_longPressMenu_doubleTapZoom_pinch_andZoomBlocksDismiss() {
        val loader = ViewerTestImageLoader.solid(width = 800, height = 600)
        val mediaActions = FakeImageViewerMediaActions(saveResult = true)
        val snackbars = RecordingSnackbarController()
        var dismissed = false
        val density = composeRule.density

        composeRule.setContent {
            CompositionLocalProvider(
                LocalHiImageLoader provides loader,
                LocalPaneSnackbarController provides snackbars,
            ) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(
                            urls = listOf(
                                "https://img.example.invalid/p1.jpg",
                                "https://img.example.invalid/p2.jpg",
                                "https://img.example.invalid/p3.jpg",
                            ),
                            initialIndex = 0,
                            floorNumber = 3,
                            author = "linus_hi",
                        ),
                        onDismiss = { dismissed = true },
                        originalDownloader = FakeOriginalImageDownloader(),
                        mediaActions = mediaActions,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 顶栏标题、页码与按钮最小触控区 ≥ 48dp
        composeRule.onNodeWithText("第 3 楼 · linus_hi").assertIsDisplayed()
        composeRule.onNodeWithText("1 / 3").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("关闭")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("保存图片").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("分享").assertHeightIsAtLeast(48.dp)

        // 1. 水平横滑（含轻微竖向偏移）：必须顺利翻到 2 / 3，不被下拉手势抢占
        composeRule.onRoot().performTouchInput {
            down(Offset(width * 0.85f, height * 0.5f))
            repeat(6) {
                moveBy(Offset(-width * 0.12f, 4f), delayMillis = 16L)
            }
            up()
        }
        composeRule.waitForIdle()
        assertFalse("水平横滑严禁误触发关闭", dismissed)
        composeRule.onNodeWithText("2 / 3").assertIsDisplayed()

        // 2. 单击切隐藏/显示工具栏，绝不关闭
        composeRule.onRoot().performTouchInput { click(center) }
        composeRule.mainClock.advanceTimeBy(450L)
        composeRule.waitForIdle()
        assertFalse("单击绝不可关闭看图", dismissed)
        composeRule.onNodeWithContentDescription("关闭").assertDoesNotExist()

        composeRule.onRoot().performTouchInput { click(center) }
        composeRule.mainClock.advanceTimeBy(450L)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()

        // 3. 长按弹出“保存图片 / 分享”菜单并触发保存
        composeRule.onRoot().performTouchInput { longClick(center) }
        composeRule.waitForIdle()
        // 长按菜单与底栏均展示“保存图片”，取弹层中最后一项点击
        val saveNodes = composeRule.onAllNodesWithText("保存图片")
        assertTrue("长按应弹出含“保存图片”的菜单", saveNodes.fetchSemanticsNodes().size >= 2)
        saveNodes[saveNodes.fetchSemanticsNodes().lastIndex].performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("https://img.example.invalid/p2.jpg"), mediaActions.savedUrls)
        assertTrue("保存成功应弹出“已保存到相册”", "已保存到相册" in snackbars.messages)

        // 4. 双击放大到 2.5f（4:3 图高 308dp -> 770dp < 891dp，视口内不被水平裁切）；
        //    放大态竖向下拉 260dp 不得触发关闭；放大态水平拖拽先平移图片，到边缘才交给 HorizontalPager；再次双击还原 1.0f
        val restHeight = composeRule.onNode(imageMatcher("2 / 3")).fetchSemanticsNode().boundsInWindow.height
        composeRule.onRoot().performTouchInput { doubleClick(center) }
        composeRule.waitForIdle()
        val zoomedHeight = composeRule.onNode(imageMatcher("2 / 3")).fetchSemanticsNode().boundsInWindow.height
        assertEquals("双击应放大至 2.5f", 2.5f, zoomedHeight / restHeight, 0.08f)

        composeRule.onRoot().performTouchInput {
            down(center)
            val step = with(density) { 26.dp.toPx() }
            repeat(10) { moveBy(Offset(0f, step), delayMillis = 16L) }
            up()
        }
        composeRule.waitForIdle()
        assertFalse("放大态竖向下拉严禁触发下拉关闭", dismissed)

        // 放大态短距离水平拖拽应优先平移图片，仍留在 2 / 3
        composeRule.onRoot().performTouchInput {
            down(center)
            repeat(4) { moveBy(Offset(-30f, 0f), delayMillis = 16L) }
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 / 3").assertIsDisplayed()

        composeRule.onRoot().performTouchInput { doubleClick(center) }
        composeRule.waitForIdle()
        val restoredHeight = composeRule.onNode(imageMatcher("2 / 3")).fetchSemanticsNode().boundsInWindow.height
        assertEquals("再次双击应还原 1.0f", 1.0f, restoredHeight / restHeight, 0.05f)

        // 5. 双指捏合连续放大
        composeRule.onRoot().performTouchInput {
            pinch(
                start0 = Offset(centerX - 60f, centerY),
                end0 = Offset(centerX - 150f, centerY),
                start1 = Offset(centerX + 60f, centerY),
                end1 = Offset(centerX + 150f, centerY),
                durationMillis = 160L,
            )
        }
        composeRule.waitForIdle()
        val pinchedHeight = composeRule.onNode(imageMatcher("2 / 3")).fetchSemanticsNode().boundsInWindow.height
        assertTrue("双指捏合应连续放大图片 (> 1.5f)，实际 ${pinchedHeight / restHeight}", pinchedHeight / restHeight > 1.5f)
        assertFalse("捏合不得触发关闭", dismissed)
    }

    @Test
    fun longImage_fitsWidthTopAlignedWithoutAutoZoom_andScrollsVertically() {
        val loader = ViewerTestImageLoader { request ->
            SuccessResult(
                image = createLongPosterBitmap(width = 400, height = 1600).asImage(),
                request = request,
                dataSource = DataSource.NETWORK,
            )
        }
        var dismissed = false
        val density = composeRule.density

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(urls = listOf("https://img.example.invalid/long_poster.png")),
                        onDismiss = { dismissed = true },
                        originalDownloader = FakeOriginalImageDownloader(),
                        mediaActions = FakeImageViewerMediaActions(),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInWindow
        val imgBounds = composeRule.onNode(imageMatcher("1 / 1")).fetchSemanticsNode().boundsInWindow
        val tolPx = with(density) { 1.dp.toPx() }
        assertTrue("长图必须贴合视口左缘: ${imgBounds.left}", abs(imgBounds.left - rootBounds.left) <= tolPx)
        assertTrue("长图必须贴合视口右缘（贴宽）: ${imgBounds.right} vs ${rootBounds.right}", abs(imgBounds.right - rootBounds.right) <= tolPx)
        assertTrue("长图必须对齐顶部: ${imgBounds.top} vs ${rootBounds.top}", abs(imgBounds.top - rootBounds.top) <= tolPx)

        // 校验像素：长图必须从顶部第 0 色带（#264653 深青）开始展示，绝非居中裁切到黄色中段
        val beforeScrollPixels = composeRule.onRoot().captureToImage().toPixelMap()
        val probeX = beforeScrollPixels.width / 2
        val probeY = with(density) { 80.dp.toPx().toInt() }.coerceIn(0, beforeScrollPixels.height - 1)
        val topBandPixel = beforeScrollPixels[probeX, probeY]
        assertTrue(
            "长图顶部色带应为深青 (#264653)，而非居中黄色中段，实际 RGB=(${topBandPixel.red}, ${topBandPixel.green}, ${topBandPixel.blue})",
            topBandPixel.red < 0.45f && topBandPixel.blue > 0.2f,
        )

        // 上划滚动长图：不触发关闭且视口顶部像素随滚动下移发生明显变化
        composeRule.onRoot().performTouchInput {
            down(Offset(centerX, height * 0.8f))
            repeat(10) { moveBy(Offset(0f, -90f), delayMillis = 16L) }
            up()
        }
        composeRule.waitForIdle()
        assertFalse("长图竖向滚动不得误触发关闭", dismissed)
        val afterScrollPixel = composeRule.onRoot().captureToImage().toPixelMap()[probeX, probeY]
        val colorDiff = abs(afterScrollPixel.red - topBandPixel.red) +
            abs(afterScrollPixel.green - topBandPixel.green) +
            abs(afterScrollPixel.blue - topBandPixel.blue)
        assertTrue("长图上划滚动后应滚出首段色带，实际色差=$colorDiff ($topBandPixel -> $afterScrollPixel)", colorDiff > 0.25f)
    }

    @Test
    fun gifBadge_togglesAnimatablePlayAndPause() {
        val animDrawable = FakeAnimatableDrawable(intrinsicW = 480, intrinsicH = 360)
        val loader = ViewerTestImageLoader { request ->
            SuccessResult(
                image = animDrawable.asImage(),
                request = request,
                dataSource = DataSource.NETWORK,
            )
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(urls = listOf("https://img.example.invalid/cat.gif")),
                        onDismiss = {},
                        originalDownloader = FakeOriginalImageDownloader(),
                        mediaActions = FakeImageViewerMediaActions(),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val gifNode = composeRule.onNodeWithText("GIF")
        gifNode.assertIsDisplayed().assertIsOn().assertHeightIsAtLeast(48.dp)
        assertTrue("GIF 初始应处于播放态", animDrawable.isRunning)

        // 点一下暂停
        gifNode.performClick()
        composeRule.waitForIdle()
        gifNode.assertIsOff()
        assertFalse("点一次 GIF 角标应停止播放", animDrawable.isRunning)

        // 再点一下恢复播放
        gifNode.performClick()
        composeRule.waitForIdle()
        gifNode.assertIsOn()
        assertTrue("再点一次 GIF 角标应恢复播放", animDrawable.isRunning)
    }

    @Test
    fun originalImageDownload_showsDeterminateProgress_cancelsOnSecondTap_completesAndShowsSaveShareFeedback() {
        val thumbGate = CompletableDeferred<Unit>()
        val hdGate = CompletableDeferred<Unit>()
        val thumbUrl = "https://img.example.invalid/thumb.jpg"
        val hdUrl = "https://img.example.invalid/hd.jpg"
        val loader = ViewerTestImageLoader { request ->
            if (request.data.toString() == thumbUrl) {
                thumbGate.complete(Unit)
                SuccessResult(createViewerPhotoBitmap(320, 240).asImage(), request, DataSource.MEMORY_CACHE)
            } else {
                hdGate.await()
                SuccessResult(createViewerPhotoBitmap(800, 600).asImage(), request, DataSource.NETWORK)
            }
        }

        val downloadStream = MutableSharedFlow<OriginalDownloadState>(extraBufferCapacity = 8)
        val downloader = FakeOriginalImageDownloader { downloadStream.asSharedFlow() }
        val mediaActions = FakeImageViewerMediaActions(saveResult = true)
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
                        mediaActions = mediaActions,
                        thumbnailUrls = listOf(thumbUrl),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 缩略图先垫底就绪，随后放行高清图淡入覆盖
        assertTrue("缩略图应先加载垫底", thumbGate.isCompleted)
        hdGate.complete(Unit)
        composeRule.waitForIdle()
        assertEquals(listOf(thumbUrl, hdUrl), loader.requestedUrls)

        // 1. 点击“查看原图” -> 发出确定进度 45% -> 显示确定进度与取消描述
        composeRule.onNodeWithText("查看原图").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(hdUrl), downloader.requestedUrls)
        downloadStream.tryEmit(OriginalDownloadState.Downloading(bytesRead = 4500L, totalBytes = 10_000L, progress = 0.45f))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("正在加载图片，点击取消").assertIsDisplayed()
        composeRule.onNodeWithText("45%").assertIsDisplayed()

        // 2. 再次点击 -> 立即取消下载并复位回“查看原图”
        composeRule.onNodeWithContentDescription("正在加载图片，点击取消").performClick()
        composeRule.waitForIdle()
        assertEquals("二次点击必须取消下载协程", 1, downloader.cancelledCount.get())
        composeRule.onNodeWithText("查看原图").assertIsDisplayed()

        // 3. 再次点击下载直至完成 -> 等待 IO 解码后切为“已加载原图”并弹 Snackbar
        composeRule.onNodeWithText("查看原图").performClick()
        composeRule.waitForIdle()
        val pngFile = writeTempPngFile(tempDir, "full_orig.png")
        downloadStream.tryEmit(OriginalDownloadState.Downloading(bytesRead = 6000L, totalBytes = 10_000L, progress = 0.60f))
        downloadStream.tryEmit(OriginalDownloadState.Completed(file = pngFile, mimeType = "image/png"))
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("已加载原图").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("已加载原图").assertIsDisplayed()
        assertTrue("完成后应提示“已加载原图”", "已加载原图" in snackbars.messages)

        // 4. 保存成功/失败发对应 Snackbar，分享转发合法 URL
        composeRule.onNodeWithText("保存图片").performClick()
        composeRule.waitForIdle()
        assertTrue("保存成功 Snackbar 缺失: ${snackbars.messages}", "已保存到相册" in snackbars.messages)

        mediaActions.saveResult = false
        composeRule.onNodeWithText("保存图片").performClick()
        composeRule.waitForIdle()
        assertTrue("保存失败 Snackbar 缺失: ${snackbars.messages}", "保存失败" in snackbars.messages)

        composeRule.onNodeWithText("分享").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(hdUrl), mediaActions.sharedUrls)
    }
}
