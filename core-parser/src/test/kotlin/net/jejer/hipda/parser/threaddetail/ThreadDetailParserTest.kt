package net.jejer.hipda.parser.threaddetail

import net.jejer.hipda.parser.SampleFixtures
import net.jejer.hipda.parser.common.firstMatch
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadDetailParserTest {

    @Test
    fun parse_realSampleThreadP1_extractsPageFloorsAttachmentsSmiliesQuotesAndDoesNotMutateDom() {
        val doc = SampleFixtures.parseSampleDocument("thread_detail/thread_p1.html")
        val beforeHtml = doc.outerHtml()

        val authorId = ThreadDetailParser.parseThreadAuthorId(doc)
        val page = ThreadDetailParser.parse(doc, tid = "2602293")

        assertEquals(beforeHtml, doc.outerHtml(), "ThreadDetailParser must never mutate caller Document DOM")
        assertTrue(page.hasPostList)
        assertEquals("2602293", page.tid)
        assertEquals(2, page.fid)
        assertEquals(1, page.page)
        assertEquals(10, page.lastPage)
        assertEquals(50, page.floors.size)
        assertEquals(50, page.postsPerPageEstimate)
        assertTrue(page.title.contains("发帖原则"))
        assertTrue(page.meta.session.isLoggedIn)
        assertEquals(page.floors.first().uid, authorId)

        // 1 楼：图片附件（aimg + div#aimg_4134918_menu 尺寸 3.52 KB = 3604L）、红字 rawColor、签名
        val floor1 = page.floors[0]
        assertEquals(1, floor1.floor)
        assertEquals("53528033", floor1.postId)
        assertEquals(1, floor1.page)
        assertFalse(floor1.isWarned)
        assertNull(floor1.postStatus)
        assertEquals("& TBW", floor1.signature)
        assertEquals(1, floor1.images.size)
        assertEquals(
            "https://img02.4d4y.com/forum/attachments/day_191007/19100712232dcaeb32ca60666a.png",
            floor1.images[0].fullUrl,
        )
        assertNull(floor1.images[0].thumbUrl)
        assertEquals(3604L, floor1.images[0].fileSizeBytes)
        val redSpans = floor1.blocks
            .filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
            .filterIsInstance<InlineSpan.Plain>()
            .filter { it.format.rawColor == "#ff0000" }
        assertTrue(redSpans.isNotEmpty(), "Floor 1 should preserve rawColor #ff0000")

        // 2 楼：含 i.pstatus 编辑状态，且正文 blocks 不重复包含编辑状态文本
        val floor2 = page.floors[1]
        assertEquals(2, floor2.floor)
        assertEquals("53528035", floor2.postId)
        assertEquals("本帖最后由 4d4z 于 2026-8-8 10:09 编辑", floor2.postStatus)
        val floor2AllText = floor2.blocks
            .filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
            .filterIsInstance<InlineSpan.Plain>()
            .joinToString("") { it.text }
        assertFalse(floor2AllText.contains("本帖最后由 4d4z"), "i.pstatus must not leak into content blocks")

        // 表情键归一化检查（样本内覆盖 default_lol / coolmonkey_01 / grapeman_08 / grapeman_18）
        val smileyKeys = page.floors
            .flatMap { it.blocks }
            .filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
            .filterIsInstance<InlineSpan.Emoji>()
            .map { it.normalizedKey }
            .toSet()
        assertTrue("default_lol" in smileyKeys)
        assertTrue("coolmonkey_01" in smileyKeys)
        assertTrue("grapeman_08" in smileyKeys)
        assertTrue("grapeman_18" in smileyKeys)

        // 锁定楼层、引用块、跳楼标记均正确识别
        assertTrue(page.floors.any { floor -> floor.blocks.any { it is ContentBlock.Notice && it.message.contains("作者被禁止或删除") } })
        assertTrue(page.floors.any { floor -> floor.blocks.any { it is ContentBlock.Quote && it.targetPid == "53529418" && it.targetTid == "2602293" } })
        assertTrue(page.floors.any { floor -> floor.blocks.any { it is ContentBlock.GoToFloor && it.floor == 44 && it.targetPid == "53529231" } })
    }

    @Test
    fun parse_realSampleThreadP2_decodesDoubleEscapedEmojiAndEstimatesPostsPerPage() {
        val doc = SampleFixtures.parseSampleDocument("thread_detail/thread_p2.html")
        val beforeHtml = doc.outerHtml()

        val page = ThreadDetailParser.parse(doc, tid = "2602293")

        assertEquals(beforeHtml, doc.outerHtml())
        assertTrue(page.hasPostList)
        assertEquals(2, page.page)
        assertEquals(10, page.lastPage)
        assertEquals(50, page.floors.size)
        assertEquals(50, page.postsPerPageEstimate)

        // 第 51 楼含二次转义 Emoji：好的&amp;#128076; -> 好的👌
        val floor51 = page.floors.first()
        assertEquals(51, floor51.floor)
        assertEquals("53529502", floor51.postId)
        val floor51Text = floor51.blocks
            .filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
            .filterIsInstance<InlineSpan.Plain>()
            .joinToString("") { it.text }
        assertTrue(floor51Text.contains("好的\uD83D\uDC4C"), "Expected decoded OK hand emoji, got: $floor51Text")
        assertFalse(floor51Text.contains("&#128076;"), "Should not leave raw numeric entity in plain text")

        // 第 2 页含 coolmonkey_16 表情与多条锁定楼层
        val smileyKeys = page.floors
            .flatMap { it.blocks }
            .filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
            .filterIsInstance<InlineSpan.Emoji>()
            .map { it.normalizedKey }
            .toSet()
        assertTrue("coolmonkey_16" in smileyKeys)
        assertEquals(4, page.floors.count { f -> f.blocks.any { it is ContentBlock.Notice } })
    }

    @Test
    fun parse_realSampleThreadAuthorOnly_parsesSinglePageQuotesAndGoToFloors() {
        val doc = SampleFixtures.parseSampleDocument("thread_detail/thread_author_only.html")
        val beforeHtml = doc.outerHtml()

        val page = ThreadDetailParser.parse(doc, tid = "2602293")

        assertEquals(beforeHtml, doc.outerHtml())
        assertTrue(page.hasPostList)
        assertEquals(1, page.page)
        assertEquals(1, page.lastPage)
        assertNull(page.postsPerPageEstimate)
        assertEquals(15, page.floors.size)
        assertEquals(4, page.floors.flatMap { it.blocks }.count { it is ContentBlock.Quote })
        assertTrue(page.floors.flatMap { it.blocks }.count { it is ContentBlock.GoToFloor } >= 8)
    }

    @Test
    fun parse_titleFallsBackToThreadTitleWithoutMutatingDomAndDecodesEmoji() {
        val html = """
            <html><body><div id="wrap">
              <div id="nav"><a href="index.php">4D4Y</a> » <a href="forumdisplay.php?fid=6&amp;page=1">Buy &amp; Sell</a> »</div>
              <div id="threadtitle">
                <h1><a href="forumdisplay.php?fid=6&amp;typeid=1">[手机]</a> 出二手设备&amp;#128512;</h1>
              </div>
              <div id="postlist">
                <div id="post_88001"><table><tbody><tr>
                  <td class="postauthor"><div class="postinfo"><a href="space.php?uid=50001">Seller</a></div></td>
                  <td class="postcontent">
                    <div class="postinfo"><strong><a href="javascript:;"><em>1</em><sup>#</sup></a></strong>
                      <div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 10:00</em></div></div>
                    </div>
                    <span class="postratings"><a href="misc.php?action=viewwarning&amp;tid=12345&amp;uid=50001">警告</a></span>
                    <div class="defaultpost"><div class="postmessage">
                      <div class="t_msgfontfix"><table><tbody><tr><td class="t_msgfont">正文内容</td></tr></tbody></table></div>
                    </div></div>
                  </td>
                </tr></tbody></table></div>
              </div>
            </div></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val before = doc.outerHtml()

        val page = ThreadDetailParser.parse(doc, tid = "12345", avatarBaseUrl = "https://img.example.invalid/avatar/")

        assertEquals(before, doc.outerHtml())
        assertEquals(6, page.fid)
        assertEquals("出二手设备\uD83D\uDE00", page.title)
        assertEquals(1, page.floors.size)
        val floor = page.floors[0]
        assertTrue(floor.isWarned)
        assertEquals("2026-11-25 10:00", floor.timePost)
        assertEquals("https://img.example.invalid/avatar/000/05/00/01_avatar_middle.jpg", floor.avatarUrl)
        assertEquals(listOf(ContentBlock.Text(listOf(InlineSpan.Plain("正文内容")))), floor.blocks)
    }

    @Test
    fun parse_missingPostListAndEdgeFloorsAndDeepDomDoNotCrashOrMutate() {
        // 1. 缺 #postlist 异常页
        val noListDoc = Jsoup.parse(
            """<html><body><div id="wrap"><div class="alert_error">指定的主题不存在或已被删除</div></div></body></html>""",
        )
        val noListPage = ThreadDetailParser.parse(noListDoc, tid = "bad_tid")
        assertFalse(noListPage.hasPostList)
        assertNull(noListPage.tid)
        assertTrue(noListPage.floors.isEmpty())
        assertEquals("指定的主题不存在或已被删除", noListPage.meta.errorMessage)

        // 2. 缺失正文 / 空正文 / 锁定楼层 / 畸形楼层跳过
        val edgeHtml = """
            <html><body><div id="wrap"><div id="postlist">
              <div id="postlistreply">ignored</div>
              <div id="post_1001"><table><tbody><tr>
                <td class="postauthor"><div class="postinfo"><a href="space.php?uid=101">A</a></div></td>
                <td class="postcontent">
                  <div class="postinfo"><strong><a><em>1</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 01:00</em></div></div></div>
                  <div class="defaultpost"><div class="postmessage"><div class="locked">提示: 作者被禁止</div></div></div>
                </td>
              </tr></tbody></table></div>
              <div id="post_1002"><table><tbody><tr>
                <td class="postauthor"><div class="postinfo"><a href="space.php?uid=102">B</a></div></td>
                <td class="postcontent">
                  <div class="postinfo"><strong><a><em>2</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 02:00</em></div></div></div>
                  <div class="defaultpost"><div class="postmessage"></div></div>
                </td>
              </tr></tbody></table></div>
              <div id="post_1003"><table><tbody><tr>
                <td class="postauthor"><div class="postinfo"><a href="space.php?uid=103">C</a></div></td>
                <td class="postcontent">
                  <div class="postinfo"><strong><a><em>3</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 03:00</em></div></div></div>
                  <div class="defaultpost"><div class="postmessage"><div class="t_msgfontfix"><table><tbody><tr><td class="t_msgfont"></td></tr></tbody></table></div></div></div>
                </td>
              </tr></tbody></table></div>
            </div></div></body></html>
        """.trimIndent()
        val edgePage = ThreadDetailParser.parse(edgeHtml)
        assertTrue(edgePage.hasPostList)
        assertEquals(3, edgePage.floors.size)
        assertEquals(listOf(ContentBlock.Notice("提示: 作者被禁止")), edgePage.floors[0].blocks)
        assertEquals(listOf(ContentBlock.Notice(ThreadDetailParser.NOTICE_CONTENT_NOT_FOUND)), edgePage.floors[1].blocks)
        assertEquals(listOf(ContentBlock.Notice(ThreadDetailParser.NOTICE_EMPTY_CONTENT)), edgePage.floors[2].blocks)

        // 3. 深层嵌套 DOM（1500 层）：非递归遍历不发生 StackOverflowError
        val deepInner = "<div>".repeat(1500) + "深层文本" + "</div>".repeat(1500)
        val deepDoc = Jsoup.parse("<table><tbody><tr><td class=\"t_msgfont\">$deepInner</td></tr></tbody></table>")
        val td = deepDoc.firstMatch("td.t_msgfont")!!
        val blocks = ThreadDetailParser.parseContentBlocks(td)
        assertEquals(listOf(ContentBlock.Text(listOf(InlineSpan.Plain("深层文本")))), blocks)
    }

    @Test
    fun parse_tidFallbackExtractsFromLinksWhenOmitted() {
        val html = """
            <html><body><div id="wrap">
              <div class="forumcontrol"><div class="pages"><strong>1</strong><a href="viewthread.php?tid=778899&amp;page=2">2</a></div></div>
              <div id="postlist"></div>
            </div></body></html>
        """.trimIndent()
        val page = ThreadDetailParser.parse(html)
        assertEquals("778899", page.tid)
        assertEquals(1, page.page)
        assertEquals(2, page.lastPage)
    }
}
