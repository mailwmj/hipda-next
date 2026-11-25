package net.jejer.hipda.ui.challenge

import java.util.Locale
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Snapshot of the hosted WebView main frame's navigation and Cloudflare challenge state. */
data class MainFrameState(
    val url: String? = null,
    val pageFinished: Boolean = false,
    val hasCfMitigatedChallengeHeader: Boolean = false,
    val httpErrorStatusCode: Int? = null,
    val hasMainFrameLoadError: Boolean = false,
) {
    /** True when the main frame HTTP response indicates an active Cloudflare challenge. */
    val isChallengedResponse: Boolean
        get() = hasCfMitigatedChallengeHeader ||
            httpErrorStatusCode == 403 ||
            httpErrorStatusCode == 429 ||
            httpErrorStatusCode == 503

    /** True when the main frame finished loading a non-blank, non-challenged, non-error document. */
    val isReadyNonChallengePage: Boolean
        get() = pageFinished &&
            !isChallengedResponse &&
            !hasMainFrameLoadError &&
            !url.isNullOrBlank() &&
            !url.equals("about:blank", ignoreCase = true)
}

/** Asynchronous events emitted by a hosted challenge WebView or its [ChallengeActivity]. */
sealed interface ChallengeWebViewEvent {
    data class PageFinished(val url: String?) : ChallengeWebViewEvent
    data object InteractiveBegin : ChallengeWebViewEvent
    data object InteractiveTimeout : ChallengeWebViewEvent
    data class RenderProcessGone(
        val didCrash: Boolean,
        val rendererPriorityAtExit: Int,
    ) : ChallengeWebViewEvent
    data object HostDestroyed : ChallengeWebViewEvent
}

/** Failure cause reported when the WebView renderer process exits during a challenge solve. */
class ChallengeRenderProcessGoneException(
    val didCrash: Boolean,
    val rendererPriorityAtExit: Int,
) : IllegalStateException(
    "Challenge WebView render process gone (didCrash=$didCrash, priority=$rendererPriorityAtExit)",
)

/** Thin abstraction over a single hosted challenge WebView session for offline unit testing. */
interface ChallengeWebViewHandle {
    val events: Flow<ChallengeWebViewEvent>
    val activityBinding: ChallengeActivityBinding?
        get() = null

    fun installChallengeMessageListener()
    fun loadUrl(url: String)
    fun reload()
    fun mainFrameState(): MainFrameState
    suspend fun isDomInChallengeState(): Boolean
    fun close()

    /**
     * Initiates teardown and suspends until host cleanup completes (WebView stopped/destroyed and
     * Activity finishing). Defaults to synchronous [close] for lightweight fakes.
     */
    suspend fun closeAndAwait() {
        close()
    }
}

/** Acquires a hosted challenge WebView session (production launches [ChallengeActivity]). */
fun interface ChallengeWebViewHost {
    suspend fun acquire(
        triggerUrl: HttpUrl,
        userAgent: String,
    ): ChallengeWebViewHandle
}

/**
 * Returns true for non-fatal Cloudflare Turnstile/Managed Challenge probe domains
 * (`*.challenges.cloudflare.com`, `challenges.cloudflare.com`, `*.dnstest.dev`, `dnstest.dev`).
 */
internal fun isIgnoredChallengeProbeHost(host: String?): Boolean {
    val normalized = host?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return false
    if (normalized.isEmpty()) return false
    return normalized == "challenges.cloudflare.com" ||
        normalized.endsWith(".challenges.cloudflare.com") ||
        normalized == "dnstest.dev" ||
        normalized.endsWith(".dnstest.dev")
}

/** Returns true when response headers contain `cf-mitigated` with a `challenge` token. */
internal fun hasCfMitigatedChallengeHeader(headers: Map<String, String>?): Boolean {
    if (headers.isNullOrEmpty()) return false
    return headers.entries.any { (name, value) ->
        name.equals("cf-mitigated", ignoreCase = true) &&
            value.contains("challenge", ignoreCase = true)
    }
}

/** Maps a Turnstile `cloudflare-challenge` event name to its corresponding [ChallengeWebViewEvent]. */
internal fun parseTurnstileChallengeEvent(eventName: String?): ChallengeWebViewEvent? =
    when (eventName?.trim()) {
        "interactiveBegin" -> ChallengeWebViewEvent.InteractiveBegin
        "interactiveTimeout" -> ChallengeWebViewEvent.InteractiveTimeout
        else -> null
    }

