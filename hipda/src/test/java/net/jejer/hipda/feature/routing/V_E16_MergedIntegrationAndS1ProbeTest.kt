package net.jejer.hipda.feature.routing

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.R
import net.jejer.hipda.data.favorite.FavoriteError
import net.jejer.hipda.data.favorite.FavoriteKind
import net.jejer.hipda.data.favorite.FavoriteListResult
import net.jejer.hipda.data.favorite.FavoriteMutationResult
import net.jejer.hipda.data.favorite.FavoriteRepository
import net.jejer.hipda.data.favorite.FavoriteSyncResult
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
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.debug.next.NextAppActivity
import net.jejer.hipda.di.SettingsModule
import net.jejer.hipda.feature.app.DetailDrilldownEntry
import net.jejer.hipda.feature.app.MultiPaneResizableWindowHost
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppHostViewModel
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.app.NextAppStateSaver
import net.jejer.hipda.feature.app.offlinePageMeta
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.SettingsGroup
import net.jejer.hipda.feature.common.SingleHostPaneSnackbarController
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.mine.FavoritesPane
import net.jejer.hipda.feature.mine.FavoritesViewModel
import net.jejer.hipda.feature.search.SearchPane
import net.jejer.hipda.feature.search.SearchViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.simplelist.FavoriteThreadItem
import net.jejer.hipda.parser.simplelist.FavoritesListPage
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.ui.HiNextApplication
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.ui.designsystem.glass.GlassDegradationReason
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import net.jejer.hipda.utils.Constants
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 独立验证卡 V-E16：
 * - 探针 2：合入 `main` 后真实链路（`NextAppRoot` + `ThreadDetailPane` + `ForumPane`）
 *   4 个快捷方式、3 类通知 Intent（短消息 SavedState 零用户名）、外链、帖内站内链接、`管理版块`；
 * - 探针 4：`R-M6-polish S1` 打断实测（在途 10s 撤销条切离页面 / 刷新报错打断 -> 令牌即时失效且后续重排恢复正常）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class V_E16_MergedIntegrationAndS1ProbeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun probe2_shortcutsNotificationsDeepLinksInPostLinksAndManageForums_onMergedMain() {
        val harness = MultiPaneTestHarness()
        val postResolvedRepo = object : ThreadDetailRepository by harness.threadDetailRepo {
            override suspend fun loadByPostId(
                pid: String,
                tid: String?,
                recordHistory: Boolean,
                forumName: String?,
                priority: AccessPriority,
            ): ThreadDetailLoadResult {
                val resolvedTid = tid ?: "8888"
                return ThreadDetailLoadResult.Success(
                    page = ThreadDetailPage(
                        tid = resolvedTid,
                        fid = 6,
                        title = "反查帖子标题 $resolvedTid",
                        page = 2,
                        lastPage = 3,
                        floors = listOf(
                            PostFloor(
                                postId = pid,
                                floor = 12,
                                page = 2,
                                uid = "555",
                                author = "作者555",
                                avatarUrl = null,
                                timePost = "2026-11-26 10:00",
                                isWarned = false,
                                postStatus = null,
                                signature = null,
                                poll = null,
                                blocks = listOf(
                                    ContentBlock.Text(listOf(InlineSpan.Link("帖内跳帖", "https://www.4d4y.com/forum/viewthread.php?tid=2002"))),
                                    ContentBlock.Text(listOf(InlineSpan.Link("帖内跳版", "https://www.4d4y.com/forum/forumdisplay.php?fid=7"))),
                                    ContentBlock.Text(listOf(InlineSpan.Link("帖内看用户", "https://www.4d4y.com/forum/space.php?uid=555"))),
                                    ContentBlock.Text(listOf(InlineSpan.Link("帖内站外网", "https://example.org/docs"))),
                                    ContentBlock.Text(listOf(InlineSpan.Link("帖内邮件", "mailto:help@example.org"))),
                                ),
                            ),
                        ),
                        postsPerPageEstimate = null,
                        meta = offlinePageMeta(),
                        hasPostList = true,
                    ),
                    filter = ThreadDetailFilter.All,
                    forumName = "Geek Talks",
                    threadAuthorId = "555",
                    targetPostId = pid,
                )
            }

            override suspend fun loadPage(
                tid: String,
                page: Int,
                filter: ThreadDetailFilter,
                recordHistory: Boolean,
                forumName: String?,
                priority: AccessPriority,
            ): ThreadDetailLoadResult {
                if (tid == "8888" || tid == "2002" || tid == "3003" || tid == "3004") {
                    return loadByPostId(pid = "${tid}_p$page", tid = tid, recordHistory = recordHistory, forumName = forumName, priority = priority)
                }
                return harness.threadDetailRepo.loadPage(tid, page, filter, recordHistory, forumName, priority)
            }
        }

        val hostVm = NextAppHostViewModel(harness.settingsRepo, postResolvedRepo)
        val detailFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostVm as T
                modelClass.isAssignableFrom(ForumViewModel::class.java) -> harness.forumViewModel as T
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) ->
                    ThreadDetailViewModel(postResolvedRepo, harness.gateway) as T
                else -> error("Unexpected VM: $modelClass")
            }
        }
        val owner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore = ViewModelStore()
            override val defaultViewModelProviderFactory = detailFactory
        }

        val externalOpened = mutableListOf<String>()
        var windowSize by mutableStateOf(DpSize(411.dp, 891.dp))

        composeRule.setContent {
            MultiPaneResizableWindowHost(windowSize) {
                ProvideGlassEnvironment(GlassEnvironmentState(setOf(GlassDegradationReason.POWER_SAVE_MODE), refractionSupported = false)) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        NextAppRoot(
                            settingsFlow = harness.settingsRepo.settings,
                            currentAccountFlow = harness.authRepo.currentAccount,
                            smsUnreadCountFlow = harness.smsUnread.asStateFlow(),
                            notifyUnreadCountFlow = harness.notifyUnread.asStateFlow(),
                            externalLinkOpener = ExternalLinkOpener { url ->
                                externalOpened += url
                                true
                            },
                            hostViewModel = hostVm,
                            forumViewModel = harness.forumViewModel,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 4 个桌面快捷方式
        hostVm.submitIncomingIntent(Intent(Constants.INTENT_NEW_POSTS))
        composeRule.waitForIdle()
        assertEquals(ShellDestination.Forum, hostVm.state.value.selectedDestination)
        assertEquals(ForumListSource.TodayNew, hostVm.state.value.activeForumSource)

        hostVm.submitIncomingIntent(Intent(Constants.INTENT_SEARCH))
        composeRule.waitForIdle()
        assertEquals(ShellDestination.Search, hostVm.state.value.selectedDestination)
        assertEquals(ShellSidebarEntry.Search, hostVm.state.value.selectedSidebarEntry)

        hostVm.submitIncomingIntent(Intent(Constants.INTENT_SMS))
        composeRule.waitForIdle()
        assertEquals(ShellDestination.Messages, hostVm.state.value.selectedDestination)
        assertEquals(ShellSidebarEntry.Sms, hostVm.state.value.selectedSidebarEntry)

        hostVm.submitIncomingIntent(Intent(Constants.INTENT_FAVORITE))
        composeRule.waitForIdle()
        assertEquals(ShellDestination.Mine, hostVm.state.value.selectedDestination)
        assertEquals(ShellSidebarEntry.FavoritesAndAttention, hostVm.state.value.selectedSidebarEntry)

        // 2. 3 类系统通知 Intent（含单条短消息 SavedState 零用户名验证）
        val sensitiveUser = "SensitiveSecretUser"
        hostVm.submitIncomingIntent(
            Intent(Constants.INTENT_NOTIFICATION).apply {
                putExtra(Constants.EXTRA_SMS_COUNT, 1)
                putExtra(Constants.EXTRA_THREAD_COUNT, 0)
                putExtra(Constants.EXTRA_UID, "723379")
                putExtra(Constants.EXTRA_USERNAME, sensitiveUser)
            },
        )
        composeRule.waitForIdle()
        assertEquals(SmsConversationArgs("723379", ""), hostVm.state.value.selectedSmsConversation)
        val savedPayload = with(NextAppStateSaver) { SaverScope { true }.save(hostVm.state.value) } as List<*>
        val savedStrings = savedPayload.flatMap { item ->
            when (item) {
                is String -> listOf(item)
                is Iterable<*> -> item.filterIsInstance<String>()
                else -> emptyList()
            }
        }
        assertTrue("SavedState must never contain notification peer username", savedStrings.none { sensitiveUser in it })

        hostVm.submitIncomingIntent(
            Intent(Constants.INTENT_NOTIFICATION).apply {
                putExtra(Constants.EXTRA_SMS_COUNT, 3)
                putExtra(Constants.EXTRA_THREAD_COUNT, 0)
            },
        )
        composeRule.waitForIdle()
        assertEquals(ShellSidebarEntry.Sms, hostVm.state.value.selectedSidebarEntry)

        hostVm.submitIncomingIntent(
            Intent(Constants.INTENT_NOTIFICATION).apply {
                putExtra(Constants.EXTRA_SMS_COUNT, 0)
                putExtra(Constants.EXTRA_THREAD_COUNT, 4)
            },
        )
        composeRule.waitForIdle()
        assertEquals(ShellSidebarEntry.Notify, hostVm.state.value.selectedSidebarEntry)

        // 3. 外部链接（版块 / 用户 / 无 tid 帖跳转）及真实 ThreadDetailPane 正文站内/站外链接点击
        hostVm.submitIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/forumdisplay.php?fid=6")))
        composeRule.waitForIdle()
        assertEquals(ForumListSource.Forum(6), hostVm.state.value.activeForumSource)

        hostVm.submitIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/space.php?uid=333")))
        composeRule.waitForIdle()
        assertEquals(listOf(DetailDrilldownEntry.UserProfile("333", "")), hostVm.state.value.detailDrilldownStack)

        hostVm.submitIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/gotopost.php?pid=999")))
        composeRule.waitUntil(timeoutMillis = 5_000L) { hostVm.state.value.selectedThread?.tid == "8888" }
        composeRule.waitForIdle()
        assertEquals("999", hostVm.state.value.selectedThread?.targetPid)

        // viewthread?page=2 与 redirect?goto=lastpost 经真实 ThreadDetailRepository 异步落地
        hostVm.submitIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/viewthread.php?tid=3003&page=2")))
        composeRule.waitUntil(timeoutMillis = 5_000L) { hostVm.state.value.selectedThread?.tid == "3003" }
        assertEquals("3003_p2", hostVm.state.value.selectedThread?.targetPid)

        hostVm.submitIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/redirect.php?goto=lastpost&tid=3004")))
        composeRule.waitUntil(timeoutMillis = 5_000L) { hostVm.state.value.selectedThread?.tid == "3004" }
        assertEquals("3004_25", hostVm.state.value.selectedThread?.targetPid)

        // 切回 8888 测试真实帖子正文内点击站内/站外链接
        hostVm.submitIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/gotopost.php?pid=999")))
        composeRule.waitUntil(timeoutMillis = 5_000L) { hostVm.state.value.selectedThread?.tid == "8888" }
        composeRule.waitForIdle()

        // 在真实 NextAppRoot + ThreadDetailPane + PostBodyRenderer 内点击站内链接与站外链接
        composeRule.onNodeWithText("帖内跳帖", substring = true).performTouchInput { click(Offset(24f, centerY)) }
        composeRule.waitForIdle()
        assertEquals("2002", hostVm.state.value.selectedThread?.tid)
        assertTrue("In-app thread link must not open externally", externalOpened.isEmpty())

        composeRule.onNodeWithText("帖内看用户", substring = true).performTouchInput { click(Offset(24f, centerY)) }
        composeRule.waitForIdle()
        assertEquals(DetailDrilldownEntry.UserProfile("555", ""), hostVm.state.value.detailDrilldownStack.lastOrNull())
        assertTrue("In-app user link must not open externally", externalOpened.isEmpty())

        hostVm.popDetailDrilldown()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("帖内站外网", substring = true).performTouchInput { click(Offset(24f, centerY)) }
        composeRule.waitForIdle()
        assertEquals(listOf("https://example.org/docs"), externalOpened)

        composeRule.onNodeWithText("帖内邮件", substring = true).performTouchInput { click(Offset(24f, centerY)) }
        composeRule.waitForIdle()
        assertEquals(listOf("https://example.org/docs", "mailto:help@example.org"), externalOpened)

        composeRule.onNodeWithText("帖内跳版", substring = true).performTouchInput { click(Offset(24f, centerY)) }
        composeRule.waitForIdle()
        assertEquals(ForumListSource.Forum(7), hostVm.state.value.activeForumSource)

        // 4. 管理版块回调（单栏论坛标签栏右侧图标 -> SettingsGroup.ForumAndPost 且展开详情）
        composeRule.onNodeWithContentDescription(context.getString(R.string.shell_sidebar_manage_forums)).performClick()
        composeRule.waitForIdle()
        assertEquals(ShellSidebarEntry.Settings, hostVm.state.value.selectedSidebarEntry)
        assertEquals(SettingsGroup.ForumAndPost, hostVm.state.value.selectedSettingsGroup)
        assertTrue(hostVm.state.value.isSettingsDetailOpen)

        // 宽屏 1280dp 下左侧栏点击管理版块 -> SettingsGroup.ForumAndPost 且单栏标记为 false
        hostVm.selectForumSource(ForumListSource.Forum(2))
        windowSize = DpSize(1280.dp, 800.dp)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(context.getString(R.string.shell_sidebar_manage_forums)).performClick()
        composeRule.waitForIdle()
        assertEquals(ShellSidebarEntry.Settings, hostVm.state.value.selectedSidebarEntry)
        assertEquals(SettingsGroup.ForumAndPost, hostVm.state.value.selectedSettingsGroup)
        assertFalse(hostVm.state.value.isSettingsDetailOpen)
    }

    @Test
    fun probe4_rM6PolishS1_searchAndFavoritesUndoInterruptedByNavigationAndRefreshError() {
        val harness = MultiPaneTestHarness()
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })

        // ---------------- 1. SearchPane S1 打断验证 ----------------
        val historyFlow = MutableStateFlow(listOf("苹果", "香蕉", "咖啡"))
        val failSearch = AtomicBoolean(false)
        val searchRepo = object : SearchRepository {
            override val searchHistory: Flow<List<String>> = historyFlow
            override suspend fun search(
                query: String, author: String, forumId: Int?, fullText: Boolean, page: Int, searchId: String?, priority: AccessPriority, recordHistory: Boolean,
            ): SearchResult = if (failSearch.get()) {
                SearchError.NetworkError("network", statusCode = 500)
            } else {
                SearchResult.Success(
                    page,
                    fullText,
                    SearchPage(
                        "s1",
                        1,
                        listOf(SearchItem("10", null, "结果", null, "作者", "1", null, "刚刚", "Discovery")),
                        offlinePageMeta(),
                    ),
                )
            }
            override suspend fun getNewPosts(page: Int, searchId: String?, priority: AccessPriority): SearchResult = throw UnsupportedOperationException()
            override suspend fun getMyThreads(page: Int, priority: AccessPriority): MyThreadsResult = throw UnsupportedOperationException()
            override suspend fun getMyReplies(page: Int, priority: AccessPriority): MyRepliesResult = throw UnsupportedOperationException()
            override suspend fun recordSearchHistory(query: String): List<String> = historyFlow.value
            override suspend fun removeSearchHistory(query: String): RemovedSearchHistoryEntry? {
                val idx = historyFlow.value.indexOf(query).takeIf { it >= 0 } ?: return null
                historyFlow.update { it.filterNot { x -> x == query } }
                return RemovedSearchHistoryEntry(query, idx)
            }
            override suspend fun restoreSearchHistory(query: String, index: Int): List<String> {
                historyFlow.update { cur ->
                    cur.filterNot { it == query }.toMutableList().apply { add(index.coerceIn(0, size), query) }
                }
                return historyFlow.value
            }
            override suspend fun clearSearchHistory() { historyFlow.value = emptyList() }
            override suspend fun resolveForumLink(rawInput: String): ResolvedForumLink? = null
        }
        val searchVm = SearchViewModel(searchRepo, harness.settingsRepo, harness.authRepo, gateway)

        var showSearch by mutableStateOf(true)
        var showFavorites by mutableStateOf(false)

        // ---------------- 2. FavoritesPane S1 打断验证 ----------------
        val favItems = mutableListOf(
            FavoriteThreadItem("7001", "收藏帖A", "今天", "Discovery"),
            FavoriteThreadItem("7002", "收藏帖B", "昨天", "Discovery"),
            FavoriteThreadItem("7003", "收藏帖C", "前天", "Discovery"),
        )
        val failFavRefresh = AtomicBoolean(false)
        val favRepo = object : FavoriteRepository {
            override val favoriteTids = MutableStateFlow(setOf("7001", "7002", "7003"))
            override val attentionTids = MutableStateFlow(emptySet<String>())
            override fun isFavorite(tid: String): Boolean = tid in favoriteTids.value
            override fun isAttention(tid: String): Boolean = false
            override suspend fun getList(kind: FavoriteKind, page: Int, priority: AccessPriority): FavoriteListResult =
                if (failFavRefresh.get()) {
                    FavoriteError.NetworkError("network", statusCode = 502)
                } else {
                    FavoriteListResult.Success(kind, 1, FavoritesListPage(1, favItems.toList(), offlinePageMeta()))
                }
            override suspend fun syncCache(kind: FavoriteKind, maxPages: Int, priority: AccessPriority): FavoriteSyncResult =
                FavoriteSyncResult.Success(kind, favoriteTids.value, 1, 1, 1L)
            override suspend fun add(kind: FavoriteKind, tid: String, priority: AccessPriority): FavoriteMutationResult {
                favoriteTids.update { it + tid }
                return FavoriteMutationResult.Success(kind, tid, "ok")
            }
            override suspend fun remove(kind: FavoriteKind, tid: String, priority: AccessPriority): FavoriteMutationResult {
                favoriteTids.update { it - tid }
                return FavoriteMutationResult.Success(kind, tid, "ok")
            }
            override suspend fun removeByForm(kind: FavoriteKind, tid: String, formhash: String, priority: AccessPriority): FavoriteMutationResult =
                remove(kind, tid, priority)
        }
        val favVm = FavoritesViewModel(favRepo, harness.authRepo, gateway)

        composeRule.setContent {
            val hostState = remember { SnackbarHostState() }
            val controller = remember(hostState) { SingleHostPaneSnackbarController(hostState) }
            HiTheme {
                CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                    Box(Modifier.fillMaxSize()) {
                        if (showSearch) {
                            SearchPane(
                                viewModel = searchVm,
                                showBack = false,
                                onBack = null,
                                onOpenThread = {},
                                onOpenForum = {},
                                onOpenUserProfile = { _, _ -> },
                                onOpenLogin = {},
                            )
                        }
                        if (showFavorites) {
                            FavoritesPane(
                                viewModel = favVm,
                                showBack = false,
                                onBack = null,
                                onOpenThread = {},
                                onOpenLogin = {},
                            )
                        }
                        HiSnackbarHost(hostState, 0.dp)
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 2a. Search: 10s 撤销条在途时切离页面 -> try/finally 立即过期旧令牌；重排后删首位再撤销必回首位
        searchVm.deleteHistory("苹果")
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.search_snackbar_deleted)).assertIsDisplayed()
        showSearch = false
        composeRule.waitForIdle()

        searchVm.undoDeleteHistory(1L)
        composeRule.waitForIdle()
        assertEquals(listOf("香蕉", "咖啡"), historyFlow.value)

        historyFlow.value = listOf("咖啡", "香蕉")
        showSearch = true
        composeRule.waitForIdle()
        searchVm.deleteHistory("咖啡")
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.feedback_action_undo)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("咖啡", "香蕉"), historyFlow.value)

        // 2b. Search: 在途撤销条被刷新报错打断 -> undoManager.invalidate() 立即失效旧令牌并重排建秩正常
        searchVm.submitHistoryQuery("香蕉")
        composeRule.waitForIdle()
        searchVm.deleteHistory("香蕉")
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.search_snackbar_deleted)).assertIsDisplayed()
        failSearch.set(true)
        searchVm.refresh()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.common_error_load_failed)).assertIsDisplayed()

        historyFlow.value = listOf("新首", "咖啡")
        composeRule.waitForIdle()
        searchVm.deleteHistory("新首")
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.feedback_action_undo)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("新首", "咖啡"), historyFlow.value)

        // 3a. Favorites: 10s 撤销条在途时切离页面 -> try/finally 立即失效旧令牌；刷新重排后删首位再撤销必回首位
        showSearch = false
        showFavorites = true
        composeRule.waitForIdle()
        assertEquals(listOf("7001", "7002", "7003"), favVm.uiState.value.tabState(FavoriteKind.FAVORITE).items.map { it.tid })

        val item7001 = favVm.uiState.value.tabState(FavoriteKind.FAVORITE).items.first()
        favVm.removeItem(FavoriteKind.FAVORITE, item7001)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.mine_snackbar_unfavorited)).assertIsDisplayed()
        showFavorites = false
        composeRule.waitForIdle()

        favItems.clear()
        favItems.addAll(
            listOf(
                FavoriteThreadItem("7003", "收藏帖C", "前天", "Discovery"),
                FavoriteThreadItem("7002", "收藏帖B", "昨天", "Discovery"),
            ),
        )
        favRepo.favoriteTids.value = setOf("7003", "7002")
        showFavorites = true
        composeRule.waitForIdle()
        favVm.refresh(FavoriteKind.FAVORITE)
        composeRule.waitForIdle()
        val reorderedFirst = favVm.uiState.value.tabState(FavoriteKind.FAVORITE).items.first()
        assertEquals("7003", reorderedFirst.tid)
        favVm.removeItem(FavoriteKind.FAVORITE, reorderedFirst)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.feedback_action_undo)).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("7003", "7002"), favVm.uiState.value.tabState(FavoriteKind.FAVORITE).items.map { it.tid })

        // 3b. Favorites: 在途撤销条被刷新报错打断 -> ActionError invalidate() 立即失效旧令牌，随后刷新重排建秩正常
        favVm.removeItem(FavoriteKind.FAVORITE, favVm.uiState.value.tabState(FavoriteKind.FAVORITE).items.first())
        composeRule.waitForIdle()
        failFavRefresh.set(true)
        favVm.refresh(FavoriteKind.FAVORITE)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.mine_snackbar_unfavorited)).assertDoesNotExist()

        failFavRefresh.set(false)
        favItems.clear()
        favItems.addAll(
            listOf(
                FavoriteThreadItem("7002", "收藏帖B", "昨天", "Discovery"),
                FavoriteThreadItem("7004", "收藏帖D", "刚刚", "Discovery"),
            ),
        )
        favRepo.favoriteTids.value = setOf("7002", "7004")
        favVm.refresh(FavoriteKind.FAVORITE)
        composeRule.waitForIdle()
        val headAfterError = favVm.uiState.value.tabState(FavoriteKind.FAVORITE).items.first()
        assertEquals("7002", headAfterError.tid)
        favVm.removeItem(FavoriteKind.FAVORITE, headAfterError)
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(context.getString(R.string.feedback_action_undo)).onFirst().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("7002", "7004"), favVm.uiState.value.tabState(FavoriteKind.FAVORITE).items.map { it.tid })
    }
}

