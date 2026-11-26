package net.jejer.hipda.feature.messages

import androidx.compose.runtime.Immutable
import net.jejer.hipda.emoji.EmojiMap
import net.jejer.hipda.feature.common.SafeLinkPolicy
import net.jejer.hipda.parser.common.DiscuzTextDecoder
import net.jejer.hipda.parser.threaddetail.ThreadDetailParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

private val PLAIN_HTTP_URL_REGEX = Regex("""https?://[^\s<>"'\]\[(){}\u3000-\u303F\uFF00-\uFFEF]+""", RegexOption.IGNORE_CASE)
private val COLLAPSE_SPACE_REGEX = Regex("\\s+")
private val SORTED_EMOJI_CODES: List<String> by lazy { EmojiMap.groups.flatMap { it.codes }.distinct().sortedByDescending { it.length } }

/** 短消息链接严格 scheme 白名单（委托统一 [SafeLinkPolicy.sanitizeLinkUrl]）。 */
fun sanitizeMessageLinkUrl(rawUrl: String?): String? = SafeLinkPolicy.sanitizeLinkUrl(rawUrl)

/** 短消息图片与头像 URL 严格 scheme 白名单（委托统一 [SafeLinkPolicy.sanitizeHttpUrl]）。 */
fun sanitizeMessageImageUrl(rawUrl: String?): String? = SafeLinkPolicy.sanitizeHttpUrl(rawUrl)

