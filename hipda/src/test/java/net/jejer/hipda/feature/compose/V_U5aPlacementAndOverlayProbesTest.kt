package net.jejer.hipda.feature.compose

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.flowOf
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.feature.app.MultiPaneResizableWindowHost
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.app.verticalHingeLayoutInfo
import net.jejer.hipda.feature.app.withWindowLayoutFlow
import net.jejer.hipda.feature.compose.emoji.EmojiPanelRendererImpl
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassBlurActive
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.shell.HiShellTestTags
import net.jejer.hipda.ui.shell.ShellPaneLayoutMode
import net.jejer.hipda.ui.shell.calculateShellPaneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class V_U5aPlacementAndOverlayProbesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val emojiSlot: @Composable (QuickReplyEmojiPanelHost) -> Unit = { host ->
        EmojiPanelRendererImpl().Content(host, Modifier)
    }

    private fun composeOwnerWithVms(
        harness: MultiPaneTestHarness,
        postRepo: FakePostRepository,
        editorVm: FullEditorViewModel,
    ): ViewModelStoreOwner {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) ->
                    QuickReplyViewModel(postRepo, harness.authRepo, clockMs = { 10_000L }) as T
                modelClass.isAssignableFrom(FullEditorViewModel::class.java) -> editorVm as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        return object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore = store
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory = factory
        }
    }

    /** Probe 2a: referenceRightPaneWidthDp + resolveFullEditorPlacement 单测矩阵（749/933 含 BALANCED/1280/手机/铰链）。 */
    @Test
    fun probe2a_referenceWidthAndPlacementMatrix() {
        // 749×559 (CompactThreePane: L=64, M=274, R=411)
        val layout749 = calculateShellPaneLayout(749f, 559f, leftCollapsedPref = false, hasDetailContent = true)
        assertEquals(ShellPaneLayoutMode.CompactThreePane, layout749.layoutMode)
        assertEquals(411f, referenceRightPaneWidthDp(layout749, 274.dp, PaneSplit.DEFAULT).value, 0.5f)
        assertEquals(411f, referenceRightPaneWidthDp(layout749, 0.dp, PaneSplit.DEFAULT).value, 0.5f)
        assertEquals(411f, referenceRightPaneWidthDp(layout749, 0.dp, PaneSplit.COLLAPSED).value, 0.5f)
        assertEquals(FullEditorPlacement.CenteredModal, resolveFullEditorPlacement(layout749, 559f, referenceRightPaneWidthDp(layout749, 274.dp, PaneSplit.DEFAULT).value))
        assertEquals(FullEditorPlacement.CenteredModal, resolveFullEditorPlacement(layout749, 559f, referenceRightPaneWidthDp(layout749, 0.dp, PaneSplit.DEFAULT).value))
        assertEquals(FullEditorPlacement.CenteredModal, resolveFullEditorPlacement(layout749, 559f, referenceRightPaneWidthDp(layout749, 0.dp, PaneSplit.COLLAPSED).value))

        // 933×696 (ExpandedThreePane: L=187, content=746, M=298, R=448, BALANCED M=373 -> R=373)
        val layout933 = calculateShellPaneLayout(933f, 696f, leftCollapsedPref = false, hasDetailContent = true)
        assertEquals(ShellPaneLayoutMode.ExpandedThreePane, layout933.layoutMode)
        assertEquals(448f, referenceRightPaneWidthDp(layout933, 298.dp, PaneSplit.DEFAULT).value, 0.5f)
        assertEquals(448f, referenceRightPaneWidthDp(layout933, 0.dp, PaneSplit.DEFAULT).value, 0.5f)
        assertEquals(448f, referenceRightPaneWidthDp(layout933, 0.dp, PaneSplit.COLLAPSED).value, 0.5f)
        assertEquals(373f, referenceRightPaneWidthDp(layout933, 373.dp, PaneSplit.BALANCED).value, 0.5f)
        assertEquals(373f, referenceRightPaneWidthDp(layout933, 0.dp, PaneSplit.BALANCED).value, 0.5f)
        assertEquals(FullEditorPlacement.CenteredModal, resolveFullEditorPlacement(layout933, 696f, referenceRightPaneWidthDp(layout933, 298.dp, PaneSplit.DEFAULT).value))
        assertEquals(FullEditorPlacement.CenteredModal, resolveFullEditorPlacement(layout933, 696f, referenceRightPaneWidthDp(layout933, 0.dp, PaneSplit.DEFAULT).value))
        assertEquals(FullEditorPlacement.CenteredModal, resolveFullEditorPlacement(layout933, 696f, referenceRightPaneWidthDp(layout933, 0.dp, PaneSplit.COLLAPSED).value))
        assertEquals(FullEditorPlacement.CenteredModal, resolveFullEditorPlacement(layout933, 696f, referenceRightPaneWidthDp(layout933, 0.dp, PaneSplit.BALANCED).value))

        // 1280×800 (ExpandedThreePane: L=220, content=1060, M=380, R=680)
        val layout1280 = calculateShellPaneLayout(1280f, 800f, leftCollapsedPref = false, hasDetailContent = true)
        assertEquals(ShellPaneLayoutMode.ExpandedThreePane, layout1280.layoutMode)
        assertEquals(680f, referenceRightPaneWidthDp(layout1280, 380.dp, PaneSplit.DEFAULT).value, 0.5f)
        assertEquals(680f, referenceRightPaneWidthDp(layout1280, 0.dp, PaneSplit.DEFAULT).value, 0.5f)
        assertEquals(680f, referenceRightPaneWidthDp(layout1280, 0.dp, PaneSplit.COLLAPSED).value, 0.5f)
        assertEquals(FullEditorPlacement.RightPane, resolveFullEditorPlacement(layout1280, 800f, referenceRightPaneWidthDp(layout1280, 380.dp, PaneSplit.DEFAULT).value))
        assertEquals(FullEditorPlacement.RightPane, resolveFullEditorPlacement(layout1280, 800f, referenceRightPaneWidthDp(layout1280, 0.dp, PaneSplit.DEFAULT).value))
        assertEquals(FullEditorPlacement.RightPane, resolveFullEditorPlacement(layout1280, 800f, referenceRightPaneWidthDp(layout1280, 0.dp, PaneSplit.COLLAPSED).value))

        // Phone single-pane 411×891
        val layout411 = calculateShellPaneLayout(411f, 891f)
        assertEquals(ShellPaneLayoutMode.SinglePane, layout411.layoutMode)
        assertEquals(FullEditorPlacement.FullScreen, resolveFullEditorPlacement(layout411, 891f, referenceRightPaneWidthDp(layout411, 411.dp, PaneSplit.DEFAULT).value))

        // HingeTwoPane 860×744 (hinge 424..436)
        val hingeLayout = calculateShellPaneLayout(
            860f, 744f,
            hingeBoundsDp = net.jejer.hipda.ui.shell.ShellHingeBoundsDp(424f, 436f),
            hasDetailContent = true,
        )
        assertEquals(ShellPaneLayoutMode.HingeTwoPane, hingeLayout.layoutMode)
        assertEquals(424f, referenceRightPaneWidthDp(hingeLayout, 0.dp, PaneSplit.DEFAULT).value, 0.5f)
        assertEquals(FullEditorPlacement.CenteredModal, resolveFullEditorPlacement(hingeLayout, 744f, referenceRightPaneWidthDp(hingeLayout, 0.dp, PaneSplit.DEFAULT).value))
    }

    /** Probe 2b: 749×559 全屏阅读/COLLAPSED 与 933×696 BALANCED->全屏阅读 完整编辑器均为居中模态；1280×800 三态为 RightPane。 */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun probe2b_749OnlyRightPane_fullEditorStaysCenteredModal() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = composeOwnerWithVms(harness, postRepo, editorVm)

        composeRule.setContent {
            MultiPaneResizableWindowHost(DpSize(749.dp, 559.dp)) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        NextAppRoot(
                            settingsFlow = harness.settingsRepo.settings,
                            currentAccountFlow = harness.authRepo.currentAccount,
                            smsUnreadCountFlow = harness.smsUnread,
                            notifyUnreadCountFlow = harness.notifyUnread,
                            hostViewModel = harness.hostViewModel,
                            forumViewModel = harness.forumViewModel,
                            fullEditorViewModel = editorVm,
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        // 1. 全屏阅读 -> 回复 -> 展开全屏 -> CenteredModal（有「关闭」×，无「返回」）
        composeRule.onNodeWithContentDescription("全屏阅读").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.waitForIdle()

        // 2. 退出全屏阅读，切至 COLLAPSED -> 回复 -> 展开全屏 -> 仍为 CenteredModal
        composeRule.onNodeWithContentDescription("显示列表").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { harness.settingsRepo.updateSettings { it.copy(paneSplitLandscape = PaneSplit.COLLAPSED) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().size)
    }

    /** Probe 2c: 749×559 全屏阅读与 COLLAPSED 下快速回复面板宽 = 411dp ±1、居中、点 x=24dp 暗罩可关闭、表情槽同宽。 */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun probe2c_749OnlyRightPane_quickReplyMatches411dpCenteredScrimDismissAndEmojiWidth() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = composeOwnerWithVms(harness, postRepo, editorVm)

        composeRule.setContent {
            MultiPaneResizableWindowHost(DpSize(749.dp, 559.dp)) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        NextAppRoot(
                            settingsFlow = harness.settingsRepo.settings,
                            currentAccountFlow = harness.authRepo.currentAccount,
                            smsUnreadCountFlow = harness.smsUnread,
                            notifyUnreadCountFlow = harness.notifyUnread,
                            hostViewModel = harness.hostViewModel,
                            forumViewModel = harness.forumViewModel,
                            fullEditorViewModel = editorVm,
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density
        fun sheetBoundsDp(): Pair<Float, Float> {
            val sheet = composeRule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true).fetchSemanticsNodes().last()
            val left = with(density) { sheet.positionInRoot.x.toDp().value }
            val width = with(density) { sheet.size.width.toDp().value }
            return left to width
        }

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        // 正常态基线宽 = 411dp
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        val (_, normalWidth) = sheetBoundsDp()
        assertEquals("749×559 正常态右栏快速回复宽应为 411dp", 411f, normalWidth, 1f)
        composeRule.onNodeWithContentDescription("收起回复面板").performClick()
        composeRule.waitForIdle()

        // 全屏阅读：宽 = 411dp ±1、水平居中 (749/2=374.5dp)、表情槽同宽、点 x=24dp 暗罩可关
        composeRule.onNodeWithContentDescription("全屏阅读").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()

        val (fsLeft, fsWidth) = sheetBoundsDp()
        assertEquals("全屏阅读下面板宽必须等于正常态右栏宽 411dp", normalWidth, fsWidth, 1f)
        assertEquals("全屏阅读下面板必须水平居中", 749f / 2f, fsLeft + fsWidth / 2f, 1.5f)

        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        val emojiBounds = composeRule.onNodeWithContentDescription("默认表情").fetchSemanticsNode().let { n ->
            var p = n
            while (p.parent != null && with(density) { p.size.width.toDp().value } < 350f) {
                p = p.parent!!
            }
            with(density) { p.positionInRoot.x.toDp().value to p.size.width.toDp().value }
        }
        assertEquals("表情槽宽度必须与快速回复面板一致", fsWidth, emojiBounds.second, 1f)
        assertEquals("表情槽左缘必须与面板左缘对齐", fsLeft, emojiBounds.first, 1f)

        val sheetNode = composeRule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true).fetchSemanticsNodes().last()
        val tapX = with(density) { 24.dp.toPx() }
        val tapY = sheetNode.positionInRoot.y + sheetNode.size.height / 2f
        composeRule.onRoot().performTouchInput { click(Offset(tapX, tapY)) }
        composeRule.waitForIdle()
        assertEquals("点击左侧外部 x=24dp 暗罩必须关闭快速回复面板", 0, composeRule.onAllNodesWithContentDescription("插入图片").fetchSemanticsNodes().size)

        // COLLAPSED 态：宽 = 411dp ±1，且仍在右栏内容区居中
        composeRule.onNodeWithContentDescription("显示列表").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { harness.settingsRepo.updateSettings { it.copy(paneSplitLandscape = PaneSplit.COLLAPSED) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()

        val (colLeft, colWidth) = sheetBoundsDp()
        assertEquals("COLLAPSED 下面板宽必须等于正常态右栏宽 411dp", normalWidth, colWidth, 1f)
        val expectedContentCenter = 64f + (749f - 64f) / 2f
        assertEquals("COLLAPSED 下应居中于右栏内容区", expectedContentCenter, colLeft + colWidth / 2f, 1.5f)
    }

    /** Probe 2d: 933×696 先切 BALANCED 再全屏阅读 -> 快速回复宽与完整编辑器形态均与 BALANCED 正常态一致。 */
    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun probe2d_933BalancedThenFullscreen_matchesBalancedWidthAndPlacement() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = composeOwnerWithVms(harness, postRepo, editorVm)

        composeRule.setContent {
            MultiPaneResizableWindowHost(DpSize(933.dp, 696.dp)) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        NextAppRoot(
                            settingsFlow = harness.settingsRepo.settings,
                            currentAccountFlow = harness.authRepo.currentAccount,
                            smsUnreadCountFlow = harness.smsUnread,
                            notifyUnreadCountFlow = harness.notifyUnread,
                            hostViewModel = harness.hostViewModel,
                            forumViewModel = harness.forumViewModel,
                            fullEditorViewModel = editorVm,
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density
        fun sheetWidthDp(): Float {
            val sheet = composeRule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true).fetchSemanticsNodes().last()
            return with(density) { sheet.size.width.toDp().value }
        }

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { harness.settingsRepo.updateSettings { it.copy(paneSplitLandscape = PaneSplit.BALANCED) } }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        val balancedNormalW = sheetWidthDp()
        assertEquals("933×696 BALANCED 正常右栏宽应为 373dp", 373f, balancedNormalW, 1f)
        composeRule.onNodeWithContentDescription("收起回复面板").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("全屏阅读").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        val balancedFullscreenW = sheetWidthDp()
        assertEquals("933×696 BALANCED 全屏阅读下面板宽必须等于 BALANCED 正常态", balancedNormalW, balancedFullscreenW, 1f)

        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().size)
    }

    /** Probe 2e: 铰链双栏（HingeTwoPane 860×744, 铰链 424..436）全屏阅读下快速回复停靠右半屏、不跨铰链。 */
    @Test
    @Config(qualifiers = "w860dp-h744dp-xhdpi")
    fun probe2e_hingeTwoPaneFullscreen_quickReplyDocksRightOfHinge() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = composeOwnerWithVms(harness, postRepo, editorVm)
        val densityFloat = 2.0f // xhdpi
        val hingeInfo = verticalHingeLayoutInfo(424f, 436f, 744f, densityFloat, isSeparating = true)

        withWindowLayoutFlow(flowOf(hingeInfo)) {
            composeRule.setContent {
                MultiPaneResizableWindowHost(DpSize(860.dp, 744.dp)) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                            NextAppRoot(
                                settingsFlow = harness.settingsRepo.settings,
                                currentAccountFlow = harness.authRepo.currentAccount,
                                smsUnreadCountFlow = harness.smsUnread,
                                notifyUnreadCountFlow = harness.notifyUnread,
                                hostViewModel = harness.hostViewModel,
                                forumViewModel = harness.forumViewModel,
                                fullEditorViewModel = editorVm,
                                emojiPanelSlot = emojiSlot,
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()
            val density = composeRule.density
            composeRule.onNodeWithText("测试帖子标题 1001").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("全屏阅读").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()

            val sheet = composeRule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true).fetchSemanticsNodes().last()
            val sheetLeftDp = with(density) { sheet.positionInRoot.x.toDp().value }
            val sheetWidthDp = with(density) { sheet.size.width.toDp().value }
            val sheetRightDp = sheetLeftDp + sheetWidthDp

            assertEquals("铰链双栏全屏阅读面板宽应为右半屏宽 424dp", 424f, sheetWidthDp, 1.5f)
            assertTrue("铰链双栏全屏阅读面板左缘($sheetLeftDp)必须停靠铰链右侧(≥435dp)，不得跨铰链", sheetLeftDp >= 435f)
            assertEquals("铰链双栏全屏阅读面板右缘应贴紧右边界 860dp", 860f, sheetRightDp, 1.5f)

            // 点击铰链左侧暗罩 x=24dp 仍能关闭
            val tapX = with(density) { 24.dp.toPx() }
            val tapY = sheet.positionInRoot.y + sheet.size.height / 2f
            composeRule.onRoot().performTouchInput { click(Offset(tapX, tapY)) }
            composeRule.waitForIdle()
            assertEquals(0, composeRule.onAllNodesWithContentDescription("插入图片").fetchSemanticsNodes().size)
        }
    }

    /** Probe 2f: 1280×800 正常 / 全屏阅读 / COLLAPSED 三态下完整编辑器均为 RightPane。 */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun probe2f_1280AllThreeStates_fullEditorRemainsRightPane() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = composeOwnerWithVms(harness, postRepo, editorVm)

        composeRule.setContent {
            MultiPaneResizableWindowHost(DpSize(1280.dp, 800.dp)) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        NextAppRoot(
                            settingsFlow = harness.settingsRepo.settings,
                            currentAccountFlow = harness.authRepo.currentAccount,
                            smsUnreadCountFlow = harness.smsUnread,
                            notifyUnreadCountFlow = harness.notifyUnread,
                            hostViewModel = harness.hostViewModel,
                            forumViewModel = harness.forumViewModel,
                            fullEditorViewModel = editorVm,
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        // 1. 全屏阅读 -> RightPane
        composeRule.onNodeWithContentDescription("全屏阅读").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("关闭").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()

        // 2. COLLAPSED -> RightPane
        composeRule.onNodeWithContentDescription("显示列表").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { harness.settingsRepo.updateSettings { it.copy(paneSplitLandscape = PaneSplit.COLLAPSED) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("关闭").fetchSemanticsNodes().size)
    }
}
