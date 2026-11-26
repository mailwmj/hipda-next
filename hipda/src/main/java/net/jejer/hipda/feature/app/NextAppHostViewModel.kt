package net.jejer.hipda.feature.app

import android.content.Intent
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jejer.hipda.BuildConfig
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.SettingsGroup
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.routing.DeepLinkTarget
import net.jejer.hipda.feature.routing.parseIncomingIntent
import net.jejer.hipda.service.NotificationScheduler
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellPaneLayoutMode
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import net.jejer.hipda.ui.shell.mapDestinationToSidebarEntry
import net.jejer.hipda.ui.shell.toShellDestination

/** 外部链接/站内跳转异步解析失败或无法识别时发给宿主 Snackbar 的反馈事件。 */
sealed interface DeepLinkFeedbackEvent {
    data class UnsupportedUrl(val url: String) : DeepLinkFeedbackEvent
    data object PostResolveFailed : DeepLinkFeedbackEvent
}

/** 按 `tid` 隔离托管的 [ViewModelStoreOwner]，显式实现 [HasDefaultViewModelProviderFactory] 支持 `hiltViewModel()`。 */
class ThreadScopedViewModelStoreOwner(
    val tid: String,
    override val viewModelStore: ViewModelStore,
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory,
    override val defaultViewModelCreationExtras: CreationExtras = CreationExtras.Empty,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory

/** Activity 级宿主 ViewModel：托管按 `tid` 隔离的 [ViewModelStore]、单一导航状态源 [NextAppState]、5 级返回栈、冷启通知调度、版本更新提示、深链路由与分栏偏好持久化。 */
@HiltViewModel
class NextAppHostViewModel internal constructor(
    private val settingsRepository: SettingsRepository?,
    private val threadDetailRepository: ThreadDetailRepository?,
    private val notificationScheduler: NotificationScheduler?,
    private val releaseNotesPromptStore: ReleaseNotesPromptStore?,
    private val currentVersionCode: Int,
) : ViewModel() {

    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        threadDetailRepository: ThreadDetailRepository,
        notificationScheduler: NotificationScheduler,
        releaseNotesPromptStore: ReleaseNotesPromptStore,
    ) : this(
        settingsRepository = settingsRepository,
        threadDetailRepository = threadDetailRepository,
        notificationScheduler = notificationScheduler,
        releaseNotesPromptStore = releaseNotesPromptStore,
        currentVersionCode = BuildConfig.VERSION_CODE,
    )

    constructor(
        settingsRepository: SettingsRepository,
        threadDetailRepository: ThreadDetailRepository,
    ) : this(settingsRepository, threadDetailRepository, null, null, BuildConfig.VERSION_CODE)

    constructor(settingsRepository: SettingsRepository) : this(settingsRepository, null, null, null, BuildConfig.VERSION_CODE)

    constructor() : this(null, null, null, null, BuildConfig.VERSION_CODE)

    private val threadStores = LinkedHashMap<String, ViewModelStore>()
    private val mountedThreadCounts = HashMap<String, Int>()
    private var hasUserSelectedForum = false
    private var hasMutatedState = false
    private var releaseNotesPromptDismissed = false
    private var deepLinkResolveJob: Job? = null
    private var deepLinkResolveGen: Long = 0L

    private val _state = MutableStateFlow(NextAppState())
    val state: StateFlow<NextAppState> = _state.asStateFlow()

    private val _showReleaseNotesPrompt = MutableStateFlow(false)
    val showReleaseNotesPrompt: StateFlow<Boolean> = _showReleaseNotesPrompt.asStateFlow()

    private val _pendingIntents = MutableStateFlow<List<Intent>>(emptyList())
    internal val pendingIntents: StateFlow<List<Intent>> = _pendingIntents.asStateFlow()

    private val _deepLinkFeedback = MutableSharedFlow<DeepLinkFeedbackEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val deepLinkFeedback: SharedFlow<DeepLinkFeedbackEvent> = _deepLinkFeedback.asSharedFlow()

    init {
        notificationScheduler?.let { scheduler ->
            viewModelScope.launch {
                try {
                    scheduler.syncFromSettings()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        releaseNotesPromptStore?.let { store ->
            if (currentVersionCode > 0) {
                viewModelScope.launch {
                    val lastSeen = try {
                        store.getLastSeenVersionCode()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        currentVersionCode
                    }
                    if (!releaseNotesPromptDismissed && lastSeen != currentVersionCode) {
                        _showReleaseNotesPrompt.value = true
                    }
                }
            }
        }
    }

    /** 关闭冷启动更新记录提示并立即持久化当前 `versionCode`，确保同版本后续冷启动与旋转不再重复弹出。 */
    fun dismissReleaseNotesPrompt() {
        releaseNotesPromptDismissed = true
        _showReleaseNotesPrompt.value = false
        val store = releaseNotesPromptStore ?: return
        if (currentVersionCode <= 0) return
        viewModelScope.launch {
            try {
                store.setLastSeenVersionCode(currentVersionCode)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

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
        cancelDeepLinkResolve()
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
        cancelDeepLinkResolve()
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
        cancelDeepLinkResolve()
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

    /** 导航至「设置」并直达指定分组（例如论坛/侧栏「管理版块」直达 [SettingsGroup.ForumAndPost]，单栏自动进入分组详情）。 */
    fun openSettingsGroup(group: SettingsGroup, openDetailInSinglePane: Boolean = false) {
        cancelDeepLinkResolve()
        hasMutatedState = true
        updateReleasingStaleThreads { cur ->
            cur.copy(
                selectedDestination = ShellDestination.Mine,
                selectedSidebarEntry = ShellSidebarEntry.Settings,
                lastVisitedMineEntry = ShellSidebarEntry.Settings,
                activeMineSubPage = ShellSidebarEntry.Settings,
                hingeShowingMineMenu = false,
                selectedSettingsGroup = group,
                isSettingsDetailOpen = openDetailInSinglePane,
                selectedThread = null,
                selectedSmsConversation = null,
                detailDrilldownStack = emptyList(),
                isFullScreenReading = false,
            )
        }
    }

    fun returnToMineMenu(keepSubListInRightPane: Boolean = false) {
        cancelDeepLinkResolve()
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
        cancelDeepLinkResolve()
        hasMutatedState = true
        updateReleasingStaleThreads { cur ->
            cur.copy(selectedThread = args, selectedSmsConversation = null, detailDrilldownStack = emptyList(), hingeShowingMineMenu = false)
        }
    }

    /** 中栏短消息列表点开对话，替换右栏根内容并清空下钻栈。 */
    fun openSmsConversation(args: SmsConversationArgs) {
        cancelDeepLinkResolve()
        hasMutatedState = true
        updateReleasingStaleThreads { cur ->
            cur.copy(selectedThread = null, selectedSmsConversation = args, detailDrilldownStack = emptyList(), hingeShowingMineMenu = false)
        }
    }

    /** 接收 Activity 传入的 Intent 排队等待 `settings.forumServer` 就绪后一次性消费（旋转不重复触发）。 */
    fun submitIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val copy = Intent(intent)
        _pendingIntents.update { it + copy }
    }

    /** 原子取出待处理 Intent 列表并按 [forumServer] 解析消费；非法/非深链 Intent 静默忽略且不污染状态。 */
    fun consumePendingIntents(forumServer: String) {
        val batch = _pendingIntents.getAndUpdate { emptyList() }
        for (intent in batch) {
            val target = parseIncomingIntent(intent, forumServer) ?: continue
            applyDeepLink(target)
        }
    }

    /** 统一应用外部链接、桌面快捷方式与系统通知解析结果。 */
    fun applyDeepLink(target: DeepLinkTarget) {
        cancelDeepLinkResolve()
        when (target) {
            DeepLinkTarget.TodayNew -> {
                selectForumSource(ForumListSource.TodayNew)
                clearDetailAndOverlay()
            }
            DeepLinkTarget.Search -> {
                selectSidebarEntry(ShellSidebarEntry.Search)
                clearDetailAndOverlay()
            }
            DeepLinkTarget.Sms -> {
                selectSidebarEntry(ShellSidebarEntry.Sms)
                clearDetailAndOverlay()
            }
            DeepLinkTarget.Notify -> {
                selectSidebarEntry(ShellSidebarEntry.Notify)
                clearDetailAndOverlay()
            }
            DeepLinkTarget.Favorites -> {
                selectSidebarEntry(ShellSidebarEntry.FavoritesAndAttention)
                clearDetailAndOverlay()
            }
            is DeepLinkTarget.SmsConversation -> {
                hasMutatedState = true
                updateReleasingStaleThreads { cur ->
                    cur.copy(
                        selectedDestination = ShellDestination.Messages,
                        selectedSidebarEntry = ShellSidebarEntry.Sms,
                        activeMessageEntry = ShellSidebarEntry.Sms,
                        activeMineSubPage = null,
                        hingeShowingMineMenu = false,
                        selectedThread = null,
                        selectedSmsConversation = SmsConversationArgs(target.uid, ""),
                        isSettingsDetailOpen = false,
                        detailDrilldownStack = emptyList(),
                        isFullScreenReading = false,
                        loginOverlayMode = null,
                    )
                }
            }
            is DeepLinkTarget.Forum -> {
                selectForumSource(ForumListSource.Forum(target.fid))
                clearDetailAndOverlay()
            }
            is DeepLinkTarget.Thread -> {
                if (target.page > 1 && threadDetailRepository != null) {
                    resolveThreadTargetAsync(fallbackTid = target.tid) {
                        val res = threadDetailRepository.loadPage(target.tid, target.page, recordHistory = false, priority = AccessPriority.UserBlocking)
                        (res as? ThreadDetailLoadResult.Success)?.let { ok ->
                            ThreadNavArgs(
                                tid = target.tid,
                                title = ok.title,
                                fid = ok.fid,
                                forumName = ok.forumName,
                                targetPid = ok.page.floors.firstOrNull()?.postId,
                            )
                        }
                    }
                } else {
                    openThread(ThreadNavArgs(tid = target.tid))
                    clearOverlayIfOpen()
                }
            }
            is DeepLinkTarget.ThreadLastPage -> {
                if (threadDetailRepository != null) {
                    resolveThreadTargetAsync(fallbackTid = target.tid) {
                        val res = threadDetailRepository.loadLastPage(target.tid, recordHistory = false, priority = AccessPriority.UserBlocking)
                        (res as? ThreadDetailLoadResult.Success)?.let { ok ->
                            ThreadNavArgs(
                                tid = target.tid,
                                title = ok.title,
                                fid = ok.fid,
                                forumName = ok.forumName,
                                targetPid = ok.page.floors.lastOrNull()?.postId,
                            )
                        }
                    }
                } else {
                    openThread(ThreadNavArgs(tid = target.tid))
                    clearOverlayIfOpen()
                }
            }
            is DeepLinkTarget.Post -> {
                val explicitTid = target.tid?.takeIf { it.isNotBlank() }
                if (explicitTid != null) {
                    openThread(ThreadNavArgs(tid = explicitTid, targetPid = target.pid))
                    clearOverlayIfOpen()
                } else if (threadDetailRepository != null) {
                    resolveThreadTargetAsync(fallbackTid = null) {
                        val res = threadDetailRepository.loadByPostId(target.pid, tid = null, recordHistory = false, priority = AccessPriority.UserBlocking)
                        val ok = res as? ThreadDetailLoadResult.Success ?: return@resolveThreadTargetAsync null
                        val resolvedTid = ok.tid?.takeIf { it.isNotBlank() } ?: return@resolveThreadTargetAsync null
                        ThreadNavArgs(
                            tid = resolvedTid,
                            title = ok.title,
                            fid = ok.fid,
                            forumName = ok.forumName,
                            targetPid = ok.targetPostId?.takeIf { it.isNotBlank() } ?: target.pid,
                        )
                    }
                } else {
                    _deepLinkFeedback.tryEmit(DeepLinkFeedbackEvent.PostResolveFailed)
                }
            }
            is DeepLinkTarget.User -> {
                hasMutatedState = true
                updateReleasingStaleThreads { cur ->
                    cur.copy(
                        selectedThread = null,
                        selectedSmsConversation = null,
                        isSettingsDetailOpen = false,
                        isFullScreenReading = false,
                        loginOverlayMode = null,
                        detailDrilldownStack = listOf(DetailDrilldownEntry.UserProfile(target.uid, "")),
                    )
                }
            }
            is DeepLinkTarget.UnsupportedUrl -> {
                _deepLinkFeedback.tryEmit(DeepLinkFeedbackEvent.UnsupportedUrl(target.url))
            }
        }
    }

    private fun resolveThreadTargetAsync(
        fallbackTid: String?,
        resolver: suspend () -> ThreadNavArgs?,
    ) {
        hasMutatedState = true
        val gen = ++deepLinkResolveGen
        deepLinkResolveJob = viewModelScope.launch {
            val resolved = runCatching { resolver() }.getOrNull()
            if (gen != deepLinkResolveGen) return@launch
            when {
                resolved != null -> {
                    openThread(resolved)
                    clearOverlayIfOpen()
                }
                fallbackTid != null -> {
                    openThread(ThreadNavArgs(tid = fallbackTid))
                    clearOverlayIfOpen()
                }
                else -> _deepLinkFeedback.tryEmit(DeepLinkFeedbackEvent.PostResolveFailed)
            }
        }
    }

    private fun cancelDeepLinkResolve() {
        deepLinkResolveGen++
        deepLinkResolveJob?.cancel()
        deepLinkResolveJob = null
    }

    private fun clearDetailAndOverlay() {
        updateReleasingStaleThreads { cur ->
            cur.clearDetailIf(true).copy(loginOverlayMode = null)
        }
    }

    private fun clearOverlayIfOpen() {
        if (_state.value.loginOverlayMode != null) {
            _state.update { it.copy(loginOverlayMode = null) }
        }
    }

    fun selectSettingsGroup(group: SettingsGroup, openDetailInSinglePane: Boolean = false) {
        hasMutatedState = true
        _state.update { it.copy(selectedSettingsGroup = group, isSettingsDetailOpen = openDetailInSinglePane) }
    }

    fun closeThread() {
        cancelDeepLinkResolve()
        hasMutatedState = true
        updateReleasingStaleThreads { it.clearDetailIf(true) }
    }

    fun pushUserProfile(uid: String, username: String) {
        hasMutatedState = true
        _state.update { it.copy(detailDrilldownStack = it.detailDrilldownStack + DetailDrilldownEntry.UserProfile(uid, username)) }
    }

    fun pushSmsConversation(args: SmsConversationArgs) {
        hasMutatedState = true
        _state.update { it.copy(detailDrilldownStack = it.detailDrilldownStack + DetailDrilldownEntry.SmsConversation(args)) }
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
        cancelDeepLinkResolve()
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
        cancelDeepLinkResolve()
        threadStores.values.toList().also { threadStores.clear() }.forEach(ViewModelStore::clear)
    }
}

private fun NextAppState.clearDetailIf(condition: Boolean): NextAppState =
    if (condition) {
        copy(selectedThread = null, selectedSmsConversation = null, isSettingsDetailOpen = false, detailDrilldownStack = emptyList(), isFullScreenReading = false)
    } else {
        this
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
