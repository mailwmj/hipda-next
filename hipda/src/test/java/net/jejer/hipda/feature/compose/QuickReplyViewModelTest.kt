package net.jejer.hipda.feature.compose

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jejer.hipda.R
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.local.HiDatabase
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.post.DefaultPostRepository
import net.jejer.hipda.data.post.PostSubmitRequest
import net.jejer.hipda.data.post.PostSubmitResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.feature.common.UiErrorAction
import okhttp3.OkHttp
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.Charset

/**
 * E5 ViewModel 核心行为、草稿自动保存/恢复、必修 `R-D6 S2`（清空旧草稿显式删除）、30 秒冷却、
 * 提交失败留原地保输入、交接契约与真实 `DefaultPostRepository` + `MockWebServer` 闭环单测。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class QuickReplyViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 必修 `R-D6 S2`（撤回必红）：恢复旧草稿后手动删空文字退出，必须显式调用 `deleteDraft` 杜绝旧草稿复活。 */
    @Test
    fun rd6S2_restoredDraftManuallyClearedAndDismissed_explicitlyDeletesDraftAndNeverResurrects() = runTest(testDispatcher) {
        val draftKey = DraftKey.reply("2605001")
        val repo = FakePostRepository(
            initialDrafts = mapOf(
                draftKey.value to DraftEntity(draftKey.value, content = "旧草稿内容", updatedAt = 1_000L),
            ),
        )
        val vm = QuickReplyViewModel(repo, FakeQuickReplyAuthRepository(), clockMs = { 10_000L })
        val events = mutableListOf<QuickReplyEvent>()
        val collectJob = launch { vm.events.collect { events += it } }

        // 1. 打开恢复旧草稿
        vm.open(QuickReplyTarget(tid = "2605001", fid = 2))
        advanceUntilIdle()
        assertEquals("旧草稿内容", vm.uiState.value.text)
        assertTrue(vm.uiState.value.hadPersistedDraft)
        assertTrue(vm.uiState.value.showDraftRestoredBanner)

        // 2. 用户手动删空全部文字（含仅剩空白场景）后退出面板
        vm.onTextChanged("   ")
        vm.onDismissSession()
        advanceUntilIdle()

        assertEquals("R-D6 S2：恢复旧草稿后删空退出必须显式调用 deleteDraft", listOf(draftKey), repo.deleteDraftCalls.toList())
        assertNull("数据库中旧草稿必须已彻底删除", repo.draftFor(draftKey))
        assertTrue("删空退出不得弹「已保存草稿」提示", events.isEmpty())

        // 3. 再次打开同 tid 面板：输入为空、不再恢复旧草稿、不显示恢复横条
        vm.open(QuickReplyTarget(tid = "2605001", fid = 2))
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.text)
        assertFalse(vm.uiState.value.hadPersistedDraft)
        assertFalse(vm.uiState.value.showDraftRestoredBanner)
        collectJob.cancel()
    }

    /** 必修 `R-D6 S2` 路径 2：点恢复横条 `清空` 按钮后退出，同样显式调用 `deleteDraft` 且下次重开为空。 */
    @Test
    fun rd6S2_clickClearBannerButtonThenDismiss_deletesDraftImmediately() = runTest(testDispatcher) {
        val draftKey = DraftKey.reply("2605002")
        val repo = FakePostRepository(
            initialDrafts = mapOf(
                draftKey.value to DraftEntity(draftKey.value, content = "待清空草稿", quotePid = "301", updatedAt = 1_000L),
            ),
        )
        val vm = QuickReplyViewModel(repo, FakeQuickReplyAuthRepository(), clockMs = { 10_000L })

        vm.open(QuickReplyTarget(tid = "2605002", fid = 2))
        advanceUntilIdle()
        assertEquals("待清空草稿", vm.uiState.value.text)
        assertEquals("301", vm.uiState.value.target.targetPid)
        assertEquals(QuickReplyMode.QuotePost, vm.uiState.value.target.mode)

        vm.clearRestoredDraft()
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.text)
        assertFalse(vm.uiState.value.showDraftRestoredBanner)
        assertEquals(listOf(draftKey), repo.deleteDraftCalls.toList())
        assertNull(repo.draftFor(draftKey))

        vm.onDismissSession()
        advanceUntilIdle()
        vm.open(QuickReplyTarget(tid = "2605002", fid = 2))
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.text)
        assertFalse(vm.uiState.value.showDraftRestoredBanner)
    }

    @Test
    fun draftAutoSave_blankNoOp_nonEmptySavesAndDiscardDeletes_sameAsLastSubmittedSkips() = runTest(testDispatcher) {
        val draftKey = DraftKey.reply("2605003")
        val repo = FakePostRepository()
        val vm = QuickReplyViewModel(repo, FakeQuickReplyAuthRepository(), clockMs = { 10_000L })
        val events = mutableListOf<QuickReplyEvent>()
        val collectJob = launch { vm.events.collect { events += it } }

        // 1. 全新空白退出：不写库、不删库、不弹提示
        vm.open(QuickReplyTarget(tid = "2605003", fid = 2))
        advanceUntilIdle()
        vm.onDismissSession()
        advanceUntilIdle()
        assertTrue(repo.saveDraftCalls.isEmpty())
        assertTrue(repo.deleteDraftCalls.isEmpty())
        assertTrue(events.isEmpty())

        // 2. 输入非空内容退出：静默保存并触发 DraftSaved；点放弃立即删除
        vm.open(QuickReplyTarget(tid = "2605003", fid = 2, mode = QuickReplyMode.QuotePost, targetPid = "505", targetAuthor = "geek", targetFloor = 5))
        advanceUntilIdle()
        vm.onTextChanged("半截回复内容")
        vm.onDismissSession()
        advanceUntilIdle()
        assertEquals(1, repo.saveDraftCalls.size)
        assertEquals("半截回复内容", repo.draftFor(draftKey)?.content)
        assertEquals("505", repo.draftFor(draftKey)?.quotePid)
        assertEquals(listOf(QuickReplyEvent.DraftSaved(draftKey)), events)

        vm.discardSavedDraft(draftKey)
        advanceUntilIdle()
        assertNull(repo.draftFor(draftKey))

        // 3. 发送成功后再输入完全相同正文退出：按 §7.2 不保存不提示
        events.clear()
        vm.open(QuickReplyTarget(tid = "2605003", fid = 2))
        advanceUntilIdle()
        vm.onTextChanged("刚刚发过的内容")
        vm.submit()
        advanceUntilIdle()
        assertTrue(events.last() is QuickReplyEvent.Submitted)

        events.clear()
        vm.open(QuickReplyTarget(tid = "2605003", fid = 2))
        advanceUntilIdle()
        vm.onTextChanged("刚刚发过的内容")
        vm.onDismissSession()
        advanceUntilIdle()
        assertEquals("与上次发出相同内容不得再次保存", 1, repo.saveDraftCalls.size)
        assertTrue("与上次发出相同内容不得弹保存提示", events.isEmpty())
        collectJob.cancel()
    }

    @Test
    fun cooldown30s_roundsUp_disablesSendWhileAllowingEditing_andAutoRecoversAtZero() = runTest(testDispatcher) {
        var now = 10_000L
        val repo = FakePostRepository(nowMs = { now })
        val vm = QuickReplyViewModel(repo, FakeQuickReplyAuthRepository(), clockMs = { now })

        vm.open(QuickReplyTarget(tid = "2605004", fid = 2))
        advanceUntilIdle()
        vm.onTextChanged("冷却期间继续编辑")
        assertTrue(vm.uiState.value.canSend)

        // 激活 30 秒冷却（剩 29_001ms 应向上取整为 30 秒）
        repo.setCooldownUntil(now + 29_001L)
        advanceTimeBy(1L)
        assertEquals(30, vm.uiState.value.cooldownSeconds)
        assertFalse("冷却期间发送按钮必须禁用", vm.uiState.value.canSend)

        // 冷却期间可继续编辑文本
        vm.onTextChanged("冷却期间继续编辑——追加内容")
        assertEquals("冷却期间继续编辑——追加内容", vm.uiState.value.text)

        // 剩 1ms 时向上取整仍为 1 秒
        now += 29_000L
        advanceTimeBy(29_000L)
        assertEquals(1, vm.uiState.value.cooldownSeconds)
        assertFalse(vm.uiState.value.canSend)

        // 归零自动恢复可发送
        now += 1_000L
        advanceTimeBy(1_000L)
        assertEquals(0, vm.uiState.value.cooldownSeconds)
        assertTrue("冷却归零后应自动恢复可发送", vm.uiState.value.canSend)
    }

    @Test
    fun submitLifecycle_zeroMsSpinner_failurePreservesInput_andHandoffPreservesAllFieldsWithoutDraftToast() = runTest(testDispatcher) {
        val gate = CompletableDeferred<PostSubmitResult>()
        val repo = FakePostRepository().apply {
            submitHandler = { gate.await() }
        }
        val vm = QuickReplyViewModel(repo, FakeQuickReplyAuthRepository(), clockMs = { 10_000L })
        val events = mutableListOf<QuickReplyEvent>()
        val collectJob = launch { vm.events.collect { events += it } }

        vm.open(QuickReplyTarget(tid = "2605005", fid = 2, mode = QuickReplyMode.QuotePost, targetPid = "808", targetAuthor = "alice", targetFloor = 8))
        advanceUntilIdle()
        vm.onTextChanged("重要长回复，失败绝不能丢")

        // 0ms 立即进入 isSubmitting=true
        vm.submit()
        assertTrue("点击发送 0ms 内必须切换为 isSubmitting", vm.uiState.value.isSubmitting)
        assertFalse(vm.uiState.value.canSend)

        // 失败留在面板原地提示原因且保留全部输入
        gate.complete(PostSubmitResult.NetworkError("SocketTimeoutException: timeout"))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSubmitting)
        assertEquals("重要长回复，失败绝不能丢", vm.uiState.value.text)
        assertEquals(R.string.compose_error_send_failed, vm.uiState.value.inlineError?.uiError?.messageRes)
        assertEquals(UiErrorAction.Retry, vm.uiState.value.inlineError?.uiError?.action)

        // AuthRequired / ChallengeRequired 错误映射校验
        val authErr = QuickReplyViewModel.mapSubmitFailure(PostSubmitResult.AuthRequired(AuthRequiredReason.RELOGIN_FAILED))
        assertEquals(R.string.common_error_session_expired, authErr.messageRes)
        assertEquals(UiErrorAction.Login, authErr.action)
        val chErr = QuickReplyViewModel.mapSubmitFailure(PostSubmitResult.ChallengeRequired(rejected = true))
        assertEquals(R.string.challenge_status_rejected, chErr.messageRes)
        assertEquals(UiErrorAction.Reverify, chErr.action)

        // 展开全屏 / 插入图片交接：无损携带文本、光标选区、引用楼层，且绝不触发 DraftSaved 提示
        val handoffFull = vm.onHandoffToFullEditor("重要长回复，失败绝不能丢", selectionStart = 2, selectionEnd = 5, openImagePickerImmediately = false)
        assertEquals(
            FullEditorHandoff(
                tid = "2605005", fid = 2, text = "重要长回复，失败绝不能丢",
                selectionStart = 2, selectionEnd = 5, quotePid = "808", quoteAuthor = "alice", quoteFloor = 8,
                openImagePickerImmediately = false,
            ),
            handoffFull,
        )
        assertTrue("交接完整编辑器不得重复触发保存草稿提示", events.none { it is QuickReplyEvent.DraftSaved })
        assertTrue("交接完整编辑器不得重复调用 saveDraft", repo.saveDraftCalls.isEmpty())

        // ReplyPost 交接必须保留 replyPid 与 threadAuthor，且构建 ReplyPost 提交请求（R-E5 S2）
        vm.open(QuickReplyTarget(tid = "2605005", fid = 2, mode = QuickReplyMode.ReplyPost, targetPid = "809", targetAuthor = "bob", targetFloor = 9, threadAuthor = "op_user"))
        advanceUntilIdle()
        val handoffReplyPost = vm.onHandoffToFullEditor("回复该楼", 0, 4, openImagePickerImmediately = true)
        assertEquals(
            FullEditorHandoff(
                tid = "2605005", fid = 2, text = "回复该楼",
                selectionStart = 0, selectionEnd = 4, quotePid = null, quoteAuthor = "bob", quoteFloor = 9,
                openImagePickerImmediately = true, replyPid = "809", threadAuthor = "op_user",
            ),
            handoffReplyPost,
        )
        val replyPostReq = QuickReplyViewModel.buildSubmitRequest(
            QuickReplyTarget(tid = "2605005", fid = 2, mode = QuickReplyMode.ReplyPost, targetPid = "809"),
            "回复该楼",
            DraftKey.reply("2605005"),
        )
        assertTrue(replyPostReq is PostSubmitRequest.ReplyPost && replyPostReq.pid == "809")
        collectJob.cancel()
    }

    /** 真实 `DefaultPostRepository` + Room `DraftDao` + `MockWebServer` 离线端到端提交与草稿/冷却闭环（无 `Thread.sleep`，R-E5 S3）。 */
    @Test
    fun realDefaultPostRepository_withMockWebServer_submitsReplyDeletesDraftAndStarts30sCooldown() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        val server = MockWebServer()
        server.start()
        val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vmStore = ViewModelStore()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), HiDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        val okHttp = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(),
            userAgentProvider = UserAgentProvider { "HiPDA-Test-UA" },
            transportInterceptor = TransportInterceptor(delegateFactory = { null }, isMainThread = { false }),
        )
        try {
            val virtualClockMs = AtomicLong(50_000L)
            val clock: () -> Long = { virtualClockMs.get() }
            val baseUrl = server.url("").toString().trimEnd('/')
            val authRepo = FakeQuickReplyAuthRepository()
            val settingsRepo = object : SettingsRepository {
                override val settings: Flow<AppSettings> = MutableStateFlow(AppSettings(forumServer = baseUrl, tailEnabled = false))
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
            val gateway = SiteAccessGateway(okHttp, ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
            val httpClient = ForumHttpClient(gateway, settingsRepo, authRepo)
            val realPostRepo = DefaultPostRepository(httpClient, authRepo, settingsRepo, db.draftDao(), nowMs = clock, scope = repoScope)

            // 先预置一条真实 Room 草稿
            val key = DraftKey.reply("2605010")
            realPostRepo.saveDraft(key, content = "Room 旧草稿")
            val vm = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = QuickReplyViewModel(realPostRepo, authRepo, clockMs = clock) as T
                },
            )[QuickReplyViewModel::class.java]

            // 1. R-D6 S2 真实 Room 验证：恢复后清空退出彻底删除 Room 记录
            vm.open(QuickReplyTarget(tid = "2605010", fid = 2))
            assertEquals("Room 旧草稿", vm.uiState.value.text)
            vm.onTextChanged("")
            vm.onDismissSession()
            vm.open(QuickReplyTarget(tid = "2605010", fid = 2))
            assertEquals("真实 Room 旧草稿删空退出后不得复活", "", vm.uiState.value.text)

            // 2. 提交回复走真实 PrePost GET + GBK POST -> 成功删除草稿并进入 30 秒冷却
            val gbk = Charset.forName("GBK")
            val prePostHtml = """
                <html><body>
                <div id="umenu"><cite><a href="space.php?uid=1001">linus_hi</a></cite></div>
                <form id="postform" method="post" action="post.php?action=reply&tid=2605010&replysubmit=yes">
                    <input type="hidden" name="formhash" value="deadbeef" />
                    <input type="hidden" name="posttime" value="1700000000" />
                    <textarea name="message"></textarea>
                </form></body></html>
            """.trimIndent()
            val redirectedHtml = """
                <html><head><title>测试主题 - Discovery</title></head><body>
                <div id="umenu"><cite><a href="space.php?uid=1001">linus_hi</a></cite></div>
                <div id="postlist">
                    <div id="post_9001"><div class="postinfo"><a href="space.php?uid=1001">linus_hi</a><strong><a id="postnum9001"><em>2</em></a></strong></div>
                    <div class="authorinfo"><em>发表于 2026-11-26 12:30</em></div>
                    <td class="t_msgfont" id="postmessage_9001">真实 MockWebServer 回复</td></div>
                </div></body></html>
            """.trimIndent()
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html; charset=gbk").body(Buffer().write(prePostHtml.toByteArray(gbk))).build())
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "viewthread.php?tid=2605010&page=1#pid9001").build())
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html; charset=gbk").body(Buffer().write(redirectedHtml.toByteArray(gbk))).build())

            val submittedDeferred = CompletableDeferred<QuickReplyEvent.Submitted>()
            val collectJob = launch {
                vm.events.filterIsInstance<QuickReplyEvent.Submitted>().first().let(submittedDeferred::complete)
            }
            vm.onTextChanged("真实 MockWebServer 回复")
            vm.submit()
            val submitted = withTimeout(5_000L) { submittedDeferred.await() }
            assertEquals("2605010", submitted.tid)
            assertEquals("提交成功后仓库应进入 30 秒冷却", 30, realPostRepo.getRemainingCooldownSeconds())
            assertEquals("提交成功后 UI 应进入 30 秒冷却", 30, vm.uiState.value.cooldownSeconds)
            virtualClockMs.addAndGet(31_000L)
            assertEquals("虚拟时钟推进 31 秒后仓库冷却归零", 0, realPostRepo.getRemainingCooldownSeconds())
            collectJob.cancel()
        } finally {
            vmStore.clear()
            repoScope.cancel()
            db.close()
            okHttp.dispatcher.cancelAll()
            okHttp.dispatcher.executorService.shutdownNow()
            okHttp.connectionPool.evictAll()
            server.close()
        }
    }
}
