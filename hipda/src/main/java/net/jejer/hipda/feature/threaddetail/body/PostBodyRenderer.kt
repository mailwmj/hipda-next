package net.jejer.hipda.feature.threaddetail.body

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
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
import net.jejer.hipda.feature.threaddetail.media.PostImageBlock
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.TextFormat
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme

private const val SMALL_FONT_SCALE: Float = 0.85f
private const val EMOJI_INLINE_SCALE: Float = 1.4f
private const val SMALL_IMAGE_INLINE_SCALE: Float = 0.9f

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
                    val style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline, fontSize = if (span.isSmallFont) smallFontSize else TextUnit.Unspecified)
                    val annotation = LinkAnnotation.Url(span.url, TextLinkStyles(style = style), onLinkClick?.let { cb -> LinkInteractionListener { cb(span.url) } })
                    withLink(annotation) { append(span.label) }
                }
                is InlineSpan.Email -> {
                    val mailUrl = "mailto:${span.email}"
                    val annotation = LinkAnnotation.Url(mailUrl, TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)), onLinkClick?.let { cb -> LinkInteractionListener { cb(mailUrl) } })
                    withLink(annotation) { append(span.email) }
                }
                is InlineSpan.Emoji -> when (val resolved = resolveForumEmoji(span.normalizedKey, darkTheme)) {
                    is ResolvedForumEmoji.Drawable -> {
                        val id = emojiInlineId(span.normalizedKey)
                        inlineSpecs.putIfAbsent(id, InlineDrawableSpec(resolved.drawableResId, resolved.code ?: span.normalizedKey, EMOJI_INLINE_SCALE))
                        appendInlineContent(id, resolved.code ?: "[${span.normalizedKey}]")
                    }
                    is ResolvedForumEmoji.FallbackText -> withStyle(SpanStyle(color = fallbackMutedColor)) { append(resolved.text) }
                }
                is InlineSpan.SmallImage -> resolveSmallImageDrawable(span.key)?.let { resId ->
                    val id = smallImageInlineId(span.key)
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

/** 帖子楼层正文 Compose 原生渲染器（tech-plan §5 `T6`；`Image` 路由至 `media/PostImageBlock` 接缝）。 */
@Composable
fun PostBodyRenderer(
    blocks: List<ContentBlock>,
    floorPostId: String,
    listState: LazyListState,
    onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null,
) {
    if (blocks.isEmpty()) return
    val uriHandler = LocalUriHandler.current
    val resolvedLinkHandler: (String) -> Unit = remember(onLinkClick, uriHandler) {
        onLinkClick ?: { url -> runCatching { uriHandler.openUri(url) }; Unit }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            when (block) {
                is ContentBlock.Text -> PostTextBlockView(block.spans, resolvedLinkHandler)
                is ContentBlock.Quote -> PostQuoteBlockView(block)
                is ContentBlock.GoToFloor -> PostGoToFloorBlockView(block)
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
    val built = remember(spans, bodyStyle.fontSize, cs.surface, cs.onSurface, cs.primary, cs.onSurfaceVariant, darkTheme, onLinkClick) {
        buildPostTextBlock(spans, bodyStyle.fontSize, cs.surface, cs.onSurface, cs.primary, cs.onSurfaceVariant, darkTheme, onLinkClick)
    }
    if (built.annotatedString.isEmpty()) return
    val inlineContent = remember(built.inlineSpecs, bodyStyle.fontSize) { buildInlineTextContentMap(built.inlineSpecs, bodyStyle.fontSize) }
    Text(text = built.annotatedString, inlineContent = inlineContent, style = bodyStyle, color = cs.onSurface, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PostQuoteBlockView(quote: ContentBlock.Quote) {
    val cs = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxWidth(), shape = HiShapeTokens.Medium, color = cs.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(cs.primary.copy(alpha = 0.65f)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (quote.authorAndTime.isNotBlank()) {
                    Text(quote.authorAndTime, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val content = quote.contentText.trim()
                if (content.isNotEmpty()) {
                    Text(content, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PostGoToFloorBlockView(goTo: ContentBlock.GoToFloor) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = HiShapeTokens.Small, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
        Text(goTo.label, style = MaterialTheme.typography.labelMedium, color = cs.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun PostAttachmentBlockView(attachment: ContentBlock.Attachment, onOpenUrl: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(HiShapeTokens.Small)
            .background(cs.surfaceContainerLow)
            .border(0.5.dp, cs.outlineVariant, HiShapeTokens.Small)
            .clickable(role = Role.Button) { onOpenUrl(attachment.url) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(attachment.title, style = MaterialTheme.typography.labelLarge, color = cs.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        attachment.description?.trim()?.takeIf { it.isNotEmpty() }?.let { desc ->
            Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PostNoticeBlockView(notice: ContentBlock.Notice) {
    val cs = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxWidth(), shape = HiShapeTokens.Small, color = cs.surfaceContainerLow) {
        Text(notice.message, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
