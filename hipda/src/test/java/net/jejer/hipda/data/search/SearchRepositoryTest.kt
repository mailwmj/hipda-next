package net.jejer.hipda.data.search

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.parser.SampleTestLoader
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SearchRepositoryTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var nowMs: AtomicLong
    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var settingsRepository: SearchTestSettingsRepository
    private lateinit var authRepository: SearchTestAuthRepository
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val clientsToClose = mutableListOf<OkHttpClient>()

    private val defaultAccount = SavedAccount(
        uid = "10000",
        username = "HiUser",
        passwordMd5 = "0123456789abcdef0123456789abcdef",
        lastLoginAt = 1_000L,
    )

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        nowMs = AtomicLong(1_700_000_000_000L)
        cookieStore = InMemorySiteCookieStore(clock = { nowMs.get() })
        server = MockWebServer()
        server.start()
        settingsRepository = SearchTestSettingsRepository(
            AppSettings(forumServer = server.url("").toString().trimEnd('/')),
        )
        authRepository = SearchTestAuthRepository(initialAccount = defaultAccount)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        clientsToClose.forEach { client ->
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        clientsToClose.clear()
        server.close()
    }

    private fun createHistoryStore(): SearchHistoryStore {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { File(tempFolder.newFolder(), "search_history.preferences_pb") },
        )
        return SearchHistoryStore(ds)
    }

    private fun createRepository(
        historyStore: SearchHistoryStore = createHistoryStore(),
        solver: ChallengeSolver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) },
    ): Pair<DefaultSearchRepository, SearchHistoryStore> {
        val client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = TransportInterceptor(
                delegateFactory = { null },
                isMainThread = { false },
            ),
        ).also(clientsToClose::add)
        val gateway = SiteAccessGateway(
            client = client,
            detector = ChallengeDetector(),
            solver = solver,
            clock = { nowMs.get() },
            gatewayScope = scope,
            solveTimeout = 5.seconds,
        )
        val forumHttpClient = ForumHttpClient(
            siteAccessGateway = gateway,
            settingsRepository = settingsRepository,
            authRepository = authRepository,
        )
        val repo = DefaultSearchRepository(
            forumHttpClient = forumHttpClient,
            authRepository = authRepository,
            settingsRepository = settingsRepository,
            searchHistoryStore = historyStore,
        )
        return repo to historyStore
    }

    @Test
    fun search_realSampleEncodesChineseQueryAndAuthorInGbkDerivesAvatarAndRecordsHistory() = runBlocking {
        val sampleHtml = SampleTestLoader.loadSample("search/search_result.html")
        val baseServer = server.url("").toString().trimEnd('/')
        settingsRepository.setForumServer("$baseServer/forum")
        server.enqueue(searchTestGbkHtmlResponse(sampleHtml))

        val (repo, historyStore) = createRepository()
        val result = repo.search(
            query = "数码 拼图",
            author = "测试作者",
            forumId = 6,
            fullText = false,
            page = 1,
        )

        assertTrue("Expected Success, got $result", result is SearchResult.Success)
        val success = result as SearchResult.Success
        assertFalse(success.fullText)
        assertEquals(1, success.page)
        assertEquals("511", success.searchId)
        assertEquals(5, success.maxPage)
        assertTrue(success.hasNextPage)
        assertEquals(75, success.items.size)

        val first = success.items.first()
        assertEquals("3478055", first.tid)
        assertEquals("送2堆puzzle解密解压拼图玩具", first.title)
        val expectedAvatarPrefix = "$baseServer/forum/uc_server/data/avatar/"
        assertTrue(
            "Expected avatarUrl starting with $expectedAvatarPrefix, got ${first.avatarUrl}",
            first.avatarUrl?.startsWith(expectedAvatarPrefix) == true,
        )
        assertFalse(first.avatarUrl.orEmpty().contains("/forum/forum/"))

        val appleItem = success.items.firstOrNull { it.tid == "3477944" }
        assertTrue("Expected decoded apple emoji, got ${appleItem?.title}", appleItem?.title?.contains("🍎") == true)

        // Verify exact GBK URL encoding in request target
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        val expectedQueryGbk = URLEncoder.encode("数码 拼图", "GBK")
        val expectedAuthorGbk = URLEncoder.encode("测试作者", "GBK")
        assertEquals(
            "/forum/search.php?srchtype=title&srchtxt=$expectedQueryGbk&searchsubmit=true&st=on" +
                "&srchuname=$expectedAuthorGbk&srchfilter=all&srchfrom=0&before=&orderby=lastpost" +
                "&ascdesc=desc&srchfid%5B0%5D=6",
            recorded.target,
        )

        // History recorded automatically on successful page-1 search
        assertEquals(listOf("数码 拼图"), historyStore.history.first())
    }

    @Test
    fun search_fullTextAllForumsPaginationAndSearchIdBuildExactPaths() = runBlocking {
        val fullTextHtml = searchTestLoggedInHtml(
            body = """
                <div class="pages_btns"><div class="pages">
                  <strong>1</strong>
                  <a href="search.php?searchid=8899&amp;orderby=lastpost&amp;page=2">2</a>
                  <a href="search.php?searchid=8899&amp;orderby=lastpost&amp;page=4">4</a>
                </div></div>
                <table class="datatable">
                  <tr><td>
                    <div class="sp_title"><a href="gotopost.php?pid=456789">全文命中 &amp;#128512;</a></div>
                    <div class="sp_content">正文摘要 &amp;#128076;</div>
                    <div class="sp_theard">
                      <span>版块: <a href="forumdisplay.php?fid=2">Discovery</a></span>
                      <span>作者: <a href="space.php?uid=189027">Alice</a></span>
                      <span>查看: 100</span>
                      <span>回复: 12</span>
                      <span>最后发表: 2026-11-25 18:30</span>
                    </div>
                  </td></tr>
                </table>
            """.trimIndent(),
        )
        server.enqueue(searchTestGbkHtmlResponse(fullTextHtml))
        server.enqueue(searchTestGbkHtmlResponse(fullTextHtml))
        server.enqueue(searchTestGbkHtmlResponse(fullTextHtml))

        val (repo, historyStore) = createRepository()

        // 1. Fulltext search across all forums without recording history
        val page1 = repo.search(
            query = "显卡",
            forumId = null,
            fullText = true,
            page = 1,
            recordHistory = false,
        )
        assertTrue("Expected Success, got $page1", page1 is SearchResult.Success)
        val s1 = page1 as SearchResult.Success
        assertTrue(s1.fullText)
        assertEquals("8899", s1.searchId)
        assertEquals(4, s1.maxPage)
        assertEquals(1, s1.items.size)
        assertEquals("456789", s1.items[0].pid)
        assertEquals("全文命中 😀", s1.items[0].title)
        assertEquals("正文摘要 👌", s1.items[0].snippet)
        assertEquals(emptyList<String>(), historyStore.history.first())

        // 2. Paginate with searchId (page = 2)
        val page2 = repo.search(
            query = "显卡",
            fullText = true,
            page = 2,
            searchId = "8899",
        )
        assertTrue(page2 is SearchResult.Success)
        assertEquals(2, (page2 as SearchResult.Success).page)
        // Paginated searchId fetch does not re-record history
        assertEquals(emptyList<String>(), historyStore.history.first())

        // 3. Author-only search with page = 3 without searchId
        val authorOnly = repo.search(
            query = "",
            author = "Alice",
            page = 3,
        )
        assertTrue(authorOnly is SearchResult.Success)

        val expectedCardGbk = URLEncoder.encode("显卡", "GBK")
        assertEquals(
            "/forum/search.php?srchtype=fulltext&srchtxt=$expectedCardGbk&searchsubmit=true&st=on" +
                "&srchuname=&srchfilter=all&srchfrom=0&before=&orderby=lastpost&ascdesc=desc&srchfid%5B0%5D=all",
            server.takeRequest().target,
        )
        assertEquals(
            "/forum/search.php?searchid=8899&orderby=lastpost&ascdesc=desc&searchsubmit=yes&page=2",
            server.takeRequest().target,
        )
        assertEquals(
            "/forum/search.php?srchtype=title&srchtxt=&searchsubmit=true&st=on" +
                "&srchuname=Alice&srchfilter=all&srchfrom=0&before=&orderby=lastpost&ascdesc=desc&srchfid%5B0%5D=all&page=3",
            server.takeRequest().target,
        )
    }

    @Test
    fun search_emptyResultReturnsEmptySuccessWhileRateLimitReturnsSiteError() = runBlocking {
        val (repo, historyStore) = createRepository()

        // 1. Discuz! "没有找到匹配结果" inside div.alert_error -> Success with empty items (NOT error)
        server.enqueue(
            searchTestGbkHtmlResponse(
                searchTestLoggedInHtml(
                    body = """<div class="alert_error">对不起，没有找到匹配结果。<a href="search.php">返回</a></div>""",
                ),
            ),
        )
        val noMatch = repo.search(query = "绝对搜不到的关键词xyz")
        assertTrue("Empty search must return Success, got $noMatch", noMatch is SearchResult.Success)
        val noMatchSuccess = noMatch as SearchResult.Success
        assertTrue(noMatchSuccess.items.isEmpty())
        assertFalse(noMatchSuccess.hasNextPage)
        assertEquals(listOf("绝对搜不到的关键词xyz"), historyStore.history.first())

        // 2. Empty table without alert_error -> Success with empty items
        server.enqueue(
            searchTestGbkHtmlResponse(
                searchTestLoggedInHtml(
                    body = """<div class="searchlist"><table class="datatable"></table><p class="nodata">暂无数据</p></div>""",
                ),
            ),
        )
        val emptyTable = repo.search(query = "空结果表", recordHistory = false)
        assertTrue(emptyTable is SearchResult.Success)
        assertTrue((emptyTable as SearchResult.Success).items.isEmpty())

        // 3. Genuine cooldown/rate-limit error -> SiteError (and does NOT record history)
        server.enqueue(
            searchTestGbkHtmlResponse(
                searchTestLoggedInHtml(
                    body = """<div class="alert_error">对不起，您在 15 秒内只能进行一次搜索<a href="search.php">返回</a></div>""",
                ),
            ),
        )
        val rateLimited = repo.search(query = "触发限频")
        assertEquals(SearchError.SiteError("对不起，您在 15 秒内只能进行一次搜索"), rateLimited)
        assertEquals(listOf("绝对搜不到的关键词xyz"), historyStore.history.first())
    }

    @Test
    fun getNewPosts_buildsInitialAndSearchIdPaginationPathsAndParsesRealSample() = runBlocking {
        val sampleHtml = SampleTestLoader.loadSample("search/search_result.html")
        server.enqueue(searchTestGbkHtmlResponse(sampleHtml))
        server.enqueue(searchTestGbkHtmlResponse(sampleHtml))
        server.enqueue(searchTestGbkHtmlResponse(sampleHtml))

        val (repo, _) = createRepository()

        val p1 = repo.getNewPosts(page = 1, searchId = null)
        assertTrue("Expected Success, got $p1", p1 is SearchResult.Success)
        val s1 = p1 as SearchResult.Success
        assertEquals("511", s1.searchId)
        assertEquals(5, s1.maxPage)
        assertEquals(75, s1.items.size)
        assertFalse(s1.fullText)

        val p2 = repo.getNewPosts(page = 2, searchId = "511")
        assertTrue(p2 is SearchResult.Success)
        assertEquals(2, (p2 as SearchResult.Success).page)

        val p1WithId = repo.getNewPosts(page = 1, searchId = "511")
        assertTrue(p1WithId is SearchResult.Success)

        assertEquals("/forum/search.php?srchfrom=86400&searchsubmit=yes", server.takeRequest().target)
        assertEquals(
            "/forum/search.php?searchid=511&orderby=lastpost&ascdesc=desc&searchsubmit=yes&page=2",
            server.takeRequest().target,
        )
        assertEquals(
            "/forum/search.php?searchid=511&orderby=lastpost&ascdesc=desc&searchsubmit=yes",
            server.takeRequest().target,
        )
    }

    @Test
    fun getMyThreadsAndMyReplies_parseRealSamplesEmptyStateAndMalformedPages() = runBlocking {
        val threadsSample = SampleTestLoader.loadSample("simple_list/my_threads.html")
        val postsSample = SampleTestLoader.loadSample("simple_list/my_posts.html")
        server.enqueue(searchTestGbkHtmlResponse(threadsSample))
        server.enqueue(searchTestGbkHtmlResponse(postsSample))
        server.enqueue(
            searchTestGbkHtmlResponse(
                searchTestLoggedInHtml(body = """<div class="datalist"><p class="nodata">暂无数据</p></div>"""),
            ),
        )
        server.enqueue(
            searchTestGbkHtmlResponse(
                searchTestLoggedInHtml(body = """<div id="broken">意外截断页面</div>"""),
            ),
        )

        val (repo, _) = createRepository()

        val threadsResult = repo.getMyThreads(page = 1)
        assertTrue("Expected Success, got $threadsResult", threadsResult is MyThreadsResult.Success)
        val threadsSuccess = threadsResult as MyThreadsResult.Success
        assertEquals(1, threadsSuccess.page)
        assertEquals(3, threadsSuccess.maxPage)
        assertTrue(threadsSuccess.hasNextPage)
        assertEquals(75, threadsSuccess.items.size)
        assertEquals("3410683", threadsSuccess.items[0].tid)
        assertEquals("已出", threadsSuccess.items[0].title)

        val repliesResult = repo.getMyReplies(page = 2)
        assertTrue("Expected Success, got $repliesResult", repliesResult is MyRepliesResult.Success)
        val repliesSuccess = repliesResult as MyRepliesResult.Success
        assertEquals(2, repliesSuccess.page)
        assertEquals(34, repliesSuccess.maxPage)
        assertTrue(repliesSuccess.hasNextPage)
        assertEquals(75, repliesSuccess.items.size)
        assertEquals("3474137", repliesSuccess.items[0].tid)
        assertEquals("74880921", repliesSuccess.items[0].pid)
        assertEquals("mark 一下 iOS fly ~", repliesSuccess.items[0].replyText)

        // Empty nodata page succeeds with empty list
        val emptyThreads = repo.getMyThreads(page = 1)
        assertTrue("Expected empty Success, got $emptyThreads", emptyThreads is MyThreadsResult.Success)
        assertTrue((emptyThreads as MyThreadsResult.Success).items.isEmpty())

        // Truncated malformed page without datatable or nodata returns InvalidPage
        val brokenReplies = repo.getMyReplies(page = 1)
        assertTrue("Expected InvalidPage, got $brokenReplies", brokenReplies is SearchError.InvalidPage)

        assertEquals("/forum/my.php?item=threads&page=1", server.takeRequest().target)
        assertEquals("/forum/my.php?item=posts&page=2", server.takeRequest().target)
        assertEquals("/forum/my.php?item=threads&page=1", server.takeRequest().target)
        assertEquals("/forum/my.php?item=posts&page=1", server.takeRequest().target)
    }

    @Test
    fun validationAuthGuestHttpChallengeAndHelpers_behaveDeterministically() = runBlocking {
        val solverCalls = AtomicInteger(0)
        val (repo, _) = createRepository(
            solver = ChallengeSolver {
                solverCalls.incrementAndGet()
                SolveResult.Failed(AssertionError("ChallengeSolver must not run under BackgroundSilent"))
            },
        )

        // 1. Invalid inputs rejected with zero network requests
        assertTrue(repo.search(query = "  ", author = "  ") is SearchError.InvalidInput)
        assertTrue(repo.search(query = "abc", page = 0) is SearchError.InvalidInput)
        assertTrue(repo.search(query = "abc", forumId = 0) is SearchError.InvalidInput)
        assertTrue(repo.search(query = "abc", searchId = "511&bad=1") is SearchError.InvalidInput)
        assertTrue(repo.getNewPosts(page = 0) is SearchError.InvalidInput)
        assertTrue(repo.getNewPosts(page = 2, searchId = "bad id!") is SearchError.InvalidInput)
        assertTrue(repo.getMyThreads(page = 0) is SearchError.InvalidInput)
        assertTrue(repo.getMyReplies(page = -1) is SearchError.InvalidInput)
        assertEquals(0, server.requestCount)

        // 2. Single silent relogin recovery on expired session for getMyThreads
        val reloginCalls = AtomicInteger(0)
        authRepository.onTrySilentRelogin = { _, _ ->
            reloginCalls.incrementAndGet()
            authRepository.bumpEpoch()
            SilentReloginResult.Recovered(authRepository.sessionEpoch)
        }
        server.enqueue(searchTestGbkHtmlResponse(searchTestLoggedOutHtml("登录已过期")))
        server.enqueue(searchTestGbkHtmlResponse(SampleTestLoader.loadSample("simple_list/my_threads.html")))
        val recovered = repo.getMyThreads(page = 1)
        assertTrue("Expected Success after relogin, got $recovered", recovered is MyThreadsResult.Success)
        assertEquals(1, reloginCalls.get())

        // 3. Guest mode on getNewPosts: allows public list; maps guest auth-required error to AuthRequired
        authRepository.setActiveAccount(null)
        authRepository.onTrySilentRelogin = null
        val guestPublicNewPostsHtml = """
            <!DOCTYPE html>
            <html><body>
              <div id="umenu"><a href="logging.php?action=login">登录</a></div>
              <table class="datatable">
                <tbody>
                  <tr>
                    <th class="subject"><a href="viewthread.php?tid=90001">游客可见新帖</a></th>
                    <td class="forum">Discovery</td>
                    <td class="author"><cite><a href="space.php?uid=12345">GuestPoster</a></cite><em>2026-11-25</em></td>
                  </tr>
                </tbody>
              </table>
            </body></html>
        """.trimIndent()
        server.enqueue(searchTestGbkHtmlResponse(guestPublicNewPostsHtml))
        val guestAllowed = repo.getNewPosts(page = 1)
        assertTrue("Expected Success for public guest new posts, got $guestAllowed", guestAllowed is SearchResult.Success)
        assertEquals("90001", (guestAllowed as SearchResult.Success).items.single().tid)

        server.enqueue(searchTestGbkHtmlResponse(searchTestLoggedOutHtml("您无权进行当前操作，请先登录")))
        val guestBlocked = repo.getNewPosts(page = 1)
        assertTrue("Expected AuthRequired for guest blocked page, got $guestBlocked", guestBlocked is SearchError.AuthRequired)
        assertEquals(AuthRequiredReason.NOT_LOGGED_IN, (guestBlocked as SearchError.AuthRequired).reason)

        // 4. HTTP 500 -> NetworkError
        authRepository.setActiveAccount(defaultAccount)
        server.enqueue(MockResponse.Builder().code(500).body("Internal Error").build())
        val httpErr = repo.search(query = "500test")
        assertTrue(httpErr is SearchError.NetworkError)
        assertEquals(500, (httpErr as SearchError.NetworkError).statusCode)

        // 5. Cloudflare 403 under BackgroundSilent -> ChallengeRequired(abandoned = true), 0 solver calls
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .body("challenge")
                .build()
        }
        val challengeErr = repo.getNewPosts(priority = AccessPriority.BackgroundSilent)
        assertTrue("Expected ChallengeRequired, got $challengeErr", challengeErr is SearchError.ChallengeRequired)
        assertTrue((challengeErr as SearchError.ChallengeRequired).abandoned)
        assertEquals(0, solverCalls.get())

        // 6. resolveForumLink reads dynamic forumServer + history delegation round-trip
        settingsRepository.setForumServer("https://mirror.example.org")
        assertEquals(
            ResolvedForumLink.Thread(tid = "42", page = 2),
            repo.resolveForumLink("https://mirror.example.org/forum/viewthread.php?tid=42&page=2"),
        )
        repo.recordSearchHistory("手动历史")
        assertEquals(listOf("手动历史"), repo.searchHistory.first())
        val removed = repo.removeSearchHistory("手动历史")
        assertEquals(RemovedSearchHistoryEntry("手动历史", 0), removed)
        assertEquals(emptyList<String>(), repo.searchHistory.first())
        repo.restoreSearchHistory(removed!!.query, removed.index)
        assertEquals(listOf("手动历史"), repo.searchHistory.first())
        repo.clearSearchHistory()
        assertEquals(emptyList<String>(), repo.searchHistory.first())
        assertNull(repo.resolveForumLink("普通搜索词"))
    }

    @Test
    fun s1_truncatedOrMalformed200SearchAndNewPostsReturnInvalidPageWithoutRecordingHistory() = runBlocking {
        val (repo, historyStore) = createRepository()
        val truncated200Html = searchTestLoggedInHtml(body = """<div id="broken">网关截断响应</div>""")
        server.enqueue(searchTestGbkHtmlResponse(truncated200Html))
        server.enqueue(searchTestGbkHtmlResponse(truncated200Html))

        val searchRes = repo.search(query = "截断关键词")
        assertTrue("Expected InvalidPage for truncated 200 search page, got $searchRes", searchRes is SearchError.InvalidPage)
        assertEquals(emptyList<String>(), historyStore.history.first())

        val newPostsRes = repo.getNewPosts(page = 1)
        assertTrue("Expected InvalidPage for truncated 200 new posts page, got $newPostsRes", newPostsRes is SearchError.InvalidPage)
    }

    @Test
    fun s2_historyStoreWriteIoExceptionDoesNotFailSearchWhileCancellationPropagates() = runBlocking {
        val backingDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { File(tempFolder.newFolder(), "search_history.preferences_pb") },
        )
        val writeFailure = AtomicReference<Throwable?>(IOException("disk full"))
        val faultDataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = backingDataStore.data
            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences {
                writeFailure.get()?.let { throw it }
                return backingDataStore.updateData(transform)
            }
        }
        val (repo, _) = createRepository(historyStore = SearchHistoryStore(faultDataStore))

        // 1. IOException during history write degrades cleanly and still returns SearchResult.Success
        server.enqueue(searchTestGbkHtmlResponse(SampleTestLoader.loadSample("search/search_result.html")))
        val ok = repo.search(query = "磁盘满搜索")
        assertTrue("Expected Success even when history write throws IOException, got $ok", ok is SearchResult.Success)
        assertEquals(75, (ok as SearchResult.Success).items.size)

        // 2. CancellationException during history write propagates cleanly
        writeFailure.set(CancellationException("history cancelled"))
        server.enqueue(searchTestGbkHtmlResponse(SampleTestLoader.loadSample("search/search_result.html")))
        var cancelled = false
        try {
            repo.search(query = "取消传播搜索")
        } catch (e: CancellationException) {
            cancelled = e.message == "history cancelled"
        }
        assertTrue("Expected CancellationException to propagate", cancelled)
    }

    @Test
    fun s4_1_searchEncodesSupplementaryEmojiViaToGbkSafeDecimalEntities() = runBlocking {
        val (repo, _) = createRepository()
        server.enqueue(searchTestGbkHtmlResponse(SampleTestLoader.loadSample("search/search_result.html")))

        val res = repo.search(query = "苹果 🍎", author = "作者😀", recordHistory = false)
        assertTrue("Expected Success, got $res", res is SearchResult.Success)

        val target = server.takeRequest().target
        // 🍎 = U+1F34E (127822), 😀 = U+1F600 (128512) -> &#127822; / &#128512; percent-encoded
        assertTrue("Expected decimal entity encoding for 🍎 in $target", target.contains("%26%23127822%3B"))
        assertTrue("Expected decimal entity encoding for 😀 in $target", target.contains("%26%23128512%3B"))
        assertFalse("Must not replace Emoji with '?' (%3F) in $target", target.contains("%3F"))
    }

    @Test
    fun s4_3_guestLoginFormPageOrLoggingRedirectReturnsAuthRequired() = runBlocking {
        authRepository.setActiveAccount(null)
        val (repo, _) = createRepository()

        // 1. Guest hits 200 login form without div.alert_error
        val loginFormHtml = """
            <!DOCTYPE html>
            <html><body>
              <div id="umenu"><a href="logging.php?action=login">登录</a></div>
              <form id="loginform" method="post" action="logging.php?action=login&amp;loginsubmit=yes">
                <input type="text" name="username" />
              </form>
            </body></html>
        """.trimIndent()
        server.enqueue(searchTestGbkHtmlResponse(loginFormHtml))
        val formAuthErr = repo.getNewPosts(page = 1)
        assertTrue("Expected AuthRequired for login form page, got $formAuthErr", formAuthErr is SearchError.AuthRequired)
        assertEquals(AuthRequiredReason.NOT_LOGGED_IN, (formAuthErr as SearchError.AuthRequired).reason)

        // 2. Guest receives 302 redirect to logging.php
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "logging.php?action=login")
                .build(),
        )
        server.enqueue(
            searchTestGbkHtmlResponse("<!DOCTYPE html><html><body><div>请登录</div></body></html>"),
        )
        val redirectAuthErr = repo.search(query = "需要登录")
        assertTrue("Expected AuthRequired for logging.php redirect, got $redirectAuthErr", redirectAuthErr is SearchError.AuthRequired)
        assertEquals(AuthRequiredReason.NOT_LOGGED_IN, (redirectAuthErr as SearchError.AuthRequired).reason)
    }

    companion object {
        private const val TEST_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

private val SEARCH_TEST_GB18030: Charset = Charset.forName("GB18030")

internal fun searchTestGbkHtmlResponse(
    html: String,
    code: Int = 200,
): MockResponse = MockResponse.Builder()
    .code(code)
    .addHeader("Content-Type", "text/html; charset=gbk")
    .body(Buffer().write(html.toByteArray(SEARCH_TEST_GB18030)))
    .build()

internal fun searchTestLoggedInHtml(
    uid: String = "10000",
    username: String = "HiUser",
    body: String = "",
): String = """
    <!DOCTYPE html>
    <html>
    <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
    <body>
      <div id="umenu">
        <cite><a href="space.php?uid=$uid" class="noborder">$username</a></cite>
        <a href="logging.php?action=logout&amp;formhash=00000000">退出</a>
      </div>
      $body
    </body>
    </html>
""".trimIndent()

internal fun searchTestLoggedOutHtml(message: String = "您无权进行当前操作"): String = """
    <!DOCTYPE html>
    <html>
    <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
    <body>
      <div id="umenu"><a href="logging.php?action=login">登录</a></div>
      <div class="alert_error">$message</div>
    </body>
    </html>
""".trimIndent()

internal class SearchTestSettingsRepository(
    initial: AppSettings = AppSettings(),
) : SettingsRepository {
    private val _settings = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = _settings.asStateFlow()

    override suspend fun setThemeMode(themeMode: ThemeMode) { _settings.update { it.copy(themeMode = themeMode) } }
    override suspend fun setPureBlack(enabled: Boolean) { _settings.update { it.copy(pureBlack = enabled) } }
    override suspend fun setThemeSeed(themeSeed: String) { _settings.update { it.copy(themeSeed = themeSeed) } }
    override suspend fun setFontStep(step: Int) { _settings.update { it.copy(fontStep = step) } }
    override suspend fun setLineSpacing(lineSpacing: LineSpacing) { _settings.update { it.copy(lineSpacing = lineSpacing) } }
    override suspend fun setFollowedForums(forumIds: List<Int>) { _settings.update { it.copy(followedForums = forumIds) } }
    override suspend fun setShowStickyThreads(show: Boolean) { _settings.update { it.copy(showStickyThreads = show) } }
    override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) {
        _settings.update { it.copy(sortByPostTime = it.sortByPostTime + (fid to sortByPostTime)) }
    }
    override suspend fun setTailEnabled(enabled: Boolean) { _settings.update { it.copy(tailEnabled = enabled) } }
    override suspend fun setTailText(text: String) { _settings.update { it.copy(tailText = text) } }
    override suspend fun setTailUrl(url: String) { _settings.update { it.copy(tailUrl = url) } }
    override suspend fun setNotificationsEnabled(enabled: Boolean) { _settings.update { it.copy(notificationsEnabled = enabled) } }
    override suspend fun setSilentModeEnabled(enabled: Boolean) { _settings.update { it.copy(silentModeEnabled = enabled) } }
    override suspend fun setSilentBeginMinutes(minutes: Int) { _settings.update { it.copy(silentBeginMinutes = minutes) } }
    override suspend fun setSilentEndMinutes(minutes: Int) { _settings.update { it.copy(silentEndMinutes = minutes) } }
    override suspend fun setForumServer(server: String) { _settings.update { it.copy(forumServer = server) } }
    override suspend fun setImageCacheLimitMb(limitMb: Int) { _settings.update { it.copy(imageCacheLimitMb = limitMb) } }
    override suspend fun setPaneSplitPortrait(split: PaneSplit) { _settings.update { it.copy(paneSplitPortrait = split) } }
    override suspend fun setPaneSplitLandscape(split: PaneSplit) { _settings.update { it.copy(paneSplitLandscape = split) } }
    override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) { _settings.update { it.copy(sidebarCollapsedPortrait = collapsed) } }
    override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) { _settings.update { it.copy(sidebarCollapsedLandscape = collapsed) } }
}

