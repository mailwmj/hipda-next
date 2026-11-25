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
                    onNodeWithText("查看详情").performSemanticsAction(SemanticsActions.OnClick)
                }
            },
        )
        assertTrue("正常展开应产生有效高度变化", normalExpand.maxStepDp > 1.dp)
        val expandedY = normalExpand.positionsPx.last().y
        assertTrue("展开后下方锚点应明显下移：$expandedY vs $initialY", expandedY > initialY + with(density) { 40.dp.toPx() })

        // 复位为收起态
        mainClock.autoAdvance = true
        onNodeWithText("收起详情").performClick()
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
                    onNodeWithText("查看详情").performSemanticsAction(SemanticsActions.OnClick)
                } else if (frame == 5) {
                    onNodeWithText("收起详情").performSemanticsAction(SemanticsActions.OnClick)
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
            onNodeWithText("查看详情").performClick()
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
            onNodeWithText("收起详情").performClick()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            waitForIdle()

            val afterCollapseY = onNodeWithTag("anchor_below_error").fetchSemanticsNode().positionInRoot.y
            assertEquals("缩放为 0 收起应立即回到初始高度", collapsedY, afterCollapseY, 1f)
            onNodeWithText("复制详情").assertDoesNotExist()
            onNodeWithText(sampleDetails).assertDoesNotExist()
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

    // region Step 3b: HiContentState Loading -> Content 顶部与水平零位移 + Empty/Error 分支 + 150ms 不闪骨架

    @Test
    fun contentState_loadingToContent_keepsFirstItemTopAndHorizontalPositionWithin1dp() =
        withAnimatorDurationScale(1f) {
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

            // 超过 150ms 后骨架显示，记录首项相对容器的顶部与水平位置
            mainClock.advanceTimeBy(180L)
            waitForIdle()
            onNodeWithTag("first_skeleton_item").assertIsDisplayed()

            val containerPos = onNodeWithTag("content_state_container").fetchSemanticsNode().positionInRoot
            val skeletonPos = onNodeWithTag("first_skeleton_item").fetchSemanticsNode().positionInRoot
            val skeletonTopDistancePx = skeletonPos.y - containerPos.y
            val skeletonLeftDistancePx = skeletonPos.x - containerPos.x

            // 切换到 Content，越过 250ms 最短停留进入交叉淡入过渡中帧
            loadState = HiLoadState.Content(listOf("第 1 条帖子", "第 2 条帖子"))
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(260L)
            waitForIdle()
            onNodeWithTag("first_content_item").assertIsDisplayed()
            val midContentPos = onNodeWithTag("first_content_item").fetchSemanticsNode().positionInRoot
            val tolerancePx = with(density) { 1.dp.toPx() }
            assertTrue(
                "交叉淡入过渡期间第一项距顶偏移超过 1dp：skeleton=$skeletonTopDistancePx, content=${midContentPos.y - containerPos.y}",
                abs((midContentPos.y - containerPos.y) - skeletonTopDistancePx) <= tolerancePx,
            )
            assertEquals(
                "交叉淡入过渡期间第一项水平坐标应零位移",
                skeletonLeftDistancePx,
                midContentPos.x - containerPos.x,
                0.5f,
            )

            // 过渡完全结束后再次验证 y 与 x
            mainClock.advanceTimeBy(400L)
            waitForIdle()
            val settledContentPos = onNodeWithTag("first_content_item").fetchSemanticsNode().positionInRoot
            assertTrue(
                "替换完成后第一项距顶偏移超过 1dp：skeleton=$skeletonTopDistancePx, content=${settledContentPos.y - containerPos.y}",
                abs((settledContentPos.y - containerPos.y) - skeletonTopDistancePx) <= tolerancePx,
            )
            assertEquals(
                "替换完成后第一项水平坐标应零位移",
                skeletonLeftDistancePx,
                settledContentPos.x - containerPos.x,
                0.5f,
            )
        }

    @Test
    fun contentState_emptyAndErrorBranches_renderExpectedSlotsAndInvokeRetry() =
        withAnimatorDurationScale(0f) {
            var retryCount by mutableIntStateOf(0)
            var loadState by mutableStateOf<HiLoadState<String>>(HiLoadState.Empty)

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        HiContentState(
                            state = loadState,
                            modifier = Modifier.fillMaxSize(),
                            skeleton = { HiSkeletonBlock(width = 120.dp, height = 24.dp) },
                            empty = { HiEmptyState(message = "还没有收藏") },
                            error = { err ->
                                HiErrorState(
                                    message = err.message,
                                    action = err.retry?.let { HiStateAction(label = "重试", onClick = it) },
                                    details = err.details,
                                )
                            },
                        ) { value ->
                            Text(text = value)
                        }
                    }
                }
            }
            waitForIdle()
            onNodeWithText("还没有收藏").assertIsDisplayed()

            loadState = HiLoadState.Error(
                message = "网络连接失败",
                details = "SocketTimeoutException",
                retry = { retryCount++ },
            )
            waitForIdle()
            onNodeWithText("还没有收藏").assertDoesNotExist()
            onNodeWithText("网络连接失败").assertIsDisplayed()
            onNodeWithText("查看详情").assertIsDisplayed()
            onNodeWithText("重试").performClick()
            waitForIdle()
            assertEquals("HiContentState Error 分支点击重试应触发 retry 回调", 1, retryCount)
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

    // region Step 3d: 无障碍（触摸目标 ≥ 48dp、动作按钮回调、HiErrorState 主提示不含 details）

    @Test
    fun accessibility_buttonsAndFooterTouchTargetsAtLeast48dp_andErrorPrimaryExcludesDetails() =
        withAnimatorDurationScale(0f) {
            var emptyActionClicks by mutableIntStateOf(0)
            var errorActionClicks by mutableIntStateOf(0)
            var footerRetryClicks by mutableIntStateOf(0)
            var footerState by mutableStateOf<HiFooterState>(HiFooterState.Failed { footerRetryClicks++ })

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            HiEmptyState(
                                message = "暂无帖子",
                                action = HiStateAction(label = "刷新", onClick = { emptyActionClicks++ }),
                            )
                            HiErrorState(
                                message = "网络连接失败",
                                action = HiStateAction(label = "重试", onClick = { errorActionClicks++ }),
                                details = "java.io.IOException: HTTP 504 Gateway Timeout",
                            )
                            HiListFooter(
                                state = footerState,
                                modifier = Modifier.testTag("list_footer"),
                            )
                        }
                    }
                }
            }
            waitForIdle()

            val minTouchPx = with(density) { 48.dp.toPx() } - 0.5f

            fun assertMinTouchTarget(label: String, node: androidx.compose.ui.semantics.SemanticsNode) {
                val bounds = node.boundsInRoot
                val touchBounds = node.touchBoundsInRoot
                assertTrue(
                    "$label 宽度应 ≥ 48dp，实际 bounds=${bounds.width}px touch=${touchBounds.width}px",
                    bounds.width >= minTouchPx && touchBounds.width >= minTouchPx,
                )
                assertTrue(
                    "$label 高度应 ≥ 48dp，实际 bounds=${bounds.height}px touch=${touchBounds.height}px",
                    bounds.height >= minTouchPx && touchBounds.height >= minTouchPx,
                )
            }

            // 1) 空状态按钮、错误状态按钮与“查看详情”按钮均 ≥ 48dp，且点击触发各自 onClick
            assertMinTouchTarget("空状态动作按钮", onNodeWithText("刷新").fetchSemanticsNode())
            assertMinTouchTarget("错误状态动作按钮", onNodeWithText("重试").fetchSemanticsNode())
            assertMinTouchTarget("查看详情按钮", onNodeWithText("查看详情").fetchSemanticsNode())

            onNodeWithText("刷新").performClick()
            onNodeWithText("重试").performClick()
            waitForIdle()
            assertEquals("点击 HiEmptyState 动作按钮应触发 onClick", 1, emptyActionClicks)
            assertEquals("点击 HiErrorState 动作按钮应触发 onClick", 1, errorActionClicks)

            // 2) 收起态下，主提示仅含原因短语，树中不得出现 details 文本
            val primaryTextCollapsed = onNodeWithText("网络连接失败")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString("") { it.text }
            assertEquals("网络连接失败", primaryTextCollapsed)
            assertFalse("主提示不得包含异常类名", primaryTextCollapsed.contains("IOException"))
            assertFalse("主提示不得包含状态码", primaryTextCollapsed.contains("504"))
            onNodeWithText("IOException", substring = true).assertDoesNotExist()
            onNodeWithText("504", substring = true).assertDoesNotExist()

            // 3) 展开后，主提示仍不含 details，且“复制详情”按钮 ≥ 48dp
            onNodeWithText("查看详情").performClick()
            waitForIdle()
            val primaryTextExpanded = onNodeWithText("网络连接失败")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .joinToString("") { it.text }
            assertEquals("网络连接失败", primaryTextExpanded)
            assertFalse(primaryTextExpanded.contains("IOException"))
            assertMinTouchTarget("复制详情按钮", onNodeWithText("复制详情").fetchSemanticsNode())

            // 4) 列表尾部三种状态整行触摸目标 ≥ 48dp，且 Failed 整行可点
            assertMinTouchTarget("列表尾部 Failed 行", onNodeWithTag("list_footer").fetchSemanticsNode())
            onNodeWithTag("list_footer").performClick()
            waitForIdle()
            assertEquals("Failed 状态整行点击应触发 onRetry", 1, footerRetryClicks)

            footerState = HiFooterState.Loading
            waitForIdle()
            onNodeWithText("正在加载…").assertIsDisplayed()
            assertMinTouchTarget("列表尾部 Loading 行", onNodeWithTag("list_footer").fetchSemanticsNode())

            footerState = HiFooterState.End
            waitForIdle()
            onNodeWithText("没有更多了").assertIsDisplayed()
            assertMinTouchTarget("列表尾部 End 行", onNodeWithTag("list_footer").fetchSemanticsNode())
        }

    // endregion
}
