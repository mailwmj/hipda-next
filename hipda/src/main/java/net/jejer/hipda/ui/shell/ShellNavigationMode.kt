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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.math.roundToInt
import net.jejer.hipda.R

/** 自适应外壳导航形态与三栏布局断点常量（product-design.md §3.2、§4.1）。 */
internal const val SHELL_COMPACT_HEIGHT_BREAKPOINT_DP: Float = 480f
internal const val SHELL_UNSELECTED_LABEL_MIN_WIDTH_DP: Float = 360f
const val SHELL_SIDEBAR_COLLAPSED_WIDTH_BREAKPOINT_DP: Float = 600f
const val SHELL_THREE_PANE_WIDTH_BREAKPOINT_DP: Float = 680f
const val SHELL_SIDEBAR_EXPANDED_WIDTH_BREAKPOINT_DP: Float = 840f
const val SHELL_MINE_GROUP_AUTO_EXPAND_HEIGHT_DP: Float = 760f

const val SHELL_SIDEBAR_COLLAPSED_WIDTH_DP: Int = 64
const val SHELL_SIDEBAR_EXPANDED_MIN_WIDTH_DP: Int = 176
const val SHELL_SIDEBAR_EXPANDED_MAX_WIDTH_DP: Int = 220
const val SHELL_SIDEBAR_MODAL_WIDTH_DP: Int = 240

const val SHELL_MIDDLE_PANE_MIN_WIDTH_DP: Int = 256
const val SHELL_MIDDLE_PANE_MAX_WIDTH_DP: Int = 380
const val SHELL_MIDDLE_PANE_BALANCED_MAX_WIDTH_DP: Int = 480
const val SHELL_MIDDLE_PANE_SNAP_MERGE_THRESHOLD_DP: Int = 24
const val SHELL_RIGHT_PANE_MIN_WIDTH_DP: Int = 360

const val HINGE_LEFT_MIN_WIDTH_DP: Int = 280
const val HINGE_RIGHT_MIN_WIDTH_DP: Int = 360

/** 自适应外壳的四种导航形态（product-design.md §4.1）。 */
enum class ShellNavigationMode {
    BottomPill,
    CompactPill,
    SidebarCollapsed,
    SidebarExpanded;

    val isSidebar: Boolean
        get() = this == SidebarCollapsed || this == SidebarExpanded
}

/** 外壳内容分栏形态（product-design.md §4.1）。 */
enum class ShellPaneLayoutMode {
    CompactSinglePane,
    HingeTwoPane,
    HingeFallbackSinglePane,
    SinglePane,
    SidebarSinglePane,
    CompactThreePane,
    ExpandedThreePane,
}

/** 竖向物理铰链在窗口坐标系内的左右边界（单位：dp）。 */
@Immutable
data class ShellHingeBoundsDp(
    val left: Float,
    val right: Float,
)

/** 底部胶囊水平对齐区间（单位：dp），用于铰链双栏放于列表侧或铰链退单栏放于较宽侧。 */
@Immutable
data class ShellPillAnchor(
    val startDp: Int,
    val widthDp: Int,
)

/** 纯函数 [calculateShellPaneLayout] 的完整输出，供外壳与多栏内容容器共享。 */
@Immutable
data class ShellPaneLayout(
    val layoutMode: ShellPaneLayoutMode,
    val navigationMode: ShellNavigationMode,
    val windowWidthDp: Int,
    val sidebarWidthDp: Int,
    val isSidebarExpanded: Boolean,
    val canToggleSidebarPermanent: Boolean,
    val middlePaneWidthDp: Int,
    val rightPaneWidthDp: Int,
    val hingeBoundsDp: ShellHingeBoundsDp? = null,
    val middlePaneSnapPointsDp: List<Int> = emptyList(),
    val pillAnchor: ShellPillAnchor? = null,
) {
    val isSidebar: Boolean
        get() = navigationMode.isSidebar

    val isThreePane: Boolean
        get() = layoutMode == ShellPaneLayoutMode.CompactThreePane ||
            layoutMode == ShellPaneLayoutMode.ExpandedThreePane

    val isTwoPane: Boolean
        get() = layoutMode == ShellPaneLayoutMode.HingeTwoPane

    val hasDetailPane: Boolean
        get() = rightPaneWidthDp > 0

    val contentWidthDp: Int
        get() = (windowWidthDp - sidebarWidthDp).coerceAtLeast(0)
}

