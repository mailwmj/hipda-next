package net.jejer.hipda.parser.search

import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.common.DiscuzTextDecoder
import net.jejer.hipda.parser.common.DiscuzUrlHelper
import net.jejer.hipda.parser.common.firstMatch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 标题搜索（兼服务今日新帖与 TA 的帖子）与全文搜索纯 JVM 解析器
 * （对照旧 `HiParser.java:525-669`）。
 */
object SearchParser {

    fun parse(html: String, fullText: Boolean = false, avatarBaseUrl: String? = null): SearchPage =
        parse(Jsoup.parse(html), fullText = fullText, avatarBaseUrl = avatarBaseUrl)

    fun parse(doc: Document, fullText: Boolean = false, avatarBaseUrl: String? = null): SearchPage =
        if (fullText) {
            parseFullTextSearch(doc, avatarBaseUrl)
        } else {
            parseTitleSearch(doc, avatarBaseUrl)
        }

    fun parseTitleSearch(html: String, avatarBaseUrl: String? = null): SearchPage =
        parseTitleSearch(Jsoup.parse(html), avatarBaseUrl)

    fun parseTitleSearch(doc: Document, avatarBaseUrl: String? = null): SearchPage {
        val meta = DiscuzCommonParser.parsePageMeta(doc)
        val (searchId, maxPage) = parsePagination(doc)

        val items = buildList {
            for (tbody in doc.select("tbody")) {
                val titleLink = tbody.firstMatch("tr th.subject a") ?: continue
                val authorLink = tbody.firstMatch("tr td.author cite a") ?: continue

                val tid = DiscuzUrlHelper.extractQueryParam(titleLink.attr("href"), "tid")
                val title = DiscuzTextDecoder.decodeDoubleEscapedEntities(titleLink.text())
                val author = authorLink.text()
                val authorUid = DiscuzUrlHelper.extractQueryParam(authorLink.attr("href"), "uid")
                val avatarUrl = DiscuzUrlHelper.avatarUrlForUid(authorUid, avatarBaseUrl)
                val time = tbody.firstMatch("tr td.author em")?.text()
                val forumName = tbody.firstMatch("tr td.forum")?.text()

                add(
                    SearchItem(
                        tid = tid,
                        pid = null,
                        title = title,
                        snippet = null,
                        author = author,
                        authorUid = authorUid,
                        avatarUrl = avatarUrl,
                        time = time,
                        forumName = forumName,
                    ),
                )
            }
        }

        return SearchPage(
            searchId = searchId,
            maxPage = maxPage,
            items = items,
            meta = meta,
        )
    }

    fun parseFullTextSearch(html: String, avatarBaseUrl: String? = null): SearchPage =
        parseFullTextSearch(Jsoup.parse(html), avatarBaseUrl)

    fun parseFullTextSearch(doc: Document, avatarBaseUrl: String? = null): SearchPage {
        val meta = DiscuzCommonParser.parsePageMeta(doc)
        val (searchId, maxPage) = parsePagination(doc)

        val items = buildList {
            for (row in doc.select("table.datatable tr")) {
                val subjectLink = row.firstMatch("div.sp_title a") ?: continue
                val postUrl = subjectLink.attr("href")
                val pid = DiscuzUrlHelper.extractQueryParam(postUrl, "pid") ?: continue
                val tid = DiscuzUrlHelper.extractQueryParam(postUrl, "tid")
                    ?: DiscuzUrlHelper.extractQueryParam(postUrl, "ptid")

                val contentElements = row.select("div.sp_content")
                val snippet = if (contentElements.isNotEmpty()) {
                    DiscuzTextDecoder.decodeDoubleEscapedEntities(contentElements.text())
                } else {
                    null
                }

                val postInfoSpans = row.select("div.sp_theard span")
                if (postInfoSpans.size != 5) {
                    continue
                }

                val forumName = postInfoSpans[0].firstMatch("a")?.text()
                val authorLink = postInfoSpans[1].firstMatch("a")
                val author = authorLink?.text()
                val authorUid = authorLink?.let { DiscuzUrlHelper.extractQueryParam(it.attr("href"), "uid") }
                val avatarUrl = DiscuzUrlHelper.avatarUrlForUid(authorUid, avatarBaseUrl)
                val time = DiscuzUrlHelper.middleString(postInfoSpans[4].text(), ":", "&").trim().ifEmpty { null }

                add(
                    SearchItem(
                        tid = tid,
                        pid = pid,
                        title = DiscuzTextDecoder.decodeDoubleEscapedEntities(subjectLink.text()),
                        snippet = snippet,
                        author = author,
                        authorUid = authorUid,
                        avatarUrl = avatarUrl,
                        time = time,
                        forumName = forumName,
                    ),
                )
            }
        }

        return SearchPage(
            searchId = searchId,
            maxPage = maxPage,
            items = items,
            meta = meta,
        )
    }

    private fun parsePagination(doc: Document): Pair<String?, Int> {
        var links: List<Element> = doc.select("div.pages_btns div.pages a")
        var strongs: List<Element> = doc.select("div.pages_btns div.pages strong")
        if (links.isEmpty() && strongs.isEmpty()) {
            links = doc.select("div.pages a")
            strongs = doc.select("div.pages strong")
        }

        val searchId = links.firstNotNullOfOrNull { anchor ->
            DiscuzUrlHelper.extractQueryParam(anchor.attr("href"), "searchid")
        }

        var lastPage = 1
        for (el in links) {
            val pageNum = DiscuzUrlHelper.extractFirstIntOrZero(el.text())
            if (pageNum > lastPage) lastPage = pageNum
        }
        for (el in strongs) {
            val pageNum = DiscuzUrlHelper.extractFirstIntOrZero(el.text())
            if (pageNum > lastPage) lastPage = pageNum
        }
        return searchId to lastPage
    }
}
