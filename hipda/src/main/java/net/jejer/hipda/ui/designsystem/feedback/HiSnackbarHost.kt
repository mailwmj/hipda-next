package net.jejer.hipda.ui.designsystem.feedback

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

/**
 * 悬浮在内容之上的 Snackbar 宿主（对齐 `product-design.md` §6.3）。
 *
 * - 复用 [SnackbarHostState] 的排队与展示时长逻辑，不使用 M3 `SnackbarHost` 自带的非令牌动画；
 * - 叠在内容上方且不推挤同层布局，底边距离容器底部固定为 [bottomClearance]（由调用方传入“胶囊或工具条高度 + 8dp”）；
 * - 入场从下方升起（[HiMotion.spatialDefault] + [HiMotion.effectsFast]），退场使用 [HiMotion.exit] 换算令牌。
 */
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
            val timeoutMs = currentSnackbarData.visuals.duration.toTimeoutMillis(
                hasAction = currentSnackbarData.visuals.actionLabel != null,
                accessibilityManager = accessibilityManager,
            )
            delay(timeoutMs)
            currentSnackbarData.dismiss()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedContent(
            targetState = currentSnackbarData,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = SNACKBAR_HORIZONTAL_MARGIN,
                    end = SNACKBAR_HORIZONTAL_MARGIN,
                    bottom = bottomClearance,
                ),
            transitionSpec = {
                val enter = hiSlideInVertically(
                    animationSpec = HiMotion.spatialDefault(),
                    initialOffsetY = { fullHeight -> fullHeight },
                ) + hiFadeIn(animationSpec = HiMotion.effectsFast())
                val exit = hiSlideOutVertically(
                    animationSpec = HiMotion.exit(HiMotion.spatialDefault()),
                    targetOffsetY = { fullHeight -> fullHeight },
                ) + hiFadeOut(animationSpec = HiMotion.exit(HiMotion.effectsFast()))
                (enter togetherWith exit).using(null)
            },
            contentAlignment = Alignment.BottomCenter,
            label = "HiSnackbarHost",
        ) { data ->
            if (data != null) {
                Box(
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        dismiss {
                            data.dismiss()
                            true
                        }
                    },
                ) {
                    snackbar(data)
                }
            }
        }
    }
}

/**
 * 复用 M3 `Snackbar` 外观与淡悬浮阴影，去掉 M3 `Snackbar(SnackbarData)` 内置的 12dp 外边距，
 * 确保底边与 [HiSnackbarHost] 的 `bottomClearance` 严格对齐。
 */
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
    val actionLabel = snackbarData.visuals.actionLabel
    val actionComposable: (@Composable () -> Unit)? = if (actionLabel != null) {
        {
            TextButton(
                colors = ButtonDefaults.textButtonColors(contentColor = actionColor),
                onClick = { snackbarData.performAction() },
            ) {
                Text(text = actionLabel)
            }
        }
    } else {
        null
    }
    val dismissActionComposable: (@Composable () -> Unit)? = if (snackbarData.visuals.withDismissAction) {
        {
            IconButton(onClick = { snackbarData.dismiss() }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.feedback_action_close),
                )
            }
        }
    } else {
        null
    }

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
    ) {
        Text(text = snackbarData.visuals.message)
    }
}

/**
 * 便捷弹出带“撤销”动作的 Snackbar（默认动作文案取自 `strings_feedback.xml` 的 `撤销`）。
 */
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
    accessibilityManager: AccessibilityManager?,
): Long {
    val original = when (this) {
        SnackbarDuration.Indefinite -> Long.MAX_VALUE
        SnackbarDuration.Long -> SNACKBAR_LONG_TIMEOUT_MS
        SnackbarDuration.Short -> SNACKBAR_SHORT_TIMEOUT_MS
    }
    return accessibilityManager?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = original,
        containsIcons = true,
        containsText = true,
        containsControls = hasAction,
    ) ?: original
}
