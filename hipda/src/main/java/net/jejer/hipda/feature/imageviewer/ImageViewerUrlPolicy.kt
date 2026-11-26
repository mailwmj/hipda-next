package net.jejer.hipda.feature.imageviewer

import net.jejer.hipda.feature.threaddetail.media.sanitizeImageUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 长图判定阈值：高宽比 `height / width > 2.5f`（product-design.md §6.3）。 */
const val LONG_IMAGE_HEIGHT_WIDTH_RATIO: Float = 2.5f

/**
 * 看图全链路图片 URL 严格 `http`/`https` 白名单。
 *
 * 复用 `feature/threaddetail/media/ImageLoadingPolicy.kt` 的 [sanitizeImageUrl]，并附加 Unicode 空白与零宽字符首尾拒绝。
 */
fun sanitizeViewerImageUrl(rawUrl: String?): String? {
    if (rawUrl.isNullOrEmpty()) return null
    if (isDisallowedBoundaryChar(rawUrl.first()) || isDisallowedBoundaryChar(rawUrl.last())) return null
    if (rawUrl.any { it.isISOControl() }) return null
    val schemeSep = rawUrl.indexOf("://")
    if (schemeSep <= 0) return null
    val authority = rawUrl.substring(schemeSep + 3)
    if (authority.isEmpty() || authority.startsWith('/') || authority.startsWith('\\')) return null
    return sanitizeImageUrl(rawUrl)
}

/** 根据已校验 URL 路径判断是否为 GIF 动图。 */
fun isGifUrl(rawUrl: String?): Boolean {
    val safeUrl = sanitizeViewerImageUrl(rawUrl) ?: return false
    return safeUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty().endsWith(".gif", ignoreCase = true)
}

/** 判定是否为长图（高宽比严格大于 [LONG_IMAGE_HEIGHT_WIDTH_RATIO]）。 */
fun isLongImage(width: Int, height: Int): Boolean =
    width > 0 && height > 0 && (height.toFloat() / width.toFloat()) > LONG_IMAGE_HEIGHT_WIDTH_RATIO

private fun isDisallowedBoundaryChar(ch: Char): Boolean =
    ch.isWhitespace() || Character.isSpaceChar(ch) || ch == '\u200B' || ch == '\uFEFF'
