package net.jejer.hipda.data.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Application interceptor that stamps every request hop with the unified browser `User-Agent`
 * and fills default `Accept` / `Accept-Language` headers when absent.
 */
class BrowserHeaderInterceptor(
    private val userAgentProvider: UserAgentProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()
            .header("User-Agent", userAgentProvider.userAgent())
        if (request.header("Accept") == null) {
            builder.header("Accept", DEFAULT_ACCEPT)
        }
        if (request.header("Accept-Language") == null) {
            builder.header("Accept-Language", DEFAULT_ACCEPT_LANGUAGE)
        }
        return chain.proceed(builder.build())
    }

    companion object {
        const val DEFAULT_ACCEPT: String =
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        const val DEFAULT_ACCEPT_LANGUAGE: String =
            "zh-CN,zh;q=0.9,en;q=0.8"
    }
}
