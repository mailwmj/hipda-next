package net.jejer.hipda.ui.designsystem.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut
import net.jejer.hipda.ui.designsystem.motion.hiScaleIn
import net.jejer.hipda.ui.designsystem.motion.hiScaleOut

private const val ROLLING_VISIBILITY_THRESHOLD: Float = 0.001f
private val BADGE_MIN_SIZE = 18.dp
private val BADGE_HORIZONTAL_PADDING = 5.dp
private val BADGE_VERTICAL_PADDING = 1.dp

/** 仅供包内单测定位未合并语义树中各个数位（0=个位，1=十位，2=百位…；-1=负号）。 */
internal val HiRollingDigitPlaceKey: SemanticsPropertyKey<Int> = SemanticsPropertyKey("HiRollingDigitPlace")

/** 仅供包内单测定位角标缩放表面节点，校验从中心放大/缩小过程。 */
internal val HiCountBadgeSurfaceKey: SemanticsPropertyKey<Boolean> = SemanticsPropertyKey("HiCountBadgeSurface")

/** 仅供包内单测观测 [HiRollingNumber] 自身重组次数，生产环境默认为 null（零开销）。 */
internal val LocalHiRollingNumberRecompositionHook = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * 逐位上下滚动的数字组件（对齐 `product-design.md` §6.1、§6.3）。
 *
 * - 按数位从低位对齐：只滚动发生变化的位，未变化位保持静止；
 * - 数值增加时新数字从下往上滚入，数值减少时从上往下滚入，使用 [HiMotion.spatialFast] 弹簧，支持任意时刻打断并连续反向；
 * - 系统开启“减少动态效果”（`ANIMATOR_DURATION_SCALE == 0`）时直接切换，一帧到位；
 * - 合并语义树对外只暴露完整数字文本，避免无障碍朗读拆分或读到退场中的旧位。
 */
@Composable
fun HiRollingNumber(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val recompositionHook = LocalHiRollingNumberRecompositionHook.current
    if (recompositionHook != null) {
        SideEffect { recompositionHook() }
    }

    val reducedMotion = HiTheme.isReducedMotion
    val resolvedStyle = remember(style) {
        if (style.fontFeatureSettings == null) style.copy(fontFeatureSettings = "tnum") else style
    }
    val state = remember { RollingNumberState(initialValue = value) }
    Snapshot.withoutReadObservation {
        state.syncTarget(value = value, reducedMotion = reducedMotion)
    }

    val orderedPlaces = state.slotsByPlace.keys.sortedDescending()

    Row(
        modifier = modifier.clearAndSetSemantics {
            text = AnnotatedString(value.toString())
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.signSlot.isVisibleInTree) {
            key(-1) {
                RollingDigitSlot(
                    slot = state.signSlot,
                    style = resolvedStyle,
                    color = color,
                    reducedMotion = reducedMotion,
                    onSettledEmpty = { state.signSlot.markSettledEmpty() },
                )
            }
        }
        for (place in orderedPlaces) {
            val slot = state.slotsByPlace[place] ?: continue
            key(place) {
                RollingDigitSlot(
                    slot = slot,
                    style = resolvedStyle,
                    color = color,
                    reducedMotion = reducedMotion,
                    onSettledEmpty = { state.removeSlotIfStillEmpty(place) },
                )
            }
        }
    }
}

/**
 * 计数角标（对齐 `product-design.md` §2 原则 2 与 §6.3）：
 *
 * - `count <= 0` 时不渲染且不在语义树中；
 * - `0 -> >0` 从中心放大出现（[HiMotion.spatialFast]），`>0 -> 0` 缩小消失（[HiMotion.exit]）后从语义树移除；
 * - 内部数字复用 [HiRollingNumber] 逐位滚动，退场过程中保留最后一个正数不闪烁 `0`。
 */
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

    var displayedCount by remember { mutableIntStateOf(count.coerceAtLeast(1)) }
    if (visible) {
        displayedCount = count
    }

    if (!visibleState.currentState && !visibleState.targetState) {
        return
    }

    AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = hiScaleIn(
            animationSpec = HiMotion.spatialFast(),
            initialScale = 0f,
            transformOrigin = TransformOrigin.Center,
        ) + hiFadeIn(animationSpec = HiMotion.effectsFast()),
        exit = hiScaleOut(
            animationSpec = HiMotion.exit(HiMotion.spatialFast()),
            targetScale = 0f,
            transformOrigin = TransformOrigin.Center,
        ) + hiFadeOut(animationSpec = HiMotion.exit(HiMotion.effectsFast())),
        label = "HiCountBadge",
    ) {
        Box(
            modifier = Modifier
                .semantics { this[HiCountBadgeSurfaceKey] = true }
                .widthIn(min = BADGE_MIN_SIZE)
                .heightIn(min = BADGE_MIN_SIZE)
                .clip(HiShapeTokens.Full)
                .background(containerColor)
                .padding(horizontal = BADGE_HORIZONTAL_PADDING, vertical = BADGE_VERTICAL_PADDING),
            contentAlignment = Alignment.Center,
        ) {
            HiRollingNumber(
                value = displayedCount,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
            )
        }
    }
}

