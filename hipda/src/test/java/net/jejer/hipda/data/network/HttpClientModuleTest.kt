package net.jejer.hipda.data.network

import android.content.Context
import dagger.Binds
import dagger.Provides
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Singleton
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import net.jejer.hipda.data.network.cookie.SiteCookieStore
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpClientModuleTest {

    private fun createClient(
        transportInterceptor: TransportInterceptor = TransportInterceptor(delegateFactory = { null }),
    ) = HttpClientModule.provideOkHttpClient(
        siteCookieStore = InMemorySiteCookieStore(),
        userAgentProvider = DefaultUserAgentProvider(),
        transportInterceptor = transportInterceptor,
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
        val factoryCalls = AtomicInteger(0)
        val client = createClient(
            transportInterceptor = TransportInterceptor(
                delegateFactory = {
                    factoryCalls.incrementAndGet()
                    null
                },
            ),
        )

        assertEquals(4, client.interceptors.size)
        assertTrue(client.interceptors[0] is ManualRedirectInterceptor)
        assertTrue(client.interceptors[1] is BrowserHeaderInterceptor)
        assertTrue(client.interceptors[2] is AppCookieInterceptor)
        assertTrue(client.interceptors[3] is TransportInterceptor)
        assertTrue(client.networkInterceptors.isEmpty())
        assertEquals(0, factoryCalls.get())
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
        val provideTransport = HttpClientModule::class.java.getDeclaredMethod(
            "provideTransportInterceptor",
            Context::class.java,
        )
        assertTrue(provideTransport.isAnnotationPresent(Provides::class.java))
        assertTrue(provideTransport.isAnnotationPresent(Singleton::class.java))

        val provideClient = HttpClientModule::class.java.getDeclaredMethod(
            "provideOkHttpClient",
            SiteCookieStore::class.java,
            UserAgentProvider::class.java,
            TransportInterceptor::class.java,
        )
        assertTrue(provideClient.isAnnotationPresent(Provides::class.java))
        assertTrue(provideClient.isAnnotationPresent(Singleton::class.java))

        val provideRecovery = HttpClientModule::class.java.getDeclaredMethod(
            "provideRecoveryOkHttpClient",
            OkHttpClient::class.java,
        )
        assertTrue(provideRecovery.isAnnotationPresent(Provides::class.java))
        assertTrue(provideRecovery.isAnnotationPresent(Singleton::class.java))
        assertTrue(provideRecovery.isAnnotationPresent(RecoveryHttpClient::class.java))

        val bindUa = UserAgentModule::class.java.getDeclaredMethod(
            "bindUserAgentProvider",
            DefaultUserAgentProvider::class.java,
        )
        assertTrue(bindUa.isAnnotationPresent(Binds::class.java))
        assertTrue(bindUa.isAnnotationPresent(Singleton::class.java))
    }

    @Test
    fun provideRecoveryOkHttpClient_usesIndependentDispatcherAndCompletesWhenPrimarySaturated() {
        val primaryClient = createClient().newBuilder()
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 1
                    maxRequestsPerHost = 1
                },
            )
            .build()
        val recoveryClient = HttpClientModule.provideRecoveryOkHttpClient(primaryClient)

        assertNotSame(primaryClient.dispatcher, recoveryClient.dispatcher)
        assertNotSame(
            primaryClient.dispatcher.executorService,
            recoveryClient.dispatcher.executorService,
        )
        assertEquals(primaryClient.interceptors, recoveryClient.interceptors)
        assertEquals(primaryClient.followRedirects, recoveryClient.followRedirects)
        assertEquals(primaryClient.followSslRedirects, recoveryClient.followSslRedirects)

        val primaryEnteredServer = CountDownLatch(1)
        val releasePrimary = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.url.encodedPath) {
                    "/stall" -> {
                        primaryEnteredServer.countDown()
                        releasePrimary.await(5, TimeUnit.SECONDS)
                        MockResponse.Builder().code(200).body("stalled-done").build()
                    }

                    "/recovery" -> MockResponse.Builder().code(200).body("recovered").build()
                    else -> MockResponse.Builder().code(404).build()
                }
        }
        server.start()
        try {
            val noopCallback = object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            }
            val stalledCall = primaryClient.newCall(Request.Builder().url(server.url("/stall")).build())
            stalledCall.enqueue(noopCallback)
            assertTrue("Primary call did not reach server", primaryEnteredServer.await(5, TimeUnit.SECONDS))

            val queuedPrimaryCall = primaryClient.newCall(Request.Builder().url(server.url("/recovery")).build())
            queuedPrimaryCall.enqueue(noopCallback)
            assertEquals(1, primaryClient.dispatcher.runningCallsCount())
            assertEquals(1, primaryClient.dispatcher.queuedCallsCount())

            // Primary dispatcher is completely saturated (1 running + 1 queued), yet recovery succeeds.
            recoveryClient.newCall(Request.Builder().url(server.url("/recovery")).build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("recovered", response.body.string())
            }

            stalledCall.cancel()
            queuedPrimaryCall.cancel()
            releasePrimary.countDown()
        } finally {
            releasePrimary.countDown()
            primaryClient.dispatcher.cancelAll()
            primaryClient.dispatcher.executorService.shutdownNow()
            primaryClient.connectionPool.evictAll()
            recoveryClient.dispatcher.cancelAll()
            recoveryClient.dispatcher.executorService.shutdownNow()
            recoveryClient.connectionPool.evictAll()
            server.close()
        }
    }
}
