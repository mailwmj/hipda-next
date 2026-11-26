package net.jejer.hipda.feature.messages

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.jejer.hipda.R
import net.jejer.hipda.data.message.NotifyEntry
import net.jejer.hipda.data.message.SmsSendOutcome
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.compose.emoji.EmojiPanelRendererImpl
import net.jejer.hipda.parser.message.NotifyItem
import net.jejer.hipda.parser.message.NotifyKind
import net.jejer.hipda.parser.message.SmsConversationItem
import net.jejer.hipda.parser.message.SmsMessageItem
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.designsystem.state.HiEmptyState
import net.jejer.hipda.ui.shell.ForumMetadataRegistry
import net.jejer.hipda.ui.shell.HiAdaptiveShell
import net.jejer.hipda.ui.shell.HiSidebarAccount
import net.jejer.hipda.ui.shell.HiSidebarState
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E8 短消息与提醒 Roborazzi 截图基线（前缀 `messages_`，共 12 张，对齐 `E8.md` 验收 4）：
 * - 短消息列表 `411 / 749 / 1280dp` × 浅/深（6 张）
 * - 短消息对话 `411dp` × 浅/深（2 张，含表情、安全链接、失败红感叹号与冷却倒计时）
 * - 提醒列表 `411dp` × 浅/深（2 张，含未读/已读区分、引用块与顶栏 `全部已读`）
 * - 空态 `411dp` 浅色与未登录/错态 `933dp` 三栏深色（2 张）
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class MessagesScreenshotTest {

    // ---- 短消息列表 411 / 749 / 1280dp × 浅/深（6 张） ----

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun messages_sms_list_w411_light() = captureMessagesList(
        name = "messages_sms_list_w411_light",
        themeMode = HiThemeMode.LIGHT,
        entry = ShellSidebarEntry.Sms,
        inAdaptiveShell = false,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun messages_sms_list_w411_dark() = captureMessagesList(
        name = "messages_sms_list_w411_dark",
        themeMode = HiThemeMode.DARK,
        entry = ShellSidebarEntry.Sms,
        inAdaptiveShell = false,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun messages_sms_list_w749_light() = captureMessagesList(
        name = "messages_sms_list_w749_light",
        themeMode = HiThemeMode.LIGHT,
        entry = ShellSidebarEntry.Sms,
        inAdaptiveShell = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun messages_sms_list_w749_dark() = captureMessagesList(
        name = "messages_sms_list_w749_dark",
        themeMode = HiThemeMode.DARK,
        entry = ShellSidebarEntry.Sms,
        inAdaptiveShell = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun messages_sms_list_w1280_light() = captureMessagesList(
        name = "messages_sms_list_w1280_light",
        themeMode = HiThemeMode.LIGHT,
        entry = ShellSidebarEntry.Sms,
        inAdaptiveShell = true,
        selectedConversation = SmsConversationArgs(uid = "20001", username = "linus_cn"),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun messages_sms_list_w1280_dark() = captureMessagesList(
        name = "messages_sms_list_w1280_dark",
        themeMode = HiThemeMode.DARK,
        entry = ShellSidebarEntry.Sms,
        inAdaptiveShell = true,
        selectedConversation = SmsConversationArgs(uid = "20001", username = "linus_cn"),
    )

    // ---- 短消息对话 411dp × 浅/深（2 张） ----

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun messages_conversation_w411_light() = captureConversation("messages_conversation_w411_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun messages_conversation_w411_dark() = captureConversation("messages_conversation_w411_dark", HiThemeMode.DARK)

    // ---- 提醒列表 411dp × 浅/深（2 张） ----

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun messages_notify_w411_light() = captureMessagesList(
        name = "messages_notify_w411_light",
        themeMode = HiThemeMode.LIGHT,
        entry = ShellSidebarEntry.Notify,
        inAdaptiveShell = false,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun messages_notify_w411_dark() = captureMessagesList(
        name = "messages_notify_w411_dark",
        themeMode = HiThemeMode.DARK,
        entry = ShellSidebarEntry.Notify,
        inAdaptiveShell = false,
    )

    // ---- 空态 411dp 浅色与未登录/错态 933dp 深色（2 张） ----

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun messages_empty_w411_light() = captureMessagesList(
        name = "messages_empty_w411_light",
        themeMode = HiThemeMode.LIGHT,
        entry = ShellSidebarEntry.Sms,
        inAdaptiveShell = false,
        emptyList = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun messages_error_w933_dark() = captureMessagesList(
        name = "messages_error_w933_dark",
        themeMode = HiThemeMode.DARK,
        entry = ShellSidebarEntry.Notify,
        inAdaptiveShell = true,
        signedIn = false,
    )

    private fun captureMessagesList(
        name: String,
        themeMode: HiThemeMode,
        entry: ShellSidebarEntry,
        inAdaptiveShell: Boolean,
        emptyList: Boolean = false,
        signedIn: Boolean = true,
        selectedConversation: SmsConversationArgs? = null,
    ) = withAnimatorDurationScale(0f) {
        val offlineLoader = ImageLoader.Builder(ApplicationProvider.getApplicationContext()).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val smsRepo = FakeSmsRepository(
            initialConversations = if (emptyList) emptyList() else sampleConversations(),
            initialMessages = sampleMessages(),
        )
        val notifyRepo = FakeNotifyRepository(
            initialEntries = if (emptyList) emptyList() else sampleNotifications(),
            initialSmsUnread = if (emptyList || !signedIn) 0 else 2,
        )
        val authRepo = FakeAuthRepository(signedIn = signedIn)
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, clock = System::currentTimeMillis, gatewayScope = scope)
        val renderer = MessagesPaneRendererImpl(smsRepo, notifyRepo, authRepo, gateway, Optional.of(EmojiPanelRendererImpl()))

        try {
            setContent {
                HiTheme(themeMode = themeMode, seed = HiColorPresets.Brand.toSeed()) {
                    CompositionLocalProvider(LocalHiImageLoader provides offlineLoader) {
                        if (inAdaptiveShell) {
                            HiAdaptiveShell(
                                selected = ShellDestination.Messages,
                                onSelect = {},
                                sidebarState = HiSidebarState(
                                    selectedEntry = entry,
                                    followedForumIds = ForumMetadataRegistry.DefaultFollowedForumIds,
                                    unreadSmsCount = if (signedIn && !emptyList) 2 else 0,
                                    unreadNotifyCount = if (signedIn && !emptyList) 2 else 0,
                                    account = HiSidebarAccount(username = if (signedIn) "HiUser" else null),
                                ),
                                managesGlassSource = { true },
                            ) { _, padding ->
                                val listState = rememberLazyListState()
                                if (paneLayout.hasDetailPane) {
                                    Row(Modifier.fillMaxSize()) {
                                        Box(Modifier.width(paneLayout.middlePaneWidthDp.dp).fillMaxHeight()) {
                                            renderer.ListContent(
                                                entry = entry,
                                                isSidebar = paneLayout.isSidebar,
                                                showBack = false,
                                                onBack = null,
                                                selectedConversationUid = selectedConversation?.uid,
                                                onSelectMessageEntry = {},
                                                onOpenConversation = {},
                                                onOpenThread = {},
                                                onOpenUserProfile = { _, _ -> },
                                                onOpenLogin = {},
                                                contentPadding = padding,
                                                listState = listState,
                                            )
                                        }
                                        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                            if (selectedConversation != null) {
                                                renderer.ConversationContent(
                                                    args = selectedConversation,
                                                    showBack = false,
                                                    onBack = {},
                                                    onOpenUserProfile = { _, _ -> },
                                                    onOpenLogin = {},
                                                    contentPadding = padding,
                                                )
                                            } else {
                                                val placeholderRes = if (entry == ShellSidebarEntry.Notify) {
                                                    R.string.shell_empty_select_notify
                                                } else {
                                                    R.string.shell_empty_select_sms
                                                }
                                                HiEmptyState(stringResource(placeholderRes))
                                            }
                                        }
                                    }
                                } else {
                                    renderer.ListContent(
                                        entry = entry,
                                        isSidebar = paneLayout.isSidebar,
                                        showBack = false,
                                        onBack = null,
                                        selectedConversationUid = selectedConversation?.uid,
                                        onSelectMessageEntry = {},
                                        onOpenConversation = {},
                                        onOpenThread = {},
                                        onOpenUserProfile = { _, _ -> },
                                        onOpenLogin = {},
                                        contentPadding = padding,
                                        listState = listState,
                                    )
                                }
                            }
                        } else {
                            val listState = rememberLazyListState()
                            renderer.ListContent(
                                entry = entry,
                                isSidebar = false,
                                showBack = false,
                                onBack = null,
                                selectedConversationUid = null,
                                onSelectMessageEntry = {},
                                onOpenConversation = {},
                                onOpenThread = {},
                                onOpenUserProfile = { _, _ -> },
                                onOpenLogin = {},
                                contentPadding = PaddingValues(0.dp),
                                listState = listState,
                            )
                        }
                    }
                }
            }
            waitForIdle()
            assertGlassSurfaceCountAtMost(0)
            onRoot().captureHiSnapshot(name)
        } finally {
            scope.cancel()
            offlineLoader.shutdown()
        }
    }

    private fun captureConversation(name: String, themeMode: HiThemeMode) = withAnimatorDurationScale(0f) {
        val offlineLoader = ImageLoader.Builder(ApplicationProvider.getApplicationContext()).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = AtomicLong(1_700_000_000_000L)
        val smsRepo = FakeSmsRepository(
            clock = { clock.get() },
            initialMessages = sampleMessages(),
            onSend = { _, _, _ -> SmsSendOutcome.Error("offline") },
        )
        val authRepo = FakeAuthRepository(signedIn = true)
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, clock = System::currentTimeMillis, gatewayScope = scope)
        val vm = SmsConversationViewModel(smsRepo, authRepo, gateway) { clock.get() }
        vm.bindConversation(SmsConversationArgs(uid = "20001", username = "linus_cn"))

        try {
            setContent {
                HiTheme(themeMode = themeMode, seed = HiColorPresets.Brand.toSeed()) {
                    CompositionLocalProvider(LocalHiImageLoader provides offlineLoader) {
                        SmsConversationScreen(
                            args = SmsConversationArgs(uid = "20001", username = "linus_cn"),
                            showBack = true,
                            onBack = {},
                            onOpenUserProfile = { _, _ -> },
                            onOpenLogin = {},
                            contentPadding = PaddingValues(0.dp),
                            viewModel = vm,
                            emojiPanelSlot = { host -> EmojiPanelRendererImpl().Content(host, Modifier) },
                        )
                    }
                }
            }
            waitForIdle()
            // 叠加一条失败气泡与冷却态，便于目视验收气泡排版、红感叹号与倒计时按钮
            vm.onInputChange("这是一条发送失败待重发的消息")
            vm.sendMessage()
            waitForIdle()
            smsRepo.armCooldown(12_000L)
            vm.onInputChange("冷却中继续编辑下一句")
            waitForIdle()

            assertGlassSurfaceCountAtMost(0)
            onRoot().captureHiSnapshot(name)
        } finally {
            scope.cancel()
            offlineLoader.shutdown()
        }
    }

    private fun sampleConversations(): List<SmsConversationItem> = listOf(
        SmsConversationItem("20001", "linus_cn", null, "刚刚", "折叠屏三栏排版已核对完毕，周末有空一起聊聊", isNew = true),
        SmsConversationItem("20002", "coffee_note", null, "18分钟前", "耶加雪菲浅烘豆的参数表格发你了", isNew = true),
        SmsConversationItem("20003", "shutter_bug", null, "昨天", "好的，镜头转接环下周寄给你", isNew = false),
        SmsConversationItem("20004", "nas_builder", null, "前天", "静音风扇实测满载 28 分贝，很满意", isNew = false),
        SmsConversationItem("20005", "eink_reader", null, "11-22", "书单收到了，多谢推荐", isNew = false),
    )

    private fun sampleMessages(): List<SmsMessageItem> = listOf(
        SmsMessageItem("20001", "linus_cn", null, "10:15", "你好！上次聊的机器到货了吗 :) <img src=\"images/smilies/coolmonkey/01.gif\" />", isNew = false, isMine = false),
        SmsMessageItem("10000", "HiUser", null, "10:18", "昨天刚签收，屏幕比例很舒服 {:3_57:}", isNew = false, isMine = true),
        SmsMessageItem("20001", "linus_cn", null, "10:20", "太好了，评测帖发在 <a href=\"https://www.4d4y.com/forum/viewthread.php?tid=2602293\">Discovery 主帖</a> 了", isNew = false, isMine = false),
        SmsMessageItem("10000", "HiUser", null, "10:22", "收到，这就去看看", isNew = false, isMine = true),
    )

    private fun sampleNotifications(): List<NotifyEntry> = listOf(
        NotifyEntry(
            key = "n1",
            item = NotifyItem(NotifyKind.QUOTE, "Galaxy Z Fold8 展开态三栏阅读排版实测", "", "linus_cn", "20001", null, "2602293", "9001", "5分钟前", true, "紧凑三栏下右栏保底 360dp", "完全同意，实测 411dp 右栏非常从容"),
            isRead = false,
        ),
        NotifyEntry(
            key = "n2",
            item = NotifyItem(NotifyKind.THREAD, "秋日山脊徒步实拍：富士直出色彩分享", "shutter_bug 回复了您的帖子", "shutter_bug", "20003", null, "2602294", "9002", "25分钟前", true),
            isRead = false,
        ),
        NotifyEntry(
            key = "n3",
            item = NotifyItem(NotifyKind.REPLY, "低功耗 HomeLab 静音机箱风道改造", "nas_builder 回复了您在楼层中的发言", "nas_builder", "20004", null, "2602295", "9003", "2小时前", false),
            isRead = true,
        ),
        NotifyEntry(
            key = "n4",
            item = NotifyItem(NotifyKind.SYSTEM, "系统信息", "您发布的帖子已被版主加入精华", null, null, null, "2602293", null, "昨天", false),
            isRead = true,
        ),
    )
}