@Stable
private class RollingDigitSlotState(
    val place: Int,
    initialChar: Char?,
) {
    var targetStep: Int by mutableIntStateOf(0)
        private set
    var targetChar: Char? by mutableStateOf(initialChar)
        private set
    val stepChars = mutableStateMapOf<Int, Char?>().apply {
        put(0, initialChar)
    }
    val progress = Animatable(0f, visibilityThreshold = ROLLING_VISIBILITY_THRESHOLD)
    val widthFraction = Animatable(
        initialValue = if (initialChar != null) 1f else 0f,
        visibilityThreshold = ROLLING_VISIBILITY_THRESHOLD,
    )
    var snappedOverride: Boolean by mutableStateOf(false)
    var isVisibleInTree: Boolean by mutableStateOf(initialChar != null)
        private set

    fun advanceTo(newChar: Char?, direction: Int) {
        val nextStep = targetStep + direction
        targetStep = nextStep
        targetChar = newChar
        stepChars[nextStep] = newChar
        snappedOverride = false
        if (newChar != null) {
            isVisibleInTree = true
        }
    }

    fun snapImmediate(newChar: Char?) {
        val nextStep = targetStep + 1
        targetStep = nextStep
        targetChar = newChar
        stepChars.clear()
        stepChars[nextStep] = newChar
        snappedOverride = true
        isVisibleInTree = newChar != null
    }

    fun pruneToTarget() {
        val currentTarget = targetStep
        val staleKeys = stepChars.keys.filter { it != currentTarget }
        for (staleKey in staleKeys) {
            stepChars.remove(staleKey)
        }
    }

    fun markSettledEmpty() {
        if (targetChar == null) {
            isVisibleInTree = false
        }
    }
}

@Stable
private class RollingNumberState(initialValue: Int) {
    private var lastTargetValue: Int = initialValue

    val signSlot = RollingDigitSlotState(
        place = -1,
        initialChar = if (initialValue < 0) '-' else null,
    )

    val slotsByPlace = mutableStateMapOf<Int, RollingDigitSlotState>().apply {
        for ((place, digit) in digitMapFor(initialValue)) {
            put(place, RollingDigitSlotState(place = place, initialChar = digit))
        }
    }

    fun syncTarget(value: Int, reducedMotion: Boolean) {
        if (value == lastTargetValue) {
            if (reducedMotion) {
                snapAllToCurrentTarget()
            }
            return
        }
        val direction = if (value > lastTargetValue) 1 else -1
        lastTargetValue = value

        val newSign = if (value < 0) '-' else null
        if (signSlot.targetChar != newSign) {
            if (reducedMotion) signSlot.snapImmediate(newSign) else signSlot.advanceTo(newSign, direction)
        }

        val newDigits = digitMapFor(value)
        val maxPlace = maxOf(
            slotsByPlace.keys.maxOrNull() ?: 0,
            newDigits.keys.maxOrNull() ?: 0,
        )
        for (place in 0..maxPlace) {
            val newChar = newDigits[place]
            val existing = slotsByPlace[place]
            if (existing == null) {
                if (newChar != null) {
                    val created = RollingDigitSlotState(
                        place = place,
                        initialChar = if (reducedMotion) newChar else null,
                    )
                    if (!reducedMotion) {
                        created.advanceTo(newChar, direction)
                    }
                    slotsByPlace[place] = created
                }
            } else if (existing.targetChar != newChar) {
                if (reducedMotion) {
                    if (newChar == null) {
                        slotsByPlace.remove(place)
                    } else {
                        existing.snapImmediate(newChar)
                    }
                } else {
                    existing.advanceTo(newChar, direction)
                }
            }
        }
    }

