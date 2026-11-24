package net.jejer.hipda.data.network

import java.nio.charset.Charset
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaTypeOrNull

private const val META_SCAN_LIMIT_BYTES = 4096
private const val META_SCAN_HARD_LIMIT_BYTES = 8192

private val GB18030: Charset = Charset.forName("GB18030")
private val GBK_ALIASES: Set<String> = setOf(
    "gbk", "x-gbk", "gb2312", "gb_2312", "gb_2312-80", "gb2312-80", "gb2312-1980",
    "csgb2312", "csiso58gb231280", "chinese", "iso-ir-58",
    "euc-cn", "euc_cn", "euccn", "x-euc-cn",
    "cp936", "ms936", "x-mswin-936", "windows-936", "936", "gb18030", "gb-18030",
)

private val CONTENT_TYPE_CHARSET_REGEX = Regex("""(?:^|[;\s,])charset\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s;,"']+))""", RegexOption.IGNORE_CASE)
private val HTML_COMMENT_REGEX = Regex("""<!--(?:.*?-->|.*)""", RegexOption.DOT_MATCHES_ALL)
private val META_TAG_REGEX = Regex("""<meta\b([^>]*)>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_ATTR_REGEX = Regex("""([a-zA-Z][-a-zA-Z0-9]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'/>]+))""")

/**
 * Decodes an HTTP response body: UTF-8 BOM → Content-Type charset → HTML `<meta>` (4 KB) → GBK.
 * GBK/GB2312 aliases are decoded with the GB18030 superset.
 */
fun decodeBody(bytes: ByteArray, contentType: String?): String {
    if (bytes.isEmpty()) return ""
    if (hasUtf8Bom(bytes)) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    return String(bytes, resolveCharset(bytes, contentType))
}

internal fun resolveCharset(bytes: ByteArray, contentType: String?): Charset {
    if (hasUtf8Bom(bytes)) return Charsets.UTF_8
    resolveCharsetFromContentType(contentType)?.let { return it }
    resolveCharsetFromHtmlMeta(bytes)?.let { return it }
    return GB18030
}

private fun hasUtf8Bom(bytes: ByteArray): Boolean =
    bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()

private fun resolveCharsetFromContentType(contentType: String?): Charset? {
    if (contentType.isNullOrBlank()) return null
    contentType.toMediaTypeOrNull()?.let { return resolveDeclaredCharset(it.parameter("charset")) }
    for (match in CONTENT_TYPE_CHARSET_REGEX.findAll(contentType)) {
        val candidate = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
        resolveDeclaredCharset(candidate)?.let { return it }
    }
    return null
}

private fun resolveCharsetFromHtmlMeta(bytes: ByteArray): Charset? {
    val head = String(bytes, 0, computeMetaScanLimit(bytes), Charsets.ISO_8859_1)
    val stripped = HTML_COMMENT_REGEX.replace(head, "")
    for (tagMatch in META_TAG_REGEX.findAll(stripped)) {
        val attrs = parseHtmlAttributes(tagMatch.groupValues[1])
        resolveDeclaredCharset(attrs["charset"])?.let { return it }
        if (attrs["http-equiv"].equals("content-type", ignoreCase = true)) {
            resolveCharsetFromContentType(attrs["content"])?.let { return it }
        }
    }
    return null
}

private fun computeMetaScanLimit(bytes: ByteArray): Int {
    val base = minOf(bytes.size, META_SCAN_LIMIT_BYTES)
    if (bytes.size <= META_SCAN_LIMIT_BYTES) return base
    var openTag = false
    for (i in base - 1 downTo 0) {
        when (bytes[i]) {
            '>'.code.toByte() -> break
            '<'.code.toByte() -> { openTag = true; break }
        }
    }
    if (!openTag) return base
    val hard = minOf(bytes.size, META_SCAN_HARD_LIMIT_BYTES)
    for (i in base until hard) {
        if (bytes[i] == '>'.code.toByte()) return i + 1
    }
    return hard
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
