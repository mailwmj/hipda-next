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
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.parser.simplelist.FavoriteThreadItem
import net.jejer.hipda.ui.challenge.ChallengeFailureKind

@Immutable
data class FavoritesUiState(
    val selectedKind: FavoriteKind = FavoriteKind.FAVORITE,
    val isSignedIn: Boolean = false,
    val favorites: PagedTabState<FavoriteThreadItem> = PagedTabState(),
    val attention: PagedTabState<FavoriteThreadItem> = PagedTabState(),
    val challengeState: ChallengeState = ChallengeState.Idle,
) {
    fun tabState(kind: FavoriteKind): PagedTabState<FavoriteThreadItem> =
        if (kind == FavoriteKind.FAVORITE) favorites else attention
}

sealed interface FavoritesSnackbarEvent {
    data class Removed(val kind: FavoriteKind, val item: FavoriteThreadItem, val originalIndex: Int) : FavoritesSnackbarEvent
    data class ActionError(val error: UiError) : FavoritesSnackbarEvent
}

/** 收藏与关注 ViewModel：双标签独立分页、乐观移除 + 可撤销恢复、登录态监听。 */
class FavoritesViewModel(
    private val favoriteRepository: FavoriteRepository,
    private val authRepository: AuthRepository,
    siteAccessGateway: SiteAccessGateway,
) : ViewModel() {

    private val state = MutableStateFlow(FavoritesUiState(isSignedIn = authRepository.currentAccount.value != null))
    private val _snackbarEvents = MutableSharedFlow<FavoritesSnackbarEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val loadJobs = HashMap<FavoriteKind, Job>()
    private var authGen: Long = 0L

    val snackbarEvents: SharedFlow<FavoritesSnackbarEvent> = _snackbarEvents.asSharedFlow()

    val uiState: StateFlow<FavoritesUiState> = combine(state, authRepository.currentAccount, siteAccessGateway.challengeState) { s, acc, ch ->
        s.copy(isSignedIn = acc != null, challengeState = ch)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), state.value)

    init {
        viewModelScope.observeAuthSession(authRepository) { uid ->
            authGen++
            loadJobs.values.forEach(Job::cancel); loadJobs.clear()
            state.update { FavoritesUiState(selectedKind = it.selectedKind, isSignedIn = uid != null) }
            if (uid != null) loadPage(state.value.selectedKind, 1, false, false, AccessPriority.UserBlocking)
        }
    }

    fun selectKind(kind: FavoriteKind) {
        if (state.value.selectedKind != kind) state.update { it.copy(selectedKind = kind) }
        val tab = state.value.tabState(kind)
        if (authRepository.currentAccount.value != null && !tab.hasLoadedOnce && !tab.isLoading) {
            loadPage(kind, 1, false, false, AccessPriority.UserBlocking)
        }
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
        val idx = state.value.tabState(kind).items.indexOfFirst { it.tid == item.tid }
        if (idx < 0) return
        val gen = authGen
        updateTab(kind) { it.copy(items = it.items.filterNot { x -> x.tid == item.tid }) }
        viewModelScope.launch {
            when (val res = favoriteRepository.remove(kind, item.tid)) {
                is FavoriteMutationResult.Success -> if (gen == authGen) _snackbarEvents.tryEmit(FavoritesSnackbarEvent.Removed(kind, item, idx))
                is FavoriteError -> if (gen == authGen) {
                    reinsert(kind, item, idx)
                    _snackbarEvents.tryEmit(FavoritesSnackbarEvent.ActionError(mapFavoriteError(res)))
                }
            }
        }
    }

    fun restoreItem(kind: FavoriteKind, item: FavoriteThreadItem, originalIndex: Int) {
        val gen = authGen
        reinsert(kind, item, originalIndex)
        viewModelScope.launch {
            when (val res = favoriteRepository.add(kind, item.tid)) {
                is FavoriteMutationResult.Success -> Unit
                is FavoriteError -> if (gen == authGen) {
                    updateTab(kind) { it.copy(items = it.items.filterNot { x -> x.tid == item.tid }) }
                    _snackbarEvents.tryEmit(FavoritesSnackbarEvent.ActionError(mapFavoriteError(res)))
                }
            }
        }
    }

    private fun loadPage(kind: FavoriteKind, page: Int, append: Boolean, isRefresh: Boolean, priority: AccessPriority) {
        val gen = authGen
        loadJobs[kind]?.cancel()
        updateTab(kind) { it.markLoading(append, isRefresh) }
        loadJobs[kind] = viewModelScope.launch {
            when (val res = favoriteRepository.getList(kind, page, priority)) {
                is FavoriteListResult.Success -> if (gen == authGen) updateTab(kind) { cur ->
                    cur.markSuccess((if (append) cur.items + res.items else res.items).distinctBy(FavoriteThreadItem::tid), res.page, res.hasNextPage)
                }
                is FavoriteError -> if (gen == authGen) {
                    val mapped = mapFavoriteError(res)
                    val cf = (res as? FavoriteError.ChallengeRequired)?.let { if (it.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete }
                    val keep = isRefresh && state.value.tabState(kind).items.isNotEmpty()
                    updateTab(kind) { it.markError(keep, mapped, cf) }
                    if (keep && res !is FavoriteError.AuthRequired) _snackbarEvents.tryEmit(FavoritesSnackbarEvent.ActionError(mapped))
                }
            }
        }
    }

    private fun reinsert(kind: FavoriteKind, item: FavoriteThreadItem, index: Int) = updateTab(kind) { cur ->
        val list = cur.items.filterNot { it.tid == item.tid }.toMutableList()
        list.add(index.coerceIn(0, list.size), item)
        cur.copy(items = list)
    }

    private fun updateTab(kind: FavoriteKind, fn: (PagedTabState<FavoriteThreadItem>) -> PagedTabState<FavoriteThreadItem>) =
        state.update { if (kind == FavoriteKind.FAVORITE) it.copy(favorites = fn(it.favorites)) else it.copy(attention = fn(it.attention)) }
}
