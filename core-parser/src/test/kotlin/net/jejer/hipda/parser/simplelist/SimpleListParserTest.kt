package net.jejer.hipda.parser.simplelist

import net.jejer.hipda.parser.SampleFixtures
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimpleListParserTest {

    @Test
    fun parseMyThreads_realSampleParsesMaxPageItemsAndDoesNotMutateDom() {
        val doc = SampleFixtures.parseSampleDocument(SAMPLE_MY_THREADS)
        val before = doc.outerHtml()

        val page = SimpleListParser.parseMyThreads(doc)
        assertEquals(before, doc.outerHtml(), "SimpleListParser.parseMyThreads must not mutate Document")
        assertTrue(page.hasTable)
        assertEquals(3, page.maxPage)
        assertTrue(page.meta.session.isLoggedIn)
        assertEquals("10000", page.meta.session.uid)
        assertTrue(page.items.isNotEmpty())

        val first = page.items[0]
        assertEquals("3410683", first.tid)
        assertEquals("已出", first.title)
        assertEquals("HiUser 2025-10-29 11:26", first.time)
        assertEquals("Buy & Sell 交易服务区", first.forumName)
    }

    @Test
    fun parseMyReplies_realSampleParsesPairedRowsMaxPageAndDoesNotMutateDom() {
        val doc = SampleFixtures.parseSampleDocument(SAMPLE_MY_POSTS)
        val before = doc.outerHtml()

        val page = SimpleListParser.parseMyReplies(doc)
        assertEquals(before, doc.outerHtml(), "SimpleListParser.parseMyReplies must not mutate Document")
        assertTrue(page.hasTable)
        assertEquals(34, page.maxPage)
        assertTrue(page.items.isNotEmpty())

        val first = page.items[0]
        assertEquals("3474137", first.tid)
        assertEquals("74880921", first.pid)
        assertEquals("有没有 VPS 发烧友，我做了一个工具，从你自己家到各大云 360+ 个区域的真实延迟", first.title)
        assertEquals("mark 一下 iOS fly ~", first.replyText)
        assertEquals("2026-10-30 00:13", first.time)
        assertEquals("Discovery", first.forumName)
    }

    @Test
    fun parseFavoritesList_realSampleParsesMaxPageAndItemsSkippingSubmitRow() {
        val doc = SampleFixtures.parseSampleDocument(SAMPLE_MY_FAVORITES)
        val before = doc.outerHtml()

        val page = SimpleListParser.parseFavoritesList(doc)
        assertEquals(before, doc.outerHtml(), "SimpleListParser.parseFavoritesList must not mutate Document")
        assertEquals(4, page.maxPage)
        assertEquals("00000000", page.meta.formhash)
        assertTrue(page.items.isNotEmpty())

        val first = page.items[0]
        assertEquals("3468951", first.tid)
        assertEquals("推荐一个AI短剧：《持械入宋》，拍的很棒", first.title)
        assertEquals("kevinwui 2026-10-25 19:52", first.time)
        assertEquals("Discovery", first.forumName)

        // 末尾提交按钮行（无 <a>）不应进入结果集
        assertTrue(page.items.none { it.title == "提交" })
    }

    @Test
    fun parseSimpleLists_handlesMissingTablesDoubleEscapedEmojiAndMalformedRows() {
        val noTable = "<html><body><p class=\"nodata\">暂无数据</p></body></html>"
        val threadsNoTable = SimpleListParser.parseMyThreads(noTable)
        assertFalse(threadsNoTable.hasTable)
        assertEquals(1, threadsNoTable.maxPage)
        assertTrue(threadsNoTable.items.isEmpty())

        val repliesNoTable = SimpleListParser.parseMyReplies(noTable)
        assertFalse(repliesNoTable.hasTable)
        assertEquals(1, repliesNoTable.maxPage)
        assertTrue(repliesNoTable.items.isEmpty())

        val emojiRepliesHtml = """
            <html><body>
              <table class="datatable">
                <thead><tr><th>主题</th><td>版块</td><td>最后发表</td></tr></thead>
                <tbody>
                  <tr>
                    <th><a href="redirect.php?goto=findpost&amp;pid=501&amp;ptid=401">主题 &amp;#127822;</a></th>
                    <td class="forum">Discovery</td>
                    <td class="lastpost">2026-11-25 12:00</td>
                  </tr>
                  <tr><th colspan="3">回复内容 &amp;#128512;</th></tr>
                  <tr><th>残缺无链接行</th><td class="lastpost">2026-11-25 12:01</td></tr>
                  <tr><th colspan="3">残缺行对应的回复应跳过</th></tr>
                </tbody>
              </table>
            </body></html>
        """.trimIndent()
        val parsedReplies = SimpleListParser.parseMyReplies(Jsoup.parse(emojiRepliesHtml))
        assertTrue(parsedReplies.hasTable)
        assertEquals(1, parsedReplies.items.size)
        assertEquals("401", parsedReplies.items[0].tid)
        assertEquals("501", parsedReplies.items[0].pid)
        assertEquals("主题 🍎", parsedReplies.items[0].title)
        assertEquals("回复内容 😀", parsedReplies.items[0].replyText)

        val emojiFavHtml = """
            <html><body>
              <table class="datatable">
                <tbody>
                  <tr>
                    <th><a href="viewthread.php?tid=601&amp;from=favorites">收藏 &amp;#128076;</a></th>
                    <td class="forum"> Discovery </td>
                    <td class="lastpost"> 2026-11-25 13:00 </td>
                  </tr>
                </tbody>
              </table>
            </body></html>
        """.trimIndent()
        val parsedFavs = SimpleListParser.parseFavoritesList(emojiFavHtml)
        assertEquals(1, parsedFavs.items.size)
        assertEquals("601", parsedFavs.items[0].tid)
        assertEquals("收藏 👌", parsedFavs.items[0].title)
        assertEquals("Discovery", parsedFavs.items[0].forumName)
        assertEquals("2026-11-25 13:00", parsedFavs.items[0].time)
    }

    companion object {
        private const val SAMPLE_MY_THREADS = "simple_list/my_threads.html"
        private const val SAMPLE_MY_POSTS = "simple_list/my_posts.html"
        private const val SAMPLE_MY_FAVORITES = "simple_list/my_favorites.html"
    }
}
