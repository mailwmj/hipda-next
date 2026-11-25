package net.jejer.hipda.data.auth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.SavedAccount
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
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ForumHttpClientTest {

    private lateinit var nowMs: AtomicLong
    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var webViewCookieAccess: RealisticFakeWebViewCookieAccess
    private lateinit var cookieDispatcher: ExecutorCoroutineDispatcher
    private lateinit var credentialsStore: FakeCredentialsStore
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        nowMs = AtomicLong(1_700_000_000_000L)
        cookieStore = InMemorySiteCookieStore(clock = { nowMs.get() })
        webViewCookieAccess = RealisticFakeWebViewCookieAccess()
        cookieDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-forum-http-cookie").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        credentialsStore = FakeCredentialsStore()
        server = MockWebServer()
        server.start()
        settingsRepository = FakeSettingsRepository(
            AppSettings(forumServer = server.url("").toString().trimEnd('/')),
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    private fun createStack(
        solver: ChallengeSolver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) },
        transportDelegate: okhttp3.Interceptor? = null,
    ): Pair<DefaultAuthRepository, ForumHttpClient> {
        val primaryClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = TransportInterceptor(
                delegateFactory = { transportDelegate },
                isMainThread = { false },
            ),
        ).also(clientsToClose::add)
        val recoveryClient = HttpClientModule.provideRecoveryOkHttpClient(primaryClient)
            .also(clientsToClose::add)
        val detector = ChallengeDetector()
        val primaryGateway = SiteAccessGateway(
            client = primaryClient,
            detector = detector,
            solver = solver,
            clock = { nowMs.get() },
            gatewayScope = scope,
            solveTimeout = 5.seconds,
        )
        val recoveryGateway = SiteAccessGateway(
            client = recoveryClient,
            detector = detector,
            solver = solver,
            clock = { nowMs.get() },
            gatewayScope = scope,
            solveTimeout = 5.seconds,
        )
        val authRepo = DefaultAuthRepository(
            credentialsStore = credentialsStore,
            siteCookieStore = cookieStore,
            settingsRepository = settingsRepository,
            siteAccessGateway = primaryGateway,
            recoveryGateway = recoveryGateway,
            clock = { nowMs.get() },
            scope = scope,
            webViewCookieAccess = webViewCookieAccess,
            cookieDispatcher = cookieDispatcher,
        )
        val forumClient = ForumHttpClient(
            siteAccessGateway = primaryGateway,
            settingsRepository = settingsRepository,
            authRepository = authRepo,
        )
        return authRepo to forumClient
    }

    @Test
    fun getHtml_readsDynamicForumServerDecodesGbkAndParsesMeta() = runBlocking {
        val secondServer = MockWebServer()
        secondServer.start()
        try {
            val (_, forumClient) = createStack()

            server.enqueue(gbkHtmlResponse(AuthTestFixtures.loggedInPageHtml("10000", "HiUser", "版块一中文内容")))
            val firstResp = forumClient.getHtml("forumdisplay.php?fid=2", requireAuth = false)
            assertEquals(200, firstResp.statusCode)
            assertTrue(firstResp.url.endsWith("/forum/forumdisplay.php?fid=2"))
            assertTrue(firstResp.html.contains("版块一中文内容"))
            assertTrue(firstResp.meta.session.isLoggedIn)
            assertEquals("10000", firstResp.meta.session.uid)
            assertEquals("HiUser", firstResp.meta.session.username)
            assertEquals("deadbeef", firstResp.meta.formhash)

            // Switch forumServer dynamically (with trailing slash) and verify next request hits second server
            settingsRepository.setForumServer(secondServer.url("").toString())
            secondServer.enqueue(gbkHtmlResponse(AuthTestFixtures.loggedOutPageHtml("游客可见内容")))

            val secondResp = forumClient.getHtml("/viewthread.php?tid=123", requireAuth = false)
            assertEquals(200, secondResp.statusCode)
            assertTrue(secondResp.url.startsWith(secondServer.url("/forum/viewthread.php?tid=123").toString()))
            assertTrue(secondResp.html.contains("游客可见内容"))
            assertFalse(secondResp.meta.session.isLoggedIn)
            assertEquals(1, server.requestCount)
            assertEquals(1, secondServer.requestCount)
        } finally {
            secondServer.close()
        }
    }

    @Test
    fun postGbkForm_encodesGbkAndSupplementaryEmojiAndRetriesOnceAfterSilentRelogin() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("alice_pw"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val (_, forumClient) = createStack()

        var postAttempts = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/forum/post.php?action=reply&tid=42" -> {
                    postAttempts++
                    val hasFreshCookie = request.headers["Cookie"].orEmpty().contains("cdb_auth=fresh_post_cookie")
                    if (hasFreshCookie) {
                        gbkHtmlResponse(AuthTestFixtures.loggedInPageHtml("10001", "Alice", "回复发布成功"))
                    } else {
                        gbkHtmlResponse(AuthTestFixtures.loggedOutPageHtml("您尚未登录"))
                    }
                }

                "/forum/logging.php?action=login" -> gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_post_relogin"))
                "/forum/logging.php?action=login&loginsubmit=yes&inajax=1" -> gbkXmlResponse(
                    """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Alice]]></root>""",
                    headers = listOf("Set-Cookie" to "cdb_auth=fresh_post_cookie; Path=/"),
                )

                else -> MockResponse.Builder().code(404).build()
            }
        }

        val response = forumClient.postGbkForm(
            relativePath = "post.php?action=reply&tid=42",
            fields = listOf(
                "formhash" to "abcd1234",
                "message" to "你好世界😀",
            ),
            requireAuth = true,
        )

        assertEquals(200, response.statusCode)
        assertTrue(response.meta.session.isLoggedIn)
        assertTrue(response.html.contains("回复发布成功"))
        assertEquals("Original POST should be sent twice (initial + 1 retry after relogin)", 2, postAttempts)

        val firstPost = server.takeRequest()
        assertEquals("POST", firstPost.method)
        val firstFields = parseGbkForm(firstPost.body?.utf8().orEmpty())
        assertEquals("abcd1234", firstFields["formhash"])
        assertEquals("你好世界&#128512;", firstFields["message"])

        // Skip GET login form and POST login
        server.takeRequest()
        server.takeRequest()

        val retriedPost = server.takeRequest()
        assertEquals("POST", retriedPost.method)
        assertTrue(retriedPost.headers["Cookie"].orEmpty().contains("cdb_auth=fresh_post_cookie"))
        val retriedFields = parseGbkForm(retriedPost.body?.utf8().orEmpty())
        assertEquals("你好世界&#128512;", retriedFields["message"])
    }

    @Test
    fun getHtml_whenStillLoggedOutAfterSilentRelogin_stopsAfterOneRetryWithoutInfiniteLoop() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("alice_pw"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val (_, forumClient) = createStack()
        val targetHits = AtomicInteger(0)
        val loginGetHits = AtomicInteger(0)
        val loginPostHits = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/forum/pm.php" -> {
                    targetHits.incrementAndGet()
                    // Always returns logged-out HTML even after relogin!
                    gbkHtmlResponse(AuthTestFixtures.loggedOutPageHtml("会话仍无效"))
                }

                "/forum/logging.php?action=login" -> {
                    loginGetHits.incrementAndGet()
                    gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_still_bad"))
                }

                "/forum/logging.php?action=login&loginsubmit=yes&inajax=1" -> {
                    loginPostHits.incrementAndGet()
                    gbkXmlResponse(
                        """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Alice]]></root>""",
                        headers = listOf("Set-Cookie" to "cdb_auth=rejected_cookie; Path=/"),
                    )
                }

                else -> MockResponse.Builder().code(404).build()
            }
        }

        try {
            forumClient.getHtml("pm.php", requireAuth = true)
            fail("Expected AuthRequiredException when retry is still logged out")
        } catch (e: AuthRequiredException) {
            assertEquals(AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED, e.reason)
            assertTrue(e.reloginResult is SilentReloginResult.Recovered)
        }

        assertEquals("Target endpoint must be hit at most twice (initial + 1 retry)", 2, targetHits.get())
        assertEquals("Silent relogin GET must run at most once", 1, loginGetHits.get())
        assertEquals("Silent relogin POST must run at most once", 1, loginPostHits.get())
    }

    @Test
    fun getHtml_whenManuallyLoggedOut_throwsAuthRequiredExceptionWithZeroReloginRequests() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("alice_pw"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val (authRepo, forumClient) = createStack()
        authRepo.logout()

        val loginRequests = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.target.startsWith("/forum/logging.php")) {
                    loginRequests.incrementAndGet()
                }
                return gbkHtmlResponse(AuthTestFixtures.loggedOutPageHtml("请先登录"))
            }
        }

        try {
            forumClient.getHtml("notice.php", requireAuth = true)
            fail("Expected AuthRequiredException when manually logged out")
        } catch (e: AuthRequiredException) {
            assertEquals(AuthRequiredReason.MANUALLY_LOGGED_OUT, e.reason)
            assertEquals(SilentReloginResult.Suppressed, e.reloginResult)
        }

        assertEquals("Must issue 0 relogin requests after manual logout", 0, loginRequests.get())
    }

    @Test
    fun getHtml_fiveConcurrentExpiredRequests_coalesceIntoSingleSilentReloginAndAllSucceed() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("alice_pw"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val (_, forumClient) = createStack()
        val loginPostCount = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.target.startsWith("/forum/viewthread.php") -> {
                    if (request.headers["Cookie"].orEmpty().contains("cdb_auth=concurrent_fresh")) {
                        gbkHtmlResponse(AuthTestFixtures.loggedInPageHtml("10001", "Alice", "内容:${request.target}"))
                    } else {
                        gbkHtmlResponse(AuthTestFixtures.loggedOutPageHtml("需要登录"))
                    }
                }

                request.target == "/forum/logging.php?action=login" -> {
                    Thread.sleep(50)
                    gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_concurrent"))
                }

                request.target == "/forum/logging.php?action=login&loginsubmit=yes&inajax=1" -> {
                    loginPostCount.incrementAndGet()
                    gbkXmlResponse(
                        """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Alice]]></root>""",
                        headers = listOf("Set-Cookie" to "cdb_auth=concurrent_fresh; Path=/"),
                    )
                }

                else -> MockResponse.Builder().code(404).build()
            }
        }

        val responses = withTimeout(5.seconds) {
            (1..5).map { tid ->
                async(Dispatchers.Default) {
                    forumClient.getHtml("viewthread.php?tid=$tid", requireAuth = true)
                }
            }.awaitAll()
        }

        assertEquals(5, responses.size)
        responses.forEachIndexed { index, resp ->
            assertTrue(resp.meta.session.isLoggedIn)
            assertTrue(resp.html.contains("内容:/forum/viewthread.php?tid=${index + 1}"))
        }
        assertEquals("5 concurrent expired requests must coalesce into 1 relogin POST", 1, loginPostCount.get())
    }

    @Test
    fun getHtml_whenNotLoggedIn_throwsAuthRequiredExceptionWithZeroReloginRequests() = runBlocking {
        val (_, forumClient) = createStack()
        val loginRequests = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.target.startsWith("/forum/logging.php")) {
                    loginRequests.incrementAndGet()
                }
                return gbkHtmlResponse(AuthTestFixtures.loggedOutPageHtml("请先登录"))
            }
        }

        try {
            forumClient.getHtml("pm.php", requireAuth = true)
            fail("Expected AuthRequiredException when no active account")
        } catch (e: AuthRequiredException) {
            assertEquals(AuthRequiredReason.NOT_LOGGED_IN, e.reason)
            assertEquals(SilentReloginResult.NotLoggedIn, e.reloginResult)
        }
        assertEquals(0, loginRequests.get())
    }

    @Test
    fun postGbkForm_withRequireAuthFalse_returnsAjaxXmlWithoutTriggeringRelogin() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("alice_pw"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val (_, forumClient) = createStack()
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[<div class="summary">发送成功</div>]]></root>""",
            ),
        )

        val response = forumClient.postGbkForm(
            relativePath = "pm.php?action=send&pmsubmit=yes&inajax=1",
            fields = listOf("message" to "短消息测试"),
            requireAuth = false,
        )

        assertEquals(200, response.statusCode)
        assertTrue(response.html.contains("发送成功"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun s2_postGbkForm_customIsUnauthenticatedPredicate_enablesSingleSilentReloginForAjaxXmlEndpoints() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("alice_pw"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val (_, forumClient) = createStack()
        val pmHits = AtomicInteger(0)
        val loginPostHits = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/forum/pm.php?action=send&pmsubmit=yes&inajax=1" -> {
                    pmHits.incrementAndGet()
                    if (request.headers["Cookie"].orEmpty().contains("cdb_auth=ajax_fresh_cookie")) {
                        gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[<div class="summary">发送成功</div>]]></root>""")
                    } else {
                        gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[对不起，您还未登录，无法进行此操作]]></root>""")
                    }
                }

                "/forum/logging.php?action=login" -> gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_ajax_relogin"))
                "/forum/logging.php?action=login&loginsubmit=yes&inajax=1" -> {
                    loginPostHits.incrementAndGet()
                    gbkXmlResponse(
                        """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Alice]]></root>""",
                        headers = listOf("Set-Cookie" to "cdb_auth=ajax_fresh_cookie; Path=/"),
                    )
                }

                else -> MockResponse.Builder().code(404).build()
            }
        }

        val ajaxUnauthPredicate: (ForumHtmlResponse) -> Boolean = { resp ->
            resp.html.contains("您还未登录") || resp.html.contains("无权进行当前操作")
        }

        val response = forumClient.postGbkForm(
            relativePath = "pm.php?action=send&pmsubmit=yes&inajax=1",
            fields = listOf("message" to "你好短消息"),
            requireAuth = true,
            isUnauthenticated = ajaxUnauthPredicate,
        )

        assertEquals(200, response.statusCode)
        assertTrue(response.html.contains("发送成功"))
        assertEquals("AJAX POST must be attempted twice (initial + 1 retry)", 2, pmHits.get())
        assertEquals("Exactly 1 silent relogin POST expected", 1, loginPostHits.get())

        // Also verify bounded retry: if retry STILL matches custom unauthenticated predicate, stop and throw
        val stuckHits = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/forum/my.php?item=favorites&action=add&inajax=1&tid=9" -> {
                    stuckHits.incrementAndGet()
                    gbkXmlResponse("""<?xml version="1.0" encoding="gbk"?><root><![CDATA[您还未登录]]></root>""")
                }

                "/forum/logging.php?action=login" -> gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_ajax_stuck"))
                "/forum/logging.php?action=login&loginsubmit=yes&inajax=1" -> gbkXmlResponse(
                    """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Alice]]></root>""",
                )

                else -> MockResponse.Builder().code(404).build()
            }
        }

        try {
            forumClient.getHtml(
                relativePath = "my.php?item=favorites&action=add&inajax=1&tid=9",
                requireAuth = true,
                isUnauthenticated = ajaxUnauthPredicate,
            )
            fail("Expected AuthRequiredException when custom predicate still matches after 1 retry")
        } catch (e: AuthRequiredException) {
            assertEquals(AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED, e.reason)
        }
        assertEquals(2, stuckHits.get())
    }

    @Test
    fun s4_getHtml_backgroundSilentPriorityIsForwardedToTrySilentRelogin() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("alice_pw"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val solverCalls = AtomicInteger(0)
        val (authRepo, forumClient) = createStack(
            solver = ChallengeSolver {
                solverCalls.incrementAndGet()
                SolveResult.Cleared
            },
        )

        // Primary GET returns logged-out HTML; silent relogin GET hits Cloudflare 403 challenge.
        // Because priority is BackgroundSilent, ForumHttpClient MUST forward BackgroundSilent to
        // trySilentRelogin so ChallengeSolver is NOT invoked and global authState stays SignedIn.
        server.enqueue(gbkHtmlResponse(AuthTestFixtures.loggedOutPageHtml("需要登录")))
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .body("challenge")
                .build(),
        )

        try {
            forumClient.getHtml(
                relativePath = "notice.php",
                priority = AccessPriority.BackgroundSilent,
                requireAuth = true,
            )
            fail("Expected AuthRequiredException when BackgroundSilent relogin abandons challenge")
        } catch (e: AuthRequiredException) {
            assertEquals(AuthRequiredReason.RELOGIN_FAILED, e.reason)
            val failed = e.reloginResult as SilentReloginResult.Failed
            assertEquals(SilentReloginFailureReason.CHALLENGE_ABANDONED, failed.reason)
        }

        assertEquals("BackgroundSilent must NEVER invoke ChallengeSolver during silent relogin", 0, solverCalls.get())
        assertEquals(AuthState.SignedIn(account), authRepo.authState.value)
    }

    @Test
    fun s4_getHtml_forumServerEndingWithForumPathDoesNotDuplicateForumSegment() = runBlocking {
        val (_, forumClient) = createStack()
        val baseUrl = server.url("").toString().trimEnd('/')

        settingsRepository.setForumServer("$baseUrl/forum")
        server.enqueue(gbkHtmlResponse(AuthTestFixtures.loggedInPageHtml("10000", "HiUser")))
        val respNoSlash = forumClient.getHtml("index.php", requireAuth = false)
        assertTrue(respNoSlash.url.endsWith("/forum/index.php"))
        assertFalse(respNoSlash.url.contains("/forum/forum/"))
        assertEquals("/forum/index.php", server.takeRequest().target)

        settingsRepository.setForumServer("$baseUrl/forum/")
        server.enqueue(gbkHtmlResponse(AuthTestFixtures.loggedInPageHtml("10000", "HiUser")))
        val respWithSlash = forumClient.getHtml("/notice.php", requireAuth = false)
        assertTrue(respWithSlash.url.endsWith("/forum/notice.php"))
        assertFalse(respWithSlash.url.contains("/forum/forum/"))
        assertEquals("/forum/notice.php", server.takeRequest().target)
    }

    @Test
    fun vD2_inFlightForumRequestCompletingAfterLogout_dropsLateAuthCookiesKeepingCfClearance() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("alice_pw"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val heldInTransport = java.util.concurrent.CountDownLatch(1)
        val releaseTransport = java.util.concurrent.CountDownLatch(1)
        val (authRepo, forumClient) = createStack(
            transportDelegate = okhttp3.Interceptor { chain ->
                val resp = chain.proceed(chain.request())
                heldInTransport.countDown()
                releaseTransport.await(5, java.util.concurrent.TimeUnit.SECONDS)
                resp
            },
        )

        server.enqueue(
            gbkHtmlResponse(
                html = AuthTestFixtures.loggedInPageHtml("10001", "Alice", "正在浏览"),
                headers = listOf(
                    "Set-Cookie" to "cdb_auth=in_flight_forum_auth; Path=/",
                    "Set-Cookie" to "cdb_sid=in_flight_forum_sid; Path=/",
                    "Set-Cookie" to "cf_clearance=in_flight_cf_keep; Path=/",
                ),
            ),
        )

        try {
            val requestJob = async(Dispatchers.Default) {
                forumClient.getHtml("forumdisplay.php?fid=2", requireAuth = false)
            }
            assertTrue(heldInTransport.await(5, java.util.concurrent.TimeUnit.SECONDS))

            // Logout while the regular forum request is still in-flight before AppCookieInterceptor saves cookies
            authRepo.logout()
            releaseTransport.countDown()
            withTimeout(5.seconds) { requestJob.await() }

            val snapshot = cookieStore.snapshot()
            assertFalse("In-flight request must NOT write cdb_auth after logout", snapshot.any { it.name == "cdb_auth" })
            assertFalse("In-flight request must NOT write cdb_sid after logout", snapshot.any { it.name == "cdb_sid" })
            assertTrue(
                "Non-auth cf_clearance should still be saved",
                snapshot.any { it.name == "cf_clearance" && it.value == "in_flight_cf_keep" },
            )
        } finally {
            releaseTransport.countDown()
        }
    }

    @Test
    fun getHtml_non2xxResponse_throwsForumHttpExceptionWithoutTriggeringSilentRelogin() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("alice_pw"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val (_, forumClient) = createStack()
        server.enqueue(MockResponse.Builder().code(500).body("Internal Server Error").build())

        try {
            forumClient.getHtml("notice.php", requireAuth = true)
            fail("Expected ForumHttpException for HTTP 500")
        } catch (e: ForumHttpException) {
            assertEquals(500, e.statusCode)
        }
        assertEquals("HTTP 500 must not trigger silent relogin", 1, server.requestCount)
    }

    companion object {
        private const val TEST_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
