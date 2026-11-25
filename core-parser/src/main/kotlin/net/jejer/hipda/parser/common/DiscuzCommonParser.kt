package net.jejer.hipda.parser.common

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Discuz! 通用页头与公共元信息纯 JVM 解析器。
 * 所有方法均为纯函数，绝不修改调用方传入的 [Document] DOM。
 */
object DiscuzCommonParser {

    fun parsePageMeta(html: String): DiscuzPageMeta = parsePageMeta(Jsoup.parse(html))

    fun parsePageMeta(doc: Document): DiscuzPageMeta = DiscuzPageMeta(
        session = parseSession(doc),
        formhash = parseFormhash(doc) ?: parseLogoutFormhash(doc),
        errorMessage = parseErrorMessage(doc),
        promptCounts = parsePromptCounts(doc),
    )

    fun parseSession(doc: Document): DiscuzSessionInfo {
        val umenu = doc.firstMatch("div#umenu") ?: doc.firstMatch("#umenu")
            ?: return DiscuzSessionInfo(isLoggedIn = false, uid = null, username = null)
        val isLoggedIn = umenu.html().contains("space.php")
        if (!isLoggedIn) {
            return DiscuzSessionInfo(isLoggedIn = false, uid = null, username = null)
        }
        val userLink = umenu.firstMatch("cite a.noborder")
            ?: umenu.firstMatch("cite a")
            ?: umenu.firstMatch("a[href*=space.php]")
        val rawUid = DiscuzUrlHelper.extractQueryParam(userLink?.attr("href"), "uid")
        val uid = rawUid?.takeIf { it.all { ch -> ch in '0'..'9' } }
        val username = userLink?.text()?.trim()?.ifEmpty { null }
        return DiscuzSessionInfo(
            isLoggedIn = true,
            uid = uid,
            username = username,
        )
    }

    /**
     * 解析表单内的 `formhash`（优先 `input[name=formhash]`，次选 `input#formhash`），
     * 与旧 `HiParser.parseFormhash` 对齐。
     */
    fun parseFormhash(doc: Document): String? {
        val byName = doc.firstMatch("input[name=formhash]")
        if (byName != null) {
            return byName.attr("value").trim().ifEmpty { null }
        }
        val byId = doc.firstMatch("input#formhash")
        if (byId != null) {
            return byId.attr("value").trim().ifEmpty { null }
        }
        return null
    }

    /**
     * 从 `#umenu` 的退出等链接中提取 `formhash`（对齐旧 `HiParser.parseUserInfo`）。
     */
    fun parseLogoutFormhash(doc: Document): String? {
        for (link in doc.select("#umenu a")) {
            val hash = DiscuzUrlHelper.extractQueryParam(link.attr("href"), "formhash")
            if (!hash.isNullOrBlank()) return hash
        }
        return null
    }

    /**
     * 解析 `div.alert_error` 错误提示，排除其中 `<a>` 标签文本，但不修改传入 [doc] DOM。
     */
    fun parseErrorMessage(doc: Document): String? {
        val errorElement = doc.firstMatch("div.alert_error") ?: return null
        val cloned = errorElement.clone()
        cloned.select("a").remove()
        return DiscuzTextDecoder.decodeDoubleEscapedEntities(cloned.text())
    }

    /**
     * 解析 `div.promptcontent` 内 5 项提醒计数。
     */
    fun parsePromptCounts(doc: Document): PromptCounts = PromptCounts(
        pm = extractPromptCount(doc, "prompt_pm"),
        announcePm = extractPromptCount(doc, "prompt_announcepm"),
        systemPm = extractPromptCount(doc, "prompt_systempm"),
        friend = extractPromptCount(doc, "prompt_friend"),
        thread = extractPromptCount(doc, "prompt_threads"),
    )

    private fun extractPromptCount(doc: Document, anchorId: String): Int {
        val anchor = doc.firstMatch("div.promptcontent a#$anchorId") ?: return 0
        val countStr = DiscuzUrlHelper.middleString(anchor.text(), "(", ")")
        return DiscuzUrlHelper.parseIntOrZero(countStr).coerceAtLeast(0)
    }
}
