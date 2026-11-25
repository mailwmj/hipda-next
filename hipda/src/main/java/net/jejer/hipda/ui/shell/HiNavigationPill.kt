package net.jejer.hipda.ui.shell

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

/** 底部悬浮毛玻璃导航胶囊（标准 [ShellNavigationMode.BottomPill] 与紧凑 [ShellNavigationMode.CompactPill]）。 */
@Composable
fun HiNavigationPill(
    selected: ShellDestination,
    onSelect: (ShellDestination) -> Unit,
    glassState: HiGlassState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showUnselectedLabels: Boolean = true,
) {
    val mode = if (compact) ShellNavigationMode.CompactPill else ShellNavigationMode.BottomPill
    val pillModifier = modifier
        .testTag(if (compact) HiShellTestTags.CompactPill else HiShellTestTags.BottomPill)
        .widthIn(max = HiNavigationPillMaxWidth)
        .then(if (compact) Modifier else Modifier.fillMaxWidth())
        .heightIn(min = if (compact) HiNavigationPillCompactMinHeight else HiNavigationPillMinHeight)

    HiCappedFontScale {
        HiGlassSurface(state = glassState, modifier = pillModifier, shape = HiShapeTokens.Full, floating = true) {
            ShellIndicatorLayout(
                selected = selected,
                mode = mode,
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
                        mode = mode,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}
