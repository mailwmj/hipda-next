package net.jejer.hipda.feature.forum

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.data.search.*
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.threadlist.*
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.parser.threadlist.ThreadSummary
import net.jejer.hipda.ui.challenge.ChallengeFailureKind

internal const val BUY_AND_SELL_FID: Int = 6

@Immutable
data class ForumUiState(
    val source: ForumListSource = ForumListSource.Forum(AppSettings.DEFAULT_FOLLOWED_FORUMS.first()),
    val followedForums: List<Int> = AppSettings.DEFAULT_FOLLOWED_FORUMS,
    val sortByPostTime: Boolean = false,
    val items: List<ThreadListItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val newThreadsCount: Int = 0,
    val error: UiError? = null,
    val challengeFailure: ChallengeFailureKind? = null,
    val challengeState: ChallengeState = ChallengeState.Idle,
    val selectedTypeId: String? = null,
)

private data class TodayNewState(
    val rawThreads: List<ThreadSummary> = emptyList(),
    val page: Int = 0,
    val searchId: String? = null,
    val hasMore: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: UiError? = null,
    val challengeFailure: ChallengeFailureKind? = null,
)

private data class AuthSessionKey(val uid: String?, val lastLoginAt: Long?, val sessionEpoch: Long, val isSignedIn: Boolean, val isSessionExpired: Boolean)

private data class ForumAuxState(
    val newThreadsBySource: Map<ForumListSource, Int> = emptyMap(),
    val authGeneration: Int = 0,
    val uidGeneration: Int = 0,
    val queryUidGenerations: Map<ThreadListQuery, Int> = emptyMap(),
)

