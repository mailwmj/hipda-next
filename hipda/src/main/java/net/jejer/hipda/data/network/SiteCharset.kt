package net.jejer.hipda.data.network

import java.nio.charset.Charset
import java.util.Locale

private const val META_SCAN_LIMIT_BYTES = 4096

private val GB18030: Charset = Charset.forName("GB18030")

private val GBK_ALIASES: Set<String> = setOf(
    "gbk", "x-gbk", "gb2312", "gb_2312", "gb_2312-80", "gb2312-80", "gb2312-1980",
    "csgb2312", "csiso58gb231280", "chinese", "iso-ir-58",
    "euc-cn", "euc_cn", "euccn", "x-euc-cn",
    "cp936", "ms936", "x-mswin-936", "windows-936", "936", "gb18030", "gb-18030",
)

private val CONTENT_TYPE_CHARSET_REGEX = Regex(
    pattern = """(?:^|[;\s,])charset\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s;,"']+))""",
    option = RegexOption.IGNORE_CASE,
)

private val HTML_COMMENT_REGEX = Regex(
    pattern = """<!--.*?-->""",
    option = RegexOption.DOT_MATCHES_ALL,
)

private val META_TAG_REGEX = Regex(
    pattern = """<meta\b([^>]*)>""",
    options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val HTML_ATTR_REGEX = Regex(
    pattern = """([a-zA-Z][-a-zA-Z0-9]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'/>]+))""",
)

/**
 * Decodes an HTTP response body into a [String].
 *
 * Resolution priority:
 * 1. `charset` parameter in [contentType]
 * 2. `<meta charset>` or `<meta http-equiv="Content-Type" content="...charset=...">` within the first 4 KB
 * 3. Default GBK (decoded with the GB18030 superset)
 */
fun decodeBody(bytes: ByteArray, contentType: String?): String {
    if (bytes.isEmpty()) return ""
    return String(bytes, resolveCharset(bytes, contentType))
}

internal fun resolveCharset(bytes: ByteArray, contentType: String?): Charset {
    resolveDeclaredCharset(extractCharsetFromContentType(contentType))?.let { return it }
    resolveCharsetFromHtmlMeta(bytes)?.let { return it }
    return GB18030
}

private fun extractCharsetFromContentType(contentType: String?): String? {
    if (contentType.isNullOrBlank()) return null
    val match = CONTENT_TYPE_CHARSET_REGEX.find(contentType) ?: return null
    return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
}

private fun resolveCharsetFromHtmlMeta(bytes: ByteArray): Charset? {
    val head = String(bytes, 0, minOf(bytes.size, META_SCAN_LIMIT_BYTES), Charsets.ISO_8859_1)
    val stripped = HTML_COMMENT_REGEX.replace(head, "")
    for (tagMatch in META_TAG_REGEX.findAll(stripped)) {
        val attrs = parseHtmlAttributes(tagMatch.groupValues[1])
        resolveDeclaredCharset(attrs["charset"])?.let { return it }
        if (attrs["http-equiv"].equals("content-type", ignoreCase = true)) {
            resolveDeclaredCharset(extractCharsetFromContentType(attrs["content"]))?.let { return it }
        }
    }
    return null
}

private fun parseHtmlAttributes(tagBody: String): Map<String, String> {
    val result = HashMap<String, String>()
    for (match in HTML_ATTR_REGEX.findAll(tagBody)) {
        val name = match.groupValues[1].lowercase(Locale.ROOT)
        val value = match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
        result.putIfAbsent(name, value)
    }
    return result
}

private fun resolveDeclaredCharset(rawName: String?): Charset? {
    val normalized = rawName?.trim()?.trim('"', '\'')?.lowercase(Locale.ROOT)
    if (normalized.isNullOrEmpty()) return null
    if (normalized in GBK_ALIASES) return GB18030
    if (normalized == "utf-8" || normalized == "utf8" || normalized == "unicode-1-1-utf-8") {
        return Charsets.UTF_8
    }
    val resolved = runCatching { Charset.forName(normalized) }.getOrNull() ?: return null
    return if (resolved.name().lowercase(Locale.ROOT) in GBK_ALIASES) GB18030 else resolved
}
