package net.jejer.hipda.feature.common

import java.io.IOException
import net.jejer.hipda.R
import net.jejer.hipda.data.auth.*
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.data.threaddetail.ThreadDetailError
import net.jejer.hipda.data.threadlist.ThreadListError

/** 将数据层错误映射为符合 `copy.md` / §8 的 [UiError]，主提示仅含原因短语，排错细节仅放 `details`。 */
object UiErrorMapper {

    fun map(error: ThreadListError): UiError = when (error) {
        is ThreadListError.AuthRequired -> mapAuthRequired(error.reason, buildDetails(error.reason.name, error.message, error.reloginResult?.toString()))
        is ThreadListError.ChallengeRequired -> mapChallenge(error.rejected, error.abandoned, error.cooldownUntilMs)
        is ThreadListError.NetworkError -> mapNetwork(error.statusCode, error.message, error.cause)
        is ThreadListError.SiteError -> if (PERMISSION_KEYWORDS.any { it in error.message }) {
            UiError(R.string.common_error_no_forum_permission, error.message.trim().ifEmpty { null })
        } else {
            UiError(R.string.common_error_load_failed, error.message.trim().ifEmpty { null }, UiErrorAction.Retry)
        }
        is ThreadListError.InvalidInput -> UiError(R.string.common_error_load_failed, error.message.trim().ifEmpty { null }, UiErrorAction.Retry)
    }

    fun map(error: ThreadDetailError): UiError = when (error) {
        is ThreadDetailError.AuthRequired -> mapAuthRequired(error.reason, buildDetails(error.reason.name, error.message, error.reloginResult?.toString()))
        is ThreadDetailError.ChallengeRequired -> mapChallenge(error.rejected, error.abandoned, error.cooldownUntilMs)
        is ThreadDetailError.NetworkError -> mapNetwork(error.statusCode, error.message, error.cause)
        is ThreadDetailError.SiteError -> when {
            NOT_FOUND_KEYWORDS.any { it in error.message } -> UiError(R.string.common_error_thread_not_found, error.message.trim().ifEmpty { null })
            PERMISSION_KEYWORDS.any { it in error.message } -> UiError(R.string.common_error_no_forum_permission, error.message.trim().ifEmpty { null })
            else -> UiError(R.string.common_error_load_failed, error.message.trim().ifEmpty { null }, UiErrorAction.Retry)
        }
        is ThreadDetailError.InvalidPage, is ThreadDetailError.InvalidInput -> {
            val msg = if (error is ThreadDetailError.InvalidPage) error.message else (error as ThreadDetailError.InvalidInput).message
            UiError(R.string.common_error_load_failed, msg.trim().ifEmpty { null }, UiErrorAction.Retry)
        }
    }

    fun mapLoginResult(result: LoginResult): UiError? = when (result) {
        is LoginResult.Success -> null
        is LoginResult.InvalidCredentials -> UiError(
            messageRes = if (SECURITY_ANSWER_KEYWORDS.any { it in result.message }) R.string.common_error_login_security_answer else R.string.common_error_login_credentials,
            details = buildDetails(result.message, result.remainingAttempts?.let { "remainingAttempts=$it" }),
        )
        is LoginResult.ChallengeRequired -> mapChallenge(result.rejected, abandoned = false, cooldownUntilMs = result.cooldownUntilMs)
        is LoginResult.NetworkError -> UiError(R.string.common_error_network, buildDetails(result.message, result.cause?.let(::formatThrowable)), UiErrorAction.Retry)
        is LoginResult.Failure -> UiError(R.string.common_error_load_failed, result.message.trim().ifEmpty { null }, UiErrorAction.Retry)
    }

