package net.jejer.hipda.feature.messages

import android.content.res.Resources
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

private data class MessagesAuthKey(val uid: String?, val lastLoginAt: Long?, val sessionEpoch: Long, val isSignedIn: Boolean, val isSessionExpired: Boolean)

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

/** 将 `D7` [MessageResult] 失败分支映射为符合 `copy.md` §8 的 `(UiError, ChallengeFailureKind?)`。 */
internal fun mapMessageResultFailure(result: MessageResult<*>): Pair<UiError, ChallengeFailureKind?>? = when (result) {
    is MessageResult.Success -> null
    is MessageResult.AuthRequired -> UiErrorMapper.mapAuthRequired(result.reason ?: AuthRequiredReason.NOT_LOGGED_IN) to null
    is MessageResult.ChallengeRequired -> UiError(if (result.rejected) R.string.challenge_status_rejected else R.string.challenge_status_incomplete, result.cooldownUntilMs?.let { "cooldownUntilMs=$it" }, UiErrorAction.Reverify) to
        if (result.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete
    MessageResult.ChallengeAbandoned -> UiError(R.string.challenge_status_incomplete, action = UiErrorAction.Reverify) to ChallengeFailureKind.Incomplete
    is MessageResult.Error -> (result.cause?.let(UiErrorMapper::map) ?: UiError(R.string.common_error_load_failed, result.message.trim().ifEmpty { null }, UiErrorAction.Retry)) to null
}

internal fun mapClearFailure(outcome: SmsClearOutcome): UiError? = when (outcome) {
    SmsClearOutcome.Cleared -> null
    is SmsClearOutcome.AuthRequired -> UiErrorMapper.mapAuthRequired(outcome.reason ?: AuthRequiredReason.RELOGIN_FAILED)
    is SmsClearOutcome.ChallengeRequired -> UiError(if (outcome.rejected) R.string.challenge_status_rejected else R.string.challenge_status_incomplete, outcome.cooldownUntilMs?.let { "cooldownUntilMs=$it" }, UiErrorAction.Reverify)
    is SmsClearOutcome.Rejected -> UiError(R.string.common_error_load_failed, outcome.message.trim().ifEmpty { null }, UiErrorAction.Retry)
    is SmsClearOutcome.Error -> outcome.cause?.let(UiErrorMapper::map) ?: UiError(R.string.common_error_load_failed, outcome.message.trim().ifEmpty { null }, UiErrorAction.Retry)
}

internal suspend fun PaneSnackbarController.showMessageUiError(
    error: UiError, resources: Resources, target: PaneSnackbarTarget, onRetry: (AccessPriority) -> Unit, onLogin: () -> Unit,
) {
    val onAction = error.action?.let { act ->
        { when (act) { UiErrorAction.Retry -> onRetry(AccessPriority.UserBlocking); UiErrorAction.Login -> onLogin(); UiErrorAction.Reverify -> onRetry(AccessPriority.UserExplicit) } }
    }
    show(resources.getString(error.messageRes), target, error.action?.let { resources.getString(it.labelRes) }, onAction)
}
