package net.jejer.hipda.data.auth

import java.security.MessageDigest
import java.util.Locale

private val MD5_HEX_REGEX: Regex = Regex("^[0-9a-fA-F]{32}$")
private val HEX_LOWER_CHARS: CharArray = "0123456789abcdef".toCharArray()

/**
 * Computes the legacy Discuz! login password MD5 digest matching `LoginHelper.java:170-181`.
 *
 * Rules:
 * - Throws [IllegalArgumentException] when [rawOrMd5] is empty (never echoing input in messages).
 * - If [rawOrMd5] is already a 32-character hexadecimal MD5 digest, returns it lowercased as-is.
 * - Otherwise escapes `\` to `\\` and `"` to `\"` (single quotes are intentionally NOT escaped,
 *   matching Java literal `"\'"` in `LoginHelper.java:176`), then returns the 32-character
 *   lowercase hexadecimal MD5 of the UTF-8 bytes.
 */
fun computeLegacyPasswordMd5(rawOrMd5: String): String {
    require(rawOrMd5.isNotEmpty()) { "Password must not be empty" }
    if (MD5_HEX_REGEX.matches(rawOrMd5)) {
        return rawOrMd5.lowercase(Locale.ROOT)
    }
    val escaped = rawOrMd5
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    val digest = MessageDigest.getInstance("MD5")
        .digest(escaped.toByteArray(Charsets.UTF_8))
    val chars = CharArray(digest.size * 2)
    for (i in digest.indices) {
        val value = digest[i].toInt() and 0xFF
        chars[i * 2] = HEX_LOWER_CHARS[value ushr 4]
        chars[i * 2 + 1] = HEX_LOWER_CHARS[value and 0x0F]
    }
    return String(chars)
}
