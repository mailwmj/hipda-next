package net.jejer.hipda.ui.shell

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.motion.HiMotion

internal val PillOuterPadding: Dp = 4.dp
private val CompactSelectedAdjacentGap: Dp = 12.dp
private val MinTouchTarget: Dp = 48.dp
private val StandardItemMinHeight: Dp = 56.dp
private const val IndicatorDragScale: Float = 1.05f

private class PillLayoutGeometry {
    var offsets: IntArray = IntArray(0)
    var widths: IntArray = IntArray(0)
    var layoutWidth: Int = 0
    var layoutHeight: Int = 0
    var indicatorTop: Float = 0f
    var indicatorBottom: Float = 0f

    fun itemRect(index: Int): Rect {
        if (offsets.isEmpty()) return Rect.Zero
        val safe = index.coerceIn(0, offsets.lastIndex)
        val left = offsets[safe].toFloat()
        return Rect(left, indicatorTop, left + widths[safe], indicatorBottom)
    }

    fun dragRectAtX(fingerX: Float): Rect {
        if (offsets.isEmpty() || layoutWidth <= 0) return Rect.Zero
        val width = interpolatedWidthAtX(fingerX)
        val left = (fingerX - width / 2f).coerceIn(0f, (layoutWidth - width).coerceAtLeast(0f))
        return Rect(left, indicatorTop, left + width, indicatorBottom)
    }

    fun indexAtX(x: Float): Int {
        val last = offsets.lastIndex
        if (last <= 0 || x <= offsets[0]) return 0
        if (x >= offsets[last] + widths[last]) return last
        for (i in offsets.indices) {
            if (x >= offsets[i] && x <= offsets[i] + widths[i]) return i
        }
        return offsets.indices.minBy { abs(x - (offsets[it] + widths[it] / 2f)) }
    }

    private fun interpolatedWidthAtX(x: Float): Float {
        val last = offsets.lastIndex
        if (last <= 0) return widths.firstOrNull()?.toFloat() ?: 0f
        if (x <= offsets[0] + widths[0] / 2f) return widths[0].toFloat()
        if (x >= offsets[last] + widths[last] / 2f) return widths[last].toFloat()
        for (i in 0 until last) {
            val c0 = offsets[i] + widths[i] / 2f
            val c1 = offsets[i + 1] + widths[i + 1] / 2f
            if (x in c0..c1) {
                val fraction = ((x - c0) / (c1 - c0).coerceAtLeast(1f)).coerceIn(0f, 1f)
                return widths[i] + (widths[i + 1] - widths[i]) * fraction
            }
        }
        return widths[0].toFloat()
    }
}

/**
 * 底部胶囊（[ShellNavigationMode.BottomPill] 与 [ShellNavigationMode.CompactPill]）的单遍横向指示器布局：
 * 每个子 `Measurable` 严格只 `measure` 一次；传入 [onCommitSelection] 时支持超 `touchSlop` 横向跟手拖拽预选。
 */
