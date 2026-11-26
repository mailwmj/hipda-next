package net.jejer.hipda.feature.threaddetail.interact

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
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
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.feature.common.ReplyRequest
import net.jejer.hipda.feature.common.ThreadNavArgs
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
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 覆盖 E2c 验收项 2 与 `R-E2a R2-S2`：
 * 1. 长按楼层触发 `HapticFeedbackType.LongPress`；本人楼层含 `编辑`/`删除`、他人楼层不含、全场景无“举报”；
 * 2. `引用回复`/`回复` 透传 `ReplyRequest`；未登录原地展示 `登录后即可操作` + `登录` 调 `onOpenLogin`；
 * 3. `只看此人` 展示 `只看 {用户名} ×` 胶囊且退出定位回原楼；
 * 4. `复制` 严格仅使用 `ClipData.newPlainText` 写入纯文本并经 `LocalPaneSnackbarController(DetailPane)` 弹 `内容已复制`；
 * 5. 作者区点击热区扩充至「头像 + 作者名/时间」且 `≥ 48dp`，含完整用户名无障碍语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class FloorContextMenuTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun longPressMenu_hapticOwnVsOtherItems_replyRoutingAndZeroReportItem() {
        val ownAccount = SavedAccount(uid = "1001", username = "本人账号", passwordMd5 = "0".repeat(32), lastLoginAt = 100L)
        val authRepo = StaticAuthRepository(ownAccount, AuthState.SignedIn(ownAccount))
        val detailRepo = MenuTestRepository()
        val vm = ThreadDetailViewModel(detailRepo, createIdleGateway(), authRepo)
        val haptics = mutableListOf<HapticFeedbackType>()
        val replyRequests = mutableListOf<ReplyRequest>()

        composeRule.setContent {
            CompositionLocalProvider(
                LocalHapticFeedback provides object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                        haptics += hapticFeedbackType
                    }
                },
            ) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "77001", title = "长按菜单测试", fid = 2),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        onOpenReply = { replyRequests += it },
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 长按本人 1 楼：触发 LongPress 触感，展示全部 6 项且绝无“举报”
        composeRule.onNodeWithText("本人 1 楼正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals(listOf(HapticFeedbackType.LongPress), haptics)
        composeRule.onNodeWithText("第 1 楼 · 本人账号").assertIsDisplayed()
        for (label in listOf("引用回复", "回复", "只看此人", "复制", "编辑", "删除")) {
            composeRule.onNodeWithText(label).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        assertEquals("绝不得出现“举报”菜单项", 0, composeRule.onAllNodesWithText("举报", substring = true).fetchSemanticsNodes().size)

        // 点击引用回复透传完整 ReplyRequest
        composeRule.onNodeWithText("引用回复").performClick()
        composeRule.waitForIdle()
        assertEquals(
            listOf(ReplyRequest(tid = "77001", quotePid = "m_p1_1", quoteAuthor = "本人账号", quoteFloor = 1)),
            replyRequests,
        )

        // 2. 长按他人 2 楼：仅展示 4 项，不含 编辑/删除/举报
        composeRule.onNodeWithText("他人 2 楼正文内容").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals(2, haptics.size)
        composeRule.onNodeWithText("第 2 楼 · 路人坛友").assertIsDisplayed()
        for (label in listOf("引用回复", "回复", "只看此人", "复制")) {
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        assertEquals(0, composeRule.onAllNodesWithText("编辑").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("删除").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("举报", substring = true).fetchSemanticsNodes().size)

        // 点击普通回复透传无 quotePid 的 ReplyRequest
        composeRule.onNodeWithText("回复").performClick()
        composeRule.waitForIdle()
        assertEquals(
            ReplyRequest(tid = "77001", quotePid = null, quoteAuthor = "路人坛友", quoteFloor = 2),
            replyRequests.last(),
        )
    }

    @Test
    fun unauthenticatedMenu_showsInPlaceLoginPromptAndRoutesLoginButton() {
        val authRepo = StaticAuthRepository(null, AuthState.SignedOut)
        val detailRepo = MenuTestRepository()
        val vm = ThreadDetailViewModel(detailRepo, createIdleGateway(), authRepo)
        var loginClicks = 0
        val replyRequests = mutableListOf<ReplyRequest>()

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "77001", title = "未登录长按菜单测试", fid = 2),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = { loginClicks++ },
                    onOpenReply = { replyRequests += it },
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("本人 1 楼正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()

        // 未登录态不出现 编辑/删除/举报
        assertEquals(0, composeRule.onAllNodesWithText("编辑").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("删除").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("举报", substring = true).fetchSemanticsNodes().size)

        // 点击需要登录的 引用回复 -> 原地展示 登录后即可操作 + 登录
        composeRule.onNodeWithText("引用回复").performClick()
        composeRule.waitForIdle()
        assertEquals(0, replyRequests.size)
        composeRule.onNodeWithText("登录后即可操作").assertIsDisplayed()
        composeRule.onNodeWithText("登录")
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.waitForIdle()

        assertEquals(1, loginClicks)
        assertEquals(0, composeRule.onAllNodesWithText("登录后即可操作").fetchSemanticsNodes().size)
    }

    @Test
    fun filterByUser_showsUserOnlyCapsuleAndExitRestoresOriginalFloor() {
        val detailRepo = MenuTestRepository()
        val vm = ThreadDetailViewModel(detailRepo, createIdleGateway())
        lateinit var listState: LazyListState

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                listState = rememberLazyListState()
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "77001", title = "只看此人测试", fid = 2),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    listState = listState,
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        // 翻至第 2 页并滚到第 8 楼（index = 7）
        composeRule.onNodeWithText("下一页").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { listState.requestScrollToItem(index = 7, scrollOffset = 20) }
        composeRule.waitForIdle()

        // 长按第 2 页第 8 楼并点击「只看此人」
        composeRule.onNodeWithText("第 2 页第 8 楼正文内容").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("只看此人").performClick()
        composeRule.waitForIdle()

        assertEquals(ThreadDetailFilter.UserOnly("2008"), detailRepo.loadCalls.last().filter)
        composeRule.onNodeWithText("只看 坛友8").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("退出只看")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.waitForIdle()

        assertEquals(ThreadDetailFilter.All, detailRepo.loadCalls.last().filter)
        assertEquals("退出只看此人必须恢复至原第 2 页", 2, detailRepo.loadCalls.last().page)
        assertTrue(
            "退出只看此人必须定位回原第 8 楼",
            listState.layoutInfo.visibleItemsInfo.any { it.key == "floor:m_p2_8" },
        )
    }

    @Test
    fun copyAction_writesOnlyPlainTextClipDataAndShowsDetailPaneSnackbar() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)!!
        val snackbarEvents = mutableListOf<Pair<String, PaneSnackbarTarget>>()
        val fakeSnackbar = object : PaneSnackbarController {
            override suspend fun show(
                message: String,
                target: PaneSnackbarTarget,
                actionLabel: String?,
                onAction: (() -> Unit)?,
            ) {
                snackbarEvents += message to target
            }
        }
        val detailRepo = MenuTestRepository()
        val vm = ThreadDetailViewModel(detailRepo, createIdleGateway())

        composeRule.setContent {
            CompositionLocalProvider(LocalPaneSnackbarController provides fakeSnackbar) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    ThreadDetailPane(
                        args = ThreadNavArgs(tid = "77001", title = "复制纯文本测试", fid = 2),
                        chromeSpec = DetailChromeSpec(showBack = true),
                        onBack = {},
                        onOpenLogin = {},
                        viewModel = vm,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("本人 1 楼正文内容", substring = true).performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("复制").performClick()
        composeRule.waitForIdle()

        val clip = clipboard.primaryClip
        assertNotNull("复制后剪贴板 primaryClip 不可为空", clip)
        assertTrue(
            "R-E2a R2-S2：必须仅使用 ClipData.newPlainText",
            clip!!.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN),
        )
        assertFalse(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))
        val item = clip.getItemAt(0)
        assertNull("严禁写入 htmlText", item.htmlText)
        assertNull("严禁写入 URI", item.uri)
        assertNull("严禁写入 Intent", item.intent)
        val copiedText = item.text.toString()
        assertTrue("复制纯文本应包含正文，实际：$copiedText", copiedText.contains("本人 1 楼正文内容"))
        assertFalse("复制纯文本不得含 HTML 标签", copiedText.contains("<") || copiedText.contains(">"))

        assertEquals(
            listOf("内容已复制" to PaneSnackbarTarget.DetailPane),
            snackbarEvents,
        )
    }

    @Test
    fun authorHeaderArea_hasAtLeast48dpTouchTargetWithFullUsernameSemanticsAndOpensProfile() {
        val detailRepo = MenuTestRepository()
        val vm = ThreadDetailViewModel(detailRepo, createIdleGateway())
        val openedProfiles = mutableListOf<Pair<String, String>>()

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ThreadDetailPane(
                    args = ThreadNavArgs(tid = "77001", title = "作者热区测试", fid = 2),
                    chromeSpec = DetailChromeSpec(showBack = true),
                    onBack = {},
                    onOpenLogin = {},
                    onOpenUserProfile = { uid, username -> openedProfiles += uid to username },
                    viewModel = vm,
                )
            }
        }
        composeRule.waitForIdle()

        val authorNode = composeRule.onNodeWithContentDescription("路人坛友", substring = true)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        val desc = authorNode.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue("无障碍描述必须包含完整用户名而非首字母，实际：$desc", desc.contains("路人坛友"))

        authorNode.performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("1002" to "路人坛友"), openedProfiles)
    }

    private data class LoadCall(val page: Int, val filter: ThreadDetailFilter)

    private class MenuTestRepository : ThreadDetailRepository {
        val loadCalls = CopyOnWriteArrayList<LoadCall>()

        override suspend fun loadPage(
            tid: String,
            page: Int,
            filter: ThreadDetailFilter,
            recordHistory: Boolean,
            forumName: String?,
            priority: AccessPriority,
        ): ThreadDetailLoadResult {
            loadCalls += LoadCall(page, filter)
            val allFloors = if (page == 1) {
                listOf(
                    PostFloor(
                        "m_p1_1", 1, 1, "1001", "本人账号", null, "2026-11-26 10:00", false, null, null, null,
                        listOf(
                            ContentBlock.Text(
                                listOf(
                                    InlineSpan.Plain("本人 1 楼正文内容"),
                                    InlineSpan.Emoji("default_lol", "images/smilies/default/lol.gif"),
                                ),
                            ),
                        ),
                    ),
                    PostFloor(
                        "m_p1_2", 2, 1, "1002", "路人坛友", null, "2026-11-26 10:05", false, null, null, null,
                        listOf(ContentBlock.Text(listOf(InlineSpan.Plain("他人 2 楼正文内容")))),
                    ),
                )
            } else {
                (1..12).map { idx ->
                    val uid = "${2000 + idx}"
                    PostFloor(
                        "m_p2_$idx", 12 + idx, 2, uid, "坛友$idx", null, "2026-11-26 11:${idx.toString().padStart(2, '0')}",
                        false, null, null, null,
                        listOf(ContentBlock.Text(listOf(InlineSpan.Plain("第 2 页第 $idx 楼正文内容")))),
                    )
                }
            }
            val filtered = when (filter) {
                ThreadDetailFilter.All -> allFloors
                is ThreadDetailFilter.AuthorOnly -> allFloors.filter { it.uid == "1001" }
                is ThreadDetailFilter.UserOnly -> listOf(
                    PostFloor("m_u_1", 20, 2, filter.uid, "坛友8", null, "2026-11-26 11:08", false, null, null, null, listOf(ContentBlock.Text(listOf(InlineSpan.Plain("只看此人筛选结果楼层"))))),
                )
            }
            return ThreadDetailLoadResult.Success(
                page = ThreadDetailPage(tid, 2, "长按菜单测试主题", page, if (filter is ThreadDetailFilter.All) 2 else 1, filtered, 12, sampleMeta(), true),
                filter = filter,
                threadAuthorId = "1001",
            )
        }

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid, 2)
        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) = loadPage(tid ?: "77001", 1)
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
        private fun sampleMeta() = DiscuzPageMeta(
            session = DiscuzSessionInfo(isLoggedIn = true, uid = "1001", username = "本人账号"),
            formhash = "abcd1234",
            errorMessage = null,
            promptCounts = PromptCounts(),
        )

        private fun createIdleGateway() = SiteAccessGateway(
            client = OkHttpClient(),
            detector = ChallengeDetector(),
            solver = ChallengeSolver { SolveResult.Cancelled },
        )
    }
}
