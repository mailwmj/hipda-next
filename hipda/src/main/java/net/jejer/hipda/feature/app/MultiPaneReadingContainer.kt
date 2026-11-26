package net.jejer.hipda.feature.app

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.HiltViewModelFactory
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dagger.hilt.internal.GeneratedComponentManagerHolder
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.ForumChromeSpec
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.FullScreenToggle
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.glass.HiGlassState
import net.jejer.hipda.ui.designsystem.glass.hiGlassSource
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut
import net.jejer.hipda.ui.designsystem.motion.hiSlideInHorizontally
import net.jejer.hipda.ui.designsystem.motion.hiSlideOutHorizontally
import net.jejer.hipda.ui.designsystem.state.HiEmptyState
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellPaneLayout
import net.jejer.hipda.ui.shell.ShellPaneLayoutMode
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import net.jejer.hipda.ui.shell.mapDestinationToSidebarEntry
import net.jejer.hipda.ui.shell.toShellDestination

@Immutable
private sealed interface RightPaneSlot {
    val depth: Int
    val slotKey: Any
    data object Empty : RightPaneSlot { override val depth = 0; override val slotKey = "empty" }
    data class Thread(val args: ThreadNavArgs) : RightPaneSlot { override val depth = 0; override val slotKey = "thread:${args.tid}" }
    data class SmsConversation(val args: SmsConversationArgs) : RightPaneSlot { override val depth = 0; override val slotKey = "sms:${args.uid}" }
    data object Settings : RightPaneSlot { override val depth = 0; override val slotKey = "settings" }
    data class MineSubList(val entry: ShellSidebarEntry) : RightPaneSlot { override val depth = 0; override val slotKey = "mine:${sidebarSaveableKey(entry)}" }
    data class Drilldown(val entry: DetailDrilldownEntry, override val depth: Int) : RightPaneSlot {
        override val slotKey: Any = when (entry) {
            is DetailDrilldownEntry.UserProfile -> "drill:$depth:u:${entry.uid}"
            is DetailDrilldownEntry.SmsConversation -> "drill:$depth:s:${entry.args.uid}"
        }
    }
}

internal const val MAX_SAVED_DETAIL_STATES: Int = 20

/**
 * 提升到 `HiAdaptiveShell` `AnimatedContent` 之外的槽位与可保存状态宿主（R-E3 B2）：
 * 跨一级目的地共享同一 `SaveableStateHolder` 与 `detailMovables`，确保切页不丢列表/详情滚动位，
 * 且同帖跨目的地移交时直接迁移唯一 `movableContentOf` 实例、绝不产生重复 `SaveableStateProvider` key。
 */
