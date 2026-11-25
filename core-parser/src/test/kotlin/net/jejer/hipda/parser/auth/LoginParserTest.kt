package net.jejer.hipda.parser.auth

import net.jejer.hipda.parser.SampleFixtures
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoginParserTest {

    @Test
    fun parseLoginPage_realSampleRecognizesAlreadyLoggedInSessionAndLogoutFormhash() {
        val doc = SampleFixtures.parseSampleDocument(SampleFixtures.LOGIN_PAGE)
        val before = doc.outerHtml()

        val result = LoginParser.parseLoginPage(doc)
        assertEquals(before, doc.outerHtml(), "LoginParser must not mutate Document")
        assertEquals(
            LoginFormParseResult.AlreadyLoggedIn(
                session = DiscuzSessionInfo(isLoggedIn = true, uid = "10000", username = "HiUser"),
                logoutFormhash = "00000000",
            ),
            result,
        )
    }

    @Test
    fun parseLoginPage_unloggedFormReturnsReadyAndErrorStates() {
        val readyHtml = """
            <html><body>
              <div id="umenu"><a href="logging.php?action=login">登录</a></div>
              <form method="post" action="logging.php?action=login&amp;loginsubmit=yes">
                <input type="hidden" name="formhash" value="a1b2c3d4" />
              </form>
            </body></html>
        """.trimIndent()
        assertEquals(LoginFormParseResult.Ready("a1b2c3d4"), LoginParser.parseLoginPage(readyHtml))

        val alertInfoHtml = """
            <html><body>
              <div class="alert_info"><p>密码错误次数过多，请 15 分钟后再试</p></div>
            </body></html>
        """.trimIndent()
        assertEquals(
            LoginFormParseResult.Error("密码错误次数过多，请 15 分钟后再试"),
            LoginParser.parseLoginPage(alertInfoHtml),
        )

        val alertErrorDoc = Jsoup.parse(
            """<html><body><div class="alert_error">站点维护中 <a href="index.php">返回</a></div></body></html>""",
        )
        val before = alertErrorDoc.outerHtml()
        assertEquals(LoginFormParseResult.Error("站点维护中"), LoginParser.parseLoginPage(alertErrorDoc))
        assertEquals(before, alertErrorDoc.outerHtml())

        assertEquals(LoginFormParseResult.Error("无法获取登录凭据"), LoginParser.parseLoginPage("<html><body></body></html>"))
    }

    @Test
    fun parseLoginSubmitResponse_coversSuccessFailedWithAttemptsFailedWithoutAttemptsAndUnknown() {
        val successXml = """
            <?xml version="1.0" encoding="gbk"?>
            <root><![CDATA[欢迎您回来，HiUser。现在将转入登录前页面。<script type="text/javascript" reload="1">setTimeout("location.href='index.php'", 1000);</script>]]></root>
        """.trimIndent()
        assertEquals(
            LoginSubmitResult.Success("欢迎您回来，HiUser。现在将转入登录前页面。"),
            LoginParser.parseLoginSubmitResponse(successXml),
        )

        val failWithAttemptsXml = """
            <?xml version="1.0" encoding="gbk"?>
            <root><![CDATA[登录失败，您还可以尝试 4 次<script type="text/javascript" reload="1">hideWindow('login');</script>]]></root>
        """.trimIndent()
        assertEquals(
            LoginSubmitResult.Failed(message = "登录失败，您还可以尝试 4 次", remainingAttempts = 4),
            LoginParser.parseLoginSubmitResponse(failWithAttemptsXml),
        )

        val failWithoutAttemptsXml = """
            <?xml version="1.0" encoding="gbk"?>
            <root><![CDATA[登录失败，密码错误]]></root>
        """.trimIndent()
        assertEquals(
            LoginSubmitResult.Failed(message = "登录失败,请检查账户信息", remainingAttempts = null),
            LoginParser.parseLoginSubmitResponse(failWithoutAttemptsXml),
        )

        val unknownXml = """<?xml version="1.0" encoding="gbk"?><root><![CDATA[站点临时维护]]></root>"""
        val unknownResult = LoginParser.parseLoginSubmitResponse(unknownXml)
        assertTrue(unknownResult is LoginSubmitResult.UnknownError)
        assertTrue((unknownResult as LoginSubmitResult.UnknownError).rawSnippet.contains("站点临时维护"))

        assertEquals(LoginSubmitResult.UnknownError(""), LoginParser.parseLoginSubmitResponse("   "))
    }

    @Test
    fun parseLoginPage_reusesCommonFormhashFallbackForIdOnlyInput() {
        val idOnlyFormhashHtml = """
            <html><body>
              <div id="umenu"><a href="logging.php?action=login">登录</a></div>
              <form method="post" action="logging.php?action=login">
                <input type="hidden" id="formhash" value="f9e8d7c6" />
              </form>
            </body></html>
        """.trimIndent()
        assertEquals(LoginFormParseResult.Ready("f9e8d7c6"), LoginParser.parseLoginPage(idOnlyFormhashHtml))
    }

    @Test
    fun parseLoginSubmitResponse_stripsHeadScriptStyleOnFullPageFallbackAndIgnoresXmlComments() {
        val fullPageSuccessHtml = """
            <html>
              <head>
                <title>4D4Y</title>
                <script type="text/javascript">var err = '登录失败，您还可以尝试 1 次';</script>
                <style>.alert { color: red; }</style>
              </head>
              <body>
                <div class="alert_info"><p>欢迎您回来，HiUser。正在跳转</p></div>
              </body>
            </html>
        """.trimIndent()
        assertEquals(
            LoginSubmitResult.Success("欢迎您回来，HiUser。正在跳转"),
            LoginParser.parseLoginSubmitResponse(fullPageSuccessHtml),
        )

        val fullPageFailHtml = """
            <html>
              <head>
                <script>var ok = '欢迎您回来';</script>
                <style>body { display: block; }</style>
              </head>
              <body>
                <div class="alert_error">登录失败，您还可以尝试 2 次</div>
              </body>
            </html>
        """.trimIndent()
        assertEquals(
            LoginSubmitResult.Failed(message = "登录失败，您还可以尝试 2 次", remainingAttempts = 2),
            LoginParser.parseLoginSubmitResponse(fullPageFailHtml),
        )

        val commentedFailXml = """
            <?xml version="1.0" encoding="gbk"?>
            <!-- 欢迎您回来 -->
            <root><!-- 欢迎您回来 --><![CDATA[登录失败，您还可以尝试 3 次<script>hide()</script>]]></root>
        """.trimIndent()
        assertEquals(
            LoginSubmitResult.Failed(message = "登录失败，您还可以尝试 3 次", remainingAttempts = 3),
            LoginParser.parseLoginSubmitResponse(commentedFailXml),
        )

        val commentedUnknownXml = """
            <?xml version="1.0" encoding="gbk"?>
            <!-- 登录失败，您还可以尝试 1 次 -->
            <root><![CDATA[系统维护中]]></root>
        """.trimIndent()
        assertTrue(LoginParser.parseLoginSubmitResponse(commentedUnknownXml) is LoginSubmitResult.UnknownError)
    }
}
