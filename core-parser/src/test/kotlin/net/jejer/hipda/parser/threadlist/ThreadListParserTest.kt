package net.jejer.hipda.parser.threadlist

import net.jejer.hipda.parser.SampleFixtures
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadListParserTest {

    @Test
    fun parse_realSamplesParseAllThreadsEmojisPaginationAndDoNotMutateDom() {
        // 1. forum_2_p1_lastpost.html：第 1 页、含双重转义 Emoji 🍎、彩色标题、附图、多页 threadpages、folder_new/lock
        val p1Doc = SampleFixtures.parseSampleDocument(SAMPLE_FORUM2_P1_LASTPOST)
        val p1Before = p1Doc.outerHtml()
        val p1 = ThreadListParser.parse(p1Doc)

        assertEquals(p1Before, p1Doc.outerHtml(), "ThreadListParser must not mutate Document")
        assertTrue(p1.hasThreadRows)
        assertEquals(1, p1.page)
        assertEquals(1000, p1.lastPage)
        assertEquals(75, p1.threads.size)
        assertTrue(p1.meta.session.isLoggedIn)
        assertEquals("10000", p1.meta.session.uid)
        assertEquals("HiUser", p1.meta.session.username)

        val first = p1.threads[0]
        assertEquals("3057651", first.tid)
        assertEquals("hipda已迁移到新域名4d4y", first.title)
        assertEquals("#EE1B2E", first.titleColor)
        assertNull(first.typeName)
        assertFalse(first.isSticky)
        assertFalse(first.isNew)
        assertFalse(first.isPoll)
        assertEquals("4d4y", first.author)
        assertEquals("29", first.authorId)
        assertEquals("000/00/00/29_avatar_middle.jpg", first.avatarUrl)
        assertEquals("2022-6-13", first.timeCreate)
        assertEquals("2022-6-13 22:57", first.timeUpdate)
        assertEquals("4d4y", first.lastPostAuthor)
        assertEquals("0", first.replyCountText)
        assertEquals(0, first.replyCount)
        assertEquals("110414", first.viewCountText)
        assertEquals(110414, first.viewCount)
        assertFalse(first.withPic)
        assertEquals(1, first.maxPage)

        val appleThread = p1.threads.first { it.tid == "3477944" }
        assertEquals("Apple Music 100 best albums 🍎 史上百大专辑", appleThread.title)

        val multipageThread = p1.threads.first { it.tid == "2602293" }
        assertEquals(10, multipageThread.maxPage)
        assertEquals("#EE1B2E", multipageThread.titleColor)
        assertTrue(p1.threads.any { it.withPic })
        assertTrue(p1.threads.any { it.isNew })

        // 2. forum_2_p2_lastpost.html：第 2 页、含双重转义 Emoji 💩、彩色标题 #3C9D40、多页 + 附图
        val p2Doc = SampleFixtures.parseSampleDocument(SAMPLE_FORUM2_P2_LASTPOST)
        val p2Before = p2Doc.outerHtml()
        val p2 = ThreadListParser.parse(p2Doc, avatarBaseUrl = "https://img02.4d4y.com/forum/uc_server/data/avatar/")

        assertEquals(p2Before, p2Doc.outerHtml(), "ThreadListParser must not mutate Document")
        assertTrue(p2.hasThreadRows)
        assertEquals(2, p2.page)
        assertEquals(1000, p2.lastPage)
        assertEquals(75, p2.threads.size)
        assertEquals("10000", p2.meta.session.uid)

        val poopThread = p2.threads.first { it.tid == "3477896" }
        assertEquals("OPPO的自动指令真是一坨💩", poopThread.title)

        val suzhouThread = p2.threads.first { it.tid == "3259384" }
        assertEquals("#3C9D40", suzhouThread.titleColor)
        assertTrue(suzhouThread.withPic)
        assertEquals(7, suzhouThread.maxPage)
        assertEquals(
            "https://img02.4d4y.com/forum/uc_server/data/avatar/000/14/16/34_avatar_middle.jpg",
            suzhouThread.avatarUrl,
        )

        // 3. forum_2_p1_dateline.html：按发帖时间排序
        val datelineDoc = SampleFixtures.parseSampleDocument(SAMPLE_FORUM2_P1_DATELINE)
        val datelineBefore = datelineDoc.outerHtml()
        val dateline = ThreadListParser.parse(datelineDoc)

        assertEquals(datelineBefore, datelineDoc.outerHtml(), "ThreadListParser must not mutate Document")
        assertTrue(dateline.hasThreadRows)
        assertEquals(1, dateline.page)
        assertEquals(1000, dateline.lastPage)
        assertEquals(75, dateline.threads.size)
        assertEquals("3057651", dateline.threads[0].tid)
        assertEquals("3478053", dateline.threads[2].tid)
    }

    @Test
    fun parse_syntheticStickyPollColorTypeMultipageAttachAndLastPage() {
        val html = """
            <html><body>
              <div id="umenu">
                <cite><a href="space.php?uid=10000">HiUser</a></cite>
                <a href="logging.php?action=logout&amp;formhash=abcd1234">退出</a>
              </div>
              <div class="pages_btns">
                <div class="pages">
                  <a href="forumdisplay.php?fid=6&amp;page=1">1</a>
                  <a href="forumdisplay.php?fid=6&amp;page=2">2</a>
                  <strong>3</strong>
                </div>
              </div>
              <table>
                <tbody id="stickthread_9001">
                  <tr>
                    <td class="folder"><a href="viewthread.php?tid=9001"><img src="images/default/pin_3.gif" /></a></td>
                    <td class="icon"><img src="images/default/pollsmall.gif" /></td>
                    <th class="subject">
                      <em>[<a href="forumdisplay.php?fid=6&amp;filter=type&amp;typeid=1">手机 &amp;#128241;</a>]</em>
                      <span id="thread_9001">
                        <a href="viewthread.php?ptid=8888&amp;tid=9001&amp;extra=page%3D3" style="background-color: #FFF; color: #2B65EC; font-weight: bold;">置顶投票帖 &amp;#128512; &amp;#129760;</a>
                      </span>
                      <img class="attach" src="images/attachicons/image_s.gif" />
                      <span class="threadpages">
                        <a href="viewthread.php?tid=9001&amp;mypage=99&amp;page=2">2</a>
                        <a href="viewthread.php?tid=9001&amp;mypage=99&amp;page=12">12</a>
                      </span>
                    </th>
                    <td class="author">
                      <cite><a href="space.php?xuid=999&amp;uid=12345">AdminUser</a></cite>
                      <em>2026-11-20</em>
                    </td>
                    <td class="nums"><strong>-</strong>/<em>-</em></td>
                    <td class="lastpost">
                      <cite><a href="space.php?username=VoterA">VoterA</a></cite>
                      <em><a href="redirect.php?tid=9001&amp;goto=lastpost">2026-11-25 19:00</a></em>
                    </td>
                  </tr>
                </tbody>
                <tbody id="normalthread_9002">
                  <tr>
                    <td class="folder"><img src="images/default/folder_new.gif" /></td>
                    <td class="icon"><img src="images/attachicons/image_s.gif" /></td>
                    <th class="subject">
                      <span id="thread_9002">
                        <a href="viewthread.php?tid=9002" style="font-weight: bold;">普通新帖无色无附图附件</a>
                      </span>
                      <img class="attach" src="images/default/digest_1.gif" />
                      <span class="threadpages"><a href="viewthread.php?tid=9002&amp;page=0">0</a></span>
                    </th>
                    <td class="author">
                      <cite><a href="space.php?uid=54321">NormalUser</a></cite>
                      <em>2026-11-25</em>
                    </td>
                    <td class="nums"><strong>5</strong>/<em>88</em></td>
                    <td class="lastpost">
                      <cite>LastPoster</cite>
                    </td>
                  </tr>
                </tbody>
              </table>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val before = doc.outerHtml()
        val page = ThreadListParser.parse(doc)

        assertEquals(before, doc.outerHtml())
        assertTrue(page.hasThreadRows)
        assertEquals(3, page.page)
        assertEquals(3, page.lastPage)
        assertEquals("abcd1234", page.meta.formhash)
        assertEquals(2, page.threads.size)

        val sticky = page.threads[0]
        assertEquals("9001", sticky.tid)
        assertEquals("置顶投票帖 😀 🫠", sticky.title)
        assertEquals("#2B65EC", sticky.titleColor)
        assertEquals("手机 📱", sticky.typeName)
        assertTrue(sticky.isSticky)
        assertFalse(sticky.isNew)
        assertTrue(sticky.isPoll)
        assertEquals("AdminUser", sticky.author)
        assertEquals("12345", sticky.authorId)
        assertEquals("000/01/23/45_avatar_middle.jpg", sticky.avatarUrl)
        assertEquals("2026-11-20", sticky.timeCreate)
        assertEquals("2026-11-25 19:00", sticky.timeUpdate)
        assertEquals("VoterA", sticky.lastPostAuthor)
        assertEquals("-", sticky.replyCountText)
        assertNull(sticky.replyCount)
        assertEquals("-", sticky.viewCountText)
        assertNull(sticky.viewCount)
        assertTrue(sticky.withPic)
        assertEquals(12, sticky.maxPage)

        val normal = page.threads[1]
        assertEquals("9002", normal.tid)
        assertEquals("普通新帖无色无附图附件", normal.title)
        assertNull(normal.titleColor, "Non-color style must produce null titleColor")
        assertNull(normal.typeName)
        assertFalse(normal.isSticky)
        assertTrue(normal.isNew)
        assertFalse(normal.isPoll)
        assertEquals("NormalUser", normal.author)
        assertEquals("54321", normal.authorId)
        assertNull(normal.timeUpdate, "Missing td.lastpost em a must produce null timeUpdate")
        assertEquals("LastPoster", normal.lastPostAuthor)
        assertEquals("5", normal.replyCountText)
        assertEquals(5, normal.replyCount)
        assertEquals("88", normal.viewCountText)
        assertEquals(88, normal.viewCount)
        assertFalse(normal.withPic, "Only img.attach ending with image_s.gif sets withPic")
        assertEquals(1, normal.maxPage, "Non-positive page=0 must fall back to 1")
    }

    @Test
    fun parse_emptyForumAndMalformedRowsAreHandledSafely() {
        val emptyHtml = """
            <html><body>
              <div class="alert_error">您没有权限浏览此版块 <a href="index.php">[返回]</a></div>
            </body></html>
        """.trimIndent()
        val emptyPage = ThreadListParser.parse(emptyHtml)
        assertFalse(emptyPage.hasThreadRows)
        assertTrue(emptyPage.threads.isEmpty())
        assertEquals(1, emptyPage.page)
        assertEquals(1, emptyPage.lastPage)
        assertEquals("您没有权限浏览此版块", emptyPage.meta.errorMessage)

        val malformedHtml = """
            <html><body>
              <table>
                <tbody id="separatorline"><tr><td>版块主题</td></tr></tbody>
                <tbody id="missing_subject">
                  <tr>
                    <td class="author"><cite><a href="space.php?uid=1">A</a></cite><em>2026-11-25</em></td>
                    <td class="nums"><strong>1</strong>/<em>2</em></td>
                    <td class="lastpost"><cite>A</cite></td>
                  </tr>
                </tbody>
                <tbody id="missing_span_a">
                  <tr>
                    <th class="subject">无链接标题</th>
                    <td class="author"><cite><a href="space.php?uid=1">A</a></cite><em>2026-11-25</em></td>
                    <td class="nums"><strong>1</strong>/<em>2</em></td>
                    <td class="lastpost"><cite>A</cite></td>
                  </tr>
                </tbody>
                <tbody id="missing_author">
                  <tr>
                    <th class="subject"><span><a href="viewthread.php?tid=1">缺作者</a></span></th>
                    <td class="nums"><strong>1</strong>/<em>2</em></td>
                    <td class="lastpost"><cite>A</cite></td>
                  </tr>
                </tbody>
                <tbody id="short_author_href">
                  <tr>
                    <th class="subject"><span><a href="viewthread.php?tid=2">短作者链接</a></span></th>
                    <td class="author"><cite><a href="#">A</a></cite><em>2026-11-25</em></td>
                    <td class="nums"><strong>1</strong>/<em>2</em></td>
                    <td class="lastpost"><cite>A</cite></td>
                  </tr>
                </tbody>
                <tbody id="missing_create_time">
                  <tr>
                    <th class="subject"><span><a href="viewthread.php?tid=3">缺发帖时间</a></span></th>
                    <td class="author"><cite><a href="space.php?uid=1">A</a></cite></td>
                    <td class="nums"><strong>1</strong>/<em>2</em></td>
                    <td class="lastpost"><cite>A</cite></td>
                  </tr>
                </tbody>
                <tbody id="missing_nums">
                  <tr>
                    <th class="subject"><span><a href="viewthread.php?tid=4">缺统计</a></span></th>
                    <td class="author"><cite><a href="space.php?uid=1">A</a></cite><em>2026-11-25</em></td>
                    <td class="lastpost"><cite>A</cite></td>
                  </tr>
                </tbody>
                <tbody id="missing_views">
                  <tr>
                    <th class="subject"><span><a href="viewthread.php?tid=5">缺浏览数</a></span></th>
                    <td class="author"><cite><a href="space.php?uid=1">A</a></cite><em>2026-11-25</em></td>
                    <td class="nums"><strong>1</strong></td>
                    <td class="lastpost"><cite>A</cite></td>
                  </tr>
                </tbody>
                <tbody id="missing_lastpost">
                  <tr>
                    <th class="subject"><span><a href="viewthread.php?tid=6">缺最后回复</a></span></th>
                    <td class="author"><cite><a href="space.php?uid=1">A</a></cite><em>2026-11-25</em></td>
                    <td class="nums"><strong>1</strong>/<em>2</em></td>
                  </tr>
                </tbody>
                <tbody id="normalthread_777">
                  <tr>
                    <th class="subject"><span><a href="viewthread.php?tid=777">唯一完整行</a></span></th>
                    <td class="author"><cite><a href="space.php?uid=77">GoodAuthor</a></cite><em>2026-11-25</em></td>
                    <td class="nums"><strong>3</strong>/<em>40</em></td>
                    <td class="lastpost"><cite>GoodAuthor</cite><em><a href="redirect.php?tid=777">2026-11-25 20:00</a></em></td>
                  </tr>
                </tbody>
              </table>
            </body></html>
        """.trimIndent()

        val malformedPage = ThreadListParser.parse(malformedHtml)
        assertTrue(malformedPage.hasThreadRows)
        assertEquals(1, malformedPage.threads.size)
        assertEquals("777", malformedPage.threads[0].tid)
        assertEquals("唯一完整行", malformedPage.threads[0].title)
    }

    @Test
    fun parse_pseudoStaticPaginationFallbackAndSignedMaxPageFallbackToOne() {
        // 闭合 R-B7 S2：
        // 1. div.pages 无 page= 参数的伪静态链接（forum-2-5.html）回退读取锚点文本 5；
        // 2. span.threadpages 带符号参数 page=+5 必须被 ASCII 纯数字校验拒绝并回退为 1（不可按 toIntOrNull() 解析成 5）。
        val html = """
            <html><body>
              <div class="pages_btns">
                <div class="pages">
                  <strong>1</strong>
                  <a href="forum-2-2.html">2</a>
                  <a href="forum-2-5.html">5</a>
                </div>
              </div>
              <table>
                <tbody id="normalthread_9003">
                  <tr>
                    <th class="subject">
                      <span><a href="viewthread.php?tid=9003">带符号页码回退测试</a></span>
                      <span class="threadpages">
                        <a href="viewthread.php?tid=9003&amp;page=+5">+5</a>
                      </span>
                    </th>
                    <td class="author">
                      <cite><a href="space.php?uid=10086">SignedTester</a></cite>
                      <em>2026-11-26</em>
                    </td>
                    <td class="nums"><strong>12</strong>/<em>340</em></td>
                    <td class="lastpost">
                      <cite>SignedTester</cite>
                      <em><a href="redirect.php?tid=9003&amp;goto=lastpost">2026-11-26 10:00</a></em>
                    </td>
                  </tr>
                </tbody>
              </table>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val before = doc.outerHtml()
        val page = ThreadListParser.parse(doc)

        assertEquals(before, doc.outerHtml())
        assertEquals(1, page.page)
        assertEquals(5, page.lastPage, "无 page= 的伪静态分页链接 forum-2-5.html 必须回退解析锚点文本 5")
        assertEquals(1, page.threads.size)
        assertEquals(1, page.threads.single().maxPage, "带正号参数 page=+5 必须被纯 ASCII 数字校验拦截并回退为 1")
    }

    companion object {
        private const val SAMPLE_FORUM2_P1_LASTPOST = "thread_list/forum_2_p1_lastpost.html"
        private const val SAMPLE_FORUM2_P2_LASTPOST = "thread_list/forum_2_p2_lastpost.html"
        private const val SAMPLE_FORUM2_P1_DATELINE = "thread_list/forum_2_p1_dateline.html"
    }
}