@Stable
class MultiPaneSlotHolder internal constructor(
    internal val hostViewModel: NextAppHostViewModel,
    internal val saveableStateHolder: SaveableStateHolder,
    private val maxSavedDetailStates: Int = MAX_SAVED_DETAIL_STATES,
) {
    private val savedDetailLimit: Int = maxSavedDetailStates.coerceAtLeast(1)
    internal var detailRenderer: @Composable (ThreadNavArgs, DetailChromeSpec, PaddingValues, LazyListState, () -> Unit) -> Unit = { _, _, _, _, _ -> }
    internal var activeThreadTids: Set<String> = emptySet()
    internal val detailMovables = LinkedHashMap<String, @Composable (ThreadNavArgs, DetailChromeSpec, PaddingValues, () -> Unit) -> Unit>()
    private val savedDetailLru = LinkedHashMap<String, Unit>(savedDetailLimit + 1, 0.75f, true)
    private val mountedDetailCounts = HashMap<String, Int>()

    private fun onDetailStateMounted(tid: String) {
        mountedDetailCounts[tid] = (mountedDetailCounts[tid] ?: 0) + 1
        savedDetailLru[tid] = Unit
        evictExcessSavedDetailStates()
    }

    private fun onDetailStateDisposed(tid: String, isInactive: Boolean) {
        val next = (mountedDetailCounts[tid] ?: 0) - 1
        if (next <= 0) mountedDetailCounts.remove(tid) else mountedDetailCounts[tid] = next
        if (isInactive) evictExcessSavedDetailStates()
    }

    private fun evictExcessSavedDetailStates() {
        if (savedDetailLru.size <= savedDetailLimit) return
        val iterator = savedDetailLru.keys.iterator()
        while (savedDetailLru.size > savedDetailLimit && iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate !in activeThreadTids && (mountedDetailCounts[candidate] ?: 0) == 0) {
                iterator.remove()
                saveableStateHolder.removeState(detailSaveableKey(candidate))
            }
        }
    }

    internal fun detailMovableFor(tid: String) = detailMovables.getOrPut(tid) {
        movableContentOf { args: ThreadNavArgs, chromeSpec: DetailChromeSpec, padding: PaddingValues, onBack: () -> Unit ->
            val factory = rememberThreadViewModelProviderFactory()
            val parentOwner = LocalViewModelStoreOwner.current
            val extras = remember(parentOwner) {
                (parentOwner as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras ?: CreationExtras.Empty
            }
            key(args.tid) {
                DisposableEffect(args.tid) {
                    hostViewModel.onThreadMounted(args.tid)
                    onDetailStateMounted(args.tid)
                    onDispose {
                        hostViewModel.onThreadDisposed(args.tid)
                        val isInactive = args.tid !in activeThreadTids
                        if (isInactive) {
                            detailMovables.remove(args.tid)
                        }
                        onDetailStateDisposed(args.tid, isInactive)
                    }
                }
                saveableStateHolder.SaveableStateProvider(key = detailSaveableKey(args.tid)) {
                    val scopedOwner = remember(hostViewModel, args.tid, factory, extras) {
                        hostViewModel.viewModelStoreOwnerForThread(args.tid, factory, extras)
                    }
                    val listState = rememberLazyListState()
                    CompositionLocalProvider(LocalViewModelStoreOwner provides scopedOwner) {
                        detailRenderer(args, chromeSpec, padding, listState, onBack)
                    }
                }
            }
        }
    }
}

@Composable
fun rememberMultiPaneSlotHolder(hostViewModel: NextAppHostViewModel): MultiPaneSlotHolder {
    val saveableStateHolder = rememberSaveableStateHolder()
    return remember(hostViewModel, saveableStateHolder) { MultiPaneSlotHolder(hostViewModel, saveableStateHolder) }
}

/**
 * M5 完整多栏阅读引擎（单栏 / 图标轨单栏 / 紧凑与完整三栏 / 物理铰链双栏 / 全屏阅读）：
 * - 三栏中右分隔线由父容器 `Box` 横向门控手势驱动标准 `AnchoredDraggableState` + `HiMotion.spatialSnap()`，
 *   不遮挡中栏右上「更多」/右栏左上「返回」及列表竖滑（R-E3 B1），空右栏锚点集严格不含 `0dp`（B4-E3 ①）；
 * - 消费外层提升的 [slotHolder] 并按分支快照 [destination] 渲染，跨一级目的地保留全部 `LazyListState`
 *   且切页过渡期间同页与同玻璃源绝不双份挂载（R-E3 B2）；
 * - 详情顶栏 `全屏阅读 / 显示列表` 联动隐藏左中栏并释放中栏玻璃源；铰链双栏强制 `useGlassTopBar = false` 守 3 区域上限。
 */
