package net.jejer.hipda.ui.challenge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.jejer.hipda.R
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeIncompleteException
import net.jejer.hipda.data.network.access.ChallengeRejectedException
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut
import net.jejer.hipda.ui.designsystem.state.HiErrorState
import net.jejer.hipda.ui.designsystem.state.HiStateAction

/** 原地验证提示常量（product-design.md §7.12）。 */
@Immutable
object ChallengeStatusDefaults {
    /** 后台自动验证超过此时长后原地切换为“正在检查安全环境…”（业务延迟，非动画）。 */
    const val CheckingDelayMs: Long = 2_000L
}

/** 页面显式持有的验证失败类型，用于单次取消或请求失败时原地提示。 */
enum class ChallengeFailureKind {
    /** 取消或连续失败（`ChallengeIncompleteException`）：文案“安全验证未完成”。 */
    Incomplete,

    /** 过了验证仍被拒绝（`ChallengeRejectedException`）：文案“验证未通过”。 */
    Rejected,
}

/** 将网关抛出的验证异常映射为 [ChallengeFailureKind]；非验证异常返回 `null`。 */
fun Throwable.toChallengeFailureKind(): ChallengeFailureKind? = when (this) {
    is ChallengeRejectedException -> ChallengeFailureKind.Rejected
    is ChallengeIncompleteException -> ChallengeFailureKind.Incomplete
    else -> null
}

/** 对 [ChallengeState.Cooldown] 应用惰性到期归位：当 `nowMs >= untilMs` 时视为 [ChallengeState.Idle]。 */
fun ChallengeState.effectiveAt(nowMs: Long): ChallengeState =
    if (this is ChallengeState.Cooldown && nowMs >= untilMs) ChallengeState.Idle else this

private enum class ChallengeVisualState {
    None,
    Checking,
    Incomplete,
    Rejected,
}

/**
 * 可复用的 Cloudflare 验证原地等待与冷却提示组件（供业务页与 debug 工具页接入）。
 *
 * - `Solving(startedAtMs)`：持续达到 2000ms 后原地显示进度圈 + “正在检查安全环境…”（业务等待，不受减少动态效果影响）；
 * - `Cooldown(untilMs, rejected)`：执行惰性归位（`nowMs >= untilMs` 时立即视为 `Idle`，未到期时到点自动归位）；
 * - 取消/连续失败与拒绝冷却均直接复用 [HiErrorState]；点击“重新验证”时通过 [onRetry] 固定传出 [AccessPriority.UserExplicit]，
 *   调用方应将该优先级传给 `SiteAccessGateway.execute` 以清除冷却并立即重试。
 */
@Composable
fun ChallengeStatusView(
    state: ChallengeState,
    onRetry: (AccessPriority) -> Unit,
    modifier: Modifier = Modifier,
    failure: ChallengeFailureKind? = null,
    errorDetails: String? = null,
    clock: () -> Long = System::currentTimeMillis,
) {
    val currentClock by rememberUpdatedState(clock)
    var checkingDelayElapsed by remember(state) {
        mutableStateOf(
            state is ChallengeState.Solving &&
                currentClock() - state.startedAtMs >= ChallengeStatusDefaults.CheckingDelayMs,
        )
    }
    var cooldownExpired by remember(state) {
        mutableStateOf(state is ChallengeState.Cooldown && currentClock() >= state.untilMs)
    }

    LaunchedEffect(state) {
        when (state) {
            is ChallengeState.Solving -> {
                val elapsed = (currentClock() - state.startedAtMs).coerceAtLeast(0L)
                val remaining = ChallengeStatusDefaults.CheckingDelayMs - elapsed
                if (remaining > 0L) {
                    delay(remaining)
                }
                checkingDelayElapsed = true
            }
            is ChallengeState.Cooldown -> {
                val remaining = state.untilMs - currentClock()
                if (remaining > 0L) {
                    delay(remaining)
                }
                cooldownExpired = true
            }
            ChallengeState.Idle -> Unit
        }
    }

    val nowMs = currentClock()
    val visualState = when (state) {
        is ChallengeState.Solving -> {
            val reachedThreshold = checkingDelayElapsed ||
                (nowMs - state.startedAtMs) >= ChallengeStatusDefaults.CheckingDelayMs
            if (reachedThreshold) ChallengeVisualState.Checking else ChallengeVisualState.None
        }
        is ChallengeState.Cooldown -> {
            if (!cooldownExpired && nowMs < state.untilMs) {
                if (state.rejected) ChallengeVisualState.Rejected else ChallengeVisualState.Incomplete
            } else {
                failure.toVisualState()
            }
        }
        ChallengeState.Idle -> failure.toVisualState()
    }

    AnimatedContent(
        targetState = visualState,
        modifier = modifier,
        transitionSpec = { (hiFadeIn() togetherWith hiFadeOut()).using(null) },
        label = "ChallengeStatusView",
    ) { target ->
        when (target) {
            ChallengeVisualState.None -> Unit
            ChallengeVisualState.Checking -> ChallengeCheckingIndicator()
            ChallengeVisualState.Incomplete -> HiErrorState(
                message = stringResource(R.string.challenge_status_incomplete),
                action = HiStateAction(
                    label = stringResource(R.string.challenge_action_retry),
                    onClick = { onRetry(AccessPriority.UserExplicit) },
                ),
                details = errorDetails,
            )
            ChallengeVisualState.Rejected -> HiErrorState(
                message = stringResource(R.string.challenge_status_rejected),
                action = HiStateAction(
                    label = stringResource(R.string.challenge_action_retry),
                    onClick = { onRetry(AccessPriority.UserExplicit) },
                ),
                details = errorDetails,
            )
        }
    }
}

private fun ChallengeFailureKind?.toVisualState(): ChallengeVisualState = when (this) {
    ChallengeFailureKind.Incomplete -> ChallengeVisualState.Incomplete
    ChallengeFailureKind.Rejected -> ChallengeVisualState.Rejected
    null -> ChallengeVisualState.None
}

@Composable
private fun ChallengeCheckingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChallengeCheckingSpinner(Modifier.size(20.dp))
        Text(
            text = stringResource(R.string.challenge_status_checking),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChallengeCheckingSpinner(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val angleState: State<Float> = if (HiTheme.isReducedMotion) {
        remember { mutableFloatStateOf(-90f) }
    } else {
        rememberInfiniteTransition(label = "challengeCheckingSpinner").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = HiMotion.spinnerRepeatable(),
            label = "challengeCheckingSpinnerAngle",
        )
    }
    Canvas(modifier) {
        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        drawArc(color = trackColor, startAngle = 0f, sweepAngle = 360f, useCenter = false, style = stroke)
        drawArc(color = color, startAngle = angleState.value, sweepAngle = 270f, useCenter = false, style = stroke)
    }
}
