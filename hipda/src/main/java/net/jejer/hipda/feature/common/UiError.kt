package net.jejer.hipda.feature.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import net.jejer.hipda.R
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.ui.designsystem.state.HiStateAction

enum class UiErrorAction(@param:StringRes val labelRes: Int) {
    Retry(R.string.common_action_retry),
    Login(R.string.common_action_login),
    Reverify(R.string.challenge_action_retry),
}

/** 统一界面错误模型：主提示固定取自 `copy.md` 字符串资源，排错细节仅放 [details]。 */
@Immutable
data class UiError(
    @param:StringRes val messageRes: Int,
    val details: String? = null,
    val action: UiErrorAction? = null,
    val formatArgs: List<Any> = emptyList(),
)

@Composable
fun UiError.asString(): String =
    if (formatArgs.isEmpty()) stringResource(messageRes) else stringResource(messageRes, *formatArgs.toTypedArray())

@Composable
fun UiError.toStateAction(onRetry: (AccessPriority) -> Unit, onLogin: () -> Unit): HiStateAction? =
    action?.let { act ->
        HiStateAction(stringResource(act.labelRes)) {
            when (act) {
                UiErrorAction.Retry -> onRetry(AccessPriority.UserBlocking)
                UiErrorAction.Login -> onLogin()
                UiErrorAction.Reverify -> onRetry(AccessPriority.UserExplicit)
            }
        }
    }
