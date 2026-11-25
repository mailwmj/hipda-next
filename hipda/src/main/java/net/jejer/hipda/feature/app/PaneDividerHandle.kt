package net.jejer.hipda.feature.app

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.shell.HiShellTestTags

internal val PANE_DIVIDER_HIT_WIDTH: Dp = 48.dp
private val PANE_DIVIDER_LINE_WIDTH: Dp = 1.dp
private val PANE_DIVIDER_ACTIVE_LINE_WIDTH: Dp = 2.dp
private val PANE_DIVIDER_GRIP_WIDTH: Dp = 4.dp
private val PANE_DIVIDER_GRIP_HEIGHT: Dp = 32.dp

/**
 * 三栏中右分隔线（product-design.md §4.1、§6.3、§10.1–§10.2）：
 * - 1dp 视觉细线（不占内容 `Row` 布局宽）+ 48dp 跨边触控热区；
 * - 复用 Compose 标准 `AnchoredDraggable` + `HiMotion.spatialSnap()`，拖动逐帧跟手、松手继承初速吸附；
 * - 暴露 `CustomAccessibilityAction`（`默认宽度` / `均衡` / `收起列表`，空右栏不暴露 `收起列表`）与键盘焦点 `Enter`/方向键切换。
 */
@Composable
fun PaneDividerHandle(
    dragState: AnchoredDraggableState<PaneSplit>,
    availableSplits: List<PaneSplit>,
    onSelectSplit: (PaneSplit) -> Unit,
    modifier: Modifier = Modifier,
    visualLineOffsetPx: Int = 0,
) {
    val cs = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    var isFocused by remember { mutableStateOf(false) }

    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = dragState,
        positionalThreshold = { distance -> distance * 0.5f },
        animationSpec = HiMotion.spatialSnap(),
    )

    val labelDefault = stringResource(R.string.shell_pane_action_default_width)
    val labelBalanced = stringResource(R.string.shell_pane_action_balanced)
    val labelCollapsed = stringResource(R.string.shell_pane_action_collapse_list)
    fun splitLabel(split: PaneSplit): String = when (split) {
        PaneSplit.COLLAPSED -> labelCollapsed
        PaneSplit.DEFAULT -> labelDefault
        PaneSplit.BALANCED -> labelBalanced
    }

    val currentSplit = dragState.currentValue
    val active = isDragged || isFocused

    Box(
        modifier = modifier
            .testTag(HiShellTestTags.PaneDivider)
            .width(PANE_DIVIDER_HIT_WIDTH)
            .fillMaxHeight()
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || availableSplits.isEmpty()) return@onKeyEvent false
                val idx = availableSplits.indexOf(currentSplit).coerceAtLeast(0)
                val target = when (event.key) {
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
                customActions = availableSplits.map { split ->
                    CustomAccessibilityAction(label = splitLabel(split)) {
                        onSelectSplit(split)
                        true
                    }
                }
            }
            .anchoredDraggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = availableSplits.isNotEmpty(),
                interactionSource = interactionSource,
                flingBehavior = flingBehavior,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(visualLineOffsetPx, 0) }
                .width(if (active) PANE_DIVIDER_ACTIVE_LINE_WIDTH else PANE_DIVIDER_LINE_WIDTH)
                .fillMaxHeight()
                .background(if (active) cs.primary else cs.outlineVariant),
        )
        if (active) {
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
