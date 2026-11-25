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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
 * E3 验收项 1：空右栏拖动锚点硬底线（B4-E3 ①）、中右分隔线逐帧跟手拖拽/初速吸附/横竖屏持久化、
 * 无障碍 CustomActions + 键盘切换、减动效 0ms 到位、Fold8 `64|274|411` 几何与详情顶栏全屏阅读联动。
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
        assertEquals("分隔线触控热区宽 48dp", 48f, with(density) { dividerBounds.width.toDp().value }, 0.5f)
    }

    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun b4e3_1_emptyRightPane_leftwardLargeDrag_keepsMiddleWidthAtLeastDefaultEveryFrame_andOmitsCollapse() {
        // 即使持久化存着 COLLAPSED，空右栏也绝不可恢复为 0dp
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

            // 逐帧向左大幅拖动（-360dp），每帧断言中栏宽恒 ≥ M_default
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

        // 749×559dp（M_default = 274dp）
        verifyEmptyRightPaneFloor(expectedDefaultDp = 274f)

        // 933×696dp（左栏 187dp，M_default = 298dp）
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

        // 打开一篇帖子使右栏有内容（解锁 COLLAPSED 0dp 锚点）
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        val density = composeRule.density
        val startW = with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }
        assertEquals(274f, startW, 1f)

        // 手动时钟验证拖动中间态逐帧跟手，随后松手吸附至 COLLAPSED 并写入横屏持久化
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
        assertEquals(
            "横屏收起列表必须持久化至 paneSplitLandscape",
            PaneSplit.COLLAPSED,
            harness.settingsRepo.settingsFlow.value.paneSplitLandscape,
        )
        assertEquals(
            "横屏拖拽不得污染竖屏 paneSplitPortrait",
            PaneSplit.DEFAULT,
            harness.settingsRepo.settingsFlow.value.paneSplitPortrait,
        )

        // 切到竖屏三栏（744×860dp，isLandscape = false）独立吸附至 BALANCED
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

        // 转回横屏（749×559dp）恢复横屏记忆的 COLLAPSED
        windowSize.value = DpSize(749.dp, 559.dp)
        composeRule.waitForIdle()
        composeRule.onNode(paneMatcher("列表")).assertDoesNotExist()
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

        // 1. CustomAccessibilityAction 依次切均衡 / 收起列表 / 默认宽度
        invokeDividerAction("均衡")
        composeRule.waitForIdle()
        assertEquals(325f, with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)

        invokeDividerAction("收起列表")
        composeRule.waitForIdle()
        composeRule.onNode(paneMatcher("列表")).assertDoesNotExist()

        invokeDividerAction("默认宽度")
        composeRule.waitForIdle()
        assertEquals(274f, with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)

        // 2. 键盘焦点 + 方向键 / Enter 切换
        val divider = composeRule.onNodeWithTag(HiShellTestTags.PaneDivider)
        divider.requestFocus()
        composeRule.waitForIdle()

        divider.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        assertEquals(325f, with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)

        divider.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.waitForIdle()
        assertEquals(274f, with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)

        // Enter 循环到下一档（DEFAULT -> BALANCED）
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

        // 切到均衡档并记住展开左栏宽（187dp）+ 均衡中栏宽（373dp）
        invokeDividerAction("均衡")
        composeRule.waitForIdle()
        val expandedLeftBefore = with(density) { composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).fetchSemanticsNode().boundsInRoot.width.toDp().value }
        val balancedMidBefore = with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }
        assertEquals(187f, expandedLeftBefore, 1f)
        assertEquals(373f, balancedMidBefore, 1f)

        // 点详情顶栏「全屏阅读」：隐藏左栏与中栏，右栏铺满 933dp，按钮变为「显示列表」
        composeRule.onNodeWithContentDescription("全屏阅读").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).assertDoesNotExist()
        composeRule.onNode(paneMatcher("列表")).assertDoesNotExist()
        composeRule.onNodeWithTag(HiShellTestTags.PaneDivider).assertDoesNotExist()
        val fullDetailW = with(density) { composeRule.onNode(paneMatcher("正文")).fetchSemanticsNode().boundsInRoot.width.toDp().value }
        assertEquals("全屏阅读右栏应占满 933dp", 933f, fullDetailW, 1f)

        // 再点「显示列表」：还原展开左栏（187dp）与均衡中栏（373dp）
        composeRule.onNodeWithContentDescription("显示列表").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(187f, with(density) { composeRule.onNodeWithTag(HiShellTestTags.NavigationSidebar).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)
        assertEquals(373f, with(density) { composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot.width.toDp().value }, 1f)

        // 收起左栏为 64dp 图标轨后再切全屏 → 退出全屏：应保持 64dp 图标轨而非误恢复展开态
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
