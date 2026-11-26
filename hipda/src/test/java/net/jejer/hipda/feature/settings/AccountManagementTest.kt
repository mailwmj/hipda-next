package net.jejer.hipda.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.SingleHostPaneSnackbarController
import net.jejer.hipda.feature.login.LoginOverlayRendererImpl
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
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

        // 1. 清除登录信息须弹出二次确认框；点“取消”不删凭据
        onNodeWithText("清除登录信息").performClick()
        waitForIdle()
        onNodeWithText("清除 alice 的保存密码并退出？").assertIsDisplayed()
        onNodeWithText("取消").performClick()
        waitForIdle()
        assertTrue(repo.removeCalls.isEmpty())
        assertEquals(alice, repo.currentAccount.value)

        // 2. 再次点“清除登录信息” -> 点“清除”删除保存密码并退出
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
    fun loginOverlayRendererImpl_switchesFromAccountMenuToLoginAndNoTempOverlayRemains() = withAnimatorDurationScale(0f) {
        val repo = FakeMultiAuthRepository(active = alice, saved = listOf(alice, bob))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cleared }, { 10_000L }, scope)
        val renderer = LoginOverlayRendererImpl(repo, gateway)
        var overlayMode by mutableStateOf<LoginOverlayMode?>(LoginOverlayMode.AccountMenu)

        try {
            // 验证临时 AccountMenuOverlay 已彻底删除（文件内不再生成顶层 Kt 辅助类，且渲染器类自身不含临时菜单方法）
            val ktClass = runCatching { Class.forName("net.jejer.hipda.feature.login.LoginOverlayRendererImplKt") }.getOrNull()
            assertTrue(
                "临时 AccountMenuOverlay 必须已删除",
                ktClass == null || ktClass.declaredMethods.none { it.name.contains("AccountMenuOverlay") },
            )
            assertTrue(
                LoginOverlayRendererImpl::class.java.declaredMethods.none { it.name.contains("AccountMenuOverlay") },
            )

            setContent {
                val snackbarHostState = remember { SnackbarHostState() }
                val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
                HiTheme {
                    CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                        Box(Modifier.fillMaxSize()) {
                            overlayMode?.let { mode ->
                                renderer.Content(mode, onDismiss = { overlayMode = null }, onLoginSuccess = { overlayMode = null })
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
    fun defaultAuthRepository_switchLogoutAndRemoveAccountAgainstMockWebServer() = runBlocking {
        val server = MockWebServer()
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val nowMs = AtomicLong(1_700_000_000_000L)
        val client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(clock = { nowMs.get() }),
            userAgentProvider = UserAgentProvider { "TestUA" },
            transportInterceptor = TransportInterceptor(delegateFactory = { null }, isMainThread = { false }),
        )
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            val creds = InMemoryCredentialsStore(listOf(alice, bob), activeUid = alice.uid)
            val cookieStore = InMemorySiteCookieStore(clock = { nowMs.get() })
            val settingsRepo = StaticSettingsRepo(AppSettings(forumServer = baseUrl))
            val gateway = SiteAccessGateway(client, ChallengeDetector(), ChallengeSolver { SolveResult.Cleared }, { nowMs.get() }, scope, 5.seconds)
            val authRepo = DefaultAuthRepository(creds, cookieStore, settingsRepo, gateway, gateway, { nowMs.get() }, scope)

            val gbk = Charset.forName("GBK")
            fun gbkResp(html: String) = MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/html; charset=gbk")
                .body(Buffer().writeString(html, gbk))
                .build()

            server.enqueue(gbkResp("""<html><body><form name="login"><input name="formhash" value="fh999" /></form></body></html>"""))
            server.enqueue(gbkResp("""<html><body><div id="umenu"><cite><a href="space.php?uid=1002">bob</a></cite></div><div class="alert_right">欢迎您回来，bob</div></body></html>"""))

            val switchRes = authRepo.switchAccount(bob.uid)
            assertTrue("切换账号应成功，实际: $switchRes", switchRes is LoginResult.Success)
            assertEquals("1002", authRepo.currentAccount.value?.uid)

            // 退出登录：保留已保存账号并抑制静默重登
            authRepo.logout()
            assertNull(authRepo.currentAccount.value)
            assertEquals(2, authRepo.savedAccounts.value.size)
            assertEquals(SilentReloginResult.Suppressed, authRepo.trySilentRelogin(authRepo.sessionEpoch))

            // 清除保存凭据
            authRepo.removeAccount(bob.uid)
            assertEquals(listOf(alice.uid), authRepo.savedAccounts.value.map { it.uid })
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
