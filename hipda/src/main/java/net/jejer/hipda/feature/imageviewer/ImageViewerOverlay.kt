package net.jejer.hipda.feature.imageviewer

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.*
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.ImageViewerRenderer
import net.jejer.hipda.feature.common.LocalImageViewerBoundsRegistry
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.hiFullscreenOverlaySafePadding
import net.jejer.hipda.ui.designsystem.feedback.rememberHiHaptics
import net.jejer.hipda.ui.designsystem.motion.*

private enum class ViewerTransitionPhase { Entering, Settled, Dragging, BouncingBack, Dismissing }

@Immutable
private data class PageViewerState(
    val imageSize: IntSize? = null,
    val isGif: Boolean = false,
    val isGifPlaying: Boolean? = null,
    val downloadState: OriginalDownloadState = OriginalDownloadState.Idle,
    val pendingOriginal: OriginalDownloadState.Completed? = null,
)

/** [ImageViewerRenderer] 生产实现，经 `di/ImageViewerUiModule.kt` 自动接入 `NextAppRoot`。 */
@Singleton
class ImageViewerRendererImpl @Inject constructor(
    private val originalDownloader: OriginalImageDownloader,
    private val mediaActions: ImageViewerMediaActions,
) : ImageViewerRenderer {
    @Composable
    override fun Overlay(args: ImageViewerLaunchArgs, onDismiss: () -> Unit) {
        val registry = LocalImageViewerBoundsRegistry.current
        val resolver = remember(args, registry) {
            registry?.createResolver(args) ?: { idx -> if (idx == args.initialIndex) args.sourceBoundsInWindow else null }
        }
        Overlay(args, onDismiss, resolver)
    }

    @Composable
    override fun Overlay(args: ImageViewerLaunchArgs, onDismiss: () -> Unit, resolveBoundsForIndex: (index: Int) -> Rect?) {
        ImageViewerOverlay(args, onDismiss, originalDownloader, mediaActions, resolveBoundsForIndex = resolveBoundsForIndex)
    }
}

