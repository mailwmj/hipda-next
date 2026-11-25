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

    private val cookies = LinkedHashMap<Key, Cookie>()

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = clock()
        cookies.entries.removeAll { it.value.expiresAt <= now }
        return cookies.values
            .filter { it.matches(url) }
            .sortedByDescending { it.path.length }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = clock()
        this.cookies.entries.removeAll { it.value.expiresAt <= now }
        for (cookie in cookies) {
            val key = Key(cookie.name, cookie.domain, cookie.path)
            if (cookie.expiresAt <= now) {
                this.cookies.remove(key)
            } else {
                this.cookies[key] = cookie
            }
        }
    }

    @Synchronized
    override suspend fun clear() {
        cookies.clear()
    }

    @Synchronized
    override suspend fun clearForHost(host: String) {
        val normalized = host.trim().trim('.').lowercase(Locale.ROOT)
        cookies.entries.removeAll { (_, cookie) ->
            cookie.domain == normalized || (!cookie.hostOnly && normalized.endsWith(".${cookie.domain}"))
        }
    }

    @Synchronized
    override fun snapshot(): List<Cookie> {
        val now = clock()
        cookies.entries.removeAll { it.value.expiresAt <= now }
        return cookies.values.toList()
    }
}
