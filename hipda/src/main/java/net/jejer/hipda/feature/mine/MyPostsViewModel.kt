package net.jejer.hipda.feature.mine

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.data.search.*
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.common.UiErrorMapper

enum class MyPostsTab { Threads, Replies }

@Immutable
data class MyPostRow(val key: String, val tid: String, val pid: String?, val title: String, val preview: String?, val forumName: String?, val time: String)

@Immutable
data class MyPostsUiState(
    val selectedTab: MyPostsTab = MyPostsTab.Threads, val isSignedIn: Boolean = false,
    val tabs: Map<MyPostsTab, PagedTabState<MyPostRow>> = emptyMap(), val challengeState: ChallengeState = ChallengeState.Idle,
) {
    fun tabState(tab: MyPostsTab): PagedTabState<MyPostRow> = tabs[tab] ?: PagedTabState()
}

/** 「我的帖子 / 我的回复」ViewModel：独立分页状态、自动恢复登录态、错误映射。 */
class MyPostsViewModel(
    private val searchRepository: SearchRepository,
    private val authRepository: AuthRepository,
    siteAccessGateway: SiteAccessGateway,
    initialTab: MyPostsTab = MyPostsTab.Threads,
) : ViewModel() {

    private val state = MutableStateFlow(MyPostsUiState(selectedTab = initialTab, isSignedIn = authRepository.currentAccount.value != null))
    private val _refreshErrors = MutableSharedFlow<UiError>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val loadJobs = HashMap<MyPostsTab, Job>(); private var authGen = 0L

    val refreshErrors: SharedFlow<UiError> = _refreshErrors.asSharedFlow()

    val uiState: StateFlow<MyPostsUiState> = combine(state, authRepository.currentAccount, siteAccessGateway.challengeState) { s, acc, ch ->
        s.copy(isSignedIn = acc != null, challengeState = ch)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), state.value)

    init {
        viewModelScope.observeAuthSession(authRepository) { uid ->
            authGen++; loadJobs.values.forEach(Job::cancel); loadJobs.clear()
            state.update { MyPostsUiState(selectedTab = it.selectedTab, isSignedIn = uid != null) }
            if (uid != null) loadTab(state.value.selectedTab, 1, false, false, AccessPriority.UserBlocking)
        }
    }

    fun selectTab(tab: MyPostsTab) {
        if (state.value.selectedTab != tab) state.update { it.copy(selectedTab = tab) }
        val st = state.value.tabState(tab)
        if (authRepository.currentAccount.value != null && !st.hasLoadedOnce && !st.isLoading) loadTab(tab, 1, false, false, AccessPriority.UserBlocking)
    }

    fun refresh(tab: MyPostsTab = state.value.selectedTab, priority: AccessPriority = AccessPriority.UserBlocking) { if (authRepository.currentAccount.value != null) loadTab(tab, 1, false, state.value.tabState(tab).items.isNotEmpty(), priority) }
    fun retry(tab: MyPostsTab = state.value.selectedTab, priority: AccessPriority = AccessPriority.UserBlocking) { if (authRepository.currentAccount.value != null) loadTab(tab, 1, false, false, priority) }
    fun loadMore(tab: MyPostsTab = state.value.selectedTab, priority: AccessPriority = AccessPriority.UserBlocking) {
        if (authRepository.currentAccount.value == null) return
        val st = state.value.tabState(tab)
        if (!st.isLoading && !st.isRefreshing && !st.isLoadingMore && st.hasMore) loadTab(tab, st.page + 1, true, false, priority)
    }

    private fun loadTab(tab: MyPostsTab, page: Int, append: Boolean, isRefresh: Boolean, priority: AccessPriority) {
        val gen = authGen; loadJobs[tab]?.cancel(); updateTab(tab) { it.markLoading(append, isRefresh) }
        loadJobs[tab] = viewModelScope.launch {
            val res: Any = if (tab == MyPostsTab.Threads) searchRepository.getMyThreads(page, priority) else searchRepository.getMyReplies(page, priority)
            if (gen != authGen) return@launch
            when (res) {
                is MyThreadsResult.Success -> applySuccess(tab, append, res.page, res.hasNextPage, res.items.map { MyPostRow("thread:${it.tid}", it.tid, null, it.title, null, it.forumName, it.time) })
                is MyRepliesResult.Success -> applySuccess(tab, append, res.page, res.hasNextPage, res.items.map { MyPostRow("reply:${it.tid}:${it.pid}", it.tid, it.pid.takeIf(String::isNotBlank), it.title, it.replyText, it.forumName, it.time) })
                is SearchError -> {
                    val mapped = UiErrorMapper.map(res); val cf = (res as? SearchError.ChallengeRequired)?.let { challengeKind(it.rejected) }
                    val keep = isRefresh && state.value.tabState(tab).items.isNotEmpty()
                    updateTab(tab) { it.markError(keep, mapped, cf) }
                    if (keep && res !is SearchError.AuthRequired) _refreshErrors.tryEmit(mapped)
                }
            }
        }
    }

    private fun applySuccess(tab: MyPostsTab, append: Boolean, page: Int, hasMore: Boolean, rows: List<MyPostRow>) =
        updateTab(tab) { cur -> cur.markSuccess((if (append) cur.items + rows else rows).distinctBy(MyPostRow::key), page, hasMore) }

    private fun updateTab(tab: MyPostsTab, fn: (PagedTabState<MyPostRow>) -> PagedTabState<MyPostRow>) =
        state.update { it.copy(tabs = it.tabs + (tab to fn(it.tabState(tab)))) }
}
