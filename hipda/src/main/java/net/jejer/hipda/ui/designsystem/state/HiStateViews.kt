package net.jejer.hipda.ui.designsystem.state

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiExpandVertically
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut
import net.jejer.hipda.ui.designsystem.motion.hiShrinkVertically

/** 空状态与错误状态的可选操作按钮定义。 */
@Immutable
data class HiStateAction(val label: String, val onClick: () -> Unit)

/** 通用空状态视图：居中说明文字 + 可选操作按钮，不添加次级说明文字（product-design.md §2 原则 8）。 */
@Composable
fun HiEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    action: HiStateAction? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        if (action != null) StateActionButton(action)
    }
}

/** 通用错误状态视图：主提示仅显示原因短语，绝不包含 [details]；[details] 非空时可打断展开详情与复制。 */
@Composable
fun HiErrorState(
    message: String,
    modifier: Modifier = Modifier,
    action: HiStateAction? = null,
    details: String? = null,
) {
    val hasDetails = !details.isNullOrBlank()
    var detailsExpanded by rememberSaveable(hasDetails) { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        if (action != null) StateActionButton(action)
        if (hasDetails) {
            Spacer(Modifier.height(if (action != null) 4.dp else 12.dp))
            TextButton(
                onClick = { detailsExpanded = !detailsExpanded },
                shape = HiShapeTokens.Small,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            ) {
                Text(stringResource(if (detailsExpanded) R.string.state_error_hide_details else R.string.state_error_view_details))
            }
            AnimatedVisibility(
                visible = detailsExpanded,
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                enter = hiExpandVertically(expandFrom = Alignment.Top) + hiFadeIn(),
                exit = hiShrinkVertically(shrinkTowards = Alignment.Top) + hiFadeOut(),
            ) {
                val copyLabel = stringResource(R.string.state_error_copy_details)
                Surface(
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                    shape = HiShapeTokens.Medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 8.dp)) {
                        Text(
                            text = details.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                runCatching {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText(copyLabel, details.orEmpty()))
                                }
                            },
                            shape = HiShapeTokens.Small,
                            modifier = Modifier.align(Alignment.End).defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        ) { Text(copyLabel) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StateActionButton(action: HiStateAction) {
    Spacer(Modifier.height(16.dp))
    FilledTonalButton(
        onClick = action.onClick,
        shape = HiShapeTokens.Small,
        modifier = Modifier.defaultMinSize(minWidth = 88.dp, minHeight = 48.dp),
    ) { Text(action.label) }
}

/** 列表尾部连续加载状态。 */
@Immutable
sealed interface HiFooterState {
    data object Loading : HiFooterState

    @Immutable
    data class Failed(val onRetry: () -> Unit) : HiFooterState

    data object End : HiFooterState
}

/** 列表尾部状态行（触摸目标高度 ≥ 48dp）：`Loading`、`Failed(onRetry)` 整行可点、`End`。 */
@Composable
fun HiListFooter(state: HiFooterState, modifier: Modifier = Modifier) {
    val clickableModifier = if (state is HiFooterState.Failed) {
        Modifier.clickable(role = Role.Button, onClick = state.onRetry)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(clickableModifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            HiFooterState.Loading -> {
                HiLoadingSpinner(Modifier.size(16.dp))
                Text(
                    text = stringResource(R.string.state_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is HiFooterState.Failed -> Text(
                text = stringResource(R.string.state_footer_load_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            HiFooterState.End -> Text(
                text = stringResource(R.string.state_footer_no_more),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HiLoadingSpinner(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val angleState: State<Float> = if (HiTheme.isReducedMotion) {
        remember { mutableFloatStateOf(-90f) }
    } else {
        rememberInfiniteTransition(label = "hiSpinner").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = HiMotion.spinnerRepeatable(),
            label = "hiSpinnerAngle",
        )
    }
    Canvas(modifier) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        drawArc(color = trackColor, startAngle = 0f, sweepAngle = 360f, useCenter = false, style = stroke)
        drawArc(color = color, startAngle = angleState.value, sweepAngle = 270f, useCenter = false, style = stroke)
    }
}
