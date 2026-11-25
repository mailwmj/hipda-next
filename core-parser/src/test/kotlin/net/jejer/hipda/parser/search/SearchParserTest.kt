package net.jejer.hipda.parser.search

import net.jejer.hipda.parser.SampleFixtures
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchParserTest {

    @Test
    fun parseTitleSearch_realSampleParsesSearchIdMaxPageItemsDoubleEscapedEmojiWithoutMutatingDom() {
        val doc = SampleFixtures.parseSampleDocument(SAMPLE_SEARCH_RESULT)
        val before = doc.outerHtml()

        val page = SearchParser.parseTitleSearch(doc)
        assertEquals(before, doc.outerHtml(), "SearchParser must not mutate Document")
        assertEquals("511", page.searchId)
        assertEquals(5, page.maxPage)
        assertTrue(page.meta.session.isLoggedIn)
        assertEquals("10000", page.meta.session.uid)
        assertEquals("HiUser", page.meta.session.username)
        assertEquals("00000000", page.meta.formhash)
        assertTrue(page.items.size >= 50, "Expected >= 50 threads in search_result.html, got ${page.items.size}")

        val first = page.items[0]
        assertEquals("3478055", first.tid)
        assertNull(first.pid)
        assertEquals("送2堆puzzle解密解压拼图玩具", first.title)
        assertNull(first.snippet)
        assertEquals("insnct", first.author)
        assertEquals("638199", first.authorUid)
        assertEquals("000/63/81/99_avatar_middle.jpg", first.avatarUrl)
        assertEquals("2026-11-25", first.time)
        assertEquals("Buy & Sell 交易服务区", first.forumName)

        // 真机样本第 944 行双重转义补充平面 Emoji：&amp;#127822; -> 🍎
        val emojiItem = page.items.firstOrNull { it.tid == "3477944" }
        assertTrue(emojiItem != null, "Expected thread 3477944 in search_result.html")
        assertEquals("Apple Music 100 best albums 🍎 史上百大专辑", emojiItem!!.title)

        // 传入 avatarBaseUrl 时自动拼接规范化地址
        val withBase = SearchParser.parseTitleSearch(doc, avatarBaseUrl = "https://img02.4d4y.com/forum/uc_server/data/avatar/")
        assertEquals(
            "https://img02.4d4y.com/forum/uc_server/data/avatar/000/63/81/99_avatar_middle.jpg",
            withBase.items[0].avatarUrl,
        )
    }

    @Test
    fun parseFullTextSearch_syntheticFixtureParsesFiveSpanMetadataSnippetAndDoubleEscapedEmoji() {
        val html = """
            <html><body>
              <div id="umenu"><cite><a href="space.php?uid=10000" class="noborder">HiUser</a></cite></div>
              <div class="pages_btns s_clear">
                <div class="pages">
                  <strong>1</strong>
                  <a href="search.php?searchid=7788&amp;orderby=lastpost&amp;page=2">2</a>
                  <a href="search.php?searchid=7788&amp;orderby=lastpost&amp;page=9" class="last">... 9</a>
                  <a href="search.php?searchid=7788&amp;orderby=lastpost&amp;page=2" class="next">下一页</a>
                </div>
              </div>
              <table class="datatable">
                <tr>
                  <td>
                    <div class="sp_title"><a href="gotopost.php?pid=12345">全文标题 &amp;#128512;</a></div>
                    <div class="sp_content">命中的 <em>关键字</em> 摘要 &amp;#128076;</div>
                    <div class="sp_theard">
                      <span class="sp_w200">版块: <a href="forumdisplay.php?fid=2">Discovery</a></span>
                      <span>作者: <a href="space.php?uid=189027">tsonglin</a></span>
                      <span>查看: 1988</span>
                      <span>回复: 56</span>
                      <span class="sp_w200">最后发表: 2015-4-4 21:58</span>
                    </div>
                  </td>
                </tr>
                <tr>
                  <td>
                    <div class="sp_title"><a href="gotopost.php?pid=">缺失 pid 应跳过</a></div>
                    <div class="sp_content">ignored</div>
                    <div class="sp_theard">
                      <span>版块: <a href="forumdisplay.php?fid=2">Discovery</a></span>
                      <span>作者: <a href="space.php?uid=1">A</a></span>
                      <span>查看: 1</span>
                      <span>回复: 1</span>
                      <span>最后发表: 2020-1-1 00:00</span>
                    </div>
                  </td>
                </tr>
                <tr>
                  <td>
                    <div class="sp_title"><a href="gotopost.php?pid=99999">span 数量不足 5 个应跳过</a></div>
                    <div class="sp_theard">
                      <span>版块: <a href="forumdisplay.php?fid=2">Discovery</a></span>
                      <span>作者: <a href="space.php?uid=2">B</a></span>
                    </div>
                  </td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val before = doc.outerHtml()
        val page = SearchParser.parse(doc, fullText = true, avatarBaseUrl = "https://img.example.invalid/avatar")

        assertEquals(before, doc.outerHtml())
        assertEquals("7788", page.searchId)
        assertEquals(9, page.maxPage)
        assertEquals(1, page.items.size)

        val item = page.items.single()
        assertNull(item.tid)
        assertEquals("12345", item.pid)
        assertEquals("全文标题 😀", item.title)
        assertEquals("命中的 关键字 摘要 👌", item.snippet)
        assertEquals("tsonglin", item.author)
        assertEquals("189027", item.authorUid)
        assertEquals("https://img.example.invalid/avatar/000/18/90/27_avatar_middle.jpg", item.avatarUrl)
        assertEquals("2015-4-4 21:58", item.time)
        assertEquals("Discovery", item.forumName)
    }

    @Test
    fun parseTitleSearch_emptyPageAndSinglePageStrongFallback() {
        val emptyPage = SearchParser.parseTitleSearch("<html><body><p>没有找到相关主题</p></body></html>")
        assertNull(emptyPage.searchId)
        assertEquals(1, emptyPage.maxPage)
        assertTrue(emptyPage.items.isEmpty())

        val lastPageHtml = """
            <html><body>
              <div class="pages_btns"><div class="pages">
                <a href="search.php?searchid=900&amp;page=2">2</a>
                <strong>3</strong>
              </div></div>
            </body></html>
        """.trimIndent()
        val lastPage = SearchParser.parseTitleSearch(lastPageHtml)
        assertEquals("900", lastPage.searchId)
        assertEquals(3, lastPage.maxPage)

        // 无 div.pages_btns 时兜底 div.pages，以及全文搜索带 tid、缺 div.sp_content 容错
        val fallbackPagesFullText = """
            <html><body>
              <div class="pages"><a href="search.php?searchid=901&amp;page=2">2</a><strong>2</strong></div>
              <table class="datatable">
                <tr><td>
                  <div class="sp_title"><a href="redirect.php?goto=findpost&amp;pid=555&amp;ptid=444">无摘要帖</a></div>
                  <div class="sp_theard">
                    <span>版块: <a href="forumdisplay.php?fid=2">Discovery</a></span>
                    <span>作者: <a href="space.php?uid=12345">Tester</a></span>
                    <span>查看: 1</span>
                    <span>回复: 0</span>
                    <span>最后发表: 2026-11-25 19:00</span>
                  </div>
                </td></tr>
              </table>
            </body></html>
        """.trimIndent()
        val parsed = SearchParser.parse(fallbackPagesFullText, fullText = true)
        assertEquals("901", parsed.searchId)
        assertEquals(2, parsed.maxPage)
        assertEquals(1, parsed.items.size)
        assertEquals("444", parsed.items[0].tid)
        assertEquals("555", parsed.items[0].pid)
        assertNull(parsed.items[0].snippet)
    }

    companion object {
        private const val SAMPLE_SEARCH_RESULT = "search/search_result.html"
    }
}
