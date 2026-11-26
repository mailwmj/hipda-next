package net.jejer.hipda.feature.common

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 全仓唯一链接与下载地址 scheme 白名单策略（DISPATCH §4 硬阻断红线）。
 *
 * - 链接档 [sanitizeLinkUrl]：允许 `http` / `https`（host 非空、拦截 `rawUserInfo` / `user@` 混淆、无空白/控制/零宽字符）
 *   以及经校验的纯邮箱 `mailto:`（无 query/fragment/header 注入）；
 * - 下载/直连档 [sanitizeHttpUrl]：仅允许 `http` / `https`（比链接档更严，明确拒绝 `mailto:`）。
 */
object SafeLinkPolicy {
    private val MAILTO_ADDRESS_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$")

    /** 链接档：仅放行合法 `http`、`https` 及纯邮箱 `mailto:`；其余一律返回 `null`。 */
    fun sanitizeLinkUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrEmpty() || hasDisallowedUrlChar(rawUrl)) return null
        if (rawUrl.startsWith("mailto:", ignoreCase = true)) {
            val address = rawUrl.substring(7)
            return if (isValidMailtoAddress(address)) "mailto:$address" else null
        }
        return sanitizeHttpUrlInternal(rawUrl)
    }

    /** 下载/直连档：仅放行合法 `http`、`https`（拒绝 `mailto:` 及全部危险或未知 scheme）。 */
    fun sanitizeHttpUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrEmpty() || hasDisallowedUrlChar(rawUrl)) return null
        return sanitizeHttpUrlInternal(rawUrl)
    }

    private fun sanitizeHttpUrlInternal(rawUrl: String): String? {
        val afterScheme = when {
            rawUrl.startsWith("http://", ignoreCase = true) -> rawUrl.substring(7)
            rawUrl.startsWith("https://", ignoreCase = true) -> rawUrl.substring(8)
            else -> return null
        }
        if (afterScheme.isEmpty() || afterScheme.startsWith('/') || afterScheme.startsWith('\\')) return null
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || '@' in authority || '\\' in authority) return null
        val parsed = rawUrl.toHttpUrlOrNull() ?: return null
        if ((parsed.scheme != "http" && parsed.scheme != "https") || parsed.host.isBlank()) return null
        if (
            parsed.username.isNotEmpty() ||
            parsed.password.isNotEmpty() ||
            parsed.encodedUsername.isNotEmpty() ||
            parsed.encodedPassword.isNotEmpty() ||
            '@' in parsed.host
        ) {
            return null
        }
        val canonical = parsed.toString()
        if (hasUnsafeJavaUriAuthority(rawUrl) || hasUnsafeJavaUriAuthority(canonical)) return null
        return canonical
    }

    private fun hasUnsafeJavaUriAuthority(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return !uri.rawUserInfo.isNullOrEmpty() ||
            uri.rawAuthority.isNullOrBlank() ||
            uri.host?.contains('@') == true
    }

    private fun isValidMailtoAddress(address: String): Boolean {
        if (address.isEmpty() || address.contains("..")) return false
        val at = address.indexOf('@')
        if (at <= 0 || at != address.lastIndexOf('@')) return false
        if (address.startsWith('.') || address[at - 1] == '.') return false
        return MAILTO_ADDRESS_REGEX.matches(address)
    }

    private fun hasDisallowedUrlChar(url: String): Boolean = url.any { ch ->
        ch.isISOControl() || ch.isWhitespace() || Character.isSpaceChar(ch) || ch == '\u200B' || ch == '\uFEFF'
    }
}
