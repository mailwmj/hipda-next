package net.jejer.hipda.feature.messages

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dagger.Binds
import dagger.Module
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.*
import net.jejer.hipda.data.message.*
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.di.MessagesUiModule
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.parser.common.*
import net.jejer.hipda.parser.message.*
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E8 消息页与短消息对话核心交互自动化 UI 测试：
 * 直接通过 [MessagesPaneRendererImpl] 驱动，覆盖验收 1–2、B2 冷却重发拦截 UI、S3 150ms 防闪、S4 窄列省略 `⋮` 与 Hilt 绑定校验。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = Application::class,
    sdk = [34],
    qualifiers = FeatureScreenshotQualifiers.PHONE_411_891,
)
class MessagesScreenBehaviorTest {

    @Test
    fun smsConversation_optimisticSendConfirmCooldownAndEditableDuringCountdown_zeroGlass() = withAnimatorDurationScale(0f) {
        val clock = AtomicLong(1_700_000_000_000L)
        val sendGate = CompletableDeferred<SmsSendOutcome>()
        val smsRepo = FakeSmsRepository(
            clock = { clock.get() },
            initialMessages = listOf(SmsMessageItem("20000", "Alice", null, "10:00", "早安 :)", isNew = false, isMine = false)),
            onSend = { _, _, _ -> sendGate.await() },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val renderer: MessagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, FakeNotifyRepository(), FakeAuthRepository(signedIn = true), idleGateway(scope))
            setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                        Box(Modifier.fillMaxSize()) {
                            renderer.ConversationContent(SmsConversationArgs("20000", "Alice"), true, {}, { _, _ -> }, {}, PaddingValues(0.dp))
                            HiSnackbarHost(hostState = snackbarHostState, bottomClearance = 0.dp, modifier = Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
            waitForIdle()
            onNodeWithText("Alice").assertIsDisplayed()
            assertGlassSurfaceCountAtMost(0)

            // 1. 输入并点击发送 -> 气泡立即入列且显示“正在发送…”进度圈
            onNode(hasSetTextAction()).performTextInput("乐观发送测试")
            onNodeWithContentDescription("发送").assertIsEnabled().performClick()
            waitForIdle()
            onNodeWithText("乐观发送测试").assertIsDisplayed()
            onNodeWithContentDescription("正在发送…").assertIsDisplayed()

            // 2. 服务器确认成功 -> 假仓库自动启动 15 秒冷却，进度圈消失，发送按钮切换为 15 秒倒计时且禁用
            sendGate.complete(SmsSendOutcome.Sent)
            waitUntil { onAllNodes(hasContentDescription("15 秒后可发送")).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            onNodeWithContentDescription("正在发送…").assertDoesNotExist()
            onNodeWithContentDescription("15 秒后可发送").assertIsDisplayed().assertIsNotEnabled()

            // 3. 冷却期间允许继续编辑输入，发送仍禁用
            onNode(hasSetTextAction()).performTextInput("倒计时期间继续编辑")
            waitForIdle()
            onNodeWithText("倒计时期间继续编辑").assertIsDisplayed()
            onNodeWithContentDescription("15 秒后可发送").assertIsNotEnabled()

            // 4. 冷却结束后恢复发送按钮且可用
            clock.addAndGet(15_000L)
            smsRepo.clearCooldown()
            waitUntil { onAllNodes(hasContentDescription("发送")).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            onNodeWithContentDescription("发送").assertIsDisplayed().assertIsEnabled()
            assertGlassSurfaceCountAtMost(0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun smsConversation_sendFailureResendSuccessAndDeleteWithUndoSnackbar() = withAnimatorDurationScale(0f) {
        val clock = AtomicLong(1_700_000_000_000L)
        var sendAttempt = 0
        val smsRepo = FakeSmsRepository(
            clock = { clock.get() },
            initialMessages = listOf(SmsMessageItem("20000", "Bob", null, "11:00", "历史消息", isNew = false, isMine = false)),
            onSend = { _, _, _ ->
                sendAttempt++
                when (sendAttempt) {
                    1 -> SmsSendOutcome.Error("network")
                    2 -> SmsSendOutcome.Sent
                    else -> SmsSendOutcome.Rejected("服务器拒绝")
                }
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val renderer: MessagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, FakeNotifyRepository(), FakeAuthRepository(signedIn = true), idleGateway(scope))
            setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                        Box(Modifier.fillMaxSize()) {
                            renderer.ConversationContent(SmsConversationArgs("20000", "Bob"), true, {}, { _, _ -> }, {}, PaddingValues(0.dp))
                            HiSnackbarHost(hostState = snackbarHostState, bottomClearance = 0.dp, modifier = Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
            waitForIdle()

            // 1. 首次发送失败 -> 气泡旁常驻红色感叹号“发送失败”
            onNode(hasSetTextAction()).performTextInput("需要重发的消息")
            onNodeWithContentDescription("发送").performClick()
            waitUntil { onAllNodes(hasContentDescription("发送失败")).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            onNodeWithText("需要重发的消息").assertIsDisplayed()
            onNodeWithContentDescription("发送失败").assertIsDisplayed()

            // 2. 点感叹号 -> 选“重发” -> 发送成功，感叹号消失并启动 15 秒冷却
            onNodeWithContentDescription("发送失败").performClick()
            waitForIdle()
            onNodeWithText("重发").assertIsDisplayed().performClick()
            waitUntil { onAllNodes(hasContentDescription("发送失败")).fetchSemanticsNodes().isEmpty() }
            waitForIdle()
            assertEquals(2, sendAttempt)

            // 等待冷却结束后再发下一条失败消息
            clock.addAndGet(15_000L)
            smsRepo.clearCooldown()
            waitUntil { onAllNodes(hasContentDescription("发送")).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()

            // 3. 再发一条失败消息 -> 点感叹号 -> 选“删除” -> 气泡消失且弹“已删除”+“撤销” -> 点“撤销”原位恢复
            onNode(hasSetTextAction()).performTextInput("将要删除再撤销的消息")
            onNodeWithContentDescription("发送").performClick()
            waitUntil { onAllNodes(hasContentDescription("发送失败")).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()

            onNodeWithContentDescription("发送失败").performClick()
            waitForIdle()
            onNodeWithText("删除").assertIsDisplayed().performClick()
            waitForIdle()

            onNodeWithText("将要删除再撤销的消息").assertDoesNotExist()
            onNodeWithText("已删除").assertIsDisplayed()
            onNodeWithText("撤销").assertIsDisplayed().performClick()
            waitForIdle()

            onNodeWithText("将要删除再撤销的消息").assertIsDisplayed()
            onNodeWithContentDescription("发送失败").assertIsDisplayed()
            assertGlassSurfaceCountAtMost(0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun smsConversation_resendBlockedDuringCooldown_keepsFailedAndShowsCooldownSnackbar() = withAnimatorDurationScale(0f) {
        val clock = AtomicLong(1_700_000_000_000L)
        val smsRepo = FakeSmsRepository(
            clock = { clock.get() },
            initialMessages = listOf(SmsMessageItem("20000", "Bob", null, "11:00", "历史消息", isNew = false, isMine = false)),
            onSend = { _, _, msg -> if (msg == "失败首条") SmsSendOutcome.Error("500") else SmsSendOutcome.Sent },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val renderer: MessagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, FakeNotifyRepository(), FakeAuthRepository(signedIn = true), idleGateway(scope))
            setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                        Box(Modifier.fillMaxSize()) {
                            renderer.ConversationContent(SmsConversationArgs("20000", "Bob"), true, {}, { _, _ -> }, {}, PaddingValues(0.dp))
                            HiSnackbarHost(hostState = snackbarHostState, bottomClearance = 0.dp, modifier = Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
            waitForIdle()

            // M1 失败（不触发冷却），随后 M2 成功（触发 15 秒冷却）
            onNode(hasSetTextAction()).performTextInput("失败首条")
            onNodeWithContentDescription("发送").performClick()
            waitUntil { onAllNodes(hasContentDescription("发送失败")).fetchSemanticsNodes().isNotEmpty() }

            onNode(hasSetTextAction()).performTextInput("成功次条")
            onNodeWithContentDescription("发送").performClick()
            waitUntil { onAllNodes(hasContentDescription("15 秒后可发送")).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            assertEquals(2, smsRepo.sendCallCount.get())

            // 冷却期间点击失败气泡“重发” -> 被 ViewModel 拦截，0 次仓库调用，保持失败态并弹冷却提示 Snackbar
            onNodeWithContentDescription("发送失败").performClick()
            waitForIdle()
            onNodeWithText("重发").performClick()
            waitForIdle()

            assertEquals("冷却期内重发不得调用仓库 sendSms", 2, smsRepo.sendCallCount.get())
            onNodeWithContentDescription("发送失败").assertIsDisplayed()
            onNodeWithText("发送太快，请等 15 秒后再试").assertIsDisplayed()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun smsConversation_consecutiveDeleteTwoFailedThenUndo_dismissesFirstSnackbarAndRestoresOnlySecondInPlace() =
        withAnimatorDurationScale(0f) {
            val clock = AtomicLong(1_700_000_000_000L)
            val smsRepo = FakeSmsRepository(
                clock = { clock.get() },
                initialMessages = listOf(SmsMessageItem("20000", "Bob", null, "10:00", "历史首条", false, false)),
                onSend = { _, _, msg -> if (msg == "尾条成功") SmsSendOutcome.Sent else SmsSendOutcome.Error("500") },
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val vm = SmsConversationViewModel(smsRepo, FakeAuthRepository(signedIn = true), idleGateway(scope)) { clock.get() }
            try {
                setContent {
                    val snackbarHostState = remember { SnackbarHostState() }
                    val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                            Box(Modifier.fillMaxSize()) {
                                SmsConversationScreen(SmsConversationArgs("20000", "Bob"), true, {}, { _, _ -> }, {}, PaddingValues(0.dp), vm)
                                HiSnackbarHost(hostState = snackbarHostState, bottomClearance = 0.dp, modifier = Modifier.align(Alignment.BottomCenter))
                            }
                        }
                    }
                }
                waitForIdle()

                // 连续构造：[历史首条, 失败第一条, 失败第二条, 尾条成功]
                onNode(hasSetTextAction()).performTextInput("失败第一条")
                onNodeWithContentDescription("发送").performClick()
                waitUntil { onAllNodes(hasContentDescription("发送失败")).fetchSemanticsNodes().size == 1 }

                onNode(hasSetTextAction()).performTextInput("失败第二条")
                onNodeWithContentDescription("发送").performClick()
                waitUntil { onAllNodes(hasContentDescription("发送失败")).fetchSemanticsNodes().size == 2 }

                onNode(hasSetTextAction()).performTextInput("尾条成功")
                onNodeWithContentDescription("发送").performClick()
                waitUntil { onAllNodes(hasContentDescription("15 秒后可发送")).fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()

                // 连续删除第一条、第二条失败消息（第一条 Snackbar 仍在展示时直接删第二条 -> 立即关掉上一条）
                onAllNodes(hasContentDescription("发送失败"))[0].performClick()
                waitForIdle()
                onNodeWithText("删除").performClick()
                waitForIdle()
                onNodeWithText("失败第一条").assertDoesNotExist()
                onNodeWithText("已删除").assertIsDisplayed()

                onAllNodes(hasContentDescription("发送失败"))[0].performClick()
                waitForIdle()
                onNodeWithText("删除").performClick()
                waitForIdle()
                onNodeWithText("失败第二条").assertDoesNotExist()
                onNodeWithText("已删除").assertIsDisplayed()

                // 点击当前 Snackbar 的“撤销” -> 只恢复第二条，且回到原位置（历史首条之后、尾条成功之前）
                onNodeWithText("撤销").assertIsDisplayed().performClick()
                waitForIdle()

                onNodeWithText("失败第一条").assertDoesNotExist()
                onNodeWithText("失败第二条").assertIsDisplayed()
                assertEquals(
                    listOf("历史首条", "失败第二条", "尾条成功"),
                    vm.uiState.value.messages.map { it.rawMessage ?: it.contentHtml },
                )
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun r2s1_smsConversation_cooldownAndErrorEventsImmediatelyReplaceActiveUndoSnackbar() =
        withAnimatorDurationScale(0f) {
            val clock = AtomicLong(1_700_000_000_000L)
            var failRefresh = false
            val initialHistory = listOf(SmsMessageItem("20000", "Bob", null, "10:00", "历史首条", false, false))
            val smsRepo = FakeSmsRepository(
                clock = { clock.get() },
                initialMessages = initialHistory,
                onLoadDetail = {
                    if (failRefresh) MessageResult.Error("refresh_failed")
                    else MessageResult.Success(SmsDetailPage("10000", "HiUser", "fh", initialHistory, fakePageMeta(), hasContainer = true))
                },
                onSend = { _, _, msg -> if (msg == "尾条成功") SmsSendOutcome.Sent else SmsSendOutcome.Error("500") },
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val vm = SmsConversationViewModel(smsRepo, FakeAuthRepository(signedIn = true), idleGateway(scope)) { clock.get() }
            try {
                setContent {
                    val snackbarHostState = remember { SnackbarHostState() }
                    val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                            Box(Modifier.fillMaxSize()) {
                                SmsConversationScreen(SmsConversationArgs("20000", "Bob"), true, {}, { _, _ -> }, {}, PaddingValues(0.dp), vm)
                                HiSnackbarHost(hostState = snackbarHostState, bottomClearance = 0.dp, modifier = Modifier.align(Alignment.BottomCenter))
                            }
                        }
                    }
                }
                waitForIdle()

                // 构造两条失败气泡 + 一条成功气泡（激活 15 秒冷却）
                onNode(hasSetTextAction()).performTextInput("失败第一条")
                onNodeWithContentDescription("发送").performClick()
                waitUntil { onAllNodes(hasContentDescription("发送失败")).fetchSemanticsNodes().size == 1 }

                onNode(hasSetTextAction()).performTextInput("失败第二条")
                onNodeWithContentDescription("发送").performClick()
                waitUntil { onAllNodes(hasContentDescription("发送失败")).fetchSemanticsNodes().size == 2 }

                onNode(hasSetTextAction()).performTextInput("尾条成功")
                onNodeWithContentDescription("发送").performClick()
                waitUntil { onAllNodes(hasContentDescription("15 秒后可发送")).fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()

                // 1. 删除第一条失败气泡 -> 10s「已删除 | 撤销」Snackbar 在途（不推进超时）
                onAllNodes(hasContentDescription("发送失败"))[0].performClick()
                waitForIdle()
                onNodeWithText("删除").performClick()
                waitForIdle()
                onNodeWithText("失败第一条").assertDoesNotExist()
                onNodeWithText("已删除").assertIsDisplayed()
                onNodeWithText("撤销").assertIsDisplayed()

                // 立即对第二条失败气泡点击重发 -> 触发 CooldownBlocked，必须立即顶替旧撤销条且不误触发撤销回调
                onAllNodes(hasContentDescription("发送失败"))[0].performClick()
                waitForIdle()
                onNodeWithText("重发").performClick()
                waitForIdle()

                onNodeWithText("发送太快，请等 15 秒后再试").assertIsDisplayed()
                onNodeWithText("已删除").assertDoesNotExist()
                onNodeWithText("撤销").assertDoesNotExist()
                onNodeWithText("失败第一条").assertDoesNotExist()

                // 2. 等待冷却提示自然消退后再删第二条失败气泡 -> 立即触发 ShowError（刷新失败）同样即时顶替旧撤销条
                mainClock.advanceTimeBy(5_000L)
                waitForIdle()
                onNodeWithText("发送太快，请等 15 秒后再试").assertDoesNotExist()

                onAllNodes(hasContentDescription("发送失败"))[0].performClick()
                waitForIdle()
                onNodeWithText("删除").performClick()
                waitForIdle()
                onNodeWithText("失败第二条").assertDoesNotExist()
                onNodeWithText("已删除").assertIsDisplayed()

                failRefresh = true
                runOnIdle { vm.refresh() }
                waitForIdle()

                onNodeWithText("已删除").assertDoesNotExist()
                onNodeWithText("撤销").assertDoesNotExist()
                onNodeWithText("重试").assertIsDisplayed()
                onNodeWithText("失败第二条").assertDoesNotExist()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun smsList_clearConfirmationDialogAndLoggedOutPromptAndSidebarMorphing() = withAnimatorDurationScale(0f) {
        val clearedUids = mutableListOf<String>()
        val smsRepo = FakeSmsRepository(
            initialConversations = listOf(
                SmsConversationItem("20001", "Alice", null, "刚刚", "晚上一起吃饭吗", isNew = true),
                SmsConversationItem("20002", "Bob", null, "昨天", "收到谢谢", isNew = false),
            ),
            onClear = { uid -> clearedUids += uid; SmsClearOutcome.Cleared },
        )
        val authRepo = FakeAuthRepository(signedIn = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var isSidebar by mutableStateOf(false)
        var loginClicks = 0
        try {
            val renderer: MessagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, FakeNotifyRepository(initialSmsUnread = 1), authRepo, idleGateway(scope))
            setContent {
                val listState = rememberLazyListState()
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    renderer.ListContent(
                        ShellSidebarEntry.Sms, isSidebar, false, null, null, {}, {}, {}, { _, _ -> }, { loginClicks++ }, PaddingValues(0.dp), listState,
                    )
                }
            }
            waitForIdle()

            onNodeWithText("消息").assertIsDisplayed()
            onNodeWithText("短消息").assertIsDisplayed()
            onNodeWithText("提醒").assertIsDisplayed()
            onNodeWithText("Alice").assertIsDisplayed()
            assertGlassSurfaceCountAtMost(0)

            isSidebar = true
            waitForIdle()
            onNodeWithText("短消息").assertIsDisplayed()
            onNodeWithText("提醒").assertDoesNotExist()
            assertGlassSurfaceCountAtMost(0)

            onAllNodes(hasContentDescription("更多"))[0].performClick()
            waitForIdle()
            onNodeWithText("清空").assertIsDisplayed().performClick()
            waitForIdle()
            onNodeWithText("清空与 Alice 的全部短消息？").assertIsDisplayed()
            onNodeWithText("清空后无法恢复。").assertIsDisplayed()
            onNodeWithText("取消").assertIsDisplayed().performClick()
            waitForIdle()
            assertTrue(clearedUids.isEmpty())
            onNodeWithText("Alice").assertIsDisplayed()

            onAllNodes(hasContentDescription("更多"))[0].performClick()
            waitForIdle()
            onNodeWithText("清空").performClick()
            waitForIdle()
            onNodeWithText("清空").performClick()
            waitForIdle()
            assertEquals(listOf("20001"), clearedUids)
            onNodeWithText("Alice").assertDoesNotExist()

            authRepo.signOut()
            waitForIdle()
            onNodeWithText("需要登录才能查看").assertIsDisplayed()
            onNodeWithText("登录").assertIsDisplayed().performClick()
            assertEquals(1, loginClicks)
            assertGlassSurfaceCountAtMost(0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun smsList_compactWidthUnder300dp_hidesTrailingMoreButtonAndAllowsLongPressClear() = withAnimatorDurationScale(0f) {
        val clearedUids = mutableListOf<String>()
        val smsRepo = FakeSmsRepository(
            initialConversations = listOf(SmsConversationItem("20001", "coffee_note", null, "18分钟前", "浅烘豆参数", isNew = true)),
            onClear = { uid -> clearedUids += uid; SmsClearOutcome.Cleared },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val renderer: MessagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, FakeNotifyRepository(), FakeAuthRepository(signedIn = true), idleGateway(scope))
            setContent {
                val listState = rememberLazyListState()
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.width(274.dp)) {
                        renderer.ListContent(
                            ShellSidebarEntry.Sms, isSidebar = true, showBack = false, onBack = null, selectedConversationUid = null,
                            onSelectMessageEntry = {}, onOpenConversation = {}, onOpenThread = {}, onOpenUserProfile = { _, _ -> }, onOpenLogin = {},
                            contentPadding = PaddingValues(0.dp), listState = listState,
                        )
                    }
                }
            }
            waitForIdle()

            onNodeWithText("coffee_note").assertIsDisplayed()
            onNodeWithContentDescription("更多").assertDoesNotExist()

            // 长按行仍可触发清空二次确认
            onNodeWithText("coffee_note").performTouchInput { longClick() }
            waitForIdle()
            onNodeWithText("清空").assertIsDisplayed().performClick()
            waitForIdle()
            onNodeWithText("清空与 coffee_note 的全部短消息？").assertIsDisplayed()
            onNodeWithText("清空").performClick()
            waitForIdle()
            assertEquals(listOf("20001"), clearedUids)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun s3_messagesList_hiContentStateDelaysSkeletonBy150msOnFastPassiveLoad() = withAnimatorDurationScale(0f) {
        val gate = CompletableDeferred<MessageResult<SmsListPage>>()
        val smsRepo = FakeSmsRepository(onLoadConversations = { gate.await() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val renderer: MessagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, FakeNotifyRepository(), FakeAuthRepository(signedIn = true), idleGateway(scope))
            mainClock.autoAdvance = false
            setContent {
                val listState = rememberLazyListState()
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    renderer.ListContent(
                        ShellSidebarEntry.Sms, false, false, null, null, {}, {}, {}, { _, _ -> }, {}, PaddingValues(0.dp), listState,
                    )
                }
            }
            mainClock.advanceTimeBy(80L)
            onNodeWithText("暂无短消息").assertDoesNotExist()

            // 150ms 内返回内容 -> 直接切入内容，无骨架闪屏
            gate.complete(MessageResult.Success(SmsListPage(listOf(SmsConversationItem("20001", "Alice", null, "刚刚", "秒开内容", true)), fakePageMeta(), true)))
            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithText("Alice").assertIsDisplayed()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun notifyList_doesNotAutoClearOnEntry_marksSingleReadWithTargetPid_andMarkAllReadShowsSnackbar() = withAnimatorDurationScale(0f) {
        val entries = listOf(
            NotifyEntry("k1", NotifyItem(NotifyKind.THREAD, "第一篇提醒帖子", "Alice 回复了您的帖子", "Alice", "20001", null, "1001", "5001", "10 分钟前", true), isRead = false),
            NotifyEntry("k2", NotifyItem(NotifyKind.QUOTE, "第二篇引用帖子", "", "Bob", "20002", null, "2602293", "556677", "5 分钟前", true, "原帖内容", "同意楼上观点"), isRead = false),
            NotifyEntry("k3", NotifyItem(NotifyKind.SYSTEM, "系统信息", "您的帖子已被加精", null, null, null, null, null, "刚刚", true), isRead = false),
        )
        val notifyRepo = FakeNotifyRepository(initialEntries = entries)
        val openedThreads = mutableListOf<ThreadNavArgs>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val renderer: MessagesPaneRenderer = MessagesPaneRendererImpl(FakeSmsRepository(), notifyRepo, FakeAuthRepository(signedIn = true), idleGateway(scope))
            setContent {
                var currentEntry by remember { mutableStateOf<ShellSidebarEntry>(ShellSidebarEntry.Sms) }
                val snackbarHostState = remember { SnackbarHostState() }
                val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                val listState = rememberLazyListState()
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                        Box(Modifier.fillMaxSize()) {
                            renderer.ListContent(
                                currentEntry, false, false, null, null, { currentEntry = it }, {}, { openedThreads += it }, { _, _ -> }, {}, PaddingValues(0.dp), listState,
                            )
                            HiSnackbarHost(hostState = snackbarHostState, bottomClearance = 0.dp, modifier = Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
            waitForIdle()

            onNodeWithText("提醒").performClick()
            waitForIdle()
            onNodeWithText("全部已读").assertIsDisplayed().assertIsEnabled()
            onNodeWithText("第二篇引用帖子").assertIsDisplayed()
            assertEquals("进入提醒页绝不能自动清零未读", 3, notifyRepo.notifyUnreadCount.value)
            assertTrue(notifyRepo.markedSingleKeys.isEmpty())
            assertEquals(0, notifyRepo.markAllCalls)

            onNodeWithText("第二篇引用帖子").performClick()
            waitForIdle()
            assertEquals(listOf("k2"), notifyRepo.markedSingleKeys)
            assertEquals(2, notifyRepo.notifyUnreadCount.value)
            assertFalse(notifyRepo.notifications.value.first { it.key == "k1" }.isRead)
            assertTrue(notifyRepo.notifications.value.first { it.key == "k2" }.isRead)
            assertFalse(notifyRepo.notifications.value.first { it.key == "k3" }.isRead)
            assertEquals(1, openedThreads.size)
            assertEquals("2602293", openedThreads.single().tid)
            assertEquals("556677", openedThreads.single().targetPid)

            onNodeWithText("全部已读").performClick()
            waitForIdle()
            assertEquals(1, notifyRepo.markAllCalls)
            assertEquals(0, notifyRepo.notifyUnreadCount.value)
            assertTrue(notifyRepo.notifications.value.all { it.isRead })
            onNodeWithText("已全部标为已读").assertIsDisplayed()
            assertGlassSurfaceCountAtMost(0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun messagesUiModule_declaresSingletonBindsForMessagesPaneRenderer() {
        val moduleClass = MessagesUiModule::class.java
        assertNotNull(moduleClass.getAnnotation(Module::class.java))
        val method = moduleClass.getDeclaredMethod("bindMessagesPaneRenderer", MessagesPaneRendererImpl::class.java)
        assertNotNull(method.getAnnotation(Binds::class.java))
        assertNotNull(method.getAnnotation(Singleton::class.java))
        assertEquals(MessagesPaneRenderer::class.java, method.returnType)
    }

    private fun idleGateway(scope: CoroutineScope): SiteAccessGateway =
        SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, clock = System::currentTimeMillis, gatewayScope = scope)
}

internal class FakeSmsRepository(
    private val clock: () -> Long = System::currentTimeMillis,
    private val initialConversations: List<SmsConversationItem> = emptyList(),
    private val initialMessages: List<SmsMessageItem> = emptyList(),
    private val onLoadConversations: (suspend () -> MessageResult<SmsListPage>)? = null,
    private val onLoadDetail: (suspend (uid: String) -> MessageResult<SmsDetailPage>)? = null,
    private val onSend: suspend (uid: String?, username: String?, message: String) -> SmsSendOutcome = { _, _, _ -> SmsSendOutcome.Sent },
    private val onClear: suspend (uid: String) -> SmsClearOutcome = { SmsClearOutcome.Cleared },
) : SmsRepository {

    private val _cooldownUntilMs = MutableStateFlow(0L)
    override val cooldownUntilMs: StateFlow<Long> = _cooldownUntilMs.asStateFlow()
    val sendCallCount = AtomicInteger(0)

    fun armCooldown(durationMs: Long) {
        _cooldownUntilMs.value = clock() + durationMs
    }

    fun clearCooldown() {
        _cooldownUntilMs.value = 0L
    }

    override fun getRemainingCooldownSeconds(): Int {
        val rem = _cooldownUntilMs.value - clock()
        return if (rem <= 0L) 0 else ((rem + 999L) / 1_000L).toInt()
    }

    override suspend fun getConversations(unreadOnly: Boolean, priority: AccessPriority): MessageResult<SmsListPage> =
        onLoadConversations?.invoke() ?: MessageResult.Success(SmsListPage(initialConversations, fakePageMeta(), hasContainer = true))

    override suspend fun getConversationDetail(uid: String, priority: AccessPriority): MessageResult<SmsDetailPage> =
        onLoadDetail?.invoke(uid) ?: MessageResult.Success(SmsDetailPage("10000", "HiUser", "fh", initialMessages, fakePageMeta(), hasContainer = true))

    override suspend fun sendSms(uid: String?, username: String?, message: String, priority: AccessPriority): SmsSendOutcome {
        sendCallCount.incrementAndGet()
        val remaining = getRemainingCooldownSeconds()
        if (remaining > 0) return SmsSendOutcome.CooldownActive(remaining)
        val outcome = onSend(uid, username, message)
        if (outcome == SmsSendOutcome.Sent) armCooldown(DefaultSmsRepository.SMS_COOLDOWN_MS)
        return outcome
    }

    override suspend fun clearConversation(uid: String, priority: AccessPriority): SmsClearOutcome = onClear(uid)
}

internal class FakeNotifyRepository(
    initialEntries: List<NotifyEntry> = emptyList(),
    initialSmsUnread: Int = 0,
) : NotifyRepository {
    private val _smsUnread = MutableStateFlow(initialSmsUnread)
    private val _entries = MutableStateFlow(initialEntries)
    private val _notifyUnread = MutableStateFlow(initialEntries.count { !it.isRead })

    val markedSingleKeys = mutableListOf<String>()
    var markAllCalls: Int = 0

    override val smsUnreadCount: StateFlow<Int> = _smsUnread.asStateFlow()
    override val notifyUnreadCount: StateFlow<Int> = _notifyUnread.asStateFlow()
    override val notifications: StateFlow<List<NotifyEntry>> = _entries.asStateFlow()

    override suspend fun getNotifications(priority: AccessPriority): MessageResult<NotifyFeed> =
        MessageResult.Success(NotifyFeed(_entries.value, fakePageMeta(), hasFeedContainer = true))

    override suspend fun markNotificationRead(key: String) {
        markedSingleKeys += key
        val next = _entries.value.map { if (it.key == key) it.copy(isRead = true) else it }
        _entries.value = next
        _notifyUnread.value = next.count { !it.isRead }
    }

    override suspend fun markAllNotificationsRead() {
        markAllCalls++
        _entries.value = _entries.value.map { it.copy(isRead = true) }
        _notifyUnread.value = 0
    }

    override suspend fun pollUnreadCounts(priority: AccessPriority): NotificationPollResult = NotificationPollResult.Failed("unused")
    override fun updateFromPageMeta(promptCounts: PromptCounts) = Unit
    override fun updateSmsUnreadState(unreadConversationUids: Set<String>, unreadCount: Int) { _smsUnread.value = unreadCount }
    override fun markSmsConversationRead(uid: String, hadUnreadMessages: Boolean) { if (_smsUnread.value > 0) _smsUnread.value -= 1 }
}

internal class FakeAuthRepository(signedIn: Boolean = true, initialAccount: SavedAccount = SavedAccount("10000", "HiUser", "0".repeat(32), lastLoginAt = 100L)) : AuthRepository {
    private val _current = MutableStateFlow(if (signedIn) initialAccount else null)
    private val _saved = MutableStateFlow(if (signedIn) listOf(initialAccount) else emptyList())
    private val _authState = MutableStateFlow<AuthState>(if (signedIn) AuthState.SignedIn(initialAccount) else AuthState.SignedOut)

    override val currentAccount: StateFlow<SavedAccount?> = _current.asStateFlow()
    override val savedAccounts: StateFlow<List<SavedAccount>> = _saved.asStateFlow()
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()
    override val sessionEpoch: Long = 1L

    fun signIn(account: SavedAccount) {
        _current.value = account
        _saved.value = (_saved.value.filterNot { it.uid == account.uid } + account)
        _authState.value = AuthState.SignedIn(account)
    }

    fun signOut() {
        _current.value = null
        _authState.value = AuthState.SignedOut
    }

    override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String): LoginResult = LoginResult.Failure("unused")
    override suspend fun switchAccount(uid: String): LoginResult = LoginResult.Failure("unused")
    override suspend fun logout() = signOut()
    override suspend fun removeAccount(uid: String) = Unit
    override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority): SilentReloginResult = SilentReloginResult.NotLoggedIn
}

internal fun fakePageMeta(): DiscuzPageMeta =
    DiscuzPageMeta(DiscuzSessionInfo(true, "10000", "HiUser"), "fh", null, PromptCounts())
