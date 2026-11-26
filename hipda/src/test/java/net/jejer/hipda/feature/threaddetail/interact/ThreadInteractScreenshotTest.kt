package net.jejer.hipda.feature.threaddetail.interact

import android.app.Application
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.FullScreenToggle
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
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 帖子详情交互 6 张 Roborazzi 截图基线（前缀 `thread_interact_`，对齐 E2c 验收项 5）：
 * 1. `411dp` 浅色 & `933dp` 深色：引用折叠/展开态 + 跳楼落位高亮态；
 * 2. `411dp` 浅色（本人楼层 6 项菜单 + 只看过滤胶囊）& `933dp` 深色（他人楼层 4 项菜单）；
 * 3. `411dp` 浅色（1 楼多选未投态，含 `最多选 2 项` 与勾选态）& `933dp` 深色（1 楼已投结果占比柱条态）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class ThreadInteractScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun thread_interact_quote_highlight_w411_light() = captureQuoteHighlight(
        name = "thread_interact_quote_highlight_w411_light",
        themeMode = HiThemeMode.LIGHT,
        chromeSpec = DetailChromeSpec(showBack = true),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun thread_interact_quote_highlight_w933_dark() = captureQuoteHighlight(
        name = "thread_interact_quote_highlight_w933_dark",
        themeMode = HiThemeMode.DARK,
        chromeSpec = DetailChromeSpec(showBack = false, fullScreenState = FullScreenToggle(false) {}),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun thread_interact_menu_w411_light() = captureContextMenu(
        name = "thread_interact_menu_w411_light",
        themeMode = HiThemeMode.LIGHT,
        chromeSpec = DetailChromeSpec(showBack = true),
        openOwnFloor = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun thread_interact_menu_w933_dark() = captureContextMenu(
        name = "thread_interact_menu_w933_dark",
        themeMode = HiThemeMode.DARK,
        chromeSpec = DetailChromeSpec(showBack = false, fullScreenState = FullScreenToggle(false) {}),
        openOwnFloor = false,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun thread_interact_poll_unvoted_w411_light() = capturePoll(
        name = "thread_interact_poll_unvoted_w411_light",
        themeMode = HiThemeMode.LIGHT,
        chromeSpec = DetailChromeSpec(showBack = true),
        voted = false,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun thread_interact_poll_voted_w933_dark() = capturePoll(
        name = "thread_interact_poll_voted_w933_dark",
        themeMode = HiThemeMode.DARK,
        chromeSpec = DetailChromeSpec(showBack = false, fullScreenState = FullScreenToggle(false) {}),
        voted = true,
    )

    private fun captureQuoteHighlight(name: String, themeMode: HiThemeMode, chromeSpec: DetailChromeSpec) {
        val longQuote1 = (1..6).joinToString("\n") { "第 $it 行长引用内容：展示引用默认折起超过四行并提供清晰展开提示，展开时不影响上方楼层位置。" }
        val longQuote2 = (1..6).joinToString("\n") { "已展开引用第 $it 行：验证展开态与收起按钮，以及下方紧邻楼层的自然排版间距。" }
        val floors = listOf(
            PostFloor(
                "qh_1", 1, 1, "1001", "linus_hi", null, "2026-11-26 09:00", false, null, null, null,
                listOf(ContentBlock.Text(listOf(InlineSpan.Plain("1 楼原帖：点击下方引用跳楼后会精确落位并点亮本楼层高亮背景。")))),
            ),
            PostFloor(
                "qh_2", 2, 1, "1002", "geek_reader", null, "2026-11-26 09:08", false, null, null, null,
                listOf(
                    ContentBlock.Quote("linus_hi 发表于 2026-11-26 09:00", longQuote1, longQuote1, "96001", "qh_1"),
                    ContentBlock.GoToFloor("回复 1# linus_hi", "96001", "qh_1", 1, "linus_hi"),
                    ContentBlock.Text(listOf(InlineSpan.Plain("2 楼保持长引用默认折叠态。"))),
                ),
            ),
            PostFloor(
                "qh_3", 3, 1, "1003", "fold_fan", null, "2026-11-26 09:15", false, null, null, null,
                listOf(
                    ContentBlock.Quote("geek_reader 发表于 2026-11-26 09:08", longQuote2, longQuote2, "96001", "qh_2"),
                    ContentBlock.Text(listOf(InlineSpan.Plain("3 楼演示展开后的长引用效果。"))),
                ),
            ),
        )
        val vm = mountDetailPane(tid = "96001", title = "引用折叠展开与跳楼高亮示例", floors = floors, themeMode = themeMode, chromeSpec = chromeSpec)

        // 展开第 3 楼长引用（第 2 楼保持折叠态，同屏展示折叠 + 展开）
        val expandNodes = composeRule.onAllNodesWithText("展开")
        expandNodes[1].performClick()
        composeRule.waitForIdle()

        // 冻结时钟触发跳楼到 1 楼，在 highlightHold 停留期内定格高亮帧截图
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnIdle { vm.jumpToFloor("qh_1", "96001") }
            repeat(4) { composeRule.mainClock.advanceTimeByFrame() }
            // E4 接入底部三段式工具条后，详情页为顶栏 + 底部工具条共 2 个玻璃区域
            composeRule.assertGlassSurfaceCountAtMost(2)
            composeRule.onRoot().captureHiSnapshot(name)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun captureContextMenu(name: String, themeMode: HiThemeMode, chromeSpec: DetailChromeSpec, openOwnFloor: Boolean) {
        val floors = listOf(
            PostFloor(
                "cm_1", 1, 1, "1001", "linus_hi", null, "2026-11-26 10:00", false, null, null, null,
                listOf(ContentBlock.Text(listOf(InlineSpan.Plain("本人发布的 1 楼主题内容，长按展示包含编辑与删除的 6 项菜单。")))),
            ),
            PostFloor(
                "cm_2", 2, 1, "1002", "geek_reader", null, "2026-11-26 10:06", false, null, null, null,
                listOf(ContentBlock.Text(listOf(InlineSpan.Plain("其他坛友的 2 楼回复内容，长按仅展示 4 项常规菜单，绝不含举报。")))),
            ),
        )
        val vm = mountDetailPane(tid = "96002", title = "楼层长按菜单与只看此人示例", floors = floors, themeMode = themeMode, chromeSpec = chromeSpec)
        if (openOwnFloor) {
            composeRule.runOnIdle { vm.filterByUser("1001", "linus_hi") }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("本人发布的 1 楼主题内容", substring = true).performTouchInput { longClick() }
        } else {
            composeRule.onNodeWithText("其他坛友的 2 楼回复内容", substring = true).performTouchInput { longClick() }
        }
        composeRule.waitForIdle()
        // E4 接入底部三段式工具条后，详情页为顶栏 + 底部工具条共 2 个玻璃区域
        composeRule.assertGlassSurfaceCountAtMost(2)
        composeRule.onRoot().captureHiSnapshot(name)
    }

    private fun capturePoll(name: String, themeMode: HiThemeMode, chromeSpec: DetailChromeSpec, voted: Boolean) {
        val poll = if (!voted) {
            ThreadPoll(
                titleHtml = "<strong>多选投票</strong>: ( 最多可选 2 项 ) , 共有 38 人参与投票",
                timerHtml = "距结束还有: <strong>3 天 6 小时</strong>",
                formhash = "pollhash1",
                maxChoices = 2,
                options = listOf(
                    PollOption("101", "折叠屏三栏自适应阅读"),
                    PollOption("102", "Compose 原生富文本与表情调色"),
                    PollOption("103", "向上插页同帧零闪跳锚定"),
                    PollOption("104", "1 楼投票与离线结果刷新"),
                ),
                footer = "投票后结果可见",
            )
        } else {
            ThreadPoll(
                titleHtml = "<strong>多选投票</strong>: ( 最多可选 2 项 ) , 共有 39 人参与投票",
                timerHtml = "距结束还有: <strong>3 天 5 小时</strong>",
                formhash = null,
                maxChoices = 2,
                options = listOf(
                    PollOption(null, "折叠屏三栏自适应阅读", "42.50% (34)"),
                    PollOption(null, "Compose 原生富文本与表情调色", "30.00% (24)"),
                    PollOption(null, "向上插页同帧零闪跳锚定", "17.50% (14)"),
                    PollOption(null, "1 楼投票与离线结果刷新", "10.00% (8)"),
                ),
                footer = "您已经投过票，谢谢您的参与",
            )
        }
        val floors = listOf(
            PostFloor(
                "pl_1", 1, 1, "1001", "linus_hi", null, "2026-11-26 11:00", false, null, null, poll,
                listOf(ContentBlock.Text(listOf(InlineSpan.Plain("欢迎为你最期待的 M5 阅读核心特性投票，每人最多可选 2 项。")))),
            ),
            PostFloor(
                "pl_2", 2, 1, "1002", "geek_reader", null, "2026-11-26 11:12", false, null, null, null,
                listOf(ContentBlock.Text(listOf(InlineSpan.Plain("已经投给三栏自适应和 Compose 原生正文了！")))),
            ),
        )
        mountDetailPane(tid = "96003", title = "M5 阅读核心特性投票", floors = floors, themeMode = themeMode, chromeSpec = chromeSpec)
        if (!voted) {
            composeRule.onNodeWithText("折叠屏三栏自适应阅读").performClick()
            composeRule.waitForIdle()
        }
        // E4 接入底部三段式工具条后，详情页为顶栏 + 底部工具条共 2 个玻璃区域
        composeRule.assertGlassSurfaceCountAtMost(2)
        composeRule.onRoot().captureHiSnapshot(name)
    }

    private fun mountDetailPane(
        tid: String,
        title: String,
        floors: List<PostFloor>,
        themeMode: HiThemeMode,
        chromeSpec: DetailChromeSpec,
    ): ThreadDetailViewModel {
        val account = SavedAccount(uid = "1001", username = "linus_hi", passwordMd5 = "0".repeat(32), lastLoginAt = 100L)
        val authRepo = StaticAuthRepository(account, AuthState.SignedIn(account))
        val repo = SnapshotInteractRepository(tid, title, floors)
        val vm = ThreadDetailViewModel(repo, createIdleGateway(), authRepo)
        val staticScale = object : MotionDurationScale {
            override val scaleFactor: Float = 0f
        }
        composeRule.setContent {
            HiTheme(themeMode = themeMode, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    val listState = rememberLazyListState()
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = tid, title = title, fid = 2, forumName = "Discovery", author = "linus_hi"),
                        chromeSpec = chromeSpec,
                        onBack = {},
                        onOpenLogin = {},
                        listState = listState,
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        return vm
    }

    private class SnapshotInteractRepository(
        private val tid: String,
        private val title: String,
        private val floors: List<PostFloor>,
    ) : ThreadDetailRepository {
        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            val filtered = when (filter) {
                is ThreadDetailFilter.UserOnly -> floors.filter { it.uid == filter.uid }
                is ThreadDetailFilter.AuthorOnly -> floors.filter { it.uid == "1001" }
                ThreadDetailFilter.All -> floors
            }
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(
                    tid = this.tid,
                    fid = 2,
                    title = title,
                    page = 1,
                    lastPage = 1,
                    floors = filtered,
                    postsPerPageEstimate = filtered.size,
                    meta = DiscuzPageMeta(
                        session = DiscuzSessionInfo(true, "1001", "linus_hi"),
                        formhash = "pollhash1",
                        errorMessage = null,
                        promptCounts = PromptCounts(),
                    ),
                    hasPostList = true,
                ),
                filter = filter,
                threadAuthorId = "1001",
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid, 1)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid ?: this.tid, 1)
        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
        override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
            floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, isAuthorBlocked = false) } }
        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<ThreadDetailFloorItem> =
            floors.map { ThreadDetailFloorItem(it, isAuthorBlocked = false) }
        override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<PostFloor> = floors
        override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
            throw UnsupportedOperationException()
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

    companion object {
        private fun createIdleGateway() = SiteAccessGateway(
            client = OkHttpClient(),
            detector = ChallengeDetector(),
            solver = ChallengeSolver { SolveResult.Cancelled },
        )
    }
}
