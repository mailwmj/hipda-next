package net.jejer.hipda.feature.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.shell.*

/** 按 `tid` 隔离托管的 [ViewModelStoreOwner]，显式实现 [HasDefaultViewModelProviderFactory] 支持 `hiltViewModel()`。 */
class ThreadScopedViewModelStoreOwner(
    val tid: String,
    override val viewModelStore: ViewModelStore,
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory,
    override val defaultViewModelCreationExtras: CreationExtras = CreationExtras.Empty,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory

/** Activity 级宿主 ViewModel：仅管 `tid -> ViewModelStore` 映射、列表状态与导航状态（不含业务 Repository）。 */
@HiltViewModel
class NextAppHostViewModel @Inject constructor() : ViewModel() {

    private val threadStores = LinkedHashMap<String, ViewModelStore>()
    private val forumListStates = LinkedHashMap<ForumListSource, LazyListState>()
    private val threadListStates = LinkedHashMap<String, LazyListState>()
    private var hasUserSelectedForum = false
    private val _state = MutableStateFlow(NextAppState())
    val state: StateFlow<NextAppState> = _state.asStateFlow()

    fun viewModelStoreOwnerForThread(
        tid: String,
        defaultViewModelProviderFactory: ViewModelProvider.Factory,
        defaultViewModelCreationExtras: CreationExtras = CreationExtras.Empty,
    ): ThreadScopedViewModelStoreOwner = ThreadScopedViewModelStoreOwner(
        tid,
        threadStores.getOrPut(tid) { ViewModelStore() },
        defaultViewModelProviderFactory,
        defaultViewModelCreationExtras,
    )

    fun releaseThreadStore(tid: String) { threadStores.remove(tid)?.clear(); threadListStates.remove(tid) }
    fun forumListStateFor(source: ForumListSource): LazyListState = forumListStates.getOrPut(source) { LazyListState() }
    fun threadListStateFor(tid: String): LazyListState = threadListStates.getOrPut(tid) { LazyListState() }

    fun syncDefaultForum(followedForums: List<Int>) {
        if (hasUserSelectedForum) return
        val preferred = ForumListSource.Forum(followedForums.firstOrNull() ?: NextAppState.DEFAULT_FORUM_FID)
        _state.update { cur ->
            if (cur.activeForumSource == preferred) cur
            else cur.copy(activeForumSource = preferred, selectedSidebarEntry = if (cur.selectedDestination == ShellDestination.Forum) preferred.toSidebarEntry() else cur.selectedSidebarEntry)
        }
    }

    fun selectDestination(destination: ShellDestination) = _state.update { cur ->
        cur.copy(
            selectedDestination = destination,
            selectedSidebarEntry = mapDestinationToSidebarEntry(destination, (cur.activeForumSource as? ForumListSource.Forum)?.fid, cur.activeMessageEntry, lastVisitedMineEntry = cur.lastVisitedMineEntry),
        )
    }

    fun selectSidebarEntry(entry: ShellSidebarEntry) {
        val nextSource = entry.toForumListSourceOrNull()
        if (nextSource != null) hasUserSelectedForum = true
        updateReleasingStaleThread { cur ->
            cur.copy(
                selectedDestination = entry.toShellDestination(),
                selectedSidebarEntry = entry,
                activeForumSource = nextSource ?: cur.activeForumSource,
                activeMessageEntry = if (entry == ShellSidebarEntry.Sms || entry == ShellSidebarEntry.Notify) entry else cur.activeMessageEntry,
                lastVisitedMineEntry = if (entry.isMineGroupChild || entry == ShellSidebarEntry.Settings) entry else cur.lastVisitedMineEntry,
                selectedThread = if (cur.selectedSidebarEntry != entry) null else cur.selectedThread,
            )
        }
    }

    fun selectForumSource(source: ForumListSource) {
        hasUserSelectedForum = true
        updateReleasingStaleThread { cur ->
            val changed = cur.activeForumSource != source || cur.selectedDestination != ShellDestination.Forum
            cur.copy(selectedDestination = ShellDestination.Forum, selectedSidebarEntry = source.toSidebarEntry(), activeForumSource = source, selectedThread = if (changed) null else cur.selectedThread)
        }
    }

    fun openThread(args: ThreadNavArgs) = updateReleasingStaleThread { it.copy(selectedThread = args) }
    fun closeThread() = updateReleasingStaleThread { it.copy(selectedThread = null) }
    fun openLoginOverlay(mode: LoginOverlayMode = LoginOverlayMode.Login) = _state.update { it.copy(loginOverlayMode = mode) }
    fun dismissLoginOverlay() = _state.update { it.copy(loginOverlayMode = null) }

    private fun updateReleasingStaleThread(transform: (NextAppState) -> NextAppState) {
        var staleTid: String? = null
        _state.update { cur ->
            val next = transform(cur)
            if (cur.selectedThread?.tid != next.selectedThread?.tid) staleTid = cur.selectedThread?.tid
            next
        }
        staleTid?.let(::releaseThreadStore)
    }

    override fun onCleared() {
        threadStores.values.toList().also { threadStores.clear() }.forEach(ViewModelStore::clear)
        threadListStates.clear()
    }
}

private fun ForumListSource.toSidebarEntry(): ShellSidebarEntry = when (this) {
    ForumListSource.TodayNew -> ShellSidebarEntry.TodayNew
    is ForumListSource.Forum -> ShellSidebarEntry.Forum(fid)
}

private fun ShellSidebarEntry.toForumListSourceOrNull(): ForumListSource? = when (this) {
    ShellSidebarEntry.TodayNew -> ForumListSource.TodayNew
    is ShellSidebarEntry.Forum -> ForumListSource.Forum(fid)
    else -> null
}
