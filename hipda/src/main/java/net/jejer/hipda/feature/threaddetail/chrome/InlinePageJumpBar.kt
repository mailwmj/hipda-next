package net.jejer.hipda.feature.threaddetail.chrome

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale

/** 内联跳页条紧凑双行 / 单行切换阈值（`product-design.md` §7.3、`R-Dkudo`）。 */
val InlinePageJumpCompactBreakpoint: Dp = 480.dp

/** 内联跳页条紧凑双行固定高度（上下 4dp 内边距 + 两行 48dp 触控区）。 */
internal val InlinePageJumpTwoRowHeight: Dp = 104.dp

/** 内联跳页条单行固定高度（上下 4dp 内边距 + 单行 48dp 触控区）。 */
internal val InlinePageJumpSingleRowHeight: Dp = 56.dp

private const val MAX_PAGE_DIGITS = 6

/**
 * 帖子详情底部原地展开内联跳页条（`product-design.md` §6.3、§7.3，`R-Dkudo`）：
 * - 容器可用宽 `< 480dp` 自动采用紧凑双行（上行 `首页` + `第 {目标} / {总页} 页` + `末页`；下行滑块 + 输入框 + `/ {M}` + `关闭`/`跳转`），
 *   `≥ 480dp` 排布为单行；
 * - 滑块严格使用 `Modifier.weight(1f)` 自适应填充且绝不写死最小宽，保证 `340dp` + `1.25×` 零挤压且全部控件触控区 `≥ 48dp`；
 * - 拖动滑块仅本地实时更新预览与输入框数字（零滚屏、零发网）；`首页` 在第 1 页禁用、`末页` 在末页禁用；
 * - 动作按钮按输入合法且变更与否动态切换 `关闭`（`×`）与 `跳转`（`✓`），任何跳转触发 [onJumpToPage] 并由外层压栈且收起。
 */
@Composable
fun InlinePageJumpBar(
    currentPage: Int,
    lastPage: Int,
    containerWidth: Dp,
    onJumpToPage: (Int) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeLastPage = lastPage.coerceAtLeast(1)
    val safeCurrentPage = currentPage.coerceIn(1, safeLastPage)
    var previewPage by remember(safeCurrentPage, safeLastPage) { mutableIntStateOf(safeCurrentPage) }
    var inputText by remember(safeCurrentPage, safeLastPage) { mutableStateOf(safeCurrentPage.toString()) }
    var sliderInteracted by remember(safeCurrentPage, safeLastPage) { mutableStateOf(false) }

    val parsedInput = inputText.toIntOrNull()
    val validChangedTarget = parsedInput?.takeIf { it in 1..safeLastPage && it != safeCurrentPage }
    val canJump = validChangedTarget != null

    val firstPageLabel = stringResource(R.string.thread_detail_jump_first_page)
    val lastPageLabel = stringResource(R.string.thread_detail_jump_last_page)
    val inputHint = stringResource(R.string.thread_detail_jump_input_hint)
    val totalSuffix = stringResource(R.string.thread_detail_jump_total_suffix, safeLastPage)
    val closeDesc = stringResource(R.string.thread_detail_jump_action_close)
    val goDesc = stringResource(R.string.thread_detail_jump_action_go)
    val previewLabel = stringResource(R.string.thread_detail_jump_preview_page, previewPage, safeLastPage)
    val expandedAnnouncement = stringResource(R.string.thread_detail_jump_expanded_announcement, safeCurrentPage, safeLastPage)
    val liveAnnouncement = if (sliderInteracted) previewLabel else expandedAnnouncement

    val submitIfValid: () -> Unit = {
        if (validChangedTarget != null) {
            onJumpToPage(validChangedTarget)
        } else {
            onCollapse()
        }
    }

    val onSliderChange: (Float) -> Unit = { raw ->
        val next = raw.roundToInt().coerceIn(1, safeLastPage)
        previewPage = next
        inputText = next.toString()
        sliderInteracted = true
    }

    val onInputChange: (String) -> Unit = { raw ->
        val digits = raw.filter { it.isDigit() }.take(MAX_PAGE_DIGITS)
        inputText = digits
        digits.toIntOrNull()?.takeIf { it in 1..safeLastPage }?.let { parsed ->
            previewPage = parsed
        }
    }

    val isCompactTwoRow = containerWidth < InlinePageJumpCompactBreakpoint
    val cs = MaterialTheme.colorScheme
    val tabularLabelStyle = MaterialTheme.typography.labelLarge.let { style ->
        remember(style) { style.copy(fontFeatureSettings = "tnum") }
    }

    HiCappedFontScale {
        Box(modifier = modifier.fillMaxWidth()) {
            // 展开与滑块步进的无障碍礼貌播报节点（零视觉占位）
            Box(
                modifier = Modifier
                    .size(0.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = liveAnnouncement
                    },
            )

            if (isCompactTwoRow) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QuickPageJumpButton(
                            label = firstPageLabel,
                            enabled = safeCurrentPage > 1,
                            onClick = { onJumpToPage(1) },
                        )
                        Text(
                            text = previewLabel,
                            style = tabularLabelStyle,
                            color = cs.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                        )
                        QuickPageJumpButton(
                            label = lastPageLabel,
                            enabled = safeCurrentPage < safeLastPage,
                            onClick = { onJumpToPage(safeLastPage) },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        PageJumpSlider(previewPage, safeLastPage, previewLabel, onSliderChange, Modifier.weight(1f))
                        PageJumpTailControls(inputText, inputHint, totalSuffix, canJump, closeDesc, goDesc, tabularLabelStyle, onInputChange, submitIfValid)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QuickPageJumpButton(firstPageLabel, enabled = safeCurrentPage > 1) { onJumpToPage(1) }
                    PageJumpSlider(previewPage, safeLastPage, previewLabel, onSliderChange, Modifier.weight(1f))
                    QuickPageJumpButton(lastPageLabel, enabled = safeCurrentPage < safeLastPage) { onJumpToPage(safeLastPage) }
                    PageJumpTailControls(inputText, inputHint, totalSuffix, canJump, closeDesc, goDesc, tabularLabelStyle, onInputChange, submitIfValid)
                }
            }
        }
    }
}

