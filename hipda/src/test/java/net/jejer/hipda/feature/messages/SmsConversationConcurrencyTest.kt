package net.jejer.hipda.feature.messages

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.message.MessageResult
import net.jejer.hipda.data.message.SmsSendOutcome
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.parser.message.SmsDetailPage
import net.jejer.hipda.parser.message.SmsMessageItem
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 覆盖 R-E8 B1–B3 与连续删除+锚点撤销的并发交错、串行发送保序、冷却重发拦截、跨账号/退登隔离与纯 uid 用户名回填回归测试。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmsConversationConcurrencyTest {

    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var gatewayScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        gatewayScope.cancel()
    }

    private fun TestScope.virtualClock(): () -> Long = { BASE_EPOCH_MS + testScheduler.currentTime }

    private fun idleGateway(clock: () -> Long): SiteAccessGateway =
        SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, clock = clock, gatewayScope = gatewayScope)

    /** B1（加载与发送交错）：慢网首屏加载期间禁止发送且保留草稿，首屏完成后才允许发送并落列。 */
    @Test
    fun b1_slowInitialLoadInterleavedWithSend_blocksSendUntilLoadedAndPreservesDraft() = runTest(mainDispatcher) {
        val clock = virtualClock()
        val initialLoadGate = CompletableDeferred<MessageResult<SmsDetailPage>>()
        val smsRepo = FakeSmsRepository(
            clock = clock,
            onLoadDetail = { initialLoadGate.await() },
            onSend = { _, _, _ -> SmsSendOutcome.Sent },
        )
        val vm = SmsConversationViewModel(smsRepo, FakeAuthRepository(signedIn = true), idleGateway(clock), clock)
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindConversation(SmsConversationArgs(uid = "20000", username = "Alice"))
        runCurrent()
        assertTrue(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.hasLoadedOnce)

        // 首屏在途期间输入草稿并尝试发送：必须被阻断，既不清空草稿也不发出网络请求
        vm.onInputChange("首屏加载中草稿")
        assertFalse("首屏加载期间 canSend 必须为 false", vm.uiState.value.canSend)
        vm.sendMessage()
        runCurrent()
        assertEquals("首屏加载期间草稿必须保留", "首屏加载中草稿", vm.uiState.value.inputText)
        assertEquals(0, smsRepo.sendCallCount.get())
        assertTrue(vm.uiState.value.messages.isEmpty())

        // 放行首屏加载 -> 恢复发送能力，草稿可正常发出且不被吞掉
        initialLoadGate.complete(
            MessageResult.Success(
                SmsDetailPage("10000", "HiUser", "fh", listOf(SmsMessageItem("20000", "Alice", null, "10:00", "早安", false, false)), fakePageMeta(), true),
            ),
        )
        runCurrent()
        assertTrue(vm.uiState.value.hasLoadedOnce)
        assertTrue(vm.uiState.value.canSend)

        vm.sendMessage()
        runCurrent()
        assertEquals(1, smsRepo.sendCallCount.get())
        assertEquals(listOf("早安", "首屏加载中草稿"), vm.uiState.value.messages.map { it.rawMessage ?: it.contentHtml })
        assertEquals(SmsBubbleDeliveryStatus.Delivered, vm.uiState.value.messages.last().deliveryStatus)
        job.cancel()
    }

    /** B1（刷新与发送交错）：旧刷新返回不得吞掉已确认本地气泡；含新消息的刷新先于发送回调到达也不得产生双份气泡。 */
    @Test
    fun b1_slowRefreshInterleavedWithSend_preservesConfirmedLocalAndDeduplicatesInFlightSend() = runTest(mainDispatcher) {
        val clock = virtualClock()
        val initialServer = listOf(
            SmsMessageItem("20000", "Alice", null, "10:00", "早安", false, false),
            SmsMessageItem("10000", "HiUser", null, "10:01", "ok", false, true),
        )
        val detailGates = ArrayDeque<CompletableDeferred<MessageResult<SmsDetailPage>>>()
        val sendGates = ArrayDeque<CompletableDeferred<SmsSendOutcome>>()

        val smsRepo = FakeSmsRepository(
            clock = clock,
            onLoadDetail = {
                val gate = CompletableDeferred<MessageResult<SmsDetailPage>>()
                detailGates.addLast(gate)
                gate.await()
            },
            onSend = { _, _, _ ->
                val gate = CompletableDeferred<SmsSendOutcome>()
                sendGates.addLast(gate)
                gate.await()
            },
        )
        val vm = SmsConversationViewModel(smsRepo, FakeAuthRepository(signedIn = true), idleGateway(clock), clock)
        val job = backgroundScope.launch { vm.uiState.collect {} }

        // 0. 首屏完成（历史中已有一条 "ok"）
        vm.bindConversation(SmsConversationArgs("20000", "Alice"))
        runCurrent()
        detailGates.removeFirst().complete(MessageResult.Success(SmsDetailPage("10000", "HiUser", "fh", initialServer, fakePageMeta(), true)))
        runCurrent()
        assertEquals(2, vm.uiState.value.messages.size)

        // 路径 ①：下拉刷新在途（服务端拍到旧列表 [早安, ok]），期间用户又发一条同文 "ok" 且先确认 Sent
        vm.refresh()
        runCurrent()
        val staleRefreshGate = detailGates.removeFirst()

        vm.onInputChange("ok")
        vm.sendMessage()
        runCurrent()
        sendGates.removeFirst().complete(SmsSendOutcome.Sent)
        runCurrent()
        assertEquals(3, vm.uiState.value.messages.size)
        assertEquals(SmsBubbleDeliveryStatus.Delivered, vm.uiState.value.messages.last().deliveryStatus)

        // 滞后的旧刷新返回 -> 绝不能吞掉刚确认的 local:* "ok"
        staleRefreshGate.complete(MessageResult.Success(SmsDetailPage("10000", "HiUser", "fh", initialServer, fakePageMeta(), true)))
        runCurrent()
        assertEquals("滞后刷新绝不能吞掉已确认发送的本地气泡", 3, vm.uiState.value.messages.size)
        assertEquals(listOf("早安", "ok", "ok"), vm.uiState.value.messages.map { it.rawMessage ?: it.contentHtml })

        // 下一次新刷新拉回服务端已落库的第三条 "ok" -> 平滑合并为 3 条 server:*，绝不重复成 4 条
        val withFirstNew = initialServer + SmsMessageItem("10000", "HiUser", null, "10:02", "ok", false, true)
        vm.refresh()
        runCurrent()
        detailGates.removeFirst().complete(MessageResult.Success(SmsDetailPage("10000", "HiUser", "fh", withFirstNew, fakePageMeta(), true)))
        runCurrent()
        assertEquals(3, vm.uiState.value.messages.size)
        assertTrue(vm.uiState.value.messages.none { it.id.startsWith("local:") })

        // 路径 ②：冷却结束后发送 M2（仍处于 Sending），期间刷新先拉回服务端已落库的 M2，随后发送回调 Sent 返回 -> 全程只有 1 份 M2 气泡
        advanceTimeBy(15_001L)
        runCurrent()
        assertEquals(0, vm.uiState.value.cooldownSeconds)

        vm.onInputChange("并行落库消息")
        vm.sendMessage()
        runCurrent()
        val inFlightSendGate = sendGates.removeFirst()
        assertEquals(4, vm.uiState.value.messages.size)
        assertEquals(SmsBubbleDeliveryStatus.Sending, vm.uiState.value.messages.last().deliveryStatus)

        val withSecondNew = withFirstNew + SmsMessageItem("10000", "HiUser", null, "10:03", "并行落库消息", false, true)
        vm.refresh()
        runCurrent()
        detailGates.removeFirst().complete(MessageResult.Success(SmsDetailPage("10000", "HiUser", "fh", withSecondNew, fakePageMeta(), true)))
        runCurrent()
        assertEquals("服务端已落库 M2 时本地 Sending 气泡必须去重吸收", 4, vm.uiState.value.messages.size)
        assertTrue(vm.uiState.value.messages.none { it.id.startsWith("local:") })

        // 发送协程随后恢复返回 Sent -> 绝不复活或产生双份气泡
        inFlightSendGate.complete(SmsSendOutcome.Sent)
        runCurrent()
        assertEquals(4, vm.uiState.value.messages.size)
        assertEquals(listOf("早安", "ok", "ok", "并行落库消息"), vm.uiState.value.messages.map { it.rawMessage ?: it.contentHtml })
        assertTrue(vm.uiState.value.messages.all { it.deliveryStatus == SmsBubbleDeliveryStatus.Delivered })
        job.cancel()
    }

    /** B2（显式串行 + 失败重发移至队尾）：连发 M1 失败、M2 成功、冷却后重发 M1，最终顺序严格为 [M2, M1]。 */
    @Test
    fun b2_slowNetworkSerialSend_m1FailsM2SucceedsThenResendM1_finalOrderIsM2ThenM1() = runTest(mainDispatcher) {
        val clock = virtualClock()
        val inFlightCount = AtomicInteger(0)
        val maxConcurrentSends = AtomicInteger(0)
        val sentOrder = mutableListOf<String>()
        val m1Gate = CompletableDeferred<SmsSendOutcome>()
        var m1Attempts = 0

        val smsRepo = FakeSmsRepository(
            clock = clock,
            initialMessages = listOf(SmsMessageItem("20000", "Alice", null, "10:00", "历史", false, false)),
            onSend = { _, _, msg ->
                val cur = inFlightCount.incrementAndGet()
                maxConcurrentSends.updateAndGet { maxOf(it, cur) }
                try {
                    sentOrder += msg
                    when (msg) {
                        "M1" -> {
                            m1Attempts++
                            if (m1Attempts == 1) m1Gate.await() else SmsSendOutcome.Sent
                        }
                        else -> SmsSendOutcome.Sent
                    }
                } finally {
                    inFlightCount.decrementAndGet()
                }
            },
        )
        val vm = SmsConversationViewModel(smsRepo, FakeAuthRepository(signedIn = true), idleGateway(clock), clock)
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindConversation(SmsConversationArgs("20000", "Alice"))
        runCurrent()

        // 慢网连发 M1 与 M2：M1 在途期间 M2 必须串行等待，不得并发乱序进入仓库
        vm.onInputChange("M1")
        vm.sendMessage()
        vm.onInputChange("M2")
        vm.sendMessage()
        runCurrent()
        assertEquals("M1 未结束前 M2 不得并发进入 sendSms", listOf("M1"), sentOrder)
        assertEquals(1, maxConcurrentSends.get())

        // M1 失败（不触发冷却），串行释放后 M2 自动发送成功并启动 15 秒冷却
        m1Gate.complete(SmsSendOutcome.Error("timeout"))
        runCurrent()
        assertEquals(listOf("M1", "M2"), sentOrder)
        assertEquals(1, maxConcurrentSends.get())
        val afterM2 = vm.uiState.value.messages.drop(1)
        assertEquals(listOf("M1", "M2"), afterM2.map { it.rawMessage })
        assertEquals(listOf(SmsBubbleDeliveryStatus.Failed, SmsBubbleDeliveryStatus.Delivered), afterM2.map { it.deliveryStatus })

        // 冷却结束后重发 M1 -> M1 移至队尾并发送成功，最终顺序严格为 [M2, M1]
        val m1Id = afterM2.first().id
        advanceTimeBy(15_001L)
        runCurrent()
        assertEquals(0, vm.uiState.value.cooldownSeconds)

        vm.resendMessage(m1Id)
        runCurrent()
        val finalLocals = vm.uiState.value.messages.drop(1)
        assertEquals("重发失败消息 M1 后最终顺序必须为 [M2, M1]", listOf("M2", "M1"), finalLocals.map { it.rawMessage })
        assertEquals(listOf(SmsBubbleDeliveryStatus.Delivered, SmsBubbleDeliveryStatus.Delivered), finalLocals.map { it.deliveryStatus })
        job.cancel()
    }

    /** B2（冷却期内重发被拦住）：假仓库实现真实冷却，校验 ViewModel 前置拦截（0 仓库调用 + 不移位 + 发 CooldownBlocked），删掉重发冷却校验行即红。 */
    @Test
    fun b2_resendDuringCooldown_blockedWithZeroRepoCallsAndOrderPreserved() = runTest(mainDispatcher) {
        val clock = virtualClock()
        val smsRepo = FakeSmsRepository(
            clock = clock,
            initialMessages = emptyList(),
            onSend = { _, _, msg -> if (msg == "M1") SmsSendOutcome.Error("500") else SmsSendOutcome.Sent },
        )
        val vm = SmsConversationViewModel(smsRepo, FakeAuthRepository(signedIn = true), idleGateway(clock), clock)
        val events = mutableListOf<SmsConversationEvent>()
        val stateJob = backgroundScope.launch { vm.uiState.collect {} }
        val eventJob = backgroundScope.launch { vm.events.collect { events += it } }

        vm.bindConversation(SmsConversationArgs("20000", "Alice"))
        runCurrent()

        // M1 失败、M2 成功触发 15 秒冷却
        vm.onInputChange("M1"); vm.sendMessage(); runCurrent()
        vm.onInputChange("M2"); vm.sendMessage(); runCurrent()

        assertEquals(15, vm.uiState.value.cooldownSeconds)
        assertEquals(2, smsRepo.sendCallCount.get())
        val m1Id = vm.uiState.value.messages.first().id
        assertEquals(listOf("M1", "M2"), vm.uiState.value.messages.map { it.rawMessage })

        // 冷却期内重发 M1：必须被 ViewModel 前置拦截（删掉 checkCooldownBlocked 行会多一次仓库调用并把 M1 挪到 M2 后 -> 变红）
        vm.resendMessage(m1Id)
        runCurrent()

        assertEquals("冷却期内重发不得调用仓库 sendSms", 2, smsRepo.sendCallCount.get())
        assertEquals("被拦截的重发不得改变原有气泡顺序", listOf("M1", "M2"), vm.uiState.value.messages.map { it.rawMessage })
        assertEquals(SmsBubbleDeliveryStatus.Failed, vm.uiState.value.messages.first().deliveryStatus)
        assertTrue("冷却期内重发必须发出 CooldownBlocked 事件", events.contains(SmsConversationEvent.CooldownBlocked(15)))

        stateJob.cancel()
        eventJob.cancel()
    }

    /** B2（撤销删除钳制）：删除失败气泡后若刷新拉回服务端新消息，点撤销必须把本地失败气泡放在服务端消息之后。 */
    @Test
    fun b2_restoreDeletedFailedBubble_afterRefreshClampsAfterServerMessages() = runTest(mainDispatcher) {
        val clock = virtualClock()
        var serverList = listOf(SmsMessageItem("20000", "Alice", null, "10:00", "旧消息", false, false))
        val smsRepo = FakeSmsRepository(
            clock = clock,
            onLoadDetail = { MessageResult.Success(SmsDetailPage("10000", "HiUser", "fh", serverList, fakePageMeta(), true)) },
            onSend = { _, _, _ -> SmsSendOutcome.Error("offline") },
        )
        val vm = SmsConversationViewModel(smsRepo, FakeAuthRepository(signedIn = true), idleGateway(clock), clock)
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindConversation(SmsConversationArgs("20000", "Alice"))
        runCurrent()
        vm.onInputChange("失败待删"); vm.sendMessage(); runCurrent()

        val deleted = vm.deleteFailedMessage(vm.uiState.value.messages.last().id)
        assertNotNull(deleted)

        serverList = serverList + listOf(
            SmsMessageItem("20000", "Alice", null, "10:05", "对端新消息1", false, false),
            SmsMessageItem("20000", "Alice", null, "10:06", "对端新消息2", false, false),
        )
        vm.refresh()
        runCurrent()
        assertEquals(3, vm.uiState.value.messages.size)

        vm.restoreDeletedMessage(deleted!!)
        assertEquals(
            listOf("旧消息", "对端新消息1", "对端新消息2", "失败待删"),
            vm.uiState.value.messages.map { it.rawMessage ?: it.contentHtml },
        )
        job.cancel()
    }

    /** 连续删除两条失败消息后点撤销：仅第二条的令牌有效，第一条令牌作废，第二条回到锚点原位（位于后续消息之前）。 */
    @Test
    fun b2_consecutiveDeleteTwoFailedThenUndo_restoresOnlySecondAtAnchorAndInvalidatesFirstToken() = runTest(mainDispatcher) {
        val clock = virtualClock()
        val smsRepo = FakeSmsRepository(
            clock = clock,
            initialMessages = listOf(SmsMessageItem("20000", "Alice", null, "10:00", "历史消息", false, false)),
            onSend = { _, _, msg -> if (msg == "尾条成功") SmsSendOutcome.Sent else SmsSendOutcome.Error("500") },
        )
        val vm = SmsConversationViewModel(smsRepo, FakeAuthRepository(signedIn = true), idleGateway(clock), clock)
        val job = backgroundScope.launch { vm.uiState.collect {} }

        vm.bindConversation(SmsConversationArgs("20000", "Alice"))
        runCurrent()

        // 构造序列：[历史消息, 失败1, 失败2, 尾条成功]
        vm.onInputChange("失败1"); vm.sendMessage(); runCurrent()
        vm.onInputChange("失败2"); vm.sendMessage(); runCurrent()
        vm.onInputChange("尾条成功"); vm.sendMessage(); runCurrent()
        assertEquals(listOf("历史消息", "失败1", "失败2", "尾条成功"), vm.uiState.value.messages.map { it.rawMessage ?: it.contentHtml })

        val failed1Id = vm.uiState.value.messages[1].id
        val failed2Id = vm.uiState.value.messages[2].id

        // 连续删除 失败1、失败2
        val token1 = vm.deleteFailedMessage(failed1Id)!!
        val token2 = vm.deleteFailedMessage(failed2Id)!!
        assertEquals(listOf("历史消息", "尾条成功"), vm.uiState.value.messages.map { it.rawMessage ?: it.contentHtml })

        // 过期令牌 token1 必须作废，调用撤销不得恢复 失败1
        vm.restoreDeletedMessage(token1)
        assertEquals(listOf("历史消息", "尾条成功"), vm.uiState.value.messages.map { it.rawMessage ?: it.contentHtml })

        // 撤销当前令牌 token2 -> 只恢复 失败2，且精确回到锚点（历史消息之后、尾条成功之前）
        vm.restoreDeletedMessage(token2)
        assertEquals(
            "连续删除两条后点撤销只能恢复第二条且必须回到原锚点位置",
            listOf("历史消息", "失败2", "尾条成功"),
            vm.uiState.value.messages.map { it.rawMessage ?: it.contentHtml },
        )

        // 令牌一次性消费后再次调用不得重复插入
        vm.restoreDeletedMessage(token2)
        assertEquals(listOf("历史消息", "失败2", "尾条成功"), vm.uiState.value.messages.map { it.rawMessage ?: it.contentHtml })
        job.cancel()
    }

    /** B3（跨账号切换与退出登录隔离 + 纯 uid 回填对端用户名）：A 切到 B 或退登后绝不可见 A 的任何消息正文与草稿。 */
    @Test
    fun b3_switchAccountAndLogout_clearsMessagesAndDraft_andBackfillsPeerUsername() = runTest(mainDispatcher) {
        val clock = virtualClock()
        val accountA = SavedAccount("10001", "UserA", "a".repeat(32), lastLoginAt = 100L)
        val accountB = SavedAccount("10002", "UserB", "b".repeat(32), lastLoginAt = 200L)
        val authRepo = FakeAuthRepository(signedIn = true, initialAccount = accountA)
        var bShouldFail = true
        val smsRepo = FakeSmsRepository(
            clock = clock,
            onLoadDetail = {
                when (authRepo.currentAccount.value?.uid) {
                    accountA.uid -> MessageResult.Success(
                        SmsDetailPage(
                            "10001", "UserA", "fhA",
                            listOf(
                                SmsMessageItem("10001", "UserA", null, "09:00", "账号A发出私密消息", false, isMine = true),
                                SmsMessageItem("20000", "PeerAlice", null, "09:01", "账号A收到私密消息", false, isMine = false),
                            ),
                            fakePageMeta(), true,
                        ),
                    )
                    accountB.uid -> if (bShouldFail) {
                        MessageResult.Error("network down")
                    } else {
                        MessageResult.Success(
                            SmsDetailPage(
                                "10002", "UserB", "fhB",
                                listOf(SmsMessageItem("20000", "PeerAlice", null, "10:00", "账号B专属消息", false, isMine = false)),
                                fakePageMeta(), true,
                            ),
                        )
                    }
                    else -> MessageResult.AuthRequired()
                }
            },
        )
        val vm = SmsConversationViewModel(smsRepo, authRepo, idleGateway(clock), clock)
        val job = backgroundScope.launch { vm.uiState.collect {} }

        // 1. 纯 uid 进入（username 留空）-> 加载后应自动回填对端 author "PeerAlice"
        vm.bindConversation(SmsConversationArgs(uid = "20000", username = ""))
        runCurrent()
        assertEquals("PeerAlice", vm.uiState.value.peerUsername)
        assertEquals(2, vm.uiState.value.messages.size)

        vm.onInputChange("账号A未发送私密草稿")
        assertEquals("账号A未发送私密草稿", vm.uiState.value.inputText)

        // 2. 直接切到账号 B（即使 B 首屏网络失败）-> 必须立即清空 A 的消息与草稿，绝不残留
        authRepo.signIn(accountB)
        runCurrent()
        assertTrue("切号后绝不能残留账号 A 的任何短消息", vm.uiState.value.messages.isEmpty())
        assertEquals("切号后绝不能残留账号 A 的草稿", "", vm.uiState.value.inputText)
        assertNotNull(vm.uiState.value.error)

        // 3. 账号 B 重试加载成功并写草稿，随后退出登录 -> 必须同时清空消息与草稿
        bShouldFail = false
        vm.refresh()
        runCurrent()
        assertEquals(listOf("账号B专属消息"), vm.uiState.value.messages.map { it.contentHtml })
        vm.onInputChange("账号B待发草稿")

        authRepo.signOut()
        runCurrent()
        assertTrue("退登后消息列表必须清空", vm.uiState.value.messages.isEmpty())
        assertEquals("退登后草稿必须清空", "", vm.uiState.value.inputText)
        assertEquals(UiErrorAction.Login, vm.uiState.value.error?.action)
        job.cancel()
    }

    private companion object {
        const val BASE_EPOCH_MS = 1_700_000_000_000L
    }
}
