package net.jejer.hipda.feature.app

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.listSaver
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import net.jejer.hipda.ui.shell.ShellSidebarEntrySaver

/** 源码兼容别名：正式枚举位于 `feature/common/ReadingNavContracts.kt`。 */
typealias SettingsGroup = net.jejer.hipda.feature.common.SettingsGroup

/** 右栏独立下钻栈条目（product-design.md §4.2：帖子 → 用户资料 → 短消息对话）。 */
@Immutable
sealed interface DetailDrilldownEntry {
    @Immutable
    data class UserProfile(val uid: String, val username: String) : DetailDrilldownEntry

    @Immutable
    data class SmsConversation(val args: SmsConversationArgs) : DetailDrilldownEntry
}

/** 新版应用单一导航与阅读状态源（供单栏、图标轨、铰链双栏与三栏共享，无合成跨形态路由）。 */
@Immutable
data class NextAppState(
    val selectedDestination: ShellDestination = ShellDestination.Forum,
    val selectedSidebarEntry: ShellSidebarEntry = ShellSidebarEntry.Forum(DEFAULT_FORUM_FID),
    val activeForumSource: ForumListSource = ForumListSource.Forum(DEFAULT_FORUM_FID),
    val activeMessageEntry: ShellSidebarEntry = ShellSidebarEntry.Sms,
    val lastVisitedMineEntry: ShellSidebarEntry? = null,
    val activeMineSubPage: ShellSidebarEntry? = null,
    val hingeShowingMineMenu: Boolean = false,
    val selectedThread: ThreadNavArgs? = null,
    val selectedSmsConversation: SmsConversationArgs? = null,
    val selectedSettingsGroup: SettingsGroup = SettingsGroup.Appearance,
    val isSettingsDetailOpen: Boolean = false,
    val detailDrilldownStack: List<DetailDrilldownEntry> = emptyList(),
    val isFullScreenReading: Boolean = false,
    val defaultForumFid: Int = DEFAULT_FORUM_FID,
    val loginOverlayMode: LoginOverlayMode? = null,
) {
    /** 当前右栏是否存在实际内容（非空占位）；决定中右分隔线是否允许 `列表收起`（闭合 B4-E3 ①）。 */
    val hasDetailContent: Boolean
        get() = selectedThread != null ||
            selectedSmsConversation != null ||
            detailDrilldownStack.isNotEmpty() ||
            (selectedSidebarEntry == ShellSidebarEntry.Settings && !hingeShowingMineMenu)

    /** 窄屏单栏当前是否位于二级页面（按 product-design.md §3.1 L38 退场底部导航胶囊）。 */
    val isSinglePaneSecondary: Boolean
        get() = selectedThread != null ||
            selectedSmsConversation != null ||
            detailDrilldownStack.isNotEmpty() ||
            (selectedDestination == ShellDestination.Mine && activeMineSubPage != null) ||
            (selectedSidebarEntry == ShellSidebarEntry.Settings && isSettingsDetailOpen)

    /** 收集当前活跃的帖子 `tid`，供 `ViewModelStore` 精准清理。 */
    fun activeThreadTids(): Set<String> = selectedThread?.tid?.let(::setOf) ?: emptySet()

    companion object {
        const val DEFAULT_FORUM_FID: Int = 2
    }
}

private val AllowAllScope = SaverScope { true }
private fun saveEntry(e: ShellSidebarEntry?): String? = e?.let { with(ShellSidebarEntrySaver) { AllowAllScope.save(it) } }
private fun restoreEntry(raw: Any?): ShellSidebarEntry? = (raw as? String)?.let(ShellSidebarEntrySaver::restore)

