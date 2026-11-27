package net.jejer.hipda.feature.threaddetail.media

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Rect
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.ImageViewerPageMeta
import net.jejer.hipda.feature.common.imageViewerBoundsKey
import net.jejer.hipda.parser.threaddetail.ContentBlock

/** 同帖看图多图横滑条目（由 [extractThreadGalleryItems] 按楼层与内容块顺序提取）。 */
@Immutable
data class ThreadGalleryItem(
    val url: String,
    val floorPostId: String,
    val floorNumber: Int,
    val author: String,
    val imageIndexInFloor: Int = 0,
)

/** 帖子详情页向下透传同帖全图列表的 CompositionLocal（未提供时默认空列表，自动安全回退单图）。 */
val LocalThreadImageGallery = compositionLocalOf<List<ThreadGalleryItem>> { emptyList() }

/**
 * 按楼层与内容块出现顺序提取同帖全部经 `http`/`https` 白名单校验的看图图片条目；
 * 自动跳过被屏蔽作者楼层及非法 scheme 图片，记录楼内图片序号以便跨楼层及同楼重复 URL 精确定位。
 */
fun extractThreadGalleryItems(floors: List<ThreadDetailFloorItem>): List<ThreadGalleryItem> = buildList {
    for (item in floors) {
        if (item.isAuthorBlocked) continue
        val floor = item.floor
        var imageIndexInFloor = 0
        for (block in floor.blocks) {
            if (block !is ContentBlock.Image) continue
            val resolved = resolveImageUrls(block) ?: continue
            val safeViewerUrl = sanitizeImageUrl(resolved.viewerUrl) ?: continue
            add(
                ThreadGalleryItem(
                    url = safeViewerUrl,
                    floorPostId = floor.postId,
                    floorNumber = floor.floor,
                    author = floor.author,
                    imageIndexInFloor = imageIndexInFloor++,
                ),
            )
        }
    }
}

/** 结合 [LocalThreadImageGallery] 构造多图 [ImageViewerLaunchArgs]；图集未提供或未命中时安全回退单图。 */
internal fun buildGalleryLaunchArgs(
    clickedUrl: String,
    floorPostId: String,
    gallery: List<ThreadGalleryItem>,
    sourceBoundsInWindow: Rect?,
    imageIndexInFloor: Int? = null,
    fallbackBoundsKey: String? = null,
): ImageViewerLaunchArgs {
    val safeGallery = gallery.mapNotNull { entry ->
        sanitizeImageUrl(entry.url)?.let { safeUrl -> entry.copy(url = safeUrl) }
    }
    val singleFallbackMeta = fallbackBoundsKey?.let { listOf(ImageViewerPageMeta(boundsKey = it)) }.orEmpty()
    if (safeGallery.isEmpty()) {
        return ImageViewerLaunchArgs(
            urls = listOf(clickedUrl),
            initialIndex = 0,
            sourceBoundsInWindow = sourceBoundsInWindow,
            pageMeta = singleFallbackMeta,
        )
    }
    val matchedIndex = imageIndexInFloor?.let { idx ->
        safeGallery.indexOfFirst { it.floorPostId == floorPostId && it.imageIndexInFloor == idx && it.url == clickedUrl }
            .takeIf { it >= 0 }
    } ?: safeGallery.indexOfFirst { it.floorPostId == floorPostId && it.url == clickedUrl }
        .takeIf { it >= 0 }
        ?: safeGallery.indexOfFirst { it.url == clickedUrl }
    if (matchedIndex < 0) {
        return ImageViewerLaunchArgs(
            urls = listOf(clickedUrl),
            initialIndex = 0,
            sourceBoundsInWindow = sourceBoundsInWindow,
            pageMeta = singleFallbackMeta,
        )
    }
    val matched = safeGallery[matchedIndex]
    return ImageViewerLaunchArgs(
        urls = safeGallery.map { it.url },
        initialIndex = matchedIndex,
        sourceBoundsInWindow = sourceBoundsInWindow,
        floorNumber = matched.floorNumber.takeIf { it > 0 },
        author = matched.author.takeIf { it.isNotBlank() },
        pageMeta = safeGallery.map { item ->
            ImageViewerPageMeta(
                floorNumber = item.floorNumber.takeIf { it > 0 },
                author = item.author.takeIf { it.isNotBlank() },
                boundsKey = imageViewerBoundsKey(item.floorPostId, item.imageIndexInFloor),
            )
        },
    )
}