@Composable
fun MultiPaneReadingContainer(
    paneLayout: ShellPaneLayout,
    appState: NextAppState,
    hostViewModel: NextAppHostViewModel,
    contentPadding: PaddingValues,
    persistedPaneSplit: PaneSplit,
    onPaneSplitChange: (PaneSplit) -> Unit,
    modifier: Modifier = Modifier,
    destination: ShellDestination = appState.selectedDestination,
    slotHolder: MultiPaneSlotHolder = rememberMultiPaneSlotHolder(hostViewModel),
    shellGlassState: HiGlassState? = null,
    onMiddlePaneWidthChanged: (Dp) -> Unit = {},
    suppressDetailBottomGlass: Boolean = false,
    rightPaneOverlay: (@Composable () -> Unit)? = null,
    forumContent: @Composable (ForumChromeSpec, PaddingValues, LazyListState) -> Unit,
    detailContent: @Composable (ThreadNavArgs, DetailChromeSpec, PaddingValues, LazyListState, () -> Unit) -> Unit,
    secondaryListContent: @Composable (ShellSidebarEntry, Boolean, (() -> Unit)?, PaddingValues, LazyListState) -> Unit = { _, _, _, _, _ -> },
    mineMenuContent: @Composable (PaddingValues) -> Unit = {},
    settingsDetailContent: @Composable (PaddingValues) -> Unit = {},
    userProfileContent: @Composable (DetailDrilldownEntry.UserProfile, Boolean, () -> Unit, PaddingValues) -> Unit = { _, _, _, _ -> },
    smsConversationContent: @Composable (SmsConversationArgs, Boolean, () -> Unit, PaddingValues) -> Unit = { _, _, _, _ -> },
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reducedMotion = HiTheme.isReducedMotion
    val middleGlassState = rememberHiGlassState()
    val isActiveDestination = destination == appState.selectedDestination
    val saveableStateHolder = slotHolder.saveableStateHolder

    val currentForumContent by rememberUpdatedState(forumContent)
    val currentSecondaryList by rememberUpdatedState(secondaryListContent)
    val currentMineMenu by rememberUpdatedState(mineMenuContent)
    val currentSettingsDetail by rememberUpdatedState(settingsDetailContent)
    val currentUserProfile by rememberUpdatedState(userProfileContent)
    val currentSmsConversation by rememberUpdatedState(smsConversationContent)
    val latestOnSplitChange by rememberUpdatedState(onPaneSplitChange)
    if (isActiveDestination) {
        slotHolder.detailRenderer = detailContent
        slotHolder.activeThreadTids = appState.activeThreadTids()
    }

    val secondaryMovables = remember(saveableStateHolder) {
        LinkedHashMap<String, @Composable (ShellSidebarEntry, Boolean, (() -> Unit)?, PaddingValues) -> Unit>()
    }

    LaunchedEffect(isActiveDestination, paneLayout.hasDetailPane, appState.hasDetailContent) {
        if (isActiveDestination && (!paneLayout.hasDetailPane || !appState.hasDetailContent)) {
            hostViewModel.exitFullScreenReading()
        }
    }

    val forumMovable = remember(saveableStateHolder) {
        movableContentOf { source: ForumListSource, chromeSpec: ForumChromeSpec, padding: PaddingValues ->
            saveableStateHolder.SaveableStateProvider(key = forumSaveableKey(source)) {
                currentForumContent(chromeSpec, padding, rememberLazyListState())
            }
        }
    }

    fun secondaryMovableFor(entry: ShellSidebarEntry) = secondaryMovables.getOrPut(sidebarSaveableKey(entry)) {
        movableContentOf { target: ShellSidebarEntry, showBack: Boolean, onBack: (() -> Unit)?, padding: PaddingValues ->
            saveableStateHolder.SaveableStateProvider(key = sidebarSaveableKey(target)) {
                currentSecondaryList(target, showBack, onBack, padding, rememberLazyListState())
            }
        }
    }

    fun detailMovableFor(tid: String) = slotHolder.detailMovableFor(tid)

    val backAction = resolveMultiPaneBackAction(appState, paneLayout.layoutMode)
    BackHandler(enabled = isActiveDestination && backAction != MultiPaneBackAction.PassToSystem) {
        hostViewModel.handleBack(paneLayout.layoutMode)
    }

    // 退场分支按自身 destination 快照解析条目，避免跨目的地过渡期间双份渲染目标页（R-E3 B2）
    val branchSidebarEntry = if (appState.selectedSidebarEntry.toShellDestination() == destination) {
        appState.selectedSidebarEntry
    } else {
        mapDestinationToSidebarEntry(
            destination = destination,
            activeForumFid = (appState.activeForumSource as? ForumListSource.Forum)?.fid,
            activeMessageEntry = appState.activeMessageEntry,
            activeMineSubPage = appState.activeMineSubPage,
            lastVisitedMineEntry = appState.lastVisitedMineEntry,
        )
    }
    val branchAppState = if (isActiveDestination) {
        appState
    } else {
        appState.copy(
            selectedDestination = destination,
            selectedSidebarEntry = branchSidebarEntry,
            selectedThread = null,
            selectedSmsConversation = null,
            isSettingsDetailOpen = false,
            detailDrilldownStack = emptyList(),
            isFullScreenReading = false,
        )
    }

    val navBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val detailPanePadding = remember(paneLayout.layoutMode, contentPadding, navBarsBottom) {
        if (paneLayout.layoutMode == ShellPaneLayoutMode.HingeTwoPane) PaddingValues(bottom = navBarsBottom) else contentPadding
    }
    val forumChromeSpec = remember(isActiveDestination, paneLayout.isSidebar, paneLayout.layoutMode, shellGlassState, middleGlassState) {
        when {
            !isActiveDestination -> ForumChromeSpec(!paneLayout.isSidebar, useGlassTopBar = false, externalGlassState = null)
            paneLayout.layoutMode == ShellPaneLayoutMode.HingeTwoPane -> ForumChromeSpec(true, useGlassTopBar = false, externalGlassState = shellGlassState)
            paneLayout.isSidebar -> ForumChromeSpec(false, useGlassTopBar = true, externalGlassState = middleGlassState)
            else -> ForumChromeSpec(true, useGlassTopBar = true, externalGlassState = shellGlassState)
        }
    }
    val fullScreenToggle = remember(paneLayout.hasDetailPane, branchAppState.isFullScreenReading, hostViewModel) {
        if (paneLayout.hasDetailPane) FullScreenToggle(branchAppState.isFullScreenReading, hostViewModel::toggleFullScreenReading) else null
    }
    val rootDetailChrome = remember(paneLayout.hasDetailPane, fullScreenToggle, suppressDetailBottomGlass) {
        DetailChromeSpec(!paneLayout.hasDetailPane, fullScreenToggle, suppressBottomGlass = suppressDetailBottomGlass)
    }

    val listPaneTitle = stringResource(R.string.shell_pane_list_title)
    val detailPaneTitle = stringResource(R.string.shell_pane_detail_title)
    val slideOffsetPx = with(density) { 24.dp.roundToPx() }
    val listGlassModifier = if (isActiveDestination && shellGlassState != null) Modifier.hiGlassSource(shellGlassState) else Modifier

    @Composable
    fun RenderRightSlot(slot: RightPaneSlot, padding: PaddingValues) {
        val currentSlot by rememberUpdatedState(slot)
        val emptyText = when (branchSidebarEntry) {
            ShellSidebarEntry.Sms -> stringResource(R.string.shell_empty_select_sms)
            ShellSidebarEntry.Notify -> stringResource(R.string.shell_empty_select_notify)
            else -> stringResource(R.string.shell_empty_select_thread)
        }
        AnimatedContent(
            targetState = slot,
            contentKey = { it.slotKey },
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                when {
                    targetState.depth > initialState.depth ->
                        (hiSlideInHorizontally(HiMotion.spatialDefault()) { slideOffsetPx } + hiFadeIn(HiMotion.effectsDefault())) togetherWith
                            hiFadeOut(HiMotion.exit(HiMotion.effectsDefault()))
                    targetState.depth < initialState.depth ->
                        hiFadeIn(HiMotion.effectsDefault()) togetherWith
                            (hiSlideOutHorizontally(HiMotion.exit(HiMotion.spatialDefault())) { slideOffsetPx } + hiFadeOut(HiMotion.exit(HiMotion.effectsDefault())))
                    else -> hiFadeIn(HiMotion.effectsDefault()) togetherWith hiFadeOut(HiMotion.exit(HiMotion.effectsDefault()))
                }.using(null)
            },
            label = "MultiPaneRightSlot",
        ) { target ->
            when (target) {
                RightPaneSlot.Empty -> Box(Modifier.fillMaxSize(), Alignment.Center) { HiEmptyState(emptyText) }
                is RightPaneSlot.Thread -> detailMovableFor(target.args.tid)(target.args, rootDetailChrome, padding) { hostViewModel.handleBack(paneLayout.layoutMode) }
                is RightPaneSlot.SmsConversation -> currentSmsConversation(target.args, !paneLayout.hasDetailPane, { hostViewModel.handleBack(paneLayout.layoutMode) }, padding)
                RightPaneSlot.Settings -> currentSettingsDetail(padding)
                is RightPaneSlot.MineSubList -> {
                    // 铰链双栏右栏副列表晋升至左栏时，退场槽即时让出 secondaryMovableFor 唯一持有权，避免同帧双占 SaveableStateProvider key（V-E3 F1）
                    if (target == currentSlot) {
                        secondaryMovableFor(target.entry)(target.entry, false, null, padding)
                    } else {
                        Box(Modifier.fillMaxSize())
                    }
                }
                is RightPaneSlot.Drilldown -> when (val e = target.entry) {
                    is DetailDrilldownEntry.UserProfile -> currentUserProfile(e, true, hostViewModel::popDetailDrilldown, padding)
                    is DetailDrilldownEntry.SmsConversation -> currentSmsConversation(e.args, true, hostViewModel::popDetailDrilldown, padding)
                }
            }
        }
    }

    // 三栏（≥ 680dp）
    if (paneLayout.isThreePane) {
        val anchorsDp = remember(paneLayout, branchAppState.hasDetailContent) { resolvePaneSplitAnchors(paneLayout, branchAppState.hasDetailContent) }
        val availableSplits = remember(anchorsDp) { anchorsDp.keys.toList() }
        val effectiveSplit = remember(persistedPaneSplit, anchorsDp) { resolveEffectivePaneSplit(persistedPaneSplit, anchorsDp) }
        val draggableAnchors = remember(anchorsDp, density) {
            DraggableAnchors { anchorsDp.forEach { (split, wDp) -> split at with(density) { wDp.dp.toPx() } } }
        }
        val dragState = remember { AnchoredDraggableState(initialValue = effectiveSplit, anchors = draggableAnchors) }
        val currentAnchorsDp by rememberUpdatedState(anchorsDp)
        val currentEffectiveSplit by rememberUpdatedState(effectiveSplit)
        var isDividerDragged by remember { mutableStateOf(false) }
        var isDividerFocused by remember { mutableStateOf(false) }
        var hadDetailContent by remember { mutableStateOf(branchAppState.hasDetailContent) }

        SideEffect {
            if (dragState.anchors != draggableAnchors) {
                val target = if (dragState.currentValue in anchorsDp) dragState.currentValue else effectiveSplit
                dragState.updateAnchors(draggableAnchors, newTarget = target)
            }
        }
        // R-E3 S2：空右栏回退 DEFAULT 后若打开帖子，将持久化档位对齐至当前可见 DEFAULT，防止后续旋转意外收起列表
        LaunchedEffect(isActiveDestination, branchAppState.hasDetailContent) {
            val enteredDetail = !hadDetailContent && branchAppState.hasDetailContent
            hadDetailContent = branchAppState.hasDetailContent
            if (isActiveDestination && enteredDetail && persistedPaneSplit == PaneSplit.COLLAPSED && dragState.settledValue == PaneSplit.DEFAULT) {
                latestOnSplitChange(PaneSplit.DEFAULT)
            }
        }
        LaunchedEffect(persistedPaneSplit) {
            val target = resolveEffectivePaneSplit(persistedPaneSplit, currentAnchorsDp)
            if (dragState.settledValue != target && !dragState.isAnimationRunning) dragState.snapTo(target)
        }
        LaunchedEffect(dragState, isActiveDestination) {
            if (!isActiveDestination) return@LaunchedEffect
            snapshotFlow { dragState.settledValue }.distinctUntilChanged().collect { settled ->
                if (settled != currentEffectiveSplit) latestOnSplitChange(settled)
            }
        }

        val defaultPx = with(density) { paneLayout.middlePaneWidthDp.dp.toPx() }
        val minPx = draggableAnchors.minPosition().let { if (it.isNaN()) defaultPx else it }
        val maxPx = draggableAnchors.maxPosition().let { if (it.isNaN()) defaultPx else it }
        val currentMiddlePx = if (branchAppState.isFullScreenReading) 0f else dragState.offset.let { if (it.isNaN()) defaultPx else it }.coerceIn(minPx, maxPx)
        val currentMiddleDp = with(density) { currentMiddlePx.toDp() }
        if (isActiveDestination) SideEffect { onMiddlePaneWidthChanged(currentMiddleDp) }

        val rightSlot: RightPaneSlot = when {
            branchAppState.detailDrilldownStack.isNotEmpty() -> RightPaneSlot.Drilldown(branchAppState.detailDrilldownStack.last(), branchAppState.detailDrilldownStack.size)
            branchAppState.selectedThread != null -> RightPaneSlot.Thread(branchAppState.selectedThread)
            branchAppState.selectedSmsConversation != null -> RightPaneSlot.SmsConversation(branchAppState.selectedSmsConversation)
            branchSidebarEntry == ShellSidebarEntry.Settings -> RightPaneSlot.Settings
            else -> RightPaneSlot.Empty
        }

        val contentPx = with(density) { paneLayout.contentWidthDp.dp.toPx() }
        val latestMiddlePx by rememberUpdatedState(currentMiddlePx)
        val latestContentPx by rememberUpdatedState(contentPx)

        Box(
            modifier = modifier
                .fillMaxSize()
                .paneDividerDrag(
                    dragState = dragState,
                    scope = scope,
                    enabled = isActiveDestination && !branchAppState.isFullScreenReading && availableSplits.isNotEmpty(),
                    reducedMotion = reducedMotion,
                    getDividerX = { latestMiddlePx },
                    getContentWidthPx = { latestContentPx },
                    onDraggingChanged = { isDividerDragged = it },
                    onSettled = { latestOnSplitChange(it) },
                ),
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (!branchAppState.isFullScreenReading && currentMiddlePx > 0.5f) {
                    Box(Modifier.width(currentMiddleDp).fillMaxHeight().semantics { isTraversalGroup = true; paneTitle = listPaneTitle }) {
                        if (destination == ShellDestination.Forum) {
                            forumMovable(branchAppState.activeForumSource, forumChromeSpec, contentPadding)
                        } else {
                            secondaryMovableFor(branchSidebarEntry)(branchSidebarEntry, false, null, contentPadding)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().semantics { isTraversalGroup = true; paneTitle = detailPaneTitle }) {
                    if (isActiveDestination) {
                        RenderRightSlot(rightSlot, detailPanePadding)
                        rightPaneOverlay?.invoke()
                    }
                }
            }
            if (!branchAppState.isFullScreenReading) {
                val handleW = with(density) { PANE_DIVIDER_HIT_WIDTH.toPx() }
                val handleOffsetPx = (currentMiddlePx - handleW / 2f).coerceIn(0f, (contentPx - handleW).coerceAtLeast(0f)).roundToInt()
                val lineOffsetPx = (currentMiddlePx - handleOffsetPx).coerceIn(0f, (handleW - 1f).coerceAtLeast(0f)).roundToInt()
                PaneDividerLine(
                    active = isDividerDragged || isDividerFocused,
                    modifier = Modifier.align(Alignment.CenterStart).offset { IntOffset(currentMiddlePx.roundToInt(), 0) },
                )
                PaneDividerHandle(
                    dragState = dragState,
                    availableSplits = availableSplits,
                    isDragged = isDividerDragged,
                    visualLineOffsetPx = lineOffsetPx,
                    onFocusedChanged = { isDividerFocused = it },
                    onSelectSplit = { target ->
                        scope.launch {
                            if (reducedMotion) dragState.snapTo(target) else dragState.animateTo(target, HiMotion.spatialSnap())
                            latestOnSplitChange(target)
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterStart).offset { IntOffset(handleOffsetPx, 0) },
                )
            }
        }
        return
    }

    // 物理铰链双栏（isSeparating == true && leftW ≥ 280dp && rightW ≥ 360dp）
    if (paneLayout.layoutMode == ShellPaneLayoutMode.HingeTwoPane) {
        val roles = resolveHingeTwoPaneRoles(branchAppState)
        val hingeGapDp = paneLayout.hingeBoundsDp?.let { (it.right - it.left).coerceAtLeast(0f).dp } ?: 0.dp
        if (isActiveDestination) SideEffect { onMiddlePaneWidthChanged(if (branchAppState.isFullScreenReading) 0.dp else paneLayout.middlePaneWidthDp.dp) }

        val rightSlot: RightPaneSlot = when (roles.right) {
            HingeRightPaneRole.DetailDrilldown -> RightPaneSlot.Drilldown(branchAppState.detailDrilldownStack.last(), branchAppState.detailDrilldownStack.size)
            HingeRightPaneRole.ThreadDetail -> branchAppState.selectedThread?.let(RightPaneSlot::Thread) ?: RightPaneSlot.Empty
            HingeRightPaneRole.SmsConversation -> branchAppState.selectedSmsConversation?.let(RightPaneSlot::SmsConversation) ?: RightPaneSlot.Empty
            HingeRightPaneRole.MineSubList -> roles.mineSubEntry?.let(RightPaneSlot::MineSubList) ?: RightPaneSlot.Empty
            HingeRightPaneRole.SettingsDetail -> RightPaneSlot.Settings
            HingeRightPaneRole.EmptyPlaceholder -> RightPaneSlot.Empty
        }

        Box(modifier = modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (!branchAppState.isFullScreenReading) {
                    val nonForumGlass = if (roles.left == HingeLeftPaneRole.Forum) Modifier else listGlassModifier
                    Box(
                        modifier = Modifier
                            .width(paneLayout.middlePaneWidthDp.dp)
                            .fillMaxHeight()
                            .then(nonForumGlass)
                            .semantics { isTraversalGroup = true; paneTitle = listPaneTitle },
                    ) {
                        when (roles.left) {
                            HingeLeftPaneRole.Forum -> forumMovable(branchAppState.activeForumSource, forumChromeSpec, contentPadding)
                            HingeLeftPaneRole.Messages -> secondaryMovableFor(branchAppState.activeMessageEntry)(branchAppState.activeMessageEntry, false, null, contentPadding)
                            HingeLeftPaneRole.Search -> secondaryMovableFor(ShellSidebarEntry.Search)(ShellSidebarEntry.Search, false, null, contentPadding)
                            HingeLeftPaneRole.MineMenu -> currentMineMenu(contentPadding)
                            HingeLeftPaneRole.PromotedMineSubList -> {
                                val promoted = roles.mineSubEntry ?: branchSidebarEntry
                                secondaryMovableFor(promoted)(promoted, true, { hostViewModel.returnToMineMenu(true) }, contentPadding)
                            }
                            HingeLeftPaneRole.SettingsGroups -> secondaryMovableFor(ShellSidebarEntry.Settings)(
                                ShellSidebarEntry.Settings, true, { hostViewModel.returnToMineMenu(false) }, contentPadding,
                            )
                        }
                    }
                    if (hingeGapDp > 0.dp) Spacer(Modifier.width(hingeGapDp).fillMaxHeight())
                }
                Box(Modifier.weight(1f).fillMaxHeight().semantics { isTraversalGroup = true; paneTitle = detailPaneTitle }) {
                    if (isActiveDestination) RenderRightSlot(rightSlot, detailPanePadding)
                }
            }
            if (!branchAppState.isFullScreenReading && hingeGapDp == 0.dp) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(with(density) { paneLayout.middlePaneWidthDp.dp.roundToPx() }, 0) }
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        return
    }

    // 单栏（< 600dp、矮屏、铰链退单栏）与 2 栏图标轨单栏（600–679dp）
    if (isActiveDestination) SideEffect { onMiddlePaneWidthChanged(paneLayout.middlePaneWidthDp.dp) }
    val isSingleSettingsDetail = branchSidebarEntry == ShellSidebarEntry.Settings && branchAppState.isSettingsDetailOpen
    val showDetail = branchAppState.detailDrilldownStack.isNotEmpty() ||
        branchAppState.selectedThread != null ||
        branchAppState.selectedSmsConversation != null ||
        isSingleSettingsDetail
    val nonForumSingleGlass = if (!showDetail && destination != ShellDestination.Forum) listGlassModifier else Modifier
    Box(modifier = modifier.fillMaxSize().then(nonForumSingleGlass)) {
        when {
            branchAppState.detailDrilldownStack.isNotEmpty() -> when (val e = branchAppState.detailDrilldownStack.last()) {
                is DetailDrilldownEntry.UserProfile -> currentUserProfile(e, true, hostViewModel::popDetailDrilldown, detailPanePadding)
                is DetailDrilldownEntry.SmsConversation -> currentSmsConversation(e.args, true, hostViewModel::popDetailDrilldown, detailPanePadding)
            }
            branchAppState.selectedThread != null -> detailMovableFor(branchAppState.selectedThread.tid)(branchAppState.selectedThread, rootDetailChrome, detailPanePadding) {
                hostViewModel.handleBack(paneLayout.layoutMode)
            }
            branchAppState.selectedSmsConversation != null -> currentSmsConversation(
                branchAppState.selectedSmsConversation, true, { hostViewModel.handleBack(paneLayout.layoutMode) }, detailPanePadding,
            )
            isSingleSettingsDetail -> currentSettingsDetail(detailPanePadding)
            destination == ShellDestination.Forum -> forumMovable(branchAppState.activeForumSource, forumChromeSpec, contentPadding)
            destination == ShellDestination.Messages -> secondaryMovableFor(branchAppState.activeMessageEntry)(branchAppState.activeMessageEntry, false, null, contentPadding)
            destination == ShellDestination.Search -> secondaryMovableFor(ShellSidebarEntry.Search)(ShellSidebarEntry.Search, false, null, contentPadding)
            destination == ShellDestination.Mine -> {
                val sub = if (paneLayout.isSidebar) branchSidebarEntry else branchAppState.activeMineSubPage
                if (sub != null) {
                    val onBack = if (!paneLayout.isSidebar) ({ hostViewModel.returnToMineMenu(false) }) else null
                    secondaryMovableFor(sub)(sub, !paneLayout.isSidebar, onBack, contentPadding)
                } else {
                    currentMineMenu(contentPadding)
                }
            }
        }
    }
}

internal fun forumSaveableKey(source: ForumListSource): String =
    if (source is ForumListSource.Forum) "forum:${source.fid}" else "forum:today_new"

internal fun detailSaveableKey(tid: String): String = "detail:$tid"

internal fun sidebarSaveableKey(entry: ShellSidebarEntry): String = when (entry) {
    is ShellSidebarEntry.Forum -> "sidebar:forum_${entry.fid}"
    else -> "sidebar:${entry::class.simpleName}"
}

@Composable
internal fun rememberThreadViewModelProviderFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current
    val parentOwner = LocalViewModelStoreOwner.current
    val delegateFactory = remember(parentOwner) {
        (parentOwner as? HasDefaultViewModelProviderFactory)?.defaultViewModelProviderFactory ?: ViewModelProvider.NewInstanceFactory()
    }
    return remember(context, delegateFactory) {
        context.findHiltComponentActivity()?.let { HiltViewModelFactory(it, delegateFactory) } ?: delegateFactory
    }
}

internal tailrec fun Context.findHiltComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> if (this is GeneratedComponentManagerHolder) this else null
    is ContextWrapper -> baseContext.findHiltComponentActivity()
    else -> null
}
