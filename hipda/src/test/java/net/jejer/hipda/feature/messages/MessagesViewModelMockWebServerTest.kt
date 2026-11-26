package net.jejer.hipda.feature.messages

import android.app.Application
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jejer.hipda.R
import net.jejer.hipda.data.account.CredentialsStore
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.DefaultAuthRepository
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.computeLegacyPasswordMd5
import net.jejer.hipda.data.message.DefaultNotifyRepository
import net.jejer.hipda.data.message.DefaultSmsRepository
import net.jejer.hipda.data.message.NotifyReadStateStore
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.parser.SampleTestLoader
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 基于 MockWebServer + 真实 `DefaultSmsRepository` / `DefaultNotifyRepository` 的 ViewModel 端到端集成测试
 * （严禁访问真实 4d4y.com，所有发短消息、清空、提醒加载均在本地 MockWebServer 内验证）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MessagesViewModelMockWebServerTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var virtualClockMs: AtomicLong
    private lateinit var server: MockWebServer
    private lateinit var repoScope: CoroutineScope
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        virtualClockMs = AtomicLong(1_700_000_000_000L)
        server = MockWebServer()
        server.start()
        repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        repoScope.cancel()
        runBlocking { repoScope.coroutineContext[Job]?.join() }
        clientsToClose.forEach { client ->
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        clientsToClose.clear()
        server.close()
    }

    private data class MockStack(
        val authRepo: DefaultAuthRepository,
        val notifyRepo: DefaultNotifyRepository,
        val smsRepo: DefaultSmsRepository,
        val gateway: SiteAccessGateway,
    )

    private fun createStack(loggedIn: Boolean = true): MockStack {
        val cookieStore = InMemorySiteCookieStore(clock = { virtualClockMs.get() })
        val credStore = InMemoryCredentialsStore()
        val settingsRepo = InMemorySettingsRepository(
            AppSettings(forumServer = server.url("").toString().trimEnd('/')),
        )
        val account = SavedAccount("10000", "HiUser", computeLegacyPasswordMd5("secret"), lastLoginAt = 100L)
        if (loggedIn) {
            runBlocking {
                credStore.upsert(account)
                credStore.setActive(account.uid)
            }
        }
        val primaryClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = TransportInterceptor(delegateFactory = { null }, isMainThread = { false }),
        ).also(clientsToClose::add)
        val recoveryClient = HttpClientModule.provideRecoveryOkHttpClient(primaryClient).also(clientsToClose::add)
        val detector = ChallengeDetector()
        val solver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected solver")) }
        val primaryGateway = SiteAccessGateway(primaryClient, detector, solver, { virtualClockMs.get() }, repoScope, 5.seconds)
        val recoveryGateway = SiteAccessGateway(recoveryClient, detector, solver, { virtualClockMs.get() }, repoScope, 5.seconds)
        val authRepo = DefaultAuthRepository(credStore, cookieStore, settingsRepo, primaryGateway, recoveryGateway, { virtualClockMs.get() }, repoScope)
        if (loggedIn) {
            runBlocking { withTimeout(3.seconds) { authRepo.currentAccount.first { it?.uid == account.uid } } }
        }
        val forumClient = ForumHttpClient(primaryGateway, settingsRepo, authRepo)
        val storeFile = File(tempFolder.newFolder(), "notify_read_state.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = repoScope,
            produceFile = { storeFile },
        )
        val notifyRepo = DefaultNotifyRepository(forumClient, settingsRepo, authRepo, NotifyReadStateStore(dataStore), { virtualClockMs.get() }, repoScope)
        val smsRepo = DefaultSmsRepository(forumClient, settingsRepo, authRepo, notifyRepo, { virtualClockMs.get() }, repoScope)
        return MockStack(authRepo, notifyRepo, smsRepo, primaryGateway)
    }

    @Test
    fun smsConversation_mockWebServer_sendConfirmCooldownAndFailedResendDeleteRestore() = runTest(mainDispatcher) {
        val stack = createStack(loggedIn = true)
        val vm = SmsConversationViewModel(stack.smsRepo, stack.authRepo, stack.gateway) { virtualClockMs.get() }
        val stateCollector = backgroundScope.launch { vm.uiState.collect {} }

        // 1. 加载真实脱敏对话样本
        server.enqueue(gbkHtmlResponse(SampleTestLoader.loadSample("sms/sms_detail.html")))
        vm.bindConversation(SmsConversationArgs(uid = "20000", username = "HiPeer"))
        waitUntil { vm.uiState.value.hasLoadedOnce }
        assertEquals(9, vm.uiState.value.messages.size)
        assertEquals("/forum/pm.php?daterange=5&uid=20000", server.takeRequest().target)

        // 2. 失败发送（HTTP 500）-> 气泡立即乐观入列，失败后转 Failed 且不触发冷却
        server.enqueue(gbkHtmlResponse(smsPrefetchHtml("fh_fail")))
        server.enqueue(MockResponse.Builder().code(500).body("Server Error").build())
        vm.onInputChange("第一条待重发消息")
        vm.sendMessage()
        assertEquals("", vm.uiState.value.inputText)
        assertEquals(10, vm.uiState.value.messages.size)
        assertEquals(SmsBubbleDeliveryStatus.Sending, vm.uiState.value.messages.last().deliveryStatus)

        waitUntil { vm.uiState.value.messages.last().deliveryStatus == SmsBubbleDeliveryStatus.Failed }
        assertEquals(0, vm.uiState.value.cooldownSeconds)
        val failedId = vm.uiState.value.messages.last().id

        // 3. 删除失败气泡 + 撤销原位恢复
        val deleted = vm.deleteFailedMessage(failedId)
        assertNotNull(deleted)
        assertEquals(9, vm.uiState.value.messages.size)
        vm.restoreDeletedMessage(deleted!!)
        assertEquals(10, vm.uiState.value.messages.size)
        assertEquals(failedId, vm.uiState.value.messages.last().id)

        // 4. 点击重发 -> MockWebServer 返回成功 XML -> 转 Delivered 并启动 15 秒冷却
        server.enqueue(gbkHtmlResponse(smsPrefetchHtml("fh_ok")))
        server.enqueue(gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[<li class="s_clear self"><div class="summary">第一条待重发消息</div></li>]]></root>"""))
        vm.resendMessage(failedId)
        assertEquals(SmsBubbleDeliveryStatus.Sending, vm.uiState.value.messages.last().deliveryStatus)
        waitUntil { vm.uiState.value.messages.last().deliveryStatus == SmsBubbleDeliveryStatus.Delivered }
        assertEquals(15, vm.uiState.value.cooldownSeconds)

        // 5. 冷却期间允许输入但 canSend 为 false，推进虚拟时钟 15 秒后归零恢复发送
        vm.onInputChange("冷却中继续输入")
        assertEquals("冷却中继续输入", vm.uiState.value.inputText)
        assertFalse(vm.uiState.value.canSend)

        virtualClockMs.addAndGet(14_001L)
        advanceTimeBy(14_001L)
        assertEquals(1, vm.uiState.value.cooldownSeconds)
        assertFalse(vm.uiState.value.canSend)

        virtualClockMs.addAndGet(999L)
        advanceTimeBy(1_000L)
        assertEquals(0, vm.uiState.value.cooldownSeconds)
        assertTrue(vm.uiState.value.canSend)

        stateCollector.cancel()
    }

    @Test
    fun messagesList_mockWebServer_smsClearAndNotifyPerItemReadAndMarkAllRead() = runTest(mainDispatcher) {
        val stack = createStack(loggedIn = true)
        val vm = MessagesListViewModel(stack.smsRepo, stack.notifyRepo, stack.authRepo, stack.gateway)
        val events = mutableListOf<MessagesListEvent>()
        val stateCollector = backgroundScope.launch { vm.uiState.collect {} }
        val eventCollector = backgroundScope.launch { vm.events.collect { events += it } }

        // 1. 加载真实脱敏 sms_list.html 验证 50 条解析，再用双会话列表验证清空单个会话仅移除目标 uid
        server.enqueue(gbkHtmlResponse(SampleTestLoader.loadSample("sms/sms_list.html")))
        vm.bindEntry(ShellSidebarEntry.Sms)
        waitUntil { vm.uiState.value.sms.hasLoadedOnce }
        assertEquals(50, vm.uiState.value.sms.items.size)
        assertEquals("/forum/pm.php?filter=privatepm", server.takeRequest().target)

        server.enqueue(
            gbkHtmlResponse(
                """
                <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head><body>
                  <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
                  <ul class="pm_list">
                    <li><p class="cite"><cite><a href="space.php?uid=20001">Alice</a></cite> 2026-11-25 10:00 <img src="images/default/notice_newpm.gif" /></p><div class="summary">待清空会话</div></li>
                    <li><p class="cite"><cite><a href="space.php?uid=20002">Bob</a></cite> 2026-11-25 09:00</p><div class="summary">保留会话</div></li>
                  </ul>
                </body></html>
                """.trimIndent(),
            ),
        )
        vm.refresh(ShellSidebarEntry.Sms)
        waitUntil { vm.uiState.value.sms.items.size == 2 }
        assertEquals("/forum/pm.php?filter=privatepm", server.takeRequest().target)

        server.enqueue(gbkHtmlResponse("""<html><body><div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div><div>ok</div></body></html>"""))
        vm.clearConversation("20001")
        waitUntil { vm.uiState.value.sms.items.size == 1 }
        assertEquals("20002", vm.uiState.value.sms.items.single().uid)
        assertEquals("/forum/pm.php?action=del&uid=20001&filter=privatepm", server.takeRequest().target)

        // 2. 切至提醒标签加载 3 条未读提醒：进入页面绝不清零
        server.enqueue(gbkHtmlResponse(syntheticThreeNotifyHtml()))
        vm.bindEntry(ShellSidebarEntry.Notify)
        waitUntil { vm.uiState.value.notify.entries.size == 3 }
        assertEquals(3, vm.uiState.value.notifyUnreadCount)
        assertTrue(vm.uiState.value.notify.entries.all { !it.isRead })

        // 3. 点击单条提醒 -> 仅该条标为已读（3 -> 2）
        val targetEntry = vm.uiState.value.notify.entries[1]
        vm.onNotificationOpened(targetEntry)
        waitUntil { vm.uiState.value.notifyUnreadCount == 2 }
        assertTrue(vm.uiState.value.notify.entries.first { it.key == targetEntry.key }.isRead)
        assertEquals(2, vm.uiState.value.notify.entries.count { !it.isRead })

        // 4. 点击“全部已读” -> 全部标已读、未读归零并发出 Snackbar 事件
        vm.markAllNotificationsRead()
        waitUntil { vm.uiState.value.notifyUnreadCount == 0 && events.contains(MessagesListEvent.AllNotificationsMarkedRead) }
        assertTrue(vm.uiState.value.notify.entries.all { it.isRead })

        stateCollector.cancel()
        eventCollector.cancel()
    }

    @Test
    fun messagesList_signedOut_showsLoginRequiredWithZeroNetworkRequests() = runTest(mainDispatcher) {
        val stack = createStack(loggedIn = false)
        val vm = MessagesListViewModel(stack.smsRepo, stack.notifyRepo, stack.authRepo, stack.gateway)
        val stateCollector = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindEntry(ShellSidebarEntry.Sms)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isLoggedIn)
        assertEquals(R.string.common_error_login_required, vm.uiState.value.sms.error?.messageRes)
        assertEquals(UiErrorAction.Login, vm.uiState.value.sms.error?.action)
        assertEquals(0, server.requestCount)

        vm.bindEntry(ShellSidebarEntry.Notify)
        advanceUntilIdle()
        assertEquals(R.string.common_error_login_required, vm.uiState.value.notify.error?.messageRes)
        assertEquals(0, server.requestCount)

        stateCollector.cancel()
    }

    @Test
    fun messagesList_mockWebServer_newSmsComposeToArbitraryUsername_refreshesListAndEnforcesCooldown() = runTest(mainDispatcher) {
        val stack = createStack(loggedIn = true)
        val vm = MessagesListViewModel(stack.smsRepo, stack.notifyRepo, stack.authRepo, stack.gateway) { virtualClockMs.get() }
        val stateCollector = backgroundScope.launch { vm.uiState.collect {} }

        // 1. 首屏加载旧会话列表
        server.enqueue(
            gbkHtmlResponse(
                """
                <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head><body>
                  <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
                  <ul class="pm_list">
                    <li><p class="cite"><cite><a href="space.php?uid=20001">Alice</a></cite> 2026-11-25 10:00</p><div class="summary">旧消息</div></li>
                  </ul>
                </body></html>
                """.trimIndent(),
            ),
        )
        vm.bindEntry(ShellSidebarEntry.Sms)
        waitUntil { vm.uiState.value.sms.items.size == 1 }
        assertEquals("/forum/pm.php?filter=privatepm", server.takeRequest().target)

        // 2. 打开新建短消息弹窗并向任意用户名发送（先校验空输入 0 网络请求）
        vm.onNewSmsClick()
        mainDispatcher.scheduler.runCurrent()
        assertTrue(vm.uiState.value.compose.isVisible)
        vm.sendNewSms()
        mainDispatcher.scheduler.runCurrent()
        assertEquals(R.string.messages_new_sms_recipient_empty, vm.uiState.value.compose.recipientErrorRes)
        assertEquals(R.string.messages_new_sms_content_empty, vm.uiState.value.compose.bodyErrorRes)
        assertEquals(1, server.requestCount)

        // 3. 首次提交返回服务端拒绝 XML -> 留窗保输入并显示错误原因
        vm.onNewSmsRecipientChange("新收件人")
        vm.onNewSmsBodyChange("通过用户名发送正文")
        server.enqueue(gbkHtmlResponse(smsPrefetchHtml("fh_new_1")))
        server.enqueue(gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[抱歉，找不到该用户]]></root>"""))
        vm.sendNewSms()
        mainDispatcher.scheduler.runCurrent()
        assertTrue(vm.uiState.value.compose.isSending)
        waitUntil { !vm.uiState.value.compose.isSending }
        assertTrue("失败必须留在本对话框", vm.uiState.value.compose.isVisible)
        assertEquals("新收件人", vm.uiState.value.compose.recipient)
        assertEquals("通过用户名发送正文", vm.uiState.value.compose.body)
        assertEquals("抱歉，找不到该用户", vm.uiState.value.compose.sendError?.serverMessage)
        assertEquals("/forum/pm.php?daterange=1&uid=", server.takeRequest().target)
        val failPost = server.takeRequest()
        assertEquals("/forum/pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1", failPost.target)
        assertTrue(failPost.body!!.utf8().contains("msgto="))

        // 4. 修正收件人重试成功 -> 关闭对话框、自动刷新列表并进入 15 秒冷却
        vm.onNewSmsRecipientChange("BobNew")
        server.enqueue(gbkHtmlResponse(smsPrefetchHtml("fh_new_2")))
        server.enqueue(gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[<li class="s_clear self"><div class="summary">通过用户名发送正文</div></li>]]></root>"""))
        server.enqueue(
            gbkHtmlResponse(
                """
                <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head><body>
                  <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
                  <ul class="pm_list">
                    <li><p class="cite"><cite><a href="space.php?uid=20099">BobNew</a></cite> 刚刚</p><div class="summary">通过用户名发送正文</div></li>
                    <li><p class="cite"><cite><a href="space.php?uid=20001">Alice</a></cite> 2026-11-25 10:00</p><div class="summary">旧消息</div></li>
                  </ul>
                </body></html>
                """.trimIndent(),
            ),
        )
        vm.sendNewSms()
        waitUntil { !vm.uiState.value.compose.isVisible && vm.uiState.value.sms.items.size == 2 }
        assertEquals("BobNew", vm.uiState.value.sms.items.first().author)
        assertEquals(15, vm.uiState.value.compose.cooldownSeconds)

        assertEquals("/forum/pm.php?daterange=1&uid=", server.takeRequest().target)
        val okPost = server.takeRequest()
        assertEquals("/forum/pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1", okPost.target)
        assertTrue(okPost.body!!.utf8().contains("msgto=BobNew"))
        assertEquals("/forum/pm.php?filter=privatepm", server.takeRequest().target)

        // 5. 冷却期内再次打开尝试发送 -> 前置拦截 0 次网络请求；推进 15 秒后归零
        vm.onNewSmsClick()
        vm.onNewSmsRecipientChange("Charlie")
        vm.onNewSmsBodyChange("冷却拦截测试")
        val reqBefore = server.requestCount
        vm.sendNewSms()
        mainDispatcher.scheduler.runCurrent()
        assertEquals(reqBefore, server.requestCount)
        assertEquals(15, vm.uiState.value.compose.sendError?.cooldownSeconds)

        virtualClockMs.addAndGet(15_001L)
        advanceTimeBy(15_001L)
        mainDispatcher.scheduler.runCurrent()
        assertEquals(0, vm.uiState.value.compose.cooldownSeconds)

        stateCollector.cancel()
    }

    private suspend fun waitUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            mainDispatcher.scheduler.runCurrent()
            if (condition()) return
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Timed out waiting for condition")
            }
            Thread.sleep(10L)
        }
    }

    private fun smsPrefetchHtml(formhash: String): String = """
        <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head><body>
          <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
          <form id="pmform"><input type="hidden" name="formhash" id="formhash" value="$formhash" /></form>
        </body></html>
    """.trimIndent()

    private fun syntheticThreeNotifyHtml(): String = """
        <html><head><meta http-equiv="Content-Type" content="text/html; charset=gbk"></head><body>
          <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
          <ul class="feed">
            <li><div class="f_thread"><a href="space.php?uid=20001">Alice</a> 回复了您的帖子 <a href="redirect.php?goto=findpost&amp;ptid=1001&amp;pid=9001">第一篇帖子</a> <em>10 分钟前</em></div></li>
            <li><div class="f_quote"><a href="space.php?uid=20002">Bob</a> 引用了您在 <a href="viewthread.php?tid=1002">第二篇帖子</a> 的发言 <a href="redirect.php?goto=findpost&amp;ptid=1002&amp;pid=9002">查看</a> <em>5 分钟前</em> <img src="images/default/notice_newpm.gif" /><dl class="summary"><dd>原帖内容</dd><dd>引用回复内容</dd></dl></div></li>
            <li><div class="f_manage">系统通知：您的帖子已被加精 <a href="viewthread.php?tid=1003">第三篇帖子</a> <img src="images/default/notice_newpm.gif" /></div></li>
          </ul>
        </body></html>
    """.trimIndent()

    private fun gbkHtmlResponse(html: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/html; charset=gbk")
        .body(Buffer().write(html.toByteArray(GBK)))
        .build()

    private fun gbkXmlResponse(xml: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/xml; charset=gbk")
        .body(Buffer().write(xml.toByteArray(GBK)))
        .build()

    private class InMemoryCredentialsStore : CredentialsStore {
        private data class Snap(val accounts: List<SavedAccount> = emptyList(), val activeUid: String? = null, val manualOut: Boolean = false)
        private val state = MutableStateFlow(Snap())
        override val accounts: Flow<List<SavedAccount>> = state.map { it.accounts }
        override val activeUid: Flow<String?> = state.map { it.activeUid }
        override val manuallyLoggedOut: Flow<Boolean> = state.map { it.manualOut }
        override suspend fun upsert(account: SavedAccount) {
            state.update { it.copy(accounts = it.accounts.filterNot { a -> a.uid == account.uid } + account) }
        }
        override suspend fun setActive(uid: String) { state.update { it.copy(activeUid = uid, manualOut = false) } }
        override suspend fun markManualLogout() { state.update { it.copy(activeUid = null, manualOut = true) } }
        override suspend fun remove(uid: String) {
            state.update { it.copy(accounts = it.accounts.filterNot { a -> a.uid == uid }, activeUid = if (it.activeUid == uid) null else it.activeUid) }
        }
    }

    private class InMemorySettingsRepository(initial: AppSettings) : SettingsRepository {
        private val _s = MutableStateFlow(initial)
        override val settings: StateFlow<AppSettings> = _s.asStateFlow()
        override suspend fun setThemeMode(themeMode: ThemeMode) = Unit
        override suspend fun setPureBlack(enabled: Boolean) = Unit
        override suspend fun setThemeSeed(themeSeed: String) = Unit
        override suspend fun setFontStep(step: Int) = Unit
        override suspend fun setLineSpacing(lineSpacing: LineSpacing) = Unit
        override suspend fun setFollowedForums(forumIds: List<Int>) = Unit
        override suspend fun setShowStickyThreads(show: Boolean) = Unit
        override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) = Unit
        override suspend fun setTailEnabled(enabled: Boolean) = Unit
        override suspend fun setTailText(text: String) = Unit
        override suspend fun setTailUrl(url: String) = Unit
        override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
        override suspend fun setSilentModeEnabled(enabled: Boolean) = Unit
        override suspend fun setSilentBeginMinutes(minutes: Int) = Unit
        override suspend fun setSilentEndMinutes(minutes: Int) = Unit
        override suspend fun setForumServer(server: String) = Unit
        override suspend fun setImageCacheLimitMb(limitMb: Int) = Unit
        override suspend fun setPaneSplitPortrait(split: PaneSplit) = Unit
        override suspend fun setPaneSplitLandscape(split: PaneSplit) = Unit
        override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) = Unit
        override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) = Unit
    }

    private companion object {
        val GBK: Charset = Charset.forName("GBK")
        const val TEST_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
