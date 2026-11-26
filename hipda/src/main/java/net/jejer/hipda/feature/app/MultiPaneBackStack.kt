package net.jejer.hipda.feature.app

import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.ui.shell.SHELL_MIDDLE_PANE_BALANCED_MAX_WIDTH_DP
import net.jejer.hipda.ui.shell.SHELL_MIDDLE_PANE_SNAP_MERGE_THRESHOLD_DP
import net.jejer.hipda.ui.shell.SHELL_RIGHT_PANE_MIN_WIDTH_DP
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellPaneLayout
import net.jejer.hipda.ui.shell.ShellPaneLayoutMode
import net.jejer.hipda.ui.shell.ShellSidebarEntry

/** 铰链双栏左栏角色（product-design.md §4.2–§4.3，闭合 B4-E3 ② 非论坛入口晋升与回退）。 */
enum class HingeLeftPaneRole { Forum, Messages, Search, MineMenu, PromotedMineSubList, SettingsGroups }

/** 铰链双栏右栏角色。 */
enum class HingeRightPaneRole { ThreadDetail, SmsConversation, DetailDrilldown, MineSubList, SettingsDetail, EmptyPlaceholder }

@Immutable
data class HingeTwoPaneRoles(
    val left: HingeLeftPaneRole,
    val right: HingeRightPaneRole,
    val mineSubEntry: ShellSidebarEntry? = null,
)

/** 纯函数解析半折铰链双栏左右栏归属：非论坛入口（`History`/`MyThreads`/`MyReplies`/`FavoritesAndAttention`/`Notify`/`Sms`/`Settings`）均严格承接原来源。 */
fun resolveHingeTwoPaneRoles(state: NextAppState): HingeTwoPaneRoles {
    val hasDrilldown = state.detailDrilldownStack.isNotEmpty()
    val hasThread = state.selectedThread != null
    val hasSms = state.selectedSmsConversation != null
    val detailOrEmpty = when {
        hasDrilldown -> HingeRightPaneRole.DetailDrilldown
        hasThread -> HingeRightPaneRole.ThreadDetail
        hasSms -> HingeRightPaneRole.SmsConversation
        else -> HingeRightPaneRole.EmptyPlaceholder
    }
    return when (state.selectedDestination) {
        ShellDestination.Forum -> HingeTwoPaneRoles(HingeLeftPaneRole.Forum, detailOrEmpty)
        ShellDestination.Messages -> HingeTwoPaneRoles(HingeLeftPaneRole.Messages, detailOrEmpty)
        ShellDestination.Search -> HingeTwoPaneRoles(HingeLeftPaneRole.Search, detailOrEmpty)
        ShellDestination.Mine -> when {
            state.selectedSidebarEntry == ShellSidebarEntry.Settings && !state.hingeShowingMineMenu -> HingeTwoPaneRoles(
                HingeLeftPaneRole.SettingsGroups,
                if (hasDrilldown) HingeRightPaneRole.DetailDrilldown else HingeRightPaneRole.SettingsDetail,
                ShellSidebarEntry.Settings,
            )
            state.selectedSidebarEntry.isMineGroupChild && (hasThread || hasDrilldown) -> HingeTwoPaneRoles(
                HingeLeftPaneRole.PromotedMineSubList,
                detailOrEmpty,
                state.selectedSidebarEntry,
            )
            else -> {
                val sub = state.activeMineSubPage?.takeIf { it.isMineGroupChild }
                    ?: state.selectedSidebarEntry.takeIf { it.isMineGroupChild && !state.hingeShowingMineMenu }
                HingeTwoPaneRoles(
                    HingeLeftPaneRole.MineMenu,
                    if (sub != null) HingeRightPaneRole.MineSubList else HingeRightPaneRole.EmptyPlaceholder,
                    sub,
                )
            }
        }
    }
}

/** 多栏/单栏返回键 5 级优先动作（product-design.md §4.2）。 */
sealed interface MultiPaneBackAction {
    data object CloseLoginOverlay : MultiPaneBackAction
    data object ExitFullScreen : MultiPaneBackAction
    data object PopDetailDrilldown : MultiPaneBackAction
    data object DemoteHingeMineSubList : MultiPaneBackAction
    data object CloseHingeSettingsToMine : MultiPaneBackAction
    data object CloseDetailThread : MultiPaneBackAction
    data object CloseSmsConversation : MultiPaneBackAction
    data object CloseSinglePaneSettingsDetail : MultiPaneBackAction
    data object CloseMineSubPageToMenu : MultiPaneBackAction
    data object ReturnToDefaultForum : MultiPaneBackAction
    data object PassToSystem : MultiPaneBackAction
}

