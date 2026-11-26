package net.jejer.hipda.feature.threaddetail.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.Image as CoilImage
import coil3.compose.asPainter
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalImageViewerBoundsRegistry
import net.jejer.hipda.feature.common.currentHiImageLoader
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner
import net.jejer.hipda.ui.designsystem.state.hiSkeleton

private sealed interface PostImageLoadState {
    data object TapToLoad : PostImageLoadState
    data class Loading(val userInitiated: Boolean) : PostImageLoadState
    data class Loaded(val image: CoilImage, val instant: Boolean) : PostImageLoadState
    data object Failed : PostImageLoadState
}

/** 图片高度变化时的三分支滚动补偿策略（闭合 `R-BATCH5 B4-E2b`）。 */
internal enum class ImageHeightChangeBranch {
    /** 图片位于当前首可见楼层内且底缘已越过视口顶边：立即到位并同步补偿滚动偏移，保首楼距顶偏差 `≤ 1dp`。 */
    ABOVE_VIEWPORT_COMPENSATE,
    /** 图片所属楼层严格位于当前首可见楼层上方（或已滚出可见区）：立即到位，`LazyColumn` 已锚定首楼无需误滚。 */
    ABOVE_FIRST_VISIBLE_FLOOR_SNAP,
    /** 图片位于首楼未越顶区域或首楼下方：绝不误滚（`== 0dp`），容器高度以 `HiMotion.spatialFast()` 平滑过渡。 */
    IN_OR_BELOW_VIEWPORT_ANIMATE,
}

private class PostImageLayoutTracker {
    var coords: LayoutCoordinates? = null
    var boundsInWindow: Rect? = null
}

/**
 * 详情列表同帧单一滚动权威接口：统一仲裁向上插页锚定与首楼越顶图片高度变化补偿，
 * 保证同一未重测帧窗口内无论二者先后顺序如何，仅由单一权威合成最终 `(targetIndex, targetOffset)`。
 */
internal fun interface AboveViewportHeightCompensationAuthority {
    fun onAboveViewportImageHeightDelta(floorPostId: String, deltaPx: Int)
}

internal val LocalAboveViewportHeightCompensationAuthority =
    staticCompositionLocalOf<AboveViewportHeightCompensationAuthority?> { null }

internal fun classifyHeightChangeBranch(
    floorPostId: String,
    listState: LazyListState,
    imageBottomInViewportPx: Float?,
): ImageHeightChangeBranch {
    val visibleItems = listState.layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return ImageHeightChangeBranch.IN_OR_BELOW_VIEWPORT_ANIMATE
    val floorItem = visibleItems.firstOrNull { it.key == "floor:$floorPostId" }
        ?: return ImageHeightChangeBranch.ABOVE_FIRST_VISIBLE_FLOOR_SNAP
    // 严格从同一帧已布局的 layoutInfo.visibleItemsInfo 快照判定首可见项，
    // 避免与向上插页同帧提前改写的未重测 listState.firstVisibleItemIndex 跨代比较导致误分类。
    val firstVisibleItem = visibleItems.firstOrNull { it.offset + it.size > 0 } ?: visibleItems.first()
    return when {
        floorItem.index < firstVisibleItem.index -> ImageHeightChangeBranch.ABOVE_FIRST_VISIBLE_FLOOR_SNAP
        floorItem.index == firstVisibleItem.index && imageBottomInViewportPx != null && imageBottomInViewportPx <= 0.5f ->
            ImageHeightChangeBranch.ABOVE_VIEWPORT_COMPENSATE
        else -> ImageHeightChangeBranch.IN_OR_BELOW_VIEWPORT_ANIMATE
    }
}

internal fun computeImageBottomInViewport(
    imageCoords: LayoutCoordinates,
    listState: LazyListState,
    floorPostId: String,
): Float? {
    if (!imageCoords.isAttached) return null
    val layoutInfo = listState.layoutInfo
    val floorItem = layoutInfo.visibleItemsInfo.firstOrNull { it.key == "floor:$floorPostId" } ?: return null
    val viewportSize = layoutInfo.viewportSize
    val beforePadding = layoutInfo.beforeContentPadding.toFloat()
    val expectedItemTop = beforePadding + floorItem.offset

    var child: LayoutCoordinates = imageCoords
    var parent: LayoutCoordinates? = imageCoords.parentLayoutCoordinates
    while (parent != null) {
        if (parent.size == viewportSize && abs(parent.localPositionOf(child, Offset.Zero).y - expectedItemTop) <= 2f) {
            return parent.localPositionOf(imageCoords, Offset.Zero).y + imageCoords.size.height - beforePadding
        }
        child = parent
        parent = parent.parentLayoutCoordinates
    }
    parent = imageCoords.parentLayoutCoordinates
    while (parent != null) {
        if (parent.size == viewportSize) {
            return parent.localPositionOf(imageCoords, Offset.Zero).y + imageCoords.size.height - beforePadding
        }
        parent = parent.parentLayoutCoordinates
    }
    return imageCoords.positionInRoot().y + imageCoords.size.height - beforePadding
}

