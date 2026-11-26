package net.jejer.hipda.feature.threaddetail.body

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.threaddetail.interact.QuoteBlock
import net.jejer.hipda.feature.threaddetail.media.PostImageBlock
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.TextFormat
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val SMALL_FONT_SCALE: Float = 0.85f
private const val EMOJI_INLINE_SCALE: Float = 1.4f
private const val SMALL_IMAGE_INLINE_SCALE: Float = 0.9f
private val MAILTO_ADDRESS_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$")

/**
 * 帖子正文链接与附件 URL 的严格 scheme 白名单纯函数（渲染层与点击层共用）。
 *
 * 仅放行：`http` / `https`（需经 `HttpUrl` 解析且含非空 host）以及经校验的 `mailto:`（仅允许合法纯邮箱地址，不含查询参数或注入字符）。
 * 其余一律返回 `null`（含 `javascript:`、`intent:`、`file:`、`content:`、`data:`、无 scheme、大小写变体、前导空白及控制字符）。
 */
fun sanitizePostUrl(rawUrl: String?): String? {
    if (rawUrl.isNullOrEmpty()) return null
    if (rawUrl.first().isWhitespace() || rawUrl.last().isWhitespace()) return null
    if (rawUrl.any { it.isISOControl() }) return null
    if (rawUrl.startsWith("mailto:", ignoreCase = true)) {
        val address = rawUrl.substring(7)
        return if (isValidMailtoAddress(address)) "mailto:$address" else null
    }
    if (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true)) {
        return null
    }
    val parsed = rawUrl.toHttpUrlOrNull() ?: return null
    if ((parsed.scheme != "http" && parsed.scheme != "https") || parsed.host.isBlank()) return null
    return parsed.toString()
}

private fun isValidMailtoAddress(address: String): Boolean {
    if (address.isEmpty() || address.contains("..")) return false
    val at = address.indexOf('@')
    if (at <= 0 || at != address.lastIndexOf('@')) return false
    val local = address.substring(0, at)
    if (local.startsWith('.') || local.endsWith('.')) return false
    return MAILTO_ADDRESS_REGEX.matches(address)
}

@Immutable
internal data class BuiltPostTextBlock(
    val annotatedString: AnnotatedString,
    val inlineSpecs: Map<String, InlineDrawableSpec>,
)

/** 将 `B8` `InlineSpan` 列表转为标准 `AnnotatedString`（`LinkAnnotation` + `InlineTextContent` + `T6` 渲染期调色）。 */
internal fun buildPostTextBlock(
    spans: List<InlineSpan>,
    baseFontSize: TextUnit,
    backgroundColor: Color,
    defaultTextColor: Color,
    linkColor: Color,
    fallbackMutedColor: Color,
    darkTheme: Boolean,
    onLinkClick: ((String) -> Unit)? = null,
): BuiltPostTextBlock {
    val inlineSpecs = LinkedHashMap<String, InlineDrawableSpec>()
    val smallFontSize = (baseFontSize.value * SMALL_FONT_SCALE).sp

    val annotated = buildAnnotatedString {
        for (span in spans) {
            when (span) {
                is InlineSpan.Plain -> if (span.format.isPlain) {
                    append(span.text)
                } else {
                    withStyle(span.format.toSpanStyle(smallFontSize, backgroundColor, defaultTextColor)) { append(span.text) }
                }
                is InlineSpan.Link -> {
                    val safeUrl = sanitizePostUrl(span.url)
                    if (safeUrl == null) {
                        if (span.isSmallFont) withStyle(SpanStyle(fontSize = smallFontSize)) { append(span.label) } else append(span.label)
                    } else {
                        val style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline, fontSize = if (span.isSmallFont) smallFontSize else TextUnit.Unspecified)
                        val annotation = LinkAnnotation.Url(safeUrl, TextLinkStyles(style = style), LinkInteractionListener { onLinkClick?.invoke(safeUrl) })
                        withLink(annotation) { append(span.label) }
                    }
                }
                is InlineSpan.Email -> {
                    val safeMailto = sanitizePostUrl("mailto:${span.email}")
                    if (safeMailto == null) {
                        append(span.email)
                    } else {
                        val annotation = LinkAnnotation.Url(safeMailto, TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)), LinkInteractionListener { onLinkClick?.invoke(safeMailto) })
                        withLink(annotation) { append(span.email) }
                    }
                }
                is InlineSpan.Emoji -> when (val resolved = resolveForumEmoji(span.normalizedKey, darkTheme)) {
                    is ResolvedForumEmoji.Drawable -> {
                        val id = "emoji:${span.normalizedKey}"
                        inlineSpecs.putIfAbsent(id, InlineDrawableSpec(resolved.drawableResId, resolved.code ?: span.normalizedKey, EMOJI_INLINE_SCALE))
                        appendInlineContent(id, resolved.code ?: "[${span.normalizedKey}]")
                    }
                    is ResolvedForumEmoji.FallbackText -> withStyle(SpanStyle(color = fallbackMutedColor)) { append(resolved.text) }
                }
                is InlineSpan.SmallImage -> resolveSmallImageDrawable(span.key)?.let { resId ->
                    val id = "small_img:${span.key}"
                    inlineSpecs.putIfAbsent(id, InlineDrawableSpec(resId, span.key, SMALL_IMAGE_INLINE_SCALE))
                    appendInlineContent(id, "[${span.key}]")
                }
                InlineSpan.LineBreak -> append('\n')
            }
        }
    }
    return BuiltPostTextBlock(annotated, inlineSpecs)
}

