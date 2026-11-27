package net.jejer.hipda.data.auth

import android.util.Log
import net.jejer.hipda.BuildConfig

/** Debug-only (`BuildConfig.DEBUG`) redacted logger for login protocol stages (`HxLogin`). */
internal object LoginDiagnosticLogger {
    internal const val TAG = "HxLogin"
    private const val MAX_BODY_SNIPPET_CHARS = 400
    private const val REDACT_WINDOW_CHARS = 4096
    private const val REDACTED = "***"

    private val COOKIE_HEADER_REGEX = Regex("""(?i)\b(set-cookie|cookie)\s*:\s*[^\r\n<]+""")
    private val INPUT_NAME_BEFORE_VALUE_REGEX = Regex(
        """(?i)(<input\b[^>]*\b(?:name|id)\s*=\s*["']?(?:m_formhash|formhash|password|answer|username)["']?[^>]*\bvalue\s*=\s*)(?:"[^"]*"|'[^']*'|[^\s"'/>]+)""",
    )
    private val INPUT_VALUE_BEFORE_NAME_REGEX = Regex(
        """(?i)(<input\b[^>]*\bvalue\s*=\s*)(?:"[^"]*"|'[^']*'|[^\s"'/>]+)([^>]*\b(?:name|id)\s*=\s*["']?(?:m_formhash|formhash|password|answer|username)["']?)""",
    )
    private val SECRET_KV_REGEX = Regex(
        """(?i)\b(set-cookie|cookie|m_formhash|formhash|password|answer|username|[a-z0-9_-]*(?:auth|sid))\s*(?:=|%3d)\s*(?:"[^"]*"|'[^']*'|[^\s;&,"'<>)]+)""",
    )
    private val SECRET_JSON_REGEX = Regex(
        """(?i)(["']?(?:m_formhash|formhash|password|answer|username|[a-z0-9_-]*(?:auth|sid))["']?\s*:\s*)(?:"[^"]*"|'[^']*'|[^\s,}\r\n<>]+)""",
    )
    private val LINE_BREAK_REGEX = Regex("""[\r\n]+""")

    internal fun logWarning(
        stage: String,
        statusCode: Int,
        contentType: String?,
        body: String,
        knownSecrets: Collection<String> = emptyList(),
    ) {
        if (!BuildConfig.DEBUG) return
        Log.w(TAG, formatLine(stage, statusCode, contentType, body, knownSecrets))
    }

    internal fun formatLine(
        stage: String,
        statusCode: Int,
        contentType: String?,
        body: String,
        knownSecrets: Collection<String> = emptyList(),
    ): String {
        val safeCt = redact(contentType.orEmpty()).replace(LINE_BREAK_REGEX, " ").trim().ifEmpty { "none" }
        val safeBody = redact(body.take(REDACT_WINDOW_CHARS), knownSecrets).replace(LINE_BREAK_REGEX, " ").trim().take(MAX_BODY_SNIPPET_CHARS)
        return "stage=$stage status=$statusCode contentType=$safeCt body=$safeBody"
    }

    internal fun redact(raw: String, knownSecrets: Collection<String> = emptyList()): String {
        if (raw.isEmpty()) return raw
        var out = COOKIE_HEADER_REGEX.replace(raw, "$1: $REDACTED")
        out = INPUT_NAME_BEFORE_VALUE_REGEX.replace(out, "$1\"$REDACTED\"")
        out = INPUT_VALUE_BEFORE_NAME_REGEX.replace(out, "$1\"$REDACTED\"$2")
        out = SECRET_KV_REGEX.replace(out, "$1=$REDACTED")
        out = SECRET_JSON_REGEX.replace(out, "$1$REDACTED")
        for (secret in knownSecrets.asSequence().filter { it.isNotBlank() && !it.all { ch -> ch == '*' } }.sortedByDescending { it.length }) {
            out = out.replace(secret, REDACTED, ignoreCase = true)
        }
        return out
    }
}
