package net.jejer.hipda.feature.search

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
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.feature.common.UiErrorMapper
import net.jejer.hipda.feature.mine.observeAuthSession
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.ui.challenge.ChallengeFailureKind

enum class SearchFilterSection { Forum, Scope, Author }

@Immutable
data class SearchUiState(
    val query: String = "",
    val author: String = "",
    val selectedForumId: Int? = null,
    val fullText: Boolean = false,
    val expandedFilterSection: SearchFilterSection? = null,
    val resolvedLink: AllowedForumLink? = null,
    val forumServer: String = AppSettings.DEFAULT_FORUM_SERVER,
    val followedForums: List<Int> = AppSettings.DEFAULT_FOLLOWED_FORUMS,
    val recentHistory: List<String> = emptyList(),
    val hasSubmitted: Boolean = false,
    val items: List<SearchItem> = emptyList(),
    val page: Int = 0,
    val searchId: String? = null,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: UiError? = null,
    val challengeFailure: ChallengeFailureKind? = null,
    val challengeState: ChallengeState = ChallengeState.Idle,
) {
    val hasActiveFilters: Boolean get() = selectedForumId != null || fullText || author.isNotBlank()
    val isFilterPanelExpanded: Boolean get() = expandedFilterSection != null
}

sealed interface SearchSnackbarEvent {
    data class HistoryDeleted(val entry: RemovedSearchHistoryEntry) : SearchSnackbarEvent
    data class RefreshError(val error: UiError) : SearchSnackbarEvent
}

