package net.jejer.hipda.feature.profile

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.user.*
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.user.UserProfile
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
class UserProfileScreenTest {

    private val emptyMeta = DiscuzPageMeta(
        session = DiscuzSessionInfo(true, "10000", "HiUser"),
        formhash = "fh1",
        errorMessage = null,
        promptCounts = PromptCounts(0, 0),
    )

    @Test
    fun profileTabs_maintainIndependentStatesEmptyThreadsAndZeroGlass() = withAnimatorDurationScale(0f) {
        val profile = UserProfile(
            uid = "10000",
            username = "HiUser",
            isOnline = true,
            avatarUrl = "file:///etc/passwd", // 危险头像应被白名单拦截为 null
            formhash = "fh1",
            detail = "基本资料\n\n用户组: 初级会员\n注册日期: 2011-4-13\n积分: 6\n签名: https://example.com javascript:alert(1)\n",
            meta = emptyMeta,
        )
        val userRepo = FakeUserRepository(
            profileResult = UserProfileResult.Success(profile),
            threadsResult = UserThreadsResult.Success("10000", 1, SearchPage(null, 1, emptyList(), emptyMeta)),
        )
        val blRepo = FakeProfileBlacklistRepo()
        val vm = UserProfileViewModel("10000", "HiUser", userRepo, blRepo)
        val openedSms = mutableListOf<SmsConversationArgs>()

        setContent {
            HiTheme {
                UserProfileScreen(
                    viewModel = vm,
                    showBack = true,
                    onBack = {},
                    onOpenThread = {},
                    onOpenSmsConversation = { openedSms += it },
                    onOpenLogin = {},
                    contentPadding = PaddingValues(0.dp),
                )
            }
        }
        waitForIdle()
        assertGlassSurfaceCountAtMost(0)
        assertNull("危险头像 scheme 必须被拦截", vm.uiState.value.avatarUrl)

        onAllNodesWithText("HiUser").onFirst().assertIsDisplayed()
        onNodeWithText("用户组: 初级会员").assertIsDisplayed()

        // 发短消息回调
        onNodeWithText("发短消息").performClick()
        assertEquals(listOf(SmsConversationArgs("10000", "HiUser")), openedSms)

        // 切至 TA 的帖子：为空时显示“TA 还没有发过帖子”，不报错
        onNodeWithText("TA 的帖子").performClick()
        waitForIdle()
        assertGlassSurfaceCountAtMost(0)
        onNodeWithText("TA 还没有发过帖子").assertIsDisplayed()
        onNodeWithText("加载失败").assertDoesNotExist()

        // 切回资料无闪烁、不重复拉资料
        onNodeWithText("资料").performClick()
        waitForIdle()
        onNodeWithText("用户组: 初级会员").assertIsDisplayed()
        assertEquals(1, userRepo.profileCalls)
    }

    @Test
    fun profileThreadsClickAndBlacklistToggleWith0msSpinnerAndUndo() = withAnimatorDurationScale(0f) {
        val profile = UserProfile("10000", "HiUser", false, "https://img.example.com/a.jpg", "fh1", "基本资料\n\n用户组: 正式会员\n", emptyMeta)
        val items = listOf(SearchItem("3001", null, "测试用户帖子标题", null, "HiUser", "10000", null, "2026-11-20", "Discovery"))
        val userRepo = FakeUserRepository(
            profileResult = UserProfileResult.Success(profile),
            threadsResult = UserThreadsResult.Success("10000", 1, SearchPage("s1", 1, items, emptyMeta)),
        )
        val addGate = CompletableDeferred<BlacklistMutationResult>()
        val blRepo = FakeProfileBlacklistRepo(pendingAdd = addGate)
        val vm = UserProfileViewModel("10000", "HiUser", userRepo, blRepo)
        val openedThreads = mutableListOf<ThreadNavArgs>()

        setContent {
            val snackbarHostState = remember { SnackbarHostState() }
            val controller = remember(snackbarHostState) { SingleHostPaneSnackbarController(snackbarHostState) }
            HiTheme {
                CompositionLocalProvider(LocalPaneSnackbarController provides controller) {
                    Box(Modifier.fillMaxSize()) {
                        UserProfileScreen(vm, true, {}, { openedThreads += it }, {}, {}, PaddingValues(0.dp))
                        HiSnackbarHost(snackbarHostState, bottomClearance = 16.dp)
                    }
                }
            }
        }
        waitForIdle()

        // 点击加入黑名单 -> 0ms 原地进度圈并禁用按钮
        mainClock.autoAdvance = false
        onNodeWithText("加入黑名单").performClick()
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithText("加入黑名单").assertDoesNotExist()
        assertEquals(listOf("HiUser"), blRepo.addCalls)

        // 完成后显示可撤销 Snackbar 并切换为“移出黑名单”
        mainClock.autoAdvance = true
        blRepo.applyAdd("HiUser")
        addGate.complete(BlacklistMutationResult.Success("HiUser", blRepo.blockedUsernames.value))
        waitForIdle()
        onNodeWithText("移出黑名单").assertIsDisplayed()
        onNodeWithText("已将 HiUser 加入黑名单").assertIsDisplayed()

        // 点击撤销恢复为未拉黑
        onNodeWithText("撤销").performClick()
        waitForIdle()
        assertEquals(listOf("HiUser"), blRepo.removeCalls)
        onNodeWithText("加入黑名单").assertIsDisplayed()

        // 连续两次拉黑/移出操作：第二次发起时立即关掉前一条 Snackbar，点撤销仅撤回第二次
        blRepo.clearPendingAdd()
        onNodeWithText("加入黑名单").performClick()
        waitForIdle()
        onNodeWithText("已将 HiUser 加入黑名单").assertIsDisplayed()

        onNodeWithText("移出黑名单").performClick()
        waitForIdle()
        onNodeWithText("已将 HiUser 加入黑名单").assertDoesNotExist()
        onNodeWithText("已将 HiUser 移出黑名单").assertIsDisplayed()

        onNodeWithText("撤销").performClick()
        waitForIdle()
        onNodeWithText("移出黑名单").assertIsDisplayed()

        // 切至 TA 的帖子并点击打开帖子
        onNodeWithText("TA 的帖子").performClick()
        waitForIdle()
        onNodeWithText("测试用户帖子标题").assertIsDisplayed().performClick()
        assertEquals("3001", openedThreads.single().tid)
    }

