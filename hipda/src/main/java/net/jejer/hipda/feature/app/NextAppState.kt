package net.jejer.hipda.feature.app

import androidx.compose.runtime.Immutable
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellSidebarEntry

/** 新版应用单一导航与阅读状态源（供单栏、图标轨、铰链双栏与三栏共享，无合成跨形态路由）。 */
@Immutable
data class NextAppState(
    val selectedDestination: ShellDestination = ShellDestination.Forum,
    val selectedSidebarEntry: ShellSidebarEntry = ShellSidebarEntry.Forum(DEFAULT_FORUM_FID),
    val activeForumSource: ForumListSource = ForumListSource.Forum(DEFAULT_FORUM_FID),
    val activeMessageEntry: ShellSidebarEntry = ShellSidebarEntry.Sms,
    val lastVisitedMineEntry: ShellSidebarEntry? = null,
    val selectedThread: ThreadNavArgs? = null,
    val loginOverlayMode: LoginOverlayMode? = null,
) {
    companion object { const val DEFAULT_FORUM_FID: Int = 2 }
}
