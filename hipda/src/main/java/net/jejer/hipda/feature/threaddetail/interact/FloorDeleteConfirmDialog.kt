package net.jejer.hipda.feature.threaddetail.interact

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.feature.threaddetail.FloorDeleteDialogState
import net.jejer.hipda.feature.threaddetail.FloorDeleteError
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner

private val UNSAFE_DELETE_ERROR_PATTERNS = Regex("""(?:Exception|Throwable|HTTP\s*[45]\d{2}|\[attachimg\])""", RegexOption.IGNORE_CASE)

/** 删除自己楼层的不可撤销二次确认框（纯色 0 玻璃，`BackHandler` 置顶于 LIFO 栈顶，文案逐字取自 `copy.md`）。 */
@Composable
fun FloorDeleteConfirmDialog(
    state: FloorDeleteDialogState?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == null) return
    key(state.floor.postId) { BackHandler(enabled = true) { if (!state.isDeleting) onDismiss() } }
    val cs = MaterialTheme.colorScheme
    val title = stringResource(R.string.thread_detail_delete_confirm_title)
    val errorMsg = resolveDeleteErrorMessage(LocalContext.current, state.error)

    Box(modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(cs.scrim.copy(alpha = 0.40f)).clickable(remember { MutableInteractionSource() }, null, enabled = !state.isDeleting, onClick = onDismiss))
        Surface(
            modifier = Modifier.align(Alignment.Center).systemBarsPadding().padding(24.dp).widthIn(max = 420.dp).fillMaxWidth().pointerInput(Unit) {}.semantics { paneTitle = title; isTraversalGroup = true },
            shape = HiShapeTokens.Large, color = cs.surfaceContainerHigh, shadowElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.thread_detail_delete_confirm_message), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                if (!errorMsg.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                        Text(errorMsg, style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.weight(1f))
                        if (state.error?.uiError?.action == UiErrorAction.Login) {
                            TextButton(onOpenLogin, Modifier.defaultMinSize(48.dp, 48.dp), shape = HiShapeTokens.Small) { Text(stringResource(R.string.common_action_login)) }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                HiCappedFontScale {
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp, Alignment.End), Alignment.CenterVertically) {
                        val cancelInt = remember { MutableInteractionSource() }
                        TextButton(onDismiss, Modifier.defaultMinSize(64.dp, 48.dp).hiPressScale(cancelInt), enabled = !state.isDeleting, shape = HiShapeTokens.Small, interactionSource = cancelInt) {
                            Text(stringResource(R.string.thread_detail_delete_action_cancel), maxLines = 1, softWrap = false)
                        }
                        val confirmInt = remember { MutableInteractionSource() }
                        Button(
                            onClick = onConfirm, enabled = !state.isDeleting, shape = HiShapeTokens.Small, interactionSource = confirmInt,
                            colors = ButtonDefaults.buttonColors(cs.error, cs.onError, cs.error.copy(alpha = 0.50f), cs.onError.copy(alpha = 0.90f)),
                            modifier = Modifier.defaultMinSize(84.dp, 48.dp).hiPressScale(confirmInt),
                        ) {
                            if (state.isDeleting) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    HiLoadingSpinner(Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.onError, trackColor = cs.onError.copy(alpha = 0.25f))
                                    Text(stringResource(R.string.thread_detail_status_deleting), maxLines = 1, softWrap = false)
                                }
                            } else {
                                Text(stringResource(R.string.thread_detail_menu_delete), maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resolveDeleteErrorMessage(ctx: android.content.Context, error: FloorDeleteError?): String? = when {
    error == null -> null
    error.cooldownMessageSeconds != null -> ctx.getString(R.string.compose_error_cooldown, error.cooldownMessageSeconds)
    !error.customMessage.isNullOrBlank() && !UNSAFE_DELETE_ERROR_PATTERNS.containsMatchIn(error.customMessage) -> error.customMessage
    error.uiError != null -> ctx.getString(error.uiError.messageRes)
    else -> null
}
