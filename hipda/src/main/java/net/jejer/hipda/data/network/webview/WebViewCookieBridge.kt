package net.jejer.hipda.data.network.webview

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.jejer.hipda.data.network.cookie.SiteCookieStore
import okhttp3.Cookie
import okhttp3.HttpUrl

const val CF_CLEARANCE_COOKIE_NAME: String = "cf_clearance"

private const val EPOCH_HTTP_DATE: String = "Thu, 01 Jan 1970 00:00:00 GMT"

private val HTTP_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .withZone(ZoneOffset.UTC)

/** Snapshot of `cf_clearance` cookies cleared before a challenge so failure can restore them. */
data class ClearanceBackup(
    val storeCookies: List<Cookie>,
    val webViewSetCookieHeaders: List<String>,
) {
    val isEmpty: Boolean
        get() = storeCookies.isEmpty() && webViewSetCookieHeaders.isEmpty()
}

/** Result of syncing WebView cookies into [SiteCookieStore] at a challenge or login boundary. */
data class CookieSyncResult(
    val syncedCookies: List<Cookie>,
    val freshClearance: String?,
)

/**
 * Boundary-oriented bridge between Android WebView cookies and [SiteCookieStore].
 *
 * All [WebViewCookieAccess] IPC runs on a dedicated single-threaded [cookieDispatcher] to avoid
 * main-thread stalls. `cf_clearance` is only synced WebView -> Store and is explicitly excluded
 * when priming WebView with app login cookies.
 */
