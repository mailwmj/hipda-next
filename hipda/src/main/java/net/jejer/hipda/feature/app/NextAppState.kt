package net.jejer.hipda.feature.app

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.listSaver
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import net.jejer.hipda.ui.shell.ShellSidebarEntrySaver

/** 设置 5 个顶层分组（docs/design/copy.md §设置、product-design.md §7.10）。 */
enum class SettingsGroup(@param:StringRes val labelRes: Int) {
    Appearance(R.string.app_settings_group_appearance),
    ForumAndPost(R.string.app_settings_group_forum),
    Notification(R.string.app_settings_group_notification),
    NetworkAndCache(R.string.app_settings_group_network),
    About(R.string.app_settings_group_about),
}

/** 右栏独立下钻栈条目（product-design.md §4.2：帖子 → 用户资料 / 站内链接帖子）。 */
@Immutable
sealed interface DetailDrilldownEntry {
    @Immutable
    data class UserProfile(val uid: String, val username: String) : DetailDrilldownEntry

    @Immutable
    data class LinkedThread(val args: ThreadNavArgs) : DetailDrilldownEntry
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
    val selectedSettingsGroup: SettingsGroup = SettingsGroup.Appearance,
    val selectedThread: ThreadNavArgs? = null,
    val detailDrilldownStack: List<DetailDrilldownEntry> = emptyList(),
    val isFullScreenReading: Boolean = false,
    val defaultForumFid: Int = DEFAULT_FORUM_FID,
    val loginOverlayMode: LoginOverlayMode? = null,
) {
    /** 当前右栏是否存在实际内容（非空占位）；决定中右分隔线是否允许 `列表收起`（闭合 B4-E3 ①）。 */
    val hasDetailContent: Boolean
        get() = selectedThread != null ||
            detailDrilldownStack.isNotEmpty() ||
            (selectedSidebarEntry == ShellSidebarEntry.Settings && !hingeShowingMineMenu)

    /** 窄屏单栏当前是否位于二级页面（按 product-design.md §3.1 L38 退场底部导航胶囊）。 */
    val isSinglePaneSecondary: Boolean
        get() = selectedThread != null ||
            detailDrilldownStack.isNotEmpty() ||
            (selectedDestination == ShellDestination.Mine && activeMineSubPage != null)

    /** 收集当前活跃的全部帖子 `tid`（含右栏根帖与下钻站内帖），供 `ViewModelStore` 精准清理。 */
    fun activeThreadTids(): Set<String> = buildSet {
        selectedThread?.tid?.let(::add)
        detailDrilldownStack.forEach { if (it is DetailDrilldownEntry.LinkedThread) add(it.args.tid) }
    }

    companion object {
        const val DEFAULT_FORUM_FID: Int = 2
    }
}

private const val FIELD_SEP: String = "\u001F"
private val AlwaysAllowSaverScope = SaverScope { true }

