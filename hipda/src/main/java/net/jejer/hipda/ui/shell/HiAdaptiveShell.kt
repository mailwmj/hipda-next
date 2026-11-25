package net.jejer.hipda.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.hiGlassSource
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut

/** 内容槽位可读取的当前自适应分栏布局上下文（供 `PreviewActivity` 与 `E1`/`E3`/`E8–E12` 消费）。 */
@Immutable
interface ShellContentScope {
    val paneLayout: ShellPaneLayout
}

@Immutable
private data class ShellContentScopeImpl(
    override val paneLayout: ShellPaneLayout,
) : ShellContentScope

/**
 * HiPDA Next 自适应主外壳：按实时窗口尺寸与竖直物理铰链分隔状态，在底部悬浮毛玻璃胶囊、
 * 紧凑胶囊、600–839dp 64dp 图标轨（含 240dp 模态浮层）与 ≥ 840dp 可伸缩常驻左侧栏之间切换。
 *
 * - 胶囊态下内容区挂载 `hiGlassSource` 与 `nestedScroll`，提供恒定的底部留白；
 * - 侧栏态（≥ 600dp）外壳自身毛玻璃数为 0，内容区按 `calculateShellPaneLayout` 与 `HiMotion.spatialSlow()`
 *   平滑让位，底部仅留 `navigationBars` inset；
 * - 竖向铰链退单栏时，紧凑胶囊水平对齐到铰链外较宽一侧以避开物理折痕。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HiAdaptiveShell(
    selected: ShellDestination,
    onSelect: (ShellDestination) -> Unit,
    modifier: Modifier = Modifier,
    barState: ShellBarState = rememberShellBarState(),
    sidebarState: HiSidebarState? = null,
    onSelectSidebarEntry: ((ShellSidebarEntry) -> Unit)? = null,
    onToggleSidebarCollapsed: ((Boolean) -> Unit)? = null,
    onAccountClick: () -> Unit = {},
    onManageForumsClick: () -> Unit = {},
    hasDetailContent: Boolean = false,
    content: @Composable ShellContentScope.(ShellDestination, PaddingValues) -> Unit,
) {
    val windowDpSize = LocalWindowInfo.current.containerDpSize
    val windowPosture = currentWindowAdaptiveInfoV2().windowPosture
    val density = LocalDensity.current
    val widthDp = windowDpSize.width.value
    val heightDp = windowDpSize.height.value
    val verticalSeparatingHinge = windowPosture.hingeList.firstOrNull { it.isVertical && it.isSeparating }
    val hingeBoundsDp = verticalSeparatingHinge?.bounds?.let { bounds ->
        with(density) {
            ShellHingeBoundsDp(
                left = bounds.left.toDp().value,
                right = bounds.right.toDp().value,
            )
        }
    }

    val isLandscape = widthDp >= heightDp
    var internalCollapsedPortrait by rememberSaveable { mutableStateOf(false) }
    var internalCollapsedLandscape by rememberSaveable { mutableStateOf(false) }
    val controlledCollapsedPref = sidebarState?.isCollapsedPref
    val effectiveCollapsedPref = controlledCollapsedPref ?: if (isLandscape) {
        internalCollapsedLandscape
    } else {
        internalCollapsedPortrait
    }

    val paneLayout = calculateShellPaneLayout(
        widthDp = widthDp,
        heightDp = heightDp,
        hingeBoundsDp = hingeBoundsDp,
        leftCollapsedPref = effectiveCollapsedPref,
        hasDetailContent = hasDetailContent,
    )
    val mode = paneLayout.navigationMode
    val isSidebar = paneLayout.isSidebar
    val isFullScreen = sidebarState?.isFullScreen == true
    val showUnselectedLabels = showLabelForUnselected(widthDp)
    val glassState = rememberHiGlassState()
    val currentSelected by rememberUpdatedState(selected)

    var isModalDrawerOpen by rememberSaveable { mutableStateOf(false) }
    var mineGroupOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val mineGroupExpanded = mineGroupOverride ?: (heightDp >= SHELL_MINE_GROUP_AUTO_EXPAND_HEIGHT_DP)

    LaunchedEffect(isSidebar, paneLayout.canToggleSidebarPermanent, isFullScreen) {
        if (!isSidebar || paneLayout.canToggleSidebarPermanent || isFullScreen) {
            isModalDrawerOpen = false
        }
    }

    val resolvedSidebarState = sidebarState ?: remember(selected) {
        HiSidebarState(selectedEntry = mapDestinationToSidebarEntry(selected))
    }
    val handleSelectSidebarEntry: (ShellSidebarEntry) -> Unit = { entry ->
        if (onSelectSidebarEntry != null) {
            onSelectSidebarEntry(entry)
        } else {
            onSelect(entry.toShellDestination())
        }
    }
    val handlePermanentCollapseToggle: (Boolean) -> Unit = { nextCollapsed ->
        if (onToggleSidebarCollapsed != null) {
            onToggleSidebarCollapsed(nextCollapsed)
        } else if (isLandscape) {
            internalCollapsedLandscape = nextCollapsed
        } else {
            internalCollapsedPortrait = nextCollapsed
        }
    }
    val handleSidebarToggleClick: () -> Unit = {
        if (paneLayout.canToggleSidebarPermanent) {
            handlePermanentCollapseToggle(!effectiveCollapsedPref)
        } else {
            isModalDrawerOpen = !isModalDrawerOpen
        }
    }
    val handleCollapsedMineClick: () -> Unit = {
        mineGroupOverride = true
        if (paneLayout.canToggleSidebarPermanent) {
            handlePermanentCollapseToggle(false)
        } else {
            isModalDrawerOpen = true
        }
    }

    var measuredPillHeightPx by remember(mode) { mutableIntStateOf(0) }
    val navBarsBottomPx = WindowInsets.navigationBars.getBottom(density)
    val bottomGapPx = with(density) { HiNavigationPillBottomGap.roundToPx() }
    val shadowClearancePx = with(density) { HiGlassTokens.FloatingShadowElevation.roundToPx() }
    val minPillHeightPx = with(density) {
        val minHeight = if (mode == ShellNavigationMode.CompactPill) {
            HiNavigationPillCompactMinHeight
        } else {
            HiNavigationPillMinHeight
        }
        minHeight.roundToPx()
    }
    val effectivePillHeightPx = if (measuredPillHeightPx > 0) measuredPillHeightPx else minPillHeightPx
    val maxHiddenOffsetPx = if (isSidebar) {
        0f
    } else {
        (effectivePillHeightPx + bottomGapPx + navBarsBottomPx + shadowClearancePx).toFloat()
    }
    val imeVisible = WindowInsets.isImeVisible

    SideEffect {
        barState.syncLayout(newMaxHiddenOffsetPx = maxHiddenOffsetPx, imeVisible = imeVisible)
    }
    LaunchedEffect(selected) {
        barState.show()
    }

    val targetSidebarWidthDp = if (isSidebar && !isFullScreen) paneLayout.sidebarWidthDp.dp else 0.dp
    val animatedSidebarWidthDp by animateDpAsState(
        targetValue = targetSidebarWidthDp,
        animationSpec = if (targetSidebarWidthDp == 0.dp) {
            HiMotion.exit(HiMotion.spatialSlow())
        } else {
            HiMotion.spatialSlow()
        },
        label = "HiAdaptiveShellSidebarWidth",
    )
    val effectiveSidebarWidthDp = animatedSidebarWidthDp.coerceAtLeast(0.dp)

    val bottomPaddingDp = with(density) {
        if (isSidebar) navBarsBottomPx.toDp() else (effectivePillHeightPx + bottomGapPx + navBarsBottomPx).toDp()
    }
    val contentPadding = remember(bottomPaddingDp) { PaddingValues(bottom = bottomPaddingDp) }
    val contentScope = remember(paneLayout) { ShellContentScopeImpl(paneLayout) }
    val contentPaneTitle = stringResource(R.string.shell_pane_content_title)

    val underlyingInteractionGuard = Modifier
        .focusProperties {
            canFocus = !isModalDrawerOpen
            onEnter = { if (isModalDrawerOpen) cancelFocusChange() }
        }
        .then(
            if (isModalDrawerOpen) {
                Modifier.clearAndSetSemantics { hideFromAccessibility() }
            } else {
                Modifier
            },
        )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(underlyingInteractionGuard),
        ) {
            Box(
                modifier = Modifier
                    .testTag(HiShellTestTags.ContentContainer)
                    .fillMaxSize()
                    .padding(start = effectiveSidebarWidthDp)
                    .then(if (isSidebar) Modifier else Modifier.hiGlassSource(glassState))
                    .semantics {
                        isTraversalGroup = true
                        paneTitle = contentPaneTitle
                    },
            ) {
                AnimatedContent(
                    targetState = selected,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        (
                            hiFadeIn(animationSpec = HiMotion.effectsDefault()) togetherWith
                                hiFadeOut(animationSpec = HiMotion.exit(HiMotion.effectsDefault()))
                            ).using(null)
                    },
                    label = "HiAdaptiveShellContent",
                ) { destination ->
                    val destinationScrollConnection = remember(barState, destination) {
                        gatedNestedScrollConnection(barState.nestedScrollConnection) {
                            currentSelected == destination
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (isSidebar) Modifier else Modifier.nestedScroll(destinationScrollConnection)),
                    ) {
                        contentScope.content(destination, contentPadding)
                    }
                }
            }

            if (isSidebar) {
                if (effectiveSidebarWidthDp > 0.dp) {
                    HiNavigationSidebar(
                        state = resolvedSidebarState,
                        expanded = paneLayout.isSidebarExpanded,
                        mineGroupExpanded = mineGroupExpanded,
                        onToggleSidebar = handleSidebarToggleClick,
                        onToggleMineGroup = { mineGroupOverride = !mineGroupExpanded },
                        onCollapsedMineClick = handleCollapsedMineClick,
                        onSelectEntry = handleSelectSidebarEntry,
                        onAccountClick = onAccountClick,
                        onManageForumsClick = onManageForumsClick,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(effectiveSidebarWidthDp),
                    )
                }
            } else {
                val anchor = paneLayout.pillAnchor
                val anchorModifier = if (
                    anchor != null &&
                    (anchor.startDp > 0 || anchor.widthDp < paneLayout.windowWidthDp)
                ) {
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = anchor.startDp.dp)
                        .width(anchor.widthDp.dp)
                } else {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                }
                Box(
                    modifier = anchorModifier,
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    HiNavigationPill(
                        selected = selected,
                        onSelect = onSelect,
                        glassState = glassState,
                        compact = mode == ShellNavigationMode.CompactPill,
                        showUnselectedLabels = showUnselectedLabels,
                        modifier = Modifier
                            .graphicsLayer {
                                translationY = if (imeVisible) maxHiddenOffsetPx else barState.hiddenOffsetPx
                            }
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(
                                start = HiNavigationPillHorizontalMargin,
                                end = HiNavigationPillHorizontalMargin,
                                bottom = HiNavigationPillBottomGap,
                            )
                            .onSizeChanged {
                                if (it.height != effectivePillHeightPx) measuredPillHeightPx = it.height
                            },
                    )
                }
            }
        }

        if (isSidebar && !paneLayout.canToggleSidebarPermanent && !isFullScreen) {
            HiNavigationSidebarModalOverlay(
                visible = isModalDrawerOpen,
                state = resolvedSidebarState,
                mineGroupExpanded = mineGroupExpanded,
                onDismiss = { isModalDrawerOpen = false },
                onToggleMineGroup = { mineGroupOverride = !mineGroupExpanded },
                onSelectEntry = handleSelectSidebarEntry,
                onAccountClick = onAccountClick,
                onManageForumsClick = onManageForumsClick,
            )
        }
    }
}

private fun gatedNestedScrollConnection(
    delegate: NestedScrollConnection,
    isActive: () -> Boolean,
): NestedScrollConnection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (isActive()) delegate.onPreScroll(available, source) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (isActive()) delegate.onPostScroll(consumed, available, source) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (isActive()) delegate.onPostFling(consumed, available) else Velocity.Zero
}
