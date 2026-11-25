package net.jejer.hipda.ui.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.jejer.hipda.ui.designsystem.glass.hiGlassSource
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut

/**
 * HiPDA Next 自适应主外壳：按实时窗口尺寸与物理铰链状态在底部悬浮胶囊、紧凑胶囊与左侧 80dp 导航轨之间切换。
 *
 * 窗口信息选型说明：
 * - 使用 [LocalWindowInfo.current] 的 `containerDpSize` 获取精确窗口宽高（dp），因为 360dp（窄窗隐藏未选中标签）
 *   等阈值细于 `WindowSizeClass` 的三档离散分桶；
 * - 使用 [currentWindowAdaptiveInfoV2] 的 `windowPosture.hingeList` 读取 Jetpack WindowManager 的实时 `isSeparating`
 *   铰链分隔信号，避免重复收集 `WindowInfoTracker`。
 *
 * 内容区作为 Haze 源铺满窗口（胶囊浮在内容上方不占布局高度；导航轨占左侧 80dp），
 * 切换一级目的地时原地交叉淡入淡出（无位移）。
 */
@Composable
fun HiAdaptiveShell(
    selected: ShellDestination,
    onSelect: (ShellDestination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (ShellDestination) -> Unit,
) {
    val windowDpSize = LocalWindowInfo.current.containerDpSize
    val windowPosture = currentWindowAdaptiveInfoV2().windowPosture
    val widthDp = windowDpSize.width.value
    val heightDp = windowDpSize.height.value
    val hingeSeparating = windowPosture.hingeList.any { it.isSeparating }
    val mode = shellNavigationMode(
        widthDp = widthDp,
        heightDp = heightDp,
        hingeSeparating = hingeSeparating,
    )
    val showUnselectedLabels = showLabelForUnselected(widthDp)

    HiAdaptiveShellLayout(
        selected = selected,
        onSelect = onSelect,
        mode = mode,
        showUnselectedLabels = showUnselectedLabels,
        modifier = modifier,
        content = content,
    )
}

@Composable
internal fun HiAdaptiveShellLayout(
    selected: ShellDestination,
    onSelect: (ShellDestination) -> Unit,
    mode: ShellNavigationMode,
    showUnselectedLabels: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (ShellDestination) -> Unit,
) {
    val glassState = rememberHiGlassState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .testTag(HiShellTestTags.ContentContainer)
                .fillMaxSize()
                .padding(start = if (mode == ShellNavigationMode.Rail) HiNavigationRailWidth else 0.dp)
                .hiGlassSource(glassState),
        ) {
            AnimatedContent(
                targetState = selected,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    (
                        hiFadeIn(animationSpec = HiMotion.effectsDefault()) togetherWith
                            hiFadeOut(animationSpec = HiMotion.exit(HiMotion.effectsDefault()))
                        ).using(null)
                },
                label = "HiAdaptiveShellContent",
            ) { destination ->
                content(destination)
            }
        }

        when (mode) {
            ShellNavigationMode.Rail -> {
                HiNavigationRail(
                    selected = selected,
                    onSelect = onSelect,
                    glassState = glassState,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }

            ShellNavigationMode.BottomPill,
            ShellNavigationMode.CompactPill,
            -> {
                HiNavigationPill(
                    selected = selected,
                    onSelect = onSelect,
                    glassState = glassState,
                    compact = mode == ShellNavigationMode.CompactPill,
                    showUnselectedLabels = showUnselectedLabels,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(
                            start = HiNavigationPillHorizontalMargin,
                            end = HiNavigationPillHorizontalMargin,
                            bottom = HiNavigationPillBottomGap,
                        ),
                )
            }
        }
    }
}