/** 供 `rememberSaveable` 与 `StateRestorationTester` 模拟进程回收无损恢复 [NextAppState] 的扁平 Saver。 */
val NextAppStateSaver: Saver<NextAppState, Any> = listSaver(
    save = { s ->
        val t = s.selectedThread
        val sms = s.selectedSmsConversation
        listOf(
            s.selectedDestination.name,
            saveEntry(s.selectedSidebarEntry),
            if (s.activeForumSource is ForumListSource.Forum) s.activeForumSource.fid else -1,
            saveEntry(s.activeMessageEntry),
            saveEntry(s.lastVisitedMineEntry),
            saveEntry(s.activeMineSubPage),
            s.hingeShowingMineMenu,
            s.isFullScreenReading,
            s.defaultForumFid,
            t?.tid, t?.title, t?.fid, t?.forumName, t?.author, t?.targetPid,
            s.detailDrilldownStack.flatMap { e ->
                when (e) {
                    is DetailDrilldownEntry.UserProfile -> listOf("u", e.uid, e.username)
                    is DetailDrilldownEntry.SmsConversation -> listOf("s", e.args.uid, e.args.username)
                }
            },
            sms?.uid,
            sms?.username,
            s.selectedSettingsGroup.name,
            s.isSettingsDetailOpen,
        )
    },
    restore = { r ->
        val fid = (r.getOrNull(2) as? Int) ?: NextAppState.DEFAULT_FORUM_FID
        val tid = r.getOrNull(9) as? String
        val smsUid = r.getOrNull(16) as? String
        @Suppress("UNCHECKED_CAST")
        val rawDrilldown = (r.getOrNull(15) as? List<String>).orEmpty()
        val drilldown = if (r.size < 20) {
            rawDrilldown.chunked(2).mapNotNull { p ->
                p.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { DetailDrilldownEntry.UserProfile(it, p.getOrNull(1).orEmpty()) }
            }
        } else {
            rawDrilldown.chunked(3).mapNotNull { p ->
                val uid = p.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val name = p.getOrNull(2).orEmpty()
                when (p.getOrNull(0)) {
                    "u" -> DetailDrilldownEntry.UserProfile(uid, name)
                    "s" -> DetailDrilldownEntry.SmsConversation(SmsConversationArgs(uid, name))
                    else -> null
                }
            }
        }
        NextAppState(
            selectedDestination = (r.getOrNull(0) as? String)?.let { runCatching { ShellDestination.valueOf(it) }.getOrNull() } ?: ShellDestination.Forum,
            selectedSidebarEntry = restoreEntry(r.getOrNull(1)) ?: ShellSidebarEntry.Forum(NextAppState.DEFAULT_FORUM_FID),
            activeForumSource = if (fid < 0) ForumListSource.TodayNew else ForumListSource.Forum(fid),
            activeMessageEntry = restoreEntry(r.getOrNull(3))?.takeIf { it == ShellSidebarEntry.Sms || it == ShellSidebarEntry.Notify } ?: ShellSidebarEntry.Sms,
            lastVisitedMineEntry = restoreEntry(r.getOrNull(4)),
            activeMineSubPage = restoreEntry(r.getOrNull(5)),
            hingeShowingMineMenu = (r.getOrNull(6) as? Boolean) ?: false,
            isFullScreenReading = (r.getOrNull(7) as? Boolean) ?: false,
            defaultForumFid = (r.getOrNull(8) as? Int) ?: NextAppState.DEFAULT_FORUM_FID,
            selectedThread = tid?.let {
                ThreadNavArgs(it, (r.getOrNull(10) as? String).orEmpty(), (r.getOrNull(11) as? Int) ?: 0, r.getOrNull(12) as? String, r.getOrNull(13) as? String, r.getOrNull(14) as? String)
            },
            selectedSmsConversation = smsUid?.let { SmsConversationArgs(it, (r.getOrNull(17) as? String).orEmpty()) },
            selectedSettingsGroup = (r.getOrNull(18) as? String)?.let { runCatching { SettingsGroup.valueOf(it) }.getOrNull() } ?: SettingsGroup.Appearance,
            isSettingsDetailOpen = (r.getOrNull(19) as? Boolean) ?: false,
            detailDrilldownStack = drilldown,
        )
    },
)
