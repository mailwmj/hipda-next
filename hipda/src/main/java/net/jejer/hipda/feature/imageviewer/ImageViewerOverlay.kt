package net.jejer.hipda.feature.imageviewer

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable as ComposeAnimatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.DrawableImage
import coil3.Image as CoilImage
import coil3.asImage
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.ImageViewerRenderer
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.rememberHiHaptics
import net.jejer.hipda.ui.designsystem.motion.*

private enum class ViewerTransitionPhase { Entering, Settled, Dragging, BouncingBack, Dismissing }

/** [ImageViewerRenderer] 生产实现，经 `di/ImageViewerUiModule.kt` 自动接入 `NextAppRoot`。 */
@Singleton
class ImageViewerRendererImpl @Inject constructor(
    private val originalDownloader: OriginalImageDownloader,
    private val mediaActions: ImageViewerMediaActions,
) : ImageViewerRenderer {
    @Composable
    override fun Overlay(args: ImageViewerLaunchArgs, onDismiss: () -> Unit) {
        ImageViewerOverlay(args, onDismiss, originalDownloader, mediaActions)
    }
}

/**
 * 新版全屏看图浮层（E14）：全屏暗色覆盖单/双/三栏、零毛玻璃、双分支共享边界进退、
 * 缩放/平移/下拉关闭手势仲裁、长图贴宽顶对齐、GIF 播停切换、字节级确定进度原图下载与取消及严格 scheme 白名单。
 */
