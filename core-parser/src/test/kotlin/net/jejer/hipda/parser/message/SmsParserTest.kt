package net.jejer.hipda.parser.message

import net.jejer.hipda.parser.SampleFixtures
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmsParserTest {

    @Test
    fun parseSmsList_realSampleParsesAllConversationsAndMeta() {
        val doc = SampleFixtures.parseSampleDocument(SampleFixtures.SMS_LIST)
        val before = doc.outerHtml()

        val page = SmsParser.parseSmsList(doc)
        assertEquals(before, doc.outerHtml(), "SmsParser must not mutate Document")
        assertTrue(page.hasContainer)
        assertTrue(page.meta.session.isLoggedIn)
        assertEquals("10000", page.meta.session.uid)
        assertEquals("HiUser", page.meta.session.username)
        assertEquals(50, page.items.size)

        val first = page.items[0]
        assertEquals("20000", first.uid)
        assertEquals("HiPeer", first.author)
        assertEquals(
            "https://img02.4d4y.com/forum/uc_server/data/avatar/000/02/00/00_avatar_middle.jpg",
            first.avatarUrl,
        )
        assertEquals("2025-12-28 18:32", first.time)
        assertEquals("", first.summary)
        assertFalse(first.isNew)

        assertTrue(page.items.any { it.summary.startsWith("测试内容") })
    }

    @Test
    fun parseSmsList_handlesNewMarkerNoAvatarDoubleEscapedEmojiAndContainerStates() {
        val html = """
            <html><body>
              <ul class="pm_list">
                <li>
                  <a class="avatar"><img src="https://img02.4d4y.com/forum/uc_server/images/noavatar_small.gif" /></a>
                  <p class="cite"><cite><a href="space.php?uid=34567">Alice</a></cite> 2026-11-25 10:00 <img src="images/default/notice_newpm.gif" /></p>
                  <div class="summary">收到&amp;#128512;</div>
                </li>
                <li><p class="cite">缺少 cite 的残缺节点</p><div class="summary">ignored</div></li>
                <li><p class="cite"><cite><a href="space.php?uid=99999">NoSummary</a></cite></p></li>
              </ul>
            </body></html>
        """.trimIndent()

        val page = SmsParser.parseSmsList(html)
        assertTrue(page.hasContainer)
        assertEquals(1, page.items.size)
        val item = page.items.single()
        assertEquals("34567", item.uid)
        assertEquals("Alice", item.author)
        assertNull(item.avatarUrl)
        assertEquals("2026-11-25 10:00", item.time)
        assertEquals("收到😀", item.summary)
        assertTrue(item.isNew)

        val emptyContainer = SmsParser.parseSmsList("""<html><body><ul class="pm_list"></ul></body></html>""")
        assertTrue(emptyContainer.hasContainer)
        assertTrue(emptyContainer.items.isEmpty())

        val missingContainer = SmsParser.parseSmsList("""<html><body><p class="nodata">暂无短消息</p></body></html>""")
        assertFalse(missingContainer.hasContainer)
        assertTrue(missingContainer.items.isEmpty())
    }

    @Test
    fun parseSmsDetail_realSampleParsesMessagesFormhashAndRelativeOrAbsoluteAvatars() {
        val doc = SampleFixtures.parseSampleDocument(SampleFixtures.SMS_DETAIL)
        val before = doc.outerHtml()

        val defaultPage = SmsParser.parseSmsDetail(doc)
        assertEquals(before, doc.outerHtml())
        assertTrue(defaultPage.hasContainer)
        assertEquals("10000", defaultPage.myUid)
        assertEquals("HiUser", defaultPage.myUsername)
        assertEquals("00000000", defaultPage.formhash)
        assertEquals(9, defaultPage.messages.size)
        assertEquals(4, defaultPage.messages.count { it.isMine })
        assertEquals(5, defaultPage.messages.count { !it.isMine })

        val selfMsg = defaultPage.messages[0]
        assertEquals("10000", selfMsg.uid)
        assertEquals("HiUser", selfMsg.author)
        assertEquals("000/01/00/00_avatar_middle.jpg", selfMsg.avatarUrl)
        assertEquals("2025-6-21 21:56", selfMsg.time)
        assertEquals("测试内容测试内容测试内容测试内容测试", selfMsg.contentHtml)
        assertFalse(selfMsg.isNew)
        assertTrue(selfMsg.isMine)

        val peerMsg = defaultPage.messages[1]
        assertEquals("20000", peerMsg.uid)
        assertEquals("HiPeer", peerMsg.author)
        assertEquals("000/02/00/00_avatar_middle.jpg", peerMsg.avatarUrl)
        assertFalse(peerMsg.isMine)

        val withBase = SmsParser.parseSmsDetail(doc, avatarBaseUrl = "https://img02.4d4y.com/forum/uc_server/data/avatar/")
        assertEquals(
            "https://img02.4d4y.com/forum/uc_server/data/avatar/000/01/00/00_avatar_middle.jpg",
            withBase.messages[0].avatarUrl,
        )
    }

    @Test
    fun parseSmsDetail_preservesRichContentHtmlAndHandlesNewFlagAndMissingContainer() {
        val html = """
            <html><body>
              <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
              <ul class="pm_list">
                <li class="s_clear">
                  <a href="space.php?uid=20000" class="avatar"><img src="x.jpg" /></a>
                  <p class="cite"><cite>HiPeer</cite> 2026-11-25 12:00 <img src="images/default/notice_newpm.gif" /></p>
                  <div class="summary">第一行<br><a href="https://example.invalid/">链接</a></div>
                </li>
              </ul>
            </body></html>
        """.trimIndent()

        val page = SmsParser.parseSmsDetail(html)
        assertTrue(page.hasContainer)
        assertEquals(1, page.messages.size)
        assertTrue(page.messages[0].isNew)
        assertFalse(page.messages[0].isMine)
        assertTrue(page.messages[0].contentHtml.contains("<br>"))
        assertTrue(page.messages[0].contentHtml.contains("https://example.invalid/"))

        val empty = SmsParser.parseSmsDetail(Jsoup.parse("<html><body></body></html>"))
        assertFalse(empty.hasContainer)
        assertTrue(empty.messages.isEmpty())
    }

    @Test
    fun parseSmsSendResult_coversSuccessFailedWithRootTextEmptyAndFallback() {
        val successXml = """
            <?xml version="1.0" encoding="gbk"?>
            <root><![CDATA[<li class="s_clear self"><div class="summary">发送正文</div></li>]]></root>
        """.trimIndent()
        assertEquals(SmsSendResult.Success, SmsParser.parseSmsSendResult(successXml))

        val failedWithReason = """
            <?xml version="1.0" encoding="gbk"?>
            <root><![CDATA[两次发送短消息间隔太短，请稍候再试<script type="text/javascript">alert(1)</script>]]></root>
        """.trimIndent()
        assertEquals(
            SmsSendResult.Failed("两次发送短消息间隔太短，请稍候再试"),
            SmsParser.parseSmsSendResult(failedWithReason),
        )

        assertEquals(
            SmsSendResult.Failed("短消息发送失败 :  无返回结果"),
            SmsParser.parseSmsSendResult(""),
        )

        val failedEmptyRoot = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[<script>err()</script>]]></root>"""
        assertEquals(
            SmsSendResult.Failed("短消息发送失败."),
            SmsParser.parseSmsSendResult(failedEmptyRoot),
        )
    }
}
