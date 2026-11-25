package net.jejer.hipda.ui.designsystem.state

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import net.jejer.hipda.testing.assertFrameContinuity
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiScreenshotTestSupport
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = Application::class,
    sdk = [34],
    qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411,
)
class HiStateBehaviorTest {

    private val sampleDetails = """
        java.net.SocketTimeoutException: connect timed out
        at okhttp3.internal.connection.RealConnection.connectSocket(RealConnection.kt:297)
        at net.jejer.hipda.data.network.ForumClient.fetchThreadList(ForumClient.kt:84)
        GET /forum/forumdisplay.php?fid=2&page=1
    """.trimIndent()

    // region Step 3a: HiErrorState 展开详情可打断 + 减少动态效果下一帧到位 + 复制详情

    @Test
    fun errorState_detailsExpandAndInterruptCollapse_isFrameContinuous() = withAnimatorDurationScale(1f) {
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        HiErrorState(
                            message = "网络连接失败",
                            action = HiStateAction(label = "重试", onClick = {}),
                            details = sampleDetails,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .testTag("anchor_below_error"),
                        )
                    }
                }
            }
        }
        waitForIdle()

        val initialY = onNodeWithTag("anchor_below_error").fetchSemanticsNode().positionInRoot.y

        // 1) 测量正常完整展开过程中的单帧最大位移，同时作为后续打断测试的基准上限
        //    使用 performSemanticsAction(OnClick) 触发点击，避免 performClick() 自带的触摸事件推进额外消耗一帧时钟
        val normalExpand = assertFrameContinuity(
            nodeProvider = { onNodeWithTag("anchor_below_error") },
            frameCount = 24,
            maxStepDp = 80.dp,
            minTotalTravelDp = 20.dp,
            onFrame = { frame ->
                if (frame == 0) {
                    onNodeWithTag(HiStateTestTags.ERROR_DETAILS_TOGGLE)
                        .performSemanticsAction(SemanticsActions.OnClick)
                }
            },
        )
        assertTrue("正常展开应产生有效高度变化", normalExpand.maxStepDp > 1.dp)
        val expandedY = normalExpand.positionsPx.last().y
        assertTrue("展开后下方锚点应明显下移：$expandedY vs $initialY", expandedY > initialY + with(density) { 40.dp.toPx() })

        // 复位为收起态
        mainClock.autoAdvance = true
        onNodeWithTag(HiStateTestTags.ERROR_DETAILS_TOGGLE).performClick()
        waitForIdle()
        val resetY = onNodeWithTag("anchor_below_error").fetchSemanticsNode().positionInRoot.y
        assertEquals(initialY, resetY, 1f)

        // 2) 展开到一半时（第 5 帧）中途触发“收起详情”，验证高度逐帧连续无跳变
        val interruptResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag("anchor_below_error") },
            frameCount = 30,
            maxStepDp = normalExpand.maxStepDp + 1.dp,
            minTotalTravelDp = 20.dp,
            onFrame = { frame ->
                if (frame == 0) {
                    onNodeWithTag(HiStateTestTags.ERROR_DETAILS_TOGGLE)
                        .performSemanticsAction(SemanticsActions.OnClick)
                } else if (frame == 5) {
                    onNodeWithTag(HiStateTestTags.ERROR_DETAILS_TOGGLE)
                        .performSemanticsAction(SemanticsActions.OnClick)
                }
            },
        )

        val peakY = interruptResult.positionsPx.maxOf { it.y }
        val finalY = interruptResult.positionsPx.last().y
        assertTrue(
            "打断前应已展开一定高度（峰值 $peakY 应大于初始 $initialY + 10dp）",
            peakY > initialY + with(density) { 10.dp.toPx() },
        )
        assertTrue(
            "打断时应处于半展开状态（峰值 $peakY 应小于完全展开 $expandedY）",
            peakY < expandedY - 1f,
        )
        assertTrue(
            "反向收起后末帧高度 $finalY 应回落至峰值 $peakY 以下",
            finalY < peakY - with(density) { 10.dp.toPx() },
        )
    }

    @Test
    fun errorState_detailsExpandAndCollapse_completesInOneAnimationFrameUnderReducedMotion() =
        withAnimatorDurationScale(0f) {
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            HiErrorState(
                                message = "网络连接失败",
                                action = HiStateAction(label = "重试", onClick = {}),
                                details = sampleDetails,
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(16.dp)
                                    .testTag("anchor_below_error"),
                            )
                        }
                    }
                }
            }
            waitForIdle()
            val collapsedY = onNodeWithTag("anchor_below_error").fetchSemanticsNode().positionInRoot.y

            mainClock.autoAdvance = false
            onNodeWithTag(HiStateTestTags.ERROR_DETAILS_TOGGLE).performClick()
            mainClock.advanceTimeByFrame() // 触发重组与状态切换
            mainClock.advanceTimeByFrame() // 动画首帧：scale=0 立即到达目标尺寸
            mainClock.advanceTimeByFrame() // 提交最终布局
            waitForIdle()

            onNodeWithText("收起详情").assertIsDisplayed()
            onNodeWithText("复制详情").assertIsDisplayed()
            val expandedYAfterOneAnimFrame =
                onNodeWithTag("anchor_below_error").fetchSemanticsNode().positionInRoot.y
            assertTrue(
                "缩放为 0 时应已完全展开：$expandedYAfterOneAnimFrame vs $collapsedY",
                expandedYAfterOneAnimFrame > collapsedY + with(density) { 40.dp.toPx() },
            )

            // 再推进多帧，位置不再变化（无残留中间帧）
            repeat(10) { mainClock.advanceTimeByFrame() }
            waitForIdle()
            val settledExpandedY = onNodeWithTag("anchor_below_error").fetchSemanticsNode().positionInRoot.y
            assertEquals("缩放为 0 展开后不应残留中间状态", expandedYAfterOneAnimFrame, settledExpandedY, 0.5f)

            // 同样验证收起一帧到位且详情节点彻底移除
            onNodeWithTag(HiStateTestTags.ERROR_DETAILS_TOGGLE).performClick()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            waitForIdle()

            val afterCollapseY = onNodeWithTag("anchor_below_error").fetchSemanticsNode().positionInRoot.y
            assertEquals("缩放为 0 收起应立即回到初始高度", collapsedY, afterCollapseY, 1f)
            onNodeWithTag(HiStateTestTags.ERROR_DETAILS_PANEL).assertDoesNotExist()
        }

    @Test
    fun errorState_copyDetails_writesDetailsToClipboard() = withAnimatorDurationScale(0f) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = requireNotNull(context.getSystemService(ClipboardManager::class.java))
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("init", ""))

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiErrorState(
                    message = "加载失败",
                    details = sampleDetails,
                )
            }
        }
        waitForIdle()

        onNodeWithText("查看详情").performClick()
        waitForIdle()
        onNodeWithText("收起详情").assertIsDisplayed()
        onNodeWithText("复制详情").assertIsDisplayed().performClick()
        waitForIdle()

        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals(sampleDetails, copied)
    }

    // endregion

    // region Step 3b: HiContentState Loading -> Content 顶部距离不变 + 150ms 不闪骨架 + 数据更新不重建

    @Test
    fun contentState_loadingToContent_keepsFirstItemTopDistanceWithin1dp() = withAnimatorDurationScale(1f) {
        var loadState by mutableStateOf<HiLoadState<List<String>>>(HiLoadState.Loading)

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HiContentState(
                        state = loadState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("content_state_container"),
                        skeleton = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                HiSkeletonBlock(
                                    width = 240.dp,
                                    height = 56.dp,
                                    shape = HiShapeTokens.Medium,
                                    modifier = Modifier.testTag("first_skeleton_item"),
                                )
                                HiSkeletonBlock(width = 240.dp, height = 56.dp, shape = HiShapeTokens.Medium)
                            }
                        },
                        empty = { HiEmptyState(message = "暂无帖子") },
                        error = { err -> HiErrorState(message = err.message, details = err.details) },
                    ) { items ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items.forEachIndexed { index, title ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .then(if (index == 0) Modifier.testTag("first_content_item") else Modifier),
                                ) {
                                    Text(text = title)
                                }
                            }
                        }
                    }
                }
            }
        }

        mainClock.autoAdvance = false
        mainClock.advanceTimeByFrame()
        waitForIdle()
        // 150ms 内不显示骨架
        onNodeWithTag("first_skeleton_item").assertDoesNotExist()

        // 超过 150ms 后骨架显示，记录首项到容器顶部距离
        mainClock.advanceTimeBy(180L)
        waitForIdle()
        onNodeWithTag("first_skeleton_item").assertIsDisplayed()

        val containerTop = onNodeWithTag("content_state_container").fetchSemanticsNode().positionInRoot.y
        val skeletonTopDistancePx =
            onNodeWithTag("first_skeleton_item").fetchSemanticsNode().positionInRoot.y - containerTop

        // 切换到 Content，并越过 250ms 最短停留与交叉淡入淡出
        loadState = HiLoadState.Content(listOf("第 1 条帖子", "第 2 条帖子"))
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(260L)
        // 检查过渡中帧：首项距离已对齐
        waitForIdle()
        onNodeWithTag("first_content_item").assertIsDisplayed()
        val midContentTopDistancePx =
            onNodeWithTag("first_content_item").fetchSemanticsNode().positionInRoot.y - containerTop
        val tolerancePx = with(density) { 1.dp.toPx() }
        assertTrue(
            "交叉淡入过渡期间第一项距顶偏移超过 1dp：skeleton=$skeletonTopDistancePx, content=$midContentTopDistancePx",
            abs(midContentTopDistancePx - skeletonTopDistancePx) <= tolerancePx,
        )

        // 过渡完全结束后再次验证
        mainClock.advanceTimeBy(400L)
        waitForIdle()
        val settledContentTopDistancePx =
            onNodeWithTag("first_content_item").fetchSemanticsNode().positionInRoot.y - containerTop
        assertTrue(
            "替换完成后第一项距顶偏移超过 1dp：skeleton=$skeletonTopDistancePx, content=$settledContentTopDistancePx",
            abs(settledContentTopDistancePx - skeletonTopDistancePx) <= tolerancePx,
        )
    }

    @Test
    fun contentState_finishesWithin150ms_neverShowsSkeletonAndPreservesStateOnContentUpdate() =
        withAnimatorDurationScale(0f) {
            var loadState by mutableStateOf<HiLoadState<String>>(HiLoadState.Loading)
            var skeletonComposed = false
            var rememberInstantiations = 0

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    HiContentState(
                        state = loadState,
                        modifier = Modifier.fillMaxSize(),
                        skeleton = {
                            skeletonComposed = true
                            HiSkeletonBlock(width = 120.dp, height = 24.dp, modifier = Modifier.testTag("sk"))
                        },
                        empty = { HiEmptyState(message = "暂无帖子") },
                        error = { err -> HiErrorState(message = err.message) },
                    ) { value ->
                        val localId = remember { ++rememberInstantiations }
                        Text(text = "$value#$localId")
                    }
                }
            }

            mainClock.autoAdvance = false
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(100L)
            loadState = HiLoadState.Content("v1")
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeBy(50L)
            waitForIdle()

            assertFalse("150ms 内完成加载不应闪烁或组合骨架", skeletonComposed)
            onNodeWithText("v1#1").assertIsDisplayed()

            // Content 内部数据更新不触发重建（保留本地 remember 状态）
            loadState = HiLoadState.Content("v2")
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeBy(50L)
            waitForIdle()

            onNodeWithText("v2#1").assertIsDisplayed()
            assertEquals("Content 增量更新不应重建子树", 1, rememberInstantiations)
        }

    // endregion

    // region Step 3c: 骨架在缩放为 0 时相隔 500ms 两帧像素/透明度完全一致（含缩放 1f 对照组）

    @Test
    fun skeleton_underScaleZero_twoFrames500msApartArePixelIdentical() {
        var surfaceBackgroundArgb = 0

        // 必须在 setContent 前关闭 autoAdvance，避免 Compose 测试环境的 InfiniteAnimationPolicy
        // 提前取消无限动画，从而确保 scale=0f 的静止结果完全由组件自身的减少动态效果逻辑保证。
        withAnimatorDurationScale(0f) {
            mainClock.autoAdvance = false
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    surfaceBackgroundArgb = MaterialTheme.colorScheme.background.toArgb()
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Box(modifier = Modifier.padding(16.dp)) {
                            HiSkeletonBlock(
                                width = 120.dp,
                                height = 24.dp,
                                shape = HiShapeTokens.Small,
                                modifier = Modifier.testTag("skeleton_block"),
                            )
                        }
                    }
                }
            }

            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            waitForIdle()
            val frame1 = onNodeWithTag("skeleton_block").captureToImage().asAndroidBitmap()
            val centerPixel1 = frame1.getPixel(frame1.width / 2, frame1.height / 2)
            assertNotEquals(
                "缩放为 0 时骨架应停在可见的固定中间明度，而非透明背景色",
                surfaceBackgroundArgb,
                centerPixel1,
            )

            mainClock.advanceTimeBy(500L)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            val frame2 = onNodeWithTag("skeleton_block").captureToImage().asAndroidBitmap()
            assertTrue("缩放为 0 时相隔 500ms 的两帧像素应完全一致", frame1.sameAs(frame2))
        }

        // 对照组：正常动画缩放 1f 下，骨架按 HiMotion.skeletonRepeatable() 在相隔 500ms 的两帧间产生可见明暗呼吸
        withAnimatorDurationScale(1f) {
            mainClock.autoAdvance = false
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        Box(modifier = Modifier.padding(16.dp)) {
                            HiSkeletonBlock(
                                width = 120.dp,
                                height = 24.dp,
                                shape = HiShapeTokens.Small,
                                modifier = Modifier.testTag("skeleton_block"),
                            )
                        }
                    }
                }
            }

            mainClock.advanceTimeByFrame() // 首次组合
            mainClock.advanceTimeByFrame() // 启动无限过渡协程并注册首帧回调
            mainClock.advanceTimeByFrame() // 首帧回调确立 startTimeNanos
            waitForIdle()
            val frameStart = onNodeWithTag("skeleton_block").captureToImage().asAndroidBitmap()

            mainClock.advanceTimeBy(500L)
            mainClock.advanceTimeByFrame()
            waitForIdle()
            val frameAfter500ms = onNodeWithTag("skeleton_block").captureToImage().asAndroidBitmap()

            assertFalse(
                "正常缩放 1f 下相隔 500ms 的骨架两帧亮度应发生变化",
                frameStart.sameAs(frameAfter500ms),
            )
        }
    }

    // endregion

    // region Step 3d: 无障碍（触摸目标 ≥ 48dp、HiErrorState 主提示不含 details）

    @Test
    fun accessibility_buttonsAndFooterTouchTargetsAtLeast48dp_andErrorPrimaryExcludesDetails() =
        withAnimatorDurationScale(0f) {
            var retryClicks by mutableIntStateOf(0)
            var footerState by mutableStateOf<HiFooterState>(HiFooterState.Failed { retryClicks++ })

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            HiEmptyState(
                                message = "暂无帖子",
                                action = HiStateAction(label = "刷新", onClick = {}),
                            )
                            HiErrorState(
                                message = "网络连接失败",
                                action = HiStateAction(label = "重试", onClick = {}),
                                details = "java.io.IOException: HTTP 504 Gateway Timeout",
                            )
                            HiListFooter(state = footerState)
                        }
                    }
                }
            }
            waitForIdle()

            val minTouchPx = with(density) { 48.dp.toPx() } - 0.5f

            fun assertMinTouchTarget(tag: String) {
                val node = onNodeWithTag(tag).fetchSemanticsNode()
                val bounds = node.boundsInRoot
                val touchBounds = node.touchBoundsInRoot
                assertTrue(
                    "$tag 宽度应 ≥ 48dp，实际 bounds=${bounds.width}px touch=${touchBounds.width}px",
                    bounds.width >= minTouchPx && touchBounds.width >= minTouchPx,
                )
                assertTrue(
                    "$tag 高度应 ≥ 48dp，实际 bounds=${bounds.height}px touch=${touchBounds.height}px",
                    bounds.height >= minTouchPx && touchBounds.height >= minTouchPx,
                )
            }

            // 1) 空状态按钮、错误状态按钮与“查看详情”按钮均 ≥ 48dp
            assertMinTouchTarget(HiStateTestTags.EMPTY_ACTION)
            assertMinTouchTarget(HiStateTestTags.ERROR_ACTION)
            assertMinTouchTarget(HiStateTestTags.ERROR_DETAILS_TOGGLE)

            // 2) 收起态下，主提示仅含原因短语，树中不得出现 details 文本
            val primaryTextCollapsed = onNodeWithTag(HiStateTestTags.ERROR_MESSAGE)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString("") { it.text }
            assertEquals("网络连接失败", primaryTextCollapsed)
            assertFalse("主提示不得包含异常类名", primaryTextCollapsed.contains("IOException"))
            assertFalse("主提示不得包含状态码", primaryTextCollapsed.contains("504"))
            onNodeWithText("IOException", substring = true).assertDoesNotExist()
            onNodeWithText("504", substring = true).assertDoesNotExist()

            // 3) 展开后，主提示仍不含 details，且“复制详情”按钮 ≥ 48dp
            onNodeWithTag(HiStateTestTags.ERROR_DETAILS_TOGGLE).performClick()
            waitForIdle()
            val primaryTextExpanded = onNodeWithTag(HiStateTestTags.ERROR_MESSAGE)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString("") { it.text }
            assertEquals("网络连接失败", primaryTextExpanded)
            assertFalse(primaryTextExpanded.contains("IOException"))
            assertMinTouchTarget(HiStateTestTags.ERROR_COPY_DETAILS)

            // 4) 列表尾部三种状态整行触摸目标 ≥ 48dp，且 Failed 整行可点
            assertMinTouchTarget(HiStateTestTags.LIST_FOOTER)
            onNodeWithTag(HiStateTestTags.LIST_FOOTER).performClick()
            waitForIdle()
            assertEquals("Failed 状态整行点击应触发 onRetry", 1, retryClicks)

            footerState = HiFooterState.Loading
            waitForIdle()
            onNodeWithText("正在加载…").assertIsDisplayed()
            assertMinTouchTarget(HiStateTestTags.LIST_FOOTER)

            footerState = HiFooterState.End
            waitForIdle()
            onNodeWithText("没有更多了").assertIsDisplayed()
            assertMinTouchTarget(HiStateTestTags.LIST_FOOTER)
        }

    // endregion
}
