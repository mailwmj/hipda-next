package net.jejer.hipda.verify

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import net.jejer.hipda.BuildConfig
import net.jejer.hipda.R
import net.jejer.hipda.data.account.CredentialsStore
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRequiredException
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.DefaultAuthRepository
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.LoginDiagnosticLogger
import net.jejer.hipda.data.auth.LoginFailureReason
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginFailureReason
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.auth.computeLegacyPasswordMd5
import net.jejer.hipda.data.network.AppCookieInterceptor
import net.jejer.hipda.data.network.BrowserHeaderInterceptor
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.DefaultUserAgentProvider
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.ManualRedirectInterceptor
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.network.webview.WebViewCookieAccess
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.feature.common.NoOpPaneSnackbarController
import net.jejer.hipda.feature.common.UiErrorMapper
import net.jejer.hipda.feature.login.LoginViewModel
import net.jejer.hipda.feature.settings.AccountManagementViewModel
import net.jejer.hipda.ui.challenge.ChallengeFailureKind
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * M7-5a 独立探针套件（覆盖验证卡探针 2、3、4 及 ErrorMapper/VM 映射；全部离线运行）：
 * - 探针 2：form / submit / verify 各阶段注入失败，严禁出现「认证 Cookie 有效但 SignedOut」，且 cf_clearance / __cf_bm 保留；
 * - 探针 3：登录进行中并发触发 ForumHttpClient 静默重登与切号，断言无死锁、无误清另一账号 Cookie；
 * - 探针 4：含敏感值的多阶段失败响应走真实链路，验证 HxLogin 脱敏及 BuildConfig.DEBUG=false 零输出路径；
 * - UI 映射：验证三种新增失败原因经 LoginViewModel / UiErrorMapper 映射到独立文案，不回落到 common_error_load_failed。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class M7_5aLoginProbesTest {

    private lateinit var nowMs: AtomicLong
    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var credentialsStore: ProbeCredentialsStore
    private lateinit var settingsRepository: ProbeSettingsRepository
    private lateinit var webViewCookieAccess: ProbeWebViewCookieAccess
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private lateinit var cookieDispatcher: ExecutorCoroutineDispatcher
    private lateinit var repoScope: CoroutineScope
    private lateinit var server: MockWebServer
    private lateinit var baseUrl: HttpUrl
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        ShadowLog.clear()
        nowMs = AtomicLong(1_760_000_000_000L)
        cookieStore = InMemorySiteCookieStore(clock = { nowMs.get() })
        credentialsStore = ProbeCredentialsStore()
        webViewCookieAccess = ProbeWebViewCookieAccess(clock = { nowMs.get() })
        mainDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "probe-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        cookieDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "probe-cookie").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server = MockWebServer()
        server.start()
        baseUrl = server.url("")
        settingsRepository = ProbeSettingsRepository(
            AppSettings(forumServer = baseUrl.toString().trimEnd('/')),
        )
    }

    @After
    fun tearDown() {
        repoScope.cancel()
        clientsToClose.forEach { client ->
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        clientsToClose.clear()
        server.close()
        Dispatchers.resetMain()
        mainDispatcher.close()
        cookieDispatcher.close()
        ShadowLog.clear()
    }

    private data class Stack(
        val authRepository: DefaultAuthRepository,
        val forumHttpClient: ForumHttpClient,
        val primaryGateway: SiteAccessGateway,
        val viewModel: LoginViewModel,
    )

    private fun buildStack(
        solver: ChallengeSolver = ChallengeSolver { SolveResult.Cancelled },
        recoverySolver: ChallengeSolver = solver,
        primaryClientOverride: OkHttpClient? = null,
    ): Stack {
        val client = (primaryClientOverride ?: HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = DefaultUserAgentProvider(),
            transportInterceptor = TransportInterceptor(
                delegateFactory = { null },
                isMainThread = { false },
            ),
        )).also(clientsToClose::add)
        val recoveryClient = HttpClientModule.provideRecoveryOkHttpClient(client).also(clientsToClose::add)
        val detector = ChallengeDetector()
        val primaryGateway = SiteAccessGateway(client = client, detector = detector, solver = solver)
        val recoveryGateway = SiteAccessGateway(client = recoveryClient, detector = detector, solver = recoverySolver)
        val repo = DefaultAuthRepository(
            credentialsStore = credentialsStore,
            siteCookieStore = cookieStore,
            settingsRepository = settingsRepository,
            siteAccessGateway = primaryGateway,
            recoveryGateway = recoveryGateway,
            clock = { nowMs.get() },
            scope = repoScope,
            webViewCookieAccess = webViewCookieAccess,
            cookieDispatcher = cookieDispatcher,
        )
        val forumHttpClient = ForumHttpClient(
            siteAccessGateway = primaryGateway,
            settingsRepository = settingsRepository,
            authRepository = repo,
        )
        val viewModel = LoginViewModel(
            authRepository = repo,
            siteAccessGateway = primaryGateway,
        )
        return Stack(repo, forumHttpClient, primaryGateway, viewModel)
    }

    private suspend fun submitViaViewModel(viewModel: LoginViewModel, username: String = "alice", password: String = "pw123456") {
        viewModel.onUsernameChange(username)
        viewModel.onPasswordChange(password)
        viewModel.submit()
        withTimeout(10.seconds) {
            var sawSubmitting = false
            while (true) {
                val state = viewModel.uiState.value
                if (state.isSubmitting) sawSubmitting = true
                if (!state.isSubmitting && (
                        state.loginSuccessEventId > 0L ||
                            state.generalErrorRes != null ||
                            state.fieldError != null ||
                            state.challengeFailure != null ||
                            sawSubmitting
                        )
                ) {
                    break
                }
                delay(15)
            }
        }
    }

    private fun seedCloudflareCookies(
        forumUrl: HttpUrl = baseUrl.newBuilder().encodedPath("/forum/").build(),
        cfValue: String = "cf_clearance_keep",
        bmValue: String = "cf_bm_keep",
        domainScoped: String? = null,
    ) {
        val attr = if (domainScoped != null) "; Domain=$domainScoped" else ""
        val cfSetCookie = "cf_clearance=$cfValue; Path=/$attr"
        val bmSetCookie = "__cf_bm=$bmValue; Path=/$attr"
        val parsed = listOfNotNull(
            Cookie.parse(forumUrl, cfSetCookie),
            Cookie.parse(forumUrl, bmSetCookie),
        )
        cookieStore.saveFromResponse(forumUrl, parsed)
        webViewCookieAccess.setCookie(forumUrl.toString(), cfSetCookie)
        webViewCookieAccess.setCookie(forumUrl.toString(), bmSetCookie)
    }

    private fun seedWebViewAuthCookies(
        forumUrl: HttpUrl = baseUrl.newBuilder().encodedPath("/forum/").build(),
        domainScoped: String? = null,
    ) {
        val attr = if (domainScoped != null) "; Domain=$domainScoped" else ""
        webViewCookieAccess.setCookie(forumUrl.toString(), "cdb_auth=stale_wv_auth; Path=/$attr; HttpOnly")
        webViewCookieAccess.setCookie(forumUrl.toString(), "cdb_sid=stale_wv_sid; Path=/$attr")
    }

    private suspend fun assertNoAuthCookiesAndCloudflareIntact(
        forumUrl: HttpUrl = baseUrl.newBuilder().encodedPath("/forum/").build(),
        expectedStoreCfValue: String = "cf_clearance_keep",
        expectedWebViewCfValue: String = "cf_clearance_keep",
        expectedBmValue: String = "cf_bm_keep",
    ) {
        val storeCookies = cookieStore.snapshot()
        val storeAuth = storeCookies.filterNot {
            it.name.equals("cf_clearance", ignoreCase = true) ||
                it.name.equals("__cf_bm", ignoreCase = true)
        }
        assertTrue("Expected zero non-CF cookies in SiteCookieStore, found: $storeAuth", storeAuth.isEmpty())
        assertEquals(
            expectedStoreCfValue,
            storeCookies.firstOrNull { it.name.equals("cf_clearance", ignoreCase = true) }?.value,
        )
        assertEquals(
            expectedBmValue,
            storeCookies.firstOrNull { it.name.equals("__cf_bm", ignoreCase = true) }?.value,
        )

        val wvAuth = webViewCookieAccess.activeCookiesForHost(forumUrl.host).filterNot {
            it.name.equals("cf_clearance", ignoreCase = true) ||
                it.name.equals("__cf_bm", ignoreCase = true)
        }
        assertTrue("Expected zero non-CF cookies in WebViewCookieAccess, found: $wvAuth", wvAuth.isEmpty())
        assertEquals(
            expectedWebViewCfValue,
            webViewCookieAccess.activeCookiesForHost(forumUrl.host)
                .firstOrNull { it.name.equals("cf_clearance", ignoreCase = true) }?.value,
        )
        assertEquals(
            expectedBmValue,
            webViewCookieAccess.activeCookiesForHost(forumUrl.host)
                .firstOrNull { it.name.equals("__cf_bm", ignoreCase = true) }?.value,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 探针 2：无中间态（form / submit / verify 各阶段注入失败，严禁出现 Cookie 有效但 SignedOut）
    // ---------------------------------------------------------------------------------------------

    @Test
    fun probe2_formStageFailures_preserveCloudflareAndLeaveZeroAuthCookiesAndSignedOut() = runBlocking {
        for (getCookieInfoMode in listOf(true, false)) {
            webViewCookieAccess.getCookieInfoSupported = getCookieInfoMode
            val failureModes = listOf("http500", "ioException", "cloudflare", "formError")
            for (mode in failureModes) {
                cookieStore.clear()
                webViewCookieAccess.reset()
                seedCloudflareCookies()
                seedWebViewAuthCookies()

                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        return when (mode) {
                            "http500" -> MockResponse.Builder()
                                .code(500)
                                .addHeader("Set-Cookie", "cdb_sid=form_err_sid; Path=/")
                                .body("form server error")
                                .build()

                            "ioException" -> MockResponse.Builder()
                                .onResponseStart(SocketEffect.CloseSocket())
                                .build()

                            "cloudflare" -> MockResponse.Builder()
                                .code(403)
                                .addHeader("cf-mitigated", "challenge")
                                .addHeader("Content-Type", "text/html")
                                .body("<title>Just a moment...</title>")
                                .build()

                            else -> MockResponse.Builder()
                                .code(200)
                                .addHeader("Content-Type", "text/html; charset=utf-8")
                                .addHeader("Set-Cookie", "cdb_sid=form_alert_sid; Path=/")
                                .body("<html><body><div class='alert_error'>系统维护中</div></body></html>")
                                .build()
                        }
                    }
                }

                val stack = buildStack(solver = ChallengeSolver { SolveResult.Cancelled })
                submitViaViewModel(stack.viewModel)

                assertEquals("mode=$mode must remain SignedOut", AuthState.SignedOut, stack.authRepository.authState.value)
                assertTrue("mode=$mode must not save credentials", credentialsStore.accounts.first().isEmpty())
                assertNull(credentialsStore.activeUid.first())
                assertNoAuthCookiesAndCloudflareIntact()

                val ui = stack.viewModel.uiState.value
                when (mode) {
                    "http500" -> {
                        assertEquals(R.string.login_error_http_status, ui.generalErrorRes)
                        assertEquals(listOf(500), ui.generalErrorArgs)
                    }
                    "ioException" -> assertEquals(R.string.login_error_network, ui.generalErrorRes)
                    "cloudflare" -> assertEquals(ChallengeFailureKind.Incomplete, ui.challengeFailure)
                    "formError" -> assertEquals(R.string.common_error_load_failed, ui.generalErrorRes)
                }
            }
        }
    }

    @Test
    fun probe2_submitStageFailures_preserveCloudflareAndClearAuthCookies() = runBlocking {
        for (getCookieInfoMode in listOf(true, false)) {
            webViewCookieAccess.getCookieInfoSupported = getCookieInfoMode
            val failureModes = listOf("http502", "ioException", "cloudflare", "unrecognized")
            for (mode in failureModes) {
                cookieStore.clear()
                webViewCookieAccess.reset()
                seedCloudflareCookies()

                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val target = request.target
                        if (request.method == "GET" && target.startsWith("/forum/logging.php?action=login")) {
                            return MockResponse.Builder()
                                .code(200)
                                .addHeader("Content-Type", "text/html; charset=utf-8")
                                .addHeader("Set-Cookie", "cdb_sid=form_stage_sid; Path=/")
                                .body("<form><input type='hidden' name='formhash' value='fh_sub' /></form>")
                                .build()
                        }
                        if (request.method == "POST" && target.startsWith("/forum/logging.php?action=login&loginsubmit=yes")) {
                            return when (mode) {
                                "http502" -> MockResponse.Builder()
                                    .code(502)
                                    .addHeader("Set-Cookie", "cdb_auth=leak_on_502; Path=/")
                                    .body("bad gateway")
                                    .build()

                                "ioException" -> MockResponse.Builder()
                                    .onResponseStart(SocketEffect.CloseSocket())
                                    .build()

                                "cloudflare" -> MockResponse.Builder()
                                    .code(403)
                                    .addHeader("cf-mitigated", "challenge")
                                    .body("<title>Just a moment...</title>")
                                    .build()

                                else -> MockResponse.Builder()
                                    .code(200)
                                    .addHeader("Content-Type", "text/xml; charset=utf-8")
                                    .addHeader("Set-Cookie", "cdb_auth=poison_unrecognized; Path=/; HttpOnly")
                                    .addHeader("Set-Cookie", "cdb_sid=poison_sid; Path=/")
                                    .body("""<?xml version="1.0" encoding="utf-8"?><root><![CDATA[服务器返回未知模板]]></root>""")
                                    .build()
                            }
                        }
                        return MockResponse.Builder().code(404).build()
                    }
                }

                val stack = buildStack(solver = ChallengeSolver { SolveResult.Cancelled })
                submitViaViewModel(stack.viewModel)

                assertEquals("mode=$mode must remain SignedOut", AuthState.SignedOut, stack.authRepository.authState.value)
                assertTrue(credentialsStore.accounts.first().isEmpty())
                assertNoAuthCookiesAndCloudflareIntact()

                val ui = stack.viewModel.uiState.value
                when (mode) {
                    "http502" -> {
                        assertEquals(R.string.login_error_http_status, ui.generalErrorRes)
                        assertEquals(listOf(502), ui.generalErrorArgs)
                    }
                    "ioException" -> assertEquals(R.string.login_error_network, ui.generalErrorRes)
                    "cloudflare" -> assertEquals(ChallengeFailureKind.Incomplete, ui.challengeFailure)
                    "unrecognized" -> assertEquals(R.string.login_error_unrecognized_response, ui.generalErrorRes)
                }
                assertNotEquals(R.string.common_error_load_failed, ui.generalErrorRes)
            }
        }
    }

    @Test
    fun probe2_verifyStageFailures_afterPostSetValidCookies_clearsAllAuthCookiesAndPreservesCloudflare() = runBlocking {
        for (getCookieInfoMode in listOf(true, false)) {
            webViewCookieAccess.getCookieInfoSupported = getCookieInfoMode
            val verifyFailureModes = listOf("http503", "ioException", "cloudflare", "twoTimesNoUmenu")
            for (mode in verifyFailureModes) {
                cookieStore.clear()
                webViewCookieAccess.reset()
                seedCloudflareCookies()

                val verifyAttemptCounter = AtomicInteger(0)
                val verifySawFreshAuthCookie = CopyOnWriteArrayList<Boolean>()

                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val target = request.target
                        return when {
                            request.method == "GET" && target.startsWith("/forum/logging.php?action=login") ->
                                MockResponse.Builder()
                                    .code(200)
                                    .addHeader("Content-Type", "text/html; charset=utf-8")
                                    .body("<form><input type='hidden' name='formhash' value='fh_ver' /></form>")
                                    .build()

                            request.method == "POST" && target.startsWith("/forum/logging.php?action=login&loginsubmit=yes") -> {
                                // 模拟站点成功种下认证 Cookie，且同步刷新了 cf_clearance
                                val forumUrl = baseUrl.newBuilder().encodedPath("/forum/").build()
                                webViewCookieAccess.setCookie(forumUrl.toString(), "cdb_auth=fresh_auth_cookie; Path=/; HttpOnly")
                                webViewCookieAccess.setCookie(forumUrl.toString(), "cdb_sid=fresh_sid_cookie; Path=/")
                                MockResponse.Builder()
                                    .code(200)
                                    .addHeader("Content-Type", "text/xml; charset=utf-8")
                                    .addHeader("Set-Cookie", "cdb_auth=fresh_auth_cookie; Path=/; HttpOnly")
                                    .addHeader("Set-Cookie", "cdb_sid=fresh_sid_cookie; Path=/")
                                    .addHeader("Set-Cookie", "cf_clearance=cf_clearance_updated; Path=/")
                                    .body("""<?xml version="1.0" encoding="utf-8"?><root><![CDATA[欢迎您回来，alice]]></root>""")
                                    .build()
                            }

                            request.method == "GET" && target.startsWith("/forum/index.php") -> {
                                verifyAttemptCounter.incrementAndGet()
                                val reqCookie = request.headers["Cookie"].orEmpty()
                                verifySawFreshAuthCookie.add(reqCookie.contains("cdb_auth=fresh_auth_cookie"))
                                when (mode) {
                                    "http503" -> MockResponse.Builder()
                                        .code(503)
                                        .addHeader("Content-Type", "application/json")
                                        .body("""{"error":"maintenance"}""")
                                        .build()

                                    "ioException" -> MockResponse.Builder()
                                        .onResponseStart(SocketEffect.CloseSocket())
                                        .build()

                                    "cloudflare" -> MockResponse.Builder()
                                        .code(403)
                                        .addHeader("cf-mitigated", "challenge")
                                        .addHeader("Content-Type", "text/html")
                                        .body("<title>Just a moment...</title>")
                                        .build()

                                    else -> MockResponse.Builder()
                                        .code(200)
                                        .addHeader("Content-Type", "text/html; charset=utf-8")
                                        .body("<html><body><div id='guest'>游客界面，无 umenu</div></body></html>")
                                        .build()
                                }
                            }

                            else -> MockResponse.Builder().code(404).build()
                        }
                    }
                }

                val stack = buildStack(solver = ChallengeSolver { SolveResult.Cancelled })
                submitViaViewModel(stack.viewModel)

                // 确认在 verify 请求发出时认证 Cookie 的确已经生效于站点请求上
                assertTrue(
                    "mode=$mode expected verify request to carry fresh auth cookie, saw=$verifySawFreshAuthCookie",
                    verifySawFreshAuthCookie.isNotEmpty() && verifySawFreshAuthCookie.all { it },
                )
                if (mode == "twoTimesNoUmenu") {
                    assertEquals("Must attempt index.php verification twice when auth cookies exist", 2, verifyAttemptCounter.get())
                }

                // 结束时严禁处于「Cookie 有效但 SignedOut」的中间态
                assertEquals(AuthState.SignedOut, stack.authRepository.authState.value)
                assertTrue(credentialsStore.accounts.first().isEmpty())
                assertNoAuthCookiesAndCloudflareIntact(
                    expectedStoreCfValue = "cf_clearance_updated",
                    expectedWebViewCfValue = "cf_clearance_keep",
                )

                val ui = stack.viewModel.uiState.value
                when (mode) {
                    "http503" -> {
                        assertEquals(R.string.login_error_http_status, ui.generalErrorRes)
                        assertEquals(listOf(503), ui.generalErrorArgs)
                    }
                    "ioException" -> assertEquals(R.string.login_error_network, ui.generalErrorRes)
                    "cloudflare" -> assertEquals(ChallengeFailureKind.Incomplete, ui.challengeFailure)
                    "twoTimesNoUmenu" -> assertEquals(R.string.login_error_session_not_confirmed, ui.generalErrorRes)
                }
                assertNotEquals(R.string.common_error_load_failed, ui.generalErrorRes)
            }
        }
    }

    @Test
    fun probe2_verifyRetryRecoveryAndNoCookieShortCircuit_matchSpec() = runBlocking {
        // 1) 首次 index.php 缺 #umenu、第二次带 #umenu 且有认证 Cookie -> 应成功
        val attemptCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                return when {
                    request.method == "GET" && target.startsWith("/forum/logging.php?action=login") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<input type='hidden' name='formhash' value='fh_r1' />")
                            .build()

                    request.method == "POST" && target.startsWith("/forum/logging.php?action=login&loginsubmit=yes") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/xml; charset=utf-8")
                            .addHeader("Set-Cookie", "cdb_auth=retry_auth; Path=/")
                            .body("<root><![CDATA[欢迎您回来，alice]]></root>")
                            .build()

                    request.method == "GET" && target.startsWith("/forum/index.php") -> {
                        val n = attemptCount.incrementAndGet()
                        val body = if (n == 1) {
                            "<html><body>transient guest view</body></html>"
                        } else {
                            "<div id='umenu'><cite><a href='space.php?uid=9001'>alice</a></cite></div>"
                        }
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body(body)
                            .build()
                    }

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        val stack1 = buildStack()
        val res1 = stack1.authRepository.login("alice", "pw123456")
        assertTrue("Expected Success on 2nd verify attempt, got $res1", res1 is LoginResult.Success)
        assertEquals(2, attemptCount.get())
        assertTrue(stack1.authRepository.authState.value is AuthState.SignedIn)

        // 2) POST 成功但站点未种任何认证 Cookie -> verify 只请求 1 次即失败
        cookieStore.clear()
        val noCookieVerifyCount = AtomicInteger(0)
        seedCloudflareCookies()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                return when {
                    request.method == "GET" && target.startsWith("/forum/logging.php?action=login") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<input type='hidden' name='formhash' value='fh_r2' />")
                            .build()

                    request.method == "POST" && target.startsWith("/forum/logging.php?action=login&loginsubmit=yes") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/xml; charset=utf-8")
                            .body("<root><![CDATA[欢迎您回来，alice]]></root>")
                            .build()

                    request.method == "GET" && target.startsWith("/forum/index.php") -> {
                        noCookieVerifyCount.incrementAndGet()
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<html><body>guest</body></html>")
                            .build()
                    }

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        val res2 = stack1.authRepository.login("alice", "pw123456")
        assertTrue(res2 is LoginResult.Failure)
        assertEquals(LoginFailureReason.SessionNotConfirmed, (res2 as LoginResult.Failure).reason)
        assertEquals("Should NOT retry verify GET when no auth cookies exist", 1, noCookieVerifyCount.get())
        assertNoAuthCookiesAndCloudflareIntact()
    }

    @Test
    fun probe2_midFlightCancellationAfterPostCookieSet_clearsAuthCookiesAndPreservesCf() = runBlocking {
        seedCloudflareCookies()
        val verifyStarted = CountDownLatch(1)
        val allowVerifyFinish = CountDownLatch(1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                return when {
                    request.method == "GET" && target.startsWith("/forum/logging.php?action=login") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<input type='hidden' name='formhash' value='fh_cancel' />")
                            .build()

                    request.method == "POST" && target.startsWith("/forum/logging.php?action=login&loginsubmit=yes") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/xml; charset=utf-8")
                            .addHeader("Set-Cookie", "cdb_auth=cancel_auth; Path=/")
                            .addHeader("Set-Cookie", "cdb_sid=cancel_sid; Path=/")
                            .body("<root><![CDATA[欢迎您回来，alice]]></root>")
                            .build()

                    request.method == "GET" && target.startsWith("/forum/index.php") -> {
                        verifyStarted.countDown()
                        allowVerifyFinish.await(5, TimeUnit.SECONDS)
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<div id='umenu'><cite><a href='space.php?uid=111'>alice</a></cite></div>")
                            .build()
                    }

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val stack = buildStack()
        val loginJob = launch(Dispatchers.Default) {
            stack.authRepository.login("alice", "pw123456")
        }
        assertTrue("Verify stage did not start in time", verifyStarted.await(5, TimeUnit.SECONDS))
        // 在 POST 已种下 Cookie、verify 未返回前取消登录协程
        loginJob.cancel()
        withTimeout(5.seconds) { loginJob.join() }
        allowVerifyFinish.countDown()

        assertEquals(AuthState.SignedOut, stack.authRepository.authState.value)
        assertNoAuthCookiesAndCloudflareIntact()
    }

    @Test
    fun probe2_domainScoped4d4yCookieCleanup_expiresDomainCookiesWhilePreservingCfClearance() = runBlocking {
        val domainUrl = "https://www.4d4y.com/forum/".toHttpUrl()
        settingsRepository.setForumServer("https://www.4d4y.com")

        for (getCookieInfoMode in listOf(true, false)) {
            cookieStore.clear()
            webViewCookieAccess.reset()
            webViewCookieAccess.getCookieInfoSupported = getCookieInfoMode

            seedCloudflareCookies(forumUrl = domainUrl, cfValue = "cf_4d4y_keep", bmValue = "bm_4d4y_keep", domainScoped = ".4d4y.com")
            seedWebViewAuthCookies(forumUrl = domainUrl, domainScoped = ".4d4y.com")

            // 完全离线本地拦截器（不发起任何真实网络连接），模拟 www.4d4y.com 域名返回 Domain=.4d4y.com Cookie 且 verify 失败
            val offlineClient = OkHttpClient.Builder()
                .addInterceptor(ManualRedirectInterceptor())
                .addInterceptor(BrowserHeaderInterceptor(DefaultUserAgentProvider()))
                .addInterceptor(AppCookieInterceptor(cookieStore))
                .addInterceptor { chain ->
                    val req = chain.request()
                    val urlStr = req.url.toString()
                    val builder = Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                    when {
                        urlStr.contains("logging.php?action=login") && req.method == "GET" ->
                            builder.code(200).message("OK")
                                .body("<input type='hidden' name='formhash' value='fh_4d4y' />".toResponseBody("text/html; charset=utf-8".toMediaType()))

                        urlStr.contains("loginsubmit=yes") && req.method == "POST" -> {
                            webViewCookieAccess.setCookie(domainUrl.toString(), "cdb_auth=dom_auth; Domain=.4d4y.com; Path=/; Secure; HttpOnly")
                            builder.code(200).message("OK")
                                .addHeader("Set-Cookie", "cdb_auth=dom_auth; Domain=.4d4y.com; Path=/; Secure; HttpOnly")
                                .addHeader("Set-Cookie", "cdb_sid=dom_sid; Domain=.4d4y.com; Path=/")
                                .body("<root><![CDATA[欢迎您回来，alice]]></root>".toResponseBody("text/xml; charset=utf-8".toMediaType()))
                        }

                        else ->
                            builder.code(200).message("OK")
                                .body("<html><body>no umenu</body></html>".toResponseBody("text/html; charset=utf-8".toMediaType()))
                    }.build()
                }
                .build()

            val stack = buildStack(primaryClientOverride = offlineClient)
            val result = stack.authRepository.login("alice", "pw123456")
            assertTrue("Expected SessionNotConfirmed failure, got $result", result is LoginResult.Failure)
            assertEquals(LoginFailureReason.SessionNotConfirmed, (result as LoginResult.Failure).reason)
            assertEquals(AuthState.SignedOut, stack.authRepository.authState.value)
            assertNoAuthCookiesAndCloudflareIntact(
                forumUrl = domainUrl,
                expectedStoreCfValue = "cf_4d4y_keep",
                expectedWebViewCfValue = "cf_4d4y_keep",
                expectedBmValue = "bm_4d4y_keep",
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 探针 3：并发（登录进行中并发触发 ForumHttpClient 静默重登与切号，断言无死锁、无误清另一账号 Cookie）
    // ---------------------------------------------------------------------------------------------

    @Test
    fun probe3a_loginInFlight_concurrentForumHttpClientSilentRelogin_noDeadlockAndNoCookieClobber() = runBlocking {
        val accountA = SavedAccount(
            uid = "1001",
            username = "userA",
            passwordMd5 = computeLegacyPasswordMd5("passA"),
            lastLoginAt = 1_000L,
        )
        credentialsStore.upsert(accountA)
        credentialsStore.setActive("1001")

        val stack = buildStack()
        // 等待 init 观察到初始已登录账号
        withTimeout(5.seconds) {
            while (stack.authRepository.authState.value !is AuthState.SignedIn) delay(10)
        }

        val verifyEntered = CountDownLatch(1)
        val allowVerifyProceed = CountDownLatch(1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                return when {
                    request.method == "GET" && target.startsWith("/forum/logging.php?action=login") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<input type='hidden' name='formhash' value='fh_b' />")
                            .build()

                    request.method == "POST" && target.startsWith("/forum/logging.php?action=login&loginsubmit=yes") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/xml; charset=utf-8")
                            .addHeader("Set-Cookie", "cdb_auth=authB_active; Path=/")
                            .addHeader("Set-Cookie", "cdb_sid=sidB_active; Path=/")
                            .body("<root><![CDATA[欢迎您回来，userB]]></root>")
                            .build()

                    request.method == "GET" && target.startsWith("/forum/index.php") -> {
                        verifyEntered.countDown()
                        allowVerifyProceed.await(5, TimeUnit.SECONDS)
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<div id='umenu'><cite><a href='space.php?uid=2002'>userB</a></cite></div>")
                            .build()
                    }

                    request.method == "GET" && target.startsWith("/forum/forumdisplay.php") ->
                        // 未登录态页面，促使 ForumHttpClient 触发 trySilentRelogin
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<html><body>guest</body></html>")
                            .build()

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val loginDeferred = async(Dispatchers.Default) {
            stack.authRepository.login("userB", "passB")
        }
        assertTrue("userB verify stage did not start", verifyEntered.await(5, TimeUnit.SECONDS))

        // 并发触发 UserBlocking 与 BackgroundSilent 的 ForumHttpClient 请求（会命中 trySilentRelogin）
        val blockingError = withTimeout(5.seconds) {
            try {
                stack.forumHttpClient.getHtml(
                    relativePath = "forumdisplay.php?fid=2",
                    priority = AccessPriority.UserBlocking,
                    requireAuth = true,
                )
                fail("Expected AuthRequiredException while explicit login is in flight")
                null
            } catch (e: AuthRequiredException) {
                e
            }
        }!!
        assertEquals(AuthRequiredReason.RELOGIN_FAILED, blockingError.reason)
        val reloginRes = blockingError.reloginResult as SilentReloginResult.Failed
        assertEquals(SilentReloginFailureReason.SESSION_INVALIDATED, reloginRes.reason)

        val bgError = withTimeout(5.seconds) {
            try {
                stack.forumHttpClient.getHtml(
                    relativePath = "forumdisplay.php?fid=6",
                    priority = AccessPriority.BackgroundSilent,
                    requireAuth = true,
                )
                fail("Expected AuthRequiredException for background request while login is in flight")
                null
            } catch (e: AuthRequiredException) {
                e
            }
        }!!
        assertEquals(AuthRequiredReason.RELOGIN_FAILED, bgError.reason)

        // 登录进行中的 userB Cookie 不应被误清
        assertEquals("authB_active", cookieStore.snapshot().firstOrNull { it.name == "cdb_auth" }?.value)

        allowVerifyProceed.countDown()
        val loginRes = withTimeout(5.seconds) { loginDeferred.await() }
        assertTrue("Expected userB login Success, got $loginRes", loginRes is LoginResult.Success)
        val signedIn = stack.authRepository.authState.value as AuthState.SignedIn
        assertEquals("2002", signedIn.account.uid)
        assertEquals("authB_active", cookieStore.snapshot().firstOrNull { it.name == "cdb_auth" }?.value)
        assertEquals(setOf("1001", "2002"), credentialsStore.accounts.first().map { it.uid }.toSet())
    }

    @Test
    fun probe3b_inFlightSilentRelogin_interruptedByLoginAndSwitch_noDeadlockAndNoStaleCookieWrite() = runBlocking {
        val accountA = SavedAccount(
            uid = "1001",
            username = "userA",
            passwordMd5 = computeLegacyPasswordMd5("passA"),
            lastLoginAt = 1_000L,
        )
        credentialsStore.upsert(accountA)
        credentialsStore.setActive("1001")

        val stack = buildStack()
        withTimeout(5.seconds) {
            while (stack.authRepository.authState.value !is AuthState.SignedIn) delay(10)
        }

        val reloginPostStarted = CountDownLatch(1)
        val allowReloginPostReturn = CountDownLatch(1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                val bodyStr = request.body?.utf8().orEmpty()
                return when {
                    request.method == "GET" && target.startsWith("/forum/forumdisplay.php") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<html><body>expired guest</body></html>")
                            .build()

                    request.method == "GET" && target.startsWith("/forum/logging.php?action=login") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<input type='hidden' name='formhash' value='fh_race' />")
                            .build()

                    request.method == "POST" && target.startsWith("/forum/logging.php?action=login&loginsubmit=yes") -> {
                        if (bodyStr.contains("username=userA")) {
                            reloginPostStarted.countDown()
                            allowReloginPostReturn.await(5, TimeUnit.SECONDS)
                            MockResponse.Builder()
                                .code(200)
                                .addHeader("Content-Type", "text/xml; charset=utf-8")
                                .addHeader("Set-Cookie", "cdb_auth=stale_authA; Path=/")
                                .body("<root><![CDATA[欢迎您回来，userA]]></root>")
                                .build()
                        } else {
                            MockResponse.Builder()
                                .code(200)
                                .addHeader("Content-Type", "text/xml; charset=utf-8")
                                .addHeader("Set-Cookie", "cdb_auth=fresh_authB; Path=/")
                                .body("<root><![CDATA[欢迎您回来，userB]]></root>")
                                .build()
                        }
                    }

                    request.method == "GET" && target.startsWith("/forum/index.php") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<div id='umenu'><cite><a href='space.php?uid=2002'>userB</a></cite></div>")
                            .build()

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        // 先让 ForumHttpClient 触发真正的静默重登并停在 recovery POST
        val forumJob = async(Dispatchers.Default) {
            try {
                stack.forumHttpClient.getHtml("forumdisplay.php?fid=2", requireAuth = true)
                null
            } catch (e: AuthRequiredException) {
                e
            }
        }
        assertTrue("Silent relogin POST did not start", reloginPostStarted.await(5, TimeUnit.SECONDS))

        // 在静默重登仍挂起时并发发起显式登录 userB，并放行滞后的静默重登响应
        val loginJob = async(Dispatchers.Default) {
            stack.authRepository.login("userB", "passB")
        }
        delay(80)
        allowReloginPostReturn.countDown()

        val loginResult = withTimeout(8.seconds) { loginJob.await() }
        val forumResult = withTimeout(8.seconds) { forumJob.await() }

        assertTrue("Expected login userB Success, got $loginResult", loginResult is LoginResult.Success)
        assertNotNull("Stale ForumHttpClient call should be invalidated, not succeed with A", forumResult)
        assertEquals(AuthRequiredReason.RELOGIN_FAILED, forumResult!!.reason)
        assertEquals(
            SilentReloginFailureReason.SESSION_INVALIDATED,
            (forumResult.reloginResult as SilentReloginResult.Failed).reason,
        )
        assertEquals("2002", (stack.authRepository.authState.value as AuthState.SignedIn).account.uid)
        assertEquals(
            "Stale relogin cookie for userA must NOT overwrite userB's auth cookie",
            "fresh_authB",
            cookieStore.snapshot().firstOrNull { it.name == "cdb_auth" }?.value,
        )
    }

    @Test
    fun probe3c_staleForumHttpClientResponseArrivingAfterLogin_cannotOverwriteNewAccountCookies() = runBlocking {
        val accountA = SavedAccount(
            uid = "1001",
            username = "userA",
            passwordMd5 = computeLegacyPasswordMd5("passA"),
            lastLoginAt = 1_000L,
        )
        credentialsStore.upsert(accountA)
        credentialsStore.setActive("1001")

        val stack = buildStack()
        withTimeout(5.seconds) {
            while (stack.authRepository.authState.value !is AuthState.SignedIn) delay(10)
        }

        val staleRequestArrived = CountDownLatch(1)
        val allowStaleResponse = CountDownLatch(1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                return when {
                    request.method == "GET" && target.startsWith("/forum/forumdisplay.php") -> {
                        staleRequestArrived.countDown()
                        allowStaleResponse.await(5, TimeUnit.SECONDS)
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .addHeader("Set-Cookie", "cdb_auth=late_stale_authA; Path=/")
                            .addHeader("Set-Cookie", "cf_clearance=cf_still_allowed; Path=/")
                            .body("<div id='umenu'><cite><a href='space.php?uid=1001'>userA</a></cite></div>")
                            .build()
                    }

                    request.method == "GET" && target.startsWith("/forum/logging.php?action=login") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<input type='hidden' name='formhash' value='fh_b2' />")
                            .build()

                    request.method == "POST" && target.startsWith("/forum/logging.php?action=login&loginsubmit=yes") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/xml; charset=utf-8")
                            .addHeader("Set-Cookie", "cdb_auth=userB_cookie; Path=/")
                            .body("<root><![CDATA[欢迎您回来，userB]]></root>")
                            .build()

                    request.method == "GET" && target.startsWith("/forum/index.php") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<div id='umenu'><cite><a href='space.php?uid=2002'>userB</a></cite></div>")
                            .build()

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val staleFetch = async(Dispatchers.Default) {
            stack.forumHttpClient.getHtml("forumdisplay.php?fid=2", requireAuth = true)
        }
        assertTrue("Stale request did not arrive at server", staleRequestArrived.await(5, TimeUnit.SECONDS))

        // 在旧请求仍未返回时完成 userB 登录
        val loginRes = withTimeout(5.seconds) { stack.authRepository.login("userB", "passB") }
        assertTrue(loginRes is LoginResult.Success)
        assertEquals("userB_cookie", cookieStore.snapshot().firstOrNull { it.name == "cdb_auth" }?.value)

        // 放行旧请求：它携带的 stale cdb_auth 必须被 AuthCookieWriteGuard 拦截，cf_clearance 可保留
        allowStaleResponse.countDown()
        withTimeout(5.seconds) { staleFetch.await() }

        val cookies = cookieStore.snapshot()
        assertEquals(
            "Late ForumHttpClient response must NOT overwrite userB's cdb_auth",
            "userB_cookie",
            cookies.firstOrNull { it.name == "cdb_auth" }?.value,
        )
        assertEquals("cf_still_allowed", cookies.firstOrNull { it.name == "cf_clearance" }?.value)
    }

    @Test
    fun probe3d_loginInFlight_concurrentSwitchAccount_bothFailAndSuccessPaths_noDeadlockAndNoCookieWipe() = runBlocking {
        // 场景 A：login(userB) 进行中停在 verify 且随后失败，同时并发切号 switchAccount(3003) 成功
        // 断言：无死锁，且 login(userB) 的失败清理不会误清随后 switchAccount(3003) 写下的 Cookie！
        val accountC = SavedAccount(
            uid = "3003",
            username = "userC",
            passwordMd5 = computeLegacyPasswordMd5("passC"),
            lastLoginAt = 2_000L,
        )
        credentialsStore.upsert(accountC)
        credentialsStore.markManualLogout()
        seedCloudflareCookies()

        val stack = buildStack()
        val verifyPaused = CountDownLatch(1)
        val allowVerifyResume = CountDownLatch(1)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                val bodyStr = request.body?.utf8().orEmpty()
                return when {
                    request.method == "GET" && target.startsWith("/forum/logging.php?action=login") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<input type='hidden' name='formhash' value='fh_sw' />")
                            .build()

                    request.method == "POST" && target.startsWith("/forum/logging.php?action=login&loginsubmit=yes") -> {
                        if (bodyStr.contains("username=userB")) {
                            MockResponse.Builder()
                                .code(200)
                                .addHeader("Content-Type", "text/xml; charset=utf-8")
                                .addHeader("Set-Cookie", "cdb_auth=temp_authB; Path=/")
                                .body("<root><![CDATA[欢迎您回来，userB]]></root>")
                                .build()
                        } else {
                            MockResponse.Builder()
                                .code(200)
                                .addHeader("Content-Type", "text/xml; charset=utf-8")
                                .addHeader("Set-Cookie", "cdb_auth=authC_ok; Path=/")
                                .addHeader("Set-Cookie", "cdb_sid=sidC_ok; Path=/")
                                .body("<root><![CDATA[欢迎您回来，userC]]></root>")
                                .build()
                        }
                    }

                    request.method == "GET" && target.startsWith("/forum/index.php") -> {
                        verifyPaused.countDown()
                        allowVerifyResume.await(5, TimeUnit.SECONDS)
                        // 故意返回无 #umenu，使 login(userB) 失败并触发 withLoginCookieCleanup
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<html><body>guest</body></html>")
                            .build()
                    }

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val failingLoginJob = async(Dispatchers.Default) { stack.authRepository.login("userB", "passB") }
        assertTrue("userB verify did not pause", verifyPaused.await(5, TimeUnit.SECONDS))

        // 在 login(userB) 仍持有 operationMutex 时并发切号到 userC
        val switchJob = async(Dispatchers.Default) { stack.authRepository.switchAccount("3003") }
        delay(60)
        allowVerifyResume.countDown()

        val (loginRes, switchRes) = withTimeout(8.seconds) {
            listOf(failingLoginJob, switchJob).awaitAll()
        }
        assertTrue("Expected failing login for userB, got $loginRes", loginRes is LoginResult.Failure)
        assertTrue("Expected switchAccount(3003) Success, got $switchRes", switchRes is LoginResult.Success)
        assertEquals("3003", (stack.authRepository.authState.value as AuthState.SignedIn).account.uid)
        assertEquals(
            "login(userB) cleanup must not clear userC's cookies",
            "authC_ok",
            cookieStore.snapshot().firstOrNull { it.name == "cdb_auth" }?.value,
        )
        assertEquals(
            "cf_clearance_keep",
            cookieStore.snapshot().firstOrNull { it.name == "cf_clearance" }?.value,
        )

        // 场景 B：login(userD) 成功进行中 + 并发切号 switchAccount(3003) + 并发切号 switchAccount(1001)
        val accountA = SavedAccount(
            uid = "1001",
            username = "userA",
            passwordMd5 = computeLegacyPasswordMd5("passA"),
            lastLoginAt = 1_000L,
        )
        credentialsStore.upsert(accountA)

        val dVerifyPaused = CountDownLatch(1)
        val dVerifyResume = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                val bodyStr = request.body?.utf8().orEmpty()
                return when {
                    request.method == "GET" && target.startsWith("/forum/logging.php?action=login") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<input type='hidden' name='formhash' value='fh_d' />")
                            .build()

                    request.method == "POST" && target.startsWith("/forum/logging.php?action=login&loginsubmit=yes") -> {
                        val cookie = when {
                            bodyStr.contains("username=userD") -> "authD_ok"
                            bodyStr.contains("username=userA") -> "authA_ok"
                            else -> "authC_2"
                        }
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/xml; charset=utf-8")
                            .addHeader("Set-Cookie", "cdb_auth=$cookie; Path=/")
                            .body("<root><![CDATA[欢迎您回来]]></root>")
                            .build()
                    }

                    request.method == "GET" && target.startsWith("/forum/index.php") -> {
                        dVerifyPaused.countDown()
                        dVerifyResume.await(5, TimeUnit.SECONDS)
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("<div id='umenu'><cite><a href='space.php?uid=4004'>userD</a></cite></div>")
                            .build()
                    }

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val loginD = async(Dispatchers.Default) { stack.authRepository.login("userD", "passD") }
        assertTrue(dVerifyPaused.await(5, TimeUnit.SECONDS))
        val switchA = async(Dispatchers.Default) { stack.authRepository.switchAccount("1001") }
        delay(60)
        dVerifyResume.countDown()

        val dRes = withTimeout(8.seconds) { loginD.await() }
        val aRes = withTimeout(8.seconds) { switchA.await() }
        assertTrue(dRes is LoginResult.Success)
        assertTrue(aRes is LoginResult.Success)
        assertEquals("1001", (stack.authRepository.authState.value as AuthState.SignedIn).account.uid)
        assertEquals("authA_ok", cookieStore.snapshot().firstOrNull { it.name == "cdb_auth" }?.value)
        assertTrue(credentialsStore.accounts.first().any { it.uid == "4004" })
    }

    // ---------------------------------------------------------------------------------------------
    // 探针 4：日志脱敏与 release (BuildConfig.DEBUG=false) 零输出检查
    // ---------------------------------------------------------------------------------------------

    @Test
    fun probe4_debugLogging_redactsAllSensitiveValuesAcrossAllFailureStages() = runBlocking {
        assertTrue("Unit test debug variant must run with BuildConfig.DEBUG=true", BuildConfig.DEBUG)
        ShadowLog.clear()

        val secretUsername = "probeSensitiveUser99"
        val secretPasswordPlain = "MyRawPass!789"
        val secretPasswordMd5 = computeLegacyPasswordMd5(secretPasswordPlain)
        val secretAnswer = "MyMotherSecretCity"
        val secretFormhash = "fhSecret9911"
        val secretMFormhash = "mfhSecret7788"
        val secretInputFh = "fhInput2233"
        val secretRevFh = "fhRev4455"
        val secretSetCookieAuth = "SC_AUTH_TOKEN_XYZ"
        val secretSetCookieSid = "SC_SID_TOKEN_UVW"
        val secretCookieHeaderAuth = "HDR_AUTH_AAA"
        val secretCookieHeaderSid = "HDR_SID_BBB"
        val secretKvAuth = "KV_AUTH_CCC"
        val secretJsonAuth = "JSON_AUTH_DDD"
        val secretUrlEncodedPw = "URL_PW_EEE"
        val secretCtToken = "CT_AUTH_FFF"

        val allForbiddenSecrets = listOf(
            secretUsername,
            secretPasswordPlain,
            secretPasswordMd5,
            secretAnswer,
            secretFormhash,
            secretMFormhash,
            secretInputFh,
            secretRevFh,
            secretSetCookieAuth,
            secretSetCookieSid,
            secretCookieHeaderAuth,
            secretCookieHeaderSid,
            secretKvAuth,
            secretJsonAuth,
            secretUrlEncodedPw,
            secretCtToken,
        )

        val stack = buildStack()

        // 1) form 阶段失败（HTTP 500 预览体含 formhash/Set-Cookie/Cookie/JSON/Content-Type 敏感参数）
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder()
                    .code(500)
                    .addHeader("Content-Type", "text/html; charset=utf-8; cdb_auth=$secretCtToken")
                    .body(
                        """Set-Cookie: cdb_auth=$secretSetCookieAuth; Path=/; HttpOnly
                          |<input type="hidden" name="formhash" value="$secretInputFh" />
                          |<input value="$secretRevFh" name="m_formhash" />
                          |Cookie: cdb_sid=$secretCookieHeaderSid
                          |{"password":"$secretPasswordPlain","answer":"$secretAnswer"}
                        """.trimMargin(),
                    )
                    .build()
        }
        val rFormErr = stack.authRepository.login(secretUsername, secretPasswordPlain, 1, secretAnswer)
        assertTrue(rFormErr is LoginResult.Failure)

        // 2) submit 阶段 UnknownError（响应内嵌 m_formhash / password / answer / username / auth / sid / URL-encoded）
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.method == "GET") {
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "text/html; charset=utf-8")
                        .body("""<input type="hidden" name="formhash" value="$secretFormhash" />""")
                        .build()
                } else {
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "text/xml; charset=utf-8")
                        .body(
                            """<root><![CDATA[err formhash=$secretFormhash m_formhash=$secretMFormhash """ +
                                """password=$secretPasswordMd5 password%3d$secretUrlEncodedPw """ +
                                """answer=$secretAnswer username=$secretUsername """ +
                                """cdb_auth=$secretKvAuth {"cdb_auth":"$secretJsonAuth"} """ +
                                """Set-Cookie: cdb_sid=$secretSetCookieSid]]></root>""",
                        )
                        .build()
                }
            }
        }
        val rSubmitUnknown = stack.authRepository.login(secretUsername, secretPasswordPlain, 1, secretAnswer)
        assertTrue(rSubmitUnknown is LoginResult.Failure)

        // 3) verify 阶段（POST 成功，但 index.php 返回无 #umenu + 含敏感值的 200 页及 502 页）
        val verifyCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                return when {
                    request.method == "GET" && target.startsWith("/forum/logging.php?action=login") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body("""<input name="formhash" value="$secretFormhash" />""")
                            .build()

                    request.method == "POST" && target.startsWith("/forum/logging.php?action=login&loginsubmit=yes") ->
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/xml; charset=utf-8")
                            .addHeader("Set-Cookie", "cdb_auth=$secretSetCookieAuth; Path=/")
                            .body("<root><![CDATA[欢迎您回来，$secretUsername]]></root>")
                            .build()

                    request.method == "GET" && target.startsWith("/forum/index.php") -> {
                        if (verifyCount.incrementAndGet() == 1) {
                            MockResponse.Builder()
                                .code(200)
                                .addHeader("Content-Type", "text/html; charset=utf-8")
                                .body(
                                    """<html><body>Cookie: cdb_auth=$secretCookieHeaderAuth; cdb_sid=$secretCookieHeaderSid """ +
                                        """$secretUsername $secretFormhash $secretAnswer """ +
                                        "X".repeat(800) + "</body></html>",
                                )
                                .build()
                        } else {
                            MockResponse.Builder()
                                .code(502)
                                .addHeader("Content-Type", "text/plain")
                                .body("verify 502 formhash=$secretFormhash cdb_auth=$secretKvAuth")
                                .build()
                        }
                    }

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        val rVerifyErr = stack.authRepository.login(secretUsername, secretPasswordPlain, 1, secretAnswer)
        assertTrue(rVerifyErr is LoginResult.Failure)

        val hxLogs = ShadowLog.getLogsForTag("HxLogin")
        assertTrue("Expected HxLogin logs to be emitted in debug build", hxLogs.isNotEmpty())
        assertTrue(hxLogs.all { it.type == Log.WARN })
        val allMessages = hxLogs.map { it.msg }
        assertTrue("Expected stage=form log, got $allMessages", allMessages.any { it.contains("stage=form ") })
        assertTrue("Expected stage=submit log, got $allMessages", allMessages.any { it.contains("stage=submit ") })
        assertTrue("Expected stage=verify log, got $allMessages", allMessages.any { it.contains("stage=verify ") })

        for (msg in allMessages) {
            for (secret in allForbiddenSecrets) {
                assertFalse(
                    "HxLogin log leaked secret [$secret] in message: $msg",
                    msg.contains(secret, ignoreCase = true),
                )
            }
            val bodyPart = msg.substringAfter("body=", missingDelimiterValue = "")
            assertTrue("Logged body snippet must be <= 400 chars, actual=${bodyPart.length}", bodyPart.length <= 400)
        }
    }

    @Test
    fun probe4_releaseGuard_allHxLoginLoggingStrictlyGatedByBuildConfigDebug() {
        val moduleDir = resolveHipdaModuleDir()
        val loggerFile = File(moduleDir, "src/main/java/net/jejer/hipda/data/auth/LoginDiagnosticLogger.kt")
        assertTrue("Missing LoginDiagnosticLogger.kt at $loggerFile", loggerFile.isFile)
        val loggerSrc = loggerFile.readText()

        // 验证 logWarning 在调用 Log.w / formatLine 前即受 !BuildConfig.DEBUG 阻断
        val logWarningBody = loggerSrc.substringAfter("fun logWarning(").substringBefore("internal fun formatLine(")
        val debugGuardIdx = logWarningBody.indexOf("if (!BuildConfig.DEBUG) return")
        val logWIdx = logWarningBody.indexOf("Log.w(")
        val formatIdx = logWarningBody.indexOf("formatLine(")
        assertTrue("logWarning must check `if (!BuildConfig.DEBUG) return`", debugGuardIdx >= 0)
        assertTrue("logWarning must guard before Log.w and formatLine", debugGuardIdx < logWIdx && debugGuardIdx < formatIdx)

        // 扫描全量 main 源码，确认不存在绕过 BuildConfig.DEBUG 的 HxLogin 日志或直接调用
        val mainSrcDir = File(moduleDir, "src/main")
        val offenders = mutableListOf<String>()
        mainSrcDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                val rel = file.relativeTo(moduleDir).invariantSeparatorsPath
                val text = file.readText()
                if (text.contains("HxLogin") && rel != "src/main/java/net/jejer/hipda/data/auth/LoginDiagnosticLogger.kt") {
                    offenders += "$rel references HxLogin directly"
                }
                if (text.contains("LoginDiagnosticLogger.logWarning")) {
                    // DefaultAuthRepository 中每次调用应同样处于 BuildConfig.DEBUG 守卫下
                    val lines = text.lines()
                    lines.forEachIndexed { idx, line ->
                        if (line.contains("LoginDiagnosticLogger.logWarning")) {
                            val window = lines.subList((idx - 6).coerceAtLeast(0), idx + 1).joinToString("\n")
                            if (!window.contains("BuildConfig.DEBUG")) {
                                offenders += "$rel:${idx + 1} calls LoginDiagnosticLogger.logWarning without nearby BuildConfig.DEBUG check"
                            }
                        }
                    }
                }
            }
        assertTrue("Found unguarded HxLogin logging paths: $offenders", offenders.isEmpty())

        // 补充对 formatLine 本身的边界输入脱敏检查
        val edgeFormatted = LoginDiagnosticLogger.formatLine(
            stage = "submit",
            statusCode = 200,
            contentType = "text/html; formhash=ctFh99",
            body = """Set-Cookie: cdb_auth=edgeAuth1; Path=/\n<input value='edgeFh2' id='m_formhash'/>\n""" +
                """{"username":"edgeUser3","password":"edgePw4","answer":"edgeAns5","my_sid":"edgeSid6"}""",
            knownSecrets = listOf("edgeKnown7"),
        )
        listOf("ctFh99", "edgeAuth1", "edgeFh2", "edgeUser3", "edgePw4", "edgeAns5", "edgeSid6", "edgeKnown7")
            .forEach { secret ->
                assertFalse("formatLine leaked [$secret] in: $edgeFormatted", edgeFormatted.contains(secret, ignoreCase = true))
            }

        // 跨越 400 字符截断边界的敏感值也必须在 4096 窗口内先脱敏再截断，不能在 400 字符处泄露半截密文
        val boundaryFormatted = LoginDiagnosticLogger.formatLine(
            stage = "verify",
            statusCode = 200,
            contentType = "text/html",
            body = "A".repeat(390) + "cdb_auth=BOUNDARY_SECRET_TOKEN_XYZ" + "B".repeat(200),
            knownSecrets = listOf("BOUNDARY_SECRET_TOKEN_XYZ"),
        )
        assertFalse(boundaryFormatted.contains("BOUNDARY", ignoreCase = true))
        assertTrue(boundaryFormatted.substringAfter("body=").length <= 400)
    }

    // ---------------------------------------------------------------------------------------------
    // 探针 5 辅助：LoginViewModel + UiErrorMapper 错误映射与真实字符串格式化端到端校验
    // ---------------------------------------------------------------------------------------------

    @Test
    fun probe5_endToEndFailureMapping_neverFallsBackToLoadFailedForCategorizedReasons() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val stack = buildStack()

        // 1) UnrecognizedSubmitResponse
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "GET") {
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "text/html; charset=utf-8")
                        .body("<input name='formhash' value='fh_m1' />")
                        .build()
                } else {
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "text/xml; charset=utf-8")
                        .body("<root><![CDATA[非预期返回]]></root>")
                        .build()
                }
        }
        submitViaViewModel(stack.viewModel)
        val uiUnrec = stack.viewModel.uiState.value
        assertEquals(R.string.login_error_unrecognized_response, uiUnrec.generalErrorRes)
        assertEquals("登录失败，站点返回了无法识别的结果", context.getString(uiUnrec.generalErrorRes!!))

        // 2) SessionNotConfirmed
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.target.startsWith("/forum/logging.php") ->
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "text/html; charset=utf-8")
                        .body("<input name='formhash' value='fh_m2' />")
                        .build()
                request.method == "POST" ->
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "text/xml; charset=utf-8")
                        .addHeader("Set-Cookie", "cdb_auth=x; Path=/")
                        .body("<root><![CDATA[欢迎您回来，alice]]></root>")
                        .build()
                else ->
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "text/html; charset=utf-8")
                        .body("<html><body>guest</body></html>")
                        .build()
            }
        }
        submitViaViewModel(stack.viewModel)
        val uiSess = stack.viewModel.uiState.value
        assertEquals(R.string.login_error_session_not_confirmed, uiSess.generalErrorRes)
        assertEquals("登录未完成，请重试", context.getString(uiSess.generalErrorRes!!))

        // 3) HttpStatus(502)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "GET") {
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "text/html; charset=utf-8")
                        .body("<input name='formhash' value='fh_m3' />")
                        .build()
                } else {
                    MockResponse.Builder()
                        .code(502)
                        .addHeader("Content-Type", "text/html; charset=utf-8")
                        .body("bad gateway")
                        .build()
                }
        }
        submitViaViewModel(stack.viewModel)
        val uiHttp = stack.viewModel.uiState.value
        assertEquals(R.string.login_error_http_status, uiHttp.generalErrorRes)
        assertEquals(listOf(502), uiHttp.generalErrorArgs)
        assertEquals("登录失败，站点返回错误 502", context.getString(uiHttp.generalErrorRes!!, *uiHttp.generalErrorArgs.toTypedArray()))

        // 4) NetworkError
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
        }
        submitViaViewModel(stack.viewModel)
        val uiNet = stack.viewModel.uiState.value
        assertEquals(R.string.login_error_network, uiNet.generalErrorRes)
        assertEquals("网络连接失败，请重试", context.getString(uiNet.generalErrorRes!!))

        // 也核对 UiErrorMapper 对这三种 FailureReason 的直映射
        assertEquals(
            R.string.login_error_unrecognized_response,
            UiErrorMapper.mapLoginResult(LoginResult.Failure("raw", LoginFailureReason.UnrecognizedSubmitResponse))?.messageRes,
        )
        assertEquals(
            R.string.login_error_session_not_confirmed,
            UiErrorMapper.mapLoginResult(LoginResult.Failure("raw", LoginFailureReason.SessionNotConfirmed))?.messageRes,
        )
        val httpUiError = UiErrorMapper.mapLoginResult(LoginResult.Failure("raw", LoginFailureReason.HttpStatus(504)))
        assertEquals(R.string.login_error_http_status, httpUiError?.messageRes)
        assertEquals(listOf(504), httpUiError?.formatArgs)

        // 验证 AccountManagementViewModel 切号失败也同步携带 errorArgs（如 HttpStatus(502)）
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "GET") {
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "text/html; charset=utf-8")
                        .body("<input name='formhash' value='fh_sw_502' />")
                        .build()
                } else {
                    MockResponse.Builder()
                        .code(502)
                        .addHeader("Content-Type", "text/html; charset=utf-8")
                        .body("bad gateway")
                        .build()
                }
        }
        val targetAcc = SavedAccount(
            uid = "777",
            username = "switchTarget",
            passwordMd5 = computeLegacyPasswordMd5("pw777"),
            lastLoginAt = 10L,
        )
        credentialsStore.upsert(targetAcc)
        withTimeout(5.seconds) {
            while (stack.authRepository.savedAccounts.value.none { it.uid == "777" }) delay(10)
        }
        val accVm = AccountManagementViewModel(stack.authRepository)
        accVm.switchAccount("777", NoOpPaneSnackbarController, "已切换到 %1\$s") {}
        withTimeout(5.seconds) {
            while (accVm.uiState.value.isBusy || accVm.uiState.value.errorRes == null) delay(15)
        }
        assertEquals(R.string.login_error_http_status, accVm.uiState.value.errorRes)
        assertEquals(listOf(502), accVm.uiState.value.errorArgs)
        assertEquals(
            "登录失败，站点返回错误 502",
            context.getString(accVm.uiState.value.errorRes!!, *accVm.uiState.value.errorArgs.toTypedArray()),
        )
    }

    private fun resolveHipdaModuleDir(): File {
        val candidates = listOf(File("."), File("hipda"))
        return candidates.firstOrNull { dir ->
            File(dir, "src/main/java/net/jejer/hipda/data/auth/LoginDiagnosticLogger.kt").isFile
        } ?: error("无法定位 hipda 模块目录（当前工作目录：${File(".").absolutePath}）")
    }

    private class ProbeCredentialsStore : CredentialsStore {
        private val lock = Any()
        private val _accounts = MutableStateFlow<List<SavedAccount>>(emptyList())
        private val _activeUid = MutableStateFlow<String?>(null)
        private val _manuallyLoggedOut = MutableStateFlow(false)

        override val accounts: Flow<List<SavedAccount>> = _accounts.asStateFlow()
        override val activeUid: Flow<String?> = _activeUid.asStateFlow()
        override val manuallyLoggedOut: Flow<Boolean> = _manuallyLoggedOut.asStateFlow()

        override suspend fun upsert(account: SavedAccount) {
            synchronized(lock) {
                val updated = (_accounts.value.filterNot { it.uid == account.uid } + account)
                    .sortedWith(compareByDescending<SavedAccount> { it.lastLoginAt }.thenBy { it.uid })
                _accounts.value = updated
            }
        }

        override suspend fun setActive(uid: String) {
            synchronized(lock) {
                require(_accounts.value.any { it.uid == uid }) { "Unknown uid: $uid" }
                _activeUid.value = uid
                _manuallyLoggedOut.value = false
            }
        }

        override suspend fun markManualLogout() {
            synchronized(lock) {
                _activeUid.value = null
                _manuallyLoggedOut.value = true
            }
        }

        override suspend fun remove(uid: String) {
            synchronized(lock) {
                _accounts.value = _accounts.value.filterNot { it.uid == uid }
                if (_activeUid.value == uid) _activeUid.value = null
            }
        }
    }

    private class ProbeSettingsRepository(initial: AppSettings) : SettingsRepository {
        private val _settings = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = _settings.asStateFlow()

        override suspend fun setThemeMode(themeMode: ThemeMode) { _settings.value = _settings.value.copy(themeMode = themeMode) }
        override suspend fun setPureBlack(enabled: Boolean) { _settings.value = _settings.value.copy(pureBlack = enabled) }
        override suspend fun setThemeSeed(themeSeed: String) { _settings.value = _settings.value.copy(themeSeed = themeSeed) }
        override suspend fun setFontStep(step: Int) { _settings.value = _settings.value.copy(fontStep = step) }
        override suspend fun setLineSpacing(lineSpacing: LineSpacing) { _settings.value = _settings.value.copy(lineSpacing = lineSpacing) }
        override suspend fun setFollowedForums(forumIds: List<Int>) { _settings.value = _settings.value.copy(followedForums = forumIds) }
        override suspend fun setShowStickyThreads(show: Boolean) { _settings.value = _settings.value.copy(showStickyThreads = show) }
        override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) {
            _settings.value = _settings.value.copy(sortByPostTime = _settings.value.sortByPostTime + (fid to sortByPostTime))
        }
        override suspend fun setTailEnabled(enabled: Boolean) { _settings.value = _settings.value.copy(tailEnabled = enabled) }
        override suspend fun setTailText(text: String) { _settings.value = _settings.value.copy(tailText = text) }
        override suspend fun setTailUrl(url: String) { _settings.value = _settings.value.copy(tailUrl = url) }
        override suspend fun setNotificationsEnabled(enabled: Boolean) { _settings.value = _settings.value.copy(notificationsEnabled = enabled) }
        override suspend fun setSilentModeEnabled(enabled: Boolean) { _settings.value = _settings.value.copy(silentModeEnabled = enabled) }
        override suspend fun setSilentBeginMinutes(minutes: Int) { _settings.value = _settings.value.copy(silentBeginMinutes = minutes) }
        override suspend fun setSilentEndMinutes(minutes: Int) { _settings.value = _settings.value.copy(silentEndMinutes = minutes) }
        override suspend fun setForumServer(server: String) { _settings.value = _settings.value.copy(forumServer = server) }
        override suspend fun setImageCacheLimitMb(limitMb: Int) { _settings.value = _settings.value.copy(imageCacheLimitMb = limitMb) }
        override suspend fun setPaneSplitPortrait(split: PaneSplit) { _settings.value = _settings.value.copy(paneSplitPortrait = split) }
        override suspend fun setPaneSplitLandscape(split: PaneSplit) { _settings.value = _settings.value.copy(paneSplitLandscape = split) }
        override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) {
            _settings.value = _settings.value.copy(sidebarCollapsedPortrait = collapsed)
        }
        override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) {
            _settings.value = _settings.value.copy(sidebarCollapsedLandscape = collapsed)
        }
    }

    /** 按 (name, domain, hostOnly, path) 精确建模真实 Android CookieManager 语义（含 domain cookie 不被 host-only 过期头删除的怪癖）。 */
    private class ProbeWebViewCookieAccess(
        private val clock: () -> Long,
    ) : WebViewCookieAccess {

        private data class Key(
            val name: String,
            val domain: String,
            val hostOnly: Boolean,
            val path: String,
        )

        private data class Entry(
            val cookie: Cookie,
            val rawHeader: String,
        )

        private val lock = Any()
        private val entries = LinkedHashMap<Key, Entry>()

        @Volatile
        var getCookieInfoSupported: Boolean = true

        fun reset() = synchronized(lock) { entries.clear() }

        fun activeCookiesForHost(host: String): List<Cookie> = synchronized(lock) {
            val now = clock()
            val canonicalHost = host.lowercase(Locale.ROOT)
            entries.entries.removeAll { it.value.cookie.expiresAt <= now }
            entries.values.map { it.cookie }.filter { it.matchesHost(canonicalHost) }
        }

        override fun isGetCookieInfoSupported(): Boolean = getCookieInfoSupported

        override fun getCookieInfo(url: String): List<String> = synchronized(lock) {
            if (!getCookieInfoSupported) return emptyList()
            val httpUrl = url.toHttpUrl()
            val now = clock()
            entries.entries.removeAll { it.value.cookie.expiresAt <= now }
            entries.values
                .filter { it.cookie.matches(httpUrl) }
                .sortedByDescending { it.cookie.path.length }
                .map { it.rawHeader }
        }

        override fun getCookie(url: String): String? {
            return synchronized(lock) {
                val httpUrl = url.toHttpUrl()
                val now = clock()
                entries.entries.removeAll { it.value.cookie.expiresAt <= now }
                val matching = entries.values
                    .map { it.cookie }
                    .filter { it.matches(httpUrl) }
                    .sortedByDescending { it.path.length }
                if (matching.isEmpty()) null else matching.joinToString("; ") { "${it.name}=${it.value}" }
            }
        }

        override fun setCookie(url: String, value: String) {
            synchronized(lock) {
                val httpUrl = url.toHttpUrl()
                val parsed = Cookie.parse(httpUrl, value) ?: return@synchronized
                val now = clock()
                val key = Key(parsed.name, parsed.domain, parsed.hostOnly, parsed.path)
                if (parsed.expiresAt <= now) {
                    // 仅当 (name, domain, hostOnly, path) 完全匹配且 Secure 要求满足时才删除（模拟真实 WebView CookieManager）
                    val existing = entries[key]
                    if (existing != null && (!existing.cookie.secure || parsed.secure || httpUrl.isHttps)) {
                        entries.remove(key)
                    }
                } else {
                    entries[key] = Entry(parsed, value)
                }
            }
        }

        override fun flush() = Unit

        private fun Cookie.matchesHost(canonicalHost: String): Boolean {
            val cookieDomain = domain.lowercase(Locale.ROOT)
            if (cookieDomain == canonicalHost) return true
            if (hostOnly) return false
            return canonicalHost.endsWith(".$cookieDomain")
        }
    }
}
