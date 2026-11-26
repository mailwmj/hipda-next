package net.jejer.hipda.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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
import net.jejer.hipda.data.user.*
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.SingleHostPaneSnackbarController
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import okhttp3.OkHttp
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
class BlacklistScreenAndMockWebServerTest {

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun blacklist_unauthenticatedShowsLoginPromptAndEmptyShowsEmptyState() = withAnimatorDurationScale(0f) {
        val auth = FakeAuthRepo(initial = null)
        val bl = FakeBlacklistRepo(emptySet())
        val vm = BlacklistViewModel(auth, bl)
        var loginClicks = 0

        setContent {
            HiTheme {
                BlacklistScreen(vm, onBack = {}, onOpenLogin = { loginClicks++ }, contentPadding = PaddingValues(0.dp))
            }
        }
        waitForIdle()
        assertGlassSurfaceCountAtMost(0)
        onNodeWithText("需要登录才能查看").assertIsDisplayed()
        onNodeWithText("登录").performClick()
        assertEquals(1, loginClicks)

        // 登录后同步为空 -> 显示“黑名单是空的”
        auth.setCurrent(SavedAccount("1001", "alice", "0123456789abcdef0123456789abcdef", lastLoginAt = 1L))
        waitForIdle()
        onNodeWithText("黑名单是空的").assertIsDisplayed()
    }

