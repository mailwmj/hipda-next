package net.jejer.hipda.parser.message

import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.common.DiscuzTextDecoder
import net.jejer.hipda.parser.common.DiscuzUrlHelper
import net.jejer.hipda.parser.common.firstMatch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * 短消息列表、短消息对话详情与发送结果 XML 的纯 JVM 解析器
 * （对照旧 `HiParser.java:212-276, 454-523` 与 `PostSmsAsyncTask.java:52-109`）。
 */
object SmsParser {

    private const val EMPTY_RESPONSE_ERROR = "短消息发送失败 :  无返回结果"
    private const val DEFAULT_SEND_ERROR = "短消息发送失败."
    private const val SUMMARY_CLASS_MARKER = "class=\"summary\""

    fun parseSmsList(html: String): SmsListPage = parseSmsList(Jsoup.parse(html))

    fun parseSmsList(doc: Document): SmsListPage {
        val meta = DiscuzCommonParser.parsePageMeta(doc)
        val pmList = doc.firstMatch("ul.pm_list")
            ?: return SmsListPage(items = emptyList(), meta = meta, hasContainer = false)

        val items = buildList {
            for (li in pmList.select("li")) {
                val pCite = li.firstMatch("p.cite") ?: continue
                val cite = pCite.firstMatch("cite") ?: continue
                val authorLink = cite.firstMatch("a") ?: continue
                val summaryElement = li.firstMatch("div.summary") ?: continue

                val avatarSrc = li.firstMatch("a.avatar img")?.attr("src")
                val uid = DiscuzUrlHelper.extractQueryParam(authorLink.attr("href"), "uid").orEmpty()
                val isNew = pCite.select("img").any {
                    it.attr("src").contains(DiscuzUrlHelper.NEW_PM_IMAGE_MARKER)
                }

                add(
                    SmsConversationItem(
                        uid = uid,
                        author = cite.text(),
                        avatarUrl = DiscuzUrlHelper.normalizeAvatarUrl(avatarSrc),
                        time = pCite.ownText(),
                        summary = DiscuzTextDecoder.decodeDoubleEscapedEntities(summaryElement.text()),
                        isNew = isNew,
                    ),
                )
            }
        }
        return SmsListPage(items = items, meta = meta, hasContainer = true)
    }

    fun parseSmsDetail(html: String, avatarBaseUrl: String? = null): SmsDetailPage =
        parseSmsDetail(Jsoup.parse(html), avatarBaseUrl)

    fun parseSmsDetail(doc: Document, avatarBaseUrl: String? = null): SmsDetailPage {
        val meta = DiscuzCommonParser.parsePageMeta(doc)
        val formhash = doc.firstMatch("input#formhash")?.attr("value")?.trim()?.ifEmpty { null }
            ?: DiscuzCommonParser.parseFormhash(doc)

        val myLink = doc.firstMatch("#umenu cite a.noborder")
        val myUid = if (myLink != null) {
            DiscuzUrlHelper.extractQueryParam(myLink.attr("href"), "uid")
        } else {
            meta.session.uid
        }
        val myUsername = if (myLink != null) {
            myLink.text()
        } else {
            meta.session.username
        }

        val smsElements = doc.select("li.s_clear")
        if (smsElements.isEmpty()) {
            return SmsDetailPage(
                myUid = myUid,
                myUsername = myUsername,
                formhash = formhash,
                messages = emptyList(),
                meta = meta,
                hasContainer = false,
            )
        }

        val messages = buildList {
            for (smsEl in smsElements) {
                val pCite = smsEl.firstMatch("p.cite") ?: continue
                val cite = pCite.firstMatch("cite") ?: continue
                val summaryEl = smsEl.firstMatch("div.summary") ?: continue

                val author = cite.text()
                val isMine = (myUsername != null && author == myUsername) || smsEl.hasClass("self")
                val avatarEl = smsEl.firstMatch("a.avatar")
                val uid: String
                val avatarUrl: String?
                if (avatarEl != null) {
                    uid = if (myUsername != null && author == myUsername) {
                        myUid.orEmpty()
                    } else {
                        DiscuzUrlHelper.extractQueryParam(avatarEl.attr("href"), "uid").orEmpty()
                    }
                    avatarUrl = DiscuzUrlHelper.avatarUrlForUid(uid, avatarBaseUrl)
                } else {
                    uid = ""
                    avatarUrl = null
                }

                val isNew = pCite.select("img").any {
                    it.attr("src").contains(DiscuzUrlHelper.NEW_PM_IMAGE_MARKER)
                }

                add(
                    SmsMessageItem(
                        uid = uid,
                        author = author,
                        avatarUrl = avatarUrl,
                        time = pCite.ownText(),
                        contentHtml = summaryEl.html(),
                        isNew = isNew,
                        isMine = isMine,
                    ),
                )
            }
        }

        return SmsDetailPage(
            myUid = myUid,
            myUsername = myUsername,
            formhash = formhash,
            messages = messages,
            meta = meta,
            hasContainer = true,
        )
    }

    fun parseSmsSendResult(xml: String): SmsSendResult {
        if (xml.isEmpty()) {
            return SmsSendResult.Failed(EMPTY_RESPONSE_ERROR)
        }
        if (xml.contains(SUMMARY_CLASS_MARKER)) {
            return SmsSendResult.Success
        }
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val rootText = doc.select("root").lastOrNull()?.text().orEmpty()
        val fragment = Jsoup.parseBodyFragment(rootText)
        fragment.select("script, style").remove()
        val cleaned = fragment.body().text().trim()
        val decoded = DiscuzTextDecoder.decodeDoubleEscapedEntities(cleaned)
        return if (decoded.isNotEmpty()) {
            SmsSendResult.Failed(decoded)
        } else {
            SmsSendResult.Failed(DEFAULT_SEND_ERROR)
        }
    }
}