/**
 * 论坛/今日新帖列表 ViewModel：显式 `sortByPostTime` 持久化（R-D4 S1）、账号水合/重登恢复与跨号按 `uid` 世代屏蔽旧缓存（B4-E1 ① / R-E0 R2-S1 / R-E1 S3）、
 * `TodayNew` `searchId` 翻页保底及响应式黑名单/已读（B4-E1 ③）、下拉刷新 `tid` diff 与慢网防闪空态（R-E0 S3）。
 */
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
    private val buyAndSellTypeId = MutableStateFlow<String?>(null)
    private val latestSettings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val initializedQueries = HashSet<ThreadListQuery>()
    private var todayNewInitialized = false
    private val todayNewState = MutableStateFlow(TodayNewState())
    private val todayNewItems = threadListRepository.observeFiltered(todayNewState.map { it.rawThreads }.distinctUntilChanged())
    private val auxState = MutableStateFlow(ForumAuxState())
    private val _refreshErrors = MutableSharedFlow<UiError>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var activeRefreshJob: Job? = null

    val refreshErrors: SharedFlow<UiError> = _refreshErrors.asSharedFlow()

    val uiState: StateFlow<ForumUiState> = combine(activeSource, latestSettings, buyAndSellTypeId) { src, s, bsType ->
        Triple(src, s, effectiveTypeId(src, bsType))
    }
        .distinctUntilChanged()
        .flatMapLatest { (src, s, typeId) ->
            when (src) {
                ForumListSource.TodayNew -> combine(todayNewState, todayNewItems, auxState, siteAccessGateway.challengeState) { tn, items, aux, ch ->
                    val loading = tn.isLoading || (!tn.hasLoadedOnce && items.isEmpty() && tn.error == null)
                    ForumUiState(src, s.followedForums, false, items, loading && items.isEmpty(), tn.isLoading && items.isNotEmpty(), tn.isLoadingMore, tn.hasMore, aux.newThreadsBySource[src] ?: 0, tn.error, tn.challengeFailure, ch, null)
                }
                is ForumListSource.Forum -> {
                    val q = ThreadListQuery(fid = src.fid, sortByPostTime = s.isSortByPostTime(src.fid), typeId = typeId)
                    combine(threadListRepository.observeThreadList(q), auxState, siteAccessGateway.challengeState) { load, aux, ch ->
                        val err = load.error
                        val cf = (err as? ThreadListError.ChallengeRequired)?.let { if (it.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete }
                        val staleUidCache = aux.uidGeneration > 0 && aux.queryUidGenerations[q] != aux.uidGeneration
                        val visibleItems = if (staleUidCache) emptyList() else load.items
                        val unsettled = (staleUidCache || (load.fetchedPage == 0 && load.stopReason == null)) && err == null
                        val loading = load.isLoading || (unsettled && visibleItems.isEmpty())
                        ForumUiState(src, s.followedForums, load.query.sortByPostTime ?: s.isSortByPostTime(src.fid), visibleItems, loading && visibleItems.isEmpty(), load.isLoading && visibleItems.isNotEmpty(), load.isLoadingMore, !staleUidCache && load.hasMore, aux.newThreadsBySource[src] ?: 0, err?.let(UiErrorMapper::map), cf, ch, typeId)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ForumUiState())

    init {
        viewModelScope.launch {
            combine(activeSource, latestSettings, buyAndSellTypeId) { src, s, bsType ->
                Triple(src, (src as? ForumListSource.Forum)?.let { s.isSortByPostTime(it.fid) }, effectiveTypeId(src, bsType))
            }
                .distinctUntilChanged()
                .collectLatest { (src, sort, typeId) ->
                    when (src) {
                        ForumListSource.TodayNew -> if (!todayNewInitialized) {
                            todayNewInitialized = true
                            try { loadTodayNew(1, false, AccessPriority.UserBlocking, emptyList()) }
                            catch (e: CancellationException) { todayNewInitialized = false; throw e }
                        }
                        is ForumListSource.Forum -> {
                            val q = ThreadListQuery(src.fid, sort ?: latestSettings.value.isSortByPostTime(src.fid), typeId)
                            if (initializedQueries.add(q)) {
                                try { executeForumRefresh(src, q, AccessPriority.UserBlocking, emptyList()) }
                                catch (e: CancellationException) { initializedQueries.remove(q); throw e }
                            }
                        }
                    }
                }
        }
        viewModelScope.launch {
            var prevKey: AuthSessionKey? = null
            combine(authRepository.currentAccount, authRepository.authState) { account, state ->
                AuthSessionKey(account?.uid, account?.lastLoginAt, authRepository.sessionEpoch, state is AuthState.SignedIn, state is AuthState.SessionExpired)
            }.distinctUntilChanged().collect { key ->
                val prev = prevKey
                prevKey = key
                if (prev == null) return@collect
                val uidChanged = key.uid != prev.uid
                val sameAccountRecovered = !uidChanged && key.uid != null && (
                    key.lastLoginAt != prev.lastLoginAt || key.sessionEpoch != prev.sessionEpoch ||
                        (key.isSignedIn && prev.isSessionExpired) ||
                        (key.isSignedIn && !prev.isSignedIn && uiState.value.error?.action == UiErrorAction.Login)
                    )
                if (uidChanged || sameAccountRecovered) {
                    resetForAuthChange(uidChanged = uidChanged)
                    refreshInternal(AccessPriority.UserBlocking, computeDiff = false)
                }
            }
        }
    }

    fun bindSource(source: ForumListSource) { if (activeSource.value != source) activeSource.value = source }

    fun setSortByPostTime(sortByPostTime: Boolean) {
        val src = activeSource.value as? ForumListSource.Forum ?: return
        val q = forumQuery(src.fid, sortByPostTime = sortByPostTime)
        initializedQueries.add(q)
        clearNewThreadsCount(src)
        viewModelScope.launch {
            settingsRepository.setSortByPostTime(src.fid, sortByPostTime)
            executeForumRefresh(src, q, AccessPriority.UserBlocking, emptyList())
        }
    }

    /** Buy & Sell（`fid=6`）分类筛选：仅对 `fid=6` 生效，其他版块/今日新帖直接忽略且不透传 `typeId`。 */
    fun selectTypeId(typeId: String?) {
        val src = activeSource.value as? ForumListSource.Forum ?: return
        if (src.fid != BUY_AND_SELL_FID) return
        val normalized = typeId?.trim()?.takeIf { it.isNotEmpty() }
        if (buyAndSellTypeId.value == normalized) return
        buyAndSellTypeId.value = normalized
        val q = forumQuery(src.fid, bsTypeId = normalized)
        initializedQueries.add(q)
        clearNewThreadsCount(src)
        activeRefreshJob?.cancel()
        activeRefreshJob = viewModelScope.launch {
            executeForumRefresh(src, q, AccessPriority.UserBlocking, emptyList())
        }
    }

    fun refresh(priority: AccessPriority = AccessPriority.UserBlocking) = refreshInternal(priority, computeDiff = true)

    fun loadMore(priority: AccessPriority = AccessPriority.UserBlocking) {
        val cur = uiState.value
        if (cur.isLoading || cur.isRefreshing || cur.isLoadingMore || !cur.hasMore) return
        when (val src = activeSource.value) {
            ForumListSource.TodayNew -> {
                val tn = todayNewState.value
                if (tn.isLoading || tn.isLoadingMore || !tn.hasMore) return
                activeRefreshJob = viewModelScope.launch { loadTodayNew(tn.page + 1, true, priority, emptyList()) }
            }
            is ForumListSource.Forum -> viewModelScope.launch { threadListRepository.loadMore(forumQuery(src.fid), priority = priority) }
        }
    }

    fun retry(priority: AccessPriority = AccessPriority.UserBlocking) = refreshInternal(priority, computeDiff = false)

    fun dismissNewThreadsPill() = clearNewThreadsCount(activeSource.value)

    private fun refreshInternal(priority: AccessPriority, computeDiff: Boolean) {
        val src = activeSource.value
        val previous = if (computeDiff) uiState.value.takeIf { it.source == src }?.items.orEmpty() else emptyList()
        activeRefreshJob?.cancel()
        activeRefreshJob = when (src) {
            ForumListSource.TodayNew -> {
                todayNewInitialized = true
                viewModelScope.launch { loadTodayNew(1, false, priority, previous) }
            }
            is ForumListSource.Forum -> {
                val q = forumQuery(src.fid)
                initializedQueries.add(q)
                viewModelScope.launch { executeForumRefresh(src, q, priority, previous) }
            }
        }
    }

    private suspend fun executeForumRefresh(src: ForumListSource.Forum, query: ThreadListQuery, priority: AccessPriority, diffPrevious: List<ThreadListItem>) {
        val snap = auxState.value
        val result = threadListRepository.refresh(query, priority = priority)
        if (auxState.value.authGeneration != snap.authGeneration) return
        when (result) {
            is ThreadListBatchResult -> {
                val newCount = computeTopNewThreadsCount(diffPrevious, result.items)
                auxState.update { it.copy(queryUidGenerations = it.queryUidGenerations + (query to snap.uidGeneration), newThreadsBySource = it.newThreadsBySource + (src to newCount)) }
            }
            is ThreadListError -> {
                if (diffPrevious.isNotEmpty() && result !is ThreadListError.AuthRequired) _refreshErrors.tryEmit(UiErrorMapper.map(result))
            }
        }
    }

    private suspend fun loadTodayNew(page: Int, append: Boolean, priority: AccessPriority, diffPrevious: List<ThreadListItem>) {
        val gen = auxState.value.authGeneration
        todayNewState.update { it.copy(isLoading = !append, isLoadingMore = append, error = null, challengeFailure = null) }
        val reqSearchId = if (append) todayNewState.value.searchId else null
        when (val res = searchRepository.getNewPosts(page = page, searchId = reqSearchId, priority = priority)) {
            is SearchResult.Success -> {
                if (auxState.value.authGeneration != gen) return
                val mapped = res.items.mapNotNull(SearchItem::toThreadSummary)
                todayNewState.update { cur ->
                    val merged = if (append) (cur.rawThreads + mapped).distinctBy(ThreadSummary::tid) else mapped
                    cur.copy(rawThreads = merged, page = res.page, searchId = if (append) (res.searchId ?: cur.searchId) else res.searchId, hasMore = res.hasNextPage, hasLoadedOnce = true, isLoading = false, isLoadingMore = false)
                }
                if (!append) {
                    val newCount = if (diffPrevious.isEmpty()) 0 else computeTopNewThreadsCount(diffPrevious, todayNewItems.first())
                    auxState.update { it.copy(newThreadsBySource = it.newThreadsBySource + (ForumListSource.TodayNew to newCount)) }
                }
            }
            is SearchError -> {
                if (auxState.value.authGeneration != gen) return
                val mappedErr = UiErrorMapper.map(res)
                val cf = (res as? SearchError.ChallengeRequired)?.let { if (it.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete }
                todayNewState.update { it.copy(hasLoadedOnce = true, isLoading = false, isLoadingMore = false, error = mappedErr, challengeFailure = cf) }
                if (!append && diffPrevious.isNotEmpty() && res !is SearchError.AuthRequired) _refreshErrors.tryEmit(mappedErr)
            }
        }
    }

    private fun resetForAuthChange(uidChanged: Boolean) {
        activeRefreshJob?.cancel()
        initializedQueries.clear(); todayNewInitialized = false
        if (uidChanged) todayNewState.value = TodayNewState()
        auxState.update {
            it.copy(
                newThreadsBySource = emptyMap(),
                authGeneration = it.authGeneration + 1,
                uidGeneration = if (uidChanged) it.uidGeneration + 1 else it.uidGeneration,
                queryUidGenerations = if (uidChanged) emptyMap() else it.queryUidGenerations,
            )
        }
    }

    private fun clearNewThreadsCount(src: ForumListSource) =
        auxState.update { if ((it.newThreadsBySource[src] ?: 0) == 0) it else it.copy(newThreadsBySource = it.newThreadsBySource - src) }

    private fun effectiveTypeId(source: ForumListSource, bsTypeId: String? = buyAndSellTypeId.value): String? =
        if (source is ForumListSource.Forum && source.fid == BUY_AND_SELL_FID) bsTypeId?.trim()?.takeIf { it.isNotEmpty() } else null

    private fun forumQuery(
        fid: Int,
        sortByPostTime: Boolean = latestSettings.value.isSortByPostTime(fid),
        bsTypeId: String? = buyAndSellTypeId.value,
    ) = ThreadListQuery(
        fid = fid,
        sortByPostTime = sortByPostTime,
        typeId = if (fid == BUY_AND_SELL_FID) bsTypeId?.trim()?.takeIf { it.isNotEmpty() } else null,
    )
}

internal fun computeTopNewThreadsCount(previousItems: List<ThreadListItem>, updatedItems: List<ThreadListItem>): Int {
    if (previousItems.isEmpty() || updatedItems.isEmpty()) return 0
    val prevTids = previousItems.mapTo(HashSet(previousItems.size)) { it.tid }
    val anchorTid = previousItems.firstOrNull { !it.summary.isSticky }?.tid ?: previousItems.first().tid
    var count = 0
    for (item in updatedItems) {
        if (item.tid == anchorTid) break
        if (item.tid !in prevTids) count++
    }
    return count
}

private fun SearchItem.toThreadSummary(): ThreadSummary? = tid?.takeIf(String::isNotBlank)?.let {
    ThreadSummary(it, title, null, forumName, false, false, false, author.orEmpty(), authorUid.orEmpty(), avatarUrl, time.orEmpty(), time, "", "", "", false, 1)
}
