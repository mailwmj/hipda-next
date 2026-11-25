package net.jejer.hipda.parser.message

import net.jejer.hipda.parser.SampleFixtures
import net.jejer.hipda.parser.common.PromptCounts
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotifyParserTest {

    @Test
    fun parseNotifyList_realEmptySampleParsesNoFeedContainerAndZeroPromptCounts() {
        val doc = SampleFixtures.parseSampleDocument(SampleFixtures.NOTIFY_LIST)
        val before = doc.outerHtml()

        val page = NotifyParser.parseNotifyList(doc)
        assertEquals(before, doc.outerHtml())
        assertFalse(page.hasFeedContainer)
        assertTrue(page.items.isEmpty())
        assertTrue(page.meta.session.isLoggedIn)
        assertEquals("10000", page.meta.session.uid)
        assertEquals(PromptCounts(0, 0, 0, 0, 0), page.meta.promptCounts)
        assertEquals(0, page.meta.promptCounts.notifyTotal)
    }

    @Test
    fun parseNotifyList_syntheticFeedCoversAllFiveKindsWithoutMutatingDom() {
        val html = """
            <html><body>
              <ul class="feed">
                <li>
                  <div class="f_thread">
                    <a href="space.php?uid=11111">Alice</a>、<a href="space.php?uid=11112">Alex</a> 回复了您的帖子
                    <a href="redirect.php?goto=findpost&amp;pid=9001&amp;ptid=8001">普通主题&amp;#128512;</a>
                    <em>2026-11-20 10:00</em>
                  </div>
                </li>
                <li>
                  <div class="f_thread">
                    <a href="space.php?uid=11113">Amy</a> 回复了您关注的主题
                    <a href="redirect.php?goto=findpost&amp;pid=9009&amp;ptid=8009">关注主题</a>
                    <em>2026-11-20 10:05</em>
                  </div>
                </li>
                <li>
                  <div class="f_quote">
                    <a href="space.php?uid=22222">Bob</a> 引用了您在
                    <a href="viewthread.php?tid=8002">引用测试帖</a> 中的回复
                    <a href="redirect.php?goto=findpost&amp;pid=9002&amp;ptid=8002">查看</a>
                    <em>2026-11-20 11:00</em>
                    <dl class="summary"><dd>原帖&amp;#127822;</dd><dd>回复&amp;#128076;</dd></dl>
                  </div>
                </li>
                <li>
                  <div class="f_reply">
                    <img src="images/default/notice_newpm.gif" />
                    <a href="space.php?uid=22223">Carol</a> 回复了您在
                    <a href="viewthread.php?tid=8003">回复测试帖</a> 中的帖子
                    <a href="redirect.php?goto=findpost&amp;pid=9003&amp;ptid=8003">查看</a>
                    <em>2026-11-20 12:00</em>
                    <div class="summary">单段摘要文本</div>
                  </div>
                </li>
                <li>
                  <div class="f_manage">
                    <img src="images/default/notice_newpm.gif" />
                    您的主题 <a href="viewthread.php?tid=8004&amp;extra=page%3D1">系统通知帖</a> 已被加精 <em>2026-11-20 13:00</em>
                  </div>
                </li>
                <li>
                  <div class="f_buddy">
                    <img src="images/default/notice_newpm.gif" />
                    <a href="space.php?uid=33333">Dave</a> 请求加您为好友
                    <a href="my.php?item=buddylist&amp;newbuddyid=33333">批准申请</a>
                    <em>2026-11-20 14:00</em>
                  </div>
                </li>
                <li><div class="f_thread"><a href="redirect.php?pid=1&amp;ptid=2">缺em被跳过</a></div></li>
                <li><div class="f_unknown">未知类型跳过</div></li>
              </ul>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val before = doc.outerHtml()
        val page = NotifyParser.parseNotifyList(doc, avatarBaseUrl = "https://img.example.invalid/avatar/")

        assertEquals(before, doc.outerHtml(), "NotifyParser must never mutate caller Document DOM (especially f_buddy)")
        assertEquals(2, doc.select("div.f_buddy a").size, "Second anchor inside f_buddy must remain in original DOM")
        assertTrue(page.hasFeedContainer)
        assertEquals(6, page.items.size)

        // 0: f_thread 普通主题
        val t0 = page.items[0]
        assertEquals(NotifyKind.THREAD, t0.kind)
        assertEquals("普通主题😀", t0.title)
        assertEquals("Alice Alex 回复了您的帖子 ", t0.info)
        assertNull(t0.author)
        assertNull(t0.authorUid)
        assertNull(t0.avatarUrl)
        assertEquals("8001", t0.tid)
        assertEquals("9001", t0.pid)
        assertEquals("2026-11-20 10:00", t0.time)
        assertTrue(t0.isNew)

        // 1: f_thread 关注主题
        val t1 = page.items[1]
        assertEquals(NotifyKind.THREAD, t1.kind)
        assertEquals("关注主题", t1.title)
        assertEquals("Amy 回复了您关注的主题", t1.info)

        // 2: f_quote 双 dd
        val t2 = page.items[2]
        assertEquals(NotifyKind.QUOTE, t2.kind)
        assertEquals("引用测试帖", t2.title)
        assertEquals("Bob", t2.author)
        assertEquals("22222", t2.authorUid)
        assertEquals("https://img.example.invalid/avatar/000/02/22/22_avatar_middle.jpg", t2.avatarUrl)
        assertEquals("8002", t2.tid)
        assertEquals("9002", t2.pid)
        assertEquals("2026-11-20 11:00", t2.time)
        assertFalse(t2.isNew)
        assertEquals("原帖🍎", t2.quoteOriginal)
        assertEquals("回复👌", t2.quoteReply)
        assertEquals("<u>您的帖子:</u>原帖🍎<br><u>Bob 说:</u>回复👌", t2.info)

        // 3: f_reply 单摘要
        val t3 = page.items[3]
        assertEquals(NotifyKind.REPLY, t3.kind)
        assertEquals("回复测试帖", t3.title)
        assertEquals("Carol", t3.author)
        assertEquals("22223", t3.authorUid)
        assertEquals("单段摘要文本", t3.info)
        assertNull(t3.quoteOriginal)
        assertNull(t3.quoteReply)
        assertTrue(t3.isNew)

        // 4: f_manage 系统信息（刻意去 Android 本地路径，avatarUrl = null）
        val t4 = page.items[4]
        assertEquals(NotifyKind.SYSTEM, t4.kind)
        assertEquals("系统信息", t4.title)
        assertEquals("您的主题 系统通知帖 已被加精 2026-11-20 13:00", t4.info)
        assertNull(t4.author)
        assertNull(t4.authorUid)
        assertNull(t4.avatarUrl)
        assertEquals("8004", t4.tid)
        assertNull(t4.pid)
        assertNull(t4.time)
        assertTrue(t4.isNew)

        // 5: f_buddy 好友信息
        val t5 = page.items[5]
        assertEquals(NotifyKind.FRIEND, t5.kind)
        assertEquals("好友信息", t5.title)
        assertEquals("Dave 请求加您为好友 2026-11-20 14:00", t5.info)
        assertEquals("Dave", t5.author)
        assertEquals("33333", t5.authorUid)
        assertEquals("https://img.example.invalid/avatar/000/03/33/33_avatar_middle.jpg", t5.avatarUrl)
        assertNull(t5.tid)
        assertNull(t5.pid)
        assertNull(t5.time)
        assertTrue(t5.isNew)
    }

    @Test
    fun parseNotifyList_emptyFeedContainerIsDistinguishedFromMissingContainer() {
        val page = NotifyParser.parseNotifyList("""<html><body><ul class="feed"></ul></body></html>""")
        assertTrue(page.hasFeedContainer)
        assertTrue(page.items.isEmpty())
    }
}
