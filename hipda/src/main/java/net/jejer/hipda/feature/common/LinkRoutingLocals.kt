package net.jejer.hipda.feature.common

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/** 帖子正文链接统一分发器（先严格 scheme 白名单，再优先站内应用内路由，站外/mailto 安全交由系统）。 */
@Stable
fun interface PostLinkDispatcher {
    fun dispatch(rawUrl: String): Boolean
}

/** 帖子附件鉴权下载处理器（仅允许 http/https，经项目 OkHttp 携 Cookie 下载并保存）。 */
@Stable
fun interface AttachmentDownloadHandler {
    fun download(url: String, suggestedFileName: String?)
}

/** 默认 `null` 保持未包裹宿主与既有单测 100% 兼容。 */
val LocalPostLinkDispatcher = staticCompositionLocalOf<PostLinkDispatcher?> { null }

/** 默认 `null` 保持未包裹宿主与既有单测 100% 兼容。 */
val LocalAttachmentDownloadHandler = staticCompositionLocalOf<AttachmentDownloadHandler?> { null }
