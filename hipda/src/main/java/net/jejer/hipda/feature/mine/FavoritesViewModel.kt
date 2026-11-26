package net.jejer.hipda.feature.mine

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.favorite.*
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.parser.simplelist.FavoriteThreadItem

/** 单次取消收藏/关注的独立撤销令牌（按前驱锚点与原序秩恢复，新操作立即替代旧令牌）。 */
@Immutable
data class FavoriteUndoToken(val id: Long, val kind: FavoriteKind, val item: FavoriteThreadItem, val anchorTid: String?)

@Immutable
data class FavoritesUiState(
    val selectedKind: FavoriteKind = FavoriteKind.FAVORITE, val isSignedIn: Boolean = false,
    val tabs: Map<FavoriteKind, PagedTabState<FavoriteThreadItem>> = emptyMap(), val challengeState: ChallengeState = ChallengeState.Idle,
) {
    fun tabState(kind: FavoriteKind): PagedTabState<FavoriteThreadItem> = tabs[kind] ?: PagedTabState()
}

sealed interface FavoritesSnackbarEvent {
    data class Removed(val token: FavoriteUndoToken) : FavoritesSnackbarEvent
    data class ActionError(val id: Long, val error: UiError) : FavoritesSnackbarEvent
}

/** 收藏与关注 ViewModel：双标签独立分页、仓库 tids 实时对齐防复活、锚点撤销令牌与串行变更。 */
class FavoritesViewModel(
    private val favoriteRepository: FavoriteRepository,
    private val authRepository: AuthRepository,
    siteAccessGateway: SiteAccessGateway,
) : ViewModel() {

    private class KindTracker {
        var loadJob: Job? = null; val ranks = HashMap<String, Int>(); var nextRank = 0
        val pendingRemovals = HashSet<String>(); val pendingRestores = HashSet<String>(); val tombstones = HashMap<String, Long>()
        fun reset() { loadJob?.cancel(); loadJob = null; ranks.clear(); nextRank = 0; pendingRemovals.clear(); pendingRestores.clear(); tombstones.clear() }
        fun ensureRanks(items: List<FavoriteThreadItem>) = items.forEach { if (it.tid !in ranks) ranks[it.tid] = nextRank++ }
        fun rebuildRanks(items: List<FavoriteThreadItem>) { ranks.clear(); nextRank = 0; ensureRanks(items) }
    }

    private val state = MutableStateFlow(FavoritesUiState(isSignedIn = authRepository.currentAccount.value != null))
    private val _snackbarEvents = MutableSharedFlow<FavoritesSnackbarEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val trackers = FavoriteKind.entries.associateWith { KindTracker() }
    private val tidJobs = HashMap<Pair<FavoriteKind, String>, Job>(); private val tidOpGen = HashMap<Pair<FavoriteKind, String>, Long>()
    private val undoManager = UndoTokenManager<FavoriteUndoToken>()
    private var mutationSeq = 0L; private var authGen = 0L

    val snackbarEvents: SharedFlow<FavoritesSnackbarEvent> = _snackbarEvents.asSharedFlow()

    val uiState: StateFlow<FavoritesUiState> = combine(state, authRepository.currentAccount, siteAccessGateway.challengeState) { s, acc, ch ->
        s.copy(isSignedIn = acc != null, challengeState = ch)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), state.value)

    init {
        observeRepoTids(FavoriteKind.FAVORITE, favoriteRepository.favoriteTids)
        observeRepoTids(FavoriteKind.ATTENTION, favoriteRepository.attentionTids)
        viewModelScope.observeAuthSession(authRepository) { uid ->
            authGen++; undoManager.clear(); trackers.values.forEach(KindTracker::reset)
            tidJobs.values.forEach(Job::cancel); tidJobs.clear(); tidOpGen.clear()
            state.update { FavoritesUiState(selectedKind = it.selectedKind, isSignedIn = uid != null) }
            if (uid != null) loadPage(state.value.selectedKind, 1, false, false, AccessPriority.UserBlocking)
        }
    }

    fun selectKind(kind: FavoriteKind) {
        if (state.value.selectedKind != kind) state.update { it.copy(selectedKind = kind) }
        val tab = state.value.tabState(kind)
        if (authRepository.currentAccount.value != null && !tab.hasLoadedOnce && !tab.isLoading) loadPage(kind, 1, false, false, AccessPriority.UserBlocking)
    }

    fun refresh(kind: FavoriteKind = state.value.selectedKind, priority: AccessPriority = AccessPriority.UserBlocking) {
        if (authRepository.currentAccount.value != null) loadPage(kind, 1, false, state.value.tabState(kind).items.isNotEmpty(), priority)
    }

    fun loadMore(kind: FavoriteKind = state.value.selectedKind, priority: AccessPriority = AccessPriority.UserBlocking) {
        if (authRepository.currentAccount.value == null) return
        val tab = state.value.tabState(kind)
        if (!tab.isLoading && !tab.isRefreshing && !tab.isLoadingMore && tab.hasMore) loadPage(kind, tab.page + 1, true, false, priority)
    }

    fun retry(kind: FavoriteKind = state.value.selectedKind, priority: AccessPriority = AccessPriority.UserBlocking) {
        if (authRepository.currentAccount.value != null) loadPage(kind, 1, false, false, priority)
    }

    fun removeItem(kind: FavoriteKind, item: FavoriteThreadItem) {
        val items = state.value.tabState(kind).items
        val idx = items.indexOfFirst { it.tid == item.tid }.takeIf { it >= 0 } ?: return
        val tr = trackers.getValue(kind); tr.ensureRanks(items)
        val anchorTid = if (idx > 0) items[idx - 1].tid else null
        val id = undoManager.nextId(); val token = FavoriteUndoToken(id, kind, item, anchorTid)
        undoManager.issue(token, id)
        tr.pendingRemovals.add(item.tid); tr.pendingRestores.remove(item.tid); tr.tombstones[item.tid] = ++mutationSeq
        updateTab(kind) { it.copy(items = it.items.filterNot { x -> x.tid == item.tid }) }
        _snackbarEvents.tryEmit(FavoritesSnackbarEvent.Removed(token))
        enqueueTidMutation(kind, item.tid, { favoriteRepository.remove(kind, item.tid) }, { tr.pendingRemovals.remove(item.tid) }) { err ->
            tr.tombstones.remove(item.tid)
            updateTab(kind) { it.copy(items = insertByAnchor(it.items, item, anchorTid, FavoriteThreadItem::tid, tr.ranks)) }
            undoManager.expire(token.id)
            _snackbarEvents.tryEmit(FavoritesSnackbarEvent.ActionError(undoManager.invalidate(), mapFavoriteError(err)))
        }
    }

    fun undoRemove(tokenId: Long) {
        val token = undoManager.consume(tokenId) ?: return
        val tr = trackers.getValue(token.kind); val item = token.item
        tr.pendingRemovals.remove(item.tid); tr.tombstones.remove(item.tid); tr.pendingRestores.add(item.tid)
        updateTab(token.kind) { it.copy(items = insertByAnchor(it.items, item, token.anchorTid, FavoriteThreadItem::tid, tr.ranks)) }
        enqueueTidMutation(token.kind, item.tid, { favoriteRepository.add(token.kind, item.tid) }, { tr.pendingRestores.remove(item.tid) }) { err ->
            updateTab(token.kind) { it.copy(items = it.items.filterNot { x -> x.tid == item.tid }) }
            _snackbarEvents.tryEmit(FavoritesSnackbarEvent.ActionError(undoManager.invalidate(), mapFavoriteError(err)))
        }
    }

    fun expireUndo(tokenId: Long) = undoManager.expire(tokenId)

    private fun enqueueTidMutation(kind: FavoriteKind, tid: String, call: suspend () -> FavoriteMutationResult, onLatestDone: () -> Unit, onError: (FavoriteError) -> Unit) {
        val key = kind to tid; val gen = authGen; val opGen = (tidOpGen[key] ?: 0L) + 1L; tidOpGen[key] = opGen; val prev = tidJobs[key]
        tidJobs[key] = viewModelScope.launch {
            prev?.join(); val res = call()
            if (gen != authGen || tidOpGen[key] != opGen) return@launch
            onLatestDone()
            if (res is FavoriteError) onError(res)
        }
    }

    private fun observeRepoTids(kind: FavoriteKind, flow: StateFlow<Set<String>>) {
        viewModelScope.launch {
            val tr = trackers.getValue(kind); var prev = flow.value
            flow.collect { current ->
                val dropped = prev - current; val added = current - prev; prev = current
                if (added.isNotEmpty()) tr.tombstones.keys.removeAll(added)
                val effective = dropped - tr.pendingRestores
                if (effective.isNotEmpty()) {
                    val seq = ++mutationSeq; effective.forEach { tr.tombstones[it] = seq }
                    updateTab(kind) { tab -> tab.copy(items = tab.items.filterNot { it.tid in effective }) }
                }
            }
        }
    }

    private fun loadPage(kind: FavoriteKind, page: Int, append: Boolean, isRefresh: Boolean, priority: AccessPriority) {
        val tr = trackers.getValue(kind); val gen = authGen; val startSeq = mutationSeq
        tr.loadJob?.cancel(); updateTab(kind) { it.markLoading(append, isRefresh) }
        tr.loadJob = viewModelScope.launch {
            when (val res = favoriteRepository.getList(kind, page, priority)) {
                is FavoriteListResult.Success -> if (gen == authGen) updateTab(kind) { cur ->
                    val filtered = res.items.filterNot { it.tid in tr.pendingRemovals || (tr.tombstones[it.tid] ?: 0L) > startSeq }
                    val merged = (if (append) cur.items + filtered else filtered).distinctBy(FavoriteThreadItem::tid)
                    if (!append && !undoManager.hasActiveToken && tr.pendingRemovals.isEmpty() && tr.pendingRestores.isEmpty()) {
                        tr.rebuildRanks(merged)
                    } else {
                        tr.ensureRanks(merged)
                    }
                    cur.markSuccess(merged, res.page, res.hasNextPage)
                }
                is FavoriteError -> if (gen == authGen) {
                    val mapped = mapFavoriteError(res); val cf = (res as? FavoriteError.ChallengeRequired)?.let { challengeKind(it.rejected) }
                    val keep = isRefresh && state.value.tabState(kind).items.isNotEmpty()
                    updateTab(kind) { it.markError(keep, mapped, cf) }
                    if (keep && res !is FavoriteError.AuthRequired) _snackbarEvents.tryEmit(FavoritesSnackbarEvent.ActionError(undoManager.invalidate(), mapped))
                }
            }
        }
    }

    private fun updateTab(kind: FavoriteKind, fn: (PagedTabState<FavoriteThreadItem>) -> PagedTabState<FavoriteThreadItem>) =
        state.update { it.copy(tabs = it.tabs + (kind to fn(it.tabState(kind)))) }
}