/** 纯函数按 product-design.md §4.2 五级优先级判定本次返回键动作。 */
fun resolveMultiPaneBackAction(state: NextAppState, layoutMode: ShellPaneLayoutMode): MultiPaneBackAction {
    if (state.loginOverlayMode != null) return MultiPaneBackAction.CloseLoginOverlay
    if (state.isFullScreenReading) return MultiPaneBackAction.ExitFullScreen
    if (state.detailDrilldownStack.isNotEmpty()) return MultiPaneBackAction.PopDetailDrilldown
    when (layoutMode) {
        ShellPaneLayoutMode.HingeTwoPane -> {
            if (state.selectedThread != null) {
                return if (state.selectedSidebarEntry.isMineGroupChild) MultiPaneBackAction.DemoteHingeMineSubList else MultiPaneBackAction.CloseDetailThread
            }
            if (state.selectedSmsConversation != null) return MultiPaneBackAction.CloseSmsConversation
            if (state.selectedSidebarEntry == ShellSidebarEntry.Settings && !state.hingeShowingMineMenu) return MultiPaneBackAction.CloseHingeSettingsToMine
        }
        ShellPaneLayoutMode.CompactThreePane, ShellPaneLayoutMode.ExpandedThreePane -> {
            if (state.selectedThread != null) return MultiPaneBackAction.CloseDetailThread
            if (state.selectedSmsConversation != null) return MultiPaneBackAction.CloseSmsConversation
        }
        ShellPaneLayoutMode.SidebarSinglePane, ShellPaneLayoutMode.SinglePane,
        ShellPaneLayoutMode.CompactSinglePane, ShellPaneLayoutMode.HingeFallbackSinglePane,
        -> {
            if (state.selectedThread != null) return MultiPaneBackAction.CloseDetailThread
            if (state.selectedSmsConversation != null) return MultiPaneBackAction.CloseSmsConversation
            if (state.selectedSidebarEntry == ShellSidebarEntry.Settings && state.isSettingsDetailOpen) return MultiPaneBackAction.CloseSinglePaneSettingsDetail
            if (layoutMode != ShellPaneLayoutMode.SidebarSinglePane && state.selectedDestination == ShellDestination.Mine && state.activeMineSubPage != null) {
                return MultiPaneBackAction.CloseMineSubPageToMenu
            }
        }
    }
    val onDefaultHome = state.selectedDestination == ShellDestination.Forum &&
        state.selectedSidebarEntry == ShellSidebarEntry.Forum(state.defaultForumFid) &&
        state.activeForumSource == ForumListSource.Forum(state.defaultForumFid)
    return if (onDefaultHome) MultiPaneBackAction.PassToSystem else MultiPaneBackAction.ReturnToDefaultForum
}

/** 纯函数执行 [MultiPaneBackAction] 并返回新状态。 */
fun applyMultiPaneBackAction(state: NextAppState, action: MultiPaneBackAction): NextAppState = when (action) {
    MultiPaneBackAction.CloseLoginOverlay -> state.copy(loginOverlayMode = null)
    MultiPaneBackAction.ExitFullScreen -> state.copy(isFullScreenReading = false)
    MultiPaneBackAction.PopDetailDrilldown -> state.copy(detailDrilldownStack = state.detailDrilldownStack.dropLast(1))
    MultiPaneBackAction.DemoteHingeMineSubList -> state.copy(
        selectedThread = null,
        selectedSmsConversation = null,
        detailDrilldownStack = emptyList(),
        isFullScreenReading = false,
        activeMineSubPage = state.selectedSidebarEntry,
        hingeShowingMineMenu = true,
    )
    MultiPaneBackAction.CloseHingeSettingsToMine -> state.copy(
        hingeShowingMineMenu = true,
        activeMineSubPage = null,
        isSettingsDetailOpen = false,
        isFullScreenReading = false,
    )
    MultiPaneBackAction.CloseDetailThread -> state.copy(selectedThread = null, detailDrilldownStack = emptyList(), isFullScreenReading = false)
    MultiPaneBackAction.CloseSmsConversation -> state.copy(selectedSmsConversation = null, detailDrilldownStack = emptyList(), isFullScreenReading = false)
    MultiPaneBackAction.CloseSinglePaneSettingsDetail -> state.copy(isSettingsDetailOpen = false)
    MultiPaneBackAction.CloseMineSubPageToMenu -> state.copy(activeMineSubPage = null, isSettingsDetailOpen = false, hingeShowingMineMenu = true)
    MultiPaneBackAction.ReturnToDefaultForum -> state.copy(
        selectedDestination = ShellDestination.Forum,
        selectedSidebarEntry = ShellSidebarEntry.Forum(state.defaultForumFid),
        activeForumSource = ForumListSource.Forum(state.defaultForumFid),
        activeMineSubPage = null,
        isSettingsDetailOpen = false,
        hingeShowingMineMenu = false,
        selectedThread = null,
        selectedSmsConversation = null,
        detailDrilldownStack = emptyList(),
        isFullScreenReading = false,
    )
    MultiPaneBackAction.PassToSystem -> state
}

/**
 * 纯函数计算三栏中右分隔线的合法锚点表（按宽度升序）。
 * B4-E3 ① 硬底线：[hasDetailContent] 为 `false` 时锚点集合严格不含 [PaneSplit.COLLAPSED]（`0dp`）。
 */
fun resolvePaneSplitAnchors(paneLayout: ShellPaneLayout, hasDetailContent: Boolean): LinkedHashMap<PaneSplit, Int> {
    val anchors = LinkedHashMap<PaneSplit, Int>(3)
    if (!paneLayout.isThreePane) return anchors
    val defaultDp = paneLayout.middlePaneWidthDp
    val remaining = paneLayout.contentWidthDp
    val balancedDp = minOf((0.50f * remaining).roundToInt(), remaining - SHELL_RIGHT_PANE_MIN_WIDTH_DP, SHELL_MIDDLE_PANE_BALANCED_MAX_WIDTH_DP)
    if (hasDetailContent) anchors[PaneSplit.COLLAPSED] = 0
    anchors[PaneSplit.DEFAULT] = defaultDp
    if (balancedDp - defaultDp >= SHELL_MIDDLE_PANE_SNAP_MERGE_THRESHOLD_DP) anchors[PaneSplit.BALANCED] = balancedDp
    return anchors
}

/** 纯函数将持久化或记忆的 [PaneSplit] 约束到当前窗口合法锚点（空右栏不可恢复 `COLLAPSED`）。 */
fun resolveEffectivePaneSplit(persistedSplit: PaneSplit, anchors: Map<PaneSplit, Int>): PaneSplit = when {
    persistedSplit in anchors -> persistedSplit
    PaneSplit.DEFAULT in anchors -> PaneSplit.DEFAULT
    else -> anchors.keys.firstOrNull() ?: PaneSplit.DEFAULT
}