/** 窄屏底部胶囊 4 个一级目的地（product-design.md §3.1、copy.md）。 */
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

/** 宽屏常驻左侧栏（≥ 600dp）的全部导航入口模型（product-design.md §3.2）。 */
@Immutable
sealed interface ShellSidebarEntry {
    data object TodayNew : ShellSidebarEntry
    data object Search : ShellSidebarEntry
    data object Sms : ShellSidebarEntry
    data object Notify : ShellSidebarEntry
    data object FavoritesAndAttention : ShellSidebarEntry
    data object MyThreads : ShellSidebarEntry
    data object MyReplies : ShellSidebarEntry
    data object History : ShellSidebarEntry
    data class Forum(val fid: Int) : ShellSidebarEntry
    data object Settings : ShellSidebarEntry

    val isMineGroupChild: Boolean
        get() = this == FavoritesAndAttention ||
            this == MyThreads ||
            this == MyReplies ||
            this == History
}

/** 供 `rememberSaveable` 保存/恢复 [ShellSidebarEntry] 的轻量 Saver。 */
val ShellSidebarEntrySaver: Saver<ShellSidebarEntry, String> = Saver(
    save = { entry ->
        when (entry) {
            ShellSidebarEntry.TodayNew -> "TodayNew"
            ShellSidebarEntry.Search -> "Search"
            ShellSidebarEntry.Sms -> "Sms"
            ShellSidebarEntry.Notify -> "Notify"
            ShellSidebarEntry.FavoritesAndAttention -> "FavoritesAndAttention"
            ShellSidebarEntry.MyThreads -> "MyThreads"
            ShellSidebarEntry.MyReplies -> "MyReplies"
            ShellSidebarEntry.History -> "History"
            is ShellSidebarEntry.Forum -> "Forum:${entry.fid}"
            ShellSidebarEntry.Settings -> "Settings"
        }
    },
    restore = { raw ->
        when {
            raw == "TodayNew" -> ShellSidebarEntry.TodayNew
            raw == "Search" -> ShellSidebarEntry.Search
            raw == "Sms" -> ShellSidebarEntry.Sms
            raw == "Notify" -> ShellSidebarEntry.Notify
            raw == "FavoritesAndAttention" -> ShellSidebarEntry.FavoritesAndAttention
            raw == "MyThreads" -> ShellSidebarEntry.MyThreads
            raw == "MyReplies" -> ShellSidebarEntry.MyReplies
            raw == "History" -> ShellSidebarEntry.History
            raw == "Settings" -> ShellSidebarEntry.Settings
            raw.startsWith("Forum:") -> raw.removePrefix("Forum:").toIntOrNull()
                ?.let(ShellSidebarEntry::Forum)
                ?: ShellSidebarEntry.TodayNew
            else -> ShellSidebarEntry.TodayNew
        }
    },
)

/** 宽 → 窄映射：将左侧栏入口确定性地映射回底部胶囊 4 个一级目的地（product-design.md §3.2）。 */
fun ShellSidebarEntry.toShellDestination(): ShellDestination = when (this) {
    ShellSidebarEntry.TodayNew,
    is ShellSidebarEntry.Forum,
    -> ShellDestination.Forum

    ShellSidebarEntry.Sms,
    ShellSidebarEntry.Notify,
    -> ShellDestination.Messages

    ShellSidebarEntry.Search -> ShellDestination.Search

    ShellSidebarEntry.FavoritesAndAttention,
    ShellSidebarEntry.MyThreads,
    ShellSidebarEntry.MyReplies,
    ShellSidebarEntry.History,
    ShellSidebarEntry.Settings,
    -> ShellDestination.Mine
}

/**
 * 窄 → 宽映射：将底部胶囊一级目的地确定性地映射回左侧栏入口（product-design.md §3.2）。
 *
 * - 处于 [ShellDestination.Mine] 总菜单未进子页时，优先恢复本次会话最近访问的“我的”子项，未访问过默认 [ShellSidebarEntry.FavoritesAndAttention]。
 */
