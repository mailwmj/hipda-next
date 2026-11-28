package net.jejer.hipda.feature.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
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
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.search.MyRepliesResult
import net.jejer.hipda.data.search.MyThreadsResult
import net.jejer.hipda.data.search.RemovedSearchHistoryEntry
import net.jejer.hipda.data.search.ResolvedForumLink
import net.jejer.hipda.data.search.SearchRepository
import net.jejer.hipda.data.search.SearchResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.threadlist.ThreadListBatchResult
import net.jejer.hipda.data.threadlist.ThreadListItem
import net.jejer.hipda.data.threadlist.ThreadListLoadResult
import net.jejer.hipda.data.threadlist.ThreadListLoadState
import net.jejer.hipda.data.threadlist.ThreadListQuery
import net.jejer.hipda.data.threadlist.ThreadListRepository
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.threadlist.ThreadSummary
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * U5b 修复轮 1 回归测试（移植自 V-U5b `verify/U5b-probes @ a0cceec` 的 `probe3b_1` / `probe3b_2`，补充入场途中切号与抬手前禁跟手断言）。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ForumSwipeAccountSwitchRegressionTest {

    private fun androidx.compose.ui.test.ComposeUiTest.findForumVerticalListNode(): SemanticsNode? =
        onAllNodes(SemanticsMatcher("verticalScroll") { SemanticsProperties.VerticalScrollAxisRange in it.config })
            .fetchSemanticsNodes()
            .firstOrNull()

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe3b_1_interruptByAccountSwitchReload_midDrag_resetsOffsetToZeroAndDisablesRemainingGesture() =
        withAnimatorDurationScale(1f) {
            val harness = AccountSwitchProbeHarness()
            setContent { harness.RenderRoot() }
            waitForIdle()
            onNodeWithText("版块2帖 1 [uid=42]").assertIsDisplayed()

            // 1. 拖到一半（-35%，手指仍按住）时账号切换导致重载
            onRoot().performTouchInput {
                down(Offset(width * 0.7f, height * 0.55f))
                repeat(7) {
                    moveBy(Offset(-width * 0.05f, 0f))
                    advanceEventTime(16L)
                }
            }
            waitForIdle()
            val midOffset = findForumVerticalListNode()!!.positionInRoot.x
            assertTrue("账号切换前应处于拖动偏移态，实际=$midOffset", midOffset < -50f)

            runOnIdle { harness.switchAccount(uid = "99", username = "User99") }
            waitForIdle()
            onNodeWithText("版块2帖 1 [uid=99]").assertIsDisplayed()
            val offsetAfterAccountSwitchWhileHeld = findForumVerticalListNode()!!.positionInRoot.x
            assertEquals(
                "账号切换重载后即使未抬手也不得残留半偏移，实际=$offsetAfterAccountSwitchWhileHeld",
                0f,
                offsetAfterAccountSwitchWhileHeld,
                0.5f,
            )

            // 2. 本次手势抬手前继续拖动不得再跟手，抬手后也不得切版块
            onRoot().performTouchInput {
                repeat(4) {
                    moveBy(Offset(-width * 0.05f, 0f))
                    advanceEventTime(16L)
                }
            }
            waitForIdle()
            assertEquals(
                "切号后本次手势抬手前不得继续跟手",
                0f,
                findForumVerticalListNode()!!.positionInRoot.x,
                0.5f,
            )

            onRoot().performTouchInput { up() }
            waitForIdle()
            assertEquals(ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)

            // 3. 抬手后发起新的横滑应恢复正常可用
            onRoot().performTouchInput {
                down(Offset(width * 0.78f, height * 0.55f))
                repeat(9) {
                    moveBy(Offset(-width * 0.05f, 0f))
                    advanceEventTime(24L)
                }
                advanceEventTime(100L)
                up()
            }
            waitForIdle()
            assertEquals(ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)
            onNodeWithText("版块6帖 1 [uid=99]").assertIsDisplayed()
        }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe3b_2_interruptByAccountSwitchReload_midExitAnimation_cancelsStaleSwitchAndResetsOffset() =
        withAnimatorDurationScale(1f) {
            val harness = AccountSwitchProbeHarness()
            setContent { harness.RenderRoot() }
            waitForIdle()
            onNodeWithText("版块2帖 1 [uid=42]").assertIsDisplayed()

            // 退场动画第 2 帧切号（42 -> 108），必须取消退场协程与待交接态，不再切至 Forum(6)
            mainClock.autoAdvance = false
            try {
                onRoot().performTouchInput {
                    down(Offset(width * 0.7f, height * 0.55f))
                    repeat(8) {
                        moveBy(Offset(-width * 0.05f, 0f))
                        advanceEventTime(16L)
                    }
                    up()
                }
                mainClock.advanceTimeByFrame()
                mainClock.advanceTimeByFrame()
                harness.switchAccount(uid = "108", username = "User108")
            } finally {
                mainClock.autoAdvance = true
            }
            waitForIdle()
            assertEquals(
                "退场动画途中账号切换重载后不应继续完成过期版块切换",
                ForumListSource.Forum(2),
                harness.hostViewModel.state.value.activeForumSource,
            )
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
            assertNull("切号后待交接状态必须清空", harness.forumViewModel.consumeSwipeHandoff(ForumListSource.Forum(6)))
            onNodeWithText("版块2帖 1 [uid=108]").assertIsDisplayed()
        }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe3b_3_interruptByAccountSwitchReload_midEnterAnimation_cancelsEnterAndResetsOffset() =
        withAnimatorDurationScale(1f) {
            val harness = AccountSwitchProbeHarness()
            setContent { harness.RenderRoot() }
            waitForIdle()
            onNodeWithText("版块2帖 1 [uid=42]").assertIsDisplayed()

            mainClock.autoAdvance = false
            var sawMidEnterOffset = 0f
            try {
                onRoot().performTouchInput {
                    down(Offset(width * 0.7f, height * 0.55f))
                    repeat(8) {
                        moveBy(Offset(-width * 0.05f, 0f))
                        advanceEventTime(16L)
                    }
                    up()
                }
                // 推进至切入 Forum(6) 入场动画进行中（偏移 > 50px）
                for (frame in 0 until 30) {
                    mainClock.advanceTimeByFrame()
                    if (harness.hostViewModel.state.value.activeForumSource == ForumListSource.Forum(6)) {
                        val pos = findForumVerticalListNode()?.positionInRoot?.x ?: 0f
                        if (pos > 50f) {
                            sawMidEnterOffset = pos
                            break
                        }
                    }
                }
                assertTrue("应已进入 Forum(6) 入场动画中段（actual=$sawMidEnterOffset）", sawMidEnterOffset > 50f)
                harness.switchAccount(uid = "209", username = "User209")
                // 推进至新账号 uid=209 首帧渲染（远早于入场弹簧结束），首帧偏移必须已归 0
                var firstNewUidOffset: Float? = null
                for (frame in 0 until 5) {
                    mainClock.advanceTimeByFrame()
                    if (onAllNodes(SemanticsMatcher("uid209") {
                            it.config.getOrElseNullable(SemanticsProperties.Text) { null }
                                ?.any { t -> t.text == "版块6帖 1 [uid=209]" } == true
                        }).fetchSemanticsNodes().isNotEmpty()
                    ) {
                        firstNewUidOffset = findForumVerticalListNode()?.positionInRoot?.x
                        break
                    }
                }
                assertEquals(
                    "入场动画途中切号后新账号首帧必须已取消入场并归 0（actual=$firstNewUidOffset）",
                    0f,
                    firstNewUidOffset ?: Float.NaN,
                    0.5f,
                )
            } finally {
                mainClock.autoAdvance = true
            }
            waitForIdle()
            assertEquals(ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
            onNodeWithText("版块6帖 1 [uid=209]").assertIsDisplayed()
        }

    private class AccountSwitchProbeHarness {
        val settingsRepo = RecordingSettingsRepository(AppSettings(followedForums = listOf(2, 6, 7)))
        private val currentAccount = MutableStateFlow<SavedAccount?>(
            SavedAccount("42", "User42", "0".repeat(32), lastLoginAt = 1L),
        )
        private val authState = MutableStateFlow<AuthState>(AuthState.SignedIn(currentAccount.value!!))
        private val sessionEpochFlow = MutableStateFlow(1L)

        val authRepo = object : AuthRepository {
            override val currentAccount: StateFlow<SavedAccount?> = this@AccountSwitchProbeHarness.currentAccount.asStateFlow()
            override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOfNotNull(currentAccount.value))
            override val authState: StateFlow<AuthState> = this@AccountSwitchProbeHarness.authState.asStateFlow()
            override val sessionEpoch: Long get() = sessionEpochFlow.value
            override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) = LoginResult.Failure("offline")
            override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
            override suspend fun logout() { this@AccountSwitchProbeHarness.currentAccount.value = null }
            override suspend fun removeAccount(uid: String) = Unit
            override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
        }

        fun switchAccount(uid: String, username: String) {
            val next = SavedAccount(uid, username, "1".repeat(32), lastLoginAt = System.currentTimeMillis())
            sessionEpochFlow.value += 1L
            currentAccount.value = next
            authState.value = AuthState.SignedIn(next)
        }

        private val smsUnread = MutableStateFlow(0)
        private val notifyUnread = MutableStateFlow(0)
        private val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })

        private val threadListRepo = object : ThreadListRepository {
            private val states = ConcurrentHashMap<ThreadListQuery, MutableStateFlow<ThreadListLoadState>>()
            private fun stateFor(q: ThreadListQuery) = states.getOrPut(q) { MutableStateFlow(ThreadListLoadState(query = q)) }

            override suspend fun loadPage(
                query: ThreadListQuery, startPage: Int, existingTids: Set<String>,
                minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority,
            ) = refresh(query, minVisibleItems, maxAutoBackfillPages, priority)

            override suspend fun refresh(
                query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority,
            ): ThreadListLoadResult {
                val uid = currentAccount.value?.uid ?: "guest"
                val summaries = (1..20).map { idx ->
                    val tid = (query.fid * 1000 + idx).toString()
                    ThreadSummary(tid, "版块${query.fid}帖 $idx [uid=$uid]", null, null, false, false, false, "作者$idx", "$idx", null, "2026-11-28", "刚刚", "回复$idx", "$idx", "${idx * 10}", false, 1)
                }
                val items = summaries.map { ThreadListItem(it, false) }
                stateFor(query).value = ThreadListLoadState(query, items, 1, 2, false, 1, offlinePageMeta(), isLoading = false, error = null)
                return ThreadListBatchResult(query, query.sortByPostTime == true, summaries, items, 1, 2, false, 1, offlinePageMeta())
            }

            override suspend fun loadMore(
                query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority,
            ) = refresh(query, minVisibleItems, maxAutoBackfillPages, priority)

            override fun observeThreadList(query: ThreadListQuery): Flow<ThreadListLoadState> = stateFor(query)
            override fun observeFiltered(rawThreads: Flow<List<ThreadSummary>>) = rawThreads.map { l -> l.map { ThreadListItem(it, false) } }
        }

        private val searchRepo = object : SearchRepository {
            override val searchHistory: Flow<List<String>> = MutableStateFlow(emptyList())
            override suspend fun search(
                query: String, author: String, forumId: Int?, fullText: Boolean,
                page: Int, searchId: String?, priority: AccessPriority, recordHistory: Boolean,
            ) = getNewPosts(page, searchId, priority)
            override suspend fun getNewPosts(page: Int, searchId: String?, priority: AccessPriority) =
                SearchResult.Success(page, false, SearchPage("sid-probe", 1, emptyList(), offlinePageMeta()))
            override suspend fun getMyThreads(page: Int, priority: AccessPriority): MyThreadsResult = throw UnsupportedOperationException()
            override suspend fun getMyReplies(page: Int, priority: AccessPriority): MyRepliesResult = throw UnsupportedOperationException()
            override suspend fun recordSearchHistory(query: String) = emptyList<String>()
            override suspend fun removeSearchHistory(query: String): RemovedSearchHistoryEntry? = null
            override suspend fun restoreSearchHistory(query: String, index: Int) = emptyList<String>()
            override suspend fun clearSearchHistory() = Unit
            override suspend fun resolveForumLink(rawInput: String): ResolvedForumLink? = null
        }

        private val threadDetailRepo = OfflineThreadDetailRepository()
        val hostViewModel = NextAppHostViewModel(settingsRepo, threadDetailRepo)
        val forumViewModel = ForumViewModel(
            threadListRepo, searchRepo, settingsRepo, authRepo, gateway,
            todayProvider = { LocalDate.of(2026, 11, 28) },
        )

        private val vmFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostViewModel as T
                modelClass.isAssignableFrom(ForumViewModel::class.java) -> forumViewModel as T
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) -> ThreadDetailViewModel(threadDetailRepo, gateway) as T
                else -> throw IllegalArgumentException("Unexpected ViewModel: $modelClass")
            }
        }

        private val parentOwner: ViewModelStoreOwner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore = ViewModelStore()
            override val defaultViewModelProviderFactory = vmFactory
        }

        @androidx.compose.runtime.Composable
        fun RenderRoot() {
            ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides parentOwner) {
                    NextAppRoot(
                        settingsFlow = settingsRepo.settings,
                        currentAccountFlow = authRepo.currentAccount,
                        smsUnreadCountFlow = smsUnread.asStateFlow(),
                        notifyUnreadCountFlow = notifyUnread.asStateFlow(),
                        hostViewModel = hostViewModel,
                        forumViewModel = forumViewModel,
                    )
                }
            }
        }
    }
}
