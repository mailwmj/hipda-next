package net.jejer.hipda.feature.app

import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellPaneLayoutMode
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import net.jejer.hipda.ui.shell.mapDestinationToSidebarEntry
import net.jejer.hipda.ui.shell.toShellDestination

/** 按 `tid` 隔离托管的 [ViewModelStoreOwner]，显式实现 [HasDefaultViewModelProviderFactory] 支持 `hiltViewModel()`。 */
class ThreadScopedViewModelStoreOwner(
    val tid: String,
    override val viewModelStore: ViewModelStore,
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory,
    override val defaultViewModelCreationExtras: CreationExtras = CreationExtras.Empty,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory

/** Activity 级宿主 ViewModel：托管按 `tid` 隔离的 [ViewModelStore]、单一导航状态源 [NextAppState]、5 级返回栈与 `SettingsRepository` 分栏偏好持久化。 */
@HiltViewModel
class NextAppHostViewModel private constructor(
    private val settingsRepository: SettingsRepository?,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : ViewModel() {

    @Inject
    constructor(settingsRepository: SettingsRepository) : this(settingsRepository, Unit)

    constructor() : this(null, Unit)

    private val threadStores = LinkedHashMap<String, ViewModelStore>()
    private val mountedThreadCounts = HashMap<String, Int>()
    private var hasUserSelectedForum = false
    private var hasMutatedState = false

    private val _state = MutableStateFlow(NextAppState())
    val state: StateFlow<NextAppState> = _state.asStateFlow()

    internal fun onThreadMounted(tid: String) {
        mountedThreadCounts[tid] = (mountedThreadCounts[tid] ?: 0) + 1
    }

    internal fun onThreadDisposed(tid: String) {
        val next = (mountedThreadCounts[tid] ?: 0) - 1
        if (next <= 0) {
            mountedThreadCounts.remove(tid)
            if (tid !in _state.value.activeThreadTids()) releaseThreadStore(tid)
        } else {
            mountedThreadCounts[tid] = next
        }
    }

    fun viewModelStoreOwnerForThread(
        tid: String,
        defaultViewModelProviderFactory: ViewModelProvider.Factory,
        defaultViewModelCreationExtras: CreationExtras = CreationExtras.Empty,
    ): ThreadScopedViewModelStoreOwner = ThreadScopedViewModelStoreOwner(
        tid = tid,
        viewModelStore = threadStores.getOrPut(tid) { ViewModelStore() },
        defaultViewModelProviderFactory = defaultViewModelProviderFactory,
        defaultViewModelCreationExtras = defaultViewModelCreationExtras,
    )

    fun releaseThreadStore(tid: String) {
        threadStores.remove(tid)?.clear()
    }

    /** 仅在进程回收重建出全新 ViewModel 实例时，从 `rememberSaveable` 恢复导航与阅读状态。 */
    fun restoreSavedStateIfFresh(restored: NextAppState) {
        if (hasMutatedState || restored == _state.value) return
        hasMutatedState = true
        if (restored.activeForumSource != ForumListSource.Forum(restored.defaultForumFid)) hasUserSelectedForum = true
        _state.value = restored
    }

    fun syncDefaultForum(followedForums: List<Int>) {
        val defaultFid = followedForums.firstOrNull() ?: NextAppState.DEFAULT_FORUM_FID
        _state.update { cur ->
            val withDefault = if (cur.defaultForumFid == defaultFid) cur else cur.copy(defaultForumFid = defaultFid)
            val preferred = ForumListSource.Forum(defaultFid)
            if (hasUserSelectedForum || withDefault.activeForumSource == preferred) {
                withDefault
            } else {
                withDefault.copy(
                    activeForumSource = preferred,
                    selectedSidebarEntry = if (withDefault.selectedDestination == ShellDestination.Forum) {
                        preferred.toSidebarEntry()
                    } else {
                        withDefault.selectedSidebarEntry
                    },
                )
            }
        }
    }

    fun selectDestination(destination: ShellDestination) {
        hasMutatedState = true
        updateReleasingStaleThreads { cur ->
            val changed = cur.selectedDestination != destination
            val mappedEntry = mapDestinationToSidebarEntry(
                destination = destination,
                activeForumFid = (cur.activeForumSource as? ForumListSource.Forum)?.fid,
                activeMessageEntry = cur.activeMessageEntry,
                activeMineSubPage = if (destination == ShellDestination.Mine) null else cur.activeMineSubPage,
                lastVisitedMineEntry = cur.lastVisitedMineEntry,
            )
            cur.copy(
                selectedDestination = destination,
                selectedSidebarEntry = mappedEntry,
                activeMineSubPage = if (destination == ShellDestination.Mine) null else cur.activeMineSubPage,
                hingeShowingMineMenu = destination == ShellDestination.Mine,
            ).clearDetailIf(changed)
        }
    }

    fun selectSidebarEntry(entry: ShellSidebarEntry) {
        hasMutatedState = true
        val nextSource = entry.toForumListSourceOrNull()
        if (nextSource != null) hasUserSelectedForum = true
        updateReleasingStaleThreads { cur ->
            val changed = cur.selectedSidebarEntry != entry || cur.selectedDestination != entry.toShellDestination()
            val isMineEntry = entry.isMineGroupChild || entry == ShellSidebarEntry.Settings
            cur.copy(
                selectedDestination = entry.toShellDestination(),
                selectedSidebarEntry = entry,
                activeForumSource = nextSource ?: cur.activeForumSource,
                activeMessageEntry = if (entry == ShellSidebarEntry.Sms || entry == ShellSidebarEntry.Notify) entry else cur.activeMessageEntry,
                lastVisitedMineEntry = if (isMineEntry) entry else cur.lastVisitedMineEntry,
                activeMineSubPage = if (isMineEntry) entry else null,
                hingeShowingMineMenu = false,
            ).clearDetailIf(changed)
        }
    }

    fun selectForumSource(source: ForumListSource) {
        hasMutatedState = true
        hasUserSelectedForum = true
        updateReleasingStaleThreads { cur ->
            val changed = cur.activeForumSource != source || cur.selectedDestination != ShellDestination.Forum
            cur.copy(
                selectedDestination = ShellDestination.Forum,
                selectedSidebarEntry = source.toSidebarEntry(),
                activeForumSource = source,
                activeMineSubPage = null,
                hingeShowingMineMenu = false,
            ).clearDetailIf(changed)
        }
    }

    fun returnToMineMenu(keepSubListInRightPane: Boolean = false) {
        hasMutatedState = true
        updateReleasingStaleThreads { cur ->
            cur.copy(
                selectedDestination = ShellDestination.Mine,
                activeMineSubPage = if (keepSubListInRightPane && cur.selectedSidebarEntry.isMineGroupChild) cur.selectedSidebarEntry else null,
                hingeShowingMineMenu = true,
            ).clearDetailIf(true)
        }
    }

    /** 中栏点击帖子只替换右栏根帖子并清空下钻栈，绝不压栈（product-design.md §4.2）。 */
    fun openThread(args: ThreadNavArgs) {
        hasMutatedState = true
        updateReleasingStaleThreads { cur ->
            cur.copy(selectedThread = args, detailDrilldownStack = emptyList(), hingeShowingMineMenu = false)
        }
    }

    fun closeThread() {
        hasMutatedState = true
        updateReleasingStaleThreads { it.clearDetailIf(true) }
    }

    fun pushUserProfile(uid: String, username: String) {
        hasMutatedState = true
        _state.update { it.copy(detailDrilldownStack = it.detailDrilldownStack + DetailDrilldownEntry.UserProfile(uid, username)) }
    }

    fun popDetailDrilldown() {
        hasMutatedState = true
        updateReleasingStaleThreads { cur ->
            if (cur.detailDrilldownStack.isEmpty()) cur else cur.copy(detailDrilldownStack = cur.detailDrilldownStack.dropLast(1))
        }
    }

    fun toggleFullScreenReading() {
        hasMutatedState = true
        _state.update { cur ->
            if (!cur.hasDetailContent) cur.copy(isFullScreenReading = false) else cur.copy(isFullScreenReading = !cur.isFullScreenReading)
        }
    }

    fun exitFullScreenReading() {
        if (!_state.value.isFullScreenReading) return
        hasMutatedState = true
        _state.update { it.copy(isFullScreenReading = false) }
    }

    /** 按 product-design.md §4.2 五级优先级处理返回事件；返回 `false` 表示应交还系统退出。 */
    fun handleBack(layoutMode: ShellPaneLayoutMode): Boolean {
        val action = resolveMultiPaneBackAction(_state.value, layoutMode)
        if (action == MultiPaneBackAction.PassToSystem) return false
        hasMutatedState = true
        updateReleasingStaleThreads { cur -> applyMultiPaneBackAction(cur, action) }
        return true
    }

    fun openLoginOverlay(mode: LoginOverlayMode = LoginOverlayMode.Login) {
        hasMutatedState = true
        _state.update { it.copy(loginOverlayMode = mode) }
    }

    fun dismissLoginOverlay() {
        hasMutatedState = true
        _state.update { it.copy(loginOverlayMode = null) }
    }

    fun persistPaneSplit(isLandscape: Boolean, split: PaneSplit) {
        val repo = settingsRepository ?: return
        viewModelScope.launch {
            if (isLandscape) repo.setPaneSplitLandscape(split) else repo.setPaneSplitPortrait(split)
        }
    }

    fun persistSidebarCollapsed(isLandscape: Boolean, collapsed: Boolean) {
        val repo = settingsRepository ?: return
        viewModelScope.launch {
            if (isLandscape) repo.setSidebarCollapsedLandscape(collapsed) else repo.setSidebarCollapsedPortrait(collapsed)
        }
    }

    private fun updateReleasingStaleThreads(transform: (NextAppState) -> NextAppState) {
        var staleTids: Set<String> = emptySet()
        _state.update { cur ->
            val next = transform(cur)
            staleTids = cur.activeThreadTids() - next.activeThreadTids()
            next
        }
        staleTids.forEach { tid ->
            if ((mountedThreadCounts[tid] ?: 0) == 0) releaseThreadStore(tid)
        }
    }

    override fun onCleared() {
        threadStores.values.toList().also { threadStores.clear() }.forEach(ViewModelStore::clear)
    }
}

private fun NextAppState.clearDetailIf(condition: Boolean): NextAppState =
    if (condition) copy(selectedThread = null, detailDrilldownStack = emptyList(), isFullScreenReading = false) else this

private fun ForumListSource.toSidebarEntry(): ShellSidebarEntry = when (this) {
    ForumListSource.TodayNew -> ShellSidebarEntry.TodayNew
    is ForumListSource.Forum -> ShellSidebarEntry.Forum(fid)
}

private fun ShellSidebarEntry.toForumListSourceOrNull(): ForumListSource? = when (this) {
    ShellSidebarEntry.TodayNew -> ForumListSource.TodayNew
    is ShellSidebarEntry.Forum -> ForumListSource.Forum(fid)
    else -> null
}
