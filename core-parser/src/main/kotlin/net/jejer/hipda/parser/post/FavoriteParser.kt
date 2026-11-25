package net.jejer.hipda.parser.post

import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzTextDecoder
import net.jejer.hipda.parser.common.DiscuzUrlHelper
import net.jejer.hipda.parser.common.firstMatch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

data class FavoriteIdsPage(
    val tids: Set<String>,
    val lastPage: Int,
    val meta: DiscuzPageMeta,
)

data class FavoriteActionResult(
    val message: String,
)

/**
 * 收藏/关注 ID 列表页与增删 XML 响应纯 JVM 解析器
 * （对照旧 `FavoriteHelper.java:104-133, 218-257`）。
 */
object FavoriteParser {

    fun parseFavoriteIdsPage(html: String): FavoriteIdsPage = parseFavoriteIdsPage(Jsoup.parse(html))

    fun parseFavoriteIdsPage(doc: Document): FavoriteIdsPage {
        val meta = DiscuzCommonParser.parsePageMeta(doc)
        var lastPage = 1
        val divPage = doc.firstMatch("div.pages")
        if (divPage != null) {
            for (el in divPage.select("a")) {
                val page = DiscuzUrlHelper.extractFirstIntOrZero(el.text())
                if (page > lastPage) lastPage = page
            }
            for (el in divPage.select("strong")) {
                val page = DiscuzUrlHelper.extractFirstIntOrZero(el.text())
                if (page > lastPage) lastPage = page
            }
        }

        val tids = buildSet {
            for (checkbox in doc.select("input.checkbox[name=delete[]]")) {
                val tid = checkbox.attr("value").trim()
                if (DiscuzUrlHelper.isValidId(tid)) {
                    add(tid)
                }
            }
        }

        return FavoriteIdsPage(
            tids = tids,
            lastPage = lastPage,
            meta = meta,
        )
    }

    fun parseFavoriteActionXml(xml: String): FavoriteActionResult {
        if (xml.isBlank()) {
            return FavoriteActionResult(message = "")
        }
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val rootText = doc.select("root").lastOrNull()?.text().orEmpty()
        val fragment = Jsoup.parseBodyFragment(rootText)
        fragment.select("script, style").remove()
        val cleaned = DiscuzTextDecoder.decodeDoubleEscapedEntities(fragment.body().text().trim())
        return FavoriteActionResult(message = cleaned)
    }
}