private fun TextFormat.toSpanStyle(smallFontSize: TextUnit, backgroundColor: Color, defaultTextColor: Color): SpanStyle = SpanStyle(
    color = if (rawColor != null) TextColorContrastAdjuster.resolveCssTextColor(rawColor, backgroundColor, defaultTextColor) else Color.Unspecified,
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    textDecoration = when {
        underline && strikethrough -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        underline -> TextDecoration.Underline
        strikethrough -> TextDecoration.LineThrough
        else -> null
    },
    fontSize = if (smallFont) smallFontSize else TextUnit.Unspecified,
)

/** 帖子楼层正文 Compose 原生渲染器（tech-plan §5 `T6`；`Image` 路由至 `media/PostImageBlock`，`Quote`/`GoToFloor` 路由至 `interact/QuoteBlock` 与跳楼回调）。 */
@Composable
fun PostBodyRenderer(
    blocks: List<ContentBlock>,
    floorPostId: String,
    listState: LazyListState,
    onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null,
    onJumpToFloor: ((targetPid: String, targetTid: String?) -> Unit)? = null,
) {
    if (blocks.isEmpty()) return
    val uriHandler = LocalUriHandler.current
    val resolvedLinkHandler: (String) -> Unit = remember(onLinkClick, uriHandler) {
        { rawUrl ->
            sanitizePostUrl(rawUrl)?.let { safeUrl ->
                if (onLinkClick != null) onLinkClick(safeUrl) else runCatching { uriHandler.openUri(safeUrl) }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            when (block) {
                is ContentBlock.Text -> PostTextBlockView(block.spans, resolvedLinkHandler)
                is ContentBlock.Quote -> QuoteBlock(block, onJumpToFloor = onJumpToFloor, onLinkClick = resolvedLinkHandler)
                is ContentBlock.GoToFloor -> PostGoToFloorBlockView(block, onJumpToFloor)
                is ContentBlock.Image -> PostImageBlock(block, floorPostId, listState, onOpenImageViewer)
                is ContentBlock.Attachment -> PostAttachmentBlockView(block, resolvedLinkHandler)
                is ContentBlock.Notice -> PostNoticeBlockView(block)
            }
        }
    }
}

@Composable
private fun PostTextBlockView(spans: List<InlineSpan>, onLinkClick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val darkTheme = HiTheme.state.darkTheme
    val bodyStyle = HiTheme.typography.postBody
    val built = remember(spans, bodyStyle.fontSize, cs.background, cs.onSurface, cs.primary, cs.onSurfaceVariant, darkTheme, onLinkClick) {
        buildPostTextBlock(spans, bodyStyle.fontSize, cs.background, cs.onSurface, cs.primary, cs.onSurfaceVariant, darkTheme, onLinkClick)
    }
    if (built.annotatedString.isEmpty()) return
    val inlineContent = remember(built.inlineSpecs, bodyStyle.fontSize) { buildInlineTextContentMap(built.inlineSpecs, bodyStyle.fontSize) }
    Text(text = built.annotatedString, inlineContent = inlineContent, style = bodyStyle, color = cs.onSurface, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PostGoToFloorBlockView(
    goTo: ContentBlock.GoToFloor,
    onJumpToFloor: ((targetPid: String, targetTid: String?) -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val targetPid = goTo.targetPid?.takeIf { it.isNotBlank() }
    val clickModifier = if (targetPid != null && onJumpToFloor != null) {
        Modifier.clickable(role = Role.Button) { onJumpToFloor(targetPid, goTo.targetTid) }
    } else {
        Modifier
    }
    Surface(shape = HiShapeTokens.Small, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
        Text(
            goTo.label,
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = clickModifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun PostAttachmentBlockView(attachment: ContentBlock.Attachment, onOpenUrl: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val safeUrl = remember(attachment.url) { sanitizePostUrl(attachment.url) }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(attachment.title, style = MaterialTheme.typography.labelLarge, color = if (safeUrl != null) cs.primary else cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            attachment.description?.trim()?.takeIf { it.isNotEmpty() }?.let { desc ->
                Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val mod = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
    val border = BorderStroke(0.5.dp, cs.outlineVariant)
    if (safeUrl != null) {
        Surface(onClick = { onOpenUrl(safeUrl) }, modifier = mod, shape = HiShapeTokens.Small, color = cs.surfaceContainerLow, border = border, content = content)
    } else {
        Surface(modifier = mod, shape = HiShapeTokens.Small, color = cs.surfaceContainerLow, border = border, content = content)
    }
}

@Composable
private fun PostNoticeBlockView(notice: ContentBlock.Notice) {
    val cs = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxWidth(), shape = HiShapeTokens.Small, color = cs.surfaceContainerLow) {
        Text(notice.message, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