    @Test
    fun profileInfoError_doesNotBreakThreadsTab() = withAnimatorDurationScale(0f) {
        val items = listOf(SearchItem("4001", null, "独立帖子标题", null, "Bob", "20000", null, "2026-11-25", "Geek Talks"))
        val userRepo = FakeUserRepository(
            profileResult = UserError.NetworkError("timeout"),
            threadsResult = UserThreadsResult.Success("20000", 1, SearchPage("s2", 1, items, emptyMeta)),
        )
        val vm = UserProfileViewModel("20000", "Bob", userRepo, FakeProfileBlacklistRepo())

        setContent {
            HiTheme {
                UserProfileScreen(vm, true, {}, {}, {}, {}, PaddingValues(0.dp))
            }
        }
        waitForIdle()
        onNodeWithText("网络连接失败").assertIsDisplayed()

        onNodeWithText("TA 的帖子").performClick()
        waitForIdle()
        onNodeWithText("独立帖子标题").assertIsDisplayed()
    }

    private class FakeUserRepository(
        private val profileResult: UserProfileResult,
        private val threadsResult: UserThreadsResult,
    ) : UserRepository {
        var profileCalls = 0
        override suspend fun getUserProfile(uid: String, priority: AccessPriority): UserProfileResult {
            profileCalls++
            return profileResult
        }
        override suspend fun getUserThreads(uid: String, page: Int, searchId: String?, priority: AccessPriority): UserThreadsResult = threadsResult
    }

    private class FakeProfileBlacklistRepo(
        private var pendingAdd: CompletableDeferred<BlacklistMutationResult>? = null,
    ) : BlacklistRepository {
        private val _blocked = MutableStateFlow<Set<String>>(emptySet())
        override val blockedUsernames: StateFlow<Set<String>> = _blocked.asStateFlow()
        override val lastSyncedAtMs: StateFlow<Long?> = MutableStateFlow(null).asStateFlow()
        val addCalls = mutableListOf<String>()
        val removeCalls = mutableListOf<String>()

        override fun isBlocked(username: String): Boolean = username in _blocked.value
        override suspend fun syncBlacklist(priority: AccessPriority): BlacklistSyncResult =
            BlacklistSyncResult.Success(BlacklistSnapshot("1", _blocked.value, 1L), _blocked.value.isEmpty())

        fun applyAdd(username: String) { _blocked.update { it + username } }
        fun clearPendingAdd() { pendingAdd = null }

        override suspend fun addToBlacklist(username: String, formhash: String?, priority: AccessPriority): BlacklistMutationResult {
            addCalls += username
            pendingAdd?.let { return it.await() }
            _blocked.update { it + username }
            return BlacklistMutationResult.Success(username, _blocked.value)
        }

        override suspend fun removeFromBlacklist(username: String, formhash: String?, priority: AccessPriority): BlacklistMutationResult {
            removeCalls += username
            _blocked.update { it - username }
            return BlacklistMutationResult.Success(username, _blocked.value)
        }
    }
}
