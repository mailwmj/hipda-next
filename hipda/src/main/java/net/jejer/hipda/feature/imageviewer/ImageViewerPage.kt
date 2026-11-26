package net.jejer.hipda.feature.imageviewer

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
import coil3.compose.asPainter
import coil3.decode.DataSource
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.*
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
internal data class ActiveTransitionFrame(
    val active: Boolean,
    val center: Offset,
    val size: Size,
    val contentAlpha: Float,
)

private enum class PageGestureMode { Undecided, Pinch, ZoomPan, LongScroll, DismissDrag, PassToPager }

private sealed interface PageLoadStatus {
    data object InvalidUrl : PageLoadStatus
    data object Loading : PageLoadStatus
    data object Ready : PageLoadStatus
    data object Failed : PageLoadStatus
}

@Composable
internal fun ImageViewerPage(
    pageIndex: Int,
    rawUrl: String?,
    rawThumbnailUrl: String?,
    originalImage: CoilImage?,
    isCurrentPage: Boolean,
    isGifPlaying: Boolean,
    viewportSize: Size,
    fallbackBounds: Rect?,
    transitionFrame: ActiveTransitionFrame,
    imageContentDescription: String,
    onImageDecoded: (IntSize, Boolean) -> Unit,
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
    var hdInstant by remember(safeUrl) { mutableStateOf(false) }
    var loadStatus by remember(safeUrl) {
        mutableStateOf<PageLoadStatus>(if (safeUrl == null) PageLoadStatus.InvalidUrl else PageLoadStatus.Loading)
    }

    val latestOnDecoded by rememberUpdatedState(onImageDecoded)
    val latestOnZoomChanged by rememberUpdatedState(onZoomStateChanged)
    val latestOnToggleChrome by rememberUpdatedState(onToggleChrome)
    val latestOnLongPress by rememberUpdatedState(onLongPress)
    val latestOnDragStart by rememberUpdatedState(onDismissDragStart)
    val latestOnDragUpdate by rememberUpdatedState(onDismissDragUpdate)
    val latestOnDragEnd by rememberUpdatedState(onDismissDragEnd)

    val viewportW = viewportSize.width.roundToInt().coerceAtLeast(1)
    val viewportH = viewportSize.height.roundToInt().coerceAtLeast(1)

    LaunchedEffect(safeThumbUrl, safeUrl, imageLoader) {
        if (safeUrl == null || safeThumbUrl == null || safeThumbUrl == safeUrl || thumbImage != null) return@LaunchedEffect
        val req = ImageRequest.Builder(context).data(safeThumbUrl)
            .size(Dimension.Pixels(viewportW), Dimension.Pixels(viewportH))
            .scale(Scale.FIT).precision(Precision.INEXACT).build()
        val res = runCatching { imageLoader.execute(req) }.getOrNull() as? SuccessResult ?: return@LaunchedEffect
        thumbImage = res.image
        if (hdImage == null && res.image.width > 0 && res.image.height > 0) {
            latestOnDecoded(IntSize(res.image.width, res.image.height), (res.image as? DrawableImage)?.drawable is Animatable)
        }
    }

    LaunchedEffect(safeUrl, retryToken, imageLoader) {
        if (safeUrl == null) {
            loadStatus = PageLoadStatus.InvalidUrl
            return@LaunchedEffect
        }
        loadStatus = PageLoadStatus.Loading
        val req = ImageRequest.Builder(context).data(safeUrl)
            .size(Dimension.Pixels(viewportW), Dimension.Pixels((viewportW * 6).coerceAtLeast(viewportH)))
            .scale(Scale.FIT).precision(Precision.INEXACT).build()
        val res = try {
            imageLoader.execute(req)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (res is SuccessResult) {
            hdImage = res.image
            hdInstant = reducedMotion || (thumbImage == null && res.dataSource == DataSource.MEMORY_CACHE)
            loadStatus = PageLoadStatus.Ready
            latestOnDecoded(IntSize(res.image.width, res.image.height), (res.image as? DrawableImage)?.drawable is Animatable)
        } else {
            loadStatus = if (thumbImage != null) PageLoadStatus.Ready else PageLoadStatus.Failed
        }
    }

    val activeImage = originalImage ?: hdImage ?: thumbImage
    val decodedW = activeImage?.width ?: 0
    val decodedH = activeImage?.height ?: 0
    val isLong = remember(decodedW, decodedH) { isLongImage(decodedW, decodedH) }
    val restRect = remember(viewportSize, decodedW, decodedH, fallbackBounds) {
        computeRestImageRect(viewportSize, decodedW, decodedH, fallbackBounds)
    }

    LaunchedEffect(activeImage, isGifPlaying, isCurrentPage) {
        val anim = (activeImage as? DrawableImage)?.drawable as? Animatable ?: return@LaunchedEffect
        if (isCurrentPage && isGifPlaying) anim.start() else anim.stop()
    }

    var liveZoomScale by remember(safeUrl, pageIndex) { mutableFloatStateOf(1f) }
    var livePanOffset by remember(safeUrl, pageIndex) { mutableStateOf(Offset.Zero) }
    var zoomAnimJob by remember(safeUrl, pageIndex) { mutableStateOf<Job?>(null) }
    val zoomScaleAnim = remember(safeUrl, pageIndex) { ComposeAnimatable(1f) }
    val panAnim = remember(safeUrl, pageIndex) { ComposeAnimatable(Offset.Zero, Offset.VectorConverter) }
    var longScrollY by remember(safeUrl, pageIndex) { mutableFloatStateOf(0f) }

    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage && (liveZoomScale > 1f || livePanOffset != Offset.Zero)) {
            zoomAnimJob?.cancel()
            liveZoomScale = 1f
            livePanOffset = Offset.Zero
            zoomScaleAnim.snapTo(1f)
            panAnim.snapTo(Offset.Zero)
        }
    }
    LaunchedEffect(liveZoomScale, livePanOffset, isCurrentPage) {
        if (isCurrentPage) latestOnZoomChanged(liveZoomScale, livePanOffset)
    }

    val longDisplayHeightPx = if (isLong && decodedW > 0) viewportSize.width * (decodedH.toFloat() / decodedW.toFloat()) else restRect.height
    val maxLongScrollY = (longDisplayHeightPx - viewportSize.height).coerceAtLeast(0f)
    val baseContentSize = if (isLong) Size(viewportSize.width, longDisplayHeightPx) else restRect.size

    val hdAlpha by animateFloatAsState(if (hdImage != null) 1f else 0f, HiMotion.effectsFast(), label = "viewerHdFadeIn")
    val originalAlpha by animateFloatAsState(if (originalImage != null) 1f else 0f, HiMotion.effectsFast(), label = "viewerOrigFadeIn")

    val latestRestRect by rememberUpdatedState(restRect)
    val latestBaseSize by rememberUpdatedState(baseContentSize)
    val latestIsLong by rememberUpdatedState(isLong)
    val latestMaxLongScrollY by rememberUpdatedState(maxLongScrollY)
    val latestViewportSize by rememberUpdatedState(viewportSize)
    val latestReducedMotion by rememberUpdatedState(reducedMotion)
    val latestSafeUrl by rememberUpdatedState(safeUrl)
    val latestTransitionActive by rememberUpdatedState(transitionFrame.active)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(pageIndex, safeUrl) {
                detectTapGestures(
                    onTap = { latestOnToggleChrome() },
                    onLongPress = { if (latestSafeUrl != null) latestOnLongPress() },
                    onDoubleTap = { tapOffset ->
                        if (latestSafeUrl == null || latestTransitionActive) return@detectTapGestures
                        val (targetScale, targetPan) = computeDoubleTapZoomTarget(
                            liveZoomScale, tapOffset, latestRestRect.center, latestBaseSize, latestViewportSize,
                        )
                        zoomAnimJob?.cancel()
                        zoomAnimJob = motionScope.launch {
                            if (latestReducedMotion) {
                                liveZoomScale = targetScale; livePanOffset = targetPan
                                zoomScaleAnim.snapTo(targetScale); panAnim.snapTo(targetPan)
                            } else {
                                zoomScaleAnim.snapTo(liveZoomScale); panAnim.snapTo(livePanOffset)
                                launch { zoomScaleAnim.animateTo(targetScale, HiMotion.spatialDefault()) { liveZoomScale = value } }
                                launch { panAnim.animateTo(targetPan, HiMotion.spatialDefault()) { livePanOffset = value } }
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
                    var totalDx = 0f
                    var totalDy = 0f
                    var dragOffset = Offset.Zero
                    var dismissDragStarted = false
                    val velocityTracker = VelocityTracker().apply { addPosition(firstDown.uptimeMillis, firstDown.position) }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            if (dismissDragStarted) { latestOnDragEnd(Offset.Zero, Offset.Zero); dismissDragStarted = false }
                            zoomAnimJob?.cancel()
                            mode = PageGestureMode.Pinch
                            val z = event.calculateZoom()
                            val p = event.calculatePan()
                            if (z != 1f || p != Offset.Zero) {
                                val (ns, np) = computePinchTransform(
                                    liveZoomScale, livePanOffset, z, p, event.calculateCentroid(true),
                                    latestRestRect.center, latestBaseSize, latestViewportSize,
                                )
                                liveZoomScale = ns; livePanOffset = np
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
                            mode = when {
                                liveZoomScale > ImageViewerMotionSpec.ZOOMED_IN_THRESHOLD ->
                                    if (shouldYieldHorizontalEdgeToPager(livePanOffset.x, latestBaseSize.width, liveZoomScale, latestViewportSize.width, totalDx, totalDy)) {
                                        PageGestureMode.PassToPager
                                    } else {
                                        PageGestureMode.ZoomPan
                                    }
                                abs(totalDx) >= abs(totalDy) -> PageGestureMode.PassToPager
                                latestIsLong && (longScrollY > 1f || totalDy < 0f) -> PageGestureMode.LongScroll
                                totalDy > 0f -> { dismissDragStarted = true; latestOnDragStart(); PageGestureMode.DismissDrag }
                                else -> PageGestureMode.PassToPager
                            }
                        }

                        when (mode) {
                            PageGestureMode.ZoomPan -> if (!shouldYieldHorizontalEdgeToPager(livePanOffset.x, latestBaseSize.width, liveZoomScale, latestViewportSize.width, delta.x, delta.y)) {
                                zoomAnimJob?.cancel()
                                livePanOffset = clampPanOffset(livePanOffset + delta, liveZoomScale, latestBaseSize, latestViewportSize)
                                change.consume()
                            }
                            PageGestureMode.LongScroll -> {
                                longScrollY = (longScrollY - delta.y).coerceIn(0f, latestMaxLongScrollY)
                                change.consume()
                            }
                            PageGestureMode.DismissDrag -> {
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                dragOffset = Offset(totalDx, totalDy)
                                latestOnDragUpdate(dragOffset)
                                change.consume()
                            }
                            else -> Unit
                        }
                    }

                    if (dismissDragStarted) {
                        val vel = velocityTracker.calculateVelocity()
                        latestOnDragEnd(dragOffset, Offset(vel.x, vel.y))
                    } else if (mode == PageGestureMode.Pinch && liveZoomScale < ImageViewerMotionSpec.ZOOMED_IN_THRESHOLD) {
                        liveZoomScale = 1f; livePanOffset = Offset.Zero
                    }
                }
            },
    ) {
        when (loadStatus) {
            PageLoadStatus.InvalidUrl -> FailedViewerContent(canRetry = false, onRetry = {})
            PageLoadStatus.Failed -> FailedViewerContent(canRetry = true, onRetry = { retryToken++ })
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
                                    scaleX = liveZoomScale; scaleY = liveZoomScale
                                    translationX = livePanOffset.x; translationY = livePanOffset.y
                                    alpha = 1f; clip = isLong
                                }
                            }
                            .semantics { role = Role.Image; contentDescription = imageContentDescription },
                    ) {
                        // 长图使用 unbounded 顶部对齐，保证完整高度不被父视口截断居中，首屏从最顶缘贴宽展示
                        val imgMod = if (isLong) {
                            Modifier
                                .align(Alignment.TopCenter)
                                .wrapContentSize(align = Alignment.TopCenter, unbounded = true)
                                .size(with(density) { viewportSize.width.toDp() }, with(density) { longDisplayHeightPx.toDp() })
                                .graphicsLayer { translationY = -longScrollY; scaleX = compX; scaleY = compY }
                        } else {
                            Modifier.fillMaxSize().graphicsLayer { scaleX = compX; scaleY = compY }
                        }
                        val scale = if (isLong) ContentScale.FillWidth else ContentScale.Fit
                        val align = if (isLong) Alignment.TopCenter else Alignment.Center
                        thumbImage?.let { img ->
                            Image(remember(img, context) { img.asPainter(context) }, null, imgMod, align, scale)
                        }
                        hdImage?.let { img ->
                            val a = if (hdInstant) 1f else hdAlpha
                            Image(remember(img, context) { img.asPainter(context) }, null, imgMod.graphicsLayer { alpha = a }, align, scale)
                        }
                        originalImage?.let { img ->
                            val a = if (reducedMotion) 1f else originalAlpha
                            Image(remember(img, context) { img.asPainter(context) }, null, imgMod.graphicsLayer { alpha = a }, align, scale)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FailedViewerContent(canRetry: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(stringResource(R.string.image_viewer_load_failed), style = MaterialTheme.typography.bodyLarge, color = ImageViewerPalette.ForegroundPrimary)
        if (canRetry) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onRetry, shape = HiShapeTokens.Small, modifier = Modifier.defaultMinSize(88.dp, 48.dp)) {
                Text(stringResource(R.string.image_viewer_action_retry))
            }
        }
    }
}
