package net.jejer.hipda.parser.threaddetail

import net.jejer.hipda.parser.common.firstMatch
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PollParserTest {

    @Test
    fun parsePoll_singleChoiceFormPollExtractsAllFieldsWithoutMutatingDom() {
        val html = """
            <html><body><div id="wrap"><div id="postlist"><div id="post_71001"><table><tbody><tr>
              <td class="postauthor"><div class="postinfo"><a href="space.php?uid=11001">OP</a></div></td>
              <td class="postcontent">
                <div class="postinfo"><strong><a><em>1</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 08:00</em></div></div></div>
                <div class="defaultpost"><div class="postmessage">
                  <div class="t_msgfontfix"><table><tbody><tr><td class="t_msgfont">你最喜欢哪个系统？</td></tr></tbody></table></div>
                  <form id="poll" method="post" action="misc.php?action=votepoll">
                    <input type="hidden" name="formhash" value="11223344" />
                    <div class="pollinfo"><strong>单选投票</strong> , 共有 10 人参与投票 <a href="misc.php?action=viewvote">查看投票参与人</a></div>
                    <p class="polltimer">距结束还有: <strong>2 天 4 小时</strong></p>
                    <div class="pollchart"><table><tbody>
                      <tr><td class="selector"><input type="radio" name="pollanswers[]" value="501" /></td><td class="polloption"><label>Android&amp;#128512;</label></td></tr>
                      <tr><td></td><td><em>70.00% (7)</em></td></tr>
                      <tr><td class="selector"><input type="radio" name="pollanswers[]" value="502" /></td><td class="polloption"><label>iOS</label></td></tr>
                      <tr><td></td><td><em>30.00% (3)</em></td></tr>
                      <tr><td colspan="2"><button type="submit">提交</button> 投票后结果可见</td></tr>
                    </tbody></table></div>
                  </form>
                </div></div>
              </td>
            </tr></tbody></table></div></div></div></body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val before = doc.outerHtml()
        val page = ThreadDetailParser.parse(doc, tid = "50001")

        assertEquals(before, doc.outerHtml(), "Poll parsing must never mutate caller Document DOM")
        assertEquals(1, doc.select("form#poll div.pollinfo a").size)
        assertEquals(1, doc.select("form#poll button").size)

        val floor1 = page.floors.single()
        val poll = floor1.poll
        assertNotNull(poll)
        assertEquals(1, poll!!.maxChoices)
        assertFalse(poll.isMultipleChoice)
        assertEquals("11223344", poll.formhash)
        assertTrue(poll.titleHtml.contains("单选投票"))
        assertFalse(poll.titleHtml.contains("查看投票参与人"))
        assertEquals("单选投票 , 共有 10 人参与投票", poll.titleText)
        assertFalse(poll.titleText.contains('<'))
        assertFalse(poll.titleText.contains('>'))
        assertFalse(poll.titleText.contains("查看投票参与人"))
        assertEquals("距结束还有: <strong>2 天 4 小时</strong>", poll.timerHtml)
        assertEquals("距结束还有: 2 天 4 小时", poll.timerText)
        val timerText = poll.timerText!!
        assertFalse(timerText.contains('<'))
        assertFalse(timerText.contains('>'))
        assertEquals("${poll.titleHtml}<br>${poll.timerHtml}", poll.combinedTitleHtml)
        assertEquals("投票后结果可见", poll.footer)
        assertEquals(2, poll.options.size)
        assertEquals(PollOption(optionId = "501", text = "Android\uD83D\uDE00", ratesText = "70.00% (7)"), poll.options[0])
        assertEquals(PollOption(optionId = "502", text = "iOS", ratesText = "30.00% (3)"), poll.options[1])
        assertEquals(listOf(ContentBlock.Text(listOf(InlineSpan.Plain("你最喜欢哪个系统？")))), floor1.blocks)
    }

    @Test
    fun parsePoll_multiChoiceAndSpecialMsgWithoutFormAndNonFirstFloorIgnored() {
        val html = """
            <html><body><div id="wrap"><div id="postlist">
              <div id="post_72001"><table><tbody><tr>
                <td class="postauthor"><div class="postinfo"><a href="space.php?uid=11002">OP2</a></div></td>
                <td class="postcontent">
                  <div class="postinfo"><strong><a><em>1</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 09:00</em></div></div></div>
                  <div class="defaultpost"><div class="postmessage">
                    <div class="specialmsg">
                      <table><tbody><tr><td class="t_msgfont">多选投票正文说明</td></tr></tbody></table>
                      <form id="poll" method="post">
                        <input type="hidden" name="formhash" value="deadbeef" />
                        <div class="pollinfo">多选投票: ( 最多可选 3 项 ) , 共有 15 人参与投票</div>
                        <div class="pollchart"><table><tbody>
                          <tr><td class="selector"><input type="checkbox" value="601" /></td><td class="polloption"><label>选项一</label></td></tr>
                          <tr><td class="selector"><input type="checkbox" value="602" /></td><td class="polloption"><label>选项二</label></td></tr>
                          <tr><td class="selector"><input type="checkbox" value="603" /></td><td class="polloption"><label>选项三</label></td></tr>
                          <tr><td colspan="2"><button type="submit">提交</button></td></tr>
                        </tbody></table></div>
                      </form>
                    </div>
                  </div></div>
                </td>
              </tr></tbody></table></div>
              <div id="post_72002"><table><tbody><tr>
                <td class="postauthor"><div class="postinfo"><a href="space.php?uid=11003">Reply</a></div></td>
                <td class="postcontent">
                  <div class="postinfo"><strong><a><em>2</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 09:05</em></div></div></div>
                  <div class="defaultpost"><div class="postmessage">
                    <div class="t_msgfontfix"><table><tbody><tr><td class="t_msgfont">2 楼不应挂载投票</td></tr></tbody></table></div>
                  </div></div>
                </td>
              </tr></tbody></table></div>
            </div></div></body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val before = doc.outerHtml()
        val page = ThreadDetailParser.parse(doc, tid = "50002")

        assertEquals(before, doc.outerHtml())
        assertEquals(2, page.floors.size)

        val poll = page.floors[0].poll
        assertNotNull(poll)
        assertEquals(3, poll!!.maxChoices)
        assertTrue(poll.isMultipleChoice)
        assertEquals("deadbeef", poll.formhash)
        assertNull(poll.timerHtml)
        assertNull(poll.footer)
        assertEquals(listOf("601", "602", "603"), poll.options.map { it.optionId })
        assertTrue(poll.options.all { it.ratesText == null })
        assertEquals(listOf(ContentBlock.Text(listOf(InlineSpan.Plain("多选投票正文说明")))), page.floors[0].blocks)

        // 2 楼不解析投票
        assertNull(page.floors[1].poll)
    }

    @Test
    fun parsePoll_closedSpecialMsgPollWithoutFormExtractsReadOnlyPollOptionsAndRates() {
        val html = """
            <html><body><div id="wrap"><div id="postlist"><div id="post_73001"><table><tbody><tr>
              <td class="postauthor"><div class="postinfo"><a href="space.php?uid=11004">OP3</a></div></td>
              <td class="postcontent">
                <div class="postinfo"><strong><a><em>1</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 10:00</em></div></div></div>
                <div class="defaultpost"><div class="postmessage">
                  <div class="specialmsg">
                    <table><tbody><tr><td class="t_msgfont">已截止投票说明</td></tr></tbody></table>
                    <div class="pollinfo">单选投票 , 共有 5 人参与投票</div>
                    <div class="pollchart"><table><tbody>
                      <tr><td class="polloption"><label>同意</label></td></tr>
                      <tr><td><em>80.00% (4)</em></td></tr>
                      <tr><td class="polloption"><label>反对</label></td></tr>
                      <tr><td><em>20.00% (1)</em></td></tr>
                      <tr><td>此投票已经关闭</td></tr>
                    </tbody></table></div>
                  </div>
                </div></div>
              </td>
            </tr></tbody></table></div></div></div></body></html>
        """.trimIndent()

        val page = ThreadDetailParser.parse(html)
        val floor1 = page.floors.single()
        val poll = floor1.poll
        assertNotNull(poll)
        assertEquals(1, poll!!.maxChoices)
        assertNull(poll.formhash)
        assertEquals("此投票已经关闭", poll.footer)
        assertEquals(
            listOf(
                PollOption(optionId = null, text = "同意", ratesText = "80.00% (4)"),
                PollOption(optionId = null, text = "反对", ratesText = "20.00% (1)"),
            ),
            poll.options,
        )
        assertEquals(listOf(ContentBlock.Text(listOf(InlineSpan.Plain("已截止投票说明")))), floor1.blocks)
    }

    @Test
    fun parsePoll_malformedPollWithoutFooterRowDoesNotLeakRatesIntoFooter() {
        val doc = Jsoup.parse(
            """
            <form id="poll">
              <div class="pollinfo">多选投票: ( 最多可选 abc 项 )</div>
              <div class="pollchart"><table><tbody>
                <tr><td class="selector"><input value="1" /></td><td class="polloption"><label>唯一项</label></td></tr>
                <tr><td></td><td><em>100.00% (1)</em></td></tr>
              </tbody></table></div>
            </form>
            """.trimIndent(),
        )
        val poll = ThreadDetailParser.parsePoll(doc.firstMatch("form#poll")!!)
        assertNotNull(poll)
        assertEquals(1, poll!!.maxChoices)
        assertEquals("100.00% (1)", poll.options.single().ratesText)
        assertNull(poll.footer)
    }

    @Test
    fun parsePoll_titleTextAndTimerTextStripHtmlTagsDecodeEntitiesAndFallbackSafely() {
        val doc = Jsoup.parse(
            """
            <form id="poll">
              <div class="pollinfo"><strong>多选投票</strong>: ( 最多可选 <em>2</em> 项 ) &amp;#128512; <a href="misc.php?action=viewvote">查看参与人</a></div>
              <p class="polltimer">距结束还有: <strong><span>3 天</span> <em>6 小时</em></strong> &amp;#128293;</p>
              <div class="pollchart"><table><tbody>
                <tr><td class="selector"><input type="checkbox" value="11" /></td><td class="polloption"><label>选项 A</label></td></tr>
              </tbody></table></div>
            </form>
            """.trimIndent(),
        )
        val poll = ThreadDetailParser.parsePoll(doc.firstMatch("form#poll")!!)
        assertNotNull(poll)
        assertEquals("多选投票: ( 最多可选 2 项 ) \uD83D\uDE00", poll!!.titleText)
        assertEquals("距结束还有: 3 天 6 小时 \uD83D\uDD25", poll.timerText)
        assertFalse(poll.titleText.contains('<') || poll.titleText.contains('>'))
        val parsedTimerText = poll.timerText!!
        assertFalse(parsedTimerText.contains('<') || parsedTimerText.contains('>'))
        assertFalse(poll.titleText.contains("查看参与人"))

        // 直接通过 6 参旧签构造 ThreadPoll 时，默认纯文本属性同样自动剥离全部 HTML 标签
        val constructed = ThreadPoll(
            titleHtml = "<strong>单选投票</strong> , 共有 <b>8</b> 人参与",
            timerHtml = "剩余 <strong>1 天</strong>",
            formhash = "abcd1234",
            maxChoices = 1,
            options = listOf(PollOption("1", "选项")),
            footer = null,
        )
        assertEquals("单选投票 , 共有 8 人参与", constructed.titleText)
        assertEquals("剩余 1 天", constructed.timerText)
        assertFalse(constructed.titleText.contains('<') || constructed.titleText.contains('>'))
        val constructedTimerText = constructed.timerText!!
        assertFalse(constructedTimerText.contains('<') || constructedTimerText.contains('>'))
    }
}
