package net.jejer.hipda.parser

import android.app.Application
import android.content.Context
import net.jejer.hipda.bean.HiSettingsHelper
import net.jejer.hipda.bean.ThreadBean
import net.jejer.hipda.bean.ThreadListBean
import net.jejer.hipda.parser.threadlist.ThreadListPage
import net.jejer.hipda.parser.threadlist.ThreadListParser
import net.jejer.hipda.parser.threadlist.ThreadSummary
import net.jejer.hipda.utils.HiParserThreadList
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
 * 旧 `HiParserThreadList` 样本回归 + `:core-parser` `ThreadListParser` 逐字段等价测试（100% 离线）：
 * 覆盖 3 份真机版块样本、合成置顶/投票/彩色/分类/多页/空版块/残缺行夹具，以及新解析器与设置无关性断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ThreadListParserEquivalenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = LegacyTestSupport.initLegacyEnvironment()
        val settings = HiSettingsHelper.getInstance()
        settings.uid = ""
        settings.isShowStickThreads = true
        settings.blacklists = emptyList()
    }

    @Test
    fun realSample_forum2P1Lastpost_legacyAndNewAreFieldByFieldEquivalent() {
        val (legacy, newPage) = parseSampleBoth("thread_list/forum_2_p1_lastpost.html")

        assertEquals(75, newPage.threads.size)
        assertEquals(1, newPage.page)
        assertEquals(1000, newPage.lastPage)
        assertEquals("10000", legacy.uid)
        assertEquals("10000", newPage.meta.session.uid)
        assertThreadListEquivalent("forum_2_p1_lastpost", legacy, newPage)

        val emojiThread = newPage.threads.first { it.tid == "3477944" }
        assertEquals("Apple Music 100 best albums 🍎 史上百大专辑", emojiThread.title)
        assertTrue(newPage.threads.any { it.withPic })
        assertTrue(newPage.threads.any { it.maxPage > 1 })
        assertTrue(newPage.threads.any { it.isNew })
        assertTrue(newPage.threads.any { it.titleColor == "#EE1B2E" })
        assertTrue(newPage.threads.any { it.titleColor == "#3C9D40" })
    }

    @Test
    fun realSample_forum2P2Lastpost_legacyAndNewAreFieldByFieldEquivalent() {
        val (legacy, newPage) = parseSampleBoth("thread_list/forum_2_p2_lastpost.html")

        assertEquals(75, newPage.threads.size)
        assertEquals(2, newPage.page)
        assertEquals(1000, newPage.lastPage)
        assertEquals("10000", legacy.uid)
        assertEquals("10000", newPage.meta.session.uid)
        assertThreadListEquivalent("forum_2_p2_lastpost", legacy, newPage)

        val emojiThread = newPage.threads.first { it.tid == "3477896" }
        assertEquals("OPPO的自动指令真是一坨💩", emojiThread.title)
    }

    @Test
    fun realSample_forum2P1Dateline_legacyAndNewAreFieldByFieldEquivalent() {
        val (legacy, newPage) = parseSampleBoth("thread_list/forum_2_p1_dateline.html")

        assertEquals(75, newPage.threads.size)
        assertEquals(1, newPage.page)
        assertEquals(1000, newPage.lastPage)
        assertEquals("10000", legacy.uid)
        assertEquals("10000", newPage.meta.session.uid)
        assertThreadListEquivalent("forum_2_p1_dateline", legacy, newPage)
    }

    @Test
    fun syntheticFixtures_stickyPollColorTypeMultipageEmptyMalformed_legacyAndNewAreEquivalent() {
        val legacyDoc = Jsoup.parse(SYNTHETIC_THREAD_LIST_HTML)
        val newDoc = Jsoup.parse(SYNTHETIC_THREAD_LIST_HTML)
        val beforeHtml = newDoc.outerHtml()

        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacy = HiParserThreadList.parse(context, legacyDoc)
        val newPage = ThreadListParser.parse(newDoc, avatarBaseUrl = HiUtils.AvatarBaseUrl)

        assertEquals("ThreadListParser must not mutate Document", beforeHtml, newDoc.outerHtml())
        assertEquals("10000", legacy.uid)
        assertEquals("10000", newPage.meta.session.uid)
        assertEquals(3, newPage.threads.size)
        assertEquals(3, newPage.page)
        assertEquals(3, newPage.lastPage)
        assertThreadListEquivalent("synthetic", legacy, newPage)

        assertTrue(newPage.threads[0].isSticky)
        assertTrue(newPage.threads[0].isPoll)
        assertEquals("#EE1B2E", newPage.threads[0].titleColor)
        assertEquals("手机", newPage.threads[0].typeName)
        assertTrue(newPage.threads[0].withPic)
        assertEquals(12, newPage.threads[0].maxPage)
        assertEquals("置顶投票贴 🍎", newPage.threads[0].title)

        assertFalse(newPage.threads[1].isSticky)
        assertTrue(newPage.threads[1].isNew)
        assertFalse(newPage.threads[1].isPoll)
        assertNull(newPage.threads[1].titleColor)
        assertNull(newPage.threads[1].typeName)
        assertNull(newPage.threads[1].timeUpdate)

        assertEquals("BlockedUser", newPage.threads[2].author)

        // 空版块（无 tbody[id]）
        val emptyHtml = "<html><body><div class=\"pages\"><strong>1</strong></div></body></html>"
        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacyEmpty = HiParserThreadList.parse(context, Jsoup.parse(emptyHtml))
        val newEmpty = ThreadListParser.parse(emptyHtml, avatarBaseUrl = HiUtils.AvatarBaseUrl)
        assertFalse(legacyEmpty.isParsed)
        assertFalse(newEmpty.hasThreadRows)
        assertTrue(legacyEmpty.threads.isEmpty())
        assertTrue(newEmpty.threads.isEmpty())
    }

    @Test
    fun newParser_ignoresLegacyStickyAndBlacklistSettings() {
        val settings = HiSettingsHelper.getInstance()
        settings.isShowStickThreads = false
        settings.blacklists = listOf("BlockedUser")

        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacyFiltered = HiParserThreadList.parse(context, Jsoup.parse(SYNTHETIC_THREAD_LIST_HTML))
        assertEquals("Legacy parser must filter sticky and blacklisted threads", 1, legacyFiltered.count)
        assertEquals("9002", legacyFiltered.threads[0].tid)

        val newDoc = Jsoup.parse(SYNTHETIC_THREAD_LIST_HTML)
        val beforeHtml = newDoc.outerHtml()
        val newUnfiltered = ThreadListParser.parse(newDoc, avatarBaseUrl = HiUtils.AvatarBaseUrl)

        assertEquals("ThreadListParser must not mutate Document", beforeHtml, newDoc.outerHtml())
        assertEquals("New parser must ignore HiSettingsHelper sticky/blacklist settings", 3, newUnfiltered.threads.size)
        assertEquals(listOf("9001", "9002", "9003"), newUnfiltered.threads.map { it.tid })
        assertTrue(newUnfiltered.threads[0].isSticky)
        assertEquals("BlockedUser", newUnfiltered.threads[2].author)
    }

    private fun parseSampleBoth(relativePath: String): Pair<ThreadListBean, ThreadListPage> {
        val html = LegacyTestSupport.loadSample(relativePath)
        val legacyDoc = Jsoup.parse(html)
        val newDoc = Jsoup.parse(html)
        val beforeHtml = newDoc.outerHtml()

        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacy = HiParserThreadList.parse(context, legacyDoc)
        assertNotNull(legacy)

        val newPage = ThreadListParser.parse(newDoc, avatarBaseUrl = HiUtils.AvatarBaseUrl)
        assertEquals("ThreadListParser must not mutate Document ($relativePath)", beforeHtml, newDoc.outerHtml())
        return legacy to newPage
    }

    private fun assertThreadListEquivalent(label: String, legacy: ThreadListBean, actual: ThreadListPage) {
        assertEquals("$label: hasThreadRows mismatch", legacy.isParsed, actual.hasThreadRows)
        assertEquals("$label: session uid mismatch", legacy.uid, actual.meta.session.uid)
        assertEquals("$label: thread count mismatch", legacy.count, actual.threads.size)

        for (i in 0 until legacy.count) {
            assertThreadEquivalent("$label[$i]", legacy.threads[i], actual.threads[i])
        }
    }

    private fun assertThreadEquivalent(label: String, oldItem: ThreadBean, newItem: ThreadSummary) {
        assertEquals("$label tid mismatch", oldItem.tid, newItem.tid)
        assertEquals("$label title mismatch", oldItem.title, newItem.title)
        assertEquals("$label titleColor mismatch", oldItem.titleColor?.ifEmpty { null }, newItem.titleColor)
        assertEquals("$label typeName mismatch", oldItem.type?.ifEmpty { null }, newItem.typeName)
        assertEquals("$label isSticky mismatch", oldItem.isSticky, newItem.isSticky)
        assertEquals("$label isNew mismatch", oldItem.isNew, newItem.isNew)
        assertEquals("$label isPoll mismatch", oldItem.isPoll, newItem.isPoll)
        assertEquals("$label author mismatch", oldItem.author, newItem.author)
        assertEquals("$label authorId mismatch", oldItem.authorId, newItem.authorId)
        assertEquals("$label avatarUrl mismatch", oldItem.avatarUrl?.ifEmpty { null }, newItem.avatarUrl)
        assertEquals("$label timeCreate mismatch", oldItem.timeCreate, newItem.timeCreate)
        assertEquals("$label timeUpdate mismatch", oldItem.timeUpdate, newItem.timeUpdate)
        assertEquals("$label lastPostAuthor mismatch", oldItem.lastPost, newItem.lastPostAuthor)
        assertEquals("$label replyCountText mismatch", oldItem.countCmts, newItem.replyCountText)
        assertEquals("$label viewCountText mismatch", oldItem.countViews, newItem.viewCountText)
        assertEquals("$label withPic mismatch", oldItem.isWithPic, newItem.withPic)
        assertEquals("$label maxPage mismatch", oldItem.maxPage, newItem.maxPage)
    }

    companion object {
        private val SYNTHETIC_THREAD_LIST_HTML = """
            <html><body>
              <div id="umenu">
                <cite><a href="space.php?uid=10000">HiUser</a></cite>
              </div>
              <div class="pages">
                <a href="forumdisplay.php?fid=6&amp;page=1">1</a>
                <a href="forumdisplay.php?fid=6&amp;page=2">2</a>
                <strong>3</strong>
              </div>
              <table>
                <tbody id="separatorline"><tr><td colspan="6">版块主题</td></tr></tbody>
                <tbody id="stickthread_9001">
                  <tr>
                    <td class="folder"><a href="viewthread.php?tid=9001"><img src="images/default/pin_1.gif" /></a></td>
                    <td class="icon"><img src="images/default/pollsmall.gif" /></td>
                    <th class="subject">
                      <em>[<a href="forumdisplay.php?fid=6&amp;filter=type&amp;typeid=1">手机</a>]</em>
                      <span id="thread_9001"><a href="viewthread.php?tid=9001&amp;extra=page%3D3" style="font-weight: bold;color: #EE1B2E">置顶投票贴 &amp;#127822;</a></span>
                      <img class="attach" src="images/attachicons/image_s.gif" />
                      <span class="threadpages">
                        <a href="viewthread.php?tid=9001&amp;page=2">2</a>
                        <a href="viewthread.php?tid=9001&amp;page=12">12</a>
                      </span>
                    </th>
                    <td class="author">
                      <cite><a href="space.php?uid=12345">StickyAdmin</a></cite>
                      <em>2026-11-20</em>
                    </td>
                    <td class="nums"><strong>25</strong>/<em>1200</em></td>
                    <td class="lastpost">
                      <cite><a href="space.php?username=Replier1">Replier1</a></cite>
                      <em><a href="redirect.php?tid=9001&amp;goto=lastpost#lastpost">2026-11-25 18:30</a></em>
                    </td>
                  </tr>
                </tbody>
                <tbody id="normalthread_9002">
                  <tr>
                    <td class="folder"><a href="viewthread.php?tid=9002"><img src="images/default/folder_new.gif" /></a></td>
                    <td class="icon">&nbsp;</td>
                    <th class="subject">
                      <span id="thread_9002"><a href="viewthread.php?tid=9002&amp;extra=page%3D3">普通新帖</a></span>
                    </th>
                    <td class="author">
                      <cite><a href="space.php?uid=20001">NormalAuthor</a></cite>
                      <em>2026-11-25</em>
                    </td>
                    <td class="nums"><strong>0</strong>/<em>12</em></td>
                    <td class="lastpost">
                      <cite><a href="space.php?username=NormalAuthor">NormalAuthor</a></cite>
                    </td>
                  </tr>
                </tbody>
                <tbody id="normalthread_9003">
                  <tr>
                    <td class="folder"><img src="images/default/folder_common.gif" /></td>
                    <td class="icon">&nbsp;</td>
                    <th class="subject">
                      <span id="thread_9003"><a href="viewthread.php?tid=9003&amp;extra=page%3D3">黑名单作者发帖</a></span>
                    </th>
                    <td class="author">
                      <cite><a href="space.php?uid=30002">BlockedUser</a></cite>
                      <em>2026-11-25</em>
                    </td>
                    <td class="nums"><strong>4</strong>/<em>99</em></td>
                    <td class="lastpost">
                      <cite><a href="space.php?username=OtherUser">OtherUser</a></cite>
                      <em><a href="redirect.php?tid=9003&amp;goto=lastpost#lastpost">2026-11-25 19:10</a></em>
                    </td>
                  </tr>
                </tbody>
                <tbody id="malformed_missing_nums">
                  <tr>
                    <th class="subject"><span><a href="viewthread.php?tid=9999">残缺行应跳过</a></span></th>
                    <td class="author"><cite><a href="space.php?uid=40003">BadRow</a></cite><em>2026-11-25</em></td>
                    <td class="lastpost"><cite>BadRow</cite></td>
                  </tr>
                </tbody>
              </table>
            </body></html>
        """.trimIndent()
    }
}
