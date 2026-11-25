package net.jejer.hipda.ui.designsystem.state

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.motion.HiMotion

/** 骨架屏呼吸透明度令牌（基于 `MaterialTheme.colorScheme.onSurface` 叠色，自动适配浅色、深色与纯黑表面）。 */
@Immutable
object HiSkeletonTokens {
    const val MinAlpha: Float = 0.07f
    const val MaxAlpha: Float = 0.17f
    const val ReducedMotionAlpha: Float = 0.12f
}

/**
 * 为占位节点叠加明暗呼吸骨架效果（[HiMotion.skeletonRepeatable]）；`Outline` 按尺寸缓存，
 * 透明度在绘制阶段读取零重组；减少动态效果（缩放 0）时静止在 [HiSkeletonTokens.ReducedMotionAlpha]。
 */
@Composable
fun Modifier.hiSkeleton(shape: Shape = HiShapeTokens.Small): Modifier {
    val alphaState: State<Float> = if (HiTheme.isReducedMotion) {
        remember { mutableFloatStateOf(HiSkeletonTokens.ReducedMotionAlpha) }
    } else {
        rememberInfiniteTransition(label = "hiSkeleton").animateFloat(
            initialValue = HiSkeletonTokens.MinAlpha,
            targetValue = HiSkeletonTokens.MaxAlpha,
            animationSpec = HiMotion.skeletonRepeatable(),
            label = "hiSkeletonAlpha",
        )
    }
    val baseColor = MaterialTheme.colorScheme.onSurface
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawWithContent {
            drawOutline(outline = outline, color = baseColor.copy(alpha = alphaState.value))
        }
    }
}

/** 固定尺寸（或单向未指定时由 [modifier] 决定）的骨架占位块。 */
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
