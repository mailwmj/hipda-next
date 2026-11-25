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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.glass.HiGlassState
import net.jejer.hipda.ui.designsystem.glass.HiGlassSurface

val HiNavigationRailWidth: Dp = 80.dp

private val RailHorizontalPadding: Dp = 8.dp
private val RailTopPadding: Dp = 20.dp
private val RailBottomPadding: Dp = 16.dp
private val RailItemSpacing: Dp = 12.dp
private val RailItemMinHeight: Dp = 56.dp

/**
 * 宽度 80dp 的左侧毛玻璃导航轨（宽度 ≥ 840dp 且无物理铰链分隔时使用）。
 *
 * 竖排 4 个一级目的地，选中指示器通过 [net.jejer.hipda.ui.designsystem.motion.HiMotion.spatialFast]
 * 沿纵轴连续滑动且可打断；文字受 [HiCappedFontScale] 限制在 1.25 倍以内。
 */
@Composable
fun HiNavigationRail(
    selected: ShellDestination,
    onSelect: (ShellDestination) -> Unit,
    glassState: HiGlassState,
    modifier: Modifier = Modifier,
) {
    val indicatorAnimator = rememberShellIndicatorAnimator()
    val destinations = ShellDestination.entries
    val selectedIndex = destinations.indexOf(selected)

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
        ) {
            Layout(
                content = {
                    ShellSelectionIndicator()
                    destinations.forEach { destination ->
                        ShellNavItem(
                            destination = destination,
                            selected = destination == selected,
                            showLabel = true,
                            horizontalLayout = false,
                            onSelect = onSelect,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical))
                    .padding(
                        start = RailHorizontalPadding,
                        end = RailHorizontalPadding,
                        top = RailTopPadding,
                        bottom = RailBottomPadding,
                    ),
            ) { measurables, constraints ->
                val indicatorMeasurable = measurables[0]
                val itemMeasurables = measurables.subList(1, measurables.size)
                val itemWidth = constraints.maxWidth
                val minItemHeightPx = RailItemMinHeight.roundToPx()
                val spacingPx = RailItemSpacing.roundToPx()

                val itemPlaceables = itemMeasurables.map { measurable ->
                    measurable.measure(
                        Constraints(
                            minWidth = itemWidth,
                            maxWidth = itemWidth,
                            minHeight = minItemHeightPx,
                            maxHeight = constraints.maxHeight,
                        ),
                    )
                }

                val itemOffsetsY = IntArray(itemPlaceables.size)
                var cursorY = 0
                itemPlaceables.forEachIndexed { index, placeable ->
                    itemOffsetsY[index] = cursorY
                    cursorY += placeable.height + if (index < itemPlaceables.lastIndex) spacingPx else 0
                }
                val layoutHeight = cursorY.coerceAtMost(constraints.maxHeight)

                val selectedY = itemOffsetsY[selectedIndex].toFloat()
                val selectedHeight = itemPlaceables[selectedIndex].height.toFloat()
                val targetRect = Rect(
                    left = 0f,
                    top = selectedY,
                    right = itemWidth.toFloat(),
                    bottom = selectedY + selectedHeight,
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

                layout(itemWidth, layoutHeight) {
                    indicatorPlaceable.placeRelative(
                        x = animatedRect.left.roundToInt(),
                        y = animatedRect.top.roundToInt(),
                    )
                    itemPlaceables.forEachIndexed { index, placeable ->
                        placeable.placeRelative(
                            x = 0,
                            y = itemOffsetsY[index],
                        )
                    }
                }
            }
        }
    }
}
