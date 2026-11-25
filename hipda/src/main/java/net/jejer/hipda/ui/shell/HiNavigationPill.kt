package net.jejer.hipda.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.glass.HiGlassState
import net.jejer.hipda.ui.designsystem.glass.HiGlassSurface
import net.jejer.hipda.ui.designsystem.motion.HiMotion

val HiNavigationPillMinHeight: Dp = 64.dp
val HiNavigationPillCompactMinHeight: Dp = 48.dp
val HiNavigationPillMaxWidth: Dp = 420.dp
val HiNavigationPillHorizontalMargin: Dp = 16.dp
val HiNavigationPillBottomGap: Dp = 8.dp

private val PillOuterPadding: Dp = 4.dp
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
 * 底部悬浮毛玻璃导航胶囊（标准 [BottomPill] 与紧凑 [CompactPill] 两种形态）。
 *
 * - 标准态最小高 64dp、最宽 420dp；窄窗（`< 360dp`）下仅选中项显示文字。
 * - 紧凑态最小高 48dp，选中项横向展示图标与文字，选中胶囊边缘到相邻图标的视觉间距与其余图标一致（24dp）。
 * - 选中指示器位置与宽度通过 [HiMotion.spatialFast] 连续驱动且可打断，形态切换时通过 `remember(compact)` 隔离状态。
 */
