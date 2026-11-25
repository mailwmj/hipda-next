package net.jejer.hipda.ui.designsystem.feedback

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut
import net.jejer.hipda.ui.designsystem.motion.hiScaleIn
import net.jejer.hipda.ui.designsystem.motion.hiScaleOut

private const val ICON_SWITCH_INITIAL_SCALE: Float = 0.5f
private const val CHECK_PROGRESS_VISIBILITY_THRESHOLD: Float = 0.001f

/** 仅供包内单测校验描线对勾当前进度（0f..1f）。 */
internal val HiStrokeCheckProgressKey: SemanticsPropertyKey<Float> =
    SemanticsPropertyKey("HiStrokeCheckProgress")

/**
 * 图标切换组件（对齐 `product-design.md` §6.3）：
 * 旧图标缩小并淡出，新图标放大并淡入，同时进行（[HiMotion.spatialFast] + [HiMotion.effectsFast]；
 * 退场采用 [HiMotion.exit] 换算令牌）。
 */
@Composable
fun HiIconSwitch(
    target: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    iconSize: Dp = 24.dp,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = target,
            transitionSpec = {
                val enter = hiScaleIn(
                    animationSpec = HiMotion.spatialFast(),
                    initialScale = ICON_SWITCH_INITIAL_SCALE,
                    transformOrigin = TransformOrigin.Center,
                ) + hiFadeIn(animationSpec = HiMotion.effectsFast())
                val exit = hiScaleOut(
                    animationSpec = HiMotion.exit(HiMotion.spatialFast()),
                    targetScale = ICON_SWITCH_INITIAL_SCALE,
                    transformOrigin = TransformOrigin.Center,
                ) + hiFadeOut(animationSpec = HiMotion.exit(HiMotion.effectsFast()))
                (enter togetherWith exit).using(null)
            },
            contentAlignment = Alignment.Center,
            label = "HiIconSwitch",
        ) { icon ->
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * 路径描线对勾（对齐 `product-design.md` §6.3）：
 * 使用 [PathMeasure] 按进度截取对勾路径并画出，进度由 [HiMotion.spatialFast] 驱动（退场用 [HiMotion.exit]）。
 */
@Composable
fun HiStrokeCheck(
    visible: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 2.5.dp,
    size: Dp = 24.dp,
    contentDescription: String? = null,
) {
    val progressState = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible) {
            HiMotion.spatialFast(visibilityThreshold = CHECK_PROGRESS_VISIBILITY_THRESHOLD)
        } else {
            HiMotion.exit(HiMotion.spatialFast(visibilityThreshold = CHECK_PROGRESS_VISIBILITY_THRESHOLD))
        },
        label = "HiStrokeCheckProgress",
    )

    val fullPath = remember { Path() }
    val drawnPath = remember { Path() }
    val pathMeasure = remember { PathMeasure() }

    Canvas(
        modifier = modifier
            .size(size)
            .semantics {
                this[HiStrokeCheckProgressKey] = progressState.value.coerceIn(0f, 1f)
                if (contentDescription != null && visible) {
                    this.contentDescription = contentDescription
                }
            },
    ) {
        val progress = progressState.value.coerceIn(0f, 1f)
        if (progress <= 0f) return@Canvas

        val side = this.size.minDimension
        val offsetX = (this.size.width - side) / 2f
        val offsetY = (this.size.height - side) / 2f

        fullPath.reset()
        fullPath.moveTo(offsetX + side * 0.20f, offsetY + side * 0.53f)
        fullPath.lineTo(offsetX + side * 0.42f, offsetY + side * 0.74f)
        fullPath.lineTo(offsetX + side * 0.80f, offsetY + side * 0.28f)

        pathMeasure.setPath(fullPath, forceClosed = false)
        drawnPath.reset()
        pathMeasure.getSegment(
            startDistance = 0f,
            stopDistance = pathMeasure.length * progress,
            destination = drawnPath,
            startWithMoveTo = true,
        )

        drawPath(
            path = drawnPath,
            color = color,
            style = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
