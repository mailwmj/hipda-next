package net.jejer.hipda.parser.simplelist

import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.common.DiscuzTextDecoder
import net.jejer.hipda.parser.common.DiscuzUrlHelper
import net.jejer.hipda.parser.common.firstMatch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 我的帖子、我的回复、我的收藏与关注简单列表纯 JVM 解析器
 * （对照旧 `HiParser.java:57-210, 671-724`）。
 */
object SimpleListParser {

    fun parseMyThreads(html: String): MyThreadsPage = parseMyThreads(Jsoup.parse(html))

    fun parseMyThreads(doc: Document): MyThreadsPage {
        val meta = DiscuzCommonParser.parsePageMeta(doc)
        val table = doc.firstMatch("table.datatable")
            ?: return MyThreadsPage(maxPage = 1, items = emptyList(), meta = meta, hasTable = false)

        val maxPage = extractMaxPage(doc, "div.pages_btns div.pages")
        val rows = table.select("tr")

        val items = buildList {
            for (i in 1 until rows.size) {
                val row = rows[i]
                val th = row.firstMatch("th") ?: continue
                val links = th.select("a")
                if (links.size != 1) continue

                val link = links[0]
                val href = link.attr("href")
                if (!href.contains("viewthread.php?tid=")) continue
                val tid = DiscuzUrlHelper.extractQueryParam(href, "tid") ?: continue

                val lastpost = row.firstMatch("td.lastpost") ?: continue
                val title = DiscuzTextDecoder.decodeDoubleEscapedEntities(link.text())
                val time = lastpost.text()
                val forumName = row.firstMatch("td.forum")?.text()

                add(
                    MyThreadItem(
                        tid = tid,
                        title = title,
                        time = time,
                        forumName = forumName,
                    ),
                )
            }
        }

        return MyThreadsPage(
            maxPage = maxPage,
            items = items,
            meta = meta,
            hasTable = true,
        )
    }

    fun parseMyReplies(html: String): MyRepliesPage = parseMyReplies(Jsoup.parse(html))

    fun parseMyReplies(doc: Document): MyRepliesPage {
        val meta = DiscuzCommonParser.parsePageMeta(doc)
        val table = doc.firstMatch("table.datatable")
            ?: return MyRepliesPage(maxPage = 1, items = emptyList(), meta = meta, hasTable = false)

        val maxPage = extractMaxPage(doc, "div.pages_btns div.pages")
        val rows = table.select("tr")

        var pending: PendingReplyHeader? = null
        val items = buildList {
            for (i in 1 until rows.size) {
                val row = rows[i]
                if (i % 2 == 1) {
                    pending = parseReplyHeaderRow(row)
                } else {
                    val header = pending ?: continue
                    pending = null
                    val replyText = row.firstMatch("th")?.text()?.let {
                        DiscuzTextDecoder.decodeDoubleEscapedEntities(it)
                    }
                    add(
                        MyReplyItem(
                            tid = header.tid,
                            pid = header.pid,
                            title = header.title,
                            replyText = replyText,
                            time = header.time,
                            forumName = header.forumName,
                        ),
                    )
                }
            }
        }

        return MyRepliesPage(
            maxPage = maxPage,
            items = items,
            meta = meta,
            hasTable = true,
        )
    }

    fun parseFavoritesList(html: String): FavoritesListPage = parseFavoritesList(Jsoup.parse(html))

    fun parseFavoritesList(doc: Document): FavoritesListPage {
        val meta = DiscuzCommonParser.parsePageMeta(doc)
        val maxPage = extractMaxPage(doc, "div.pages")

        val items = buildList {
            for (row in doc.select("table.datatable tbody tr")) {
                val th = row.firstMatch("th") ?: continue
                val link = th.firstMatch("a") ?: continue
                val tid = DiscuzUrlHelper.extractQueryParam(link.attr("href"), "tid").orEmpty()
                val title = DiscuzTextDecoder.decodeDoubleEscapedEntities(th.text())
                val time = row.firstMatch("td.lastpost")?.text()?.trim()
                val forumName = row.firstMatch("td.forum")?.text()?.trim()

                add(
                    FavoriteThreadItem(
                        tid = tid,
                        title = title,
                        time = time,
                        forumName = forumName,
                    ),
                )
            }
        }

        return FavoritesListPage(
            maxPage = maxPage,
            items = items,
            meta = meta,
        )
    }

    private fun parseReplyHeaderRow(row: org.jsoup.nodes.Element): PendingReplyHeader? {
        val th = row.firstMatch("th") ?: return null
        val links = th.select("a")
        if (links.size != 1) return null

        val link = links[0]
        val href = link.attr("href")
        if (!href.contains("redirect.php?goto=")) return null

        val lastpost = row.firstMatch("td.lastpost") ?: return null
        return PendingReplyHeader(
            tid = DiscuzUrlHelper.extractQueryParam(href, "ptid").orEmpty(),
            pid = DiscuzUrlHelper.extractQueryParam(href, "pid").orEmpty(),
            title = DiscuzTextDecoder.decodeDoubleEscapedEntities(link.text()),
            time = lastpost.text(),
            forumName = row.firstMatch("td.forum")?.text(),
        )
    }

    private fun extractMaxPage(doc: Document, pagesContainerSelector: String): Int {
        var lastPage = 1
        for (el in doc.select("$pagesContainerSelector a")) {
            val page = DiscuzUrlHelper.extractFirstIntOrZero(el.text())
            if (page > lastPage) lastPage = page
        }
        for (el in doc.select("$pagesContainerSelector strong")) {
            val page = DiscuzUrlHelper.extractFirstIntOrZero(el.text())
            if (page > lastPage) lastPage = page
        }
        return lastPage
    }

    private data class PendingReplyHeader(
        val tid: String,
        val pid: String,
        val title: String,
        val time: String,
        val forumName: String?,
    )
}
