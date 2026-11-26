package net.jejer.hipda.feature.threaddetail.interact

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.feature.threaddetail.body.sanitizePostUrl
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiAnimateContentSize

internal const val QUOTE_COLLAPSED_MAX_LINES = 4

private val QUOTE_URI_TOKEN_REGEX = Regex("""(?i)\b[a-z][a-z0-9+.-]*:[^\s<>"'\]）)]+""")
private val TRAILING_PUNCTUATION = charArrayOf('.', ',', '!', '?', '。', '，', '！', '？')

/** 将引用纯文本转为 `AnnotatedString`：对文本中出现的链接严格走 [sanitizePostUrl] 白名单，危险 scheme 退化为纯文本。 */
internal fun buildQuoteContentAnnotatedString(
    contentText: String,
    linkColor: Color,
    onLinkClick: ((String) -> Unit)? = null,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in QUOTE_URI_TOKEN_REGEX.findAll(contentText)) {
        val trimmedToken = match.value.trimEnd(*TRAILING_PUNCTUATION)
        if (trimmedToken.isEmpty()) continue
        if (match.range.first > cursor) append(contentText.substring(cursor, match.range.first))
        val safeUrl = sanitizePostUrl(trimmedToken)
        if (safeUrl != null) {
            val style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
            val annotation = LinkAnnotation.Url(safeUrl, TextLinkStyles(style = style), LinkInteractionListener { onLinkClick?.invoke(safeUrl) })
            withLink(annotation) { append(trimmedToken) }
        } else {
            append(trimmedToken)
        }
        val trailingStart = match.range.first + trimmedToken.length
        if (trailingStart <= match.range.last) append(contentText.substring(trailingStart, match.range.last + 1))
        cursor = match.range.last + 1
    }
    if (cursor < contentText.length) append(contentText.substring(cursor))
}

/**
 * 楼层引用块（`product-design.md` §6.3、§7.3）：超 4 行默认折叠并以 `HiMotion.spatialDefault()` 自顶向下展开（减动效 0ms）；
 * 短引用点击（或长引用点击作者行/展开后点击卡片）经 [onJumpToFloor] 跳往被引楼层；链接经渲染/点击双层 [sanitizePostUrl] 守卫。
 */
@Composable
fun QuoteBlock(
    quote: ContentBlock.Quote,
    modifier: Modifier = Modifier,
    onJumpToFloor: ((targetPid: String, targetTid: String?) -> Unit)? = null,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val reducedMotion = HiTheme.isReducedMotion
    val uriHandler = LocalUriHandler.current
    val resolvedLinkHandler: (String) -> Unit = remember(onLinkClick, uriHandler) {
        { rawUrl -> sanitizePostUrl(rawUrl)?.let { if (onLinkClick != null) onLinkClick(it) else runCatching { uriHandler.openUri(it) } } }
    }

    val content = remember(quote.contentText) { quote.contentText.trim() }
    val quoteKey = remember(quote.targetPid, quote.authorAndTime, content) { "${quote.targetPid.orEmpty()}:${quote.authorAndTime}:${content.hashCode()}" }
    var expanded by rememberSaveable(quoteKey) { mutableStateOf(false) }
    var isTruncated by remember(quoteKey) { mutableStateOf(false) }
    val annotatedContent = remember(content, cs.primary, resolvedLinkHandler) { buildQuoteContentAnnotatedString(content, cs.primary, resolvedLinkHandler) }

    val targetPid = quote.targetPid?.takeIf { it.isNotBlank() }
    val canJump = targetPid != null && onJumpToFloor != null
    val jumpAction: () -> Unit = { if (targetPid != null) onJumpToFloor?.invoke(targetPid, quote.targetTid) }
    val cardClickModifier = when {
        isTruncated && !expanded -> Modifier.clickable(onClickLabel = stringResource(R.string.thread_detail_quote_expand)) { expanded = true }
        canJump -> Modifier.clickable(onClickLabel = stringResource(R.string.thread_detail_quote_jump), onClick = jumpAction)
        else -> Modifier
    }
    val sizeAnimModifier = if (reducedMotion) Modifier else Modifier.hiAnimateContentSize(HiMotion.spatialDefault(IntSize.VisibilityThreshold), Alignment.TopStart)

    Surface(modifier = modifier.fillMaxWidth().then(sizeAnimModifier), shape = HiShapeTokens.Medium, color = cs.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).then(cardClickModifier)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(cs.primary.copy(alpha = 0.65f)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (quote.authorAndTime.isNotBlank()) {
                    val headerJumpMod = if (canJump && isTruncated && !expanded) {
                        Modifier.clickable(onClickLabel = stringResource(R.string.thread_detail_quote_jump), onClick = jumpAction)
                    } else {
                        Modifier
                    }
                    Text(quote.authorAndTime, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = headerJumpMod)
                }
                if (content.isNotEmpty()) {
                    Text(
                        text = annotatedContent, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface,
                        maxLines = if (expanded) Int.MAX_VALUE else QUOTE_COLLAPSED_MAX_LINES, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!expanded) isTruncated = it.hasVisualOverflow },
                    )
                }
                if (isTruncated || expanded) {
                    Text(
                        text = stringResource(if (expanded) R.string.thread_detail_quote_collapse else R.string.thread_detail_quote_expand),
                        style = MaterialTheme.typography.labelMedium, color = cs.primary,
                        modifier = Modifier.clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}
