package net.jejer.hipda.data.network

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.net.InetAddress
import java.net.ProtocolException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [ManualRedirectInterceptor], [BrowserHeaderInterceptor], [AppCookieInterceptor],
 * and the assembled chain provided by [HttpClientModule.provideOkHttpClient].
 *
 * Uses [InMemorySiteCookieStore] (instead of encrypted DataStore) to keep tests synchronous,
 * deterministic, and focused on interceptor behavior while still exercising OkHttp's
 * [Cookie.parseAll] and [Cookie.matches] under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NetworkInterceptorChainTest {

    private lateinit var cookieStore: InMemorySiteCookieStore
    private lateinit var userAgentProvider: UserAgentProvider
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        OkHttp.initialize(ApplicationProvider.getApplicationContext())
        cookieStore = InMemorySiteCookieStore()
        userAgentProvider = UserAgentProvider { TEST_UA }
        client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = userAgentProvider,
            transportInterceptor = TransportInterceptor(
                delegateFactory = { null },
                isMainThread = { false },
            ),
        )
    }

    // (a) Redirect method & body handling: 301/302/303 vs 307/308
    @Test
    fun post302Redirect_rewritesToGetAndStripsBodyAndEntityHeaders() {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", "/b")
                    .build(),
            )
            server.enqueue(MockResponse.Builder().code(200).body("done").build())

            val request = Request.Builder()
                .url(server.url("/a"))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=GBK")
                .header("Transfer-Encoding", "chunked")
                .post("message=hello".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("done", response.body.string())
            }

            val reqA = server.takeRequest()
            assertEquals("POST", reqA.method)
            val reqB = server.takeRequest()
            assertEquals("GET", reqB.method)
            assertEquals("/b", reqB.url.encodedPath)
            assertEquals(0, reqB.body?.size ?: 0)
            assertNull(reqB.headers["Content-Type"])
            assertNull(reqB.headers["Content-Length"])
            assertNull(reqB.headers["Transfer-Encoding"])
        }
    }

    @Test
    fun post301And303Redirects_rewriteToGetAndStripBodyAndContentType() {
        for (statusCode in listOf(301, 303)) {
            withServer { server ->
                server.enqueue(
                    MockResponse.Builder()
                        .code(statusCode)
                        .addHeader("Location", "/target-$statusCode")
                        .build(),
                )
                server.enqueue(MockResponse.Builder().code(200).body("ok-$statusCode").build())

                val request = Request.Builder()
                    .url(server.url("/submit"))
                    .post("k=v".toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("ok-$statusCode", response.body.string())
                }

                assertEquals("POST", server.takeRequest().method)
                val next = server.takeRequest()
                assertEquals("GET", next.method)
                assertEquals(0, next.body?.size ?: 0)
                assertNull(next.headers["Content-Type"])
            }
        }
    }

    @Test
    fun post302Redirect_directlyStripsExplicitEntityHeadersInInterceptor() {
        val seenRequests = mutableListOf<Request>()
        val testClient = clientWithTerminalInterceptor(ManualRedirectInterceptor()) { req ->
            seenRequests += req
            if (seenRequests.size == 1) {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .addHeader("Location", "/b")
                    .body("".toResponseBody())
                    .build()
            } else {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("ok".toResponseBody())
                    .build()
            }
        }

        val initial = Request.Builder()
            .url("https://example.test/a")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Content-Length", "3")
            .header("Transfer-Encoding", "chunked")
            .post("k=v".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        testClient.newCall(initial).execute().use { assertEquals(200, it.code) }

        assertEquals(2, seenRequests.size)
        val hop2 = seenRequests[1]
        assertEquals("GET", hop2.method)
        assertNull(hop2.body)
        assertNull(hop2.header("Content-Type"))
        assertNull(hop2.header("Content-Length"))
        assertNull(hop2.header("Transfer-Encoding"))
    }

    @Test
    fun post307And308Redirects_preserveMethodAndByteIdenticalBody() {
        val rawPayload = byteArrayOf(0x00, 0x01, 0x7F, 0x80.toByte(), 0xD6.toByte(), 0xD0.toByte(), 0xFF.toByte())
        for (statusCode in listOf(307, 308)) {
            withServer { server ->
                server.enqueue(
                    MockResponse.Builder()
                        .code(statusCode)
                        .addHeader("Location", "/preserve-$statusCode")
                        .build(),
                )
                server.enqueue(MockResponse.Builder().code(200).body("preserved").build())

                val request = Request.Builder()
                    .url(server.url("/origin"))
                    .post(rawPayload.toRequestBody("application/octet-stream".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("preserved", response.body.string())
                }

                val first = server.takeRequest()
                val second = server.takeRequest()
                assertEquals("POST", first.method)
                assertEquals("POST", second.method)
                assertArrayEquals(rawPayload, first.body?.toByteArray())
                assertArrayEquals(rawPayload, second.body?.toByteArray())
                assertEquals("application/octet-stream", second.headers["Content-Type"])
            }
        }
    }

    @Test
    fun head302Redirect_preservesHeadMethod() {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/b").build())
            server.enqueue(MockResponse.Builder().code(200).build())

            val request = Request.Builder()
                .url(server.url("/a"))
                .head()
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
            }

            assertEquals("HEAD", server.takeRequest().method)
            assertEquals("HEAD", server.takeRequest().method)
        }
    }

    // (b) Per-hop cookie saving on 302 and loading on next hop
    @Test
    fun redirect302SetCookie_savedAndSentOnSameHostNextHop() {
        runSharedRedirectAndCookieScenario(client, cookieStore, TEST_UA)
    }

    @Test
    fun appCookieInterceptor_formatsMultipleCookiesOverwritesExistingAndRemovesWhenEmpty() {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(200).body("no-cookies").build())
            server.enqueue(MockResponse.Builder().code(200).body("with-cookies").build())

            val url = server.url("/forum/index.php")
            // 1. Store empty -> existing Cookie header must be removed
            client.newCall(
                Request.Builder().url(url).header("Cookie", "stale=1").build(),
            ).execute().use { assertEquals(200, it.code) }
            assertEquals(emptyList<String>(), server.takeRequest().headers.values("Cookie"))

            // 2. Store populated -> existing Cookie header overwritten with joined pairs
            cookieStore.saveFromResponse(
                url,
                listOf(
                    requireNotNull(Cookie.parse(url, "a=1; Path=/forum")),
                    requireNotNull(Cookie.parse(url, "b=2; Path=/")),
                ),
            )
            client.newCall(
                Request.Builder().url(url).header("Cookie", "caller=ignored").build(),
            ).execute().use { assertEquals(200, it.code) }
            assertEquals(listOf("a=1; b=2"), server.takeRequest().headers.values("Cookie"))
        }
    }

    @Test
    fun appCookieInterceptor_parsesDomainSetCookieWithPublicSuffixDatabase() {
        val url = "https://www.4d4y.com/forum/logging.php".toHttpUrl()
        val testClient = clientWithTerminalInterceptor(AppCookieInterceptor(cookieStore)) { req ->
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .addHeader("Set-Cookie", "cdb_auth=token123; Domain=.4d4y.com; Path=/")
                .body("".toResponseBody())
                .build()
        }

        testClient.newCall(Request.Builder().url(url).build()).execute().close()

        val loaded = cookieStore.loadForRequest("https://4d4y.com/forum/index.php".toHttpUrl())
        assertEquals(listOf("cdb_auth" to "token123"), loaded.map { it.name to it.value })
    }

    // (c) Cross-host (localhost -> 127.0.0.1) and HTTPS -> HTTP downgrade
    @Test
    fun crossHostRedirect_stripsAuthorizationProxyAuthAndFirstHostCookies() {
        val serverA = MockWebServer()
        val serverB = MockWebServer()
        serverA.start(InetAddress.getByName("localhost"), 0)
        serverB.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val urlA = HttpUrl.Builder()
                .scheme("http")
                .host("localhost")
                .port(serverA.port)
                .encodedPath("/start")
                .build()
            val urlB = HttpUrl.Builder()
                .scheme("http")
                .host("127.0.0.1")
                .port(serverB.port)
                .encodedPath("/dest")
                .build()
            assertEquals("localhost", urlA.host)
            assertEquals("127.0.0.1", urlB.host)

            cookieStore.saveFromResponse(
                urlA,
                listOf(requireNotNull(Cookie.parse(urlA, "auth_a=secretA; Path=/"))),
            )

            serverA.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", urlB.toString())
                    .addHeader("Set-Cookie", "hop_a=extra; Path=/")
                    .build(),
            )
            serverB.enqueue(MockResponse.Builder().code(200).body("from-b").build())

            val request = Request.Builder()
                .url(urlA)
                .header("Authorization", "Bearer top-secret")
                .header("Proxy-Authorization", "Basic cHJveHk=")
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("from-b", response.body.string())
            }

            val reqA = serverA.takeRequest()
            assertEquals("Bearer top-secret", reqA.headers["Authorization"])
            assertEquals("Basic cHJveHk=", reqA.headers["Proxy-Authorization"])
            assertEquals(listOf("auth_a=secretA"), reqA.headers.values("Cookie"))

            val reqB = serverB.takeRequest()
            assertNull(reqB.headers["Authorization"])
            assertNull(reqB.headers["Proxy-Authorization"])
            assertEquals(emptyList<String>(), reqB.headers.values("Cookie"))
        } finally {
            serverA.close()
            serverB.close()
        }
    }

    @Test
    fun crossHostRedirect_loadsSecondHostCookiesWithoutFirstHostCookies() {
        val serverA = MockWebServer()
        val serverB = MockWebServer()
        serverA.start(InetAddress.getByName("localhost"), 0)
        serverB.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val urlA = HttpUrl.Builder()
                .scheme("http")
                .host("localhost")
                .port(serverA.port)
                .encodedPath("/start")
                .build()
            val urlB = HttpUrl.Builder()
                .scheme("http")
                .host("127.0.0.1")
                .port(serverB.port)
                .encodedPath("/dest")
                .build()

            cookieStore.saveFromResponse(urlA, listOf(requireNotNull(Cookie.parse(urlA, "hostA=1; Path=/"))))
            cookieStore.saveFromResponse(urlB, listOf(requireNotNull(Cookie.parse(urlB, "hostB=2; Path=/"))))

            serverA.enqueue(MockResponse.Builder().code(302).addHeader("Location", urlB.toString()).build())
            serverB.enqueue(MockResponse.Builder().code(200).body("ok").build())

            client.newCall(Request.Builder().url(urlA).build()).execute().use { assertEquals(200, it.code) }

            assertEquals(listOf("hostA=1"), serverA.takeRequest().headers.values("Cookie"))
            assertEquals(listOf("hostB=2"), serverB.takeRequest().headers.values("Cookie"))
        } finally {
            serverA.close()
            serverB.close()
        }
    }

    @Test
    fun httpsToHttpDowngrade_stripsSensitiveHeadersEvenOnSameHost() {
        val seenRequests = mutableListOf<Request>()
        val testClient = clientWithTerminalInterceptor(ManualRedirectInterceptor()) { req ->
            seenRequests += req
            if (seenRequests.size == 1) {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .addHeader("Location", "http://example.test/insecure")
                    .body("".toResponseBody())
                    .build()
            } else {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("ok".toResponseBody())
                    .build()
            }
        }

        val initialRequest = Request.Builder()
            .url("https://example.test/secure")
            .header("Authorization", "Bearer secret")
            .header("Proxy-Authorization", "Basic cHJveHk=")
            .header("Cookie", "sid=abc")
            .build()

        testClient.newCall(initialRequest).execute().close()

        assertEquals(2, seenRequests.size)
        val downgraded = seenRequests[1]
        assertEquals("http://example.test/insecure", downgraded.url.toString())
        assertNull(downgraded.header("Authorization"))
        assertNull(downgraded.header("Proxy-Authorization"))
        assertNull(downgraded.header("Cookie"))
    }

    // (d) Max 16 redirects allowed; 17th redirect throws ProtocolException
    @Test
    fun sixteenRedirectsFollowedBy200_succeeds() {
        withServer { server ->
            for (hop in 1..16) {
                server.enqueue(
                    MockResponse.Builder()
                        .code(302)
                        .addHeader("Location", "/hop/$hop")
                        .build(),
                )
            }
            server.enqueue(MockResponse.Builder().code(200).body("final-hop").build())

            val request = Request.Builder().url(server.url("/hop/0")).build()
            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("final-hop", response.body.string())
                assertEquals("/hop/16", response.request.url.encodedPath)
            }
            assertEquals(17, server.requestCount)
        }
    }

    @Test
    fun seventeenRedirects_throwsProtocolException() {
        withServer { server ->
            for (hop in 1..17) {
                server.enqueue(
                    MockResponse.Builder()
                        .code(302)
                        .addHeader("Location", "/hop/$hop")
                        .build(),
                )
            }

            val request = Request.Builder().url(server.url("/hop/0")).build()
            val ex = assertThrows(ProtocolException::class.java) {
                client.newCall(request).execute().close()
            }
            assertTrue(
                "Expected message containing 'Too many follow-up requests: 17', got: ${ex.message}",
                ex.message?.contains("Too many follow-up requests: 17") == true,
            )
            assertEquals(17, server.requestCount)
        }
    }

    // (e) Relative Location resolution & missing/unparseable Location
    @Test
    fun relativeLocation_resolvedAgainstCurrentUrl() {
        withServer { server ->
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", "/next?x=1")
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", "sub/page?y=2")
                    .build(),
            )
            server.enqueue(MockResponse.Builder().code(200).body("arrived").build())

            val request = Request.Builder().url(server.url("/forum/start")).build()
            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("arrived", response.body.string())
                assertEquals("/sub/page", response.request.url.encodedPath)
                assertEquals("y=2", response.request.url.encodedQuery)
            }

            assertEquals("/forum/start", server.takeRequest().url.encodedPath)
            val hop1 = server.takeRequest()
            assertEquals("/next", hop1.url.encodedPath)
            assertEquals("x=1", hop1.url.encodedQuery)
            val hop2 = server.takeRequest()
            assertEquals("/sub/page", hop2.url.encodedPath)
            assertEquals("y=2", hop2.url.encodedQuery)
        }
    }

    @Test
    fun missingOrUnparseableLocation_returns302AsIs() {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(302).body("no-location").build())
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", "mailto:user@example.com")
                    .body("bad-scheme")
                    .build(),
            )

            client.newCall(Request.Builder().url(server.url("/missing")).build()).execute().use { response ->
                assertEquals(302, response.code)
                assertEquals("no-location", response.body.string())
            }
            client.newCall(Request.Builder().url(server.url("/invalid")).build()).execute().use { response ->
                assertEquals(302, response.code)
                assertEquals("bad-scheme", response.body.string())
            }
            assertEquals(2, server.requestCount)
        }
    }

    // (f) User-Agent injected on every hop; caller Accept not overwritten
    @Test
    fun everyHopSendsInjectedUserAgent_andPreservesCallerAccept() {
        val uaVersion = AtomicInteger(0)
        val dynamicClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { "$TEST_UA/hop-${uaVersion.incrementAndGet()}" },
            transportInterceptor = TransportInterceptor(
                delegateFactory = { null },
                isMainThread = { false },
            ),
        )

        withServer { server ->
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/b").build())
            server.enqueue(MockResponse.Builder().code(200).body("ok").build())

            val request = Request.Builder()
                .url(server.url("/a"))
                .header("User-Agent", "Caller-UA/0.1")
                .header("Accept", "application/json")
                .build()

            dynamicClient.newCall(request).execute().use { assertEquals(200, it.code) }

            val reqA = server.takeRequest()
            assertEquals(listOf("$TEST_UA/hop-1"), reqA.headers.values("User-Agent"))
            assertEquals("application/json", reqA.headers["Accept"])
            assertEquals(BrowserHeaderInterceptor.DEFAULT_ACCEPT_LANGUAGE, reqA.headers["Accept-Language"])

            val reqB = server.takeRequest()
            assertEquals(listOf("$TEST_UA/hop-2"), reqB.headers.values("User-Agent"))
            assertEquals("application/json", reqB.headers["Accept"])
            assertEquals(BrowserHeaderInterceptor.DEFAULT_ACCEPT_LANGUAGE, reqB.headers["Accept-Language"])
        }
    }

    @Test
    fun browserHeaderInterceptor_populatesDefaultsWhenMissingAndPreservesAcceptLanguage() {
        withServer { server ->
            server.enqueue(MockResponse.Builder().code(200).body("defaults").build())
            server.enqueue(MockResponse.Builder().code(200).body("custom-lang").build())

            client.newCall(Request.Builder().url(server.url("/defaults")).build()).execute().use {
                assertEquals(200, it.code)
            }
            val defaultReq = server.takeRequest()
            assertEquals(listOf(TEST_UA), defaultReq.headers.values("User-Agent"))
            assertEquals(BrowserHeaderInterceptor.DEFAULT_ACCEPT, defaultReq.headers["Accept"])
            assertEquals(BrowserHeaderInterceptor.DEFAULT_ACCEPT_LANGUAGE, defaultReq.headers["Accept-Language"])

            client.newCall(
                Request.Builder()
                    .url(server.url("/custom-lang"))
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build(),
            ).execute().use { assertEquals(200, it.code) }
            val customLangReq = server.takeRequest()
            assertEquals(BrowserHeaderInterceptor.DEFAULT_ACCEPT, customLangReq.headers["Accept"])
            assertEquals("en-US,en;q=0.9", customLangReq.headers["Accept-Language"])
        }
    }

    // (g) Interceptor chain ordering
    @Test
    fun clientInterceptors_orderedAsManualRedirectThenBrowserHeaderThenAppCookieThenTransport() {
        val interceptors = client.interceptors
        assertEquals(4, interceptors.size)
        assertTrue(interceptors[0] is ManualRedirectInterceptor)
        assertTrue(interceptors[1] is BrowserHeaderInterceptor)
        assertTrue(interceptors[2] is AppCookieInterceptor)
        assertTrue(interceptors[3] is TransportInterceptor)
        val defaultUa = DefaultUserAgentProvider().userAgent()
        assertEquals(DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA, defaultUa)
        assertTrue(defaultUa.contains("X11; Linux x86_64"))
        assertFalse(defaultUa.contains("Android"))
        assertFalse(defaultUa.contains("Mobile"))
    }

    @Test
    fun manualRedirectInterceptor_closesPreviousResponseBodyBeforeNextHop() {
        val firstClosed = AtomicBoolean(false)
        val firstClosedBeforeSecondProceed = AtomicBoolean(false)
        val calls = AtomicInteger(0)

        val testClient = clientWithTerminalInterceptor(ManualRedirectInterceptor()) { req ->
            if (calls.incrementAndGet() == 1) {
                val trackingBody = object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = 4L
                    override fun source(): BufferedSource =
                        object : ForwardingSource(Buffer().writeUtf8("body")) {
                            override fun close() {
                                firstClosed.set(true)
                                super.close()
                            }
                        }.buffer()
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .addHeader("Location", "/b")
                    .body(trackingBody)
                    .build()
            } else {
                firstClosedBeforeSecondProceed.set(firstClosed.get())
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("ok".toResponseBody())
                    .build()
            }
        }

        val request = Request.Builder().url("https://example.test/a").build()
        testClient.newCall(request).execute().close()

        assertTrue(
            "First response body must be closed before proceeding to next hop",
            firstClosedBeforeSecondProceed.get(),
        )
    }

    @Test
    fun canceledCallBeforeSecondHop_stopsClosesFirstResponseAndThrowsIOException() {
        val firstClosed = AtomicBoolean(false)
        val hopCount = AtomicInteger(0)
        val callRef = AtomicReference<Call>()

        val testClient = clientWithTerminalInterceptor(ManualRedirectInterceptor()) { req ->
            val currentHop = hopCount.incrementAndGet()
            if (currentHop == 1) {
                callRef.get().cancel()
                val trackingBody = object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = 4L
                    override fun source(): BufferedSource =
                        object : ForwardingSource(Buffer().writeUtf8("body")) {
                            override fun close() {
                                firstClosed.set(true)
                                super.close()
                            }
                        }.buffer()
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .addHeader("Location", "/b")
                    .body(trackingBody)
                    .build()
            } else {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("unreachable".toResponseBody())
                    .build()
            }
        }

        val call = testClient.newCall(Request.Builder().url("https://example.test/a").build())
        callRef.set(call)

        val ex = assertThrows(IOException::class.java) {
            call.execute().close()
        }
        assertEquals("Canceled", ex.message)
        assertEquals("Second hop must not execute after cancellation", 1, hopCount.get())
        assertTrue("First redirect response must be closed on cancellation", firstClosed.get())
    }

    @Test
    fun inMemorySiteCookieStore_rejectsCrossHostCookieLikeDataStore() {
        val evilCookie = Cookie.Builder()
            .name("hijack")
            .value("1")
            .domain("4d4y.com")
            .path("/")
            .build()
        cookieStore.saveFromResponse("https://other.example/path".toHttpUrl(), listOf(evilCookie))
        assertTrue(cookieStore.loadForRequest("https://4d4y.com/forum/".toHttpUrl()).isEmpty())

        cookieStore.saveFromResponse("https://www.4d4y.com/forum/".toHttpUrl(), listOf(evilCookie))
        assertEquals(
            listOf("hijack" to "1"),
            cookieStore.loadForRequest("https://4d4y.com/forum/".toHttpUrl()).map { it.name to it.value },
        )
    }

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.close()
        }
    }

    private fun clientWithTerminalInterceptor(
        vararg interceptors: Interceptor,
        terminal: (Request) -> Response,
    ): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .apply {
            interceptors.forEach { addInterceptor(it) }
            addInterceptor(Interceptor { chain -> terminal(chain.request()) })
        }
        .build()

    private companion object {
        const val TEST_UA = "Mozilla/5.0 (Linux; Android 14; Test) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}

/**
 * Shared redirect + per-hop cookie scenario reused by [NetworkInterceptorChainTest] and
 * [TransportInterceptorTest] to verify unavailable-transport fallback matches A3a behavior.
 */
internal fun runSharedRedirectAndCookieScenario(
    client: OkHttpClient,
    cookieStore: InMemorySiteCookieStore,
    expectedUserAgent: String,
) {
    val server = MockWebServer()
    server.start()
    try {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/b")
                .addHeader("Set-Cookie", "s=1; Path=/")
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("logged-in").build())

        val request = Request.Builder()
            .url(server.url("/a"))
            .post("k=v".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("logged-in", response.body.string())
        }

        val reqA = server.takeRequest()
        assertEquals("POST", reqA.method)
        assertEquals(emptyList<String>(), reqA.headers.values("Cookie"))
        assertEquals(listOf(expectedUserAgent), reqA.headers.values("User-Agent"))

        val reqB = server.takeRequest()
        assertEquals("GET", reqB.method)
        assertEquals("/b", reqB.url.encodedPath)
        assertEquals(listOf("s=1"), reqB.headers.values("Cookie"))
        assertEquals(listOf(expectedUserAgent), reqB.headers.values("User-Agent"))
        assertEquals(
            listOf("s" to "1"),
            cookieStore.loadForRequest(server.url("/b")).map { it.name to it.value },
        )
    } finally {
        server.close()
    }
}