    fun removeSlotIfStillEmpty(place: Int) {
        val slot = slotsByPlace[place] ?: return
        if (slot.targetChar == null) {
            slotsByPlace.remove(place)
        }
    }

    private fun snapAllToCurrentTarget() {
        val emptyPlaces = mutableListOf<Int>()
        for ((place, slot) in slotsByPlace) {
            if (slot.targetChar == null) {
                emptyPlaces += place
            } else if (slot.stepChars.size > 1) {
                slot.snapImmediate(slot.targetChar)
            }
        }
        emptyPlaces.forEach { slotsByPlace.remove(it) }
        if (signSlot.targetChar == null) {
            signSlot.markSettledEmpty()
        } else if (signSlot.stepChars.size > 1) {
            signSlot.snapImmediate(signSlot.targetChar)
        }
    }
}

@Composable
private fun RollingDigitSlot(
    slot: RollingDigitSlotState,
    style: TextStyle,
    color: Color,
    reducedMotion: Boolean,
    onSettledEmpty: () -> Unit,
) {
    val targetStep = slot.targetStep
    val targetChar = slot.targetChar

    LaunchedEffect(targetStep, targetChar, reducedMotion) {
        val targetProgress = targetStep.toFloat()
        val targetWidth = if (targetChar != null) 1f else 0f
        if (reducedMotion) {
            slot.progress.snapTo(targetProgress)
            slot.widthFraction.snapTo(targetWidth)
            slot.snappedOverride = false
            slot.pruneToTarget()
            if (targetChar == null) {
                onSettledEmpty()
            }
        } else {
            val spec = if (targetChar != null) {
                HiMotion.spatialFast<Float>(visibilityThreshold = ROLLING_VISIBILITY_THRESHOLD)
            } else {
                HiMotion.exit(HiMotion.spatialFast<Float>(visibilityThreshold = ROLLING_VISIBILITY_THRESHOLD))
            }
            coroutineScope {
                launch {
                    slot.progress.animateTo(targetValue = targetProgress, animationSpec = spec)
                }
                launch {
                    if (slot.widthFraction.value != targetWidth) {
                        slot.widthFraction.animateTo(targetValue = targetWidth, animationSpec = spec)
                    }
                }
            }
            slot.pruneToTarget()
            if (targetChar == null) {
                onSettledEmpty()
            }
        }
    }

    val activeEntries = slot.stepChars.entries
        .mapNotNull { (stepIdx, ch) -> ch?.let { stepIdx to it } }
        .sortedBy { it.first }

    if (activeEntries.isEmpty()) return

    Layout(
        content = {
            for ((stepIdx, ch) in activeEntries) {
                key(stepIdx) {
                    Text(
                        text = ch.toString(),
                        style = style,
                        color = color,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.semantics {
                            this[HiRollingDigitPlaceKey] = slot.place
                        },
                    )
                }
            }
        },
        modifier = Modifier.clipToBounds(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val baseWidth = placeables.maxOfOrNull { it.width } ?: 0
        val slotHeight = placeables.maxOfOrNull { it.height } ?: 0
        val widthFactor = if (slot.snappedOverride) {
            if (slot.targetChar != null) 1f else 0f
        } else {
            slot.widthFraction.value.coerceIn(0f, 1f)
        }
        val layoutWidth = (baseWidth * widthFactor).roundToInt()

        layout(layoutWidth, slotHeight) {
            for (index in placeables.indices) {
                val stepIdx = activeEntries[index].first
                val placeable = placeables[index]
                val x = (layoutWidth - placeable.width) / 2
                placeable.placeWithLayer(x = x, y = 0) {
                    val currentProgress = if (slot.snappedOverride) {
                        slot.targetStep.toFloat()
                    } else {
                        slot.progress.value
                    }
                    val delta = stepIdx - currentProgress
                    translationY = delta * slotHeight
                    alpha = (1f - abs(delta)).coerceIn(0f, 1f)
                }
            }
        }
    }
}

private fun digitMapFor(value: Int): Map<Int, Char> {
    val digits = abs(value.toLong()).toString()
    val len = digits.length
    return buildMap(len) {
        for (i in 0 until len) {
            put(len - 1 - i, digits[i])
        }
    }
}
