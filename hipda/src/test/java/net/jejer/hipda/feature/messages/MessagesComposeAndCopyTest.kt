package net.jejer.hipda.feature.messages

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.message.MessageResult
import net.jejer.hipda.data.message.SmsSendOutcome
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.parser.message.SmsConversationItem
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M7-G4 撤回即红行为测试：
 * - #2 短消息列表顶栏新建按钮（仅 Sms 显示、Notify 不显示）、未登录提示、空校验、0ms 进度圈、失败留窗保输入重试、成功关窗刷新、15 秒冷却倒计时与可编辑、切号隔离；
 * - #10 短消息对话气泡长按复制纯文本（HTML 剥离 + 表情转回规范代码 + 本地乐观纯文本 + 失败气泡 + 空内容安全）与链接点击 / 失败 `!` 菜单零冲突。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = Application::class,
    sdk = [34],
    qualifiers = FeatureScreenshotQualifiers.PHONE_411_891,
)
class MessagesComposeAndCopyTest {

    @Test
    fun newSmsTopBarButton_shownOnlyOnSmsTabAndNotOnNotify_andPromptsLoginWhenSignedOut() = withAnimatorDurationScale(0f) {
        val authRepo = FakeAuthRepository(signedIn = false)
        val smsRepo = FakeSmsRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var loginClicks = 0
        try {
            val renderer: MessagesPaneRenderer = MessagesPaneRendererImpl(smsRepo, FakeNotifyRepository(), authRepo, idleGateway(scope))
            setContent {
                var activeEntry by remember { mutableStateOf<ShellSidebarEntry>(ShellSidebarEntry.Sms) }
                val snackbarHostState = remember { SnackbarHostState() }
                val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                val listState = rememberLazyListState()
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                        Box(Modifier.fillMaxSize()) {
                            renderer.ListContent(
                                entry = activeEntry,
                                isSidebar = false,
                                showBack = false,
                                onBack = null,
                                selectedConversationUid = null,
                                onSelectMessageEntry = { activeEntry = it },
                                onOpenConversation = {},
                                onOpenThread = {},
                                onOpenUserProfile = { _, _ -> },
                                onOpenLogin = { loginClicks++ },
                                contentPadding = PaddingValues(0.dp),
                                listState = listState,
                            )
                            HiSnackbarHost(hostState = snackbarHostState, bottomClearance = 0.dp, modifier = Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
            waitForIdle()

            // Sms 列表顶栏显示 48×48dp「发送短消息」按钮
            onNodeWithContentDescription("发送短消息")
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
            waitForIdle()

            // 未登录点击不弹对话框，原地提示「登录后即可操作」并提供「登录」按钮
            onNodeWithText("收件人").assertDoesNotExist()
            onNodeWithText("登录后即可操作").assertIsDisplayed()
            onAllNodesWithText("登录").onLast().performClick()
            waitForIdle()
            assertEquals(1, loginClicks)
            assertEquals(0, smsRepo.sendCallCount.get())

            // 切到「提醒」标签：顶栏仅显示「全部已读」，绝不显示「发送短消息」按钮
            onNodeWithText("提醒").performClick()
            waitForIdle()
            onNodeWithContentDescription("发送短消息").assertDoesNotExist()
            onNodeWithText("全部已读").assertIsDisplayed()
            assertGlassSurfaceCountAtMost(0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun newSmsDialog_emptyValidationFailurePreservesInput_0msSpinner_successRefreshesAndCooldownBlocksResend() =
        withAnimatorDurationScale(0f) {
            val clock = AtomicLong(1_700_000_000_000L)
            val sentCalls = mutableListOf<Triple<String?, String?, String>>()
            val secondSendGate = CompletableDeferred<SmsSendOutcome>()
            var sendAttempt = 0
            var conversations = listOf(
                SmsConversationItem("20001", "Alice", null, "昨天", "老会话", isNew = false),
            )
            val smsRepo = FakeSmsRepository(
                clock = { clock.get() },
                onLoadConversations = {
                    MessageResult.Success(SmsListPage(conversations, fakePageMeta(), hasContainer = true))
                },
                onSend = { uid, username, message ->
                    sentCalls += Triple(uid, username, message)
                    sendAttempt++
                    when (sendAttempt) {
                        1 -> SmsSendOutcome.Rejected("您指定的收件人不存在")
                        2 -> {
                            val outcome = secondSendGate.await()
                            if (outcome == SmsSendOutcome.Sent) {
                                conversations = listOf(
                                    SmsConversationItem("20099", "任意坛友", null, "刚刚", "你好，测试新对话", isNew = false),
                                ) + conversations
                            }
                            outcome
                        }
                        else -> SmsSendOutcome.Error("network", IOException("offline"))
                    }
                },
            )
            val authRepo = FakeAuthRepository(signedIn = true)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val vm = MessagesListViewModel(smsRepo, FakeNotifyRepository(), authRepo, idleGateway(scope)) { clock.get() }
            try {
                setContent {
                    val snackbarHostState = remember { SnackbarHostState() }
                    val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                    val listState = rememberLazyListState()
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                            Box(Modifier.fillMaxSize()) {
                                MessagesListScreen(
                                    entry = ShellSidebarEntry.Sms,
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
                                    viewModel = vm,
                                )
                                HiSnackbarHost(hostState = snackbarHostState, bottomClearance = 0.dp, modifier = Modifier.align(Alignment.BottomCenter))
                            }
                        }
                    }
                }
                waitForIdle()
                onNodeWithText("Alice").assertIsDisplayed()

                // 1. 点击顶栏新建按钮弹出对话框
                onNodeWithContentDescription("发送短消息").performClick()
                waitForIdle()
                onNodeWithText("发送短消息").assertIsDisplayed()
                onNodeWithText("收件人").assertIsDisplayed()
                onNodeWithText("内容").assertIsDisplayed()

                // 2. 空值校验：双空 / 仅填收件人 / 仅填内容 均原地提示并阻止网络调用
                onNodeWithText("发送").performClick()
                waitForIdle()
                onNodeWithText("请输入收件人").assertIsDisplayed()
                onNodeWithText("请输入内容").assertIsDisplayed()
                assertEquals(0, smsRepo.sendCallCount.get())

                onNode(hasSetTextAction() and hasText("收件人", substring = true)).performTextInput("   ")
                onNodeWithText("发送").performClick()
                waitForIdle()
                onNodeWithText("请输入收件人").assertIsDisplayed()
                assertEquals(0, smsRepo.sendCallCount.get())

                // 3. 输入任意用户名与内容 -> 首次发送被服务端拒绝 -> 留在本对话框、保留已填输入并原地显示错误原因
                onNode(hasSetTextAction() and hasText("收件人", substring = true)).performTextClearance()
                onNode(hasSetTextAction() and hasText("收件人", substring = true)).performTextInput("任意坛友")
                onNode(hasSetTextAction() and hasText("内容", substring = true)).performTextInput("你好，测试新对话")
                onNodeWithText("发送").performClick()
                waitUntil { onAllNodes(hasText("您指定的收件人不存在")).fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()

                assertEquals(listOf(Triple(null, "任意坛友", "你好，测试新对话")), sentCalls)
                onNodeWithText("任意坛友").assertIsDisplayed()
                onNodeWithText("你好，测试新对话").assertIsDisplayed()
                onNodeWithText("您指定的收件人不存在").assertIsDisplayed()

                // 4. 直接重试发送 -> 0ms 按钮内切进度圈「正在发送…」并留在本对话框
                onNodeWithText("发送").performClick()
                waitForIdle()
                onNodeWithContentDescription("正在发送…").assertIsDisplayed().assertIsNotEnabled()
                onNodeWithText("发送短消息").assertIsDisplayed()

                // 5. 第二次发送成功 -> 关闭对话框、刷新会话列表展示新会话，并激活 15 秒冷却
                secondSendGate.complete(SmsSendOutcome.Sent)
                waitUntil { onAllNodes(hasText("任意坛友")).fetchSemanticsNodes().isNotEmpty() && onAllNodes(hasText("收件人")).fetchSemanticsNodes().isEmpty() }
                waitForIdle()
                assertEquals(2, smsRepo.sendCallCount.get())
                onNodeWithText("收件人").assertDoesNotExist()
                onNodeWithText("任意坛友").assertIsDisplayed()
                onNodeWithText("你好，测试新对话").assertIsDisplayed()

                // 6. 15 秒冷却中重新打开对话框：发送按钮原地显示倒计时且禁用，允许继续编辑收件人与内容
                onNodeWithContentDescription("发送短消息").performClick()
                waitForIdle()
                onNodeWithContentDescription("15 秒后可发送").assertIsDisplayed().assertIsNotEnabled()
                onNode(hasSetTextAction() and hasText("收件人", substring = true)).performTextInput("冷却中编辑")
                onNode(hasSetTextAction() and hasText("内容", substring = true)).performTextInput("等待冷却结束")
                waitForIdle()
                onNodeWithText("冷却中编辑").assertIsDisplayed()
                onNodeWithText("等待冷却结束").assertIsDisplayed()
                // 直接调用 ViewModel sendNewSms 同样被前置拦截，0 次额外仓库调用
                runOnIdle { vm.sendNewSms() }
                waitForIdle()
                assertEquals(2, smsRepo.sendCallCount.get())

                // 7. 冷却结束后恢复「发送」按钮可用
                clock.addAndGet(15_000L)
                smsRepo.clearCooldown()
                waitUntil { onAllNodes(hasText("发送")).fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                onNodeWithText("发送").assertIsDisplayed().assertIsEnabled()

                // 8. 切号立即关闭对话框并清空草稿（防跨账号泄漏）
                authRepo.signIn(SavedAccount("10002", "OtherUser", "b".repeat(32), lastLoginAt = 200L))
                waitForIdle()
                onNodeWithText("收件人").assertDoesNotExist()
                assertEquals("", vm.uiState.value.compose.recipient)
                assertEquals("", vm.uiState.value.compose.body)
                assertGlassSurfaceCountAtMost(0)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun extractSmsBubblePlainText_stripsHtmlConvertsEmojisToCodesAndHandlesEmptySafely() {
        val html = """
            <p>第一行 <b>加粗</b> &amp;#128512; <img src="images/smilies/default/lol.gif" /></p>
            <p>酷猴 <img src="images/smilies/coolmonkey/01.gif" /> 与呆呆 <img src="images/smilies/grapeman/08.gif" /></p>
            <div>看 <a href="https://www.4d4y.com/forum/viewthread.php?tid=1">主帖链接</a> <a href="javascript:alert(1)">纯标签</a></div>
            <script>alert('xss')</script>
        """.trimIndent()
        val extracted = extractSmsBubblePlainText(html, isPlainText = false)
        assertFalse("不得残留 HTML 标签：$extracted", extracted.contains("<") || extracted.contains(">"))
        assertFalse("不得残留 script 内容：$extracted", extracted.contains("xss"))
        assertFalse("不得泄露 javascript: 协议：$extracted", extracted.contains("javascript:"))
        assertTrue("应包含解码后正文与补充平面表情：$extracted", extracted.contains("第一行 加粗 😀 :lol:"))
        assertTrue("酷猴表情应转回规范代码 {:2_41:}：$extracted", extracted.contains("{:2_41:}"))
        assertTrue("呆呆表情应转回规范代码 {:3_64:}：$extracted", extracted.contains("{:3_64:}"))
        assertTrue("链接应提取可见文本：$extracted", extracted.contains("看 主帖链接 纯标签"))

        assertEquals("本地纯文本 :)\n第二行", extractSmsBubblePlainText("本地纯文本 :)\n第二行", isPlainText = true))
        assertEquals("", extractSmsBubblePlainText("", isPlainText = false))
        assertEquals("", extractSmsBubblePlainText("   <br/>  ", isPlainText = false))
        assertEquals("", extractSmsBubblePlainText("<img src=\"https://img.example.invalid/only.jpg\" />", isPlainText = false))
    }

    @Test
    fun smsConversation_bubbleLongPressCopy_htmlPlainFailedEmpty_andPreservesLinksAndFailedMenu() =
        withAnimatorDurationScale(0f) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val clipboard = context.getSystemService(ClipboardManager::class.java)!!
            clipboard.setPrimaryClip(ClipData.newPlainText("init", "INITIAL_CLIP"))

            val clock = AtomicLong(1_700_000_000_000L)
            val openedUris = mutableListOf<String>()
            val haptics = mutableListOf<HapticFeedbackType>()
            val snackbarEvents = mutableListOf<Pair<String, PaneSnackbarTarget>>()
            val fakeSnackbar = object : PaneSnackbarController {
                override suspend fun show(
                    message: String,
                    target: PaneSnackbarTarget,
                    actionLabel: String?,
                    onAction: (() -> Unit)?,
                ) {
                    snackbarEvents += message to target
                }
            }
            var sendCount = 0
            val smsRepo = FakeSmsRepository(
                clock = { clock.get() },
                initialMessages = listOf(
                    SmsMessageItem(
                        uid = "20001",
                        author = "Alice",
                        avatarUrl = null,
                        time = "10:01",
                        contentHtml = "<a href=\"https://www.4d4y.com/forum/viewthread.php?tid=2602293\">安全帖子链接</a> <b>HTML气泡</b> <img src=\"images/smilies/coolmonkey/01.gif\" />",
                        isNew = false,
                        isMine = false,
                    ),
                    SmsMessageItem(
                        uid = "20001",
                        author = "Alice",
                        avatarUrl = null,
                        time = "10:02",
                        contentHtml = "   <br/>  ",
                        isNew = false,
                        isMine = false,
                    ),
                ),
                onSend = { _, _, msg ->
                    sendCount++
                    if (msg.startsWith("失败气泡")) SmsSendOutcome.Error("offline") else SmsSendOutcome.Sent
                },
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val vm = SmsConversationViewModel(smsRepo, FakeAuthRepository(signedIn = true), idleGateway(scope)) { clock.get() }
            try {
                setContent {
                    CompositionLocalProvider(
                        LocalPaneSnackbarController provides fakeSnackbar,
                        LocalHapticFeedback provides object : HapticFeedback {
                            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                                haptics += hapticFeedbackType
                            }
                        },
                        LocalUriHandler provides object : UriHandler {
                            override fun openUri(uri: String) {
                                openedUris += uri
                            }
                        },
                    ) {
                        HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                            SmsConversationScreen(
                                args = SmsConversationArgs("20001", "Alice"),
                                showBack = true,
                                onBack = {},
                                onOpenUserProfile = { _, _ -> },
                                onOpenLogin = {},
                                contentPadding = PaddingValues(0.dp),
                                viewModel = vm,
                            )
                        }
                    }
                }
                waitForIdle()

                // 1. 先验证原气泡内链接单击仍然正常打开，与长按复制零冲突
                onNodeWithText("安全帖子链接", substring = true)
                    .assertIsDisplayed()
                    .performFirstLinkClick()
                waitForIdle()
                assertEquals(listOf("https://www.4d4y.com/forum/viewthread.php?tid=2602293"), openedUris)

                // 2. 长按已送达 HTML 气泡 -> 触感反馈 + 弹「复制」-> 写入严格 ClipData.newPlainText 纯文本并弹「内容已复制」
                onNodeWithText("HTML气泡", substring = true).performTouchInput { longClick() }
                waitForIdle()
                assertEquals(listOf(HapticFeedbackType.LongPress), haptics)
                onNodeWithText("复制").assertIsDisplayed().performClick()
                waitForIdle()

                val htmlClip = clipboard.primaryClip
                assertNotNull(htmlClip)
                assertTrue(htmlClip!!.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
                assertFalse(htmlClip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))
                assertNull(htmlClip.getItemAt(0).htmlText)
                assertNull(htmlClip.getItemAt(0).uri)
                assertNull(htmlClip.getItemAt(0).intent)
                val copiedHtml = htmlClip.getItemAt(0).text.toString()
                assertFalse("剪贴板不得含 HTML 标签：$copiedHtml", copiedHtml.contains("<") || copiedHtml.contains(">"))
                assertEquals("安全帖子链接 HTML气泡 {:2_41:}", copiedHtml)
                assertEquals(listOf("内容已复制" to PaneSnackbarTarget.DetailPane), snackbarEvents)

                // 3. 发送一条失败的本地乐观纯文本气泡 -> 验证失败态红色「!」菜单与气泡长按「复制」互不冲突
                onNode(hasSetTextAction()).performTextInput("失败气泡纯文本 :)")
                onNodeWithContentDescription("发送").performClick()
                waitUntil { onAllNodes(hasContentDescription("发送失败")).fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()

                // 通过无障碍 OnLongClick 语义动作长按失败气泡并复制
                onNodeWithText("失败气泡纯文本", substring = true).performSemanticsAction(SemanticsActions.OnLongClick)
                waitForIdle()
                assertEquals(2, haptics.size)
                onNodeWithText("复制").assertIsDisplayed().performClick()
                waitForIdle()
                assertEquals("失败气泡纯文本 :)", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                assertEquals(2, snackbarEvents.size)

                // 失败气泡红色「!」按钮仍可弹出「重发 / 删除」并正常工作
                onNodeWithContentDescription("发送失败").performClick()
                waitForIdle()
                onNodeWithText("重发").assertIsDisplayed()
                onNodeWithText("删除").assertIsDisplayed().performClick()
                waitForIdle()
                onNodeWithText("失败气泡纯文本", substring = true).assertDoesNotExist()

                // 4. 再发一条本地乐观成功纯文本气泡 -> 长按复制纯文本
                onNode(hasSetTextAction()).performTextInput("乐观发送成功正文")
                onNodeWithContentDescription("发送").performClick()
                waitUntil { onAllNodes(hasContentDescription("15 秒后可发送")).fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()

                onNodeWithText("乐观发送成功正文").performTouchInput { longClick() }
                waitForIdle()
                onNodeWithText("复制").assertIsDisplayed().performClick()
                waitForIdle()
                assertEquals("乐观发送成功正文", clipboard.primaryClip?.getItemAt(0)?.text?.toString())

                // 5. 空内容气泡安全复制测试（不崩溃、不篡改剪贴板）
                val beforeEmpty = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                assertFalse(copySmsBubblePlainTextToClipboard(context, "复制", "   <br/>  ", isPlainText = false))
                assertEquals(beforeEmpty, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                assertGlassSurfaceCountAtMost(0)
            } finally {
                scope.cancel()
            }
        }

    /** 负责人裁定 #3（闭合 R-M7-G4 S2）：短消息气泡仅有图片、没有文字时隐藏长按「复制」菜单；图文混排与纯表情气泡仍支持复制。 */
    @Test
    fun smsConversation_imageOnlyBubbleHidesCopyMenu_whileMixedAndEmojiBubblesKeepCopy() =
        withAnimatorDurationScale(0f) {
            val clock = AtomicLong(1_700_000_000_000L)
            val haptics = mutableListOf<HapticFeedbackType>()
            val smsRepo = FakeSmsRepository(
                clock = { clock.get() },
                initialMessages = listOf(
                    SmsMessageItem(
                        uid = "20001",
                        author = "Alice",
                        avatarUrl = null,
                        time = "11:01",
                        contentHtml = "<img src=\"https://img.example.invalid/only.jpg\" />",
                        isNew = false,
                        isMine = false,
                    ),
                    SmsMessageItem(
                        uid = "20001",
                        author = "Alice",
                        avatarUrl = null,
                        time = "11:02",
                        contentHtml = "<img src=\"https://img.example.invalid/mixed.jpg\" /> 带文字说明",
                        isNew = false,
                        isMine = false,
                    ),
                    SmsMessageItem(
                        uid = "20001",
                        author = "Alice",
                        avatarUrl = null,
                        time = "11:03",
                        contentHtml = "<img src=\"images/smilies/coolmonkey/01.gif\" />",
                        isNew = false,
                        isMine = false,
                    ),
                ),
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val vm = SmsConversationViewModel(smsRepo, FakeAuthRepository(signedIn = true), idleGateway(scope)) { clock.get() }
            try {
                setContent {
                    CompositionLocalProvider(
                        LocalHapticFeedback provides object : HapticFeedback {
                            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                                haptics += hapticFeedbackType
                            }
                        },
                    ) {
                        HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                            SmsConversationScreen(
                                args = SmsConversationArgs("20001", "Alice"),
                                showBack = true,
                                onBack = {},
                                onOpenUserProfile = { _, _ -> },
                                onOpenLogin = {},
                                contentPadding = PaddingValues(0.dp),
                                viewModel = vm,
                            )
                        }
                    }
                }
                waitForIdle()

                // 仅 2 条带文字/表情的气泡暴露长按「复制」语义；纯图气泡（11:01）不得暴露长按复制动作
                val copyableNodes = onAllNodes(
                    SemanticsMatcher("hasCopyLongClick") { node ->
                        node.config.contains(SemanticsActions.OnLongClick) &&
                            node.config[SemanticsActions.OnLongClick].label == "复制"
                    },
                ).fetchSemanticsNodes()
                assertEquals("3 条消息中仅有图文混排与表情气泡应暴露长按复制（纯图气泡隐藏复制）", 2, copyableNodes.size)

                // 长按纯图气泡对应时间附近区域不弹「复制」，且长按图文混排气泡仍可正常弹「复制」
                onNodeWithText("11:01").assertIsDisplayed()
                onNodeWithText("复制").assertDoesNotExist()
                assertTrue(haptics.isEmpty())

                onNodeWithText("带文字说明", substring = true).performTouchInput { longClick() }
                waitForIdle()
                assertEquals(listOf(HapticFeedbackType.LongPress), haptics)
                onNodeWithText("复制").assertIsDisplayed()
            } finally {
                scope.cancel()
            }
        }

    private fun idleGateway(scope: CoroutineScope): SiteAccessGateway =
        SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, clock = System::currentTimeMillis, gatewayScope = scope)
}
