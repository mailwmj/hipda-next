package net.jejer.hipda.feature.compose

import android.app.Application
import android.net.Uri
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.post.PrePostRequest
import net.jejer.hipda.data.post.PrePostResult
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.feature.app.MultiPaneResizableWindowHost
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.ImageViewerRenderer
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.shell.HiShellTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E6 验收项 2 / 3 / 4 / 5 真实 `NextAppRoot` 全链路集成测试：
 * - `QuickReplySheet` -> `展开全屏` / `插入图片` 零丢失交接、立即拉起多图选择器、内联缩略图块 0 `[attachimg]` 源码文本；
 * - 单例 `FullEditorRequestBus`（`FullEditorLauncher`）消费打开编辑器；
 * - `ForumPane` `发新帖`（含 `今日新帖` R-D1 选版块/分类）提交刷新列表并弹 `帖子已发出 | 查看` 打开新帖；
 * - §4.2 多栏投放几何验证（`411` 全屏、`640` 2 栏居中、`749`/`933` 三栏居中、`1280` 右栏含空右栏发新帖）；
 * - 返回优先级（`LoginOverlay`/`ImageViewer` > `FullEditor` > `PositionStack` > `MultiPaneBackStack`）；
 * - 跨宽缩放与 `StateRestorationTester` 进程回收保活、可选 E7 表情插槽显隐与 §11 无引用/粗体按钮。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FullEditorIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun quickReplyHandoff_zeroLossTextSelectionQuote_insertImagePicker_zeroAttachImgInUi_andEmojiSlot() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val uploadRepo = FakeImageUploadRepository()
        val editorVm = FullEditorViewModel(postRepo, uploadRepo, harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        val pickedUris = listOf(Uri.parse("content://net.jejer.hipda.test/media/pick1.jpg"))
        var pickerLaunchCount = 0
        val fakeRegistryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I : Any?, O : Any?> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    pickerLaunchCount++
                    dispatchResult(requestCode, pickedUris)
                }
            }
        }
        var bindEmoji by mutableStateOf(false)
        var emojiHostSeen: QuickReplyEmojiPanelHost? = null

        composeRule.setContent {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides owner,
                LocalActivityResultRegistryOwner provides fakeRegistryOwner,
            ) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                        fullEditorViewModel = editorVm,
                        emojiPanelSlot = if (bindEmoji) {
                            { host ->
                                emojiHostSeen = host
                                Box(Modifier.fillMaxSize(), Alignment.Center) { Text("E7 完整编辑器表情槽") }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 打开帖子 1001，长按 2 楼点「引用回复」，输入文本并设选区 [2..5]，点「展开全屏」
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("帖子 1001 第 2 楼正文", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("引用回复").performClick()
        composeRule.waitForIdle()

        val qrInput = composeRule.onNodeWithContentDescription("输入回复内容")
        qrInput.performTextInput("交接测试正文内容")
        composeRule.waitForIdle()
        qrInput.performSemanticsAction(SemanticsActions.SetSelection) { it(2, 5, false) }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()

        // 零丢失核验：文本、选区 [2..5]、引用楼层完整到达，未弹「已保存草稿」，0 个 HiGlassMarker，无表情/引用/粗体按钮
        assertTrue(editorVm.uiState.value.isOpen)
        assertEquals("交接测试正文内容", editorVm.uiState.value.body.serialize())
        assertEquals(2, editorVm.uiState.value.body.activeSelectionStart)
        assertEquals(5, editorVm.uiState.value.body.activeSelectionEnd)
        val quoteMode = editorVm.uiState.value.mode as FullEditorMode.QuotePost
        assertEquals("1001_2", quoteMode.targetPid)
        assertEquals(2, quoteMode.targetFloor)
        assertEquals("楼层作者2", quoteMode.targetAuthor)
        assertEquals(0, composeRule.onAllNodesWithText("已保存草稿").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("未绑定 E7 时必须隐藏表情按钮", 0, composeRule.onAllNodesWithContentDescription("表情").fetchSemanticsNodes().size)
        assertEquals("§11 不加粗体快捷按钮", 0, composeRule.onAllNodesWithContentDescription("粗体").fetchSemanticsNodes().size)

        // 绑定 E7 表情槽后显示「表情」按钮，可插入表情
        bindEmoji = true
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("E7 完整编辑器表情槽").assertIsDisplayed()
        composeRule.runOnIdle { emojiHostSeen?.onInsertEmoji(":)") }
        composeRule.waitForIdle()
        assertTrue(editorVm.uiState.value.body.serialize().contains(":)"))

        // 2. 退回（静默存 reply:1001 草稿并弹「已保存草稿 | 放弃」），再从快速回复点「插入图片」-> 立即拉起系统多图选择器并上传
        composeRule.onAllNodesWithContentDescription("返回").let { nodes ->
            nodes[nodes.fetchSemanticsNodes().lastIndex].performClick()
        }
        composeRule.waitForIdle()
        assertNotNull(postRepo.draftFor(DraftKey.reply("1001")))
        composeRule.onNodeWithText("已保存草稿").assertIsDisplayed()
        composeRule.onNodeWithText("放弃").assertIsDisplayed()

        // Snackbar 抬高至底部工具条上方，无需等待消失即可直接点击「回复」
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        val beforePickCount = pickerLaunchCount
        composeRule.onNodeWithContentDescription("插入图片").performClick()
        composeRule.waitForIdle()
        assertEquals("从快速回复点插入图片必须在打开编辑器后立即拉起系统选图器", beforePickCount + 1, pickerLaunchCount)
        assertEquals(listOf("1721001"), editorVm.uiState.value.uploadedImageIds)
        composeRule.onNodeWithText("未插入").assertIsDisplayed()

        // 点击已上传缩略图在正文插入可视化缩略图块，校验全语义树 0 处 `[attachimg]` 源码文本
        composeRule.onAllNodesWithContentDescription("插入图片")[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("已插入图片").assertIsDisplayed()
        val attachTagNodes = composeRule.onAllNodesWithText("[attachimg]", substring = true).fetchSemanticsNodes()
        assertEquals("编辑器 UI 严禁向用户暴露原始 [attachimg] 标签文本", 0, attachTagNodes.size)

        // 发送回复 -> 关闭编辑器并弹「回复已发送 | 查看」（先推进时钟让前序「已保存草稿」自然退场）
        composeRule.onNodeWithContentDescription("发送").performClick()
        composeRule.waitForIdle()
        assertFalse(editorVm.uiState.value.isOpen)
        composeRule.mainClock.advanceTimeBy(11_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复已发送").assertIsDisplayed()
        composeRule.onNodeWithText("查看").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun tablet1280_newThreadFromTodayNew_opensInRightPane_validatesRD1_refreshesForumAndOpensNewThread() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository().apply {
            prePostHandler = { req ->
                val fid = (req as PrePostRequest.NewThread).fid
                PrePostResult.Success(
                    defaultSamplePrePostInfo(
                        typeOptions = if (fid == 2) linkedMapOf("0" to "分类", "56" to "Discovery") else emptyMap(),
                    ),
                )
            }
        }
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        harness.hostViewModel.selectForumSource(ForumListSource.TodayNew)

        composeRule.setContent {
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
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1280dp 空右栏下从 ForumPane 顶栏点「发新帖」-> 方案 B 在右栏内展开（左缘 ≥ 600dp）
        composeRule.onNodeWithContentDescription("发新帖").performClick()
        composeRule.waitForIdle()
        val backBounds = composeRule.onNodeWithContentDescription("返回").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "1280dp 下从 ForumPane 点发新帖必须在右栏展开（左缘 ${with(composeRule.density) { backBounds.left.toDp().value }}dp ≥ 600dp）",
            with(composeRule.density) { backBounds.left.toDp().value } >= 600f,
        )

        // R-D1：今日新帖打开版块未选中 -> 点发送先报「请选择版块」
        composeRule.onNodeWithContentDescription("发送").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("请选择版块").assertIsDisplayed()

        // 选择版块 Discovery (fid=2) 与分类 Discovery (typeId=56)，填标题与正文并提交
        composeRule.onNodeWithContentDescription("版块").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Discovery").fetchSemanticsNodes().let {
            composeRule.onAllNodesWithText("Discovery")[it.lastIndex].performClick()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("分类").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Discovery").fetchSemanticsNodes().let {
            composeRule.onAllNodesWithText("Discovery")[it.lastIndex].performClick()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("标题").performTextInput("全新三栏完整编辑器发新帖")
        composeRule.onNodeWithContentDescription("正文").performTextInput("这是一篇用于验证发新帖完整链路的正文内容。")
        composeRule.waitForIdle()

        // B1 ①：1280dp 右栏展开态中栏「发新帖」仍可点击；同版块/未选版块再点不得清空在编标题与正文
        composeRule.onNodeWithContentDescription("发新帖").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("全新三栏完整编辑器发新帖").assertIsDisplayed()
        composeRule.onNodeWithText("这是一篇用于验证发新帖完整链路的正文内容。").assertIsDisplayed()

        val refreshBefore = harness.searchRepo.newPostsCalls.get()
        composeRule.onNodeWithContentDescription("发送").performClick()
        composeRule.waitForIdle()

        assertFalse("提交成功后必须关闭编辑器", editorVm.uiState.value.isOpen)
        assertTrue("发新帖成功后必须刷新列表", harness.searchRepo.newPostsCalls.get() > refreshBefore)
        composeRule.onNodeWithText("帖子已发出").assertIsDisplayed()
        composeRule.onNodeWithText("查看").performClick()
        composeRule.waitForIdle()
        assertEquals("990001", harness.hostViewModel.state.value.selectedThread?.tid)
    }

    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun threePane749_centeredModalWidth_singletonRequestBus_andBackPriorityHierarchy() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val requestBus = FullEditorRequestBus()
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        var backDispatcher: OnBackPressedDispatcher? = null

        val fakeLogin = object : LoginOverlayRenderer {
            @Composable
            override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
                BackHandler(enabled = true, onBack = onDismiss)
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), Alignment.Center) { Text("模拟登录浮层") }
            }
        }
        val fakeViewer = object : ImageViewerRenderer {
            @Composable
            override fun Overlay(args: ImageViewerLaunchArgs, onDismiss: () -> Unit) {
                BackHandler(enabled = true, onBack = onDismiss)
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim), Alignment.Center) { Text("模拟看图浮层") }
            }
        }

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        loginOverlayRenderer = fakeLogin,
                        imageViewerRenderer = fakeViewer,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                        fullEditorViewModel = editorVm,
                        fullEditorRequestBus = requestBus,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 打开帖子并建立 PositionStack（第 1 页 -> 末页）
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 1 楼，点按展开楼层跳转").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("最新回复").performClick()
        composeRule.waitForIdle()
        val detailVm = harness.createdDetailVms.single()
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        // 2. 经单例 FullEditorRequestBus (FullEditorLauncher) 打开完整编辑器 -> 749dp 右栏 411 < 560 -> 全三栏水平居中模态（宽 680dp）
        composeRule.runOnIdle {
            requestBus.open(FullEditorHandoff(tid = "1001", fid = 2, text = "总线触发待存草稿", selectionStart = 8, selectionEnd = 8))
        }
        composeRule.waitForIdle()
        val backBounds = composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val sendBounds = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val modalLeftDp = with(composeRule.density) { backBounds.left.toDp().value }
        val modalRightDp = with(composeRule.density) { sendBounds.right.toDp().value }
        assertTrue("749dp 三栏居中模态应跨左中右居中（左缘约 34.5dp，实际=$modalLeftDp）", modalLeftDp in 20f..80f)
        assertTrue("749dp 三栏居中模态宽应 ≤ 680dp（右缘约 714.5dp，实际=$modalRightDp）", modalRightDp - modalLeftDp <= 682f)

        // 3. 在完整编辑器上方打开 LoginOverlay -> 按返回优先级：LoginOverlay > FullEditor(静默存草稿) > PositionStack > MultiPane
        composeRule.runOnIdle { harness.hostViewModel.openLoginOverlay(LoginOverlayMode.Login) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("模拟登录浮层").assertIsDisplayed()

        // 返回 1：先关 LoginOverlay，编辑器仍打开
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("模拟登录浮层").fetchSemanticsNodes().size)
        assertTrue(editorVm.uiState.value.isOpen)

        // 返回 2：关完整编辑器并静默保存草稿，不误出 PositionStack
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertFalse(editorVm.uiState.value.isOpen)
        assertEquals("总线触发待存草稿", postRepo.draftFor(DraftKey.reply("1001"))?.content)
        assertTrue("关闭完整编辑器不得误弹 PositionStack", detailVm.uiState.value.canPopPositionStack)

        // 返回 3：PositionStack 回第 1 页
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, detailVm.uiState.value.page)
        assertFalse(detailVm.uiState.value.canPopPositionStack)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun resizeAndProcessRecreation_preservesOpenEditorContentAndInlineImages() {
        val restorationTester = StateRestorationTester(composeRule)
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val uploadRepo = FakeImageUploadRepository()
        var currentSize by mutableStateOf(DpSize(1280.dp, 800.dp))
        var activeVm = FullEditorViewModel(postRepo, uploadRepo, harness.authRepo, harness.settingsRepo) { 10_000L }

        restorationTester.setContent {
            val owner = ownerWithComposeVms(harness, postRepo, activeVm)
            MultiPaneResizableWindowHost(windowSize = currentSize) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        NextAppRoot(
                            settingsFlow = harness.settingsRepo.settings,
                            currentAccountFlow = harness.authRepo.currentAccount,
                            smsUnreadCountFlow = harness.smsUnread,
                            notifyUnreadCountFlow = harness.notifyUnread,
                            hostViewModel = harness.hostViewModel,
                            forumViewModel = harness.forumViewModel,
                            fullEditorViewModel = activeVm,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 在 1280dp 右栏打开发新帖，填写标题和带内联图片的正文
        composeRule.onNodeWithContentDescription("发新帖").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            activeVm.onSubjectChanged("跨形态保活标题测试")
            activeVm.onTextBlockChanged(activeVm.uiState.value.body.firstTextBlockId, "旋转保活正文", 6, 6)
            activeVm.onPickImages(listOf(Uri.parse("content://net.jejer.hipda.test/media/rot1.jpg")))
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            activeVm.onInsertUploadedImage(activeVm.uiState.value.uploads.single().localId)
        }
        composeRule.waitForIdle()

        // 动态缩窗 1280dp (RightPane) -> 749dp (CenteredModal) -> 411dp (FullScreen)：内容零丢失且 UI 实际可见
        currentSize = DpSize(749.dp, 559.dp)
        composeRule.waitForIdle()
        assertTrue(activeVm.uiState.value.isOpen)
        composeRule.onNodeWithText("跨形态保活标题测试").assertIsDisplayed()
        composeRule.onNodeWithText("旋转保活正文").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("已插入图片").assertIsDisplayed()

        currentSize = DpSize(411.dp, 891.dp)
        composeRule.waitForIdle()
        assertTrue(activeVm.uiState.value.isOpen)
        composeRule.onNodeWithText("跨形态保活标题测试").assertIsDisplayed()
        composeRule.onNodeWithText("旋转保活正文").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("已插入图片").assertIsDisplayed()

        // 模拟进程回收（全新空 FullEditorViewModel 实例）：由 rememberSaveable 无损恢复打开态、标题、正文与内联图片，且 UI 语义树全部可见（B2 防空心断言）
        activeVm = FullEditorViewModel(postRepo, uploadRepo, harness.authRepo, harness.settingsRepo) { 10_000L }
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertTrue("进程重建后编辑器必须保持打开", activeVm.uiState.value.isOpen)
        assertEquals("跨形态保活标题测试", activeVm.uiState.value.subject)
        assertEquals(setOf("1721001"), activeVm.uiState.value.insertedImageIds)
        assertTrue(activeVm.uiState.value.body.serialize().contains("旋转保活正文"))
        composeRule.onNodeWithText("跨形态保活标题测试").assertIsDisplayed()
        composeRule.onNodeWithText("旋转保活正文").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("已插入图片").assertIsDisplayed()
    }

    /** 移植自 V-E6 `probe2b`：含内联图片块的多段正文（`t0 + img + t2`）光标停在 `t2` 偏移 1，经进程重建后仍留在 `t2` 偏移 1。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe2b_multiBlockInlineImageCursor_preservedAcrossStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val uploadRepo = FakeImageUploadRepository()
        val createdEditorVms = mutableListOf<FullEditorViewModel>()

        restorationTester.setContent {
            val freshStore = remember { ViewModelStore() }
            val vmFactory = remember {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                        modelClass.isAssignableFrom(QuickReplyViewModel::class.java) -> QuickReplyViewModel(postRepo, harness.authRepo) as T
                        modelClass.isAssignableFrom(FullEditorViewModel::class.java) -> FullEditorViewModel(postRepo, uploadRepo, harness.authRepo, harness.settingsRepo) { 10_000L }.also(createdEditorVms::add) as T
                        else -> harness.vmFactory.create(modelClass)
                    }
                }
            }
            val owner = remember(freshStore) {
                object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                    override val viewModelStore: ViewModelStore = freshStore
                    override val defaultViewModelProviderFactory: ViewModelProvider.Factory = vmFactory
                }
            }
            Box(Modifier.requiredSize(DpSize(411.dp, 891.dp))) {
                MultiPaneResizableWindowHost(DpSize(411.dp, 891.dp)) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
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
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()

        val editorVm = createdEditorVms.last()
        composeRule.runOnIdle { editorVm.onPickImages(listOf(Uri.parse("content://probe.test/img/1"))) }
        composeRule.waitForIdle()

        val bodyField = composeRule.onNodeWithContentDescription("回复 楼层作者1")
        bodyField.assertIsFocused()
        bodyField.performTextInput("前段后段")
        composeRule.waitForIdle()
        bodyField.performTextInputSelection(TextRange(2, 2))
        composeRule.waitForIdle()
        val uploadedLocalId = editorVm.uiState.value.uploads.single().localId
        composeRule.runOnIdle { editorVm.onInsertUploadedImage(uploadedLocalId) }
        composeRule.waitForIdle()

        val afterImgBlockId = editorVm.uiState.value.body.lastTextBlockId
        composeRule.runOnIdle { editorVm.onTextBlockSelectionChanged(afterImgBlockId, 1, 1) }
        composeRule.waitForIdle()
        assertEquals(afterImgBlockId, editorVm.uiState.value.body.activeTextBlockId)
        assertEquals(1, editorVm.uiState.value.body.activeSelectionStart)

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        val restoredVm = createdEditorVms.last()
        assertEquals(
            "多段图文重建后光标应留在图片后的文本块 (${restoredVm.uiState.value.body.lastTextBlockId})，实际 active=${restoredVm.uiState.value.body.activeTextBlockId}",
            restoredVm.uiState.value.body.lastTextBlockId,
            restoredVm.uiState.value.body.activeTextBlockId,
        )
        assertEquals(1, restoredVm.uiState.value.body.activeSelectionStart)
        assertEquals(1, restoredVm.uiState.value.body.activeSelectionEnd)
        composeRule.onNodeWithText("前段").assertIsDisplayed()
        composeRule.onNodeWithText("后段").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("已插入图片").assertIsDisplayed()
    }

    /**
     * U5a 更新（原 V-E6 `probe4b`）：
     * - 749×559 收起中栏后仍保持居中模态（与正常三栏一致）：先点 `SidebarToggleButton` 打开 `SidebarModalDrawer`，再经 `FullEditorRequestBus` 打开完整编辑器（模态盖在抽屉上），经 `StateRestorationTester` 重建后两次返回依次关闭编辑器、抽屉；
     * - 1280×800 收起中栏后仍为 `RightPane`，经 `StateRestorationTester` 重建后保持 `RightPane`。
     */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun probe4b_drawerAboveRightPaneEditor_backPriorityPreservedAcrossStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val uploadRepo = FakeImageUploadRepository()
        val editorVm = FullEditorViewModel(postRepo, uploadRepo, harness.authRepo, harness.settingsRepo) { 10_000L }
        val requestBus = FullEditorRequestBus()
        val preservedStore = harness.parentOwner.viewModelStore
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        var backDispatcher: OnBackPressedDispatcher? = null

        restorationTester.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            Box(Modifier.requiredSize(DpSize(749.dp, 559.dp))) {
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
                                fullEditorRequestBus = requestBus,
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 进帖子并收起中栏：749dp 下参考右栏宽 411 < 560 -> 保持 CenteredModal；先开抽屉，再经总线开居中模态编辑器（模态盖在抽屉之上）
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { harness.settingsRepo.updateSettings { it.copy(paneSplitLandscape = PaneSplit.COLLAPSED) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()

        composeRule.runOnIdle {
            requestBus.open(FullEditorHandoff(tid = "1001", fid = 2, text = "收起中栏模态正文", selectionStart = 8, selectionEnd = 8))
        }
        composeRule.waitForIdle()
        assertTrue(editorVm.uiState.value.isOpen)
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        assertEquals("749×559 COLLAPSED 下应为居中模态而非 RightPane", 0, composeRule.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().size)

        // 重建后抽屉与居中模态编辑器同时恢复打开，两次返回依次关闭编辑器、抽屉
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()
        assertTrue(editorVm.uiState.value.isOpen)

        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertFalse("第一次返回应先关闭盖在抽屉上的居中模态编辑器", editorVm.uiState.value.isOpen)
        composeRule.onNodeWithTag(HiShellTestTags.SidebarModalDrawer).assertIsDisplayed()

        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertTrue(
            "第二次返回应关闭模态侧栏抽屉",
            composeRule.onAllNodesWithTag(HiShellTestTags.SidebarModalDrawer).fetchSemanticsNodes().isEmpty(),
        )
        assertNotNull(preservedStore)
    }

    /** U5a 验收 2：1280×800 收起中栏（COLLAPSED）下完整编辑器保持 RightPane，且经 StateRestorationTester 重建后仍为 RightPane。 */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun probe4b_tablet1280Collapsed_rightPaneEditorPreservedAcrossStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)

        restorationTester.setContent {
            Box(Modifier.requiredSize(DpSize(1280.dp, 800.dp))) {
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
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { harness.settingsRepo.updateSettings { it.copy(paneSplitLandscape = PaneSplit.COLLAPSED) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()

        assertTrue(editorVm.uiState.value.isOpen)
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
        assertEquals("1280×800 COLLAPSED 下应为 RightPane（无关闭按钮）", 0, composeRule.onAllNodesWithContentDescription("关闭").fetchSemanticsNodes().size)

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertTrue(editorVm.uiState.value.isOpen)
        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
        assertEquals("1280×800 COLLAPSED 重建后仍必须为 RightPane", 0, composeRule.onAllNodesWithContentDescription("关闭").fetchSemanticsNodes().size)
    }

    /** 移植自 V-E6 `probe9`：`FullEditorRequestBus` 单例总线消费 + 退出保存草稿时「已保存草稿」Snackbar 必须避开底部「回复」工具条且可立即点击「回复」。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe9_fullEditorRequestBus_andDraftSavedSnackbarAvoidsBottomReplyToolbar() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val bus = FullEditorRequestBus()
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        var backDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            MultiPaneResizableWindowHost(DpSize(411.dp, 891.dp)) {
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
                            fullEditorRequestBus = bus,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()

        val handoff = FullEditorHandoff(
            tid = "1001", fid = 2, text = "总线交接文本", selectionStart = 2, selectionEnd = 2,
            quotePid = "1001_2", quoteAuthor = "楼层作者2", quoteFloor = 2,
        )
        composeRule.runOnIdle { bus.open(handoff) }
        composeRule.waitForIdle()
        assertNull("PendingFullEditorRequest 在 NextAppRoot 打开后必须被立即消费", bus.pendingRequest.value)
        assertTrue(editorVm.uiState.value.isOpen)
        assertEquals("总线交接文本", editorVm.uiState.value.body.serialize())
        assertEquals(2, editorVm.uiState.value.body.activeSelectionStart)

        // 返回退出保存草稿，核验「已保存草稿」Snackbar 严格在底部「回复」按钮上方（0 重叠），且不等待 11 秒即可直接点开快速回复
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        val snackbarBounds = composeRule.onNodeWithText("已保存草稿").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val replyBounds = composeRule.onNodeWithText("回复").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertFalse(
            "「已保存草稿」Snackbar ($snackbarBounds) 不得遮挡底部「回复」按钮 ($replyBounds)",
            snackbarBounds.overlaps(replyBounds),
        )
        assertTrue(
            "「已保存草稿」Snackbar 底缘 (${snackbarBounds.bottom}) 必须严格高于「回复」按钮顶缘 (${replyBounds.top})",
            snackbarBounds.bottom <= replyBounds.top,
        )

        // 立即点「回复」应直接打开快速回复面板，无需等待 Snackbar 消失
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("插入图片").assertIsDisplayed()
    }

    /** U3 验收项 2：单行标题去重、回复态正文无重复视觉占位（保留无障碍语义）、空态无底发送钮可点触发 ContentTooShort、32dp 视觉胶囊 + 48dp 触控区、模态关闭图标 vs 全屏返回图标。 */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun u3_singleLineTopBar_deduplicatedReplyPlaceholder_emptyTransparentSendTriggersValidation_32dpPill48dpTouch_andModalClose() {
        val postRepo = FakePostRepository()
        val authRepo = FakeQuickReplyAuthRepository()
        val vm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), authRepo, FakeComposeSettingsRepository()) { 10_000L }
        var isModal by mutableStateOf(false)
        var capturedPrimaryArgb = 0
        var capturedSurfaceArgb = 0

        vm.open(FullEditorRequest.fromHandoff(FullEditorHandoff("2609100", 2, "", 0, 0, threadAuthor = "linus_hi")))

        composeRule.setContent {
            net.jejer.hipda.ui.designsystem.HiTheme(
                themeMode = net.jejer.hipda.ui.designsystem.HiThemeMode.LIGHT,
                seed = net.jejer.hipda.ui.designsystem.HiColorPresets.Brand.toSeed(),
                motionDurationScale = object : androidx.compose.ui.MotionDurationScale { override val scaleFactor: Float = 0f },
            ) {
                capturedPrimaryArgb = MaterialTheme.colorScheme.primary.hashCode()
                capturedSurfaceArgb = MaterialTheme.colorScheme.surface.hashCode()
                if (isModal) {
                    FullEditorPlacementFrame(FullEditorPlacement.CenteredModal, 749f, isSubmitting = false, onRequestClose = { vm.requestClose() }) { modalCard ->
                        FullEditorScreen(viewModel = vm, onOpenLogin = {}, isModalCard = modalCard)
                    }
                } else {
                    FullEditorScreen(viewModel = vm, onOpenLogin = {}, isModalCard = false)
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density

        fun assertSendButtonGeometry(label: String) {
            val sendNode = composeRule.onNodeWithContentDescription("发送").assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
            val touchHDp = with(density) { sendNode.size.height.toDp().value }
            val touchWDp = with(density) { sendNode.size.width.toDp().value }
            assertTrue("$label 触控高必须 ≥ 48dp，实测 ${touchHDp}dp", touchHDp >= 47.5f)
            assertTrue("$label 触控宽必须 ≥ 48dp，实测 ${touchWDp}dp", touchWDp >= 47.5f)
            val pillHeightPx = sendNode.firstChildLayoutHeightPx()
            assertNotNull("$label 必须包含独立 32dp 视觉胶囊子层", pillHeightPx)
            val visualHDp = with(density) { requireNotNull(pillHeightPx).toDp().value }
            assertEquals("$label 视觉胶囊高度必须为 32dp", 32f, visualHDp, 1f)
        }

        fun sampleSendPillEdgeRgb(): Int {
            val img = composeRule.onNodeWithContentDescription("发送").captureToImage()
            val buf = IntArray(img.width * img.height)
            img.readPixels(buf)
            // 在 48dp 节点垂直中线（落入 32dp 视觉胶囊内）、距左缘 4dp 处取底色像素（避开中间文字）
            val sampleX = with(density) { 6.dp.roundToPx() }.coerceIn(1, img.width - 2)
            val sampleY = img.height / 2
            return buf[sampleY * img.width + sampleX]
        }

        // 1. ReplyThread（全屏态）：单行标题「回复 linus_hi」，无独立「回复」副/主标题重复；正文视觉无占位但无障碍描述保留
        assertEquals("全屏态左上角应为「返回」", 1, composeRule.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().size)
        assertEquals("全屏态不应显示「关闭」", 0, composeRule.onAllNodesWithContentDescription("关闭").fetchSemanticsNodes().size)
        assertEquals("顶栏「回复 linus_hi」只应出现一次，正文不重复视觉占位", 1, composeRule.onAllNodesWithText("回复 linus_hi").fetchSemanticsNodes().size)
        assertEquals("回复态顶栏不得再拆出单独「回复」行", 0, composeRule.onAllNodesWithText("回复").fetchSemanticsNodes().size)
        assertEquals("回复态正文不得显示「正文」占位", 0, composeRule.onAllNodesWithText("正文").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("回复 linus_hi").assertIsDisplayed()

        // 空态发送按钮：无底色（胶囊底缘与顶栏 surface 同色），32dp 视觉高 + 48dp 触控区，可点击且触发「内容至少 6 个字」
        assertSendButtonGeometry("ReplyThread 空态发送钮")
        val emptyPillPixel = sampleSendPillEdgeRgb()
        composeRule.onNodeWithContentDescription("发送").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("内容至少 6 个字").assertIsDisplayed()

        // 输入内容后：视觉胶囊切换为实心 primary 底色（像素显著变化），几何仍为 32dp/48dp
        composeRule.onNodeWithContentDescription("回复 linus_hi").performTextInput("输入有效回复内容")
        composeRule.waitForIdle()
        assertSendButtonGeometry("ReplyThread 有内容发送钮")
        val filledPillPixel = sampleSendPillEdgeRgb()
        assertTrue(
            "有内容后发送胶囊必须从透明无底切换为实心 primary 底色 (empty=0x${emptyPillPixel.toUInt().toString(16)}, filled=0x${filledPillPixel.toUInt().toString(16)})",
            emptyPillPixel != filledPillPixel,
        )
        assertTrue(capturedPrimaryArgb != capturedSurfaceArgb)

        // 2. QuotePost：单行标题「引用第 3 楼 · alice」，无独立「引用回复」行，正文无视觉占位，无障碍描述保留
        composeRule.runOnIdle {
            vm.open(FullEditorRequest.fromHandoff(FullEditorHandoff("2609101", 2, "", 0, 0, quotePid = "301", quoteAuthor = "alice", quoteFloor = 3, threadAuthor = "linus_hi")))
        }
        composeRule.waitForIdle()
        assertEquals("引用态单行标题只应出现一次", 1, composeRule.onAllNodesWithText("引用第 3 楼 · alice").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("引用回复").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("引用第 3 楼 · alice").assertIsDisplayed()

        // QuotePost 缺楼层回退「回复 alice」，缺楼层且缺作者回退「引用回复」
        composeRule.runOnIdle {
            vm.open(FullEditorRequest.fromHandoff(FullEditorHandoff("2609102", 2, "", 0, 0, quotePid = "302", quoteAuthor = "alice", quoteFloor = null)))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复 alice").assertIsDisplayed()
        composeRule.runOnIdle {
            vm.open(FullEditorRequest.fromHandoff(FullEditorHandoff("2609103", 2, "", 0, 0, quotePid = "303", quoteAuthor = null, quoteFloor = null)))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("引用回复").assertIsDisplayed()

        // 3. NewThread：顶栏「发新帖」，正文保留「正文」占位以与标题框区分
        composeRule.runOnIdle { vm.open(FullEditorRequest.NewThread(initialFid = 2)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("发新帖").assertIsDisplayed()
        composeRule.onNodeWithText("正文").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("正文").assertIsDisplayed()

        // 4. 居中模态态（isModalCard = true）：左上角为「关闭」（无「返回」），且可点击关闭
        isModal = true
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        assertEquals("居中模态卡片左上角不得显示「返回」", 0, composeRule.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().size)
        assertSendButtonGeometry("CenteredModal 发送钮")
        composeRule.onNodeWithContentDescription("关闭").performClick()
        composeRule.waitForIdle()
        assertFalse("点击模态左上关闭按钮必须关闭编辑器", vm.uiState.value.isOpen)
    }

    private fun ownerWithComposeVms(
        harness: MultiPaneTestHarness,
        postRepo: FakePostRepository,
        editorVm: FullEditorViewModel,
    ): ViewModelStoreOwner {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) -> QuickReplyViewModel(postRepo, harness.authRepo) as T
                modelClass.isAssignableFrom(FullEditorViewModel::class.java) -> editorVm as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        return object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore = harness.parentOwner.viewModelStore
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory = factory
        }
    }
}
