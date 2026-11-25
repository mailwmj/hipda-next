package net.jejer.hipda.ui.challenge

import java.util.concurrent.CopyOnWriteArrayList
import net.jejer.hipda.data.network.webview.WebViewCookieAccess
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Realistic in-memory [WebViewCookieAccess] keyed by `(name, domain, path, hostOnly, partitioned)`
 * to mirror Chromium `CookieManager` semantics across domain/host-only/CHIPS cookie variants.
 */
internal class TestWebViewCookieAccess : WebViewCookieAccess {
    private data class CookieScopeKey(
        val name: String,
        val domain: String,
        val path: String,
        val hostOnly: Boolean,
        val partitioned: Boolean,
    )

    private data class StoredEntry(
        val cookie: Cookie,
        val rawHeader: String,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<CookieScopeKey, StoredEntry>()

    @Volatile
    var getCookieInfoSupported: Boolean = true

    val setCookieCalls: MutableList<Pair<String, String>> = CopyOnWriteArrayList()
    val eventLog: MutableList<String> = CopyOnWriteArrayList()

    @Volatile
    var flushCount: Int = 0
        private set

    fun seedCookie(url: HttpUrl, setCookieHeader: String) {
        synchronized(lock) {
            applySetCookieLocked(url, setCookieHeader)
        }
    }

    fun storedRawHeaders(url: HttpUrl): List<String> = synchronized(lock) {
        matchingEntriesLocked(url).map { it.rawHeader }
    }

    override fun isGetCookieInfoSupported(): Boolean {
        eventLog.add("isGetCookieInfoSupported")
        return getCookieInfoSupported
    }

    override fun getCookieInfo(url: String): List<String> = synchronized(lock) {
        eventLog.add("getCookieInfo")
        matchingEntriesLocked(url.toHttpUrl()).map { it.rawHeader }
    }

    override fun getCookie(url: String): String? = synchronized(lock) {
        eventLog.add("getCookie")
        val matched = matchingEntriesLocked(url.toHttpUrl())
        if (matched.isEmpty()) null else matched.joinToString("; ") { "${it.cookie.name}=${it.cookie.value}" }
    }

    override fun setCookie(url: String, value: String) = synchronized(lock) {
        eventLog.add("setCookie")
        setCookieCalls.add(url to value)
        applySetCookieLocked(url.toHttpUrl(), value)
    }

    override fun flush() {
        eventLog.add("flush")
        flushCount++
    }

    private fun applySetCookieLocked(url: HttpUrl, rawHeader: String) {
        val cookie = Cookie.parse(url, rawHeader) ?: return
        if (cookie.sameSite.equals("None", ignoreCase = true) && !cookie.secure) return
        if (cookie.secure && !url.isHttps) return
        val key = CookieScopeKey(
            name = cookie.name,
            domain = cookie.domain,
            path = cookie.path,
            hostOnly = cookie.hostOnly,
            partitioned = hasAttributeFlag(rawHeader, "partitioned"),
        )
        val now = System.currentTimeMillis()
        if (cookie.expiresAt <= now) {
            entries.remove(key)
        } else {
            entries[key] = StoredEntry(cookie = cookie, rawHeader = rawHeader)
        }
    }

    private fun matchingEntriesLocked(url: HttpUrl): List<StoredEntry> {
        val now = System.currentTimeMillis()
        entries.entries.removeAll { it.value.cookie.expiresAt <= now }
        return entries.values.filter { it.cookie.matches(url) }
    }

    private fun hasAttributeFlag(rawHeader: String, flagName: String): Boolean =
        rawHeader.split(';')
            .drop(1)
            .any { it.trim().substringBefore('=').trim().equals(flagName, ignoreCase = true) }
}
