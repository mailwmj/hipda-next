package net.jejer.hipda.ui.shell

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.glass.HiGlassState
import net.jejer.hipda.ui.designsystem.glass.HiGlassSurface

val HiNavigationPillMinHeight: Dp = 64.dp
val HiNavigationPillCompactMinHeight: Dp = 48.dp
val HiNavigationPillMaxWidth: Dp = 420.dp
val HiNavigationPillHorizontalMargin: Dp = 16.dp
val HiNavigationPillBottomGap: Dp = 8.dp
val HiNavigationPillDragCancelVerticalDp: Dp = 48.dp
const val HiNavigationPillNarrowGestureExclusionMaxWidthDp: Float = 452f
val HiNavigationPillLeftEdgeGuardDp: Dp = 16.dp

/**
 * 底部悬浮毛玻璃导航胶囊（标准 [ShellNavigationMode.BottomPill] 与紧凑 [ShellNavigationMode.CompactPill]）。
 *
 * 固定 4 项（论坛/消息/搜索/我的，不加 `+`），支持轻点、TalkBack 探索及横向跟手拖拽预选；
 * 窗口宽 `< 452dp` 时在胶囊高度区挂局部 [systemGestureExclusion] 并忽略左缘 16dp 起点拖拽，防止与系统预测性返回冲突。
 */
@Composable
fun HiNavigationPill(
    selected: ShellDestination,
    onSelect: (ShellDestination) -> Unit,
    glassState: HiGlassState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showUnselectedLabels: Boolean = true,
    unreadMessagesCount: Int = 0,
) {
    val mode = if (compact) ShellNavigationMode.CompactPill else ShellNavigationMode.BottomPill
    val windowWidthDp = LocalWindowInfo.current.containerDpSize.width.value
    val isNarrowEdgeRisk = windowWidthDp < HiNavigationPillNarrowGestureExclusionMaxWidthDp
    var previewDestination by remember(mode) { mutableStateOf<ShellDestination?>(null) }

    val pillModifier = modifier
        .testTag(if (compact) HiShellTestTags.CompactPill else HiShellTestTags.BottomPill)
        .widthIn(max = HiNavigationPillMaxWidth)
        .then(if (compact) Modifier else Modifier.fillMaxWidth())
        .heightIn(min = if (compact) HiNavigationPillCompactMinHeight else HiNavigationPillMinHeight)
        .then(if (isNarrowEdgeRisk) Modifier.systemGestureExclusion() else Modifier)

    HiCappedFontScale {
        HiGlassSurface(state = glassState, modifier = pillModifier, shape = HiShapeTokens.Full, floating = true) {
            ShellIndicatorLayout(
                selected = selected,
                mode = mode,
                onPreviewChange = { previewDestination = it },
                onCommitSelection = onSelect,
                guardLeftEdge = isNarrowEdgeRisk,
                modifier = Modifier
                    .then(if (compact) Modifier else Modifier.fillMaxWidth())
                    .padding(horizontal = PillOuterPadding, vertical = if (compact) 0.dp else PillOuterPadding),
            ) {
                ShellDestination.entries.forEach { destination ->
                    val isSelected = destination == selected
                    val isHighlighted = previewDestination?.let { it == destination } ?: isSelected
                    ShellNavItem(
                        destination = destination,
                        selected = isSelected,
                        highlighted = isHighlighted,
                        showLabel = if (compact) isSelected else (showUnselectedLabels || isSelected),
                        mode = mode,
                        onSelect = onSelect,
                        unreadCount = if (destination == ShellDestination.Messages) unreadMessagesCount else 0,
                    )
                }
            }
        }
    }
}
