package net.jejer.hipda.feature.search

import net.jejer.hipda.data.search.ForumLinkResolver
import net.jejer.hipda.data.search.ResolvedForumLink
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.feature.common.SafeLinkPolicy
import net.jejer.hipda.feature.common.ThreadNavArgs

/** 站内地址严格 `http`/`https` scheme 白名单（委托统一 [SafeLinkPolicy.sanitizeHttpUrl]）。 */
fun sanitizeAllowedForumUrl(rawInput: String?): String? = SafeLinkPolicy.sanitizeHttpUrl(rawInput)

/** 结合 scheme 白名单与 [ForumLinkResolver] 识别合法站内目标。 */
fun resolveAllowedForumLink(rawInput: String?, forumServer: String = AppSettings.DEFAULT_FORUM_SERVER): ResolvedForumLink? =
    sanitizeAllowedForumUrl(rawInput)?.let { ForumLinkResolver.resolve(it, forumServer) }
        ?.takeUnless { it is ResolvedForumLink.Post && it.tid.isNullOrBlank() }

/** 点击层二次白名单校验并路由至站内页面。 */
fun dispatchAllowedForumLink(
    rawInput: String?, forumServer: String,
    onOpenThread: (ThreadNavArgs) -> Unit, onOpenForum: (fid: Int) -> Unit,
    onOpenUserProfile: (uid: String, username: String) -> Unit,
): Boolean {
    when (val t = resolveAllowedForumLink(rawInput, forumServer) ?: return false) {
        is ResolvedForumLink.Forum -> onOpenForum(t.fid)
        is ResolvedForumLink.Thread -> onOpenThread(ThreadNavArgs(t.tid))
        is ResolvedForumLink.ThreadLastPage -> onOpenThread(ThreadNavArgs(t.tid))
        is ResolvedForumLink.Post -> onOpenThread(ThreadNavArgs(requireNotNull(t.tid), targetPid = t.pid))
        is ResolvedForumLink.User -> onOpenUserProfile(t.uid, "")
    }
    return true
}
