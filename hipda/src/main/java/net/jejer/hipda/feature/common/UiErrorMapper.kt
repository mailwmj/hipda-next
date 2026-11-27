package net.jejer.hipda.feature.common

import java.io.IOException
import net.jejer.hipda.R
import net.jejer.hipda.data.auth.*
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.data.search.SearchError
import net.jejer.hipda.data.threaddetail.ThreadDetailError
import net.jejer.hipda.data.threadlist.ThreadListError

/** 将数据层错误映射为符合 `copy.md` / §8 的 [UiError]，主提示仅含原因短语，排错细节仅放 `details`。 */
object UiErrorMapper {

    fun map(error: ThreadListError): UiError = when (error) {
        is ThreadListError.AuthRequired -> mapAuthRequired(error.reason, buildDetails(error.reason.name, error.message, error.reloginResult?.toString()))
        is ThreadListError.ChallengeRequired -> mapChallenge(error.rejected, error.abandoned, error.cooldownUntilMs)
        is ThreadListError.NetworkError -> mapNetwork(error.statusCode, error.message, error.cause)
        is ThreadListError.SiteError -> mapSiteError(error.message, checkNotFound = false)
        is ThreadListError.InvalidInput -> mapLoadFailed(error.message)
    }

    fun map(error: ThreadDetailError): UiError = when (error) {
        is ThreadDetailError.AuthRequired -> mapAuthRequired(error.reason, buildDetails(error.reason.name, error.message, error.reloginResult?.toString()))
        is ThreadDetailError.ChallengeRequired -> mapChallenge(error.rejected, error.abandoned, error.cooldownUntilMs)
        is ThreadDetailError.NetworkError -> mapNetwork(error.statusCode, error.message, error.cause)
        is ThreadDetailError.SiteError -> mapSiteError(error.message, checkNotFound = true)
        is ThreadDetailError.InvalidPage -> mapLoadFailed(error.message)
        is ThreadDetailError.InvalidInput -> mapLoadFailed(error.message)
    }

    fun map(error: SearchError): UiError = when (error) {
        is SearchError.AuthRequired -> mapAuthRequired(error.reason, buildDetails(error.reason.name, error.message, error.reloginResult?.toString()))
        is SearchError.ChallengeRequired -> mapChallenge(error.rejected, error.abandoned, error.cooldownUntilMs)
        is SearchError.NetworkError -> mapNetwork(error.statusCode, error.message, error.cause)
        is SearchError.SiteError -> mapSiteError(error.message, checkNotFound = false)
        is SearchError.InvalidPage -> mapLoadFailed(error.message)
        is SearchError.InvalidInput -> mapLoadFailed(error.message)
    }

    fun mapLoginResult(result: LoginResult): UiError? = when (result) {
        is LoginResult.Success -> null
        is LoginResult.InvalidCredentials -> UiError(
            if (SECURITY_ANSWER_KEYWORDS.any { it in result.message }) R.string.common_error_login_security_answer else R.string.common_error_login_credentials,
            buildDetails(result.message, result.remainingAttempts?.let { "remainingAttempts=$it" }),
        )
        is LoginResult.ChallengeRequired -> mapChallenge(result.rejected, false, result.cooldownUntilMs)
        is LoginResult.NetworkError -> UiError(R.string.common_error_network, buildDetails(result.message, result.cause?.let(::formatThrowable)), UiErrorAction.Retry)
        is LoginResult.Failure -> when (val reason = result.reason) {
            LoginFailureReason.UnrecognizedSubmitResponse -> UiError(
                messageRes = R.string.login_error_unrecognized_response,
                details = result.message.trim().ifEmpty { null },
            )
            LoginFailureReason.SessionNotConfirmed -> UiError(
                messageRes = R.string.login_error_session_not_confirmed,
                details = result.message.trim().ifEmpty { null },
            )
            is LoginFailureReason.HttpStatus -> UiError(
                messageRes = R.string.login_error_http_status,
                details = buildDetails("HTTP ${reason.code}", result.message),
                formatArgs = listOf(reason.code),
            )
            LoginFailureReason.Other -> mapLoadFailed(result.message)
        }
    }

    fun mapAuthRequired(reason: AuthRequiredReason, details: String? = null): UiError = UiError(
        when (reason) {
            AuthRequiredReason.RELOGIN_FAILED, AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED -> R.string.common_error_session_expired
            AuthRequiredReason.NOT_LOGGED_IN, AuthRequiredReason.MANUALLY_LOGGED_OUT -> R.string.common_error_login_required
        },
        details?.trim()?.ifEmpty { null },
        UiErrorAction.Login,
    )

    fun map(throwable: Throwable): UiError = when (throwable) {
        is AuthRequiredException -> mapAuthRequired(throwable.reason, buildDetails(formatThrowable(throwable), throwable.reloginResult?.toString()))
        is ChallengeRejectedException -> UiError(R.string.challenge_status_rejected, formatThrowable(throwable), UiErrorAction.Reverify)
        is ChallengeIncompleteException, is ChallengeAbandonedException -> UiError(R.string.challenge_status_incomplete, formatThrowable(throwable), UiErrorAction.Reverify)
        is ForumHttpException -> UiError(R.string.common_error_load_failed, buildDetails("HTTP ${throwable.statusCode}", throwable.url, throwable.message), UiErrorAction.Retry)
        is IOException -> UiError(R.string.common_error_network, formatThrowable(throwable), UiErrorAction.Retry)
        else -> UiError(R.string.common_error_load_failed, formatThrowable(throwable), UiErrorAction.Retry)
    }

    private fun mapSiteError(message: String, checkNotFound: Boolean): UiError = when {
        checkNotFound && NOT_FOUND_KEYWORDS.any { it in message } -> UiError(R.string.common_error_thread_not_found, message.trim().ifEmpty { null })
        PERMISSION_KEYWORDS.any { it in message } -> UiError(R.string.common_error_no_forum_permission, message.trim().ifEmpty { null })
        else -> mapLoadFailed(message)
    }

    private fun mapLoadFailed(message: String) = UiError(R.string.common_error_load_failed, message.trim().ifEmpty { null }, UiErrorAction.Retry)
    private fun mapNetwork(code: Int?, msg: String, cause: Throwable?) = UiError(if (code != null) R.string.common_error_load_failed else R.string.common_error_network, buildDetails(code?.let { "HTTP $it" }, msg, cause?.let(::formatThrowable)), UiErrorAction.Retry)
    private fun mapChallenge(rejected: Boolean, abandoned: Boolean, cooldownMs: Long?) = UiError(if (rejected) R.string.challenge_status_rejected else R.string.challenge_status_incomplete, buildDetails(if (abandoned) "challenge_abandoned" else null, cooldownMs?.let { "cooldownUntilMs=$it" }), UiErrorAction.Reverify)
    private fun formatThrowable(t: Throwable): String = t.message?.trim().orEmpty().let { m -> val n = t::class.java.simpleName.ifBlank { t::class.java.name }; if (m.isEmpty()) n else "$n: $m" }
    private fun buildDetails(vararg parts: String?): String? = parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct().joinToString("\n").ifEmpty { null }

    private val PERMISSION_KEYWORDS = listOf("无权", "权限", "不允许访问", "等级不够")
    private val NOT_FOUND_KEYWORDS = listOf("不存在", "已被删除", "已删除", "找不到")
    private val SECURITY_ANSWER_KEYWORDS = listOf("安全提问", "提问回答", "回答错误")
}
