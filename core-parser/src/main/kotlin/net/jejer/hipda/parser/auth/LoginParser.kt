package net.jejer.hipda.parser.auth

import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.common.DiscuzTextDecoder
import net.jejer.hipda.parser.common.firstMatch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * 登录页与登录 POST XML 响应的纯 JVM 解析器（对照旧 `LoginHelper.java:83-147`）。
 */
object LoginParser {

    private const val LOGIN_SUCCESS_MARKER = "欢迎您回来"
    private const val LOGIN_FAIL_MARKER = "登录失败"
    private const val ATTEMPTS_SUFFIX = "次"
    private const val DEFAULT_FORMHASH_ERROR = "无法获取登录凭据"
    private const val DEFAULT_LOGIN_FAIL_MESSAGE = "登录失败,请检查账户信息"
    private const val MAX_UNKNOWN_SNIPPET_LENGTH = 200

    private val REMAINING_ATTEMPTS_REGEX = Regex("""(\d+)\s*次""")

    fun parseLoginPage(html: String): LoginFormParseResult = parseLoginPage(Jsoup.parse(html))

    fun parseLoginPage(doc: Document): LoginFormParseResult {
        val session = DiscuzCommonParser.parseSession(doc)
        if (session.isLoggedIn) {
            return LoginFormParseResult.AlreadyLoggedIn(
                session = session,
                logoutFormhash = DiscuzCommonParser.parseLogoutFormhash(doc),
            )
        }

        val formhash = DiscuzCommonParser.parseFormhash(doc)
        if (formhash != null) {
            return LoginFormParseResult.Ready(formhash)
        }

        val alertInfo = doc.firstMatch("div.alert_info")?.text()?.trim()?.ifEmpty { null }
        if (alertInfo != null) {
            return LoginFormParseResult.Error(DiscuzTextDecoder.decodeDoubleEscapedEntities(alertInfo))
        }

        val alertError = DiscuzCommonParser.parseErrorMessage(doc)?.trim()?.ifEmpty { null }
        if (alertError != null) {
            return LoginFormParseResult.Error(alertError)
        }

        return LoginFormParseResult.Error(DEFAULT_FORMHASH_ERROR)
    }

    fun parseLoginSubmitResponse(xml: String): LoginSubmitResult {
        if (xml.isBlank()) {
            return LoginSubmitResult.UnknownError("")
        }

        val visibleText = extractVisibleMessageText(xml)

        if (visibleText.contains(LOGIN_SUCCESS_MARKER)) {
            return LoginSubmitResult.Success(DiscuzTextDecoder.decodeDoubleEscapedEntities(visibleText))
        }

        val failIndex = visibleText.indexOf(LOGIN_FAIL_MARKER)
        if (failIndex >= 0) {
            val attemptsSuffixIndex = visibleText.indexOf(ATTEMPTS_SUFFIX, failIndex)
            return if (attemptsSuffixIndex >= failIndex) {
                val extracted = visibleText.substring(failIndex, attemptsSuffixIndex + ATTEMPTS_SUFFIX.length)
                val remaining = REMAINING_ATTEMPTS_REGEX.find(extracted)?.groupValues?.get(1)?.toIntOrNull()
                LoginSubmitResult.Failed(
                    message = DiscuzTextDecoder.decodeDoubleEscapedEntities(extracted),
                    remainingAttempts = remaining,
                )
            } else {
                LoginSubmitResult.Failed(
                    message = DEFAULT_LOGIN_FAIL_MESSAGE,
                    remainingAttempts = null,
                )
            }
        }

        return LoginSubmitResult.UnknownError(xml.trim().take(MAX_UNKNOWN_SNIPPET_LENGTH))
    }

    private fun extractVisibleMessageText(xml: String): String {
        val xmlDoc = Jsoup.parse(xml, "", Parser.xmlParser())
        val rootElement = xmlDoc.select("root").lastOrNull()
        return if (rootElement != null) {
            val fragment = Jsoup.parseBodyFragment(rootElement.text())
            fragment.select("script, style").remove()
            fragment.body().text().trim()
        } else {
            val htmlDoc = Jsoup.parse(xml)
            htmlDoc.select("script, style").remove()
            htmlDoc.body().text().trim().ifEmpty { htmlDoc.text().trim() }
        }
    }
}
