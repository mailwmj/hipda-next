package net.jejer.hipda.data.auth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.CredentialsStore
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
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.di.AuthModule
import net.jejer.hipda.parser.LegacyTestSupport
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
class AuthRepositoryTest {

    private lateinit var nowMs: AtomicLong
    private lateinit var cookieStore: InMemorySiteCookieStore
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
        clientsToClose.forEach { client ->
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        clientsToClose.clear()
        server.close()
    }

    private fun createPrimaryClient(
        maxRequests: Int? = null,
        maxRequestsPerHost: Int? = null,
        eventListener: EventListener? = null,
    ): OkHttpClient {
        val base = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = TransportInterceptor(
                delegateFactory = { null },
                isMainThread = { false },
            ),
        )
        val builder = base.newBuilder()
        if (maxRequests != null || maxRequestsPerHost != null) {
            builder.dispatcher(
                okhttp3.Dispatcher().apply {
                    if (maxRequests != null) this.maxRequests = maxRequests
                    if (maxRequestsPerHost != null) this.maxRequestsPerHost = maxRequestsPerHost
                },
            )
        }
        if (eventListener != null) {
            builder.eventListener(eventListener)
        }
        return builder.build().also(clientsToClose::add)
    }

    private fun createRecoveryClient(
        primaryClient: OkHttpClient,
        eventListener: EventListener? = null,
    ): OkHttpClient {
        val recovery = HttpClientModule.provideRecoveryOkHttpClient(primaryClient)
        val finalClient = if (eventListener != null) {
            recovery.newBuilder().eventListener(eventListener).build()
        } else {
            recovery
        }
        return finalClient.also(clientsToClose::add)
    }

    private fun createRepository(
        primaryClient: OkHttpClient = createPrimaryClient(),
        recoveryClient: OkHttpClient = createRecoveryClient(primaryClient),
        solver: ChallengeSolver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) },
        detector: ChallengeDetector = ChallengeDetector(),
    ): DefaultAuthRepository {
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
        return DefaultAuthRepository(
            credentialsStore = credentialsStore,
            siteCookieStore = cookieStore,
            settingsRepository = settingsRepository,
            siteAccessGateway = primaryGateway,
            recoveryGateway = recoveryGateway,
            clock = { nowMs.get() },
            scope = scope,
        )
    }

    @Test
    fun login_formSubmitSuccess_encodesGbkEscapesPasswordPersistsCanonicalAccountAndClearsManualLogout() = runBlocking {
        val rawPassword = """P@ss'word"\中文"""
        val expectedMd5 = computeLegacyPasswordMd5(rawPassword)
        val realLoginSample = LegacyTestSupport.loadSample("login/login_page.html")

        credentialsStore.markManualLogout()
        val repo = createRepository()

        server.enqueue(gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_login_01")))
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，HiUser。现在将转入登录前页面。<script>setTimeout("location.href='index.php'",1000);</script>]]></root>""",
                headers = listOf("Set-Cookie" to "cdb_auth=secret_auth_cookie_value; Path=/"),
            ),
        )
        server.enqueue(gbkHtmlResponse(realLoginSample))

        val result = repo.login(
            username = "hiuser",
            passwordOrMd5 = rawPassword,
            securityQuestionId = 3,
            securityAnswer = "我的答案",
        )

        assertTrue("Expected Success but got $result", result is LoginResult.Success)
        val account = (result as LoginResult.Success).account
        assertEquals("10000", account.uid)
        assertEquals("HiUser", account.username)
        assertEquals(expectedMd5, account.passwordMd5)
        assertEquals(3, account.securityQuestionId)
        assertEquals("我的答案", account.securityAnswer)
        assertEquals(nowMs.get(), account.lastLoginAt)

        assertEquals(account, repo.currentAccount.value)
        assertEquals(listOf(account), repo.savedAccounts.value)
        assertEquals(AuthState.SignedIn(account), repo.authState.value)
        assertFalse(credentialsStore.currentManuallyLoggedOut())
        assertEquals("10000", credentialsStore.currentActiveUid())
        assertTrue(repo.sessionEpoch > 0L)

        // Verify 3 requests: GET login form -> POST login -> GET login page to confirm #umenu uid/username
        val req1 = server.takeRequest()
        assertEquals("GET", req1.method)
        assertEquals("/forum/logging.php?action=login", req1.target)

        val req2 = server.takeRequest()
        assertEquals("POST", req2.method)
        assertEquals("/forum/logging.php?action=login&loginsubmit=yes&inajax=1", req2.target)
        assertTrue(req2.headers["Content-Type"].orEmpty().contains("charset=GBK", ignoreCase = true))
        val postedFields = parseGbkForm(req2.body?.utf8().orEmpty())
        assertEquals("fh_login_01", postedFields["m_formhash"])
        assertEquals("${server.url("").toString().trimEnd('/')}/forum/index.php", postedFields["referer"])
        assertEquals("username", postedFields["loginfield"])
        assertEquals("hiuser", postedFields["username"])
        assertEquals(expectedMd5, postedFields["password"])
        assertEquals("3", postedFields["questionid"])
        assertEquals("我的答案", postedFields["answer"])
        assertEquals("2592000", postedFields["cookietime"])

        val req3 = server.takeRequest()
        assertEquals("GET", req3.method)
        assertEquals("/forum/logging.php?action=login", req3.target)
        assertTrue(req3.headers["Cookie"].orEmpty().contains("cdb_auth=secret_auth_cookie_value"))
    }

    @Test
    fun login_wrongPasswordWithRemainingAttempts_returnsInvalidCredentialsAndDoesNotPersist() = runBlocking {
        val repo = createRepository()
        val rawPassword = "WrongSecretPassword!@#"

        server.enqueue(gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_bad_pw")))
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[登录失败，您还可以尝试 4 次<script>hideWindow('login');</script>]]></root>""",
            ),
        )

        val result = repo.login(username = "HiUser", passwordOrMd5 = rawPassword)

        assertTrue("Expected InvalidCredentials but got $result", result is LoginResult.InvalidCredentials)
        val failed = result as LoginResult.InvalidCredentials
        assertEquals(4, failed.remainingAttempts)
        assertTrue(failed.message.contains("4 次"))
        assertNull(repo.currentAccount.value)
        assertTrue(repo.savedAccounts.value.isEmpty())
        assertEquals(AuthState.SignedOut, repo.authState.value)

        // Ensure no secret leaked into result or state strings
        val combinedText = "$result ${repo.authState.value}"
        assertFalse(combinedText.contains(rawPassword))
        assertFalse(combinedText.contains(computeLegacyPasswordMd5(rawPassword)))
    }

    @Test
    fun login_alreadyLoggedInSamplePage_sameUserPersistsImmediatelyWithoutPosting() = runBlocking {
        val repo = createRepository()
        val realLoginSample = LegacyTestSupport.loadSample("login/login_page.html")
        server.enqueue(gbkHtmlResponse(realLoginSample))

        val result = repo.login(username = "hiuser", passwordOrMd5 = "SecretPass123")

        assertTrue("Expected Success but got $result", result is LoginResult.Success)
        val account = (result as LoginResult.Success).account
        assertEquals("10000", account.uid)
        assertEquals("HiUser", account.username)
        assertEquals(1, server.requestCount)
        assertEquals(AuthState.SignedIn(account), repo.authState.value)
    }

    @Test
    fun login_alreadyLoggedInAsDifferentUser_clearsAuthCookiesKeepsCfClearanceAndLogsInTarget() = runBlocking {
        val forumUrl = server.url("/forum/")
        val host = forumUrl.host
        cookieStore.saveFromResponse(
            forumUrl,
            listOf(
                Cookie.Builder().name("cdb_auth").value("old_user_token").hostOnlyDomain(host).path("/").build(),
                Cookie.Builder().name("cf_clearance").value("cf_keep_me").hostOnlyDomain(host).path("/").build(),
            ),
        )

        val repo = createRepository()
        // First GET sees old user HiUser (uid=10000)
        server.enqueue(gbkHtmlResponse(LegacyTestSupport.loadSample("login/login_page.html")))
        // After clearing cdb_* while keeping cf_clearance, second GET returns login form
        server.enqueue(gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_second")))
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，SecondUser]]></root>""",
                headers = listOf("Set-Cookie" to "cdb_auth=second_user_token; Path=/"),
            ),
        )
        server.enqueue(gbkHtmlResponse(AuthTestFixtures.loggedInPageHtml("20002", "SecondUser")))

        val result = repo.login(username = "SecondUser", passwordOrMd5 = "pass2")

        assertTrue("Expected Success but got $result", result is LoginResult.Success)
        val account = (result as LoginResult.Success).account
        assertEquals("20002", account.uid)
        assertEquals("SecondUser", account.username)

        server.takeRequest() // 1st GET saw old session
        val retryGet = server.takeRequest() // 2nd GET must have cf_clearance and NO old cdb_auth
        val retryCookieHeader = retryGet.headers["Cookie"].orEmpty()
        assertFalse("Old cdb_auth must be cleared before re-fetching login form", retryCookieHeader.contains("old_user_token"))
        assertTrue("cf_clearance must be preserved", retryCookieHeader.contains("cf_clearance=cf_keep_me"))
    }

    @Test
    fun login_formhashMissing_returnsFailureAndDoesNotSubmitPost() = runBlocking {
        val repo = createRepository()
        server.enqueue(
            gbkHtmlResponse(
                """<html><body><div class="alert_info"><p>密码错误次数过多，请 15 分钟后再试</p></div></body></html>""",
            ),
        )

        val result = repo.login(username = "HiUser", passwordOrMd5 = "AnyPass")

        assertTrue("Expected Failure but got $result", result is LoginResult.Failure)
        assertTrue((result as LoginResult.Failure).message.contains("15 分钟"))
        assertEquals(1, server.requestCount)
        assertNull(repo.currentAccount.value)
    }

    @Test
    fun switchAccount_clearsOldAuthCookiesPreservingCfClearanceAndUpdatesActiveAccount() = runBlocking {
        val accountA = SavedAccount(
            uid = "10001",
            username = "Alice",
            passwordMd5 = computeLegacyPasswordMd5("alice_pw"),
            lastLoginAt = 1_000L,
        )
        val accountB = SavedAccount(
            uid = "10002",
            username = "Bob",
            passwordMd5 = computeLegacyPasswordMd5("bob_pw"),
            securityQuestionId = 2,
            securityAnswer = "blue",
            lastLoginAt = 900L,
        )
        credentialsStore.upsert(accountA)
        credentialsStore.upsert(accountB)
        credentialsStore.setActive(accountA.uid)

        // Seed host-only cdb_auth, sub-path cdb_sid, cf_clearance, and unrelated host cookie.
        val forumUrl = "https://www.4d4y.com/forum/".toHttpUrl()
        val otherUrl = "https://img02.4d4y.com/forum/".toHttpUrl()
        cookieStore.saveFromResponse(
            forumUrl,
            listOf(
                Cookie.Builder().name("cdb_auth").value("alice_auth_secret").hostOnlyDomain("www.4d4y.com").path("/").build(),
                Cookie.Builder().name("cdb_sid").value("alice_sid").domain("4d4y.com").path("/forum").build(),
                Cookie.Builder().name("cf_clearance").value("cf_token_4d4y").domain("4d4y.com").path("/").build(),
            ),
        )
        cookieStore.saveFromResponse(
            otherUrl,
            listOf(
                Cookie.Builder().name("img_pref").value("1").hostOnlyDomain("img02.4d4y.com").path("/").build(),
            ),
        )
        // Also seed cookies for MockWebServer host so network requests verify real headers
        val mockForumUrl = server.url("/forum/")
        val mockHost = mockForumUrl.host
        cookieStore.saveFromResponse(
            mockForumUrl,
            listOf(
                Cookie.Builder().name("cdb_auth").value("alice_mock_auth").hostOnlyDomain(mockHost).path("/").build(),
                Cookie.Builder().name("cdb_sid").value("alice_mock_sid").hostOnlyDomain(mockHost).path("/forum").build(),
                Cookie.Builder().name("cf_clearance").value("cf_mock_keep").hostOnlyDomain(mockHost).path("/").build(),
            ),
        )

        val repo = createRepository()
        nowMs.set(2_500_000L)

        server.enqueue(gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_switch_b")))
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Bob]]></root>""",
                headers = listOf("Set-Cookie" to "cdb_auth=bob_new_auth; Path=/"),
            ),
        )

        val result = repo.switchAccount(accountB.uid)

        assertTrue("Expected Success but got $result", result is LoginResult.Success)
        val switched = (result as LoginResult.Success).account
        assertEquals(accountB.uid, switched.uid)
        assertEquals(2_500_000L, switched.lastLoginAt)
        assertEquals(switched, repo.currentAccount.value)
        assertEquals(AuthState.SignedIn(switched), repo.authState.value)
        assertEquals(accountB.uid, credentialsStore.currentActiveUid())

        val getReq = server.takeRequest()
        val getCookies = getReq.headers["Cookie"].orEmpty()
        assertFalse("Old cdb_auth must be cleared", getCookies.contains("alice_mock_auth"))
        assertFalse("Old cdb_sid must be cleared", getCookies.contains("alice_mock_sid"))
        assertTrue("cf_clearance must be preserved", getCookies.contains("cf_clearance=cf_mock_keep"))

        val postReq = server.takeRequest()
        val postedFields = parseGbkForm(postReq.body?.utf8().orEmpty())
        assertEquals("Bob", postedFields["username"])
        assertEquals(accountB.passwordMd5, postedFields["password"])
        assertEquals("2", postedFields["questionid"])
        assertEquals("blue", postedFields["answer"])

        val remainingMockCookies = cookieStore.loadForRequest(mockForumUrl)
        assertTrue(remainingMockCookies.any { it.name == "cf_clearance" && it.value == "cf_mock_keep" })
        assertTrue(remainingMockCookies.any { it.name == "cdb_auth" && it.value == "bob_new_auth" })
        // Unrelated host cookies untouched
        assertTrue(cookieStore.loadForRequest(otherUrl).any { it.name == "img_pref" })

        // Verify domain-scoped cleanup on www.4d4y.com directly via settings switch + logout
        settingsRepository.setForumServer("https://www.4d4y.com")
        repo.logout()
        val remaining4d4y = cookieStore.loadForRequest(forumUrl)
        assertFalse(remaining4d4y.any { it.name == "cdb_auth" })
        assertFalse(remaining4d4y.any { it.name == "cdb_sid" })
        assertTrue("Domain cf_clearance on .4d4y.com must survive", remaining4d4y.any { it.name == "cf_clearance" && it.value == "cf_token_4d4y" })
        assertTrue(cookieStore.loadForRequest(otherUrl).any { it.name == "img_pref" })
    }

    @Test
    fun switchAccount_failure_doesNotActivateTargetAccountAndFallsBackCleanly() = runBlocking {
        val accountA = SavedAccount(
            uid = "10001",
            username = "Alice",
            passwordMd5 = computeLegacyPasswordMd5("alice_pw"),
            lastLoginAt = 1_000L,
        )
        val accountB = SavedAccount(
            uid = "10002",
            username = "Bob",
            passwordMd5 = computeLegacyPasswordMd5("bob_wrong_pw"),
            lastLoginAt = 900L,
        )
        credentialsStore.upsert(accountA)
        credentialsStore.upsert(accountB)
        credentialsStore.setActive(accountA.uid)

        val mockForumUrl = server.url("/forum/")
        val mockHost = mockForumUrl.host
        cookieStore.saveFromResponse(
            mockForumUrl,
            listOf(
                Cookie.Builder().name("cdb_auth").value("alice_auth").hostOnlyDomain(mockHost).path("/").build(),
                Cookie.Builder().name("cf_clearance").value("cf_keep_on_fail").hostOnlyDomain(mockHost).path("/").build(),
            ),
        )

        val repo = createRepository()
        server.enqueue(gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_fail_b")))
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[登录失败，您还可以尝试 3 次]]></root>""",
            ),
        )

        val result = repo.switchAccount(accountB.uid)

        assertTrue("Expected InvalidCredentials but got $result", result is LoginResult.InvalidCredentials)
        assertEquals(accountA.uid, credentialsStore.currentActiveUid())
        assertEquals(accountA.uid, repo.currentAccount.value?.uid)
        assertTrue("State should fall back to SessionExpired(accountA), got ${repo.authState.value}", repo.authState.value is AuthState.SessionExpired)
        assertEquals(accountA.uid, (repo.authState.value as AuthState.SessionExpired).account.uid)

        val cookiesAfterFail = cookieStore.loadForRequest(mockForumUrl)
        assertFalse(cookiesAfterFail.any { it.name == "cdb_auth" })
        assertTrue(cookiesAfterFail.any { it.name == "cf_clearance" && it.value == "cf_keep_on_fail" })
    }

    @Test
    fun switchAccount_unknownUid_returnsFailureWithoutClearingExistingCookies() = runBlocking {
        val accountA = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("pw"), lastLoginAt = 1_000L)
        credentialsStore.upsert(accountA)
        credentialsStore.setActive(accountA.uid)

        val mockForumUrl = server.url("/forum/")
        cookieStore.saveFromResponse(
            mockForumUrl,
            listOf(Cookie.Builder().name("cdb_auth").value("keep_alice").hostOnlyDomain(mockForumUrl.host).path("/").build()),
        )

        val repo = createRepository()
        val result = repo.switchAccount("99999")

        assertTrue(result is LoginResult.Failure)
        assertEquals(0, server.requestCount)
        assertEquals(accountA.uid, repo.currentAccount.value?.uid)
        assertTrue(cookieStore.loadForRequest(mockForumUrl).any { it.name == "cdb_auth" && it.value == "keep_alice" })
    }

    @Test
    fun logout_setsManuallyLoggedOutPreservesSavedAccountsAndSuppressesSilentReloginWithZeroRequests() = runBlocking {
        val accountA = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("pwA"), lastLoginAt = 1_000L)
        credentialsStore.upsert(accountA)
        credentialsStore.setActive(accountA.uid)

        val mockForumUrl = server.url("/forum/")
        cookieStore.saveFromResponse(
            mockForumUrl,
            listOf(
                Cookie.Builder().name("cdb_auth").value("alice_secret_cookie").hostOnlyDomain(mockForumUrl.host).path("/").build(),
                Cookie.Builder().name("cf_clearance").value("cf_survives_logout").hostOnlyDomain(mockForumUrl.host).path("/").build(),
            ),
        )

        val repo = createRepository()
        val epochBeforeLogout = repo.sessionEpoch

        repo.logout()

        assertTrue(credentialsStore.currentManuallyLoggedOut())
        assertNull(repo.currentAccount.value)
        assertEquals(listOf(accountA), repo.savedAccounts.value)
        assertEquals(AuthState.SignedOut, repo.authState.value)

        val remainingCookies = cookieStore.loadForRequest(mockForumUrl)
        assertFalse(remainingCookies.any { it.name == "cdb_auth" })
        assertTrue(remainingCookies.any { it.name == "cf_clearance" && it.value == "cf_survives_logout" })

        // Even with an older failedEpoch, logout must NOT return Recovered and must issue 0 network requests.
        val silentResult = repo.trySilentRelogin(failedEpoch = epochBeforeLogout)
        assertEquals(SilentReloginResult.Suppressed, silentResult)
        assertEquals(0, server.requestCount)

        // Explicit switchAccount clears manuallyLoggedOut and re-enables silent relogin
        server.enqueue(gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_reenter")))
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Alice]]></root>""",
                headers = listOf("Set-Cookie" to "cdb_auth=alice_reentered; Path=/"),
            ),
        )
        val switchResult = repo.switchAccount(accountA.uid)
        assertTrue(switchResult is LoginResult.Success)
        assertFalse(credentialsStore.currentManuallyLoggedOut())

        // Now silent relogin works again for the current epoch
        val activeEpoch = repo.sessionEpoch
        server.enqueue(gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_silent_ok")))
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Alice]]></root>""",
                headers = listOf("Set-Cookie" to "cdb_auth=alice_refreshed; Path=/"),
            ),
        )
        val reloginAfterSwitch = repo.trySilentRelogin(failedEpoch = activeEpoch)
        assertTrue(reloginAfterSwitch is SilentReloginResult.Recovered)
        assertTrue((reloginAfterSwitch as SilentReloginResult.Recovered).newSessionEpoch > activeEpoch)
    }

    @Test
    fun removeAccount_clearsCookiesOnlyWhenRemovingActiveAccount() = runBlocking {
        val accountA = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("pwA"), lastLoginAt = 2_000L)
        val accountB = SavedAccount("10002", "Bob", computeLegacyPasswordMd5("pwB"), lastLoginAt = 1_000L)
        credentialsStore.upsert(accountA)
        credentialsStore.upsert(accountB)
        credentialsStore.setActive(accountA.uid)

        val mockForumUrl = server.url("/forum/")
        cookieStore.saveFromResponse(
            mockForumUrl,
            listOf(
                Cookie.Builder().name("cdb_auth").value("alice_live").hostOnlyDomain(mockForumUrl.host).path("/").build(),
                Cookie.Builder().name("cf_clearance").value("cf_alive").hostOnlyDomain(mockForumUrl.host).path("/").build(),
            ),
        )

        val repo = createRepository()

        // Removing inactive Bob keeps Alice signed in with her cookies intact
        repo.removeAccount(accountB.uid)
        assertEquals(listOf(accountA), repo.savedAccounts.value)
        assertEquals(accountA, repo.currentAccount.value)
        assertEquals(AuthState.SignedIn(accountA), repo.authState.value)
        assertTrue(cookieStore.loadForRequest(mockForumUrl).any { it.name == "cdb_auth" && it.value == "alice_live" })

        // Removing active Alice clears auth cookie (preserving cf_clearance) and transitions to SignedOut
        repo.removeAccount(accountA.uid)
        assertTrue(repo.savedAccounts.value.isEmpty())
        assertNull(repo.currentAccount.value)
        assertEquals(AuthState.SignedOut, repo.authState.value)
        assertFalse(cookieStore.loadForRequest(mockForumUrl).any { it.name == "cdb_auth" })
        assertTrue(cookieStore.loadForRequest(mockForumUrl).any { it.name == "cf_clearance" })

        // Silent relogin with no active account returns NotLoggedIn with 0 network calls
        assertEquals(SilentReloginResult.NotLoggedIn, repo.trySilentRelogin(failedEpoch = repo.sessionEpoch))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun trySilentRelogin_tenConcurrentCallersCoalesceToSingleLoginAndSingleWaiterCancelDoesNotAbortFlight() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("pwA"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val getEntered = CountDownLatch(1)
        val releaseGet = CountDownLatch(1)
        val getCount = AtomicInteger(0)
        val postCount = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.target) {
                    "/forum/logging.php?action=login" -> {
                        getCount.incrementAndGet()
                        getEntered.countDown()
                        releaseGet.await(5, TimeUnit.SECONDS)
                        gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_single_flight"))
                    }

                    "/forum/logging.php?action=login&loginsubmit=yes&inajax=1" -> {
                        postCount.incrementAndGet()
                        gbkXmlResponse(
                            """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Alice]]></root>""",
                            headers = listOf("Set-Cookie" to "cdb_auth=refreshed_token; Path=/"),
                        )
                    }

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val repo = createRepository()
        val failedEpoch = repo.sessionEpoch

        // Launch first caller (flight initiator) and wait until HTTP GET actually hits server
        val initiatorJob = async(Dispatchers.Default) {
            repo.trySilentRelogin(failedEpoch = failedEpoch)
        }
        assertTrue("Relogin GET did not reach server", getEntered.await(5, TimeUnit.SECONDS))
        assertTrue(repo.authState.value is AuthState.Reauthenticating)

        // Launch 9 additional concurrent callers that coalesce onto the same flight
        val otherJobs = (1..9).map {
            async(Dispatchers.Default) {
                repo.trySilentRelogin(failedEpoch = failedEpoch)
            }
        }

        // Cancel ONLY the first caller (the initiator!); the in-flight relogin and 9 waiters MUST succeed
        initiatorJob.cancelAndJoin()
        assertTrue(initiatorJob.isCancelled)

        // Release server response
        releaseGet.countDown()

        val results = withTimeout(5.seconds) { otherJobs.awaitAll() }
        assertEquals(9, results.size)
        val recoveredEpochs = results.map { result ->
            assertTrue("Expected Recovered but got $result", result is SilentReloginResult.Recovered)
            (result as SilentReloginResult.Recovered).newSessionEpoch
        }.toSet()
        assertEquals(1, recoveredEpochs.size)
        val newEpoch = recoveredEpochs.single()
        assertTrue(newEpoch > failedEpoch)

        assertEquals("Coalesced 10 callers must issue exactly 1 GET", 1, getCount.get())
        assertEquals("Coalesced 10 callers must issue exactly 1 POST", 1, postCount.get())
        assertEquals(AuthState.SignedIn(account), repo.authState.value)

        // Subsequent late caller with stale failedEpoch immediately recovers without hitting network
        val lateResult = repo.trySilentRelogin(failedEpoch = failedEpoch)
        assertEquals(SilentReloginResult.Recovered(newEpoch), lateResult)
        assertEquals(1, getCount.get())
        assertEquals(1, postCount.get())
    }

    @Test
    fun trySilentRelogin_runsOnRecoveryDispatcherThreadEvenWhenPrimaryDispatcherIsCompletelySaturated() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("pwA"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val recoveryCallThreads = CopyOnWriteArrayList<Thread>()
        val primaryClient = createPrimaryClient(maxRequests = 1, maxRequestsPerHost = 1)
        val recoveryClient = HttpClientModule.provideRecoveryOkHttpClient(primaryClient)
            .newBuilder()
            .addInterceptor { chain ->
                recoveryCallThreads.add(Thread.currentThread())
                chain.proceed(chain.request())
            }
            .build()
            .also(clientsToClose::add)

        val primaryEnteredStall = CountDownLatch(1)
        val releaseStall = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/stall" -> {
                    primaryEnteredStall.countDown()
                    releaseStall.await(5, TimeUnit.SECONDS)
                    MockResponse.Builder().code(200).body("stall-done").build()
                }

                "/forum/logging.php?action=login" -> gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_iso"))
                "/forum/logging.php?action=login&loginsubmit=yes&inajax=1" -> gbkXmlResponse(
                    """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Alice]]></root>""",
                    headers = listOf("Set-Cookie" to "cdb_auth=recovered_isolated; Path=/"),
                )

                else -> MockResponse.Builder().code(404).build()
            }
        }

        val repo = createRepository(primaryClient = primaryClient, recoveryClient = recoveryClient)

        val noopCallback = object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        }
        val stalledCall = primaryClient.newCall(Request.Builder().url(server.url("/stall")).build())
        stalledCall.enqueue(noopCallback)
        assertTrue("Primary stall call did not reach server", primaryEnteredStall.await(5, TimeUnit.SECONDS))

        // Queue a second call to confirm primary dispatcher is completely saturated
        val queuedPrimary = primaryClient.newCall(Request.Builder().url(server.url("/stall")).build())
        queuedPrimary.enqueue(noopCallback)
        assertEquals(1, primaryClient.dispatcher.runningCallsCount())
        assertEquals(1, primaryClient.dispatcher.queuedCallsCount())

        try {
            val result = withTimeout(5.seconds) {
                repo.trySilentRelogin(failedEpoch = repo.sessionEpoch)
            }
            assertTrue("Expected Recovered despite saturated primary client, got $result", result is SilentReloginResult.Recovered)
            assertTrue("Expected at least 2 recovery interceptor invocations", recoveryCallThreads.size >= 2)
            // OkHttp temporarily renames dispatcher threads during AsyncCall.run and restores the
            // original pool thread name in finally; wait briefly until restoration completes.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (
                recoveryCallThreads.none { it.name.startsWith("OkHttp Recovery Dispatcher-") } &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(10)
            }
            val threadNames = recoveryCallThreads.map { it.name }
            assertTrue(
                "Expected recovery requests to execute on OkHttp Recovery Dispatcher threads, got $threadNames",
                threadNames.any { it.startsWith("OkHttp Recovery Dispatcher-") },
            )
        } finally {
            stalledCall.cancel()
            queuedPrimary.cancel()
            releaseStall.countDown()
        }
    }

    @Test
    fun trySilentRelogin_backgroundSilentAbandonsImmediatelyOnChallengeWithoutCallingSolver() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("pwA"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val solverCalls = AtomicInteger(0)
        val repo = createRepository(
            solver = ChallengeSolver {
                solverCalls.incrementAndGet()
                SolveResult.Cleared
            },
        )

        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .body("<html><title>Just a moment...</title></html>")
                .build(),
        )

        val result = repo.trySilentRelogin(
            failedEpoch = repo.sessionEpoch,
            priority = AccessPriority.BackgroundSilent,
        )

        assertTrue("Expected Failed but got $result", result is SilentReloginResult.Failed)
        val failed = result as SilentReloginResult.Failed
        assertEquals(SilentReloginFailureReason.CHALLENGE_ABANDONED, failed.reason)
        assertEquals(0, solverCalls.get())
        assertEquals(1, server.requestCount)
        assertTrue(repo.authState.value is AuthState.SessionExpired)
    }

    @Test
    fun trySilentRelogin_foregroundSolvesChallengeOnRecoveryChannelWithoutDeadlock() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("pwA"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val solverCalls = AtomicInteger(0)
        val forumUrl = server.url("/forum/")
        val repo = createRepository(
            solver = ChallengeSolver {
                solverCalls.incrementAndGet()
                cookieStore.saveFromResponse(
                    forumUrl,
                    listOf(Cookie.Builder().name("cf_clearance").value("cf_fresh").hostOnlyDomain(forumUrl.host).path("/").build()),
                )
                SolveResult.Cleared
            },
        )

        // First GET challenged; after solver writes cf_clearance, retry GET returns login form, then POST succeeds
        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .body("challenge")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_after_cf")))
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Alice]]></root>""",
                headers = listOf("Set-Cookie" to "cdb_auth=fresh_after_cf; Path=/"),
            ),
        )

        val result = withTimeout(5.seconds) {
            repo.trySilentRelogin(
                failedEpoch = repo.sessionEpoch,
                priority = AccessPriority.UserBlocking,
            )
        }

        assertTrue("Expected Recovered after solving challenge, got $result", result is SilentReloginResult.Recovered)
        assertEquals(1, solverCalls.get())
        assertEquals(AuthState.SignedIn(account), repo.authState.value)
    }

    @Test
    fun trySilentRelogin_logoutDuringInFlightReloginInvalidatesFlightAndLeavesSignedOut() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("pwA"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val enteredServer = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                enteredServer.countDown()
                releaseServer.await(5, TimeUnit.SECONDS)
                return gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_late"))
            }
        }

        val repo = createRepository()
        val waiter = async(Dispatchers.Default) {
            repo.trySilentRelogin(failedEpoch = repo.sessionEpoch)
        }
        assertTrue(enteredServer.await(5, TimeUnit.SECONDS))

        // User manually logs out while silent relogin is in flight
        repo.logout()
        releaseServer.countDown()

        val result = withTimeout(5.seconds) { waiter.await() }
        assertTrue("Expected Failed(SESSION_INVALIDATED) but got $result", result is SilentReloginResult.Failed)
        assertEquals(SilentReloginFailureReason.SESSION_INVALIDATED, (result as SilentReloginResult.Failed).reason)
        assertEquals(AuthState.SignedOut, repo.authState.value)
        assertNull(repo.currentAccount.value)
        assertTrue(credentialsStore.currentManuallyLoggedOut())
    }

    @Test
    fun trySilentRelogin_invalidCredentialsSetsSessionExpiredAndPreventsRepeatedLockoutAttempts() = runBlocking {
        val secretPw = "ChangedPassword999"
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5(secretPw), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val repo = createRepository()
        server.enqueue(gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_pw_changed")))
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[登录失败，您还可以尝试 4 次]]></root>""",
            ),
        )

        val firstResult = repo.trySilentRelogin(failedEpoch = repo.sessionEpoch)
        assertTrue(firstResult is SilentReloginResult.Failed)
        assertEquals(SilentReloginFailureReason.INVALID_CREDENTIALS, (firstResult as SilentReloginResult.Failed).reason)
        assertTrue(repo.authState.value is AuthState.SessionExpired)
        assertEquals(2, server.requestCount)

        // Second silent attempt with the same rejected credentials does NOT hammer server again
        val secondResult = repo.trySilentRelogin(failedEpoch = repo.sessionEpoch)
        assertTrue(secondResult is SilentReloginResult.Failed)
        assertEquals(SilentReloginFailureReason.INVALID_CREDENTIALS, (secondResult as SilentReloginResult.Failed).reason)
        assertEquals(2, server.requestCount)

        // Ensure no secret leaked
        val dump = "$firstResult $secondResult ${repo.authState.value}"
        assertFalse(dump.contains(secretPw))
        assertFalse(dump.contains(account.passwordMd5))
    }

    @Test
    fun trySilentRelogin_backgroundSilentAbandonsWithZeroRequestsWhenPrimaryGatewayIsSolving() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("pwA"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val solverStarted = CountDownLatch(1)
        val releaseSolver = CompletableDeferred<SolveResult>()
        val primaryClient = createPrimaryClient()
        val recoveryClient = createRecoveryClient(primaryClient)
        val detector = ChallengeDetector()
        val solver = ChallengeSolver {
            solverStarted.countDown()
            releaseSolver.await()
        }
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
        val repo = DefaultAuthRepository(
            credentialsStore = credentialsStore,
            siteCookieStore = cookieStore,
            settingsRepository = settingsRepository,
            siteAccessGateway = primaryGateway,
            recoveryGateway = recoveryGateway,
            clock = { nowMs.get() },
            scope = scope,
        )

        server.enqueue(
            MockResponse.Builder()
                .code(403)
                .addHeader("cf-mitigated", "challenge")
                .body("challenge")
                .build(),
        )
        server.enqueue(gbkHtmlResponse(AuthTestFixtures.loggedInPageHtml("10001", "Alice")))

        val foregroundJob = async(Dispatchers.Default) {
            primaryGateway.execute(
                Request.Builder().url(server.url("/forum/index.php")).build(),
                priority = AccessPriority.UserBlocking,
            ).close()
        }
        assertTrue("Primary gateway did not enter Solving", solverStarted.await(5, TimeUnit.SECONDS))

        val countBeforeBg = server.requestCount
        val bgResult = repo.trySilentRelogin(
            failedEpoch = repo.sessionEpoch,
            priority = AccessPriority.BackgroundSilent,
        )
        assertTrue(bgResult is SilentReloginResult.Failed)
        assertEquals(SilentReloginFailureReason.CHALLENGE_ABANDONED, (bgResult as SilentReloginResult.Failed).reason)
        assertEquals("BackgroundSilent must not issue network requests while primary gateway is solving", countBeforeBg, server.requestCount)

        releaseSolver.complete(SolveResult.Cleared)
        withTimeout(5.seconds) { foregroundJob.await() }
    }

    @Test
    fun trySilentRelogin_networkFailureSetsSessionExpiredAndAllowsRecoveryOnceNetworkReturns() = runBlocking {
        val account = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("pwA"), lastLoginAt = 1_000L)
        credentialsStore.upsert(account)
        credentialsStore.setActive(account.uid)

        val repo = createRepository()
        // Simulate server 500 error first -> SessionExpired
        server.enqueue(MockResponse.Builder().code(500).body("Server Down").build())

        val firstAttempt = repo.trySilentRelogin(failedEpoch = repo.sessionEpoch)
        assertTrue(firstAttempt is SilentReloginResult.Failed)
        assertEquals(SilentReloginFailureReason.SERVER_ERROR, (firstAttempt as SilentReloginResult.Failed).reason)
        assertTrue(repo.authState.value is AuthState.SessionExpired)

        // Once server recovers, silent relogin is NOT locked out (unlike INVALID_CREDENTIALS)
        server.enqueue(gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_recovered")))
        server.enqueue(
            gbkXmlResponse(
                """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Alice]]></root>""",
                headers = listOf("Set-Cookie" to "cdb_auth=back_online; Path=/"),
            ),
        )

        val secondAttempt = repo.trySilentRelogin(failedEpoch = repo.sessionEpoch)
        assertTrue("Expected Recovered once server came back, got $secondAttempt", secondAttempt is SilentReloginResult.Recovered)
        assertEquals(AuthState.SignedIn(account), repo.authState.value)
    }

    @Test
    fun switchAccount_duringInFlightSilentReloginInvalidatesOldFlightAndSwitchesCleanly() = runBlocking {
        val accountA = SavedAccount("10001", "Alice", computeLegacyPasswordMd5("pwA"), lastLoginAt = 2_000L)
        val accountB = SavedAccount("10002", "Bob", computeLegacyPasswordMd5("pwB"), lastLoginAt = 1_000L)
        credentialsStore.upsert(accountA)
        credentialsStore.upsert(accountB)
        credentialsStore.setActive(accountA.uid)

        val firstGetEntered = CountDownLatch(1)
        val releaseFirstGet = CountDownLatch(1)
        val getCounter = AtomicInteger(0)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.target) {
                "/forum/logging.php?action=login" -> {
                    val idx = getCounter.incrementAndGet()
                    if (idx == 1) {
                        firstGetEntered.countDown()
                        releaseFirstGet.await(5, TimeUnit.SECONDS)
                    }
                    gbkHtmlResponse(AuthTestFixtures.loginFormHtml("fh_$idx"))
                }

                "/forum/logging.php?action=login&loginsubmit=yes&inajax=1" -> {
                    gbkXmlResponse(
                        """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，Bob]]></root>""",
                        headers = listOf("Set-Cookie" to "cdb_auth=bob_clean_cookie; Path=/"),
                    )
                }

                else -> MockResponse.Builder().code(404).build()
            }
        }

        val repo = createRepository()
        val staleRelogin = async(Dispatchers.Default) {
            repo.trySilentRelogin(failedEpoch = repo.sessionEpoch)
        }
        assertTrue(firstGetEntered.await(5, TimeUnit.SECONDS))

        try {
            val switchJob = async(Dispatchers.Default) {
                repo.switchAccount(accountB.uid)
            }
            // Wait for switchAccount to deterministically invalidate the stale silent relogin first.
            val reloginRes = withTimeout(5.seconds) { staleRelogin.await() }
            releaseFirstGet.countDown()
            val switchRes = withTimeout(5.seconds) { switchJob.await() }

            assertTrue(reloginRes is SilentReloginResult.Failed)
            assertEquals(SilentReloginFailureReason.SESSION_INVALIDATED, (reloginRes as SilentReloginResult.Failed).reason)
            assertTrue("Expected switchAccount to succeed, got $switchRes", switchRes is LoginResult.Success)
            assertEquals(accountB.uid, repo.currentAccount.value?.uid)
            assertEquals(AuthState.SignedIn((switchRes as LoginResult.Success).account), repo.authState.value)
        } finally {
            releaseFirstGet.countDown()
        }
    }

    @Test
    fun authModule_hasExpectedHiltBindingsForAuthRepositoryAndForumHttpClient() {
        assertTrue(AuthModule::class.java.isAnnotationPresent(Module::class.java))

        val bindAuth = AuthModule::class.java.getDeclaredMethod("bindAuthRepository", DefaultAuthRepository::class.java)
        assertTrue(bindAuth.isAnnotationPresent(Binds::class.java))
        assertTrue(bindAuth.isAnnotationPresent(Singleton::class.java))
        assertEquals(AuthRepository::class.java, bindAuth.returnType)

        val provideClient = AuthModule.Companion::class.java.getDeclaredMethod(
            "provideForumHttpClient",
            SiteAccessGateway::class.java,
            SettingsRepository::class.java,
            AuthRepository::class.java,
        )
        assertTrue(provideClient.isAnnotationPresent(Provides::class.java))
        assertTrue(provideClient.isAnnotationPresent(Singleton::class.java))
        assertEquals(ForumHttpClient::class.java, provideClient.returnType)
    }

    companion object {
        private const val TEST_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

internal object AuthTestFixtures {
    private val GBK: Charset = Charset.forName("GBK")

    fun loginFormHtml(formhash: String): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
        <body>
          <div id="umenu"><a href="logging.php?action=login">登录</a></div>
          <form method="post" action="logging.php?action=login&amp;loginsubmit=yes">
            <input type="hidden" name="formhash" value="$formhash" />
          </form>
        </body>
        </html>
    """.trimIndent()

    fun loggedInPageHtml(uid: String, username: String, bodyText: String = "正文内容"): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
        <body>
          <div id="umenu">
            <cite><a href="space.php?uid=$uid" class="noborder">$username</a></cite>
            <a href="logging.php?action=logout&amp;formhash=deadbeef">退出</a>
          </div>
          <div id="content">$bodyText</div>
        </body>
        </html>
    """.trimIndent()

    fun loggedOutPageHtml(bodyText: String = "您无权进行当前操作"): String = """
        <!DOCTYPE html>
        <html>
        <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
        <body>
          <div id="umenu"><a href="logging.php?action=login">登录</a></div>
          <div class="alert_error">$bodyText</div>
        </body>
        </html>
    """.trimIndent()

    fun gbkBuffer(text: String): Buffer = Buffer().write(text.toByteArray(GBK))
}

internal fun gbkHtmlResponse(
    html: String,
    code: Int = 200,
    headers: List<Pair<String, String>> = emptyList(),
): MockResponse = MockResponse.Builder()
    .code(code)
    .addHeader("Content-Type", "text/html; charset=gbk")
    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
    .body(AuthTestFixtures.gbkBuffer(html))
    .build()

internal fun gbkXmlResponse(
    xml: String,
    code: Int = 200,
    headers: List<Pair<String, String>> = emptyList(),
): MockResponse = MockResponse.Builder()
    .code(code)
    .addHeader("Content-Type", "text/xml; charset=gbk")
    .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
    .body(AuthTestFixtures.gbkBuffer(xml))
    .build()

internal fun parseGbkForm(rawBody: String): Map<String, String> {
    if (rawBody.isBlank()) return emptyMap()
    return rawBody.split('&').associate { pair ->
        val idx = pair.indexOf('=')
        if (idx < 0) {
            URLDecoder.decode(pair, "GBK") to ""
        } else {
            URLDecoder.decode(pair.substring(0, idx), "GBK") to
                URLDecoder.decode(pair.substring(idx + 1), "GBK")
        }
    }
}

internal class FakeCredentialsStore : CredentialsStore {
    private data class Snapshot(
        val accounts: List<SavedAccount> = emptyList(),
        val activeUid: String? = null,
        val manuallyLoggedOut: Boolean = false,
    )

    private val state = MutableStateFlow(Snapshot())

    override val accounts: Flow<List<SavedAccount>> = state.map { it.accounts }
    override val activeUid: Flow<String?> = state.map { it.activeUid }
    override val manuallyLoggedOut: Flow<Boolean> = state.map { it.manuallyLoggedOut }

    fun currentActiveUid(): String? = state.value.activeUid
    fun currentManuallyLoggedOut(): Boolean = state.value.manuallyLoggedOut

    override suspend fun upsert(account: SavedAccount) {
        require(account.uid.isNotBlank()) { "Account uid must not be blank" }
        require(account.username.isNotBlank()) { "Account username must not be blank" }
        require(Regex("^[0-9a-fA-F]{32}$").matches(account.passwordMd5)) { "Invalid passwordMd5" }
        val normalized = account.copy(passwordMd5 = account.passwordMd5.lowercase())
        state.update { current ->
            val updated = (current.accounts.filterNot { it.uid == normalized.uid } + normalized)
                .sortedWith(compareByDescending<SavedAccount> { it.lastLoginAt }.thenBy { it.uid })
            current.copy(accounts = updated)
        }
    }

    override suspend fun setActive(uid: String) {
        require(uid.isNotBlank()) { "Account uid must not be blank" }
        state.update { current ->
            require(current.accounts.any { it.uid == uid }) { "Unknown uid: $uid" }
            current.copy(activeUid = uid, manuallyLoggedOut = false)
        }
    }

    override suspend fun markManualLogout() {
        state.update { current ->
            current.copy(activeUid = null, manuallyLoggedOut = true)
        }
    }

    override suspend fun remove(uid: String) {
        if (uid.isBlank()) return
        state.update { current ->
            val remaining = current.accounts.filterNot { it.uid == uid }
            current.copy(
                accounts = remaining,
                activeUid = if (current.activeUid == uid) null else current.activeUid,
            )
        }
    }
}

internal class FakeSettingsRepository(
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
