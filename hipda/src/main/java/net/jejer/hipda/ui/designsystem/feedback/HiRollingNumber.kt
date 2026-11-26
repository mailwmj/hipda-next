package net.jejer.hipda.ui.designsystem.feedback

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut
import net.jejer.hipda.ui.designsystem.motion.hiScaleIn
import net.jejer.hipda.ui.designsystem.motion.hiScaleOut
import net.jejer.hipda.ui.designsystem.motion.hiSlideInVertically
import net.jejer.hipda.ui.designsystem.motion.hiSlideOutVertically

/** 逐位上下滚动数字（§6.1、§6.3）：低位对齐、仅变化位滚动、增上减下（[HiMotion.spatialFast]）、减少动态效果一帧到位。 */
@Composable
fun HiRollingNumber(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val reducedMotion = HiTheme.isReducedMotion
    val resolvedStyle = remember(style) {
        if (style.fontFeatureSettings == null) style.copy(fontFeatureSettings = "tnum") else style
    }
    val digits = remember(value) { abs(value.toLong()).toString() }
    val lastValue = remember { IntArray(1).apply { this[0] = value } }
    val hasMounted = remember { BooleanArray(1) }
    val direction = value.compareTo(lastValue[0])
    SideEffect {
        lastValue[0] = value
        hasMounted[0] = true
    }

    var activeMaxPlace by remember { mutableIntStateOf(digits.lastIndex) }
    var keepSignSlot by remember { mutableStateOf(value < 0) }
    val displayMaxPlace = if (reducedMotion) digits.lastIndex else maxOf(activeMaxPlace, digits.lastIndex)
    LaunchedEffect(digits.lastIndex, value < 0, reducedMotion) {
        if (reducedMotion || digits.lastIndex > activeMaxPlace) activeMaxPlace = digits.lastIndex
        if (value < 0) keepSignSlot = true else if (reducedMotion) keepSignSlot = false
    }

    Row(
        modifier = modifier.clearAndSetSemantics { text = AnnotatedString(value.toString()) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (reducedMotion) {
            val rendered = if (value < 0) "-$digits" else digits
            for (ch in rendered) {
                Text(text = ch.toString(), style = resolvedStyle, color = color, maxLines = 1, softWrap = false)
            }
        } else {
            val signChar = if (value < 0) '-' else null
            if (signChar != null || keepSignSlot) {
                key(-1) {
                    RollingDigitCell(signChar, hasMounted[0], direction, resolvedStyle, color) {
                        if (value >= 0) keepSignSlot = false
                    }
                }
            }
            for (place in displayMaxPlace downTo 0) {
                val targetChar = digits.getOrNull(digits.length - 1 - place)
                key(place) {
                    RollingDigitCell(targetChar, hasMounted[0], direction, resolvedStyle, color) {
                        if (place == activeMaxPlace && digits.lastIndex < activeMaxPlace) {
                            activeMaxPlace = (activeMaxPlace - 1).coerceAtLeast(digits.lastIndex)
                        }
                    }
                }
            }
        }
    }
}

/** 计数角标（§2 原则 2、§6.3）：count <= 0 不进语义树，0→>0 中心放大，>0→0 缩小退场且不闪烁 0。 */
@Composable
fun HiCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.error,
    contentColor: Color = MaterialTheme.colorScheme.onError,
) {
    val visible = count > 0
    val visibleState = remember { MutableTransitionState(visible) }
    visibleState.targetState = visible
    val lastPositiveCount = remember { IntArray(1).apply { this[0] = count.coerceAtLeast(1) } }
    if (visible) lastPositiveCount[0] = count
    val displayedCount = if (visible) count else lastPositiveCount[0]
    if (!visibleState.currentState && !visibleState.targetState) return

    AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = hiScaleIn(HiMotion.spatialFast(), 0f, TransformOrigin.Center) + hiFadeIn(HiMotion.effectsFast()),
        exit = hiScaleOut(HiMotion.exit(HiMotion.spatialFast()), 0f, TransformOrigin.Center) +
            hiFadeOut(HiMotion.exit(HiMotion.effectsFast())),
        label = "HiCountBadge",
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 18.dp)
                .heightIn(min = 18.dp)
                .clip(HiShapeTokens.Full)
                .background(containerColor)
                .padding(horizontal = 5.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            HiRollingNumber(displayedCount, style = MaterialTheme.typography.labelSmall, color = contentColor)
        }
    }
}

private class RollingCharRef(var value: Char?)

@Composable
private fun RollingDigitCell(
    char: Char?,
    startEmpty: Boolean,
    direction: Int,
    style: TextStyle,
    color: Color,
    onSettledEmpty: (() -> Unit)? = null,
) {
    val visibleState = remember { MutableTransitionState(!startEmpty && char != null) }
    visibleState.targetState = char != null
    val lastNonNullChar = remember { RollingCharRef(char) }
    if (char != null) lastNonNullChar.value = char
    val displayedChar = char ?: lastNonNullChar.value

    val lastDirection = remember { IntArray(1).apply { this[0] = 1 } }
    if (direction != 0) lastDirection[0] = direction
    val rollUp = lastDirection[0] >= 0

    if (onSettledEmpty != null) {
        LaunchedEffect(visibleState.currentState, visibleState.targetState) {
            if (!visibleState.currentState && !visibleState.targetState) onSettledEmpty()
        }
    }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = expandHorizontally(animationSpec = HiMotion.spatialFast()) +
            hiSlideInVertically(HiMotion.spatialFast()) { if (rollUp) it else -it } +
            hiFadeIn(HiMotion.effectsFast()),
        exit = shrinkHorizontally(animationSpec = HiMotion.exit(HiMotion.spatialFast())) +
            hiSlideOutVertically(HiMotion.spatialFast()) { if (rollUp) -it else it } +
            hiFadeOut(HiMotion.exit(HiMotion.effectsFast())),
        label = "HiRollingDigitSlot",
    ) {
        val activeChar = displayedChar ?: return@AnimatedVisibility
        AnimatedContent(
            targetState = activeChar,
            modifier = Modifier.clipToBounds(),
            transitionSpec = {
                val up = lastDirection[0] >= 0
                val enter = hiSlideInVertically(HiMotion.spatialFast()) { if (up) it else -it } +
                    hiFadeIn(HiMotion.effectsFast())
                val exit = hiSlideOutVertically(HiMotion.spatialFast()) { if (up) -it else it } +
                    hiFadeOut(HiMotion.exit(HiMotion.effectsFast()))
                (enter togetherWith exit).using(null)
            },
            contentAlignment = Alignment.Center,
            label = "HiRollingDigit",
        ) { digit ->
            Text(text = digit.toString(), style = style, color = color, maxLines = 1, softWrap = false)
        }
    }
}
