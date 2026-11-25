package net.jejer.hipda.parser

import android.app.Application
import android.content.Context
import net.jejer.hipda.job.SimpleListJob
import net.jejer.hipda.parser.common.DiscuzTextDecoder
import net.jejer.hipda.parser.search.SearchParser
import net.jejer.hipda.parser.simplelist.SimpleListParser
import net.jejer.hipda.utils.HiParser
import net.jejer.hipda.utils.HiUtils
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 旧解析器样本回归 + `:core-parser` 新解析器逐字段等价测试（完全离线）：
 * 覆盖标题搜索（兼今日新帖/TA 的帖子）、合成全文搜索、我的帖子、我的回复、我的收藏/关注。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SearchAndSimpleListEquivalenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = LegacyTestSupport.initLegacyEnvironment()
    }

    @Test
    fun titleSearchSample_legacyAndNewAreFieldByFieldEquivalentWithDoubleEscapedEmojiFix() {
        val html = LegacyTestSupport.loadSample("search/search_result.html")
        val legacyDoc = Jsoup.parse(html)
        val newDoc = Jsoup.parse(html)
        val beforeHtml = newDoc.outerHtml()

        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacySearch = HiParser.parseSimpleList(context, SimpleListJob.TYPE_SEARCH, legacyDoc, false)
        assertNotNull(legacySearch)
        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacyNewPosts = HiParser.parseSimpleList(context, SimpleListJob.TYPE_NEW_POSTS, legacyDoc, false)
        assertNotNull(legacyNewPosts)
        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacyUserThreads = HiParser.parseSimpleList(context, SimpleListJob.TYPE_SEARCH_USER_THREADS, legacyDoc, false)
        assertNotNull(legacyUserThreads)
        assertEquals(legacySearch!!.count, legacyNewPosts!!.count)
        assertEquals(legacySearch.count, legacyUserThreads!!.count)

        val newPage = SearchParser.parseTitleSearch(newDoc, avatarBaseUrl = HiUtils.AvatarBaseUrl)
        assertEquals("SearchParser must not mutate Document", beforeHtml, newDoc.outerHtml())

        assertEquals(legacySearch.searchId, newPage.searchId)
        assertEquals("511", newPage.searchId)
        assertEquals(legacySearch.maxPage, newPage.maxPage)
        assertEquals(5, newPage.maxPage)
        assertEquals(legacySearch.count, newPage.items.size)
        assertTrue(newPage.items.size >= 50)

        var doubleEscapedEmojiChecked = false
        for (i in 0 until legacySearch.count) {
            val oldItem = legacySearch.all[i]
            val newItem = newPage.items[i]
            assertEquals("tid mismatch at $i", oldItem.tid.ifEmpty { null }, newItem.tid)
            assertNull("pid must be null for title search at $i", newItem.pid)
            assertNull("snippet must be null for title search at $i", newItem.snippet)
            assertEquals("author mismatch at $i", oldItem.author, newItem.author)
            assertEquals("avatarUrl mismatch at $i", oldItem.avatarUrl.ifEmpty { null }, newItem.avatarUrl)
            assertEquals("time mismatch at $i", oldItem.time, newItem.time)
            assertEquals("forumName mismatch at $i", oldItem.forum, newItem.forumName)

            if (oldItem.tid == "3477944") {
                // 真机样本双重转义 Emoji：旧版保留字面量 &#127822;，新版经 DiscuzTextDecoder 还原为 🍎
                assertEquals("Apple Music 100 best albums &#127822; 史上百大专辑", oldItem.title)
                assertEquals("Apple Music 100 best albums 🍎 史上百大专辑", newItem.title)
                doubleEscapedEmojiChecked = true
            } else {
                assertEquals("title mismatch at $i", oldItem.title, newItem.title)
            }
        }
        assertTrue("Expected double-escaped emoji thread 3477944 to be verified", doubleEscapedEmojiChecked)
    }

    @Test
    fun fullTextSearchSynthetic_legacyAndNewAreFieldByFieldEquivalent() {
        val legacyDoc = Jsoup.parse(SYNTHETIC_FULLTEXT_SEARCH_HTML)
        val newDoc = Jsoup.parse(SYNTHETIC_FULLTEXT_SEARCH_HTML)
        val beforeHtml = newDoc.outerHtml()

        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacy = HiParser.parseSimpleList(context, SimpleListJob.TYPE_SEARCH, legacyDoc, true)
        assertNotNull(legacy)

        val newPage = SearchParser.parseFullTextSearch(newDoc, avatarBaseUrl = HiUtils.AvatarBaseUrl)
        assertEquals("SearchParser.parseFullTextSearch must not mutate Document", beforeHtml, newDoc.outerHtml())

        assertEquals(legacy!!.searchId, newPage.searchId)
        assertEquals("8899", newPage.searchId)
        assertEquals(legacy.maxPage, newPage.maxPage)
        assertEquals(4, newPage.maxPage)
        assertEquals(legacy.count, newPage.items.size)
        assertEquals(2, newPage.items.size)

        for (i in 0 until legacy.count) {
            val oldItem = legacy.all[i]
            val newItem = newPage.items[i]
            assertEquals("pid mismatch at $i", oldItem.pid, newItem.pid)
            assertEquals(
                "title mismatch at $i",
                DiscuzTextDecoder.decodeDoubleEscapedEntities(oldItem.title),
                newItem.title,
            )
            assertEquals(
                "snippet mismatch at $i",
                DiscuzTextDecoder.decodeDoubleEscapedEntities(oldItem.info),
                newItem.snippet,
            )
            assertEquals("author mismatch at $i", oldItem.author, newItem.author)
            assertEquals("avatarUrl mismatch at $i", oldItem.avatarUrl.ifEmpty { null }, newItem.avatarUrl)
            // 旧 getMiddleString(":", "&") 在 "最后发表: 2015-4-4 21:58" 处保留冒号后的前导空格，新版已规范化 trim
            assertEquals("time mismatch at $i", oldItem.time.trim(), newItem.time)
            assertEquals("forumName mismatch at $i", oldItem.forum, newItem.forumName)
        }
        assertEquals("189027", newPage.items[0].authorUid)
        assertEquals("发现 🍎", newPage.items[1].title)
        assertEquals("正文摘要 😀", newPage.items[1].snippet)
    }

    @Test
    fun myThreadsSample_legacyAndNewAreFieldByFieldEquivalent() {
        val html = LegacyTestSupport.loadSample("simple_list/my_threads.html")
        val legacyDoc = Jsoup.parse(html)
        val newDoc = Jsoup.parse(html)
        val beforeHtml = newDoc.outerHtml()

        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacy = HiParser.parseSimpleList(context, SimpleListJob.TYPE_MYPOST, legacyDoc, false)
        assertNotNull(legacy)

        val newPage = SimpleListParser.parseMyThreads(newDoc)
        assertEquals("SimpleListParser.parseMyThreads must not mutate Document", beforeHtml, newDoc.outerHtml())
        assertTrue(newPage.hasTable)
        assertEquals(legacy!!.maxPage, newPage.maxPage)
        assertEquals(3, newPage.maxPage)
        assertEquals(legacy.count, newPage.items.size)
        assertTrue(newPage.items.size >= 30)

        for (i in 0 until legacy.count) {
            val oldItem = legacy.all[i]
            val newItem = newPage.items[i]
            assertEquals("tid mismatch at $i", oldItem.tid, newItem.tid)
            assertEquals("title mismatch at $i", oldItem.title, newItem.title)
            assertEquals("time mismatch at $i", oldItem.time, newItem.time)
            assertEquals("forumName mismatch at $i", oldItem.forum, newItem.forumName)
        }

        // 无 table.datatable 时旧返回 null、新 hasTable=false
        val emptyDoc = Jsoup.parse("<html><body><p>空页</p></body></html>")
        LegacyTestSupport.suppressBackgroundNotifyFetch()
        assertNull(HiParser.parseSimpleList(context, SimpleListJob.TYPE_MYPOST, emptyDoc, false))
        val emptyPage = SimpleListParser.parseMyThreads(emptyDoc)
        assertFalse(emptyPage.hasTable)
        assertTrue(emptyPage.items.isEmpty())
    }

    @Test
    fun myRepliesSample_legacyAndNewAreFieldByFieldEquivalent() {
        val html = LegacyTestSupport.loadSample("simple_list/my_posts.html")
        val legacyDoc = Jsoup.parse(html)
        val newDoc = Jsoup.parse(html)
        val beforeHtml = newDoc.outerHtml()

        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacy = HiParser.parseSimpleList(context, SimpleListJob.TYPE_MYREPLY, legacyDoc, false)
        assertNotNull(legacy)

        val newPage = SimpleListParser.parseMyReplies(newDoc)
        assertEquals("SimpleListParser.parseMyReplies must not mutate Document", beforeHtml, newDoc.outerHtml())
        assertTrue(newPage.hasTable)
        assertEquals(legacy!!.maxPage, newPage.maxPage)
        assertEquals(34, newPage.maxPage)
        assertEquals(legacy.count, newPage.items.size)
        assertTrue(newPage.items.size >= 30)

        for (i in 0 until legacy.count) {
            val oldItem = legacy.all[i]
            val newItem = newPage.items[i]
            assertEquals("tid mismatch at $i", oldItem.tid, newItem.tid)
            assertEquals("pid mismatch at $i", oldItem.pid, newItem.pid)
            assertEquals("title mismatch at $i", oldItem.title, newItem.title)
            assertEquals("replyText/info mismatch at $i", oldItem.info, newItem.replyText)
            assertEquals("time mismatch at $i", oldItem.time, newItem.time)
            assertEquals("forumName mismatch at $i", oldItem.forum, newItem.forumName)
        }
    }

    @Test
    fun favoritesSample_legacyAndNewAreFieldByFieldEquivalent() {
        val html = LegacyTestSupport.loadSample("simple_list/my_favorites.html")
        val legacyDoc = Jsoup.parse(html)
        val newDoc = Jsoup.parse(html)
        val beforeHtml = newDoc.outerHtml()

        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacyFav = HiParser.parseSimpleList(context, SimpleListJob.TYPE_FAVORITES, legacyDoc, false)
        assertNotNull(legacyFav)
        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacyAtt = HiParser.parseSimpleList(context, SimpleListJob.TYPE_ATTENTION, legacyDoc, false)
        assertNotNull(legacyAtt)
        assertEquals(legacyFav!!.count, legacyAtt!!.count)

        val newPage = SimpleListParser.parseFavoritesList(newDoc)
        assertEquals("SimpleListParser.parseFavoritesList must not mutate Document", beforeHtml, newDoc.outerHtml())
        assertEquals(legacyFav.maxPage, newPage.maxPage)
        assertEquals(4, newPage.maxPage)
        assertEquals(legacyFav.count, newPage.items.size)
        assertEquals(75, newPage.items.size)

        for (i in 0 until legacyFav.count) {
            val oldItem = legacyFav.all[i]
            val newItem = newPage.items[i]
            assertEquals("tid mismatch at $i", oldItem.tid, newItem.tid)
            assertEquals("title mismatch at $i", oldItem.title, newItem.title)
            assertEquals("time mismatch at $i", oldItem.time, newItem.time)
            assertEquals("forumName mismatch at $i", oldItem.forum, newItem.forumName)
        }
    }

    companion object {
        private val SYNTHETIC_FULLTEXT_SEARCH_HTML = """
            <html><body>
              <div class="pages_btns s_clear">
                <div class="pages">
                  <strong>1</strong>
                  <a href="search.php?searchid=8899&amp;orderby=lastpost&amp;page=2">2</a>
                  <a href="search.php?searchid=8899&amp;orderby=lastpost&amp;page=4">4</a>
                </div>
              </div>
              <table class="datatable">
                <tr>
                  <td>
                    <div class="sp_title"><a href="gotopost.php?pid=12345">旧版全文搜索标题</a></div>
                    <div class="sp_content">命中的全文片段内容</div>
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
                    <div class="sp_title"><a href="gotopost.php?pid=67890&amp;extra=1">发现 &amp;#127822;</a></div>
                    <div class="sp_content">正文摘要 &amp;#128512;</div>
                    <div class="sp_theard">
                      <span class="sp_w200">版块: <a href="forumdisplay.php?fid=6">Buy &amp; Sell 交易服务区</a></span>
                      <span>作者: <a href="space.php?uid=20000">HiPeer</a></span>
                      <span>查看: 10</span>
                      <span>回复: 2</span>
                      <span class="sp_w200">最后发表: 2026-11-25 18:00</span>
                    </div>
                  </td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()
    }
}
