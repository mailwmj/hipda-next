package net.jejer.hipda.data.network

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandlerFactory
import java.nio.ByteBuffer
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.chromium.net.BidirectionalStream
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import org.chromium.net.ExperimentalBidirectionalStream
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.ExperimentalUrlRequest
import org.chromium.net.ICronetEngineBuilder
import org.chromium.net.UploadDataProvider
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [TransportInterceptor], lazy thread-safe Cronet initialization, fallback parity
 * with OkHttp transport, real `CronetInterceptor` redirect strategy verification, and provider
 * engine builder configuration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TransportInterceptorTest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        OkHttp.initialize(appContext)
    }

    // (b) Unavailable transport matches A3a chain behavior on redirect + per-hop cookie scenario
    @Test
    fun unavailableTransport_matchesBaselineA3aRedirectAndCookieBehavior() {
        val factoryCalls = AtomicInteger(0)
        val unavailableStore = InMemorySiteCookieStore()
        val unavailableClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = unavailableStore,
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = TransportInterceptor(
                delegateFactory = {
                    factoryCalls.incrementAndGet()
                    null
                },
                isMainThread = { false },
            ),
        )
        val baselineStore = InMemorySiteCookieStore()
        val baselineClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(ManualRedirectInterceptor())
            .addInterceptor(BrowserHeaderInterceptor(UserAgentProvider { TEST_UA }))
            .addInterceptor(AppCookieInterceptor(baselineStore))
            .build()

        runSharedRedirectAndCookieScenario(baselineClient, baselineStore, TEST_UA)
        runSharedRedirectAndCookieScenario(unavailableClient, unavailableStore, TEST_UA)

        assertEquals(
            "Unavailable transport factory must be invoked once and cached across all hops",
            1,
            factoryCalls.get(),
        )
    }

    // (c) Factory is NOT called during provideOkHttpClient and is called ONLY ONCE under 8 concurrent requests
    @Test
    fun provideOkHttpClient_doesNotInvokeFactory_andEightConcurrentRequestsInitializeOnlyOnce() {
        val factoryCalls = AtomicInteger(0)
        val delegateCalls = AtomicInteger(0)
        val transport = TransportInterceptor(
            delegateFactory = {
                factoryCalls.incrementAndGet()
                Thread.sleep(50)
                Interceptor { chain ->
                    delegateCalls.incrementAndGet()
                    chain.proceed(chain.request())
                }
            },
        )

        val client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(),
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = transport,
        )
        assertEquals("provideOkHttpClient must not initialize transport eagerly", 0, factoryCalls.get())

        val server = MockWebServer()
        server.start()
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(8) {
                server.enqueue(MockResponse.Builder().code(200).body("ok").build())
            }
            val barrier = CyclicBarrier(8)
            val futures = (0 until 8).map { idx ->
                pool.submit(
                    Callable {
                        barrier.await(5, TimeUnit.SECONDS)
                        client.newCall(Request.Builder().url(server.url("/req/$idx")).build()).execute().use {
                            assertEquals(200, it.code)
                            it.body.string()
                        }
                    },
                )
            }
            futures.forEach { assertEquals("ok", it.get(10, TimeUnit.SECONDS)) }

            assertEquals("Concurrent requests must initialize transport factory once", 1, factoryCalls.get())
            assertEquals("All 8 concurrent requests must use initialized delegate", 8, delegateCalls.get())
        } finally {
            pool.shutdownNow()
            server.close()
        }
    }

    @Test
    fun unavailableResultIsCachedAcrossEightConcurrentRequests() {
        val factoryCalls = AtomicInteger(0)
        val transport = TransportInterceptor(
            delegateFactory = {
                factoryCalls.incrementAndGet()
                Thread.sleep(30)
                null
            },
        )
        val client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(),
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = transport,
        )

        val server = MockWebServer()
        server.start()
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(8) { server.enqueue(MockResponse.Builder().code(200).body("fallback").build()) }
            val barrier = CyclicBarrier(8)
            val futures = (0 until 8).map { idx ->
                pool.submit(
                    Callable {
                        barrier.await(5, TimeUnit.SECONDS)
                        client.newCall(Request.Builder().url(server.url("/fb/$idx")).build()).execute().use {
                            assertEquals(200, it.code)
                            it.body.string()
                        }
                    },
                )
            }
            futures.forEach { assertEquals("fallback", it.get(10, TimeUnit.SECONDS)) }
            assertEquals("Unavailable result must be cached and not retried", 1, factoryCalls.get())
        } finally {
            pool.shutdownNow()
            server.close()
        }
    }

    @Test
    fun mainThreadCall_doesNotInitializeFactory_workerThreadInitializesLater() {
        val factoryCalls = AtomicInteger(0)
        val delegateCalls = AtomicInteger(0)
        val onMainThread = AtomicBoolean(true)
        val transport = TransportInterceptor(
            delegateFactory = {
                factoryCalls.incrementAndGet()
                Interceptor { chain ->
                    delegateCalls.incrementAndGet()
                    chain.proceed(chain.request())
                }
            },
            isMainThread = { onMainThread.get() },
        )
        val client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(),
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = transport,
        )

        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(200).body("main-fallback").build())
            server.enqueue(MockResponse.Builder().code(200).body("worker-delegate").build())

            client.newCall(Request.Builder().url(server.url("/main")).build()).execute().use {
                assertEquals("main-fallback", it.body.string())
            }
            assertEquals(0, factoryCalls.get())
            assertEquals(0, delegateCalls.get())

            onMainThread.set(false)
            client.newCall(Request.Builder().url(server.url("/worker")).build()).execute().use {
                assertEquals("worker-delegate", it.body.string())
            }
            assertEquals(1, factoryCalls.get())
            assertEquals(1, delegateCalls.get())
        } finally {
            server.close()
        }
    }

    // (d) Available transport receives requests processed by Redirect/Header/Cookie interceptors
    @Test
    fun availableTransport_receivesRequestsProcessedByRedirectHeaderAndCookieInterceptors() {
        val cookieStore = InMemorySiteCookieStore()
        val startUrl = "https://www.4d4y.com/forum/login.php".toHttpUrl()
        cookieStore.saveFromResponse(
            startUrl,
            listOf(requireNotNull(Cookie.parse(startUrl, "pre=1; Path=/"))),
        )

        val seenRequests = mutableListOf<Request>()
        val transportDouble = Interceptor { chain ->
            val req = chain.request()
            seenRequests += req
            if (seenRequests.size == 1) {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_2)
                    .code(302)
                    .message("Found")
                    .addHeader("Location", "/forum/index.php")
                    .addHeader("Set-Cookie", "cdb_auth=fresh; Path=/")
                    .body("".toResponseBody())
                    .build()
            } else {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_2)
                    .code(200)
                    .message("OK")
                    .body("welcome".toResponseBody())
                    .build()
            }
        }

        val client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = TransportInterceptor(
                delegateFactory = { transportDouble },
                isMainThread = { false },
            ),
        )

        val request = Request.Builder()
            .url(startUrl)
            .header("User-Agent", "Caller-UA")
            .post("user=demo".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("welcome", response.body.string())
        }

        assertEquals(2, seenRequests.size)
        val hop1 = seenRequests[0]
        assertEquals("POST", hop1.method)
        assertEquals(listOf(TEST_UA), hop1.headers.values("User-Agent"))
        assertEquals(BrowserHeaderInterceptor.DEFAULT_ACCEPT, hop1.header("Accept"))
        assertEquals(BrowserHeaderInterceptor.DEFAULT_ACCEPT_LANGUAGE, hop1.header("Accept-Language"))
        assertEquals(listOf("pre=1"), hop1.headers.values("Cookie"))

        val hop2 = seenRequests[1]
        assertEquals("GET", hop2.method)
        assertEquals("https://www.4d4y.com/forum/index.php", hop2.url.toString())
        assertNull(hop2.body)
        assertEquals(listOf(TEST_UA), hop2.headers.values("User-Agent"))
        assertEquals(listOf("pre=1; cdb_auth=fresh"), hop2.headers.values("Cookie"))
    }

    // (d) Real CronetInterceptor built by buildCronetInterceptor uses RedirectStrategy.withoutRedirects()
    @Test
    fun buildCronetInterceptor_disablesCronetRedirectsSoManualRedirectAndCookiesRunPerHop() {
        val fakeEngine = RedirectRecordingFakeCronetEngine()
        val realCronetInterceptor = buildCronetInterceptor(fakeEngine)
        val cookieStore = InMemorySiteCookieStore()
        val client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = cookieStore,
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = TransportInterceptor(
                delegateFactory = { realCronetInterceptor },
                isMainThread = { false },
            ),
        )

        try {
            val request = Request.Builder()
                .url("https://www.4d4y.com/forum/redirect.php")
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("final-page", response.body.string())
            }

            assertEquals(
                "Cronet must NOT auto-follow redirects when RedirectStrategy.withoutRedirects() is set",
                0,
                fakeEngine.followRedirectCalls.get(),
            )
            assertEquals(
                "CronetInterceptor must cancel the Cronet redirect request and return 302 to OkHttp chain",
                1,
                fakeEngine.cancelCalls.get(),
            )
            assertEquals(2, fakeEngine.recordedRequests.size)

            val hop1 = fakeEngine.recordedRequests[0]
            assertEquals("https://www.4d4y.com/forum/redirect.php", hop1.url)
            assertEquals(listOf(TEST_UA), hop1.headers.filter { it.first.equals("User-Agent", true) }.map { it.second })

            val hop2 = fakeEngine.recordedRequests[1]
            assertEquals("https://www.4d4y.com/forum/target.php", hop2.url)
            assertEquals(listOf(TEST_UA), hop2.headers.filter { it.first.equals("User-Agent", true) }.map { it.second })
            assertEquals(
                listOf("hop_sid=xyz"),
                hop2.headers.filter { it.first.equals("Cookie", true) }.map { it.second },
            )
        } finally {
            realCronetInterceptor.close()
        }
    }

    // Engine creation tests: HttpEngine vs PlayServices, HTTP/2 + QUIC + Brotli flags, installer gating
    @Test
    fun createCronetEngine_api34WithHttpEngine_skipsPlayServicesInstallerAndEnablesHttp2QuicBrotli() {
        val httpEngineBuilder = RecordingCronetEngineBuilder()
        val playServicesEnabledChecked = AtomicBoolean(false)
        val installerCalled = AtomicBoolean(false)

        val providers = listOf(
            FakeCronetProvider(
                context = appContext,
                providerName = PROVIDER_NAME_PLAY_SERVICES,
                enabled = {
                    playServicesEnabledChecked.set(true)
                    true
                },
                builderDelegate = RecordingCronetEngineBuilder(),
            ),
            FakeCronetProvider(
                context = appContext,
                providerName = PROVIDER_NAME_HTTP_ENGINE,
                enabled = { true },
                builderDelegate = httpEngineBuilder,
            ),
            FakeCronetProvider(
                context = appContext,
                providerName = PROVIDER_NAME_FALLBACK,
                enabled = { true },
                builderDelegate = RecordingCronetEngineBuilder(),
            ),
        )

        val engine = createCronetEngine(
            context = appContext,
            sdkInt = 34,
            installPlayServicesProvider = {
                installerCalled.set(true)
                true
            },
            providersLoader = { providers },
        )

        assertNotNull(engine)
        assertFalse("HttpEngine on API 34 must not call PlayServices installer", installerCalled.get())
        assertFalse(
            "HttpEngine on API 34 must not trigger PlayServicesCronetProvider.isEnabled()",
            playServicesEnabledChecked.get(),
        )
        assertTrue(httpEngineBuilder.http2Enabled)
        assertTrue(httpEngineBuilder.quicEnabled)
        assertTrue(httpEngineBuilder.brotliEnabled)
    }

    @Test
    fun createCronetEngine_api33_installsPlayServicesAndEnablesHttp2QuicBrotli() {
        val playBuilder = RecordingCronetEngineBuilder()
        val installerCalled = AtomicBoolean(false)

        val providers = listOf(
            FakeCronetProvider(
                context = appContext,
                providerName = PROVIDER_NAME_HTTP_ENGINE,
                enabled = { true },
                builderDelegate = RecordingCronetEngineBuilder(),
            ),
            FakeCronetProvider(
                context = appContext,
                providerName = PROVIDER_NAME_PLAY_SERVICES,
                enabled = { true },
                builderDelegate = playBuilder,
            ),
            FakeCronetProvider(
                context = appContext,
                providerName = PROVIDER_NAME_FALLBACK,
                enabled = { true },
                builderDelegate = RecordingCronetEngineBuilder(),
            ),
        )

        val engine = createCronetEngine(
            context = appContext,
            sdkInt = 33,
            installPlayServicesProvider = {
                installerCalled.set(true)
                true
            },
            providersLoader = { providers },
        )

        assertNotNull(engine)
        assertTrue(installerCalled.get())
        assertTrue(playBuilder.http2Enabled)
        assertTrue(playBuilder.quicEnabled)
        assertTrue(playBuilder.brotliEnabled)
    }

    @Test
    fun createCronetEngine_whenPlayServicesInstallFails_doesNotQueryPlayServicesIsEnabledAndReturnsNull() {
        val playServicesEnabledChecked = AtomicBoolean(false)
        val fallbackBuilt = AtomicBoolean(false)

        val providers = listOf(
            FakeCronetProvider(
                context = appContext,
                providerName = PROVIDER_NAME_PLAY_SERVICES,
                enabled = {
                    playServicesEnabledChecked.set(true)
                    true
                },
                builderDelegate = RecordingCronetEngineBuilder(),
            ),
            FakeCronetProvider(
                context = appContext,
                providerName = PROVIDER_NAME_FALLBACK,
                enabled = { true },
                builderDelegate = RecordingCronetEngineBuilder(onBuild = { fallbackBuilt.set(true) }),
            ),
        )

        val engine = createCronetEngine(
            context = appContext,
            sdkInt = 33,
            installPlayServicesProvider = { false },
            providersLoader = { providers },
        )

        assertNull(engine)
        assertFalse(
            "Must not call PlayServicesCronetProvider.isEnabled() when install failed or timed out",
            playServicesEnabledChecked.get(),
        )
        assertFalse("Must never build Fallback-Cronet-Provider", fallbackBuilt.get())
    }

    @Test
    fun createCronetEngine_api34WhenHttpEngineBuildThrows_fallsBackToPlayServices() {
        val playBuilder = RecordingCronetEngineBuilder()
        val installerCalled = AtomicBoolean(false)
        val providers = listOf(
            FakeCronetProvider(
                context = appContext,
                providerName = PROVIDER_NAME_HTTP_ENGINE,
                enabled = { true },
                builderDelegate = RecordingCronetEngineBuilder(onBuild = { error("OEM HttpEngine broken") }),
            ),
            FakeCronetProvider(
                context = appContext,
                providerName = PROVIDER_NAME_PLAY_SERVICES,
                enabled = { true },
                builderDelegate = playBuilder,
            ),
        )

        val engine = createCronetEngine(
            context = appContext,
            sdkInt = 34,
            installPlayServicesProvider = {
                installerCalled.set(true)
                true
            },
            providersLoader = { providers },
        )

        assertNotNull(engine)
        assertTrue(installerCalled.get())
        assertTrue(playBuilder.http2Enabled)
        assertTrue(playBuilder.quicEnabled)
        assertTrue(playBuilder.brotliEnabled)
    }

    @Test
    fun delegateFactoryException_cachesUnavailableAndFallsBackToOkHttp() {
        val factoryCalls = AtomicInteger(0)
        val client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(),
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = TransportInterceptor(
                delegateFactory = {
                    factoryCalls.incrementAndGet()
                    throw IllegalStateException("cronet init crash")
                },
                isMainThread = { false },
            ),
        )

        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(200).body("first").build())
            server.enqueue(MockResponse.Builder().code(200).body("second").build())

            client.newCall(Request.Builder().url(server.url("/1")).build()).execute().use {
                assertEquals("first", it.body.string())
            }
            client.newCall(Request.Builder().url(server.url("/2")).build()).execute().use {
                assertEquals("second", it.body.string())
            }
            assertEquals(1, factoryCalls.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun isAndroidMainThread_returnsTrueOnMainThreadAndFalseOnWorkerThread() {
        assertTrue("Robolectric test thread must be recognized as Android main thread", isAndroidMainThread())

        val worker = Executors.newSingleThreadExecutor()
        try {
            val onWorker = worker.submit(Callable { isAndroidMainThread() }).get(5, TimeUnit.SECONDS)
            assertFalse("Background worker thread must not be reported as Android main thread", onWorker)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun defaultMainThreadGuard_defersInitializationOnMainThreadAndInitializesOnWorkerThread() {
        val factoryCalls = AtomicInteger(0)
        val delegateCalls = AtomicInteger(0)
        // Uses merged internal constructor default: isMainThread = ::isAndroidMainThread
        val transport = TransportInterceptor(
            delegateFactory = {
                factoryCalls.incrementAndGet()
                Interceptor { chain ->
                    delegateCalls.incrementAndGet()
                    chain.proceed(chain.request())
                }
            },
        )
        val client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(),
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = transport,
        )

        val server = MockWebServer()
        server.start()
        val worker = Executors.newSingleThreadExecutor()
        try {
            server.enqueue(MockResponse.Builder().code(200).body("main-fallback").build())
            server.enqueue(MockResponse.Builder().code(200).body("worker-delegate").build())

            client.newCall(Request.Builder().url(server.url("/main-default")).build()).execute().use {
                assertEquals("main-fallback", it.body.string())
            }
            assertEquals("Default main-thread guard must skip factory on main thread", 0, factoryCalls.get())
            assertEquals(0, delegateCalls.get())

            val workerBody = worker.submit(
                Callable {
                    client.newCall(Request.Builder().url(server.url("/worker-default")).build()).execute().use {
                        it.body.string()
                    }
                },
            ).get(5, TimeUnit.SECONDS)
            assertEquals("worker-delegate", workerBody)
            assertEquals(1, factoryCalls.get())
            assertEquals(1, delegateCalls.get())
        } finally {
            worker.shutdownNow()
            server.close()
        }
    }

    @Test
    fun createFactory_defersOnMainThread_andBuildsRealCronetInterceptorOnWorkerThread() {
        val fakeEngine = RedirectRecordingFakeCronetEngine()
        val builder = RecordingCronetEngineBuilder(engineFactory = { fakeEngine })
        val stubAppContext = StubCronetApplicationContext(appContext, builder)
        val outerContext = object : ContextWrapper(appContext) {
            override fun getApplicationContext(): Context = stubAppContext
        }

        val client = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(),
            userAgentProvider = UserAgentProvider { TEST_UA },
            transportInterceptor = TransportInterceptor.create(outerContext),
        )
        assertEquals("TransportInterceptor.create must remain lazy", 0, builder.buildCount)

        val server = MockWebServer()
        server.start()
        val worker = Executors.newSingleThreadExecutor()
        try {
            server.enqueue(MockResponse.Builder().code(200).body("main-thread-fallback").build())
            // Safety response so a broken TransportInterceptor.create returning { null } fails immediately
            // against local MockWebServer instead of timing out or reaching external hosts.
            server.enqueue(MockResponse.Builder().code(200).body("unexpected-okhttp-fallback").build())

            // Main-thread call must fall back to OkHttp without invoking Cronet provider discovery.
            client.newCall(Request.Builder().url(server.url("/main")).build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("main-thread-fallback", response.body.string())
            }
            assertEquals(0, builder.buildCount)
            assertTrue(fakeEngine.recordedRequests.isEmpty())

            // Worker-thread call exercises TransportInterceptor.create -> createCronetEngine ->
            // CronetProvider.getAllProviders -> buildCronetInterceptor end to end.
            val workerBody = worker.submit(
                Callable {
                    client.newCall(Request.Builder().url(server.url("/forum/redirect.php")).build())
                        .execute()
                        .use { response ->
                            assertEquals(200, response.code)
                            response.body.string()
                        }
                },
            ).get(10, TimeUnit.SECONDS)

            assertEquals("final-page", workerBody)
            assertEquals("Default create() factory must build Cronet engine once", 1, builder.buildCount)
            assertTrue(builder.http2Enabled)
            assertTrue(builder.quicEnabled)
            assertTrue(builder.brotliEnabled)
            assertEquals(0, fakeEngine.followRedirectCalls.get())
            assertEquals(2, fakeEngine.recordedRequests.size)
            assertEquals(1, server.requestCount)
        } finally {
            worker.shutdownNow()
            server.close()
        }
    }

    @Test
    fun installPlayServicesCronet_returnsFalseWhenUnavailableAndPreservesInterruptFlag() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            val installedWithoutGms = worker.submit(
                Callable { installPlayServicesCronet(appContext) },
            ).get(10, TimeUnit.SECONDS)
            assertFalse("Play Services Cronet install must fail safely without GMS", installedWithoutGms)

            val (installedWhenInterrupted, interruptPreserved) = worker.submit(
                Callable {
                    Thread.currentThread().interrupt()
                    try {
                        val installed = installPlayServicesCronet(appContext)
                        installed to Thread.currentThread().isInterrupted
                    } finally {
                        Thread.interrupted()
                    }
                },
            ).get(10, TimeUnit.SECONDS)
            assertFalse(installedWhenInterrupted)
            assertTrue("InterruptedException handling must restore thread interrupt flag", interruptPreserved)

            // Default installPlayServicesProvider wiring inside createCronetEngine also returns null safely.
            val playProvider = FakeCronetProvider(
                context = appContext,
                providerName = PROVIDER_NAME_PLAY_SERVICES,
                enabled = { true },
                builderDelegate = RecordingCronetEngineBuilder(),
            )
            val engine = worker.submit(
                Callable {
                    createCronetEngine(
                        context = appContext,
                        sdkInt = 33,
                        providersLoader = { listOf(playProvider) },
                    )
                },
            ).get(10, TimeUnit.SECONDS)
            assertNull(engine)
        } finally {
            worker.shutdownNow()
        }
    }

    private data class RecordedCronetRequest(
        val url: String,
        val method: String,
        val headers: List<Pair<String, String>>,
    )

    private class StubCronetApplicationContext(
        base: Context,
        val builder: RecordingCronetEngineBuilder,
    ) : ContextWrapper(base) {
        private val interceptingClassLoader = object : ClassLoader(base.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == HTTP_ENGINE_PROVIDER_CLASS_NAME) {
                    StubHttpEngineCronetProvider::class.java
                } else {
                    super.loadClass(name, resolve)
                }
        }

        override fun getApplicationContext(): Context = this
        override fun getClassLoader(): ClassLoader = interceptingClassLoader
    }

    class StubHttpEngineCronetProvider(context: Context) : CronetProvider(context) {
        override fun createBuilder(): CronetEngine.Builder =
            CronetEngine.Builder((mContext as StubCronetApplicationContext).builder)

        override fun getName(): String = PROVIDER_NAME_HTTP_ENGINE
        override fun getVersion(): String = "1.0.0"
        override fun isEnabled(): Boolean = true
    }

    private class RedirectRecordingFakeCronetEngine : ExperimentalCronetEngine() {
        val recordedRequests = mutableListOf<RecordedCronetRequest>()
        val followRedirectCalls = AtomicInteger(0)
        val cancelCalls = AtomicInteger(0)

        override fun getVersionString(): String = "fake-cronet"
        override fun shutdown() {}
        override fun startNetLogToFile(fileName: String?, logAll: Boolean) {}
        override fun stopNetLog() {}
        override fun getGlobalMetricsDeltas(): ByteArray = ByteArray(0)
        override fun openConnection(url: URL?): URLConnection = throw UnsupportedOperationException()
        override fun openConnection(url: URL?, proxy: java.net.Proxy?): URLConnection =
            throw UnsupportedOperationException()
        override fun createURLStreamHandlerFactory(): URLStreamHandlerFactory = throw UnsupportedOperationException()
        override fun newBidirectionalStreamBuilder(
            url: String?,
            callback: BidirectionalStream.Callback?,
            executor: Executor?,
        ): ExperimentalBidirectionalStream.Builder = throw UnsupportedOperationException()

        override fun newUrlRequestBuilder(
            url: String,
            callback: UrlRequest.Callback,
            executor: Executor,
        ): ExperimentalUrlRequest.Builder = object : ExperimentalUrlRequest.Builder() {
            private var httpMethod: String = "GET"
            private val headers = mutableListOf<Pair<String, String>>()

            override fun setHttpMethod(method: String): ExperimentalUrlRequest.Builder = apply { httpMethod = method }
            override fun addHeader(header: String, value: String): ExperimentalUrlRequest.Builder = apply {
                headers += header to value
            }

            override fun disableCache(): ExperimentalUrlRequest.Builder = this
            override fun setPriority(priority: Int): ExperimentalUrlRequest.Builder = this
            override fun setUploadDataProvider(
                uploadDataProvider: UploadDataProvider,
                executor: Executor,
            ): ExperimentalUrlRequest.Builder = this

            override fun allowDirectExecutor(): ExperimentalUrlRequest.Builder = this

            override fun build(): ExperimentalUrlRequest {
                val snapshot = RecordedCronetRequest(url, httpMethod, headers.toList())
                return object : ExperimentalUrlRequest() {
                    private var bodyDelivered = false
                    private var done = false

                    override fun start() {
                        recordedRequests += snapshot
                        executor.execute {
                            if (url.endsWith("/redirect.php")) {
                                val targetUrl = requireNotNull(url.toHttpUrl().resolve("/forum/target.php")).toString()
                                val info = fakeResponseInfo(
                                    url = url,
                                    statusCode = 302,
                                    statusText = "Found",
                                    headers = listOf(
                                        "Location" to "/forum/target.php",
                                        "Set-Cookie" to "hop_sid=xyz; Path=/",
                                    ),
                                )
                                callback.onRedirectReceived(this, info, targetUrl)
                            } else {
                                val info = fakeResponseInfo(
                                    url = url,
                                    statusCode = 200,
                                    statusText = "OK",
                                    headers = listOf("Content-Type" to "text/plain; charset=utf-8"),
                                )
                                callback.onResponseStarted(this, info)
                            }
                        }
                    }

                    override fun followRedirect() {
                        followRedirectCalls.incrementAndGet()
                    }

                    override fun read(buffer: ByteBuffer) {
                        executor.execute {
                            val info = fakeResponseInfo(url, 200, "OK", emptyList())
                            if (!bodyDelivered) {
                                bodyDelivered = true
                                buffer.put("final-page".toByteArray(Charsets.UTF_8))
                                callback.onReadCompleted(this, info, buffer)
                            } else {
                                done = true
                                callback.onSucceeded(this, info)
                            }
                        }
                    }

                    override fun cancel() {
                        cancelCalls.incrementAndGet()
                        done = true
                    }

                    override fun isDone(): Boolean = done
                    override fun getStatus(listener: StatusListener) {
                        listener.onStatus(Status.IDLE)
                    }
                }
            }
        }
    }

    private class FakeCronetProvider(
        context: Context,
        private val providerName: String,
        private val enabled: () -> Boolean,
        private val builderDelegate: ICronetEngineBuilder,
    ) : CronetProvider(context) {
        override fun createBuilder(): CronetEngine.Builder = CronetEngine.Builder(builderDelegate)
        override fun getName(): String = providerName
        override fun getVersion(): String = "1.0.0"
        override fun isEnabled(): Boolean = enabled()
    }

    private class RecordingCronetEngineBuilder(
        private val onBuild: () -> Unit = {},
        private val engineFactory: () -> ExperimentalCronetEngine = { NoOpExperimentalCronetEngine() },
    ) : ICronetEngineBuilder() {
        var buildCount: Int = 0
        var http2Enabled: Boolean = false
        var quicEnabled: Boolean = false
        var brotliEnabled: Boolean = false

        override fun addPublicKeyPins(
            hostName: String?,
            pinsSha256: MutableSet<ByteArray>?,
            includeSubdomains: Boolean,
            expirationDate: Date?,
        ): ICronetEngineBuilder = this

        override fun addQuicHint(host: String?, port: Int, alternatePort: Int): ICronetEngineBuilder = this
        override fun enableHttp2(value: Boolean): ICronetEngineBuilder = apply { http2Enabled = value }
        override fun enableSdch(value: Boolean): ICronetEngineBuilder = this
        override fun enableHttpCache(cacheMode: Int, maxSize: Long): ICronetEngineBuilder = this
        override fun enablePublicKeyPinningBypassForLocalTrustAnchors(value: Boolean): ICronetEngineBuilder = this
        override fun enableQuic(value: Boolean): ICronetEngineBuilder = apply { quicEnabled = value }
        override fun enableBrotli(value: Boolean): ICronetEngineBuilder = apply { brotliEnabled = value }
        override fun setExperimentalOptions(options: String?): ICronetEngineBuilder = this
        override fun setLibraryLoader(loader: CronetEngine.Builder.LibraryLoader?): ICronetEngineBuilder = this
        override fun setStoragePath(value: String?): ICronetEngineBuilder = this
        override fun setUserAgent(userAgent: String?): ICronetEngineBuilder = this
        override fun getDefaultUserAgent(): String = "fake-ua"
        override fun build(): ExperimentalCronetEngine {
            buildCount++
            onBuild()
            return engineFactory()
        }
    }

    private class NoOpExperimentalCronetEngine : ExperimentalCronetEngine() {
        override fun getVersionString(): String = "noop"
        override fun shutdown() {}
        override fun startNetLogToFile(fileName: String?, logAll: Boolean) {}
        override fun stopNetLog() {}
        override fun getGlobalMetricsDeltas(): ByteArray = ByteArray(0)
        override fun openConnection(url: URL?): URLConnection = throw UnsupportedOperationException()
        override fun openConnection(url: URL?, proxy: java.net.Proxy?): URLConnection =
            throw UnsupportedOperationException()
        override fun createURLStreamHandlerFactory(): URLStreamHandlerFactory = throw UnsupportedOperationException()
        override fun newUrlRequestBuilder(
            url: String?,
            callback: UrlRequest.Callback?,
            executor: Executor?,
        ): org.chromium.net.ExperimentalUrlRequest.Builder = throw UnsupportedOperationException()
        override fun newBidirectionalStreamBuilder(
            url: String?,
            callback: org.chromium.net.BidirectionalStream.Callback?,
            executor: Executor?,
        ): org.chromium.net.ExperimentalBidirectionalStream.Builder = throw UnsupportedOperationException()
    }

    private companion object {
        const val TEST_UA = "Mozilla/5.0 (Linux; Android 14; Test) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
        const val HTTP_ENGINE_PROVIDER_CLASS_NAME = "org.chromium.net.impl.HttpEngineNativeProvider"

        fun fakeResponseInfo(
            url: String,
            statusCode: Int,
            statusText: String,
            headers: List<Pair<String, String>>,
        ): UrlResponseInfo = object : UrlResponseInfo() {
            private val headerEntries = headers.map { (k, v) -> java.util.AbstractMap.SimpleImmutableEntry(k, v) }
            private val headerMap = headers.groupBy({ it.first }, { it.second })

            override fun getUrl(): String = url
            override fun getUrlChain(): List<String> = listOf(url)
            override fun getHttpStatusCode(): Int = statusCode
            override fun getHttpStatusText(): String = statusText
            override fun getAllHeadersAsList(): List<Map.Entry<String, String>> = headerEntries
            override fun getAllHeaders(): Map<String, List<String>> = headerMap
            override fun wasCached(): Boolean = false
            override fun getNegotiatedProtocol(): String = "h2"
            override fun getProxyServer(): String = ""
            override fun getReceivedByteCount(): Long = 0L
        }
    }
}