@Immutable
sealed interface SmsTextPart {
    @Immutable data class Plain(val text: String) : SmsTextPart
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

/** 直接解析本地纯文本短消息（免 HTML 转义往返），同样走安全链接与 `:emoji` C1b 扫描。 */
fun parseSmsBubblePlainText(plainText: String): SmsBubbleContent {
    if (plainText.isBlank()) return SmsBubbleContent(emptyList())
    val parts = mutableListOf<SmsTextPart>().also { appendPlainTextWithEmojisAndSafeLinks(plainText, it) }
    trimOuterLineBreaks(parts)
    return if (parts.isEmpty()) SmsBubbleContent(emptyList()) else SmsBubbleContent(listOf(SmsBubbleSegment.Text(parts)))
}

/** 将短消息 HTML 解析为不可变 [SmsBubbleContent]；全部链接与图片严格经白名单过滤。 */
fun parseSmsBubbleContent(contentHtml: String): SmsBubbleContent {
    if (contentHtml.isBlank()) return SmsBubbleContent(emptyList())
    val doc = Jsoup.parseBodyFragment(contentHtml).apply { select("script, style, iframe, object, embed").remove() }
    val segments = mutableListOf<SmsBubbleSegment>()
    val textParts = mutableListOf<SmsTextPart>()

    fun flushText() {
        trimOuterLineBreaks(textParts)
        if (textParts.isNotEmpty()) { segments += SmsBubbleSegment.Text(textParts.toList()); textParts.clear() }
    }
    fun breakLine() { if (textParts.isNotEmpty() && textParts.last() != SmsTextPart.LineBreak) textParts += SmsTextPart.LineBreak }

    fun walk(node: Node) {
        when (node) {
            is TextNode -> appendPlainTextWithEmojisAndSafeLinks(DiscuzTextDecoder.decodeDoubleEscapedEntities(node.wholeText), textParts)
            is Element -> when (node.tagName().lowercase()) {
                "br" -> textParts += SmsTextPart.LineBreak
                "p", "div", "li", "blockquote" -> { breakLine(); node.childNodes().forEach(::walk); breakLine() }
                "a" -> {
                    val safeUrl = sanitizeMessageLinkUrl(node.attr("href"))
                    val label = DiscuzTextDecoder.decodeDoubleEscapedEntities(node.text()).trim()
                    when {
                        label.isEmpty() && node.select("img").isNotEmpty() -> node.childNodes().forEach(::walk)
                        safeUrl != null -> textParts += SmsTextPart.Link(label.ifEmpty { safeUrl }, safeUrl)
                        label.isNotEmpty() -> textParts += SmsTextPart.Plain(label)
                    }
                }
                "img" -> {
                    val rawSrc = node.attr("src")
                    val smileyKey = ThreadDetailParser.normalizeSmileyKey(rawSrc)
                    if (smileyKey != null) textParts += SmsTextPart.Emoji(smileyKey, EmojiMap.codeForImageSrc(smileyKey))
                    else sanitizeMessageImageUrl(rawSrc)?.let { flushText(); segments += SmsBubbleSegment.Image(it) }
                }
                else -> node.childNodes().forEach(::walk)
            }
        }
    }

    doc.body().childNodes().forEach(::walk)
    flushText()
    return SmsBubbleContent(segments)
}

/** 将服务端 HTML 或本地纯文本归一化为一致的可比对键，供本地乐观气泡与服务端返回去重对齐。 */
internal fun normalizeSmsCompareText(content: String, isPlainText: Boolean): String = buildString {
    val parsed = if (isPlainText) parseSmsBubblePlainText(content) else parseSmsBubbleContent(content)
    for (seg in parsed.segments) when (seg) {
        is SmsBubbleSegment.Image -> append("[img:${seg.safeUrl}]")
        is SmsBubbleSegment.Text -> for (p in seg.parts) append(when (p) {
            is SmsTextPart.Plain -> p.text; is SmsTextPart.Link -> p.label; is SmsTextPart.Emoji -> p.code ?: p.imageSrc.orEmpty(); SmsTextPart.LineBreak -> "\n"
        })
    }
}.trim().replace(COLLAPSE_SPACE_REGEX, " ")

private fun trimOuterLineBreaks(parts: MutableList<SmsTextPart>) {
    while (parts.isNotEmpty() && parts.first() == SmsTextPart.LineBreak) parts.removeAt(0)
    while (parts.isNotEmpty() && parts.last() == SmsTextPart.LineBreak) parts.removeAt(parts.lastIndex)
}

private fun appendPlainTextWithEmojisAndSafeLinks(rawText: String, out: MutableList<SmsTextPart>) {
    rawText.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEachIndexed { idx, line ->
        if (idx > 0) out += SmsTextPart.LineBreak
        if (line.isEmpty()) return@forEachIndexed
        var cursor = 0
        for (match in PLAIN_HTTP_URL_REGEX.findAll(line)) {
            if (match.range.first > cursor) appendTextAndEmojis(line.substring(cursor, match.range.first), out)
            val candidate = match.value.trimEnd('.', ',', ';', '!', '?')
            val safeUrl = sanitizeMessageLinkUrl(candidate)
            if (safeUrl != null) {
                out += SmsTextPart.Link(candidate, safeUrl)
                match.value.substring(candidate.length).takeIf { it.isNotEmpty() }?.let { appendTextAndEmojis(it, out) }
            } else appendTextAndEmojis(match.value, out)
            cursor = match.range.last + 1
        }
        if (cursor < line.length) appendTextAndEmojis(line.substring(cursor), out)
    }
}

private fun appendTextAndEmojis(text: String, out: MutableList<SmsTextPart>) {
    var cursor = 0
    val buf = StringBuilder()
    while (cursor < text.length) {
        val ch = text[cursor]
        val matched = if (ch == ':' || ch == '{') SORTED_EMOJI_CODES.firstOrNull { text.startsWith(it, cursor) } else null
        if (matched != null) {
            if (buf.isNotEmpty()) { out += SmsTextPart.Plain(buf.toString()); buf.setLength(0) }
            out += SmsTextPart.Emoji(null, matched)
            cursor += matched.length
        } else { buf.append(ch); cursor++ }
    }
    if (buf.isNotEmpty()) out += SmsTextPart.Plain(buf.toString())
}
