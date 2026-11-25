package net.jejer.hipda.ui.designsystem.feedback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.LocalHiMotionDurationScale

/** 按压与状态层常量（§6.3）。 */
@Immutable
object HiFeedbackTokens {
    const val PressScalePressed: Float = 0.97f
    const val PressScaleResting: Float = 1.0f
    const val PressScaleVisibilityThreshold: Float = 0.0005f
    const val PressStateLayerAlpha: Float = 0.10f
    const val StateLayerVisibilityThreshold: Float = 0.001f
}

/** 按钮/标签/导航项按压缩放：按下立即缩至 0.97，松开弹回 1.0（[HiMotion.spatialFast]）。 */
fun Modifier.hiPressScale(interactionSource: InteractionSource): Modifier =
    this then HiPressScaleElement(interactionSource)

/** 列表项按压状态层 [IndicationNodeFactory]，可直接传给 `clickable(indication = ...)`，不缩放。 */
fun hiListItemPressIndication(color: Color = Color.Unspecified): IndicationNodeFactory =
    HiListItemPressIndication(color)

/** 列表项按压反馈：仅显示状态层（[HiMotion.effectsFast]），不缩放。 */
fun Modifier.hiListItemPress(interactionSource: InteractionSource, color: Color = Color.Unspecified): Modifier =
    indication(interactionSource, hiListItemPressIndication(color))

/** 确认类操作触感封装（Compose UI 1.13.0 使用 [HapticFeedbackType.Confirm]）。 */
@Stable
class HiHaptics internal constructor(private val hapticFeedback: HapticFeedback) {
    fun confirm() = hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
}

/** 获取绑定当前 [LocalHapticFeedback] 的 [HiHaptics]。 */
@Composable
fun rememberHiHaptics(): HiHaptics {
    val hapticFeedback = LocalHapticFeedback.current
    return remember(hapticFeedback) { HiHaptics(hapticFeedback) }
}

private abstract class HiPressFeedbackNode(
    protected var interactionSource: InteractionSource,
    private val restingValue: Float,
    private val visibilityThreshold: Float,
) : Modifier.Node(), CompositionLocalConsumerModifierNode {
    private var anim: Animatable<Float, AnimationVector1D>? = null
    private var collectJob: Job? = null
    private var animJob: Job? = null

    protected val currentValue: Float get() = anim?.value ?: restingValue
    protected abstract fun targetValue(pressed: Boolean): Float
    protected abstract fun animationSpec(pressed: Boolean): FiniteAnimationSpec<Float>
    protected abstract fun onAnimatorAllocated()

    override fun onAttach() = startCollecting()
    override fun onDetach() {
        collectJob?.cancel(); collectJob = null
        animJob?.cancel(); animJob = null
    }

    fun updateInteractionSource(newSource: InteractionSource) {
        if (interactionSource === newSource) return
        interactionSource = newSource
        if (isAttached) {
            startCollecting()
            if (anim != null) animateToPressed(false)
        }
    }

    private fun startCollecting() {
        collectJob?.cancel()
        collectJob = coroutineScope.launch {
            var pressCount = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> if (pressCount++ == 0) animateToPressed(true)
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        pressCount = (pressCount - 1).coerceAtLeast(0)
                        if (pressCount == 0) animateToPressed(false)
                    }
                }
            }
        }
    }

    private fun animateToPressed(pressed: Boolean) {
        val target = targetValue(pressed)
        val animator = anim ?: Animatable(restingValue, visibilityThreshold).also { anim = it; onAnimatorAllocated() }
        val reducedMotion = currentValueOf(LocalHiMotionDurationScale).scaleFactor == 0f
        animJob?.cancel()
        animJob = coroutineScope.launch {
            if (reducedMotion) animator.snapTo(target)
            else animator.animateTo(targetValue = target, animationSpec = animationSpec(pressed))
        }
    }
}

private data class HiPressScaleElement(val interactionSource: InteractionSource) : ModifierNodeElement<HiPressScaleNode>() {
    override fun create() = HiPressScaleNode(interactionSource)
    override fun update(node: HiPressScaleNode) = node.updateInteractionSource(interactionSource)
    override fun InspectorInfo.inspectableProperties() {
        name = "hiPressScale"
        properties["interactionSource"] = interactionSource
    }
}

private class HiPressScaleNode(interactionSource: InteractionSource) : HiPressFeedbackNode(
    interactionSource, HiFeedbackTokens.PressScaleResting, HiFeedbackTokens.PressScaleVisibilityThreshold,
), LayoutModifierNode {
    override fun targetValue(pressed: Boolean) =
        if (pressed) HiFeedbackTokens.PressScalePressed else HiFeedbackTokens.PressScaleResting
    override fun animationSpec(pressed: Boolean) =
        HiMotion.spatialFast<Float>(visibilityThreshold = HiFeedbackTokens.PressScaleVisibilityThreshold)
    override fun onAnimatorAllocated() = invalidatePlacement()
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) { currentValue.let { scaleX = it; scaleY = it } }
        }
    }
}

private data class HiListItemPressIndication(val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        HiListItemPressNode(interactionSource, color)
}

private class HiListItemPressNode(interactionSource: InteractionSource, private val color: Color) : HiPressFeedbackNode(
    interactionSource, 0f, HiFeedbackTokens.StateLayerVisibilityThreshold,
), DrawModifierNode {
    override fun targetValue(pressed: Boolean) = if (pressed) HiFeedbackTokens.PressStateLayerAlpha else 0f
    override fun animationSpec(pressed: Boolean): FiniteAnimationSpec<Float> {
        val fast = HiMotion.effectsFast<Float>(visibilityThreshold = HiFeedbackTokens.StateLayerVisibilityThreshold)
        return if (pressed) fast else HiMotion.exit(fast)
    }
    override fun onAnimatorAllocated() = invalidateDraw()
    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = currentValue.coerceIn(0f, 1f)
        if (alpha > 0f) {
            val resolved = if (color.isSpecified) color else currentValueOf(LocalContentColor)
            drawRect(color = resolved.copy(alpha = alpha))
        }
    }
}
