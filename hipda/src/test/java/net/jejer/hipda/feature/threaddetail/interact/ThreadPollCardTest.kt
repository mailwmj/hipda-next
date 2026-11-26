package net.jejer.hipda.feature.threaddetail.interact

import android.app.Application
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.local.HiDatabase
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
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.data.threaddetail.DefaultThreadDetailRepository
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailError
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.data.user.BlacklistMutationResult
import net.jejer.hipda.data.user.BlacklistRepository
import net.jejer.hipda.data.user.BlacklistSnapshot
import net.jejer.hipda.data.user.BlacklistSyncResult
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.ThreadDetailPane
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PollOption
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.parser.threaddetail.ThreadPoll
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * 覆盖 E2c 验收项 3（全部严格离线，假 Repository + 本地 MockWebServer，严禁访问真实论坛）：
 * 1. 单选投票、0ms `HiLoadingSpinner` 防连点、`D5.submitPollVote` 成功刷新柱条与零 HTML 标签渲染；
 * 2. 多选投票 `最多选 {N} 项` 与超选拦截；
 * 3. 提交失败原地提示且保留已勾选项；
 * 4. 未登录态原地显示 `登录后即可操作` + `登录`（0 网络请求）；
 * 5. 本地 `MockWebServer` + 真实 `DefaultThreadDetailRepository` 离线端到端 GBK 投票与刷新闭环。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ThreadPollCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun singleChoicePoll_0msSpinnerPreventsDoubleSubmit_zeroHtmlRendered_andRefreshesResults() {
        val gate = CompletableDeferred<PollVoteResult>()
        val poll = ThreadPoll(
            titleHtml = "<strong>单选投票</strong> , 共有 10 人参与投票",
            timerHtml = "距结束还有: <strong>2 天 4 小时</strong>",
            formhash = "form1234",
            maxChoices = 1,
            options = listOf(
                PollOption("501", "Android 方案"),
                PollOption("502", "折叠屏原生方案"),
            ),
            footer = "投票后结果可见",
        )
        val refreshedPoll = ThreadPoll(
            titleHtml = "<strong>单选投票</strong> , 共有 11 人参与投票",
            timerHtml = "距结束还有: <strong>2 天 4 小时</strong>",
            formhash = null,
            maxChoices = 1,
            options = listOf(
                PollOption(null, "Android 方案", "36.36% (4)"),
                PollOption(null, "折叠屏原生方案", "63.64% (7)"),
            ),
            footer = "您已经投过票，谢谢您的参与",
        )
        val repo = PollFakeRepository(initialPoll = poll, voteGate = gate)
        val account = SavedAccount(uid = "1001", username = "HiUser", passwordMd5 = "0".repeat(32), lastLoginAt = 100L)
        val vm = ThreadDetailViewModel(repo, createIdleGateway(), StaticAuthRepository(account, AuthState.SignedIn(account)))

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "50001", title = "单选投票测试", fid = 2),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        // 零 HTML 标签直接渲染纯文本 titleText / timerText
        composeRule.onNodeWithText("单选投票 , 共有 10 人参与投票").assertIsDisplayed()
        composeRule.onNodeWithText("距结束还有: 2 天 4 小时").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("<strong>", substring = true).fetchSemanticsNodes().size)
        composeRule.onNodeWithText("投票").assertIsDisplayed().assertIsNotEnabled()

        // 单选切换后仅提交最后选中项
        composeRule.onNodeWithText("Android 方案").performClick()
        composeRule.onNodeWithText("折叠屏原生方案").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("投票").assertIsEnabled()

        // 点击投票 0ms 立即切换 HiLoadingSpinner 并禁用防连点
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithText("投票").performClick()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithContentDescription("正在投票…").assertIsDisplayed()
            assertEquals("防连点期间仅发出 1 次投票请求", 1, repo.voteCalls.size)
            assertEquals(listOf("502"), repo.voteCalls.single().optionIds)

            gate.complete(
                PollVoteResult.Success(
                    refreshedPage = repo.buildPage(refreshedPoll),
                    poll = refreshedPoll,
                    message = "投票已经提交",
                ),
            )
            repeat(20) { composeRule.mainClock.advanceTimeByFrame() }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("63.64% (7)").assertIsDisplayed()
        composeRule.onNodeWithText("36.36% (4)").assertIsDisplayed()
        composeRule.onNodeWithText("您已经投过票，谢谢您的参与").assertIsDisplayed()
        assertEquals("投票完成后不应再显示投票按钮", 0, composeRule.onAllNodesWithText("投票").fetchSemanticsNodes().size)
        assertEquals(0.6364f, parsePollOptionFraction("63.64% (7)")!!, 0.0001f)
        assertNull(parsePollOptionFraction("无百分比"))
    }

    @Test
    fun multiChoicePoll_showsMaxChoicesAndBlocksOverSelection() {
        val poll = ThreadPoll(
            titleHtml = "多选投票: ( 最多可选 2 项 )",
            timerHtml = null,
            formhash = "multi123",
            maxChoices = 2,
            options = listOf(
                PollOption("601", "选项 A"),
                PollOption("602", "选项 B"),
                PollOption("603", "选项 C"),
            ),
            footer = null,
        )
        val repo = PollFakeRepository(initialPoll = poll)
        val account = SavedAccount(uid = "1001", username = "HiUser", passwordMd5 = "0".repeat(32), lastLoginAt = 100L)
        val vm = ThreadDetailViewModel(repo, createIdleGateway(), StaticAuthRepository(account, AuthState.SignedIn(account)))

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "50002", title = "多选限额测试", fid = 2),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("最多选 2 项").assertIsDisplayed()
        composeRule.onNodeWithText("选项 A").performClick()
        composeRule.onNodeWithText("选项 B").performClick()
        composeRule.waitForIdle()

        // 达到上限 2 项时，第 3 项禁止超选
        composeRule.onNodeWithText("选项 C").assertIsOff().assertIsNotEnabled()
        composeRule.onNodeWithText("选项 A").assertIsOn()
        composeRule.onNodeWithText("选项 B").assertIsOn()

        // 取消选项 A 后可选选项 C，提交载荷严格只有 2 项
        composeRule.onNodeWithText("选项 A").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("选项 C").assertIsEnabled().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("投票").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("602", "603"), repo.voteCalls.single().optionIds)
        assertEquals(2, repo.voteCalls.single().maxChoices)
    }

    @Test
    fun pollSubmitFailure_showsInPlaceErrorWithoutLosingCheckedOptions() {
        val poll = ThreadPoll(
            titleHtml = "多选失败保留测试",
            timerHtml = null,
            formhash = "fail1234",
            maxChoices = 2,
            options = listOf(
                PollOption("701", "第一项"),
                PollOption("702", "第二项"),
            ),
            footer = null,
        )
        val repo = PollFakeRepository(
            initialPoll = poll,
            immediateVoteResult = ThreadDetailError.NetworkError("连接超时"),
        )
        val account = SavedAccount(uid = "1001", username = "HiUser", passwordMd5 = "0".repeat(32), lastLoginAt = 100L)
        val vm = ThreadDetailViewModel(repo, createIdleGateway(), StaticAuthRepository(account, AuthState.SignedIn(account)))

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "50003", title = "投票失败保留勾选", fid = 2),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("第一项").performClick()
        composeRule.onNodeWithText("第二项").performClick()
        composeRule.onNodeWithText("投票").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("网络连接失败").assertIsDisplayed()
        composeRule.onNodeWithText("第一项").assertIsOn()
        composeRule.onNodeWithText("第二项").assertIsOn()
        composeRule.onNodeWithText("投票").assertIsEnabled()
    }

    @Test
    fun unauthenticatedPoll_showsInPlaceLoginPromptAndFiresZeroVoteRequests() = withAnimatorDurationScale(0f) {
        val poll = ThreadPoll(
            titleHtml = "未登录投票提示测试",
            timerHtml = null,
            formhash = "guest123",
            maxChoices = 1,
            options = listOf(
                PollOption("801", "赞成", "50.00% (5)"),
                PollOption("802", "反对", "50.00% (5)"),
            ),
            footer = null,
        )
        val repo = PollFakeRepository(initialPoll = poll)
        val vm = ThreadDetailViewModel(repo, createIdleGateway(), StaticAuthRepository(null, AuthState.SignedOut))
        var loginClicks = 0

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "50004", title = "未登录投票", fid = 2),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = { loginClicks++ },
                    viewModel = vm,
                )
            }
        }
        waitForIdle()

        onNodeWithText("登录后即可操作").assertIsDisplayed()
        onNodeWithText("登录").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        waitForIdle()

        assertEquals(1, loginClicks)
        assertEquals("未登录态严禁发起任何投票写请求", 0, repo.voteCalls.size)
        assertEquals(0, onAllNodesWithText("投票").fetchSemanticsNodes().size)
    }

    @Test
    fun s1_pollVoteUnderAuthorOnlyAndUserOnlyFilters_updatesOnlyFloor1PollInPlace() {
        val initialPoll = ThreadPoll(
            titleHtml = "<strong>单选投票</strong> , 共有 5 人参与投票",
            timerHtml = null,
            formhash = "filterhash",
            maxChoices = 1,
            options = listOf(PollOption("901", "方案一"), PollOption("902", "方案二")),
            footer = "投票后结果可见",
        )
        val refreshedPoll = ThreadPoll(
            titleHtml = "<strong>单选投票</strong> , 共有 6 人参与投票",
            timerHtml = null,
            formhash = null,
            maxChoices = 1,
            options = listOf(PollOption(null, "方案一", "100.00% (6)"), PollOption(null, "方案二", "0.00% (0)")),
            footer = "您已经投过票，谢谢您的参与",
        )
        val opFloor1 = PostFloor("pf_1", 1, 1, "1001", "HiUser", null, "2026-11-26 12:00", false, null, null, initialPoll, listOf(ContentBlock.Text(listOf(InlineSpan.Plain("楼主 1 楼正文")))))
        val otherFloor2 = PostFloor("pf_2", 2, 1, "2002", "路人甲", null, "2026-11-26 12:02", false, null, null, null, listOf(ContentBlock.Text(listOf(InlineSpan.Plain("非目标作者 2 楼正文")))))
        val opFloor3 = PostFloor("pf_3", 3, 1, "1001", "HiUser", null, "2026-11-26 12:05", false, null, null, null, listOf(ContentBlock.Text(listOf(InlineSpan.Plain("楼主 3 楼正文")))))
        val unfilteredRefreshedPage = ThreadDetailPage(
            tid = "50009", fid = 2, title = "过滤态投票隔离", page = 1, lastPage = 5,
            floors = listOf(opFloor1.copy(poll = refreshedPoll), otherFloor2, opFloor3),
            postsPerPageEstimate = 3,
            meta = DiscuzPageMeta(DiscuzSessionInfo(true, "1001", "HiUser"), null, null, PromptCounts()),
            hasPostList = true,
        )
        val repo = object : ThreadDetailRepository {
            override suspend fun loadPage(
                tid: String, page: Int, filter: ThreadDetailFilter, recordHistory: Boolean, forumName: String?, priority: AccessPriority,
            ): ThreadDetailLoadResult {
                val floors = when (filter) {
                    ThreadDetailFilter.All -> listOf(opFloor1, otherFloor2, opFloor3)
                    is ThreadDetailFilter.AuthorOnly, is ThreadDetailFilter.UserOnly -> listOf(opFloor1, opFloor3)
                }
                val lastPage = if (filter is ThreadDetailFilter.All) 5 else 1
                return ThreadDetailLoadResult.Success(
                    page = unfilteredRefreshedPage.copy(floors = floors, lastPage = lastPage),
                    filter = filter,
                    threadAuthorId = "1001",
                )
            }
            override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid, 1)
            override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid ?: "50009", 1)
            override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
            override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
            override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
                floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, isAuthorBlocked = false) } }
            override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
            override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<ThreadDetailFloorItem> =
                floors.map { ThreadDetailFloorItem(it, isAuthorBlocked = false) }
            override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<PostFloor> = floors
            override suspend fun submitPollVote(
                fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority,
            ): PollVoteResult = PollVoteResult.Success(refreshedPage = unfilteredRefreshedPage, poll = refreshedPoll)
        }

        val account = SavedAccount(uid = "1001", username = "HiUser", passwordMd5 = "0".repeat(32), lastLoginAt = 100L)
        val vm = ThreadDetailViewModel(repo, createIdleGateway(), StaticAuthRepository(account, AuthState.SignedIn(account)))

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "50009", title = "过滤态投票隔离", fid = 2),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        // 1. 只看楼主态投票刷新：只原地更新 1 楼 poll，绝不混入路人甲 2 楼或篡改过滤态 lastPage
        composeRule.runOnIdle { vm.toggleAuthorOnly() }
        composeRule.waitForIdle()
        assertEquals(listOf("pf_1", "pf_3"), vm.uiState.value.floors.map { it.postId })
        composeRule.onNodeWithText("方案一").performClick()
        composeRule.onNodeWithText("投票").performClick()
        composeRule.waitForIdle()
        assertEquals("S1 只看楼主态投票刷新不得混入非楼主楼层", listOf("pf_1", "pf_3"), vm.uiState.value.floors.map { it.postId })
        assertEquals("S1 过滤态 lastPage 应保持原过滤页数", 1, vm.uiState.value.lastPage)
        assertEquals("您已经投过票，谢谢您的参与", vm.uiState.value.floors.first().floor.poll?.footer)
        assertEquals(0, composeRule.onAllNodesWithText("非目标作者 2 楼正文").fetchSemanticsNodes().size)

        // 2. 切至只看此人态投票刷新：同样只原地更新 1 楼 poll
        composeRule.runOnIdle { vm.clearFilter() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { vm.filterByUser("1001", "HiUser") }
        composeRule.waitForIdle()
        assertEquals(listOf("pf_1", "pf_3"), vm.uiState.value.floors.map { it.postId })
        composeRule.onNodeWithText("方案一").performClick()
        composeRule.onNodeWithText("投票").performClick()
        composeRule.waitForIdle()
        assertEquals("S1 只看此人态投票刷新不得混入非目标作者楼层", listOf("pf_1", "pf_3"), vm.uiState.value.floors.map { it.postId })
        assertEquals(1, vm.uiState.value.lastPage)
        assertEquals("您已经投过票，谢谢您的参与", vm.uiState.value.floors.first().floor.poll?.footer)
    }

    @Test
    fun endToEndMockWebServer_submitsGbkPollVoteOfflineAndRefreshesPollResults() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        OkHttp.initialize(context)
        val server = MockWebServer()
        server.start()
        val database = Room.inMemoryDatabaseBuilder(context, HiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val okHttp = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(),
            userAgentProvider = UserAgentProvider { "HiPDA-Test-UA" },
            transportInterceptor = TransportInterceptor(
                delegateFactory = { null },
                isMainThread = { false },
            ),
        )
        try {
            val gbk = Charset.forName("GB18030")
            val initialHtml = """
                <html><body><div id="wrap">
                  <div id="umenu"><cite><a href="space.php?uid=10000">HiUser</a></cite><a href="logging.php?action=logout&amp;formhash=aabbccdd">退出</a></div>
                  <div id="nav"><a href="index.php">HiPDA</a> &raquo; <a href="forumdisplay.php?fid=2">Discovery</a> &raquo; 离线投票端到端</div>
                  <div id="postlist"><div id="post_91001"><table><tbody><tr>
                    <td class="postauthor"><div class="postinfo"><a href="space.php?uid=10000">HiUser</a></div></td>
                    <td class="postcontent">
                      <div class="postinfo"><strong><a><em>1</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-26 12:00</em></div></div></div>
                      <div class="defaultpost"><div class="postmessage">
                        <div class="t_msgfontfix"><table><tbody><tr><td class="t_msgfont">请参与离线 MockWebServer 投票</td></tr></tbody></table></div>
                        <form id="poll" method="post" action="misc.php?action=votepoll">
                          <input type="hidden" name="formhash" value="aabbccdd" />
                          <div class="pollinfo"><strong>多选投票</strong>: ( 最多可选 2 项 ) , 共有 2 人参与投票</div>
                          <p class="polltimer">距结束还有: <strong>1 天</strong></p>
                          <div class="pollchart"><table><tbody>
                            <tr><td class="selector"><input type="checkbox" name="pollanswers[]" value="601" /></td><td class="polloption"><label>Compose 原生</label></td></tr>
                            <tr><td class="selector"><input type="checkbox" name="pollanswers[]" value="602" /></td><td class="polloption"><label>三栏自适应</label></td></tr>
                            <tr><td class="selector"><input type="checkbox" name="pollanswers[]" value="603" /></td><td class="polloption"><label>离线测试保障</label></td></tr>
                            <tr><td colspan="2"><button type="submit">提交</button> 投票后结果可见</td></tr>
                          </tbody></table></div>
                        </form>
                      </div></div>
                    </td>
                  </tr></tbody></table></div></div>
                </div></body></html>
            """.trimIndent()
            val voteAckXml = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[非常感谢，您的投票已经提交]]></root>"""
            val refreshedHtml = """
                <html><body><div id="wrap">
                  <div id="umenu"><cite><a href="space.php?uid=10000">HiUser</a></cite><a href="logging.php?action=logout&amp;formhash=aabbccdd">退出</a></div>
                  <div id="nav"><a href="index.php">HiPDA</a> &raquo; <a href="forumdisplay.php?fid=2">Discovery</a> &raquo; 离线投票端到端</div>
                  <div id="postlist"><div id="post_91001"><table><tbody><tr>
                    <td class="postauthor"><div class="postinfo"><a href="space.php?uid=10000">HiUser</a></div></td>
                    <td class="postcontent">
                      <div class="postinfo"><strong><a><em>1</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-26 12:00</em></div></div></div>
                      <div class="defaultpost"><div class="postmessage">
                        <div class="t_msgfontfix"><table><tbody><tr><td class="t_msgfont">请参与离线 MockWebServer 投票</td></tr></tbody></table></div>
                        <form id="poll" method="post">
                          <div class="pollinfo"><strong>多选投票</strong>: ( 最多可选 2 项 ) , 共有 3 人参与投票</div>
                          <p class="polltimer">距结束还有: <strong>1 天</strong></p>
                          <div class="pollchart"><table><tbody>
                            <tr><td class="polloption"><label>Compose 原生</label></td></tr>
                            <tr><td><em>50.00% (2)</em></td></tr>
                            <tr><td class="polloption"><label>三栏自适应</label></td></tr>
                            <tr><td><em>50.00% (2)</em></td></tr>
                            <tr><td class="polloption"><label>离线测试保障</label></td></tr>
                            <tr><td><em>0.00% (0)</em></td></tr>
                            <tr><td>您已经投过票，谢谢您的参与</td></tr>
                          </tbody></table></div>
                        </form>
                      </div></div>
                    </td>
                  </tr></tbody></table></div></div>
                </div></body></html>
            """.trimIndent()

            fun gbkResponse(body: String, contentType: String = "text/html; charset=gbk"): MockResponse =
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", contentType)
                    .body(Buffer().write(body.toByteArray(gbk)))
                    .build()

            val hasVoted = AtomicBoolean(false)
            val forumRequests = CopyOnWriteArrayList<RecordedRequest>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val target = request.target
                    return when {
                        target.contains("misc.php?action=votepoll") -> {
                            forumRequests += request
                            hasVoted.set(true)
                            gbkResponse(voteAckXml, "text/xml; charset=gbk")
                        }
                        target.contains("viewthread.php") -> {
                            forumRequests += request
                            gbkResponse(if (hasVoted.get()) refreshedHtml else initialHtml)
                        }
                        else -> MockResponse.Builder().code(404).build()
                    }
                }
            }

            val account = SavedAccount(uid = "10000", username = "HiUser", passwordMd5 = "0".repeat(32), lastLoginAt = 1_000L)
            val authRepo = StaticAuthRepository(account, AuthState.SignedIn(account))
            val gateway = SiteAccessGateway(okHttp, ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
            val settingsRepo = OfflineSettingsRepository(AppSettings(forumServer = server.url("").toString().trimEnd('/')))
            val forumHttpClient = ForumHttpClient(
                siteAccessGateway = gateway,
                settingsRepository = settingsRepo,
                authRepository = authRepo,
            )
            val detailRepo = DefaultThreadDetailRepository(
                forumHttpClient = forumHttpClient,
                authRepository = authRepo,
                settingsRepository = settingsRepo,
                blacklistRepository = EmptyBlacklistRepository(),
                browseHistoryDao = database.browseHistoryDao(),
                readingPositionDao = database.readingPositionDao(),
            )
            val vm = ThreadDetailViewModel(detailRepo, gateway, authRepo)

            composeRule.setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "50001", title = "离线投票端到端", fid = 2),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        viewModel = vm,
                    )
                }
            }
            awaitWallCondition(timeoutMs = 5_000L, failureDetail = { "state=${vm.uiState.value}" }) {
                vm.uiState.value.floors.isNotEmpty()
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("多选投票: ( 最多可选 2 项 ) , 共有 2 人参与投票").assertIsDisplayed()
            composeRule.onNodeWithText("最多选 2 项").assertIsDisplayed()
            composeRule.onNodeWithText("Compose 原生").performClick()
            composeRule.onNodeWithText("三栏自适应").performClick()
            composeRule.onNodeWithText("投票").performClick()

            awaitWallCondition(timeoutMs = 5_000L, failureDetail = { "state=${vm.uiState.value}" }) {
                !vm.uiState.value.isPollSubmitting &&
                    vm.uiState.value.floors.firstOrNull()?.floor?.poll?.footer == "您已经投过票，谢谢您的参与"
            }
            composeRule.waitForIdle()

            // 验证 MockWebServer 收到预期 3 条本地离线论坛请求（隔离异步头像请求），且第 2 条为 GBK 投票 POST
            assertEquals("应按序发出 加载 → 投票 POST → 刷新 3 条论坛请求", 3, forumRequests.size)
            val req1 = forumRequests[0]
            val req2 = forumRequests[1]
            val req3 = forumRequests[2]
            assertEquals("GET", req1.method)
            assertEquals("POST", req2.method)
            assertTrue(req2.target.contains("misc.php?action=votepoll&fid=2&tid=50001"))
            val decodedBody = URLDecoder.decode(req2.body?.utf8().orEmpty(), "GB18030")
            assertTrue("投票表单必须含 formhash=aabbccdd，实际：$decodedBody", decodedBody.contains("formhash=aabbccdd"))
            assertTrue("投票表单必须含 pollanswers[]=601，实际：$decodedBody", decodedBody.contains("pollanswers[]=601"))
            assertTrue("投票表单必须含 pollanswers[]=602，实际：$decodedBody", decodedBody.contains("pollanswers[]=602"))
            assertEquals("GET", req3.method)

            assertEquals(2, composeRule.onAllNodesWithText("50.00% (2)").fetchSemanticsNodes().size)
            composeRule.onNodeWithText("0.00% (0)").assertIsDisplayed()
            composeRule.onNodeWithText("您已经投过票，谢谢您的参与").assertIsDisplayed()
        } finally {
            okHttp.dispatcher.executorService.shutdown()
            okHttp.connectionPool.evictAll()
            database.close()
            server.close()
        }
    }

    private fun awaitWallCondition(
        timeoutMs: Long,
        failureDetail: () -> String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            composeRule.waitForIdle()
            if (predicate()) return
            Thread.sleep(20L)
        }
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
        assertTrue("Timed out after ${timeoutMs}ms: ${failureDetail()}", predicate())
    }

    private data class VoteCall(val fid: Int, val tid: String, val formhash: String, val optionIds: List<String>, val maxChoices: Int?)

    private class PollFakeRepository(
        private val initialPoll: ThreadPoll,
        private val voteGate: CompletableDeferred<PollVoteResult>? = null,
        private val immediateVoteResult: PollVoteResult? = null,
    ) : ThreadDetailRepository {
        val voteCalls = CopyOnWriteArrayList<VoteCall>()

        fun buildPage(poll: ThreadPoll): ThreadDetailPage = ThreadDetailPage(
            tid = "50001",
            fid = 2,
            title = "投票测试帖",
            page = 1,
            lastPage = 1,
            floors = listOf(
                PostFloor(
                    postId = "poll_floor_1",
                    floor = 1,
                    page = 1,
                    uid = "1001",
                    author = "HiUser",
                    avatarUrl = null,
                    timePost = "2026-11-26 12:00",
                    isWarned = false,
                    postStatus = null,
                    signature = null,
                    poll = poll,
                    blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("1 楼投票正文说明")))),
                ),
            ),
            postsPerPageEstimate = 1,
            meta = DiscuzPageMeta(
                session = DiscuzSessionInfo(isLoggedIn = true, uid = "1001", username = "HiUser"),
                formhash = poll.formhash,
                errorMessage = null,
                promptCounts = PromptCounts(),
            ),
            hasPostList = true,
        )

        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult = ThreadDetailLoadResult.Success(buildPage(initialPoll), filter, "1001")

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid, 1)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid ?: "50001", 1)
        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
        override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
            floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, isAuthorBlocked = false) } }
        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<ThreadDetailFloorItem> =
            floors.map { ThreadDetailFloorItem(it, isAuthorBlocked = false) }
        override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<PostFloor> = floors

        override suspend fun submitPollVote(
            fid: Int,
            tid: String,
            formhash: String,
            optionIds: List<String>,
            maxChoices: Int?,
            priority: AccessPriority,
        ): PollVoteResult {
            voteCalls += VoteCall(fid, tid, formhash, optionIds, maxChoices)
            voteGate?.let { return it.await() }
            immediateVoteResult?.let { return it }
            val votedPoll = initialPoll.copy(
                formhash = null,
                options = initialPoll.options.map { opt -> opt.copy(optionId = null, ratesText = "50.00% (1)") },
                footer = "投票已提交",
            )
            return PollVoteResult.Success(buildPage(votedPoll), votedPoll)
        }
    }

    private class StaticAuthRepository(account: SavedAccount?, state: AuthState) : AuthRepository {
        override val currentAccount: StateFlow<SavedAccount?> = MutableStateFlow(account).asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOfNotNull(account)).asStateFlow()
        override val authState: StateFlow<AuthState> = MutableStateFlow(state).asStateFlow()
        override val sessionEpoch: Long = if (account != null) 1L else 0L
        override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) = LoginResult.Failure("offline")
        override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
        override suspend fun logout() = Unit
        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
    }

    private class OfflineSettingsRepository(initial: AppSettings) : SettingsRepository {
        private val flow = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = flow.asStateFlow()
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

    private class EmptyBlacklistRepository : BlacklistRepository {
        private val blocked = MutableStateFlow(emptySet<String>())
        override val blockedUsernames: StateFlow<Set<String>> = blocked.asStateFlow()
        override val lastSyncedAtMs: StateFlow<Long?> = MutableStateFlow(null).asStateFlow()
        override fun isBlocked(username: String): Boolean = false
        override suspend fun syncBlacklist(priority: AccessPriority) =
            BlacklistSyncResult.Success(BlacklistSnapshot(usernames = emptySet()), true)
        override suspend fun addToBlacklist(username: String, formhash: String?, priority: AccessPriority) =
            BlacklistMutationResult.Success(username, emptySet())
        override suspend fun removeFromBlacklist(username: String, formhash: String?, priority: AccessPriority) =
            BlacklistMutationResult.Success(username, emptySet())
    }

    companion object {
        private fun createIdleGateway() = SiteAccessGateway(
            client = OkHttpClient(),
            detector = ChallengeDetector(),
            solver = ChallengeSolver { SolveResult.Cancelled },
        )
    }
}
