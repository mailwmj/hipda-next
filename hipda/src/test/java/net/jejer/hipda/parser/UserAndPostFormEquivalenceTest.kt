package net.jejer.hipda.parser

import android.app.Application
import android.content.Context
import net.jejer.hipda.async.PostHelper
import net.jejer.hipda.async.PrePostAsyncTask
import net.jejer.hipda.bean.HiSettingsHelper
import net.jejer.hipda.bean.PrePostInfoBean
import net.jejer.hipda.parser.post.FavoriteActionResult
import net.jejer.hipda.parser.post.FavoriteParser
import net.jejer.hipda.parser.post.PrePostMode
import net.jejer.hipda.parser.post.PrePostParseResult
import net.jejer.hipda.parser.post.PrePostParser
import net.jejer.hipda.parser.post.UploadImageResult
import net.jejer.hipda.parser.post.UploadResponseParser
import net.jejer.hipda.parser.user.BlacklistParseResult
import net.jejer.hipda.parser.user.UserParser
import net.jejer.hipda.utils.HiParser
import net.jejer.hipda.utils.HiUtils
import net.jejer.hipda.utils.Utils
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
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
 * 覆盖用户资料、黑名单、发帖/回复/引用/编辑预取表单（反射调用只读旧 `PrePostAsyncTask.parseRsp`）、
 * 收藏 ID 与 XML 响应、图片上传 `DISCUZUPLOAD` 响应。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class UserAndPostFormEquivalenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = LegacyTestSupport.initLegacyEnvironment()
    }

    @Test
    fun userProfileSample_legacyAndNewAreFieldByFieldEquivalent() {
        val html = LegacyTestSupport.loadSample("user_info/user_profile.html")
        val newDoc = Jsoup.parse(html)
        val beforeHtml = newDoc.outerHtml()

        val legacy = HiParser.parseUserInfo(html)
        assertNotNull(legacy)
        val profile = UserParser.parseUserInfo(newDoc)

        assertEquals("UserParser.parseUserInfo must not mutate Document", beforeHtml, newDoc.outerHtml())
        assertEquals(legacy!!.uid, profile.uid)
        assertEquals(legacy.username, profile.username)
        assertEquals(legacy.isOnline, profile.isOnline)
        assertEquals(legacy.avatarUrl, profile.avatarUrl)
        assertEquals(legacy.formhash, profile.formhash)
        assertEquals(legacy.detail, profile.detail)

        assertEquals("10000", profile.uid)
        assertEquals("HiUser", profile.username)
        assertTrue(profile.isOnline)
        assertEquals("00000000", profile.formhash)
    }

    @Test
    fun blacklistSyntheticFixtures_legacyAndNewAreEquivalentForItemsEmptyAndInvalidPages() {
        // 1. 含条目与重复项
        val populatedHtml = """
            <html><body>
              <div class="blacklist">
                <ul class="commonlist">
                  <li><a href="space.php?username=SpammerA">SpammerA</a></li>
                  <li><a href="space.php?username=SpammerB&amp;from=black">SpammerB</a></li>
                  <li><a href="space.php?username=SpammerA">SpammerA</a></li>
                </ul>
              </div>
            </body></html>
        """.trimIndent()
        val legacyPop = HiParser.parseBlacklist(Jsoup.parse(populatedHtml))
        val newPopDoc = Jsoup.parse(populatedHtml)
        val beforePop = newPopDoc.outerHtml()
        val newPop = UserParser.parseBlacklist(newPopDoc)
        assertEquals(beforePop, newPopDoc.outerHtml())
        assertEquals(
            BlacklistParseResult.Success(usernames = legacyPop, isEmptyConfirmed = false),
            newPop,
        )

        // 2. 空态："暂无数据"
        val emptyHtml = """<html><body><div class="blacklist"><p>暂无数据</p></div></body></html>"""
        val legacyEmpty = HiParser.parseBlacklist(Jsoup.parse(emptyHtml))
        assertTrue(legacyEmpty.isEmpty())
        assertEquals(
            BlacklistParseResult.Success(usernames = emptyList(), isEmptyConfirmed = true),
            UserParser.parseBlacklist(emptyHtml),
        )

        // 3. 无条目且无"暂无数据"：旧抛 "黑名单数据解析错误"，新返回 InvalidPage
        val invalidDataHtml = """<html><body><div class="blacklist"><ul class="commonlist"></ul></div></body></html>"""
        val legacyDataErr = runCatching { HiParser.parseBlacklist(Jsoup.parse(invalidDataHtml)) }.exceptionOrNull()
        assertEquals("黑名单数据解析错误", legacyDataErr?.message)
        assertEquals(
            BlacklistParseResult.InvalidPage(legacyDataErr!!.message!!),
            UserParser.parseBlacklist(invalidDataHtml),
        )

        // 4. 缺 div.blacklist：旧抛 "页面解析错误"，新返回 InvalidPage
        val invalidPageHtml = """<html><body><p>未登录</p></body></html>"""
        val legacyPageErr = runCatching { HiParser.parseBlacklist(Jsoup.parse(invalidPageHtml)) }.exceptionOrNull()
        assertEquals("页面解析错误", legacyPageErr?.message)
        assertEquals(
            BlacklistParseResult.InvalidPage(legacyPageErr!!.message!!),
            UserParser.parseBlacklist(invalidPageHtml),
        )
    }

    @Test
    fun prePostNewThreadSample_legacyReflectionAndNewAreFieldByFieldEquivalent() {
        val html = LegacyTestSupport.loadSample("post_form/newthread_fid2.html")
        val legacyDoc = Jsoup.parse(html)
        val newDoc = Jsoup.parse(html)
        val beforeHtml = newDoc.outerHtml()

        HiSettingsHelper.getInstance().setMaxUploadFileSize(0)
        val (legacyBean, legacyMessage) = invokeLegacyPrePostParse(PostHelper.MODE_NEW_THREAD, legacyDoc)
        val legacyMaxUploadSize = HiSettingsHelper.getInstance().maxUploadFileSize
        assertNull(legacyMessage)
        assertNotNull(legacyBean)

        // 新解析器纯数值返回且绝不写入 HiSettingsHelper
        HiSettingsHelper.getInstance().setMaxUploadFileSize(12345)
        val newResult = PrePostParser.parse(newDoc, PrePostMode.NEW_THREAD)
        assertEquals(12345, HiSettingsHelper.getInstance().maxUploadFileSize)
        assertEquals("PrePostParser must not mutate Document", beforeHtml, newDoc.outerHtml())

        assertTrue(newResult is PrePostParseResult.Success)
        val info = (newResult as PrePostParseResult.Success).info
        assertPrePostEquivalent(legacyBean!!, legacyMaxUploadSize, info)
        assertEquals("00000000", info.formhash)
        assertEquals("10000", info.uid)
        assertEquals("00000000000000000000000000000000", info.hash)
        assertEquals(8 * 1024 * 1024, info.maxUploadFileSizeBytes)
        assertEquals(16, info.typeOptions.size)
    }

    @Test
    fun prePostSyntheticEditReplyQuoteAndError_legacyReflectionAndNewAreFieldByFieldEquivalent() {
        // 1. EDIT_POST
        val editHtml = """
            <html><head>
              <script type="text/javascript">var STYLEID = '1', discuz_uid = 10000, fid = 2;</script>
            </head><body>
              <form id="postform">
                <input type="hidden" name="formhash" value="f1122334" />
                <input type="hidden" name="hash" value="h99887766" />
                <input name="subject" value="编辑中的主题" />
                <select id="typeid">
                  <option value="0">分类</option>
                  <option value="33" selected="selected">汽车</option>
                  <option value="56">Discovery</option>
                </select>
                <textarea name="message">这是编辑前的正文内容</textarea>
                <input type="checkbox" id="delete" value="1" />
                <div class="uploadinfo">文件尺寸: <strong>小于 512KB </strong></div>
                <div id="unusedimgattachlist">
                  <table class="imglist">
                    <tr><td><img src="attachments/month_2611/img1.jpg" id="attach_3001" /></td></tr>
                  </table>
                </div>
                <div class="upfilelist">
                  <img id="image_3002" src="a.jpg" />
                  <span><a href="javascript:;" onclick="insertAttachimgTag('3003')">3003.jpg</a></span>
                  <span><a href="javascript:;" onclick="insertAttachTag('4001')">file.zip</a></span>
                </div>
              </form>
            </body></html>
        """.trimIndent()
        HiSettingsHelper.getInstance().setMaxUploadFileSize(0)
        val (legacyEdit, legacyEditErr) = invokeLegacyPrePostParse(PostHelper.MODE_EDIT_POST, Jsoup.parse(editHtml))
        val legacyEditMaxSize = HiSettingsHelper.getInstance().maxUploadFileSize
        assertNull(legacyEditErr)
        val newEditDoc = Jsoup.parse(editHtml)
        val beforeEdit = newEditDoc.outerHtml()
        val newEdit = (PrePostParser.parse(newEditDoc, PrePostMode.EDIT_POST) as PrePostParseResult.Success).info
        assertEquals(beforeEdit, newEditDoc.outerHtml())
        assertPrePostEquivalent(legacyEdit!!, legacyEditMaxSize, newEdit)

        // 2. QUOTE_POST 与 REPLY_POST
        val quoteHtml = """
            <html><head>
              <script type="text/javascript">var discuz_uid = 10000, fid = 2;</script>
            </head><body>
              <form id="postform">
                <input type="hidden" name="formhash" value="q8877665" />
                <input type="hidden" name="hash" value="qhash1122" />
                <textarea name="message">[quote]引用内容[/quote]</textarea>
                <input type="hidden" name="noticeauthor" value="u_enc_1" />
                <input type="hidden" name="noticeauthormsg" value="msg_enc_2" />
                <input type="hidden" name="noticetrimstr" value="trim_str_3" />
                <div class="uploadinfo">文件尺寸: 小于 2MB</div>
              </form>
            </body></html>
        """.trimIndent()
        for ((legacyMode, newMode) in listOf(
            PostHelper.MODE_QUOTE_POST to PrePostMode.QUOTE_POST,
            PostHelper.MODE_REPLY_POST to PrePostMode.REPLY_POST,
            PostHelper.MODE_REPLY_THREAD to PrePostMode.REPLY_THREAD,
            PostHelper.MODE_QUICK_REPLY to PrePostMode.QUICK_REPLY,
        )) {
            HiSettingsHelper.getInstance().setMaxUploadFileSize(0)
            val (legacyBean, legacyErr) = invokeLegacyPrePostParse(legacyMode, Jsoup.parse(quoteHtml))
            val legacyMax = HiSettingsHelper.getInstance().maxUploadFileSize
            assertNull(legacyErr)
            val newInfo = (PrePostParser.parse(quoteHtml, newMode) as PrePostParseResult.Success).info
            assertPrePostEquivalent(legacyBean!!, legacyMax, newInfo)
        }

        // 3. 缺失 formhash 错页（含 alert_error 链接：旧方法删 <a>，新方法不修改 DOM 且文案逐字相等）
        val noFormhashErrHtml = """
            <html><body>
              <div class="alert_error">对不起，您没有发帖权限 <a href="javascript:history.back()">[ 返回 ]</a></div>
            </body></html>
        """.trimIndent()
        val legacyErrDoc = Jsoup.parse(noFormhashErrHtml)
        val (legacyErrBean, legacyErrMsg) = invokeLegacyPrePostParse(PostHelper.MODE_NEW_THREAD, legacyErrDoc)
        assertNull(legacyErrBean!!.formhash)
        assertEquals("对不起，您没有发帖权限", legacyErrMsg)

        val newErrDoc = Jsoup.parse(noFormhashErrHtml)
        val newErrBefore = newErrDoc.outerHtml()
        assertEquals(
            PrePostParseResult.Error(legacyErrMsg!!),
            PrePostParser.parse(newErrDoc, PrePostMode.NEW_THREAD),
        )
        assertEquals(newErrBefore, newErrDoc.outerHtml())

        // 4. 缺失 formhash 且无 alert_error：回退 "页面解析错误"
        val fallbackHtml = "<html><body><div>empty</div></body></html>"
        val (_, legacyFallbackMsg) = invokeLegacyPrePostParse(PostHelper.MODE_NEW_THREAD, Jsoup.parse(fallbackHtml))
        assertEquals("页面解析错误", legacyFallbackMsg)
        assertEquals(
            PrePostParseResult.Error(legacyFallbackMsg!!),
            PrePostParser.parse(fallbackHtml, PrePostMode.NEW_THREAD),
        )
    }

    @Test
    fun favoriteIdsXmlAndUploadResponse_legacyAndNewAreEquivalent() {
        // 1. 收藏 ID 列表（真机样本 my_favorites.html，对照 FavoriteHelper.java:104-133）
        val html = LegacyTestSupport.loadSample("simple_list/my_favorites.html")
        val legacyDoc = Jsoup.parse(html)
        val newDoc = Jsoup.parse(html)
        val beforeHtml = newDoc.outerHtml()

        val (legacyLastPage, legacyTids) = simulateLegacyFavoriteIdsParse(legacyDoc)
        val newFavIds = FavoriteParser.parseFavoriteIdsPage(newDoc)
        assertEquals("FavoriteParser must not mutate Document", beforeHtml, newDoc.outerHtml())
        assertEquals(legacyLastPage, newFavIds.lastPage)
        assertEquals(4, newFavIds.lastPage)
        assertEquals(legacyTids, newFavIds.tids)
        assertEquals(75, newFavIds.tids.size)

        // 2. 收藏/关注增删 XML（对照 FavoriteHelper.java:218-257）
        val addXml = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[此主题已成功添加到收藏夹中<script>hide()</script>]]></root>"""
        val removeXml = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[此主题已从您的收藏夹中移除]]></root>"""
        assertEquals(
            FavoriteActionResult(simulateLegacyFavoriteActionXmlParse(addXml)),
            FavoriteParser.parseFavoriteActionXml(addXml),
        )
        assertEquals(
            FavoriteActionResult(simulateLegacyFavoriteActionXmlParse(removeXml)),
            FavoriteParser.parseFavoriteActionXml(removeXml),
        )

        // 3. 图片上传响应（对照 UploadImgHelper.java:210-227）
        for (response in listOf(
            "DISCUZUPLOAD|0|1721652|1",
            "DISCUZUPLOAD|0|0|1",
            "DISCUZUPLOAD|0",
            "0",
            "unexpected_error",
        )) {
            val (legacyImgId, legacyReason) = simulateLegacyUploadResponseParse(response)
            val newResult = UploadResponseParser.parse(response)
            if (legacyImgId != null) {
                assertEquals(UploadImageResult.Success(legacyImgId), newResult)
            } else {
                assertEquals(UploadImageResult.Failed(legacyReason!!), newResult)
            }
        }
    }

    private fun invokeLegacyPrePostParse(mode: Int, doc: Document): Pair<PrePostInfoBean?, String?> {
        val task = PrePostAsyncTask(context, null, mode)
        val method = PrePostAsyncTask::class.java.getDeclaredMethod("parseRsp", Document::class.java)
        method.isAccessible = true
        val bean = method.invoke(task, doc) as PrePostInfoBean?
        return bean to task.message
    }

    private fun assertPrePostEquivalent(
        legacy: PrePostInfoBean,
        legacyMaxUploadSize: Int,
        actual: net.jejer.hipda.parser.post.PrePostInfo,
    ) {
        assertEquals("formhash mismatch", legacy.formhash, actual.formhash)
        assertEquals("uid mismatch", legacy.uid?.ifEmpty { null }, actual.uid)
        assertEquals("hash mismatch", legacy.hash?.ifEmpty { null }, actual.hash)
        assertEquals("subject mismatch", legacy.subject, actual.subject)
        assertEquals("text mismatch", legacy.text, actual.text)
        assertEquals("quoteText mismatch", legacy.quoteText, actual.quoteText)
        assertEquals("isDeletable mismatch", legacy.isDeleteable, actual.isDeletable)
        assertEquals(
            "maxUploadFileSizeBytes mismatch",
            legacyMaxUploadSize.takeIf { it > 1024 },
            actual.maxUploadFileSizeBytes,
        )
        assertEquals("noticeAuthor mismatch", legacy.noticeAuthor, actual.noticeAuthor)
        assertEquals("noticeAuthorMsg mismatch", legacy.noticeAuthorMsg, actual.noticeAuthorMsg)
        assertEquals("noticeTrimStr mismatch", legacy.noticeTrimStr, actual.noticeTrimStr)
        assertEquals("existingImages mismatch", legacy.allImages, actual.existingImages)
        assertEquals("existingAttachments mismatch", legacy.attaches, actual.existingAttachments)
        assertEquals("typeId mismatch", legacy.typeId, actual.typeId)
        assertEquals("typeOptions entries mismatch", legacy.typeValues, actual.typeOptions)
        assertEquals("typeOptions key order mismatch", legacy.typeValues.keys.toList(), actual.typeOptions.keys.toList())
    }

    // 只读复现 FavoriteHelper.java:105-132
    private fun simulateLegacyFavoriteIdsParse(doc: Document): Pair<Int, Set<String>> {
        var lastPage = 1
        val divPageES = doc.select("div.pages")
        if (divPageES.size > 0) {
            val divPage = divPageES.firstOrNull()!!
            val pagesES = divPage.select("div.pages a")
            pagesES.addAll(divPage.select("div.pages strong"))
            for (n in pagesES) {
                val tmp = Utils.getIntFromString(n.text())
                if (tmp > lastPage) lastPage = tmp
            }
        }
        val tids = HashSet<String>()
        for (n in doc.select("input.checkbox[name=delete[]]")) {
            val tid = n.attr("value")
            if (HiUtils.isValidId(tid)) {
                tids.add(tid)
            }
        }
        return lastPage to tids
    }

    // 只读复现 FavoriteHelper.java:219-225, 248-254
    private fun simulateLegacyFavoriteActionXmlParse(response: String): String {
        var result = ""
        val doc = Jsoup.parse(response, "", Parser.xmlParser())
        for (e in doc.select("root")) {
            result = e.text()
            if (result.contains("<")) {
                result = result.substring(0, result.indexOf("<"))
            }
        }
        return result
    }

    // 只读复现 UploadImgHelper.java:211-226
    private fun simulateLegacyUploadResponseParse(responseText: String): Pair<String?, String?> {
        return if (responseText.contains("DISCUZUPLOAD")) {
            val s = responseText.split("|")
            if (s.size < 3 || s[2] == "0") {
                null to "无效上传图片ID"
            } else {
                s[2] to null
            }
        } else {
            null to "无法获取图片ID"
        }
    }
}
