package net.jejer.hipda.feature.profile

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextDecoration
import net.jejer.hipda.feature.common.SafeLinkPolicy

/** 用户资料页链接、签名与头像/图片 URL 严格 scheme 白名单（委托统一 [SafeLinkPolicy]）。 */
object ProfileUrlSanitizer {
    private val BBCODE_URL_REGEX = Regex("""(?i)\[url=([^\]]+)](.*?)\[/url]""")
    private val SCHEME_TOKEN_REGEX = Regex("""([A-Za-z][A-Za-z0-9+.-]*:[^\s\u3000<>\]\)"']+)""")
    private val IMG_TAG_REGEX = Regex("""(?i)\[img]([^\[\]]+)\[/img]""")
    private val TRAILING_PUNCT = setOf('.', ',', ';', '!', '?', ')', ']', '，', '。', '；', '！', '？', '）', '】')

    fun sanitizeProfileLinkUrl(rawUrl: String?): String? = SafeLinkPolicy.sanitizeLinkUrl(rawUrl)

    fun sanitizeProfileImageUrl(rawUrl: String?): String? = SafeLinkPolicy.sanitizeHttpUrl(rawUrl)

    fun extractSafeSignatureImageUrls(text: String): List<String> =
        IMG_TAG_REGEX.findAll(text).mapNotNull { sanitizeProfileImageUrl(it.groupValues[1].trim()) }.distinct().toList()

    fun buildSafeAnnotatedText(rawText: String, linkColor: Color, onLinkClick: (String) -> Unit): AnnotatedString {
        val text = IMG_TAG_REGEX.replace(rawText, "").trim()
        if (text.isEmpty()) return AnnotatedString("")
        return buildAnnotatedString {
            var cursor = 0
            for (bb in BBCODE_URL_REGEX.findAll(text)) {
                if (bb.range.first > cursor) appendPlainWithLinks(text.substring(cursor, bb.range.first), linkColor, onLinkClick)
                val targetUrl = bb.groupValues[1].trim()
                val label = bb.groupValues[2].ifBlank { targetUrl }
                val safe = sanitizeProfileLinkUrl(targetUrl)
                if (safe != null) appendSafeLink(label, safe, linkColor, onLinkClick) else append(label)
                cursor = bb.range.last + 1
            }
            if (cursor < text.length) appendPlainWithLinks(text.substring(cursor), linkColor, onLinkClick)
        }
    }

    private fun AnnotatedString.Builder.appendPlainWithLinks(segment: String, linkColor: Color, onLinkClick: (String) -> Unit) {
        var cursor = 0
        for (m in SCHEME_TOKEN_REGEX.findAll(segment)) {
            val start = m.range.first
            var end = m.range.last + 1
            while (end > start && segment[end - 1] in TRAILING_PUNCT) end--
            if (start > cursor) append(segment.substring(cursor, start))
            val candidate = segment.substring(start, end)
            val safe = sanitizeProfileLinkUrl(candidate)
            if (safe != null) appendSafeLink(candidate, safe, linkColor, onLinkClick) else append(candidate)
            cursor = end
        }
        if (cursor < segment.length) append(segment.substring(cursor))
    }

    private fun AnnotatedString.Builder.appendSafeLink(label: String, safeUrl: String, linkColor: Color, onLinkClick: (String) -> Unit) {
        val annotation = LinkAnnotation.Url(
            url = safeUrl,
            styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
            linkInteractionListener = LinkInteractionListener { onLinkClick(safeUrl) },
        )
        withLink(annotation) { append(label) }
    }
}

@Immutable
data class ProfileDetailSection(val title: String?, val lines: List<String>)

@Immutable
data class ParsedProfileDetails(val headerStats: List<String>, val sections: List<ProfileDetailSection>)

fun parseProfileDetails(detail: String): ParsedProfileDetails {
    if (detail.isBlank()) return ParsedProfileDetails(emptyList(), emptyList())
    val sections = mutableListOf<ProfileDetailSection>()
    var pendingTitle: String? = null
    for (block in detail.split(Regex("\n{2,}")).map(String::trim).filter(String::isNotEmpty)) {
        val lines = block.lines().map(String::trim).filter(String::isNotEmpty)
        if (lines.size == 1 && ':' !in lines[0] && '：' !in lines[0]) {
            if (pendingTitle != null) sections += ProfileDetailSection(pendingTitle, emptyList())
            pendingTitle = lines[0]
        } else {
            sections += ProfileDetailSection(pendingTitle, lines)
            pendingTitle = null
        }
    }
    if (pendingTitle != null) sections += ProfileDetailSection(pendingTitle, emptyList())
    val nonEmpty = sections.filter { it.lines.isNotEmpty() }
    val allLines = nonEmpty.flatMap { it.lines }
    val stats = listOf("用户组", "积分", "帖子", "注册日期").mapNotNull { p -> allLines.firstOrNull { it.startsWith(p) } }.take(3).ifEmpty { allLines.take(2) }
    return ParsedProfileDetails(stats, nonEmpty)
}
