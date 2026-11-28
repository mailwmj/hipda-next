package net.jejer.hipda.feature.compose

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import java.util.concurrent.atomic.AtomicInteger
import net.jejer.hipda.feature.app.MultiPaneResizableWindowHost
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.compose.emoji.EmojiPanelRendererImpl
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * U6 验收 1–5 真实 `NextAppRoot` 全链路测试：
 * 1. 跨 `RightPane` / `CenteredModal` / `FullScreen` 移动保留标题、单正文块选区及多正文块（含内联图）原焦点；
 * 2. 移动前无焦点时不抢焦；
 * 3. `1280→411` 与 7 步跨形态旋转保持表情面板打开、正文与选区不变；
 * 4. 逐帧推进（`autoAdvance = false`）验证 `1280→411` 与 `411→1280` 每一帧编辑器恰好挂载 1 处；
 * 5. 全程跨形态移动不触发 `keyboardController.show()`，`hide()` 不关闭表情面板。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FullEditorPlacementMoveTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private class RecordingKeyboardController : SoftwareKeyboardController {
        val showCalls = AtomicInteger(0)
        val hideCalls = AtomicInteger(0)

        override fun show() {
            showCalls.incrementAndGet()
        }

        override fun hide() {
            hideCalls.incrementAndGet()
        }

        fun reset() {
            showCalls.set(0)
            hideCalls.set(0)
        }
    }

    private val emojiSlot: @Composable (QuickReplyEmojiPanelHost) -> Unit = { host ->
        EmojiPanelRendererImpl().Content(host, Modifier)
    }

    private val subjectMatcher: SemanticsMatcher = hasSetTextAction() and SemanticsMatcher.expectValue(
        SemanticsProperties.ContentDescription,
        listOf("标题"),
    )

    private val newThreadBodyMatcher: SemanticsMatcher = hasSetTextAction() and SemanticsMatcher.expectValue(
        SemanticsProperties.ContentDescription,
        listOf("正文"),
    )

    private fun isNodeFocused(matcher: SemanticsMatcher): Boolean =
        composeRule.onNode(matcher).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

    private fun selectionOf(matcher: SemanticsMatcher): TextRange? =
        composeRule.onNode(matcher).fetchSemanticsNode().config
            .let { if (SemanticsProperties.TextSelectionRange in it) it[SemanticsProperties.TextSelectionRange] else null }

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

    /** 验收 1(a) & 5：1280×800 发新帖标题输入「标题保焦」并保焦 → 749×559（居中模态）→ 411×891（全屏）→ 1280×800（右栏），每步标题获焦、正文未获焦、选区在末尾且移动期间 show()==0。 */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun acceptance1a_newThreadSubjectFocusAndEndSelectionPreservedAcrossPlacements() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        val keyboardSpy = RecordingKeyboardController()
        var winSize by mutableStateOf(DpSize(1280.dp, 800.dp))

        composeRule.setContent {
            MultiPaneResizableWindowHost(winSize) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalSoftwareKeyboardController provides keyboardSpy,
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
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("发新帖").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(subjectMatcher).performTextInput("标题保焦")
        composeRule.waitForIdle()

        assertTrue("发新帖标题初始应获焦", isNodeFocused(subjectMatcher))
        assertFalse("发新帖正文初始不应获焦", isNodeFocused(newThreadBodyMatcher))
        assertEquals("标题初始选区应在末尾", TextRange(4, 4), selectionOf(subjectMatcher))

        keyboardSpy.reset()

        // 1280×800 (RightPane) -> 749×559 (CenteredModal)
        winSize = DpSize(749.dp, 559.dp)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("关闭").assertIsDisplayed()
        assertTrue("缩窗至 749×559 (CenteredModal) 后标题必须保持获焦", isNodeFocused(subjectMatcher))
        assertFalse("缩窗至 749×559 后正文不得获焦", isNodeFocused(newThreadBodyMatcher))
        assertEquals("缩窗至 749×559 后标题选区必须在末尾", TextRange(4, 4), selectionOf(subjectMatcher))
        assertEquals("缩窗至 749×559 移动不得调用 show()", 0, keyboardSpy.showCalls.get())

        // 749×559 (CenteredModal) -> 411×891 (FullScreen)
        winSize = DpSize(411.dp, 891.dp)
        composeRule.waitForIdle()
        assertEquals("缩窗至 411×891 (FullScreen) 后不应有关闭按钮", 0, composeRule.onAllNodesWithContentDescription("关闭").fetchSemanticsNodes().size)
        assertTrue("缩窗至 411×891 (FullScreen) 后标题必须保持获焦", isNodeFocused(subjectMatcher))
        assertFalse("缩窗至 411×891 后正文不得获焦", isNodeFocused(newThreadBodyMatcher))
        assertEquals("缩窗至 411×891 后标题选区必须在末尾", TextRange(4, 4), selectionOf(subjectMatcher))
        assertEquals("缩窗至 411×891 移动不得调用 show()", 0, keyboardSpy.showCalls.get())

        // 411×891 (FullScreen) -> 1280×800 (RightPane, isModalCard 同为 false)
        winSize = DpSize(1280.dp, 800.dp)
        composeRule.waitForIdle()
        assertTrue("扩窗至 1280×800 (RightPane) 后标题必须保持获焦", isNodeFocused(subjectMatcher))
        assertFalse("扩窗至 1280×800 后正文不得获焦", isNodeFocused(newThreadBodyMatcher))
        assertEquals("扩窗至 1280×800 后标题选区必须在末尾", TextRange(4, 4), selectionOf(subjectMatcher))
        assertEquals("扩窗至 1280×800 移动不得调用 show()", 0, keyboardSpy.showCalls.get())
    }

    /** 验收 1(b) & 5：411×891 回复正文输入「移动保活选区」设选区 [2,4] → 411→749→1280→749→411→1280→411，每步正文获焦、选区保持 [2,4] 且移动期间 show()==0。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun acceptance1b_replyBodyFocusAndSelectionPreservedAcrossSevenStepSequence() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        val keyboardSpy = RecordingKeyboardController()
        var winSize by mutableStateOf(DpSize(411.dp, 891.dp))

        composeRule.setContent {
            MultiPaneResizableWindowHost(winSize) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalSoftwareKeyboardController provides keyboardSpy,
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
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            editorVm.open(FullEditorRequest.Reply(FullEditorHandoff("1001", 2, "", 0, 0, threadAuthor = "linus_hi")))
        }
        composeRule.waitForIdle()

        val bodyMatcher = hasSetTextAction() and hasText("移动保活选区")
        composeRule.onNode(hasSetTextAction()).performTextInput("移动保活选区")
        composeRule.waitForIdle()
        composeRule.onNode(bodyMatcher).performSemanticsAction(SemanticsActions.SetSelection) { it(2, 4, false) }
        composeRule.waitForIdle()

        assertTrue("初始正文块应获焦", isNodeFocused(bodyMatcher))
        assertEquals("初始选区应为 [2, 4]", TextRange(2, 4), selectionOf(bodyMatcher))

        keyboardSpy.reset()

        val steps = listOf(
            "411->749(CenteredModal)" to DpSize(749.dp, 559.dp),
            "749->1280(RightPane)" to DpSize(1280.dp, 800.dp),
            "1280->749(CenteredModal)" to DpSize(749.dp, 559.dp),
            "749->411(FullScreen)" to DpSize(411.dp, 891.dp),
            "411->1280(RightPane)" to DpSize(1280.dp, 800.dp),
            "1280->411(FullScreen)" to DpSize(411.dp, 891.dp),
        )
        for ((stage, nextSize) in steps) {
            winSize = nextSize
            composeRule.waitForIdle()
            assertTrue("$stage: 正文块必须保持获焦", isNodeFocused(bodyMatcher))
            assertEquals("$stage: UI 选区必须保持 [2, 4]", TextRange(2, 4), selectionOf(bodyMatcher))
            val doc = editorVm.uiState.value.body
            assertEquals("$stage: VM 选区必须保持 [2, 4]", TextRange(2, 4), TextRange(doc.activeSelectionStart, doc.activeSelectionEnd))
            assertEquals("$stage: 正文文本不得丢失", "移动保活选区", doc.serialize())
            assertEquals("$stage: 跨形态移动不得调用 show()", 0, keyboardSpy.showCalls.get())
        }
    }

    /** 验收 1(c) & 5：多正文块（中间含内联图片）聚焦第二个文本块后跨形态移动 → 仍是第二个文本块获焦且选区不变。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun acceptance1c_multiTextBlocksWithInlineImage_secondBlockKeepsFocusAcrossMoves() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        val keyboardSpy = RecordingKeyboardController()
        var winSize by mutableStateOf(DpSize(411.dp, 891.dp))

        composeRule.setContent {
            MultiPaneResizableWindowHost(winSize) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalSoftwareKeyboardController provides keyboardSpy,
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
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val multiBlockRaw = "第一段正文\n[attachimg]1721001[/attachimg]\n第二段正文"
        composeRule.runOnIdle {
            editorVm.open(FullEditorRequest.Reply(FullEditorHandoff("1001", 2, multiBlockRaw, 0, 0, threadAuthor = "linus_hi")))
        }
        composeRule.waitForIdle()

        val firstBlockMatcher = hasSetTextAction() and hasText("第一段正文")
        val secondBlockMatcher = hasSetTextAction() and hasText("第二段正文")
        assertEquals("应存在两个正文文本块", 2, composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)

        // 先点第一块确认可切焦，再点第二块并设选区 [1, 3]
        composeRule.onNode(firstBlockMatcher).performClick()
        composeRule.waitForIdle()
        assertTrue("点击第一块后第一块应获焦", isNodeFocused(firstBlockMatcher))

        composeRule.onNode(secondBlockMatcher).performClick()
        composeRule.waitForIdle()
        composeRule.onNode(secondBlockMatcher).performSemanticsAction(SemanticsActions.SetSelection) { it(1, 3, false) }
        composeRule.waitForIdle()
        assertTrue("第二块应获焦", isNodeFocused(secondBlockMatcher))
        assertFalse("第一块不应获焦", isNodeFocused(firstBlockMatcher))
        assertEquals(TextRange(1, 3), selectionOf(secondBlockMatcher))

        keyboardSpy.reset()

        for ((stage, nextSize) in listOf(
            "411->749" to DpSize(749.dp, 559.dp),
            "749->1280" to DpSize(1280.dp, 800.dp),
            "1280->411" to DpSize(411.dp, 891.dp),
        )) {
            winSize = nextSize
            composeRule.waitForIdle()
            assertTrue("$stage: 第二个正文块必须保持获焦", isNodeFocused(secondBlockMatcher))
            assertFalse("$stage: 第一个正文块不得获焦", isNodeFocused(firstBlockMatcher))
            assertEquals("$stage: 第二个正文块选区必须保持 [1, 3]", TextRange(1, 3), selectionOf(secondBlockMatcher))
            assertEquals("$stage: 移动不得调用 show()", 0, keyboardSpy.showCalls.get())
        }

        // 切回第一个正文块再跨形态移动，确认同样精确恢复第一个正文块（非固定落末块）
        composeRule.onNode(firstBlockMatcher).performClick()
        composeRule.waitForIdle()
        assertTrue("切回第一块后第一块应获焦", isNodeFocused(firstBlockMatcher))
        keyboardSpy.reset()
        winSize = DpSize(1280.dp, 800.dp)
        composeRule.waitForIdle()
        assertTrue("411->1280: 第一个正文块必须保持获焦", isNodeFocused(firstBlockMatcher))
        assertFalse("411->1280: 第二个正文块不得获焦", isNodeFocused(secondBlockMatcher))
        assertEquals("切回第一块移动不得调用 show()", 0, keyboardSpy.showCalls.get())
    }

    /** 验收 2 & 5：编辑器打开后清除焦点 → 跨形态移动后标题与正文均保持未聚焦且不调 show()。 */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun acceptance2_clearFocusBeforeMove_staysUnfocusedAcrossPlacements() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        val keyboardSpy = RecordingKeyboardController()
        var winSize by mutableStateOf(DpSize(1280.dp, 800.dp))
        var focusManager: FocusManager? = null

        composeRule.setContent {
            MultiPaneResizableWindowHost(winSize) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalSoftwareKeyboardController provides keyboardSpy,
                ) {
                    focusManager = LocalFocusManager.current
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

        // 1. NewThread：清除焦点后跨 1280 -> 749 -> 411 -> 1280 均不抢焦
        composeRule.onNodeWithContentDescription("发新帖").performClick()
        composeRule.waitForIdle()
        assertTrue("发新帖初始标题应获焦", isNodeFocused(subjectMatcher))

        composeRule.runOnIdle { requireNotNull(focusManager).clearFocus(force = true) }
        composeRule.waitForIdle()
        assertFalse("清焦后标题应未聚焦", isNodeFocused(subjectMatcher))
        assertFalse("清焦后正文应未聚焦", isNodeFocused(newThreadBodyMatcher))

        keyboardSpy.reset()
        for ((stage, nextSize) in listOf(
            "NewThread 1280->749" to DpSize(749.dp, 559.dp),
            "NewThread 749->411" to DpSize(411.dp, 891.dp),
            "NewThread 411->1280" to DpSize(1280.dp, 800.dp),
        )) {
            winSize = nextSize
            composeRule.waitForIdle()
            assertFalse("$stage: 无焦点移动后标题不得抢焦", isNodeFocused(subjectMatcher))
            assertFalse("$stage: 无焦点移动后正文不得抢焦", isNodeFocused(newThreadBodyMatcher))
            assertEquals("$stage: 无焦点移动不得调用 show()", 0, keyboardSpy.showCalls.get())
        }

        // 2. Reply：清除焦点后跨 1280 -> 411 -> 749 均不抢焦
        composeRule.runOnIdle {
            editorVm.requestClose(emitSavedSnackbar = false)
            editorVm.open(FullEditorRequest.Reply(FullEditorHandoff("1001", 2, "无焦正文", 4, 4, threadAuthor = "linus_hi")))
        }
        composeRule.waitForIdle()
        val replyBodyMatcher = hasSetTextAction() and hasText("无焦正文")
        assertTrue("回复打开后正文初始应获焦", isNodeFocused(replyBodyMatcher))

        composeRule.runOnIdle { requireNotNull(focusManager).clearFocus(force = true) }
        composeRule.waitForIdle()
        assertFalse("清焦后回复正文应未聚焦", isNodeFocused(replyBodyMatcher))

        keyboardSpy.reset()
        for ((stage, nextSize) in listOf(
            "Reply 1280->411" to DpSize(411.dp, 891.dp),
            "Reply 411->749" to DpSize(749.dp, 559.dp),
        )) {
            winSize = nextSize
            composeRule.waitForIdle()
            assertFalse("$stage: 无焦点移动后回复正文不得抢焦", isNodeFocused(replyBodyMatcher))
            assertEquals("$stage: 无焦点移动不得调用 show()", 0, keyboardSpy.showCalls.get())
        }
    }

    /** 验收 3(a) & 5：1280×800 回复（RightPane）打开表情面板 → 411×891（FullScreen）：面板仍打开（切换钮为「键盘」）、正文与选区不变、移动期间 show()==0 且 hide() 不关面板。 */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun acceptance3a_rightPane1280ToFullScreen411_keepsEmojiPanelOpenAndSelection() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        val keyboardSpy = RecordingKeyboardController()
        var winSize by mutableStateOf(DpSize(1280.dp, 800.dp))

        composeRule.setContent {
            MultiPaneResizableWindowHost(winSize) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalSoftwareKeyboardController provides keyboardSpy,
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
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            editorVm.open(FullEditorRequest.Reply(FullEditorHandoff("1001", 2, "", 0, 0, threadAuthor = "linus_hi")))
        }
        composeRule.waitForIdle()

        val bodyMatcher = hasSetTextAction() and hasText("移动保活选区")
        composeRule.onNode(hasSetTextAction()).performTextInput("移动保活选区")
        composeRule.waitForIdle()
        composeRule.onNode(bodyMatcher).performSemanticsAction(SemanticsActions.SetSelection) { it(2, 4, false) }
        composeRule.waitForIdle()

        keyboardSpy.reset()
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        assertTrue("打开表情面板应调用 hide() 隐藏软键盘", keyboardSpy.hideCalls.get() >= 1)
        assertEquals("打开表情面板后切换按钮应为「键盘」", 1, composeRule.onAllNodesWithContentDescription("键盘").fetchSemanticsNodes().size)
        assertTrue("表情面板默认分类应可见", composeRule.onAllNodesWithContentDescription("默认表情").fetchSemanticsNodes().isNotEmpty())

        keyboardSpy.reset()
        winSize = DpSize(411.dp, 891.dp)
        composeRule.waitForIdle()

        assertEquals("1280->411 后表情面板切换按钮必须仍为「键盘」", 1, composeRule.onAllNodesWithContentDescription("键盘").fetchSemanticsNodes().size)
        assertTrue("1280->411 后表情面板默认分类必须仍可见", composeRule.onAllNodesWithContentDescription("默认表情").fetchSemanticsNodes().isNotEmpty())
        assertTrue("1280->411 后表情面板删除键必须仍可见", composeRule.onAllNodesWithContentDescription("删除").fetchSemanticsNodes().isNotEmpty())
        assertEquals("1280->411 后正文必须保持不变", "移动保活选区", editorVm.uiState.value.body.serialize())
        assertEquals("1280->411 后选区必须保持 [2, 4]", TextRange(2, 4), selectionOf(bodyMatcher))
        assertEquals("1280->411 表情面板打开下移动不得调用 show()", 0, keyboardSpy.showCalls.get())
        assertTrue("1280->411 恢复焦点后 hide() 压制键盘不得关闭表情面板", keyboardSpy.hideCalls.get() >= 1)
    }

    /** 验收 3(b) & 5：表情面板打开时走 411→749→1280→749→411→1280→411 的 7 步序列，每步面板保持打开、选区与焦点不变、show()==0。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun acceptance3b_emojiPanelStaysOpenAcrossSevenStepSequenceWithZeroKeyboardShow() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        val keyboardSpy = RecordingKeyboardController()
        var winSize by mutableStateOf(DpSize(411.dp, 891.dp))

        composeRule.setContent {
            MultiPaneResizableWindowHost(winSize) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalSoftwareKeyboardController provides keyboardSpy,
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
                            emojiPanelSlot = emojiSlot,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            editorVm.open(FullEditorRequest.Reply(FullEditorHandoff("1001", 2, "", 0, 0, threadAuthor = "linus_hi")))
        }
        composeRule.waitForIdle()

        val bodyMatcher = hasSetTextAction() and hasText("移动保活选区")
        composeRule.onNode(hasSetTextAction()).performTextInput("移动保活选区")
        composeRule.waitForIdle()
        composeRule.onNode(bodyMatcher).performSemanticsAction(SemanticsActions.SetSelection) { it(2, 4, false) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()

        fun assertStage(stage: String) {
            assertEquals("$stage: 插入图片按钮数应为 1", 1, composeRule.onAllNodesWithContentDescription("插入图片").fetchSemanticsNodes().size)
            assertEquals("$stage: 输入框数应为 1", 1, composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
            assertEquals("$stage: 切换按钮必须为「键盘」", 1, composeRule.onAllNodesWithContentDescription("键盘").fetchSemanticsNodes().size)
            assertTrue("$stage: 默认表情标签应可见", composeRule.onAllNodesWithContentDescription("默认表情").fetchSemanticsNodes().isNotEmpty())
            assertTrue("$stage: 表情删除键应可见", composeRule.onAllNodesWithContentDescription("删除").fetchSemanticsNodes().isNotEmpty())
            assertEquals("$stage: 正文应不变", "移动保活选区", editorVm.uiState.value.body.serialize())
            assertEquals("$stage: UI 选区应为 [2, 4]", TextRange(2, 4), selectionOf(bodyMatcher))
        }

        assertStage("FullScreen(411x891)")
        keyboardSpy.reset()

        for ((stage, nextSize) in listOf(
            "411->749" to DpSize(749.dp, 559.dp),
            "749->1280" to DpSize(1280.dp, 800.dp),
            "1280->749" to DpSize(749.dp, 559.dp),
            "749->411" to DpSize(411.dp, 891.dp),
            "411->1280" to DpSize(1280.dp, 800.dp),
            "1280->411" to DpSize(411.dp, 891.dp),
        )) {
            winSize = nextSize
            composeRule.waitForIdle()
            assertStage(stage)
            assertEquals("$stage: 移动期间不得调用 show()", 0, keyboardSpy.showCalls.get())
        }

        // 7 步结束后点击表情仍按 [2, 4] 替换插入
        composeRule.onNodeWithContentDescription("表情 :)").performClick()
        composeRule.waitForIdle()
        assertEquals("7 步后表情插入应精确替换 [2, 4]", "移动:)选区", editorVm.uiState.value.body.serialize())
    }

    /** 验收 4：关闭 autoAdvance，1280×800 → 411×891 与 411×891 → 1280×800 逐帧推进至稳定，每一帧编辑器输入框节点数严格 == 1（从不为 0、从不为 2）。 */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun acceptance4_frameByFrameEditorMountedExactlyOnce_1280To411AndBack() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val owner = ownerWithComposeVms(harness, postRepo, editorVm)
        var winSize by mutableStateOf(DpSize(1280.dp, 800.dp))

        composeRule.setContent {
            MultiPaneResizableWindowHost(winSize) {
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

        composeRule.runOnIdle {
            editorVm.open(FullEditorRequest.Reply(FullEditorHandoff("1001", 2, "逐帧单挂载校验", 7, 7, threadAuthor = "linus_hi")))
        }
        composeRule.waitForIdle()
        assertEquals("初始 1280×800 编辑器输入框数应为 1", 1, composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)

        composeRule.mainClock.autoAdvance = false
        try {
            // 1. 1280×800 -> 411×891 逐帧校验
            winSize = DpSize(411.dp, 891.dp)
            for (frame in 1..20) {
                composeRule.mainClock.advanceTimeByFrame()
                val textFieldCount = composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
                val insertBtnCount = composeRule.onAllNodesWithContentDescription("插入图片").fetchSemanticsNodes().size
                assertEquals("1280->411 第 $frame 帧输入框数必须 == 1（不得为 0 或 2）", 1, textFieldCount)
                assertEquals("1280->411 第 $frame 帧插入图片按钮数必须 == 1", 1, insertBtnCount)
            }
            val fullScreenLeftDp = with(composeRule.density) {
                composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.left.toDp().value
            }
            assertTrue("1280->411 稳定后应落在单栏全屏区（左缘 ${fullScreenLeftDp}dp < 100dp）", fullScreenLeftDp < 100f)

            // 2. 411×891 -> 1280×800 逐帧校验
            winSize = DpSize(1280.dp, 800.dp)
            for (frame in 1..20) {
                composeRule.mainClock.advanceTimeByFrame()
                val textFieldCount = composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
                val insertBtnCount = composeRule.onAllNodesWithContentDescription("插入图片").fetchSemanticsNodes().size
                assertEquals("411->1280 第 $frame 帧输入框数必须 == 1（不得为 0 或 2）", 1, textFieldCount)
                assertEquals("411->1280 第 $frame 帧插入图片按钮数必须 == 1", 1, insertBtnCount)
            }
            val rightPaneNode = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode()
            assertNotNull(rightPaneNode)
            val rightPaneLeftDp = with(composeRule.density) { rightPaneNode.boundsInRoot.left.toDp().value }
            assertTrue("411->1280 稳定后应落在右栏区（左缘 ${rightPaneLeftDp}dp >= 500dp）", rightPaneLeftDp >= 500f)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }
}
