package net.jejer.hipda.feature.messages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.feedback.HiRollingNumber
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.*
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner

private enum class NewSmsSendButtonMode { Idle, Sending, Cooldown }

/** 新建短消息对话框（输入任意用户名与内容；含空校验、15 秒冷却倒计时、0ms 发送进度圈与失败留窗保输入重试）。 */
@Composable
internal fun NewSmsDialog(
    state: NewSmsComposeUiState, onRecipientChange: (String) -> Unit, onBodyChange: (String) -> Unit,
    onSend: () -> Unit, onDismiss: () -> Unit, onOpenLogin: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bodyFocus = remember { FocusRequester() }
    val buttonMode = when {
        state.isSending -> NewSmsSendButtonMode.Sending
        state.cooldownSeconds > 0 -> NewSmsSendButtonMode.Cooldown
        else -> NewSmsSendButtonMode.Idle
    }
    val sendCd = when (buttonMode) {
        NewSmsSendButtonMode.Sending -> stringResource(R.string.messages_status_sending)
        NewSmsSendButtonMode.Cooldown -> stringResource(R.string.messages_cooldown_cd, state.cooldownSeconds)
        NewSmsSendButtonMode.Idle -> null
    }

    AlertDialog(
        onDismissRequest = { if (!state.isSending) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !state.isSending, dismissOnClickOutside = !state.isSending, usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(min = 280.dp, max = 480.dp),
        shape = HiShapeTokens.Large,
        title = { Text(stringResource(R.string.messages_action_new_sms)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.recipient, onValueChange = onRecipientChange,
                    label = { Text(stringResource(R.string.messages_new_sms_recipient)) },
                    singleLine = true, enabled = !state.isSending, isError = state.recipientErrorRes != null,
                    supportingText = state.recipientErrorRes?.let { errRes -> { Text(stringResource(errRes), color = cs.error) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onNext = { bodyFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.body, onValueChange = onBodyChange,
                    label = { Text(stringResource(R.string.messages_new_sms_content)) },
                    minLines = 3, maxLines = 5, enabled = !state.isSending, isError = state.bodyErrorRes != null,
                    supportingText = state.bodyErrorRes?.let { errRes -> { Text(stringResource(errRes), color = cs.error) } },
                    modifier = Modifier.fillMaxWidth().focusRequester(bodyFocus),
                )
                state.sendError?.let { err ->
                    val errText = when {
                        err.cooldownSeconds != null -> stringResource(R.string.messages_error_cooldown, err.cooldownSeconds)
                        !err.serverMessage.isNullOrBlank() -> err.serverMessage
                        else -> stringResource(err.messageRes)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(errText, style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                        if (err.requiresLogin) TextButton(onOpenLogin, Modifier.defaultMinSize(48.dp, 48.dp)) { Text(stringResource(R.string.common_action_login)) }
                    }
                }
            }
        },
        dismissButton = {
            val cancelInt = remember { MutableInteractionSource() }
            TextButton(onDismiss, Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(cancelInt), enabled = !state.isSending, shape = HiShapeTokens.Small, interactionSource = cancelInt) {
                Text(stringResource(R.string.messages_action_cancel))
            }
        },
        confirmButton = {
            val sendInt = remember { MutableInteractionSource() }
            val sendColors = if (state.isSending) ButtonDefaults.buttonColors(disabledContainerColor = cs.primary.copy(alpha = 0.72f), disabledContentColor = cs.onPrimary) else ButtonDefaults.buttonColors()
            Button(
                onClick = onSend, enabled = buttonMode == NewSmsSendButtonMode.Idle, interactionSource = sendInt, shape = HiShapeTokens.Small, colors = sendColors,
                modifier = Modifier.defaultMinSize(minWidth = 76.dp, minHeight = 48.dp).hiPressScale(sendInt).then(if (sendCd != null) Modifier.semantics { contentDescription = sendCd } else Modifier),
            ) {
                AnimatedContent(
                    targetState = buttonMode, label = "NewSmsSendButton",
                    transitionSpec = { ((hiScaleIn(HiMotion.spatialFast()) + hiFadeIn(HiMotion.effectsFast())) togetherWith (hiScaleOut(HiMotion.exit(HiMotion.spatialFast())) + hiFadeOut(HiMotion.exit(HiMotion.effectsFast())))).using(null) },
                ) { mode ->
                    when (mode) {
                        NewSmsSendButtonMode.Sending -> HiLoadingSpinner(Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onPrimary, trackColor = cs.onPrimary.copy(alpha = 0.28f))
                        NewSmsSendButtonMode.Cooldown -> HiRollingNumber(state.cooldownSeconds.coerceAtLeast(1), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        NewSmsSendButtonMode.Idle -> Text(stringResource(R.string.messages_action_send), maxLines = 1, softWrap = false)
                    }
                }
            }
        },
    )
}
