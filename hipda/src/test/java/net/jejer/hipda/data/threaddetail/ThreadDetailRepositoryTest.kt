package net.jejer.hipda.data.threaddetail

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import dagger.Binds
import dagger.Module
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginFailureReason
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.local.BrowseHistoryDao
import net.jejer.hipda.data.local.BrowseHistoryEntity
import net.jejer.hipda.data.local.HiDatabase
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionDao
import net.jejer.hipda.data.local.ReadingPositionEntity
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
import net.jejer.hipda.data.user.BlacklistMutationResult
import net.jejer.hipda.data.user.BlacklistRepository
import net.jejer.hipda.data.user.BlacklistSnapshot
import net.jejer.hipda.data.user.BlacklistSyncResult
import net.jejer.hipda.di.ThreadDetailModule
import net.jejer.hipda.parser.SampleTestLoader
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ThreadDetailRepositoryTest {

    private lateinit var nowMs: AtomicLong
    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var authRepository: FakeAuthRepository
    private lateinit var blacklistRepository: FakeBlacklistRepository
    private lateinit var database: HiDatabase
    private lateinit var browseHistoryDao: BrowseHistoryDao
    private lateinit var readingPositionDao: ReadingPositionDao
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        OkHttp.initialize(context)
        nowMs = AtomicLong(1_700_000_000_000L)
        cookieStore = InMemorySiteCookieStore(clock = { nowMs.get() })
        server = MockWebServer()
        server.start()
        settingsRepository = FakeSettingsRepository(
            AppSettings(forumServer = server.url("").toString().trimEnd('/')),
        )
        authRepository = FakeAuthRepository(
            initialAccount = SavedAccount(
                uid = "10000",
                username = "HiUser",
                passwordMd5 = "0123456789abcdef0123456789abcdef",
                lastLoginAt = 1_000L,
            ),
        )
        blacklistRepository = FakeBlacklistRepository()
        database = Room.inMemoryDatabaseBuilder(context, HiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        browseHistoryDao = database.browseHistoryDao()
        readingPositionDao = database.readingPositionDao()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        clientsToClose.forEach { client ->
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        clientsToClose.clear()
        server.close()
    }

    private fun createRepository(
        maxBrowseHistory: Int = DefaultThreadDetailRepository.DEFAULT_MAX_BROWSE_HISTORY,
        solver: ChallengeSolver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) },
    ): DefaultThreadDetailRepository {
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
        return DefaultThreadDetailRepository(
            forumHttpClient = forumHttpClient,
            authRepository = authRepository,
            settingsRepository = settingsRepository,
            blacklistRepository = blacklistRepository,
            browseHistoryDao = browseHistoryDao,
            readingPositionDao = readingPositionDao,
            clock = { nowMs.get() },
            maxBrowseHistory = maxBrowseHistory,
        )
    }

    @Test
    fun loadPage_realSamplesP1P2AndAuthorOnly_parseAllFieldsAndDeriveBaseUrls() = runBlocking {
        val sampleP1 = SampleTestLoader.loadSample("thread_detail/thread_p1.html")
        val sampleP2 = SampleTestLoader.loadSample("thread_detail/thread_p2.html")
        val sampleAuthorOnly = SampleTestLoader.loadSample("thread_detail/thread_author_only.html")
        server.enqueue(gbkHtmlResponse(sampleP1))
        server.enqueue(gbkHtmlResponse(sampleP2))
        server.enqueue(gbkHtmlResponse(sampleAuthorOnly))
        server.enqueue(gbkHtmlResponse(sampleAuthorOnly))

        val repo = createRepository()
        val serverBase = server.url("").toString().trimEnd('/')

        // 1. Page 1 real sample
        val p1Result = repo.loadPage(tid = "2602293", page = 1)
        assertTrue("Expected Success for p1, got $p1Result", p1Result is ThreadDetailLoadResult.Success)
        val p1 = p1Result as ThreadDetailLoadResult.Success
        assertEquals("2602293", p1.tid)
        assertEquals(2, p1.fid)
        assertEquals(1, p1.pageNumber)
        assertEquals(10, p1.lastPage)
        assertTrue(p1.hasNextPage)
        assertEquals(50, p1.page.floors.size)
        assertEquals(50, p1.page.postsPerPageEstimate)
        assertTrue(p1.title.contains("发帖原则"))
        assertEquals("Discovery", p1.forumName)
        assertEquals("26171", p1.threadAuthorId)
        assertEquals(ThreadDetailFilter.All, p1.filter)
        assertEquals(
            "$serverBase/forum/uc_server/data/avatar/000/02/61/71_avatar_middle.jpg",
            p1.page.floors.first().avatarUrl,
        )
        assertEquals("/forum/viewthread.php?tid=2602293&page=1", server.takeRequest().target)

        // 2. Page 2 real sample (double-escaped emoji decoded; threadAuthorId null on non-1st page)
        val p2Result = repo.loadPage(tid = "2602293", page = 2, recordHistory = false)
        assertTrue("Expected Success for p2, got $p2Result", p2Result is ThreadDetailLoadResult.Success)
        val p2 = p2Result as ThreadDetailLoadResult.Success
        assertEquals(2, p2.pageNumber)
        assertEquals(10, p2.lastPage)
        assertEquals(50, p2.page.floors.size)
        assertNull(p2.threadAuthorId)
        val floor51Text = p2.page.floors.first().blocks
            .filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
            .filterIsInstance<InlineSpan.Plain>()
            .joinToString("") { it.text }
        assertTrue("Expected decoded OK hand emoji, got $floor51Text", floor51Text.contains("好的\uD83D\uDC4C"))
        assertEquals("/forum/viewthread.php?tid=2602293&page=2", server.takeRequest().target)

        // 3. AuthorOnly with known authorId (single request directly with &authorid=)
        val authorOnlyResult = repo.loadPage(
            tid = "2602293",
            page = 1,
            filter = ThreadDetailFilter.AuthorOnly(authorId = "26171"),
            recordHistory = false,
        )
        assertTrue("Expected Success for authorOnly, got $authorOnlyResult", authorOnlyResult is ThreadDetailLoadResult.Success)
        val authorOnly = authorOnlyResult as ThreadDetailLoadResult.Success
        assertEquals(15, authorOnly.page.floors.size)
        assertEquals(1, authorOnly.lastPage)
        assertFalse(authorOnly.hasNextPage)
        assertEquals(ThreadDetailFilter.AuthorOnly(authorId = "26171"), authorOnly.filter)
        assertEquals("26171", authorOnly.threadAuthorId)
        assertEquals("/forum/viewthread.php?tid=2602293&page=1&authorid=26171", server.takeRequest().target)

        // 4. UserOnly with explicit uid
        val userOnlyResult = repo.loadPage(
            tid = "2602293",
            page = 1,
            filter = ThreadDetailFilter.UserOnly(uid = "26171"),
            recordHistory = false,
        )
        assertTrue("Expected Success for userOnly, got $userOnlyResult", userOnlyResult is ThreadDetailLoadResult.Success)
        val userOnly = userOnlyResult as ThreadDetailLoadResult.Success
        assertEquals(ThreadDetailFilter.UserOnly(uid = "26171"), userOnly.filter)
        assertEquals("/forum/viewthread.php?tid=2602293&page=1&authorid=26171", server.takeRequest().target)
    }

    @Test
    fun loadPage_authorOnlyWithoutAuthorId_fetchesPage1ToResolveOpUidThenFilteredPage() = runBlocking {
        val sampleP1 = SampleTestLoader.loadSample("thread_detail/thread_p1.html")
        val sampleAuthorOnly = SampleTestLoader.loadSample("thread_detail/thread_author_only.html")
        // 1) page > 1 with authorId = null -> fetches page 1 then page 2 with &authorid=26171
        server.enqueue(gbkHtmlResponse(sampleP1))
        server.enqueue(gbkHtmlResponse(sampleAuthorOnly))

        val repo = createRepository()
        val result = repo.loadPage(
            tid = "2602293",
            page = 2,
            filter = ThreadDetailFilter.AuthorOnly(authorId = null),
            recordHistory = true,
        )

        assertTrue("Expected Success, got $result", result is ThreadDetailLoadResult.Success)
        val success = result as ThreadDetailLoadResult.Success
        assertEquals(ThreadDetailFilter.AuthorOnly(authorId = "26171"), success.filter)
        assertEquals("26171", success.threadAuthorId)
        assertEquals(15, success.page.floors.size)

        val firstReq = server.takeRequest()
        val secondReq = server.takeRequest()
        assertEquals("/forum/viewthread.php?tid=2602293&page=1", firstReq.target)
        assertEquals("/forum/viewthread.php?tid=2602293&page=2&authorid=26171", secondReq.target)

        // Browse history written once with resolved OP info
        val history = browseHistoryDao.observeRecent(10).first()
        assertEquals(1, history.size)
        assertEquals("2602293", history.single().tid)

        // 2) page == 1 with authorId = null also fetches page 1 first then filtered page 1
        server.enqueue(gbkHtmlResponse(sampleP1))
        server.enqueue(gbkHtmlResponse(sampleAuthorOnly))
        val p1AuthorOnly = repo.loadPage(
            tid = "2602293",
            page = 1,
            filter = ThreadDetailFilter.AuthorOnly(authorId = null),
            recordHistory = false,
        )
        assertTrue(p1AuthorOnly is ThreadDetailLoadResult.Success)
        assertEquals("/forum/viewthread.php?tid=2602293&page=1", server.takeRequest().target)
        assertEquals("/forum/viewthread.php?tid=2602293&page=1&authorid=26171", server.takeRequest().target)

        // 3) If page 1 fails with SiteError, aborts without issuing second request
        server.enqueue(
            gbkHtmlResponse(
                loggedInPageHtml(
                    body = """<div id="wrap"><div class="alert_error">指定的主题不存在或已被删除</div></div>""",
                ),
            ),
        )
        val failed = repo.loadPage(
            tid = "999999",
            page = 1,
            filter = ThreadDetailFilter.AuthorOnly(authorId = null),
        )
        assertTrue("Expected SiteError, got $failed", failed is ThreadDetailError.SiteError)
        assertEquals(5, server.requestCount)
        assertEquals(1, browseHistoryDao.observeRecent(10).first().size)
    }

    @Test
    fun loadLastPage_and_loadByPostId_followRedirectsAndResolveTidAndTargetPid() = runBlocking {
        val sampleP2 = SampleTestLoader.loadSample("thread_detail/thread_p2.html")
        val repo = createRepository()

        // 1. loadLastPage -> 302 redirect to page 2
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=2602293&page=2#lastpost")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(sampleP2))

        val lastPageRes = repo.loadLastPage(tid = "2602293")
        assertTrue("Expected Success, got $lastPageRes", lastPageRes is ThreadDetailLoadResult.Success)
        val lastPage = lastPageRes as ThreadDetailLoadResult.Success
        assertEquals("2602293", lastPage.tid)
        assertEquals(2, lastPage.pageNumber)
        assertEquals(10, lastPage.lastPage)
        assertEquals(50, lastPage.page.floors.size)
        assertNull(lastPage.targetPostId)
        assertEquals("/forum/redirect.php?goto=lastpost&from=fastpost&tid=2602293", server.takeRequest().target)
        assertEquals("/forum/viewthread.php?tid=2602293&page=2", server.takeRequest().target)

        // 2. loadByPostId WITH tid -> redirect.php?goto=findpost&pid=...&ptid=...
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=2602293&page=2#pid53529502")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(sampleP2))

        val findPostRes = repo.loadByPostId(pid = "53529502", tid = "2602293")
        assertTrue("Expected Success, got $findPostRes", findPostRes is ThreadDetailLoadResult.Success)
        val findPost = findPostRes as ThreadDetailLoadResult.Success
        assertEquals("2602293", findPost.tid)
        assertEquals("53529502", findPost.targetPostId)
        assertEquals(2, findPost.pageNumber)
        assertNotNull(findPost.page.findFloorByPostId("53529502"))
        assertEquals(
            "/forum/redirect.php?goto=findpost&pid=53529502&ptid=2602293",
            server.takeRequest().target,
        )
        assertEquals("/forum/viewthread.php?tid=2602293&page=2", server.takeRequest().target)

        // 3. loadByPostId WITHOUT tid -> gotopost.php?pid=... -> resolves tid from redirect URL
        val syntheticNoDomTidHtml = syntheticThreadPageHtml(
            tidInDom = null,
            fid = 7,
            forumName = "Geek Talks",
            title = "定位帖子测试标题",
            page = 3,
            lastPage = 5,
            floors = listOf(
                SyntheticFloor(pid = "88901", floor = 101, uid = "3001", author = "FinderUser", text = "目标楼层内容"),
            ),
        )
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=77001&page=3#pid88901")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(syntheticNoDomTidHtml))

        val gotoPostRes = repo.loadByPostId(pid = "88901", tid = null)
        assertTrue("Expected Success, got $gotoPostRes", gotoPostRes is ThreadDetailLoadResult.Success)
        val gotoPost = gotoPostRes as ThreadDetailLoadResult.Success
        assertEquals("77001", gotoPost.tid)
        assertEquals("88901", gotoPost.targetPostId)
        assertEquals(3, gotoPost.pageNumber)
        assertEquals(5, gotoPost.lastPage)
        assertEquals("Geek Talks", gotoPost.forumName)
        assertEquals("/forum/gotopost.php?pid=88901", server.takeRequest().target)
        assertEquals("/forum/viewthread.php?tid=77001&page=3", server.takeRequest().target)

        val historyTids = browseHistoryDao.observeRecent(10).first().map { it.tid }
        assertTrue("Expected tid 77001 recorded in browse history, got $historyTids", "77001" in historyTids)

        // 4. loadByPostId WITHOUT tid and direct 200 (no redirect URL tid) -> resolves tid from DOM
        val directDomTidHtml = syntheticThreadPageHtml(
            tidInDom = "77002",
            fid = 2,
            forumName = "Discovery",
            title = "直出DOM定位帖",
            page = 1,
            lastPage = 1,
            floors = listOf(
                SyntheticFloor(pid = "88902", floor = 1, uid = "3002", author = "DirectUser", text = "直出内容"),
            ),
        )
        server.enqueue(gbkHtmlResponse(directDomTidHtml))
        val directGotoRes = repo.loadByPostId(pid = "88902", tid = null, recordHistory = false)
        assertTrue("Expected Success, got $directGotoRes", directGotoRes is ThreadDetailLoadResult.Success)
        assertEquals("77002", (directGotoRes as ThreadDetailLoadResult.Success).tid)
        assertEquals("88902", directGotoRes.targetPostId)
        assertEquals("/forum/gotopost.php?pid=88902", server.takeRequest().target)
    }

    @Test
    fun loadPage_authPoliciesForLoggedInAndGuestAndErrorMapping() = runBlocking {
        val sampleP1 = SampleTestLoader.loadSample("thread_detail/thread_p1.html")
        val repo = createRepository()

        // 1. Logged-in state: expired response triggers single silent relogin and succeeds on retry
        val reloginCalls = AtomicInteger(0)
        authRepository.onTrySilentRelogin = { _, _ ->
            reloginCalls.incrementAndGet()
            authRepository.bumpEpoch()
            SilentReloginResult.Recovered(authRepository.sessionEpoch)
        }
        server.enqueue(gbkHtmlResponse(loggedOutHtml("请先登录")))
        server.enqueue(gbkHtmlResponse(sampleP1))

        val recovered = repo.loadPage(tid = "2602293", page = 1)
        assertTrue("Expected Success after silent relogin, got $recovered", recovered is ThreadDetailLoadResult.Success)
        assertEquals(1, reloginCalls.get())
        assertEquals(2, server.requestCount)

        // 2. Logged-in state: relogin fails -> AuthRequired
        authRepository.onTrySilentRelogin = { _, _ ->
            reloginCalls.incrementAndGet()
            SilentReloginResult.Failed(SilentReloginFailureReason.INVALID_CREDENTIALS, "密码已更改")
        }
        server.enqueue(gbkHtmlResponse(loggedOutHtml("请先登录")))
        val authFailed = repo.loadPage(tid = "2602293", page = 1)
        assertTrue("Expected AuthRequired, got $authFailed", authFailed is ThreadDetailError.AuthRequired)
        assertEquals(AuthRequiredReason.RELOGIN_FAILED, (authFailed as ThreadDetailError.AuthRequired).reason)

        // 3. Guest / manually logged-out state: public thread loads with requireAuth = false (0 relogins)
        val reloginBeforeGuest = reloginCalls.get()
        authRepository.setCurrentAccount(null)
        val publicThreadHtml = syntheticThreadPageHtml(
            loggedIn = false,
            tidInDom = "60001",
            fid = 2,
            forumName = "Discovery",
            title = "公开可见帖子",
            page = 1,
            lastPage = 1,
            floors = listOf(SyntheticFloor(pid = "61001", floor = 1, uid = "4001", author = "PublicOP", text = "游客可读")),
        )
        server.enqueue(gbkHtmlResponse(publicThreadHtml))
        val guestPublic = repo.loadPage(tid = "60001", page = 1)
        assertTrue("Guest should load public thread, got $guestPublic", guestPublic is ThreadDetailLoadResult.Success)
        assertEquals("公开可见帖子", (guestPublic as ThreadDetailLoadResult.Success).title)
        assertEquals(reloginBeforeGuest, reloginCalls.get())

        // 4. Guest hitting login-required thread -> AuthRequired (0 relogins)
        server.enqueue(
            gbkHtmlResponse(
                """
                <html><body>
                  <div id="umenu"><a href="logging.php?action=login">登录</a></div>
                  <div id="wrap">
                    <div class="alert_error"><p>您无权进行当前操作，您所在的用户组(游客)无法进行此操作。</p></div>
                    <form name="login" method="post" action="logging.php?action=login"><input name="loginsubmit" value="true" /></form>
                  </div>
                </body></html>
                """.trimIndent(),
            ),
        )
        val guestRestricted = repo.loadPage(tid = "60002", page = 1)
        assertTrue("Expected AuthRequired for guest on restricted thread, got $guestRestricted", guestRestricted is ThreadDetailError.AuthRequired)
        assertEquals(reloginBeforeGuest, reloginCalls.get())

        // 5. Guest hitting deleted thread -> SiteError (not AuthRequired)
        server.enqueue(
            gbkHtmlResponse(
                """
                <html><body>
                  <div id="umenu"><a href="logging.php?action=login">登录</a></div>
                  <div id="wrap"><div class="alert_error">指定的主题不存在或已被删除</div></div>
                </body></html>
                """.trimIndent(),
            ),
        )
        val guestSiteError = repo.loadPage(tid = "60003", page = 1)
        assertTrue("Expected SiteError, got $guestSiteError", guestSiteError is ThreadDetailError.SiteError)
        assertEquals("指定的主题不存在或已被删除", (guestSiteError as ThreadDetailError.SiteError).message)

        // 6. HTTP 500 -> NetworkError; malformed HTML -> InvalidPage; bad inputs -> InvalidInput (0 requests)
        server.enqueue(MockResponse.Builder().code(500).body("Server Error").build())
        val httpErr = repo.loadPage(tid = "60004", page = 1)
        assertTrue(httpErr is ThreadDetailError.NetworkError)
        assertEquals(500, (httpErr as ThreadDetailError.NetworkError).statusCode)

        server.enqueue(gbkHtmlResponse("<html><body><div>broken markup</div></body></html>"))
        val invalidPage = repo.loadPage(tid = "60005", page = 1)
        assertTrue("Expected InvalidPage, got $invalidPage", invalidPage is ThreadDetailError.InvalidPage)

        val requestsBeforeInvalidInput = server.requestCount
        assertTrue(repo.loadPage(tid = "", page = 1) is ThreadDetailError.InvalidInput)
        assertTrue(repo.loadPage(tid = "abc", page = 1) is ThreadDetailError.InvalidInput)
        assertTrue(repo.loadPage(tid = "0", page = 1) is ThreadDetailError.InvalidInput)
        assertTrue(repo.loadPage(tid = "123", page = 0) is ThreadDetailError.InvalidInput)
        assertTrue(
            repo.loadPage(tid = "123", page = 1, filter = ThreadDetailFilter.UserOnly(uid = "bad")) is ThreadDetailError.InvalidInput,
        )
        assertTrue(
            repo.loadPage(tid = "123", page = 1, filter = ThreadDetailFilter.AuthorOnly(authorId = "0")) is ThreadDetailError.InvalidInput,
        )
        assertTrue(repo.loadLastPage(tid = "-1") is ThreadDetailError.InvalidInput)
        assertTrue(repo.loadByPostId(pid = "bad") is ThreadDetailError.InvalidInput)
        assertTrue(repo.loadByPostId(pid = "100", tid = "bad") is ThreadDetailError.InvalidInput)
        assertEquals(requestsBeforeInvalidInput, server.requestCount)
    }

    @Test
    fun browseHistory_autoUpsertsAndTrimsToLimitAndRespectsRecordHistoryFlag() = runBlocking {
        val repo = createRepository(maxBrowseHistory = 3)

        // Load 4 distinct threads sequentially; maxBrowseHistory = 3 should keep only the 3 newest
        for (i in 1..4) {
            nowMs.set(1_700_000_000_000L + i * 1_000L)
            server.enqueue(
                gbkHtmlResponse(
                    syntheticThreadPageHtml(
                        tidInDom = "700$i",
                        fid = 2,
                        forumName = "Discovery",
                        title = "测试帖子 $i",
                        page = 1,
                        lastPage = 1,
                        floors = listOf(
                            SyntheticFloor(pid = "710$i", floor = 1, uid = "500$i", author = "Author$i", text = "内容 $i"),
                        ),
                    ),
                ),
            )
            val res = repo.loadPage(
                tid = "700$i",
                page = 1,
                forumName = if (i == 2) "自定义版块名" else null,
            )
            assertTrue(res is ThreadDetailLoadResult.Success)
        }

        val afterTrim = browseHistoryDao.observeRecent(10).first()
        assertEquals(listOf("7004", "7003", "7002"), afterTrim.map { it.tid })
        assertEquals(
            BrowseHistoryEntity(
                tid = "7002",
                title = "测试帖子 2",
                fid = "2",
                forumName = "自定义版块名",
                author = "Author2",
                lastVisitedAt = 1_700_000_002_000L,
            ),
            afterTrim[2],
        )
        assertEquals("Discovery", afterTrim[0].forumName)
        assertEquals("Author4", afterTrim[0].author)

        // Loading with recordHistory = false must not upsert or evict
        nowMs.set(1_700_000_010_000L)
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "7005",
                    fid = 6,
                    forumName = "Buy & Sell",
                    title = "不记录历史",
                    page = 1,
                    lastPage = 1,
                    floors = listOf(SyntheticFloor(pid = "7105", floor = 1, uid = "5005", author = "Author5", text = "内容 5")),
                ),
            ),
        )
        val noHistoryRes = repo.loadPage(tid = "7005", page = 1, recordHistory = false)
        assertTrue(noHistoryRes is ThreadDetailLoadResult.Success)
        assertEquals(listOf("7004", "7003", "7002"), browseHistoryDao.observeRecent(10).first().map { it.tid })

        // Revisiting 7002 bumps it to top
        nowMs.set(1_700_000_020_000L)
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "7002",
                    fid = 2,
                    forumName = "Discovery",
                    title = "测试帖子 2（更新）",
                    page = 1,
                    lastPage = 1,
                    floors = listOf(SyntheticFloor(pid = "7102", floor = 1, uid = "5002", author = "Author2", text = "内容 2")),
                ),
            ),
        )
        assertTrue(repo.loadPage(tid = "7002", page = 1) is ThreadDetailLoadResult.Success)
        val afterRevisit = browseHistoryDao.observeRecent(10).first()
        assertEquals(listOf("7002", "7004", "7003"), afterRevisit.map { it.tid })
        assertEquals("测试帖子 2（更新）", afterRevisit.first().title)
        assertEquals(1_700_000_020_000L, afterRevisit.first().lastVisitedAt)
    }

    @Test
    fun readingPosition_roundTripsAllFilterModesInMemoryRoom() = runBlocking {
        val repo = createRepository()

        assertNull(repo.getReadingPosition("2602293"))
        assertNull(repo.getReadingPosition("invalid_tid"))

        // Save OP filter mode
        nowMs.set(1_700_000_100_000L)
        repo.saveReadingPosition(
            tid = "2602293",
            page = 2,
            pid = "53529502",
            offsetPx = 128,
            filterMode = ReadingFilterMode.OP,
            filterUid = "26171",
            filterUserName = "zhuyi",
        )
        assertEquals(
            ReadingPositionEntity(
                tid = "2602293",
                page = 2,
                pid = "53529502",
                offsetPx = 128,
                filterMode = ReadingFilterMode.OP,
                filterUid = "26171",
                filterUserName = "zhuyi",
                updatedAt = 1_700_000_100_000L,
            ),
            repo.getReadingPosition("2602293"),
        )

        // Overwrite with USER filter mode
        nowMs.set(1_700_000_200_000L)
        repo.saveReadingPosition(
            tid = "2602293",
            page = 3,
            pid = "53529999",
            offsetPx = 64,
            filterMode = ReadingFilterMode.USER,
            filterUid = "88888",
            filterUserName = "SomeAuthor",
        )
        assertEquals(
            ReadingPositionEntity(
                tid = "2602293",
                page = 3,
                pid = "53529999",
                offsetPx = 64,
                filterMode = ReadingFilterMode.USER,
                filterUid = "88888",
                filterUserName = "SomeAuthor",
                updatedAt = 1_700_000_200_000L,
            ),
            repo.getReadingPosition("2602293"),
        )

        // Overwrite with NONE filter mode clears filterUid/filterUserName and clamps page/offset
        nowMs.set(1_700_000_300_000L)
        repo.saveReadingPosition(
            tid = "2602293",
            page = 0,
            pid = "53528033",
            offsetPx = -20,
            filterMode = ReadingFilterMode.NONE,
            filterUid = "stale_uid",
            filterUserName = "stale_user",
        )
        assertEquals(
            ReadingPositionEntity(
                tid = "2602293",
                page = 1,
                pid = "53528033",
                offsetPx = 0,
                filterMode = ReadingFilterMode.NONE,
                filterUid = null,
                filterUserName = null,
                updatedAt = 1_700_000_300_000L,
            ),
            repo.getReadingPosition("2602293"),
        )
    }

    @Test
    fun observeFloors_reactsToBlacklistAddRemoveUndoAndAccountSwitchWithZeroNetworkRequests() = runBlocking {
        val repo = createRepository()
        val floor1 = sampleFloor(pid = "1001", floor = 1, uid = "101", author = "Alice")
        val floor2 = sampleFloor(pid = "1002", floor = 2, uid = "102", author = "TrollUser")
        val floor3 = sampleFloor(pid = "1003", floor = 3, uid = "103", author = "Bob")
        val floorsFlow = MutableStateFlow(listOf(floor1, floor2, floor3))

        // 1. Reactive marked view (observeFloors)
        repo.observeFloors(floorsFlow).test {
            val initial = awaitItem()
            assertEquals(listOf("1001", "1002", "1003"), initial.map { it.postId })
            assertEquals(listOf(false, false, false), initial.map { it.isAuthorBlocked })

            // Add TrollUser -> immediately masked
            blacklistRepository.setBlocked(setOf("TrollUser"))
            val afterAdd = awaitItem()
            assertEquals(listOf(false, true, false), afterAdd.map { it.isAuthorBlocked })

            // Add unrelated user -> distinctUntilChanged suppresses redundant emission, then add Bob
            blacklistRepository.setBlocked(setOf("TrollUser", "UnrelatedUser"))
            blacklistRepository.setBlocked(setOf("TrollUser", "UnrelatedUser", "Bob"))
            val afterAddBob = awaitItem()
            assertEquals(listOf(false, true, true), afterAddBob.map { it.isAuthorBlocked })

            // Undo / remove TrollUser -> immediately unmasked
            blacklistRepository.setBlocked(setOf("UnrelatedUser", "Bob"))
            val afterUndo = awaitItem()
            assertEquals(listOf(false, false, true), afterUndo.map { it.isAuthorBlocked })

            // Account switch / logout clears blacklist -> all unmasked
            blacklistRepository.setBlocked(emptySet())
            val afterLogout = awaitItem()
            assertEquals(listOf(false, false, false), afterLogout.map { it.isAuthorBlocked })

            cancelAndIgnoreRemainingEvents()
        }

        // 2. Reactive hidden view (observeVisibleFloors)
        blacklistRepository.setBlocked(setOf("TrollUser"))
        repo.observeVisibleFloors(floorsFlow).test {
            assertEquals(listOf("1001", "1003"), awaitItem().map { it.postId })

            blacklistRepository.setBlocked(emptySet())
            assertEquals(listOf("1001", "1002", "1003"), awaitItem().map { it.postId })

            cancelAndIgnoreRemainingEvents()
        }

        // 3. Synchronous helpers
        blacklistRepository.setBlocked(setOf("Bob"))
        val mapped = repo.mapFloorsWithBlacklist(listOf(floor1, floor2, floor3))
        assertEquals(listOf(false, false, true), mapped.map { it.isAuthorBlocked })
        assertEquals(listOf("1001", "1002"), repo.filterBlockedFloors(listOf(floor1, floor2, floor3)).map { it.postId })

        // Zero network requests throughout all blacklist operations
        assertEquals(0, server.requestCount)
    }

    @Test
    fun submitPollVote_singleAndMultiChoice_encodesSortedGbkFormAndRefreshesPage1() = runBlocking {
        val reloginCalls = AtomicInteger(0)
        authRepository.onTrySilentRelogin = { _, _ ->
            reloginCalls.incrementAndGet()
            SilentReloginResult.Suppressed
        }
        val repo = createRepository()

        // 1. Single-choice poll returning AJAX XML (no #umenu) -> does NOT trigger silent relogin, refreshes page 1
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[非常感谢，您的投票已经提交，现在将转入主题页面<script type="text/javascript">location.href='viewthread.php?tid=50001';</script>]]></root>""",
            ),
        )
        server.enqueue(
            gbkHtmlResponse(
                syntheticPollThreadHtml(
                    tid = "50001",
                    fid = 2,
                    page = 1,
                    title = "系统调查",
                    maxChoices = 1,
                    options = listOf(
                        SyntheticPollOpt(id = null, text = "Android", rates = "80.00% (8)"),
                        SyntheticPollOpt(id = null, text = "iOS", rates = "20.00% (2)"),
                    ),
                    footer = "您已经投过票，谢谢您的参与",
                ),
            ),
        )

        val singleRes = repo.submitPollVote(
            fid = 2,
            tid = "50001",
            formhash = "11223344",
            optionIds = listOf("501"),
            maxChoices = 1,
        )
        assertTrue("Expected PollVoteResult.Success, got $singleRes", singleRes is PollVoteResult.Success)
        val singleSuccess = singleRes as PollVoteResult.Success
        assertEquals("50001", singleSuccess.refreshedPage.tid)
        assertEquals(1, singleSuccess.refreshedPage.page)
        assertNotNull(singleSuccess.poll)
        val singlePoll = singleSuccess.poll!!
        assertEquals("80.00% (8)", singlePoll.options[0].ratesText)
        assertEquals("20.00% (2)", singlePoll.options[1].ratesText)
        assertEquals(0, reloginCalls.get())

        val singlePost = server.takeRequest()
        assertEquals("POST", singlePost.method)
        assertEquals(
            "/forum/misc.php?action=votepoll&fid=2&tid=50001&pollsubmit=yes&quickforward=yes&inajax=1",
            singlePost.target,
        )
        assertTrue(singlePost.headers["Content-Type"].orEmpty().contains("charset=GBK", ignoreCase = true))
        assertEquals(
            listOf("formhash" to "11223344", "pollanswers[]" to "501"),
            decodeOrderedGbkForm(singlePost.body?.utf8().orEmpty()),
        )
        val refreshGet = server.takeRequest()
        assertEquals("GET", refreshGet.method)
        assertEquals("/forum/viewthread.php?tid=50001&page=1", refreshGet.target)

        // 2. Multi-choice poll with unsorted + duplicate options -> deduplicated, lexicographically sorted pollanswers[]
        //    Tests 302 redirect directly to viewthread.php?tid=50002 (page 1)
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=50002")
                .build(),
        )
        server.enqueue(
            gbkHtmlResponse(
                syntheticPollThreadHtml(
                    tid = "50002",
                    fid = 7,
                    page = 1,
                    title = "多选调查",
                    maxChoices = 3,
                    options = listOf(
                        SyntheticPollOpt(id = null, text = "选项一", rates = "50.00% (5)"),
                        SyntheticPollOpt(id = null, text = "选项二", rates = "30.00% (3)"),
                        SyntheticPollOpt(id = null, text = "选项三", rates = "20.00% (2)"),
                    ),
                    footer = "您已经投过票",
                ),
            ),
        )

        val multiRes = repo.submitPollVote(
            fid = 7,
            tid = "50002",
            formhash = "deadbeef",
            optionIds = listOf("603", "601", "602", "601"),
            maxChoices = 3,
        )
        assertTrue("Expected Success on multi-choice 302, got $multiRes", multiRes is PollVoteResult.Success)
        val multiSuccess = multiRes as PollVoteResult.Success
        assertEquals("50002", multiSuccess.refreshedPage.tid)
        assertEquals(3, multiSuccess.poll?.options?.size)
        assertEquals("50.00% (5)", multiSuccess.poll?.options?.get(0)?.ratesText)
        assertEquals(0, reloginCalls.get())

        val multiPost = server.takeRequest()
        assertEquals("POST", multiPost.method)
        assertEquals(
            "/forum/misc.php?action=votepoll&fid=7&tid=50002&pollsubmit=yes&quickforward=yes&inajax=1",
            multiPost.target,
        )
        assertEquals(
            listOf(
                "formhash" to "deadbeef",
                "pollanswers[]" to "601",
                "pollanswers[]" to "602",
                "pollanswers[]" to "603",
            ),
            decodeOrderedGbkForm(multiPost.body?.utf8().orEmpty()),
        )
        val redirectedGet = server.takeRequest()
        assertEquals("GET", redirectedGet.method)
        assertEquals("/forum/viewthread.php?tid=50002", redirectedGet.target)
        // Did not issue a redundant second GET because 302 already landed on logged-in page 1
        assertEquals(4, server.requestCount)

        // 3. 302 redirect landing on page 2 -> automatically fetches page 1 so poll is refreshed
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=50003&page=2")
                .build(),
        )
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "50003",
                    fid = 2,
                    forumName = "Discovery",
                    title = "多页投票帖",
                    page = 2,
                    lastPage = 2,
                    floors = listOf(SyntheticFloor(pid = "51051", floor = 51, uid = "999", author = "R", text = "第二页")),
                ),
            ),
        )
        server.enqueue(
            gbkHtmlResponse(
                syntheticPollThreadHtml(
                    tid = "50003",
                    fid = 2,
                    page = 1,
                    title = "多页投票帖",
                    maxChoices = 1,
                    options = listOf(SyntheticPollOpt(id = null, text = "同意", rates = "100.00% (1)")),
                    footer = "您已经投过票",
                ),
            ),
        )
        val redirectPage2Res = repo.submitPollVote(
            fid = 2,
            tid = "50003",
            formhash = "abcd1234",
            optionIds = listOf("701"),
            maxChoices = 1,
        )
        assertTrue("Expected Success after page-1 refresh, got $redirectPage2Res", redirectPage2Res is PollVoteResult.Success)
        assertEquals(1, (redirectPage2Res as PollVoteResult.Success).refreshedPage.page)
        assertEquals("100.00% (1)", redirectPage2Res.poll?.options?.single()?.ratesText)
        server.takeRequest() // POST
        server.takeRequest() // 302 GET page 2
        assertEquals("/forum/viewthread.php?tid=50003&page=1", server.takeRequest().target)
    }

    @Test
    fun submitPollVote_preflightValidationAndAuthGate_rejectWithZeroRequests() = runBlocking {
        val repo = createRepository()

        // Invalid inputs -> 0 requests
        assertTrue(repo.submitPollVote(0, "50001", "fh", listOf("1")) is ThreadDetailError.InvalidInput)
        assertTrue(repo.submitPollVote(-2, "50001", "fh", listOf("1")) is ThreadDetailError.InvalidInput)
        assertTrue(repo.submitPollVote(2, "", "fh", listOf("1")) is ThreadDetailError.InvalidInput)
        assertTrue(repo.submitPollVote(2, "abc", "fh", listOf("1")) is ThreadDetailError.InvalidInput)
        assertTrue(repo.submitPollVote(2, "50001", "   ", listOf("1")) is ThreadDetailError.InvalidInput)
        assertTrue(repo.submitPollVote(2, "50001", "fh", emptyList()) is ThreadDetailError.InvalidInput)
        assertTrue(repo.submitPollVote(2, "50001", "fh", listOf("  ")) is ThreadDetailError.InvalidInput)
        assertTrue(repo.submitPollVote(2, "50001", "fh", listOf("1", " ")) is ThreadDetailError.InvalidInput)
        assertTrue(repo.submitPollVote(2, "50001", "fh", listOf("1"), maxChoices = 0) is ThreadDetailError.InvalidInput)
        // Exceeding maxChoices -> 0 requests
        assertTrue(
            repo.submitPollVote(2, "50001", "fh", listOf("1", "2", "3"), maxChoices = 2) is ThreadDetailError.InvalidInput,
        )

        // Signed out / manually logged out -> AuthRequired with 0 requests
        authRepository.setCurrentAccount(null)
        val unauth = repo.submitPollVote(2, "50001", "fh", listOf("1"), maxChoices = 1)
        assertTrue("Expected AuthRequired when signed out, got $unauth", unauth is ThreadDetailError.AuthRequired)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun submitPollVote_serverErrorResponses_returnErrorsWithoutRefreshingPage1() = runBlocking {
        val repo = createRepository()

        // 1. AJAX XML already-voted error -> SiteError, no page 1 refresh
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[对不起，您已经投过票，不能重复投票]]></root>""",
            ),
        )
        val xmlErr = repo.submitPollVote(2, "50001", "fh1", listOf("501"), maxChoices = 1)
        assertTrue("Expected SiteError, got $xmlErr", xmlErr is ThreadDetailError.SiteError)
        assertTrue((xmlErr as ThreadDetailError.SiteError).message.contains("已经投过票"))
        assertEquals(1, server.requestCount)

        // 2. HTML div.alert_error -> SiteError, no page 1 refresh
        server.enqueue(
            gbkHtmlResponse(
                loggedInPageHtml("""<div id="wrap"><div class="alert_error">对不起，此投票已经关闭</div></div>"""),
            ),
        )
        val alertErr = repo.submitPollVote(2, "50001", "fh2", listOf("501"), maxChoices = 1)
        assertTrue("Expected SiteError, got $alertErr", alertErr is ThreadDetailError.SiteError)
        assertEquals("对不起，此投票已经关闭", (alertErr as ThreadDetailError.SiteError).message)
        assertEquals(2, server.requestCount)

        // 3. HTML div.alert_info error -> SiteError, no page 1 refresh
        server.enqueue(
            gbkHtmlResponse(
                loggedInPageHtml("""<div id="wrap"><div class="alert_info"><p>对不起，您最多只能选择 2 项</p></div></div>"""),
            ),
        )
        val alertInfoErr = repo.submitPollVote(2, "50001", "fh3", listOf("501"), maxChoices = 2)
        assertTrue("Expected SiteError, got $alertInfoErr", alertInfoErr is ThreadDetailError.SiteError)
        assertTrue((alertInfoErr as ThreadDetailError.SiteError).message.contains("最多只能选择 2 项"))
        assertEquals(3, server.requestCount)

        // 4. AJAX XML unauthenticated error -> AuthRequired, no page 1 refresh
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[您尚未登录，无权进行此操作]]></root>""",
            ),
        )
        val xmlUnauth = repo.submitPollVote(2, "50001", "fh4", listOf("501"), maxChoices = 1)
        assertTrue("Expected AuthRequired, got $xmlUnauth", xmlUnauth is ThreadDetailError.AuthRequired)
        assertEquals(4, server.requestCount)

        // 5. Logged-out thread HTML (no #umenu) returned on votepoll -> AuthRequired, not false success
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    loggedIn = false,
                    tidInDom = "50001",
                    fid = 2,
                    forumName = "Discovery",
                    title = "未登录落地页",
                    page = 1,
                    lastPage = 1,
                    floors = listOf(SyntheticFloor(pid = "51001", floor = 1, uid = "1001", author = "OP", text = "内容")),
                ),
            ),
        )
        val loggedOutPostList = repo.submitPollVote(2, "50001", "fh5", listOf("501"), maxChoices = 1)
        assertTrue("Expected AuthRequired on logged-out postlist, got $loggedOutPostList", loggedOutPostList is ThreadDetailError.AuthRequired)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun loadPage_forumServerWithForumSuffixAndRelativeAttachmentUrl_doesNotDuplicateForumSegment() = runBlocking {
        val baseWithForum = server.url("").toString().trimEnd('/') + "/forum"
        settingsRepository.setForumServer(baseWithForum)
        val html = syntheticThreadPageHtml(
            tidInDom = "80001",
            fid = 2,
            forumName = "Discovery",
            title = "相对链接解析测试",
            page = 1,
            lastPage = 1,
            floors = listOf(
                SyntheticFloor(
                    pid = "81001",
                    floor = 1,
                    uid = "26171",
                    author = "OpUser",
                    text = """<a href="viewthread.php?tid=80002">站内帖</a><img id="aimg_9001" src="images/common/none.gif" file="attachments/day_261125/demo.jpg" />""",
                ),
            ),
        )
        server.enqueue(gbkHtmlResponse(html))

        val repo = createRepository()
        val result = repo.loadPage(tid = "80001", page = 1, recordHistory = false)
        assertTrue("Expected Success, got $result", result is ThreadDetailLoadResult.Success)
        val success = result as ThreadDetailLoadResult.Success
        val floor = success.page.floors.single()
        assertEquals(
            "$baseWithForum/uc_server/data/avatar/000/02/61/71_avatar_middle.jpg",
            floor.avatarUrl,
        )
        assertEquals(
            "$baseWithForum/attachments/day_261125/demo.jpg",
            floor.images.single().fullUrl,
        )
        val link = floor.blocks
            .filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
            .filterIsInstance<InlineSpan.Link>()
            .single()
        assertEquals("$baseWithForum/viewthread.php?tid=80002", link.url)
        assertEquals("/forum/viewthread.php?tid=80001&page=1", server.takeRequest().target)
    }

    @Test
    fun loadPage_challengeRequiredAndCancellationPropagation() = runBlocking {
        val repo = createRepository(
            solver = ChallengeSolver { SolveResult.Cancelled },
        )

        // Cloudflare 403 challenge -> ChallengeRequired
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .body("<html><title>Just a moment...</title></html>")
                .build(),
        )
        val challengeRes = repo.loadPage(tid = "90001", page = 1)
        assertTrue("Expected ChallengeRequired, got $challengeRes", challengeRes is ThreadDetailError.ChallengeRequired)
        assertFalse((challengeRes as ThreadDetailError.ChallengeRequired).rejected)
        assertFalse(challengeRes.abandoned)

        // BackgroundSilent priority abandons challenge without invoking solver
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .body("<html><title>Just a moment...</title></html>")
                .build(),
        )
        val abandonedRes = repo.loadPage(
            tid = "90002",
            page = 1,
            priority = AccessPriority.BackgroundSilent,
        )
        assertTrue("Expected ChallengeRequired(abandoned=true), got $abandonedRes", abandonedRes is ThreadDetailError.ChallengeRequired)
        assertTrue((abandonedRes as ThreadDetailError.ChallengeRequired).abandoned)

        // CancellationException must propagate and never be swallowed into NetworkError
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .headersDelay(5, TimeUnit.SECONDS)
                .body("delayed")
                .build(),
        )
        val deferred = async(Dispatchers.IO) {
            repo.loadPage(tid = "90003", page = 1)
        }
        delay(50)
        deferred.cancel()
        val caughtCancellation = try {
            deferred.await()
            false
        } catch (_: CancellationException) {
            true
        }
        assertTrue("CancellationException must not be swallowed", caughtCancellation)
    }

    @Test
    fun submitPollVote_quickForwardScriptXmlAndRefreshFailurePropagation() = runBlocking {
        val repo = createRepository()

        // 1. AJAX XML with JS quickforward redirect only -> succeeds and refreshes page 1
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[<script type="text/javascript" reload="1">window.location.href='viewthread.php?tid=50004';</script>]]></root>""",
            ),
        )
        server.enqueue(
            gbkHtmlResponse(
                syntheticPollThreadHtml(
                    tid = "50004",
                    fid = 2,
                    page = 1,
                    title = "JS跳转投票",
                    maxChoices = 1,
                    options = listOf(SyntheticPollOpt(id = null, text = "支持", rates = "100.00% (1)")),
                    footer = "您已经投过票",
                ),
            ),
        )
        val jsForwardRes = repo.submitPollVote(2, "50004", "fh_js", listOf("901"), maxChoices = 1)
        assertTrue("Expected Success, got $jsForwardRes", jsForwardRes is PollVoteResult.Success)
        assertEquals("100.00% (1)", (jsForwardRes as PollVoteResult.Success).poll?.options?.single()?.ratesText)

        // 2. Vote succeeds via XML, but page 1 refresh hits HTTP 503 -> returns NetworkError
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[非常感谢，您的投票已经提交]]></root>""",
            ),
        )
        server.enqueue(MockResponse.Builder().code(503).body("Unavailable").build())
        val refreshErr = repo.submitPollVote(2, "50005", "fh_err", listOf("902"), maxChoices = 1)
        assertTrue("Expected NetworkError from refresh, got $refreshErr", refreshErr is ThreadDetailError.NetworkError)
        assertEquals(503, (refreshErr as ThreadDetailError.NetworkError).statusCode)

        // 3. Error XML containing delayed redirect script must STILL be classified as SiteError, not success
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[对不起，您已经投过票<script type="text/javascript">setTimeout("location.href='viewthread.php?tid=50006'", 3000);</script>]]></root>""",
            ),
        )
        val countBefore = server.requestCount
        val errWithScript = repo.submitPollVote(2, "50006", "fh_dup", listOf("903"), maxChoices = 1)
        assertTrue("Expected SiteError, got $errWithScript", errWithScript is ThreadDetailError.SiteError)
        assertEquals(countBefore + 1, server.requestCount)
    }

    @Test
    fun browseHistory_nonFirstPageNeverOverwritesOpAuthor_coversPage3ThenPage1AndLastPageOnlyAndUserOnly() = runBlocking {
        val repo = createRepository()

        // 1. Sequence A: visit Page 3 first (no floor 1) -> must NOT record page 3 replier as OP
        nowMs.set(1_700_100_001_000L)
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "71001",
                    fid = 2,
                    forumName = "Discovery",
                    title = "顺序测试长帖",
                    page = 3,
                    lastPage = 5,
                    floors = listOf(
                        SyntheticFloor(pid = "71101", floor = 101, uid = "9001", author = "ReplierP3", text = "三楼页首回帖"),
                    ),
                ),
            ),
        )
        assertTrue(repo.loadPage(tid = "71001", page = 3) is ThreadDetailLoadResult.Success)
        val afterPage3 = browseHistoryDao.observeRecent(10).first().single { it.tid == "71001" }
        assertEquals("", afterPage3.author)

        // Then visit Page 1 -> populates real OP author from floor 1
        nowMs.set(1_700_100_002_000L)
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "71001",
                    fid = 2,
                    forumName = "Discovery",
                    title = "顺序测试长帖",
                    page = 1,
                    lastPage = 5,
                    floors = listOf(
                        SyntheticFloor(pid = "71001", floor = 1, uid = "8001", author = "RealOP", text = "主楼内容"),
                        SyntheticFloor(pid = "71002", floor = 2, uid = "9002", author = "ReplierF2", text = "二楼回复"),
                    ),
                ),
            ),
        )
        assertTrue(repo.loadPage(tid = "71001", page = 1) is ThreadDetailLoadResult.Success)
        val afterPage1 = browseHistoryDao.observeRecent(10).first().single { it.tid == "71001" }
        assertEquals("RealOP", afterPage1.author)

        // Then revisit Page 2, UserOnly(replier), and loadByPostId(non-1st-page) -> must PRESERVE "RealOP"
        nowMs.set(1_700_100_003_000L)
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "71001",
                    fid = 2,
                    forumName = "Discovery",
                    title = "顺序测试长帖",
                    page = 2,
                    lastPage = 5,
                    floors = listOf(
                        SyntheticFloor(pid = "71051", floor = 51, uid = "9003", author = "ReplierP2", text = "二页首楼"),
                    ),
                ),
            ),
        )
        assertTrue(repo.loadPage(tid = "71001", page = 2) is ThreadDetailLoadResult.Success)
        val afterPage2 = browseHistoryDao.observeRecent(10).first().single { it.tid == "71001" }
        assertEquals("RealOP", afterPage2.author)
        assertEquals(1_700_100_003_000L, afterPage2.lastVisitedAt)

        nowMs.set(1_700_100_004_000L)
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "71001",
                    fid = 2,
                    forumName = "Discovery",
                    title = "顺序测试长帖",
                    page = 1,
                    lastPage = 1,
                    floors = listOf(
                        SyntheticFloor(pid = "71052", floor = 52, uid = "9003", author = "ReplierP2", text = "只看某人回帖"),
                    ),
                ),
            ),
        )
        assertTrue(
            repo.loadPage(tid = "71001", page = 1, filter = ThreadDetailFilter.UserOnly(uid = "9003")) is ThreadDetailLoadResult.Success,
        )
        assertEquals("RealOP", browseHistoryDao.observeRecent(10).first().single { it.tid == "71001" }.author)

        nowMs.set(1_700_100_005_000L)
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=71001&page=2#pid71052")
                .build(),
        )
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "71001",
                    fid = 2,
                    forumName = "Discovery",
                    title = "顺序测试长帖",
                    page = 2,
                    lastPage = 5,
                    floors = listOf(
                        SyntheticFloor(pid = "71052", floor = 52, uid = "9003", author = "ReplierP2", text = "定位楼层"),
                    ),
                ),
            ),
        )
        assertTrue(repo.loadByPostId(pid = "71052", tid = "71001") is ThreadDetailLoadResult.Success)
        assertEquals("RealOP", browseHistoryDao.observeRecent(10).first().single { it.tid == "71001" }.author)

        // 2. Sequence B: visit Last Page ONLY (lands on page 10 without floor 1) -> author must stay empty, not last replier
        nowMs.set(1_700_100_006_000L)
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=71002&page=10#lastpost")
                .build(),
        )
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "71002",
                    fid = 6,
                    forumName = "Buy & Sell",
                    title = "仅进末页测试帖",
                    page = 10,
                    lastPage = 10,
                    floors = listOf(
                        SyntheticFloor(pid = "72451", floor = 451, uid = "9099", author = "LastPageReplier", text = "末页回帖"),
                    ),
                ),
            ),
        )
        assertTrue(repo.loadLastPage(tid = "71002") is ThreadDetailLoadResult.Success)
        val lastPageOnly = browseHistoryDao.observeRecent(10).first().single { it.tid == "71002" }
        assertEquals("", lastPageOnly.author)
        assertEquals("Buy & Sell", lastPageOnly.forumName)

        // 3. AuthorOnly(null) directly jumping to page 2 (no floor 1 on page 2) uses fallbackOpAuthor from page 1
        nowMs.set(1_700_100_007_000L)
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "71003",
                    fid = 2,
                    forumName = "Discovery",
                    title = "只看楼主二页帖",
                    page = 1,
                    lastPage = 2,
                    floors = listOf(
                        SyntheticFloor(pid = "73001", floor = 1, uid = "8003", author = "AuthorOnlyOP", text = "一楼"),
                    ),
                ),
            ),
        )
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "71003",
                    fid = 2,
                    forumName = "Discovery",
                    title = "只看楼主二页帖",
                    page = 2,
                    lastPage = 2,
                    floors = listOf(
                        SyntheticFloor(pid = "73060", floor = 60, uid = "8003", author = "AuthorOnlyOP", text = "楼主后续回复"),
                    ),
                ),
            ),
        )
        assertTrue(
            repo.loadPage(tid = "71003", page = 2, filter = ThreadDetailFilter.AuthorOnly(authorId = null)) is ThreadDetailLoadResult.Success,
        )
        assertEquals("AuthorOnlyOP", browseHistoryDao.observeRecent(10).first().single { it.tid == "71003" }.author)

        // 4. D5-fixcheck micro-note: AuthorOnly(null) page 2 with blank-author floor locks fallbackOpAuthor branch directly
        nowMs.set(1_700_100_008_000L)
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "71004",
                    fid = 2,
                    forumName = "Discovery",
                    title = "只看楼主二页空作者帖",
                    page = 1,
                    lastPage = 2,
                    floors = listOf(
                        SyntheticFloor(pid = "74001", floor = 1, uid = "8004", author = "FallbackOpBlankFloor", text = "一楼"),
                    ),
                ),
            ),
        )
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "71004",
                    fid = 2,
                    forumName = "Discovery",
                    title = "只看楼主二页空作者帖",
                    page = 2,
                    lastPage = 2,
                    floors = listOf(
                        SyntheticFloor(pid = "74060", floor = 60, uid = "8004", author = "   ", text = "二页缺失作者名的楼层"),
                    ),
                ),
            ),
        )
        val blankAuthorP2Res = repo.loadPage(
            tid = "71004",
            page = 2,
            filter = ThreadDetailFilter.AuthorOnly(authorId = null),
        )
        assertTrue("Expected Success for blank-author AuthorOnly page 2, got $blankAuthorP2Res", blankAuthorP2Res is ThreadDetailLoadResult.Success)
        assertEquals(
            "AuthorOnly page 2 with blank floor author must still record page-1 fallbackOpAuthor",
            "FallbackOpBlankFloor",
            browseHistoryDao.observeRecent(10).first().single { it.tid == "71004" }.author,
        )

        // 5. D5-fixcheck micro-note: AuthorOnly(null) page 2 with empty floors list also locks fallbackOpAuthor branch
        nowMs.set(1_700_100_009_000L)
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "71005",
                    fid = 2,
                    forumName = "Discovery",
                    title = "只看楼主二页空列表帖",
                    page = 1,
                    lastPage = 2,
                    floors = listOf(
                        SyntheticFloor(pid = "75001", floor = 1, uid = "8005", author = "FallbackOpEmptyList", text = "一楼"),
                    ),
                ),
            ),
        )
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "71005",
                    fid = 2,
                    forumName = "Discovery",
                    title = "只看楼主二页空列表帖",
                    page = 2,
                    lastPage = 2,
                    floors = emptyList(),
                ),
            ),
        )
        val emptyFloorsP2Res = repo.loadPage(
            tid = "71005",
            page = 2,
            filter = ThreadDetailFilter.AuthorOnly(authorId = null),
        )
        assertTrue("Expected Success for empty-floor AuthorOnly page 2, got $emptyFloorsP2Res", emptyFloorsP2Res is ThreadDetailLoadResult.Success)
        assertTrue((emptyFloorsP2Res as ThreadDetailLoadResult.Success).page.floors.isEmpty())
        assertEquals(
            "AuthorOnly page 2 with empty floor list must still record page-1 fallbackOpAuthor",
            "FallbackOpEmptyList",
            browseHistoryDao.observeRecent(10).first().single { it.tid == "71005" }.author,
        )
    }

    @Test
    fun submitPollVote_customErrorWithPoliteOrRedirectText_classifiedAsSiteErrorWithoutRefresh() = runBlocking {
        val repo = createRepository()

        // 1. HTML alert_info containing "请稍候/正在跳转" while actually reporting an error -> SiteError, 0 refresh
        server.enqueue(
            gbkHtmlResponse(
                loggedInPageHtml(
                    """
                    <div id="wrap">
                      <div class="alert_info">
                        <p>操作失败，投票参数异常，请稍候，正在跳转至主题页面</p>
                        <p><a href="viewthread.php?tid=50010">如果您的浏览器没有自动跳转，请点击这里</a></p>
                      </div>
                    </div>
                    """.trimIndent(),
                ),
            ),
        )
        val htmlErr = repo.submitPollVote(2, "50010", "fh_s2_1", listOf("101"), maxChoices = 1)
        assertTrue("Expected SiteError for custom redirect error page, got $htmlErr", htmlErr is ThreadDetailError.SiteError)
        assertTrue((htmlErr as ThreadDetailError.SiteError).message.contains("正在跳转"))
        assertEquals(1, server.requestCount)

        // 2. AJAX XML with polite/redirect phrase only (no explicit vote-submitted confirmation) -> SiteError, 0 refresh
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[非常感谢您的参与，请稍候，正在跳转，现在将转入主题页面]]></root>""",
            ),
        )
        val politeXmlErr = repo.submitPollVote(2, "50010", "fh_s2_2", listOf("101"), maxChoices = 1)
        assertTrue("Expected SiteError for polite/redirect-only XML, got $politeXmlErr", politeXmlErr is ThreadDetailError.SiteError)
        assertEquals(2, server.requestCount)

        // 3. AJAX XML with error marker + redirect text -> SiteError, 0 refresh
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[对不起，投票已经提交失败，请稍候，正在跳转]]></root>""",
            ),
        )
        val negatedXmlErr = repo.submitPollVote(2, "50010", "fh_s2_3", listOf("101"), maxChoices = 1)
        assertTrue("Expected SiteError when failure phrase negates submission, got $negatedXmlErr", negatedXmlErr is ThreadDetailError.SiteError)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun loadByPostId_withoutTidAndRedirectUrlTid_fallsBackToHeadScriptParseIntTid() = runBlocking {
        val repo = createRepository()
        // Direct 200 response from gotopost.php: no tid in URL, no input[name=tid], no viewthread.php link in #wrap,
        // only <head><script> tid = parseInt('77003'); body also contains a fake parseInt to verify head scoping.
        val html = syntheticThreadPageHtml(
            tidInDom = null,
            fid = 2,
            forumName = "Discovery",
            title = "Head脚本提取TID测试",
            page = 1,
            lastPage = 1,
            headScript = "var STYLEID = '1', fid = parseInt('2'), tid = parseInt('77003');",
            floors = listOf(
                SyntheticFloor(
                    pid = "88903",
                    floor = 1,
                    uid = "3003",
                    author = "ScriptOP",
                    text = "正文伪造 tid = parseInt('99999') 不应干扰 head 提取",
                ),
            ),
        )
        server.enqueue(gbkHtmlResponse(html))

        val res = repo.loadByPostId(pid = "88903", tid = null)
        assertTrue("Expected Success, got $res", res is ThreadDetailLoadResult.Success)
        val success = res as ThreadDetailLoadResult.Success
        assertEquals("77003", success.tid)
        assertEquals("88903", success.targetPostId)
        assertEquals("/forum/gotopost.php?pid=88903", server.takeRequest().target)

        val history = browseHistoryDao.observeRecent(10).first()
        assertEquals(listOf("77003"), history.map { it.tid })
        assertEquals("ScriptOP", history.single().author)
    }

    @Test
    fun loadLastPage_loadByPostId_andAuthorOnlyPage1Discovery_triggerSingleSilentReloginOnExpiredSession() = runBlocking {
        val reloginCalls = AtomicInteger(0)
        authRepository.onTrySilentRelogin = { _, _ ->
            reloginCalls.incrementAndGet()
            authRepository.bumpEpoch()
            SilentReloginResult.Recovered(authRepository.sessionEpoch)
        }
        val repo = createRepository()
        val page1Html = syntheticThreadPageHtml(
            tidInDom = "2602293",
            fid = 2,
            forumName = "Discovery",
            title = "过期重登测试帖",
            page = 1,
            lastPage = 2,
            floors = listOf(SyntheticFloor(pid = "53528033", floor = 1, uid = "26171", author = "zhuyi", text = "一楼")),
        )
        val page2Html = syntheticThreadPageHtml(
            tidInDom = "2602293",
            fid = 2,
            forumName = "Discovery",
            title = "过期重登测试帖",
            page = 2,
            lastPage = 2,
            floors = listOf(SyntheticFloor(pid = "53529502", floor = 51, uid = "26171", author = "zhuyi", text = "二页")),
        )

        // 1. loadLastPage with expired session -> silent relogin + retry succeeds
        server.enqueue(gbkHtmlResponse(loggedOutHtml("请先登录")))
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=2602293&page=2")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(page2Html))
        val lastPageRes = repo.loadLastPage(tid = "2602293", recordHistory = false)
        assertTrue("Expected loadLastPage Success after relogin, got $lastPageRes", lastPageRes is ThreadDetailLoadResult.Success)
        assertEquals(1, reloginCalls.get())

        // 2. loadByPostId with expired session -> silent relogin + retry succeeds
        server.enqueue(gbkHtmlResponse(loggedOutHtml("请先登录")))
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/forum/viewthread.php?tid=2602293&page=2#pid53529502")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(page2Html))
        val byPostRes = repo.loadByPostId(pid = "53529502", tid = "2602293", recordHistory = false)
        assertTrue("Expected loadByPostId Success after relogin, got $byPostRes", byPostRes is ThreadDetailLoadResult.Success)
        assertEquals(2, reloginCalls.get())

        // 3. AuthorOnly(authorId = null) page-1 discovery with expired session -> silent relogin + retry succeeds
        server.enqueue(gbkHtmlResponse(loggedOutHtml("请先登录")))
        server.enqueue(gbkHtmlResponse(page1Html))
        server.enqueue(gbkHtmlResponse(page2Html))
        val authorOnlyRes = repo.loadPage(
            tid = "2602293",
            page = 2,
            filter = ThreadDetailFilter.AuthorOnly(authorId = null),
            recordHistory = false,
        )
        assertTrue("Expected AuthorOnly Success after page-1 discovery relogin, got $authorOnlyRes", authorOnlyRes is ThreadDetailLoadResult.Success)
        assertEquals(ThreadDetailFilter.AuthorOnly(authorId = "26171"), (authorOnlyRes as ThreadDetailLoadResult.Success).filter)
        assertEquals(3, reloginCalls.get())
    }

    @Test
    fun submitPollVote_successRefreshDoesNotWriteBrowseHistory_andMultiChoiceSortsLexicographically() = runBlocking {
        // Also exercises the 6-arg @Inject constructor directly to lock DEFAULT_MAX_BROWSE_HISTORY
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
            solver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) },
            clock = { nowMs.get() },
            gatewayScope = scope,
            solveTimeout = 5.seconds,
        )
        val forumHttpClient = ForumHttpClient(
            siteAccessGateway = gateway,
            settingsRepository = settingsRepository,
            authRepository = authRepository,
        )
        val repo = DefaultThreadDetailRepository(
            forumHttpClient = forumHttpClient,
            authRepository = authRepository,
            settingsRepository = settingsRepository,
            blacklistRepository = blacklistRepository,
            browseHistoryDao = browseHistoryDao,
            readingPositionDao = readingPositionDao,
        )

        // Multi-choice with cross-digit IDs ("10", "2", "10") locks lexicographical sort ("10" < "2")
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[非常感谢，您的投票已经提交，现在将转入主题页面]]></root>""",
            ),
        )
        server.enqueue(
            gbkHtmlResponse(
                syntheticPollThreadHtml(
                    tid = "50020",
                    fid = 2,
                    page = 1,
                    title = "投票刷新不写历史测试",
                    maxChoices = 3,
                    options = listOf(
                        SyntheticPollOpt(id = null, text = "选项二", rates = "50.00% (1)"),
                        SyntheticPollOpt(id = null, text = "选项十", rates = "50.00% (1)"),
                    ),
                    footer = "您已经投过票",
                ),
            ),
        )

        val voteRes = repo.submitPollVote(
            fid = 2,
            tid = "50020",
            formhash = "fh_lex",
            optionIds = listOf("2", "10", "2"),
            maxChoices = 3,
        )
        assertTrue("Expected Success, got $voteRes", voteRes is PollVoteResult.Success)

        val postReq = server.takeRequest()
        assertEquals(
            listOf(
                "formhash" to "fh_lex",
                "pollanswers[]" to "10",
                "pollanswers[]" to "2",
            ),
            decodeOrderedGbkForm(postReq.body?.utf8().orEmpty()),
        )
        assertEquals("/forum/viewthread.php?tid=50020&page=1", server.takeRequest().target)

        // Poll refresh must NOT write browse history
        assertTrue(
            "Poll vote page-1 refresh must not write browse history",
            browseHistoryDao.observeRecent(10).first().isEmpty(),
        )

        // 6-arg @Inject constructor retains history under DEFAULT_MAX_BROWSE_HISTORY (500)
        server.enqueue(
            gbkHtmlResponse(
                syntheticThreadPageHtml(
                    tidInDom = "50021",
                    fid = 2,
                    forumName = "Discovery",
                    title = "默认上限保留验证",
                    page = 1,
                    lastPage = 1,
                    floors = listOf(SyntheticFloor(pid = "51021", floor = 1, uid = "1001", author = "DefaultCapOP", text = "正文")),
                ),
            ),
        )
        assertTrue(repo.loadPage(tid = "50021", page = 1) is ThreadDetailLoadResult.Success)
        assertEquals(listOf("50021"), browseHistoryDao.observeRecent(10).first().map { it.tid })

        // maxBrowseHistory < 1 is rejected fail-closed
        val rejectedZero = try {
            createRepository(maxBrowseHistory = 0)
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue("maxBrowseHistory = 0 must throw IllegalArgumentException", rejectedZero)
    }

    @Test
    fun threadDetailModule_hasExpectedHiltBindingForThreadDetailRepository() {
        assertTrue(ThreadDetailModule::class.java.isAnnotationPresent(Module::class.java))
        assertTrue(DefaultThreadDetailRepository::class.java.isAnnotationPresent(Singleton::class.java))

        val bindMethod = ThreadDetailModule::class.java.getDeclaredMethod(
            "bindThreadDetailRepository",
            DefaultThreadDetailRepository::class.java,
        )
        assertTrue(bindMethod.isAnnotationPresent(Binds::class.java))
        assertTrue(bindMethod.isAnnotationPresent(Singleton::class.java))
        assertEquals(ThreadDetailRepository::class.java, bindMethod.returnType)
    }

    // region Test Fixtures & Helpers

    private data class SyntheticFloor(
        val pid: String,
        val floor: Int,
        val uid: String,
        val author: String,
        val text: String,
    )

    private data class SyntheticPollOpt(
        val id: String?,
        val text: String,
        val rates: String?,
    )

    private fun sampleFloor(
        pid: String,
        floor: Int,
        uid: String,
        author: String,
    ): PostFloor = PostFloor(
        postId = pid,
        floor = floor,
        page = 1,
        uid = uid,
        author = author,
        avatarUrl = null,
        timePost = "2026-11-25 10:00",
        isWarned = false,
        postStatus = null,
        signature = null,
        poll = null,
        blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("Floor $floor body")))),
    )

    private fun loggedInPageHtml(
        body: String,
        uid: String = "10000",
        username: String = "HiUser",
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

    private fun loggedOutHtml(errorText: String): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
        <body>
          <div id="umenu"><a href="logging.php?action=login">登录</a></div>
          <div id="wrap"><div class="alert_error">$errorText</div></div>
        </body>
        </html>
    """.trimIndent()

    private fun syntheticThreadPageHtml(
        loggedIn: Boolean = true,
        tidInDom: String?,
        fid: Int,
        forumName: String,
        title: String,
        page: Int,
        lastPage: Int,
        floors: List<SyntheticFloor>,
        headScript: String? = null,
    ): String {
        val umenu = if (loggedIn) {
            """<div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite><a href="logging.php?action=logout&amp;formhash=00000000">退出</a></div>"""
        } else {
            """<div id="umenu"><a href="logging.php?action=login">登录</a></div>"""
        }
        val pagesHtml = buildString {
            if (lastPage > 1) {
                append("""<div class="pages">""")
                for (p in 1..lastPage) {
                    if (p == page) {
                        append("<strong>$p</strong>")
                    } else if (tidInDom != null) {
                        append("""<a href="viewthread.php?tid=$tidInDom&amp;page=$p">$p</a>""")
                    } else {
                        append("""<a href="javascript:;">$p</a>""")
                    }
                }
                append("</div>")
            }
        }
        val hiddenTid = tidInDom?.let { """<input type="hidden" name="tid" value="$it" />""" }.orEmpty()
        val floorsHtml = floors.joinToString("\n") { f ->
            """
            <div id="post_${f.pid}"><table><tbody><tr>
              <td class="postauthor"><div class="postinfo"><a href="space.php?uid=${f.uid}">${f.author}</a></div></td>
              <td class="postcontent">
                <div class="postinfo"><strong><a href="javascript:;"><em>${f.floor}</em><sup>#</sup></a></strong>
                  <div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 10:00</em></div></div>
                </div>
                <div class="defaultpost"><div class="postmessage">
                  <div class="t_msgfontfix"><table><tbody><tr><td class="t_msgfont">${f.text}</td></tr></tbody></table></div>
                </div></div>
              </td>
            </tr></tbody></table></div>
            """.trimIndent()
        }
        val scriptTag = headScript?.let { """<script type="text/javascript">$it</script>""" }.orEmpty()
        return """
            <!DOCTYPE html>
            <html>
            <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" />$scriptTag</head>
            <body>
              $umenu
              <div id="nav"><a href="index.php">4D4Y</a> &raquo; <a href="forumdisplay.php?fid=$fid">$forumName</a> &raquo; $title</div>
              <div id="wrap">
                $hiddenTid
                <div class="forumcontrol">$pagesHtml</div>
                <div id="postlist">
                  $floorsHtml
                </div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun syntheticPollThreadHtml(
        tid: String,
        fid: Int,
        page: Int,
        title: String,
        maxChoices: Int,
        options: List<SyntheticPollOpt>,
        footer: String,
    ): String {
        val header = if (maxChoices > 1) {
            "多选投票: ( 最多可选 $maxChoices 项 ) , 共有 10 人参与投票"
        } else {
            "单选投票 , 共有 10 人参与投票"
        }
        val rows = options.joinToString("\n") { opt ->
            val selector = if (opt.id != null) {
                """<td class="selector"><input type="checkbox" name="pollanswers[]" value="${opt.id}" /></td>"""
            } else {
                ""
            }
            val ratesRow = opt.rates?.let { "<tr><td></td><td><em>$it</em></td></tr>" }.orEmpty()
            """
            <tr>$selector<td class="polloption"><label>${opt.text}</label></td></tr>
            $ratesRow
            """.trimIndent()
        }
        return loggedInPageHtml(
            body = """
                <div id="nav"><a href="index.php">4D4Y</a> &raquo; <a href="forumdisplay.php?fid=$fid">Discovery</a> &raquo; $title</div>
                <div id="wrap">
                  <input type="hidden" name="tid" value="$tid" />
                  <div class="forumcontrol"><div class="pages"><strong>$page</strong></div></div>
                  <div id="postlist">
                    <div id="post_71001"><table><tbody><tr>
                      <td class="postauthor"><div class="postinfo"><a href="space.php?uid=11001">PollOP</a></div></td>
                      <td class="postcontent">
                        <div class="postinfo"><strong><a><em>1</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 08:00</em></div></div></div>
                        <div class="defaultpost"><div class="postmessage">
                          <div class="t_msgfontfix"><table><tbody><tr><td class="t_msgfont">请参与投票</td></tr></tbody></table></div>
                          <div class="pollinfo">$header</div>
                          <div class="pollchart"><table><tbody>
                            $rows
                            <tr><td colspan="2">$footer</td></tr>
                          </tbody></table></div>
                        </div></div>
                      </td>
                    </tr></tbody></table></div>
                  </div>
                </div>
            """.trimIndent(),
        )
    }

    private fun gbkHtmlResponse(html: String): MockResponse {
        val bytes = html.toByteArray(GBK)
        return MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "text/html; charset=gbk")
            .body(Buffer().write(bytes))
            .build()
    }

    private fun gbkXmlResponse(xml: String): MockResponse {
        val bytes = xml.toByteArray(GBK)
        return MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "text/xml; charset=gbk")
            .body(Buffer().write(bytes))
            .build()
    }

    private fun decodeOrderedGbkForm(rawBody: String): List<Pair<String, String>> {
        if (rawBody.isBlank()) return emptyList()
        return rawBody.split('&').map { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) {
                URLDecoder.decode(pair, "GBK") to ""
            } else {
                URLDecoder.decode(pair.substring(0, idx), "GBK") to
                    URLDecoder.decode(pair.substring(idx + 1), "GBK")
            }
        }
    }

    private class FakeSettingsRepository(initial: AppSettings) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state.asStateFlow()

        override suspend fun setThemeMode(themeMode: ThemeMode) = state.update { it.copy(themeMode = themeMode) }
        override suspend fun setPureBlack(enabled: Boolean) = state.update { it.copy(pureBlack = enabled) }
        override suspend fun setThemeSeed(themeSeed: String) = state.update { it.copy(themeSeed = themeSeed) }
        override suspend fun setFontStep(step: Int) = state.update { it.copy(fontStep = step) }
        override suspend fun setLineSpacing(lineSpacing: LineSpacing) = state.update { it.copy(lineSpacing = lineSpacing) }
        override suspend fun setFollowedForums(forumIds: List<Int>) = state.update { it.copy(followedForums = forumIds) }
        override suspend fun setShowStickyThreads(show: Boolean) = state.update { it.copy(showStickyThreads = show) }
        override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) =
            state.update { it.copy(sortByPostTime = it.sortByPostTime + (fid to sortByPostTime)) }
        override suspend fun setTailEnabled(enabled: Boolean) = state.update { it.copy(tailEnabled = enabled) }
        override suspend fun setTailText(text: String) = state.update { it.copy(tailText = text) }
        override suspend fun setTailUrl(url: String) = state.update { it.copy(tailUrl = url) }
        override suspend fun setNotificationsEnabled(enabled: Boolean) = state.update { it.copy(notificationsEnabled = enabled) }
        override suspend fun setSilentModeEnabled(enabled: Boolean) = state.update { it.copy(silentModeEnabled = enabled) }
        override suspend fun setSilentBeginMinutes(minutes: Int) = state.update { it.copy(silentBeginMinutes = minutes) }
        override suspend fun setSilentEndMinutes(minutes: Int) = state.update { it.copy(silentEndMinutes = minutes) }
        override suspend fun setForumServer(server: String) = state.update { it.copy(forumServer = server) }
        override suspend fun setImageCacheLimitMb(limitMb: Int) = state.update { it.copy(imageCacheLimitMb = limitMb) }
        override suspend fun setPaneSplitPortrait(split: PaneSplit) = state.update { it.copy(paneSplitPortrait = split) }
        override suspend fun setPaneSplitLandscape(split: PaneSplit) = state.update { it.copy(paneSplitLandscape = split) }
        override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) =
            state.update { it.copy(sidebarCollapsedPortrait = collapsed) }
        override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) =
            state.update { it.copy(sidebarCollapsedLandscape = collapsed) }
    }

    private class FakeAuthRepository(initialAccount: SavedAccount?) : AuthRepository {
        private val _currentAccount = MutableStateFlow(initialAccount)
        private val _savedAccounts = MutableStateFlow(listOfNotNull(initialAccount))
        private val _authState = MutableStateFlow<AuthState>(
            if (initialAccount != null) AuthState.SignedIn(initialAccount) else AuthState.SignedOut,
        )
        private val epochCounter = AtomicLong(1L)

        var onTrySilentRelogin: suspend (Long, AccessPriority) -> SilentReloginResult = { _, _ ->
            SilentReloginResult.Suppressed
        }

        override val currentAccount: StateFlow<SavedAccount?> = _currentAccount.asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = _savedAccounts.asStateFlow()
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override val sessionEpoch: Long get() = epochCounter.get()

        fun bumpEpoch(): Long = epochCounter.incrementAndGet()

        fun setCurrentAccount(account: SavedAccount?) {
            _currentAccount.value = account
            _authState.value = if (account != null) AuthState.SignedIn(account) else AuthState.SignedOut
        }

        override suspend fun login(
            username: String,
            passwordOrMd5: String,
            securityQuestionId: Int,
            securityAnswer: String,
        ): LoginResult = LoginResult.Failure("Not used in test")

        override suspend fun switchAccount(uid: String): LoginResult = LoginResult.Failure("Not used in test")

        override suspend fun logout() = setCurrentAccount(null)

        override suspend fun removeAccount(uid: String) {
            if (_currentAccount.value?.uid == uid) setCurrentAccount(null)
        }

        override suspend fun trySilentRelogin(
            failedEpoch: Long,
            priority: AccessPriority,
        ): SilentReloginResult = onTrySilentRelogin(failedEpoch, priority)
    }

    private class FakeBlacklistRepository(
        initialBlocked: Set<String> = emptySet(),
    ) : BlacklistRepository {
        private val _blocked = MutableStateFlow(initialBlocked)
        private val _lastSyncedAtMs = MutableStateFlow<Long?>(null)

        override val blockedUsernames: StateFlow<Set<String>> = _blocked.asStateFlow()
        override val lastSyncedAtMs: StateFlow<Long?> = _lastSyncedAtMs.asStateFlow()

        fun setBlocked(usernames: Set<String>) {
            _blocked.value = usernames
        }

        override fun isBlocked(username: String): Boolean = username.trim() in _blocked.value

        override suspend fun syncBlacklist(priority: AccessPriority): BlacklistSyncResult =
            BlacklistSyncResult.Success(BlacklistSnapshot(usernames = _blocked.value), _blocked.value.isEmpty())

        override suspend fun addToBlacklist(
            username: String,
            formhash: String?,
            priority: AccessPriority,
        ): BlacklistMutationResult {
            val trimmed = username.trim()
            _blocked.value = _blocked.value + trimmed
            return BlacklistMutationResult.Success(trimmed, _blocked.value)
        }

        override suspend fun removeFromBlacklist(
            username: String,
            formhash: String?,
            priority: AccessPriority,
        ): BlacklistMutationResult {
            val trimmed = username.trim()
            _blocked.value = _blocked.value - trimmed
            return BlacklistMutationResult.Success(trimmed, _blocked.value)
        }
    }

    // endregion

    companion object {
        private val GBK: Charset = Charset.forName("GBK")
        private const val TEST_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
