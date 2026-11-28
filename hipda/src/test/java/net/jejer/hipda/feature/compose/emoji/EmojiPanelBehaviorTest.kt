package net.jejer.hipda.feature.compose.emoji

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.message.SmsSendOutcome
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.post.PrePostResult
import net.jejer.hipda.emoji.EmojiMap
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.compose.*
import net.jejer.hipda.feature.messages.*
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.message.SmsMessageItem
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassBlurActive
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * E7 全链路 UI / 逐帧等高互换 / 回退限高 / 61 表情映射 / 光标无损插入 / BackHandler 优先级 / M7-0 #11 短消息表情测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class EmojiPanelBehaviorTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val renderer = EmojiPanelRendererImpl()
    private val emojiSlot: @Composable (QuickReplyEmojiPanelHost) -> Unit = { host -> renderer.Content(host, Modifier) }

    /** 验收 1a：QuickReplySheet 在 IME=260dp / 310dp（含 24dp 导航栏）下逐帧 IME ↔ 表情互换、点输入框切回与中途反转，全程 |Δbottom| == 0dp。 */
    @Test
    fun quickReply_frameByFrameImeEmojiSwap_andInputTapHandoff_zeroBottomShift() {
        val qrVm = QuickReplyViewModel(FakePostRepository(), FakeQuickReplyAuthRepository(), clockMs = { 10_000L })
        qrVm.open(QuickReplyTarget("2701001", 2, threadAuthor = "linus_hi"))
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    val gs = rememberHiGlassState()
                    QuickReplySheet(
                        viewModel = qrVm,
                        glassState = gs,
                        onDismissFinished = {},
                        onOpenLogin = {},
                        onExpandToFullEditor = {},
                        emojiPanelSlot = emojiSlot,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density
        fun dispatchInsets(imeDp: Int, navDp: Int = 24) {
            composeRule.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, with(density) { imeDp.dp.roundToPx() }))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, with(density) { navDp.dp.roundToPx() }))
                    .build()
                ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insets)
            }
            composeRule.waitForIdle()
        }
        fun sheetBottomDp(): Float {
            val sheet = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true).fetchSemanticsNodes().last()
            return with(density) { (sheet.positionInRoot.y + sheet.size.height).toDp().value }
        }

        // 1. IME = 260dp（含 24dp 导航栏，净键盘高 236dp）
        dispatchInsets(imeDp = 260, navDp = 24)
        val baseline260 = sheetBottomDp()
        assertEquals(891f - 260f, baseline260, 1.5f)
        composeRule.onNodeWithContentDescription("表情").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("键盘").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("默认表情").assertIsDisplayed()

        // IME 逐帧退场：260 -> 200 -> 140 -> 80 -> 20 -> 0，全程底缘零位移
        for (frameIme in listOf(200, 140, 80, 20, 0)) {
            dispatchInsets(imeDp = frameIme, navDp = 24)
            assertEquals("QuickReply IME→表情退场帧 ime=${frameIme}dp 底缘不得偏移", baseline260, sheetBottomDp(), 0.5f)
            composeRule.onNodeWithContentDescription("默认表情").assertIsDisplayed()
        }

        // 2. 点「键盘」→ IME 逐帧升起：0 -> 60 -> 130 -> 200 -> 260，全程底缘零位移
        composeRule.onNodeWithContentDescription("键盘").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情").assertIsDisplayed()
        assertEquals("点键盘首帧(ime=0)底缘不得塌陷", baseline260, sheetBottomDp(), 0.5f)
        for (frameIme in listOf(60, 130, 200, 260)) {
            dispatchInsets(imeDp = frameIme, navDp = 24)
            assertEquals("QuickReply 表情→IME升起帧 ime=${frameIme}dp 底缘不得偏移", baseline260, sheetBottomDp(), 0.5f)
        }

        // 3. IME 升至 310dp 后切入表情再点输入框切回键盘，全程 |Δbottom| == 0dp
        dispatchInsets(imeDp = 310, navDp = 24)
        val baseline310 = sheetBottomDp()
        assertEquals(891f - 310f, baseline310, 1.5f)
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        for (frameIme in listOf(220, 110, 0)) {
            dispatchInsets(imeDp = frameIme, navDp = 24)
            assertEquals("QuickReply 310dp 退场帧 ime=${frameIme}dp 底缘不得偏移", baseline310, sheetBottomDp(), 0.5f)
        }
        // 点击输入框触发切回键盘
        composeRule.onNodeWithContentDescription("回复 linus_hi").performClick()
        composeRule.waitForIdle()
        assertEquals("点输入框首帧底缘不得塌陷", baseline310, sheetBottomDp(), 0.5f)
        for (frameIme in listOf(90, 200, 310)) {
            dispatchInsets(imeDp = frameIme, navDp = 24)
            assertEquals("QuickReply 点输入框→IME帧 ime=${frameIme}dp 底缘不得偏移", baseline310, sheetBottomDp(), 0.5f)
        }

        // 4. 退场中途（ime=150dp）立即反转回键盘，全程零位移
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        dispatchInsets(imeDp = 150, navDp = 24)
        assertEquals(baseline310, sheetBottomDp(), 0.5f)
        composeRule.onNodeWithContentDescription("键盘").performClick()
        composeRule.waitForIdle()
        assertEquals(baseline310, sheetBottomDp(), 0.5f)
        for (frameIme in listOf(230, 310)) {
            dispatchInsets(imeDp = frameIme, navDp = 24)
            assertEquals("中途反转帧 ime=${frameIme}dp 底缘不得偏移", baseline310, sheetBottomDp(), 0.5f)
        }

        // 5. S1：退场中途（310 -> 180）若系统重新拉起键盘（180 -> 240 -> 310），自动切回键盘态且底缘零位移
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        dispatchInsets(imeDp = 180, navDp = 24)
        assertEquals(baseline310, sheetBottomDp(), 0.5f)
        dispatchInsets(imeDp = 240, navDp = 24)
        assertEquals(baseline310, sheetBottomDp(), 0.5f)
        composeRule.onNodeWithContentDescription("表情").assertIsDisplayed()
        dispatchInsets(imeDp = 310, navDp = 24)
        assertEquals(baseline310, sheetBottomDp(), 0.5f)
    }

    /** 验收 1b：FullEditorScreen 单栏全屏（411×891 + 24dp 导航栏）逐帧 IME ↔ 表情互换，底缘变化 ≤ 1dp 且正文不塌陷。 */
    @Test
    fun fullEditor_frameByFrameImeEmojiSwap_zeroToolbarBottomShift() {
        val vm = FullEditorViewModel(FakePostRepository(), FakeImageUploadRepository(), FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository()) { 10_000L }
        vm.open(FullEditorRequest.fromHandoff(FullEditorHandoff("2701002", 2, "测试正文", 2, 2, threadAuthor = "linus_hi")))
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                FullEditorPlacementFrame(FullEditorPlacement.FullScreen, 411f, isSubmitting = false, onRequestClose = {}) { isModal ->
                    FullEditorScreen(viewModel = vm, onOpenLogin = {}, isModalCard = isModal, emojiPanelSlot = emojiSlot)
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density
        fun dispatchIme(imeDp: Int, navDp: Int = 24) {
            composeRule.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, with(density) { navDp.dp.roundToPx() }))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, with(density) { imeDp.dp.roundToPx() }))
                    .build()
                ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insets)
            }
            composeRule.waitForIdle()
        }
        fun toolbarButtonBottomDp(): Float {
            val node = composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode()
            return with(density) { (node.positionInRoot.y + node.size.height).toDp().value }
        }
        fun assertBodyPreserved(label: String) {
            val toolbar = composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode()
            val back = composeRule.onNodeWithContentDescription("返回").fetchSemanticsNode()
            val bodyDp = with(density) { (toolbar.positionInRoot.y - (back.positionInRoot.y + back.size.height)).toDp().value }
            assertTrue("$label 单栏全屏正文高度塌陷(${bodyDp}dp)", bodyDp >= 300f)
        }

        dispatchIme(280)
        val baselineBottom = toolbarButtonBottomDp()
        assertBodyPreserved("FullEditor-FullScreen 基线")
        composeRule.onNodeWithContentDescription("表情").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("键盘").assertIsDisplayed()
        assertEquals(baselineBottom, toolbarButtonBottomDp(), 1.0f)
        assertBodyPreserved("FullEditor-FullScreen 首帧")

        for (ime in listOf(210, 140, 60, 0)) {
            dispatchIme(ime)
            assertEquals("FullEditor IME→表情帧 ime=${ime}dp 工具条底缘偏移", baselineBottom, toolbarButtonBottomDp(), 1.0f)
            assertBodyPreserved("FullEditor-FullScreen ime=$ime")
        }

        composeRule.onNodeWithContentDescription("键盘").performClick()
        composeRule.waitForIdle()
        assertEquals("FullEditor 表情→IME首帧工具条塌陷", baselineBottom, toolbarButtonBottomDp(), 1.0f)
        assertBodyPreserved("FullEditor-FullScreen 切回首帧")
        for (ime in listOf(80, 180, 280)) {
            dispatchIme(ime)
            assertEquals("FullEditor 表情→IME帧 ime=${ime}dp 工具条底缘偏移", baselineBottom, toolbarButtonBottomDp(), 1.0f)
            assertBodyPreserved("FullEditor-FullScreen 升起 ime=$ime")
        }
        assertEquals("完整编辑器与表情面板均为纯色表面，0 个 HiGlassMarker", 0, composeRule.onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    /** 负责人裁定 #2（M7-0 #11）：短消息输入栏表情按钮显隐、等高 0dp 互换、光标/选区插入、回删、发送与 BackHandler 先关表情面板。 */
    @Test
    fun smsConversation_emojiSlot_zeroShiftSwap_cursorSelectionInsert_send_andBackHandlerPriority() {
        val clock = AtomicLong(1_700_000_000_000L)
        val sentMessages = mutableListOf<String>()
        val smsRepo = FakeSmsRepository(
            clock = { clock.get() },
            initialMessages = listOf(SmsMessageItem("20001", "Alice", null, "10:00", "早安 :)", false, false)),
            onSend = { _, _, msg -> sentMessages += msg; SmsSendOutcome.Sent },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vm = SmsConversationViewModel(smsRepo, FakeAuthRepository(signedIn = true), SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, clock = System::currentTimeMillis, gatewayScope = scope)) { clock.get() }
        var bindEmoji by mutableStateOf(false)
        var backCalls = 0
        var backDispatcher: OnBackPressedDispatcher? = null
        var hostView: View? = null

        try {
            composeRule.setContent {
                hostView = LocalView.current
                backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    SmsConversationScreen(
                        args = SmsConversationArgs("20001", "Alice"),
                        showBack = true,
                        onBack = { backCalls++ },
                        onOpenUserProfile = { _, _ -> },
                        onOpenLogin = {},
                        contentPadding = PaddingValues(0.dp),
                        viewModel = vm,
                        emojiPanelSlot = if (bindEmoji) emojiSlot else null,
                    )
                }
            }
            composeRule.waitForIdle()
            assertEquals("未绑定表情插槽时短消息输入栏隐藏表情按钮", 0, composeRule.onAllNodesWithContentDescription("表情").fetchSemanticsNodes().size)

            bindEmoji = true
            composeRule.waitForIdle()
            val density = composeRule.density
            fun dispatchIme(imeDp: Int) {
                composeRule.runOnUiThread {
                    val insets = WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, with(density) { imeDp.dp.roundToPx() }))
                        .build()
                    ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insets)
                }
                composeRule.waitForIdle()
            }
            fun sendButtonBottomDp(): Float {
                val n = composeRule.onNodeWithContentDescription("发送").fetchSemanticsNode()
                return with(density) { (n.positionInRoot.y + n.size.height).toDp().value }
            }

            // 1. IME=260dp → 点表情 → 逐帧零位移
            dispatchIme(260)
            val baselineBottom = sendButtonBottomDp()
            composeRule.onNodeWithContentDescription("表情").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("键盘").assertIsDisplayed()
            for (ime in listOf(180, 90, 0)) {
                dispatchIme(ime)
                assertEquals("SMS IME→表情帧 ime=${ime}dp 底缘偏移", baselineBottom, sendButtonBottomDp(), 0.5f)
            }

            // 2. 输入文本、中间光标插入与选区替换 + 三分类切换 + 回删
            val input = composeRule.onNode(hasSetTextAction())
            input.performTextInput("头中尾")
            composeRule.waitForIdle()
            input.performSemanticsAction(SemanticsActions.SetSelection) { it(1, 2, false) }
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情 :)").performClick()
            composeRule.waitForIdle()
            assertEquals("选区替换应精确插入 :) 并移动光标至末尾", "头:)尾", vm.uiState.value.inputText)

            composeRule.onNodeWithContentDescription("酷猴表情").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情 {:2_41:}").performClick()
            composeRule.waitForIdle()
            assertEquals("连续插入酷猴表情代码", "头:){:2_41:}尾", vm.uiState.value.inputText)

            composeRule.onNodeWithContentDescription("删除").performClick()
            composeRule.waitForIdle()
            assertEquals("删除键应整枚回删 {:2_41:} 而非残删", "头:)尾", vm.uiState.value.inputText)

            // 3. 表情 → 键盘逐帧零位移
            composeRule.onNodeWithContentDescription("键盘").performClick()
            composeRule.waitForIdle()
            assertEquals(baselineBottom, sendButtonBottomDp(), 0.5f)
            for (ime in listOf(100, 200, 260)) {
                dispatchIme(ime)
                assertEquals("SMS 表情→IME帧 ime=${ime}dp 底缘偏移", baselineBottom, sendButtonBottomDp(), 0.5f)
            }

            // 4. 打开表情面板按系统返回：仅关闭表情面板，不误退对话页
            composeRule.onNodeWithContentDescription("表情").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("酷猴表情").assertIsDisplayed()
            composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
            composeRule.waitForIdle()
            assertEquals("按返回应先关表情面板", 0, composeRule.onAllNodesWithContentDescription("酷猴表情").fetchSemanticsNodes().size)
            assertEquals("关闭表情面板不得误触发退出短消息对话", 0, backCalls)

            // 5. 发送含论坛表情代码的短消息
            composeRule.onNodeWithContentDescription("发送").performClick()
            composeRule.waitForIdle()
            assertEquals(listOf("头:)尾"), sentMessages)
        } finally {
            scope.cancel()
        }
    }

    /** 验收 2：无键盘高度回退限高 UI 验证（891 / 600 / 480 / 479 / 411dp）与外接键盘超时恢复。 */
    @Test
    fun fallbackPanelHeight_matchesSection74InUi_andExternalKeyboardHandoffRecovers() {
        var boxHeight by mutableStateOf(891.dp)
        val qrVm = QuickReplyViewModel(FakePostRepository(), FakeQuickReplyAuthRepository(), clockMs = { 10_000L })
        qrVm.open(QuickReplyTarget("2701003", 2, threadAuthor = "linus_hi"))

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    val gs = rememberHiGlassState()
                    Box(Modifier.requiredSize(411.dp, boxHeight)) {
                        QuickReplySheet(qrVm, gs, onDismissFinished = {}, onOpenLogin = {}, emojiPanelSlot = emojiSlot)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()

        val density = composeRule.density
        val cases = listOf(891.dp to 280f, 600.dp to 270f, 480.dp to 216f, 479.dp to 167.65f, 411.dp to 143.85f)
        for ((windowH, expectedPanelDp) in cases) {
            boxHeight = windowH
            composeRule.waitForIdle()
            val panelNode = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).fetchSemanticsNode()
            val actualDp = with(density) { panelNode.size.height.toDp().value }
            assertTrue("窗口高 $windowH 下面板实测 ${actualDp}dp 应吻合 §7.4 ${expectedPanelDp}dp", abs(actualDp - expectedPanelDp) <= 1.5f)
        }

        // 外接物理键盘场景：点「键盘」但系统从未下发 IME insets -> 超时自动恢复至 Closed，无永久空占位
        composeRule.onNodeWithContentDescription("键盘").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).fetchSemanticsNodes().size)
    }

    /** 验收 3：3 组 61 表情渲染、自适应列数与 ≥ 48×48dp 触控区、C1b#3 无障碍文案、深浅切换保态、减动效 1 帧到位。 */
    @Test
    fun pickerPanel_all61Emojis_adaptive48dpTargets_c1b3Labels_themeSwitchPreservesState_andReducedMotion() {
        val inserted = mutableListOf<String>()
        var dark by mutableStateOf(false)
        var panelWidth by mutableStateOf(411.dp)

        composeRule.setContent {
            HiTheme(themeMode = if (dark) HiThemeMode.DARK else HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                Box(Modifier.requiredSize(panelWidth, 280.dp)) {
                    EmojiPickerPanel(
                        host = QuickReplyEmojiPanelHost(280.dp, 891.dp, onInsertEmoji = { inserted += it }, onBackspace = {}, onClose = {}),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // C1b#3：标签描述来自 strings_emoji.xml，不使用硬编码 EmojiGroup.name（"默认"/"酷猴"/"呆呆"）
        val expectedTabLabels = listOf("默认表情", "酷猴表情", "呆呆表情")
        expectedTabLabels.forEach { label ->
            composeRule.onNodeWithContentDescription(label).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        listOf("默认", "酷猴", "呆呆").forEach { rawName ->
            assertEquals("不得使用硬编码 EmojiGroup.name=$rawName", 0, composeRule.onAllNodesWithContentDescription(rawName).fetchSemanticsNodes().size)
        }
        composeRule.onNodeWithContentDescription("删除").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

        // 逐组验证首尾表情可渲染且触控目标 ≥ 48×48dp，点击准确产出规范论坛代码
        EmojiMap.groups.forEachIndexed { idx, group ->
            composeRule.onNodeWithContentDescription(expectedTabLabels[idx]).performClick()
            composeRule.waitForIdle()
            val firstCode = group.codes.first()
            composeRule.onNodeWithContentDescription("表情 $firstCode")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            composeRule.waitForIdle()
        }
        assertEquals(listOf(":)", "{:2_41:}", "{:3_57:}"), inserted)

        // 停留在呆呆分类时切换深色模式：分类选中态保持不变，且不同宽度下每格恒 ≥ 48dp
        dark = true
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("呆呆表情").assertIsSelected()
        for (w in listOf(340.dp, 411.dp, 680.dp)) {
            panelWidth = w
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("表情 {:3_57:}").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }

        // 减动效 0ms 1 帧完成分类切换
        withAnimatorDurationScale(0f) {
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.requiredSize(411.dp, 280.dp)) {
                        EmojiPickerPanel(QuickReplyEmojiPanelHost(280.dp, 891.dp, {}, {}, {}))
                    }
                }
            }
            waitForIdle()
            mainClock.autoAdvance = false
            onNodeWithContentDescription("呆呆表情").performClick()
            mainClock.advanceTimeByFrame()
            onNodeWithContentDescription("呆呆表情").assertIsSelected()
            onNodeWithContentDescription("表情 {:3_57:}").assertIsDisplayed()
        }
    }

    /** 验收 4：QuickReplySheet 与 FullEditorScreen 光标中间插入/选区替换，及真实 NextAppRoot BackHandler LIFO 优先级（表情 > 面板/编辑器 > PositionStack）。 */
    @Test
    fun realNextAppRoot_cursorSelectionInsertion_andBackHandlerPriorityAboveQuickReplyFullEditorAndPositionStack() {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val qrVm = QuickReplyViewModel(postRepo, harness.authRepo, clockMs = { 10_000L })
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) -> qrVm as T
                modelClass.isAssignableFrom(FullEditorViewModel::class.java) -> editorVm as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        val owner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore = harness.parentOwner.viewModelStore
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory = factory
        }
        var backDispatcher: OnBackPressedDispatcher? = null

        composeRule.setContent {
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
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
        composeRule.waitForIdle()

        // 1. 打开帖子并跳末页建立非空 PositionStack（栈底第 1 页）
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("第 1 楼，点按展开楼层跳转").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("最新回复").performClick()
        composeRule.waitForIdle()
        val detailVm = harness.createdDetailVms.single()
        assertEquals(3, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        // 2. QuickReplySheet 光标中间插入 & 选区替换，随后 Back 1 先关表情面板，Back 2 再关快速回复，均不误出 PositionStack
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        val qrInput = composeRule.onNodeWithContentDescription("回复 楼层作者1")
        qrInput.performTextInput("你好世界")
        composeRule.waitForIdle()
        qrInput.performSemanticsAction(SemanticsActions.SetSelection) { it(2, 2, false) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情 :)").performClick()
        composeRule.waitForIdle()
        assertEquals("你好:)世界", qrVm.uiState.value.text)

        qrInput.performSemanticsAction(SemanticsActions.SetSelection) { it(2, 4, false) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("酷猴表情").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情 {:2_41:}").performClick()
        composeRule.waitForIdle()
        assertEquals("选区替换应精确落入 {:2_41:}", "你好{:2_41:}世界", qrVm.uiState.value.text)

        // 返回 1：仅关表情面板，快速回复面板与草稿仍在，PositionStack 未动
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("酷猴表情").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("收起回复面板").assertIsDisplayed()
        assertEquals("你好{:2_41:}世界", qrVm.uiState.value.text)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        // 展开到完整编辑器并打开表情面板 -> 中间光标与选区替换 -> Back 1 仅关表情面板 -> Back 2 关编辑器存草稿 -> Back 3 出 PositionStack
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()
        assertTrue(editorVm.uiState.value.isOpen)
        composeRule.runOnIdle {
            editorVm.onTextBlockSelectionChanged(editorVm.uiState.value.body.firstTextBlockId, 2, 10)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("呆呆表情").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情 {:3_57:}").performClick()
        composeRule.waitForIdle()
        assertEquals("完整编辑器选区替换应落入 {:3_57:}", "你好{:3_57:}世界", editorVm.uiState.value.body.serialize())

        // 返回 1：仅关完整编辑器的表情面板
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("呆呆表情").fetchSemanticsNodes().size)
        assertTrue("按返回 1 仅关表情面板，不得误关完整编辑器", editorVm.uiState.value.isOpen)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        // 返回 2：关完整编辑器并保存草稿，不误出 PositionStack
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertFalse(editorVm.uiState.value.isOpen)
        assertEquals("你好{:3_57:}世界", postRepo.draftFor(DraftKey.reply("1001"))?.content)
        assertEquals(3, detailVm.uiState.value.page)
        assertTrue(detailVm.uiState.value.canPopPositionStack)

        // 重新打开快速回复时表情面板展开态已重置
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithContentDescription("默认表情").fetchSemanticsNodes().size)
        composeRule.onNodeWithContentDescription("收起回复面板").performClick()
        composeRule.waitForIdle()

        // 返回 3：PositionStack 回第 1 页
        composeRule.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        composeRule.waitForIdle()
        assertEquals(1, detailVm.uiState.value.page)
        assertFalse(detailVm.uiState.value.canPopPositionStack)
    }

    /** B1 回归（749×559 居中模态）：逐帧 IME ↔ 表情底缘变化 ≤ 1dp、正文不塌陷，且切至同窗 QuickReplySheet 无 +24dp 缓存污染。 */
    @Test
    @Config(qualifiers = "w749dp-h559dp-xhdpi")
    fun fullEditor_centeredModal_749x559_zeroShift_bodyPreserved_andNoCachePollutionToQuickReply() {
        val editorVm = FullEditorViewModel(FakePostRepository(), FakeImageUploadRepository(), FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository()) { 10_000L }
        editorVm.open(FullEditorRequest.fromHandoff(FullEditorHandoff("2703001", 2, "居中模态测试正文", 4, 4, threadAuthor = "linus_hi")))
        val qrVm = QuickReplyViewModel(FakePostRepository(), FakeQuickReplyAuthRepository(), clockMs = { 10_000L })
        qrVm.open(QuickReplyTarget("2703001", 2, threadAuthor = "linus_hi"))
        var showQuickReply by mutableStateOf(false)
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    if (!showQuickReply) {
                        FullEditorPlacementFrame(FullEditorPlacement.CenteredModal, 749f, isSubmitting = false, onRequestClose = {}) { isModal ->
                            FullEditorScreen(viewModel = editorVm, onOpenLogin = {}, isModalCard = isModal, emojiPanelSlot = emojiSlot)
                        }
                    } else {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.align(Alignment.CenterEnd).width(411.dp).fillMaxHeight()) {
                                QuickReplySheet(qrVm, rememberHiGlassState(), onDismissFinished = {}, onOpenLogin = {}, emojiPanelSlot = emojiSlot)
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density
        fun dispatchInsets(imeDp: Int, navDp: Int = 24, statusDp: Int = 24) {
            composeRule.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, with(density) { statusDp.dp.roundToPx() }, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, with(density) { navDp.dp.roundToPx() }))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, with(density) { imeDp.dp.roundToPx() }))
                    .build()
                ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insets)
            }
            composeRule.waitForIdle()
        }
        fun assertModalEditorFrame(label: String, expectedBottomDp: Float) {
            val toolbarNode = composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode()
            val topBarBack = composeRule.onNodeWithContentDescription("关闭").fetchSemanticsNode()
            val toolbarBottomDp = with(density) { (toolbarNode.positionInRoot.y + toolbarNode.size.height).toDp().value }
            val bodySpaceDp = with(density) { (toolbarNode.positionInRoot.y - (topBarBack.positionInRoot.y + topBarBack.size.height)).toDp().value }
            assertEquals("$label 工具栏底缘每帧变化不得超过 1dp", expectedBottomDp, toolbarBottomDp, 1.0f)
            assertTrue("$label 正文区高度不得塌陷，实测 ${bodySpaceDp}dp", bodySpaceDp >= 60f)
        }

        dispatchInsets(imeDp = 260, navDp = 24, statusDp = 24)
        val baseline = with(density) {
            val n = composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode()
            (n.positionInRoot.y + n.size.height).toDp().value
        }
        assertModalEditorFrame("CenteredModal-749 IME=260 基线", baseline)

        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        assertModalEditorFrame("CenteredModal-749 IME→表情首帧(ime=260)", baseline)
        for (ime in listOf(200, 140, 80, 20, 0)) {
            dispatchInsets(imeDp = ime, navDp = 24, statusDp = 24)
            assertModalEditorFrame("CenteredModal-749 IME→表情退场帧 ime=$ime", baseline)
        }

        composeRule.onNodeWithContentDescription("键盘").performClick()
        composeRule.waitForIdle()
        assertModalEditorFrame("CenteredModal-749 表情→键盘首帧(ime=0)", baseline)
        for (ime in listOf(60, 130, 200, 260)) {
            dispatchInsets(imeDp = ime, navDp = 24, statusDp = 24)
            assertModalEditorFrame("CenteredModal-749 表情→键盘升起帧 ime=$ime", baseline)
        }

        // 关闭居中模态后切回同窗口 749×559dp 的 QuickReplySheet：验证净键盘高缓存未受 +24dp 导航栏污染
        dispatchInsets(imeDp = 0, navDp = 24, statusDp = 24)
        showQuickReply = true
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        val sheetNode = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true).fetchSemanticsNodes().last()
        val qrBottomWithRememberedEmoji = with(density) { (sheetNode.positionInRoot.y + sheetNode.size.height).toDp().value }
        assertEquals(
            "同窗 QuickReplySheet 应命中 236dp 净键盘高 + 24dp 导航栏 = 260dp（而非受污染的 284dp）",
            559f - 260f,
            qrBottomWithRememberedEmoji,
            1.0f,
        )
        // 切回键盘至 260dp 时同样底缘零漂移
        composeRule.onNodeWithContentDescription("键盘").performClick()
        composeRule.waitForIdle()
        for (ime in listOf(80, 180, 260)) {
            dispatchInsets(imeDp = ime, navDp = 24, statusDp = 24)
            val s = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true).fetchSemanticsNodes().last()
            val curBottom = with(density) { (s.positionInRoot.y + s.size.height).toDp().value }
            assertEquals("QuickReply 切回键盘 ime=$ime 底缘无漂移", qrBottomWithRememberedEmoji, curBottom, 1.0f)
        }
    }

    /** B1 回归（933×696 居中模态）：逐帧 IME ↔ 表情底缘变化 ≤ 1dp、正文不塌陷。 */
    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun fullEditor_centeredModal_933x696_zeroShift_andBodyPreserved() =
        verifyFullEditorPlacementZeroShift(933f, FullEditorPlacement.CenteredModal, minBodySpaceDp = 120f)

    /** B1 回归（640×1024 居中模态）：逐帧 IME ↔ 表情底缘变化 ≤ 1dp、正文不塌陷。 */
    @Test
    @Config(qualifiers = "w640dp-h1024dp-xhdpi")
    fun fullEditor_centeredModal_640x1024_zeroShift_andBodyPreserved() =
        verifyFullEditorPlacementZeroShift(640f, FullEditorPlacement.CenteredModal, minBodySpaceDp = 200f)

    /** B1 回归（1280×800 右栏三宿主）：QuickReplySheet / FullEditor(RightPane) / SmsConversationScreen 逐帧 ≤ 1dp 且正文不塌陷。 */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun threeHosts_rightPane_1280x800_zeroShift_andBodyPreserved() {
        val qrVm = QuickReplyViewModel(FakePostRepository(), FakeQuickReplyAuthRepository(), clockMs = { 10_000L })
        qrVm.open(QuickReplyTarget("2703010", 2, threadAuthor = "linus_hi"))
        val editorVm = FullEditorViewModel(FakePostRepository(), FakeImageUploadRepository(), FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository()) { 10_000L }
        editorVm.open(FullEditorRequest.fromHandoff(FullEditorHandoff("2703010", 2, "右栏编辑正文", 3, 3, threadAuthor = "linus_hi")))
        val clock = AtomicLong(1_700_000_000_000L)
        val smsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val smsRepo = FakeSmsRepository(
            clock = { clock.get() },
            initialMessages = listOf(SmsMessageItem("20001", "Alice", null, "10:00", "早安 :)", false, false)),
        )
        val smsVm = SmsConversationViewModel(smsRepo, FakeAuthRepository(signedIn = true), SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, clock = System::currentTimeMillis, gatewayScope = smsScope)) { clock.get() }
        var activeHost by mutableIntStateOf(0)
        var hostView: View? = null

        try {
            composeRule.setContent {
                hostView = LocalView.current
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.align(Alignment.CenterEnd).width(680.dp).fillMaxHeight()) {
                                when (activeHost) {
                                    0 -> QuickReplySheet(qrVm, rememberHiGlassState(), onDismissFinished = {}, onOpenLogin = {}, emojiPanelSlot = emojiSlot)
                                    1 -> FullEditorPlacementFrame(FullEditorPlacement.RightPane, 1280f, isSubmitting = false, onRequestClose = {}) { isModal ->
                                        FullEditorScreen(editorVm, onOpenLogin = {}, isModalCard = isModal, emojiPanelSlot = emojiSlot)
                                    }
                                    else -> SmsConversationScreen(SmsConversationArgs("20001", "Alice"), false, {}, { _, _ -> }, {}, PaddingValues(0.dp), smsVm, emojiPanelSlot = emojiSlot)
                                }
                            }
                        }
                    }
                }
            }
            composeRule.waitForIdle()
            val density = composeRule.density
            fun dispatchInsets(imeDp: Int, navDp: Int = 24, statusDp: Int = 24) {
                composeRule.runOnUiThread {
                    val insets = WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, with(density) { statusDp.dp.roundToPx() }, 0, 0))
                        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, with(density) { navDp.dp.roundToPx() }))
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, with(density) { imeDp.dp.roundToPx() }))
                        .build()
                    ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insets)
                }
                composeRule.waitForIdle()
            }
            fun runSwapChecks(tag: String, measureBottom: () -> Float, measureBodyHeight: () -> Float, minBody: Float) {
                dispatchInsets(260)
                val base = measureBottom()
                assertTrue("$tag 基线正文塌陷", measureBodyHeight() >= minBody)
                composeRule.onNodeWithContentDescription("表情").performClick()
                composeRule.waitForIdle()
                assertEquals("$tag IME→表情首帧偏移", base, measureBottom(), 1.0f)
                assertTrue("$tag IME→表情首帧正文塌陷", measureBodyHeight() >= minBody)
                for (ime in listOf(180, 90, 0)) {
                    dispatchInsets(ime)
                    assertEquals("$tag IME→表情 ime=$ime 偏移", base, measureBottom(), 1.0f)
                    assertTrue("$tag IME→表情 ime=$ime 正文塌陷", measureBodyHeight() >= minBody)
                }
                composeRule.onNodeWithContentDescription("键盘").performClick()
                composeRule.waitForIdle()
                assertEquals("$tag 表情→键盘首帧塌陷", base, measureBottom(), 1.0f)
                assertTrue("$tag 表情→键盘首帧正文塌陷", measureBodyHeight() >= minBody)
                for (ime in listOf(80, 180, 260)) {
                    dispatchInsets(ime)
                    assertEquals("$tag 表情→键盘 ime=$ime 偏移", base, measureBottom(), 1.0f)
                    assertTrue("$tag 表情→键盘 ime=$ime 正文塌陷", measureBodyHeight() >= minBody)
                }
            }

            // 1. QuickReplySheet in 1280dp right pane
            runSwapChecks(
                tag = "RightPane-1280-QuickReply",
                measureBottom = {
                    val s = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true).fetchSemanticsNodes().last()
                    with(density) { (s.positionInRoot.y + s.size.height).toDp().value }
                },
                measureBodyHeight = {
                    val sheet = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(HiGlassBlurActive), useUnmergedTree = true).fetchSemanticsNodes().last()
                    val input = composeRule.onNodeWithContentDescription("回复 linus_hi").fetchSemanticsNode()
                    val inputH = with(density) { input.size.height.toDp().value }
                    assertTrue("QuickReply 输入框单行高度不得塌陷(实测 ${inputH}dp)", inputH >= 12f)
                    val sendBtn = composeRule.onNodeWithContentDescription("发送").fetchSemanticsNode()
                    val collapseHandle = composeRule.onNodeWithContentDescription("收起回复面板").fetchSemanticsNode()
                    val inputRegionH = with(density) { (sendBtn.positionInRoot.y - (collapseHandle.positionInRoot.y + collapseHandle.size.height)).toDp().value }
                    assertTrue("QuickReply 输入区域高度不得塌陷(实测 ${inputRegionH}dp)", inputRegionH >= 44f)
                    with(density) { sheet.size.height.toDp().value }
                },
                minBody = 120f,
            )

            // 2. FullEditor(RightPane) in 1280dp right pane
            activeHost = 1
            composeRule.waitForIdle()
            runSwapChecks(
                tag = "RightPane-1280-FullEditor",
                measureBottom = {
                    val n = composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode()
                    with(density) { (n.positionInRoot.y + n.size.height).toDp().value }
                },
                measureBodyHeight = {
                    val toolbar = composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode()
                    val back = composeRule.onNodeWithContentDescription("返回").fetchSemanticsNode()
                    with(density) { (toolbar.positionInRoot.y - (back.positionInRoot.y + back.size.height)).toDp().value }
                },
                minBody = 200f,
            )

            // 3. SmsConversationScreen in 1280dp right pane
            activeHost = 2
            composeRule.waitForIdle()
            runSwapChecks(
                tag = "RightPane-1280-SMS",
                measureBottom = {
                    val n = composeRule.onNodeWithContentDescription("发送").fetchSemanticsNode()
                    with(density) { (n.positionInRoot.y + n.size.height).toDp().value }
                },
                measureBodyHeight = {
                    val send = composeRule.onNodeWithContentDescription("发送").fetchSemanticsNode()
                    val title = composeRule.onNodeWithText("Alice").fetchSemanticsNode()
                    with(density) { (send.positionInRoot.y - (title.positionInRoot.y + title.size.height)).toDp().value }
                },
                minBody = 200f,
            )
        } finally {
            smsScope.cancel()
        }
    }

    /** B2 回归：三宿主删除键整枚回删 {:2_41:}、:)、:loveliness:，并保留中文、Unicode 代理对与非空选区回删行为。 */
    @Test
    fun threeHosts_emojiBackspace_deletesWholeForumEmoji_chinese_surrogatePair_andSelection() {
        val qrVm = QuickReplyViewModel(FakePostRepository(), FakeQuickReplyAuthRepository(), clockMs = { 10_000L })
        qrVm.open(QuickReplyTarget("2703020", 2, threadAuthor = "linus_hi"))
        val editorVm = FullEditorViewModel(FakePostRepository(), FakeImageUploadRepository(), FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository()) { 10_000L }
        editorVm.open(FullEditorRequest.fromHandoff(FullEditorHandoff("2703020", 2, "", 0, 0, threadAuthor = "linus_hi")))
        val clock = AtomicLong(1_700_000_000_000L)
        val smsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val smsRepo = FakeSmsRepository(clock = { clock.get() }, initialMessages = emptyList())
        val smsVm = SmsConversationViewModel(smsRepo, FakeAuthRepository(signedIn = true), SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, clock = System::currentTimeMillis, gatewayScope = smsScope)) { clock.get() }
        var activeHost by mutableIntStateOf(0)

        try {
            composeRule.setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                        when (activeHost) {
                            0 -> QuickReplySheet(qrVm, rememberHiGlassState(), onDismissFinished = {}, onOpenLogin = {}, emojiPanelSlot = emojiSlot)
                            1 -> FullEditorScreen(editorVm, onOpenLogin = {}, emojiPanelSlot = emojiSlot)
                            else -> SmsConversationScreen(SmsConversationArgs("20001", "Alice"), true, {}, { _, _ -> }, {}, PaddingValues(0.dp), smsVm, emojiPanelSlot = emojiSlot)
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            fun tapBackspace() {
                composeRule.onNodeWithContentDescription("删除").performClick()
                composeRule.waitForIdle()
            }
            fun runHostSequence(
                hostLabel: String,
                setInitial: (text: String, selStart: Int, selEnd: Int) -> Unit,
                readText: () -> String,
            ) {
                composeRule.onNodeWithContentDescription("表情").performClick()
                composeRule.waitForIdle()
                // 序列：中😀:){:2_41:} -> 依次整枚删 {:2_41:}、:)、😀、中
                setInitial("中😀:){:2_41:}", 13, 13)
                tapBackspace()
                assertEquals("$hostLabel 应整枚删除 {:2_41:}", "中😀:)", readText())
                tapBackspace()
                assertEquals("$hostLabel 应整枚删除 :)", "中😀", readText())
                tapBackspace()
                assertEquals("$hostLabel 应完整删除代理对 emoji 😀", "中", readText())
                tapBackspace()
                assertEquals("$hostLabel 应删除单个中文字符", "", readText())

                // 选区非空时只删选区（含横跨表情代码）
                setInitial("前{:2_41:}后", 1, 9)
                tapBackspace()
                assertEquals("$hostLabel 非空选区应删除选中内容", "前后", readText())
            }

            // 1. QuickReplySheet
            runHostSequence(
                hostLabel = "QuickReply",
                setInitial = { txt, s, e ->
                    val input = composeRule.onNodeWithContentDescription("回复 linus_hi")
                    input.performSemanticsAction(SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString(txt)) }
                    composeRule.waitForIdle()
                    input.performSemanticsAction(SemanticsActions.SetSelection) { it(s, e, false) }
                    composeRule.waitForIdle()
                },
                readText = { qrVm.uiState.value.text },
            )

            // 2. FullEditorScreen
            activeHost = 1
            composeRule.waitForIdle()
            runHostSequence(
                hostLabel = "FullEditor",
                setInitial = { txt, s, e ->
                    composeRule.runOnIdle {
                        val id = editorVm.uiState.value.body.firstTextBlockId
                        editorVm.onTextBlockChanged(id, txt, s, e)
                    }
                    composeRule.waitForIdle()
                },
                readText = { editorVm.uiState.value.body.serialize() },
            )

            // 3. SmsConversationScreen
            activeHost = 2
            composeRule.waitForIdle()
            runHostSequence(
                hostLabel = "SMS",
                setInitial = { txt, s, e ->
                    val input = composeRule.onNode(hasSetTextAction())
                    input.performSemanticsAction(SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString(txt)) }
                    composeRule.waitForIdle()
                    input.performSemanticsAction(SemanticsActions.SetSelection) { it(s, e, false) }
                    composeRule.waitForIdle()
                },
                readText = { smsVm.uiState.value.inputText },
            )
        } finally {
            smsScope.cancel()
        }
    }

    private fun verifyFullEditorPlacementZeroShift(windowWidthDp: Float, placement: FullEditorPlacement, minBodySpaceDp: Float) {
        val vm = FullEditorViewModel(FakePostRepository(), FakeImageUploadRepository(), FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository()) { 10_000L }
        vm.open(FullEditorRequest.fromHandoff(FullEditorHandoff("2703002", 2, "测试正文", 2, 2, threadAuthor = "linus_hi")))
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                FullEditorPlacementFrame(placement, windowWidthDp, isSubmitting = false, onRequestClose = {}) { isModal ->
                    FullEditorScreen(viewModel = vm, onOpenLogin = {}, isModalCard = isModal, emojiPanelSlot = emojiSlot)
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density
        fun dispatchInsets(imeDp: Int, navDp: Int = 24, statusDp: Int = 24) {
            composeRule.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, with(density) { statusDp.dp.roundToPx() }, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, with(density) { navDp.dp.roundToPx() }))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, with(density) { imeDp.dp.roundToPx() }))
                    .build()
                ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insets)
            }
            composeRule.waitForIdle()
        }
        fun assertFrame(label: String, expectedBottomDp: Float) {
            val toolbar = composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode()
            val navCd = if (placement == FullEditorPlacement.CenteredModal) "关闭" else "返回"
            val back = composeRule.onNodeWithContentDescription(navCd).fetchSemanticsNode()
            val bottomDp = with(density) { (toolbar.positionInRoot.y + toolbar.size.height).toDp().value }
            val bodySpaceDp = with(density) { (toolbar.positionInRoot.y - (back.positionInRoot.y + back.size.height)).toDp().value }
            assertEquals("$label 工具栏底缘偏移超 1dp", expectedBottomDp, bottomDp, 1.0f)
            assertTrue("$label 正文区高度塌陷(${bodySpaceDp}dp < ${minBodySpaceDp}dp)", bodySpaceDp >= minBodySpaceDp)
        }

        dispatchInsets(260)
        val baseline = with(density) {
            val n = composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode()
            (n.positionInRoot.y + n.size.height).toDp().value
        }
        assertFrame("${placement.name}-${windowWidthDp.toInt()} 基线", baseline)

        composeRule.onNodeWithContentDescription("表情").performClick()
        composeRule.waitForIdle()
        assertFrame("${placement.name}-${windowWidthDp.toInt()} IME→表情首帧", baseline)
        for (ime in listOf(200, 120, 40, 0)) {
            dispatchInsets(ime)
            assertFrame("${placement.name}-${windowWidthDp.toInt()} IME→表情 ime=$ime", baseline)
        }

        composeRule.onNodeWithContentDescription("键盘").performClick()
        composeRule.waitForIdle()
        assertFrame("${placement.name}-${windowWidthDp.toInt()} 表情→键盘首帧", baseline)
        for (ime in listOf(80, 170, 260)) {
            dispatchInsets(ime)
            assertFrame("${placement.name}-${windowWidthDp.toInt()} 表情→键盘 ime=$ime", baseline)
        }
    }

    /** V-E7 原样移植探针 1/3（933×696 CenteredModal）：真实 NextAppRoot + FullEditorHost 单帧步进校验工具栏与图标底缘 ≤ 1dp。 */
    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun probe1b_bugcheck_nextAppRoot_fullEditor_centeredModal933dp_frameByFrameImeEmojiSwap_zeroBottomDisplacement() {
        verifyNextAppRootCenteredModalProbe(label = "CenteredModal-933x696", navBarDp = 0, imeMaxDp = 260)
    }

    /** V-E7 原样移植探针 2/3（749×559 CenteredModal）：真实 NextAppRoot + FullEditorHost 单帧步进校验工具栏与图标底缘 ≤ 1dp。 */
    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun probe1b_bugcheck_nextAppRoot_fullEditor_centeredModal749dp_frameByFrameImeEmojiSwap_zeroBottomDisplacement() {
        verifyNextAppRootCenteredModalProbe(label = "CenteredModal-749x559", navBarDp = 0, imeMaxDp = 250)
    }

    /** V-E7 原样移植探针 3/3（640×1024 CenteredModal）：真实 NextAppRoot + FullEditorHost 单帧步进校验工具栏与图标底缘 ≤ 1dp。 */
    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.SIDEBAR_SINGLE_640_1024)
    fun probe1b_bugcheck_nextAppRoot_fullEditor_centeredModal640dp_frameByFrameImeEmojiSwap_zeroBottomDisplacement() {
        verifyNextAppRootCenteredModalProbe(label = "CenteredModal-640x1024", navBarDp = 24, imeMaxDp = 280)
    }

    private fun verifyNextAppRootCenteredModalProbe(label: String, navBarDp: Int, imeMaxDp: Int) {
        val harness = MultiPaneTestHarness()
        val postRepo = FakePostRepository()
        val editorVm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), harness.authRepo, harness.settingsRepo) { 10_000L }
        val qrVm = QuickReplyViewModel(postRepo, harness.authRepo, clockMs = { 10_000L })
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) -> qrVm as T
                modelClass.isAssignableFrom(FullEditorViewModel::class.java) -> editorVm as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        val owner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore = harness.parentOwner.viewModelStore
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory = factory
        }
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
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
        composeRule.waitForIdle()

        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.mainClock.advanceTimeBy(250L)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250L))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("展开全屏").performClick()
        composeRule.waitForIdle()

        val density = composeRule.density
        val rootView = requireNotNull(hostView)
        fun dispatchWindowInsets(imeDp: Int) {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, with(density) { navBarDp.dp.roundToPx() }))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, with(density) { imeDp.dp.roundToPx() }))
                .build()
            composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(rootView, insets) }
        }
        fun assertFrameEdgeStable(phaseLabel: String, baselineDp: Float, actualDp: Float) {
            val deltaDp = abs(actualDp - baselineDp)
            assertTrue("[$label][$phaseLabel] 底缘发生位移！baseline=${baselineDp}dp, actual=${actualDp}dp, |Δbottom|=${deltaDp}dp (> 1dp)", deltaDp <= 1f)
        }
        fun stepFrameAndCheck(onStep: (String) -> Unit) {
            Snapshot.sendApplyNotifications()
            composeRule.mainClock.advanceTimeByFrame()
            onStep("postCompose")
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            onStep("postLooper")
            composeRule.mainClock.advanceTimeByFrame()
            onStep("nextFrame")
        }
        fun editorToolbarBottomDp(): Float {
            val imgBtn = composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode()
            val toolbarRowBounds = imgBtn.layoutInfo.parentInfo?.let { parentLayout ->
                val coords = parentLayout.coordinates
                coords.localToRoot(Offset.Zero).y + coords.size.height
            } ?: imgBtn.boundsInRoot.bottom
            return with(density) { toolbarRowBounds.toDp().value }
        }
        fun editorInsertImageIconBottomDp(): Float =
            with(density) { composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode().boundsInRoot.bottom.toDp().value }

        dispatchWindowInsets(imeDp = imeMaxDp)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(250L)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250L))
        composeRule.waitForIdle()

        val baselineToolbarBottom = editorToolbarBottomDp()
        val baselineIconBottom = editorInsertImageIconBottomDp()

        composeRule.mainClock.autoAdvance = false
        try {
            // 1. IME -> 表情
            composeRule.onNodeWithContentDescription("表情").performClick()
            Snapshot.sendApplyNotifications()
            composeRule.mainClock.advanceTimeByFrame()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFrameEdgeStable("Editor tapEmoji frame0 toolbar", baselineToolbarBottom, editorToolbarBottomDp())
            assertFrameEdgeStable("Editor tapEmoji frame0 icon", baselineIconBottom, editorInsertImageIconBottomDp())
            composeRule.onNodeWithContentDescription("键盘").assertIsDisplayed()
            composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "表情面板")).assertIsDisplayed()

            val hideSteps = listOf(imeMaxDp - 60, imeMaxDp - 130, 60, 0).map { it.coerceAtLeast(0) }
            for (imeStep in hideSteps) {
                dispatchWindowInsets(imeDp = imeStep)
                stepFrameAndCheck { stepLabel ->
                    assertFrameEdgeStable("Editor hide($imeStep/$stepLabel) toolbar", baselineToolbarBottom, editorToolbarBottomDp())
                    assertFrameEdgeStable("Editor hide($imeStep/$stepLabel) icon", baselineIconBottom, editorInsertImageIconBottomDp())
                }
            }

            // 2. 表情 -> 键盘
            composeRule.onNodeWithContentDescription("键盘").performClick()
            Snapshot.sendApplyNotifications()
            composeRule.mainClock.advanceTimeByFrame()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFrameEdgeStable("Editor tapKbd frame0 toolbar", baselineToolbarBottom, editorToolbarBottomDp())
            assertFrameEdgeStable("Editor tapKbd frame0 icon", baselineIconBottom, editorInsertImageIconBottomDp())
            composeRule.onNodeWithContentDescription("表情").assertIsDisplayed()

            val showSteps = listOf(60, imeMaxDp / 2, imeMaxDp - 40, imeMaxDp)
            for (imeStep in showSteps) {
                dispatchWindowInsets(imeDp = imeStep)
                stepFrameAndCheck { stepLabel ->
                    assertFrameEdgeStable("Editor show($imeStep/$stepLabel) toolbar", baselineToolbarBottom, editorToolbarBottomDp())
                    assertFrameEdgeStable("Editor show($imeStep/$stepLabel) icon", baselineIconBottom, editorInsertImageIconBottomDp())
                }
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    /** S3/E6b 合并回归：编辑已发表首楼（EditPost）下表情面板等高零位移互换、插入与整枚回删、点标题框切回键盘及「还原原文」联动。 */
    @Test
    fun fullEditor_editPostMode_emojiPanel_zeroShift_insertWholeBackspace_andSubjectTapSwitchesToKeyboard() {
        val postRepo = FakePostRepository().apply {
            prePostHandler = {
                PrePostResult.Success(
                    defaultSamplePrePostInfo(
                        typeOptions = linkedMapOf("0" to "分类", "56" to "Discovery"),
                        typeId = "56",
                    ).copy(subject = "编辑模式原标题", text = "原始楼层正文"),
                )
            }
        }
        val vm = FullEditorViewModel(postRepo, FakeImageUploadRepository(), FakeQuickReplyAuthRepository(), FakeComposeSettingsRepository()) { 10_000L }
        vm.open(FullEditorRequest.EditPost(fid = 2, tid = "2704001", pid = "53529099", page = 1, isFirstFloor = true, floorNumber = 1, author = "linus_hi"))
        var hostView: View? = null

        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                FullEditorPlacementFrame(FullEditorPlacement.FullScreen, 411f, isSubmitting = false, onRequestClose = {}) { isModal ->
                    FullEditorScreen(viewModel = vm, onOpenLogin = {}, isModalCard = isModal, emojiPanelSlot = emojiSlot)
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density
        fun dispatchInsets(imeDp: Int, navDp: Int = 24) {
            composeRule.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, with(density) { navDp.dp.roundToPx() }))
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, with(density) { imeDp.dp.roundToPx() }))
                    .build()
                ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insets)
            }
            composeRule.waitForIdle()
        }
        fun toolbarBottomDp(): Float =
            with(density) { composeRule.onNodeWithContentDescription("插入图片").fetchSemanticsNode().boundsInRoot.bottom.toDp().value }

        // 1. IME=260dp → 表情，逐帧底缘零位移
        dispatchInsets(260)
        val baseline = toolbarBottomDp()
        composeRule.onNodeWithContentDescription("表情").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("键盘").assertIsDisplayed()
        for (ime in listOf(160, 60, 0)) {
            dispatchInsets(ime)
            assertEquals("EditPost IME→表情 ime=$ime 工具栏底缘偏移", baseline, toolbarBottomDp(), 1.0f)
        }

        // 2. 编辑模式下插入 {:2_41:} 触发「还原原文」出现，再点删除键整枚回删恢复原文
        composeRule.onNodeWithContentDescription("酷猴表情").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情 {:2_41:}").performClick()
        composeRule.waitForIdle()
        assertEquals("原始楼层正文{:2_41:}", vm.uiState.value.body.serialize())
        composeRule.onNodeWithText("还原原文").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("删除").performClick()
        composeRule.waitForIdle()
        assertEquals("编辑模式删除键应整枚回删 {:2_41:}", "原始楼层正文", vm.uiState.value.body.serialize())

        // 3. 点编辑首楼标题框（EditorSubjectField.onInputPress）自动收起表情切回键盘，底缘零位移
        composeRule.onNodeWithContentDescription("标题").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("表情").assertIsDisplayed()
        assertEquals("EditPost 点标题框首帧底缘不得塌陷", baseline, toolbarBottomDp(), 1.0f)
        for (ime in listOf(120, 260)) {
            dispatchInsets(ime)
            assertEquals("EditPost 点标题框→IME ime=$ime 底缘偏移", baseline, toolbarBottomDp(), 1.0f)
        }
    }
}
