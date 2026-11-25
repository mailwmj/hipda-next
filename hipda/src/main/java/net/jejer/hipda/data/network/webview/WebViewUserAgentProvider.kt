package net.jejer.hipda.data.network.webview

import android.content.Context
import android.webkit.WebSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import net.jejer.hipda.data.network.DefaultUserAgentProvider
import net.jejer.hipda.data.network.UserAgentProvider

private val CHROME_VERSION_REGEX = Regex("""(?:^|\s)Chrome/(\d+)(?:\.\d+)*(?=\s|$)""", RegexOption.IGNORE_CASE)

/**
 * Extracts the positive Chrome major version from a raw WebView or browser User-Agent string
 * (e.g. `Chrome/131.0.6778.200` -> `"131"`), returning `null` when absent or malformed.
 */
internal fun extractChromeMajorVersion(raw: String): String? {
    if (raw.isBlank()) return null
    return CHROME_VERSION_REGEX.findAll(raw)
        .mapNotNull { match ->
            match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }?.toString()
        }
        .lastOrNull()
}

/** Builds the unified desktop Linux Chrome User-Agent string for [chromeMajorVersion]. */
fun buildDesktopChromeUserAgent(chromeMajorVersion: String): String =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeMajorVersion.0.0.0 Safari/537.36"

/**
 * Converts a raw Android WebView User-Agent [raw] into a desktop Linux Chrome User-Agent
 * preserving the real Chrome major version (without `Android`, `Mobile`, `; wv`, or `Version/4.0`).
 *
 * 4d4y.com serves a broken mobile Discuz! template whenever `Android` appears in `User-Agent`;
 * adopting desktop site identity ensures both WebView and OkHttp receive the desktop template
 * while remaining bound to the same `cf_clearance` User-Agent string.
 *
 * Falls back to [DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA] when [raw] is blank or
 * contains no valid `Chrome/<major>` token.
 */
fun normalizeWebViewUserAgent(raw: String): String {
    val major = extractChromeMajorVersion(raw)
        ?: return DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA
    return buildDesktopChromeUserAgent(major)
}

/**
 * [UserAgentProvider] backed by the device's system WebView User-Agent, lazily resolved on the
 * calling thread, converted to desktop Linux Chrome identity, and cached for the process lifetime.
 */
@Singleton
class WebViewUserAgentProvider internal constructor(
    private val rawUserAgentSupplier: () -> String,
) : UserAgentProvider {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        rawUserAgentSupplier = { WebSettings.getDefaultUserAgent(context).orEmpty() },
    )

    private val cachedUserAgent: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val raw = try {
            rawUserAgentSupplier()
        } catch (_: Throwable) {
            ""
        }
        normalizeWebViewUserAgent(raw)
    }

    override fun userAgent(): String = cachedUserAgent
}
