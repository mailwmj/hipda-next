package net.jejer.hipda.feature.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.data.search.*
import net.jejer.hipda.data.settings.*
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.feature.mine.*
import net.jejer.hipda.parser.search.SearchItem

enum class SearchFilterSection { Forum, Scope, Author }

/** 单次删除最近搜索生成的独立撤销令牌（按前驱锚点与原序秩恢复，不依赖裸下标）。 */
@Immutable
data class SearchHistoryUndoToken(val id: Long, val query: String, val anchorQuery: String?)

@Immutable
data class SearchUiState(
    val query: String = "", val author: String = "", val selectedForumId: Int? = null, val fullText: Boolean = false,
    val expandedFilterSection: SearchFilterSection? = null, val resolvedLink: ResolvedForumLink? = null,
    val forumServer: String = AppSettings.DEFAULT_FORUM_SERVER, val followedForums: List<Int> = AppSettings.DEFAULT_FOLLOWED_FORUMS,
    val recentHistory: List<String> = emptyList(), val hasSubmitted: Boolean = false, val searchId: String? = null,
    val results: PagedTabState<SearchItem> = PagedTabState(), val challengeState: ChallengeState = ChallengeState.Idle,
) {
    val hasActiveFilters: Boolean get() = selectedForumId != null || fullText || author.isNotBlank()
}

sealed interface SearchSnackbarEvent {
    data class HistoryDeleted(val token: SearchHistoryUndoToken) : SearchSnackbarEvent
    data class RefreshError(val id: Long, val error: UiError) : SearchSnackbarEvent
}