fun mapDestinationToSidebarEntry(
    destination: ShellDestination,
    activeForumFid: Int? = null,
    activeMessageEntry: ShellSidebarEntry = ShellSidebarEntry.Sms,
    activeMineSubPage: ShellSidebarEntry? = null,
    lastVisitedMineEntry: ShellSidebarEntry? = null,
): ShellSidebarEntry = when (destination) {
    ShellDestination.Forum -> activeForumFid?.let(ShellSidebarEntry::Forum) ?: ShellSidebarEntry.TodayNew
    ShellDestination.Messages -> if (activeMessageEntry == ShellSidebarEntry.Notify) {
        ShellSidebarEntry.Notify
    } else {
        ShellSidebarEntry.Sms
    }
    ShellDestination.Search -> ShellSidebarEntry.Search
    ShellDestination.Mine -> {
        val currentSub = activeMineSubPage?.takeIf { it.isMineGroupChild || it == ShellSidebarEntry.Settings }
        val recentMine = lastVisitedMineEntry?.takeIf { it.isMineGroupChild || it == ShellSidebarEntry.Settings }
        currentSub ?: recentMine ?: ShellSidebarEntry.FavoritesAndAttention
    }
}

/**
 * 纯函数决定导航形态（product-design.md §4.1，按优先级自上而下）：
 * 1. 高度 < 480dp → [ShellNavigationMode.CompactPill]
 * 2. 竖向物理铰链分隔 → [ShellNavigationMode.CompactPill]
 * 3. 宽度 < 600dp → [ShellNavigationMode.BottomPill]
 * 4. 宽度 600–839dp → [ShellNavigationMode.SidebarCollapsed]
 * 5. 宽度 ≥ 840dp → [ShellNavigationMode.SidebarExpanded]
 */
fun shellNavigationMode(
    widthDp: Float,
    heightDp: Float,
    hingeSeparating: Boolean = false,
): ShellNavigationMode = when {
    heightDp < SHELL_COMPACT_HEIGHT_BREAKPOINT_DP -> ShellNavigationMode.CompactPill
    hingeSeparating -> ShellNavigationMode.CompactPill
    widthDp < SHELL_SIDEBAR_COLLAPSED_WIDTH_BREAKPOINT_DP -> ShellNavigationMode.BottomPill
    widthDp < SHELL_SIDEBAR_EXPANDED_WIDTH_BREAKPOINT_DP -> ShellNavigationMode.SidebarCollapsed
    else -> ShellNavigationMode.SidebarExpanded
}

/**
 * 纯函数计算自适应三栏 / 双栏 / 单栏布局参数（product-design.md §4.1、R-D3pane §6.3）。
 *
 * 先按浮点窗口尺寸完成 `< 480f / 铰链 / < 600f / < 680f / < 840f` 分档判定，再取整 `w = widthDp.roundToInt()`，
 * 随后按整数顺序计算 `L → S = w - L → M → R = S - M`，确保非整数真机窗口宽同样严格满足 `L + M + R == w`。
 */
