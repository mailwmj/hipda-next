package net.jejer.hipda.feature.forum

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.local.BrowseHistoryEntity
import net.jejer.hipda.data.local.HiDatabase
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.search.MyRepliesResult
import net.jejer.hipda.data.search.MyThreadsResult
import net.jejer.hipda.data.search.RemovedSearchHistoryEntry
import net.jejer.hipda.data.search.ResolvedForumLink
import net.jejer.hipda.data.search.SearchError
import net.jejer.hipda.data.search.SearchRepository
import net.jejer.hipda.data.search.SearchResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.data.threadlist.DefaultThreadListRepository
import net.jejer.hipda.data.threadlist.ThreadListBackfillStopReason
import net.jejer.hipda.data.threadlist.ThreadListBatchResult
import net.jejer.hipda.data.threadlist.ThreadListError
import net.jejer.hipda.data.threadlist.ThreadListItem
import net.jejer.hipda.data.threadlist.ThreadListLoadResult
import net.jejer.hipda.data.threadlist.ThreadListLoadState
import net.jejer.hipda.data.threadlist.ThreadListQuery
import net.jejer.hipda.data.threadlist.ThreadListRepository
import net.jejer.hipda.data.user.BlacklistMutationResult
import net.jejer.hipda.data.user.BlacklistRepository
import net.jejer.hipda.data.user.BlacklistSyncResult
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.threadlist.ThreadSummary
import okhttp3.OkHttpClient
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ForumViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 验收 1（B4-E1 ①）：冷启动 currentAccount=null 报 AuthRequired 后，Tink 异步发射 SavedAccount 自动重拉恢复。 */
    @Test
    fun accountAsyncHydration_nullToSavedAccount_recoversFromAuthRequiredAutomatically() = runTest {
        val authRepo = TestAuthRepository(initialAccount = null)
        val settingsRepo = TestSettingsRepository()
        val threadRepo = TestThreadListRepository { query ->
            if (authRepo.currentAccount.value == null) {
                ThreadListError.AuthRequired(AuthRequiredReason.NOT_LOGGED_IN)
            } else {
                successBatch(query, listOf(summary("2001", "登录后恢复的真实帖子")))
            }
        }
        val searchRepo = TestSearchRepository()
        val vm = ForumViewModel(threadRepo, searchRepo, settingsRepo, authRepo, idleGateway())
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertTrue("初始未水合时应处于空列表错误态", vm.uiState.value.items.isEmpty())
        assertEquals(R.string.common_error_login_required, vm.uiState.value.error?.messageRes)
        assertEquals(UiErrorAction.Login, vm.uiState.value.error?.action)

        // Tink 异步水合完成，发射 SavedAccount
        authRepo.signIn(SavedAccount(uid = "42", username = "HiUser", passwordMd5 = "0".repeat(32), lastLoginAt = 100L))
        advanceUntilIdle()

        assertNull("水合自动重拉成功后不应滞留错误", vm.uiState.value.error)
        assertEquals(listOf("2001"), vm.uiState.value.items.map(ThreadListItem::tid))
        assertEquals(2, threadRepo.refreshQueries.size)
        collector.cancel()
    }

    /** 验收 1（B4-E1 ①）：同账号会话过期后重新登录（SessionExpired → SignedIn / epoch 递增）与切号均自动重拉。 */
    @Test
    fun sameAccountReloginAndAccountSwitch_automaticallyRecoverAndReload() = runTest {
        val accountA = SavedAccount(uid = "42", username = "UserA", passwordMd5 = "a".repeat(32), lastLoginAt = 100L)
        val accountB = SavedAccount(uid = "99", username = "UserB", passwordMd5 = "b".repeat(32), lastLoginAt = 200L)
        val authRepo = TestAuthRepository(initialAccount = accountA)
        val settingsRepo = TestSettingsRepository()
        var sessionValid = false
        val threadRepo = TestThreadListRepository { query ->
            if (!sessionValid) {
                ThreadListError.AuthRequired(AuthRequiredReason.RELOGIN_FAILED)
            } else {
                val uid = authRepo.currentAccount.value?.uid ?: "guest"
                successBatch(query, listOf(summary("3001", "账号 $uid 的帖子")))
            }
        }
        val vm = ForumViewModel(threadRepo, TestSearchRepository(), settingsRepo, authRepo, idleGateway())
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(R.string.common_error_session_expired, vm.uiState.value.error?.messageRes)

        // 同账号 E15 重新登录成功（epoch 递增 + SessionExpired -> SignedIn）
        authRepo.markSessionExpired(accountA)
        sessionValid = true
        authRepo.reloginSameAccount(accountA.copy(lastLoginAt = 150L))
        advanceUntilIdle()

        assertNull("同账号重新登录后应自动恢复", vm.uiState.value.error)
        assertEquals("账号 42 的帖子", vm.uiState.value.items.single().summary.title)

        // 切换为账号 B
        authRepo.signIn(accountB)
        advanceUntilIdle()

        assertNull(vm.uiState.value.error)
        assertEquals("账号 99 的帖子", vm.uiState.value.items.single().summary.title)
        collector.cancel()
    }

    /** 闭合 R-E0 R2-S1：退登/换号同步清空 todayNewState，防止残留上一账号列表遮蔽「需要登录才能查看」。 */
    @Test
    fun logoutAndAccountSwitch_resetTodayNewStateAndShowLoginErrorInsteadOfStaleItems() = runTest {
        val accountA = SavedAccount(uid = "42", username = "UserA", passwordMd5 = "a".repeat(32), lastLoginAt = 100L)
        val authRepo = TestAuthRepository(initialAccount = accountA)
        val searchRepo = TestSearchRepository { page, _ ->
            if (authRepo.currentAccount.value == null) {
                SearchError.AuthRequired(AuthRequiredReason.MANUALLY_LOGGED_OUT)
            } else {
                SearchResult.Success(
                    page = page,
                    fullText = false,
                    searchPage = SearchPage(
                        searchId = "sid-a",
                        maxPage = 2,
                        items = listOf(SearchItem("9001", null, "账号A的今日新帖", null, "作者A", "1", null, "刚刚", "Discovery")),
                        meta = emptyMeta(),
                    ),
                )
            }
        }
        val vm = ForumViewModel(TestThreadListRepository(), searchRepo, TestSettingsRepository(), authRepo, idleGateway())
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        vm.bindSource(ForumListSource.TodayNew)
        advanceUntilIdle()

        assertEquals(listOf("9001"), vm.uiState.value.items.map(ThreadListItem::tid))

        // 手动退出登录：todayNewState 必须同步清空并展示 AuthRequired 错误页
        authRepo.logout()
        advanceUntilIdle()

        assertTrue("退登后不得残留旧账号今日新帖条目", vm.uiState.value.items.isEmpty())
        assertEquals(R.string.common_error_login_required, vm.uiState.value.error?.messageRes)
        assertEquals(UiErrorAction.Login, vm.uiState.value.error?.action)
        collector.cancel()
    }

    /** 验收 3（B4-E1 ③）+ R-E0 R2-S1：今日新帖翻页原样携带 searchId，中间页省略 searchId 时仍保留旧值。 */
    @Test
    fun todayNewPagination_carriesSearchIdAndRetainsPreviousSearchIdWhenPageOmitsIt() = runTest {
        val recordedCalls = CopyOnWriteArrayList<Pair<Int, String?>>()
        val searchRepo = TestSearchRepository { page, searchId ->
            recordedCalls += page to searchId
            when (page) {
                1 -> SearchResult.Success(
                    page = 1,
                    fullText = false,
                    searchPage = SearchPage(
                        searchId = "sid-initial-777",
                        maxPage = 3,
                        items = listOf(SearchItem("9101", null, "第1页新帖", null, "甲", "1", null, "1分钟前", "Discovery")),
                        meta = emptyMeta(),
                    ),
                )
                2 -> SearchResult.Success(
                    page = 2,
                    fullText = false,
                    searchPage = SearchPage(
                        searchId = null, // 极端模板第 2 页未回传 searchId，应保留 cur.searchId
                        maxPage = 3,
                        items = listOf(SearchItem("9102", null, "第2页新帖", null, "乙", "2", null, "2分钟前", "Geek Talks")),
                        meta = emptyMeta(),
                    ),
                )
                else -> SearchResult.Success(
                    page = 3,
                    fullText = false,
                    searchPage = SearchPage(
                        searchId = "sid-initial-777",
                        maxPage = 3,
                        items = listOf(SearchItem("9103", null, "第3页新帖", null, "丙", "3", null, "3分钟前", "Buy & Sell")),
                        meta = emptyMeta(),
                    ),
                )
            }
        }
        val vm = ForumViewModel(TestThreadListRepository(), searchRepo, TestSettingsRepository(), TestAuthRepository(), idleGateway())
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        vm.bindSource(ForumListSource.TodayNew)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()

        assertEquals(
            listOf(1 to null, 2 to "sid-initial-777", 3 to "sid-initial-777"),
            recordedCalls.toList(),
        )
        assertEquals(listOf("9101", "9102", "9103"), vm.uiState.value.items.map(ThreadListItem::tid))
        assertFalse("到达第 3 / 3 页后 hasMore 应为 false", vm.uiState.value.hasMore)
        collector.cancel()
    }

    /** 验收 3（B4-E1 ③）：今日新帖接入真实 DefaultThreadListRepository.observeFiltered，黑名单隐藏与 Room 已读变灰即时响应且 0 额外请求。 */
    @Test
    fun todayNew_reactsImmediatelyToBlacklistAndBrowseHistoryWithZeroExtraRequests() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val directExecutor = Executor { it.run() }
        val db = Room.inMemoryDatabaseBuilder(context, HiDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        try {
            val settingsRepo = TestSettingsRepository()
            val authRepo = TestAuthRepository()
            val blacklistRepo = TestBlacklistRepository()
            val realThreadRepo = DefaultThreadListRepository(
                forumHttpClient = ForumHttpClient(idleGateway(), settingsRepo, authRepo),
                authRepository = authRepo,
                settingsRepository = settingsRepo,
                blacklistRepository = blacklistRepo,
                browseHistoryDao = db.browseHistoryDao(),
            )
            val newPostsCallCount = AtomicInteger(0)
            val searchRepo = TestSearchRepository { page, _ ->
                newPostsCallCount.incrementAndGet()
                SearchResult.Success(
                    page = page,
                    fullText = false,
                    searchPage = SearchPage(
                        searchId = "sid-live",
                        maxPage = 1,
                        items = listOf(
                            SearchItem("9201", null, "正常作者帖子", null, "GoodUser", "10", null, "刚刚", "Discovery"),
                            SearchItem("9202", null, "将被拉黑作者帖子", null, "Spammer", "11", null, "1分钟前", "Discovery"),
                        ),
                        meta = emptyMeta(),
                    ),
                )
            }
            val vm = ForumViewModel(realThreadRepo, searchRepo, settingsRepo, authRepo, idleGateway())
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            vm.bindSource(ForumListSource.TodayNew)
            advanceUntilIdle()
            val loadedState = withTimeout(5_000L) {
                vm.uiState.first { it.items.map(ThreadListItem::tid) == listOf("9201", "9202") }
            }

            assertEquals(listOf("9201", "9202"), loadedState.items.map(ThreadListItem::tid))
            assertFalse(loadedState.items.first().isVisited)
            val callsBefore = newPostsCallCount.get()

            // 1. 黑名单加入 Spammer：立即隐藏 9202，0 额外请求
            blacklistRepo.setBlocked(setOf("Spammer"))
            advanceUntilIdle()
            val afterBlacklist = withTimeout(5_000L) {
                vm.uiState.first { it.items.map(ThreadListItem::tid) == listOf("9201") }
            }
            assertEquals(listOf("9201"), afterBlacklist.items.map(ThreadListItem::tid))
            assertEquals(callsBefore, newPostsCallCount.get())

            // 2. 浏览历史写入 9201：立即切为 isVisited=true，0 额外请求
            db.browseHistoryDao().upsert(
                BrowseHistoryEntity(
                    tid = "9201",
                    title = "正常作者帖子",
                    fid = "2",
                    forumName = "Discovery",
                    author = "GoodUser",
                    lastVisitedAt = 1000L,
                ),
            )
            advanceUntilIdle()
            val afterVisited = withTimeout(5_000L) {
                vm.uiState.first { it.items.singleOrNull()?.isVisited == true }
            }
            assertTrue("写入 BrowseHistoryDao 后今日新帖对应项应立刻切换已读态", afterVisited.items.single().isVisited)
            assertEquals(callsBefore, newPostsCallCount.get())

            collector.cancel()
        } finally {
            db.close()
        }
    }

    /** 闭合 R-E0 S3：慢网下切版块绝不闪现「暂无帖子」空态，且新版块请求不被旧慢请求串行阻塞。 */
    @Test
    fun slowNetworkSourceSwitch_neverFlashesEmptyStateAndStartsNewForumImmediately() = runTest {
        val standardDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(standardDispatcher)
        try {
            val gateForum2 = CompletableDeferred<Unit>()
            val gateForum6 = CompletableDeferred<Unit>()
            val threadRepo = TestThreadListRepository { query ->
                when (query.fid) {
                    2 -> {
                        gateForum2.await()
                        successBatch(query, listOf(summary("2001", "版块2帖子")))
                    }
                    6 -> {
                        gateForum6.await()
                        successBatch(query, listOf(summary("6001", "版块6帖子")))
                    }
                    else -> successBatch(query, emptyList(), stopReason = ThreadListBackfillStopReason.EMPTY_PAGE)
                }
            }
            val vm = ForumViewModel(threadRepo, TestSearchRepository(), TestSettingsRepository(), TestAuthRepository(), idleGateway())
            val observedStates = mutableListOf<ForumUiState>()
            val collector = backgroundScope.launch { vm.uiState.collect { observedStates += it } }
            advanceUntilIdle()

            // Forum(2) 仍在慢网挂起期间立即切到 Forum(6)
            vm.bindSource(ForumListSource.Forum(6))
            advanceUntilIdle()

            assertTrue(
                "Forum(2) 慢网阻塞期间切到 Forum(6)，必须立刻发出 Forum(6) 请求而非排队等 Forum(2)",
                threadRepo.refreshQueries.any { it.fid == 6 },
            )
            val flashedEmptyDuringSwitch = observedStates.any { state ->
                state.source == ForumListSource.Forum(6) &&
                    state.items.isEmpty() &&
                    !state.isLoading &&
                    state.error == null
            }
            assertFalse("慢网切换版块全过程严禁闪现 items.isEmpty() && !isLoading 空态", flashedEmptyDuringSwitch)

            gateForum6.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("6001"), vm.uiState.value.items.map(ThreadListItem::tid))
            assertFalse(vm.uiState.value.isLoading)

            // 切至真正无帖的空版块 7：加载完成后才正常显示空态
            vm.bindSource(ForumListSource.Forum(7))
            advanceUntilIdle()
            assertTrue(vm.uiState.value.items.isEmpty())
            assertFalse(vm.uiState.value.isLoading)
            assertNull(vm.uiState.value.error)

            gateForum2.complete(Unit)
            collector.cancel()
        } finally {
            Dispatchers.setMain(testDispatcher)
        }
    }

    /** 验收 4（R-D4 S1）：排序切换经 SettingsRepository 按版块持久化并显式传入 sortByPostTime，今日新帖下忽略。 */
    @Test
    fun sortByPostTime_persistsPerForumAndPassesExplicitBooleanToRepository() = runTest {
        val settingsRepo = TestSettingsRepository()
        val threadRepo = TestThreadListRepository { query ->
            val label = if (query.sortByPostTime == true) "按发帖" else "按回复"
            successBatch(query, listOf(summary("${query.fid}01", "$label 帖子")))
        }
        val vm = ForumViewModel(threadRepo, TestSearchRepository(), settingsRepo, TestAuthRepository(), idleGateway())
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.sortByPostTime)
        assertTrue("R-D4 S1：observeThreadList 必须传非 null sortByPostTime", threadRepo.observedQueries.all { it.sortByPostTime != null })
        assertEquals(false, threadRepo.refreshQueries.last().sortByPostTime)

        // 切换为按发帖排序
        vm.setSortByPostTime(true)
        advanceUntilIdle()

        assertTrue(settingsRepo.settingsFlow.value.isSortByPostTime(2))
        assertTrue(vm.uiState.value.sortByPostTime)
        assertEquals(true, threadRepo.refreshQueries.last().sortByPostTime)
        assertEquals("按发帖 帖子", vm.uiState.value.items.single().summary.title)

        // loadMore 同样显式传入 sortByPostTime = true
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(true, threadRepo.loadMoreQueries.last().sortByPostTime)

        // 切至今日新帖后调用 setSortByPostTime 不写设置也不崩
        vm.bindSource(ForumListSource.TodayNew)
        advanceUntilIdle()
        vm.setSortByPostTime(false)
        advanceUntilIdle()
        assertTrue("今日新帖下切换排序不得改写版块 2 持久化设置", settingsRepo.settingsFlow.value.isSortByPostTime(2))

        collector.cancel()
    }

    /** 验收 4：下拉刷新按 tid 原地 diff（兼容置顶帖），仅顶部新增帖子显示「有 N 条新帖」，点清除归零，失败发一次性事件。 */
    @Test
    fun refreshDiff_countsNewTopThreadsAcrossStickyHandlesNoChangeAndEmitsRefreshError() = runTest {
        var callIndex = 0
        val threadRepo = TestThreadListRepository { query ->
            callIndex += 1
            when (callIndex) {
                1 -> successBatch(
                    query,
                    listOf(
                        summary("100", "置顶帖", isSticky = true),
                        summary("500", "旧首帖"),
                        summary("499", "旧次帖"),
                    ),
                )
                2 -> successBatch(
                    query,
                    listOf(
                        summary("100", "置顶帖", isSticky = true),
                        summary("502", "新帖二"),
                        summary("501", "新帖一"),
                        summary("500", "旧首帖"),
                        summary("499", "旧次帖"),
                    ),
                )
                3 -> successBatch(
                    query,
                    listOf(
                        summary("100", "置顶帖", isSticky = true),
                        summary("499", "旧次帖被顶上来"),
                        summary("502", "新帖二"),
                        summary("501", "新帖一"),
                        summary("500", "旧首帖"),
                    ),
                )
                else -> ThreadListError.NetworkError("timeout")
            }
        }
        val vm = ForumViewModel(threadRepo, TestSearchRepository(), TestSettingsRepository(), TestAuthRepository(), idleGateway())
        val snackbarErrors = mutableListOf<UiError>()
        val stateJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        val errJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.refreshErrors.collect { snackbarErrors += it } }
        advanceUntilIdle()

        assertEquals("首载不应弹出新帖胶囊", 0, vm.uiState.value.newThreadsCount)

        // 下拉刷新出现 2 条新帖（位于置顶帖下方、旧首帖 500 上方）
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.newThreadsCount)

        vm.dismissNewThreadsPill()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.newThreadsCount)

        // 仅旧帖 499 被顶至顶部、无新 tid：新帖计数仍为 0
        vm.refresh()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.newThreadsCount)

        // 有旧列表时下拉刷新网络失败：保留旧列表并发出 refreshErrors 事件
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.items.isNotEmpty())
        assertEquals("callIndex=$callIndex, uiError=${vm.uiState.value.error}", 1, snackbarErrors.size)
        assertEquals(R.string.common_error_network, snackbarErrors.single().messageRes)

        stateJob.cancel()
        errJob.cancel()
    }

    // ---- Test Fakes ----

    private class TestAuthRepository(
        initialAccount: SavedAccount? = SavedAccount(uid = "42", username = "HiUser", passwordMd5 = "0".repeat(32), lastLoginAt = 1L),
    ) : AuthRepository {
        private val _account = MutableStateFlow(initialAccount)
        private val _authState = MutableStateFlow<AuthState>(
            if (initialAccount != null) AuthState.SignedIn(initialAccount) else AuthState.SignedOut,
        )
        private var _epoch = 1L
        override val currentAccount: StateFlow<SavedAccount?> = _account.asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOfNotNull(initialAccount))
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override val sessionEpoch: Long get() = _epoch

        fun signIn(account: SavedAccount) {
            _epoch += 1
            _account.value = account
            _authState.value = AuthState.SignedIn(account)
        }

        fun markSessionExpired(account: SavedAccount) {
            _authState.value = AuthState.SessionExpired(account)
        }

        fun reloginSameAccount(updated: SavedAccount) {
            _epoch += 1
            _account.value = updated
            _authState.value = AuthState.SignedIn(updated)
        }

        override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) =
            LoginResult.Failure("offline")
        override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
        override suspend fun logout() {
            _account.value = null
            _authState.value = AuthState.SignedOut
        }
        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
    }

    private class TestSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
        val settingsFlow = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = settingsFlow
        override suspend fun setThemeMode(themeMode: ThemeMode) = settingsFlow.update { it.copy(themeMode = themeMode) }
        override suspend fun setPureBlack(enabled: Boolean) = settingsFlow.update { it.copy(pureBlack = enabled) }
        override suspend fun setThemeSeed(themeSeed: String) = settingsFlow.update { it.copy(themeSeed = themeSeed) }
        override suspend fun setFontStep(step: Int) = settingsFlow.update { it.copy(fontStep = step) }
        override suspend fun setLineSpacing(lineSpacing: LineSpacing) = settingsFlow.update { it.copy(lineSpacing = lineSpacing) }
        override suspend fun setFollowedForums(forumIds: List<Int>) = settingsFlow.update { it.copy(followedForums = forumIds) }
        override suspend fun setShowStickyThreads(show: Boolean) = settingsFlow.update { it.copy(showStickyThreads = show) }
        override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) =
            settingsFlow.update { it.copy(sortByPostTime = it.sortByPostTime + (fid to sortByPostTime)) }
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

    private class TestBlacklistRepository : BlacklistRepository {
        private val _blocked = MutableStateFlow<Set<String>>(emptySet())
        override val blockedUsernames: StateFlow<Set<String>> = _blocked.asStateFlow()
        override val lastSyncedAtMs: StateFlow<Long?> = MutableStateFlow(null)
        fun setBlocked(names: Set<String>) { _blocked.value = names }
        override fun isBlocked(username: String): Boolean = username in _blocked.value
        override suspend fun syncBlacklist(priority: AccessPriority): BlacklistSyncResult = throw UnsupportedOperationException()
        override suspend fun addToBlacklist(username: String, formhash: String?, priority: AccessPriority): BlacklistMutationResult =
            throw UnsupportedOperationException()
        override suspend fun removeFromBlacklist(username: String, formhash: String?, priority: AccessPriority): BlacklistMutationResult =
            throw UnsupportedOperationException()
    }

    private class TestSearchRepository(
        private val onGetNewPosts: suspend (page: Int, searchId: String?) -> SearchResult = { page, _ ->
            SearchResult.Success(page, false, SearchPage("sid-1", 1, emptyList(), emptyMeta()))
        },
    ) : SearchRepository {
        override val searchHistory: Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun search(query: String, author: String, forumId: Int?, fullText: Boolean, page: Int, searchId: String?, priority: AccessPriority, recordHistory: Boolean) =
            onGetNewPosts(page, searchId)
        override suspend fun getNewPosts(page: Int, searchId: String?, priority: AccessPriority) = onGetNewPosts(page, searchId)
        override suspend fun getMyThreads(page: Int, priority: AccessPriority): MyThreadsResult = throw UnsupportedOperationException()
        override suspend fun getMyReplies(page: Int, priority: AccessPriority): MyRepliesResult = throw UnsupportedOperationException()
        override suspend fun recordSearchHistory(query: String) = emptyList<String>()
        override suspend fun removeSearchHistory(query: String): RemovedSearchHistoryEntry? = null
        override suspend fun restoreSearchHistory(query: String, index: Int) = emptyList<String>()
        override suspend fun clearSearchHistory() = Unit
        override suspend fun resolveForumLink(rawInput: String): ResolvedForumLink? = null
    }

    private class TestThreadListRepository(
        private val onRefresh: suspend (ThreadListQuery) -> ThreadListLoadResult = { q ->
            successBatch(q, listOf(summary("${q.fid}001", "默认帖子")))
        },
    ) : ThreadListRepository {
        val observedQueries = CopyOnWriteArrayList<ThreadListQuery>()
        val refreshQueries = CopyOnWriteArrayList<ThreadListQuery>()
        val loadMoreQueries = CopyOnWriteArrayList<ThreadListQuery>()
        private val states = LinkedHashMap<ThreadListQuery, MutableStateFlow<ThreadListLoadState>>()

        private fun stateFor(q: ThreadListQuery) = states.getOrPut(q) { MutableStateFlow(ThreadListLoadState(query = q)) }

        override suspend fun loadPage(query: ThreadListQuery, startPage: Int, existingTids: Set<String>, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority) =
            refresh(query, minVisibleItems, maxAutoBackfillPages, priority)

        override suspend fun refresh(query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority): ThreadListLoadResult {
            refreshQueries += query
            stateFor(query).update { it.copy(isLoading = true, error = null) }
            val result = onRefresh(query)
            when (result) {
                is ThreadListBatchResult -> stateFor(query).value = ThreadListLoadState(
                    query = query,
                    items = result.items,
                    fetchedPage = result.fetchedPage,
                    lastPage = result.lastPage,
                    hasMore = result.hasMore,
                    pagesFetchedInBatch = result.pagesFetchedInBatch,
                    meta = result.meta,
                    isLoading = false,
                    error = result.partialError,
                    stopReason = result.stopReason,
                )
                is ThreadListError -> stateFor(query).update { it.copy(isLoading = false, error = result) }
            }
            return result
        }

        override suspend fun loadMore(query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority): ThreadListLoadResult {
            loadMoreQueries += query
            return stateFor(query).value.let { cur ->
                ThreadListBatchResult(query, query.sortByPostTime == true, emptyList(), cur.items, cur.fetchedPage + 1, cur.lastPage, false, 1, emptyMeta())
            }
        }

        override fun observeThreadList(query: ThreadListQuery): Flow<ThreadListLoadState> {
            observedQueries += query
            return stateFor(query)
        }

        override fun observeFiltered(rawThreads: Flow<List<ThreadSummary>>): Flow<List<ThreadListItem>> =
            rawThreads.map { list -> list.map { ThreadListItem(it, false) } }
    }

    companion object {
        private fun emptyMeta() = DiscuzPageMeta(
            session = DiscuzSessionInfo(true, "42", "HiUser"),
            formhash = "fh123",
            errorMessage = null,
            promptCounts = PromptCounts(),
        )

        private fun idleGateway() = SiteAccessGateway(
            client = OkHttpClient(),
            detector = ChallengeDetector(),
            solver = ChallengeSolver { SolveResult.Cancelled },
        )

        private fun summary(tid: String, title: String, isSticky: Boolean = false) = ThreadSummary(
            tid = tid,
            title = title,
            titleColor = null,
            typeName = null,
            isSticky = isSticky,
            isNew = false,
            isPoll = false,
            author = "作者$tid",
            authorId = tid,
            avatarUrl = null,
            timeCreate = "2026-11-26",
            timeUpdate = "刚刚",
            lastPostAuthor = "回复者",
            replyCountText = "12",
            viewCountText = "120",
            withPic = false,
            maxPage = 1,
        )

        private fun successBatch(
            query: ThreadListQuery,
            threads: List<ThreadSummary>,
            stopReason: ThreadListBackfillStopReason = ThreadListBackfillStopReason.TARGET_REACHED,
        ) = ThreadListBatchResult(
            query = query,
            resolvedSortByPostTime = query.sortByPostTime == true,
            rawThreads = threads,
            items = threads.map { ThreadListItem(it, false) },
            fetchedPage = 1,
            lastPage = 3,
            hasMore = threads.isNotEmpty(),
            pagesFetchedInBatch = 1,
            meta = emptyMeta(),
            stopReason = stopReason,
        )
    }
}
