package net.jejer.hipda.feature.messages

import android.content.res.Resources
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.data.auth.*
import net.jejer.hipda.data.message.MessageResult
import net.jejer.hipda.data.message.SmsClearOutcome
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.ui.challenge.ChallengeFailureKind

internal data class MappedMessageFailure(val uiError: UiError, val challengeFailure: ChallengeFailureKind? = null)

private data class MessagesAuthKey(val uid: String?, val lastLoginAt: Long?, val sessionEpoch: Long, val isSignedIn: Boolean, val isSessionExpired: Boolean)

internal inline fun <T> runCatchingExceptCancel(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    Result.failure(t)
}

/** 订阅账号登录、切号、退登与同号重登恢复事件，供列表与对话 ViewModel 共享。 */
internal fun CoroutineScope.observeMessageAuthChanges(
    authRepository: AuthRepository,
    hasLoginError: () -> Boolean,
    onChange: (uidChanged: Boolean, signedIn: Boolean) -> Unit,
) = launch {
    var prevKey: MessagesAuthKey? = null
    combine(authRepository.currentAccount, authRepository.authState) { account, state ->
        MessagesAuthKey(account?.uid, account?.lastLoginAt, authRepository.sessionEpoch, state is AuthState.SignedIn, state is AuthState.SessionExpired)
    }.distinctUntilChanged().collect { key ->
        val prev = prevKey
        prevKey = key
        if (prev == null) return@collect
        val uidChanged = key.uid != prev.uid
        val recovered = !uidChanged && key.uid != null && (
            key.lastLoginAt != prev.lastLoginAt || key.sessionEpoch != prev.sessionEpoch ||
                (key.isSignedIn && prev.isSessionExpired) || (key.isSignedIn && !prev.isSignedIn && hasLoginError())
            )
        if (uidChanged || recovered) onChange(uidChanged, key.uid != null)
    }
}

/** 将 `D7` [MessageResult] 失败分支映射为符合 `copy.md` §8 的 [UiError]（严禁异常类名与 HTTP 状态码进入主提示）。 */
internal fun mapMessageResultFailure(result: MessageResult<*>): MappedMessageFailure? = when (result) {
    is MessageResult.Success -> null
    is MessageResult.AuthRequired -> MappedMessageFailure(UiErrorMapper.mapAuthRequired(result.reason ?: AuthRequiredReason.NOT_LOGGED_IN))
    is MessageResult.ChallengeRequired -> MappedMessageFailure(
        uiError = UiError(if (result.rejected) R.string.challenge_status_rejected else R.string.challenge_status_incomplete, result.cooldownUntilMs?.let { "cooldownUntilMs=$it" }, UiErrorAction.Reverify),
        challengeFailure = if (result.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete,
    )
    MessageResult.ChallengeAbandoned -> MappedMessageFailure(UiError(R.string.challenge_status_incomplete, action = UiErrorAction.Reverify), ChallengeFailureKind.Incomplete)
    is MessageResult.Error -> MappedMessageFailure(mapRawMessageError(result.message, result.cause))
}

internal fun mapClearFailure(outcome: SmsClearOutcome): UiError? = when (outcome) {
    SmsClearOutcome.Cleared -> null
    is SmsClearOutcome.AuthRequired -> UiErrorMapper.mapAuthRequired(outcome.reason ?: AuthRequiredReason.RELOGIN_FAILED)
    is SmsClearOutcome.ChallengeRequired -> UiError(if (outcome.rejected) R.string.challenge_status_rejected else R.string.challenge_status_incomplete, outcome.cooldownUntilMs?.let { "cooldownUntilMs=$it" }, UiErrorAction.Reverify)
    is SmsClearOutcome.Rejected -> UiError(R.string.common_error_load_failed, outcome.message.trim().ifEmpty { null }, UiErrorAction.Retry)
    is SmsClearOutcome.Error -> mapRawMessageError(outcome.message, outcome.cause)
}

internal suspend fun PaneSnackbarController.showMessageUiError(
    error: UiError,
    resources: Resources,
    target: PaneSnackbarTarget,
    onRetry: (AccessPriority) -> Unit,
    onLogin: () -> Unit,
) {
    val onAction = error.action?.let { act ->
        {
            when (act) {
                UiErrorAction.Retry -> onRetry(AccessPriority.UserBlocking)
                UiErrorAction.Login -> onLogin()
                UiErrorAction.Reverify -> onRetry(AccessPriority.UserExplicit)
            }
        }
    }
    show(resources.getString(error.messageRes), target, error.action?.let { resources.getString(it.labelRes) }, onAction)
}

private fun mapRawMessageError(message: String, cause: Throwable?): UiError = when (cause) {
    is ForumHttpException -> UiErrorMapper.map(cause)
    is IOException -> UiError(R.string.common_error_network, buildErrorDetails(message, cause), UiErrorAction.Retry)
    else -> UiError(R.string.common_error_load_failed, buildErrorDetails(message, cause), UiErrorAction.Retry)
}

private fun buildErrorDetails(message: String, cause: Throwable?): String? = listOfNotNull(
    message.trim().ifEmpty { null },
    cause?.let { t ->
        val name = t::class.java.simpleName.ifBlank { t::class.java.name }
        val msg = t.message?.trim().orEmpty()
        if (msg.isEmpty()) name else "$name: $msg"
    },
).distinct().joinToString("\n").ifEmpty { null }
