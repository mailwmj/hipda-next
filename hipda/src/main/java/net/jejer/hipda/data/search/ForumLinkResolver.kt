package net.jejer.hipda.data.search

import java.util.Locale
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.parser.common.DiscuzUrlHelper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * In-site forum link targets recognized by [ForumLinkResolver] (product-design §3, §7.7).
 */
sealed interface ResolvedForumLink {
    data class Forum(val fid: Int) : ResolvedForumLink
    data class Thread(val tid: String, val page: Int = 1) : ResolvedForumLink
    data class ThreadLastPage(val tid: String) : ResolvedForumLink
    data class Post(val tid: String?, val pid: String) : ResolvedForumLink
    data class User(val uid: String) : ResolvedForumLink
}

/**
 * Pure resolver recognizing HiPDA / 4D4Y in-site links (legacy `FragmentUtils.java:87-169`).
 *
 * Accepts:
 * - Canonical `4d4y.com` / `*.4d4y.com` and historical `hi-pda.com` / `*.hi-pda.com` hosts;
 * - The user-configured [forumServer] host (including custom mirrors or loopback test servers);
 * - Relative forum script paths such as `viewthread.php?tid=...` or `/forum/viewthread.php?tid=...`.
 *
 * Rejects external domains, spoofed hostnames, non-HTTP(S) schemes, unknown scripts, and invalid IDs.
 */
object ForumLinkResolver {

    private val BUILTIN_DOMAINS: List<String> = listOf("4d4y.com", "hi-pda.com")
    private val HOST_PORT_PREFIX_REGEX = Regex("""^[^/?#:]+:\d+(?:/.*)?$""")

