package net.jejer.hipda.data.network

import dagger.Binds
import dagger.Provides
import javax.inject.Singleton
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jejer.hipda.data.network.cookie.SiteCookieStore
import okhttp3.CookieJar
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpClientModuleTest {

    private fun createClient() = HttpClientModule.provideOkHttpClient(
        siteCookieStore = InMemorySiteCookieStore(),
        userAgentProvider = DefaultUserAgentProvider(),
    )

    @Test
    fun provideOkHttpClientConfiguresTimeoutsAndDisablesRedirects() {
        val client = createClient()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
        assertEquals(CookieJar.NO_COOKIES, client.cookieJar)
    }

    @Test
    fun provideOkHttpClientConfiguresInterceptorsInOrderAndEmptyNetworkInterceptors() {
        val client = createClient()

        assertEquals(3, client.interceptors.size)
        assertTrue(client.interceptors[0] is ManualRedirectInterceptor)
        assertTrue(client.interceptors[1] is BrowserHeaderInterceptor)
        assertTrue(client.interceptors[2] is AppCookieInterceptor)
        assertTrue(client.networkInterceptors.isEmpty())
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

            // Strip app-level ManualRedirectInterceptor to verify OkHttp's own redirect engine stays disabled.
            val client = createClient().newBuilder()
                .apply { interceptors().clear() }
                .build()
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
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

    @Test
    fun hiltModuleAnnotationsAreConfiguredForOkHttpClientAndUserAgent() {
        val provideClient = HttpClientModule::class.java.getDeclaredMethod(
            "provideOkHttpClient",
            SiteCookieStore::class.java,
            UserAgentProvider::class.java,
        )
        assertTrue(provideClient.isAnnotationPresent(Provides::class.java))
        assertTrue(provideClient.isAnnotationPresent(Singleton::class.java))

        val bindUa = UserAgentModule::class.java.getDeclaredMethod(
            "bindUserAgentProvider",
            DefaultUserAgentProvider::class.java,
        )
        assertTrue(bindUa.isAnnotationPresent(Binds::class.java))
        assertTrue(bindUa.isAnnotationPresent(Singleton::class.java))
    }
}
