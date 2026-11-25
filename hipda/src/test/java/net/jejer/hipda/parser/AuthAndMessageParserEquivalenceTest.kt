package net.jejer.hipda.parser

import android.app.Application
import android.content.Context
import net.jejer.hipda.R
import net.jejer.hipda.async.LoginHelper
import net.jejer.hipda.glide.GlideHelper
import net.jejer.hipda.job.SimpleListJob
import net.jejer.hipda.parser.auth.LoginFormParseResult
import net.jejer.hipda.parser.auth.LoginParser
import net.jejer.hipda.parser.auth.LoginSubmitResult
import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.message.NotifyKind
import net.jejer.hipda.parser.message.NotifyParser
import net.jejer.hipda.parser.message.SmsParser
import net.jejer.hipda.parser.message.SmsSendResult
import net.jejer.hipda.service.NotiHelper
import net.jejer.hipda.utils.Constants
import net.jejer.hipda.utils.HiParser
import net.jejer.hipda.utils.HiUtils
import org.jsoup.Jsoup
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
 * 旧解析器样本回归 + `:core-parser` 新解析器逐字段等价测试（完全离线）。
 * 覆盖：登录与页头、短消息列表、短消息详情、短消息发送 XML、提醒列表（空态 + 合成 5 类）与通知计数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AuthAndMessageParserEquivalenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = LegacyTestSupport.initLegacyEnvironment()
    }

    @Test
    fun loginPageSample_legacyAndNewHeaderSessionFormhashAndPromptCountsAreEquivalent() {
        val html = LegacyTestSupport.loadSample("login/login_page.html")
        val legacyDoc = Jsoup.parse(html)
        val newDoc = Jsoup.parse(html)

        // 旧解析器基线
        val legacyLoggedIn = LoginHelper.checkLoggedin(legacyDoc)
        val legacyFormhash = HiParser.parseFormhash(legacyDoc)
        val legacyError = HiParser.parseErrorMessage(legacyDoc)
        LegacyTestSupport.suppressBackgroundNotifyFetch()
        NotiHelper.fetchNotification(legacyDoc)
        val legacyNotifyTotal = NotiHelper.getCurrentNotification().threadCount

        assertTrue(legacyLoggedIn)
        assertNull(legacyFormhash)
        assertNull(legacyError)
        assertEquals(0, legacyNotifyTotal)

        // 新解析器对照
        val beforeHtml = newDoc.outerHtml()
        val meta = DiscuzCommonParser.parsePageMeta(newDoc)
        val loginResult = LoginParser.parseLoginPage(newDoc)
        assertEquals(beforeHtml, newDoc.outerHtml())

        assertEquals(legacyLoggedIn, meta.session.isLoggedIn)
        assertEquals("10000", meta.session.uid)
        assertEquals("HiUser", meta.session.username)
        assertEquals(legacyFormhash, DiscuzCommonParser.parseFormhash(newDoc))
        assertEquals("00000000", DiscuzCommonParser.parseLogoutFormhash(newDoc))
        assertEquals("00000000", meta.formhash)
        assertEquals(legacyError, meta.errorMessage)
        assertEquals(legacyNotifyTotal, meta.promptCounts.notifyTotal)
        assertEquals(PromptCounts(0, 0, 0, 0, 0), meta.promptCounts)

        assertTrue(loginResult is LoginFormParseResult.AlreadyLoggedIn)
        val already = loginResult as LoginFormParseResult.AlreadyLoggedIn
        assertEquals(meta.session, already.session)
        assertEquals("00000000", already.logoutFormhash)
    }

    @Test
    fun syntheticLoginFormErrorAndSubmitXml_legacyAndNewAreEquivalent() {
        // 1. 未登录表单页
        val unloggedHtml = """
            <html><body>
              <div id="umenu"><a href="logging.php?action=login">登录</a></div>
              <form action="logging.php?action=login"><input type="hidden" name="formhash" value="abcd1234" /></form>
            </body></html>
        """.trimIndent()
        val legacyUnlogged = Jsoup.parse(unloggedHtml)
        val newUnlogged = Jsoup.parse(unloggedHtml)
        assertFalse(LoginHelper.checkLoggedin(legacyUnlogged))
        assertEquals("abcd1234", HiParser.parseFormhash(legacyUnlogged))
        assertEquals(LoginHelper.checkLoggedin(legacyUnlogged), DiscuzCommonParser.parseSession(newUnlogged).isLoggedIn)
        assertEquals(HiParser.parseFormhash(legacyUnlogged), DiscuzCommonParser.parseFormhash(newUnlogged))
        assertEquals(LoginFormParseResult.Ready("abcd1234"), LoginParser.parseLoginPage(newUnlogged))

        // 2. div.alert_error（旧方法会删除 <a>，新方法不修改 DOM 且文本逐字符相等）
        val errorHtml = """
            <html><body>
              <div class="alert_error">
                <p>对不起，您没有权限访问该版块 <a href="javascript:history.back()">[ 返回 ]</a></p>
              </div>
            </body></html>
        """.trimIndent()
        val legacyErrDoc = Jsoup.parse(errorHtml)
        val newErrDoc = Jsoup.parse(errorHtml)
        val newErrBefore = newErrDoc.outerHtml()
        val legacyErrMsg = HiParser.parseErrorMessage(legacyErrDoc)
        val newErrMsg = DiscuzCommonParser.parseErrorMessage(newErrDoc)
        assertEquals("对不起，您没有权限访问该版块", legacyErrMsg)
        assertEquals(legacyErrMsg, newErrMsg)
        assertEquals(0, legacyErrDoc.select("div.alert_error a").size)
        assertEquals(newErrBefore, newErrDoc.outerHtml())

        // 3. div.promptcontent 非零 5 项计数与旧 NotiHelper 等价
        val promptHtml = """
            <html><body>
              <div class="promptcontent">
                <ul>
                  <li><a id="prompt_pm">私人消息 (2)</a></li>
                  <li><a id="prompt_announcepm">公共消息 (1)</a></li>
                  <li><a id="prompt_systempm">系统消息 (3)</a></li>
                  <li><a id="prompt_friend">好友消息 (4)</a></li>
                  <li><a id="prompt_threads">帖子消息 (5)</a></li>
                </ul>
              </div>
            </body></html>
        """.trimIndent()
        val promptDoc = Jsoup.parse(promptHtml)
        LegacyTestSupport.suppressBackgroundNotifyFetch()
        NotiHelper.fetchNotification(promptDoc)
        val legacyPromptSum = NotiHelper.getCurrentNotification().threadCount
        val newCounts = DiscuzCommonParser.parsePromptCounts(promptDoc)
        assertEquals(12, legacyPromptSum)
        assertEquals(legacyPromptSum, newCounts.notifyTotal)
        assertEquals(PromptCounts(pm = 2, announcePm = 1, systemPm = 3, friend = 4, thread = 5), newCounts)

        // 4. 登录提交 XML（对照 LoginHelper.java:121-136 与 R.string.login_success/login_fail）
        val successXml = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[欢迎您回来，HiUser。现在将转入登录前页面。<script>go()</script>]]></root>"""
        val failAttemptsXml = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[登录失败，您还可以尝试 4 次<script>x()</script>]]></root>"""
        val failNoAttemptsXml = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[登录失败，请检查密码]]></root>"""
        val unknownXml = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[系统繁忙]]></root>"""

        val (legacySuccessStatus, _) = simulateLegacyLoginSubmitParse(successXml)
        assertEquals(Constants.STATUS_SUCCESS, legacySuccessStatus)
        val newSuccess = LoginParser.parseLoginSubmitResponse(successXml)
        assertTrue(newSuccess is LoginSubmitResult.Success)
        assertTrue((newSuccess as LoginSubmitResult.Success).message.contains(context.getString(R.string.login_success)))

        val (legacyFail1Status, legacyFail1Msg) = simulateLegacyLoginSubmitParse(failAttemptsXml)
        assertEquals(Constants.STATUS_FAIL_ABORT, legacyFail1Status)
        assertEquals(
            LoginSubmitResult.Failed(message = legacyFail1Msg, remainingAttempts = 4),
            LoginParser.parseLoginSubmitResponse(failAttemptsXml),
        )

        val (legacyFail2Status, legacyFail2Msg) = simulateLegacyLoginSubmitParse(failNoAttemptsXml)
        assertEquals(Constants.STATUS_FAIL_ABORT, legacyFail2Status)
        assertEquals(
            LoginSubmitResult.Failed(message = legacyFail2Msg, remainingAttempts = null),
            LoginParser.parseLoginSubmitResponse(failNoAttemptsXml),
        )

        val (legacyUnknownStatus, _) = simulateLegacyLoginSubmitParse(unknownXml)
        assertEquals(Constants.STATUS_FAIL_ABORT, legacyUnknownStatus)
        assertTrue(LoginParser.parseLoginSubmitResponse(unknownXml) is LoginSubmitResult.UnknownError)
    }

    @Test
    fun smsListSampleAndSyntheticFixtures_legacyAndNewAreFieldByFieldEquivalent() {
        val html = LegacyTestSupport.loadSample("sms/sms_list.html")
        val legacyDoc = Jsoup.parse(html)
        val newDoc = Jsoup.parse(html)

        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacyList = HiParser.parseSimpleList(context, SimpleListJob.TYPE_SMS, legacyDoc, false)
        assertNotNull(legacyList)
        val legacyDirect = HiParser.parseSMS(legacyDoc)
        assertNotNull(legacyDirect)
        assertEquals(legacyDirect!!.count, legacyList!!.count)

        val newPage = SmsParser.parseSmsList(newDoc)
        assertTrue(newPage.hasContainer)
        assertEquals(legacyList.count, newPage.items.size)
        assertEquals(50, newPage.items.size)

        for (i in 0 until legacyList.count) {
            val oldItem = legacyList.all[i]
            val newItem = newPage.items[i]
            assertEquals("uid mismatch at $i", oldItem.uid, newItem.uid)
            assertEquals("author mismatch at $i", oldItem.author, newItem.author)
            assertEquals("avatarUrl mismatch at $i", oldItem.avatarUrl.ifEmpty { null }, newItem.avatarUrl)
            assertEquals("time mismatch at $i", oldItem.time, newItem.time)
            assertEquals("summary/title mismatch at $i", oldItem.title, newItem.summary)
            assertEquals("isNew mismatch at $i", oldItem.isNew, newItem.isNew)
        }

        // 合成未读图标 + 空/缺失容器等价
        val syntheticSmsHtml = """
            <html><body>
              <ul class="pm_list">
                <li class="s_clear">
                  <a class="avatar" href="space.php?uid=50001"><img src="https://img02.4d4y.com/forum/uc_server/data/avatar/000/05/00/01_avatar_small.jpg" /></a>
                  <p class="cite"><cite><a href="space.php?uid=50001">UnreadPeer</a></cite> 2026-11-25 18:00 <img src="images/default/notice_newpm.gif" /></p>
                  <div class="summary">新消息摘要</div>
                </li>
              </ul>
            </body></html>
        """.trimIndent()
        val oldUnread = HiParser.parseSMS(Jsoup.parse(syntheticSmsHtml))!!
        val newUnread = SmsParser.parseSmsList(syntheticSmsHtml)
        assertEquals(1, oldUnread.count)
        assertEquals(1, newUnread.items.size)
        assertEquals(oldUnread.all[0].uid, newUnread.items[0].uid)
        assertEquals(oldUnread.all[0].author, newUnread.items[0].author)
        assertEquals(oldUnread.all[0].avatarUrl, newUnread.items[0].avatarUrl)
        assertEquals(oldUnread.all[0].time, newUnread.items[0].time)
        assertEquals(oldUnread.all[0].title, newUnread.items[0].summary)
        assertEquals(oldUnread.all[0].isNew, newUnread.items[0].isNew)
        assertTrue(newUnread.items[0].isNew)

        // 无 ul.pm_list 容器：旧返回 null，新 hasContainer=false
        val noListHtml = """<html><body><p class="nodata">暂无消息</p></body></html>"""
        assertNull(HiParser.parseSMS(Jsoup.parse(noListHtml)))
        val newNoList = SmsParser.parseSmsList(noListHtml)
        assertFalse(newNoList.hasContainer)
        assertTrue(newNoList.items.isEmpty())

        // 已知旧 Bean 缺陷记录：SimpleListItemBean.setAvatarUrl 缺 else 导致 noavatar_small.gif 被改写成 noavatar_middle.gif；
        // 新解析器按规范置空返回 null
        val noAvatarHtml = """
            <html><body><ul class="pm_list"><li>
              <a class="avatar"><img src="https://img02.4d4y.com/forum/uc_server/images/noavatar_small.gif" /></a>
              <p class="cite"><cite><a href="space.php?uid=50002">NoAvatarPeer</a></cite> 2026-11-25 18:01</p>
              <div class="summary">hello</div>
            </li></ul></body></html>
        """.trimIndent()
        val oldNoAvatar = HiParser.parseSMS(Jsoup.parse(noAvatarHtml))!!.all[0]
        assertEquals("https://img02.4d4y.com/forum/uc_server/images/noavatar_middle.gif", oldNoAvatar.avatarUrl)
        assertNull(SmsParser.parseSmsList(noAvatarHtml).items[0].avatarUrl)
    }

    @Test
    fun smsDetailSampleAndSendXml_legacyAndNewAreFieldByFieldEquivalent() {
        val html = LegacyTestSupport.loadSample("sms/sms_detail.html")
        val legacyDoc = Jsoup.parse(html)
        val newDoc = Jsoup.parse(html)

        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacyList = HiParser.parseSimpleList(context, SimpleListJob.TYPE_SMS_DETAIL, legacyDoc, false)
        assertNotNull(legacyList)
        assertEquals(9, legacyList!!.count)
        val legacyFormhash = HiParser.parseFormhash(legacyDoc)
        assertEquals("00000000", legacyFormhash)

        val newDetail = SmsParser.parseSmsDetail(newDoc, avatarBaseUrl = HiUtils.AvatarBaseUrl)
        assertTrue(newDetail.hasContainer)
        assertEquals("10000", newDetail.myUid)
        assertEquals("HiUser", newDetail.myUsername)
        assertEquals(legacyFormhash, newDetail.formhash)
        assertEquals(legacyList.count, newDetail.messages.size)

        for (i in 0 until legacyList.count) {
            val oldItem = legacyList.all[i]
            val newItem = newDetail.messages[i]
            assertEquals("uid mismatch at $i", oldItem.uid, newItem.uid)
            assertEquals("author mismatch at $i", oldItem.author, newItem.author)
            assertEquals("avatarUrl mismatch at $i", oldItem.avatarUrl.ifEmpty { null }, newItem.avatarUrl)
            assertEquals("time mismatch at $i", oldItem.time, newItem.time)
            assertEquals("contentHtml/info mismatch at $i", oldItem.info, newItem.contentHtml)
            assertEquals("isNew mismatch at $i", oldItem.isNew, newItem.isNew)
            assertEquals("isMine mismatch at $i", oldItem.author == "HiUser", newItem.isMine)
        }

        // SMS 发送 XML（对照 PostSmsAsyncTask.java:90-109）
        val sendSuccessXml = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[<li class="s_clear self"><div class="summary">已发送消息</div></li>]]></root>"""
        val sendFailXml = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[抱歉，您的发送频率太快<script>fail()</script>]]></root>"""
        val sendEmptyRootXml = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[<script>err()</script>]]></root>"""

        val (legacyOkStatus, _) = simulateLegacySmsSendParse(sendSuccessXml)
        assertEquals(Constants.STATUS_SUCCESS, legacyOkStatus)
        assertEquals(SmsSendResult.Success, SmsParser.parseSmsSendResult(sendSuccessXml))

        val (legacyFailStatus, legacyFailMsg) = simulateLegacySmsSendParse(sendFailXml)
        assertEquals(Constants.STATUS_FAIL, legacyFailStatus)
        assertEquals(SmsSendResult.Failed(legacyFailMsg), SmsParser.parseSmsSendResult(sendFailXml))

        val (legacyEmptyStatus, legacyEmptyMsg) = simulateLegacySmsSendParse("")
        assertEquals(Constants.STATUS_FAIL, legacyEmptyStatus)
        assertEquals(SmsSendResult.Failed(legacyEmptyMsg), SmsParser.parseSmsSendResult(""))

        val (legacyFallbackStatus, legacyFallbackMsg) = simulateLegacySmsSendParse(sendEmptyRootXml)
        assertEquals(Constants.STATUS_FAIL, legacyFallbackStatus)
        assertEquals(SmsSendResult.Failed(legacyFallbackMsg), SmsParser.parseSmsSendResult(sendEmptyRootXml))
    }

    @Test
    fun notifyEmptySampleAndSyntheticAllFourBranches_legacyAndNewAreFieldByFieldEquivalent() {
        // 1. 真机空态样本
        val emptyHtml = LegacyTestSupport.loadSample("notify/notify_list.html")
        val legacyEmptyDoc = Jsoup.parse(emptyHtml)
        val newEmptyDoc = Jsoup.parse(emptyHtml)

        assertNull(HiParser.parseNotify(legacyEmptyDoc))
        LegacyTestSupport.suppressBackgroundNotifyFetch()
        NotiHelper.fetchNotification(legacyEmptyDoc)
        val legacyEmptyNotiCount = NotiHelper.getCurrentNotification().threadCount

        val newEmpty = NotifyParser.parseNotifyList(newEmptyDoc, avatarBaseUrl = HiUtils.AvatarBaseUrl)
        assertFalse(newEmpty.hasFeedContainer)
        assertTrue(newEmpty.items.isEmpty())
        assertEquals(legacyEmptyNotiCount, newEmpty.meta.promptCounts.notifyTotal)

        // 2. 合成 ul.feed 全 4 支（5 类提醒：THREAD[普通+关注]、QUOTE、REPLY、SYSTEM、FRIEND）
        val legacyFeedDoc = Jsoup.parse(SYNTHETIC_NOTIFY_FEED_HTML)
        val newFeedDoc = Jsoup.parse(SYNTHETIC_NOTIFY_FEED_HTML)
        val newBeforeHtml = newFeedDoc.outerHtml()

        val legacyFeed = HiParser.parseNotify(legacyFeedDoc)
        assertNotNull(legacyFeed)
        val newFeed = NotifyParser.parseNotifyList(newFeedDoc, avatarBaseUrl = HiUtils.AvatarBaseUrl)

        assertEquals("NotifyParser must not mutate Document DOM", newBeforeHtml, newFeedDoc.outerHtml())
        assertTrue(newFeed.hasFeedContainer)
        assertEquals(legacyFeed!!.count, newFeed.items.size)
        assertEquals(6, newFeed.items.size)

        val expectedKinds = listOf(
            NotifyKind.THREAD,
            NotifyKind.THREAD,
            NotifyKind.QUOTE,
            NotifyKind.REPLY,
            NotifyKind.SYSTEM,
            NotifyKind.FRIEND,
        )
        assertEquals(expectedKinds, newFeed.items.map { it.kind })

        for (i in 0 until legacyFeed.count) {
            val oldItem = legacyFeed.all[i]
            val newItem = newFeed.items[i]
            assertEquals("title mismatch at $i", oldItem.title.orEmpty(), newItem.title)
            assertEquals("info mismatch at $i", oldItem.info.orEmpty(), newItem.info)
            assertEquals("author mismatch at $i", oldItem.author, newItem.author)
            assertEquals("tid mismatch at $i", oldItem.tid?.ifEmpty { null }, newItem.tid)
            assertEquals("pid mismatch at $i", oldItem.pid?.ifEmpty { null }, newItem.pid)
            assertEquals("time mismatch at $i", oldItem.time, newItem.time)
            assertEquals("isNew mismatch at $i", oldItem.isNew, newItem.isNew)

            if (newItem.kind == NotifyKind.SYSTEM) {
                // 旧解析器将系统头像写死为 Android 本地缓存路径，新解析器刻意去耦合置 null
                assertEquals(GlideHelper.SYSTEM_AVATAR_FILE.absolutePath, oldItem.avatarUrl)
                assertNull(newItem.avatarUrl)
            } else {
                assertEquals("avatarUrl mismatch at $i", oldItem.avatarUrl.ifEmpty { null }, newItem.avatarUrl)
            }
        }

        // 核对 authorUid（FRIEND 与旧 uid 一致；QUOTE/REPLY 补齐旧代码漏设的 uid；THREAD/SYSTEM 为 null）
        assertNull(newFeed.items[0].authorUid)
        assertNull(newFeed.items[1].authorUid)
        assertEquals("22222", newFeed.items[2].authorUid)
        assertEquals("22223", newFeed.items[3].authorUid)
        assertNull(newFeed.items[4].authorUid)
        assertEquals(legacyFeed.all[5].uid, newFeed.items[5].authorUid)
        assertEquals("33333", newFeed.items[5].authorUid)

        // 核对旧 f_buddy 确实变异了原 DOM，而新解析器保持完整
        assertEquals(1, legacyFeedDoc.select("div.f_buddy a").size)
        assertEquals(2, newFeedDoc.select("div.f_buddy a").size)
    }

    // 复现旧 LoginHelper.java:121-136（原 doLogin 为 private 且依赖网络调用）
    private fun simulateLegacyLoginSubmitParse(rspStr: String): Pair<Int, String> {
        val loginSuccess = context.getString(R.string.login_success)
        val loginFail = context.getString(R.string.login_fail)
        return if (rspStr.contains(loginSuccess)) {
            Constants.STATUS_SUCCESS to ""
        } else if (rspStr.contains(loginFail)) {
            val msgIndex = rspStr.indexOf(loginFail)
            val msgIndexEnd = rspStr.indexOf("次", msgIndex) + 1
            val msg = if (msgIndexEnd > msgIndex) {
                rspStr.substring(msgIndex, msgIndexEnd)
            } else {
                "登录失败,请检查账户信息"
            }
            Constants.STATUS_FAIL_ABORT to msg
        } else {
            Constants.STATUS_FAIL_ABORT to "登录失败,未知错误"
        }
    }

    // 复现旧 PostSmsAsyncTask.java:90-109（原 doPost 为 private 且依赖网络调用）
    private fun simulateLegacySmsSendParse(response: String): Pair<Int, String> {
        if (response.isEmpty()) {
            return Constants.STATUS_FAIL to "短消息发送失败 :  无返回结果"
        }
        if (!response.contains("class=\"summary\"")) {
            var result = ""
            val doc = Jsoup.parse(response, "", Parser.xmlParser())
            for (e in doc.select("root")) {
                result = e.text()
                if (result.contains("<")) {
                    result = result.substring(0, result.indexOf("<"))
                }
            }
            return if (result.isNotEmpty()) {
                Constants.STATUS_FAIL to result
            } else {
                Constants.STATUS_FAIL to "短消息发送失败."
            }
        }
        return Constants.STATUS_SUCCESS to "短消息发送成功."
    }

    companion object {
        private val SYNTHETIC_NOTIFY_FEED_HTML = """
            <html><body>
              <ul class="feed">
                <li>
                  <div class="f_thread">
                    <a href="space.php?uid=11111">Alice</a> 回复了您的帖子
                    <a href="redirect.php?goto=findpost&amp;pid=9001&amp;ptid=8001">主题回复标题</a>
                    <em>2026-11-20 10:00</em>
                  </div>
                </li>
                <li>
                  <div class="f_thread">
                    <a href="space.php?uid=11112">Amy</a> 回复了您关注的主题
                    <a href="redirect.php?goto=findpost&amp;pid=9009&amp;ptid=8009">关注主题标题</a>
                    <em>2026-11-20 10:05</em>
                  </div>
                </li>
                <li>
                  <div class="f_quote">
                    <a href="space.php?uid=22222">Bob</a> 引用了您在
                    <a href="viewthread.php?tid=8002">引用测试帖</a> 中的回复
                    <a href="redirect.php?goto=findpost&amp;pid=9002&amp;ptid=8002">查看</a>
                    <em>2026-11-20 11:00</em>
                    <dl class="summary"><dd>原帖内容</dd><dd>回复内容</dd></dl>
                  </div>
                </li>
                <li>
                  <div class="f_reply">
                    <img src="images/default/notice_newpm.gif" />
                    <a href="space.php?uid=22223">Carol</a> 回复了您在
                    <a href="viewthread.php?tid=8003">回复测试帖</a> 中的帖子
                    <a href="redirect.php?goto=findpost&amp;pid=9003&amp;ptid=8003">查看</a>
                    <em>2026-11-20 12:00</em>
                    <dl class="summary"><dd>原楼层</dd><dd>跟帖回复</dd></dl>
                  </div>
                </li>
                <li>
                  <div class="f_manage">
                    <img src="images/default/notice_newpm.gif" />
                    您的主题 <a href="viewthread.php?tid=8004&amp;extra=page%3D1">系统通知帖</a> 已被加精 <em>2026-11-20 13:00</em>
                  </div>
                </li>
                <li>
                  <div class="f_buddy">
                    <img src="images/default/notice_newpm.gif" />
                    <a href="space.php?uid=33333">Dave</a> 请求加您为好友
                    <a href="my.php?item=buddylist&amp;newbuddyid=33333">批准申请</a>
                    <em>2026-11-20 14:00</em>
                  </div>
                </li>
              </ul>
            </body></html>
        """.trimIndent()
    }
}
