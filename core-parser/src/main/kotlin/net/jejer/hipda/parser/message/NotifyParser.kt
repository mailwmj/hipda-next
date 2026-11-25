package net.jejer.hipda.parser.message

import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.common.DiscuzTextDecoder
import net.jejer.hipda.parser.common.DiscuzUrlHelper
import net.jejer.hipda.parser.common.firstMatch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 提醒列表（`notice.php`）纯 JVM 解析器（对照旧 `HiParser.java:278-452`）。
 * 绝不修改调用方传入的 [Document] DOM，也不耦合 Android 本地系统头像文件路径。
 */
object NotifyParser {

    private const val TITLE_SYSTEM = "系统信息"
    private const val TITLE_FRIEND = "好友信息"
    private const val PHRASE_FAVORITE_REPLY = "回复了您关注的主题"
    private const val PHRASE_THREAD_REPLY = "回复了您的帖子 "

    fun parseNotifyList(html: String, avatarBaseUrl: String? = null): NotifyListPage =
        parseNotifyList(Jsoup.parse(html), avatarBaseUrl)

    fun parseNotifyList(doc: Document, avatarBaseUrl: String? = null): NotifyListPage {
        val meta = DiscuzCommonParser.parsePageMeta(doc)
        val feed = doc.firstMatch("ul.feed")
            ?: return NotifyListPage(items = emptyList(), meta = meta, hasFeedContainer = false)

        val items = buildList {
            for (li in feed.select("li")) {
                val root = li.firstMatch("div") ?: continue
                val parsed = when {
                    root.hasClass("f_thread") -> parseNotifyThread(root)
                    root.hasClass("f_quote") -> parseNotifyQuoteOrReply(root, NotifyKind.QUOTE, avatarBaseUrl)
                    root.hasClass("f_reply") -> parseNotifyQuoteOrReply(root, NotifyKind.REPLY, avatarBaseUrl)
                    root.hasClass("f_manage") -> parseSystemInfo(root)
                    root.hasClass("f_buddy") -> parseFriendInfo(root, avatarBaseUrl)
                    else -> null
                }
                if (parsed != null) add(parsed)
            }
        }
        return NotifyListPage(items = items, meta = meta, hasFeedContainer = true)
    }

    private fun parseNotifyThread(root: Element): NotifyItem? {
        val infoBuilder = StringBuilder()
        var title = ""
        var tid: String? = null
        var pid: String? = null

        for (anchor in root.select("a")) {
            val href = anchor.attr("href")
            if (href.contains("space.php")) {
                infoBuilder.append(anchor.text()).append(' ')
            } else if (href.contains("redirect.php?")) {
                title = DiscuzTextDecoder.decodeDoubleEscapedEntities(anchor.text())
                tid = DiscuzUrlHelper.extractQueryParam(href, "ptid")
                pid = DiscuzUrlHelper.extractQueryParam(href, "pid")
                break
            }
        }

        val timeElement = root.firstMatch("em") ?: return null
        if (root.text().contains(PHRASE_FAVORITE_REPLY)) {
            infoBuilder.append(PHRASE_FAVORITE_REPLY)
        } else {
            infoBuilder.append(PHRASE_THREAD_REPLY)
        }

        return NotifyItem(
            kind = NotifyKind.THREAD,
            title = title,
            info = DiscuzTextDecoder.decodeDoubleEscapedEntities(infoBuilder.toString()),
            author = null,
            authorUid = null,
            avatarUrl = null,
            tid = tid,
            pid = pid,
            time = timeElement.text(),
            isNew = true,
        )
    }

