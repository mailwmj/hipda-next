package net.jejer.hipda.ui.designsystem.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** 带 [HiMotion.effectsDefault] 默认令牌的淡入过渡。 */
fun hiFadeIn(
    animationSpec: FiniteAnimationSpec<Float> = HiMotion.effectsDefault(),
    initialAlpha: Float = 0f,
): EnterTransition = fadeIn(
    animationSpec = animationSpec,
    initialAlpha = initialAlpha,
)

/** 带 `HiMotion.exit(HiMotion.effectsDefault())` 默认令牌的淡出过渡。 */
fun hiFadeOut(
    animationSpec: FiniteAnimationSpec<Float> = HiMotion.exit(HiMotion.effectsDefault()),
    targetAlpha: Float = 0f,
): ExitTransition = fadeOut(
    animationSpec = animationSpec,
    targetAlpha = targetAlpha,
)

/** 带 [HiMotion.spatialDefault] 默认令牌的水平滑入过渡。 */
fun hiSlideInHorizontally(
    animationSpec: FiniteAnimationSpec<IntOffset> = HiMotion.spatialDefault(IntOffset.VisibilityThreshold),
    initialOffsetX: (fullWidth: Int) -> Int = { -it / 2 },
): EnterTransition = slideInHorizontally(
    animationSpec = animationSpec,
    initialOffsetX = initialOffsetX,
)

/** 带 `HiMotion.exit(HiMotion.spatialDefault())` 默认令牌的水平滑出过渡。 */
fun hiSlideOutHorizontally(
    animationSpec: FiniteAnimationSpec<IntOffset> = HiMotion.exit(
        HiMotion.spatialDefault(IntOffset.VisibilityThreshold),
    ),
    targetOffsetX: (fullWidth: Int) -> Int = { -it / 2 },
): ExitTransition = slideOutHorizontally(
    animationSpec = animationSpec,
    targetOffsetX = targetOffsetX,
)

/** 带 [HiMotion.spatialDefault] 默认令牌的放大入场过渡。 */
fun hiScaleIn(
    animationSpec: FiniteAnimationSpec<Float> = HiMotion.spatialDefault(),
    initialScale: Float = 0f,
    transformOrigin: TransformOrigin = TransformOrigin.Center,
): EnterTransition = scaleIn(
    animationSpec = animationSpec,
    initialScale = initialScale,
    transformOrigin = transformOrigin,
)

/** 带 `HiMotion.exit(HiMotion.spatialDefault())` 默认令牌的缩小退场过渡。 */
fun hiScaleOut(
    animationSpec: FiniteAnimationSpec<Float> = HiMotion.exit(HiMotion.spatialDefault()),
    targetScale: Float = 0f,
    transformOrigin: TransformOrigin = TransformOrigin.Center,
): ExitTransition = scaleOut(
    animationSpec = animationSpec,
    targetScale = targetScale,
    transformOrigin = transformOrigin,
)

/** 带 [HiMotion.spatialDefault] 默认令牌的纵向展开过渡。 */
fun hiExpandVertically(
    animationSpec: FiniteAnimationSpec<IntSize> = HiMotion.spatialDefault(IntSize.VisibilityThreshold),
    expandFrom: Alignment.Vertical = Alignment.Bottom,
    clip: Boolean = true,
    initialHeight: (fullHeight: Int) -> Int = { 0 },
): EnterTransition = expandVertically(
    animationSpec = animationSpec,
    expandFrom = expandFrom,
    clip = clip,
    initialHeight = initialHeight,
)

/** 带 `HiMotion.exit(HiMotion.spatialDefault())` 默认令牌的纵向收起过渡。 */
fun hiShrinkVertically(
    animationSpec: FiniteAnimationSpec<IntSize> = HiMotion.exit(
        HiMotion.spatialDefault(IntSize.VisibilityThreshold),
    ),
    shrinkTowards: Alignment.Vertical = Alignment.Bottom,
    clip: Boolean = true,
    targetHeight: (fullHeight: Int) -> Int = { 0 },
): ExitTransition = shrinkVertically(
    animationSpec = animationSpec,
    shrinkTowards = shrinkTowards,
    clip = clip,
    targetHeight = targetHeight,
)

/** 带 [HiMotion.spatialDefault] 默认令牌的内容尺寸变化修饰符。 */
fun Modifier.hiAnimateContentSize(
    animationSpec: FiniteAnimationSpec<IntSize> = HiMotion.spatialDefault(IntSize.VisibilityThreshold),
    alignment: Alignment = Alignment.TopStart,
    finishedListener: ((initialValue: IntSize, targetValue: IntSize) -> Unit)? = null,
): Modifier = animateContentSize(
    animationSpec = animationSpec,
    alignment = alignment,
    finishedListener = finishedListener,
)
