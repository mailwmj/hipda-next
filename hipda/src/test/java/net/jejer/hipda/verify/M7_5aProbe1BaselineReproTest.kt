package net.jejer.hipda.verify

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.CredentialsStore
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.DefaultAuthRepository
import net.jejer.hipda.data.auth.computeLegacyPasswordMd5
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.DefaultUserAgentProvider
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.network.webview.WebViewCookieAccess
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.feature.login.LoginViewModel
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 探针 1（真机现象复现，基线 9e98c9f 红 / M7-5a 绿）：
 * 构造「POST 登录成功并种下认证 Cookie」的 4 种真实 Discuz 响应形态，
 * 经 MockWebServer + DefaultAuthRepository + LoginViewModel 走完整登录链路，
 * 仅使用基线 9e98c9f 与 M7-5a 共有的公共 API，保证同一文件可在基线与目标分支无修改对比。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class M7_5aProbe1BaselineReproTest {

    private lateinit var nowMs: AtomicLong
    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var credentialsStore: Probe1CredentialsStore
    private lateinit var settingsRepository: Probe1SettingsRepository
    private lateinit var webViewCookieAccess: Probe1WebViewCookieAccess
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private lateinit var cookieDispatcher: ExecutorCoroutineDispatcher
    private lateinit var repoScope: CoroutineScope
    private lateinit var server: MockWebServer
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        nowMs = AtomicLong(1_760_000_000_000L)
        cookieStore = InMemorySiteCookieStore(clock = { nowMs.get() })
        credentialsStore = Probe1CredentialsStore()
        webViewCookieAccess = Probe1WebViewCookieAccess()
        mainDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "probe1-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        cookieDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "probe1-cookie").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server = MockWebServer()
        server.start()
        settingsRepository = Probe1SettingsRepository(
            AppSettings(forumServer = server.url("").toString().trimEnd('/')),
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
    }

    @Test
    fun probe1_cdataWithScript_completesLoginSignedInAndWritesCredentials() = runBlocking {
        assertLoginFlowSucceeds(
            postResponseBuilder = {
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/xml; charset=utf-8")
                    .addHeader("Set-Cookie", "cdb_sid=sid_cdata_script; Path=/")
                    .addHeader("Set-Cookie", "cdb_auth=auth_cdata_script; Path=/; HttpOnly")
                    .body(
                        """<?xml version="1.0" encoding="utf-8"?>""" +
                            """<root><![CDATA[欢迎您回来，probeUser，现在将转入登录前页面。""" +
                            """<script type="text/javascript" reload="1">setTimeout("window.location.href='index.php'", 3000);</script>""" +
                            """]]></root>""",
                    )
                    .build()
            },
            expectedAuthCookie = "auth_cdata_script",
        )
    }

    @Test
    fun probe1_scriptOnlyInsideCdata_completesLoginSignedInAndWritesCredentials() = runBlocking {
        assertLoginFlowSucceeds(
            postResponseBuilder = {
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/xml; charset=utf-8")
                    .addHeader("Set-Cookie", "cdb_sid=sid_script_only; Path=/")
                    .addHeader("Set-Cookie", "cdb_auth=auth_script_only; Path=/; HttpOnly")
                    .body(
                        """<?xml version="1.0" encoding="utf-8"?>""" +
                            """<root><![CDATA[<script type="text/javascript" reload="1">""" +
                            """succeedhandle_login('https://example.invalid/forum/index.php', '欢迎您回来，probeUser，现在将转入登录前页面。', {});""" +
                            """</script>]]></root>""",
                    )
                    .build()
            },
            expectedAuthCookie = "auth_script_only",
        )
    }

    @Test
    fun probe1_nonXmlHtmlPromptPage_completesLoginSignedInAndWritesCredentials() = runBlocking {
        assertLoginFlowSucceeds(
            postResponseBuilder = {
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .addHeader("Set-Cookie", "cdb_sid=sid_html_prompt; Path=/")
                    .addHeader("Set-Cookie", "cdb_auth=auth_html_prompt; Path=/; HttpOnly")
                    .body(
                        """<!DOCTYPE html>
                        |<html>
                        |<head><meta charset="utf-8"><title>提示信息</title></head>
                        |<body>
                        |<div id="header">HiPDA</div>
                        |<div class="alert_right">
                        |  <p>欢迎您回来，probeUser，现在将转入登录前页面。</p>
                        |  <p class="alert_btnleft"><a href="index.php">如果您的浏览器没有自动跳转，请点击这里</a></p>
                        |</div>
                        |<script type="text/javascript">setTimeout(function(){location.href='index.php';}, 2000);</script>
                        |</body>
                        |</html>
                        """.trimMargin(),
                    )
                    .build()
            },
            expectedAuthCookie = "auth_html_prompt",
        )
    }

    @Test
    fun probe1_gbkBytesWithoutCharsetHeader_completesLoginSignedInAndWritesCredentials() = runBlocking {
        val gbkXml = """<?xml version="1.0" encoding="gbk"?>""" +
            """<root><![CDATA[<script type="text/javascript" reload="1">""" +
            """showDialog('欢迎您回来，测试用户 probeUser，现在将转入登录前页面。');""" +
            """</script>]]></root>"""
        val gbkBytes = gbkXml.toByteArray(Charset.forName("GBK"))

        assertLoginFlowSucceeds(
            postResponseBuilder = {
                MockResponse.Builder()
                    .code(200)
                    // 故意不带 charset=，验证 decodeBody 的 GBK/GB18030 回退链路
                    .addHeader("Content-Type", "text/xml")
                    .addHeader("Set-Cookie", "cdb_sid=sid_gbk; Path=/")
                    .addHeader("Set-Cookie", "cdb_auth=auth_gbk; Path=/; HttpOnly")
                    .body(Buffer().write(gbkBytes))
                    .build()
            },
            expectedAuthCookie = "auth_gbk",
        )
    }

    private suspend fun assertLoginFlowSucceeds(
        postResponseBuilder: () -> MockResponse,
        expectedAuthCookie: String,
    ) {
        val recordedTargets = CopyOnWriteArrayList<String>()
        var capturedPostBody: String? = null
        val indexHtml = """
            <!DOCTYPE html>
            <html>
            <body>
            <div id="umenu">
              <cite><a href="space.php?uid=20480" class="noborder">probeUser</a></cite>
              <a href="logging.php?action=logout&amp;formhash=fh_logout_1">退出</a>
            </div>
            </body>
            </html>
        """.trimIndent()
        val loginFormHtml = """
            <!DOCTYPE html>
            <html>
            <body>
            <form method="post" action="logging.php?action=login&amp;loginsubmit=yes">
              <input type="hidden" name="formhash" value="fh_probe_1" />
            </form>
            </body>
            </html>
        """.trimIndent()
        // 真实站点：已登录状态访问 logging.php?action=login 时未保证返回带 #umenu 的完整页头
        val loggedInLoginPageWithoutUmenu = """
            <!DOCTYPE html>
            <html>
            <body><div class="alert_info">您已经登录，现在将转入首页</div></body>
            </html>
        """.trimIndent()

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target
                recordedTargets.add("${request.method} $target")
                val cookieHeader = request.headers["Cookie"].orEmpty()
                val hasAuthCookie = cookieHeader.contains("cdb_auth=$expectedAuthCookie")
                return when {
                    request.method == "GET" && target.startsWith("/forum/logging.php?action=login") -> {
                        val body = if (hasAuthCookie) loggedInLoginPageWithoutUmenu else loginFormHtml
                        MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/html; charset=utf-8")
                            .body(body)
                            .build()
                    }

                    request.method == "POST" && target.startsWith("/forum/logging.php?action=login&loginsubmit=yes") -> {
                        capturedPostBody = request.body?.string(Charset.forName("GBK"))
                        postResponseBuilder()
                    }

                    request.method == "GET" && target.startsWith("/forum/index.php") -> {
                        if (hasAuthCookie) {
                            MockResponse.Builder()
                                .code(200)
                                .addHeader("Content-Type", "text/html; charset=utf-8")
                                .body(indexHtml)
                                .build()
                        } else {
                            MockResponse.Builder()
                                .code(200)
                                .addHeader("Content-Type", "text/html; charset=utf-8")
                                .body("<html><body>未登录</body></html>")
                                .build()
                        }
                    }

                    else -> MockResponse.Builder().code(404).body("unexpected: ${request.method} $target").build()
                }
            }
        }

        val client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = DefaultUserAgentProvider(),
            transportInterceptor = TransportInterceptor(
                delegateFactory = { null },
                isMainThread = { false },
            ),
        ).also(clientsToClose::add)
        val recoveryClient = HttpClientModule.provideRecoveryOkHttpClient(client).also(clientsToClose::add)
        val detector = ChallengeDetector()
        val gateway = SiteAccessGateway(
            client = client,
            detector = detector,
            solver = ChallengeSolver { SolveResult.Failed(AssertionError("unexpected challenge")) },
        )
        val recoveryGateway = SiteAccessGateway(
            client = recoveryClient,
            detector = detector,
            solver = ChallengeSolver { SolveResult.Failed(AssertionError("unexpected recovery challenge")) },
        )
        val authRepository = DefaultAuthRepository(
            credentialsStore = credentialsStore,
            siteCookieStore = cookieStore,
            settingsRepository = settingsRepository,
            siteAccessGateway = gateway,
            recoveryGateway = recoveryGateway,
            clock = { nowMs.get() },
            scope = repoScope,
            webViewCookieAccess = webViewCookieAccess,
            cookieDispatcher = cookieDispatcher,
        )
        val viewModel = LoginViewModel(
            authRepository = authRepository,
            siteAccessGateway = gateway,
        )

        viewModel.onUsernameChange("probeUser")
        viewModel.onPasswordChange("plainPass123")
        viewModel.onSecurityQuestionSelected(1)
        viewModel.onSecurityAnswerChange("答案中文")
        viewModel.submit()

        withTimeout(10.seconds) {
            var sawSubmitStart = false
            while (true) {
                val state = viewModel.uiState.value
                if (state.isSubmitting) sawSubmitStart = true
                if (!state.isSubmitting && (state.loginSuccessEventId > 0L || state.generalErrorRes != null || state.fieldError != null || state.challengeFailure != null)) {
                    break
                }
                if (sawSubmitStart && !state.isSubmitting) break
                delay(15)
            }
        }

        val uiState = viewModel.uiState.value
        assertFalse("LoginViewModel should finish submitting, state=$uiState, requests=$recordedTargets", uiState.isSubmitting)
        assertNull("Expected no generalErrorRes, got ${uiState.generalErrorRes}, requests=$recordedTargets", uiState.generalErrorRes)
        assertNull("Expected no fieldError, got ${uiState.fieldError}", uiState.fieldError)
        assertNull("Expected no challengeFailure, got ${uiState.challengeFailure}", uiState.challengeFailure)
        assertTrue("Expected loginSuccessEventId > 0, got ${uiState.loginSuccessEventId}", uiState.loginSuccessEventId > 0L)
        assertEquals("", uiState.password)
        assertEquals("", uiState.securityAnswer)

        val authState = authRepository.authState.value
        assertTrue("Expected AuthState.SignedIn, got $authState, requests=$recordedTargets", authState is AuthState.SignedIn)
        val signedIn = authState as AuthState.SignedIn
        assertEquals("20480", signedIn.account.uid)
        assertEquals("probeUser", signedIn.account.username)

        val savedAccounts = credentialsStore.accounts.first()
        assertEquals(1, savedAccounts.size)
        assertEquals("20480", savedAccounts[0].uid)
        assertEquals("probeUser", savedAccounts[0].username)
        assertEquals(computeLegacyPasswordMd5("plainPass123"), savedAccounts[0].passwordMd5)
        assertEquals(1, savedAccounts[0].securityQuestionId)
        assertEquals("答案中文", savedAccounts[0].securityAnswer)
        assertEquals("20480", credentialsStore.activeUid.first())
        assertFalse(credentialsStore.manuallyLoggedOut.first())

        val storedAuth = cookieStore.snapshot().firstOrNull { it.name == "cdb_auth" }
        assertEquals(expectedAuthCookie, storedAuth?.value)

        // 验证 POST 字段与 GBK 编码未被改动
        val postParams = parseFormParams(capturedPostBody ?: "", Charset.forName("GBK"))
        assertEquals("fh_probe_1", postParams["m_formhash"])
        assertEquals("username", postParams["loginfield"])
        assertEquals("probeUser", postParams["username"])
        assertEquals(computeLegacyPasswordMd5("plainPass123"), postParams["password"])
        assertEquals("1", postParams["questionid"])
        assertEquals("答案中文", postParams["answer"])
        assertEquals("2592000", postParams["cookietime"])
    }

    private fun parseFormParams(body: String, charset: Charset): Map<String, String> =
        body.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val idx = pair.indexOf('=')
                val k = if (idx >= 0) pair.substring(0, idx) else pair
                val v = if (idx >= 0) pair.substring(idx + 1) else ""
                URLDecoder.decode(k, charset.name()) to URLDecoder.decode(v, charset.name())
            }

    private class Probe1CredentialsStore : CredentialsStore {
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

    private class Probe1SettingsRepository(initial: AppSettings) : SettingsRepository {
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

    private class Probe1WebViewCookieAccess : WebViewCookieAccess {
        override fun isGetCookieInfoSupported(): Boolean = false
        override fun getCookieInfo(url: String): List<String> = emptyList()
        override fun getCookie(url: String): String? = null
        override fun setCookie(url: String, value: String) = Unit
        override fun flush() = Unit
    }
}
