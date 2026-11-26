package net.jejer.hipda.feature.messages

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dagger.Binds
import dagger.Module
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
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.message.MessageResult
import net.jejer.hipda.data.message.NotificationPollResult
import net.jejer.hipda.data.message.NotifyEntry
import net.jejer.hipda.data.message.NotifyFeed
import net.jejer.hipda.data.message.NotifyRepository
import net.jejer.hipda.data.message.SmsClearOutcome
import net.jejer.hipda.data.message.SmsRepository
import net.jejer.hipda.data.message.SmsSendOutcome
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.di.MessagesUiModule
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.MessagesPaneRenderer
import net.jejer.hipda.feature.common.SingleHostPaneSnackbarController
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.message.NotifyItem
import net.jejer.hipda.parser.message.NotifyKind
import net.jejer.hipda.parser.message.SmsConversationItem
import net.jejer.hipda.parser.message.SmsDetailPage
import net.jejer.hipda.parser.message.SmsListPage
import net.jejer.hipda.parser.message.SmsMessageItem
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
 * 直接通过 [MessagesPaneRendererImpl] 驱动，覆盖验收 1（乐观发送、失败重发/删除撤销、15 秒冷却、清空确认、未登录、零毛玻璃）、
 * 验收 2（单条已读 + `targetPid` 派发 + 独立 `全部已读` + Snackbar）以及 `MessagesUiModule` Hilt 绑定校验。
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
            initialMessages = listOf(
                SmsMessageItem("20000", "Alice", null, "10:00", "早安 :)", isNew = false, isMine = false),
            ),
            onSend = { _, _, _ -> sendGate.await() },
        )
        val notifyRepo = FakeNotifyRepository()
        val authRepo = FakeAuthRepository(signedIn = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val renderer: MessagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, notifyRepo, authRepo, idleGateway(scope))
            setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                        Box(Modifier.fillMaxSize()) {
                            renderer.ConversationContent(
                                args = SmsConversationArgs(uid = "20000", username = "Alice"),
                                showBack = true,
                                onBack = {},
                                onOpenUserProfile = { _, _ -> },
                                onOpenLogin = {},
                                contentPadding = PaddingValues(0.dp),
                            )
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

            // 2. 服务器确认成功 -> 进度圈消失，发送按钮切换为 15 秒倒计时且禁用
            smsRepo.armCooldown(15_000L)
            sendGate.complete(SmsSendOutcome.Sent)
            waitUntil { onAllNodes(androidx.compose.ui.test.hasContentDescription("15 秒后可发送")).fetchSemanticsNodes().isNotEmpty() }
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
            waitUntil { onAllNodes(androidx.compose.ui.test.hasContentDescription("发送")).fetchSemanticsNodes().isNotEmpty() }
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
            initialMessages = listOf(
                SmsMessageItem("20000", "Bob", null, "11:00", "历史消息", isNew = false, isMine = false),
            ),
            onSend = { _, _, _ ->
                sendAttempt++
                when (sendAttempt) {
                    1 -> SmsSendOutcome.Error("network")
                    2 -> SmsSendOutcome.Sent
                    else -> SmsSendOutcome.Rejected("服务器拒绝")
                }
            },
        )
        val notifyRepo = FakeNotifyRepository()
        val authRepo = FakeAuthRepository(signedIn = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val renderer: MessagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, notifyRepo, authRepo, idleGateway(scope))
            setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                        Box(Modifier.fillMaxSize()) {
                            renderer.ConversationContent(
                                args = SmsConversationArgs(uid = "20000", username = "Bob"),
                                showBack = true,
                                onBack = {},
                                onOpenUserProfile = { _, _ -> },
                                onOpenLogin = {},
                                contentPadding = PaddingValues(0.dp),
                            )
                            HiSnackbarHost(hostState = snackbarHostState, bottomClearance = 0.dp, modifier = Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
            waitForIdle()

            // 1. 首次发送失败 -> 气泡旁常驻红色感叹号“发送失败”
            onNode(hasSetTextAction()).performTextInput("需要重发的消息")
            onNodeWithContentDescription("发送").performClick()
            waitUntil { onAllNodes(androidx.compose.ui.test.hasContentDescription("发送失败")).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            onNodeWithText("需要重发的消息").assertIsDisplayed()
            onNodeWithContentDescription("发送失败").assertIsDisplayed()

            // 2. 点感叹号 -> 选“重发” -> 发送成功，感叹号消失
            onNodeWithContentDescription("发送失败").performClick()
            waitForIdle()
            onNodeWithText("重发").assertIsDisplayed().performClick()
            waitUntil { onAllNodes(androidx.compose.ui.test.hasContentDescription("发送失败")).fetchSemanticsNodes().isEmpty() }
            waitForIdle()
            assertEquals(2, sendAttempt)

            // 3. 再发一条失败消息 -> 点感叹号 -> 选“删除” -> 气泡消失且弹“已删除”+“撤销” -> 点“撤销”原位恢复
            onNode(hasSetTextAction()).performTextInput("将要删除再撤销的消息")
            onNodeWithContentDescription("发送").performClick()
            waitUntil { onAllNodes(androidx.compose.ui.test.hasContentDescription("发送失败")).fetchSemanticsNodes().isNotEmpty() }
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
    fun smsList_clearConfirmationDialogAndLoggedOutPromptAndSidebarMorphing() = withAnimatorDurationScale(0f) {
        val clearedUids = mutableListOf<String>()
        val smsRepo = FakeSmsRepository(
            initialConversations = listOf(
                SmsConversationItem("20001", "Alice", null, "刚刚", "晚上一起吃饭吗", isNew = true),
                SmsConversationItem("20002", "Bob", null, "昨天", "收到谢谢", isNew = false),
            ),
            onClear = { uid ->
                clearedUids += uid
                SmsClearOutcome.Cleared
            },
        )
        val notifyRepo = FakeNotifyRepository(initialSmsUnread = 1)
        val authRepo = FakeAuthRepository(signedIn = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var isSidebar by mutableStateOf(false)
        var loginClicks = 0
        try {
            val renderer: MessagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, notifyRepo, authRepo, idleGateway(scope))
            setContent {
                val listState = rememberLazyListState()
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    renderer.ListContent(
                        entry = ShellSidebarEntry.Sms,
                        isSidebar = isSidebar,
                        showBack = false,
                        onBack = null,
                        selectedConversationUid = null,
                        onSelectMessageEntry = {},
                        onOpenConversation = {},
                        onOpenThread = {},
                        onOpenUserProfile = { _, _ -> },
                        onOpenLogin = { loginClicks++ },
                        contentPadding = PaddingValues(0.dp),
                        listState = listState,
                    )
                }
            }
            waitForIdle()

            // 单栏显示双标签“短消息 | 提醒”且零毛玻璃
            onNodeWithText("消息").assertIsDisplayed()
            onNodeWithText("短消息").assertIsDisplayed()
            onNodeWithText("提醒").assertIsDisplayed()
            onNodeWithText("Alice").assertIsDisplayed()
            assertGlassSurfaceCountAtMost(0)

            // 宽屏侧栏态隐藏双标签行，直接显示标题“短消息”
            isSidebar = true
            waitForIdle()
            onNodeWithText("短消息").assertIsDisplayed()
            onNodeWithText("提醒").assertDoesNotExist()
            assertGlassSurfaceCountAtMost(0)

            // 清空会话二次确认框：先取消不删除，再确认删除
            onAllNodes(androidx.compose.ui.test.hasContentDescription("更多"))[0].performClick()
            waitForIdle()
            onNodeWithText("清空").assertIsDisplayed().performClick()
            waitForIdle()
            onNodeWithText("清空与 Alice 的全部短消息？").assertIsDisplayed()
            onNodeWithText("清空后无法恢复。").assertIsDisplayed()
            onNodeWithText("取消").assertIsDisplayed().performClick()
            waitForIdle()
            assertTrue(clearedUids.isEmpty())
            onNodeWithText("Alice").assertIsDisplayed()

            onAllNodes(androidx.compose.ui.test.hasContentDescription("更多"))[0].performClick()
            waitForIdle()
            onNodeWithText("清空").performClick()
            waitForIdle()
            onNodeWithText("清空").performClick()
            waitForIdle()
            assertEquals(listOf("20001"), clearedUids)
            onNodeWithText("Alice").assertDoesNotExist()

            // 退出登录 -> 原地显示“需要登录才能查看” + “登录”按钮
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
    fun notifyList_doesNotAutoClearOnEntry_marksSingleReadWithTargetPid_andMarkAllReadShowsSnackbar() =
        withAnimatorDurationScale(0f) {
            val entries = listOf(
                NotifyEntry(
                    key = "k1",
                    item = NotifyItem(NotifyKind.THREAD, "第一篇提醒帖子", "Alice 回复了您的帖子", "Alice", "20001", null, "1001", "5001", "10 分钟前", true),
                    isRead = false,
                ),
                NotifyEntry(
                    key = "k2",
                    item = NotifyItem(NotifyKind.QUOTE, "第二篇引用帖子", "", "Bob", "20002", null, "2602293", "556677", "5 分钟前", true, "原帖内容", "同意楼上观点"),
                    isRead = false,
                ),
                NotifyEntry(
                    key = "k3",
                    item = NotifyItem(NotifyKind.SYSTEM, "系统信息", "您的帖子已被加精", null, null, null, null, null, "刚刚", true),
                    isRead = false,
                ),
            )
            val smsRepo = FakeSmsRepository()
            val notifyRepo = FakeNotifyRepository(initialEntries = entries)
            val authRepo = FakeAuthRepository(signedIn = true)
            val openedThreads = mutableListOf<ThreadNavArgs>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val renderer: MessagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, notifyRepo, authRepo, idleGateway(scope))
                setContent {
                    var currentEntry by remember { mutableStateOf<ShellSidebarEntry>(ShellSidebarEntry.Sms) }
                    val snackbarHostState = remember { SnackbarHostState() }
                    val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                    val listState = rememberLazyListState()
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                            Box(Modifier.fillMaxSize()) {
                                renderer.ListContent(
                                    entry = currentEntry,
                                    isSidebar = false,
                                    showBack = false,
                                    onBack = null,
                                    selectedConversationUid = null,
                                    onSelectMessageEntry = { currentEntry = it },
                                    onOpenConversation = {},
                                    onOpenThread = { openedThreads += it },
                                    onOpenUserProfile = { _, _ -> },
                                    onOpenLogin = {},
                                    contentPadding = PaddingValues(0.dp),
                                    listState = listState,
                                )
                                HiSnackbarHost(hostState = snackbarHostState, bottomClearance = 0.dp, modifier = Modifier.align(Alignment.BottomCenter))
                            }
                        }
                    }
                }
                waitForIdle()

                // 切到“提醒”标签 -> 绝不进入页面就全部清零
                onNodeWithText("提醒").performClick()
                waitForIdle()
                onNodeWithText("全部已读").assertIsDisplayed().assertIsEnabled()
                onNodeWithText("第二篇引用帖子").assertIsDisplayed()
                assertEquals("进入提醒页绝不能自动清零未读", 3, notifyRepo.notifyUnreadCount.value)
                assertTrue(notifyRepo.markedSingleKeys.isEmpty())
                assertEquals(0, notifyRepo.markAllCalls)

                // 点击第 2 条提醒 -> 仅标该条已读并正确派发带 targetPid 的 onOpenThread
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

                // 点击顶栏“全部已读” -> 标全已读并弹“已全部标为已读” Snackbar
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
    private val onSend: suspend (uid: String?, username: String?, message: String) -> SmsSendOutcome = { _, _, _ -> SmsSendOutcome.Sent },
    private val onClear: suspend (uid: String) -> SmsClearOutcome = { SmsClearOutcome.Cleared },
) : SmsRepository {

    private val _cooldownUntilMs = MutableStateFlow(0L)
    override val cooldownUntilMs: StateFlow<Long> = _cooldownUntilMs.asStateFlow()

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
        MessageResult.Success(SmsListPage(initialConversations, fakePageMeta(), hasContainer = true))

    override suspend fun getConversationDetail(uid: String, priority: AccessPriority): MessageResult<SmsDetailPage> =
        MessageResult.Success(SmsDetailPage("10000", "HiUser", "fh", initialMessages, fakePageMeta(), hasContainer = true))

    override suspend fun sendSms(uid: String?, username: String?, message: String, priority: AccessPriority): SmsSendOutcome =
        onSend(uid, username, message)

    override suspend fun clearConversation(uid: String, priority: AccessPriority): SmsClearOutcome =
        onClear(uid)
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

    override suspend fun pollUnreadCounts(priority: AccessPriority): NotificationPollResult =
        NotificationPollResult.Failed("unused")

    override fun updateFromPageMeta(promptCounts: PromptCounts) = Unit
    override fun updateSmsUnreadState(unreadConversationUids: Set<String>, unreadCount: Int) { _smsUnread.value = unreadCount }
    override fun markSmsConversationRead(uid: String, hadUnreadMessages: Boolean) {
        if (_smsUnread.value > 0) _smsUnread.value -= 1
    }
}

internal class FakeAuthRepository(signedIn: Boolean = true) : AuthRepository {
    private val account = SavedAccount("10000", "HiUser", "0".repeat(32), lastLoginAt = 100L)
    private val _current = MutableStateFlow(if (signedIn) account else null)
    private val _saved = MutableStateFlow(if (signedIn) listOf(account) else emptyList())
    private val _authState = MutableStateFlow<AuthState>(if (signedIn) AuthState.SignedIn(account) else AuthState.SignedOut)

    override val currentAccount: StateFlow<SavedAccount?> = _current.asStateFlow()
    override val savedAccounts: StateFlow<List<SavedAccount>> = _saved.asStateFlow()
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()
    override val sessionEpoch: Long = 1L

    fun signOut() {
        _current.value = null
        _authState.value = AuthState.SignedOut
    }

    override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String): LoginResult =
        LoginResult.Failure("unused")

    override suspend fun switchAccount(uid: String): LoginResult = LoginResult.Failure("unused")
    override suspend fun logout() = signOut()
    override suspend fun removeAccount(uid: String) = Unit
    override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority): SilentReloginResult =
        SilentReloginResult.NotLoggedIn
}

private fun fakePageMeta(): DiscuzPageMeta =
    DiscuzPageMeta(DiscuzSessionInfo(true, "10000", "HiUser"), "fh", null, PromptCounts())