fun calculateShellPaneLayout(
    widthDp: Float,
    heightDp: Float,
    hingeBoundsDp: ShellHingeBoundsDp? = null,
    leftCollapsedPref: Boolean = false,
    hasDetailContent: Boolean = false,
): ShellPaneLayout {
    val w = widthDp.roundToInt().coerceAtLeast(0)

    // 1. 矮屏（高 < 480dp）
    if (heightDp < SHELL_COMPACT_HEIGHT_BREAKPOINT_DP) {
        return ShellPaneLayout(
            layoutMode = ShellPaneLayoutMode.CompactSinglePane,
            navigationMode = ShellNavigationMode.CompactPill,
            windowWidthDp = w,
            sidebarWidthDp = 0,
            isSidebarExpanded = false,
            canToggleSidebarPermanent = false,
            middlePaneWidthDp = w,
            rightPaneWidthDp = 0,
            pillAnchor = ShellPillAnchor(startDp = 0, widthDp = w),
        )
    }

    // 2. 竖向物理铰链分隔
    if (hingeBoundsDp != null) {
        val leftFloat = hingeBoundsDp.left.coerceIn(0f, widthDp.coerceAtLeast(0f))
        val rightFloat = (widthDp - hingeBoundsDp.right).coerceAtLeast(0f)
        val hingeLeftInt = hingeBoundsDp.left.roundToInt().coerceIn(0, w)
        val hingeRightInt = hingeBoundsDp.right.roundToInt().coerceIn(hingeLeftInt, w)
        val leftW = hingeLeftInt
        val rightW = (w - hingeRightInt).coerceAtLeast(0)

        if (leftFloat >= HINGE_LEFT_MIN_WIDTH_DP && rightFloat >= HINGE_RIGHT_MIN_WIDTH_DP &&
            leftW >= HINGE_LEFT_MIN_WIDTH_DP && rightW >= HINGE_RIGHT_MIN_WIDTH_DP
        ) {
            val snapPoints = if (hasDetailContent) listOf(0, leftW) else listOf(leftW)
            return ShellPaneLayout(
                layoutMode = ShellPaneLayoutMode.HingeTwoPane,
                navigationMode = ShellNavigationMode.CompactPill,
                windowWidthDp = w,
                sidebarWidthDp = 0,
                isSidebarExpanded = false,
                canToggleSidebarPermanent = false,
                middlePaneWidthDp = leftW,
                rightPaneWidthDp = rightW,
                hingeBoundsDp = hingeBoundsDp,
                middlePaneSnapPointsDp = snapPoints,
                pillAnchor = ShellPillAnchor(startDp = 0, widthDp = leftW),
            )
        }

        val anchor = when {
            leftFloat >= rightFloat && leftFloat >= HINGE_LEFT_MIN_WIDTH_DP && leftW >= HINGE_LEFT_MIN_WIDTH_DP ->
                ShellPillAnchor(startDp = 0, widthDp = leftW)
            rightFloat > leftFloat && rightFloat >= HINGE_LEFT_MIN_WIDTH_DP && rightW >= HINGE_LEFT_MIN_WIDTH_DP ->
                ShellPillAnchor(startDp = hingeRightInt, widthDp = rightW)
            else -> ShellPillAnchor(startDp = 0, widthDp = w)
        }
        return ShellPaneLayout(
            layoutMode = ShellPaneLayoutMode.HingeFallbackSinglePane,
            navigationMode = ShellNavigationMode.CompactPill,
            windowWidthDp = w,
            sidebarWidthDp = 0,
            isSidebarExpanded = false,
            canToggleSidebarPermanent = false,
            middlePaneWidthDp = w,
            rightPaneWidthDp = 0,
            hingeBoundsDp = hingeBoundsDp,
            pillAnchor = anchor,
        )
    }

    // 3. 窄屏单栏（宽 < 600dp）
    if (widthDp < SHELL_SIDEBAR_COLLAPSED_WIDTH_BREAKPOINT_DP) {
        return ShellPaneLayout(
            layoutMode = ShellPaneLayoutMode.SinglePane,
            navigationMode = ShellNavigationMode.BottomPill,
            windowWidthDp = w,
            sidebarWidthDp = 0,
            isSidebarExpanded = false,
            canToggleSidebarPermanent = false,
            middlePaneWidthDp = w,
            rightPaneWidthDp = 0,
            pillAnchor = ShellPillAnchor(startDp = 0, widthDp = w),
        )
    }

    // 4. 图标轨单栏（600–679dp）
    if (widthDp < SHELL_THREE_PANE_WIDTH_BREAKPOINT_DP) {
        val left = SHELL_SIDEBAR_COLLAPSED_WIDTH_DP
        val remaining = (w - left).coerceAtLeast(0)
        return ShellPaneLayout(
            layoutMode = ShellPaneLayoutMode.SidebarSinglePane,
            navigationMode = ShellNavigationMode.SidebarCollapsed,
            windowWidthDp = w,
            sidebarWidthDp = left,
            isSidebarExpanded = false,
            canToggleSidebarPermanent = false,
            middlePaneWidthDp = remaining,
            rightPaneWidthDp = 0,
        )
    }

    // 5. 紧凑三栏（680–839dp）与 6. 完整三栏（≥ 840dp）
    val isExpandedBand = widthDp >= SHELL_SIDEBAR_EXPANDED_WIDTH_BREAKPOINT_DP
    val isSidebarExpanded = isExpandedBand && !leftCollapsedPref
    val left = if (isSidebarExpanded) {
        (0.20f * w).roundToInt().coerceIn(
            SHELL_SIDEBAR_EXPANDED_MIN_WIDTH_DP,
            SHELL_SIDEBAR_EXPANDED_MAX_WIDTH_DP,
        )
    } else {
        SHELL_SIDEBAR_COLLAPSED_WIDTH_DP
    }
    val remaining = (w - left).coerceAtLeast(0)
    val middle = (0.40f * remaining).roundToInt().coerceIn(
        SHELL_MIDDLE_PANE_MIN_WIDTH_DP,
        SHELL_MIDDLE_PANE_MAX_WIDTH_DP,
    )
    val right = remaining - middle
    val balanced = minOf(
        (0.50f * remaining).roundToInt(),
        remaining - SHELL_RIGHT_PANE_MIN_WIDTH_DP,
        SHELL_MIDDLE_PANE_BALANCED_MAX_WIDTH_DP,
    )
    val snapPoints = buildList {
        if (hasDetailContent) add(0)
        add(middle)
        if (balanced - middle >= SHELL_MIDDLE_PANE_SNAP_MERGE_THRESHOLD_DP) {
            add(balanced)
        }
    }

    return ShellPaneLayout(
        layoutMode = if (isExpandedBand) {
            ShellPaneLayoutMode.ExpandedThreePane
        } else {
            ShellPaneLayoutMode.CompactThreePane
        },
        navigationMode = if (isExpandedBand) {
            ShellNavigationMode.SidebarExpanded
        } else {
            ShellNavigationMode.SidebarCollapsed
        },
        windowWidthDp = w,
        sidebarWidthDp = left,
        isSidebarExpanded = isSidebarExpanded,
        canToggleSidebarPermanent = isExpandedBand,
        middlePaneWidthDp = middle,
        rightPaneWidthDp = right,
        middlePaneSnapPointsDp = snapPoints,
    )
}