@Singleton
class WebViewCookieBridge @Inject constructor(
    private val siteCookieStore: SiteCookieStore,
    private val webViewCookieAccess: WebViewCookieAccess,
    @param:WebViewCookieDispatcher private val cookieDispatcher: CoroutineDispatcher,
) {

    /** Collects all non-blank `cf_clearance` values matching [url] from both Store and WebView. */
    suspend fun snapshotClearances(url: HttpUrl): Set<String> {
        val clearances = LinkedHashSet<String>()
        for (cookie in siteCookieStore.loadForRequest(url)) {
            if (cookie.name == CF_CLEARANCE_COOKIE_NAME && cookie.value.isNotBlank()) {
                clearances.add(cookie.value)
            }
        }
        val webViewCookies = withContext(cookieDispatcher) {
            readWebViewCookies(url)
        }
        for (cookie in webViewCookies) {
            if (cookie.name == CF_CLEARANCE_COOKIE_NAME && cookie.value.isNotBlank()) {
                clearances.add(cookie.value)
            }
        }
        return clearances.toSet()
    }

    /**
     * Backs up and removes `cf_clearance` cookies matching [url] from both [SiteCookieStore] and
     * WebView without disturbing login cookies such as `cdb_auth` or `cdb_sid`.
     */
    suspend fun clearClearance(url: HttpUrl): ClearanceBackup {
        val storeBackups = siteCookieStore.loadForRequest(url)
            .filter { it.name == CF_CLEARANCE_COOKIE_NAME }
        if (storeBackups.isNotEmpty()) {
            val expiredCookies = storeBackups.map(::toExpiredStoreCookie)
            siteCookieStore.saveFromResponse(url, expiredCookies)
        }

        val webViewBackups = withContext(cookieDispatcher) {
            clearWebViewClearances(url)
        }
        return ClearanceBackup(
            storeCookies = storeBackups,
            webViewSetCookieHeaders = webViewBackups,
        )
    }

    /** Restores a [ClearanceBackup] previously captured by [clearClearance]. */
    suspend fun restoreClearance(url: HttpUrl, backup: ClearanceBackup) {
        if (backup.storeCookies.isNotEmpty()) {
            siteCookieStore.saveFromResponse(url, backup.storeCookies)
        }
        if (backup.webViewSetCookieHeaders.isNotEmpty()) {
            withContext(cookieDispatcher) {
                val targetUrl = url.toString()
                for (header in backup.webViewSetCookieHeaders) {
                    webViewCookieAccess.setCookie(targetUrl, header)
                }
                webViewCookieAccess.flush()
            }
        }
    }

    /**
     * Primes WebView with app cookies matching [url] (such as `cdb_auth` and `cdb_sid`),
     * explicitly excluding `cf_clearance`.
     */
    suspend fun primeWebView(url: HttpUrl) {
        val headers = siteCookieStore.loadForRequest(url)
            .filter { it.name != CF_CLEARANCE_COOKIE_NAME }
            .map(::formatSetCookieHeader)
        if (headers.isEmpty()) return

        withContext(cookieDispatcher) {
            val targetUrl = url.toString()
            for (header in headers) {
                webViewCookieAccess.setCookie(targetUrl, header)
            }
            webViewCookieAccess.flush()
        }
    }

    /**
     * Reads WebView cookies for [url], validates host scope, drops blank or excluded
     * `cf_clearance` values, resolves duplicate `(domain, path)` `cf_clearance` candidates by
     * latest `expiresAt`, and saves the surviving cookies into [SiteCookieStore].
     */
    suspend fun syncFromWebView(
        url: HttpUrl,
        excludeClearanceValues: Set<String> = emptySet(),
    ): CookieSyncResult {
        val parsedCookies = withContext(cookieDispatcher) {
            readWebViewCookies(url)
        }

        val otherCookies = mutableListOf<Cookie>()
        val clearanceWinners = LinkedHashMap<Pair<String, String>, Cookie>()

        for (cookie in parsedCookies) {
            if (cookie.name != CF_CLEARANCE_COOKIE_NAME) {
                otherCookies.add(cookie)
                continue
            }
            if (cookie.value.isBlank() || cookie.value in excludeClearanceValues) {
                continue
            }
            val scopeKey = cookie.domain to cookie.path
            val current = clearanceWinners[scopeKey]
            if (current == null || cookie.expiresAt > current.expiresAt) {
                clearanceWinners[scopeKey] = cookie
            }
        }

        val selectedClearances = clearanceWinners.values.toList()
        val toSave = otherCookies + selectedClearances
        if (toSave.isNotEmpty()) {
            siteCookieStore.saveFromResponse(url, toSave)
        }
        val freshClearance = selectedClearances.maxByOrNull { it.expiresAt }?.value
        return CookieSyncResult(
            syncedCookies = toSave,
            freshClearance = freshClearance,
        )
    }

    private fun readWebViewCookies(url: HttpUrl): List<Cookie> {
        val targetUrl = url.toString()
        if (webViewCookieAccess.isGetCookieInfoSupported()) {
            val now = System.currentTimeMillis()
            return webViewCookieAccess.getCookieInfo(targetUrl).mapNotNull { raw ->
                Cookie.parse(url, raw)?.takeIf { cookie ->
                    cookie.matchesHost(url.host) && cookie.expiresAt > now
                }
            }
        }
        return parseLegacyCookieHeader(url, webViewCookieAccess.getCookie(targetUrl))
    }

    private fun clearWebViewClearances(url: HttpUrl): List<String> {
        val targetUrl = url.toString()
        val backups = mutableListOf<String>()
        val expireHeaders = LinkedHashSet<String>()

        if (webViewCookieAccess.isGetCookieInfoSupported()) {
            val now = System.currentTimeMillis()
            for (raw in webViewCookieAccess.getCookieInfo(targetUrl)) {
                val parsed = Cookie.parse(url, raw) ?: continue
                if (!parsed.matchesHost(url.host) || parsed.expiresAt <= now) continue
                if (parsed.name != CF_CLEARANCE_COOKIE_NAME || parsed.value.isBlank()) continue
                backups.add(raw)
                expireHeaders.add(buildClearanceExpirationHeader(raw, fallbackPath = parsed.path))
            }
        } else {
            val legacyClearances = parseLegacyCookieHeader(url, webViewCookieAccess.getCookie(targetUrl))
                .filter { it.name == CF_CLEARANCE_COOKIE_NAME && it.value.isNotBlank() }
            for (cookie in legacyClearances) {
                backups.add("$CF_CLEARANCE_COOKIE_NAME=${cookie.value}; Path=/")
            }
            if (backups.isNotEmpty()) {
                expireHeaders.add("$CF_CLEARANCE_COOKIE_NAME=; Max-Age=0; Expires=$EPOCH_HTTP_DATE; Path=/")
            }
        }

        if (expireHeaders.isNotEmpty()) {
            for (expireHeader in expireHeaders) {
                webViewCookieAccess.setCookie(targetUrl, expireHeader)
            }
            webViewCookieAccess.flush()
        }
        return backups
    }

    private fun parseLegacyCookieHeader(url: HttpUrl, rawHeader: String?): List<Cookie> {
        if (rawHeader.isNullOrBlank()) return emptyList()
        return rawHeader.split(';').mapNotNull { segment ->
            val eqIndex = segment.indexOf('=')
            if (eqIndex <= 0) return@mapNotNull null
            val name = segment.substring(0, eqIndex).trim()
            if (name.isEmpty()) return@mapNotNull null
            val value = segment.substring(eqIndex + 1).trim()
            runCatching {
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .hostOnlyDomain(url.host)
                    .path("/")
                    .build()
            }.getOrNull()
        }
    }

    private fun toExpiredStoreCookie(cookie: Cookie): Cookie =
        Cookie.Builder()
            .name(cookie.name)
            .value("")
            .path(cookie.path)
            .expiresAt(1L)
            .apply {
                if (cookie.hostOnly) hostOnlyDomain(cookie.domain) else domain(cookie.domain)
                if (cookie.secure) secure()
                if (cookie.httpOnly) httpOnly()
                cookie.sameSite?.let(::sameSite)
            }
            .build()

    private fun formatSetCookieHeader(cookie: Cookie): String = buildString {
        append(cookie.name).append('=').append(cookie.value)
        if (cookie.persistent) {
            append("; Expires=").append(HTTP_DATE_FORMATTER.format(Instant.ofEpochMilli(cookie.expiresAt)))
        }
        if (!cookie.hostOnly) {
            append("; Domain=").append(cookie.domain)
        }
        append("; Path=").append(cookie.path)
        if (cookie.secure) append("; Secure")
        if (cookie.httpOnly) append("; HttpOnly")
        cookie.sameSite?.takeIf { it.isNotBlank() }?.let { sameSite ->
            append("; SameSite=").append(sameSite)
        }
    }

    private fun buildClearanceExpirationHeader(rawSetCookie: String, fallbackPath: String): String {
        var domain: String? = null
        var path: String? = null
        var secure = false
        var httpOnly = false
        var sameSite: String? = null
        var partitioned = false

        for (segment in rawSetCookie.split(';').drop(1)) {
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) continue
            val eqIndex = trimmed.indexOf('=')
            val attrName = (if (eqIndex >= 0) trimmed.substring(0, eqIndex) else trimmed).trim()
            val attrValue = if (eqIndex >= 0) trimmed.substring(eqIndex + 1).trim() else ""
            when (attrName.lowercase(Locale.ROOT)) {
                "domain" -> if (attrValue.isNotEmpty()) domain = attrValue
                "path" -> if (attrValue.isNotEmpty()) path = attrValue
                "secure" -> secure = true
                "httponly" -> httpOnly = true
                "samesite" -> if (attrValue.isNotEmpty()) sameSite = attrValue
                "partitioned" -> partitioned = true
            }
        }

        return buildString {
            append(CF_CLEARANCE_COOKIE_NAME)
            append("=; Max-Age=0; Expires=")
            append(EPOCH_HTTP_DATE)
            if (domain != null) {
                append("; Domain=").append(domain)
            }
            append("; Path=").append(path ?: fallbackPath)
            if (secure) append("; Secure")
            if (httpOnly) append("; HttpOnly")
            if (sameSite != null) {
                append("; SameSite=").append(sameSite)
            }
            if (partitioned) append("; Partitioned")
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
}
