package net.jejer.hipda.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jejer.hipda.data.account.CredentialsStore
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.*
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.settings.*
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.SingleHostPaneSnackbarController
import net.jejer.hipda.feature.login.LoginOverlayRendererImpl
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.challenge.TestWebViewCookieAccess
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
class AccountManagementTest {

    private val alice = SavedAccount("1001", "alice", "0123456789abcdef0123456789abcdef", lastLoginAt = 2_000L)
    private val bob = SavedAccount("1002", "bob", "fedcba9876543210fedcba9876543210", lastLoginAt = 1_000L)

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun switchAccount_executesImmediatelyWithoutConfirmationAndEmitsSnackbar() = withAnimatorDurationScale(0f) {
        val repo = FakeMultiAuthRepository(active = alice, saved = listOf(alice, bob))
        val vm = AccountManagementViewModel(repo)
        var visible by mutableStateOf(true)

        setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
            HiTheme {
                CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                    Box(Modifier.fillMaxSize()) {
                        if (visible) AccountManagementOverlay(vm, onAddAccount = {}, onDismiss = { visible = false })
                        HiSnackbarHost(snackbarHostState, bottomClearance = 16.dp)
                    }
                }
            }
        }
        waitForIdle()
        assertGlassSurfaceCountAtMost(0)
        onNodeWithText("当前账号").assertIsDisplayed()
        onNodeWithText("bob").assertIsDisplayed().performClick()
        waitForIdle()

        assertEquals(listOf("1002"), repo.switchCalls)
        assertEquals(bob, repo.currentAccount.value)
        assertFalse(visible)
        onNodeWithText("已切换到 bob").assertIsDisplayed()
    }

    @Test
    fun logoutWithoutConfirmationAndClearCredentialsWithConfirmationDialog() = withAnimatorDurationScale(0f) {
        val repo = FakeMultiAuthRepository(active = alice, saved = listOf(alice, bob))
        val vm = AccountManagementViewModel(repo)
        var visible by mutableStateOf(true)

        setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
            HiTheme {
                CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                    Box(Modifier.fillMaxSize()) {
                        if (visible) AccountManagementOverlay(vm, onAddAccount = {}, onDismiss = { visible = false })
                        HiSnackbarHost(snackbarHostState, bottomClearance = 16.dp)
                    }
                }
            }
        }
        waitForIdle()

        onNodeWithText("清除登录信息").performClick()
        waitForIdle()
        onNodeWithText("清除 alice 的保存密码并退出？").assertIsDisplayed()
        onNodeWithText("取消").performClick()
        waitForIdle()
        assertTrue(repo.removeCalls.isEmpty())
        assertEquals(alice, repo.currentAccount.value)

        onNodeWithText("清除登录信息").performClick()
        waitForIdle()
        onNodeWithText("清除").performClick()
        waitForIdle()
        assertEquals(listOf("1001"), repo.removeCalls)
        assertNull(repo.currentAccount.value)
        assertFalse(visible)
        onNodeWithText("已退出登录").assertIsDisplayed()
    }

    @Test
    fun loginOverlayRendererImpl_switchesFromAccountMenuToLoginAndPreservesModeAcrossRotationWithoutLeakingPassword() = withAnimatorDurationScale(0f) {
        val repo = FakeMultiAuthRepository(active = alice, saved = listOf(alice, bob))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cleared }, { 10_000L }, scope)
        val renderer = LoginOverlayRendererImpl(repo, gateway)
        var overlayMode by mutableStateOf<LoginOverlayMode?>(LoginOverlayMode.AccountMenu)
        var mounted by mutableStateOf(true)
        var registry by mutableStateOf(SaveableStateRegistry(null) { true })
        val passwordSentinel = "SynthRotSecret_987!"

        try {
            val ktClass = runCatching { Class.forName("net.jejer.hipda.feature.login.LoginOverlayRendererImplKt") }.getOrNull()
            assertTrue("临时 AccountMenuOverlay 必须已删除", ktClass == null || ktClass.declaredMethods.none { it.name.contains("AccountMenuOverlay") })
            assertTrue(LoginOverlayRendererImpl::class.java.declaredMethods.none { it.name.contains("AccountMenuOverlay") })

            setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                HiTheme {
                    CompositionLocalProvider(LocalPaneSnackbarController provides controller, LocalSaveableStateRegistry provides registry) {
                        Box(Modifier.fillMaxSize()) {
                            if (mounted) {
                                overlayMode?.let { mode ->
                                    renderer.Content(mode, onDismiss = { overlayMode = null }, onLoginSuccess = { overlayMode = null })
                                }
                            }
                            HiSnackbarHost(snackbarHostState, bottomClearance = 16.dp)
                        }
                    }
                }
            }
            waitForIdle()
            onNodeWithText("添加账号").assertIsDisplayed().performClick()
            waitForIdle()
            onNodeWithText("登录 HiPDA").assertIsDisplayed()

            // 输入用户名与密码后模拟旋转重建：应保持在“添加账号”登录页，且保存状态中绝不含密码
            onNode(hasSetTextAction() and hasText("用户名")).performTextInput("synth_new_user")
            onNode(hasSetTextAction() and hasText("密码")).performTextInput(passwordSentinel)
            waitForIdle()

            val saved = registry.performSave()
            assertFalse("SaveableStateRegistry 不得序列化密码", saved.toString().contains(passwordSentinel))
            mounted = false
            waitForIdle()
            registry = SaveableStateRegistry(saved) { true }
            mounted = true
            waitForIdle()

            onNodeWithText("登录 HiPDA").assertIsDisplayed()
            onNodeWithText("synth_new_user").assertIsDisplayed()

            // 返回回账号管理页后点退出登录（无确认框）
            onNodeWithContentDescription("返回").performClick()
            waitForIdle()
            onNodeWithText("退出登录").performClick()
            waitForIdle()
            assertEquals(1, repo.logoutCalls)
            assertNull(overlayMode)
            onNodeWithText("已退出登录").assertIsDisplayed()
        } finally {
            scope.cancel()
        }
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun wideScreen_rendersCenteredModalCardMax480dp() = withAnimatorDurationScale(0f) {
        val repo = FakeMultiAuthRepository(active = alice, saved = listOf(alice, bob))
        val vm = AccountManagementViewModel(repo)

        setContent {
            HiTheme {
                AccountManagementOverlay(vm, onAddAccount = {}, onDismiss = {})
            }
        }
        waitForIdle()
        assertGlassSurfaceCountAtMost(0)
        val modalNode = onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "账号")).fetchSemanticsNode()
        val maxWidthPx = with(density) { 480.dp.toPx() } + 1f
        assertTrue("宽屏账号管理模态卡片宽度不得超过 480dp，实际=${modalNode.size.width}px", modalNode.size.width <= maxWidthPx)
    }

    @Test
    fun defaultAuthRepository_switchClearActiveAccountAndLogoutAgainstMockWebServer() = runBlocking {
        val server = MockWebServer()
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val nowMs = AtomicLong(1_700_000_000_000L)
        // S2：与 HttpClient 共用同一个 SiteCookieStore，并注入真实语义 TestWebViewCookieAccess
        val sharedCookieStore = InMemorySiteCookieStore(clock = { nowMs.get() })
        val webViewCookieAccess = TestWebViewCookieAccess()
        val client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = sharedCookieStore,
            userAgentProvider = UserAgentProvider { "TestUA" },
            transportInterceptor = TransportInterceptor(delegateFactory = { null }, isMainThread = { false }),
        )
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            val forumUrl = "$baseUrl/forum/".toHttpUrl()
            val creds = InMemoryCredentialsStore(listOf(alice, bob), activeUid = alice.uid)
            val settingsRepo = StaticSettingsRepo(AppSettings(forumServer = baseUrl))
            val gateway = SiteAccessGateway(client, ChallengeDetector(), ChallengeSolver { SolveResult.Cleared }, { nowMs.get() }, scope, 5.seconds)
            val authRepo = DefaultAuthRepository(
                credentialsStore = creds,
                siteCookieStore = sharedCookieStore,
                settingsRepository = settingsRepo,
                siteAccessGateway = gateway,
                recoveryGateway = gateway,
                clock = { nowMs.get() },
                scope = scope,
                webViewCookieAccess = webViewCookieAccess,
                cookieDispatcher = Dispatchers.Unconfined,
            )

            val gbk = Charset.forName("GBK")
            fun gbkResp(html: String, setCookies: List<String> = emptyList()) = MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/html; charset=gbk")
                .apply { setCookies.forEach { addHeader("Set-Cookie", it) } }
                .body(Buffer().writeString(html, gbk))
                .build()

            server.enqueue(gbkResp("""<html><body><form name="login"><input name="formhash" value="fh999" /></form></body></html>"""))
            server.enqueue(
                gbkResp(
                    """<html><body><div id="umenu"><cite><a href="space.php?uid=1002">bob</a></cite></div><div class="alert_right">欢迎您回来，bob</div></body></html>""",
                    setCookies = listOf("cdb_auth=SYNTH_BOB_AUTH; Path=/", "cdb_sid=SYNTH_BOB_SID; Path=/"),
                ),
            )

            // 1. 切号到 bob，并在双端写入 cf_clearance + WebView 认证 Cookie
            val switchRes = authRepo.switchAccount(bob.uid)
            assertTrue("切换账号应成功，实际: $switchRes", switchRes is LoginResult.Success)
            assertEquals("1002", authRepo.currentAccount.value?.uid)

            val cfCookie = Cookie.parse(forumUrl, "cf_clearance=SYNTH_CF_KEEP; Path=/; Max-Age=3600")!!
            sharedCookieStore.saveFromResponse(forumUrl, listOf(cfCookie))
            webViewCookieAccess.seedCookie(forumUrl, "cdb_auth=SYNTH_BOB_AUTH; Path=/; Max-Age=3600")
            webViewCookieAccess.seedCookie(forumUrl, "cdb_sid=SYNTH_BOB_SID; Path=/; Max-Age=3600")
            webViewCookieAccess.seedCookie(forumUrl, "cf_clearance=SYNTH_CF_KEEP; Path=/; Max-Age=3600")

            // 2. S2：在 bob 为当前活跃账号时直接执行“清除登录信息”（removeAccount 走 removingActive == true 分支）
            authRepo.removeAccount(bob.uid)
            assertNull("清除当前活跃账号后应回到未登录", authRepo.currentAccount.value)
            assertEquals(AuthState.SignedOut, authRepo.authState.value)
            assertEquals("仅删除目标账号 bob，另一保存账号 alice 必须完好保留", listOf(alice.uid), authRepo.savedAccounts.value.map { it.uid })

            val remainingSiteNames = sharedCookieStore.loadForRequest(forumUrl).map { it.name }.toSet()
            assertFalse("SiteCookieStore 必须清空 cdb_auth", "cdb_auth" in remainingSiteNames)
            assertFalse("SiteCookieStore 必须清空 cdb_sid", "cdb_sid" in remainingSiteNames)
            assertTrue("SiteCookieStore 必须保留 cf_clearance", "cf_clearance" in remainingSiteNames)

            val remainingWebView = webViewCookieAccess.getCookie(forumUrl.toString()).orEmpty()
            assertFalse("WebViewCookieAccess 必须清空 cdb_auth", remainingWebView.contains("cdb_auth"))
            assertFalse("WebViewCookieAccess 必须清空 cdb_sid", remainingWebView.contains("cdb_sid"))
            assertTrue("WebViewCookieAccess 必须保留 cf_clearance", remainingWebView.contains("cf_clearance=SYNTH_CF_KEEP"))
            assertTrue("清理 WebView Cookie 后必须 flush", webViewCookieAccess.flushCount >= 1)

            // 3. 切回 alice 再测“退出登录”：保留已保存凭据并抑制静默重登
            server.enqueue(gbkResp("""<html><body><form name="login"><input name="formhash" value="fh888" /></form></body></html>"""))
            server.enqueue(gbkResp("""<html><body><div id="umenu"><cite><a href="space.php?uid=1001">alice</a></cite></div><div class="alert_right">欢迎您回来，alice</div></body></html>"""))
            assertTrue(authRepo.switchAccount(alice.uid) is LoginResult.Success)
            authRepo.logout()
            assertNull(authRepo.currentAccount.value)
            assertEquals("退出登录必须保留已保存账号 alice", listOf(alice.uid), authRepo.savedAccounts.value.map { it.uid })
            assertEquals(SilentReloginResult.Suppressed, authRepo.trySilentRelogin(authRepo.sessionEpoch))
        } finally {
            scope.cancel()
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.close()
        }
    }

    private class FakeMultiAuthRepository(active: SavedAccount?, saved: List<SavedAccount>) : AuthRepository {
        private val _current = MutableStateFlow(active)
        private val _saved = MutableStateFlow(saved)
        override val currentAccount: StateFlow<SavedAccount?> = _current.asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = _saved.asStateFlow()
        override val authState: StateFlow<AuthState> = MutableStateFlow(if (active != null) AuthState.SignedIn(active) else AuthState.SignedOut).asStateFlow()
        override var sessionEpoch: Long = 1L
        val switchCalls = mutableListOf<String>()
        val removeCalls = mutableListOf<String>()
        var logoutCalls = 0

        override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String): LoginResult = error("unused")
        override suspend fun switchAccount(uid: String): LoginResult {
            switchCalls += uid
            val target = _saved.value.first { it.uid == uid }
            _current.value = target
            return LoginResult.Success(target)
        }
        override suspend fun logout() { logoutCalls++; _current.value = null }
        override suspend fun removeAccount(uid: String) {
            removeCalls += uid
            _saved.update { list -> list.filterNot { it.uid == uid } }
            if (_current.value?.uid == uid) _current.value = null
        }
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority): SilentReloginResult = SilentReloginResult.Suppressed
    }

    private class InMemoryCredentialsStore(initial: List<SavedAccount>, activeUid: String?) : CredentialsStore {
        private val _accounts = MutableStateFlow(initial)
        private val _activeUid = MutableStateFlow(activeUid)
        private val _manual = MutableStateFlow(false)
        override val accounts: Flow<List<SavedAccount>> = _accounts
        override val activeUid: Flow<String?> = _activeUid
        override val manuallyLoggedOut: Flow<Boolean> = _manual
        override suspend fun upsert(account: SavedAccount) { _accounts.update { list -> list.filterNot { it.uid == account.uid } + account } }
        override suspend fun setActive(uid: String) { _activeUid.value = uid; _manual.value = false }
        override suspend fun markManualLogout() { _activeUid.value = null; _manual.value = true }
        override suspend fun remove(uid: String) {
            _accounts.update { list -> list.filterNot { it.uid == uid } }
            if (_activeUid.value == uid) _activeUid.value = null
        }
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
}
