package net.jejer.hipda.ui.designsystem.feedback

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.LocalHiMotionDurationScale

/** 反馈组件按压与状态层常量（对齐 `product-design.md` §6.3）。 */
@Immutable
object HiFeedbackTokens {
    const val PressScalePressed: Float = 0.97f
    const val PressScaleResting: Float = 1.0f
    const val PressScaleVisibilityThreshold: Float = 0.0005f
    const val PressStateLayerAlpha: Float = 0.10f
    const val StateLayerVisibilityThreshold: Float = 0.001f
}

/**
 * 按钮、图标按钮、标签与导航项的按压缩放反馈（对齐 `product-design.md` §6.3）：
 * 按下立即无延迟缩至 0.97，松开弹回 1.0，使用 [HiMotion.spatialFast] 弹簧。
 */
fun Modifier.hiPressScale(
    interactionSource: InteractionSource,
): Modifier = this then HiPressScaleElement(interactionSource)

/**
 * 列表项按压反馈（对齐 `product-design.md` §6.3）：
 * 按下立即显示状态层（[HiMotion.effectsFast]），始终保持 1.0 缩放、不缩放变形。
 */
fun Modifier.hiListItemPress(
    interactionSource: InteractionSource,
    color: Color = Color.Unspecified,
): Modifier = this then HiListItemPressElement(interactionSource, color)

/**
 * 确认类操作的轻量触感封装（收藏、发送成功、长按出菜单等，对齐 `product-design.md` §6.3）。
 * 当前 Compose UI 版本（1.13.0）已提供 [HapticFeedbackType.Confirm]。
 */
@Stable
class HiHaptics internal constructor(
    private val hapticFeedback: HapticFeedback,
) {
    /** 触发确认类触感反馈（[HapticFeedbackType.Confirm]）。 */
    fun confirm() {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
    }
}

/** 获取绑定当前 [LocalHapticFeedback] 的 [HiHaptics]。 */
@Composable
fun rememberHiHaptics(): HiHaptics {
    val hapticFeedback = LocalHapticFeedback.current
    return remember(hapticFeedback) { HiHaptics(hapticFeedback) }
}

private data class HiPressScaleElement(
    val interactionSource: InteractionSource,
) : ModifierNodeElement<HiPressScaleNode>() {
    override fun create(): HiPressScaleNode = HiPressScaleNode(interactionSource)

    override fun update(node: HiPressScaleNode) {
        node.updateInteractionSource(interactionSource)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "hiPressScale"
        properties["interactionSource"] = interactionSource
    }
}

private class HiPressScaleNode(
    private var interactionSource: InteractionSource,
) : Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {

    private val scaleAnim = Animatable(
        initialValue = HiFeedbackTokens.PressScaleResting,
        visibilityThreshold = HiFeedbackTokens.PressScaleVisibilityThreshold,
    )
    private var collectJob: Job? = null
    private var animJob: Job? = null

    override fun onAttach() {
        startCollecting()
    }

    override fun onDetach() {
        collectJob?.cancel()
        collectJob = null
        animJob?.cancel()
        animJob = null
    }

    fun updateInteractionSource(newSource: InteractionSource) {
        if (interactionSource === newSource) return
        interactionSource = newSource
        if (isAttached) {
            startCollecting()
            animateToPressed(false)
        }
    }

    private fun startCollecting() {
        collectJob?.cancel()
        collectJob = coroutineScope.launch {
            var pressCount = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        val wasPressed = pressCount > 0
                        pressCount++
                        if (!wasPressed) animateToPressed(true)
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        pressCount = (pressCount - 1).coerceAtLeast(0)
                        if (pressCount == 0) animateToPressed(false)
                    }
                }
            }
        }
    }

    private fun animateToPressed(pressed: Boolean) {
        val target = if (pressed) {
            HiFeedbackTokens.PressScalePressed
        } else {
            HiFeedbackTokens.PressScaleResting
        }
        val reducedMotion = currentValueOf(LocalHiMotionDurationScale).scaleFactor == 0f
        animJob?.cancel()
        animJob = coroutineScope.launch {
            if (reducedMotion) {
                scaleAnim.snapTo(target)
            } else {
                scaleAnim.animateTo(
                    targetValue = target,
                    animationSpec = HiMotion.spatialFast(
                        visibilityThreshold = HiFeedbackTokens.PressScaleVisibilityThreshold,
                    ),
                )
            }
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val scale = scaleAnim.value
                scaleX = scale
                scaleY = scale
            }
        }
    }
}

private data class HiListItemPressElement(
    val interactionSource: InteractionSource,
    val color: Color,
) : ModifierNodeElement<HiListItemPressNode>() {
    override fun create(): HiListItemPressNode = HiListItemPressNode(interactionSource, color)

    override fun update(node: HiListItemPressNode) {
        node.color = color
        node.updateInteractionSource(interactionSource)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "hiListItemPress"
        properties["interactionSource"] = interactionSource
        properties["color"] = color
    }
}

private class HiListItemPressNode(
    private var interactionSource: InteractionSource,
    var color: Color,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {

    private val alphaAnim = Animatable(
        initialValue = 0f,
        visibilityThreshold = HiFeedbackTokens.StateLayerVisibilityThreshold,
    )
    private var collectJob: Job? = null
    private var animJob: Job? = null

    override fun onAttach() {
        startCollecting()
    }

    override fun onDetach() {
        collectJob?.cancel()
        collectJob = null
        animJob?.cancel()
        animJob = null
    }

    fun updateInteractionSource(newSource: InteractionSource) {
        if (interactionSource === newSource) return
        interactionSource = newSource
        if (isAttached) {
            startCollecting()
            animateToPressed(false)
        }
    }

    private fun startCollecting() {
        collectJob?.cancel()
        collectJob = coroutineScope.launch {
            var pressCount = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        val wasPressed = pressCount > 0
                        pressCount++
                        if (!wasPressed) animateToPressed(true)
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        pressCount = (pressCount - 1).coerceAtLeast(0)
                        if (pressCount == 0) animateToPressed(false)
                    }
                }
            }
        }
    }

    private fun animateToPressed(pressed: Boolean) {
        val target = if (pressed) HiFeedbackTokens.PressStateLayerAlpha else 0f
        val reducedMotion = currentValueOf(LocalHiMotionDurationScale).scaleFactor == 0f
        animJob?.cancel()
        animJob = coroutineScope.launch {
            if (reducedMotion) {
                alphaAnim.snapTo(target)
            } else {
                val spec = if (pressed) {
                    HiMotion.effectsFast<Float>(
                        visibilityThreshold = HiFeedbackTokens.StateLayerVisibilityThreshold,
                    )
                } else {
                    HiMotion.exit(
                        HiMotion.effectsFast<Float>(
                            visibilityThreshold = HiFeedbackTokens.StateLayerVisibilityThreshold,
                        ),
                    )
                }
                alphaAnim.animateTo(targetValue = target, animationSpec = spec)
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = alphaAnim.value.coerceIn(0f, 1f)
        if (alpha > 0f) {
            val resolvedColor = if (color.isSpecified) color else currentValueOf(LocalContentColor)
            drawRect(color = resolvedColor.copy(alpha = alpha))
        }
    }
}
