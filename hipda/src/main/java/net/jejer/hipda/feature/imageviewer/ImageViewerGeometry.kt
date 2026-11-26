package net.jejer.hipda.feature.imageviewer

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs

/** 看图手势与几何常量（对齐 `docs/design/product-design.md` §6.3 与 E14 卡片）。 */
object ImageViewerMotionSpec {
    const val MAX_DRAG_DISMISS_DP: Float = 240f
    const val DISMISS_DISTANCE_THRESHOLD_DP: Float = 120f
    const val DISMISS_VELOCITY_THRESHOLD_DP_PER_SEC: Float = 800f
    const val MIN_DRAG_SCALE: Float = 0.8f
    const val MIN_DRAG_SCRIM_ALPHA: Float = 0.3f
    const val FALLBACK_EXIT_SCALE: Float = 0.85f
    const val DOUBLE_TAP_ZOOM_SCALE: Float = 2.5f
    const val MAX_PINCH_ZOOM_SCALE: Float = 5.0f
    const val ZOOMED_IN_THRESHOLD: Float = 1.01f
    const val EDGE_EPSILON_PX: Float = 1.5f
}

/** 下拉关闭跟手阶段的瞬时缩放与背景遮罩不透明度。 */
@Immutable
data class DragDismissMetrics(val scale: Float, val scrimAlpha: Float)

/** 下拉 `0 → 240dp` 期间：缩放线性 `1.0f → 0.8f`，遮罩线性 `1.0f → 0.3f`；超过 `240dp` 钳制下限。 */
fun computeDragDismissMetrics(dragOffsetYPx: Float, maxDragPx: Float): DragDismissMetrics {
    if (maxDragPx <= 0f) return DragDismissMetrics(scale = 1f, scrimAlpha = 1f)
    val fraction = (dragOffsetYPx.coerceAtLeast(0f) / maxDragPx).coerceIn(0f, 1f)
    return DragDismissMetrics(
        scale = (1f - (1f - ImageViewerMotionSpec.MIN_DRAG_SCALE) * fraction).coerceIn(ImageViewerMotionSpec.MIN_DRAG_SCALE, 1f),
        scrimAlpha = (1f - (1f - ImageViewerMotionSpec.MIN_DRAG_SCRIM_ALPHA) * fraction).coerceIn(ImageViewerMotionSpec.MIN_DRAG_SCRIM_ALPHA, 1f),
    )
}

/** 下拉松手判定：位移严格超过 `120dp` 或向下速度严格超过 `800dp/s` 时关闭，否则弹回。 */
fun shouldDismissOnRelease(
    dragOffsetYPx: Float,
    velocityYPxPerSec: Float,
    dismissDistancePx: Float,
    dismissVelocityPxPerSec: Float,
): Boolean = dragOffsetYPx > dismissDistancePx || velocityYPxPerSec > dismissVelocityPxPerSec

/** 计算静止态图片在全屏容器内的展示边界（长图贴宽满视口，常规图按比例居中 Fit）。 */
fun computeRestImageRect(
    containerSize: Size,
    imageWidth: Int,
    imageHeight: Int,
    fallbackBounds: Rect? = null,
): Rect {
    val cw = containerSize.width
    val ch = containerSize.height
    if (cw <= 0f || ch <= 0f) return Rect.Zero
    if (isLongImage(imageWidth, imageHeight)) return Rect(0f, 0f, cw, ch)
    val aspectRatio = when {
        imageWidth > 0 && imageHeight > 0 -> imageWidth.toFloat() / imageHeight.toFloat()
        fallbackBounds != null && fallbackBounds.width > 0f && fallbackBounds.height > 0f ->
            fallbackBounds.width / fallbackBounds.height
        else -> return Rect(0f, 0f, cw, ch)
    }
    val (fitW, fitH) = if (cw / ch > aspectRatio) (ch * aspectRatio to ch) else (cw to cw / aspectRatio)
    val left = (cw - fitW) * 0.5f
    val top = (ch - fitH) * 0.5f
    return Rect(left, top, left + fitW, top + fitH)
}

/** 钳制给定缩放倍数下的平移边界，避免露出黑边。 */
fun clampPanOffset(offset: Offset, scale: Float, baseContentSize: Size, viewportSize: Size): Offset {
    val maxPanX = ((baseContentSize.width * scale - viewportSize.width) * 0.5f).coerceAtLeast(0f)
    val maxPanY = ((baseContentSize.height * scale - viewportSize.height) * 0.5f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxPanX, maxPanX), offset.y.coerceIn(-maxPanY, maxPanY))
}

/** 双击在 `1.0f ↔ 2.5f` 间围绕 [tapOffset] 平滑切换并钳制边界平移。 */
fun computeDoubleTapZoomTarget(
    currentScale: Float,
    tapOffset: Offset,
    contentCenter: Offset,
    baseContentSize: Size,
    viewportSize: Size,
): Pair<Float, Offset> {
    if (currentScale > ImageViewerMotionSpec.ZOOMED_IN_THRESHOLD) return 1f to Offset.Zero
    val targetScale = ImageViewerMotionSpec.DOUBLE_TAP_ZOOM_SCALE
    val desiredPan = (tapOffset - contentCenter) * (1f - targetScale)
    return targetScale to clampPanOffset(desiredPan, targetScale, baseContentSize, viewportSize)
}

/** 计算双指捏合围绕 [centroid] 的新 `(scale, pan)` 并钳制边界。 */
fun computePinchTransform(
    currentScale: Float,
    currentPan: Offset,
    zoomChange: Float,
    panChange: Offset,
    centroid: Offset,
    contentCenter: Offset,
    baseContentSize: Size,
    viewportSize: Size,
): Pair<Float, Offset> {
    val newScale = (currentScale * zoomChange).coerceIn(1f, ImageViewerMotionSpec.MAX_PINCH_ZOOM_SCALE)
    if (newScale <= 1f) return 1f to Offset.Zero
    val ratio = newScale / currentScale.coerceAtLeast(0.01f)
    val c = centroid - contentCenter
    val rawPan = (currentPan - c) * ratio + c + panChange
    return newScale to clampPanOffset(rawPan, newScale, baseContentSize, viewportSize)
}

/** 放大态水平拖拽是否已抵边缘、应交还 `HorizontalPager` 翻页。 */
fun shouldYieldHorizontalEdgeToPager(
    panX: Float,
    baseContentWidth: Float,
    scale: Float,
    viewportWidth: Float,
    dragDeltaX: Float,
    dragDeltaY: Float,
): Boolean {
    if (abs(dragDeltaX) <= abs(dragDeltaY)) return false
    val maxPanX = ((baseContentWidth * scale - viewportWidth) * 0.5f).coerceAtLeast(0f)
    if (maxPanX <= ImageViewerMotionSpec.EDGE_EPSILON_PX) return true
    return (dragDeltaX > 0f && panX >= maxPanX - ImageViewerMotionSpec.EDGE_EPSILON_PX) ||
        (dragDeltaX < 0f && panX <= -maxPanX + ImageViewerMotionSpec.EDGE_EPSILON_PX)
}