    @Test
    fun blacklist_removeShows0msSpinnerCollapsesRowAndUndoRestoresUser() = withAnimatorDurationScale(0f) {
        val auth = FakeAuthRepo(SavedAccount("1001", "alice", "0123456789abcdef0123456789abcdef", lastLoginAt = 1L))
        val removeGate = CompletableDeferred<BlacklistMutationResult>()
        val bl = FakeBlacklistRepo(linkedSetOf("spammer_a", "troll_b"), pendingRemove = removeGate)
        val vm = BlacklistViewModel(auth, bl)

        setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
            HiTheme {
                CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                    Box(Modifier.fillMaxSize()) {
                        BlacklistScreen(vm, onBack = {}, onOpenLogin = {}, contentPadding = PaddingValues(0.dp))
                        HiSnackbarHost(snackbarHostState, bottomClearance = 16.dp)
                    }
                }
            }
        }
        waitForIdle()
        assertGlassSurfaceCountAtMost(0)
        onNodeWithText("spammer_a").assertIsDisplayed()
        onNodeWithText("troll_b").assertIsDisplayed()

        // 点击移出黑名单 -> 0ms 原地变进度圈且禁用防连点
        mainClock.autoAdvance = false
        onAllNodesWithText("移出黑名单").onFirst().performClick()
        mainClock.advanceTimeByFrame()
        waitForIdle()

        onNodeWithContentDescription("正在移除…").assertIsDisplayed().assertIsNotEnabled()
        assertEquals(listOf("spammer_a"), bl.removeCalls)

        // 完成后行收起并弹可撤销 Snackbar
        mainClock.autoAdvance = true
        bl.completeRemove("spammer_a")
        removeGate.complete(BlacklistMutationResult.Success("spammer_a", bl.blockedUsernames.value))
        waitForIdle()

        onNodeWithText("spammer_a").assertDoesNotExist()
        onNodeWithText("troll_b").assertIsDisplayed()
        onNodeWithText("已将 spammer_a 移出黑名单").assertIsDisplayed()

        // 点击撤销 -> 调用加入黑名单恢复
        onNodeWithText("撤销").performClick()
        waitForIdle()
        assertEquals(listOf("spammer_a"), bl.addCalls)
        onNodeWithText("spammer_a").assertIsDisplayed()
    }

    @Test
    fun blacklist_consecutiveRemovals_dismissesFirstSnackbarAndUndoRestoresOnlySecondAtAnchorPosition() = withAnimatorDurationScale(0f) {
        val auth = FakeAuthRepo(SavedAccount("1001", "alice", "0123456789abcdef0123456789abcdef", lastLoginAt = 1L))
        val bl = FakeBlacklistRepo(linkedSetOf("alpha_u", "beta_u", "gamma_u", "delta_u"))
        val vm = BlacklistViewModel(auth, bl)

        setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
            HiTheme {
                CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                    Box(Modifier.fillMaxSize()) {
                        BlacklistScreen(vm, onBack = {}, onOpenLogin = {}, contentPadding = PaddingValues(0.dp))
                        HiSnackbarHost(snackbarHostState, bottomClearance = 16.dp)
                    }
                }
            }
        }
        waitForIdle()

        // 1. 移出第一个用户 alpha_u -> 弹出 alpha_u 的可撤销 Snackbar
        onAllNodesWithText("移出黑名单")[0].performClick()
        waitForIdle()
        onNodeWithText("已将 alpha_u 移出黑名单").assertIsDisplayed()
        onNodeWithText("alpha_u").assertDoesNotExist()

        // 2. 不等待也不撤销，直接移出当前第 2 个可见用户 gamma_u（位于 beta_u 与 delta_u 之间）
        //    必须立即关掉 alpha_u 的 Snackbar（alpha_u 就此确认不可再撤），立即显示 gamma_u 的 Snackbar
        onAllNodesWithText("移出黑名单")[1].performClick()
        waitForIdle()
        onNodeWithText("已将 alpha_u 移出黑名单").assertDoesNotExist()
        onNodeWithText("已将 gamma_u 移出黑名单").assertIsDisplayed()
        onNodeWithText("gamma_u").assertDoesNotExist()

        // 3. 点击撤销 -> 仅恢复第二个（gamma_u），不恢复 alpha_u，且精确放回前邻居 beta_u 之后、delta_u 之前
        onNodeWithText("撤销").performClick()
        waitForIdle()
        assertEquals(listOf("gamma_u"), bl.addCalls)
        onNodeWithText("alpha_u").assertDoesNotExist()
        onNodeWithText("beta_u").assertIsDisplayed()
        onNodeWithText("gamma_u").assertIsDisplayed()
        onNodeWithText("delta_u").assertIsDisplayed()
        assertEquals(listOf("beta_u", "gamma_u", "delta_u"), vm.uiState.value.orderedUsernames.filter { it in vm.uiState.value.activeBlockedSet })

        val betaTop = onNodeWithText("beta_u").fetchSemanticsNode().boundsInRoot.top
        val gammaTop = onNodeWithText("gamma_u").fetchSemanticsNode().boundsInRoot.top
        val deltaTop = onNodeWithText("delta_u").fetchSemanticsNode().boundsInRoot.top
        assertTrue("gamma_u 应回到 beta_u 之后（beta=$betaTop, gamma=$gammaTop）", gammaTop > betaTop)
        assertTrue("gamma_u 应回到 delta_u 之前（gamma=$gammaTop, delta=$deltaTop）", deltaTop > gammaTop)
    }

    @Test
    fun rE12R2S1_removeAdjacentWhilePreviousInFlight_skipsRemovingNeighborAsAnchorAndRestoresOrder() = withAnimatorDurationScale(0f) {
        val account = SavedAccount("1001", "alice", "0123456789abcdef0123456789abcdef", lastLoginAt = 1L)

        // 1. ViewModel 级精准校验：挂起移出 C（仍在 removingUsernames）时发起移出 D -> D 令牌的 previousNeighbor 必须跳过 C 锁定为 B
        val gateC1 = CompletableDeferred<BlacklistMutationResult>()
        val gateD1 = CompletableDeferred<BlacklistMutationResult>()
        val bl1 = FakeBlacklistRepo(
            initial = linkedSetOf("A", "B", "C", "D"),
            perUserRemoveGates = mapOf("C" to gateC1, "D" to gateD1),
        )
        val vm1 = BlacklistViewModel(FakeAuthRepo(account), bl1)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        var removedC: BlacklistUndoToken? = null
        var removedD: BlacklistUndoToken? = null
        vm1.removeUser("C", onRemoved = { removedC = it }, onFailed = { fail("unexpected error: $it") })
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("C 应处于在途移除态", "C" in vm1.uiState.value.removingUsernames)
        assertTrue("慢网未返回前 C 仍在 activeBlockedSet", "C" in vm1.uiState.value.activeBlockedSet)

        vm1.removeUser("D", onRemoved = { removedD = it }, onFailed = { fail("unexpected error: $it") })
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        bl1.completeRemove("C")
        gateC1.complete(BlacklistMutationResult.Success("C", bl1.blockedUsernames.value))
        bl1.completeRemove("D")
        gateD1.complete(BlacklistMutationResult.Success("D", bl1.blockedUsernames.value))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertNull("旧令牌 C 被新令牌 D 取代后不得回调 onRemoved", removedC)
        val tokenD = requireNotNull(removedD) { "D 移除完成后应回调 onRemoved" }
        assertEquals("D 捕获的前邻居锚点必须跳过在途移除项 C 并锁定为 B", "B", tokenD.previousNeighbor)

        vm1.restoreUser(tokenD, onFailed = { fail("unexpected restore error: $it") })
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(
            "撤销 D 后必须精确恢复回 B 之后得到 [A, B, D]，绝不得回退插到首位 [D, A, B]",
            listOf("A", "B", "D"),
            vm1.uiState.value.orderedUsernames.filter { it in vm1.uiState.value.activeBlockedSet },
        )

        // 2. 端到端 UI 校验：在黑名单页依次点移出 C（在途进度圈）与 D -> 放行两者 -> 点撤销 -> 界面顺序严格为 [A, B, D]
        val gateC2 = CompletableDeferred<BlacklistMutationResult>()
        val gateD2 = CompletableDeferred<BlacklistMutationResult>()
        val bl2 = FakeBlacklistRepo(
            initial = linkedSetOf("A", "B", "C", "D"),
            perUserRemoveGates = mapOf("C" to gateC2, "D" to gateD2),
        )
        val vm2 = BlacklistViewModel(FakeAuthRepo(account), bl2)
        setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
            HiTheme {
                CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                    Box(Modifier.fillMaxSize()) {
                        BlacklistScreen(vm2, onBack = {}, onOpenLogin = {}, contentPadding = PaddingValues(0.dp))
                        HiSnackbarHost(snackbarHostState, bottomClearance = 16.dp)
                    }
                }
            }
        }
        waitForIdle()

        onAllNodesWithText("移出黑名单")[2].performClick() // C
        waitForIdle()
        onAllNodesWithText("移出黑名单")[2].performClick() // C 变为进度圈后，索引 2 为 D
        waitForIdle()
        assertEquals(listOf("C", "D"), bl2.removeCalls)

        bl2.completeRemove("C")
        gateC2.complete(BlacklistMutationResult.Success("C", bl2.blockedUsernames.value))
        bl2.completeRemove("D")
        gateD2.complete(BlacklistMutationResult.Success("D", bl2.blockedUsernames.value))
        waitForIdle()

        onNodeWithText("已将 D 移出黑名单").assertIsDisplayed()
        onNodeWithText("撤销").performClick()
        waitForIdle()

        assertEquals(listOf("A", "B", "D"), vm2.uiState.value.orderedUsernames.filter { it in vm2.uiState.value.activeBlockedSet })
        val topA = onAllNodesWithText("A").onLast().fetchSemanticsNode().boundsInRoot.top
        val topB = onAllNodesWithText("B").onLast().fetchSemanticsNode().boundsInRoot.top
        val topD = onAllNodesWithText("D").onLast().fetchSemanticsNode().boundsInRoot.top
        assertTrue("B 应位于 A 之后（A=$topA, B=$topB）", topB > topA)
        assertTrue("D 应恢复到 B 之后（B=$topB, D=$topD）", topD > topB)
    }

    @Test
    fun blacklist_syncRemoveAndUndoAgainstMockWebServer() = runBlocking {
        val server = MockWebServer()
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val nowMs = AtomicLong(1_700_000_000_000L)
        val client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(clock = { nowMs.get() }),
            userAgentProvider = UserAgentProvider { "TestUA" },
            transportInterceptor = TransportInterceptor(delegateFactory = { null }, isMainThread = { false }),
        )
        val tempDir = createTempDirectory("e12-bl-store").toFile()
        try {
            val baseUrl = server.url("").toString().trimEnd('/')
            val settingsRepo = StaticSettingsRepo(AppSettings(forumServer = baseUrl))
            val auth = FakeAuthRepo(SavedAccount("10000", "HiUser", "0123456789abcdef0123456789abcdef", lastLoginAt = 1L))
            val gateway = SiteAccessGateway(client, ChallengeDetector(), ChallengeSolver { SolveResult.Cleared }, { nowMs.get() }, scope, 5.seconds)
            val httpClient = ForumHttpClient(gateway, settingsRepo, auth)
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(tempDir, "blacklist.preferences_pb") }
            val store = BlacklistStore(dataStore)
            val repo = DefaultBlacklistRepository(httpClient, auth, store, { nowMs.get() }, scope)

            val gbk = Charset.forName("GBK")
            fun gbkHtml(body: String) = MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/html; charset=gbk")
                .body(Buffer().writeString(body, gbk))
                .build()

            server.enqueue(
                gbkHtml(
                    """<html><body>
                        <div id="umenu"><cite><a href="space.php?uid=10000">HiUser</a></cite><a href="logging.php?action=logout&formhash=fh123456">退出</a></div>
                        <div class="blacklist"><ul class="commonlist"><li><a href="space.php?username=bad_user">bad_user</a></li></ul></div>
                    </body></html>""",
                ),
            )
            server.enqueue(gbkHtml("""<html><body><div id="umenu"><cite><a href="space.php?uid=10000">HiUser</a></cite></div><div class="alert_right">操作成功</div></body></html>"""))
            server.enqueue(gbkHtml("""<html><body><div id="umenu"><cite><a href="space.php?uid=10000">HiUser</a></cite></div><div class="alert_right">操作成功</div></body></html>"""))

            val syncRes = repo.syncBlacklist()
            assertTrue(syncRes is BlacklistSyncResult.Success)
            assertEquals(setOf("bad_user"), repo.blockedUsernames.value)

            val removeRes = repo.removeFromBlacklist("bad_user")
            assertTrue(removeRes is BlacklistMutationResult.Success)
            assertTrue(repo.blockedUsernames.value.isEmpty())

            val undoAddRes = repo.addToBlacklist("bad_user")
            assertTrue(undoAddRes is BlacklistMutationResult.Success)
            assertEquals(setOf("bad_user"), repo.blockedUsernames.value)

            assertEquals("/forum/pm.php?action=viewblack", server.takeRequest().target)
            assertEquals("/forum/pm.php?action=delblack", server.takeRequest().target)
            assertEquals("/forum/pm.php?action=addblack", server.takeRequest().target)
        } finally {
            scope.cancel()
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.close()
            tempDir.deleteRecursively()
        }
    }

    private class FakeAuthRepo(initial: SavedAccount?) : AuthRepository {
        private val _current = MutableStateFlow(initial)
        override val currentAccount: StateFlow<SavedAccount?> = _current.asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOfNotNull(initial)).asStateFlow()
        override val authState: StateFlow<AuthState> = MutableStateFlow(if (initial != null) AuthState.SignedIn(initial) else AuthState.SignedOut).asStateFlow()
        override var sessionEpoch: Long = 1L
        fun setCurrent(account: SavedAccount?) { _current.value = account }
        override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String): LoginResult = error("unused")
        override suspend fun switchAccount(uid: String): LoginResult = error("unused")
        override suspend fun logout() { _current.value = null }
        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority): SilentReloginResult = SilentReloginResult.Suppressed
    }

    private class FakeBlacklistRepo(
        initial: Set<String>,
        private val pendingRemove: CompletableDeferred<BlacklistMutationResult>? = null,
        private val perUserRemoveGates: Map<String, CompletableDeferred<BlacklistMutationResult>> = emptyMap(),
    ) : BlacklistRepository {
        private val _blocked = MutableStateFlow(initial)
        override val blockedUsernames: StateFlow<Set<String>> = _blocked.asStateFlow()
        override val lastSyncedAtMs: StateFlow<Long?> = MutableStateFlow(1000L).asStateFlow()
        val removeCalls = mutableListOf<String>()
        val addCalls = mutableListOf<String>()

        override fun isBlocked(username: String): Boolean = username in _blocked.value
        override suspend fun syncBlacklist(priority: AccessPriority): BlacklistSyncResult =
            BlacklistSyncResult.Success(BlacklistSnapshot("1001", _blocked.value, 1000L), _blocked.value.isEmpty(), "fh1")

        fun completeRemove(username: String) { _blocked.update { it - username } }

        override suspend fun removeFromBlacklist(username: String, formhash: String?, priority: AccessPriority): BlacklistMutationResult {
            removeCalls += username
            perUserRemoveGates[username]?.let { return it.await() }
            if (pendingRemove != null) return pendingRemove.await()
            _blocked.update { it - username }
            return BlacklistMutationResult.Success(username, _blocked.value)
        }

        override suspend fun addToBlacklist(username: String, formhash: String?, priority: AccessPriority): BlacklistMutationResult {
            addCalls += username
            _blocked.update { it + username }
            return BlacklistMutationResult.Success(username, _blocked.value)
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
