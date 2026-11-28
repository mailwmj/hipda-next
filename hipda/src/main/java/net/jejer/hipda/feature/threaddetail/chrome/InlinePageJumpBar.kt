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
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.ui.text.TextStyle
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
 * 帖子详情底部原地展开内联楼层/跳页条（`< 480dp` 紧凑双行 / `≥ 480dp` 单行，滑块 `Modifier.weight(1f)` 无硬编码最小宽）：
 * - 多页帖（`lastPage > 1`）：`顶楼` / `最新回复` + 按页滑块与页码输入；
 * - 单页多楼帖（`lastPage == 1 && totalFloors > 1`）：`顶楼` / `最新回复` + 按楼层滑块与楼层输入；
 * - 单页单楼帖（`lastPage == 1 && totalFloors <= 1`）：极简单行 `顶楼` / `第 1 楼` / `最新回复` / `关闭`。
 * 单一 `TextFieldState` 驱动数字输入与动态 `关闭`/`跳转` 按钮，拖动滑块仅本地实时预览（零滚屏、零发网）。
 */
@Composable
fun InlinePageJumpBar(
    currentPage: Int,
    lastPage: Int,
    containerWidth: Dp,
    onJumpToPage: (Int) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    currentFloor: Int = 1,
    totalFloors: Int = currentFloor.coerceAtLeast(1),
    isAtTop: Boolean = currentPage <= 1 && currentFloor <= 1,
    isAtBottom: Boolean = if (lastPage > 1) currentPage >= lastPage else currentFloor >= totalFloors.coerceAtLeast(1),
    onJumpToFirstFloor: () -> Unit = { onJumpToPage(1) },
    onJumpToLatestReply: () -> Unit = { onJumpToPage(lastPage.coerceAtLeast(1)) },
    onJumpToFloorNumber: (Int) -> Unit = {},
) {
    val safeLastPage = lastPage.coerceAtLeast(1)
    val isPageMode = safeLastPage > 1
    val safeTotalFloors = totalFloors.coerceAtLeast(1)
    val safeMaxValue = if (isPageMode) safeLastPage else safeTotalFloors
    val safeCurrentValue = if (isPageMode) {
        currentPage.coerceIn(1, safeLastPage)
    } else {
        currentFloor.coerceIn(1, safeTotalFloors)
    }
    val hasRangeSlider = safeMaxValue > 1

    var sliderPreviewValue by remember(isPageMode, safeCurrentValue, safeMaxValue) { mutableIntStateOf(safeCurrentValue) }
    var sliderInteracted by remember(isPageMode, safeCurrentValue, safeMaxValue) { mutableStateOf(false) }
    val textFieldState = rememberTextFieldState(initialText = safeCurrentValue.toString())
    LaunchedEffect(isPageMode, safeCurrentValue, safeMaxValue) {
        val targetText = safeCurrentValue.toString()
        if (textFieldState.text.toString() != targetText) textFieldState.setTextAndPlaceCursorAtEnd(targetText)
    }

    val parsedInput = textFieldState.text.toString().toIntOrNull()
    val previewValue = parsedInput?.takeIf { it in 1..safeMaxValue } ?: sliderPreviewValue
    val validChangedTarget = parsedInput?.takeIf { it in 1..safeMaxValue && it != safeCurrentValue }
    val canJump = hasRangeSlider && validChangedTarget != null

    val firstFloorLabel = stringResource(R.string.thread_detail_jump_first_floor)
    val latestReplyLabel = stringResource(R.string.thread_detail_jump_latest_reply)
    val inputHint = stringResource(if (isPageMode) R.string.thread_detail_jump_input_hint else R.string.thread_detail_jump_floor_input_hint)
    val totalSuffix = stringResource(R.string.thread_detail_jump_total_suffix, safeMaxValue)
    val closeDesc = stringResource(R.string.thread_detail_jump_action_close)
    val goDesc = stringResource(R.string.thread_detail_jump_action_go)
    val previewLabel = when {
        isPageMode -> stringResource(R.string.thread_detail_jump_preview_page, previewValue, safeMaxValue)
        hasRangeSlider -> stringResource(R.string.thread_detail_jump_preview_floor, previewValue, safeMaxValue)
        else -> stringResource(R.string.thread_detail_jump_single_floor, safeCurrentValue)
    }
    val expandedAnnouncement = if (isPageMode) {
        stringResource(R.string.thread_detail_jump_expanded_announcement, safeCurrentValue, safeMaxValue)
    } else {
        stringResource(R.string.thread_detail_jump_expanded_floor_announcement, safeCurrentValue, safeMaxValue)
    }
    val liveAnnouncement = if (sliderInteracted) previewLabel else expandedAnnouncement

    val submitIfValid: () -> Unit = {
        val target = validChangedTarget
        if (hasRangeSlider && target != null) {
            if (isPageMode) onJumpToPage(target) else onJumpToFloorNumber(target)
        } else {
            onCollapse()
        }
    }
    val onSliderChange: (Float) -> Unit = { raw ->
        val next = raw.roundToInt().coerceIn(1, safeMaxValue)
        sliderPreviewValue = next
        sliderInteracted = true
        val nextText = next.toString()
        if (textFieldState.text.toString() != nextText) textFieldState.setTextAndPlaceCursorAtEnd(nextText)
    }

    val cs = MaterialTheme.colorScheme
    val tabularLabelStyle = MaterialTheme.typography.labelLarge.let { style ->
        remember(style) { style.copy(fontFeatureSettings = "tnum") }
    }

    HiCappedFontScale {
        Box(modifier = modifier.fillMaxWidth()) {
            // 展开与滑块步进的无障碍礼貌播报节点（保留 1.dp 非零尺寸以防系统无障碍服务过滤 0×0 节点）
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = liveAnnouncement
                    },
            )

            when {
                !hasRangeSlider -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        QuickPageJumpButton(firstFloorLabel, enabled = !isAtTop, onClick = onJumpToFirstFloor)
                        Text(
                            text = previewLabel, style = tabularLabelStyle, color = cs.onSurfaceVariant,
                            textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        )
                        QuickPageJumpButton(latestReplyLabel, enabled = !isAtBottom, onClick = onJumpToLatestReply)
                        DynamicJumpActionButton(canJump = false, closeDescription = closeDesc, goDescription = goDesc, onClick = onCollapse)
                    }
                }
                containerWidth < InlinePageJumpCompactBreakpoint -> {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            QuickPageJumpButton(firstFloorLabel, enabled = !isAtTop, onClick = onJumpToFirstFloor)
                            Text(
                                text = previewLabel, style = tabularLabelStyle, color = cs.onSurface,
                                textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                            )
                            QuickPageJumpButton(latestReplyLabel, enabled = !isAtBottom, onClick = onJumpToLatestReply)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            PageJumpSlider(previewValue, safeMaxValue, previewLabel, onSliderChange, Modifier.weight(1f))
                            PageJumpTailControls(textFieldState, inputHint, totalSuffix, canJump, closeDesc, goDesc, tabularLabelStyle, submitIfValid)
                        }
                    }
                }
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        QuickPageJumpButton(firstFloorLabel, enabled = !isAtTop, onClick = onJumpToFirstFloor)
                        PageJumpSlider(previewValue, safeMaxValue, previewLabel, onSliderChange, Modifier.weight(1f))
                        QuickPageJumpButton(latestReplyLabel, enabled = !isAtBottom, onClick = onJumpToLatestReply)
                        PageJumpTailControls(textFieldState, inputHint, totalSuffix, canJump, closeDesc, goDesc, tabularLabelStyle, submitIfValid)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageJumpTailControls(
    textFieldState: TextFieldState,
    inputHint: String,
    totalSuffix: String,
    canJump: Boolean,
    closeDesc: String,
    goDesc: String,
    labelStyle: TextStyle,
    onSubmit: () -> Unit,
) {
    PageJumpNumberInput(textFieldState, inputHint, onSubmit)
    Text(totalSuffix, style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
    DynamicJumpActionButton(canJump, closeDesc, goDesc, onSubmit)
}

@Composable
private fun QuickPageJumpButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .hiPressScale(interaction)
            .clip(HiShapeTokens.Small)
            .clickable(interaction, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) cs.primary else cs.onSurface.copy(alpha = 0.38f), maxLines = 1, softWrap = false)
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
    val rangeEnd = lastPage.coerceAtLeast(1).toFloat().let { if (it > 1f) it else 2f }
    val targetValue = previewPage.toFloat().coerceIn(1f, rangeEnd)
    val sliderState = remember(rangeEnd) { SliderState(value = targetValue, steps = 0, trackRange = 1f..rangeEnd) }
    if (sliderState.value != targetValue) sliderState.value = targetValue
    val interactionSource = remember { MutableInteractionSource() }
    // 标准 Box 解除 Row.weight(1f) 紧最小宽约束，并为 M3 Slider 10dp 水平无障碍聚焦外扩留白
    Box(modifier = modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Slider(
            state = sliderState,
            onValueChange = onValueChange,
            enabled = lastPage > 1,
            interactionSource = interactionSource,
            thumb = {
                Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                    SliderDefaults.Thumb(interactionSource, enabled = lastPage > 1, thumbSize = DpSize(4.dp, 36.dp))
                }
            },
            modifier = Modifier.padding(horizontal = 10.dp).heightIn(min = 48.dp).semantics { stateDescription = stateDesc },
        )
    }
}

@Composable
private fun PageJumpNumberInput(textFieldState: TextFieldState, hint: String, onSubmit: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val latestOnSubmit by rememberUpdatedState(onSubmit)
    val inputTextStyle = MaterialTheme.typography.labelLarge.copy(color = cs.onSurface, textAlign = TextAlign.Center, fontFeatureSettings = "tnum")
    BasicTextField(
        state = textFieldState,
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = inputTextStyle,
        cursorBrush = SolidColor(cs.primary),
        inputTransformation = InputTransformation {
            val raw = asCharSequence()
            val filtered = raw.filter { it.isDigit() }.take(MAX_PAGE_DIGITS)
            if (filtered != raw.toString()) replace(0, length, filtered)
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
        onKeyboardAction = { latestOnSubmit() },
        modifier = Modifier.width(48.dp).heightIn(min = 48.dp).semantics { contentDescription = hint },
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
                    Text(hint, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun DynamicJumpActionButton(canJump: Boolean, closeDescription: String, goDescription: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(48.dp)
            .hiPressScale(interaction)
            .clip(HiShapeTokens.Full)
            .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(HiShapeTokens.Full).background(if (canJump) cs.primaryContainer else cs.surfaceContainerHigh.copy(alpha = 0.72f)),
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
