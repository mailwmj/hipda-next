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
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
private val CompactItemSpacing: Dp = 4.dp
private val MinTouchTarget: Dp = 48.dp
private val StandardItemMinHeight: Dp = 56.dp

/**
 * 底部悬浮毛玻璃导航胶囊（支持标准 [BottomPill] 与紧凑 [CompactPill] 两种形态）。
 *
 * - 标准态最小高 64dp、最宽 420dp；窄窗（`< 360dp`）下仅选中项显示文字。
 * - 紧凑态最小高 48dp，图标横排，仅选中项在图标旁显示文字。
 * - 选中指示器位置与宽度通过 [HiMotion.spatialFast] 连续驱动、可随时打断。
 * - 内部文字经 [HiCappedFontScale] 限制在 1.25 倍以内，字号放大时胶囊随内容长高不裁切。
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
    val indicatorAnimator = rememberShellIndicatorAnimator()
    val pillModifier = modifier
        .testTag(if (compact) HiShellTestTags.CompactPill else HiShellTestTags.BottomPill)
        .widthIn(max = HiNavigationPillMaxWidth)
        .then(if (compact) Modifier else Modifier.fillMaxWidth())
        .heightIn(min = if (compact) HiNavigationPillCompactMinHeight else HiNavigationPillMinHeight)

    HiCappedFontScale {
        HiGlassSurface(
            state = glassState,
            modifier = pillModifier,
            shape = HiShapeTokens.Full,
            floating = true,
        ) {
            val destinations = ShellDestination.entries
            val selectedIndex = destinations.indexOf(selected)
            val horizontalPadding = PillOuterPadding
            val verticalPadding = if (compact) 0.dp else PillOuterPadding
            Layout(
                content = {
                    ShellSelectionIndicator()
                    destinations.forEach { destination ->
                        val isSelected = destination == selected
                        ShellNavItem(
                            destination = destination,
                            selected = isSelected,
                            showLabel = if (compact) isSelected else (showUnselectedLabels || isSelected),
                            horizontalLayout = compact,
                            onSelect = onSelect,
                        )
                    }
                },
                modifier = Modifier
                    .then(if (compact) Modifier else Modifier.fillMaxWidth())
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            ) { measurables, constraints ->
                val indicatorMeasurable = measurables[0]
                val itemMeasurables = measurables.subList(1, measurables.size)
                val itemCount = itemMeasurables.size
                val minTouchPx = MinTouchTarget.roundToPx()

                val itemPlaceables: List<androidx.compose.ui.layout.Placeable>
                val itemOffsetsX: IntArray
                val layoutWidth: Int
                val layoutHeight: Int
                val indicatorInsetVerticalPx: Float

                if (!compact) {
                    val minItemHeightPx = StandardItemMinHeight.roundToPx()
                    val totalWidth = constraints.maxWidth
                    val baseWidth = totalWidth / itemCount
                    val remainder = totalWidth - baseWidth * itemCount
                    itemOffsetsX = IntArray(itemCount)
                    var cursorX = 0
                    itemPlaceables = itemMeasurables.mapIndexed { index, measurable ->
                        val width = baseWidth + if (index == itemCount - 1) remainder else 0
                        itemOffsetsX[index] = cursorX
                        cursorX += width
                        measurable.measure(
                            Constraints(
                                minWidth = width,
                                maxWidth = width,
                                minHeight = minItemHeightPx,
                                maxHeight = constraints.maxHeight,
                            ),
                        )
                    }
                    layoutWidth = totalWidth
                    layoutHeight = maxOf(minItemHeightPx, itemPlaceables.maxOf { it.height })
                    indicatorInsetVerticalPx = 0f
                } else {
                    val gapPx = CompactItemSpacing.roundToPx()
                    val totalGaps = gapPx * (itemCount - 1)
                    val availableForItems = (constraints.maxWidth - totalGaps).coerceAtLeast(0)
                    val intrinsicItemConstraints = Constraints(
                        minWidth = minTouchPx.coerceAtMost(availableForItems / itemCount),
                        maxWidth = availableForItems,
                        minHeight = minTouchPx,
                        maxHeight = constraints.maxHeight,
                    )
                    val naturalPlaceables = itemMeasurables.map { it.measure(intrinsicItemConstraints) }
                    val naturalSum = naturalPlaceables.sumOf { it.width }
                    itemPlaceables = if (naturalSum <= availableForItems) {
                        naturalPlaceables
                    } else {
                        val scale = availableForItems.toFloat() / naturalSum.coerceAtLeast(1)
                        itemMeasurables.mapIndexed { idx, measurable ->
                            val clampedW = (naturalPlaceables[idx].width * scale).roundToInt().coerceAtLeast(1)
                            measurable.measure(
                                Constraints(
                                    minWidth = clampedW,
                                    maxWidth = clampedW,
                                    minHeight = minTouchPx,
                                    maxHeight = constraints.maxHeight,
                                ),
                            )
                        }
                    }
                    itemOffsetsX = IntArray(itemCount)
                    var cursorX = 0
                    itemPlaceables.forEachIndexed { index, placeable ->
                        itemOffsetsX[index] = cursorX
                        cursorX += placeable.width + if (index < itemCount - 1) gapPx else 0
                    }
                    layoutWidth = cursorX.coerceAtMost(constraints.maxWidth)
                    layoutHeight = maxOf(minTouchPx, itemPlaceables.maxOf { it.height })
                    indicatorInsetVerticalPx = PillOuterPadding.toPx()
                }

                val selectedX = itemOffsetsX[selectedIndex].toFloat()
                val selectedWidth = itemPlaceables[selectedIndex].width.toFloat()
                val targetRect = Rect(
                    left = selectedX,
                    top = indicatorInsetVerticalPx,
                    right = selectedX + selectedWidth,
                    bottom = (layoutHeight - indicatorInsetVerticalPx).coerceAtLeast(indicatorInsetVerticalPx),
                )
                val animatedRect = indicatorAnimator.resolveRect(
                    selectedKey = selected,
                    target = targetRect,
                )
                val indicatorPlaceable = indicatorMeasurable.measure(
                    Constraints.fixed(
                        width = animatedRect.width.roundToInt().coerceAtLeast(0),
                        height = animatedRect.height.roundToInt().coerceAtLeast(0),
                    ),
                )

                layout(layoutWidth, layoutHeight) {
                    indicatorPlaceable.placeRelative(
                        x = animatedRect.left.roundToInt(),
                        y = animatedRect.top.roundToInt(),
                    )
                    itemPlaceables.forEachIndexed { index, placeable ->
                        placeable.placeRelative(
                            x = itemOffsetsX[index],
                            y = (layoutHeight - placeable.height) / 2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ShellSelectionIndicator() {
    Box(
        modifier = Modifier
            .testTag(HiShellTestTags.SelectionIndicator)
            .clip(HiShapeTokens.Full)
            .background(MaterialTheme.colorScheme.secondaryContainer),
    )
}

@Composable
internal fun ShellNavItem(
    destination: ShellDestination,
    selected: Boolean,
    showLabel: Boolean,
    horizontalLayout: Boolean,
    onSelect: (ShellDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val compositionObserver = LocalShellItemCompositionObserver.current
    if (compositionObserver != null) {
        SideEffect { compositionObserver(destination) }
    }

    val label = stringResource(destination.labelRes)
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = if (selected) destination.selectedIcon else destination.unselectedIcon
    val iconContentDescription = if (showLabel) null else label
    val onClick = remember(destination, onSelect) { { onSelect(destination) } }

    Box(
        modifier = modifier
            .testTag(HiShellTestTags.navItem(destination))
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .clip(HiShapeTokens.Full)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (horizontalLayout) {
            Row(
                modifier = Modifier.padding(
                    horizontal = if (showLabel) 14.dp else 12.dp,
                    vertical = 8.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp),
                )
                if (showLabel) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = iconContentDescription,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp),
                )
                if (showLabel) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        }
    }
}

private data class IndicatorCommand(
    val target: Rect,
    val animate: Boolean,
)

@Stable
internal class ShellIndicatorAnimator {
    private var animatable: Animatable<Rect, AnimationVector4D>? = null
    private var lastTarget: Rect? = null
    private var lastSelectedKey: Any? = null
    private val commands = Channel<IndicatorCommand>(Channel.UNLIMITED)

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
            val shouldAnimate = selectionChanged || current.isRunning
            commands.trySend(IndicatorCommand(target = target, animate = shouldAnimate))
            if (!shouldAnimate) {
                return target
            }
        }
        return current.value
    }

    suspend fun runCommandLoop() {
        coroutineScope {
            for (command in commands) {
                var latest = command
                while (true) {
                    val next = commands.tryReceive().getOrNull() ?: break
                    if (!latest.animate && next.animate) {
                        animatable?.snapTo(latest.target)
                    }
                    latest = next
                }
                val targetAnimatable = animatable ?: continue
                if (latest.animate) {
                    launch {
                        targetAnimatable.animateTo(
                            targetValue = latest.target,
                            animationSpec = HiMotion.spatialFast(),
                        )
                    }
                } else {
                    targetAnimatable.snapTo(latest.target)
                }
            }
        }
    }
}

@Composable
internal fun rememberShellIndicatorAnimator(): ShellIndicatorAnimator {
    val animator = remember { ShellIndicatorAnimator() }
    LaunchedEffect(animator) {
        animator.runCommandLoop()
    }
    return animator
}
