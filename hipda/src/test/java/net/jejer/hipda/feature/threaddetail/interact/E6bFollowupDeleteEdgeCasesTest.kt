package net.jejer.hipda.feature.threaddetail.interact

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.DefaultAuthRepository
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.auth.computeLegacyPasswordMd5
import net.jejer.hipda.data.local.DraftDao
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.ReadingFilterMode
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
import net.jejer.hipda.data.post.DefaultPostRepository
import net.jejer.hipda.data.post.PostRepository
import net.jejer.hipda.data.post.PostTestCredentialsStore
import net.jejer.hipda.data.post.PostTestFixtures
import net.jejer.hipda.data.post.PostTestSettingsRepository
import net.jejer.hipda.data.post.gbkHtmlResponse
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.compose.FakeImageUploadRepository
import net.jejer.hipda.feature.compose.FullEditorViewModel
import net.jejer.hipda.feature.compose.QuickReplyViewModel
import net.jejer.hipda.feature.compose.RecordingPaneSnackbarController
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * E6b 跟进小修（S1 / S2）离线 `MockWebServer` 集成单测（0 访问真实站点）：
 * - S1：三栏（1280dp）删除确认框打开时切换账号或退出登录 → 确认框自动收起，且绝不以新账号或登出态发出删除请求（含同步竞态防御）；
 * - S2：末页仅剩的楼层被删空后，优先按服务端返回的 `redirectedPage` 回到上一页；当 `redirectedPage == null` 时自动按 `page - 1` 回拉上一页，绝不显示空页；连续滚动含末页删空时同步收缩末页页码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class E6bFollowupDeleteEdgeCasesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var virtualClockMs: AtomicLong
    private val clientsToClose = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext<Application>())
        virtualClockMs = AtomicLong(1_700_000_000_000L)
        server = MockWebServer()
        server.start()
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

    private data class RealPostEnv(
        val postRepo: DefaultPostRepository,
        val authRepo: DefaultAuthRepository,
    )

    private fun createRealPostEnv(initialUid: String = "1001", initialUser: String = "本人账号"): RealPostEnv {
        val cookieStore = InMemorySiteCookieStore(clock = { virtualClockMs.get() })
        val credentialsStore = PostTestCredentialsStore()
        val settingsRepo = PostTestSettingsRepository(
            AppSettings(forumServer = server.url("").toString().trimEnd('/'), tailEnabled = false),
        )
        runBlocking {
            val acc = SavedAccount(initialUid, initialUser, computeLegacyPasswordMd5("pw"), lastLoginAt = 1_000L)
            credentialsStore.upsert(acc)
            credentialsStore.setActive(acc.uid)
        }
        val primaryClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { "Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0" },
            transportInterceptor = TransportInterceptor(delegateFactory = { null }, isMainThread = { false }),
        ).also(clientsToClose::add)
        val recoveryClient = HttpClientModule.provideRecoveryOkHttpClient(primaryClient).also(clientsToClose::add)
        val detector = ChallengeDetector()
        val solver = ChallengeSolver { SolveResult.Failed(IllegalStateException("Unexpected challenge")) }
        val primaryGw = SiteAccessGateway(primaryClient, detector, solver, { virtualClockMs.get() }, scope, 5.seconds)
        val recoveryGw = SiteAccessGateway(recoveryClient, detector, solver, { virtualClockMs.get() }, scope, 5.seconds)
        val authRepo = DefaultAuthRepository(credentialsStore, cookieStore, settingsRepo, primaryGw, recoveryGw, { virtualClockMs.get() }, scope)
        val forumHttp = ForumHttpClient(primaryGw, settingsRepo, authRepo)
        val postRepo = DefaultPostRepository(forumHttp, authRepo, settingsRepo, InMemoryDraftDao(), { virtualClockMs.get() }, scope)
        return RealPostEnv(postRepo, authRepo)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun s1_threePaneDeleteDialog_autoDismissesOnAccountSwitchAndLogout_andSendsZeroRequests() {
        val receivedRequests = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                receivedRequests += "${request.method} ${request.target}"
                return MockResponse.Builder().code(200).body("unexpected").build()
            }
        }

        val accountA = SavedAccount(uid = "42", username = "HiUser", passwordMd5 = "0".repeat(32), lastLoginAt = 100L)
        val accountB = SavedAccount(uid = "99", username = "OtherUser", passwordMd5 = "1".repeat(32), lastLoginAt = 200L)
        val switchableAuth = SwitchableTestAuthRepository(accountA)

        val settingsRepo = PostTestSettingsRepository(
            AppSettings(forumServer = server.url("").toString().trimEnd('/'), tailEnabled = false),
        )
        val primaryClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(clock = { virtualClockMs.get() }),
            userAgentProvider = UserAgentProvider { "Mozilla/5.0 (X11; Linux x86_64) Chrome/131.0.0.0" },
            transportInterceptor = TransportInterceptor(delegateFactory = { null }, isMainThread = { false }),
        ).also(clientsToClose::add)
        val gateway = SiteAccessGateway(primaryClient, ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }, { virtualClockMs.get() }, scope, 5.seconds)
        val realPostRepo = DefaultPostRepository(
            forumHttpClient = ForumHttpClient(gateway, settingsRepo, switchableAuth),
            authRepository = switchableAuth,
            settingsRepository = settingsRepo,
            draftDao = InMemoryDraftDao(),
            nowMs = { virtualClockMs.get() },
            scope = scope,
        )

        val harness = MultiPaneTestHarness()
        val detailRepo = MultiPageTestDetailRepository(ownUid = accountA.uid)
        val detailVms = mutableListOf<ThreadDetailViewModel>()
        val editorVm = FullEditorViewModel(realPostRepo, FakeImageUploadRepository(), switchableAuth, settingsRepo) { 10_000L }
        val owner = buildOwner(harness, detailRepo, realPostRepo, switchableAuth, editorVm, detailVms)

        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = switchableAuth.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread,
                        notifyUnreadCountFlow = harness.notifyUnread,
                        hostViewModel = harness.hostViewModel,
                        forumViewModel = harness.forumViewModel,
                        fullEditorViewModel = editorVm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 三栏右栏打开帖子，长按本人楼层打开删除确认框
        composeRule.onNodeWithText("测试帖子标题 1001").performClick()
        composeRule.waitForIdle()
        val detailVm = detailVms.single()
        composeRule.onNodeWithText("第 2 楼本人测试内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除这条回复？").assertIsDisplayed()

        // 2. 确认框打开期间切换账号 A -> B -> 确认框自动收起，且即使触发 confirmDeleteFloor 也 0 请求发往 MockWebServer
        composeRule.runOnIdle { switchableAuth.setSignedIn(accountB) }
        composeRule.waitForIdle()
        assertNull("切换账号后 deleteDialog 必须自动收起", detailVm.uiState.value.deleteDialog)
        assertEquals("切换账号后确认框节点必须消失", 0, composeRule.onAllNodesWithText("删除这条回复？").fetchSemanticsNodes().size)
        composeRule.runOnIdle { detailVm.confirmDeleteFloor() }
        composeRule.waitForIdle()
        assertEquals("切换账号后不得以新账号发出任何删除请求", 0, server.requestCount)
        assertTrue(receivedRequests.isEmpty())

        // 3. 切回账号 A 再次打开删除确认框 -> 退出登录 -> 确认框自动收起，0 网络请求
        composeRule.runOnIdle { switchableAuth.setSignedIn(accountA) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("第 2 楼本人测试内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除这条回复？").assertIsDisplayed()

        composeRule.runOnIdle { switchableAuth.signOutNow() }
        composeRule.waitForIdle()
        assertNull("退出登录后 deleteDialog 必须自动收起", detailVm.uiState.value.deleteDialog)
        assertEquals("退出登录后确认框节点必须消失", 0, composeRule.onAllNodesWithText("删除这条回复？").fetchSemanticsNodes().size)
        composeRule.runOnIdle { detailVm.confirmDeleteFloor() }
        composeRule.waitForIdle()
        assertEquals("退出登录后不得发出任何删除请求", 0, server.requestCount)

        // 4. 同步竞态防御：账号 A 打开确认框后，在同一帧同步切至账号 B 并立刻调用 confirmDeleteFloor -> 直接拦截收起、0 请求
        composeRule.runOnIdle { switchableAuth.setSignedIn(accountA) }
        composeRule.waitForIdle()
        val targetFloor = detailVm.uiState.value.floors.first { it.floor.uid == accountA.uid }.floor
        composeRule.runOnIdle {
            detailVm.requestDeleteFloor(targetFloor)
            switchableAuth.setSignedIn(accountB)
            detailVm.confirmDeleteFloor()
        }
        composeRule.waitForIdle()
        assertNull("同步竞态下必须收起确认框", detailVm.uiState.value.deleteDialog)
        assertEquals("同步竞态下绝不得以新账号发出删除请求", 0, server.requestCount)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun s2a_deleteLastRemainingFloorOnLastPage_commitsServerRedirectedPageWithoutEmptyState() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target.orEmpty()
                return when {
                    target.contains("post.php?action=edit") && request.method == "GET" ->
                        gbkHtmlResponse(PostTestFixtures.prePostHtml(formhash = "del_s2a_fh", uid = "1001", username = "本人账号", isDeletable = true))

                    target.contains("post.php?action=edit") && target.contains("editsubmit=yes") && request.method == "POST" ->
                        MockResponse.Builder().code(302).addHeader("Location", "/forum/viewthread.php?tid=88001&page=2").build()

                    target.contains("viewthread.php?tid=88001") ->
                        gbkHtmlResponse(
                            discuzViewThreadHtml(
                                uid = "1001",
                                username = "本人账号",
                                title = "末页删空回上一页测试主题",
                                currentPage = 2,
                                lastPage = 2,
                                floors = listOf(
                                    Triple("6004", 4, "第 2 页第 4 楼正文（来自 redirectedPage）"),
                                    Triple("6005", 5, "第 2 页第 5 楼正文（来自 redirectedPage）"),
                                    Triple("6006", 6, "第 2 页第 6 楼正文（来自 redirectedPage）"),
                                ),
                            ),
                        )

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val env = createRealPostEnv(initialUid = "1001", initialUser = "本人账号")
        val detailRepo = MultiPageTestDetailRepository(ownUid = "1001")
        val vm = ThreadDetailViewModel(detailRepo, createIdleGateway(), env.authRepo, postRepository = env.postRepo)
        val recordedSnackbars = RecordingPaneSnackbarController()

        composeRule.setContent {
            CompositionLocalProvider(LocalPaneSnackbarController provides recordedSnackbars) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "88001", title = "末页删空回上一页测试主题", fid = 2, openLastPage = true),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals(3, vm.uiState.value.page)
        assertEquals(3, vm.uiState.value.lastPage)
        assertEquals(listOf("6007"), vm.uiState.value.floors.map { it.postId })
        val loadCallsBeforeDelete = detailRepo.loadPageCalls.get()

        composeRule.onNodeWithText("第 3 页末页唯一本人楼层", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.waitForIdle()
        val dialogPane = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "删除这条回复？"))
        composeRule.onNode(hasText("删除") and dialogPane).performClick()

        awaitWallCondition(5_000L) {
            composeRule.waitForIdle()
            vm.uiState.value.page == 2 && vm.uiState.value.deleteDialog == null
        }
        composeRule.waitForIdle()
        assertEquals("应按 redirectedPage 回到第 2 页", 2, vm.uiState.value.page)
        assertEquals("总页数应收缩为 2", 2, vm.uiState.value.lastPage)
        assertEquals(2, vm.uiState.value.firstLoadedPage)
        assertEquals(2, vm.uiState.value.lastLoadedPage)
        assertEquals(listOf("6004", "6005", "6006"), vm.uiState.value.floors.map { it.postId })
        assertEquals("复用 redirectedPage 不应额外回拉 loadPage", loadCallsBeforeDelete, detailRepo.loadPageCalls.get())
        assertEquals("绝不得显示空页占位", 0, composeRule.onAllNodesWithText("暂无内容").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("第 2 页第 6 楼正文（来自 redirectedPage）", substring = true).assertIsDisplayed()
        assertTrue("删除严禁弹撤销 Snackbar", recordedSnackbars.events.none { it.actionLabel == "撤销" })
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun s2b_deleteLastRemainingFloorOnLastPage_fallsBackToPageMinusOneWhenRedirectedPageIsNull() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = request.target.orEmpty()
                return when {
                    target.contains("post.php?action=edit") && request.method == "GET" ->
                        gbkHtmlResponse(PostTestFixtures.prePostHtml(formhash = "del_s2b_fh", uid = "1001", username = "本人账号", isDeletable = true))

                    target.contains("post.php?action=edit") && target.contains("editsubmit=yes") && request.method == "POST" ->
                        MockResponse.Builder().code(302).addHeader("Location", "/forum/viewthread.php?tid=88001&page=2").build()

                    target.contains("viewthread.php?tid=88001") ->
                        // 无有效楼层 HTML -> DefaultPostRepository 返回 redirectedPage = null
                        gbkHtmlResponse(PostTestFixtures.loggedInHtml("1001", "本人账号", "<div id=\"postlist\"></div>"))

                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }

        val env = createRealPostEnv(initialUid = "1001", initialUser = "本人账号")
        val detailRepo = MultiPageTestDetailRepository(ownUid = "1001").apply { afterDeleteLastPage = 2 }
        val vm = ThreadDetailViewModel(detailRepo, createIdleGateway(), env.authRepo, postRepository = env.postRepo)
        val recordedSnackbars = RecordingPaneSnackbarController()

        composeRule.setContent {
            CompositionLocalProvider(LocalPaneSnackbarController provides recordedSnackbars) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "88001", title = "末页删空回上一页测试主题", fid = 2, openLastPage = true),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals(3, vm.uiState.value.page)
        assertEquals(listOf("6007"), vm.uiState.value.floors.map { it.postId })
        val requestedPagesBeforeFallback = detailRepo.requestedPages.toList()

        composeRule.onNodeWithText("第 3 页末页唯一本人楼层", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.waitForIdle()
        val dialogPane = hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "删除这条回复？"))
        composeRule.onNode(hasText("删除") and dialogPane).performClick()

        awaitWallCondition(5_000L) {
            composeRule.waitForIdle()
            vm.uiState.value.page == 2 && vm.uiState.value.floors.map { it.postId } == listOf("6004", "6005", "6006")
        }
        composeRule.waitForIdle()
        assertEquals("redirectedPage 为空时必须按 page - 1 回拉第 2 页", requestedPagesBeforeFallback + 2, detailRepo.requestedPages.toList())
        assertEquals(2, vm.uiState.value.page)
        assertEquals(2, vm.uiState.value.lastPage)
        assertEquals(listOf("6004", "6005", "6006"), vm.uiState.value.floors.map { it.postId })
        assertEquals("回退上一页后绝不得显示空页占位", 0, composeRule.onAllNodesWithText("暂无内容").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("第 2 页第 6 楼正文（回退加载）", substring = true).assertIsDisplayed()
        assertTrue("删除严禁弹撤销 Snackbar", recordedSnackbars.events.none { it.actionLabel == "撤销" })
    }

    private fun buildOwner(
        harness: MultiPaneTestHarness,
        detailRepo: ThreadDetailRepository,
        postRepo: PostRepository,
        authRepo: AuthRepository,
        editorVm: FullEditorViewModel,
        createdDetails: MutableList<ThreadDetailViewModel>,
    ): ViewModelStoreOwner {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) ->
                    ThreadDetailViewModel(detailRepo, harness.gateway, authRepo, postRepository = postRepo).also(createdDetails::add) as T
                modelClass.isAssignableFrom(QuickReplyViewModel::class.java) -> QuickReplyViewModel(postRepo, authRepo) as T
                modelClass.isAssignableFrom(FullEditorViewModel::class.java) -> editorVm as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        return object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore = harness.parentOwner.viewModelStore
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory = factory
        }
    }

    private fun awaitWallCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(25)
        }
        ShadowLooper.idleMainLooper()
        assertTrue("等待条件超时 (${timeoutMs}ms)", condition())
    }

    private fun discuzViewThreadHtml(
        uid: String,
        username: String,
        title: String,
        currentPage: Int,
        lastPage: Int,
        floors: List<Triple<String, Int, String>>,
    ): String {
        val pagerLinks = (1..lastPage).joinToString("") { p ->
            if (p == currentPage) "<strong>$p</strong>" else """<a href="viewthread.php?tid=88001&amp;page=$p">$p</a>"""
        }
        val postBlocks = floors.joinToString("\n") { (pid, floorNum, body) ->
            """
            <div id="post_$pid">
              <table><tbody><tr>
                <td class="postauthor"><div class="postinfo"><a href="space.php?uid=$uid">$username</a></div></td>
                <td class="postcontent">
                  <div class="postinfo">
                    <strong><a href="#"><em>$floorNum</em><sup>#</sup></a></strong>
                    <div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-26 12:${floorNum.toString().padStart(2, '0')}</em></div></div>
                  </div>
                  <div class="defaultpost"><div class="postmessage"><div class="t_msgfontfix"><table cellspacing="0" cellpadding="0"><tbody><tr><td class="t_msgfont" id="postmessage_$pid">$body</td></tr></tbody></table></div></div></div>
                </td>
              </tr></tbody></table>
            </div>
            """.trimIndent()
        }
        return """
            <!DOCTYPE html>
            <html>
            <head><meta http-equiv="Content-Type" content="text/html; charset=gbk" /></head>
            <body>
              <div id="umenu"><cite><a href="space.php?uid=$uid" class="noborder">$username</a></cite></div>
              <div id="wrap">
                <div id="nav"><a href="forumdisplay.php?fid=2">Discovery</a> &raquo; $title</div>
                <div class="forumcontrol"><div class="pages">$pagerLinks</div></div>
                <div id="postlist">$postBlocks</div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private class SwitchableTestAuthRepository(initial: SavedAccount) : AuthRepository {
        private val _account = MutableStateFlow<SavedAccount?>(initial)
        private val _saved = MutableStateFlow(listOf(initial))
        private val _state = MutableStateFlow<AuthState>(AuthState.SignedIn(initial))
        private val epochCounter = AtomicLong(1L)

        override val currentAccount: StateFlow<SavedAccount?> = _account.asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = _saved.asStateFlow()
        override val authState: StateFlow<AuthState> = _state.asStateFlow()
        override val sessionEpoch: Long get() = epochCounter.get()

        fun setSignedIn(account: SavedAccount) {
            epochCounter.incrementAndGet()
            _account.value = account
            _state.value = AuthState.SignedIn(account)
        }

        fun signOutNow() {
            epochCounter.incrementAndGet()
            _account.value = null
            _state.value = AuthState.SignedOut
        }

        override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) = LoginResult.Failure("offline")
        override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
        override suspend fun logout() { signOutNow() }
        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
    }

    private class MultiPageTestDetailRepository(private val ownUid: String) : ThreadDetailRepository {
        val loadPageCalls = AtomicInteger(0)
        val requestedPages = CopyOnWriteArrayList<Int>()
        @Volatile var afterDeleteLastPage: Int = 3

        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            loadPageCalls.incrementAndGet()
            requestedPages += page
            val lastPage = if (page == 3) 3 else afterDeleteLastPage
            val floors = when (page) {
                1 -> listOf(
                    floor("6001", 1, 1, "2001", "楼主", "第 1 页第 1 楼正文内容"),
                    floor("6002", 2, 1, ownUid, "本人账号", "第 2 楼本人测试内容，用于验证三栏切号收起确认框。"),
                    floor("6003", 3, 1, "2003", "坛友3", "第 1 页第 3 楼正文内容"),
                )
                2 -> listOf(
                    floor("6004", 4, 2, "2004", "坛友4", "第 2 页第 4 楼正文（回退加载）"),
                    floor("6005", 5, 2, "2005", "坛友5", "第 2 页第 5 楼正文（回退加载）"),
                    floor("6006", 6, 2, "2006", "坛友6", "第 2 页第 6 楼正文（回退加载）"),
                )
                else -> listOf(
                    floor("6007", 7, 3, ownUid, "本人账号", "第 3 页末页唯一本人楼层，删除后应回到第 2 页。"),
                )
            }
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(
                    tid = tid, fid = 2, title = "末页删空回上一页测试主题", page = page, lastPage = lastPage,
                    floors = floors, postsPerPageEstimate = 3,
                    meta = DiscuzPageMeta(DiscuzSessionInfo(true, ownUid, "本人账号"), "fh_s2", null, PromptCounts()),
                    hasPostList = true,
                ),
                filter = filter,
                forumName = forumName,
                threadAuthorId = "2001",
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid, 3, ThreadDetailFilter.All, recordHistory, forumName, priority)

        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
            loadPage(tid ?: "88001", 1, ThreadDetailFilter.All, recordHistory, forumName, priority)

        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
        override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = floorsFlow.map { l -> l.map { ThreadDetailFloorItem(it, false) } }
        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors.map { ThreadDetailFloorItem(it, false) }
        override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors
        override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
            throw UnsupportedOperationException()

        private fun floor(pid: String, num: Int, page: Int, uid: String, author: String, text: String) = PostFloor(
            postId = pid, floor = num, page = page, uid = uid, author = author, avatarUrl = null,
            timePost = "2026-11-26 12:${num.toString().padStart(2, '0')}", isWarned = false,
            postStatus = null, signature = null, poll = null,
            blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain(text)))),
        )
    }

    private class InMemoryDraftDao : DraftDao {
        private val store = MutableStateFlow<Map<String, DraftEntity>>(emptyMap())
        override fun observe(key: String): Flow<DraftEntity?> = store.map { it[key] }
        override suspend fun upsert(draft: DraftEntity) { store.value = store.value + (draft.key to draft) }
        override suspend fun delete(key: String) { store.value = store.value - key }
    }

    private fun createIdleGateway() = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
}
