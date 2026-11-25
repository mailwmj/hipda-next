package net.jejer.hipda.data.network.webview

import android.content.Context
import android.webkit.WebSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import net.jejer.hipda.data.network.DefaultUserAgentProvider
import net.jejer.hipda.data.network.UserAgentProvider

private val VERSION_4_0_REGEX = Regex("""\bVersion/4\.0(?=\s|$)""", RegexOption.IGNORE_CASE)
private val WV_TOKEN_REGEX = Regex("""\s*;\s*wv(?=\s*(?:[;)]|$))""", RegexOption.IGNORE_CASE)
private val SPACE_BEFORE_DELIM_REGEX = Regex("""\s+([;)])""")
private val MULTI_WHITESPACE_REGEX = Regex("""\s+""")

/**
 * Strips Android WebView identity markers (`; wv` and `Version/4.0`) from [raw] so WebView and
 * OkHttp share a consistent mobile Chrome User-Agent. Falls back to
 * [DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA] when the input or cleaned result is blank.
 */
fun normalizeWebViewUserAgent(raw: String): String {
    if (raw.isBlank()) return DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA
    val cleaned = raw
        .replace(VERSION_4_0_REGEX, "")
        .replace(WV_TOKEN_REGEX, "")
        .replace(SPACE_BEFORE_DELIM_REGEX, "$1")
        .replace(MULTI_WHITESPACE_REGEX, " ")
        .trim()
    return cleaned.ifBlank { DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA }
}

/**
 * [UserAgentProvider] backed by the device's system WebView User-Agent, lazily resolved on the
 * calling thread, normalized, and cached for the process lifetime.
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