/** 搜索页 ViewModel：实时站内链接识别、基于撤销令牌的最近搜索恢复、筛选条件与分页加载。 */
class SearchViewModel(
    private val searchRepository: SearchRepository,
    settingsRepository: SettingsRepository,
    authRepository: AuthRepository,
    siteAccessGateway: SiteAccessGateway,
) : ViewModel() {

    private val state = MutableStateFlow(SearchUiState())
    private val _snackbarEvents = MutableSharedFlow<SearchSnackbarEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val historyMutex = Mutex(); private val historyRanks = HashMap<String, Int>()
    private val undoManager = UndoTokenManager<SearchHistoryUndoToken>()
    private var latestHistory: List<String> = emptyList(); private var activeJob: Job? = null; private var requestGen = 0L

    val snackbarEvents: SharedFlow<SearchSnackbarEvent> = _snackbarEvents.asSharedFlow()

    val uiState: StateFlow<SearchUiState> = combine(
        state, searchRepository.searchHistory, settingsRepository.settings, siteAccessGateway.challengeState,
    ) { s, history, settings, challenge ->
        syncHistoryRanks(history); latestHistory = history
        s.copy(resolvedLink = resolveAllowedForumLink(s.query, settings.forumServer), forumServer = settings.forumServer, followedForums = settings.followedForums, recentHistory = history, challengeState = challenge)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), SearchUiState())

    init {
        viewModelScope.observeAuthSession(authRepository, skipInitial = true) { uid ->
            if (uid != null && state.value.hasSubmitted && state.value.results.error?.action == UiErrorAction.Login) submitSearch()
        }
    }

    fun onQueryChange(newQuery: String) { state.update { it.copy(query = newQuery) }; if (newQuery.isBlank() && state.value.author.isBlank() && state.value.hasSubmitted) resetToInitialState() }
    fun clearQuery() { state.update { it.copy(query = "") }; if (state.value.author.isBlank()) resetToInitialState() }
    fun toggleFilterSection(section: SearchFilterSection) = state.update { it.copy(expandedFilterSection = if (it.expandedFilterSection == section) null else section) }
    fun selectForum(fid: Int?) { state.update { it.copy(selectedForumId = fid, expandedFilterSection = null) }; if (state.value.hasSubmitted) submitSearch() }
    fun selectFullText(fullText: Boolean) { state.update { it.copy(fullText = fullText, expandedFilterSection = null) }; if (state.value.hasSubmitted) submitSearch() }
    fun onAuthorChange(author: String) { state.update { it.copy(author = author) }; if (author.isBlank() && state.value.query.isBlank() && state.value.hasSubmitted) resetToInitialState() }
    fun submitHistoryQuery(historyQuery: String) { state.update { it.copy(query = historyQuery, expandedFilterSection = null) }; submitSearch() }

    fun submitSearch(priority: AccessPriority = AccessPriority.UserBlocking) {
        if (state.value.query.isBlank() && state.value.author.isBlank()) return
        state.update { it.copy(expandedFilterSection = null) }
        executeRequest(1, false, false, priority)
    }

    fun refresh(priority: AccessPriority = AccessPriority.UserBlocking) {
        val s = state.value
        if (s.hasSubmitted && (s.query.isNotBlank() || s.author.isNotBlank())) executeRequest(1, false, s.results.items.isNotEmpty(), priority)
    }

    fun loadMore(priority: AccessPriority = AccessPriority.UserBlocking) {
        val s = state.value; val r = s.results
        if (s.hasSubmitted && !r.isLoading && !r.isRefreshing && !r.isLoadingMore && r.hasMore) executeRequest(r.page + 1, true, false, priority)
    }

    fun retry(priority: AccessPriority = AccessPriority.UserBlocking) { if (state.value.hasSubmitted) executeRequest(1, false, false, priority) }

    fun clearFiltersAndRetry() {
        state.update { it.copy(selectedForumId = null, fullText = false, author = "", expandedFilterSection = null) }
        if (state.value.query.isNotBlank()) submitSearch() else resetToInitialState()
    }

    fun deleteHistory(query: String) {
        val snapshot = latestHistory.ifEmpty { uiState.value.recentHistory }
        val idx = snapshot.indexOf(query).takeIf { it >= 0 } ?: return
        syncHistoryRanks(snapshot); latestHistory = snapshot.filterNot { it == query }
        val id = undoManager.nextId()
        val token = SearchHistoryUndoToken(id, query, if (idx > 0) snapshot[idx - 1] else null)
        undoManager.issue(token, id)
        _snackbarEvents.tryEmit(SearchSnackbarEvent.HistoryDeleted(token))
        viewModelScope.launch { historyMutex.withLock { searchRepository.removeSearchHistory(query) } }
    }

    fun undoDeleteHistory(tokenId: Long) {
        val token = undoManager.consume(tokenId) ?: return
        viewModelScope.launch {
            historyMutex.withLock {
                val restored = insertByAnchor(searchRepository.searchHistory.first(), token.query, token.anchorQuery, { it }, historyRanks)
                latestHistory = restored
                searchRepository.restoreSearchHistory(token.query, restored.indexOf(token.query))
            }
        }
    }

    fun expireHistoryUndo(tokenId: Long) = undoManager.expire(tokenId)

    private fun syncHistoryRanks(history: List<String>) {
        if (history.any { it !in historyRanks }) { historyRanks.clear(); history.forEachIndexed { i, q -> historyRanks[q] = i } }
    }

    private fun resetToInitialState() { activeJob?.cancel(); requestGen++; state.update { it.copy(hasSubmitted = false, searchId = null, results = PagedTabState()) } }

    private fun executeRequest(page: Int, append: Boolean, isRefresh: Boolean, priority: AccessPriority) {
        val s = state.value; val reqSearchId = if (append) s.searchId else null
        activeJob?.cancel(); val gen = ++requestGen
        state.update { cur -> cur.copy(hasSubmitted = true, results = (if (append || isRefresh) cur.results else PagedTabState()).markLoading(append, isRefresh)) }
        activeJob = viewModelScope.launch {
            val res = searchRepository.search(s.query, s.author, s.selectedForumId, s.fullText, page, reqSearchId, priority, recordHistory = !append)
            if (gen != requestGen) return@launch
            when (res) {
                is SearchResult.Success -> state.update { cur ->
                    val merged = (if (append) cur.results.items + res.items else res.items).distinctBy { "${it.tid.orEmpty()}:${it.pid.orEmpty()}:${it.title}" }
                    cur.copy(hasSubmitted = true, searchId = if (append) (res.searchId ?: cur.searchId) else res.searchId, results = cur.results.markSuccess(merged, res.page, res.hasNextPage))
                }
                is SearchError -> {
                    val mapped = UiErrorMapper.map(res); val cf = (res as? SearchError.ChallengeRequired)?.let { challengeKind(it.rejected) }
                    val keep = isRefresh && state.value.results.items.isNotEmpty()
                    state.update { it.copy(hasSubmitted = true, results = it.results.markError(keep, mapped, cf)) }
                    if (keep && res !is SearchError.AuthRequired) _snackbarEvents.tryEmit(SearchSnackbarEvent.RefreshError(undoManager.nextId(), mapped))
                }
            }
        }
    }
}
