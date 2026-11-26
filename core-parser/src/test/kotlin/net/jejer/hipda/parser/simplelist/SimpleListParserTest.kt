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
        assertTrue(page.items.size >= 30, "Expected >= 30 threads in my_threads.html, got ${page.items.size}")

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
        assertTrue(page.items.size >= 30, "Expected >= 30 replies in my_posts.html, got ${page.items.size}")

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
        assertEquals(75, page.items.size)

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

    @Test
    fun parseMyReplies_structuralPairingSurvivesInsertedSingleRowsAndMissingSummaries() {
        // R-B9b S2：插入单行公告/分隔行导致奇偶翻转，按头行结构配对仍可正确提取后续回复对
        val shiftedHtml = """
            <html><body>
              <table class="datatable">
                <thead><tr><th>主题</th><td>版块</td><td>最后发表</td></tr></thead>
                <tbody>
                  <tr class="notice"><td colspan="3">单行分隔公告（翻转行下标奇偶）</td></tr>
                  <tr>
                    <th><a href="redirect.php?goto=findpost&amp;pid=701&amp;ptid=301">翻转后的第一条</a></th>
                    <td class="forum">Discovery</td>
                    <td class="lastpost">2026-11-25 14:00</td>
                  </tr>
                  <tr><th colspan="3">第一条回复摘要</th></tr>
                  <tr><td colspan="3">中间插入的单行提示</td></tr>
                  <tr>
                    <th><a href="redirect.php?goto=findpost&amp;pid=702&amp;ptid=302">缺摘要的连续头行</a></th>
                    <td class="forum">Geek Talks</td>
                    <td class="lastpost">2026-11-25 14:05</td>
                  </tr>
                  <tr>
                    <th><a href="redirect.php?goto=findpost&amp;pid=703&amp;ptid=303">紧随其后的第二条</a></th>
                    <td class="forum">E-INK</td>
                    <td class="lastpost">2026-11-25 14:10</td>
                  </tr>
                  <tr><th colspan="3">第二条回复摘要 &amp;#128512;</th></tr>
                </tbody>
              </table>
            </body></html>
        """.trimIndent()

        val page = SimpleListParser.parseMyReplies(shiftedHtml)
        assertTrue(page.hasTable)
        assertEquals(3, page.items.size)

        assertEquals("301", page.items[0].tid)
        assertEquals("701", page.items[0].pid)
        assertEquals("翻转后的第一条", page.items[0].title)
        assertEquals("第一条回复摘要", page.items[0].replyText)

        assertEquals("302", page.items[1].tid)
        assertEquals("702", page.items[1].pid)
        assertEquals("缺摘要的连续头行", page.items[1].title)
        assertEquals(null, page.items[1].replyText)

        assertEquals("303", page.items[2].tid)
        assertEquals("703", page.items[2].pid)
        assertEquals("紧随其后的第二条", page.items[2].title)
        assertEquals("第二条回复摘要 😀", page.items[2].replyText)
    }

    @Test
    fun parseSimpleLists_readsLastPageStrongAndDecodesMyThreadsDoubleEscapedEmoji() {
        // R-B9b S4：末页 <strong> 分页 + parseMyThreads 标题二次实体解码
        val myThreadsLastPageHtml = """
            <html><body>
              <div class="pages_btns">
                <div class="pages">
                  <a href="my.php?item=threads&amp;page=2">2</a>
                  <a href="my.php?item=threads&amp;page=3">3</a>
                  <strong>4</strong>
                </div>
              </div>
              <table class="datatable">
                <tr><th>主题</th><td>版块</td><td>最后发表</td></tr>
                <tr>
                  <th><a href="viewthread.php?tid=801">我的发帖 &amp;#128512;</a></th>
                  <td class="forum">Discovery</td>
                  <td class="lastpost">2026-11-25 15:00</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()
        val threadsPage = SimpleListParser.parseMyThreads(myThreadsLastPageHtml)
        assertEquals(4, threadsPage.maxPage)
        assertEquals(1, threadsPage.items.size)
        assertEquals("801", threadsPage.items[0].tid)
        assertEquals("我的发帖 😀", threadsPage.items[0].title)

        val myRepliesLastPageHtml = """
            <html><body>
              <div class="pages_btns">
                <div class="pages">
                  <a href="my.php?item=posts&amp;page=4">4</a>
                  <strong>5</strong>
                </div>
              </div>
              <table class="datatable">
                <tr><th>主题</th><td>版块</td><td>最后发表</td></tr>
              </table>
            </body></html>
        """.trimIndent()
        assertEquals(5, SimpleListParser.parseMyReplies(myRepliesLastPageHtml).maxPage)

        val favoritesLastPageHtml = """
            <html><body>
              <div class="pages">
                <a href="my.php?item=favorites&amp;page=5">5</a>
                <strong>6</strong>
              </div>
              <table class="datatable"><tbody></tbody></table>
            </body></html>
        """.trimIndent()
        assertEquals(6, SimpleListParser.parseFavoritesList(favoritesLastPageHtml).maxPage)
    }

    companion object {
        private const val SAMPLE_MY_THREADS = "simple_list/my_threads.html"
        private const val SAMPLE_MY_POSTS = "simple_list/my_posts.html"
        private const val SAMPLE_MY_FAVORITES = "simple_list/my_favorites.html"
    }
}
