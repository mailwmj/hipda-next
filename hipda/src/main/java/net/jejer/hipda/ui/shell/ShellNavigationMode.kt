package net.jejer.hipda.ui.shell

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import net.jejer.hipda.R

/** 自适应外壳导航形态断点（product-design.md §4.1）。 */
internal const val SHELL_COMPACT_HEIGHT_BREAKPOINT_DP: Float = 480f
internal const val SHELL_RAIL_WIDTH_BREAKPOINT_DP: Float = 840f
internal const val SHELL_UNSELECTED_LABEL_MIN_WIDTH_DP: Float = 360f

/** 自适应外壳的三种导航形态。 */
enum class ShellNavigationMode {
    BottomPill,
    CompactPill,
    Rail,
}

/** 4 个一级目的地（product-design.md §3、copy.md）。 */
enum class ShellDestination(
    @param:StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Forum(
        labelRes = R.string.shell_nav_forum,
        selectedIcon = Icons.Rounded.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    Messages(
        labelRes = R.string.shell_nav_messages,
        selectedIcon = Icons.Rounded.Email,
        unselectedIcon = Icons.Outlined.Email,
    ),
    Search(
        labelRes = R.string.shell_nav_search,
        selectedIcon = Icons.Rounded.Search,
        unselectedIcon = Icons.Outlined.Search,
    ),
    Mine(
        labelRes = R.string.shell_nav_mine,
        selectedIcon = Icons.Rounded.Person,
        unselectedIcon = Icons.Outlined.Person,
    ),
}

/**
 * 纯函数决定导航形态（product-design.md §4.1，先看高度）：
 * 1. 高度 < 480dp → [ShellNavigationMode.CompactPill]
 * 2. 物理铰链分隔 → [ShellNavigationMode.CompactPill]
 * 3. 宽度 ≥ 840dp → [ShellNavigationMode.Rail]
 * 4. 其余 → [ShellNavigationMode.BottomPill]
 */
fun shellNavigationMode(
    widthDp: Float,
    heightDp: Float,
    hingeSeparating: Boolean = false,
): ShellNavigationMode = when {
    heightDp < SHELL_COMPACT_HEIGHT_BREAKPOINT_DP -> ShellNavigationMode.CompactPill
    hingeSeparating -> ShellNavigationMode.CompactPill
    widthDp >= SHELL_RAIL_WIDTH_BREAKPOINT_DP -> ShellNavigationMode.Rail
    else -> ShellNavigationMode.BottomPill
}

/** 窗口宽度小于 360dp 时底部胶囊只给选中项显示文字，其余仅显示图标（product-design.md §4.1）。 */
fun showLabelForUnselected(widthDp: Float): Boolean =
    widthDp >= SHELL_UNSELECTED_LABEL_MIN_WIDTH_DP

/** 外壳与导航组件的语义测试标签。 */
object HiShellTestTags {
    const val BottomPill: String = "hi_shell_bottom_pill"
    const val CompactPill: String = "hi_shell_compact_pill"
    const val NavigationRail: String = "hi_shell_navigation_rail"
    const val SelectionIndicator: String = "hi_shell_selection_indicator"
    const val ContentContainer: String = "hi_shell_content_container"

    fun navItem(destination: ShellDestination): String = "hi_shell_nav_item_${destination.name}"
}

/** 仅用于测试观测各导航项的组合次数，生产环境下为 `null`。 */
internal val LocalShellItemCompositionObserver: ProvidableCompositionLocal<((ShellDestination) -> Unit)?> =
    staticCompositionLocalOf { null }
