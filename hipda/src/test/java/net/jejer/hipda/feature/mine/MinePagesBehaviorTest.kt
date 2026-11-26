package net.jejer.hipda.feature.mine

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.*
import net.jejer.hipda.data.favorite.*
import net.jejer.hipda.data.local.BrowseHistoryEntity
import net.jejer.hipda.data.local.HiDatabase
import net.jejer.hipda.data.network.*
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.data.search.*
import net.jejer.hipda.data.settings.*
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.SingleHostPaneSnackbarController
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.simplelist.MyRepliesPage
import net.jejer.hipda.parser.simplelist.MyReplyItem
import net.jejer.hipda.parser.simplelist.MyThreadItem
import net.jejer.hipda.parser.simplelist.MyThreadsPage
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 「我的」子页核心交互自动化测试（验收 2：收藏/关注 MockWebServer 取消与撤销恢复、我的帖子单栏/宽屏与 targetPid、浏览历史离线 Room 删除/清空撤销、未登录原地登录、零毛玻璃）。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MinePagesBehaviorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: HiDatabase
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val clients = mutableListOf<OkHttpClient>()
    private val testAccount = SavedAccount("10000", "HiUser", "0".repeat(32), lastLoginAt = 2_000L)

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        OkHttp.initialize(ctx)
        database = Room.inMemoryDatabaseBuilder(ctx, HiDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer().apply { start() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        clients.forEach {
            it.dispatcher.cancelAll()
            it.dispatcher.executorService.shutdownNow()
            it.connectionPool.evictAll()
        }
        clients.clear()
        server.close()
        database.close()
    }

    @Test
    fun mineMenu_triggersAccountAndAllFourSubEntriesWithMin48dpTouchTargets() {
        var accountClicks = 0
        val selected = mutableListOf<ShellSidebarEntry>()

        composeRule.setContent {
            HiTheme(HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                MineMenuScreen(
                    account = testAccount,
                    onAccountClick = { accountClicks++ },
                    onSelectSubEntry = selected::add,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("HiUser").assertIsDisplayed().performClick()
        assertEquals(1, accountClicks)

        val entries = listOf(
            "收藏与关注" to ShellSidebarEntry.FavoritesAndAttention,
            "我的帖子" to ShellSidebarEntry.MyThreads,
            "浏览历史" to ShellSidebarEntry.History,
            "设置" to ShellSidebarEntry.Settings,
        )
        for ((label, expected) in entries) {
            val node = composeRule.onNodeWithText(label).assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("$label 触控区高度必须 ≥ 48dp", with(composeRule.density) { bounds.height.toDp() } >= 48.dp)
            node.performClick()
            assertEquals(expected, selected.last())
        }
        composeRule.assertGlassSurfaceCountAtMost(0)
    }

    @Test
    fun favoritesAndAttention_removeAndUndoRestoresViaMockWebServer() {
        val recordedTargets = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                recordedTargets += target
                return when {
                    target.contains("item=favorites&type=thread") -> gbkHtmlResponse(
                        favoriteListHtml(testAccount.uid, testAccount.username, FavoriteKind.FAVORITE, listOf("7001" to "收藏主题一", "7002" to "收藏主题二")),
                    )
                    target.contains("item=attention&type=thread") -> gbkHtmlResponse(
                        favoriteListHtml(testAccount.uid, testAccount.username, FavoriteKind.ATTENTION, listOf("8001" to "关注主题一")),
                    )
                    target.contains("item=favorites&action=remove") -> gbkXmlResponse("此主题已成功从您的收藏夹中移除。")
                    target.contains("item=favorites&action=add") -> gbkXmlResponse("此主题已成功添加到您的收藏夹中。")
                    target.contains("item=attention&action=remove") -> gbkXmlResponse("已取消对此主题的关注。")
                    target.contains("item=attention&action=add") -> gbkXmlResponse("指定主题已成功加入到关注列表中。")
                    else -> MockResponse(code = 404)
                }
            }
        }

        val authRepo = MutableAuthRepo(testAccount)
        val (favRepo, gateway) = createMockWebFavoriteRepo(authRepo)
        val viewModel = FavoritesViewModel(favRepo, authRepo, gateway)
        val opened = mutableListOf<ThreadNavArgs>()

        composeRule.setContent {
            MineTestHost {
                FavoritesPane(viewModel, showBack = true, onBack = {}, onOpenThread = opened::add, onOpenLogin = {})
            }
        }
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("收藏主题一").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.assertGlassSurfaceCountAtMost(0)

        // 点击进入帖子
        composeRule.onNodeWithText("收藏主题二").performClick()
        assertEquals(listOf(ThreadNavArgs("7002", "收藏主题二", forumName = "Discovery")), opened)

        // 取消收藏 -> 立即移除 + Snackbar “已取消收藏” + “撤销”恢复
        composeRule.onAllNodesWithContentDescription("取消收藏").onFirst().performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("已取消收藏").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("收藏主题一").assertDoesNotExist()
        assertFalse(favRepo.isFavorite("7001"))

        composeRule.onNodeWithText("撤销").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("收藏主题一").fetchSemanticsNodes().isNotEmpty() && favRepo.isFavorite("7001")
        }
        assertTrue(recordedTargets.any { it.contains("item=favorites&action=remove") && it.contains("tid=7001") })
        assertTrue(recordedTargets.any { it.contains("item=favorites&action=add") && it.contains("tid=7001") })

        // 切至“关注”标签 -> 取消关注 + “撤销”恢复
        composeRule.onNodeWithText("关注").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("关注主题一").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("取消关注").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("已取消关注").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("关注主题一").assertDoesNotExist()
        assertFalse(favRepo.isAttention("8001"))

        composeRule.onNodeWithText("撤销").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("关注主题一").fetchSemanticsNodes().isNotEmpty() && favRepo.isAttention("8001")
        }
        assertTrue(recordedTargets.any { it.contains("item=attention&action=remove") && it.contains("tid=8001") })
        assertTrue(recordedTargets.any { it.contains("item=attention&action=add") && it.contains("tid=8001") })
    }

    @Test
    fun myPosts_singlePaneShowsDualTabsAndReplyCarriesTargetPid_sidebarHidesDuplicateTabs() {
        val authRepo = MutableAuthRepo(testAccount)
        val searchRepo = FakeMyPostsSearchRepo(
            threads = listOf(MyThreadItem("9001", "我发布的主题", "2026-11-26", "Discovery")),
            replies = listOf(MyReplyItem("9002", "778899", "我回复的讨论帖", "这是我的回复预览", "2026-11-26", "Geek Talks")),
        )
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        val singleVm = MyPostsViewModel(searchRepo, authRepo, gateway)
        val switchedEntries = mutableListOf<ShellSidebarEntry>()
        val opened = mutableListOf<ThreadNavArgs>()

        composeRule.setContent {
            MineTestHost {
                MyPostsPane(
                    viewModel = singleVm,
                    entry = ShellSidebarEntry.MyThreads,
                    isSidebar = false,
                    showBack = true,
                    onBack = {},
                    onSelectMineEntry = switchedEntries::add,
                    onOpenThread = opened::add,
                    onOpenLogin = {},
                )
            }
        }
        composeRule.waitForIdle()

        // 单栏下显示“主题 | 回复”双标签
        composeRule.onNodeWithText("主题").assertIsDisplayed()
        composeRule.onNodeWithText("回复").assertIsDisplayed()
        composeRule.onNodeWithText("我发布的主题").assertIsDisplayed()
        composeRule.assertGlassSurfaceCountAtMost(0)

        // 切到“回复”标签并点击回复项，验证携带 targetPid
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(ShellSidebarEntry.MyReplies), switchedEntries)
        composeRule.onNodeWithText("我回复的讨论帖").assertIsDisplayed().performClick()
        assertEquals(listOf(ThreadNavArgs("9002", "我回复的讨论帖", forumName = "Geek Talks", targetPid = "778899")), opened)
    }

    @Test
    fun myPosts_wideSidebar_hidesDuplicateTabsAndShowsSingleEntry() {
        val authRepo = MutableAuthRepo(testAccount)
        val searchRepo = FakeMyPostsSearchRepo(
            threads = emptyList(),
            replies = listOf(MyReplyItem("9010", "556677", "宽屏侧栏我的回复项", "回复摘录", "昨天", "Discovery")),
        )
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        val wideVm = MyPostsViewModel(searchRepo, authRepo, gateway, initialTab = MyPostsTab.Replies)
        val opened = mutableListOf<ThreadNavArgs>()

        composeRule.setContent {
            MineTestHost {
                MyPostsPane(
                    viewModel = wideVm,
                    entry = ShellSidebarEntry.MyReplies,
                    isSidebar = true,
                    showBack = false,
                    onBack = null,
                    onSelectMineEntry = {},
                    onOpenThread = opened::add,
                    onOpenLogin = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("我的回复").assertIsDisplayed()
        composeRule.onNodeWithText("主题").assertDoesNotExist()
        composeRule.onNodeWithText("回复").assertDoesNotExist()
        composeRule.onNodeWithText("宽屏侧栏我的回复项").assertIsDisplayed().performClick()
        assertEquals("556677", opened.single().targetPid)
        composeRule.assertGlassSurfaceCountAtMost(0)
    }

    @Test
    fun browseHistory_worksOfflineAndRestoresRoomOnSingleDeleteAndClearAllUndo() {
        val dao = database.browseHistoryDao()
        val e1 = BrowseHistoryEntity("101", "离线历史一", "2", "Discovery", "alice", 1_764_100_000_000L)
        val e2 = BrowseHistoryEntity("102", "离线历史二", "6", "Buy & Sell", "bob", 1_764_090_000_000L)
        val e3 = BrowseHistoryEntity("103", "离线历史三", "7", "Geek Talks", "carol", 1_764_080_000_000L)
        runBlocking { dao.upsert(e1); dao.upsert(e2); dao.upsert(e3) }

        val vm = BrowseHistoryViewModel(dao)
        val opened = mutableListOf<ThreadNavArgs>()
        composeRule.setContent {
            MineTestHost {
                BrowseHistoryPane(vm, showBack = true, onBack = {}, onOpenThread = opened::add)
            }
        }
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("离线历史一").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("离线历史一").assertIsDisplayed().performClick()
        assertEquals(listOf(ThreadNavArgs("101", "离线历史一", 2, "Discovery", "alice")), opened)
        composeRule.assertGlassSurfaceCountAtMost(0)

        // 单条删除 + 撤销完整恢复 Room 记录
        composeRule.onAllNodesWithContentDescription("删除")[1].performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("已删除").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("离线历史二").assertDoesNotExist()
        assertEquals(listOf("101", "103"), runBlocking { dao.observeRecent(10).first().map { it.tid } })

        composeRule.onNodeWithText("撤销").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("离线历史二").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf(e1, e2, e3), runBlocking { dao.observeRecent(10).first() })

        // 一键清空 -> 空态 “暂无浏览历史” -> 撤销完整恢复全部 Room 记录
        composeRule.onNodeWithText("清空").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("暂无浏览历史").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(runBlocking { dao.observeRecent(10).first().isEmpty() })

        composeRule.onNodeWithText("撤销").performClick()
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("离线历史一").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("离线历史三").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf(e1, e2, e3), runBlocking { dao.observeRecent(10).first() })
    }

    @Test
    fun signedOut_showsInPlaceLoginPromptAndTriggersCallback() {
        val authRepo = MutableAuthRepo(initialAccount = null)
        val (favRepo, gateway) = createMockWebFavoriteRepo(authRepo)
        val favVm = FavoritesViewModel(favRepo, authRepo, gateway)
        var loginClicks = 0

        composeRule.setContent {
            MineTestHost {
                FavoritesPane(favVm, showBack = false, onBack = null, onOpenThread = {}, onOpenLogin = { loginClicks++ })
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("需要登录才能查看").assertIsDisplayed()
        composeRule.onNodeWithText("登录").assertIsDisplayed().performClick()
        assertEquals(1, loginClicks)
        composeRule.assertGlassSurfaceCountAtMost(0)
    }

    @Test
    fun rendererImpl_mountsHistoryAndMenuWithZeroGlass() {
        val dao = database.browseHistoryDao()
        runBlocking { dao.upsert(BrowseHistoryEntity("501", "渲染器直连历史", "2", "Discovery", "alice", 1_764_100_000_000L)) }
        val authRepo = MutableAuthRepo(testAccount)
        val (favRepo, gateway) = createMockWebFavoriteRepo(authRepo)
        val renderer = SearchMinePaneRendererImpl(
            searchRepository = FakeMyPostsSearchRepo(emptyList(), emptyList()),
            favoriteRepository = favRepo,
            browseHistoryDao = dao,
            authRepository = authRepo,
            settingsRepository = StaticSettingsRepo(AppSettings()),
            siteAccessGateway = gateway,
        )

        composeRule.setContent {
            MineTestHost {
                renderer.MineSubPageContent(
                    entry = ShellSidebarEntry.History,
                    isSidebar = true,
                    showBack = false,
                    onBack = null,
                    onSelectMineEntry = {},
                    onOpenThread = {},
                    onOpenLogin = {},
                    contentPadding = PaddingValues(0.dp),
                    listState = rememberLazyListState(),
                )
            }
        }
        composeRule.waitUntil(5_000L) {
            composeRule.onAllNodesWithText("渲染器直连历史").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("渲染器直连历史").assertIsDisplayed()
        composeRule.assertGlassSurfaceCountAtMost(0)
    }

    @androidx.compose.runtime.Composable
    private fun MineTestHost(content: @androidx.compose.runtime.Composable () -> Unit) {
        val snackbarHostState = remember { SnackbarHostState() }
        val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
        HiTheme(HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
            CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                Box(Modifier.fillMaxSize()) {
                    content()
                    HiSnackbarHost(hostState = snackbarHostState, bottomClearance = 16.dp)
                }
            }
        }
    }

    private fun createMockWebFavoriteRepo(authRepo: AuthRepository): Pair<DefaultFavoriteRepository, SiteAccessGateway> {
        val now = AtomicLong(1_700_000_000_000L)
        val client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore { now.get() },
            userAgentProvider = UserAgentProvider { "Mozilla/5.0" },
            transportInterceptor = TransportInterceptor({ null }, { false }),
        ).also(clients::add)
        val gateway = SiteAccessGateway(client, ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, { now.get() }, scope, 5.seconds)
        val settingsRepo = StaticSettingsRepo(AppSettings(forumServer = server.url("").toString().trimEnd('/')))
        val httpClient = ForumHttpClient(gateway, settingsRepo, authRepo)
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { File(tempFolder.newFolder(), "fav.preferences_pb") },
        )
        val repo = DefaultFavoriteRepository(httpClient, authRepo, FavoriteCacheStore(dataStore), { now.get() }, scope)
        return repo to gateway
    }

    private class MutableAuthRepo(initialAccount: SavedAccount?) : AuthRepository {
        private val accountFlow = MutableStateFlow(initialAccount)
        private val stateFlow = MutableStateFlow<AuthState>(initialAccount?.let(AuthState::SignedIn) ?: AuthState.SignedOut)
        override val currentAccount: StateFlow<SavedAccount?> = accountFlow.asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = accountFlow.map { listOfNotNull(it) }.stateIn(CoroutineScope(Dispatchers.Unconfined), SharingStarted.Eagerly, listOfNotNull(initialAccount))
        override val authState: StateFlow<AuthState> = stateFlow.asStateFlow()
        override var sessionEpoch: Long = if (initialAccount != null) 1L else 0L
        override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) = LoginResult.Failure("offline")
        override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
        override suspend fun logout() { accountFlow.value = null; stateFlow.value = AuthState.SignedOut }
        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
    }

    private class FakeMyPostsSearchRepo(
        private val threads: List<MyThreadItem>,
        private val replies: List<MyReplyItem>,
    ) : SearchRepository {
        override val searchHistory: Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun search(query: String, author: String, forumId: Int?, fullText: Boolean, page: Int, searchId: String?, priority: AccessPriority, recordHistory: Boolean) =
            SearchResult.Success(1, false, SearchPage(null, 1, emptyList(), meta()))
        override suspend fun getNewPosts(page: Int, searchId: String?, priority: AccessPriority) =
            SearchResult.Success(1, false, SearchPage(null, 1, emptyList(), meta()))
        override suspend fun getMyThreads(page: Int, priority: AccessPriority) =
            MyThreadsResult.Success(page, MyThreadsPage(1, threads, meta(), hasTable = true))
        override suspend fun getMyReplies(page: Int, priority: AccessPriority) =
            MyRepliesResult.Success(page, MyRepliesPage(1, replies, meta(), hasTable = true))
        override suspend fun recordSearchHistory(query: String) = emptyList<String>()
        override suspend fun removeSearchHistory(query: String): RemovedSearchHistoryEntry? = null
        override suspend fun restoreSearchHistory(query: String, index: Int) = emptyList<String>()
        override suspend fun clearSearchHistory() = Unit
        override suspend fun resolveForumLink(rawInput: String): ResolvedForumLink? = null
        private fun meta() = DiscuzPageMeta(DiscuzSessionInfo(true, "10000", "HiUser"), "fh", null, PromptCounts())
    }

    private class StaticSettingsRepo(initial: AppSettings) : SettingsRepository {
        override val settings: Flow<AppSettings> = MutableStateFlow(initial)
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

        fun gbkHtmlResponse(html: String): MockResponse = MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "text/html; charset=gbk")
            .body(Buffer().write(html.toByteArray(GBK)))
            .build()

        fun gbkXmlResponse(message: String): MockResponse {
            val xml = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[$message]]></root>"""
            return MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/xml; charset=gbk")
                .body(Buffer().write(xml.toByteArray(GBK)))
                .build()
        }

        fun favoriteListHtml(uid: String, username: String, kind: FavoriteKind, items: List<Pair<String, String>>): String {
            val rows = items.joinToString("\n") { (tid, title) ->
                """
                <tr>
                    <td class="selector"><input class="checkbox" type="checkbox" name="delete[]" value="$tid" /></td>
                    <th><a href="viewthread.php?tid=$tid" target="_blank">$title</a></th>
                    <td class="forum"><a href="forumdisplay.php?fid=2" target="_blank">Discovery</a></td>
                    <td class="nums">12</td>
                    <td class="lastpost"><cite><a href="space.php?uid=$uid">$username</a></cite><em>2026-11-26 09:30</em></td>
                </tr>
                """.trimIndent()
            }
            return """
                <html><body>
                <div id="umenu"><cite><a href="space.php?uid=$uid">$username</a></cite><a href="logging.php?action=logout&amp;formhash=a1b2c3d4">退出</a></div>
                <form method="post" action="my.php?item=${kind.itemParam}&amp;type=thread">
                <input type="hidden" name="formhash" value="a1b2c3d4" />
                <table class="datatable"><tbody>$rows</tbody></table>
                </form>
                </body></html>
            """.trimIndent()
        }
    }
}
