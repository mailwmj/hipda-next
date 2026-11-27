package net.jejer.hipda.feature.imageviewer

import android.content.Context
import android.graphics.drawable.Animatable
import androidx.compose.animation.core.Animatable as ComposeAnimatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.DrawableImage
import coil3.Image as CoilImage
import coil3.ImageLoader
import coil3.compose.asPainter
import coil3.decode.DataSource
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.currentHiImageLoader
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.rememberHiMotionCoroutineScope
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner

/** 活跃页的共享边界进退 / 下拉关闭几何快照，经 `graphicsLayer` 零重排渲染。 */
@Stable
internal data class ActiveTransitionFrame(val active: Boolean, val center: Offset, val size: Size, val contentAlpha: Float)

private enum class PageGestureMode { Undecided, Pinch, ZoomPan, LongScroll, DismissDrag, PassToPager }
private enum class PageLoadStatus { InvalidUrl, Loading, Ready, Failed }

private class PageLiveRefs {
    var restRect = Rect.Zero
    var baseSize = Size.Zero
    var viewportSize = Size.Zero
    var isLong = false
    var maxLongScrollY = 0f
    var reducedMotion = false
    var safeUrl: String? = null
    var transitionActive = false
    var onDecoded: (IntSize, Boolean) -> Unit = { _, _ -> }
    var onOriginalDecodeResult: (Boolean, IntSize?, Boolean) -> Unit = { _, _, _ -> }
    var onZoomChanged: (Float, Offset) -> Unit = { _, _ -> }
    var onToggleChrome: () -> Unit = {}
    var onLongPress: () -> Unit = {}
    var onDragStart: () -> Unit = {}
    var onDragUpdate: (Offset) -> Unit = {}
    var onDragEnd: (Offset, Offset) -> Unit = { _, _ -> }
}

private suspend fun executeBoundedImage(
    context: Context,
    imageLoader: ImageLoader,
    data: Any,
    maxWidthPx: Int,
    maxHeightPx: Int,
): SuccessResult? {
    val req = ImageRequest.Builder(context)
        .data(data)
        .size(Dimension.Pixels(maxWidthPx.coerceAtLeast(1)), Dimension.Pixels(maxHeightPx.coerceAtLeast(1)))
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .build()
    return try {
        imageLoader.execute(req) as? SuccessResult
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }
}

