package net.jejer.hipda.ui.designsystem.feedback

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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
private const val CHECK_PROGRESS_THRESHOLD: Float = 0.001f

/** 图标切换（§6.3）：旧图标缩小淡出、新图标放大淡入同时进行（[HiMotion.spatialFast] + [HiMotion.effectsFast]）。 */
@Composable
fun HiIconSwitch(
    target: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    iconSize: Dp = 24.dp,
) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            val enter = hiScaleIn(HiMotion.spatialFast(), ICON_SWITCH_INITIAL_SCALE, TransformOrigin.Center) +
                hiFadeIn(HiMotion.effectsFast())
            val exit = hiScaleOut(HiMotion.exit(HiMotion.spatialFast()), ICON_SWITCH_INITIAL_SCALE, TransformOrigin.Center) +
                hiFadeOut(HiMotion.exit(HiMotion.effectsFast()))
            (enter togetherWith exit).using(null)
        },
        contentAlignment = Alignment.Center,
        label = "HiIconSwitch",
    ) { icon ->
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** 路径描线对勾（§6.3）：`drawWithCache` 缓存 [Path]/[PathMeasure]，绘制阶段按 [HiMotion.spatialFast] 进度截取。 */
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
        animationSpec = if (visible) HiMotion.spatialFast(CHECK_PROGRESS_THRESHOLD)
        else HiMotion.exit(HiMotion.spatialFast(CHECK_PROGRESS_THRESHOLD)),
        label = "HiStrokeCheckProgress",
    )
    val semanticsModifier = if (contentDescription != null && visible) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else Modifier

    Spacer(
        modifier = modifier
            .size(size)
            .then(semanticsModifier)
            .drawWithCache {
                val side = this.size.minDimension
                val ox = (this.size.width - side) / 2f
                val oy = (this.size.height - side) / 2f
                val fullPath = Path().apply {
                    moveTo(ox + side * 0.20f, oy + side * 0.53f)
                    lineTo(ox + side * 0.42f, oy + side * 0.74f)
                    lineTo(ox + side * 0.80f, oy + side * 0.28f)
                }
                val pathMeasure = PathMeasure().apply { setPath(fullPath, forceClosed = false) }
                val totalLength = pathMeasure.length
                val drawnPath = Path()
                val stroke = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind {
                    val progress = progressState.value.coerceIn(0f, 1f)
                    if (progress <= 0f) return@onDrawBehind
                    drawnPath.reset()
                    pathMeasure.getSegment(0f, totalLength * progress, drawnPath, startWithMoveTo = true)
                    drawPath(path = drawnPath, color = color, style = stroke)
                }
            },
    )
}