/** 窗口宽度小于 360dp 时底部胶囊只给选中项显示文字，其余仅显示图标（product-design.md §4.1）。 */
fun showLabelForUnselected(widthDp: Float): Boolean =
    widthDp >= SHELL_UNSELECTED_LABEL_MIN_WIDTH_DP

/** 外壳与导航组件的语义测试标签。 */
object HiShellTestTags {
    const val BottomPill: String = "hi_shell_bottom_pill"
    const val CompactPill: String = "hi_shell_compact_pill"
    const val NavigationSidebar: String = "hi_shell_navigation_sidebar"
    const val SidebarModalDrawer: String = "hi_shell_sidebar_modal_drawer"
    const val SidebarModalScrim: String = "hi_shell_sidebar_modal_scrim"
    const val SidebarToggleButton: String = "hi_shell_sidebar_toggle_button"
    const val SidebarAccountRow: String = "hi_shell_sidebar_account_row"
    const val SidebarMineGroup: String = "hi_shell_sidebar_mine_group"
    const val SidebarManageForumsButton: String = "hi_shell_sidebar_manage_forums_button"
    const val SidebarList: String = "hi_shell_sidebar_list"
    const val SidebarTopBar: String = "hi_shell_sidebar_top_bar"
    const val PaneDivider: String = "hi_shell_pane_divider"
    const val SelectionIndicator: String = "hi_shell_selection_indicator"
    const val ContentContainer: String = "hi_shell_content_container"

    fun navItem(destination: ShellDestination): String = "hi_shell_nav_item_${destination.name}"

    fun sidebarEntry(entry: ShellSidebarEntry): String = when (entry) {
        ShellSidebarEntry.TodayNew -> "hi_shell_sidebar_entry_TodayNew"
        ShellSidebarEntry.Search -> "hi_shell_sidebar_entry_Search"
        ShellSidebarEntry.Sms -> "hi_shell_sidebar_entry_Sms"
        ShellSidebarEntry.Notify -> "hi_shell_sidebar_entry_Notify"
        ShellSidebarEntry.FavoritesAndAttention -> "hi_shell_sidebar_entry_FavoritesAndAttention"
        ShellSidebarEntry.MyThreads -> "hi_shell_sidebar_entry_MyThreads"
        ShellSidebarEntry.MyReplies -> "hi_shell_sidebar_entry_MyReplies"
        ShellSidebarEntry.History -> "hi_shell_sidebar_entry_History"
        is ShellSidebarEntry.Forum -> "hi_shell_sidebar_entry_Forum_${entry.fid}"
        ShellSidebarEntry.Settings -> "hi_shell_sidebar_entry_Settings"
    }
}
