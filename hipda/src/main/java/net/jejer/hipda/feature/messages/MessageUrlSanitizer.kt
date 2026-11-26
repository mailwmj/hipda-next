package net.jejer.hipda.feature.messages

import androidx.compose.runtime.Immutable
import net.jejer.hipda.emoji.EmojiMap
import net.jejer.hipda.parser.common.DiscuzTextDecoder
import net.jejer.hipda.parser.threaddetail.ThreadDetailParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

private val MAILTO_ADDRESS_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$")
private val PLAIN_HTTP_URL_REGEX = Regex("""https?://[^\s<>"'\]\[(){}\u3000-\u303F\uFF00-\uFFEF]+""", RegexOption.IGNORE_CASE)
private val SORTED_EMOJI_CODES: List<String> by lazy { EmojiMap.groups.flatMap { it.codes }.distinct().sortedByDescending { it.length } }

/**
 * 短消息链接严格 scheme 白名单（渲染层与点击层共用）：仅放行 `http`、`https` 与经校验的 `mailto:<email>`；
 * 拒绝 `javascript:`、`intent:`、`file:`、`content:`、`data:`、大小写/空白/控制字符/零宽字符绕过及相对路径。
 */
fun sanitizeMessageLinkUrl(rawUrl: String?): String? {
    if (rawUrl.isNullOrEmpty() || hasDisallowedUrlChar(rawUrl)) return null
    if (rawUrl.startsWith("mailto:", ignoreCase = true)) {
        val addr = rawUrl.substring(7)
        val at = addr.indexOf('@')
        val valid = addr.isNotEmpty() && !addr.contains("..") && at > 0 && at == addr.lastIndexOf('@') &&
            !addr.startsWith('.') && addr[at - 1] != '.' && MAILTO_ADDRESS_REGEX.matches(addr)
        return if (valid) "mailto:$addr" else null
    }
    return sanitizeHttpOrHttpsUrl(rawUrl)
}

/** 短消息图片与头像 URL 严格 scheme 白名单：仅放行 `http` 与 `https`（明确拒绝 `mailto:` 及全部危险 scheme）。 */
fun sanitizeMessageImageUrl(rawUrl: String?): String? =
    if (rawUrl.isNullOrEmpty() || hasDisallowedUrlChar(rawUrl)) null else sanitizeHttpOrHttpsUrl(rawUrl)

private fun sanitizeHttpOrHttpsUrl(rawUrl: String): String? {
    if (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true)) return null
    val parsed = rawUrl.toHttpUrlOrNull() ?: return null
    return if ((parsed.scheme == "http" || parsed.scheme == "https") && parsed.host.isNotBlank()) parsed.toString() else null
}

private fun hasDisallowedUrlChar(url: String): Boolean = url.any { ch ->
    ch.isISOControl() || ch.isWhitespace() || Character.isSpaceChar(ch) || ch == '\u200B' || ch == '\uFEFF'
}

@Immutable
data class SmsTextStyleFlags(val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false, val strikethrough: Boolean = false)

@Immutable
sealed interface SmsTextPart {
    @Immutable data class Plain(val text: String, val style: SmsTextStyleFlags = SmsTextStyleFlags()) : SmsTextPart
    @Immutable data class Link(val label: String, val safeUrl: String) : SmsTextPart
    @Immutable data class Emoji(val imageSrc: String?, val code: String?) : SmsTextPart
    data object LineBreak : SmsTextPart
}

@Immutable
sealed interface SmsBubbleSegment {
    @Immutable data class Text(val parts: List<SmsTextPart>) : SmsBubbleSegment
    @Immutable data class Image(val safeUrl: String) : SmsBubbleSegment
}

@Immutable
data class SmsBubbleContent(val segments: List<SmsBubbleSegment>)

