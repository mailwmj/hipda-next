package net.jejer.hipda.data.network.cookie

import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * Encrypted, RFC 6265-compliant cookie store for the application-level network layer.
 *
 * Cookies are kept in memory for fast request reads and persisted asynchronously via encrypted
 * DataStore. Session cookies without an `Expires` or `Max-Age` attribute (`persistent == false`)
 * are also persisted across process restarts because the forum login cookies (`cdb_sid`,
 * `cdb_auth`) rely on them to remain signed in, matching legacy client behavior.
 */
interface SiteCookieStore {

    /** Returns non-expired cookies matching [url] via [Cookie.matches], longer paths first. */
    fun loadForRequest(url: HttpUrl): List<Cookie>

    /** Overwrites by `(name, domain, path)`; already-expired cookies delete any matching entry. */
    fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>)

    /** Clears all stored cookies and flushes the empty state to disk. */
    suspend fun clear()

    /** Clears host-only and domain cookies matching [host] and flushes to disk. */
    suspend fun clearForHost(host: String)

    /** Returns a point-in-time list of all non-expired cookies for debugging and tests. */
    fun snapshot(): List<Cookie>
}
