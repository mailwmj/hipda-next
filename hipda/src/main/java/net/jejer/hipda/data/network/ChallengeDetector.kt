package net.jejer.hipda.data.network

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Response

/** Result of inspecting an HTTP [Response] for a Cloudflare challenge page. */
sealed interface ChallengeSignal {
    data object None : ChallengeSignal

    data class Challenge(
        val statusCode: Int,
        val byHeader: Boolean,
    ) : ChallengeSignal
}

/**
 * Stateless, thread-safe detector for Cloudflare challenge responses.
 *
 * Detection rules:
 * - Rule A (authoritative): status in `{403, 429, 503}` and any `cf-mitigated` response header
 *   contains `challenge` (case-insensitive) -> [ChallengeSignal.Challenge] with `byHeader = true`.
 * - Rule B (body fallback, only when no `cf-mitigated` header is present): status in `{403, 503}`,
 *   `Content-Type` is empty or contains `text/html`, and the first [peekLimitBytes] bytes match a
 *   strong Cloudflare challenge marker -> [ChallengeSignal.Challenge] with `byHeader = false`.
 *
 * Uses [Response.peekBody] with ISO-8859-1 byte decoding so the original response body is never
 * consumed or closed and detection does not depend on GBK decoding.
 */
@Singleton
class ChallengeDetector(
    private val peekLimitBytes: Long = DEFAULT_PEEK_LIMIT_BYTES,
) {
    @Inject
    constructor() : this(DEFAULT_PEEK_LIMIT_BYTES)

    init {
        require(peekLimitBytes >= 0L) { "peekLimitBytes must be non-negative: $peekLimitBytes" }
    }

    fun inspect(response: Response): ChallengeSignal {
        val code = response.code
        if (code != 403 && code != 429 && code != 503) return ChallengeSignal.None

        val mitigatedHeaders = response.headers(HEADER_CF_MITIGATED)
        if (mitigatedHeaders.isNotEmpty()) {
            return if (mitigatedHeaders.any { it.contains(MITIGATED_CHALLENGE_TOKEN, ignoreCase = true) }) {
                ChallengeSignal.Challenge(statusCode = code, byHeader = true)
            } else {
                ChallengeSignal.None
            }
        }

        if (code != 403 && code != 503) return ChallengeSignal.None
        if (!isHtmlOrEmptyContentType(response)) return ChallengeSignal.None

        val snippet = peekBodyAscii(response) ?: return ChallengeSignal.None
        return if (hasStrongChallengeMarker(snippet)) {
            ChallengeSignal.Challenge(statusCode = code, byHeader = false)
        } else {
            ChallengeSignal.None
        }
    }

    private fun isHtmlOrEmptyContentType(response: Response): Boolean {
        val contentType = response.header(HEADER_CONTENT_TYPE) ?: response.body.contentType()?.toString()
        return contentType.isNullOrBlank() || contentType.contains(HTML_MEDIA_TYPE, ignoreCase = true)
    }

    private fun peekBodyAscii(response: Response): String? = try {
        response.peekBody(peekLimitBytes).use { peeked ->
            String(peeked.bytes(), Charsets.ISO_8859_1)
        }
    } catch (_: IOException) {
        null
    }

    private fun hasStrongChallengeMarker(snippet: String): Boolean {
        if (snippet.contains(MARKER_CF_CHL_OPT, ignoreCase = true)) return true
        if (snippet.contains(MARKER_CHALLENGE_PLATFORM_H, ignoreCase = true)) return true
        if (snippet.contains(MARKER_ORCHESTRATE_CHL_PAGE, ignoreCase = true)) return true
        return snippet.contains(MARKER_TITLE_JUST_A_MOMENT, ignoreCase = true) &&
            (
                snippet.contains(MARKER_CLOUDFLARE, ignoreCase = true) ||
                    snippet.contains(MARKER_CF_CHALLENGE, ignoreCase = true)
                )
    }

    companion object {
        const val DEFAULT_PEEK_LIMIT_BYTES: Long = 16L * 1024L

        private const val HEADER_CF_MITIGATED = "cf-mitigated"
        private const val HEADER_CONTENT_TYPE = "Content-Type"
        private const val MITIGATED_CHALLENGE_TOKEN = "challenge"
        private const val HTML_MEDIA_TYPE = "text/html"

        private const val MARKER_CF_CHL_OPT = "_cf_chl_opt"
        private const val MARKER_CHALLENGE_PLATFORM_H = "/cdn-cgi/challenge-platform/h/"
        private const val MARKER_ORCHESTRATE_CHL_PAGE = "orchestrate/chl_page"
        private const val MARKER_TITLE_JUST_A_MOMENT = "<title>Just a moment"
        private const val MARKER_CLOUDFLARE = "cloudflare"
        private const val MARKER_CF_CHALLENGE = "cf-challenge"
    }
}