/** 将短消息 HTML（或本地乐观纯文本）解析为不可变 [SmsBubbleContent]；全部链接与图片严格经白名单过滤。 */
fun parseSmsBubbleContent(contentHtml: String): SmsBubbleContent {
    if (contentHtml.isBlank()) return SmsBubbleContent(emptyList())
    val doc = Jsoup.parseBodyFragment(contentHtml)
    doc.select("script, style, iframe, object, embed").remove()
    val segments = mutableListOf<SmsBubbleSegment>()
    val textParts = mutableListOf<SmsTextPart>()

    fun flushText() {
        while (textParts.isNotEmpty() && textParts.first() == SmsTextPart.LineBreak) textParts.removeAt(0)
        while (textParts.isNotEmpty() && textParts.last() == SmsTextPart.LineBreak) textParts.removeAt(textParts.lastIndex)
        if (textParts.isNotEmpty()) { segments += SmsBubbleSegment.Text(textParts.toList()); textParts.clear() }
    }
    fun breakLine() { if (textParts.isNotEmpty() && textParts.last() != SmsTextPart.LineBreak) textParts += SmsTextPart.LineBreak }

    fun walk(node: Node, style: SmsTextStyleFlags) {
        when (node) {
            is TextNode -> appendPlainTextWithEmojisAndSafeLinks(DiscuzTextDecoder.decodeDoubleEscapedEntities(node.wholeText), style, textParts)
            is Element -> when (node.tagName().lowercase()) {
                "br" -> textParts += SmsTextPart.LineBreak
                "p", "div", "li", "blockquote" -> { breakLine(); node.childNodes().forEach { walk(it, style) }; breakLine() }
                "b", "strong" -> node.childNodes().forEach { walk(it, style.copy(bold = true)) }
                "i", "em" -> node.childNodes().forEach { walk(it, style.copy(italic = true)) }
                "u" -> node.childNodes().forEach { walk(it, style.copy(underline = true)) }
                "s", "del", "strike" -> node.childNodes().forEach { walk(it, style.copy(strikethrough = true)) }
                "a" -> {
                    val safeUrl = sanitizeMessageLinkUrl(node.attr("href"))
                    val label = DiscuzTextDecoder.decodeDoubleEscapedEntities(node.text()).trim()
                    when {
                        label.isEmpty() && node.select("img").isNotEmpty() -> node.childNodes().forEach { walk(it, style) }
                        safeUrl != null -> textParts += SmsTextPart.Link(label.ifEmpty { safeUrl }, safeUrl)
                        label.isNotEmpty() -> textParts += SmsTextPart.Plain(label, style)
                    }
                }
                "img" -> {
                    val rawSrc = node.attr("src")
                    val smileyKey = ThreadDetailParser.normalizeSmileyKey(rawSrc)
                    if (smileyKey != null) textParts += SmsTextPart.Emoji(smileyKey, EmojiMap.codeForImageSrc(smileyKey))
                    else sanitizeMessageImageUrl(rawSrc)?.let { flushText(); segments += SmsBubbleSegment.Image(it) }
                }
                else -> node.childNodes().forEach { walk(it, style) }
            }
        }
    }

    doc.body().childNodes().forEach { walk(it, SmsTextStyleFlags()) }
    flushText()
    return SmsBubbleContent(segments)
}

internal fun escapeHtmlText(plainText: String): String = buildString(plainText.length) {
    for (ch in plainText) append(when (ch) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\n' -> "<br>"; else -> ch })
}

private fun appendPlainTextWithEmojisAndSafeLinks(rawText: String, style: SmsTextStyleFlags, out: MutableList<SmsTextPart>) {
    rawText.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEachIndexed { idx, line ->
        if (idx > 0) out += SmsTextPart.LineBreak
        if (line.isEmpty()) return@forEachIndexed
        var cursor = 0
        for (match in PLAIN_HTTP_URL_REGEX.findAll(line)) {
            if (match.range.first > cursor) appendTextAndEmojis(line.substring(cursor, match.range.first), style, out)
            val candidate = match.value.trimEnd('.', ',', ';', '!', '?')
            val safeUrl = sanitizeMessageLinkUrl(candidate)
            if (safeUrl != null) {
                out += SmsTextPart.Link(candidate, safeUrl)
                match.value.substring(candidate.length).takeIf { it.isNotEmpty() }?.let { appendTextAndEmojis(it, style, out) }
            } else appendTextAndEmojis(match.value, style, out)
            cursor = match.range.last + 1
        }
        if (cursor < line.length) appendTextAndEmojis(line.substring(cursor), style, out)
    }
}

private fun appendTextAndEmojis(text: String, style: SmsTextStyleFlags, out: MutableList<SmsTextPart>) {
    var cursor = 0
    val buf = StringBuilder()
    while (cursor < text.length) {
        val ch = text[cursor]
        val matched = if (ch == ':' || ch == '{') SORTED_EMOJI_CODES.firstOrNull { text.startsWith(it, cursor) } else null
        if (matched != null) {
            if (buf.isNotEmpty()) { out += SmsTextPart.Plain(buf.toString(), style); buf.setLength(0) }
            out += SmsTextPart.Emoji(null, matched)
            cursor += matched.length
        } else {
            buf.append(ch); cursor++
        }
    }
    if (buf.isNotEmpty()) out += SmsTextPart.Plain(buf.toString(), style)
}