/**
 * 帖子正文图片块（严守 `E2a` 锁定的 5 参签名）：
 * - `http`/`https` 双层白名单守卫（渲染加载与看图出口）；
 * - 按 `ImageAspectRatioCache` → 解码真实尺寸 → `4:3` 占位并按容器像素尺寸解码；
 * - `HiMotion.effectsDefault()` 淡入与三分支无跳动滚动补偿；
 * - 系统流量节省+计费网络自动切点击加载、可取消加载中进度圈与原地失败重试。
 */
@Composable
fun PostImageBlock(
    image: ContentBlock.Image,
    floorPostId: String,
    listState: LazyListState,
    onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolvedUrls = remember(image.thumbUrl, image.fullUrl) { resolveImageUrls(image) }
    if (resolvedUrls == null) {
        InvalidImagePlaceholder(modifier)
        return
    }

    val context = LocalContext.current
    val imageLoader = currentHiImageLoader()
    val boundsRegistry = LocalImageViewerBoundsRegistry.current
    val scrollAuthority by rememberUpdatedState(LocalAboveViewportHeightCompensationAuthority.current)
    val threadGallery by rememberUpdatedState(LocalThreadImageGallery.current)
    val reducedMotion = HiTheme.isReducedMotion
    val displayUrl = resolvedUrls.displayUrl
    val viewerUrl = resolvedUrls.viewerUrl
    val verifiedViewerUrl = remember(viewerUrl) { sanitizeImageUrl(viewerUrl) }

    val initialMode = remember(context, displayUrl) { ImageLoadingPolicy.resolveForContext(context) }
    val initialRatio = remember(displayUrl, viewerUrl) {
        ImageAspectRatioCache.Default.resolveInitialAspectRatio(displayUrl, viewerUrl)
    }
    val aspectRatioAnim = remember(displayUrl) { Animatable(initialRatio) }
    val layoutTracker = remember(displayUrl) { PostImageLayoutTracker() }
    var containerWidthPx by remember(displayUrl) { mutableIntStateOf(0) }
    var activeLoadId by remember(displayUrl) { mutableIntStateOf(if (initialMode == ImageLoadMode.AutoLoad) 1 else 0) }
    var nextLoadId by remember(displayUrl) { mutableIntStateOf(2) }
    var loadState by remember(displayUrl) {
        mutableStateOf<PostImageLoadState>(
            if (initialMode == ImageLoadMode.AutoLoad) {
                PostImageLoadState.Loading(userInitiated = false)
            } else {
                PostImageLoadState.TapToLoad
            },
        )
    }

    val hasMeasuredWidth = containerWidthPx > 0
    val loadTrigger = remember(displayUrl, activeLoadId, imageLoader) {
        Triple(displayUrl, activeLoadId, imageLoader)
    }
    LaunchedEffect(loadTrigger, hasMeasuredWidth) {
        if (activeLoadId == 0 || !hasMeasuredWidth) return@LaunchedEffect
        val widthPx = containerWidthPx
        if (widthPx <= 0) return@LaunchedEffect
        val maxDecodeHeightPx = (widthPx / ImageAspectRatioCache.MIN_ASPECT_RATIO)
            .roundToInt()
            .coerceAtLeast(widthPx)
        val request = ImageRequest.Builder(context)
            .data(displayUrl)
            .size(Dimension.Pixels(widthPx), Dimension.Pixels(maxDecodeHeightPx))
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .build()

        val result = try {
            imageLoader.execute(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }

        if (result !is SuccessResult) {
            activeLoadId = 0
            loadState = PostImageLoadState.Failed
            return@LaunchedEffect
        }

        val decoded = result.image
        val cachedRatio = ImageAspectRatioCache.Default.put(displayUrl, decoded.width, decoded.height)
        val loadedState = PostImageLoadState.Loaded(
            image = decoded,
            instant = result.dataSource == DataSource.MEMORY_CACHE || reducedMotion,
        )
        val deltaPx = cachedRatio?.let { (widthPx / it) - (widthPx / aspectRatioAnim.value) } ?: 0f
        if (cachedRatio == null || abs(deltaPx) < 1f) {
            loadState = loadedState
            return@LaunchedEffect
        }

        val bottomInViewport = layoutTracker.coords?.let { computeImageBottomInViewport(it, listState, floorPostId) }
        when (classifyHeightChangeBranch(floorPostId, listState, bottomInViewport)) {
            ImageHeightChangeBranch.ABOVE_VIEWPORT_COMPENSATE -> {
                aspectRatioAnim.snapTo(cachedRatio)
                val deltaInt = deltaPx.roundToInt()
                val authority = scrollAuthority
                if (authority != null) {
                    authority.onAboveViewportImageHeightDelta(floorPostId, deltaInt)
                } else {
                    val targetOffset = (listState.firstVisibleItemScrollOffset + deltaInt).coerceAtLeast(0)
                    listState.requestScrollToItem(listState.firstVisibleItemIndex, targetOffset)
                }
                loadState = loadedState
            }
            ImageHeightChangeBranch.ABOVE_FIRST_VISIBLE_FLOOR_SNAP -> {
                aspectRatioAnim.snapTo(cachedRatio)
                loadState = loadedState
            }
            ImageHeightChangeBranch.IN_OR_BELOW_VIEWPORT_ANIMATE -> {
                loadState = loadedState
                if (reducedMotion) {
                    aspectRatioAnim.snapTo(cachedRatio)
                } else {
                    aspectRatioAnim.animateTo(targetValue = cachedRatio, animationSpec = HiMotion.spatialFast())
                }
            }
        }
    }

    val loaded = loadState as? PostImageLoadState.Loaded
    val animatedAlpha by animateFloatAsState(
        targetValue = if (loaded != null) 1f else 0f,
        animationSpec = HiMotion.effectsDefault(),
        label = "postImageFadeIn",
    )
    val cs = MaterialTheme.colorScheme
    DisposableEffect(boundsRegistry, verifiedViewerUrl) {
        onDispose { verifiedViewerUrl?.let { boundsRegistry?.remove(it, layoutTracker.coords) } }
    }

    val containerModifier = modifier
        .fillMaxWidth()
        .aspectRatio(aspectRatioAnim.value)
        .onGloballyPositioned { coords ->
            if (!coords.isAttached) return@onGloballyPositioned
            layoutTracker.coords = coords
            val w = coords.size.width
            if (w > 0 && containerWidthPx != w) containerWidthPx = w
            val topLeft = coords.positionInWindow()
            layoutTracker.boundsInWindow = Rect(topLeft, Size(w.toFloat(), coords.size.height.toFloat()))
            verifiedViewerUrl?.let { boundsRegistry?.update(it, coords) }
        }

    Surface(
        modifier = containerModifier,
        shape = HiShapeTokens.Medium,
        color = cs.surfaceContainerLow,
        border = BorderStroke(0.5.dp, cs.outlineVariant),
    ) {
        when (val current = loadState) {
            is PostImageLoadState.Loaded -> {
                val painter = remember(current.image, context) { current.image.asPainter(context) }
                val imageAlpha = if (current.instant) 1f else animatedAlpha
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                            role = Role.Image,
                            onClick = {
                                val url = verifiedViewerUrl ?: return@clickable
                                onOpenImageViewer(
                                    buildGalleryLaunchArgs(
                                        clickedUrl = url,
                                        floorPostId = floorPostId,
                                        gallery = threadGallery,
                                        sourceBoundsInWindow = layoutTracker.boundsInWindow,
                                    ),
                                )
                            },
                        ),
                ) {
                    Image(
                        painter = painter,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = imageAlpha },
                    )
                }
            }
            PostImageLoadState.TapToLoad -> TapToLoadContent(
                fileSizeBytes = image.fileSizeBytes,
                onTapLoad = {
                    loadState = PostImageLoadState.Loading(userInitiated = true)
                    activeLoadId = nextLoadId++
                },
            )
            is PostImageLoadState.Loading -> if (current.userInitiated) {
                CancellableLoadingContent(
                    onCancel = {
                        activeLoadId = 0
                        loadState = PostImageLoadState.TapToLoad
                    },
                )
            } else {
                Box(Modifier.fillMaxSize().hiSkeleton(shape = HiShapeTokens.Medium))
            }
            PostImageLoadState.Failed -> FailedImageContent(
                onRetry = {
                    loadState = PostImageLoadState.Loading(userInitiated = initialMode == ImageLoadMode.ClickToLoad)
                    activeLoadId = nextLoadId++
                },
            )
        }
    }
}