@Composable
fun HiNavigationPill(
    selected: ShellDestination,
    onSelect: (ShellDestination) -> Unit,
    glassState: HiGlassState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showUnselectedLabels: Boolean = true,
) {
    val pillModifier = modifier
        .testTag(if (compact) HiShellTestTags.CompactPill else HiShellTestTags.BottomPill)
        .widthIn(max = HiNavigationPillMaxWidth)
        .then(if (compact) Modifier else Modifier.fillMaxWidth())
        .heightIn(min = if (compact) HiNavigationPillCompactMinHeight else HiNavigationPillMinHeight)
    val arrangement = if (compact) ShellLayoutArrangement.CompactHorizontal else ShellLayoutArrangement.EqualHorizontal
    val itemStyle = if (compact) ShellNavItemStyle.CompactPill else ShellNavItemStyle.BottomPill

    HiCappedFontScale {
        HiGlassSurface(
            state = glassState,
            modifier = pillModifier,
            shape = HiShapeTokens.Full,
            floating = true,
        ) {
            ShellIndicatorLayout(
                selected = selected,
                arrangement = arrangement,
                animatorKey = compact,
                modifier = Modifier
                    .then(if (compact) Modifier else Modifier.fillMaxWidth())
                    .padding(horizontal = PillOuterPadding, vertical = if (compact) 0.dp else PillOuterPadding),
            ) {
                ShellDestination.entries.forEach { destination ->
                    val isSelected = destination == selected
                    ShellNavItem(
                        destination = destination,
                        selected = isSelected,
                        showLabel = if (compact) isSelected else (showUnselectedLabels || isSelected),
                        style = itemStyle,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

internal enum class ShellLayoutArrangement {
    EqualHorizontal,
    CompactHorizontal,
    VerticalRail,
}

internal enum class ShellNavItemStyle {
    BottomPill,
    CompactPill,
    Rail,
}

/**
 * 胶囊与导航轨共用的单遍指示器布局：每个子 `Measurable` 严格只 `measure` 一次，
 * 所有最小宽高约束均钳制至父级 `maxWidth`/`maxHeight`，极端窄窗与低高度下不崩溃。
 */
@Composable
internal fun ShellIndicatorLayout(
    selected: ShellDestination,
    arrangement: ShellLayoutArrangement,
    animatorKey: Any,
    modifier: Modifier = Modifier,
    itemsContent: @Composable () -> Unit,
) {
    val indicatorAnimator = rememberShellIndicatorAnimator(key = animatorKey)
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
        val indicatorMeasurable = measurables[0]
        val itemMeasurables = measurables.subList(1, measurables.size)
        val count = itemMeasurables.size
        val maxW = constraints.maxWidth.coerceAtLeast(0)
        val maxH = constraints.maxHeight.coerceAtLeast(0)
        val minTouchPx = MinTouchTarget.roundToPx()

        val offsetsX = IntArray(count)
        val offsetsY = IntArray(count)
        val itemPlaceables: List<androidx.compose.ui.layout.Placeable>
        val layoutWidth: Int
        val layoutHeight: Int
        val targetRect: Rect

        when (arrangement) {
            ShellLayoutArrangement.EqualHorizontal -> {
                val minItemH = StandardItemMinHeight.roundToPx().coerceAtMost(maxH)
                val widths = splitEvenly(maxW, count)
                var cursorX = 0
                itemPlaceables = itemMeasurables.mapIndexed { i, m ->
                    offsetsX[i] = cursorX
                    cursorX += widths[i]
                    m.measure(Constraints(widths[i], widths[i], minItemH, maxH))
                }
                layoutWidth = maxW
                layoutHeight = maxOf(minItemH, itemPlaceables.maxOf { it.height }).coerceAtMost(maxH)
                itemPlaceables.forEachIndexed { i, p -> offsetsY[i] = ((layoutHeight - p.height) / 2).coerceAtLeast(0) }
                val selX = offsetsX[selectedIndex].toFloat()
                targetRect = Rect(selX, 0f, selX + itemPlaceables[selectedIndex].width, layoutHeight.toFloat())
            }

            ShellLayoutArrangement.CompactHorizontal -> {
                val minItemH = minTouchPx.coerceAtMost(maxH)
                val naturalW = IntArray(count) { i -> maxOf(minTouchPx, itemMeasurables[i].maxIntrinsicWidth(maxH)) }
                val adjGap = CompactSelectedAdjacentGap.roundToPx()
                val gaps = IntArray((count - 1).coerceAtLeast(0)) { i ->
                    if (i == selectedIndex || i + 1 == selectedIndex) adjGap else 0
                }
                val finalW = fitAxis(naturalW, gaps, maxW, minTouchPx, selectedIndex)
                var cursorX = 0
                itemPlaceables = itemMeasurables.mapIndexed { i, m ->
                    val w = finalW[i].coerceAtLeast(0)
                    offsetsX[i] = cursorX
                    cursorX += w + if (i < gaps.size) gaps[i] else 0
                    m.measure(Constraints(w, w, minItemH, maxH))
                }
                layoutWidth = cursorX.coerceAtMost(maxW)
                layoutHeight = maxOf(minItemH, itemPlaceables.maxOf { it.height }).coerceAtMost(maxH)
                itemPlaceables.forEachIndexed { i, p -> offsetsY[i] = ((layoutHeight - p.height) / 2).coerceAtLeast(0) }
                val insetV = PillOuterPadding.toPx().coerceAtMost(layoutHeight / 4f)
                val selX = offsetsX[selectedIndex].toFloat()
                targetRect = Rect(selX, insetV, selX + itemPlaceables[selectedIndex].width, (layoutHeight - insetV).coerceAtLeast(insetV))
            }

            ShellLayoutArrangement.VerticalRail -> {
                val minItemH = RailItemMinHeight.roundToPx()
                val naturalH = IntArray(count) { i -> maxOf(minItemH, itemMeasurables[i].maxIntrinsicHeight(maxW)) }
                val extras = intArrayOf(RailTopPadding.roundToPx(), RailBottomPadding.roundToPx()) +
                    IntArray((count - 1).coerceAtLeast(0)) { RailItemSpacing.roundToPx() }
                val finalH = fitAxis(naturalH, extras, maxH, minTouchPx = 0, selectedIndex = -1)
                var cursorY = extras[0]
                itemPlaceables = itemMeasurables.mapIndexed { i, m ->
                    val h = finalH[i].coerceIn(0, maxH)
                    offsetsY[i] = cursorY
                    cursorY += h + if (i < count - 1) extras[i + 2] else 0
                    m.measure(Constraints(maxW, maxW, h, h))
                }
                layoutWidth = maxW
                layoutHeight = (cursorY + extras[1]).coerceAtMost(maxH)
                val selY = offsetsY[selectedIndex].toFloat()
                val selH = itemPlaceables[selectedIndex].height.toFloat()
                val indW = RailIndicatorWidth.toPx().coerceAtMost(maxW.toFloat())
                val indH = RailIndicatorHeight.toPx().coerceAtMost(selH)
                val indLeft = (maxW - indW) / 2f
                val indTop = selY + RailItemTopPadding.toPx().coerceAtMost(((selH - indH) / 2f).coerceAtLeast(0f))
                targetRect = Rect(indLeft, indTop, indLeft + indW, indTop + indH)
            }
        }

        val animatedRect = indicatorAnimator.resolveRect(selectedKey = selected, target = targetRect)
        val indicatorPlaceable = indicatorMeasurable.measure(
            Constraints.fixed(
                width = animatedRect.width.roundToInt().coerceIn(0, layoutWidth),
                height = animatedRect.height.roundToInt().coerceIn(0, layoutHeight),
            ),
        )

        layout(layoutWidth, layoutHeight) {
            indicatorPlaceable.placeRelative(
                x = animatedRect.left.roundToInt(),
                y = animatedRect.top.roundToInt(),
            )
            itemPlaceables.forEachIndexed { i, placeable ->
                placeable.placeRelative(x = offsetsX[i], y = offsetsY[i])
            }
        }
    }
}

@Composable
internal fun ShellNavItem(
    destination: ShellDestination,
    selected: Boolean,
    showLabel: Boolean,
    style: ShellNavItemStyle,
    onSelect: (ShellDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val compositionObserver = LocalShellItemCompositionObserver.current
    if (compositionObserver != null) {
        SideEffect { compositionObserver(destination) }
    }

    val label = stringResource(destination.labelRes)
    val iconColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val labelColor = when {
        !selected -> MaterialTheme.colorScheme.onSurfaceVariant
        style == ShellNavItemStyle.Rail -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val icon = if (selected) destination.selectedIcon else destination.unselectedIcon
    val iconContentDescription = if (showLabel) null else label
    val onClick = remember(destination, onSelect) { { onSelect(destination) } }

    val itemContent: @Composable () -> Unit = {
        val iconNode: @Composable () -> Unit = {
            Icon(
                imageVector = icon,
                contentDescription = iconContentDescription,
                tint = iconColor,
                modifier = Modifier.size(24.dp),
            )
        }
        if (style == ShellNavItemStyle.Rail) {
            Box(
                modifier = Modifier.size(width = RailIndicatorWidth, height = RailIndicatorHeight),
                contentAlignment = Alignment.Center,
            ) {
                iconNode()
            }
        } else {
            iconNode()
        }
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

    Box(
        modifier = modifier
            .testTag(HiShellTestTags.navItem(destination))
            .clip(HiShapeTokens.Full)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            ),
        contentAlignment = if (style == ShellNavItemStyle.Rail) Alignment.TopCenter else Alignment.Center,
    ) {
        if (style == ShellNavItemStyle.CompactPill) {
            Row(
                modifier = Modifier.padding(horizontal = if (showLabel) 16.dp else 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemContent()
            }
        } else {
            Column(
                modifier = Modifier.padding(
                    horizontal = 4.dp,
                    vertical = if (style == ShellNavItemStyle.Rail) RailItemTopPadding else 6.dp,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (style == ShellNavItemStyle.Rail) 4.dp else 2.dp),
            ) {
                itemContent()
            }
        }
    }
}

@Stable
internal class ShellIndicatorAnimator(
    private val scope: CoroutineScope,
) {
    private var animatable: Animatable<Rect, AnimationVector4D>? = null
    private var lastTarget: Rect? = null
    private var lastSelectedKey: Any? = null
    private var pendingSnap: Rect? = null

    fun resolveRect(selectedKey: Any, target: Rect): Rect {
        val current = animatable
        if (current == null) {
            animatable = Animatable(target, Rect.VectorConverter, Rect.VisibilityThreshold)
            lastTarget = target
            lastSelectedKey = selectedKey
            return target
        }
        if (target != lastTarget) {
            val selectionChanged = selectedKey != lastSelectedKey
            lastTarget = target
            lastSelectedKey = selectedKey
            if (selectionChanged || current.isRunning) {
                pendingSnap = null
                scope.launch {
                    current.animateTo(
                        targetValue = target,
                        animationSpec = HiMotion.spatialFast(),
                    )
                }
            } else {
                pendingSnap = target
                scope.launch {
                    current.snapTo(target)
                    if (pendingSnap == target) pendingSnap = null
                }
            }
        }
        return pendingSnap ?: current.value
    }
}

@Composable
internal fun rememberShellIndicatorAnimator(key: Any = Unit): ShellIndicatorAnimator {
    val scope = rememberCoroutineScope()
    return remember(key, scope) { ShellIndicatorAnimator(scope) }
}

private fun splitEvenly(total: Int, count: Int): IntArray {
    if (count <= 0) return IntArray(0)
    val base = total / count
    val rem = total - base * count
    return IntArray(count) { i -> base + if (i == count - 1) rem else 0 }
}

/** 单遍主轴尺寸分配：优先满足自然尺寸与间距，空间不足时先压缩间距，再按最小触摸目标或等分兜底。 */
private fun fitAxis(
    natural: IntArray,
    spacings: IntArray,
    maxTotal: Int,
    minTouchPx: Int,
    selectedIndex: Int,
): IntArray {
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
