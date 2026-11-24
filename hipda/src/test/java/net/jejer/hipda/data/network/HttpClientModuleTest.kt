package net.jejer.hipda.data.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.CookieJar
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HttpClientModuleTest {

    @Test
    fun provideOkHttpClientConfiguresTimeoutsAndDisablesRedirects() {
        val client = HttpClientModule.provideOkHttpClient()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
        assertEquals(CookieJar.NO_COOKIES, client.cookieJar)
    }

    @Test
    fun provideOkHttpClientDoesNotFollowRedirectsAutomatically() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", "/forum/index.php")
                    .build(),
            )

            val client = HttpClientModule.provideOkHttpClient()
            val request = Request.Builder()
                .url(server.url("/forum/redirect.php"))
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(302, response.code)
                assertEquals("/forum/index.php", response.header("Location"))
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }
}
