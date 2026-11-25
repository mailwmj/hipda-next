package net.jejer.hipda.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.hiGlassSource
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut

/**
 * HiPDA Next 自适应主外壳：按实时窗口尺寸与竖直物理铰链分隔状态在底部悬浮胶囊、紧凑胶囊与左侧 80dp 导航轨之间切换。
 *
 * - 内容区作为 Haze 源铺满窗口（胶囊浮在内容上方不占布局高度；导航轨占左侧 80dp），
 *   通过 [content] 的第二个参数 `PaddingValues` 提供恒定的底部留白（按胶囊完全可见时计算，不随滚动收起变化）；
 * - 底部胶囊与紧凑胶囊通过 [barState] 接收当前选中目的地的滚动并经 `graphicsLayer` 跟手滑出/吸附，键盘可见时立即隐藏；
 * - 切换一级目的地时原地交叉淡入淡出（无位移），隔离退场页残余惯性滚动并恢复被滚动收起的胶囊。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HiAdaptiveShell(
    selected: ShellDestination,
    onSelect: (ShellDestination) -> Unit,
    modifier: Modifier = Modifier,
    barState: ShellBarState = rememberShellBarState(),
    content: @Composable (ShellDestination, PaddingValues) -> Unit,
) {
    val windowDpSize = LocalWindowInfo.current.containerDpSize
    val windowPosture = currentWindowAdaptiveInfoV2().windowPosture
    val widthDp = windowDpSize.width.value
    val heightDp = windowDpSize.height.value
    val hingeSeparating = windowPosture.hingeList.any { it.isVertical && it.isSeparating }
    val mode = shellNavigationMode(
        widthDp = widthDp,
        heightDp = heightDp,
        hingeSeparating = hingeSeparating,
    )
    val showUnselectedLabels = showLabelForUnselected(widthDp)
    val glassState = rememberHiGlassState()
    val density = LocalDensity.current
    val currentSelected by rememberUpdatedState(selected)

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
    val isRail = mode == ShellNavigationMode.Rail
    val maxHiddenOffsetPx = if (isRail) {
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

    val bottomPaddingDp = with(density) {
        if (isRail) navBarsBottomPx.toDp() else (effectivePillHeightPx + bottomGapPx + navBarsBottomPx).toDp()
    }
    val contentPadding = remember(bottomPaddingDp) { PaddingValues(bottom = bottomPaddingDp) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .testTag(HiShellTestTags.ContentContainer)
                .fillMaxSize()
                .padding(start = if (isRail) HiNavigationRailWidth else 0.dp)
                .hiGlassSource(glassState),
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
                        .then(if (isRail) Modifier else Modifier.nestedScroll(destinationScrollConnection)),
                ) {
                    content(destination, contentPadding)
                }
            }
        }

        if (isRail) {
            HiNavigationRail(
                selected = selected,
                onSelect = onSelect,
                glassState = glassState,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        } else {
            HiNavigationPill(
                selected = selected,
                onSelect = onSelect,
                glassState = glassState,
                compact = mode == ShellNavigationMode.CompactPill,
                showUnselectedLabels = showUnselectedLabels,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
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