/**
 * 新版全屏看图浮层（E14）：全屏暗色覆盖单/双/三栏、零毛玻璃、双分支共享边界进退、
 * 缩放/平移/下拉关闭手势仲裁、长图贴宽顶对齐、GIF 播停切换、字节级确定进度原图下载与 Coil 限尺寸解码及严格 scheme 白名单。
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
    val context = LocalContext.current

    val paneTitleText = stringResource(R.string.image_viewer_pane_title)
    val originalLoadedMsg = stringResource(R.string.image_viewer_original_loaded)
    val savedMsg = stringResource(R.string.image_viewer_saved_to_gallery)
    val saveFailedMsg = stringResource(R.string.image_viewer_save_failed)
    val topTitle = when {
        args.floorNumber != null && !args.author.isNullOrBlank() -> stringResource(R.string.image_viewer_title_floor_author, args.floorNumber, args.author)
        !args.author.isNullOrBlank() -> args.author
        else -> null
    }

    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestResolveBounds by rememberUpdatedState(resolveBoundsForIndex)
    val latestReducedMotion by rememberUpdatedState(reducedMotion)

    val pageStates = remember(args.urls) {
        mutableStateMapOf<Int, PageViewerState>().apply {
            args.urls.forEachIndexed { i, u -> if (isGifUrl(u)) put(i, PageViewerState(isGif = true)) }
        }
    }
    val downloadJobs = remember(args.urls) { mutableMapOf<Int, Job>() }
    var chromeVisible by remember(args) { mutableStateOf(true) }
    var longPressMenuOpen by remember(args) { mutableStateOf(false) }
    var isSaving by remember(args) { mutableStateOf(false) }
    var pendingPermSavePage by remember(args) { mutableStateOf<Int?>(null) }
    var activeZoomScale by remember { mutableFloatStateOf(1f) }
    var activeZoomPan by remember { mutableStateOf(Offset.Zero) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }

    fun stateOf(page: Int): PageViewerState = pageStates[page] ?: PageViewerState()
    fun updatePage(page: Int, transform: (PageViewerState) -> PageViewerState) { pageStates[page] = transform(stateOf(page)) }

    fun executeSave(page: Int) {
        val safeUrl = sanitizeViewerImageUrl(args.urls.getOrNull(page)) ?: return
        if (isSaving) return
        val cached = (stateOf(page).downloadState as? OriginalDownloadState.Completed)?.file
        isSaving = true
        motionScope.launch {
            val ok = runCatching { mediaActions.saveImage(safeUrl, cached) }.getOrDefault(false)
            isSaving = false
            snackbar.show(if (ok) savedMsg else saveFailedMsg)
        }
    }

    val storagePermLauncher = if (LocalActivityResultRegistryOwner.current != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val targetPage = pendingPermSavePage ?: return@rememberLauncherForActivityResult
            pendingPermSavePage = null
            if (granted) executeSave(targetPage) else motionScope.launch { snackbar.show(saveFailedMsg) }
        }
    } else null

    fun performSave(page: Int) {
        if (sanitizeViewerImageUrl(args.urls.getOrNull(page)) == null || isSaving) return
        if (requiresLegacyStoragePermission() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            if (storagePermLauncher != null) {
                pendingPermSavePage = page
                storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                motionScope.launch { snackbar.show(saveFailedMsg) }
            }
            return
        }
        executeSave(page)
    }

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

        fun toLocalRect(r: Rect) = r.translate(-overlayOrigin.x, -overlayOrigin.y)
        fun restRectForPage(page: Int): Rect {
            val s = stateOf(page).imageSize
            val fb = latestResolveBounds(page) ?: if (page == initialPage) args.sourceBoundsInWindow else null
            return computeRestImageRect(viewportSize, s?.width ?: 0, s?.height ?: 0, fb)
        }

        val initialWindowBounds = remember(args) { resolveBoundsForIndex(initialPage) ?: args.sourceBoundsInWindow }
        val initialRest = remember(viewportSize, initialWindowBounds) { computeRestImageRect(viewportSize, 0, 0, initialWindowBounds) }
        val startRect = remember(viewportSize, initialWindowBounds, reducedMotion) {
            if (reducedMotion || initialWindowBounds == null) initialRest else toLocalRect(initialWindowBounds)
        }

        var phase by remember(args) { mutableStateOf(if (reducedMotion) ViewerTransitionPhase.Settled else ViewerTransitionPhase.Entering) }
        var dragRestRect by remember(args) { mutableStateOf(initialRest) }
        var dragOffset by remember(args) { mutableStateOf(Offset.Zero) }
        val centerAnim = remember(args) { ComposeAnimatable(startRect.center, Offset.VectorConverter) }
        val sizeAnim = remember(args) { ComposeAnimatable(startRect.size, Size.VectorConverter) }
        val scrimAlphaAnim = remember(args) { ComposeAnimatable(if (reducedMotion) 1f else 0f) }
        val contentAlphaAnim = remember(args) { ComposeAnimatable(if (reducedMotion || initialWindowBounds != null) 1f else 0f) }
        var transitionJob by remember(args) { mutableStateOf<Job?>(null) }

        suspend fun snapTransition(center: Offset, size: Size, scrim: Float, alpha: Float = 1f) {
            centerAnim.snapTo(center); sizeAnim.snapTo(size); scrimAlphaAnim.snapTo(scrim); contentAlphaAnim.snapTo(alpha)
        }

        fun launchSpatialEnter(target: Rect) {
            transitionJob?.cancel()
            transitionJob = motionScope.launch {
                listOf(
                    launch { centerAnim.animateTo(target.center, HiMotion.spatialSlow()) },
                    launch { sizeAnim.animateTo(target.size, HiMotion.spatialSlow()) },
                    launch { scrimAlphaAnim.animateTo(1f, HiMotion.effectsSlow()) },
                ).joinAll()
                if (phase == ViewerTransitionPhase.Entering) phase = ViewerTransitionPhase.Settled
            }
        }

        LaunchedEffect(args) {
            val target = restRectForPage(initialPage)
            when {
                latestReducedMotion -> { snapTransition(target.center, target.size, 1f, 1f); phase = ViewerTransitionPhase.Settled }
                initialWindowBounds != null -> launchSpatialEnter(target)
                else -> transitionJob = motionScope.launch {
                    centerAnim.snapTo(target.center); sizeAnim.snapTo(target.size)
                    listOf(
                        launch { contentAlphaAnim.animateTo(1f, HiMotion.effectsDefault()) },
                        launch { scrimAlphaAnim.animateTo(1f, HiMotion.effectsDefault()) },
                    ).joinAll()
                    if (phase == ViewerTransitionPhase.Entering) phase = ViewerTransitionPhase.Settled
                }
            }
        }

        // 长图/异比例图带源边界入场时，解码就绪后平滑重定入场终点，消除 Entering -> Settled 末帧跳变（R-E14 S3）
        val initialDecodedSize = stateOf(initialPage).imageSize
        LaunchedEffect(initialDecodedSize, viewportSize) {
            if (phase != ViewerTransitionPhase.Entering || initialWindowBounds == null || initialDecodedSize == null || latestReducedMotion) return@LaunchedEffect
            val target = restRectForPage(initialPage)
            val differs = (centerAnim.targetValue - target.center).getDistance() > 1f ||
                abs(sizeAnim.targetValue.width - target.width) > 1f || abs(sizeAnim.targetValue.height - target.height) > 1f
            if (differs) launchSpatialEnter(target)
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
            val localTarget = exitBoundsWindow?.let(::toLocalRect)

            transitionJob = motionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                when (prevPhase) {
                    ViewerTransitionPhase.Settled -> snapTransition(
                        rest.center + activeZoomPan, Size(rest.width * activeZoomScale, rest.height * activeZoomScale), 1f,
                    )
                    ViewerTransitionPhase.Dragging -> {
                        val m = computeDragDismissMetrics(capturedOffset.y, maxDragPx)
                        snapTransition(capturedRect.center + capturedOffset, Size(capturedRect.width * m.scale, capturedRect.height * m.scale), m.scrimAlpha)
                    }
                    else -> Unit
                }
                val fallbackSize = Size(
                    minOf(sizeAnim.value.width, rest.width * ImageViewerMotionSpec.FALLBACK_EXIT_SCALE),
                    minOf(sizeAnim.value.height, rest.height * ImageViewerMotionSpec.FALLBACK_EXIT_SCALE),
                )
                if (latestReducedMotion) {
                    if (localTarget != null) snapTransition(localTarget.center, localTarget.size, 0f, 1f)
                    else snapTransition(centerAnim.value, fallbackSize, 0f, 0f)
                    latestOnDismiss()
                    return@launch
                }
                if (localTarget != null) {
                    listOf(
                        launch { centerAnim.animateTo(localTarget.center, HiMotion.exit(HiMotion.spatialSlow()), releaseVelocity) },
                        launch { sizeAnim.animateTo(localTarget.size, HiMotion.exit(HiMotion.spatialSlow())) },
                        launch { scrimAlphaAnim.animateTo(0f, HiMotion.exit(HiMotion.effectsDefault())) },
                    ).joinAll()
                    snapTransition(localTarget.center, localTarget.size, 0f, 1f)
                } else {
                    listOf(
                        launch { sizeAnim.animateTo(fallbackSize, HiMotion.exit(HiMotion.spatialDefault())) },
                        launch { if (releaseVelocity != Offset.Zero) centerAnim.animateTo(centerAnim.value, HiMotion.exit(HiMotion.spatialDefault()), releaseVelocity) },
                        launch { contentAlphaAnim.animateTo(0f, HiMotion.exit(HiMotion.effectsDefault())) },
                        launch { scrimAlphaAnim.animateTo(0f, HiMotion.exit(HiMotion.effectsDefault())) },
                    ).joinAll()
                    snapTransition(centerAnim.value, fallbackSize, 0f, 0f)
                }
                withFrameNanos { }
                latestOnDismiss()
            }
        }

        // 全程启用直至浮层卸载（含 Dismissing 退场期）：吞掉重复返回，绝不穿透关闭底层帖子（V-E14 probeM3b）
        BackHandler(enabled = true) {
            if (phase == ViewerTransitionPhase.Dismissing) return@BackHandler
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
        val currentState = stateOf(currentPage)
        val currentGifPlaying = currentState.isGifPlaying ?: !reducedMotion

        fun toggleOriginalDownload(page: Int) {
            val safeUrl = sanitizeViewerImageUrl(args.urls.getOrNull(page)) ?: return
            when (stateOf(page).downloadState) {
                is OriginalDownloadState.Downloading -> {
                    downloadJobs.remove(page)?.cancel()
                    updatePage(page) { it.copy(downloadState = OriginalDownloadState.Idle, pendingOriginal = null) }
                }
                is OriginalDownloadState.Completed -> return
                else -> {
                    updatePage(page) { it.copy(downloadState = OriginalDownloadState.Downloading(0L, null, null), pendingOriginal = null) }
                    downloadJobs[page] = motionScope.launch {
                        originalDownloader.download(safeUrl).collect { state ->
                            if (state is OriginalDownloadState.Completed) {
                                updatePage(page) { it.copy(pendingOriginal = state, isGif = it.isGif || state.mimeType.equals("image/gif", true)) }
                            } else {
                                updatePage(page) { it.copy(downloadState = state) }
                            }
                        }
                    }
                }
            }
        }

        fun performShare(page: Int) {
            val safeUrl = sanitizeViewerImageUrl(args.urls.getOrNull(page)) ?: return
            val cached = (stateOf(page).downloadState as? OriginalDownloadState.Completed)?.file
            motionScope.launch { runCatching { mediaActions.shareImage(safeUrl, cached) } }
        }

        HorizontalPager(pagerState, Modifier.fillMaxSize(), userScrollEnabled = phase == ViewerTransitionPhase.Settled && !longPressMenuOpen) { page ->
            val pState = stateOf(page)
            val fb = remember(page, args) { latestResolveBounds(page) ?: if (page == initialPage) args.sourceBoundsInWindow else null }
            ImageViewerPage(
                pageIndex = page,
                rawUrl = args.urls.getOrNull(page),
                rawThumbnailUrl = thumbnailUrls.getOrNull(page),
                pendingOriginal = pState.pendingOriginal,
                isCurrentPage = page == currentPage,
                isGifPlaying = pState.isGifPlaying ?: !reducedMotion,
                viewportSize = viewportSize,
                fallbackBounds = fb,
                transitionFrame = transitionFrame,
                imageContentDescription = stringResource(R.string.image_viewer_page_indicator, page + 1, totalPages),
                onImageDecoded = { sz, anim ->
                    updatePage(page) { it.copy(imageSize = if (sz.width > 0 && sz.height > 0) sz else it.imageSize, isGif = it.isGif || anim) }
                },
                onOriginalDecodeResult = { ok, sz, anim ->
                    val current = stateOf(page)
                    val completed = current.pendingOriginal
                    val wasAlreadyCompleted = current.downloadState is OriginalDownloadState.Completed
                    if (ok && completed != null) {
                        updatePage(page) { it.copy(imageSize = if (sz != null && sz.width > 0 && sz.height > 0) sz else it.imageSize, isGif = it.isGif || anim, downloadState = completed) }
                        if (!wasAlreadyCompleted) {
                            motionScope.launch { snackbar.show(originalLoadedMsg) }
                        }
                    } else {
                        updatePage(page) { it.copy(downloadState = OriginalDownloadState.Failed, pendingOriginal = null) }
                    }
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
                            snapTransition(rest.center + offset, Size(rest.width * m.scale, rest.height * m.scale), m.scrimAlpha)
                            if (latestReducedMotion) {
                                snapTransition(rest.center, rest.size, 1f)
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

        val showChrome = chromeVisible && phase == ViewerTransitionPhase.Settled
        AnimatedVisibility(
            visible = currentState.isGif && currentSafeUrl != null && phase == ViewerTransitionPhase.Settled,
            enter = hiFadeIn(HiMotion.effectsFast()), exit = hiFadeOut(HiMotion.exit(HiMotion.effectsFast())),
            modifier = Modifier.align(Alignment.TopStart).hiFullscreenOverlaySafePadding(includeTop = true, includeBottomNav = false).padding(top = 68.dp, start = 16.dp),
        ) { ImageViewerGifBadge(currentGifPlaying, onToggle = { updatePage(currentPage) { it.copy(isGifPlaying = !currentGifPlaying) } }) }
        AnimatedVisibility(showChrome, Modifier.align(Alignment.TopCenter), hiFadeIn(HiMotion.effectsFast()), hiFadeOut(HiMotion.exit(HiMotion.effectsFast()))) {
            ImageViewerTopBar(topTitle, currentPage, totalPages, onClose = { triggerDismiss() })
        }
        AnimatedVisibility(showChrome, Modifier.align(Alignment.BottomCenter), hiFadeIn(HiMotion.effectsFast()), hiFadeOut(HiMotion.exit(HiMotion.effectsFast()))) {
            ImageViewerBottomBar(
                currentState.downloadState, currentSafeUrl != null, isSaving,
                onOriginalClick = { toggleOriginalDownload(currentPage) },
                onSaveClick = { performSave(currentPage) },
                onShareClick = { performShare(currentPage) },
            )
        }
        Box(Modifier.align(Alignment.Center)) {
            ImageViewerLongPressMenu(
                expanded = longPressMenuOpen && phase == ViewerTransitionPhase.Settled,
                onSave = { performSave(currentPage) },
                onShare = { performShare(currentPage) },
                onDismiss = { longPressMenuOpen = false },
            )
        }
    }
}
