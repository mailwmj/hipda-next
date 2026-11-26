package net.jejer.hipda.feature.search

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.search.*
import net.jejer.hipda.data.settings.*
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.SingleHostPaneSnackbarController
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 搜索页核心交互自动化测试（验收 1：初始态无空态、最近搜索删除撤销、可打断筛选面板与减动效 0ms、0 结果区分筛选、站内链接识别与反例、分页）。 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SearchPaneTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun initialState_withEmptyHistory_hidesRecentHeadingAndNeverShowsEmptyState() {
        val harness = SearchTestHarness(initialHistory = emptyList())
        composeRule.setContent {
            SearchTestHost(harness.viewModel)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("搜索标题、正文或作者").assertIsDisplayed()
        composeRule.onNodeWithText("版块").assertIsDisplayed()
        composeRule.onNodeWithText("标题").assertIsDisplayed()
        composeRule.onNodeWithText("作者").assertIsDisplayed()
        composeRule.onNodeWithText("最近搜索").assertDoesNotExist()
        composeRule.onNodeWithText("没有找到相关内容").assertDoesNotExist()
        composeRule.assertGlassSurfaceCountAtMost(0)
    }

    @Test
    fun recentHistory_deleteImmediatelyAndUndoRestoresOriginalPosition() {
        val harness = SearchTestHarness(initialHistory = listOf("折叠屏", "键盘", "咖啡"))
        composeRule.setContent {
            SearchTestHost(harness.viewModel)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("最近搜索").assertIsDisplayed()
        composeRule.onNodeWithText("键盘").assertIsDisplayed()

        // 点击第二条（“键盘”）右侧 × 删除按钮
        composeRule.onAllNodesWithContentDescription("删除")[1].performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("键盘").assertDoesNotExist()
        assertEquals(listOf("折叠屏", "咖啡"), harness.historyFlow.value)
        composeRule.onNodeWithText("已删除").assertIsDisplayed()
        composeRule.onNodeWithText("撤销").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("键盘").assertIsDisplayed()
        assertEquals(listOf("折叠屏", "键盘", "咖啡"), harness.historyFlow.value)
    }

    @Test
    fun recentHistory_consecutiveDeletes_undoRestoresOnlyLatestAndInvalidatesPrevious() {
        val harness = SearchTestHarness(initialHistory = listOf("折叠屏", "键盘", "咖啡"))
        val emittedTokens = mutableListOf<SearchHistoryUndoToken>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val collectJob = scope.launch {
            harness.viewModel.snackbarEvents.collect {
                if (it is SearchSnackbarEvent.HistoryDeleted) emittedTokens += it.token
            }
        }
        try {
            composeRule.setContent { SearchTestHost(harness.viewModel) }
            composeRule.waitForIdle()

            // 连续快速删除 "折叠屏" 与 "键盘"（上一条 Snackbar 立即替换失效）
            composeRule.onAllNodesWithContentDescription("删除")[0].performClick()
            composeRule.waitForIdle()
            composeRule.onAllNodesWithContentDescription("删除")[0].performClick()
            composeRule.waitForIdle()

            assertEquals(2, emittedTokens.size)
            assertEquals(listOf("咖啡"), harness.historyFlow.value)

            // 前一次删除令牌已确认，调用 undoDeleteHistory 无效
            harness.viewModel.undoDeleteHistory(emittedTokens[0].id)
            composeRule.waitForIdle()
            assertEquals(listOf("咖啡"), harness.historyFlow.value)

            // 点当前 Snackbar "撤销" 只恢复最新的 "键盘" 并回到 "咖啡" 之前
            composeRule.onNodeWithText("撤销").assertIsDisplayed().performClick()
            composeRule.waitForIdle()

            assertEquals(listOf("键盘", "咖啡"), harness.historyFlow.value)
            composeRule.onNodeWithText("键盘").assertIsDisplayed()
            composeRule.onNodeWithText("折叠屏").assertDoesNotExist()
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun filterPanel_expandAndCollapse_isInterruptibleMidAnimation() {
        val harness = SearchTestHarness(initialHistory = listOf("折叠屏"))
        composeRule.setContent {
            SearchTestHost(harness.viewModel)
        }
        composeRule.waitForIdle()

        // 关闭自动时钟，展开筛选面板并在半途打断收起，验证下方列表锚点位置连续无跳变
        composeRule.mainClock.autoAdvance = false
        val yBefore = composeRule.onNodeWithText("最近搜索").fetchSemanticsNode().positionInRoot.y
        composeRule.onNodeWithText("版块").performClick()
        val ys = mutableListOf(yBefore)
        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            ys += composeRule.onNodeWithText("最近搜索").fetchSemanticsNode().positionInRoot.y
        }
        assertTrue("展开中途列表应向下让位，实测：$ys", ys.last() > yBefore + 1f)

        // 中途打断：再次点击“版块”立即反向收起
        composeRule.onNodeWithText("版块").performClick()
        repeat(20) {
            composeRule.mainClock.advanceTimeByFrame()
            ys += composeRule.onNodeWithText("最近搜索").fetchSemanticsNode().positionInRoot.y
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        val maxSingleFrameJumpPx = ys.zipWithNext { a, b -> kotlin.math.abs(b - a) }.maxOrNull() ?: 0f
        val maxAllowedPx = with(composeRule.density) { 48.dp.toPx() }
        assertTrue("打断展开/收起时相邻帧位移应连续，实测 maxJump=$maxSingleFrameJumpPx px", maxSingleFrameJumpPx <= maxAllowedPx)
        composeRule.onNodeWithText("全部").assertDoesNotExist()
    }

    @Test
    fun filterPanel_underReducedMotion_settlesWithinSingleFrame() = withAnimatorDurationScale(0f) {
        val harness = SearchTestHarness(initialHistory = listOf("折叠屏"))
        setContent { SearchTestHost(harness.viewModel) }
        waitForIdle()

        mainClock.autoAdvance = false
        val yInitial = onNodeWithText("最近搜索").fetchSemanticsNode().positionInRoot.y
        onNodeWithText("版块").performClick()
        mainClock.advanceTimeByFrame() // 触发重组与 0ms 过渡启动
        mainClock.advanceTimeByFrame() // 0ms 过渡立即到位，零中间帧
        onNodeWithText("全部").assertIsDisplayed()
        onNodeWithText("Discovery").assertIsDisplayed()
        val yExpanded = onNodeWithText("最近搜索").fetchSemanticsNode().positionInRoot.y
        assertTrue("展开后列表应让位", yExpanded > yInitial + 1f)
        mainClock.advanceTimeByFrame()
        assertEquals("减动效下应无残留中间帧", yExpanded, onNodeWithText("最近搜索").fetchSemanticsNode().positionInRoot.y, 0.5f)

        onNodeWithText("版块").performClick()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        onNodeWithText("全部").assertDoesNotExist()
        assertEquals("减动效收起应立即复位", yInitial, onNodeWithText("最近搜索").fetchSemanticsNode().positionInRoot.y, 0.5f)
        mainClock.autoAdvance = true
    }

    @Test
    fun zeroResults_distinguishesWithAndWithoutActiveFilters() {
        val harness = SearchTestHarness(initialHistory = emptyList())
        harness.nextSearchResult = SearchResult.Success(
            page = 1,
            fullText = false,
            searchPage = SearchPage("sid-0", 1, emptyList(), emptyMeta()),
        )
        composeRule.setContent { SearchTestHost(harness.viewModel) }
        composeRule.waitForIdle()

        // 1. 无筛选提交 0 条结果：显示“没有找到相关内容”，不显示“清除筛选”
        composeRule.onNodeWithText("搜索标题、正文或作者").performTextInput("不存在的关键词")
        composeRule.onNodeWithText("不存在的关键词").performImeAction()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("没有找到相关内容").assertIsDisplayed()
        composeRule.onNodeWithText("清除筛选").assertDoesNotExist()

        // 2. 开启版块筛选（Discovery）后仍 0 条：显示“没有找到相关内容” + “清除筛选”
        composeRule.onNodeWithText("版块").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Discovery").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("没有找到相关内容").assertIsDisplayed()
        composeRule.onNodeWithText("清除筛选").assertIsDisplayed()

        // 3. 点击“清除筛选”后重新搜索并展示恢复结果
        harness.nextSearchResult = SearchResult.Success(
            page = 1,
            fullText = false,
            searchPage = SearchPage(
                searchId = "sid-1",
                maxPage = 1,
                items = listOf(SearchItem("3001", null, "恢复后的搜索结果", null, "alice", "1", null, "刚刚", "Discovery")),
                meta = emptyMeta(),
            ),
        )
        composeRule.onNodeWithText("清除筛选").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("恢复后的搜索结果").assertIsDisplayed()
        assertEquals(null, harness.lastForumId)
        assertEquals(false, harness.lastFullText)
    }

    @Test
    fun linkDetection_routesValidHttpLinksAndBlocksDangerousSchemesInUi() {
        val harness = SearchTestHarness(initialHistory = emptyList())
        val openedThreads = mutableListOf<ThreadNavArgs>()
        val openedForums = mutableListOf<Int>()
        val openedUsers = mutableListOf<Pair<String, String>>()

        composeRule.setContent {
            SearchTestHost(
                viewModel = harness.viewModel,
                onOpenThread = openedThreads::add,
                onOpenForum = openedForums::add,
                onOpenUserProfile = { uid, name -> openedUsers += uid to name },
            )
        }
        composeRule.waitForIdle()

        val inputNode = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))

        // 合法站内帖子链接
        inputNode.performTextReplacement("https://www.4d4y.com/forum/viewthread.php?tid=3478055")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("打开这个链接").assertIsDisplayed().performClick()
        assertEquals(listOf(ThreadNavArgs(tid = "3478055")), openedThreads)

        // 合法站内版块链接
        inputNode.performTextReplacement("http://www.hi-pda.com/forum/forumdisplay.php?fid=2")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("打开这个链接").assertIsDisplayed().performClick()
        assertEquals(listOf(2), openedForums)

        // 合法站内用户链接
        inputNode.performTextReplacement("https://www.4d4y.com/forum/space.php?uid=10000")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("打开这个链接").assertIsDisplayed().performClick()
        assertEquals(listOf("10000" to ""), openedUsers)

        // 危险 scheme（含携带 4d4y.com 路径载荷）、大小写/控制字符变体与站外同路径域名一律不显示“打开这个链接”
        val dangerousInputs = listOf(
            "javascript:alert(1)",
            "javascript://www.4d4y.com/forum/viewthread.php?tid=3478055",
            "JAVASCRIPT://www.4d4y.com/forum/viewthread.php?tid=3478055",
            "intent://scan/#Intent;scheme=zxing;end",
            "intent://www.4d4y.com/forum/viewthread.php?tid=3478055#Intent;scheme=https;end",
            "file:///etc/hosts",
            "file://www.4d4y.com/forum/viewthread.php?tid=3478055",
            "content://media/external/images/media/1",
            "content://www.4d4y.com/forum/viewthread.php?tid=3478055",
            "data:text/html,<script>alert(1)</script>",
            "data:text/html,https://www.4d4y.com/forum/viewthread.php?tid=3478055",
            "mailto:qa@example.com",
            "mailto:qa@4d4y.com?subject=https://www.4d4y.com/forum/viewthread.php?tid=3478055",
            " https://www.4d4y.com/forum/viewthread.php?tid=3478055",
            "\thttps://www.4d4y.com/forum/viewthread.php?tid=3478055",
            "https://www.4d4y.com/forum/viewthread.php?tid=3478055\n",
            "https://www.4d4y.com/forum/viewthread.php?tid=3478055\u0000",
            "https://evil.com/forum/viewthread.php?tid=3478055",
        )
        for (bad in dangerousInputs) {
            inputNode.performTextReplacement(bad)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("打开这个链接").assertDoesNotExist()
        }
        assertEquals(1, openedThreads.size)
        assertEquals(1, openedForums.size)
        assertEquals(1, openedUsers.size)
    }

    @Test
    fun searchResults_supportsPaginationAndOpensThreadWithTargetPid() {
        val harness = SearchTestHarness(initialHistory = emptyList())
        harness.pagedResults[1] = SearchResult.Success(
            page = 1,
            fullText = true,
            searchPage = SearchPage(
                searchId = "sid-page",
                maxPage = 2,
                items = listOf(SearchItem("5001", "88001", "第一页匹配主题", "匹配正文片段", "alice", "1", null, "10分钟前", "Geek Talks")),
                meta = emptyMeta(),
            ),
        )
        harness.pagedResults[2] = SearchResult.Success(
            page = 2,
            fullText = true,
            searchPage = SearchPage(
                searchId = "sid-page",
                maxPage = 2,
                items = listOf(SearchItem("5002", "88002", "第二页匹配主题", "第二页片段", "bob", "2", null, "1小时前", "Discovery")),
                meta = emptyMeta(),
            ),
        )
        val opened = mutableListOf<ThreadNavArgs>()
        composeRule.setContent { SearchTestHost(harness.viewModel, onOpenThread = opened::add) }
        composeRule.waitForIdle()

        val inputNode = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        inputNode.performTextInput("折叠屏")
        inputNode.performImeAction()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第一页匹配主题").assertIsDisplayed()
        composeRule.onNodeWithText("加载更多").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第二页匹配主题").assertIsDisplayed().performClick()
        assertEquals("sid-page", harness.lastSearchId)
        assertEquals(listOf(ThreadNavArgs("5002", "第二页匹配主题", 0, "Discovery", "bob", "88002")), opened)
    }

    @Test
    fun clearingAuthorWithEmptyQuery_resetsSubmittedStateToInitial() {
        val harness = SearchTestHarness(initialHistory = listOf("折叠屏"))
        composeRule.setContent { SearchTestHost(harness.viewModel) }
        composeRule.waitForIdle()

        harness.viewModel.onAuthorChange("linus_cn")
        harness.viewModel.submitSearch()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("没有找到相关内容").assertIsDisplayed()

        harness.viewModel.onAuthorChange("")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("没有找到相关内容").assertDoesNotExist()
        composeRule.onNodeWithText("最近搜索").assertIsDisplayed()
    }

    @androidx.compose.runtime.Composable
    private fun SearchTestHost(
        viewModel: SearchViewModel,
        onOpenThread: (ThreadNavArgs) -> Unit = {},
        onOpenForum: (Int) -> Unit = {},
        onOpenUserProfile: (String, String) -> Unit = { _, _ -> },
    ) {
        val snackbarHostState = remember { SnackbarHostState() }
        val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
        HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
            CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                Box(Modifier.fillMaxSize()) {
                    SearchPane(
                        viewModel = viewModel,
                        showBack = false,
                        onBack = null,
                        onOpenThread = onOpenThread,
                        onOpenForum = onOpenForum,
                        onOpenUserProfile = onOpenUserProfile,
                        onOpenLogin = {},
                    )
                    HiSnackbarHost(hostState = snackbarHostState, bottomClearance = 16.dp)
                }
            }
        }
    }

    private class SearchTestHarness(initialHistory: List<String>) {
        val historyFlow = MutableStateFlow(initialHistory)
        var nextSearchResult: SearchResult = SearchResult.Success(1, false, SearchPage("sid-1", 1, emptyList(), emptyMeta()))
        val pagedResults = HashMap<Int, SearchResult>()
        var lastForumId: Int? = null
        var lastFullText: Boolean = false
        var lastSearchId: String? = null

        private val searchRepo = object : SearchRepository {
            override val searchHistory: Flow<List<String>> = historyFlow
            override suspend fun search(query: String, author: String, forumId: Int?, fullText: Boolean, page: Int, searchId: String?, priority: AccessPriority, recordHistory: Boolean): SearchResult {
                lastForumId = forumId
                lastFullText = fullText
                lastSearchId = searchId
                return pagedResults[page] ?: nextSearchResult
            }
            override suspend fun getNewPosts(page: Int, searchId: String?, priority: AccessPriority) = nextSearchResult
            override suspend fun getMyThreads(page: Int, priority: AccessPriority): MyThreadsResult = throw UnsupportedOperationException()
            override suspend fun getMyReplies(page: Int, priority: AccessPriority): MyRepliesResult = throw UnsupportedOperationException()
            override suspend fun recordSearchHistory(query: String): List<String> {
                historyFlow.update { listOf(query) + it.filterNot { x -> x == query } }
                return historyFlow.value
            }
            override suspend fun removeSearchHistory(query: String): RemovedSearchHistoryEntry? {
                val idx = historyFlow.value.indexOf(query)
                if (idx < 0) return null
                historyFlow.update { it.filterNot { x -> x == query } }
                return RemovedSearchHistoryEntry(query, idx)
            }
            override suspend fun restoreSearchHistory(query: String, index: Int): List<String> {
                historyFlow.update { cur ->
                    val list = cur.filterNot { it == query }.toMutableList()
                    list.add(index.coerceIn(0, list.size), query)
                    list
                }
                return historyFlow.value
            }
            override suspend fun clearSearchHistory() { historyFlow.value = emptyList() }
            override suspend fun resolveForumLink(rawInput: String): ResolvedForumLink? = ForumLinkResolver.resolve(rawInput)
        }

        private val settingsRepo = object : SettingsRepository {
            override val settings: Flow<AppSettings> = MutableStateFlow(AppSettings())
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

        private val account = SavedAccount("42", "HiPDA", "0".repeat(32), lastLoginAt = 1L)
        private val authRepo = object : AuthRepository {
            override val currentAccount: StateFlow<SavedAccount?> = MutableStateFlow(account).asStateFlow()
            override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOf(account)).asStateFlow()
            override val authState: StateFlow<AuthState> = MutableStateFlow<AuthState>(AuthState.SignedIn(account)).asStateFlow()
            override val sessionEpoch: Long = 1L
            override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) = LoginResult.Failure("offline")
            override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
            override suspend fun logout() = Unit
            override suspend fun removeAccount(uid: String) = Unit
            override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
        }

        val viewModel = SearchViewModel(
            searchRepository = searchRepo,
            settingsRepository = settingsRepo,
            authRepository = authRepo,
            siteAccessGateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }),
        )
    }

    private companion object {
        fun emptyMeta() = DiscuzPageMeta(DiscuzSessionInfo(true, "42", "HiPDA"), "fh", null, PromptCounts())
    }
}