    private fun parseNotifyQuoteOrReply(
        root: Element,
        kind: NotifyKind,
        avatarBaseUrl: String?,
    ): NotifyItem? {
        var author: String? = null
        var authorUid: String? = null
        var avatarUrl: String? = null
        var title = ""
        var tid: String? = null
        var pid: String? = null

        for (anchor in root.select("a")) {
            val href = anchor.attr("href")
            when {
                href.contains("space.php") -> {
                    val uid = DiscuzUrlHelper.extractQueryParam(href, "uid")
                    authorUid = uid
                    author = anchor.text()
                    avatarUrl = DiscuzUrlHelper.avatarUrlForUid(uid, avatarBaseUrl)
                }
                href.contains("viewthread.php") -> {
                    title = DiscuzTextDecoder.decodeDoubleEscapedEntities(anchor.text())
                    tid = DiscuzUrlHelper.extractQueryParam(href, "tid")
                }
                href.contains("redirect.php") -> {
                    val redirectTid = DiscuzUrlHelper.extractQueryParam(href, "ptid")
                    if (redirectTid != null) tid = redirectTid
                    pid = DiscuzUrlHelper.extractQueryParam(href, "pid")
                }
            }
        }

        val timeElement = root.firstMatch("em") ?: return null

        var quoteOriginal: String? = null
        var quoteReply: String? = null
        var info = ""
        val summaryElement = root.firstMatch(".summary")
        if (summaryElement != null) {
            val ddElements = summaryElement.select("dd")
            if (ddElements.size == 2) {
                val originalText = DiscuzTextDecoder.decodeDoubleEscapedEntities(ddElements[0].text())
                val replyText = DiscuzTextDecoder.decodeDoubleEscapedEntities(ddElements[1].text())
                quoteOriginal = originalText
                quoteReply = replyText
                info = "<u>您的帖子:</u>$originalText<br><u>${author.orEmpty()} 说:</u>$replyText"
            } else {
                info = DiscuzTextDecoder.decodeDoubleEscapedEntities(summaryElement.text())
            }
        }

        val isNew = root.select("img").any {
            it.attr("src").contains(DiscuzUrlHelper.NEW_PM_IMAGE_MARKER)
        }

        return NotifyItem(
            kind = kind,
            title = title,
            info = info,
            author = author,
            authorUid = authorUid,
            avatarUrl = avatarUrl,
            tid = tid,
            pid = pid,
            time = timeElement.text(),
            isNew = isNew,
            quoteOriginal = quoteOriginal,
            quoteReply = quoteReply,
        )
    }

    private fun parseSystemInfo(root: Element): NotifyItem {
        val firstAnchor = root.firstMatch("a")
        val tid = firstAnchor?.attr("href")?.let { href ->
            DiscuzUrlHelper.extractQueryParam(href, "tid")
                ?: DiscuzUrlHelper.extractQueryParam(href, "ptid")
        }
        val isNew = root.select("img").any {
            it.attr("src").contains(DiscuzUrlHelper.NEW_PM_IMAGE_MARKER)
        }
        return NotifyItem(
            kind = NotifyKind.SYSTEM,
            title = TITLE_SYSTEM,
            info = DiscuzTextDecoder.decodeDoubleEscapedEntities(root.text()),
            author = null,
            authorUid = null,
            avatarUrl = null,
            tid = tid,
            pid = null,
            time = null,
            isNew = isNew,
        )
    }

    private fun parseFriendInfo(root: Element, avatarBaseUrl: String?): NotifyItem {
        val anchors = root.select("a")
        var author: String? = null
        var authorUid: String? = null
        var avatarUrl: String? = null
        if (anchors.isNotEmpty()) {
            val firstAnchor = anchors[0]
            authorUid = DiscuzUrlHelper.extractQueryParam(firstAnchor.attr("href"), "uid")
            author = firstAnchor.text()
            avatarUrl = DiscuzUrlHelper.avatarUrlForUid(authorUid, avatarBaseUrl)
        }
        val isNew = root.select("img").any {
            it.attr("src").contains(DiscuzUrlHelper.NEW_PM_IMAGE_MARKER)
        }

        // 不修改原 DOM：克隆子树后移除第 2 个操作链接（如“批准申请”）再提取文本
        val infoText = if (anchors.size > 1) {
            val cloned = root.clone()
            cloned.select("a").getOrNull(1)?.remove()
            cloned.text()
        } else {
            root.text()
        }

        return NotifyItem(
            kind = NotifyKind.FRIEND,
            title = TITLE_FRIEND,
            info = DiscuzTextDecoder.decodeDoubleEscapedEntities(infoText),
            author = author,
            authorUid = authorUid,
            avatarUrl = avatarUrl,
            tid = null,
            pid = null,
            time = null,
            isNew = isNew,
        )
    }
}
