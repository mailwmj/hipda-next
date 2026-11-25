package net.jejer.hipda.feature.forum

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.data.search.*
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.threadlist.*
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.parser.threadlist.ThreadSummary
import net.jejer.hipda.ui.challenge.ChallengeFailureKind

@Immutable
data class ForumUiState(
    val source: ForumListSource = ForumListSource.Forum(AppSettings.DEFAULT_FOLLOWED_FORUMS.first()),
    val followedForums: List<Int> = AppSettings.DEFAULT_FOLLOWED_FORUMS,
    val sortByPostTime: Boolean = false,
    val items: List<ThreadListItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: UiError? = null,
    val challengeFailure: ChallengeFailureKind? = null,
    val challengeState: ChallengeState = ChallengeState.Idle,
)

private data class TodayNewState(
    val rawThreads: List<ThreadSummary> = emptyList(),
    val page: Int = 0,
    val searchId: String? = null,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: UiError? = null,
    val challengeFailure: ChallengeFailureKind? = null,
)

/** 最薄真实版块/今日新帖列表 ViewModel（遵守 `R-D4 S1`，显式传 `AppSettings.isSortByPostTime(fid)`）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ForumViewModel @Inject constructor(
    private val threadListRepository: ThreadListRepository,
    private val searchRepository: SearchRepository,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val siteAccessGateway: SiteAccessGateway,
) : ViewModel() {

    private val activeSource = MutableStateFlow<ForumListSource>(ForumListSource.Forum(AppSettings.DEFAULT_FOLLOWED_FORUMS.first()))
    private val latestSettings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val initializedQueries = HashSet<ThreadListQuery>()
    private var todayNewInitialized = false
    private val todayNewState = MutableStateFlow(TodayNewState())
    private val todayNewItems = threadListRepository.observeFiltered(todayNewState.map { it.rawThreads }.distinctUntilChanged())

    val uiState: StateFlow<ForumUiState> = combine(activeSource, latestSettings) { src, s -> src to s }
        .distinctUntilChanged()
        .flatMapLatest { (src, s) ->
            when (src) {
                ForumListSource.TodayNew -> combine(todayNewState, todayNewItems, siteAccessGateway.challengeState) { tn, items, ch ->
                    ForumUiState(src, s.followedForums, false, items, tn.isLoading, tn.isLoadingMore, tn.hasMore, tn.error, tn.challengeFailure, ch)
                }
                is ForumListSource.Forum -> {
                    val q = ThreadListQuery(fid = src.fid, sortByPostTime = s.isSortByPostTime(src.fid))
                    combine(threadListRepository.observeThreadList(q), siteAccessGateway.challengeState) { load, ch ->
                        val err = load.error
                        val cf = (err as? ThreadListError.ChallengeRequired)?.let { if (it.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete }
                        ForumUiState(src, s.followedForums, load.query.sortByPostTime ?: s.isSortByPostTime(src.fid), load.items, load.isLoading, load.isLoadingMore, load.hasMore, err?.let(UiErrorMapper::map), cf, ch)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ForumUiState())

    init {
        viewModelScope.launch {
            combine(activeSource, latestSettings) { src, s -> src to (src as? ForumListSource.Forum)?.let { s.isSortByPostTime(it.fid) } }
                .distinctUntilChanged()
                .collect { (src, sort) ->
                    when (src) {
                        ForumListSource.TodayNew -> if (!todayNewInitialized) { todayNewInitialized = true; loadTodayNew(1, false, AccessPriority.UserBlocking) }
                        is ForumListSource.Forum -> ThreadListQuery(src.fid, sort ?: latestSettings.value.isSortByPostTime(src.fid)).let { q -> if (initializedQueries.add(q)) threadListRepository.refresh(q) }
                    }
                }
        }
        viewModelScope.launch {
            var lastUid = authRepository.currentAccount.value?.uid
            authRepository.currentAccount.map { it?.uid }.distinctUntilChanged().collect { uid ->
                if (uid != lastUid) { lastUid = uid; initializedQueries.clear(); todayNewInitialized = false; refresh() }
            }
        }
    }

    fun bindSource(source: ForumListSource) { if (activeSource.value != source) activeSource.value = source }

    fun refresh(priority: AccessPriority = AccessPriority.UserBlocking) {
        when (val src = activeSource.value) {
            ForumListSource.TodayNew -> { todayNewInitialized = true; viewModelScope.launch { loadTodayNew(1, false, priority) } }
            is ForumListSource.Forum -> forumQuery(src.fid).also(initializedQueries::add).let { q -> viewModelScope.launch { threadListRepository.refresh(q, priority = priority) } }
        }
    }

    fun loadMore(priority: AccessPriority = AccessPriority.UserBlocking) {
        val cur = uiState.value
        if (cur.isLoading || cur.isLoadingMore || !cur.hasMore) return
        when (val src = activeSource.value) {
            ForumListSource.TodayNew -> viewModelScope.launch { loadTodayNew(todayNewState.value.page + 1, true, priority) }
            is ForumListSource.Forum -> viewModelScope.launch { threadListRepository.loadMore(forumQuery(src.fid), priority = priority) }
        }
    }

    fun retry(priority: AccessPriority = AccessPriority.UserBlocking) = refresh(priority)

    private suspend fun loadTodayNew(page: Int, append: Boolean, priority: AccessPriority) {
        todayNewState.update { it.copy(isLoading = !append, isLoadingMore = append, error = null, challengeFailure = null) }
        when (val res = searchRepository.getNewPosts(page = page, searchId = if (append) todayNewState.value.searchId else null, priority = priority)) {
            is SearchResult.Success -> {
                val mapped = res.items.mapNotNull(SearchItem::toThreadSummary)
                todayNewState.update { cur ->
                    val merged = if (append) (cur.rawThreads + mapped).distinctBy(ThreadSummary::tid) else mapped
                    cur.copy(rawThreads = merged, page = res.page, searchId = res.searchId, hasMore = res.hasNextPage, isLoading = false, isLoadingMore = false)
                }
            }
            is SearchError -> todayNewState.update {
                val cf = (res as? SearchError.ChallengeRequired)?.let { c -> if (c.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete }
                it.copy(isLoading = false, isLoadingMore = false, error = UiErrorMapper.map(res), challengeFailure = cf)
            }
        }
    }

    private fun forumQuery(fid: Int) = ThreadListQuery(fid = fid, sortByPostTime = latestSettings.value.isSortByPostTime(fid))
}

private fun SearchItem.toThreadSummary(): ThreadSummary? = tid?.takeIf(String::isNotBlank)?.let {
    ThreadSummary(it, title, null, forumName, false, false, false, author.orEmpty(), authorUid.orEmpty(), avatarUrl, time.orEmpty(), time, "", "", "", false, 1)
}