internal class SearchTestAuthRepository(
    initialAccount: SavedAccount? = null,
) : AuthRepository {
    private val _currentAccount = MutableStateFlow(initialAccount)
    override val currentAccount = _currentAccount.asStateFlow()

    private val _savedAccounts = MutableStateFlow(listOfNotNull(initialAccount))
    override val savedAccounts = _savedAccounts.asStateFlow()

    private val _authState = MutableStateFlow<AuthState>(
        if (initialAccount != null) AuthState.SignedIn(initialAccount) else AuthState.SignedOut,
    )
    override val authState = _authState.asStateFlow()

    @Volatile
    private var epoch: Long = 1L
    override val sessionEpoch: Long get() = epoch

    @Volatile
    var nextReloginResult: SilentReloginResult = SilentReloginResult.NotLoggedIn

    @Volatile
    var onTrySilentRelogin: (suspend (Long, AccessPriority) -> SilentReloginResult)? = null

    fun setActiveAccount(account: SavedAccount?) {
        _currentAccount.value = account
        if (account != null) {
            _savedAccounts.update { list -> list.filterNot { it.uid == account.uid } + account }
            _authState.value = AuthState.SignedIn(account)
        } else {
            _authState.value = AuthState.SignedOut
        }
    }

    fun bumpEpoch(): Long {
        epoch += 1L
        return epoch
    }

    override suspend fun login(
        username: String,
        passwordOrMd5: String,
        securityQuestionId: Int,
        securityAnswer: String,
    ): LoginResult = LoginResult.Failure("Not used in unit test")

    override suspend fun switchAccount(uid: String): LoginResult = LoginResult.Failure("Not used in unit test")

    override suspend fun logout() {
        setActiveAccount(null)
    }

    override suspend fun removeAccount(uid: String) {
        _savedAccounts.update { list -> list.filterNot { it.uid == uid } }
        if (_currentAccount.value?.uid == uid) {
            setActiveAccount(null)
        }
    }

    override suspend fun trySilentRelogin(
        failedEpoch: Long,
        priority: AccessPriority,
    ): SilentReloginResult {
        val handler = onTrySilentRelogin
        return if (handler != null) {
            handler(failedEpoch, priority)
        } else {
            nextReloginResult
        }
    }
}
