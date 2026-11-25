package net.jejer.hipda.feature.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
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

/** `tid -> ViewModelStore` 映射表（零 Context/Activity 引用，安全跨旋转存活）。 */
class ScopedThreadViewModelStoreRegistry {
    private val stores = LinkedHashMap<String, ViewModelStore>()
    fun getOrCreateStore(tid: String): ViewModelStore = stores.getOrPut(tid) { ViewModelStore() }
    fun hasStore(tid: String): Boolean = tid in stores
    fun release(tid: String) { stores.remove(tid)?.clear() }
    fun clearAll() { stores.values.toList().also { stores.clear() }.forEach(ViewModelStore::clear) }
}

/** Activity 级宿主 ViewModel：仅管 `tid -> ViewModelStore` 映射、列表状态与导航状态（不含业务 Repository）。 */
@HiltViewModel
class NextAppHostViewModel @Inject constructor() : ViewModel() {

    private val storeRegistry = ScopedThreadViewModelStoreRegistry()
    private val forumListStates = LinkedHashMap<Int, LazyListState>()
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
        storeRegistry.getOrCreateStore(tid),
        defaultViewModelProviderFactory,
        defaultViewModelCreationExtras,
    )

    fun releaseThreadStore(tid: String) {
        storeRegistry.release(tid)
        threadListStates.remove(tid)
    }

    fun hasThreadStore(tid: String): Boolean = storeRegistry.hasStore(tid)
    fun forumListStateFor(fid: Int): LazyListState = forumListStates.getOrPut(fid) { LazyListState() }
    fun threadListStateFor(tid: String): LazyListState = threadListStates.getOrPut(tid) { LazyListState() }

    fun syncDefaultForum(followedForums: List<Int>) {
        if (hasUserSelectedForum) return
        val preferred = followedForums.firstOrNull() ?: NextAppState.DEFAULT_FORUM_FID
        _state.update { cur ->
            if (cur.activeForumFid == preferred) cur
            else cur.copy(
                activeForumFid = preferred,
                selectedSidebarEntry = if (cur.selectedDestination == ShellDestination.Forum) ShellSidebarEntry.Forum(preferred) else cur.selectedSidebarEntry,
            )
        }
    }

    fun selectDestination(destination: ShellDestination) = _state.update { cur ->
        cur.copy(
            selectedDestination = destination,
            selectedSidebarEntry = mapDestinationToSidebarEntry(destination, cur.activeForumFid, cur.activeMessageEntry, lastVisitedMineEntry = cur.lastVisitedMineEntry),
        )
    }

    fun selectSidebarEntry(entry: ShellSidebarEntry) {
        if (entry is ShellSidebarEntry.Forum) hasUserSelectedForum = true
        updateReleasingStaleThread { cur ->
            val changed = cur.selectedSidebarEntry != entry
            cur.copy(
                selectedDestination = entry.toShellDestination(),
                selectedSidebarEntry = entry,
                activeForumFid = (entry as? ShellSidebarEntry.Forum)?.fid ?: cur.activeForumFid,
                activeMessageEntry = if (entry == ShellSidebarEntry.Sms || entry == ShellSidebarEntry.Notify) entry else cur.activeMessageEntry,
                lastVisitedMineEntry = if (entry.isMineGroupChild || entry == ShellSidebarEntry.Settings) entry else cur.lastVisitedMineEntry,
                selectedThread = if (changed) null else cur.selectedThread,
            )
        }
    }

    fun selectForum(fid: Int) {
        hasUserSelectedForum = true
        updateReleasingStaleThread { cur ->
            val changed = cur.activeForumFid != fid || cur.selectedDestination != ShellDestination.Forum
            cur.copy(
                selectedDestination = ShellDestination.Forum,
                selectedSidebarEntry = ShellSidebarEntry.Forum(fid),
                activeForumFid = fid,
                selectedThread = if (changed) null else cur.selectedThread,
            )
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
        storeRegistry.clearAll()
        threadListStates.clear()
    }
}
