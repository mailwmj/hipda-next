package net.jejer.hipda.data.network

import net.jejer.hipda.data.network.cookie.SiteCookieStore
import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.Response

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
            siteCookieStore.saveFromResponse(requestWithCookies.url, responseCookies)
        }
        return response
    }

    private fun formatCookieHeader(cookies: List<Cookie>): String =
        cookies.joinToString(separator = "; ") { "${it.name}=${it.value}" }
}
