package net.jejer.hipda.feature.threaddetail.interact

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.parser.threaddetail.PollOption
import net.jejer.hipda.parser.threaddetail.ThreadPoll
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.hiListItemPressIndication
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner

private val PERCENT_REGEX = Regex("""(\d+(?:\.\d+)?)\s*%""")

/** 从 `PollOption.ratesText`（如 `"70.00% (7)"`）提取 `[0f, 1f]` 进度比例，解析失败返回 `null`。 */
internal fun parsePollOptionFraction(ratesText: String?): Float? {
    val percent = ratesText?.takeIf { it.isNotBlank() }?.let { PERCENT_REGEX.find(it)?.groupValues?.getOrNull(1)?.toFloatOrNull() }
    return if (percent != null && percent.isFinite()) (percent / 100f).coerceIn(0f, 1f) else null
}

/**
 * 帖子 1 楼投票卡片（`product-design.md` §6.3、§7.1、§9，`copy.md`）：
 * 消费 `:core-parser` 纯文本 `titleText`/`timerText`（零 HTML 解析）；单/多选限额防超选；
 * 0ms `HiLoadingSpinner` 防连点，失败原地提示且保留已勾选项；未登录原地引导登录；柱条按 `HiMotion.spatialDefault()` 增长。
 */
@Composable
fun ThreadPollCard(
    poll: ThreadPoll,
    isLoggedIn: Boolean,
    isSubmitting: Boolean,
    error: UiError?,
    onSubmitVote: (List<String>) -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
    onClearError: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val maxChoices = poll.maxChoices.coerceAtLeast(1)
    val canSubmitVote = !poll.formhash.isNullOrBlank() && poll.options.any { !it.optionId.isNullOrBlank() }
    val selectionKey = remember(poll.formhash, poll.options.size, maxChoices) { "${poll.formhash.orEmpty()}:${poll.options.size}:$maxChoices" }
    var selectedIds by rememberSaveable(selectionKey) { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(canSubmitVote) { if (!canSubmitVote && selectedIds.isNotEmpty()) selectedIds = emptyList() }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = HiShapeTokens.Medium,
        color = cs.surfaceContainerLow,
        border = BorderStroke(0.5.dp, cs.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (poll.titleText.isNotBlank()) Text(poll.titleText, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            poll.timerText?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            if (poll.isMultipleChoice && canSubmitVote) {
                Text(stringResource(R.string.thread_detail_poll_max_choices, maxChoices), style = MaterialTheme.typography.labelMedium, color = cs.primary)
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (option in poll.options) {
                    val optionId = option.optionId?.takeIf { it.isNotBlank() }
                    val selected = optionId != null && optionId in selectedIds
                    val atLimit = poll.isMultipleChoice && selectedIds.size >= maxChoices && !selected
                    val showSelector = canSubmitVote && isLoggedIn && optionId != null
                    PollOptionRow(
                        option = option,
                        isMultipleChoice = poll.isMultipleChoice,
                        showSelector = showSelector,
                        selected = selected,
                        enabled = showSelector && !isSubmitting && !atLimit,
                        onToggle = {
                            if (optionId == null || isSubmitting) return@PollOptionRow
                            onClearError()
                            selectedIds = if (!poll.isMultipleChoice) {
                                listOf(optionId)
                            } else if (optionId in selectedIds) {
                                selectedIds - optionId
                            } else if (selectedIds.size < maxChoices) {
                                selectedIds + optionId
                            } else {
                                selectedIds
                            }
                        },
                    )
                }
            }

            poll.footer?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }

            if (canSubmitVote && !isLoggedIn) {
                PollLoginPromptRow(onOpenLogin)
            } else if (canSubmitVote) {
                if (error != null) {
                    if (error.action == UiErrorAction.Login) {
                        PollLoginPromptRow(onOpenLogin)
                    } else {
                        Surface(Modifier.fillMaxWidth(), shape = HiShapeTokens.Small, color = cs.errorContainer, contentColor = cs.onErrorContainer) {
                            Text(stringResource(error.messageRes), style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                }
                val submitInteraction = remember { MutableInteractionSource() }
                val submittingDesc = stringResource(R.string.thread_detail_poll_submitting)
                Button(
                    onClick = { onSubmitVote(selectedIds) },
                    enabled = selectedIds.isNotEmpty() && !isSubmitting,
                    interactionSource = submitInteraction,
                    shape = HiShapeTokens.Small,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).hiPressScale(submitInteraction),
                ) {
                    if (isSubmitting) {
                        HiLoadingSpinner(
                            modifier = Modifier.size(18.dp).semantics { contentDescription = submittingDesc },
                            color = cs.onSurfaceVariant,
                            trackColor = cs.onSurfaceVariant.copy(alpha = 0.25f),
                        )
                    } else {
                        Text(stringResource(R.string.thread_detail_poll_submit))
                    }
                }
            }
        }
    }
}

@Composable
private fun PollOptionRow(
    option: PollOption,
    isMultipleChoice: Boolean,
    showSelector: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val selectionMod = when {
        !showSelector -> Modifier
        isMultipleChoice -> Modifier.toggleable(selected, interaction, hiListItemPressIndication(), enabled, Role.Checkbox) { onToggle() }
        else -> Modifier.selectable(selected, interaction, hiListItemPressIndication(), enabled, Role.RadioButton, onToggle)
    }
    Column(
        modifier = Modifier.fillMaxWidth().clip(HiShapeTokens.Small).then(selectionMod).heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showSelector) {
                if (isMultipleChoice) Checkbox(selected, onCheckedChange = null, enabled = enabled)
                else RadioButton(selected, onClick = null, enabled = enabled)
            }
            Text(option.text, style = MaterialTheme.typography.bodyMedium, color = if (!showSelector || enabled || selected) cs.onSurface else cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            option.ratesText?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) }
        }
        parsePollOptionFraction(option.ratesText)?.let { fraction ->
            PollResultBar("${option.optionId.orEmpty()}:${option.text}", fraction)
        }
    }
}

@Composable
private fun PollResultBar(optionKey: String, targetFraction: Float) {
    val cs = MaterialTheme.colorScheme
    val reducedMotion = HiTheme.isReducedMotion
    val fractionAnim = remember(optionKey) { Animatable(if (reducedMotion) targetFraction else 0f) }

    LaunchedEffect(targetFraction, reducedMotion) {
        if (reducedMotion) fractionAnim.snapTo(targetFraction)
        else fractionAnim.animateTo(targetValue = targetFraction, animationSpec = HiMotion.spatialDefault())
    }

    Box(Modifier.fillMaxWidth().height(6.dp).clip(HiShapeTokens.Full).background(cs.surfaceContainerHighest)) {
        val fill = fractionAnim.value.coerceIn(0f, 1f)
        if (fill > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(cs.primary))
    }
}

@Composable
private fun PollLoginPromptRow(onOpenLogin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val loginInteraction = remember { MutableInteractionSource() }
    Surface(Modifier.fillMaxWidth(), shape = HiShapeTokens.Small, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.thread_detail_login_to_operate), style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer, modifier = Modifier.weight(1f).padding(end = 10.dp))
            FilledTonalButton(
                onClick = onOpenLogin,
                interactionSource = loginInteraction,
                shape = HiShapeTokens.Small,
                modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 48.dp).hiPressScale(loginInteraction),
            ) { Text(stringResource(R.string.common_action_login)) }
        }
    }
}
