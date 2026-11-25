package net.jejer.hipda.feature.threaddetail.interact

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.delay
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.motion.HiMotion

private const val FLOOR_HIGHLIGHT_PEAK_ALPHA = 0.45f

/**
 * 跳楼落位后的目标楼层高亮修饰符（`product-design.md` §6.1、§6.3）：
 * - 常规态：`HiMotion.effectsDefault()` 淡入 → 停留 `HiMotion.highlightHold`（1000ms）→ `HiMotion.effectsSlow()` 淡出；
 * - 减少动态效果下：0ms 静态高亮，停留 `HiMotion.highlightHold`（1000ms）后 0ms 直接消失；
 * - 仅使用标准 Compose API（`Animatable` + `drawBehind` + 无障碍 `stateDescription`）。
 */
@Composable
fun Modifier.floorHighlight(
    highlightToken: Long?,
    onHighlightFinished: (Long) -> Unit = {},
): Modifier {
    val reducedMotion = HiTheme.isReducedMotion
    val highlightColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = FLOOR_HIGHLIGHT_PEAK_ALPHA)
    val latestOnFinished by rememberUpdatedState(onHighlightFinished)
    val alphaAnim = remember { Animatable(0f) }
    var activeHighlight by remember { mutableStateOf(false) }

    LaunchedEffect(highlightToken) {
        if (highlightToken == null) {
            activeHighlight = false
            alphaAnim.snapTo(0f)
            return@LaunchedEffect
        }
        activeHighlight = true
        if (reducedMotion) {
            alphaAnim.snapTo(1f)
            delay(HiMotion.highlightHold)
            alphaAnim.snapTo(0f)
        } else {
            alphaAnim.animateTo(targetValue = 1f, animationSpec = HiMotion.effectsDefault())
            delay(HiMotion.highlightHold)
            alphaAnim.animateTo(targetValue = 0f, animationSpec = HiMotion.effectsSlow())
        }
        activeHighlight = false
        latestOnFinished(highlightToken)
    }

    val stateDesc = if (activeHighlight) stringResource(R.string.thread_detail_floor_highlighted) else null
    return this
        .then(if (stateDesc != null) Modifier.semantics { stateDescription = stateDesc } else Modifier)
        .drawBehind {
            val currentAlpha = alphaAnim.value
            if (currentAlpha > 0.001f) {
                drawRect(color = highlightColor.copy(alpha = highlightColor.alpha * currentAlpha))
            }
        }
}
