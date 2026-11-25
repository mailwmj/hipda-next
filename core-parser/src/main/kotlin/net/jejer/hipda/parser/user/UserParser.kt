package net.jejer.hipda.parser.user

import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.common.DiscuzTextDecoder
import net.jejer.hipda.parser.common.DiscuzUrlHelper
import net.jejer.hipda.parser.common.firstMatch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 用户资料页与黑名单页纯 JVM 解析器（对照旧 `HiParser.java:726-784, 806-825`）。
 */
object UserParser {

    private const val EMPTY_BLACKLIST_MARKER = "暂无数据"
    private const val ERROR_BLACKLIST_DATA = "黑名单数据解析错误"
    private const val ERROR_INVALID_PAGE = "页面解析错误"
    private const val MAX_PROFILE_SECTIONS = 2

    fun parseUserInfo(html: String): UserProfile = parseUserInfo(Jsoup.parse(html))

    fun parseUserInfo(doc: Document): UserProfile {
        val meta = DiscuzCommonParser.parsePageMeta(doc)
        val username = doc.firstMatch("div#profilecontent div.itemtitle h1")
            ?.text()
            ?.trim()
            ?.ifEmpty { null }
        val isOnline = doc.firstMatch("div#profilecontent div.itemtitle img")
            ?.attr("src")
            ?.contains("online") == true
        val uid = doc.firstMatch("div#profilecontent div.itemtitle ul li")
            ?.let { DiscuzUrlHelper.middleString(it.text(), "(UID:", ")").trim().ifEmpty { null } }
        val avatarUrl = doc.firstMatch("div.side div.profile_side div.avatar img")
            ?.attr("src")
            ?.trim()
            ?.ifEmpty { null }
        val formhash = DiscuzCommonParser.parseLogoutFormhash(doc)

        val detailBuilder = StringBuilder()
        val titles = doc.select("h3.blocktitle")
        var sectionIndex = 0
        for (titleEl in titles) {
            detailBuilder.append(DiscuzTextDecoder.decodeDoubleEscapedEntities(titleEl.text())).append("\n\n")
            if (sectionIndex == 0) {
                for (detailEl in doc.select("div.main div.s_clear ul.commonlist li")) {
                    detailBuilder.append(DiscuzTextDecoder.decodeDoubleEscapedEntities(detailEl.text())).append('\n')
                }
            }
            sectionIndex++
            detailBuilder.append('\n')
            if (sectionIndex >= MAX_PROFILE_SECTIONS) {
                break
            }
        }

        return UserProfile(
            uid = uid,
            username = username,
            isOnline = isOnline,
            avatarUrl = avatarUrl,
            formhash = formhash,
            detail = detailBuilder.toString(),
            meta = meta,
        )
    }

    fun parseBlacklist(html: String): BlacklistParseResult = parseBlacklist(Jsoup.parse(html))

    fun parseBlacklist(doc: Document): BlacklistParseResult {
        val blacklistDivs = doc.select("div.blacklist")
        if (blacklistDivs.isEmpty()) {
            return BlacklistParseResult.InvalidPage(ERROR_INVALID_PAGE)
        }

        val usernames = LinkedHashSet<String>()
        for (anchor in doc.select("ul.commonlist a")) {
            val spaceUrl = anchor.attr("href")
            if (spaceUrl.contains("space.php")) {
                val username = DiscuzUrlHelper.extractQueryParam(spaceUrl, "username")
                if (!username.isNullOrEmpty()) {
                    usernames.add(username)
                }
            }
        }

        if (usernames.isEmpty()) {
            return if (blacklistDivs.text().contains(EMPTY_BLACKLIST_MARKER)) {
                BlacklistParseResult.Success(usernames = emptyList(), isEmptyConfirmed = true)
            } else {
                BlacklistParseResult.InvalidPage(ERROR_BLACKLIST_DATA)
            }
        }

        return BlacklistParseResult.Success(
            usernames = usernames.toList(),
            isEmptyConfirmed = false,
        )
    }
}