/** Formats the normalized `scheme://host[:port]` origin for [url], omitting default HTTP/HTTPS ports. */
internal fun formatOrigin(url: HttpUrl): String {
    val scheme = url.scheme.lowercase(Locale.ROOT)
    val rawHost = url.host.lowercase(Locale.ROOT)
    val formattedHost = if (rawHost.contains(':')) "[$rawHost]" else rawHost
    val defaultPort = HttpUrl.defaultPort(scheme)
    return if (url.port != defaultPort) {
        "$scheme://$formattedHost:${url.port}"
    } else {
        "$scheme://$formattedHost"
    }
}

/**
 * Builds restricted `allowedOriginRules` for `WebViewCompat.addWebMessageListener` and
 * `WebViewCompat.addDocumentStartJavaScript`: `4d4y.com`, `*.4d4y.com`,
 * `challenges.cloudflare.com`, `*.challenges.cloudflare.com`, plus the origin of [triggerUrl].
 */
internal fun buildAllowedChallengeOriginRules(triggerUrl: HttpUrl): Set<String> = linkedSetOf(
    "https://4d4y.com",
    "https://*.4d4y.com",
    "https://challenges.cloudflare.com",
    "https://*.challenges.cloudflare.com",
).apply {
    val scheme = triggerUrl.scheme.lowercase(Locale.ROOT)
    if (scheme == "http" || scheme == "https") {
        add(formatOrigin(triggerUrl))
    }
}

/**
 * Returns true when [urlOrOrigin] belongs to `4d4y.com` (HTTPS), `challenges.cloudflare.com`
 * (HTTPS), or matches the exact `(scheme, host, port)` origin of [triggerUrl].
 */
internal fun isAllowedChallengeOrigin(urlOrOrigin: String?, triggerUrl: HttpUrl): Boolean {
    val parsed = urlOrOrigin?.trim()?.toHttpUrlOrNull() ?: return false
    val scheme = parsed.scheme.lowercase(Locale.ROOT)
    val host = parsed.host.lowercase(Locale.ROOT)
    if (scheme == triggerUrl.scheme.lowercase(Locale.ROOT) &&
        host == triggerUrl.host.lowercase(Locale.ROOT) &&
        parsed.port == triggerUrl.port
    ) {
        return true
    }
    if (scheme != "https" || parsed.port != 443) return false
    return host == "4d4y.com" ||
        host.endsWith(".4d4y.com") ||
        host == "challenges.cloudflare.com" ||
        host.endsWith(".challenges.cloudflare.com")
}

/**
 * Thread-safe tracker reconciling WebView main-frame callbacks across Chromium orderings
 * (both `onReceivedHttpError -> onPageStarted -> onPageFinished` and
 * `onPageStarted -> onReceivedHttpError -> onPageFinished`).
 */
internal class MainFrameStateTracker {
    private val lock = Any()
    private var state = MainFrameState()
    private var awaitingPageStarted = true

    fun snapshot(): MainFrameState = synchronized(lock) { state }

    fun onLoadRequested(url: String?) = synchronized(lock) {
        state = MainFrameState(url = url, pageFinished = false)
        awaitingPageStarted = true
    }

    fun onPageStarted(url: String?) = synchronized(lock) {
        val preservePreCommitError = awaitingPageStarted &&
            !state.pageFinished &&
            (state.url == null || url == null || state.url == url)
        state = if (preservePreCommitError) {
            state.copy(url = url ?: state.url, pageFinished = false)
        } else {
            MainFrameState(url = url, pageFinished = false)
        }
        awaitingPageStarted = false
    }

    fun onMainFrameHttpError(
        requestUrl: String?,
        statusCode: Int,
        responseHeaders: Map<String, String>?,
    ) = synchronized(lock) {
        val mitigated = hasCfMitigatedChallengeHeader(responseHeaders)
        state = if (state.pageFinished) {
            awaitingPageStarted = true
            MainFrameState(
                url = requestUrl,
                pageFinished = false,
                hasCfMitigatedChallengeHeader = mitigated,
                httpErrorStatusCode = statusCode,
                hasMainFrameLoadError = false,
            )
        } else {
            state.copy(
                url = requestUrl ?: state.url,
                hasCfMitigatedChallengeHeader = state.hasCfMitigatedChallengeHeader || mitigated,
                httpErrorStatusCode = statusCode,
            )
        }
    }

    fun onMainFrameLoadError(requestUrl: String?) = synchronized(lock) {
        state = if (state.pageFinished) {
            awaitingPageStarted = true
            MainFrameState(
                url = requestUrl,
                pageFinished = false,
                hasMainFrameLoadError = true,
            )
        } else {
            state.copy(
                url = requestUrl ?: state.url,
                hasMainFrameLoadError = true,
            )
        }
    }

    fun onPageFinished(url: String?) = synchronized(lock) {
        state = state.copy(
            url = url ?: state.url,
            pageFinished = true,
        )
        awaitingPageStarted = true
    }
}
