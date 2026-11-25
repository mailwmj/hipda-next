package net.jejer.hipda.parser.common

/**
 * Discuz! 7.2 二次转义数字实体解码器。
 *
 * Discuz! 7.2 在 GBK 站点会将补充平面 Emoji 等字符二次转义为 `&amp;#128512;`；
 * 经 Jsoup 首次解码后文本中仍残留 `&#128512;` / `&#x1F600;`。
 * 本解码器仅解码合法十进制与十六进制数字字符引用，不解码 `&lt;`/`&gt;` 等命名实体，
 * 对非法码点（`<= 0`、`> 0x10FFFF`、孤立代理区 `0xD800..0xDFFF`）与残缺实体原样保留。
 */
object DiscuzTextDecoder {

    private val NUMERIC_ENTITY_REGEX = Regex("""&#(?:([0-9]+)|[xX]([0-9a-fA-F]+));""")

    fun decodeDoubleEscapedEntities(text: String): String {
        if (!text.contains("&#")) return text
        return NUMERIC_ENTITY_REGEX.replace(text) { match ->
            val decimalPart = match.groupValues[1]
            val hexPart = match.groupValues[2]
            val codePoint = if (decimalPart.isNotEmpty()) {
                decimalPart.toIntOrNull(10)
            } else {
                hexPart.toIntOrNull(16)
            }
            if (codePoint != null && isValidDecodedCodePoint(codePoint)) {
                String(Character.toChars(codePoint))
            } else {
                match.value
            }
        }
    }

    private fun isValidDecodedCodePoint(codePoint: Int): Boolean =
        codePoint in 1..Character.MAX_CODE_POINT &&
            codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code
}