/**
 * 独立验证真实 `NextAppActivity`（`@AndroidEntryPoint` + Hilt `HiNextApplication`）的 `onCreate`、旋转重建不重复消费与 `onNewIntent`；
 * 启动前把 `SettingsModule` DataStore 预置指向本地 `MockWebServer`，严禁触发真实站点请求。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = HiNextApplication::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class V_E16_NextAppActivityLifecycleProbeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun probe2_nextAppActivity_onCreateRecreationDoesNotReplayAndOnNewIntentApplies() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val mockServer = MockWebServer()
        mockServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder()
                    .code(200)
                    .body("<html><body><div id=\"umenu\"><cite><a href=\"space.php?uid=42\">HiUser</a></cite></div></body></html>")
                    .build()
        }
        mockServer.start()
        try {
            val mockServerUrl = mockServer.url("/").toString().trimEnd('/')
            val seedScopeJob = SupervisorJob()
            val seedScope = CoroutineScope(Dispatchers.IO + seedScopeJob)
            val tempFile = File(context.cacheDir, "v_e16_seed_settings.preferences_pb")
            val seedDs = PreferenceDataStoreFactory.create(scope = seedScope) { tempFile }
            runBlocking {
                seedDs.edit { prefs -> prefs[stringPreferencesKey("forum_server")] = mockServerUrl }
                seedScopeJob.cancel()
                seedScopeJob.join()
            }
            val realSettingsFile = SettingsModule.settingsDataStoreFile(context)
            realSettingsFile.parentFile?.mkdirs()
            tempFile.copyTo(realSettingsFile, overwrite = true)

            val launchIntent = NextAppActivity.createShortcutIntent(context, Constants.INTENT_SEARCH)
            val controller = Robolectric.buildActivity(NextAppActivity::class.java, launchIntent).setup()
            val activity = controller.get()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                val vm = ViewModelProvider(activity)[NextAppHostViewModel::class.java]
                vm.state.value.selectedDestination == ShellDestination.Search
            }

            // 切离 Search 到 Mine，随后模拟旋转重建（savedInstanceState != null）
            val vm = ViewModelProvider(activity)[NextAppHostViewModel::class.java]
            vm.selectDestination(ShellDestination.Mine)
            composeRule.waitForIdle()
            assertEquals(ShellDestination.Mine, vm.state.value.selectedDestination)

            controller.recreate()
            composeRule.waitForIdle()
            val recreated = controller.get()
            val recreatedVm = ViewModelProvider(recreated)[NextAppHostViewModel::class.java]
            assertEquals(
                "Rotation (non-null savedInstanceState) must NOT replay initial search shortcut Intent",
                ShellDestination.Mine,
                recreatedVm.state.value.selectedDestination,
            )

            // onNewIntent 投递短消息快捷方式应立即消费并切到 Messages/Sms
            controller.newIntent(NextAppActivity.createShortcutIntent(context, Constants.INTENT_SMS))
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                recreatedVm.state.value.selectedSidebarEntry == ShellSidebarEntry.Sms
            }
            assertEquals(ShellDestination.Messages, recreatedVm.state.value.selectedDestination)

            repeat(mockServer.requestCount) {
                val req = mockServer.takeRequest()
                assertFalse("Must never target 4d4y.com", req.headers["Host"].orEmpty().contains("4d4y.com"))
            }
            controller.pause().stop().destroy()
        } finally {
            mockServer.close()
        }
    }
}
