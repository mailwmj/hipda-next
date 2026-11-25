package net.jejer.hipda.parser.post

import net.jejer.hipda.parser.SampleFixtures
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PostFavoriteUploadParserTest {

    @Test
    fun prePostParser_realNewThreadSampleParsesFormFieldsUploadLimitAndOrderedTypeOptions() {
        val doc = SampleFixtures.parseSampleDocument(SAMPLE_NEWTHREAD_FID2)
        val before = doc.outerHtml()

        val result = PrePostParser.parse(doc, PrePostMode.NEW_THREAD)
        assertEquals(before, doc.outerHtml(), "PrePostParser must not mutate Document")
        assertTrue(result is PrePostParseResult.Success)
        val info = (result as PrePostParseResult.Success).info

        assertEquals("00000000", info.formhash)
        assertEquals("10000", info.uid)
        assertEquals("00000000000000000000000000000000", info.hash)
        assertEquals("", info.subject)
        assertEquals("", info.text)
        assertEquals("", info.quoteText)
        assertFalse(info.isDeletable)
        assertEquals(8 * 1024 * 1024, info.maxUploadFileSizeBytes)
        assertNull(info.noticeAuthor)
        assertNull(info.noticeAuthorMsg)
        assertNull(info.noticeTrimStr)
        assertTrue(info.existingImages.isEmpty())
        assertTrue(info.existingAttachments.isEmpty())
        assertEquals("0", info.typeId)
        assertEquals(16, info.typeOptions.size)
        assertEquals(
            listOf("0", "9", "33", "38", "40", "56", "57", "58", "65", "66", "67", "79", "81", "82", "39", "19"),
            info.typeOptions.keys.toList(),
        )
        assertEquals("Discovery", info.typeOptions["56"])
        assertEquals("站务", info.typeOptions["19"])
        assertTrue(info.meta.session.isLoggedIn)
    }

    @Test
    fun prePostParser_syntheticEditQuoteReplyAndErrorFixturesCoverAllBranches() {
        // 1. EDIT_POST：含 subject、input#delete、selected typeid、三类已上传图片/附件去重、KB 上限
        val editHtml = """
            <html><head>
              <script type="text/javascript">var discuz_uid = 10000, fid = 2;</script>
            </head><body>
              <form id="postform">
                <input type="hidden" name="formhash" value="edit1234" />
                <input type="hidden" name="hash" value="hash5678" />
                <input name="subject" value="原主题标题" />
                <select id="typeid">
                  <option value="0">分类</option>
                  <option value="33" selected="selected">汽车</option>
                  <option value="56">Discovery</option>
                </select>
                <textarea name="message">原正文内容</textarea>
                <input type="checkbox" id="delete" value="1" />
                <input type="hidden" name="noticeauthor" value="should_ignore_in_edit" />
                <div class="notice uploadinfo">文件尺寸: <strong>小于 512KB </strong></div>
                <div id="unusedimgattachlist">
                  <table class="imglist">
                    <tr><td><img src="attachments/day_261125/a.jpg" id="image_1001" /></td></tr>
                    <tr><td><img src="other/ignore.jpg" id="image_9999" /></td></tr>
                  </table>
                </div>
                <div class="upfilelist">
                  <img id="image_1002" src="thumb.jpg" />
                  <img id="image_1001" src="dup.jpg" />
                  <span><a href="javascript:;" onclick="insertAttachimgTag('1003')">img3.jpg</a></span>
                  <span><a href="javascript:;" onclick="insertAttachimgTag('1002')">dup2.jpg</a></span>
                  <span><a href="javascript:;" onclick="insertAttachTag('2001')">doc1.pdf</a></span>
                  <span><a href="javascript:;" onclick="insertAttachTag('2001')">dup.pdf</a></span>
                  <span><a href="javascript:;" onclick="insertAttachTag('2002')">doc2.zip</a></span>
                </div>
              </form>
            </body></html>
        """.trimIndent()
        val editResult = PrePostParser.parse(editHtml, PrePostMode.EDIT_POST)
        assertTrue(editResult is PrePostParseResult.Success)
        val editInfo = (editResult as PrePostParseResult.Success).info
        assertEquals("edit1234", editInfo.formhash)
        assertEquals("10000", editInfo.uid)
        assertEquals("hash5678", editInfo.hash)
        assertEquals("原主题标题", editInfo.subject)
        assertEquals("原正文内容", editInfo.text)
        assertEquals("", editInfo.quoteText)
        assertTrue(editInfo.isDeletable)
        assertEquals(512 * 1024, editInfo.maxUploadFileSizeBytes)
        assertNull(editInfo.noticeAuthor, "EDIT_POST must not populate noticeAuthor")
        assertEquals(listOf("1001", "1002", "1003"), editInfo.existingImages)
        assertEquals(listOf("2001", "2002"), editInfo.existingAttachments)
        assertEquals("33", editInfo.typeId)

        // 2. QUOTE_POST & REPLY_POST：quoteText + 3 个 notice* 字段
        val quoteHtml = """
            <html><head>
              <script>var discuz_uid = 10000, fid = 2;</script>
            </head><body>
              <form>
                <input type="hidden" name="formhash" value="q1234567" />
                <input type="hidden" name="hash" value="qhash999" />
                <textarea name="message">[quote]被引用的内容[/quote]</textarea>
                <input type="hidden" name="noticeauthor" value="enc_author" />
                <input type="hidden" name="noticeauthormsg" value="enc_msg" />
                <input type="hidden" name="noticetrimstr" value="[b]回复 [url=...]1#[/url] [i]Alice[/i] [/b]" />
                <div class="uploadinfo">文件尺寸: 小于 1KB</div>
              </form>
            </body></html>
        """.trimIndent()
        val quoteInfo = (PrePostParser.parse(quoteHtml, PrePostMode.QUOTE_POST) as PrePostParseResult.Success).info
        assertEquals("", quoteInfo.text)
        assertEquals("[quote]被引用的内容[/quote]", quoteInfo.quoteText)
        assertEquals("enc_author", quoteInfo.noticeAuthor)
        assertEquals("enc_msg", quoteInfo.noticeAuthorMsg)
        assertEquals("[b]回复 [url=...]1#[/url] [i]Alice[/i] [/b]", quoteInfo.noticeTrimStr)
        assertNull(quoteInfo.maxUploadFileSizeBytes, "1024 bytes is not > 1024 and must be ignored")

        val replyPostInfo = (PrePostParser.parse(quoteHtml, PrePostMode.REPLY_POST) as PrePostParseResult.Success).info
        assertEquals("[quote]被引用的内容[/quote]", replyPostInfo.quoteText)
        assertEquals("enc_author", replyPostInfo.noticeAuthor)

        // 3. 缺 formhash 错页（带 alert_error 且不能变异 DOM，以及无 alert_error 兜底）
        val errorDoc = Jsoup.parse(
            """<html><body><div class="alert_error">您所在的用户组无法发帖 <a href="index.php">[返回]</a></div></body></html>""",
        )
        val errorBefore = errorDoc.outerHtml()
        assertEquals(
            PrePostParseResult.Error("您所在的用户组无法发帖"),
            PrePostParser.parse(errorDoc, PrePostMode.NEW_THREAD),
        )
        assertEquals(errorBefore, errorDoc.outerHtml())

        assertEquals(
            PrePostParseResult.Error("页面解析错误"),
            PrePostParser.parse("<html><body><p>空页</p></body></html>", PrePostMode.NEW_THREAD),
        )

        // 4. 布尔简写 selected、小数 MB 上限、缺 script 时从 input[name=uid] 兜底
        val compactHtml = """
            <html><body>
              <form>
                <input type="hidden" name="formhash" value="c1234567" />
                <input type="hidden" name="uid" value="54321" />
                <select id="typeid">
                  <option value="0">分类</option>
                  <option value="56" selected>Discovery</option>
                </select>
                <div class="uploadinfo">文件尺寸: 小于 1.5MB</div>
              </form>
            </body></html>
        """.trimIndent()
        val compactInfo = (PrePostParser.parse(compactHtml, PrePostMode.NEW_THREAD) as PrePostParseResult.Success).info
        assertEquals("54321", compactInfo.uid)
        assertEquals("56", compactInfo.typeId)
        assertEquals((1.5f * 1024f * 1024f).toInt(), compactInfo.maxUploadFileSizeBytes)
    }

    @Test
    fun favoriteParser_parsesRealSampleIdsAndSyntheticActionXml() {
        val doc = SampleFixtures.parseSampleDocument(SAMPLE_MY_FAVORITES)
        val before = doc.outerHtml()

        val page = FavoriteParser.parseFavoriteIdsPage(doc)
        assertEquals(before, doc.outerHtml(), "FavoriteParser must not mutate Document")
        assertEquals(4, page.lastPage)
        assertTrue(page.tids.contains("3468951"))
        assertTrue(page.tids.contains("3149924"))
        assertEquals(75, page.tids.size)
        assertFalse(page.tids.contains("0"))

        val addXml = """
            <?xml version="1.0" encoding="gbk"?>
            <root><![CDATA[此主题已成功添加到收藏夹中<script type="text/javascript" reload="1">hideWindow('fav');</script>]]></root>
        """.trimIndent()
        assertEquals(
            FavoriteActionResult("此主题已成功添加到收藏夹中"),
            FavoriteParser.parseFavoriteActionXml(addXml),
        )

        val removeXml = """
            <?xml version="1.0" encoding="gbk"?>
            <root><![CDATA[  此主题已从您的收藏夹中移除 &amp;#128076; <script>x()</script>]]></root>
        """.trimIndent()
        assertEquals(
            FavoriteActionResult("此主题已从您的收藏夹中移除 👌"),
            FavoriteParser.parseFavoriteActionXml(removeXml),
        )

        assertEquals(FavoriteActionResult(""), FavoriteParser.parseFavoriteActionXml(""))

        val mixedIdsHtml = """
            <html><body>
              <input class="checkbox" type="checkbox" name="delete[]" value="12345" />
              <input class="checkbox" type="checkbox" name="delete[]" value="0" />
              <input class="checkbox" type="checkbox" name="delete[]" value="invalid" />
              <input class="checkbox" type="checkbox" name="chkall" value="99999" />
            </body></html>
        """.trimIndent()
        val mixedPage = FavoriteParser.parseFavoriteIdsPage(mixedIdsHtml)
        assertEquals(1, mixedPage.lastPage)
        assertEquals(setOf("12345"), mixedPage.tids)
    }

    @Test
    fun uploadResponseParser_parsesValidZeroMissingAndInvalidResponses() {
        assertEquals(
            UploadImageResult.Success("1721652"),
            UploadResponseParser.parse("DISCUZUPLOAD|0|1721652|1"),
        )
        assertEquals(
            UploadImageResult.Success("2810014"),
            UploadResponseParser.parse("  DISCUZUPLOAD|0|2810014|1\r\n"),
        )
        assertEquals(
            UploadImageResult.Failed("无效上传图片ID"),
            UploadResponseParser.parse("DISCUZUPLOAD|0|0|1"),
        )
        assertEquals(
            UploadImageResult.Failed("无效上传图片ID"),
            UploadResponseParser.parse("DISCUZUPLOAD|0|"),
        )
        assertEquals(
            UploadImageResult.Failed("无效上传图片ID"),
            UploadResponseParser.parse("DISCUZUPLOAD|0"),
        )
        assertEquals(
            UploadImageResult.Failed("无效上传图片ID"),
            UploadResponseParser.parse("DISCUZUPLOAD|0|not_a_number|1"),
        )
        assertEquals(
            UploadImageResult.Failed("无法获取图片ID"),
            UploadResponseParser.parse("0"),
        )
        assertEquals(
            UploadImageResult.Failed("无法获取图片ID"),
            UploadResponseParser.parse("<html>error</html>"),
        )
    }

    companion object {
        private const val SAMPLE_NEWTHREAD_FID2 = "post_form/newthread_fid2.html"
        private const val SAMPLE_MY_FAVORITES = "simple_list/my_favorites.html"
    }
}
