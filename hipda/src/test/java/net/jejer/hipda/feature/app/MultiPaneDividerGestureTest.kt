package net.jejer.hipda.feature.app

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.shell.HiShellTestTags
import net.jejer.hipda.ui.shell.calculateShellPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E3 验收项 1 + R-E3 B1/B3/S2：
 * - Fold8 `64|274|411` 精确几何、空右栏锚点硬底线（B4-E3 ①）；
 * - 父容器横向门控手势不拦截中栏右上「更多」、右栏左上「返回」与交界附近列表竖滑（R-E3 B1）；
 * - 拖拽逐帧跟手/初速吸附/横竖屏持久化、冷流首帧恢复无闪烁（R-E3 B3）、空右栏回退后开帖对齐持久化（R-E3 S2）；
 * - 无障碍 CustomActions + 键盘切换、减动效 0ms 到位与详情顶栏全屏阅读联动。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MultiPaneDividerGestureTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun paneMatcher(title: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun fold8_749x559_exactPaneGeometry_64_274_411_andZeroLayoutWidthDivider() {
        val harness = MultiPaneTestHarness()
        composeRule.setContent { harness.RenderRoot() }
        composeRule.waitForIdle()

        val density = composeRule.density
        val sidebarBounds = composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).fetchSemanticsNode().boundsInRoot
        val listBounds = composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot
        val detailBounds = composeRule.onNode(paneMatcher("正文")).fetchSemanticsNode().boundsInRoot
        val dividerBounds = composeRule.onNodeWithTag(HiShellTestTags.PaneDivider).fetchSemanticsNode().boundsInRoot

        assertEquals("Fold8 左栏宽必须精确为 64dp", 64f, with(density) { sidebarBounds.width.toDp().value }, 0.5f)
        assertEquals("Fold8 中栏起始必须紧贴 64dp", 64f, with(density) { listBounds.left.toDp().value }, 0.5f)
        assertEquals("Fold8 中栏宽必须精确为 274dp", 274f, with(density) { listBounds.width.toDp().value }, 0.5f)
        assertEquals("Fold8 右栏起始必须紧贴 338dp（分隔线 0dp 布局宽）", 338f, with(density) { detailBounds.left.toDp().value }, 0.5f)
        assertEquals("Fold8 右栏宽必须精确为 411dp", 411f, with(density) { detailBounds.width.toDp().value }, 0.5f)
        assertEquals("分隔线触控/无障碍热区宽 48dp", 48f, with(density) { dividerBounds.width.toDp().value }, 0.5f)
    }

    /**
     * 闭合 R-E3 B1：三栏下点击中栏右上「更多」、交界 48dp 热区内竖滑滚动列表、横向拖动分隔线至 COLLAPSED
     * 并在 COLLAPSED 下点击右栏左上「返回」均全部生效、零交互死区。
     */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun b1_dividerDoesNotBlockMiddleMoreButton_rightBackButton_orVerticalScrollNearSeam_whileHorizontalDragWorks() {
        val harness = MultiPaneTestHarness()
        composeRule.setContent { harness.RenderRoot() }
        composeRule.waitForIdle()

        val density = composeRule.density
        // 1. 点击中栏顶栏右上角「更多」（位于 314..362dp 分隔线热区重叠处）必须正常弹出排序菜单
        composeRule.onNodeWithContentDescription("更多").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("按发帖排序").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(true, harness.settingsRepo.settingsFlow.value.isSortByPostTime(2))

        // 2. 在分隔线 48dp 热区附近（dividerX - 10dp = 328dp）竖向滑动，必须透传给中栏 LazyColumn 滚动
        val listScrollMatcher = SemanticsMatcher("verticalScroll") { SemanticsProperties.VerticalScrollAxisRange in it.config } and
            hasAnyAncestor(paneMatcher("列表"))
        val scrollBefore = composeRule.onAllNodes(listScrollMatcher, useUnmergedTree = true).onFirst()
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val seamNearX = with(density) { 328.dp.toPx() }
        val midY = with(density) { 360.dp.toPx() }
        composeRule.onRoot().performTouchInput {
            down(Offset(seamNearX, midY))
            repeat(6) {
                moveBy(Offset(0f, with(density) { -32.dp.toPx() }))
                advanceEventTime(16L)
            }
            up()
        }
        composeRule.waitForIdle()
        val scrollAfter = composeRule.onAllNodes(listScrollMatcher, useUnmergedTree = true).onFirst()
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("R-E3 B1：在分隔线热区附近竖滑必须滚动中栏列表，before=$scrollBefore after=$scrollAfter", scrollAfter > scrollBefore)

        // 3. 打开帖子后，横向拖动分隔线仍然有效并吸附至 COLLAPSED（0dp）
        composeRule.onNodeWithText("测试帖子标题 1005").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.PaneDivider).performTouchInput {
            down(center)
            repeat(6) {
                moveBy(Offset(with(density) { -48.dp.toPx() }, 0f))
                advanceEventTime(16L)
            }
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNode(paneMatcher("列表")).assertDoesNotExist()

        // 4. 在 COLLAPSED（热区位于右栏左缘 0..48dp）下钻用户资料页，点击左上角「返回」必须正常弹出下钻栈
        composeRule.runOnIdle { harness.hostViewModel.pushUserProfile("42", "测试楼主") }
        composeRule.waitForIdle()
        assertEquals(1, harness.hostViewModel.state.value.detailDrilldownStack.size)
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue("R-E3 B1：COLLAPSED 态点击右栏左上「返回」必须触发出栈", harness.hostViewModel.state.value.detailDrilldownStack.isEmpty())
    }

    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun b4e3_1_emptyRightPane_leftwardLargeDrag_keepsMiddleWidthAtLeastDefaultEveryFrame_andOmitsCollapse() {
        val harness = MultiPaneTestHarness(initialSettings = AppSettings(paneSplitLandscape = PaneSplit.COLLAPSED))
        val windowSize = mutableStateOf(DpSize(749.dp, 559.dp))

        composeRule.setContent {
            MultiPaneResizableWindowHost(windowSize.value) { harness.RenderRoot() }
        }
        composeRule.waitForIdle()

        val density = composeRule.density

        fun verifyEmptyRightPaneFloor(expectedDefaultDp: Float) {
            val actions = composeRule.onNodeWithTag(HiShellTestTags.PaneDivider)
                .fetchSemanticsNode().config[SemanticsActions.CustomActions]
            val labels = actions.map { it.label }
            assertFalse("空右栏绝不可暴露「收起列表」无障碍动作，实际=$labels", "收起列表" in labels)
            assertTrue("应暴露「默认宽度」", "默认宽度" in labels)

            val initialWidth = with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }
            assertEquals("空右栏不得恢复为列表收起，必须落在默认宽", expectedDefaultDp, initialWidth, 1f)

            composeRule.mainClock.autoAdvance = false
            try {
                val dragStepPx = with(density) { -40.dp.toPx() }
                composeRule.onNodeWithTag(HiShellTestTags.PaneDivider).performTouchInput {
                    down(center)
                    repeat(9) {
                        moveBy(Offset(dragStepPx, 0f))
                        advanceEventTime(16L)
                    }
                    up()
                }
                repeat(12) { frame ->
                    composeRule.mainClock.advanceTimeByFrame()
                    val wDp = with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }
                    assertTrue(
                        "B4-E3 ①：第 $frame 帧中栏宽 ${wDp}dp 跌破硬底线 M_default=${expectedDefaultDp}dp",
                        wDp >= expectedDefaultDp - 0.5f,
                    )
                }
            } finally {
                composeRule.mainClock.autoAdvance = true
            }
            composeRule.waitForIdle()
            val settledWidth = with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }
            assertEquals("松手后必须停留在 M_default", expectedDefaultDp, settledWidth, 1f)
        }

        verifyEmptyRightPaneFloor(expectedDefaultDp = 274f)
        windowSize.value = DpSize(933.dp, 696.dp)
        composeRule.waitForIdle()
        verifyEmptyRightPaneFloor(expectedDefaultDp = 298f)
    }

    @Test
    @Config(qualifiers = "w860dp-h860dp-xhdpi")
    fun withDetailContent_dragFollowsFinger1To1_snapsAndPersistsIndependentlyPerOrientation() {
        val harness = MultiPaneTestHarness()
        val windowSize = mutableStateOf(DpSize(749.dp, 559.dp))

        composeRule.setContent {
            MultiPaneResizableWindowHost(windowSize.value) { harness.RenderRoot() }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        val density = composeRule.density
        val startW = with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }
        assertEquals(274f, startW, 1f)

        composeRule.mainClock.autoAdvance = false
        try {
            val midStepPx = with(density) { -60.dp.toPx() }
            composeRule.onNodeWithTag(HiShellTestTags.PaneDivider).performTouchInput {
                down(center)
                moveBy(Offset(midStepPx, 0f))
                advanceEventTime(16L)
                moveBy(Offset(midStepPx, 0f))
                advanceEventTime(16L)
            }
            composeRule.mainClock.advanceTimeByFrame()
            val midWidth = with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }
            assertTrue("拖动中段应跟手收缩至 (0, 274) 区间，实际=$midWidth", midWidth in 40f..250f)

            composeRule.onNodeWithTag(HiShellTestTags.PaneDivider).performTouchInput {
                moveBy(Offset(with(density) { -140.dp.toPx() }, 0f))
                advanceEventTime(16L)
                up()
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        composeRule.onNode(paneMatcher("列表")).assertDoesNotExist()
        assertEquals(PaneSplit.COLLAPSED, harness.settingsRepo.settingsFlow.value.paneSplitLandscape)
        assertEquals(PaneSplit.DEFAULT, harness.settingsRepo.settingsFlow.value.paneSplitPortrait)

        windowSize.value = DpSize(744.dp, 860.dp)
        composeRule.waitForIdle()
        val portraitDefaultW = with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }
        assertEquals("竖屏应恢复自身的 DEFAULT（272dp），不受横屏 COLLAPSED 影响", 272f, portraitDefaultW, 1f)

        invokeDividerAction("均衡")
        composeRule.waitForIdle()
        val portraitBalancedW = with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }
        assertEquals("竖屏 744dp 均衡档应为 320dp", 320f, portraitBalancedW, 1f)
        assertEquals(PaneSplit.BALANCED, harness.settingsRepo.settingsFlow.value.paneSplitPortrait)
        assertEquals(PaneSplit.COLLAPSED, harness.settingsRepo.settingsFlow.value.paneSplitLandscape)

        windowSize.value = DpSize(749.dp, 559.dp)
        composeRule.waitForIdle()
        composeRule.onNode(paneMatcher("列表")).assertDoesNotExist()
    }

    /**
     * 闭合 R-E3 B3：模拟进程恢复时 `DataStore` 冷流异步发出已保存的分栏比例（`BALANCED`）与收起侧栏（`true`），
     * 断言首帧绝不被默认初值冲回闪烁，渲染出的第一帧即精确呈现 `64dp` 收起侧栏 + `435dp` 均衡中栏。
     */
    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun b3_coldSettingsFlow_restoresPersistedSplitAndSidebarOnFirstRenderedFrameWithoutFlash() {
        val gate = CompletableDeferred<AppSettings>()
        val coldSettingsFlow = flow { emit(gate.await()) }
        val harness = MultiPaneTestHarness()
        val observedSidebarWidths = mutableListOf<Float>()
        val observedListWidths = mutableListOf<Float>()
        val density = composeRule.density

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides harness.parentOwner) {
                    NextAppRoot(
                        settingsFlow = coldSettingsFlow,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                    )
                }
            }
            // 冷流尚未发出前：绝不得渲染出默认展开侧栏（187dp）或默认中栏（298dp）造成首帧闪烁
            repeat(3) {
                composeRule.mainClock.advanceTimeByFrame()
                assertTrue(composeRule.onAllNodes(paneMatcher("列表")).fetchSemanticsNodes().isEmpty())
            }

            // 模拟 DataStore 读盘完成发出已持久化偏好：BALANCED + sidebarCollapsedLandscape = true
            gate.complete(AppSettings(paneSplitLandscape = PaneSplit.BALANCED, sidebarCollapsedLandscape = true))
            repeat(10) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, HiShellTestTags.NavigationSidebar))
                    .fetchSemanticsNodes().firstOrNull()?.let { node ->
                        observedSidebarWidths.add(with(density) { node.boundsInRoot.width.toDp().value })
                    }
                composeRule.onAllNodes(paneMatcher("列表"))
                    .fetchSemanticsNodes().firstOrNull()?.let { node ->
                        observedListWidths.add(with(density) { node.boundsInRoot.width.toDp().value })
                    }
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        assertTrue("应已捕获首帧侧栏宽", observedSidebarWidths.isNotEmpty())
        assertTrue("应已捕获首帧中栏宽", observedListWidths.isNotEmpty())
        // 933dp 收起左栏 64dp -> remaining=869 -> balanced=min(435, 869-360=509, 480)=435dp
        assertEquals("R-E3 B3：首帧侧栏宽必须直接为 64dp，不得先闪 187dp", 64f, observedSidebarWidths.first(), 1f)
        assertEquals("R-E3 B3：首帧中栏宽必须直接为 BALANCED(435dp)，不得先闪 DEFAULT(348dp)", 435f, observedListWidths.first(), 1f)
    }

    /**
     * 闭合 R-E3 S2：持久化为 `COLLAPSED` 时因空右栏回退至 `DEFAULT`，用户随后点击开帖进入有详情态时，
     * 将持久化同步对齐至当前可见 `DEFAULT`，后续旋转往返不再意外收起列表。
     */
    @Test
    @Config(qualifiers = "w860dp-h860dp-xhdpi")
    fun s2_emptyRightPaneFallbackFromCollapsed_alignsPersistedSplitToDefaultOnOpeningThread() {
        val harness = MultiPaneTestHarness(initialSettings = AppSettings(paneSplitLandscape = PaneSplit.COLLAPSED))
        val windowSize = mutableStateOf(DpSize(749.dp, 559.dp))

        composeRule.setContent {
            MultiPaneResizableWindowHost(windowSize.value) { harness.RenderRoot() }
        }
        composeRule.waitForIdle()
        val density = composeRule.density
        assertEquals(274f, with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)

        // 空右栏回退 DEFAULT 后打开帖子：应同步将横屏持久化由陈旧 COLLAPSED 对齐至 DEFAULT
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        assertEquals(PaneSplit.DEFAULT, harness.settingsRepo.settingsFlow.value.paneSplitLandscape)

        // 旋转至竖屏再转回横屏：列表应保持 DEFAULT（274dp），不再意外收起
        windowSize.value = DpSize(744.dp, 860.dp)
        composeRule.waitForIdle()
        windowSize.value = DpSize(749.dp, 559.dp)
        composeRule.waitForIdle()
        assertEquals(274f, with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)
    }

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun divider_customAccessibilityActions_andKeyboardFocusSwitchSplits() {
        val harness = MultiPaneTestHarness()
        composeRule.setContent { harness.RenderRoot() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        val density = composeRule.density
        val actions = composeRule.onNodeWithTag(HiShellTestTags.PaneDivider)
            .fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("收起列表", "默认宽度", "均衡"), actions)

        invokeDividerAction("均衡")
        composeRule.waitForIdle()
        assertEquals(325f, with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)

        invokeDividerAction("收起列表")
        composeRule.waitForIdle()
        composeRule.onNode(paneMatcher("列表")).assertDoesNotExist()

        invokeDividerAction("默认宽度")
        composeRule.waitForIdle()
        assertEquals(274f, with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)

        val divider = composeRule.onNodeWithTag(HiShellTestTags.PaneDivider)
        divider.requestFocus()
        composeRule.waitForIdle()

        divider.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        assertEquals(325f, with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)

        divider.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.waitForIdle()
        assertEquals(274f, with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)

        divider.performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()
        assertEquals(325f, with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)
    }

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun divider_reducedMotion_completesSplitTransitionInSingleFrame() {
        val hostVm = NextAppHostViewModel()
        hostVm.openThread(ThreadNavArgs("1001", "标题", 2))
        val splitState = mutableStateOf(PaneSplit.DEFAULT)
        val layout = calculateShellPaneLayout(749f, 559f, hasDetailContent = true)
        val zeroMotion = object : MotionDurationScale { override val scaleFactor = 0f }

        composeRule.setContent {
            HiTheme(motionDurationScale = zeroMotion) {
                MultiPaneReadingContainer(
                    paneLayout = layout,
                    appState = hostVm.state.value,
                    hostViewModel = hostVm,
                    contentPadding = PaddingValues(0.dp),
                    persistedPaneSplit = splitState.value,
                    onPaneSplitChange = { splitState.value = it },
                    forumContent = { _, _, _ -> Text("列表正文") },
                    detailContent = { _, _, _, _, _ -> Text("详情正文") },
                )
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        composeRule.mainClock.autoAdvance = false
        try {
            invokeDividerAction("均衡")
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeByFrame()
            val wDp = with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }
            assertEquals("减动效下档位切换必须 0ms 立即到位（325dp）", 325f, wDp, 1f)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun fullScreenReadingButton_hidesLeftAndMiddlePanes_andRestoresPriorSidebarAndSplit() {
        val harness = MultiPaneTestHarness()
        composeRule.setContent { harness.RenderRoot() }
        composeRule.waitForIdle()

        val density = composeRule.density
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        invokeDividerAction("均衡")
        composeRule.waitForIdle()
        assertEquals(187f, with(density) { composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)
        assertEquals(373f, with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)

        composeRule.onNodeWithContentDescription("全屏阅读").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).assertDoesNotExist()
        composeRule.onNode(paneMatcher("列表")).assertDoesNotExist()
        composeRule.onNodeWithTag(HiShellTestTags.PaneDivider).assertDoesNotExist()
        assertEquals(933f, with(density) { composeRule.onNode(paneMatcher("正文")).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)

        composeRule.onNodeWithContentDescription("显示列表").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(187f, with(density) { composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)
        assertEquals(373f, with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)

        composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
        composeRule.waitForIdle()
        assertEquals(64f, with(density) { composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)

        composeRule.onNodeWithContentDescription("全屏阅读").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).assertDoesNotExist()

        composeRule.onNodeWithContentDescription("显示列表").performClick()
        composeRule.waitForIdle()
        assertEquals("退出全屏必须保持全屏前的 64dp 收起态", 64f, with(density) { composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)
    }

    private fun invokeDividerAction(label: String) {
        val node = composeRule.onNodeWithTag(HiShellTestTags.PaneDivider).fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].first { it.label == label }
        composeRule.runOnIdle { action.action() }
    }
}
