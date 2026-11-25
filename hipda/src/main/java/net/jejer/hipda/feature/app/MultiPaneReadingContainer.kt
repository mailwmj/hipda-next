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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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

@Immutable
private sealed interface RightPaneSlot {
    val depth: Int
    val slotKey: Any
    data object Empty : RightPaneSlot { override val depth = 0; override val slotKey = "empty" }
    data class Thread(val args: ThreadNavArgs) : RightPaneSlot { override val depth = 0; override val slotKey = "thread:${args.tid}" }
    data class Settings(val group: SettingsGroup) : RightPaneSlot { override val depth = 0; override val slotKey = "settings:${group.name}" }
    data class MineSubList(val entry: ShellSidebarEntry) : RightPaneSlot { override val depth = 0; override val slotKey = "mine:${sidebarSaveableKey(entry)}" }
    data class Drilldown(val entry: DetailDrilldownEntry, override val depth: Int) : RightPaneSlot {
        override val slotKey: Any = when (entry) {
            is DetailDrilldownEntry.UserProfile -> "drill:$depth:u:${entry.uid}"
            is DetailDrilldownEntry.LinkedThread -> "drill:$depth:t:${entry.args.tid}"
        }
    }
}

/**
 * M5 完整多栏阅读引擎（单栏 / 图标轨单栏 / 紧凑与完整三栏 / 物理铰链双栏 / 全屏阅读）：
 * - 三栏中右分隔线采用 Compose 标准 `AnchoredDraggable` + `HiMotion.spatialSnap()`，空右栏锚点集严格不含 `0dp`；
 * - 详情顶栏 `全屏阅读 / 显示列表` 经 `DetailChromeSpec.fullScreenState` 联动隐藏/恢复左中栏并释放中栏玻璃源；
 * - 中栏独立 `middleGlassState` + 右栏 `detailGlassState` 严格兄弟隔离，铰链双栏强制 `useGlassTopBar = false` 守 3 区域上限；
 * - `movableContentOf` + 按 `ForumListSource` / `tid` 键控的 `SaveableStateHolder` + `rememberLazyListState()`，
 *   实现跨形态 0 重建、切 `tid` 无键局部态彻底隔离与进程回收滚动位恢复。
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
    shellGlassState: HiGlassState? = null,
    onMiddlePaneWidthChanged: (Dp) -> Unit = {},
    forumContent: @Composable (ForumChromeSpec, PaddingValues, LazyListState) -> Unit,
    detailContent: @Composable (ThreadNavArgs, DetailChromeSpec, PaddingValues, LazyListState, () -> Unit) -> Unit,
    secondaryListContent: @Composable (ShellSidebarEntry, Boolean, (() -> Unit)?, PaddingValues, LazyListState) -> Unit = { _, _, _, _, _ -> },
    mineMenuContent: @Composable (PaddingValues) -> Unit = {},
    settingsDetailContent: @Composable (SettingsGroup, PaddingValues) -> Unit = { _, _ -> },
    userProfileContent: @Composable (DetailDrilldownEntry.UserProfile, Boolean, () -> Unit, PaddingValues) -> Unit = { _, _, _, _ -> },
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reducedMotion = HiTheme.isReducedMotion
    val saveableStateHolder = rememberSaveableStateHolder()
    val middleGlassState = rememberHiGlassState()

    val currentForumContent by rememberUpdatedState(forumContent)
    val currentDetailContent by rememberUpdatedState(detailContent)
    val currentSecondaryList by rememberUpdatedState(secondaryListContent)
    val currentMineMenu by rememberUpdatedState(mineMenuContent)
    val currentSettingsDetail by rememberUpdatedState(settingsDetailContent)
    val currentUserProfile by rememberUpdatedState(userProfileContent)
    val latestOnSplitChange by rememberUpdatedState(onPaneSplitChange)
    val latestActiveThreadTids by rememberUpdatedState(appState.activeThreadTids())

    val detailMovables = remember(hostViewModel, saveableStateHolder) {
        LinkedHashMap<String, @Composable (ThreadNavArgs, DetailChromeSpec, PaddingValues, () -> Unit) -> Unit>()
    }
    val secondaryMovables = remember(saveableStateHolder) {
        LinkedHashMap<String, @Composable (ShellSidebarEntry, Boolean, (() -> Unit)?, PaddingValues) -> Unit>()
    }

    LaunchedEffect(paneLayout.hasDetailPane, appState.hasDetailContent) {
        if (!paneLayout.hasDetailPane || !appState.hasDetailContent) hostViewModel.exitFullScreenReading()
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

    fun detailMovableFor(tid: String) = detailMovables.getOrPut(tid) {
        movableContentOf { args: ThreadNavArgs, chromeSpec: DetailChromeSpec, padding: PaddingValues, onBack: () -> Unit ->
            val factory = rememberThreadViewModelProviderFactory()
            val parentOwner = LocalViewModelStoreOwner.current
            val extras = remember(parentOwner) {
                (parentOwner as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras ?: CreationExtras.Empty
            }
            key(args.tid) {
                DisposableEffect(args.tid) {
                    hostViewModel.onThreadMounted(args.tid)
                    onDispose {
                        hostViewModel.onThreadDisposed(args.tid)
                        if (args.tid !in latestActiveThreadTids) {
                            saveableStateHolder.removeState(detailSaveableKey(args.tid))
                            detailMovables.remove(args.tid)
                        }
                    }
                }
                saveableStateHolder.SaveableStateProvider(key = detailSaveableKey(args.tid)) {
                    val scopedOwner = remember(hostViewModel, args.tid, factory, extras) {
                        hostViewModel.viewModelStoreOwnerForThread(args.tid, factory, extras)
                    }
                    val listState = rememberLazyListState()
                    CompositionLocalProvider(LocalViewModelStoreOwner provides scopedOwner) {
                        currentDetailContent(args, chromeSpec, padding, listState, onBack)
                    }
                }
            }
        }
    }

    val backAction = resolveMultiPaneBackAction(appState, paneLayout.layoutMode)
    BackHandler(enabled = backAction != MultiPaneBackAction.PassToSystem) {
        hostViewModel.handleBack(paneLayout.layoutMode)
    }

    val navBarsBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val detailPanePadding = remember(paneLayout.layoutMode, contentPadding, navBarsBottom) {
        if (paneLayout.layoutMode == ShellPaneLayoutMode.HingeTwoPane) PaddingValues(bottom = navBarsBottom) else contentPadding
    }
    val forumChromeSpec = remember(paneLayout.isSidebar, paneLayout.layoutMode, shellGlassState, middleGlassState) {
        when {
            paneLayout.layoutMode == ShellPaneLayoutMode.HingeTwoPane -> ForumChromeSpec(true, useGlassTopBar = false, externalGlassState = shellGlassState)
            paneLayout.isSidebar -> ForumChromeSpec(false, useGlassTopBar = true, externalGlassState = middleGlassState)
            else -> ForumChromeSpec(true, useGlassTopBar = true, externalGlassState = shellGlassState)
        }
    }
    val fullScreenToggle = remember(paneLayout.hasDetailPane, appState.isFullScreenReading, hostViewModel) {
        if (paneLayout.hasDetailPane) FullScreenToggle(appState.isFullScreenReading, hostViewModel::toggleFullScreenReading) else null
    }
    val rootDetailChrome = remember(paneLayout.hasDetailPane, fullScreenToggle) { DetailChromeSpec(!paneLayout.hasDetailPane, fullScreenToggle) }
    val drilldownDetailChrome = remember(fullScreenToggle) { DetailChromeSpec(true, fullScreenToggle) }

    val listPaneTitle = stringResource(R.string.shell_pane_list_title)
    val detailPaneTitle = stringResource(R.string.shell_pane_detail_title)
    val slideOffsetPx = with(density) { 24.dp.roundToPx() }
    val listGlassModifier = if (shellGlassState != null) Modifier.hiGlassSource(shellGlassState) else Modifier

    @Composable
    fun RenderRightSlot(slot: RightPaneSlot, padding: PaddingValues) {
        val emptyText = when (appState.selectedSidebarEntry) {
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
                is RightPaneSlot.Settings -> currentSettingsDetail(target.group, padding)
                is RightPaneSlot.MineSubList -> secondaryMovableFor(target.entry)(target.entry, false, null, padding)
                is RightPaneSlot.Drilldown -> when (val e = target.entry) {
                    is DetailDrilldownEntry.UserProfile -> currentUserProfile(e, true, hostViewModel::popDetailDrilldown, padding)
                    is DetailDrilldownEntry.LinkedThread -> detailMovableFor(e.args.tid)(e.args, drilldownDetailChrome, padding, hostViewModel::popDetailDrilldown)
                }
            }
        }
    }

    // 三栏（≥ 680dp）
    if (paneLayout.isThreePane) {
        val anchorsDp = remember(paneLayout, appState.hasDetailContent) { resolvePaneSplitAnchors(paneLayout, appState.hasDetailContent) }
        val availableSplits = remember(anchorsDp) { anchorsDp.keys.toList() }
        val effectiveSplit = remember(persistedPaneSplit, anchorsDp) { resolveEffectivePaneSplit(persistedPaneSplit, anchorsDp) }
        val draggableAnchors = remember(anchorsDp, density) {
            DraggableAnchors { anchorsDp.forEach { (split, wDp) -> split at with(density) { wDp.dp.toPx() } } }
        }
        val dragState = remember { AnchoredDraggableState(initialValue = effectiveSplit, anchors = draggableAnchors) }
        val currentAnchorsDp by rememberUpdatedState(anchorsDp)
        val currentEffectiveSplit by rememberUpdatedState(effectiveSplit)

        SideEffect {
            if (dragState.anchors != draggableAnchors) {
                val target = if (dragState.currentValue in anchorsDp) dragState.currentValue else effectiveSplit
                dragState.updateAnchors(draggableAnchors, newTarget = target)
            }
        }
        LaunchedEffect(persistedPaneSplit) {
            val target = resolveEffectivePaneSplit(persistedPaneSplit, currentAnchorsDp)
            if (dragState.settledValue != target && !dragState.isAnimationRunning) dragState.snapTo(target)
        }
        LaunchedEffect(dragState) {
            snapshotFlow { dragState.settledValue }.distinctUntilChanged().collect { settled ->
                if (settled != currentEffectiveSplit) latestOnSplitChange(settled)
            }
        }

        val defaultPx = with(density) { paneLayout.middlePaneWidthDp.dp.toPx() }
        val minPx = draggableAnchors.minPosition().let { if (it.isNaN()) defaultPx else it }
        val maxPx = draggableAnchors.maxPosition().let { if (it.isNaN()) defaultPx else it }
        val currentMiddlePx = if (appState.isFullScreenReading) 0f else dragState.offset.let { if (it.isNaN()) defaultPx else it }.coerceIn(minPx, maxPx)
        val currentMiddleDp = with(density) { currentMiddlePx.toDp() }
        SideEffect { onMiddlePaneWidthChanged(currentMiddleDp) }

        val rightSlot: RightPaneSlot = when {
            appState.detailDrilldownStack.isNotEmpty() -> RightPaneSlot.Drilldown(appState.detailDrilldownStack.last(), appState.detailDrilldownStack.size)
            appState.selectedThread != null -> RightPaneSlot.Thread(appState.selectedThread)
            appState.selectedSidebarEntry == ShellSidebarEntry.Settings -> RightPaneSlot.Settings(appState.selectedSettingsGroup)
            else -> RightPaneSlot.Empty
        }

        Box(modifier = modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (!appState.isFullScreenReading && currentMiddlePx > 0.5f) {
                    Box(Modifier.width(currentMiddleDp).fillMaxHeight().semantics { isTraversalGroup = true; paneTitle = listPaneTitle }) {
                        if (appState.selectedDestination == ShellDestination.Forum) {
                            forumMovable(appState.activeForumSource, forumChromeSpec, contentPadding)
                        } else {
                            secondaryMovableFor(appState.selectedSidebarEntry)(appState.selectedSidebarEntry, false, null, contentPadding)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().semantics { isTraversalGroup = true; paneTitle = detailPaneTitle }) {
                    RenderRightSlot(rightSlot, detailPanePadding)
                }
            }
            if (!appState.isFullScreenReading) {
                val contentPx = with(density) { paneLayout.contentWidthDp.dp.toPx() }
                val handleW = with(density) { PANE_DIVIDER_HIT_WIDTH.toPx() }
                val handleOffsetPx = (currentMiddlePx - handleW / 2f).coerceIn(0f, (contentPx - handleW).coerceAtLeast(0f)).roundToInt()
                val lineOffsetPx = (currentMiddlePx - handleOffsetPx).coerceIn(0f, (handleW - 1f).coerceAtLeast(0f)).roundToInt()
                PaneDividerHandle(
                    dragState = dragState,
                    availableSplits = availableSplits,
                    visualLineOffsetPx = lineOffsetPx,
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
        val roles = resolveHingeTwoPaneRoles(appState)
        val hingeGapDp = paneLayout.hingeBoundsDp?.let { (it.right - it.left).coerceAtLeast(0f).dp } ?: 0.dp
        SideEffect { onMiddlePaneWidthChanged(if (appState.isFullScreenReading) 0.dp else paneLayout.middlePaneWidthDp.dp) }

        val rightSlot: RightPaneSlot = when (roles.right) {
            HingeRightPaneRole.DetailDrilldown -> RightPaneSlot.Drilldown(appState.detailDrilldownStack.last(), appState.detailDrilldownStack.size)
            HingeRightPaneRole.ThreadDetail -> appState.selectedThread?.let(RightPaneSlot::Thread) ?: RightPaneSlot.Empty
            HingeRightPaneRole.MineSubList -> roles.mineSubEntry?.let(RightPaneSlot::MineSubList) ?: RightPaneSlot.Empty
            HingeRightPaneRole.SettingsDetail -> RightPaneSlot.Settings(appState.selectedSettingsGroup)
            HingeRightPaneRole.EmptyPlaceholder -> RightPaneSlot.Empty
        }

        Box(modifier = modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (!appState.isFullScreenReading) {
                    val nonForumGlass = if (roles.left == HingeLeftPaneRole.Forum) Modifier else listGlassModifier
                    Box(
                        modifier = Modifier
                            .width(paneLayout.middlePaneWidthDp.dp)
                            .fillMaxHeight()
                            .then(nonForumGlass)
                            .semantics { isTraversalGroup = true; paneTitle = listPaneTitle },
                    ) {
                        when (roles.left) {
                            HingeLeftPaneRole.Forum -> forumMovable(appState.activeForumSource, forumChromeSpec, contentPadding)
                            HingeLeftPaneRole.Messages -> secondaryMovableFor(appState.activeMessageEntry)(appState.activeMessageEntry, false, null, contentPadding)
                            HingeLeftPaneRole.Search -> secondaryMovableFor(ShellSidebarEntry.Search)(ShellSidebarEntry.Search, false, null, contentPadding)
                            HingeLeftPaneRole.MineMenu -> currentMineMenu(contentPadding)
                            HingeLeftPaneRole.PromotedMineSubList -> {
                                val promoted = roles.mineSubEntry ?: appState.selectedSidebarEntry
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
                    RenderRightSlot(rightSlot, detailPanePadding)
                }
            }
            if (!appState.isFullScreenReading && hingeGapDp == 0.dp) {
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
    SideEffect { onMiddlePaneWidthChanged(paneLayout.middlePaneWidthDp.dp) }
    val showDetail = appState.detailDrilldownStack.isNotEmpty() || appState.selectedThread != null
    val nonForumSingleGlass = if (!showDetail && appState.selectedDestination != ShellDestination.Forum) listGlassModifier else Modifier
    Box(modifier = modifier.fillMaxSize().then(nonForumSingleGlass)) {
        when {
            appState.detailDrilldownStack.isNotEmpty() -> when (val e = appState.detailDrilldownStack.last()) {
                is DetailDrilldownEntry.UserProfile -> currentUserProfile(e, true, hostViewModel::popDetailDrilldown, detailPanePadding)
                is DetailDrilldownEntry.LinkedThread -> detailMovableFor(e.args.tid)(e.args, drilldownDetailChrome, detailPanePadding, hostViewModel::popDetailDrilldown)
            }
            appState.selectedThread != null -> detailMovableFor(appState.selectedThread.tid)(appState.selectedThread, rootDetailChrome, detailPanePadding) {
                hostViewModel.handleBack(paneLayout.layoutMode)
            }
            appState.selectedDestination == ShellDestination.Forum -> forumMovable(appState.activeForumSource, forumChromeSpec, contentPadding)
            appState.selectedDestination == ShellDestination.Messages -> secondaryMovableFor(appState.activeMessageEntry)(appState.activeMessageEntry, false, null, contentPadding)
            appState.selectedDestination == ShellDestination.Search -> secondaryMovableFor(ShellSidebarEntry.Search)(ShellSidebarEntry.Search, false, null, contentPadding)
            appState.selectedDestination == ShellDestination.Mine -> {
                val sub = if (paneLayout.isSidebar) appState.selectedSidebarEntry else appState.activeMineSubPage
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
