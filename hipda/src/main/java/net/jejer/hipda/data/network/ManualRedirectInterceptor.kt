package net.jejer.hipda.data.network

import java.io.IOException
import java.net.ProtocolException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Outermost application interceptor that follows HTTP 301/302/303/307/308 redirects manually so
 * inner interceptors ([BrowserHeaderInterceptor], [AppCookieInterceptor]) run on every hop.
 */
class ManualRedirectInterceptor(
    private val maxRedirects: Int = MAX_FOLLOW_UPS,
) : Interceptor {

    init {
        require(maxRedirects >= 0) { "maxRedirects must be >= 0: $maxRedirects" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        var currentRequest = chain.request()
        var followUpCount = 0

        while (true) {
            if (chain.call().isCanceled()) {
                throw IOException("Canceled")
            }
            val response = chain.proceed(currentRequest)
            val nextRequest = buildFollowUpRequest(currentRequest, response) ?: return response

            if (chain.call().isCanceled()) {
                response.close()
                throw IOException("Canceled")
            }

            followUpCount++
            if (followUpCount > maxRedirects) {
                response.close()
                throw ProtocolException("Too many follow-up requests: $followUpCount")
            }

            response.close()
            currentRequest = nextRequest
        }
    }

    private fun buildFollowUpRequest(currentRequest: Request, response: Response): Request? {
        val code = response.code
        if (code !in REDIRECT_STATUS_CODES) return null

        val location = response.header("Location")?.takeIf { it.isNotBlank() } ?: return null
        val previousUrl = response.request.url
        val nextUrl = previousUrl.resolve(location) ?: return null

        val preserveMethodAndBody = code == 307 || code == 308
        if (preserveMethodAndBody && currentRequest.body?.isOneShot() == true) {
            return null
        }

        val builder = currentRequest.newBuilder().url(nextUrl)
        if (!preserveMethodAndBody && currentRequest.method != "GET" && currentRequest.method != "HEAD") {
            builder.method("GET", null)
                .removeHeader("Transfer-Encoding")
                .removeHeader("Content-Length")
                .removeHeader("Content-Type")
        }

        if (shouldStripSensitiveHeaders(previousUrl, nextUrl)) {
            builder.removeHeader("Authorization")
                .removeHeader("Proxy-Authorization")
                .removeHeader("Cookie")
        }

        return builder.build()
    }

    private fun shouldStripSensitiveHeaders(previousUrl: HttpUrl, nextUrl: HttpUrl): Boolean =
        previousUrl.host != nextUrl.host || (previousUrl.isHttps && !nextUrl.isHttps)

    companion object {
        const val MAX_FOLLOW_UPS: Int = 16
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}
