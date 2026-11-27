package net.jejer.hipda.feature.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jejer.hipda.feature.common.PaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.ui.designsystem.feedback.HiSnackbarHost
import net.jejer.hipda.ui.designsystem.hiHorizontalSafeInsets
import net.jejer.hipda.ui.shell.ShellPaneLayout
import net.jejer.hipda.ui.shell.ShellPaneLayoutMode

private val SNACKBAR_BOTTOM_GAP_ABOVE_CHROME: Dp = 8.dp
private val SNACKBAR_BASE_BOTTOM_CLEARANCE: Dp = 16.dp

/** 携带目标分区语义的 [SnackbarVisuals]，依托 `SnackbarHostState` 内置队列保证并发零串区。 */
@Immutable
internal data class PaneSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
    override val withDismissAction: Boolean,
    override val duration: SnackbarDuration,
    val target: PaneSnackbarTarget,
) : SnackbarVisuals

/** 多栏分区对齐的 [PaneSnackbarController] 实现，保持带按钮时有限时长 `SnackbarDuration.Long`（闭合 R-E0 B2）。 */
@Stable
class MultiPaneSnackbarController(val hostState: SnackbarHostState) : PaneSnackbarController {
    override suspend fun show(
        message: String,
        target: PaneSnackbarTarget,
        actionLabel: String?,
        onAction: (() -> Unit)?,
    ) {
        val duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
        val result = hostState.showSnackbar(PaneSnackbarVisuals(message, actionLabel, false, duration, target))
        if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
    }
}

/** 分区 Snackbar 的水平区域与底部避让高度（绝不横跨分隔线或物理铰链，product-design.md §5）。 */
@Immutable
data class SnackbarRegionBounds(val startDp: Dp, val widthDp: Dp, val bottomClearanceDp: Dp)

/** 纯函数计算当前窗口形态与目标分区对应的 Snackbar 水平区间与底部留白。 */
fun calculateSnackbarRegion(
    paneLayout: ShellPaneLayout,
    effectiveMiddleWidthDp: Dp,
    isFullScreen: Boolean,
    target: PaneSnackbarTarget,
    bottomPillVisible: Boolean,
    bottomPillClearanceDp: Dp,
    navBarsBottomDp: Dp,
    detailBottomClearanceDp: Dp = 0.dp,
): SnackbarRegionBounds {
    val windowWidth = paneLayout.windowWidthDp.dp
    val defaultBottom = navBarsBottomDp + SNACKBAR_BASE_BOTTOM_CLEARANCE
    val pillBottom = (bottomPillClearanceDp + SNACKBAR_BOTTOM_GAP_ABOVE_CHROME).coerceAtLeast(defaultBottom)
    val detailBottom = (detailBottomClearanceDp + SNACKBAR_BOTTOM_GAP_ABOVE_CHROME).coerceAtLeast(defaultBottom)
    val activeDetailBottom = if (target == PaneSnackbarTarget.DetailPane) detailBottom else defaultBottom
    if (isFullScreen) return SnackbarRegionBounds(0.dp, windowWidth, activeDetailBottom)
    return when (paneLayout.layoutMode) {
        ShellPaneLayoutMode.CompactThreePane,
        ShellPaneLayoutMode.ExpandedThreePane,
        -> {
            val sidebarW = paneLayout.sidebarWidthDp.dp
            val contentW = paneLayout.contentWidthDp.dp
            if (target == PaneSnackbarTarget.DetailPane) {
                val mid = effectiveMiddleWidthDp.coerceIn(0.dp, contentW)
                SnackbarRegionBounds(sidebarW + mid, (contentW - mid).coerceAtLeast(0.dp), detailBottom)
            } else {
                SnackbarRegionBounds(sidebarW, contentW, defaultBottom)
            }
        }
        ShellPaneLayoutMode.HingeTwoPane -> if (target == PaneSnackbarTarget.DetailPane) {
            val hingeRight = paneLayout.hingeBoundsDp?.right?.dp ?: paneLayout.middlePaneWidthDp.dp
            SnackbarRegionBounds(hingeRight, paneLayout.rightPaneWidthDp.dp, detailBottom)
        } else {
            SnackbarRegionBounds(0.dp, paneLayout.middlePaneWidthDp.dp, pillBottom)
        }
        ShellPaneLayoutMode.SidebarSinglePane -> SnackbarRegionBounds(paneLayout.sidebarWidthDp.dp, paneLayout.contentWidthDp.dp, activeDetailBottom)
        ShellPaneLayoutMode.SinglePane,
        ShellPaneLayoutMode.CompactSinglePane,
        ShellPaneLayoutMode.HingeFallbackSinglePane,
        -> {
            val baseBottom = if (bottomPillVisible) pillBottom else defaultBottom
            SnackbarRegionBounds(0.dp, windowWidth, if (target == PaneSnackbarTarget.DetailPane) maxOf(baseBottom, detailBottom) else baseBottom)
        }
    }
}

/** 多栏分区对齐 Snackbar 宿主：复用 [HiSnackbarHost]，并缓存最近非空分区使退场动画期间绝不跨分隔线/铰链跳变（R-E3 S1）。 */
@Composable
fun MultiPaneSnackbarHost(
    hostState: SnackbarHostState,
    paneLayout: ShellPaneLayout,
    effectiveMiddleWidthDp: Dp,
    isFullScreen: Boolean,
    bottomPillVisible: Boolean,
    bottomPillClearanceDp: Dp,
    navBarsBottomDp: Dp,
    modifier: Modifier = Modifier,
    detailBottomClearanceDp: Dp = 0.dp,
) {
    val currentVisuals = hostState.currentSnackbarData?.visuals as? PaneSnackbarVisuals
    var cachedTarget by remember { mutableStateOf(PaneSnackbarTarget.ContentArea) }
    if (currentVisuals != null) cachedTarget = currentVisuals.target
    val target = currentVisuals?.target ?: cachedTarget

    val region = calculateSnackbarRegion(paneLayout, effectiveMiddleWidthDp, isFullScreen, target, bottomPillVisible, bottomPillClearanceDp, navBarsBottomDp, detailBottomClearanceDp)
    val horizontalSafeInsets = if (paneLayout.layoutMode == ShellPaneLayoutMode.HingeTwoPane) {
        Modifier
    } else {
        Modifier.windowInsetsPadding(WindowInsets.hiHorizontalSafeInsets)
    }
    Box(modifier = modifier.fillMaxSize().then(horizontalSafeInsets)) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = region.startDp)
                .width(region.widthDp)
                .fillMaxHeight(),
        ) {
            HiSnackbarHost(hostState = hostState, bottomClearance = region.bottomClearanceDp)
        }
    }
}