@Composable
internal fun ImageViewerPage(
    pageIndex: Int,
    rawUrl: String?,
    rawThumbnailUrl: String?,
    pendingOriginal: OriginalDownloadState.Completed?,
    isCurrentPage: Boolean,
    isGifPlaying: Boolean,
    viewportSize: Size,
    fallbackBounds: Rect?,
    transitionFrame: ActiveTransitionFrame,
    imageContentDescription: String,
    onImageDecoded: (IntSize, Boolean) -> Unit,
    onOriginalDecodeResult: (success: Boolean, size: IntSize?, isAnimatable: Boolean) -> Unit,
    onZoomStateChanged: (scale: Float, pan: Offset) -> Unit,
    onToggleChrome: () -> Unit,
    onLongPress: () -> Unit,
    onDismissDragStart: () -> Unit,
    onDismissDragUpdate: (Offset) -> Unit,
    onDismissDragEnd: (Offset, Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeUrl = remember(rawUrl) { sanitizeViewerImageUrl(rawUrl) }
    val safeThumbUrl = remember(rawThumbnailUrl) { sanitizeViewerImageUrl(rawThumbnailUrl) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val imageLoader = currentHiImageLoader()
    val reducedMotion = HiTheme.isReducedMotion
    val motionScope = rememberHiMotionCoroutineScope()

    var retryToken by remember(safeUrl) { mutableIntStateOf(0) }
    var thumbImage by remember(safeUrl, safeThumbUrl) {
        mutableStateOf(
            safeThumbUrl?.let { imageLoader.memoryCache?.get(MemoryCache.Key(it))?.image }
                ?: safeUrl?.let { imageLoader.memoryCache?.get(MemoryCache.Key(it))?.image },
        )
    }
    var hdImage by remember(safeUrl) { mutableStateOf<CoilImage?>(null) }
    var originalImage by remember(safeUrl) { mutableStateOf<CoilImage?>(null) }
    var hdInstant by remember(safeUrl) { mutableStateOf(false) }
    var loadStatus by remember(safeUrl) { mutableStateOf(if (safeUrl == null) PageLoadStatus.InvalidUrl else PageLoadStatus.Loading) }

    val refs = remember { PageLiveRefs() }
    val viewportW = viewportSize.width.roundToInt().coerceAtLeast(1)
    val viewportH = viewportSize.height.roundToInt().coerceAtLeast(1)

    val activeImage = originalImage ?: hdImage ?: thumbImage
    val decodedW = activeImage?.width ?: 0
    val decodedH = activeImage?.height ?: 0
    val isLong = remember(decodedW, decodedH) { isLongImage(decodedW, decodedH) }
    val restRect = remember(viewportSize, decodedW, decodedH, fallbackBounds) {
        computeRestImageRect(viewportSize, decodedW, decodedH, fallbackBounds)
    }
    val longDisplayHeightPx = if (isLong && decodedW > 0) viewportSize.width * (decodedH.toFloat() / decodedW.toFloat()) else restRect.height
    val maxLongScrollY = (longDisplayHeightPx - viewportSize.height).coerceAtLeast(0f)
    val baseContentSize = if (isLong) Size(viewportSize.width, longDisplayHeightPx) else restRect.size

    refs.apply {
        this.restRect = restRect; baseSize = baseContentSize; this.viewportSize = viewportSize; this.isLong = isLong
        this.maxLongScrollY = maxLongScrollY; this.reducedMotion = reducedMotion; this.safeUrl = safeUrl
        transitionActive = transitionFrame.active; onDecoded = onImageDecoded; this.onOriginalDecodeResult = onOriginalDecodeResult
        onZoomChanged = onZoomStateChanged; this.onToggleChrome = onToggleChrome; this.onLongPress = onLongPress
        onDragStart = onDismissDragStart; onDragUpdate = onDismissDragUpdate; onDragEnd = onDismissDragEnd
    }

    LaunchedEffect(safeThumbUrl, safeUrl, imageLoader) {
        if (safeUrl == null || safeThumbUrl == null || safeThumbUrl == safeUrl || thumbImage != null) return@LaunchedEffect
        val thumbBounds = computeOriginalDecodeBounds(viewportW, viewportH, widthMultiplier = 1, heightMultiplier = 1)
        val res = executeBoundedImage(context, imageLoader, safeThumbUrl, thumbBounds.width, thumbBounds.height) ?: return@LaunchedEffect
        thumbImage = res.image
        if (hdImage == null && res.image.width > 0 && res.image.height > 0) {
            refs.onDecoded(IntSize(res.image.width, res.image.height), (res.image as? DrawableImage)?.drawable is Animatable)
        }
    }

    LaunchedEffect(safeUrl, retryToken, imageLoader) {
        if (safeUrl == null) { loadStatus = PageLoadStatus.InvalidUrl; return@LaunchedEffect }
        loadStatus = PageLoadStatus.Loading
        val hdBounds = computeOriginalDecodeBounds(
            viewportW, viewportH, widthMultiplier = 1, heightMultiplier = 1, minHeightWidthMultiplier = 6,
        )
        val res = executeBoundedImage(context, imageLoader, safeUrl, hdBounds.width, hdBounds.height)
        if (res != null) {
            hdImage = res.image
            hdInstant = reducedMotion || (thumbImage == null && res.dataSource == DataSource.MEMORY_CACHE)
            loadStatus = PageLoadStatus.Ready
            refs.onDecoded(IntSize(res.image.width, res.image.height), (res.image as? DrawableImage)?.drawable is Animatable)
        } else {
            loadStatus = if (thumbImage != null) PageLoadStatus.Ready else PageLoadStatus.Failed
        }
    }

    // 超大原图经 Coil 按屏幕尺寸 2×（硬封顶 4096px）限尺寸解码，防止 Canvas too large 崩溃（R-E14 S1）
    LaunchedEffect(pendingOriginal, imageLoader) {
        val orig = pendingOriginal ?: return@LaunchedEffect
        if (safeUrl == null) { refs.onOriginalDecodeResult(false, null, false); return@LaunchedEffect }
        val maxBounds = computeOriginalDecodeBounds(viewportW, viewportH)
        val res = executeBoundedImage(context, imageLoader, orig.file, maxBounds.width, maxBounds.height)
        if (res != null) {
            originalImage = res.image
            val isAnim = orig.mimeType.equals("image/gif", true) || (res.image as? DrawableImage)?.drawable is Animatable
            refs.onOriginalDecodeResult(true, IntSize(res.image.width, res.image.height), isAnim)
        } else {
            refs.onOriginalDecodeResult(false, null, false)
        }
    }

    LaunchedEffect(activeImage, isGifPlaying, isCurrentPage) {
        val anim = (activeImage as? DrawableImage)?.drawable as? Animatable ?: return@LaunchedEffect
        if (isCurrentPage && isGifPlaying) anim.start() else anim.stop()
    }

    // 单一缩放/平移状态源（R-E14 S4）：手势期 UNDISPATCHED snapTo，双击期 spatialDefault() animateTo
    val zoomScaleAnim = remember(safeUrl, pageIndex) { ComposeAnimatable(1f) }
    val panAnim = remember(safeUrl, pageIndex) { ComposeAnimatable(Offset.Zero, Offset.VectorConverter) }
    var zoomAnimJob by remember(safeUrl, pageIndex) { mutableStateOf<Job?>(null) }
    var longScrollY by remember(safeUrl, pageIndex) { mutableFloatStateOf(0f) }

    // 旋转/尺寸变化时同步钳制长图滚动与放大平移，消除越界留黑（R-E14 S3）
    LaunchedEffect(maxLongScrollY) { longScrollY = longScrollY.coerceIn(0f, maxLongScrollY) }
    LaunchedEffect(viewportSize, baseContentSize) {
        val clamped = clampPanOffset(panAnim.value, zoomScaleAnim.value, baseContentSize, viewportSize)
        if (clamped != panAnim.value) panAnim.snapTo(clamped)
    }
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage && (zoomScaleAnim.value > 1f || panAnim.value != Offset.Zero)) {
            zoomAnimJob?.cancel(); zoomScaleAnim.snapTo(1f); panAnim.snapTo(Offset.Zero)
        }
    }
    LaunchedEffect(zoomScaleAnim.value, panAnim.value, isCurrentPage) {
        if (isCurrentPage) refs.onZoomChanged(zoomScaleAnim.value, panAnim.value)
    }

    val hdAlpha by animateFloatAsState(if (hdImage != null) 1f else 0f, HiMotion.effectsFast(), label = "viewerHdFadeIn")
    val originalAlpha by animateFloatAsState(if (originalImage != null) 1f else 0f, HiMotion.effectsFast(), label = "viewerOrigFadeIn")

    fun snapZoomPanSync(scale: Float, pan: Offset) {
        zoomAnimJob?.cancel()
        motionScope.launch(start = CoroutineStart.UNDISPATCHED) { zoomScaleAnim.snapTo(scale); panAnim.snapTo(pan) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(pageIndex, safeUrl) {
                detectTapGestures(
                    onTap = { refs.onToggleChrome() },
                    onLongPress = { if (refs.safeUrl != null) refs.onLongPress() },
                    onDoubleTap = { tapOffset ->
                        if (refs.safeUrl == null || refs.transitionActive) return@detectTapGestures
                        val (targetScale, targetPan) = computeDoubleTapZoomTarget(
                            zoomScaleAnim.value, tapOffset, refs.restRect.center, refs.baseSize, refs.viewportSize,
                        )
                        zoomAnimJob?.cancel()
                        zoomAnimJob = motionScope.launch {
                            if (refs.reducedMotion) {
                                zoomScaleAnim.snapTo(targetScale); panAnim.snapTo(targetPan)
                            } else {
                                launch { zoomScaleAnim.animateTo(targetScale, HiMotion.spatialDefault()) }
                                launch { panAnim.animateTo(targetPan, HiMotion.spatialDefault()) }
                            }
                        }
                    },
                )
            }
            .pointerInput(pageIndex, safeUrl) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    var mode = PageGestureMode.Undecided
                    var totalDx = 0f; var totalDy = 0f
                    var dragOffset = Offset.Zero
                    var dismissDragStarted = false
                    val velocityTracker = VelocityTracker().apply { addPosition(firstDown.uptimeMillis, firstDown.position) }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (dismissDragStarted) { refs.onDragEnd(Offset.Zero, Offset.Zero); dismissDragStarted = false }
                            mode = PageGestureMode.Pinch
                            val z = event.calculateZoom(); val p = event.calculatePan()
                            if (z != 1f || p != Offset.Zero) {
                                val (ns, np) = computePinchTransform(
                                    zoomScaleAnim.value, panAnim.value, z, p, event.calculateCentroid(true),
                                    refs.restRect.center, refs.baseSize, refs.viewportSize,
                                )
                                snapZoomPanSync(ns, np)
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (mode == PageGestureMode.Pinch) { event.changes.forEach { it.consume() }; continue }

                        val change = pressed.first()
                        val delta = change.position - change.previousPosition
                        if (delta == Offset.Zero) continue
                        totalDx += delta.x; totalDy += delta.y

                        if (mode == PageGestureMode.Undecided && hypot(totalDx, totalDy) > touchSlop) {
                            val curScale = zoomScaleAnim.value
                            mode = when {
                                curScale > ImageViewerMotionSpec.ZOOMED_IN_THRESHOLD ->
                                    if (shouldYieldHorizontalEdgeToPager(panAnim.value.x, refs.baseSize.width, curScale, refs.viewportSize.width, totalDx, totalDy)) {
                                        PageGestureMode.PassToPager
                                    } else PageGestureMode.ZoomPan
                                abs(totalDx) >= abs(totalDy) -> PageGestureMode.PassToPager
                                refs.isLong && (longScrollY > 1f || totalDy < 0f) -> PageGestureMode.LongScroll
                                totalDy > 0f -> { dismissDragStarted = true; refs.onDragStart(); PageGestureMode.DismissDrag }
                                else -> PageGestureMode.PassToPager
                            }
                        }

                        when (mode) {
                            PageGestureMode.ZoomPan -> {
                                val curScale = zoomScaleAnim.value
                                if (!shouldYieldHorizontalEdgeToPager(panAnim.value.x, refs.baseSize.width, curScale, refs.viewportSize.width, delta.x, delta.y)) {
                                    snapZoomPanSync(curScale, clampPanOffset(panAnim.value + delta, curScale, refs.baseSize, refs.viewportSize))
                                    change.consume()
                                }
                            }
                            PageGestureMode.LongScroll -> {
                                longScrollY = (longScrollY - delta.y).coerceIn(0f, refs.maxLongScrollY)
                                change.consume()
                            }
                            PageGestureMode.DismissDrag -> {
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                dragOffset = Offset(totalDx, totalDy)
                                refs.onDragUpdate(dragOffset)
                                change.consume()
                            }
                            else -> Unit
                        }
                    }

                    if (dismissDragStarted) {
                        val vel = velocityTracker.calculateVelocity()
                        refs.onDragEnd(dragOffset, Offset(vel.x, vel.y))
                    } else if (mode == PageGestureMode.Pinch && zoomScaleAnim.value < ImageViewerMotionSpec.ZOOMED_IN_THRESHOLD) {
                        snapZoomPanSync(1f, Offset.Zero)
                    }
                }
            },
    ) {
        when (loadStatus) {
            PageLoadStatus.InvalidUrl -> FailedViewerContent(false) {}
            PageLoadStatus.Failed -> FailedViewerContent(true) { retryToken++ }
            PageLoadStatus.Loading, PageLoadStatus.Ready -> {
                if (loadStatus == PageLoadStatus.Loading && activeImage == null && !transitionFrame.active) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        HiLoadingSpinner(Modifier.size(36.dp), 3.dp, ImageViewerPalette.AccentProgress, ImageViewerPalette.ProgressTrack)
                    }
                } else {
                    val useTransition = isCurrentPage && transitionFrame.active
                    val sx = if (useTransition) (transitionFrame.size.width / restRect.width.coerceAtLeast(1f)).coerceAtLeast(0.01f) else 1f
                    val sy = if (useTransition) (transitionFrame.size.height / restRect.height.coerceAtLeast(1f)).coerceAtLeast(0.01f) else 1f
                    val cover = max(sx, sy)
                    val compX = if (useTransition) cover / sx else 1f
                    val compY = if (useTransition) cover / sy else 1f
                    val safeScrollY = longScrollY.coerceIn(0f, maxLongScrollY)

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(restRect.left.roundToInt(), restRect.top.roundToInt()) }
                            .size(with(density) { restRect.width.toDp() }, with(density) { restRect.height.toDp() })
                            .graphicsLayer {
                                if (useTransition) {
                                    scaleX = sx; scaleY = sy
                                    translationX = transitionFrame.center.x - restRect.center.x
                                    translationY = transitionFrame.center.y - restRect.center.y
                                    alpha = transitionFrame.contentAlpha; clip = true
                                } else {
                                    val s = zoomScaleAnim.value
                                    val clamped = clampPanOffset(panAnim.value, s, baseContentSize, viewportSize)
                                    scaleX = s; scaleY = s; translationX = clamped.x; translationY = clamped.y
                                    alpha = 1f; clip = isLong
                                }
                            }
                            .semantics { role = Role.Image; contentDescription = imageContentDescription },
                    ) {
                        val imgMod = if (isLong) {
                            Modifier.align(Alignment.TopCenter).wrapContentSize(Alignment.TopCenter, unbounded = true)
                                .size(with(density) { viewportSize.width.toDp() }, with(density) { longDisplayHeightPx.toDp() })
                                .graphicsLayer { translationY = -safeScrollY; scaleX = compX; scaleY = compY }
                        } else {
                            Modifier.fillMaxSize().graphicsLayer { scaleX = compX; scaleY = compY }
                        }
                        val scale = if (isLong) ContentScale.FillWidth else ContentScale.Fit
                        val align = if (isLong) Alignment.TopCenter else Alignment.Center
                        thumbImage?.let { ViewerImageLayer(it, context, imgMod, align, scale, 1f) }
                        hdImage?.let { ViewerImageLayer(it, context, imgMod, align, scale, if (hdInstant) 1f else hdAlpha) }
                        originalImage?.let { ViewerImageLayer(it, context, imgMod, align, scale, if (reducedMotion) 1f else originalAlpha) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerImageLayer(
    image: CoilImage,
    context: Context,
    baseModifier: Modifier,
    alignment: Alignment,
    contentScale: ContentScale,
    alphaValue: Float,
) {
    val mod = if (alphaValue < 1f) baseModifier.graphicsLayer { alpha = alphaValue } else baseModifier
    Image(remember(image, context) { image.asPainter(context) }, null, mod, alignment, contentScale)
}

@Composable
private fun FailedViewerContent(canRetry: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(stringResource(R.string.image_viewer_load_failed), style = MaterialTheme.typography.bodyLarge, color = ImageViewerPalette.ForegroundPrimary)
        if (canRetry) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onRetry, Modifier.defaultMinSize(88.dp, 48.dp), shape = HiShapeTokens.Small) {
                Text(stringResource(R.string.image_viewer_action_retry))
            }
        }
    }
}