/** 供 `rememberSaveable` 与 `StateRestorationTester` 模拟进程回收无损恢复 [NextAppState] 的 Saver。 */
val NextAppStateSaver: Saver<NextAppState, Any> = listSaver(
    save = { s ->
        listOf(
            s.selectedDestination.name,
            with(ShellSidebarEntrySaver) { AlwaysAllowSaverScope.save(s.selectedSidebarEntry) ?: "TodayNew" },
            if (s.activeForumSource is ForumListSource.Forum) "F:${s.activeForumSource.fid}" else "T",
            with(ShellSidebarEntrySaver) { AlwaysAllowSaverScope.save(s.activeMessageEntry) ?: "Sms" },
            s.lastVisitedMineEntry?.let { with(ShellSidebarEntrySaver) { AlwaysAllowSaverScope.save(it) } },
            s.activeMineSubPage?.let { with(ShellSidebarEntrySaver) { AlwaysAllowSaverScope.save(it) } },
            s.hingeShowingMineMenu,
            s.selectedSettingsGroup.name,
            s.selectedThread?.let(::encodeThreadNavArgs),
            s.detailDrilldownStack.map { entry ->
                when (entry) {
                    is DetailDrilldownEntry.UserProfile -> "U$FIELD_SEP${entry.uid}$FIELD_SEP${entry.username}"
                    is DetailDrilldownEntry.LinkedThread -> "T$FIELD_SEP${encodeThreadNavArgs(entry.args)}"
                }
            },
            s.isFullScreenReading,
            s.defaultForumFid,
        )
    },
    restore = { raw ->
        val defaultForum = ForumListSource.Forum(NextAppState.DEFAULT_FORUM_FID)
        @Suppress("UNCHECKED_CAST")
        val stackRaw = (raw.getOrNull(9) as? List<String>).orEmpty()
        NextAppState(
            selectedDestination = (raw.getOrNull(0) as? String)?.let { runCatching { ShellDestination.valueOf(it) }.getOrNull() } ?: ShellDestination.Forum,
            selectedSidebarEntry = (raw.getOrNull(1) as? String)?.let(ShellSidebarEntrySaver::restore) ?: ShellSidebarEntry.Forum(NextAppState.DEFAULT_FORUM_FID),
            activeForumSource = when (val src = raw.getOrNull(2) as? String) {
                "T" -> ForumListSource.TodayNew
                else -> src?.removePrefix("F:")?.toIntOrNull()?.let(ForumListSource::Forum) ?: defaultForum
            },
            activeMessageEntry = (raw.getOrNull(3) as? String)?.let(ShellSidebarEntrySaver::restore)
                ?.takeIf { it == ShellSidebarEntry.Sms || it == ShellSidebarEntry.Notify } ?: ShellSidebarEntry.Sms,
            lastVisitedMineEntry = (raw.getOrNull(4) as? String)?.let(ShellSidebarEntrySaver::restore),
            activeMineSubPage = (raw.getOrNull(5) as? String)?.let(ShellSidebarEntrySaver::restore),
            hingeShowingMineMenu = (raw.getOrNull(6) as? Boolean) ?: false,
            selectedSettingsGroup = (raw.getOrNull(7) as? String)?.let { runCatching { SettingsGroup.valueOf(it) }.getOrNull() } ?: SettingsGroup.Appearance,
            selectedThread = (raw.getOrNull(8) as? String)?.let(::decodeThreadNavArgs),
            detailDrilldownStack = stackRaw.mapNotNull(::decodeDrilldownEntry),
            isFullScreenReading = (raw.getOrNull(10) as? Boolean) ?: false,
            defaultForumFid = (raw.getOrNull(11) as? Int) ?: NextAppState.DEFAULT_FORUM_FID,
        )
    },
)

private fun encodeThreadNavArgs(a: ThreadNavArgs): String =
    listOf(a.tid, a.title, a.fid.toString(), a.forumName.orEmpty(), a.author.orEmpty(), a.targetPid.orEmpty()).joinToString(FIELD_SEP)

private fun decodeThreadNavArgs(raw: String): ThreadNavArgs? {
    val p = raw.split(FIELD_SEP)
    val tid = p.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return null
    return ThreadNavArgs(
        tid = tid,
        title = p.getOrNull(1).orEmpty(),
        fid = p.getOrNull(2)?.toIntOrNull() ?: 0,
        forumName = p.getOrNull(3)?.takeIf { it.isNotEmpty() },
        author = p.getOrNull(4)?.takeIf { it.isNotEmpty() },
        targetPid = p.getOrNull(5)?.takeIf { it.isNotEmpty() },
    )
}

private fun decodeDrilldownEntry(raw: String): DetailDrilldownEntry? = when {
    raw.startsWith("U$FIELD_SEP") -> {
        val p = raw.removePrefix("U$FIELD_SEP").split(FIELD_SEP)
        p.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { DetailDrilldownEntry.UserProfile(it, p.getOrNull(1).orEmpty()) }
    }
    raw.startsWith("T$FIELD_SEP") -> decodeThreadNavArgs(raw.removePrefix("T$FIELD_SEP"))?.let(DetailDrilldownEntry::LinkedThread)
    else -> null
}
