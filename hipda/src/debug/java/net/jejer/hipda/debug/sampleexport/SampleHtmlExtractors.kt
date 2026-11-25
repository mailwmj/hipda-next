package net.jejer.hipda.debug.sampleexport

import net.jejer.hipda.parser.sanitize.Identity
import net.jejer.hipda.parser.sanitize.SanitizeSelectors
import org.jsoup.Jsoup

/**
 * 轻量只读 HTML 提取器，仅用于从已抓取的原始页面中提取后续只读 GET URL 与脱敏所需的动态 ID。
 * 不依赖任何旧版解析器。
 */
object SampleHtmlExtractors {

    private val UID_PARAM_REGEX = Regex("""(?i)(?:^|[?&;])uid=(\d+)(?![0-9A-Za-z_])""")
    private val TID_PARAM_REGEX = Regex("""(?i)(?:^|[?&;])tid=(\d+)(?![0-9A-Za-z_])""")
    private val PAGE_PARAM_REGEX = Regex("""(?i)(?:^|[?&;])page=(\d+)(?![0-9A-Za-z_])""")
    private val THREAD_TBODY_ID_REGEX = Regex("""(?i)^(?:normal|stick)thread_(\d+)$""")
    private val DISCUZ_UID_REGEX = Regex("""(?i)\bdiscuz_uid\s*=\s*['"]?(\d+)['"]?""")

    fun extractIdentity(html: String): Identity? {
        val doc = Jsoup.parse(html)
        val userLink = doc.select(SanitizeSelectors.UMENU_USER_LINK).firstOrNull()
        val umenuUid = userLink?.attr("href")?.let(::extractUidParam)
        val username = userLink?.text()?.trim().orEmpty()
        val uid = umenuUid
            ?: doc.select(SanitizeSelectors.UMENU_LINKS)
                .firstNotNullOfOrNull { extractUidParam(it.attr("href")) }
            ?: DISCUZ_UID_REGEX.find(html)?.groupValues?.get(1)?.takeIf(::isPositiveUid)
            ?: ""
        if (uid.isBlank() && username.isBlank()) return null
        return Identity(uid = uid, username = username)
    }

    /**
     * 从版块页挑选一个非置顶的公开多页普通帖 `tid`；若当前页没有多页普通帖，则退回第一个普通帖。
     */
    fun extractMultiPagePublicTid(html: String): String? {
        val doc = Jsoup.parse(html)
        val candidates = mutableListOf<ThreadCandidate>()
        for (tbody in doc.select("tbody[id]")) {
            val tbodyId = tbody.attr("id")
            val folderSrc = tbody.select("td.folder img").firstOrNull()?.attr("src").orEmpty()
            val isSticky = tbodyId.startsWith("stickthread_", ignoreCase = true) || folderSrc.contains("/pin_")
            val titleLink = tbody.select("th.subject span a").firstOrNull()
                ?: tbody.select("th.subject a[href*=viewthread.php]").firstOrNull()
            val tid = titleLink?.attr("href")?.let(::extractTidParam)
                ?: THREAD_TBODY_ID_REGEX.matchEntire(tbodyId)?.groupValues?.get(1)
                ?: continue
            var maxPage = 1
            for (pageLink in tbody.select("span.threadpages a")) {
                val hrefPage = extractPageParam(pageLink.attr("href"))?.toIntOrNull() ?: 1
                val textPage = pageLink.text().trim().toIntOrNull() ?: 1
                maxPage = maxOf(maxPage, hrefPage, textPage)
            }
            candidates += ThreadCandidate(tid = tid, isSticky = isSticky, maxPage = maxPage)
        }
        return candidates.firstOrNull { !it.isSticky && it.maxPage >= 2 }?.tid
            ?: candidates.firstOrNull { !it.isSticky }?.tid
            ?: candidates.firstOrNull { it.maxPage >= 2 }?.tid
            ?: candidates.firstOrNull()?.tid
    }

    fun extractFirstPostAuthorUid(html: String): String? {
        val doc = Jsoup.parse(html)
        val authorLink = doc.select("td.postauthor div.postinfo a").firstOrNull()
            ?: doc.select("div#postlist a[href*=space.php]").firstOrNull()
        return authorLink?.attr("href")?.let(::extractUidParam)
    }

    fun extractFirstSmsPeerUid(html: String, selfUid: String? = null): String? {
        val doc = Jsoup.parse(html)
        val found = linkedSetOf<String>()
        for (item in doc.select("ul.pm_list li")) {
            item.select("p.cite cite a").firstOrNull()?.attr("href")?.let(::extractUidParam)?.let(found::add)
            item.select("a.avatar").firstOrNull()?.attr("href")?.let(::extractUidParam)?.let(found::add)
            item.select("a[href*=pm.php]").firstOrNull()?.attr("href")?.let(::extractUidParam)?.let(found::add)
        }
        return found.firstOrNull { selfUid.isNullOrBlank() || it != selfUid } ?: found.firstOrNull()
    }

    private fun extractUidParam(url: String): String? =
        UID_PARAM_REGEX.find(url)?.groupValues?.get(1)?.takeIf(::isPositiveUid)

    private fun extractTidParam(url: String): String? =
        TID_PARAM_REGEX.find(url)?.groupValues?.get(1)?.takeIf(::isPositiveUid)

    private fun extractPageParam(url: String): String? =
        PAGE_PARAM_REGEX.find(url)?.groupValues?.get(1)

    private fun isPositiveUid(value: String): Boolean =
        value.isNotEmpty() && value != "0" && value.all(Char::isDigit)

    private data class ThreadCandidate(
        val tid: String,
        val isSticky: Boolean,
        val maxPage: Int,
    )
}
