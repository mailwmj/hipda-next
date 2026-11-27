package net.jejer.hipda.feature.common

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.net.SocketTimeoutException
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRequiredException
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.ForumHttpException
import net.jejer.hipda.data.auth.LoginFailureReason
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.network.access.ChallengeAbandonedException
import net.jejer.hipda.data.network.access.ChallengeIncompleteException
import net.jejer.hipda.data.network.access.ChallengeRejectedException
import net.jejer.hipda.data.search.SearchError
import net.jejer.hipda.data.threaddetail.ThreadDetailError
import net.jejer.hipda.data.threadlist.ThreadListError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class UiErrorMapperTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val exceptionClassPattern = Regex("""\b(?:[A-Z][A-Za-z0-9_]*)?(?:Exception|Throwable)\b""")
    private val httpStatusPattern = Regex("""(?:HTTP\s*[45]\d{2}|错误代码\s*\(\s*\d{3}\s*\)|\b[45]\d{2}\b)""")

    @Test
    fun threadListErrors_mapAllBranchesToCleanCopyPhrasesAndHideTechDetails() {
        val rawNetwork = "java.net.SocketTimeoutException: connect timed out HTTP 502"
        val cases = listOf(
            ThreadListError.AuthRequired(AuthRequiredReason.NOT_LOGGED_IN, message = "raw_not_logged_in") to
                Triple("需要登录才能查看", UiErrorAction.Login, "raw_not_logged_in"),
            ThreadListError.AuthRequired(AuthRequiredReason.MANUALLY_LOGGED_OUT, message = "raw_manual_logout") to
                Triple("需要登录才能查看", UiErrorAction.Login, "raw_manual_logout"),
            ThreadListError.AuthRequired(AuthRequiredReason.RELOGIN_FAILED, message = "raw_relogin_failed") to
                Triple("登录已失效", UiErrorAction.Login, "raw_relogin_failed"),
            ThreadListError.AuthRequired(AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED, message = "raw_unauth") to
                Triple("登录已失效", UiErrorAction.Login, "raw_unauth"),
            ThreadListError.ChallengeRequired(cooldownUntilMs = 1234L, rejected = false) to
                Triple("安全验证未完成", UiErrorAction.Reverify, "1234"),
            ThreadListError.ChallengeRequired(cooldownUntilMs = 5678L, rejected = true) to
                Triple("验证未通过", UiErrorAction.Reverify, "5678"),
            ThreadListError.ChallengeRequired(abandoned = true) to
                Triple("安全验证未完成", UiErrorAction.Reverify, "challenge_abandoned"),
            ThreadListError.NetworkError(message = rawNetwork, statusCode = null, cause = SocketTimeoutException("secret_timeout")) to
                Triple("网络连接失败", UiErrorAction.Retry, "secret_timeout"),
            ThreadListError.NetworkError(message = "HTTP 503 Service Unavailable", statusCode = 503) to
                Triple("加载失败", UiErrorAction.Retry, "HTTP 503"),
            ThreadListError.SiteError("对不起，您无权进行当前操作") to
                Triple("当前账号无权查看这个版块", null, "无权"),
            ThreadListError.SiteError("服务器维护中（Error 500）") to
                Triple("加载失败", UiErrorAction.Retry, "服务器维护中"),
            ThreadListError.InvalidInput("版块 ID 无效: fid=-1") to
                Triple("加载失败", UiErrorAction.Retry, "fid=-1"),
        )

        for ((error, expected) in cases) {
            val (expectedMessage, expectedAction, expectedDetailSnippet) = expected
            val mapped = UiErrorMapper.map(error)
            val resolved = context.getString(mapped.messageRes)
            assertEquals("Error=$error", expectedMessage, resolved)
            assertEquals("Action for $error", expectedAction, mapped.action)
            assertSafePrimaryMessage(resolved, forbiddenRaw = expectedDetailSnippet)
            assertNotNull("Details should preserve diagnostic info for $error", mapped.details)
            assertTrue("Details should include '$expectedDetailSnippet': ${mapped.details}", mapped.details!!.contains(expectedDetailSnippet))
        }
    }

    @Test
    fun threadDetailAndSearchErrors_mapAllBranchesToCleanCopyPhrasesAndHideTechDetails() {
        val detailCases = listOf(
            ThreadDetailError.AuthRequired(AuthRequiredReason.NOT_LOGGED_IN, message = "detail_unauth") to
                Triple("需要登录才能查看", UiErrorAction.Login, "detail_unauth"),
            ThreadDetailError.AuthRequired(AuthRequiredReason.RELOGIN_FAILED, message = "detail_expired") to
                Triple("登录已失效", UiErrorAction.Login, "detail_expired"),
            ThreadDetailError.ChallengeRequired(cooldownUntilMs = 100L, rejected = false) to
                Triple("安全验证未完成", UiErrorAction.Reverify, "100"),
            ThreadDetailError.ChallengeRequired(cooldownUntilMs = 200L, rejected = true) to
                Triple("验证未通过", UiErrorAction.Reverify, "200"),
            ThreadDetailError.NetworkError(message = "IOException: reset by peer", statusCode = null, cause = IOException("reset_token")) to
                Triple("网络连接失败", UiErrorAction.Retry, "reset_token"),
            ThreadDetailError.NetworkError(message = "HTTP 502 Bad Gateway", statusCode = 502) to
                Triple("加载失败", UiErrorAction.Retry, "HTTP 502"),
            ThreadDetailError.SiteError("指定的主题不存在或已被删除或正在被审核") to
                Triple("帖子不存在或已被删除", null, "指定的主题不存在"),
            ThreadDetailError.SiteError("您无权访问该版块") to
                Triple("当前账号无权查看这个版块", null, "无权访问"),
            ThreadDetailError.SiteError("未知站点错误 [attachimg]") to
                Triple("加载失败", UiErrorAction.Retry, "未知站点错误"),
            ThreadDetailError.InvalidPage("missing div#postlist") to
                Triple("加载失败", UiErrorAction.Retry, "div#postlist"),
            ThreadDetailError.InvalidInput("invalid tid=abc") to
                Triple("加载失败", UiErrorAction.Retry, "tid=abc"),
        )
        for ((error, expected) in detailCases) {
            val (expectedMessage, expectedAction, expectedDetailSnippet) = expected
            val mapped = UiErrorMapper.map(error)
            val resolved = context.getString(mapped.messageRes)
            assertEquals("Error=$error", expectedMessage, resolved)
            assertEquals("Action for $error", expectedAction, mapped.action)
            assertSafePrimaryMessage(resolved, forbiddenRaw = expectedDetailSnippet)
            assertTrue("Details missing '$expectedDetailSnippet': ${mapped.details}", mapped.details.orEmpty().contains(expectedDetailSnippet))
        }

        val searchCases = listOf(
            SearchError.AuthRequired(AuthRequiredReason.NOT_LOGGED_IN, message = "search_unauth") to
                Triple("需要登录才能查看", UiErrorAction.Login, "search_unauth"),
            SearchError.ChallengeRequired(cooldownUntilMs = 300L, rejected = true) to
                Triple("验证未通过", UiErrorAction.Reverify, "300"),
            SearchError.NetworkError(message = "IOException: offline", statusCode = null, cause = IOException("search_net")) to
                Triple("网络连接失败", UiErrorAction.Retry, "search_net"),
            SearchError.SiteError("您无权使用搜索") to
                Triple("当前账号无权查看这个版块", null, "无权使用"),
            SearchError.SiteError("搜索冷却中") to
                Triple("加载失败", UiErrorAction.Retry, "搜索冷却中"),
            SearchError.InvalidPage("empty search table") to
                Triple("加载失败", UiErrorAction.Retry, "empty search table"),
            SearchError.InvalidInput("bad page") to
                Triple("加载失败", UiErrorAction.Retry, "bad page"),
        )
        for ((error, expected) in searchCases) {
            val (expectedMessage, expectedAction, expectedDetailSnippet) = expected
            val mapped = UiErrorMapper.map(error)
            val resolved = context.getString(mapped.messageRes)
            assertEquals("SearchError=$error", expectedMessage, resolved)
            assertEquals("Action for $error", expectedAction, mapped.action)
            assertSafePrimaryMessage(resolved, forbiddenRaw = expectedDetailSnippet)
            assertTrue("Details missing '$expectedDetailSnippet': ${mapped.details}", mapped.details.orEmpty().contains(expectedDetailSnippet))
        }
    }

    @Test
    fun authErrorsAndGenericThrowables_neverLeakThrowableMessageClassNameOrStatusCode() {
        val dummyAccount = SavedAccount(uid = "1", username = "u", passwordMd5 = "0".repeat(32), lastLoginAt = 1L)
        assertNull(UiErrorMapper.mapLoginResult(LoginResult.Success(dummyAccount)))

        val loginCases = listOf(
            LoginResult.InvalidCredentials("密码错误，还可以尝试 4 次", remainingAttempts = 4) to
                Pair("用户名或密码错误", null),
            LoginResult.InvalidCredentials("安全提问回答错误", remainingAttempts = 3) to
                Pair("安全提问回答错误", null),
            LoginResult.ChallengeRequired(cooldownUntilMs = 99L, rejected = false) to
                Pair("安全验证未完成", UiErrorAction.Reverify),
            LoginResult.ChallengeRequired(cooldownUntilMs = 99L, rejected = true) to
                Pair("验证未通过", UiErrorAction.Reverify),
            LoginResult.NetworkError("SocketException: broken pipe", IOException("pipe_secret")) to
                Pair("网络连接失败", UiErrorAction.Retry),
            LoginResult.Failure("formhash missing HTTP 500") to
                Pair("加载失败", UiErrorAction.Retry),
        )
        for ((result, expected) in loginCases) {
            val mapped = UiErrorMapper.mapLoginResult(result)!!
            val resolved = context.getString(mapped.messageRes)
            assertEquals(expected.first, resolved)
            assertEquals(expected.second, mapped.action)
            assertSafePrimaryMessage(resolved, forbiddenRaw = "pipe_secret")
        }

        val throwableCases = listOf(
            AuthRequiredException(AuthRequiredReason.NOT_LOGGED_IN, message = "AuthRequiredException_secret") to
                Pair("需要登录才能查看", UiErrorAction.Login),
            AuthRequiredException(AuthRequiredReason.RELOGIN_FAILED, message = "expired_secret") to
                Pair("登录已失效", UiErrorAction.Login),
            ChallengeIncompleteException(cooldownUntilMs = 50L) to
                Pair("安全验证未完成", UiErrorAction.Reverify),
            ChallengeRejectedException(cooldownUntilMs = 60L) to
                Pair("验证未通过", UiErrorAction.Reverify),
            ChallengeAbandonedException("bg_abandoned_secret") to
                Pair("安全验证未完成", UiErrorAction.Reverify),
            ForumHttpException(statusCode = 403, url = "https://www.4d4y.com/forum/", message = "HTTP 403 Forbidden") to
                Pair("加载失败", UiErrorAction.Retry),
            IOException("UnknownHostException: raw_host_secret") to
                Pair("网络连接失败", UiErrorAction.Retry),
            IllegalStateException("NullPointerException: internal_bug_secret") to
                Pair("加载失败", UiErrorAction.Retry),
        )
        for ((throwable, expected) in throwableCases) {
            val mapped = UiErrorMapper.map(throwable)
            val resolved = context.getString(mapped.messageRes)
            assertEquals("Throwable=$throwable", expected.first, resolved)
            assertEquals("Action for $throwable", expected.second, mapped.action)
            assertSafePrimaryMessage(resolved, forbiddenRaw = throwable.message)
            val details = mapped.details
            assertNotNull(details)
            assertTrue(
                "Details should contain class or status info for $throwable: $details",
                details!!.contains(throwable::class.java.simpleName) || details.contains("HTTP "),
            )
        }
    }

    @Test
    fun mapLoginResult_mapsCategorizedLoginFailureReasonsToDistinctCopy() {
        val unrecognized = UiErrorMapper.mapLoginResult(
            LoginResult.Failure("raw unknown", LoginFailureReason.UnrecognizedSubmitResponse),
        )!!
        assertEquals("登录失败，站点返回了无法识别的结果", context.getString(unrecognized.messageRes))
        assertTrue(unrecognized.formatArgs.isEmpty())

        val unconfirmed = UiErrorMapper.mapLoginResult(
            LoginResult.Failure("raw verify", LoginFailureReason.SessionNotConfirmed),
        )!!
        assertEquals("登录未完成，请重试", context.getString(unconfirmed.messageRes))
        assertTrue(unconfirmed.formatArgs.isEmpty())

        val httpStatus = UiErrorMapper.mapLoginResult(
            LoginResult.Failure("raw 502", LoginFailureReason.HttpStatus(502)),
        )!!
        assertEquals(listOf(502), httpStatus.formatArgs)
        assertEquals(
            "登录失败，站点返回错误 502",
            context.getString(httpStatus.messageRes, *httpStatus.formatArgs.toTypedArray()),
        )
        assertTrue(httpStatus.details.orEmpty().contains("HTTP 502"))
    }

    private fun assertSafePrimaryMessage(primary: String, forbiddenRaw: String?) {
        assertFalse("Primary must not contain exception class name: $primary", exceptionClassPattern.containsMatchIn(primary))
        assertFalse("Primary must not contain HTTP status code: $primary", httpStatusPattern.containsMatchIn(primary))
        assertFalse("Primary must not end with period: $primary", primary.endsWith("。") || primary.endsWith("."))
        assertFalse("Primary must not contain exclamation: $primary", primary.contains("!") || primary.contains("！"))
        if (!forbiddenRaw.isNullOrBlank() && forbiddenRaw != primary && forbiddenRaw.length >= 6) {
            assertFalse("Primary leaked raw message '$forbiddenRaw': $primary", primary.contains(forbiddenRaw))
        }
    }
}
