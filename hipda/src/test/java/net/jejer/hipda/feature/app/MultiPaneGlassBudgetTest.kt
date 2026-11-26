package net.jejer.hipda.feature.app

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.forum.ForumPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.HiGlassRegion
import net.jejer.hipda.ui.designsystem.glass.HiGlassStyle
import net.jejer.hipda.ui.designsystem.glass.HiGlassSurface
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.shell.ShellHingeBoundsDp
import net.jejer.hipda.ui.shell.ShellPaneLayoutMode
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import net.jejer.hipda.ui.shell.calculateShellPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E3 验收项 4：真实 `E1` + `E2a` 三栏 3 区域液态玻璃协同（`HiGlassMarker ≤ 3`、独立 `LayerBackdrop ≤ 2`、
 * 静止 `drawBackdrop ≤ 5`、0 嵌套源）、全屏阅读释放中栏源、铰链双栏左顶栏纯色（0 顶栏玻璃）及多栏分区对齐 `MultiPaneSnackbarHost`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MultiPaneGlassBudgetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun threePane_realForumAndThreadDetail_andSimulatedToolbar_staysWithin3Markers_2Sources_5Passes() {
        val harness = MultiPaneTestHarness()
        composeRule.setContent { harness.RenderRoot() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        // 真实 E1 + E3 + E4 协同：中栏 ForumTopBar (1) + 右栏 ThreadDetailTopBar (1) + 右栏底部三段工具条 HiGlassRegion (1) = 3 HiGlassMarker，2 LayerBackdrop，0 嵌套，静止 drawBackdrop ≤ 5
        val markers = composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("真实 E1+E3+E4 三栏应恰有 3 个玻璃区域（中栏顶栏 + 右栏顶栏 + 右栏底部工具条）", 3, markers.size)
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)

        val audit = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
        assertEquals("三栏独立 LayerBackdrop 采样源必须为 2（middleGlassState + detailGlassState）", 2, audit.layerBackdropCount)
        assertEquals("严禁父子嵌套 LayerBackdrop", 0, audit.nestedLayerBackdropCount)
        assertTrue("三栏静止 drawBackdrop pass 必须 ≤ 5，实际=${audit.drawBackdropCount}", audit.drawBackdropCount <= 5)
    }

    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun threePane_withSimulatedThreeCapsuleToolbar_exact3Markers_2Sources_5Passes() {
        val harness = MultiPaneTestHarness()
        harness.hostViewModel.openThread(ThreadNavArgs("1001", "三栏满编玻璃测试", 2))
        val layout = calculateShellPaneLayout(933f, 696f, hasDetailContent = true)

        composeRule.setContent {
            HiTheme {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides harness.parentOwner,
                    ) {
                        MultiPaneReadingContainer(
                            paneLayout = layout,
                            appState = harness.hostViewModel.state.value,
                            hostViewModel = harness.hostViewModel,
                            contentPadding = PaddingValues(0.dp),
                            persistedPaneSplit = PaneSplit.DEFAULT,
                            onPaneSplitChange = {},
                            forumContent = { chromeSpec, padding, listState ->
                                ForumPane(
                                    source = harness.hostViewModel.state.value.activeForumSource,
                                    chromeSpec = chromeSpec,
                                    selectedTid = "1001",
                                    onSelectSource = {},
                                    onOpenThread = {},
                                    onOpenLogin = {},
                                    listState = listState,
                                    contentPadding = padding,
                                    viewModel = harness.forumViewModel,
                                )
                            },
                            detailContent = { args, chromeSpec, padding, listState, onBack ->
                                // 真实 E4 ThreadDetailPane（含顶栏 1 + 底部三段工具条 HiGlassRegion 1，共享同一 detailGlassState）
                                ThreadDetailPane(
                                    args = args,
                                    chromeSpec = chromeSpec,
                                    onBack = onBack,
                                    onOpenLogin = {},
                                    listState = listState,
                                    contentPadding = padding,
                                )
                            },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val markers = composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("满编三栏（中顶栏 + 右顶栏 + 右真实三段工具条）必须为 3 个 HiGlassMarker", 3, markers.size)
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)

        val audit = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
        assertTrue("独立 LayerBackdrop 采样源必须 ≤ 2，实际=${audit.layerBackdropCount}", audit.layerBackdropCount <= 2)
        assertEquals("严禁父子嵌套 LayerBackdrop", 0, audit.nestedLayerBackdropCount)
        assertEquals("满编三栏静止 drawBackdrop pass 必须 ≤ 5（1+1+3=5）", 5, audit.drawBackdropCount)
    }

    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun fullScreenReading_releasesMiddleLayerBackdropSource() {
        val harness = MultiPaneTestHarness()
        composeRule.setContent { harness.RenderRoot() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        val beforeAudit = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
        assertEquals(2, beforeAudit.layerBackdropCount)

        composeRule.onNodeWithContentDescription("全屏阅读").performClick()
        composeRule.waitForIdle()

        val fullScreenAudit = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
        assertEquals("全屏阅读必须释放中栏 LayerBackdrop，仅剩右栏 1 个采样源", 1, fullScreenAudit.layerBackdropCount)
        val markers = composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("全屏阅读仅剩右栏详情顶栏与底部三段工具条共 2 个玻璃区域", 2, markers.size)
    }

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun hingeTwoPane_leftTopBarIsSolidZeroGlass_andNonForumHasZeroNestedLayerBackdrops() {
        val hingeInfo = verticalHingeLayoutInfo(leftDp = 371f, rightDp = 378f, heightDp = 559f, density = 2f, isSeparating = true)
        withWindowLayoutFlow(flowOf(hingeInfo)) {
            val harness = MultiPaneTestHarness()
            composeRule.setContent { harness.RenderRoot() }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("测试帖子标题 1001").performClick()
            composeRule.waitForIdle()

            // 左栏（帖子列表）内不得存在任何 HiGlassMarker（左顶栏强制纯色）
            val leftGlassNodes = composeRule.onAllNodes(
                SemanticsMatcher.expectValue(HiGlassMarker, true) and
                    hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "列表")),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertEquals("铰链双栏左列表顶栏必须退纯色（0 玻璃区域）", 0, leftGlassNodes.size)

            // 全局玻璃区域：左紧凑胶囊 (1) + 右详情顶栏 (1) + 右底部三段工具条 (1) = 3 ≤ 3
            val allMarkers = composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals("铰链双栏应为左紧凑胶囊 + 右详情顶栏 + 右底部工具条共 3 块玻璃", 3, allMarkers.size)
            composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)

            val forumAudit = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
            assertEquals("铰链双栏独立采样源为 2（左 shellGlassState + 右 detailGlassState）", 2, forumAudit.layerBackdropCount)
            assertEquals("铰链双栏严禁父子嵌套 LayerBackdrop", 0, forumAudit.nestedLayerBackdropCount)
            assertTrue("铰链双栏静止 drawBackdrop pass 必须 ≤ 5，实际=${forumAudit.drawBackdropCount}", forumAudit.drawBackdropCount <= 5)

            // 切至非论坛入口（History + 帖子详情）仍保持 0 嵌套 LayerBackdrop
            composeRule.runOnIdle {
                harness.hostViewModel.selectSidebarEntry(ShellSidebarEntry.History)
                harness.hostViewModel.openThread(ThreadNavArgs("1001", "历史帖", 2))
            }
            composeRule.waitForIdle()
            val nonForumAudit = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
            assertEquals("非论坛入口铰链双栏同样严禁父子嵌套 LayerBackdrop", 0, nonForumAudit.nestedLayerBackdropCount)
            assertTrue(nonForumAudit.layerBackdropCount <= 2)
        }
    }

    @Test
    fun calculateSnackbarRegion_coversAllLayoutModesAndNeverCrossesDividerOrHinge() {
        // 1. Fold8 三栏 749×559（64 | 274 | 411）
        val threePane = calculateShellPaneLayout(749f, 559f, hasDetailContent = true)
        val detailThree = calculateSnackbarRegion(threePane, 274.dp, false, PaneSnackbarTarget.DetailPane, false, 0.dp, 0.dp)
        assertEquals(338.dp, detailThree.startDp)
        assertEquals(411.dp, detailThree.widthDp)

        val contentThree = calculateSnackbarRegion(threePane, 274.dp, false, PaneSnackbarTarget.ContentArea, false, 0.dp, 0.dp)
        assertEquals(64.dp, contentThree.startDp)
        assertEquals(685.dp, contentThree.widthDp)

        // 2. 铰链双栏 749×559（0..371 | 7dp 铰链 | 378..749）
        val hinge = calculateShellPaneLayout(749f, 559f, hingeBoundsDp = ShellHingeBoundsDp(371f, 378f), hasDetailContent = true)
        assertEquals(ShellPaneLayoutMode.HingeTwoPane, hinge.layoutMode)
        val detailHinge = calculateSnackbarRegion(hinge, 371.dp, false, PaneSnackbarTarget.DetailPane, true, 64.dp, 0.dp)
        assertEquals(378.dp, detailHinge.startDp)
        assertEquals(371.dp, detailHinge.widthDp)

        val contentHinge = calculateSnackbarRegion(hinge, 371.dp, false, PaneSnackbarTarget.ContentArea, true, 64.dp, 0.dp)
        assertEquals(0.dp, contentHinge.startDp)
        assertEquals(371.dp, contentHinge.widthDp)
        assertEquals(72.dp, contentHinge.bottomClearanceDp)

        // 3. 640dp 图标轨单栏（64 | 576）
        val rail = calculateShellPaneLayout(640f, 1024f)
        val railRegion = calculateSnackbarRegion(rail, 576.dp, false, PaneSnackbarTarget.ContentArea, false, 0.dp, 0.dp)
        assertEquals(64.dp, railRegion.startDp)
        assertEquals(576.dp, railRegion.widthDp)

        // 4. 全屏阅读（整窗居中）
        val fullRegion = calculateSnackbarRegion(threePane, 0.dp, true, PaneSnackbarTarget.DetailPane, false, 0.dp, 0.dp)
        assertEquals(0.dp, fullRegion.startDp)
        assertEquals(749.dp, fullRegion.widthDp)
    }

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun multiPaneSnackbarHost_uiBoundsStayStrictlyInsideRightPaneOrContentAreaWithoutCrossingDividerOrHinge() {
        val hostState = SnackbarHostState()
        val controller = MultiPaneSnackbarController(hostState)
        val threePane = calculateShellPaneLayout(749f, 559f, hasDetailContent = true)
        lateinit var scope: CoroutineScope

        composeRule.setContent {
            scope = rememberCoroutineScope()
            HiTheme {
                MultiPaneSnackbarHost(
                    hostState = hostState,
                    paneLayout = threePane,
                    effectiveMiddleWidthDp = 274.dp,
                    isFullScreen = false,
                    bottomPillVisible = false,
                    bottomPillClearanceDp = 0.dp,
                    navBarsBottomDp = 0.dp,
                )
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density
        // 触发 DetailPane Snackbar：左边界必须 ≥ 338dp（绝不越过中右分隔线）
        composeRule.runOnIdle {
            scope.launch { controller.show("已收藏", PaneSnackbarTarget.DetailPane, "撤销") }
        }
        composeRule.waitForIdle()

        val snackNode = composeRule.onNodeWithText("已收藏").assertIsDisplayed().fetchSemanticsNode()
        val leftDp = with(density) { snackNode.boundsInRoot.left.toDp().value }
        val rightDp = with(density) { snackNode.boundsInRoot.right.toDp().value }
        assertTrue("DetailPane Snackbar 左缘 ${leftDp}dp 不得越过分隔线 338dp", leftDp >= 338f)
        assertTrue("DetailPane Snackbar 右缘 ${rightDp}dp 不得越过窗口 749dp", rightDp <= 749f)

        // 撤销当前并触发 ContentArea Snackbar：左边界 ≥ 64dp（绝不覆盖左栏）
        composeRule.runOnIdle {
            hostState.currentSnackbarData?.dismiss()
            scope.launch { controller.show("已切换账号", PaneSnackbarTarget.ContentArea) }
        }
        composeRule.waitForIdle()
        val contentSnack = composeRule.onNodeWithText("已切换账号").assertIsDisplayed().fetchSemanticsNode()
        val contentLeftDp = with(density) { contentSnack.boundsInRoot.left.toDp().value }
        assertTrue("ContentArea Snackbar 左缘 ${contentLeftDp}dp 不得越入 64dp 左栏", contentLeftDp >= 64f)
    }

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun multiPaneSnackbarHost_hingeTwoPane_neverCrossesPhysicalHinge() {
        val hostState = SnackbarHostState()
        val controller = MultiPaneSnackbarController(hostState)
        val hingeLayout = calculateShellPaneLayout(749f, 559f, hingeBoundsDp = ShellHingeBoundsDp(371f, 378f), hasDetailContent = true)
        lateinit var scope: CoroutineScope

        composeRule.setContent {
            scope = rememberCoroutineScope()
            HiTheme {
                MultiPaneSnackbarHost(
                    hostState = hostState,
                    paneLayout = hingeLayout,
                    effectiveMiddleWidthDp = 371.dp,
                    isFullScreen = false,
                    bottomPillVisible = true,
                    bottomPillClearanceDp = 64.dp,
                    navBarsBottomDp = 0.dp,
                )
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density

        composeRule.runOnIdle { scope.launch { controller.show("右栏已复制", PaneSnackbarTarget.DetailPane) } }
        composeRule.waitForIdle()
        val rightBounds = composeRule.onNodeWithText("右栏已复制").fetchSemanticsNode().boundsInRoot
        assertTrue("铰链双栏 DetailPane Snackbar 必须完全在铰链右侧 (≥ 378dp)", with(density) { rightBounds.left.toDp().value } >= 378f)

        composeRule.runOnIdle {
            hostState.currentSnackbarData?.dismiss()
            scope.launch { controller.show("左栏已删除", PaneSnackbarTarget.ContentArea) }
        }
        composeRule.waitForIdle()
        val leftBounds = composeRule.onNodeWithText("左栏已删除").fetchSemanticsNode().boundsInRoot
        assertTrue("铰链双栏 ContentArea Snackbar 必须完全在铰链左侧 (≤ 371dp)", with(density) { leftBounds.right.toDp().value } <= 371f)
    }

    /** 闭合 R-E3 S1：右栏 DetailPane Snackbar 退场动画期间（currentSnackbarData 已置空）仍缓存右栏区域，绝不跨分隔线跳变。 */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun s1_multiPaneSnackbarHost_detailPaneDismissExitAnimation_staysStrictlyInsideRightPane() {
        val hostState = SnackbarHostState()
        val controller = MultiPaneSnackbarController(hostState)
        val threePane = calculateShellPaneLayout(749f, 559f, hasDetailContent = true)
        lateinit var scope: CoroutineScope

        composeRule.setContent {
            scope = rememberCoroutineScope()
            HiTheme {
                MultiPaneSnackbarHost(
                    hostState = hostState,
                    paneLayout = threePane,
                    effectiveMiddleWidthDp = 274.dp,
                    isFullScreen = false,
                    bottomPillVisible = false,
                    bottomPillClearanceDp = 0.dp,
                    navBarsBottomDp = 0.dp,
                )
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density

        composeRule.runOnIdle { scope.launch { controller.show("右栏已收藏", PaneSnackbarTarget.DetailPane) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("右栏已收藏").assertIsDisplayed()

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnIdle { hostState.currentSnackbarData?.dismiss() }
            repeat(12) { frame ->
                composeRule.mainClock.advanceTimeByFrame()
                val nodes = composeRule.onAllNodes(SemanticsMatcher("snackText") { node ->
                    node.config.contains(SemanticsProperties.Text) &&
                        node.config[SemanticsProperties.Text].any { it.text == "右栏已收藏" }
                }).fetchSemanticsNodes()
                nodes.forEach { node ->
                    val leftDp = with(density) { node.boundsInRoot.left.toDp().value }
                    assertTrue("R-E3 S1：退场第 $frame 帧 DetailPane Snackbar 左缘 ${leftDp}dp 不得跳越分隔线 338dp", leftDp >= 338f)
                }
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
    }
}
