package net.jejer.hipda.parser.user

import net.jejer.hipda.parser.SampleFixtures
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UserParserTest {

    @Test
    fun parseUserInfo_realSampleParsesAllFieldsWithoutMutatingDom() {
        val doc = SampleFixtures.parseSampleDocument(SAMPLE_USER_PROFILE)
        val before = doc.outerHtml()

        val profile = UserParser.parseUserInfo(doc)
        assertEquals(before, doc.outerHtml(), "UserParser.parseUserInfo must not mutate Document")
        assertEquals("10000", profile.uid)
        assertEquals("HiUser", profile.username)
        assertTrue(profile.isOnline)
        assertEquals(
            "https://img02.4d4y.com/forum/uc_server/data/avatar/000/01/00/00_avatar_middle.jpg",
            profile.avatarUrl,
        )
        assertEquals("00000000", profile.formhash)
        assertTrue(profile.meta.session.isLoggedIn)
        assertTrue(profile.detail.contains("用户组: 初级会员"))
        assertTrue(profile.detail.contains("注册日期: 2011-4-13"))
        assertTrue(profile.detail.contains("积分: 6"))
        assertFalse(profile.detail.contains("信用评价"), "Only first 2 blocktitle sections should be included")
    }

    @Test
    fun parseUserInfo_offlineAndMinimalPageReturnsNullOptionalFields() {
        val html = """
            <html><body>
              <div id="profilecontent">
                <div class="itemtitle">
                  <h1>OfflineUser<img src="images/default/offline_buddy.gif" /></h1>
                  <ul><li>(UID: 20202)</li></ul>
                </div>
              </div>
            </body></html>
        """.trimIndent()
        val profile = UserParser.parseUserInfo(html)
        assertEquals("20202", profile.uid)
        assertEquals("OfflineUser", profile.username)
        assertFalse(profile.isOnline)
        assertNull(profile.avatarUrl)
        assertNull(profile.formhash)
        assertEquals("", profile.detail)
    }

    @Test
    fun parseBlacklist_coversPopulatedDeduplicatedEmptyConfirmedAndInvalidPages() {
        val populatedHtml = """
            <html><body>
              <div class="blacklist">
                <ul class="commonlist">
                  <li><a href="space.php?username=Alice">Alice</a></li>
                  <li><a href="space.php?username=Bob&amp;extra=1">Bob</a></li>
                  <li><a href="space.php?username=Alice">Alice 重复</a></li>
                  <li><a href="space.php?uid=99999">无 username 参数应忽略</a></li>
                  <li><a href="other.php?username=Ignored">Ignored</a></li>
                </ul>
              </div>
            </body></html>
        """.trimIndent()
        val populatedDoc = Jsoup.parse(populatedHtml)
        val before = populatedDoc.outerHtml()
        val populated = UserParser.parseBlacklist(populatedDoc)
        assertEquals(before, populatedDoc.outerHtml())
        assertEquals(
            BlacklistParseResult.Success(usernames = listOf("Alice", "Bob"), isEmptyConfirmed = false),
            populated,
        )

        val emptyConfirmedHtml = """
            <html><body>
              <div class="blacklist"><p class="nodata">暂无数据</p></div>
            </body></html>
        """.trimIndent()
        assertEquals(
            BlacklistParseResult.Success(usernames = emptyList(), isEmptyConfirmed = true),
            UserParser.parseBlacklist(emptyConfirmedHtml),
        )

        val emptyUnconfirmedHtml = """
            <html><body>
              <div class="blacklist"><ul class="commonlist"></ul></div>
            </body></html>
        """.trimIndent()
        assertEquals(
            BlacklistParseResult.InvalidPage("黑名单数据解析错误"),
            UserParser.parseBlacklist(emptyUnconfirmedHtml),
        )

        val missingContainerHtml = "<html><body><div class=\"alert_error\">请先登录</div></body></html>"
        assertEquals(
            BlacklistParseResult.InvalidPage("页面解析错误"),
            UserParser.parseBlacklist(missingContainerHtml),
        )
    }

    companion object {
        private const val SAMPLE_USER_PROFILE = "user_info/user_profile.html"
    }
}
