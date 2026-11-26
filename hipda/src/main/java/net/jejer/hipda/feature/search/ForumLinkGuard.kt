package net.jejer.hipda.feature.search

import androidx.compose.runtime.Immutable
import net.jejer.hipda.data.search.ForumLinkResolver
import net.jejer.hipda.data.search.ResolvedForumLink
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.feature.common.ThreadNavArgs
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 经严格 `http`/`https` 白名单与 [ForumLinkResolver] 双重校验后的可路由站内链接。 */
@Immutable
data class AllowedForumLink(val normalizedUrl: String, val target: ResolvedForumLink)

/**
 * 搜索框站内地址的严格 `http`/`https` scheme 白名单纯函数：
 * 仅放行显式 `http://` 或 `https://` 且可被 `HttpUrl` 解析、含非空 host、无 userinfo 的链接；
 * 拦截 `javascript:`、`intent:`、`file:`、`content:`、`data:`、`mailto:` 及空白/控制/零宽字符绕过。
 */
fun sanitizeAllowedForumUrl(rawInput: String?): String? {
    if (rawInput.isNullOrEmpty() || rawInput.any(::isDisallowedUrlChar)) return null
    if (!rawInput.startsWith("http://", ignoreCase = true) && !rawInput.startsWith("https://", ignoreCase = true)) return null
    val parsed = rawInput.toHttpUrlOrNull() ?: return null
    if ((parsed.scheme != "http" && parsed.scheme != "https") || parsed.host.isBlank()) return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    return parsed.toString()
}

/** 结合 scheme 白名单与 [ForumLinkResolver] 识别合法站内目标。 */
fun resolveAllowedForumLink(rawInput: String?, forumServer: String = AppSettings.DEFAULT_FORUM_SERVER): AllowedForumLink? {
    val safeUrl = sanitizeAllowedForumUrl(rawInput) ?: return null
    val resolved = ForumLinkResolver.resolve(rawInput = safeUrl, forumServer = forumServer) ?: return null
    if (resolved is ResolvedForumLink.Post && resolved.tid.isNullOrBlank()) return null
    return AllowedForumLink(normalizedUrl = safeUrl, target = resolved)
}

/** 点击层二次白名单校验并路由至站内页面。 */
fun dispatchAllowedForumLink(
    rawInput: String?,
    forumServer: String,
    onOpenThread: (ThreadNavArgs) -> Unit,
    onOpenForum: (fid: Int) -> Unit,
    onOpenUserProfile: (uid: String, username: String) -> Unit,
): Boolean {
    val allowed = resolveAllowedForumLink(rawInput = rawInput, forumServer = forumServer) ?: return false
    when (val t = allowed.target) {
        is ResolvedForumLink.Forum -> onOpenForum(t.fid)
        is ResolvedForumLink.Thread -> onOpenThread(ThreadNavArgs(tid = t.tid))
        is ResolvedForumLink.ThreadLastPage -> onOpenThread(ThreadNavArgs(tid = t.tid))
        is ResolvedForumLink.Post -> onOpenThread(ThreadNavArgs(tid = t.tid?.takeIf(String::isNotBlank) ?: return false, targetPid = t.pid))
        is ResolvedForumLink.User -> onOpenUserProfile(t.uid, "")
    }
    return true
}

private fun isDisallowedUrlChar(ch: Char): Boolean =
    ch.isISOControl() || ch.isWhitespace() || Character.isSpaceChar(ch) || ch == '\u200B' || ch == '\uFEFF'
