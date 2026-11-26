package net.jejer.hipda.feature.compose

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.flow.flowOf
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.app.auditGlassModifiers
import net.jejer.hipda.feature.app.verticalHingeLayoutInfo
import net.jejer.hipda.feature.app.withWindowLayoutFlow
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.ImageViewerRenderer
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassBlurActive
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.LocalHiGlassSuspended
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.shell.HiShellTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E5 验收项 1 & 4（真实 `NextAppRoot` 全链路集成测试）：
 * 1. 验证 `ImageViewer` / `LoginOverlay` > `ModalDrawer` > `QuickReplySheet`（静默保存草稿收起）> `PositionStack` > `MultiPaneBackStack` 严格返回优先级；
 * 2. 验证物理铰链双栏（`isSeparating == true`）下面板与暗罩严格位于铰链右侧不越界且守住 `≤ 3 / ≤ 2 / ≤ 5` 玻璃预算；
 * 3. 验证上方看图暂停底层玻璃（`LocalHiGlassSuspended == true`）时 `QuickReplySheet` 同步停采样、归零 `HiGlassMarker` 与 `drawBackdrop`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class QuickReplyIntegrationBackPriorityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun realNextAppRoot_backPriority_loginOverlayAndModalDrawerAboveQuickReplyAbovePositionStackAboveMultiPane() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val owner = ownerWithQuickReply(harness, postRepo)
        var backDispatcher: OnBackPressedDispatcher? = null

        val fakeImageViewer = object : ImageViewerRenderer {
            @Composable
            override fun Overlay(args: ImageViewerLaunchArgs, onDismiss: () -> Unit) {
                BackHandler(enabled = true, onBack = onDismiss)
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim), Alignment.Center) {
                    Text("模拟全屏看图浮层")
                }
            }
        }

        val fakeLoginOverlay = object : LoginOverlayRenderer {
            @Composable
            override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
                BackHandler(enabled = true, onBack = onDismiss)
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), Alignment.Center) {
                    Text("模拟登录浮层")
                }
            }
        }

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        loginOverlayRenderer = fakeLoginOverlay,
                        imageViewerRenderer = fakeImageViewer,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 打开帖子 1001 并经内联跳页跳至末页建立非空 PositionStack（栈底第 1 页）
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 1 页，共 3 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("末页").performClick()
        composeRule.waitForIdle()
        val detailVm = harness.createdDetailVms.single()
        assertEquals(3, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        // 2. 打开快速回复面板并输入草稿
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("回复 楼层作者1").performTextInput("待保存返回草稿")
        composeRule.waitForIdle()

        // 3. 在快速回复打开状态下展开 749dp 模态侧栏抽屉（ModalDrawer）并点击打开 LoginOverlay
        composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()

        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay(LoginOverlayMode.Login) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("模拟登录浮层").assertIsDisplayed()

        // 返回 1：仅关闭 LoginOverlay，ModalDrawer 仍展开（其 underlyingInteractionGuard 期间隐藏底层无障碍节点，收起抽屉后可验证 QuickReplySheet 未被关闭）
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("模拟登录浮层").fetchSemanticsNodes().size)
        assertNull(harness.hostViewModel.state.value.loginOverlayMode)
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()

        // 返回 2：仅关闭 ModalDrawer，底层 QuickReplySheet 与已输入草稿与 PositionStack 均完好无损
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
        assertEquals(3, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        // 返回 3：收起 QuickReplySheet 并静默保存 reply:1001 草稿，不误出 PositionStack、不误关帖子
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("收起回复面板").fetchSemanticsNodes().size)
        assertEquals("待保存返回草稿", postRepo.draftFor(DraftKey.reply("1001"))?.content)
        assertEquals("关闭快速回复不得出栈 PositionStack", 3, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)
        assertEquals("1001", harness.hostViewModel.state.value.selectedThread?.tid)

        // 返回 4：PositionStack 出栈回到第 1 页，帖子仍打开
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, detailVm.uiState.value.page)
        assertFalse(detailVm.uiState.value.canPopPositionStack)
        assertEquals("1001", harness.hostViewModel.state.value.selectedThread?.tid)

        // 返回 5：MultiPaneBackStack 清空右栏回到安静占位
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertNull(harness.hostViewModel.state.value.selectedThread)
    }

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun hingeTwoPane_quickReplyStaysStrictlyRightOfHinge_within3Markers_andDoesNotBlockLeftPane() {
        val hingeInfo = verticalHingeLayoutInfo(leftDp = 371f, rightDp = 378f, heightDp = 559f, density = 2f, isSeparating = true)
        withWindowLayoutFlow(flowOf(hingeInfo)) {
            val harness = MultiPaneTestHarness()
            val postRepo = FakePostRepository()
            val owner = ownerWithQuickReply(harness, postRepo)

            composeRule.setContent {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        NextAppRoot(
                            settingsFlow = harness.settingsRepo.settings,
                            currentAccountFlow = harness.authRepo.currentAccount,
                            smsUnreadCountFlow = harness.smsUnread,
                            notifyUnreadCountFlow = harness.notifyUnread,
                            hostViewModel = harness.hostViewModel,
                            forumViewModel = harness.forumViewModel,
                        )
                    }
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("测试帖子标题 1001").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("回复").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("回复 楼层作者1").performTextInput("铰链右栏草稿")
            composeRule.waitForIdle()

            // 1. 铰链双栏下快速回复面板严格位于铰链右侧（≥ 378dp）且不溢出右缘
            val handleBounds = composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val leftDp = with(composeRule.density) { handleBounds.left.toDp().value }
            val rightDp = with(composeRule.density) { handleBounds.right.toDp().value }
            assertTrue("铰链双栏快速回复面板左缘 ${leftDp}dp 必须 ≥ 378dp（不跨铰链）", leftDp >= 377.5f)
            assertTrue("铰链双栏快速回复面板右缘 ${rightDp}dp 必须 ≤ 749dp", rightDp <= 749.5f)

            // 2. 玻璃预算守卫：左紧凑胶囊 (1) + 右顶栏 (1) + 右快速回复面板 (1) = 3 ≤ 3，2 LayerBackdrop，0 嵌套，drawBackdrop ≤ 5
            val markers = composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(3, markers.size)
            composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
            val audit = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
            assertEquals(2, audit.layerBackdropCount)
            assertEquals(0, audit.nestedLayerBackdropCount)
            assertTrue("铰链双栏快速回复展开时 drawBackdrop 必须 ≤ 5，实际=${audit.drawBackdropCount}", audit.drawBackdropCount <= 5)

            // 3. 暗罩仅覆盖右栏，左栏可继续点选另一帖子且不遮挡左栏交互
            composeRule.onNodeWithText("测试帖子标题 1002").performClick()
            composeRule.waitForIdle()
            assertEquals("1002", harness.hostViewModel.state.value.selectedThread?.tid)
        }
    }

    /** 验证顶层全屏看图浮层覆盖快速回复面板时的返回优先级（`ImageViewer` > `QuickReplySheet` > `PositionStack`）与 `LocalHiGlassSuspended` 玻璃暂停恢复。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun imageViewerOverlayAboveQuickReply_suspendsGlassAndWinsBackPriority() {
        val postRepo = FakePostRepository()
        val authRepo = FakeQuickReplyAuthRepository()
        val detailVm = ThreadDetailViewModel(FakeComposeThreadDetailRepository(totalPages = 3), createIdleComposeGateway(), authRepo)
        val qrVm = QuickReplyViewModel(postRepo, authRepo, clockMs = { 10_000L })
        var backDispatcher: OnBackPressedDispatcher? = null
        var showViewer by mutableStateOf(false)

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    Box(Modifier.fillMaxSize()) {
                        // 对齐 NextAppRoot.kt:152：看图浮层覆盖期间向底层详情栏提供 LocalHiGlassSuspended = true
                        CompositionLocalProvider(LocalHiGlassSuspended provides showViewer) {
                            ThreadDetailPane(
                                args = ThreadNavArgs(tid = "2606099", title = "看图覆盖玻璃暂停测试", fid = 2, author = "linus_hi"),
                                chromeSpec = DetailChromeSpec(showBack = true),
                                onBack = {},
                                onOpenLogin = {},
                                viewModel = detailVm,
                                quickReplyViewModel = qrVm,
                            )
                        }
                        if (showViewer) {
                            BackHandler(enabled = true) { showViewer = false }
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim), Alignment.Center) {
                                Text("全屏看图覆盖中")
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 跳至第 3 页建立非空 PositionStack，展开快速回复面板并输入草稿
        composeRule.onNodeWithContentDescription("第 1 页，共 3 页，点按展开跳页").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("末页").performClick()
        composeRule.waitForIdle()
        assertEquals(3, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("回复 linus_hi").performTextInput("看图下方草稿")
        composeRule.waitForIdle()
        assertEquals(2, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(2, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassBlurActive, true), useUnmergedTree = true).fetchSemanticsNodes().size)

        // 2. 覆盖全屏看图（LocalHiGlassSuspended = true）：全部玻璃标记、模糊、LayerBackdrop 与 DrawBackdrop 归零
        showViewer = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("全屏看图覆盖中").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassBlurActive, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        val suspendedAudit = auditGlassModifiers(composeRule.onRoot().fetchSemanticsNode().layoutInfo)
        assertEquals(0, suspendedAudit.layerBackdropCount)
        assertEquals(0, suspendedAudit.drawBackdropCount)

        // 3. 按返回 1：仅关闭看图并自动恢复顶栏 + 快速回复面板 2 区域玻璃，输入内容保持完整
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertFalse(showViewer)
        composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("看图下方草稿", qrVm.uiState.value.text)

        // 4. 按返回 2：静默保存草稿并收起快速回复面板，不误出 PositionStack
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("收起回复面板").fetchSemanticsNodes().size)
        assertEquals("看图下方草稿", postRepo.draftFor(DraftKey.reply("2606099"))?.content)
        assertEquals(3, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        // 5. 按返回 3：PositionStack 出栈回到第 1 页
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, detailVm.uiState.value.page)
        assertFalse(detailVm.uiState.value.canPopPositionStack)
    }

    private fun ownerWithQuickReply(
        harness: MultiPaneTestHarness,
        postRepo: FakePostRepository,
    ): ViewModelStoreOwner {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) ->
                    QuickReplyViewModel(postRepo, harness.authRepo) as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        return object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore = harness.parentOwner.viewModelStore
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory = factory
        }
    }
}
