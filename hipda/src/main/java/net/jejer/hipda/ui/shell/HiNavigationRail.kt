package net.jejer.hipda.ui.shell

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.glass.HiGlassBorderMode
import net.jejer.hipda.ui.designsystem.glass.HiGlassState
import net.jejer.hipda.ui.designsystem.glass.HiGlassSurface

val HiNavigationRailWidth: Dp = 80.dp
private val RailHorizontalPadding: Dp = 8.dp

/**
 * 宽度 80dp 的左侧毛玻璃导航轨（宽度 ≥ 840dp 且无竖直物理铰链分隔时使用）。
 *
 * 使用 `surfaceContainer` 作为毛玻璃底色（与滚动后顶栏一致），在深浅色下均与内容区 `background` 形成清晰层级区分。
 */
@Composable
fun HiNavigationRail(
    selected: ShellDestination,
    onSelect: (ShellDestination) -> Unit,
    glassState: HiGlassState,
    modifier: Modifier = Modifier,
) {
    HiCappedFontScale {
        HiGlassSurface(
            state = glassState,
            modifier = modifier
                .testTag(HiShellTestTags.NavigationRail)
                .width(HiNavigationRailWidth)
                .fillMaxHeight(),
            shape = RectangleShape,
            floating = false,
            contentAlignment = Alignment.TopCenter,
            borderMode = HiGlassBorderMode.EndEdgeOnly,
            surfaceColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            ShellIndicatorLayout(
                selected = selected,
                mode = ShellNavigationMode.Rail,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical))
                    .padding(horizontal = RailHorizontalPadding),
            ) {
                ShellDestination.entries.forEach { destination ->
                    ShellNavItem(
                        destination = destination,
                        selected = destination == selected,
                        showLabel = true,
                        mode = ShellNavigationMode.Rail,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}
