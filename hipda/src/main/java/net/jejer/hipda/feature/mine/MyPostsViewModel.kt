package net.jejer.hipda.feature.mine

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.search.*
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.common.UiErrorMapper
import net.jejer.hipda.parser.simplelist.MyReplyItem
import net.jejer.hipda.parser.simplelist.MyThreadItem
import net.jejer.hipda.ui.challenge.ChallengeFailureKind

enum class MyPostsTab { Threads, Replies }

@Immutable
data class MyPostsUiState(
    val selectedTab: MyPostsTab = MyPostsTab.Threads,
    val isSignedIn: Boolean = false,
    val threads: PagedTabState<MyThreadItem> = PagedTabState(),
    val replies: PagedTabState<MyReplyItem> = PagedTabState(),
    val challengeState: ChallengeState = ChallengeState.Idle,
)

/** 「我的帖子 / 我的回复」ViewModel：独立分页状态、自动恢复登录态、错误映射。 */
class MyPostsViewModel(
    private val searchRepository: SearchRepository,
    private val authRepository: AuthRepository,
    siteAccessGateway: SiteAccessGateway,
    initialTab: MyPostsTab = MyPostsTab.Threads,
) : ViewModel() {

    private val state = MutableStateFlow(MyPostsUiState(selectedTab = initialTab, isSignedIn = authRepository.currentAccount.value != null))
    private val _refreshErrors = MutableSharedFlow<UiError>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val loadJobs = HashMap<MyPostsTab, Job>()
    private var authGen: Long = 0L

    val refreshErrors: SharedFlow<UiError> = _refreshErrors.asSharedFlow()

    val uiState: StateFlow<MyPostsUiState> = combine(state, authRepository.currentAccount, siteAccessGateway.challengeState) { s, acc, ch ->
        s.copy(isSignedIn = acc != null, challengeState = ch)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), state.value)

    init {
        viewModelScope.observeAuthSession(authRepository) { uid ->
            authGen++
            loadJobs.values.forEach(Job::cancel); loadJobs.clear()
            state.update { MyPostsUiState(selectedTab = it.selectedTab, isSignedIn = uid != null) }
            if (uid != null) loadTab(state.value.selectedTab, 1, false, false, AccessPriority.UserBlocking)
        }
    }

    fun selectTab(tab: MyPostsTab) {
        if (state.value.selectedTab != tab) state.update { it.copy(selectedTab = tab) }
        val loaded = if (tab == MyPostsTab.Threads) state.value.threads.let { it.hasLoadedOnce || it.isLoading } else state.value.replies.let { it.hasLoadedOnce || it.isLoading }
        if (authRepository.currentAccount.value != null && !loaded) loadTab(tab, 1, false, false, AccessPriority.UserBlocking)
    }

    fun refresh(tab: MyPostsTab = state.value.selectedTab, priority: AccessPriority = AccessPriority.UserBlocking) {
        if (authRepository.currentAccount.value == null) return
        val hasItems = if (tab == MyPostsTab.Threads) state.value.threads.items.isNotEmpty() else state.value.replies.items.isNotEmpty()
        loadTab(tab, 1, false, hasItems, priority)
    }

    fun loadMore(tab: MyPostsTab = state.value.selectedTab, priority: AccessPriority = AccessPriority.UserBlocking) {
        if (authRepository.currentAccount.value == null) return
        val st = if (tab == MyPostsTab.Threads) state.value.threads else state.value.replies
        if (!st.isLoading && !st.isRefreshing && !st.isLoadingMore && st.hasMore) loadTab(tab, st.page + 1, true, false, priority)
    }

    fun retry(tab: MyPostsTab = state.value.selectedTab, priority: AccessPriority = AccessPriority.UserBlocking) {
        if (authRepository.currentAccount.value != null) loadTab(tab, 1, false, false, priority)
    }

    private fun loadTab(tab: MyPostsTab, page: Int, append: Boolean, isRefresh: Boolean, priority: AccessPriority) {
        val gen = authGen
        loadJobs[tab]?.cancel()
        if (tab == MyPostsTab.Threads) {
            state.update { it.copy(threads = it.threads.markLoading(append, isRefresh)) }
            loadJobs[tab] = viewModelScope.launch {
                when (val res = searchRepository.getMyThreads(page, priority)) {
                    is MyThreadsResult.Success -> if (gen == authGen) state.update { cur ->
                        cur.copy(threads = cur.threads.markSuccess((if (append) cur.threads.items + res.items else res.items).distinctBy(MyThreadItem::tid), res.page, res.hasNextPage))
                    }
                    is SearchError -> handleError(tab, gen, isRefresh, res)
                }
            }
        } else {
            state.update { it.copy(replies = it.replies.markLoading(append, isRefresh)) }
            loadJobs[tab] = viewModelScope.launch {
                when (val res = searchRepository.getMyReplies(page, priority)) {
                    is MyRepliesResult.Success -> if (gen == authGen) state.update { cur ->
                        cur.copy(replies = cur.replies.markSuccess((if (append) cur.replies.items + res.items else res.items).distinctBy { "${it.tid}:${it.pid}" }, res.page, res.hasNextPage))
                    }
                    is SearchError -> handleError(tab, gen, isRefresh, res)
                }
            }
        }
    }

    private fun handleError(tab: MyPostsTab, gen: Long, isRefresh: Boolean, err: SearchError) {
        if (gen != authGen) return
        val mapped = UiErrorMapper.map(err)
        val cf = (err as? SearchError.ChallengeRequired)?.let { if (it.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete }
        val keep = isRefresh && (if (tab == MyPostsTab.Threads) state.value.threads.items.isNotEmpty() else state.value.replies.items.isNotEmpty())
        state.update { if (tab == MyPostsTab.Threads) it.copy(threads = it.threads.markError(keep, mapped, cf)) else it.copy(replies = it.replies.markError(keep, mapped, cf)) }
        if (keep && err !is SearchError.AuthRequired) _refreshErrors.tryEmit(mapped)
    }
}
