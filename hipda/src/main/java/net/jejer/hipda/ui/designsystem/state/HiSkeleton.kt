package net.jejer.hipda.ui.designsystem.state

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.motion.HiMotion

/** 骨架屏呼吸透明度令牌（基于当前 `LocalContentColor` 叠色，自动适配浅色、深色与纯黑表面）。 */
@Immutable
object HiSkeletonTokens {
    const val MinAlpha: Float = 0.07f
    const val MaxAlpha: Float = 0.17f

    /** 减少动态效果（动画时长缩放为 0）时静止的固定中间明度。 */
    const val ReducedMotionAlpha: Float = 0.12f
}

/**
 * 为任意占位节点叠加明暗呼吸骨架效果（使用 [HiMotion.skeletonRepeatable]）。
 *
 * - 透明度读取延迟至绘制阶段，呼吸过程中不触发调用方重组；
 * - 当系统动画时长缩放为 0（减少动态效果）时，静止在固定的中间明度 [HiSkeletonTokens.ReducedMotionAlpha]，
 *   不闪烁且不残留中间帧。
 */
@Composable
fun Modifier.hiSkeleton(
    shape: Shape = HiShapeTokens.Small,
): Modifier {
    val reducedMotion = HiTheme.isReducedMotion
    val alphaState: State<Float> = if (reducedMotion) {
        remember { mutableFloatStateOf(HiSkeletonTokens.ReducedMotionAlpha) }
    } else {
        val transition = rememberInfiniteTransition(label = "hiSkeleton")
        transition.animateFloat(
            initialValue = HiSkeletonTokens.MinAlpha,
            targetValue = HiSkeletonTokens.MaxAlpha,
            animationSpec = HiMotion.skeletonRepeatable(),
            label = "hiSkeletonAlpha",
        )
    }
    val contentColor = LocalContentColor.current
    val baseColor = if (contentColor.isSpecified) contentColor else Color.Gray
    return this then HiSkeletonDrawElement(
        shape = shape,
        baseColor = baseColor,
        reducedMotion = reducedMotion,
        alphaState = alphaState,
    )
}

/**
 * 固定尺寸（或单向未指定时由 [modifier] 决定）的骨架占位块。
 */
@Composable
fun HiSkeletonBlock(
    width: Dp,
    height: Dp,
    shape: Shape = HiShapeTokens.Small,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .then(if (width.isSpecified) Modifier.width(width) else Modifier)
            .then(if (height.isSpecified) Modifier.height(height) else Modifier)
            .hiSkeleton(shape = shape),
    )
}

private data class HiSkeletonDrawElement(
    val shape: Shape,
    val baseColor: Color,
    val reducedMotion: Boolean,
    val alphaState: State<Float>,
) : ModifierNodeElement<HiSkeletonDrawNode>() {
    override fun create(): HiSkeletonDrawNode = HiSkeletonDrawNode(
        shape = shape,
        baseColor = baseColor,
        reducedMotion = reducedMotion,
        alphaState = alphaState,
    )

    override fun update(node: HiSkeletonDrawNode) {
        val changed = node.shape != shape ||
            node.baseColor != baseColor ||
            node.reducedMotion != reducedMotion ||
            node.alphaState !== alphaState
        node.shape = shape
        node.baseColor = baseColor
        node.reducedMotion = reducedMotion
        node.alphaState = alphaState
        if (changed) {
            node.invalidateDraw()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "hiSkeleton"
        properties["shape"] = shape
        properties["reducedMotion"] = reducedMotion
    }
}

private class HiSkeletonDrawNode(
    var shape: Shape,
    var baseColor: Color,
    var reducedMotion: Boolean,
    var alphaState: State<Float>,
) : Modifier.Node(), DrawModifierNode {

    private var effectMotionScale: MotionDurationScale? = null

    override fun onAttach() {
        effectMotionScale = coroutineScope.coroutineContext[MotionDurationScale]
    }

    override fun onDetach() {
        effectMotionScale = null
    }

    override fun ContentDrawScope.draw() {
        val alpha = if (reducedMotion || effectMotionScale?.scaleFactor == 0f) {
            HiSkeletonTokens.ReducedMotionAlpha
        } else {
            alphaState.value
        }
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(
            outline = outline,
            color = baseColor.copy(alpha = alpha),
        )
    }
}