@Composable
private fun PageJumpTailControls(
    inputText: String,
    inputHint: String,
    totalSuffix: String,
    canJump: Boolean,
    closeDesc: String,
    goDesc: String,
    labelStyle: androidx.compose.ui.text.TextStyle,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    PageJumpNumberInput(inputText, inputHint, onInputChange, onSubmit)
    Text(totalSuffix, style = labelStyle, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
    DynamicJumpActionButton(canJump, closeDesc, goDesc, onSubmit)
}

@Composable
private fun QuickPageJumpButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .hiPressScale(interaction)
            .clip(HiShapeTokens.Small)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) cs.primary else cs.onSurface.copy(alpha = 0.38f),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageJumpSlider(
    previewPage: Int,
    lastPage: Int,
    stateDesc: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeMax = lastPage.coerceAtLeast(1).toFloat()
    val rangeEnd = if (safeMax > 1f) safeMax else 2f
    val targetValue = previewPage.toFloat().coerceIn(1f, rangeEnd)
    val sliderState = remember(rangeEnd) {
        SliderState(
            value = targetValue,
            steps = 0,
            trackRange = 1f..rangeEnd,
        )
    }
    if (sliderState.value != targetValue) {
        sliderState.value = targetValue
    }
    val interactionSource = remember { MutableInteractionSource() }
    // 用标准 Box（默认不传递 minWidth）解除 Row.weight(1f) 的紧定最小宽约束，
    // 并为 M3 Slider 内置的 10dp 水平无障碍聚焦外扩留白，避免聚焦框压到输入框或被胶囊左缘裁切。
    Box(
        modifier = modifier.heightIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Slider(
            state = sliderState,
            onValueChange = onValueChange,
            enabled = lastPage > 1,
            interactionSource = interactionSource,
            thumb = {
                Box(
                    modifier = Modifier.height(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    SliderDefaults.Thumb(
                        interactionSource = interactionSource,
                        enabled = lastPage > 1,
                        thumbSize = DpSize(4.dp, 36.dp),
                    )
                }
            },
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .heightIn(min = 48.dp)
                .semantics { stateDescription = stateDesc },
        )
    }
}

@Composable
private fun PageJumpNumberInput(
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val textFieldState = rememberTextFieldState(initialText = value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnSubmit by rememberUpdatedState(onSubmit)
    LaunchedEffect(value) {
        if (textFieldState.text.toString() != value) {
            textFieldState.setTextAndPlaceCursorAtEnd(value)
        }
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect { current ->
            latestOnValueChange(current)
        }
    }
    val inputTextStyle = MaterialTheme.typography.labelLarge.copy(
        color = cs.onSurface,
        textAlign = TextAlign.Center,
        fontFeatureSettings = "tnum",
    )
    BasicTextField(
        state = textFieldState,
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = inputTextStyle,
        cursorBrush = SolidColor(cs.primary),
        inputTransformation = InputTransformation {
            val raw = asCharSequence()
            val filtered = raw.filter { it.isDigit() }.take(MAX_PAGE_DIGITS)
            if (filtered != raw.toString()) {
                replace(0, length, filtered)
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Go,
        ),
        onKeyboardAction = { latestOnSubmit() },
        modifier = Modifier
            .width(48.dp)
            .heightIn(min = 48.dp)
            .semantics { contentDescription = hint },
        decorator = { innerTextField ->
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .padding(vertical = 6.dp)
                    .clip(HiShapeTokens.Small)
                    .background(cs.surfaceContainerHigh.copy(alpha = 0.85f))
                    .border(0.75.dp, cs.outlineVariant, HiShapeTokens.Small)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (textFieldState.text.isEmpty()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun DynamicJumpActionButton(
    canJump: Boolean,
    closeDescription: String,
    goDescription: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(48.dp)
            .hiPressScale(interaction)
            .clip(HiShapeTokens.Full)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(HiShapeTokens.Full)
                .background(if (canJump) cs.primaryContainer else cs.surfaceContainerHigh.copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (canJump) Icons.Rounded.Check else Icons.Rounded.Close,
                contentDescription = if (canJump) goDescription else closeDescription,
                tint = if (canJump) cs.onPrimaryContainer else cs.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
