package net.jejer.hipda.parser.common

import net.jejer.hipda.parser.SampleFixtures
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscuzCommonParserTest {

    @Test
    fun urlHelper_middleStringAndExtractQueryParamCoverEdgeCases() {
        assertEquals("20000", DiscuzUrlHelper.middleString("space.php?uid=20000&foo=1", "uid=", "&"))
        assertEquals("20000", DiscuzUrlHelper.middleString("space.php?uid=20000", "uid=", "&"))
        assertEquals("red", DiscuzUrlHelper.middleString("color: red", "color:", "").trim())
        assertEquals("", DiscuzUrlHelper.middleString("space.php", "uid=", "&"))
        assertEquals("", DiscuzUrlHelper.middleString(null, "uid=", "&"))
        assertEquals("", DiscuzUrlHelper.middleString("space.php?uid=&x=1", "uid=", "&"))

        assertEquals("20000", DiscuzUrlHelper.extractQueryParam("space.php?uid=20000", "uid"))
        assertEquals("20000", DiscuzUrlHelper.extractQueryParam("pm.php?uid=20000&amp;daterange=5#new", "uid"))
        assertEquals("5", DiscuzUrlHelper.extractQueryParam("pm.php?uid=20000&amp;daterange=5#new", "daterange"))
        assertNull(DiscuzUrlHelper.extractQueryParam("my.php?item=buddylist&newbuddyid=424242", "uid"))
        assertEquals("424242", DiscuzUrlHelper.extractQueryParam("my.php?item=buddylist&newbuddyid=424242", "newbuddyid"))
        assertEquals("00000000", DiscuzUrlHelper.extractQueryParam("logging.php?action=logout&formhash=00000000", "formhash"))
        assertNull(DiscuzUrlHelper.extractQueryParam("space.php?uid=", "uid"))
        assertNull(DiscuzUrlHelper.extractQueryParam("", "uid"))
    }

    @Test
    fun urlHelper_avatarPathsAndNormalizationBehaveWithoutHardcodedHost() {
        assertEquals("000/00/00/01_avatar_middle.jpg", DiscuzUrlHelper.avatarRelativePathForUid("1"))
        assertEquals("000/01/00/00_avatar_middle.jpg", DiscuzUrlHelper.avatarRelativePathForUid("10000"))
        assertEquals("123/45/67/89_avatar_middle.jpg", DiscuzUrlHelper.avatarRelativePathForUid("123456789"))
        assertNull(DiscuzUrlHelper.avatarRelativePathForUid(""))
        assertNull(DiscuzUrlHelper.avatarRelativePathForUid("1234567890"))
        assertNull(DiscuzUrlHelper.avatarRelativePathForUid("100a"))

        assertEquals("000/01/00/00_avatar_middle.jpg", DiscuzUrlHelper.avatarUrlForUid("10000"))
        assertEquals(
            "https://img.example.invalid/avatar/000/01/00/00_avatar_middle.jpg",
            DiscuzUrlHelper.avatarUrlForUid("10000", "https://img.example.invalid/avatar"),
        )
        assertEquals(
            "https://img.example.invalid/avatar/000/01/00/00_avatar_middle.jpg",
            DiscuzUrlHelper.avatarUrlForUid("10000", "https://img.example.invalid/avatar/"),
        )
        assertNull(DiscuzUrlHelper.avatarUrlForUid("bad", "https://img.example.invalid/avatar/"))

        assertEquals(
            "https://img02.4d4y.com/forum/uc_server/data/avatar/000/02/00/00_avatar_middle.jpg",
            DiscuzUrlHelper.normalizeAvatarUrl("https://img02.4d4y.com/forum/uc_server/data/avatar/000/02/00/00_avatar_small.jpg"),
        )
        assertNull(DiscuzUrlHelper.normalizeAvatarUrl("https://img02.4d4y.com/forum/uc_server/images/noavatar_small.gif"))
        assertNull(DiscuzUrlHelper.normalizeAvatarUrl("https://img02.4d4y.com/forum/NOAVATAR.gif"))
        assertNull(DiscuzUrlHelper.normalizeAvatarUrl(""))
        assertNull(DiscuzUrlHelper.normalizeAvatarUrl("   "))
        assertNull(DiscuzUrlHelper.normalizeAvatarUrl(null))

        assertTrue(DiscuzUrlHelper.isValidId("12345"))
        assertFalse(DiscuzUrlHelper.isValidId("0"))
        assertFalse(DiscuzUrlHelper.isValidId("-1"))
        assertFalse(DiscuzUrlHelper.isValidId("abc"))
        assertEquals(42, DiscuzUrlHelper.parseIntOrZero(" 42 "))
        assertEquals(0, DiscuzUrlHelper.parseIntOrZero("abc"))
        assertEquals(123, DiscuzUrlHelper.extractFirstIntOrZero("共 123 页"))
    }

    @Test
    fun textDecoder_decodesDoubleEscapedDecimalAndHexEntitiesIncludingSupplementaryEmoji() {
        // 标准十进制 & 十六进制（含补充平面代理对与组合 Emoji）
        assertEquals("你好😀世界", DiscuzTextDecoder.decodeDoubleEscapedEntities("你好&#128512;世界"))
        assertEquals("😀 & 😀", DiscuzTextDecoder.decodeDoubleEscapedEntities("&#x1F600; & &#X1f600;"))
        assertEquals("A中", DiscuzTextDecoder.decodeDoubleEscapedEntities("&#65;&#x4E2D;"))
        assertEquals("👨‍💻", DiscuzTextDecoder.decodeDoubleEscapedEntities("&#128104;&#8205;&#128187;"))

        // 模拟 Jsoup 解析真实 Discuz! 二次转义 HTML 后的解码链路
        val jsoupDecoded = Jsoup.parse("<p>好的&amp;#128076; 苹果&amp;#127822; 便便&amp;#128169;</p>").firstMatch("p")!!.text()
        assertEquals("好的&#128076; 苹果&#127822; 便便&#128169;", jsoupDecoded)
        assertEquals("好的👌 苹果🍎 便便💩", DiscuzTextDecoder.decodeDoubleEscapedEntities(jsoupDecoded))

        // 验证真机样本中实测存在的二次转义补充平面 Emoji
        val threadP2 = SampleFixtures.parseSampleDocument("thread_detail/thread_p2.html")
        val emojiPost = threadP2.firstMatch("#postmessage_53529502")!!.text()
        assertEquals("好的👌", DiscuzTextDecoder.decodeDoubleEscapedEntities(emojiPost))

        val forumP1 = SampleFixtures.parseSampleDocument("thread_list/forum_2_p1_lastpost.html")
        val appleTitle = forumP1.firstMatch("#thread_3477944 a")!!.text()
        assertEquals(
            "Apple Music 100 best albums 🍎 史上百大专辑",
            DiscuzTextDecoder.decodeDoubleEscapedEntities(appleTitle),
        )
    }

    @Test
    fun textDecoder_preservesInvalidCodePointsMalformedEntitiesAndNamedHtmlEntities() {
        val inputs = listOf(
            "&#0;",
            "&#x0;",
            "&#1114112;",
            "&#x110000;",
            "&#999999999999999;",
            "&#55296;",
            "&#57343;",
            "&#xD800;",
            "&#xDFFF;",
            "&#128512",
            "&#;",
            "&#x;",
            "&#xZZ;",
            "&#-1;",
            "&lt;script&gt;alert(1)&lt;/script&gt;",
            "&quot;hello&quot; &amp; &nbsp;",
        )
        for (raw in inputs) {
            assertEquals(raw, DiscuzTextDecoder.decodeDoubleEscapedEntities(raw), "Must preserve raw input: $raw")
        }
    }

    @Test
    fun commonParser_parsesRealSamplesAndDoesNotMutateDocumentDom() {
        val loginDoc = SampleFixtures.parseSampleDocument(SampleFixtures.LOGIN_PAGE)
        val loginBefore = loginDoc.outerHtml()
        val loginMeta = DiscuzCommonParser.parsePageMeta(loginDoc)
        assertEquals(loginBefore, loginDoc.outerHtml())
        assertEquals(DiscuzSessionInfo(isLoggedIn = true, uid = "10000", username = "HiUser"), loginMeta.session)
        assertNull(DiscuzCommonParser.parseFormhash(loginDoc))
        assertEquals("00000000", DiscuzCommonParser.parseLogoutFormhash(loginDoc))
        assertEquals("00000000", loginMeta.formhash)
        assertNull(loginMeta.errorMessage)
        assertEquals(PromptCounts(0, 0, 0, 0, 0), loginMeta.promptCounts)
        assertEquals(0, loginMeta.promptCounts.notifyTotal)

        val smsDetailDoc = SampleFixtures.parseSampleDocument(SampleFixtures.SMS_DETAIL)
        assertEquals("00000000", DiscuzCommonParser.parseFormhash(smsDetailDoc))
    }

    @Test
    fun commonParser_parsesErrorMessageWithoutMutatingDomAndParsesPromptCounts() {
        val html = """
            <html><body>
              <div id="umenu">
                <a href="register.php">注册</a>
                <a href="logging.php?action=login">登录</a>
              </div>
              <div class="promptcontent">
                <ul>
                  <li><a id="prompt_pm">私人消息 (2)</a></li>
                  <li><a id="prompt_announcepm">公共消息 (1)</a></li>
                  <li><a id="prompt_systempm">系统消息 (3)</a></li>
                  <li><a id="prompt_friend">好友消息 (4)</a></li>
                  <li><a id="prompt_threads">帖子消息 (5)</a></li>
                </ul>
              </div>
              <div class="alert_error">
                <p>您无权进行当前操作&amp;#128512; <a href="javascript:history.back()">[ 点击这里返回上一页 ]</a></p>
              </div>
              <input type="hidden" id="formhash" value="deadbeef" />
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val snapshotBefore = doc.outerHtml()
        val meta = DiscuzCommonParser.parsePageMeta(doc)

        assertEquals(snapshotBefore, doc.outerHtml(), "Parser must never mutate caller Document DOM")
        assertEquals(1, doc.select("div.alert_error a").size, "Link inside alert_error must remain intact")
        assertEquals(DiscuzSessionInfo(isLoggedIn = false, uid = null, username = null), meta.session)
        assertEquals("deadbeef", meta.formhash)
        assertEquals("您无权进行当前操作😀", meta.errorMessage)
        assertEquals(PromptCounts(pm = 2, announcePm = 1, systemPm = 3, friend = 4, thread = 5), meta.promptCounts)
        assertEquals(12, meta.promptCounts.notifyTotal)
    }

    @Test
    fun commonParser_handlesEmptyAndMalformedHtmlGracefully() {
        val meta = DiscuzCommonParser.parsePageMeta("")
        assertEquals(DiscuzSessionInfo(false, null, null), meta.session)
        assertNull(meta.formhash)
        assertNull(meta.errorMessage)
        assertEquals(PromptCounts(), meta.promptCounts)

        val negativePrompt = Jsoup.parse("""<div class="promptcontent"><a id="prompt_threads">帖子消息 (-3)</a><a id="prompt_friend">好友消息 (abc)</a></div>""")
        assertEquals(PromptCounts(), DiscuzCommonParser.parsePromptCounts(negativePrompt))
    }
}