@Composable
internal fun ShellIndicatorLayout(
    selected: ShellDestination,
    mode: ShellNavigationMode,
    modifier: Modifier = Modifier,
    onPreviewChange: ((ShellDestination?) -> Unit)? = null,
    onCommitSelection: ((ShellDestination) -> Unit)? = null,
    guardLeftEdge: Boolean = false,
    itemsContent: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val indicatorAnimator = remember(mode, scope) { ShellIndicatorAnimator(scope) }
    val geometry = remember(mode) { PillLayoutGeometry() }
    val reducedMotion = HiTheme.isReducedMotion
    val darkTheme = HiTheme.state.darkTheme
    val selectedIndex = ShellDestination.entries.indexOf(selected)
    var isDragging by remember(mode) { mutableStateOf(false) }

    val currentSelected by rememberUpdatedState(selected)
    val currentReducedMotion by rememberUpdatedState(reducedMotion)
    val currentOnPreviewChange by rememberUpdatedState(onPreviewChange)
    val currentOnCommitSelection by rememberUpdatedState(onCommitSelection)

    val dragScale by animateFloatAsState(
        targetValue = if (isDragging) IndicatorDragScale else 1f,
        animationSpec = HiMotion.spatialFast(),
        label = "ShellIndicatorDragScale",
    )
    val indicatorColor = MaterialTheme.colorScheme.primary.copy(
        alpha = HiTheme.glass.indicatorAlpha.coerceIn(0f, 1f),
    )
    val api33DragLens = isDragging && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val dragLensBrush = remember(darkTheme) {
        val alphas = if (darkTheme) {
            listOf(
                HiGlassTokens.DarkBorderHighlightTopLeftAlpha,
                HiGlassTokens.DarkBorderHighlightMidAlpha,
                HiGlassTokens.DarkBorderHighlightBottomRightAlpha,
            )
        } else {
            listOf(
                HiGlassTokens.LightBorderHighlightTopLeftAlpha,
                HiGlassTokens.LightBorderHighlightMidAlpha,
                HiGlassTokens.LightBorderHighlightBottomRightAlpha,
            )
        }
        Brush.linearGradient(alphas.map { Color.White.copy(alpha = it) }, Offset.Zero, Offset.Infinite)
    }

    val dragGestureModifier = if (onCommitSelection != null) {
        Modifier.pointerInput(mode, guardLeftEdge) {
            val touchSlop = viewConfiguration.touchSlop
            val cancelVerticalPx = HiNavigationPillDragCancelVerticalDp.toPx()
            val leftEdgeGuardPx = (HiNavigationPillLeftEdgeGuardDp - PillOuterPadding).coerceAtLeast(0.dp).toPx()

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (guardLeftEdge && down.position.x < leftEdgeGuardPx) return@awaitEachGesture
                var dragging = false
                var cancelledByVertical = false
                var lastPreviewIndex: Int? = null
                var committedIndex: Int? = null

                try {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes
                            .firstOrNull { it.id == down.id } ?: break
                        if (!dragging) {
                            if (!change.pressed) break
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (abs(dy) > touchSlop && abs(dy) > abs(dx)) break
                            if (abs(dx) <= touchSlop || abs(dx) < abs(dy)) continue
                            dragging = true
                            isDragging = true
                        }
                        val externallyConsumed = change.isConsumed
                        change.consume()
                        if (!change.pressed) {
                            if (!cancelledByVertical && !externallyConsumed) {
                                committedIndex = lastPreviewIndex
                            }
                            break
                        }
                        val selIdx = ShellDestination.entries.indexOf(currentSelected).coerceAtLeast(0)
                        val verticalOut = when {
                            change.position.y < 0f -> -change.position.y
                            change.position.y > geometry.layoutHeight -> change.position.y - geometry.layoutHeight
                            else -> 0f
                        }
                        val nextPreview = if (verticalOut > cancelVerticalPx) {
                            if (!cancelledByVertical) {
                                indicatorAnimator.reboundDuringDrag(
                                    targetKey = currentSelected,
                                    targetRect = geometry.itemRect(selIdx),
                                    reducedMotion = currentReducedMotion,
                                )
                            }
                            null
                        } else if (geometry.layoutWidth > 0 && geometry.offsets.isNotEmpty()) {
                            indicatorAnimator.snapDuringDrag(geometry.dragRectAtX(change.position.x))
                            geometry.indexAtX(change.position.x)
                        } else {
                            null
                        }
                        val nowCancelled = nextPreview == null
                        if (nowCancelled != cancelledByVertical || nextPreview != lastPreviewIndex) {
                            cancelledByVertical = nowCancelled
                            lastPreviewIndex = nextPreview
                            currentOnPreviewChange?.invoke(nextPreview?.let { ShellDestination.entries.getOrNull(it) })
                        }
                    }
                } finally {
                    if (dragging) {
                        isDragging = false
                        currentOnPreviewChange?.invoke(null)
                        val commitIdx = committedIndex
                        if (commitIdx != null) {
                            val targetDest = ShellDestination.entries[commitIdx]
                            indicatorAnimator.endDrag(targetDest, geometry.itemRect(commitIdx), false, currentReducedMotion)
                            if (targetDest != currentSelected) currentOnCommitSelection?.invoke(targetDest)
                        } else {
                            val origIdx = ShellDestination.entries.indexOf(currentSelected).coerceAtLeast(0)
                            indicatorAnimator.endDrag(currentSelected, geometry.itemRect(origIdx), true, currentReducedMotion)
                        }
                    }
                }
            }
        }
    } else {
        Modifier
    }

    Layout(
        content = {
            Box(
                modifier = Modifier
                    .testTag(HiShellTestTags.SelectionIndicator)
                    .graphicsLayer {
                        scaleX = dragScale
                        scaleY = dragScale
                    }
                    .clip(HiShapeTokens.Full)
                    .background(indicatorColor)
                    .then(
                        if (api33DragLens) {
                            Modifier.border(HiGlassTokens.BorderWidth, dragLensBrush, HiShapeTokens.Full)
                        } else {
                            Modifier
                        },
                    ),
            )
            itemsContent()
        },
        modifier = modifier.then(dragGestureModifier),
    ) { measurables, constraints ->
        val itemMeasurables = measurables.subList(1, measurables.size)
        val count = itemMeasurables.size
        val maxW = constraints.maxWidth.coerceAtLeast(0)
        val maxH = constraints.maxHeight.coerceAtLeast(0)
        val minTouchPx = MinTouchTarget.roundToPx()
        val gapCount = (count - 1).coerceAtLeast(0)

        val gaps: IntArray
        val mainSizes: IntArray
        val minCrossPx: Int
        when (mode) {
            ShellNavigationMode.CompactPill -> {
                val adjGap = CompactSelectedAdjacentGap.roundToPx()
                gaps = IntArray(gapCount) { i -> if (i == selectedIndex || i + 1 == selectedIndex) adjGap else 0 }
                val natural = IntArray(count) { i -> maxOf(minTouchPx, itemMeasurables[i].maxIntrinsicWidth(maxH)) }
                mainSizes = fitAxis(natural, gaps, maxW, minTouchPx, selectedIndex)
                minCrossPx = minTouchPx.coerceAtMost(maxH)
            }
            else -> {
                gaps = IntArray(gapCount)
                mainSizes = splitEvenly(maxW, count)
                minCrossPx = StandardItemMinHeight.roundToPx().coerceAtMost(maxH)
            }
        }

        val mainOffsets = IntArray(count)
        var cursor = 0
        val itemPlaceables = itemMeasurables.mapIndexed { i, m ->
            val main = mainSizes[i].coerceIn(0, maxW)
            mainOffsets[i] = cursor
            cursor += main + if (i < gapCount) gaps[i] else 0
            m.measure(Constraints(main, main, minCrossPx, maxH))
        }

        val layoutWidth = if (mode == ShellNavigationMode.CompactPill) cursor.coerceAtMost(maxW) else maxW
        val layoutHeight = maxOf(minCrossPx, itemPlaceables.maxOf { it.height }).coerceAtMost(maxH)
        val insetV = if (mode == ShellNavigationMode.CompactPill) {
            PillOuterPadding.toPx().coerceAtMost(layoutHeight / 4f)
        } else {
            0f
        }
        val indicatorBottom = (layoutHeight - insetV).coerceAtLeast(insetV)

        geometry.offsets = mainOffsets
        geometry.widths = IntArray(count) { i -> itemPlaceables[i].width }
        geometry.layoutWidth = layoutWidth
        geometry.layoutHeight = layoutHeight
        geometry.indicatorTop = insetV
        geometry.indicatorBottom = indicatorBottom

        val selMain = mainOffsets[selectedIndex].toFloat()
        val targetRect = Rect(selMain, insetV, selMain + itemPlaceables[selectedIndex].width, indicatorBottom)

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
                val cross = ((layoutHeight - p.height) / 2).coerceAtLeast(0)
                p.placeRelative(x = mainOffsets[i], y = cross)
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
    highlighted: Boolean = selected,
    unreadCount: Int = 0,
) {
    val cs = MaterialTheme.colorScheme
    val label = stringResource(destination.labelRes)
    val unreadDesc = if (unreadCount > 0) stringResource(R.string.shell_sidebar_unread_cd, label, unreadCount) else null
    val tint = if (highlighted) cs.primary else cs.onSurfaceVariant
    val onClick = remember(destination, onSelect) { { onSelect(destination) } }
    val content: @Composable () -> Unit = {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (highlighted) destination.selectedIcon else destination.unselectedIcon,
                contentDescription = if (showLabel || unreadDesc != null) null else label,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            if (unreadCount > 0) {
                SidebarUnreadBadge(
                    count = unreadCount,
                    compact = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-4).dp)
                        .clearAndSetSemantics {},
                )
            }
        }
        if (showLabel) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
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
            .then(
                if (unreadDesc != null) Modifier.semantics(mergeDescendants = true) { contentDescription = unreadDesc } else Modifier,
            )
            .clip(HiShapeTokens.Full)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = indication,
                onClick = onClick,
                role = Role.Tab,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (mode == ShellNavigationMode.CompactPill) {
            Row(
                modifier = Modifier.padding(horizontal = if (showLabel) 16.dp else 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { content() }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
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
    private var dragSnapRect by mutableStateOf<Rect?>(null)
    private var dragging: Boolean = false

    fun resolveRect(selectedKey: Any, target: Rect, reducedMotion: Boolean = false): Rect {
        val activeDragRect = dragSnapRect
        val current = animatable ?: Animatable(target, Rect.VectorConverter, Rect.VisibilityThreshold).also {
            animatable = it
            lastTarget = target
            lastSelectedKey = selectedKey
        }
        if (dragging) return activeDragRect ?: pendingSnap ?: current.value
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

    fun snapDuringDrag(rect: Rect) {
        dragging = true
        pendingSnap = null
        dragSnapRect = rect
        lastTarget = rect
        val current = animatable
        if (current != null) {
            scope.launch { current.snapTo(rect) }
        } else {
            animatable = Animatable(rect, Rect.VectorConverter, Rect.VisibilityThreshold)
        }
    }

    fun reboundDuringDrag(targetKey: Any, targetRect: Rect, reducedMotion: Boolean) {
        val current = animatable ?: return
        val startRect = dragSnapRect
        dragging = true
        dragSnapRect = null
        lastSelectedKey = targetKey
        lastTarget = targetRect
        if (reducedMotion) {
            snapTo(current, targetRect)
        } else {
            pendingSnap = startRect
            scope.launch {
                if (startRect != null && current.value != startRect) current.snapTo(startRect)
                if (pendingSnap == startRect) pendingSnap = null
                current.animateTo(targetValue = targetRect, animationSpec = HiMotion.spatialSnap())
            }
        }
    }

    fun endDrag(targetKey: Any, targetRect: Rect, cancelled: Boolean, reducedMotion: Boolean) {
        val startRect = dragSnapRect
        dragging = false
        dragSnapRect = null
        val current = animatable ?: return
        lastSelectedKey = targetKey
        lastTarget = targetRect
        if (reducedMotion) {
            snapTo(current, targetRect)
        } else {
            val spec = if (cancelled) HiMotion.spatialSnap<Rect>() else HiMotion.spatialFast<Rect>()
            val effectiveStart = startRect ?: current.value
            if (effectiveStart != targetRect && (startRect != null || current.targetValue != targetRect || !current.isRunning)) {
                pendingSnap = startRect
                scope.launch {
                    if (startRect != null && current.value != startRect) current.snapTo(startRect)
                    if (pendingSnap == startRect) pendingSnap = null
                    current.animateTo(targetValue = targetRect, animationSpec = spec)
                }
            }
        }
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
