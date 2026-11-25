package net.jejer.hipda.feature.app

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.shell.HiShellTestTags

internal val PANE_DIVIDER_HIT_WIDTH: Dp = 48.dp
private val PANE_DIVIDER_A11Y_HEIGHT: Dp = 96.dp
private val PANE_DIVIDER_LINE_WIDTH: Dp = 1.dp
private val PANE_DIVIDER_ACTIVE_LINE_WIDTH: Dp = 2.dp
private val PANE_DIVIDER_GRIP_WIDTH: Dp = 4.dp
private val PANE_DIVIDER_GRIP_HEIGHT: Dp = 32.dp
private val PANE_DIVIDER_VELOCITY_THRESHOLD: Dp = 125.dp

/**
 * 挂在三栏父容器 `Box` 上的横向分隔线拖拽门控（闭合 R-E3 B1）：仅当落点位于交界 `48dp` 热区且横向滑动占优时消费事件，
 * 轻点（「更多」/「返回」）与竖滑（列表滚动）100% 透传给底层组件；松手继承初速经 `HiMotion.spatialSnap()` 吸附。
 */
internal fun Modifier.paneDividerDrag(
    dragState: AnchoredDraggableState<PaneSplit>,
    scope: CoroutineScope,
    enabled: Boolean,
    reducedMotion: Boolean,
    getDividerX: () -> Float,
    getContentWidthPx: () -> Float,
    onDraggingChanged: (Boolean) -> Unit,
    onSettled: (PaneSplit) -> Unit,
): Modifier = if (!enabled) this else pointerInput(dragState, scope, reducedMotion) {
    val touchSlop = viewConfiguration.touchSlop
    val hitW = PANE_DIVIDER_HIT_WIDTH.toPx()
    val velocityThresholdPx = PANE_DIVIDER_VELOCITY_THRESHOLD.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val hitLeft = (getDividerX() - hitW / 2f).coerceIn(0f, (getContentWidthPx() - hitW).coerceAtLeast(0f))
        if (down.position.x !in hitLeft..(hitLeft + hitW)) return@awaitEachGesture

        var dragging = false
        var lastX = down.position.x
        val tracker = VelocityTracker()
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!dragging) {
                    if (!change.pressed) break
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx)) break
                    if (abs(dx) <= touchSlop || abs(dx) < abs(dy)) continue
                    dragging = true
                    onDraggingChanged(true)
                    tracker.resetTracking()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    lastX = down.position.x
                }
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                if (!change.pressed) {
                    settleDividerWithVelocity(dragState, scope, tracker.calculateVelocity().x, velocityThresholdPx, reducedMotion, onSettled)
                    break
                }
                val stepDx = change.position.x - lastX
                lastX = change.position.x
                if (stepDx != 0f) {
                    dragState.dispatchRawDelta(stepDx)
                    // 立即派发快照通知，确保拖拽偏移在当前 vsync 帧 1:1 跟手生效无 1 帧滞后
                    Snapshot.sendApplyNotifications()
                }
            }
        } finally {
            if (dragging) onDraggingChanged(false)
        }
    }
}

private fun settleDividerWithVelocity(
    dragState: AnchoredDraggableState<PaneSplit>,
    scope: CoroutineScope,
    velocityX: Float,
    velocityThresholdPx: Float,
    reducedMotion: Boolean,
    onSettled: (PaneSplit) -> Unit,
) {
    val anchors = dragState.anchors
    val offset = dragState.offset
    if (offset.isNaN() || anchors.size == 0) return
    val target = if (abs(velocityX) >= velocityThresholdPx) {
        anchors.closestAnchor(offset, searchUpwards = velocityX > 0f) ?: dragState.currentValue
    } else {
        anchors.closestAnchor(offset) ?: dragState.currentValue
    }
    scope.launch {
        if (reducedMotion) {
            dragState.snapTo(target)
        } else {
            dragState.anchoredDrag(targetValue = target) { latest, latestTarget ->
                val targetPos = latest.positionOf(latestTarget)
                val startPos = dragState.offset
                if (!targetPos.isNaN() && !startPos.isNaN()) {
                    animate(startPos, targetPos, velocityX, HiMotion.spatialSnap()) { v, vel -> dragTo(v, vel) }
                }
            }
        }
        onSettled(target)
    }
}

/** 三栏中右 1dp 视觉分隔细线（无指针输入与语义，不阻挡任何点击/滚动）。 */
@Composable
internal fun PaneDividerLine(active: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.width(if (active) PANE_DIVIDER_ACTIVE_LINE_WIDTH else PANE_DIVIDER_LINE_WIDTH).fillMaxHeight().background(if (active) cs.primary else cs.outlineVariant))
}

/** 三栏中右分隔线无障碍与键盘手柄（不挂指针输入、居中避开顶栏/底栏按钮，暴露 `CustomAccessibilityAction` 与键盘切换）。 */
@Composable
fun PaneDividerHandle(
    dragState: AnchoredDraggableState<PaneSplit>,
    availableSplits: List<PaneSplit>,
    onSelectSplit: (PaneSplit) -> Unit,
    modifier: Modifier = Modifier,
    isDragged: Boolean = false,
    visualLineOffsetPx: Int = 0,
    onFocusedChanged: (Boolean) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    var isFocused by remember { mutableStateOf(false) }
    val labelDefault = stringResource(R.string.shell_pane_action_default_width)
    val labelBalanced = stringResource(R.string.shell_pane_action_balanced)
    val labelCollapsed = stringResource(R.string.shell_pane_action_collapse_list)
    fun splitLabel(s: PaneSplit) = when (s) {
        PaneSplit.COLLAPSED -> labelCollapsed
        PaneSplit.DEFAULT -> labelDefault
        PaneSplit.BALANCED -> labelBalanced
    }

    val currentSplit = dragState.currentValue
    Box(
        modifier = modifier
            .testTag(HiShellTestTags.PaneDivider)
            .width(PANE_DIVIDER_HIT_WIDTH)
            .height(PANE_DIVIDER_A11Y_HEIGHT)
            .onFocusChanged { isFocused = it.isFocused; onFocusedChanged(it.isFocused) }
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || availableSplits.isEmpty()) return@onKeyEvent false
                val idx = availableSplits.indexOf(currentSplit).coerceAtLeast(0)
                val target = when (e.key) {
                    Key.DirectionLeft -> availableSplits[(idx - 1).coerceAtLeast(0)]
                    Key.DirectionRight -> availableSplits[(idx + 1).coerceAtMost(availableSplits.lastIndex)]
                    Key.Enter, Key.NumPadEnter, Key.Spacebar -> availableSplits[(idx + 1) % availableSplits.size]
                    else -> return@onKeyEvent false
                }
                if (target != currentSplit) onSelectSplit(target)
                true
            }
            .semantics {
                stateDescription = splitLabel(currentSplit)
                customActions = availableSplits.map { s -> CustomAccessibilityAction(splitLabel(s)) { onSelectSplit(s); true } }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (isDragged || isFocused) {
            Box(
                modifier = Modifier
                    .offset { IntOffset((visualLineOffsetPx - 1).coerceAtLeast(0), 0) }
                    .width(PANE_DIVIDER_GRIP_WIDTH)
                    .height(PANE_DIVIDER_GRIP_HEIGHT)
                    .clip(HiShapeTokens.Full)
                    .background(cs.primary),
            )
        }
    }
}
