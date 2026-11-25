package net.jejer.hipda.data.network

import net.jejer.hipda.data.network.cookie.SiteCookieStore
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Optional per-request guard attached via `Request.Builder.tag(AuthCookieWriteGuard::class.java, ...)`
 * so response cookie persistence can be checked atomically against the caller's auth generation.
 *
 * When [saveAction] is invoked with `allowAuthCookies = false`, authentication/session cookies
 * (such as `cdb_auth` / `cdb_sid`) are dropped while non-auth Cloudflare cookies (`cf_clearance`,
 * `__cf_bm`) are still persisted.
 */
fun interface AuthCookieWriteGuard {
    fun saveIfValid(saveAction: (allowAuthCookies: Boolean) -> Unit)
}

/**
 * Application-layer cookie interceptor backed by [SiteCookieStore].
 *
 * Manages `Cookie` and `Set-Cookie` headers directly so cookies stay consistent when a custom
 * transport bypasses OkHttp's `CookieJar` and cookies on 3xx redirect hops are persisted.
 */
class AppCookieInterceptor(
    private val siteCookieStore: SiteCookieStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cookies = siteCookieStore.loadForRequest(request.url)
        val requestWithCookies = request.newBuilder().apply {
            if (cookies.isNotEmpty()) {
                header("Cookie", formatCookieHeader(cookies))
            } else {
                removeHeader("Cookie")
            }
        }.build()

        val response = chain.proceed(requestWithCookies)
        val responseCookies = Cookie.parseAll(requestWithCookies.url, response.headers)
        if (responseCookies.isNotEmpty()) {
            val guard = requestWithCookies.tag(AuthCookieWriteGuard::class.java)
            val callCanceled = chain.call().isCanceled()
            if (guard != null) {
                guard.saveIfValid { allowAuthCookies ->
                    saveFilteredCookies(
                        url = requestWithCookies.url,
                        cookies = responseCookies,
                        allowAuthCookies = allowAuthCookies && !callCanceled,
                    )
                }
            } else {
                saveFilteredCookies(
                    url = requestWithCookies.url,
                    cookies = responseCookies,
                    allowAuthCookies = !callCanceled,
                )
            }
        }
        return response
    }

    private fun saveFilteredCookies(
        url: HttpUrl,
        cookies: List<Cookie>,
        allowAuthCookies: Boolean,
    ) {
        val toSave = if (allowAuthCookies) {
            cookies
        } else {
            cookies.filter { isNonAuthCookie(it.name) }
        }
        if (toSave.isNotEmpty()) {
            siteCookieStore.saveFromResponse(url, toSave)
        }
    }

    private fun isNonAuthCookie(name: String): Boolean =
        name.equals(COOKIE_CF_CLEARANCE, ignoreCase = true) ||
            name.equals(COOKIE_CF_BM, ignoreCase = true)

    private fun formatCookieHeader(cookies: List<Cookie>): String =
        cookies.joinToString(separator = "; ") { "${it.name}=${it.value}" }

    private companion object {
        const val COOKIE_CF_CLEARANCE = "cf_clearance"
        const val COOKIE_CF_BM = "__cf_bm"
    }
}
