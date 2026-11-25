package net.jejer.hipda.data.network

import java.util.Locale
import net.jejer.hipda.data.network.cookie.SiteCookieStore
import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * Synchronous in-memory [SiteCookieStore] fake for fast, deterministic interceptor unit tests
 * without encrypted DataStore disk I/O. Enforces [Cookie.matches], expiration, and
 * longer-path-first ordering.
 */
internal class InMemorySiteCookieStore(
    private val clock: () -> Long = System::currentTimeMillis,
) : SiteCookieStore {

    private data class Key(val name: String, val domain: String, val path: String)

    private val lock = Any()
    private val cookies = LinkedHashMap<Key, Cookie>()

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        val now = clock()
        cookies.entries.removeAll { it.value.expiresAt <= now }
        cookies.values
            .filter { it.matches(url) }
            .sortedByDescending { it.path.length }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = synchronized(lock) {
        val now = clock()
        val requestHost = url.host
        this.cookies.entries.removeAll { it.value.expiresAt <= now }
        for (cookie in cookies) {
            if (!cookie.matchesHost(requestHost)) continue
            val key = Key(cookie.name, cookie.domain, cookie.path)
            if (cookie.expiresAt <= now) {
                this.cookies.remove(key)
            } else {
                this.cookies[key] = cookie
            }
        }
    }

    override suspend fun clear() {
        synchronized(lock) { cookies.clear() }
    }

    override suspend fun clearForHost(host: String) {
        val normalized = host.trim().trim('.').lowercase(Locale.ROOT)
        synchronized(lock) {
            cookies.entries.removeAll { (_, cookie) -> cookie.matchesHost(normalized) }
        }
    }

    private fun Cookie.matchesHost(canonicalHost: String): Boolean {
        if (domain == canonicalHost) return true
        if (hostOnly) return false
        return canonicalHost.endsWith(".$domain") && !isIpAddress(canonicalHost)
    }

    private fun isIpAddress(host: String): Boolean {
        if (host.contains(':')) return true
        val lastLabel = host.substringAfterLast('.')
        return lastLabel.isNotEmpty() && lastLabel.all(Char::isDigit)
    }

    override fun snapshot(): List<Cookie> = synchronized(lock) {
        val now = clock()
        cookies.entries.removeAll { it.value.expiresAt <= now }
        cookies.values.toList()
    }
}
