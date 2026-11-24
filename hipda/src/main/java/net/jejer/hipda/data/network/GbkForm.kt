package net.jejer.hipda.data.network

import java.nio.charset.Charset
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

private val GBK: Charset = Charset.forName("GBK")
private val GBK_FORM_MEDIA_TYPE: MediaType =
    "application/x-www-form-urlencoded; charset=GBK".toMediaType()
private val HEX_DIGITS: CharArray = "0123456789ABCDEF".toCharArray()

/**
 * Replaces every Unicode code point that cannot be encoded in GBK (including Emoji and
 * GB18030-only CJK characters) with its decimal HTML numeric character reference `&#<cp>;`.
 */
fun toGbkSafe(text: String): String {
    if (text.isEmpty()) return text
    val encoder = GBK.newEncoder()
    val sb = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val charCount = Character.charCount(codePoint)
        val canEncode = if (charCount == 1) {
            encoder.canEncode(text[index])
        } else {
            encoder.canEncode(text.subSequence(index, index + charCount))
        }
        if (canEncode) {
            sb.append(text, index, index + charCount)
        } else {
            sb.append("&#").append(codePoint).append(';')
        }
        index += charCount
    }
    return sb.toString()
}

/**
 * Builds an `application/x-www-form-urlencoded; charset=GBK` [RequestBody].
 *
 * Field names and values are converted via [toGbkSafe], encoded as GBK bytes, and percent-encoded
 * with spaces mapped to `+` and reserved bytes escaped as uppercase `%XX`.
 */
fun gbkFormBody(fields: List<Pair<String, String>>): RequestBody {
    val encoded = buildString {
        fields.forEachIndexed { idx, (name, value) ->
            if (idx > 0) append('&')
            appendGbkFormComponent(name)
            append('=')
            appendGbkFormComponent(value)
        }
    }
    return encoded.toByteArray(Charsets.US_ASCII).toRequestBody(GBK_FORM_MEDIA_TYPE)
}

private fun StringBuilder.appendGbkFormComponent(raw: String) {
    val bytes = toGbkSafe(raw).toByteArray(GBK)
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        when {
            value == ' '.code -> append('+')
            isFormUnreserved(value) -> append(value.toChar())
            else -> {
                append('%')
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0F])
            }
        }
    }
}

private fun isFormUnreserved(byteValue: Int): Boolean =
    byteValue in 'a'.code..'z'.code ||
        byteValue in 'A'.code..'Z'.code ||
        byteValue in '0'.code..'9'.code ||
        byteValue == '*'.code ||
        byteValue == '-'.code ||
        byteValue == '.'.code ||
        byteValue == '_'.code
