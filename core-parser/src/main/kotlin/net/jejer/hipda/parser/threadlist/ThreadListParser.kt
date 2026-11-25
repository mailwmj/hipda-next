package net.jejer.hipda.parser.threadlist

import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.common.DiscuzTextDecoder
import net.jejer.hipda.parser.common.DiscuzUrlHelper
import net.jejer.hipda.parser.common.firstMatch
import net.jejer.hipda.parser.common.lastElementOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 帖子列表纯 JVM 解析器（对照旧 `HiParserThreadList.java:21-202`）。
 *
 * 零 Android 依赖、绝不修改调用方传入的 [Document] DOM、不读写设置（不过滤置顶与黑名单）、不启动后台线程。
 */
object ThreadListParser {

    private const val STICKY_SRC_MARKER = "/pin_"
    private const val NEW_SRC_MARKER = "new"
    private const val POLL_SRC_MARKER = "/poll"
    private const val ATTACH_IMAGE_SUFFIX = "image_s.gif"
    private const val MIN_USER_LINK_LENGTH = "space.php?uid=".length

    fun parse(html: String, avatarBaseUrl: String? = null): ThreadListPage =
        parse(Jsoup.parse(html), avatarBaseUrl)

    fun parse(doc: Document, avatarBaseUrl: String? = null): ThreadListPage {
        val meta = DiscuzCommonParser.parsePageMeta(doc)
        val (page, lastPage) = parsePagination(doc)
        val tbodies = doc.select("tbody[id]")
        val hasThreadRows = tbodies.isNotEmpty()

        val threads = buildList {
            for (tbody in tbodies) {
                parseThreadRow(tbody, avatarBaseUrl)?.let(::add)
            }
        }

        return ThreadListPage(
            threads = threads,
            hasThreadRows = hasThreadRows,
            page = page,
            lastPage = lastPage,
            meta = meta,
        )
    }

    private fun parseThreadRow(tbody: Element, avatarBaseUrl: String?): ThreadSummary? {
        val folderImgSrc = tbody.firstMatch("td.folder")?.firstMatch("img")?.attr("src").orEmpty()
        val isSticky = folderImgSrc.contains(STICKY_SRC_MARKER)
        val isNew = folderImgSrc.contains(NEW_SRC_MARKER)

        val isPoll = tbody.firstMatch("td.icon")
            ?.firstMatch("img")
            ?.attr("src")
            ?.contains(POLL_SRC_MARKER) == true

        val subjectTh = tbody.firstMatch("th.subject") ?: return null
        val titleLink = subjectTh.firstMatch("span a") ?: return null
        val tid = DiscuzUrlHelper.extractQueryParam(titleLink.attr("href"), "tid").orEmpty()
        val title = DiscuzTextDecoder.decodeDoubleEscapedEntities(titleLink.text())
        val titleColor = extractTitleColor(titleLink.attr("style"))

        val typeElements = subjectTh.select("em a")
        val typeName = if (typeElements.isNotEmpty()) {
            DiscuzTextDecoder.decodeDoubleEscapedEntities(typeElements.text()).ifEmpty { null }
        } else {
            null
        }

        val authorTd = tbody.firstMatch("td.author") ?: return null
        val authorLink = authorTd.firstMatch("cite a") ?: return null
        val author = authorLink.text()
        val userLink = authorLink.attr("href")
        if (userLink.length < MIN_USER_LINK_LENGTH) {
            return null
        }
        val authorId = DiscuzUrlHelper.extractQueryParam(userLink, "uid").orEmpty()
        val avatarUrl = DiscuzUrlHelper.avatarUrlForUid(authorId, avatarBaseUrl)

        val timeCreate = authorTd.firstMatch("em")?.text() ?: return null
        val timeUpdate = tbody.firstMatch("td.lastpost em a")?.text()

        val numsTd = tbody.firstMatch("td.nums") ?: return null
        val replyCountText = numsTd.firstMatch("strong")?.text() ?: return null
        val viewCountText = numsTd.firstMatch("em")?.text() ?: return null

        val lastPostAuthor = tbody.firstMatch("td.lastpost cite")?.text() ?: return null

        val withPic = tbody.select("img.attach").any { attach ->
            val src = attach.attr("src")
            src.isNotEmpty() && src.endsWith(ATTACH_IMAGE_SUFFIX)
        }

        val maxPage = extractThreadMaxPage(tbody)

        return ThreadSummary(
            tid = tid,
            title = title,
            titleColor = titleColor,
            typeName = typeName,
            isSticky = isSticky,
            isNew = isNew,
            isPoll = isPoll,
            author = author,
            authorId = authorId,
            avatarUrl = avatarUrl,
            timeCreate = timeCreate,
            timeUpdate = timeUpdate,
            lastPostAuthor = lastPostAuthor,
            replyCountText = replyCountText,
            viewCountText = viewCountText,
            withPic = withPic,
            maxPage = maxPage,
        )
    }

    private fun extractTitleColor(styleAttr: String): String? {
        if (styleAttr.isBlank()) return null
        for (declaration in styleAttr.split(';')) {
            val colonIndex = declaration.indexOf(':')
            if (colonIndex <= 0) continue
            val property = declaration.substring(0, colonIndex).trim()
            if (property.equals("color", ignoreCase = true)) {
                val value = declaration.substring(colonIndex + 1).trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    private fun extractThreadMaxPage(tbody: Element): Int {
        val lastPageLink = tbody.select("span.threadpages a").lastElementOrNull() ?: return 1
        val pageParam = DiscuzUrlHelper.extractQueryParam(lastPageLink.attr("href"), "page") ?: return 1
        if (!pageParam.all { it in '0'..'9' }) return 1
        return pageParam.toIntOrNull()?.takeIf { it > 0 } ?: 1
    }

    /**
     * 解析版块分页栏 `div.pages`：
     * - `page` 取 `<strong>` 中的正整数页码（缺省 `1`）；
     * - `lastPage` 优先从 `<a>` 的 `href` 查询参数 `page=` 读取（遵循 Discuz! 实际可访问页数上限），
     *   若 `href` 不含正整数 `page=` 则回退读取文本数字，并与 `<strong>` 页码取最大值。
     */
    private fun parsePagination(doc: Document): Pair<Int, Int> {
        val pagesDiv = doc.firstMatch("div.pages_btns div.pages")
            ?: doc.firstMatch("div.pages")
            ?: return 1 to 1

        var currentPage = 1
        var lastPage = 1

        for (strong in pagesDiv.select("strong")) {
            val pageNum = DiscuzUrlHelper.extractFirstIntOrZero(strong.text())
            if (pageNum > 0) {
                currentPage = pageNum
                if (pageNum > lastPage) {
                    lastPage = pageNum
                }
            }
        }

        for (anchor in pagesDiv.select("a")) {
            val hrefPage = DiscuzUrlHelper.extractQueryParam(anchor.attr("href"), "page")
                ?.takeIf { it.all { ch -> ch in '0'..'9' } }
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
            val pageNum = hrefPage ?: DiscuzUrlHelper.extractFirstIntOrZero(anchor.text())
            if (pageNum > lastPage) {
                lastPage = pageNum
            }
        }

        return currentPage to maxOf(lastPage, currentPage)
    }
}
