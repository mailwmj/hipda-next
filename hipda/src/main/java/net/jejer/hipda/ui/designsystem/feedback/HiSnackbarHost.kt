package net.jejer.hipda.ui.designsystem.feedback

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut
import net.jejer.hipda.ui.designsystem.motion.hiSlideInVertically
import net.jejer.hipda.ui.designsystem.motion.hiSlideOutVertically

private const val SNACKBAR_SHORT_TIMEOUT_MS: Long = 4_000L
private const val SNACKBAR_LONG_TIMEOUT_MS: Long = 10_000L
private val SNACKBAR_HORIZONTAL_MARGIN: Dp = 16.dp
private val SNACKBAR_MAX_WIDTH: Dp = 600.dp

/** 悬浮 Snackbar 宿主（§6.3）：底边距底部 [bottomClearance]、不推挤同层、限宽 600dp，复用 [hiSlideInVertically]/[hiSlideOutVertically] 默认令牌。 */
@Composable
fun HiSnackbarHost(
    hostState: SnackbarHostState,
    bottomClearance: Dp,
    modifier: Modifier = Modifier,
    snackbar: @Composable (SnackbarData) -> Unit = { data -> HiSnackbar(snackbarData = data) },
) {
    val currentSnackbarData = hostState.currentSnackbarData
    val accessibilityManager = LocalAccessibilityManager.current

    LaunchedEffect(currentSnackbarData) {
        if (currentSnackbarData != null) {
            delay(
                currentSnackbarData.visuals.duration.toTimeoutMillis(
                    hasAction = currentSnackbarData.visuals.actionLabel != null,
                    hasDismissIcon = currentSnackbarData.visuals.withDismissAction,
                    accessibilityManager = accessibilityManager,
                ),
            )
            currentSnackbarData.dismiss()
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedContent(
            targetState = currentSnackbarData,
            modifier = Modifier
                .widthIn(max = SNACKBAR_MAX_WIDTH)
                .fillMaxWidth()
                .padding(start = SNACKBAR_HORIZONTAL_MARGIN, end = SNACKBAR_HORIZONTAL_MARGIN, bottom = bottomClearance),
            transitionSpec = {
                val enter = hiSlideInVertically(initialOffsetY = { it }) + hiFadeIn(HiMotion.effectsFast())
                val exit = hiSlideOutVertically(targetOffsetY = { it }) + hiFadeOut(HiMotion.exit(HiMotion.effectsFast()))
                (enter togetherWith exit).using(null)
            },
            contentAlignment = Alignment.BottomCenter,
            label = "HiSnackbarHost",
        ) { data ->
            if (data != null) {
                Box(
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        dismiss { data.dismiss(); true }
                    },
                ) {
                    snackbar(data)
                }
            }
        }
    }
}

/** 复用 M3 `Snackbar` 外观与淡悬浮阴影，去掉内置 12dp 外边距以严格对齐 `bottomClearance`。 */
@Composable
fun HiSnackbar(
    snackbarData: SnackbarData,
    modifier: Modifier = Modifier,
    actionOnNewLine: Boolean = false,
    shape: Shape = HiShapeTokens.Medium,
    containerColor: Color = SnackbarDefaults.color,
    contentColor: Color = SnackbarDefaults.contentColor,
    actionColor: Color = SnackbarDefaults.actionColor,
    actionContentColor: Color = SnackbarDefaults.actionContentColor,
    dismissActionContentColor: Color = SnackbarDefaults.dismissActionContentColor,
) {
    val actionComposable: (@Composable () -> Unit)? = snackbarData.visuals.actionLabel?.let { label ->
        {
            TextButton(
                colors = ButtonDefaults.textButtonColors(contentColor = actionColor),
                onClick = { snackbarData.performAction() },
            ) { Text(text = label) }
        }
    }
    val dismissActionComposable: (@Composable () -> Unit)? = if (snackbarData.visuals.withDismissAction) {
        {
            IconButton(onClick = { snackbarData.dismiss() }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.feedback_action_close))
            }
        }
    } else null

    Snackbar(
        modifier = modifier,
        action = actionComposable,
        dismissAction = dismissActionComposable,
        actionOnNewLine = actionOnNewLine,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        actionContentColor = actionContentColor,
        dismissActionContentColor = dismissActionContentColor,
    ) { Text(text = snackbarData.visuals.message) }
}

/** 便捷弹出带“撤销”动作的 Snackbar（文案取自 `strings_feedback.xml`）。 */
suspend fun SnackbarHostState.showUndoSnackbar(
    context: Context,
    message: String,
    duration: SnackbarDuration = SnackbarDuration.Short,
): SnackbarResult = showSnackbar(
    message = message,
    actionLabel = context.getString(R.string.feedback_action_undo),
    duration = duration,
)

private fun SnackbarDuration.toTimeoutMillis(
    hasAction: Boolean,
    hasDismissIcon: Boolean,
    accessibilityManager: AccessibilityManager?,
): Long {
    val original = when (this) {
        SnackbarDuration.Indefinite -> Long.MAX_VALUE
        SnackbarDuration.Long -> SNACKBAR_LONG_TIMEOUT_MS
        SnackbarDuration.Short -> SNACKBAR_SHORT_TIMEOUT_MS
    }
    return accessibilityManager?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = original,
        containsIcons = hasDismissIcon,
        containsText = true,
        containsControls = hasAction || hasDismissIcon,
    ) ?: original
}
