package net.jejer.hipda.parser.threaddetail

import net.jejer.hipda.parser.common.firstMatch
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentBlockParserTest {

    private fun parseBody(innerHtml: String, forumBaseUrl: String? = "https://www.4d4y.com/forum/"): List<ContentBlock> {
        val doc = Jsoup.parse("<table><tbody><tr><td class=\"t_msgfont\">$innerHtml</td></tr></tbody></table>")
        val before = doc.outerHtml()
        val td = doc.firstMatch("td.t_msgfont")!!
        val blocks = ThreadDetailParser.parseContentBlocks(td, forumBaseUrl = forumBaseUrl)
        assertEquals(before, doc.outerHtml(), "parseContentBlocks must never mutate Document DOM")
        return blocks
    }

    @Test
    fun textFormatting_preservesRawColorForT6AndDecodesEmojiWithoutEntityLeak() {
        val blocks = parseBody(
            """
            普通 &lt;tag&gt; &amp;#128512;
            <strong>粗体</strong>
            <i>斜体</i>
            <u>下划线</u>
            <strike>删除线</strike>
            <font color="#ffffff">纯白原样透传</font>
            <font color="black">纯黑原样透传</font>
            <font color="#ff0000"><font size="4">继承红色</font><font color="blue">覆盖蓝色</font>恢复红色</font>
            <font size="1">小字号</font>
            """.trimIndent(),
        )

        assertEquals(1, blocks.size)
        val spans = (blocks[0] as ContentBlock.Text).spans.filterIsInstance<InlineSpan.Plain>()
        assertTrue(spans.any { it.text.contains("普通 <tag> \uD83D\uDE00") && it.format.isPlain })
        assertTrue(spans.any { it.text == "粗体" && it.format.bold })
        assertTrue(spans.any { it.text == "斜体" && it.format.italic })
        assertTrue(spans.any { it.text == "下划线" && it.format.underline })
        assertTrue(spans.any { it.text == "删除线" && it.format.strikethrough })
        assertTrue(spans.any { it.text == "纯白原样透传" && it.format.rawColor == "#ffffff" })
        assertTrue(spans.any { it.text == "纯黑原样透传" && it.format.rawColor == "black" })
        assertTrue(spans.any { it.text == "继承红色" && it.format.rawColor == "#ff0000" })
        assertTrue(spans.any { it.text == "覆盖蓝色" && it.format.rawColor == "blue" })
        assertTrue(spans.any { it.text == "恢复红色" && it.format.rawColor == "#ff0000" })
        assertTrue(spans.any { it.text == "小字号" && it.format.smallFont })
    }

    @Test
    fun linksAndEmails_supportAnchorsPlainTextDetectionAndSmallFontOldDomainReplacement() {
        val blocks = parseBody(
            """
            访问 https://example.com/a?b=1 或 www.test.org/path 联系 user@example.com
            <br>
            <a href="https://www.hi-pda.com/forum/viewthread.php?tid=1579403"><font size="1">来自小尾巴</font></a>
            <font size="1"><a href="https://www.hi-pda.com/forum/space.php?uid=1">外层小字号</a></font>
            <a href="https://www.hi-pda.com/forum/viewthread.php?tid=999">普通链接不替换</a>
            <a href="mailto:hi@4d4y.com">发邮件</a>
            """.trimIndent(),
        )

        assertEquals(1, blocks.size)
        val spans = (blocks[0] as ContentBlock.Text).spans
        val links = spans.filterIsInstance<InlineSpan.Link>()
        val emails = spans.filterIsInstance<InlineSpan.Email>()

        assertTrue(links.any { it.url == "https://example.com/a?b=1" && !it.isSmallFont })
        assertTrue(links.any { it.url == "https://www.test.org/path" && !it.isSmallFont })
        assertTrue(links.any { it.label == "来自小尾巴" && it.url == "https://www.4d4y.com/forum/viewthread.php?tid=1579403" && it.isSmallFont })
        assertTrue(links.any { it.label == "外层小字号" && it.url == "https://www.4d4y.com/forum/space.php?uid=1" && it.isSmallFont })
        assertTrue(links.any { it.label == "普通链接不替换" && it.url == "https://www.hi-pda.com/forum/viewthread.php?tid=999" && !it.isSmallFont })
        assertEquals(listOf("user@example.com", "hi@4d4y.com"), emails.map { it.email })
        assertTrue(spans.contains(InlineSpan.LineBreak))
    }

    @Test
    fun smiliesAndSmallImages_normalizeKeysPerC1b2AndSkipForumUiIcons() {
        assertEquals("default_smile", ThreadDetailParser.normalizeSmileyKey("https://img02.4d4y.com/forum/images/smilies/default/smile.gif"))
        assertEquals("coolmonkey_01", ThreadDetailParser.normalizeSmileyKey("images/smilies/coolmonkey/01.gif?v=1"))
        assertEquals("grapeman_24", ThreadDetailParser.normalizeSmileyKey("https://www.4d4y.com/forum/images/smilies/grapeman/24.png"))
        assertNull(ThreadDetailParser.normalizeSmileyKey("https://img02.4d4y.com/forum/images/default/attachimg.gif"))
        assertNull(ThreadDetailParser.normalizeSmileyKey(""))

        val blocks = parseBody(
            """
            你好
            <img src="https://img02.4d4y.com/forum/images/smilies/default/smile.gif" />
            <img src="images/smilies/coolmonkey/01.gif" />
            <img src="https://img02.4d4y.com/forum/images/smilies/grapeman/08.gif" />
            <img src="https://img02.4d4y.com/forum/attachments/day_140621/1406211752793e731a4fec8f7b.png" />
            <img src="https://img02.4d4y.com/forum/images/default/attachimg.gif" />
            <img src="data:image/png;base64,iVBORw0KGgo=" />
            """.trimIndent(),
        )

        assertEquals(1, blocks.size)
        val spans = (blocks[0] as ContentBlock.Text).spans
        val emojis = spans.filterIsInstance<InlineSpan.Emoji>()
        assertEquals(listOf("default_smile", "coolmonkey_01", "grapeman_08"), emojis.map { it.normalizedKey })

        val smallImgs = spans.filterIsInstance<InlineSpan.SmallImage>()
        assertEquals(1, smallImgs.size)
        assertEquals(ThreadDetailParser.SMALL_IMAGE_WIN_KEY, smallImgs[0].key)
    }

    @Test
    fun quoteBlock_removesMetaAndDisplayNoneWithoutMutatingDom() {
        val html = """
            <div class="quote">
              <blockquote>
                <font size="2"><font color="#999999">Alice 发表于 2026-11-20 09:00</font>
                  <a href="https://www.4d4y.com/forum/redirect.php?goto=findpost&amp;pid=55001&amp;ptid=22001">跳转</a>[/img][/url]
                </font>
                <br>可见引用&amp;#128076;
                <span style="display: none">隐藏水印内容</span>
                <div style="color:red; display:none;">另一隐藏段</div>
              </blockquote>
            </div>
            <br>跟帖回复
        """.trimIndent()

        val doc = Jsoup.parse("<table><tbody><tr><td class=\"t_msgfont\">$html</td></tr></tbody></table>")
        val before = doc.outerHtml()
        val blocks = ThreadDetailParser.parseContentBlocks(doc.firstMatch("td.t_msgfont")!!)

        assertEquals(before, doc.outerHtml())
        assertEquals(1, doc.select("div.quote font[size=2]").size, "Original font[size=2] must remain in DOM")
        assertEquals(2, doc.select("div.quote [style*=display][style*=none]").size, "Original hidden nodes must remain in DOM")

        assertEquals(2, blocks.size)
        val quote = blocks[0] as ContentBlock.Quote
        assertEquals("22001", quote.targetTid)
        assertEquals("55001", quote.targetPid)
        assertEquals("Alice 发表于 2026-11-20 09:00", quote.authorAndTime)
        assertEquals("Alice", quote.author)
        assertEquals("2026-11-20 09:00", quote.time)
        assertEquals("可见引用\uD83D\uDC4C", quote.contentText)
        assertFalse(quote.contentHtml.contains("隐藏水印内容"))
        assertFalse(quote.contentHtml.contains("另一隐藏段"))
        assertFalse(quote.contentText.contains("隐藏水印内容"))

        val replyText = blocks[1] as ContentBlock.Text
        assertEquals(listOf(InlineSpan.Plain("跟帖回复")), replyText.spans)
    }

    @Test
    fun goToFloorBlock_parsesReplyStrongAndKeepsRegularStrongAsBold() {
        val blocks = parseBody(
            """
            <strong>回复 <a href="redirect.php?goto=findpost&amp;pid=66001&amp;ptid=33001">12#</a> <i>Bob</i></strong>
            <br>
            <strong>普通加粗文本</strong>
            """.trimIndent(),
        )

        assertEquals(2, blocks.size)
        val goto = blocks[0] as ContentBlock.GoToFloor
        assertEquals("回复 12# Bob", goto.label)
        assertEquals("33001", goto.targetTid)
        assertEquals("66001", goto.targetPid)
        assertEquals(12, goto.floor)
        assertEquals("Bob", goto.author)

        val boldBlock = blocks[1] as ContentBlock.Text
        assertEquals(listOf(InlineSpan.Plain("普通加粗文本", TextFormat(bold = true))), boldBlock.spans)
    }

    @Test
    fun images_respectOnclickOverFileOverThumbPriorityAndAttachImgSize() {
        val postHtml = """
            <html><body><div id="wrap"><div id="postlist"><div id="post_90001"><table><tbody><tr>
              <td class="postauthor"><div class="postinfo"><a href="space.php?uid=9001">ImgAuthor</a></div></td>
              <td class="postcontent">
                <div class="postinfo"><strong><a><em>1</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 12:00</em></div></div></div>
                <div class="defaultpost"><div class="postmessage">
                  <div class="t_msgfontfix">
                    <table><tbody><tr><td class="t_msgfont">
                      <img id="aimg_101" src="attachments/day_260101/pic1.jpg.thumb.jpg" file="attachments/day_260101/pic1_mid.jpg" onclick="zoom(this, 'attachments/day_260101/pic1_full.jpg')" />
                      <div class="t_attach" id="aimg_101_menu">下载 (1.5 MB)</div>
                      <img id="aimg_102" src="images/common/none.gif" file="https://img02.4d4y.com/forum/attachments/day_260101/pic2.png" />
                      <div class="t_attach" id="aimg_102_menu">下载 (512 Bytes)</div>
                      <img id="aimg_103" src="https://img02.4d4y.com/forum/attachments/day_260101/pic3.thumb.jpg" />
                      <img src="https://cdn.example.invalid/external.webp" />
                      <img src="" />
                    </td></tr></tbody></table>
                    <div class="postattachlist">
                      <dl class="t_attachlist attachimg">
                        <dt><em>(2.5 KB)</em></dt>
                        <dd><img src="attachments/day_260101/tail.thumb.jpg" file="attachments/day_260101/tail.jpg" /></dd>
                      </dl>
                    </div>
                  </div>
                </div></div>
              </td>
            </tr></tbody></table></div></div></div></body></html>
        """.trimIndent()

        val page = ThreadDetailParser.parse(postHtml, forumBaseUrl = "https://www.4d4y.com/forum/")
        val blocks = page.floors.single().blocks
        val images = blocks.filterIsInstance<ContentBlock.Image>()
        val notices = blocks.filterIsInstance<ContentBlock.Notice>()

        assertEquals(5, images.size)
        // 1: onclick 优先于 file 与 thumb
        assertEquals("https://www.4d4y.com/forum/attachments/day_260101/pic1_full.jpg", images[0].fullUrl)
        assertEquals("https://www.4d4y.com/forum/attachments/day_260101/pic1.jpg.thumb.jpg", images[0].thumbUrl)
        assertEquals(1572864L, images[0].fileSizeBytes)
        // 2: file 优先于 none.gif
        assertEquals("https://img02.4d4y.com/forum/attachments/day_260101/pic2.png", images[1].fullUrl)
        assertNull(images[1].thumbUrl)
        assertEquals(512L, images[1].fileSizeBytes)
        // 3: 仅 thumb. 回退
        assertEquals("https://img02.4d4y.com/forum/attachments/day_260101/pic3.thumb.jpg", images[2].fullUrl)
        assertEquals("https://img02.4d4y.com/forum/attachments/day_260101/pic3.thumb.jpg", images[2].thumbUrl)
        assertEquals(0L, images[2].fileSizeBytes)
        // 4: 外链图
        assertEquals("https://cdn.example.invalid/external.webp", images[3].fullUrl)
        assertNull(images[3].thumbUrl)
        assertEquals(0L, images[3].fileSizeBytes)
        // 5: 文末 dl.attachimg
        assertEquals("https://www.4d4y.com/forum/attachments/day_260101/tail.jpg", images[4].fullUrl)
        assertEquals("https://www.4d4y.com/forum/attachments/day_260101/tail.thumb.jpg", images[4].thumbUrl)
        assertEquals(2560L, images[4].fileSizeBytes)

        // 空 src 产出 Notice
        assertEquals(listOf(ContentBlock.Notice("[[ERROR:UNPARSED IMG:]]")), notices)

        // parseSizeText 边界
        assertEquals(-1L, ThreadDetailParser.parseSizeText("invalid"))
        assertEquals(-1L, ThreadDetailParser.parseSizeText("NaN KB"))
    }

    @Test
    fun attachments_coverInlineSpanDirectAnchorAndTailAttachListWithoutDomMutation() {
        val html = """
            <html><body><div id="wrap"><div id="postlist"><div id="post_90002"><table><tbody><tr>
              <td class="postauthor"><div class="postinfo"><a href="space.php?uid=9002">AttAuthor</a></div></td>
              <td class="postcontent">
                <div class="postinfo"><strong><a><em>1</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 13:00</em></div></div></div>
                <div class="defaultpost"><div class="postmessage">
                  <div class="t_msgfontfix">
                    <table><tbody><tr><td class="t_msgfont">
                      <span><a href="attachment.php?aid=111">manual.pdf</a></span> (120 KB, 下载次数: 5)
                      <span>
                        <img id="aimg_222" src="images/common/none.gif" file="https://img02.4d4y.com/forum/attachments/day_260101/in_span.png" />
                        <div class="t_attach" id="aimg_222_menu"><a href="attachment.php?aid=222&amp;nothumb=yes">下载</a> (4 KB)</div>
                      </span>
                      <a href="attachment.php?aid=333">direct.zip</a>
                    </td></tr></tbody></table>
                  </div>
                  <dl class="t_attachlist">
                    <dd><p class="attachname"><a href="attachment.php?aid=444">archive.tar.gz</a> (2.1 MB) 下载次数: 3</p></dd>
                  </dl>
                </div></div>
              </td>
            </tr></tbody></table></div></div></div></body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val before = doc.outerHtml()
        val page = ThreadDetailParser.parse(doc, forumBaseUrl = "https://www.4d4y.com/forum/")

        assertEquals(before, doc.outerHtml())
        val blocks = page.floors.single().blocks
        val attachments = blocks.filterIsInstance<ContentBlock.Attachment>()
        assertEquals(3, attachments.size)

        // 1: 行内 span 非 nothumb 附件，消费后续兄弟文本节点而不重复进 Text
        assertEquals("https://www.4d4y.com/forum/attachment.php?aid=111", attachments[0].url)
        assertEquals("manual.pdf", attachments[0].title)
        assertEquals("(120 KB, 下载次数: 5)", attachments[0].description)
        val leakedDesc = blocks.filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
            .filterIsInstance<InlineSpan.Plain>()
            .any { it.text.contains("下载次数: 5") }
        assertFalse(leakedDesc, "Sibling description text must not duplicate into ContentBlock.Text")

        // 2: 直出 attachment.php? 链接
        assertEquals("https://www.4d4y.com/forum/attachment.php?aid=333", attachments[1].url)
        assertEquals("direct.zip", attachments[1].title)
        assertNull(attachments[1].description)

        // 3: 文末 dl.t_attachlist p.attachname
        assertEquals("https://www.4d4y.com/forum/attachment.php?aid=444", attachments[2].url)
        assertEquals("archive.tar.gz", attachments[2].title)
        assertEquals("(2.1 MB) 下载次数: 3", attachments[2].description)

        // 含 nothumb= 的图片包装 span 应下钻提取为图片块而非附件
        val images = blocks.filterIsInstance<ContentBlock.Image>()
        assertEquals(1, images.size)
        assertEquals("https://img02.4d4y.com/forum/attachments/day_260101/in_span.png", images[0].fullUrl)
        assertEquals(4096L, images[0].fileSizeBytes)
    }

    @Test
    fun listsTablesAndVideoScripts_produceExpectedSeparatorsAndLinks() {
        val blocks = parseBody(
            """
            <ul><li>第一项</li><li>第二项</li></ul>
            <table><tbody><tr><td>格1</td><td>格2</td></tr><tr><td>格3</td></tr></tbody></table>
            <script>'src', 'http://player.youku.com/player.php/sid/XNzIyMTUxMzEy.html/v.swf'</script>
            <p class="imgtitle">应忽略的图片标题</p>
            <div class="attach_popup">应忽略的弹窗</div>
            """.trimIndent(),
        )

        assertEquals(1, blocks.size)
        val spans = (blocks[0] as ContentBlock.Text).spans
        val textJoined = spans.filterIsInstance<InlineSpan.Plain>().joinToString("|") { it.text }
        assertTrue(textJoined.contains("第一项"))
        assertTrue(textJoined.contains("第二项"))
        assertTrue(textJoined.contains("格1"))
        assertTrue(textJoined.contains("格2"))
        assertFalse(textJoined.contains("应忽略的图片标题"))
        assertFalse(textJoined.contains("应忽略的弹窗"))
        assertTrue(spans.count { it == InlineSpan.LineBreak } >= 2)
        val youkuLink = spans.filterIsInstance<InlineSpan.Link>().single()
        assertEquals("http://v.youku.com/v_show/id_XNzIyMTUxMzEy.html", youkuLink.url)
    }

    @Test
    fun relativeLinks_preserveRelativePathsWhenForumBaseUrlIsNullAndResolveWhenProvided() {
        val html = """
            <a href="/">首页</a>
            <a href="/forum/viewthread.php?tid=1">根路径帖子</a>
            <a href="search.php?srchtxt=hi">搜索</a>
            <a href="pm.php?filter=privatepm">短消息</a>
            <a href="my.php?item=favorites">收藏</a>
            <a href="viewthread.php?tid=2">相对帖子</a>
            <a href="www.test.org/path">裸外链</a>
            <a href="example.com/search.php">外站脚本</a>
        """.trimIndent()

        // forumBaseUrl == null：站内相对链接保持相对路径，绝不误加 https:// 前缀；外链仍补全 https://
        val relativeLinks = (parseBody(html, forumBaseUrl = null).single() as ContentBlock.Text)
            .spans
            .filterIsInstance<InlineSpan.Link>()
            .associate { it.label to it.url }

        assertEquals("/", relativeLinks["首页"])
        assertEquals("/forum/viewthread.php?tid=1", relativeLinks["根路径帖子"])
        assertEquals("search.php?srchtxt=hi", relativeLinks["搜索"])
        assertEquals("pm.php?filter=privatepm", relativeLinks["短消息"])
        assertEquals("my.php?item=favorites", relativeLinks["收藏"])
        assertEquals("viewthread.php?tid=2", relativeLinks["相对帖子"])
        assertEquals("https://www.test.org/path", relativeLinks["裸外链"])
        assertEquals("https://example.com/search.php", relativeLinks["外站脚本"])

        // 提供 forumBaseUrl 时按 origin / forumBaseUrl 正确补全绝对地址
        val resolvedLinks = (parseBody(html, forumBaseUrl = "https://www.4d4y.com/forum/").single() as ContentBlock.Text)
            .spans
            .filterIsInstance<InlineSpan.Link>()
            .associate { it.label to it.url }

        assertEquals("https://www.4d4y.com/", resolvedLinks["首页"])
        assertEquals("https://www.4d4y.com/forum/viewthread.php?tid=1", resolvedLinks["根路径帖子"])
        assertEquals("https://www.4d4y.com/forum/search.php?srchtxt=hi", resolvedLinks["搜索"])
        assertEquals("https://www.4d4y.com/forum/pm.php?filter=privatepm", resolvedLinks["短消息"])
        assertEquals("https://www.4d4y.com/forum/my.php?item=favorites", resolvedLinks["收藏"])
        assertEquals("https://www.4d4y.com/forum/viewthread.php?tid=2", resolvedLinks["相对帖子"])
        assertEquals("https://www.test.org/path", resolvedLinks["裸外链"])
        assertEquals("https://example.com/search.php", resolvedLinks["外站脚本"])
    }

    @Test
    fun inlineWhitespace_preservesSingleSpaceBetweenInlineElementsWithoutBlockEdgeSpaces() {
        // 1. "文字 <a>链接</a> <b>粗</b>" 不粘连且无块首尾空格
        val combo1 = parseBody("""   文字 <a href="https://a.com">链接</a> <b>粗</b>   """)
        assertEquals(
            listOf(
                ContentBlock.Text(
                    listOf(
                        InlineSpan.Plain("文字 "),
                        InlineSpan.Link(label = "链接", url = "https://a.com", isSmallFont = false),
                        InlineSpan.Plain(" "),
                        InlineSpan.Plain("粗", TextFormat(bold = true)),
                    ),
                ),
            ),
            combo1,
        )

        // 2. 紧邻双 <a> 与 <b>/<i> 间多空白折叠为单个空格
        val combo2 = parseBody("""  <a href="https://a.com">A</a>   <a href="https://b.com">B</a>  """)
        assertEquals(
            listOf(
                ContentBlock.Text(
                    listOf(
                        InlineSpan.Link("A", "https://a.com"),
                        InlineSpan.Plain(" "),
                        InlineSpan.Link("B", "https://b.com"),
                    ),
                ),
            ),
            combo2,
        )

        val combo3 = parseBody("  <b>A</b>   \n\t   <i>B</i>  ")
        assertEquals(
            listOf(
                ContentBlock.Text(
                    listOf(
                        InlineSpan.Plain("A", TextFormat(bold = true)),
                        InlineSpan.Plain(" "),
                        InlineSpan.Plain("B", TextFormat(italic = true)),
                    ),
                ),
            ),
            combo3,
        )

        // 3. 紧邻纯文本双 URL 保留空格，且 <br> 与块级图片前后不产生多余空格
        val combo4 = parseBody(
            """
              https://a.com https://b.com
              <br>
              第二行
              <img src="https://cdn.example.invalid/pic.jpg" />
              图后文本
            """.trimIndent(),
        )
        assertEquals(3, combo4.size)
        assertEquals(
            ContentBlock.Text(
                listOf(
                    InlineSpan.Link("https://a.com", "https://a.com"),
                    InlineSpan.Plain(" "),
                    InlineSpan.Link("https://b.com", "https://b.com"),
                    InlineSpan.LineBreak,
                    InlineSpan.Plain("第二行"),
                ),
            ),
            combo4[0],
        )
        assertEquals(ContentBlock.Image("https://cdn.example.invalid/pic.jpg", null, 0L), combo4[1])
        assertEquals(ContentBlock.Text(listOf(InlineSpan.Plain("图后文本"))), combo4[2])
    }

    @Test
    fun interleavedBlocks_preserveTextImageTextOrderAndTableCellSpacing() {
        // 1. 文本 -> 图片 -> 文本 -> 引用 -> 文本 精确交错块序（守卫 addBlock 前 flushPendingText）
        val interleaved = parseBody(
            """图前正文<img src="https://cdn.example.invalid/mid.jpg" />图后正文<div class="quote"><blockquote><font size="2">Bob 发表于 10:00</font>引用正文</blockquote></div>引后正文""",
        )
        assertEquals(5, interleaved.size)
        assertEquals(ContentBlock.Text(listOf(InlineSpan.Plain("图前正文"))), interleaved[0])
        assertEquals(ContentBlock.Image("https://cdn.example.invalid/mid.jpg", null, 0L), interleaved[1])
        assertEquals(ContentBlock.Text(listOf(InlineSpan.Plain("图后正文"))), interleaved[2])
        val quote = interleaved[3] as ContentBlock.Quote
        assertEquals("Bob 发表于 10:00", quote.authorAndTime)
        assertEquals("引用正文", quote.contentText)
        assertEquals(ContentBlock.Text(listOf(InlineSpan.Plain("引后正文"))), interleaved[4])

        // 2. 表格无源码空白时，<td> 之间保留单空格分隔且行首无多余空格
        val tableBlocks = parseBody(
            """<table><tbody><tr><td>格1</td><td>格2</td></tr><tr><td>格3</td></tr></tbody></table>""",
        )
        assertEquals(
            listOf(
                ContentBlock.Text(
                    listOf(
                        InlineSpan.Plain("格1 格2"),
                        InlineSpan.LineBreak,
                        InlineSpan.Plain("格3"),
                    ),
                ),
            ),
            tableBlocks,
        )
    }
}
