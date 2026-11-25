package net.jejer.hipda.feature.threaddetail.media

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.Immutable
import net.jejer.hipda.parser.threaddetail.ContentBlock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 帖子图片 URL 严格 scheme 白名单（闭合 `R-E2a R2-S1` + 界面卡安全条款）。
 *
 * 仅放行经 `OkHttp HttpUrl` 校验且含非空 host 的 `http` / `https` 地址；
 * 拒绝 `file:`、`content:`、`javascript:`、`intent:`、`data:`、`mailto:`、无 scheme、协议相对路径、
 * 前后空白及任意 ISO 控制字符。
 */
fun sanitizeImageUrl(rawUrl: String?): String? {
    if (rawUrl.isNullOrEmpty()) return null
    if (rawUrl.first().isWhitespace() || rawUrl.last().isWhitespace()) return null
    if (rawUrl.any { it.isISOControl() }) return null
    if (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true)) {
        return null
    }
    val parsed = rawUrl.toHttpUrlOrNull() ?: return null
    if ((parsed.scheme != "http" && parsed.scheme != "https") || parsed.host.isBlank()) return null
    return parsed.toString()
}

/** 经白名单净化后的帖子图片地址对：`displayUrl` 优先合法缩略图，`viewerUrl` 优先合法原图。 */
@Immutable
data class ResolvedImageUrls(
    val displayUrl: String,
    val viewerUrl: String,
)

/** 解析并校验 [ContentBlock.Image] 的显示地址与看图原图地址；均不合法时返回 `null`。 */
fun resolveImageUrls(image: ContentBlock.Image): ResolvedImageUrls? {
    val safeThumb = sanitizeImageUrl(image.thumbUrl)
    val safeFull = sanitizeImageUrl(image.fullUrl)
    val display = safeThumb ?: safeFull ?: return null
    val viewer = safeFull ?: safeThumb ?: return null
    return ResolvedImageUrls(displayUrl = display, viewerUrl = viewer)
}

/** 帖子图片加载策略（`product-design.md` §7.9）。 */
enum class ImageLoadMode {
    AutoLoad,
    ClickToLoad,
}

/** 系统网络计费与流量节省状态快照。 */
@Immutable
data class ImageNetworkState(
    val isMetered: Boolean,
    val isDataSaverEnabled: Boolean,
)

/** 自动流量节省判定：仅当系统“流量节省程序”开启且当前为计费网络时切点击加载，其余默认自动加载。 */
object ImageLoadingPolicy {
    fun resolveLoadMode(isMetered: Boolean, isDataSaverEnabled: Boolean): ImageLoadMode =
        if (isMetered && isDataSaverEnabled) ImageLoadMode.ClickToLoad else ImageLoadMode.AutoLoad

    fun resolveLoadMode(networkState: ImageNetworkState): ImageLoadMode =
        resolveLoadMode(networkState.isMetered, networkState.isDataSaverEnabled)

    fun readNetworkState(context: Context): ImageNetworkState {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return ImageNetworkState(isMetered = false, isDataSaverEnabled = false)
        return readNetworkState(cm)
    }

    fun readNetworkState(connectivityManager: ConnectivityManager): ImageNetworkState {
        val metered = runCatching { connectivityManager.isActiveNetworkMetered }.getOrDefault(false)
        val dataSaver = runCatching {
            connectivityManager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }.getOrDefault(false)
        return ImageNetworkState(isMetered = metered, isDataSaverEnabled = dataSaver)
    }

    fun resolveForContext(context: Context): ImageLoadMode = resolveLoadMode(readNetworkState(context))
}