@Composable
fun ImageViewerOverlay(
    args: ImageViewerLaunchArgs,
    onDismiss: () -> Unit,
    originalDownloader: OriginalImageDownloader,
    mediaActions: ImageViewerMediaActions,
    modifier: Modifier = Modifier,
    resolveBoundsForIndex: (index: Int) -> Rect? = { if (it == args.initialIndex) args.sourceBoundsInWindow else null },
    thumbnailUrls: List<String?> = emptyList(),
) {
    val totalPages = args.urls.size.coerceAtLeast(1)
    val initialPage = args.initialIndex.coerceIn(0, totalPages - 1)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { totalPages })
    val motionScope = rememberHiMotionCoroutineScope()
    val snackbar = LocalPaneSnackbarController.current
    val haptics = rememberHiHaptics()
    val reducedMotion = HiTheme.isReducedMotion
    val density = LocalDensity.current

    val paneTitleText = stringResource(R.string.image_viewer_pane_title)
    val originalLoadedMsg = stringResource(R.string.image_viewer_original_loaded)
    val savedMsg = stringResource(R.string.image_viewer_saved_to_gallery)
    val saveFailedMsg = stringResource(R.string.image_viewer_save_failed)
    val topTitle = when {
        args.floorNumber != null && !args.author.isNullOrBlank() ->
            stringResource(R.string.image_viewer_title_floor_author, args.floorNumber, args.author)
        !args.author.isNullOrBlank() -> args.author
        else -> null
    }

    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestResolveBounds by rememberUpdatedState(resolveBoundsForIndex)
    val latestReducedMotion by rememberUpdatedState(reducedMotion)

    val pageSizes = remember(args.urls) { mutableStateMapOf<Int, IntSize>() }
    val pageIsGif = remember(args.urls) {
        mutableStateMapOf<Int, Boolean>().apply { args.urls.forEachIndexed { i, u -> if (isGifUrl(u)) put(i, true) } }
    }
    val pageGifPlaying = remember(args.urls) { mutableStateMapOf<Int, Boolean>() }
    val downloadStates = remember(args.urls) { mutableStateMapOf<Int, OriginalDownloadState>() }
    val originalImages = remember(args.urls) { mutableStateMapOf<Int, CoilImage>() }
    val downloadJobs = remember(args.urls) { mutableMapOf<Int, Job>() }

    var chromeVisible by remember(args) { mutableStateOf(true) }
    var longPressMenuOpen by remember(args) { mutableStateOf(false) }
    var isSaving by remember(args) { mutableStateOf(false) }
    var activeZoomScale by remember { mutableFloatStateOf(1f) }
    var activeZoomPan by remember { mutableStateOf(Offset.Zero) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }

    DisposableEffect(args.urls) { onDispose { downloadJobs.values.forEach { it.cancel() } } }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { if (it.isAttached) overlayOrigin = it.positionInWindow() }
            .semantics { paneTitle = paneTitleText; isTraversalGroup = true },
    ) {
        val viewportSize = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val maxDragPx = with(density) { ImageViewerMotionSpec.MAX_DRAG_DISMISS_DP.dp.toPx() }
        val dismissDistPx = with(density) { ImageViewerMotionSpec.DISMISS_DISTANCE_THRESHOLD_DP.dp.toPx() }
        val dismissVelPx = with(density) { ImageViewerMotionSpec.DISMISS_VELOCITY_THRESHOLD_DP_PER_SEC.dp.toPx() }

        fun toLocalRect(windowRect: Rect) = windowRect.translate(-overlayOrigin.x, -overlayOrigin.y)
        fun restRectForPage(page: Int): Rect {
            val s = pageSizes[page]
            val fb = latestResolveBounds(page) ?: if (page == initialPage) args.sourceBoundsInWindow else null
            return computeRestImageRect(viewportSize, s?.width ?: 0, s?.height ?: 0, fb)
        }

        val initialWindowBounds = remember(args) { resolveBoundsForIndex(initialPage) ?: args.sourceBoundsInWindow }
        val initialRest = remember(viewportSize, initialWindowBounds) { computeRestImageRect(viewportSize, 0, 0, initialWindowBounds) }
        val startRect = remember(viewportSize, initialWindowBounds, reducedMotion) {
            if (reducedMotion || initialWindowBounds == null) initialRest else toLocalRect(initialWindowBounds)
        }

        var phase by remember(args) {
            mutableStateOf(if (reducedMotion) ViewerTransitionPhase.Settled else ViewerTransitionPhase.Entering)
        }
        var dragRestRect by remember(args) { mutableStateOf(initialRest) }
        var dragOffset by remember(args) { mutableStateOf(Offset.Zero) }
        val centerAnim = remember(args) { ComposeAnimatable(startRect.center, Offset.VectorConverter) }
        val sizeAnim = remember(args) { ComposeAnimatable(startRect.size, Size.VectorConverter) }
        val scrimAlphaAnim = remember(args) { ComposeAnimatable(if (reducedMotion) 1f else 0f) }
        val contentAlphaAnim = remember(args) { ComposeAnimatable(if (reducedMotion || initialWindowBounds != null) 1f else 0f) }
        var transitionJob by remember(args) { mutableStateOf<Job?>(null) }

        LaunchedEffect(args) {
            val target = restRectForPage(initialPage)
            if (latestReducedMotion) {
                centerAnim.snapTo(target.center); sizeAnim.snapTo(target.size)
                scrimAlphaAnim.snapTo(1f); contentAlphaAnim.snapTo(1f)
                phase = ViewerTransitionPhase.Settled
                return@LaunchedEffect
            }
            transitionJob = motionScope.launch {
                val jobs = if (initialWindowBounds != null) {
                    listOf(
                        launch { centerAnim.animateTo(target.center, HiMotion.spatialSlow()) },
                        launch { sizeAnim.animateTo(target.size, HiMotion.spatialSlow()) },
                        launch { scrimAlphaAnim.animateTo(1f, HiMotion.effectsSlow()) },
                    )
                } else {
                    centerAnim.snapTo(target.center); sizeAnim.snapTo(target.size)
                    listOf(
                        launch { contentAlphaAnim.animateTo(1f, HiMotion.effectsDefault()) },
                        launch { scrimAlphaAnim.animateTo(1f, HiMotion.effectsDefault()) },
                    )
                }
                jobs.joinAll()
                if (phase == ViewerTransitionPhase.Entering) phase = ViewerTransitionPhase.Settled
            }
        }

        fun triggerDismiss(releaseVelocity: Offset = Offset.Zero) {
            if (phase == ViewerTransitionPhase.Dismissing) return
            val prevPhase = phase; val capturedRect = dragRestRect; val capturedOffset = dragOffset
            transitionJob?.cancel()
            phase = ViewerTransitionPhase.Dismissing
            chromeVisible = false; longPressMenuOpen = false
            downloadJobs.values.forEach { it.cancel() }; downloadJobs.clear()

            val page = pagerState.currentPage
            val rest = restRectForPage(page)
            val exitBoundsWindow = latestResolveBounds(page)

            transitionJob = motionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                when (prevPhase) {
                    ViewerTransitionPhase.Settled -> {
                        centerAnim.snapTo(rest.center + activeZoomPan)
                        sizeAnim.snapTo(Size(rest.width * activeZoomScale, rest.height * activeZoomScale))
                        scrimAlphaAnim.snapTo(1f); contentAlphaAnim.snapTo(1f)
                    }
                    ViewerTransitionPhase.Dragging -> {
                        val m = computeDragDismissMetrics(capturedOffset.y, maxDragPx)
                        centerAnim.snapTo(capturedRect.center + capturedOffset)
                        sizeAnim.snapTo(Size(capturedRect.width * m.scale, capturedRect.height * m.scale))
                        scrimAlphaAnim.snapTo(m.scrimAlpha); contentAlphaAnim.snapTo(1f)
                    }
                    else -> Unit
                }

                if (latestReducedMotion) {
                    if (exitBoundsWindow != null) {
                        val t = toLocalRect(exitBoundsWindow); centerAnim.snapTo(t.center); sizeAnim.snapTo(t.size)
                    } else {
                        sizeAnim.snapTo(Size(rest.width * ImageViewerMotionSpec.FALLBACK_EXIT_SCALE, rest.height * ImageViewerMotionSpec.FALLBACK_EXIT_SCALE))
                        contentAlphaAnim.snapTo(0f)
                    }
                    scrimAlphaAnim.snapTo(0f)
                    latestOnDismiss()
                    return@launch
                }

                if (exitBoundsWindow != null) {
                    val t = toLocalRect(exitBoundsWindow)
                    listOf(
                        launch { centerAnim.animateTo(t.center, HiMotion.exit(HiMotion.spatialSlow()), releaseVelocity) },
                        launch { sizeAnim.animateTo(t.size, HiMotion.exit(HiMotion.spatialSlow())) },
                        launch { scrimAlphaAnim.animateTo(0f, HiMotion.exit(HiMotion.effectsDefault())) },
                    ).joinAll()
                    centerAnim.snapTo(t.center); sizeAnim.snapTo(t.size); scrimAlphaAnim.snapTo(0f)
                } else {
                    val cur = sizeAnim.value
                    val targetSize = Size(
                        minOf(cur.width, rest.width * ImageViewerMotionSpec.FALLBACK_EXIT_SCALE),
                        minOf(cur.height, rest.height * ImageViewerMotionSpec.FALLBACK_EXIT_SCALE),
                    )
                    listOf(
                        launch { sizeAnim.animateTo(targetSize, HiMotion.exit(HiMotion.spatialDefault())) },
                        launch {
                            if (releaseVelocity != Offset.Zero) {
                                centerAnim.animateTo(centerAnim.value, HiMotion.exit(HiMotion.spatialDefault()), releaseVelocity)
                            }
                        },
                        launch { contentAlphaAnim.animateTo(0f, HiMotion.exit(HiMotion.effectsDefault())) },
                        launch { scrimAlphaAnim.animateTo(0f, HiMotion.exit(HiMotion.effectsDefault())) },
                    ).joinAll()
                    sizeAnim.snapTo(targetSize); contentAlphaAnim.snapTo(0f); scrimAlphaAnim.snapTo(0f)
                }
                withFrameNanos { }
                latestOnDismiss()
            }
        }

        BackHandler(enabled = phase != ViewerTransitionPhase.Dismissing) {
            if (longPressMenuOpen) longPressMenuOpen = false else triggerDismiss()
        }

        val liveDrag = if (phase == ViewerTransitionPhase.Dragging) computeDragDismissMetrics(dragOffset.y, maxDragPx) else null
        Box(Modifier.fillMaxSize().background(ImageViewerPalette.ScrimColor.copy(alpha = (liveDrag?.scrimAlpha ?: scrimAlphaAnim.value).coerceIn(0f, 1f))))

        val transitionFrame = if (liveDrag != null) {
            ActiveTransitionFrame(true, dragRestRect.center + dragOffset, Size(dragRestRect.width * liveDrag.scale, dragRestRect.height * liveDrag.scale), 1f)
        } else {
            ActiveTransitionFrame(phase != ViewerTransitionPhase.Settled, centerAnim.value, sizeAnim.value, contentAlphaAnim.value)
        }

        val currentPage = pagerState.currentPage
        val currentSafeUrl = remember(args.urls, currentPage) { sanitizeViewerImageUrl(args.urls.getOrNull(currentPage)) }
        val currentDownloadState = downloadStates[currentPage] ?: OriginalDownloadState.Idle
        val currentIsGif = pageIsGif[currentPage] == true
        val currentGifPlaying = pageGifPlaying[currentPage] ?: !reducedMotion

        fun toggleOriginalDownload(page: Int) {
            val safeUrl = sanitizeViewerImageUrl(args.urls.getOrNull(page)) ?: return
            if (downloadStates[page] is OriginalDownloadState.Downloading) {
                downloadJobs.remove(page)?.cancel(); downloadStates[page] = OriginalDownloadState.Idle; return
            }
            if (downloadStates[page] is OriginalDownloadState.Completed) return
            downloadStates[page] = OriginalDownloadState.Downloading(0L, null, null)
            downloadJobs[page] = motionScope.launch {
                originalDownloader.download(safeUrl).collect { state ->
                    if (state is OriginalDownloadState.Completed) {
                        val decoded = withContext(Dispatchers.IO) { decodeDownloadedImageFile(state.file, state.mimeType) }
                        if (decoded != null) {
                            originalImages[page] = decoded
                            if (decoded.width > 0 && decoded.height > 0) pageSizes[page] = IntSize(decoded.width, decoded.height)
                            if (state.mimeType.equals("image/gif", true) || (decoded as? DrawableImage)?.drawable is Animatable) pageIsGif[page] = true
                            downloadStates[page] = state
                            snackbar.show(originalLoadedMsg)
                        } else {
                            downloadStates[page] = OriginalDownloadState.Failed
                        }
                    } else {
                        downloadStates[page] = state
                    }
                }
            }
        }

        fun performSave(page: Int) {
            val safeUrl = sanitizeViewerImageUrl(args.urls.getOrNull(page)) ?: return
            if (isSaving) return
            val cached = (downloadStates[page] as? OriginalDownloadState.Completed)?.file
            isSaving = true
            motionScope.launch {
                val ok = runCatching { mediaActions.saveImage(safeUrl, cached) }.getOrDefault(false)
                isSaving = false
                snackbar.show(if (ok) savedMsg else saveFailedMsg)
            }
        }

        fun performShare(page: Int) {
            val safeUrl = sanitizeViewerImageUrl(args.urls.getOrNull(page)) ?: return
            val cached = (downloadStates[page] as? OriginalDownloadState.Completed)?.file
            motionScope.launch { runCatching { mediaActions.shareImage(safeUrl, cached) } }
        }

        HorizontalPager(
            state = pagerState,
            userScrollEnabled = phase == ViewerTransitionPhase.Settled && !longPressMenuOpen,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val pageDesc = stringResource(R.string.image_viewer_page_indicator, page + 1, totalPages)
            val fb = remember(page, args) { latestResolveBounds(page) ?: if (page == initialPage) args.sourceBoundsInWindow else null }
            ImageViewerPage(
                pageIndex = page,
                rawUrl = args.urls.getOrNull(page),
                rawThumbnailUrl = thumbnailUrls.getOrNull(page),
                originalImage = originalImages[page],
                isCurrentPage = page == currentPage,
                isGifPlaying = pageGifPlaying[page] ?: !reducedMotion,
                viewportSize = viewportSize,
                fallbackBounds = fb,
                transitionFrame = transitionFrame,
                imageContentDescription = pageDesc,
                onImageDecoded = { sz, anim ->
                    if (sz.width > 0 && sz.height > 0) pageSizes[page] = sz
                    if (anim) pageIsGif[page] = true
                },
                onZoomStateChanged = { s, p -> activeZoomScale = s; activeZoomPan = p },
                onToggleChrome = { if (phase == ViewerTransitionPhase.Settled) chromeVisible = !chromeVisible },
                onLongPress = { if (phase == ViewerTransitionPhase.Settled && currentSafeUrl != null) { haptics.confirm(); longPressMenuOpen = true } },
                onDismissDragStart = {
                    if (phase == ViewerTransitionPhase.Dismissing) return@ImageViewerPage
                    transitionJob?.cancel()
                    dragRestRect = restRectForPage(pagerState.currentPage); dragOffset = Offset.Zero
                    phase = ViewerTransitionPhase.Dragging
                },
                onDismissDragUpdate = { if (phase == ViewerTransitionPhase.Dragging) dragOffset = it },
                onDismissDragEnd = { offset, velocity ->
                    if (phase != ViewerTransitionPhase.Dragging) return@ImageViewerPage
                    dragOffset = offset
                    if (shouldDismissOnRelease(offset.y, velocity.y, dismissDistPx, dismissVelPx)) {
                        triggerDismiss(velocity)
                    } else {
                        val rest = dragRestRect; val m = computeDragDismissMetrics(offset.y, maxDragPx)
                        phase = ViewerTransitionPhase.BouncingBack
                        transitionJob = motionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            centerAnim.snapTo(rest.center + offset); sizeAnim.snapTo(Size(rest.width * m.scale, rest.height * m.scale))
                            scrimAlphaAnim.snapTo(m.scrimAlpha); contentAlphaAnim.snapTo(1f)
                            if (latestReducedMotion) {
                                centerAnim.snapTo(rest.center); sizeAnim.snapTo(rest.size); scrimAlphaAnim.snapTo(1f)
                            } else {
                                listOf(
                                    launch { centerAnim.animateTo(rest.center, HiMotion.spatialSnap(), velocity) },
                                    launch { sizeAnim.animateTo(rest.size, HiMotion.spatialSnap()) },
                                    launch { scrimAlphaAnim.animateTo(1f, HiMotion.effectsFast()) },
                                ).joinAll()
                            }
                            if (phase == ViewerTransitionPhase.BouncingBack) phase = ViewerTransitionPhase.Settled
                        }
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = currentIsGif && currentSafeUrl != null && phase == ViewerTransitionPhase.Settled,
            enter = hiFadeIn(HiMotion.effectsFast()),
            exit = hiFadeOut(HiMotion.exit(HiMotion.effectsFast())),
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 68.dp, start = 16.dp),
        ) {
            ImageViewerGifBadge(currentGifPlaying, onToggle = { pageGifPlaying[currentPage] = !currentGifPlaying })
        }

        val showChrome = chromeVisible && phase == ViewerTransitionPhase.Settled
        AnimatedVisibility(showChrome, Modifier.align(Alignment.TopCenter), hiFadeIn(HiMotion.effectsFast()), hiFadeOut(HiMotion.exit(HiMotion.effectsFast()))) {
            ImageViewerTopBar(topTitle, currentPage, totalPages, onClose = { triggerDismiss() })
        }
        AnimatedVisibility(showChrome, Modifier.align(Alignment.BottomCenter), hiFadeIn(HiMotion.effectsFast()), hiFadeOut(HiMotion.exit(HiMotion.effectsFast()))) {
            ImageViewerBottomBar(
                currentDownloadState, currentSafeUrl != null, isSaving,
                onOriginalClick = { toggleOriginalDownload(currentPage) },
                onSaveClick = { performSave(currentPage) },
                onShareClick = { performShare(currentPage) },
            )
        }
        AnimatedVisibility(
            visible = longPressMenuOpen && phase == ViewerTransitionPhase.Settled,
            enter = hiFadeIn(HiMotion.effectsFast()) + hiScaleIn(HiMotion.spatialFast(), initialScale = 0.92f),
            exit = hiFadeOut(HiMotion.exit(HiMotion.effectsFast())) + hiScaleOut(HiMotion.exit(HiMotion.spatialFast()), targetScale = 0.92f),
        ) {
            ImageViewerLongPressMenu({ performSave(currentPage) }, { performShare(currentPage) }, { longPressMenuOpen = false })
        }
    }
}

private fun decodeDownloadedImageFile(file: File, mimeType: String): CoilImage? {
    if (!file.isFile || file.length() <= 0L) return null
    if (mimeType.equals("image/gif", ignoreCase = true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) }.getOrNull()?.let { return it.asImage() }
    }
    return runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImage() }.getOrNull()
}