/** 搜索页 ViewModel：实时站内链接识别、可撤销最近搜索、筛选条件与分页加载。 */
class SearchViewModel(
    private val searchRepository: SearchRepository,
    settingsRepository: SettingsRepository,
    authRepository: AuthRepository,
    siteAccessGateway: SiteAccessGateway,
) : ViewModel() {

    private val state = MutableStateFlow(SearchUiState())
    private val _snackbarEvents = MutableSharedFlow<SearchSnackbarEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var activeJob: Job? = null
    private var requestGen: Long = 0L

    val snackbarEvents: SharedFlow<SearchSnackbarEvent> = _snackbarEvents.asSharedFlow()

    val uiState: StateFlow<SearchUiState> = combine(
        state, searchRepository.searchHistory, settingsRepository.settings, siteAccessGateway.challengeState,
    ) { s, history, settings, challenge ->
        s.copy(
            resolvedLink = resolveAllowedForumLink(s.query, settings.forumServer),
            forumServer = settings.forumServer,
            followedForums = settings.followedForums,
            recentHistory = history,
            challengeState = challenge,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SearchUiState())

    init {
        var initialized = false
        viewModelScope.observeAuthSession(authRepository) { uid ->
            if (!initialized) { initialized = true; return@observeAuthSession }
            val cur = state.value
            if (uid != null && cur.hasSubmitted && cur.error?.action == UiErrorAction.Login) submitSearch()
        }
    }

    fun onQueryChange(newQuery: String) {
        state.update { it.copy(query = newQuery) }
        if (newQuery.isBlank() && state.value.author.isBlank() && state.value.hasSubmitted) resetToInitialState()
    }

    fun clearQuery() {
        state.update { it.copy(query = "") }
        if (state.value.author.isBlank()) resetToInitialState()
    }

    fun toggleFilterSection(section: SearchFilterSection) =
        state.update { it.copy(expandedFilterSection = if (it.expandedFilterSection == section) null else section) }

    fun selectForum(fid: Int?) {
        state.update { it.copy(selectedForumId = fid, expandedFilterSection = null) }
        if (state.value.hasSubmitted) submitSearch()
    }

    fun selectFullText(fullText: Boolean) {
        state.update { it.copy(fullText = fullText, expandedFilterSection = null) }
        if (state.value.hasSubmitted) submitSearch()
    }

    fun onAuthorChange(author: String) = state.update { it.copy(author = author) }

    fun submitHistoryQuery(historyQuery: String) {
        state.update { it.copy(query = historyQuery, expandedFilterSection = null) }
        submitSearch()
    }

    fun submitSearch(priority: AccessPriority = AccessPriority.UserBlocking) {
        val s = state.value
        if (s.query.isBlank() && s.author.isBlank()) return
        state.update { it.copy(expandedFilterSection = null) }
        executeRequest(page = 1, append = false, isRefresh = false, priority = priority)
    }

    fun refresh(priority: AccessPriority = AccessPriority.UserBlocking) {
        val s = state.value
        if (!s.hasSubmitted || (s.query.isBlank() && s.author.isBlank())) return
        executeRequest(page = 1, append = false, isRefresh = s.items.isNotEmpty(), priority = priority)
    }

    fun loadMore(priority: AccessPriority = AccessPriority.UserBlocking) {
        val s = state.value
        if (s.hasSubmitted && !s.isLoading && !s.isRefreshing && !s.isLoadingMore && s.hasMore) {
            executeRequest(page = s.page + 1, append = true, isRefresh = false, priority = priority)
        }
    }

    fun retry(priority: AccessPriority = AccessPriority.UserBlocking) {
        if (state.value.hasSubmitted) executeRequest(page = 1, append = false, isRefresh = false, priority = priority)
    }

    fun clearFiltersAndRetry() {
        state.update { it.copy(selectedForumId = null, fullText = false, author = "", expandedFilterSection = null) }
        if (state.value.query.isNotBlank()) submitSearch() else resetToInitialState()
    }

    fun deleteHistory(query: String) {
        viewModelScope.launch {
            searchRepository.removeSearchHistory(query)?.let { _snackbarEvents.tryEmit(SearchSnackbarEvent.HistoryDeleted(it)) }
        }
    }

    fun restoreHistory(entry: RemovedSearchHistoryEntry) {
        viewModelScope.launch { searchRepository.restoreSearchHistory(entry.query, entry.index) }
    }

    private fun resetToInitialState() {
        activeJob?.cancel(); requestGen++
        state.update { it.copy(hasSubmitted = false, items = emptyList(), page = 0, searchId = null, hasMore = false, isLoading = false, isRefreshing = false, isLoadingMore = false, error = null, challengeFailure = null) }
    }

    private fun executeRequest(page: Int, append: Boolean, isRefresh: Boolean, priority: AccessPriority) {
        val s = state.value
        val reqSearchId = if (append) s.searchId else null
        activeJob?.cancel()
        val gen = ++requestGen
        state.update {
            it.copy(
                hasSubmitted = true, items = if (append || isRefresh) it.items else emptyList(),
                isLoading = !append && !isRefresh, isRefreshing = isRefresh, isLoadingMore = append,
                error = null, challengeFailure = null,
            )
        }
        activeJob = viewModelScope.launch {
            val res = searchRepository.search(s.query, s.author, s.selectedForumId, s.fullText, page, reqSearchId, priority, recordHistory = !append)
            if (gen != requestGen) return@launch
            when (res) {
                is SearchResult.Success -> state.update { cur ->
                    val merged = (if (append) cur.items + res.items else res.items).distinctBy { "${it.tid.orEmpty()}:${it.pid.orEmpty()}:${it.title}" }
                    cur.copy(hasSubmitted = true, items = merged, page = res.page, searchId = if (append) (res.searchId ?: cur.searchId) else res.searchId, hasMore = res.hasNextPage, isLoading = false, isRefreshing = false, isLoadingMore = false, error = null, challengeFailure = null)
                }
                is SearchError -> {
                    val mapped = UiErrorMapper.map(res)
                    val cf = (res as? SearchError.ChallengeRequired)?.let { if (it.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete }
                    val keep = isRefresh && state.value.items.isNotEmpty()
                    state.update { it.copy(hasSubmitted = true, isLoading = false, isRefreshing = false, isLoadingMore = false, error = if (keep) null else mapped, challengeFailure = if (keep) null else cf) }
                    if (keep && res !is SearchError.AuthRequired) _snackbarEvents.tryEmit(SearchSnackbarEvent.RefreshError(mapped))
                }
            }
        }
    }
}