    fun resolve(
        rawInput: String,
        forumServer: String = AppSettings.DEFAULT_FORUM_SERVER,
    ): ResolvedForumLink? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) {
            return null
        }

        val configuredServer = parseConfiguredServer(forumServer)

        // 1. Explicit scheme URL (http:// or https:// only)
        if (trimmed.contains("://")) {
            if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return null
            }
            val parsedUrl = trimmed.toHttpUrlOrNull() ?: return null
            return resolveHttpUrl(parsedUrl, configuredServer)
        }

        // 2. Protocol-relative URL (//www.4d4y.com/forum/...)
        if (trimmed.startsWith("//")) {
            val parsedUrl = "https:$trimmed".toHttpUrlOrNull() ?: return null
            return resolveHttpUrl(parsedUrl, configuredServer)
        }

        // 3. Colon before slash/query: allow scheme-less host:port/path, reject other schemes (javascript:, ftp:, file:, etc.)
        val firstDelimiter = trimmed.indexOfAny(charArrayOf('/', '?', '#'))
        val colonIndex = trimmed.indexOf(':')
        if (colonIndex >= 0 && (firstDelimiter < 0 || colonIndex < firstDelimiter)) {
            if (!HOST_PORT_PREFIX_REGEX.matches(trimmed)) {
                return null
            }
            val parsedUrl = "https://$trimmed".toHttpUrlOrNull() ?: return null
            return resolveHttpUrl(parsedUrl, configuredServer)
        }

        // 4. Relative forum script path (e.g. viewthread.php?tid=1, /forum/viewthread.php?tid=1, ./gotopost.php?pid=1)
        val beforeFragment = trimmed.substringBefore('#')
        val questionIndex = beforeFragment.indexOf('?')
        if (questionIndex < 0) {
            return null
        }
        val pathPart = beforeFragment.substring(0, questionIndex)
        val queryPart = beforeFragment.substring(questionIndex + 1)
        resolveRelativePath(pathPart, queryPart)?.let { return it }

        // 5. Scheme-less domain URL (e.g. www.4d4y.com/forum/viewthread.php?tid=1)
        val parsedUrl = "https://$trimmed".toHttpUrlOrNull() ?: return null
        return resolveHttpUrl(parsedUrl, configuredServer)
    }

    private fun resolveRelativePath(pathPart: String, queryPart: String): ResolvedForumLink? {
        val cleaned = pathPart.removePrefix("./").trimStart('/')
        if (cleaned.isEmpty()) return null
        val segments = cleaned.split('/').filter { it.isNotEmpty() }
        val scriptName = when {
            segments.size == 1 -> segments[0]
            segments.size == 2 && segments[0].equals("forum", ignoreCase = true) -> segments[1]
            else -> return null
        }
        return resolveScript(scriptName, queryPart)
    }

    private fun resolveHttpUrl(
        url: HttpUrl,
        configuredServer: ConfiguredServer?,
    ): ResolvedForumLink? {
        val host = url.host.lowercase(Locale.ROOT)
        if (!isAllowedHost(host, configuredServer?.host)) {
            return null
        }

        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        val scriptName = segments.last()
        val prefix = segments.dropLast(1).map { it.lowercase(Locale.ROOT) }
        if (!isAllowedPathPrefix(prefix, configuredServer?.allowedPrefixes)) {
            return null
        }

        val query = url.encodedQuery ?: return null
        return resolveScript(scriptName, query)
    }

    private fun isAllowedHost(host: String, configuredHost: String?): Boolean {
        if (configuredHost != null && host == configuredHost) {
            return true
        }
        return BUILTIN_DOMAINS.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    private fun isAllowedPathPrefix(
        prefix: List<String>,
        configuredPrefixes: Set<List<String>>?,
    ): Boolean {
        if (prefix.isEmpty() || prefix == listOf("forum")) {
            return true
        }
        return configuredPrefixes != null && prefix in configuredPrefixes
    }

    private fun resolveScript(scriptName: String, query: String): ResolvedForumLink? {
        return when (scriptName.lowercase(Locale.ROOT)) {
            "forumdisplay.php" -> {
                val fid = parsePositiveInt(DiscuzUrlHelper.extractQueryParam(query, "fid")) ?: return null
                ResolvedForumLink.Forum(fid = fid)
            }

            "viewthread.php" -> {
                val tid = parsePositiveNumericId(DiscuzUrlHelper.extractQueryParam(query, "tid")) ?: return null
                val page = parsePositiveInt(DiscuzUrlHelper.extractQueryParam(query, "page")) ?: 1
                ResolvedForumLink.Thread(tid = tid, page = page)
            }

            "redirect.php" -> {
                when (DiscuzUrlHelper.extractQueryParam(query, "goto")?.lowercase(Locale.ROOT)) {
                    "lastpost" -> {
                        val tid = parsePositiveNumericId(DiscuzUrlHelper.extractQueryParam(query, "tid"))
                            ?: return null
                        ResolvedForumLink.ThreadLastPage(tid = tid)
                    }

                    "findpost" -> {
                        val tid = parsePositiveNumericId(
                            DiscuzUrlHelper.extractQueryParam(query, "ptid")
                                ?: DiscuzUrlHelper.extractQueryParam(query, "tid"),
                        ) ?: return null
                        val pid = parsePositiveNumericId(DiscuzUrlHelper.extractQueryParam(query, "pid"))
                            ?: return null
                        ResolvedForumLink.Post(tid = tid, pid = pid)
                    }

                    else -> null
                }
            }

            "gotopost.php" -> {
                val pid = parsePositiveNumericId(DiscuzUrlHelper.extractQueryParam(query, "pid")) ?: return null
                val tid = parsePositiveNumericId(
                    DiscuzUrlHelper.extractQueryParam(query, "tid")
                        ?: DiscuzUrlHelper.extractQueryParam(query, "ptid"),
                )
                ResolvedForumLink.Post(tid = tid, pid = pid)
            }

            "space.php" -> {
                val uid = parsePositiveNumericId(DiscuzUrlHelper.extractQueryParam(query, "uid")) ?: return null
                ResolvedForumLink.User(uid = uid)
            }

            else -> null
        }
    }

    private fun parseConfiguredServer(rawServer: String): ConfiguredServer? {
        val trimmed = rawServer.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        val host = parsed.host.lowercase(Locale.ROOT)
        val baseSegments = parsed.pathSegments
            .filter { it.isNotEmpty() }
            .map { it.lowercase(Locale.ROOT) }
        val allowedPrefixes = buildSet {
            add(baseSegments)
            if (baseSegments.lastOrNull() != "forum") {
                add(baseSegments + "forum")
            }
        }
        return ConfiguredServer(host = host, allowedPrefixes = allowedPrefixes)
    }

    private fun parsePositiveInt(raw: String?): Int? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty() || !trimmed.all { it in '0'..'9' }) return null
        return trimmed.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun parsePositiveNumericId(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty() || !trimmed.all { it in '0'..'9' }) return null
        val numeric = trimmed.toLongOrNull() ?: return null
        return if (numeric > 0L) trimmed else null
    }

    private data class ConfiguredServer(
        val host: String,
        val allowedPrefixes: Set<List<String>>,
    )
}