@Composable
private fun InvalidImagePlaceholder(modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth().aspectRatio(ImageAspectRatioCache.DEFAULT_ASPECT_RATIO),
        shape = HiShapeTokens.Medium,
        color = cs.surfaceContainerLow,
        border = BorderStroke(0.5.dp, cs.outlineVariant),
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.thread_media_load_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TapToLoadContent(fileSizeBytes: Long, onTapLoad: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val sizeText = remember(fileSizeBytes) { ImageSizeFormatter.formatOrNull(fileSizeBytes) }
    val label = if (sizeText != null) {
        stringResource(R.string.thread_media_tap_to_load_with_size, sizeText)
    } else {
        stringResource(R.string.thread_detail_image_placeholder)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .defaultMinSize(minHeight = 48.dp)
            .hiPressScale(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onTapLoad)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CancellableLoadingContent(onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cancelDesc = stringResource(R.string.thread_media_loading_cancel)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = cancelDesc
                role = Role.Button
            }
            .hiPressScale(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            HiLoadingSpinner(modifier = Modifier.size(40.dp), strokeWidth = 2.5.dp)
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun FailedImageContent(onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val retryInteraction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.thread_media_load_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(
            onClick = onRetry,
            interactionSource = retryInteraction,
            shape = HiShapeTokens.Small,
            modifier = Modifier.defaultMinSize(minWidth = 88.dp, minHeight = 48.dp).hiPressScale(retryInteraction),
        ) {
            Text(stringResource(R.string.common_action_retry))
        }
    }
}
