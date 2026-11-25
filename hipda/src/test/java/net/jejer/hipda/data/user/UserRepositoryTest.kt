package net.jejer.hipda.data.user

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import net.jejer.hipda.data.auth.SilentReloginFailureReason
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
import net.jejer.hipda.parser.LegacyTestSupport
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class UserRepositoryTest {

    private lateinit var nowMs: AtomicLong
    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var settingsRepository: UserTestSettingsRepository
    private lateinit var authRepository: UserTestAuthRepository
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var cookieDispatcher: ExecutorCoroutineDispatcher
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        nowMs = AtomicLong(1_700_000_000_000L)
        cookieStore = InMemorySiteCookieStore(clock = { nowMs.get() })
        server = MockWebServer()
        server.start()
        settingsRepository = UserTestSettingsRepository(
            AppSettings(forumServer = server.url("").toString().trimEnd('/')),
        )
        authRepository = UserTestAuthRepository(
            initialAccount = SavedAccount(
                uid = "10000",
                username = "HiUser",
                passwordMd5 = "0123456789abcdef0123456789abcdef",
                lastLoginAt = 1_000L,
            ),
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        cookieDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-user-repo-cookie").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    @After
    fun tearDown() {
        scope.cancel()
        cookieDispatcher.close()
        clientsToClose.forEach { client ->
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        clientsToClose.clear()
        server.close()
    }

    private fun createRepository(
        solver: ChallengeSolver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) },
    ): DefaultUserRepository {
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
        return DefaultUserRepository(
            forumHttpClient = forumHttpClient,
            settingsRepository = settingsRepository,
        )
    }

    @Test
    fun getUserProfile_realSampleParsesAllFieldsAndFormhash() = runBlocking {
        val sampleHtml = LegacyTestSupport.loadSample("user_info/user_profile.html")
        server.enqueue(userTestGbkHtmlResponse(sampleHtml))

        val repo = createRepository()
        val result = repo.getUserProfile("10000")

        assertTrue("Expected Success, got $result", result is UserProfileResult.Success)
        val profile = (result as UserProfileResult.Success).profile
        assertEquals("10000", profile.uid)
        assertEquals("HiUser", profile.username)
        assertTrue(profile.isOnline)
        assertEquals(
            "https://img02.4d4y.com/forum/uc_server/data/avatar/000/01/00/00_avatar_middle.jpg",
            profile.avatarUrl,
        )
        assertEquals("00000000", profile.formhash)
        assertTrue(profile.meta.session.isLoggedIn)
        assertTrue(profile.detail.contains("用户组: 初级会员"))
        assertTrue(profile.detail.contains("注册日期: 2011-4-13"))
        assertTrue(profile.detail.contains("积分: 6"))
        assertFalse(profile.detail.contains("信用评价"))

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/forum/space.php?uid=10000", recorded.target)
    }

    @Test
    fun getUserProfile_rejectsBlankAndInvalidUidWithZeroRequests() = runBlocking {
        val repo = createRepository()
        val invalidUids = listOf("", "   ", "0", "-1", "abc", "10000&foo=bar")

        for (badUid in invalidUids) {
            val result = repo.getUserProfile(badUid)
            assertTrue("Expected InvalidInput for '$badUid', got $result", result is UserError.InvalidInput)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun getUserProfile_siteErrorAndMalformedPageReturnExplicitFailures() = runBlocking {
        val repo = createRepository()

        server.enqueue(
            userTestGbkHtmlResponse(
                userTestLoggedInHtml(
                    body = """<div class="alert_error">用户不存在或已被删除<a href="index.php">返回</a></div>""",
                ),
            ),
        )
        val siteErrorResult = repo.getUserProfile("999999")
        assertEquals(UserError.SiteError("用户不存在或已被删除"), siteErrorResult)

        server.enqueue(
            userTestGbkHtmlResponse(
                userTestLoggedInHtml(body = """<div id="empty_body">没有资料结构</div>"""),
            ),
        )
        val malformedResult = repo.getUserProfile("10000")
        assertTrue("Expected InvalidPage, got $malformedResult", malformedResult is UserError.InvalidPage)
    }

    @Test
    fun getUserThreads_realSampleParsesFirstPageAndDerivesAvatarBaseUrlFromForumServer() = runBlocking {
        val sampleHtml = LegacyTestSupport.loadSample("search/search_result.html")
        val baseServer = server.url("").toString().trimEnd('/')
        settingsRepository.setForumServer("$baseServer/forum")
        server.enqueue(userTestGbkHtmlResponse(sampleHtml))

        val repo = createRepository()
        val result = repo.getUserThreads(uid = "10000")

        assertTrue("Expected Success, got $result", result is UserThreadsResult.Success)
        val page = result as UserThreadsResult.Success
        assertEquals("10000", page.uid)
        assertEquals(1, page.page)
        assertEquals("511", page.searchId)
        assertEquals(5, page.maxPage)
        assertTrue(page.hasNextPage)
        assertEquals(75, page.items.size)

        val firstItem = page.items.first()
        val expectedAvatarPrefix = "$baseServer/forum/uc_server/data/avatar/"
        assertTrue(
            "Expected avatarUrl starting with $expectedAvatarPrefix without duplicate /forum, got ${firstItem.avatarUrl}",
            firstItem.avatarUrl?.startsWith(expectedAvatarPrefix) == true,
        )
        assertFalse(firstItem.avatarUrl.orEmpty().contains("/forum/forum/"))

        val appleItem = page.items.firstOrNull { it.tid == "3477944" }
        assertTrue("Expected decoded apple emoji in title, got ${appleItem?.title}", appleItem?.title?.contains("🍎") == true)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/forum/search.php?srchfid=all&srchfrom=0&searchsubmit=yes&srchuid=10000", recorded.target)
    }

    @Test
    fun getUserThreads_buildsExpectedPathsForSearchIdAndPagination() = runBlocking {
        val sampleHtml = LegacyTestSupport.loadSample("search/search_result.html")
        server.enqueue(userTestGbkHtmlResponse(sampleHtml))
        server.enqueue(userTestGbkHtmlResponse(sampleHtml))
        server.enqueue(userTestGbkHtmlResponse(sampleHtml))

        val repo = createRepository()

        val page2WithSearchId = repo.getUserThreads(uid = "10000", page = 2, searchId = "511")
        assertTrue(page2WithSearchId is UserThreadsResult.Success)
        assertEquals(2, (page2WithSearchId as UserThreadsResult.Success).page)

        val page1WithSearchId = repo.getUserThreads(uid = "10000", page = 1, searchId = "511")
        assertTrue(page1WithSearchId is UserThreadsResult.Success)

        val page3WithoutSearchId = repo.getUserThreads(uid = "10000", page = 3, searchId = null)
        assertTrue(page3WithoutSearchId is UserThreadsResult.Success)

        assertEquals(
            "/forum/search.php?searchid=511&orderby=lastpost&ascdesc=desc&searchsubmit=yes&page=2",
            server.takeRequest().target,
        )
        assertEquals(
            "/forum/search.php?searchid=511&orderby=lastpost&ascdesc=desc&searchsubmit=yes",
            server.takeRequest().target,
        )
        assertEquals(
            "/forum/search.php?srchfid=all&srchfrom=0&searchsubmit=yes&srchuid=10000&page=3",
            server.takeRequest().target,
        )
    }

    @Test
    fun getUserThreads_emptyUserReturnsSuccessEmptyListNotError() = runBlocking {
        val repo = createRepository()

        // Case 1: Discuz! standard empty search message inside div.alert_error
        server.enqueue(
            userTestGbkHtmlResponse(
                userTestLoggedInHtml(
                    body = """<div class="alert_error">对不起，没有找到匹配结果。<a href="search.php">返回</a></div>""",
                ),
            ),
        )
        val noMatchResult = repo.getUserThreads(uid = "20001")
        assertTrue("User with no posts must succeed with empty list, got $noMatchResult", noMatchResult is UserThreadsResult.Success)
        val noMatchSuccess = noMatchResult as UserThreadsResult.Success
        assertEquals(emptyList<Any>(), noMatchSuccess.items)
        assertFalse(noMatchSuccess.hasNextPage)

        // Case 2: Empty search result table without alert_error
        server.enqueue(
            userTestGbkHtmlResponse(
                userTestLoggedInHtml(
                    body = """<div class="searchlist"><table class="datatable"></table><p class="nodata">暂无数据</p></div>""",
                ),
            ),
        )
        val emptyTableResult = repo.getUserThreads(uid = "20002")
        assertTrue("Empty search table must succeed with empty list, got $emptyTableResult", emptyTableResult is UserThreadsResult.Success)
        assertEquals(emptyList<Any>(), (emptyTableResult as UserThreadsResult.Success).items)
    }

    @Test
    fun getUserThreads_genuineSiteErrorReturnsSiteError() = runBlocking {
        val repo = createRepository()
        server.enqueue(
            userTestGbkHtmlResponse(
                userTestLoggedInHtml(
                    body = """<div class="alert_error">对不起，您在 15 秒内只能进行一次搜索<a href="search.php">返回</a></div>""",
                ),
            ),
        )

        val result = repo.getUserThreads(uid = "10000")
        assertEquals(UserError.SiteError("对不起，您在 15 秒内只能进行一次搜索"), result)
    }

    @Test
    fun getUserThreads_rejectsInvalidParametersWithZeroRequests() = runBlocking {
        val repo = createRepository()

        assertTrue(repo.getUserThreads(uid = " ").isInvalidInput())
        assertTrue(repo.getUserThreads(uid = "10000", page = 0).isInvalidInput())
        assertTrue(repo.getUserThreads(uid = "10000", page = -2).isInvalidInput())
        assertTrue(repo.getUserThreads(uid = "10000", page = 2, searchId = "511&bad=1").isInvalidInput())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun getUserProfileAndThreads_propagateAuthFailureReloginRecoveryHttpAndChallengeErrors() = runBlocking {
        val repo = createRepository()

        // 1. Auth required (manually logged out -> Suppressed)
        authRepository.nextReloginResult = SilentReloginResult.Suppressed
        server.enqueue(userTestGbkHtmlResponse(userTestLoggedOutHtml("您尚未登录")))
        val authErr = repo.getUserProfile("10000")
        assertTrue("Expected AuthRequired, got $authErr", authErr is UserError.AuthRequired)
        assertEquals(AuthRequiredReason.MANUALLY_LOGGED_OUT, (authErr as UserError.AuthRequired).reason)

        // 2. Transparent single silent relogin recovery via ForumHttpClient
        val reloginCalls = AtomicInteger(0)
        authRepository.onTrySilentRelogin = { _, _ ->
            reloginCalls.incrementAndGet()
            authRepository.bumpEpoch()
            SilentReloginResult.Recovered(authRepository.sessionEpoch)
        }
        val attempts = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = attempts.incrementAndGet()
                return if (n == 1) {
                    userTestGbkHtmlResponse(userTestLoggedOutHtml("登录已过期"))
                } else {
                    userTestGbkHtmlResponse(LegacyTestSupport.loadSample("user_info/user_profile.html"))
                }
            }
        }
        val recovered = repo.getUserProfile("10000")
        assertTrue("Expected Success after silent relogin, got $recovered", recovered is UserProfileResult.Success)
        assertEquals(1, reloginCalls.get())
        assertEquals(2, attempts.get())

        // 3. HTTP 500 -> NetworkError(statusCode = 500)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(500).body("Server Error").build()
        }
        val httpErr = repo.getUserThreads("10000")
        assertTrue("Expected NetworkError, got $httpErr", httpErr is UserError.NetworkError)
        assertEquals(500, (httpErr as UserError.NetworkError).statusCode)

        // 4. Cloudflare 403 under BackgroundSilent -> ChallengeRequired(abandoned = true)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .body("challenge")
                .build()
        }
        val challengeErr = repo.getUserProfile("10000", priority = AccessPriority.BackgroundSilent)
        assertTrue("Expected ChallengeRequired, got $challengeErr", challengeErr is UserError.ChallengeRequired)
        assertTrue((challengeErr as UserError.ChallengeRequired).abandoned)
    }

    @Test
    fun rD8bS3_4_getUserProfileResolvesFormhashFromUmenuLogoutLinkAndMetaInputFallback() = runBlocking {
        val repo = createRepository()

        // 1. No <input name="formhash">, only #umenu logout link formhash
        server.enqueue(
            userTestGbkHtmlResponse(
                userTestLoggedInHtml(
                    uid = "10000",
                    username = "HiUser",
                    logoutFormhash = "fh_profile_logout_only",
                    body = """
                        <div id="profilecontent">
                          <div class="itemtitle">
                            <h1>HiUser</h1>
                            <ul><li>(UID: 10000)</li></ul>
                          </div>
                        </div>
                    """.trimIndent(),
                ),
            ),
        )
        val logoutFallback = repo.getUserProfile("10000")
        assertTrue("Expected Success, got $logoutFallback", logoutFallback is UserProfileResult.Success)
        assertEquals("fh_profile_logout_only", (logoutFallback as UserProfileResult.Success).profile.formhash)

        // 2. No #umenu logout link formhash, falls back to parsed.meta.formhash from <input name="formhash">
        server.enqueue(
            userTestGbkHtmlResponse(
                """
                <!DOCTYPE html>
                <html>
                <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
                <body>
                  <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
                  <input type="hidden" name="formhash" value="fh_meta_input_fallback" />
                  <div id="profilecontent">
                    <div class="itemtitle">
                      <h1>HiUser</h1>
                      <ul><li>(UID: 10000)</li></ul>
                    </div>
                  </div>
                </body>
                </html>
                """.trimIndent(),
            ),
        )
        val metaInputFallback = repo.getUserProfile("10000")
        assertTrue("Expected Success, got $metaInputFallback", metaInputFallback is UserProfileResult.Success)
        assertEquals("fh_meta_input_fallback", (metaInputFallback as UserProfileResult.Success).profile.formhash)
    }

    private fun Any.isInvalidInput(): Boolean = this is UserError.InvalidInput

    companion object {
        private const val TEST_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

private val TEST_GB18030: Charset = Charset.forName("GB18030")

internal fun userTestGbkHtmlResponse(
    html: String,
    code: Int = 200,
    headers: List<Pair<String, String>> = emptyList(),
): MockResponse = MockResponse.Builder()
    .code(code)
    .addHeader("Content-Type", "text/html; charset=gbk")
    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
    .body(Buffer().write(html.toByteArray(TEST_GB18030)))
    .build()

internal fun userTestLoggedInHtml(
    uid: String = "10000",
    username: String = "HiUser",
    logoutFormhash: String = "fh_logout_1",
    body: String = "",
): String = """
    <!DOCTYPE html>
    <html>
    <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
    <body>
      <div id="umenu">
        <cite><a href="space.php?uid=$uid" class="noborder">$username</a></cite>
        <a href="logging.php?action=logout&amp;formhash=$logoutFormhash">退出</a>
      </div>
      $body
    </body>
    </html>
""".trimIndent()

internal fun userTestLoggedOutHtml(message: String = "您无权进行当前操作"): String = """
    <!DOCTYPE html>
    <html>
    <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
    <body>
      <div id="umenu"><a href="logging.php?action=login">登录</a></div>
      <div class="alert_error">$message</div>
    </body>
    </html>
""".trimIndent()

internal class UserTestSettingsRepository(
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

internal class UserTestAuthRepository(
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
            _savedAccounts.update { list -> (list.filterNot { it.uid == account.uid } + account) }
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