    fun mapSilentReloginFailure(failed: SilentReloginResult.Failed): UiError {
        val details = buildDetails(failed.reason.name, failed.message, failed.cause?.let(::formatThrowable))
        return when (failed.reason) {
            SilentReloginFailureReason.CHALLENGE_REJECTED -> UiError(R.string.challenge_status_rejected, details, UiErrorAction.Reverify)
            SilentReloginFailureReason.CHALLENGE_INCOMPLETE, SilentReloginFailureReason.CHALLENGE_ABANDONED -> UiError(R.string.challenge_status_incomplete, details, UiErrorAction.Reverify)
            SilentReloginFailureReason.NETWORK_ERROR -> UiError(R.string.common_error_network, details, UiErrorAction.Retry)
            SilentReloginFailureReason.INVALID_CREDENTIALS, SilentReloginFailureReason.SESSION_INVALIDATED -> UiError(R.string.common_error_session_expired, details, UiErrorAction.Login)
            SilentReloginFailureReason.SERVER_ERROR -> UiError(R.string.common_error_load_failed, details, UiErrorAction.Retry)
        }
    }

    fun mapAuthRequired(reason: AuthRequiredReason, details: String? = null): UiError = UiError(
        messageRes = when (reason) {
            AuthRequiredReason.RELOGIN_FAILED, AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED -> R.string.common_error_session_expired
            AuthRequiredReason.NOT_LOGGED_IN, AuthRequiredReason.MANUALLY_LOGGED_OUT -> R.string.common_error_login_required
        },
        details = details?.trim()?.ifEmpty { null },
        action = UiErrorAction.Login,
    )

    fun map(throwable: Throwable): UiError = when (throwable) {
        is AuthRequiredException -> mapAuthRequired(throwable.reason, buildDetails(formatThrowable(throwable), throwable.reloginResult?.toString()))
        is ChallengeRejectedException -> UiError(R.string.challenge_status_rejected, formatThrowable(throwable), UiErrorAction.Reverify)
        is ChallengeIncompleteException, is ChallengeAbandonedException -> UiError(R.string.challenge_status_incomplete, formatThrowable(throwable), UiErrorAction.Reverify)
        is ForumHttpException -> UiError(R.string.common_error_load_failed, buildDetails("HTTP ${throwable.statusCode}", throwable.url, throwable.message), UiErrorAction.Retry)
        is IOException -> UiError(R.string.common_error_network, formatThrowable(throwable), UiErrorAction.Retry)
        else -> UiError(R.string.common_error_load_failed, formatThrowable(throwable), UiErrorAction.Retry)
    }

    private fun mapNetwork(statusCode: Int?, message: String, cause: Throwable?) = UiError(
        messageRes = if (statusCode != null) R.string.common_error_load_failed else R.string.common_error_network,
        details = buildDetails(statusCode?.let { "HTTP $it" }, message, cause?.let(::formatThrowable)),
        action = UiErrorAction.Retry,
    )

    private fun mapChallenge(rejected: Boolean, abandoned: Boolean, cooldownUntilMs: Long?) = UiError(
        messageRes = if (rejected) R.string.challenge_status_rejected else R.string.challenge_status_incomplete,
        details = buildDetails(if (abandoned) "challenge_abandoned" else null, cooldownUntilMs?.let { "cooldownUntilMs=$it" }),
        action = UiErrorAction.Reverify,
    )

    private fun formatThrowable(t: Throwable): String {
        val name = t::class.java.simpleName.ifBlank { t::class.java.name }
        val msg = t.message?.trim().orEmpty()
        return if (msg.isEmpty()) name else "$name: $msg"
    }

    private fun buildDetails(vararg parts: String?): String? =
        parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct().joinToString("\n").ifEmpty { null }

    private val PERMISSION_KEYWORDS = listOf("无权", "权限", "不允许访问", "等级不够")
    private val NOT_FOUND_KEYWORDS = listOf("不存在", "已被删除", "已删除", "找不到")
    private val SECURITY_ANSWER_KEYWORDS = listOf("安全提问", "提问回答", "回答错误")
}
