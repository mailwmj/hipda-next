package net.jejer.hipda.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.HiMotion

internal val PillOuterPadding: Dp = 4.dp
private val CompactSelectedAdjacentGap: Dp = 12.dp
private val MinTouchTarget: Dp = 48.dp
private val StandardItemMinHeight: Dp = 56.dp
private val RailTopPadding: Dp = 20.dp
private val RailBottomPadding: Dp = 16.dp
private val RailItemSpacing: Dp = 12.dp
private val RailItemMinHeight: Dp = 56.dp
private val RailIndicatorWidth: Dp = 56.dp
private val RailIndicatorHeight: Dp = 32.dp
private val RailItemTopPadding: Dp = 4.dp

/**
 * 胶囊与导航轨共用的单遍指示器布局：每个子 `Measurable` 严格只 `measure` 一次，
 * 所有最小宽高约束均钳制至父级上限，极端窄窗与低高度下不崩溃。
 */
@Composable
internal fun ShellIndicatorLayout(
    selected: ShellDestination,
    mode: ShellNavigationMode,
    modifier: Modifier = Modifier,
    animatorKey: Any = mode,
    itemsContent: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val indicatorAnimator = remember(animatorKey, scope) { ShellIndicatorAnimator(scope) }
    val reducedMotion = HiTheme.isReducedMotion
    val selectedIndex = ShellDestination.entries.indexOf(selected)

    Layout(
        content = {
            Box(
                modifier = Modifier
                    .testTag(HiShellTestTags.SelectionIndicator)
                    .clip(HiShapeTokens.Full)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            )
            itemsContent()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val itemMeasurables = measurables.subList(1, measurables.size)
        val count = itemMeasurables.size
        val maxW = constraints.maxWidth.coerceAtLeast(0)
        val maxH = constraints.maxHeight.coerceAtLeast(0)
        val minTouchPx = MinTouchTarget.roundToPx()
        val isVertical = mode == ShellNavigationMode.Rail
        val gapCount = (count - 1).coerceAtLeast(0)

        var startPad = 0
        var endPad = 0
        val gaps: IntArray
        val mainSizes: IntArray
        val minCrossPx: Int
        when (mode) {
            ShellNavigationMode.BottomPill -> {
                gaps = IntArray(gapCount)
                mainSizes = splitEvenly(maxW, count)
                minCrossPx = StandardItemMinHeight.roundToPx().coerceAtMost(maxH)
            }
            ShellNavigationMode.CompactPill -> {
                val adjGap = CompactSelectedAdjacentGap.roundToPx()
                gaps = IntArray(gapCount) { i -> if (i == selectedIndex || i + 1 == selectedIndex) adjGap else 0 }
                val natural = IntArray(count) { i -> maxOf(minTouchPx, itemMeasurables[i].maxIntrinsicWidth(maxH)) }
                mainSizes = fitAxis(natural, gaps, maxW, minTouchPx, selectedIndex)
                minCrossPx = minTouchPx.coerceAtMost(maxH)
            }
            ShellNavigationMode.Rail -> {
                val extras = intArrayOf(RailTopPadding.roundToPx(), RailBottomPadding.roundToPx()) +
                    IntArray(gapCount) { RailItemSpacing.roundToPx() }
                val minH = RailItemMinHeight.roundToPx()
                val natural = IntArray(count) { i -> maxOf(minH, itemMeasurables[i].maxIntrinsicHeight(maxW)) }
                mainSizes = fitAxis(natural, extras, maxH, minTouchPx = 0, selectedIndex = -1)
                startPad = extras[0]
                endPad = extras[1]
                gaps = IntArray(gapCount) { i -> extras[i + 2] }
                minCrossPx = maxW
            }
        }

        val maxMain = if (isVertical) maxH else maxW
        val mainOffsets = IntArray(count)
        var cursor = startPad
        val itemPlaceables = itemMeasurables.mapIndexed { i, m ->
            val main = mainSizes[i].coerceIn(0, maxMain)
            mainOffsets[i] = cursor
            cursor += main + if (i < gapCount) gaps[i] else 0
            val c = if (isVertical) Constraints(maxW, maxW, main, main) else Constraints(main, main, minCrossPx, maxH)
            m.measure(c)
        }

        val layoutWidth = if (mode == ShellNavigationMode.CompactPill) cursor.coerceAtMost(maxW) else maxW
        val layoutHeight = if (isVertical) {
            (cursor + endPad).coerceAtMost(maxH)
        } else {
            maxOf(minCrossPx, itemPlaceables.maxOf { it.height }).coerceAtMost(maxH)
        }
        val selMain = mainOffsets[selectedIndex].toFloat()
        val selPlaceable = itemPlaceables[selectedIndex]
        val targetRect = when (mode) {
            ShellNavigationMode.BottomPill -> Rect(selMain, 0f, selMain + selPlaceable.width, layoutHeight.toFloat())
            ShellNavigationMode.CompactPill -> {
                val insetV = PillOuterPadding.toPx().coerceAtMost(layoutHeight / 4f)
                Rect(selMain, insetV, selMain + selPlaceable.width, (layoutHeight - insetV).coerceAtLeast(insetV))
            }
            ShellNavigationMode.Rail -> {
                val selH = selPlaceable.height.toFloat()
                val indW = RailIndicatorWidth.toPx().coerceAtMost(maxW.toFloat())
                val indH = RailIndicatorHeight.toPx().coerceAtMost(selH)
                val indLeft = (maxW - indW) / 2f
                val indTop = selMain + RailItemTopPadding.toPx().coerceAtMost(((selH - indH) / 2f).coerceAtLeast(0f))
                Rect(indLeft, indTop, indLeft + indW, indTop + indH)
            }
        }

        val animatedRect = indicatorAnimator.resolveRect(selected, targetRect, reducedMotion)
        val indicatorPlaceable = measurables[0].measure(
            Constraints.fixed(
                width = animatedRect.width.roundToInt().coerceIn(0, layoutWidth),
                height = animatedRect.height.roundToInt().coerceIn(0, layoutHeight),
            ),
        )
        layout(layoutWidth, layoutHeight) {
            indicatorPlaceable.placeRelative(animatedRect.left.roundToInt(), animatedRect.top.roundToInt())
            itemPlaceables.forEachIndexed { i, p ->
                val cross = if (isVertical) 0 else ((layoutHeight - p.height) / 2).coerceAtLeast(0)
                p.placeRelative(x = if (isVertical) cross else mainOffsets[i], y = if (isVertical) mainOffsets[i] else cross)
            }
        }
    }
}

@Composable
internal fun ShellNavItem(
    destination: ShellDestination,
    selected: Boolean,
    showLabel: Boolean,
    mode: ShellNavigationMode,
    onSelect: (ShellDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    LocalShellItemCompositionObserver.current?.let { observer -> SideEffect { observer(destination) } }

    val cs = MaterialTheme.colorScheme
    val label = stringResource(destination.labelRes)
    val isRail = mode == ShellNavigationMode.Rail
    val iconColor = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant
    val labelColor = when {
        !selected -> cs.onSurfaceVariant
        isRail -> cs.onSurface
        else -> cs.onSecondaryContainer
    }
    val onClick = remember(destination, onSelect) { { onSelect(destination) } }
    val content: @Composable () -> Unit = {
        val iconModifier = if (isRail) {
            Modifier.size(RailIndicatorWidth, RailIndicatorHeight).wrapContentSize(Alignment.Center).size(24.dp)
        } else {
            Modifier.size(24.dp)
        }
        Icon(
            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
            contentDescription = if (showLabel) null else label,
            tint = iconColor,
            modifier = iconModifier,
        )
        if (showLabel) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current

    Box(
        modifier = modifier
            .hiPressScale(interactionSource)
            .testTag(HiShellTestTags.navItem(destination))
            .clip(HiShapeTokens.Full)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = indication,
                onClick = onClick,
                role = Role.Tab,
            ),
        contentAlignment = if (isRail) Alignment.TopCenter else Alignment.Center,
    ) {
        if (mode == ShellNavigationMode.CompactPill) {
            Row(
                modifier = Modifier.padding(horizontal = if (showLabel) 16.dp else 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { content() }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = if (isRail) RailItemTopPadding else 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (isRail) 4.dp else 2.dp),
            ) { content() }
        }
    }
}

@Stable
internal class ShellIndicatorAnimator(private val scope: CoroutineScope) {
    private var animatable: Animatable<Rect, AnimationVector4D>? = null
    private var lastTarget: Rect? = null
    private var lastSelectedKey: Any? = null
    private var pendingSnap: Rect? = null

    fun resolveRect(selectedKey: Any, target: Rect, reducedMotion: Boolean = false): Rect {
        val current = animatable ?: Animatable(target, Rect.VectorConverter, Rect.VisibilityThreshold).also {
            animatable = it
            lastTarget = target
            lastSelectedKey = selectedKey
            return target
        }
        if (target != lastTarget) {
            val selectionChanged = selectedKey != lastSelectedKey
            lastTarget = target
            lastSelectedKey = selectedKey
            if ((selectionChanged || current.isRunning) && !reducedMotion) {
                pendingSnap = null
                scope.launch { current.animateTo(targetValue = target, animationSpec = HiMotion.spatialFast()) }
            } else {
                snapTo(current, target)
            }
        } else if (reducedMotion && current.isRunning) {
            snapTo(current, target)
        }
        return pendingSnap ?: current.value
    }

    private fun snapTo(current: Animatable<Rect, AnimationVector4D>, target: Rect) {
        pendingSnap = target
        scope.launch {
            current.snapTo(target)
            if (pendingSnap == target) pendingSnap = null
        }
    }
}

private fun splitEvenly(total: Int, count: Int): IntArray {
    if (count <= 0) return IntArray(0)
    val base = total / count
    val rem = total - base * count
    return IntArray(count) { i -> base + if (i == count - 1) rem else 0 }
}

/** 单遍主轴尺寸分配：优先满足自然尺寸与间距，空间不足时先压缩间距，再按最小触摸目标或等分兜底。 */
private fun fitAxis(natural: IntArray, spacings: IntArray, maxTotal: Int, minTouchPx: Int, selectedIndex: Int): IntArray {
    val count = natural.size
    val sumNatural = natural.sum()
    val sumSpacings = spacings.sum()
    return when {
        sumNatural + sumSpacings <= maxTotal -> natural.copyOf()
        sumNatural <= maxTotal -> {
            val scale = (maxTotal - sumNatural).toFloat() / sumSpacings.coerceAtLeast(1)
            for (i in spacings.indices) spacings[i] = (spacings[i] * scale).roundToInt()
            natural.copyOf()
        }
        minTouchPx > 0 && selectedIndex >= 0 && maxTotal >= count * minTouchPx -> {
            spacings.fill(0)
            IntArray(count) { i -> if (i == selectedIndex) maxTotal - (count - 1) * minTouchPx else minTouchPx }
        }
        else -> {
            spacings.fill(0)
            splitEvenly(maxTotal, count)
        }
    }
}
