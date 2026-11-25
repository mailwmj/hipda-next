package net.jejer.hipda.data.network.webview

import android.content.Context
import dagger.Binds
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import net.jejer.hipda.data.network.DefaultUserAgentProvider
import net.jejer.hipda.data.network.UserAgentModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewUserAgentProviderTest {

    @Test
    fun normalizeWebViewUserAgent_stripsWvAndVersion4FromStandardPixelWebViewUa() {
        val raw =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/131.0.6778.200 Mobile Safari/537.36"

        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36",
            normalizeWebViewUserAgent(raw),
        )
    }

    @Test
    fun normalizeWebViewUserAgent_stripsWvAndVersion4FromAndroid10ReducedUa() {
        val raw =
            "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/134.0.0.0 Mobile Safari/537.36"

        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36",
            normalizeWebViewUserAgent(raw),
        )
    }

    @Test
    fun normalizeWebViewUserAgent_preservesTabletOrFoldableUaWithoutMobileToken() {
        val raw =
            "Mozilla/5.0 (Linux; Android 14; Pixel Fold Build/AP1A.240505.004; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/131.0.6778.200 Safari/537.36"

        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel Fold Build/AP1A.240505.004) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Safari/537.36",
            normalizeWebViewUserAgent(raw),
        )
    }

    @Test
    fun normalizeWebViewUserAgent_handlesMidParenthesisWvWithoutStraySemicolons() {
        val raw =
            "Mozilla/5.0 (Linux; Android 14; wv; Pixel 8 Build/AP2A.240805.005) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.0.0 Mobile Safari/537.36"

        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            normalizeWebViewUserAgent(raw),
        )
    }

    @Test
    fun normalizeWebViewUserAgent_isIdempotentForStandardChromeUa() {
        val chromeUa =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        assertEquals(chromeUa, normalizeWebViewUserAgent(chromeUa))
        assertEquals(chromeUa, normalizeWebViewUserAgent(normalizeWebViewUserAgent(chromeUa)))
    }

    @Test
    fun normalizeWebViewUserAgent_handlesMixedCaseAndExtraSpacesWithoutManglingOtherTokens() {
        val raw =
            "  Mozilla/5.0 (Linux; Android 14; WVGA-Device ;  WV )  AppleWebKit/537.36 " +
                "(KHTML, like Gecko)   version/4.0   Chrome/131.0.0.0   Mobile Safari/537.36  "

        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; WVGA-Device) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            normalizeWebViewUserAgent(raw),
        )
    }

    @Test
    fun normalizeWebViewUserAgent_fallsBackToDefaultChromeUaWhenBlankOrEmptyAfterCleanup() {
        assertEquals(DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA, normalizeWebViewUserAgent(""))
        assertEquals(DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA, normalizeWebViewUserAgent("   \t\n  "))
        assertEquals(DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA, normalizeWebViewUserAgent("; wv Version/4.0"))
    }

    @Test
    fun provider_loadsLazilyOnCallingThreadAndCachesResult() {
        val calls = AtomicInteger(0)
        val supplierThread = AtomicReference<String>()
        val rawUa =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/132.0.0.0 Mobile Safari/537.36"
        val expectedUa =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"

        val provider = WebViewUserAgentProvider {
            calls.incrementAndGet()
            supplierThread.set(Thread.currentThread().name)
            rawUa
        }

        assertEquals("Supplier must not run during construction", 0, calls.get())

        val executor = Executors.newSingleThreadExecutor { Thread(it, "ua-caller-thread") }
        try {
            val firstResult = executor.submit<String> { provider.userAgent() }.get(5, TimeUnit.SECONDS)
            assertEquals(expectedUa, firstResult)
            assertEquals("ua-caller-thread", supplierThread.get())
            assertEquals(1, calls.get())

            assertEquals(expectedUa, provider.userAgent())
            assertEquals(expectedUa, provider.userAgent())
            assertEquals(1, calls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun provider_concurrentFirstCallsInvokeSupplierExactlyOnce() {
        val calls = AtomicInteger(0)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val provider = WebViewUserAgentProvider {
            calls.incrementAndGet()
            Thread.sleep(20)
            "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/133.0.0.0 Mobile Safari/537.36"
        }
        val expected =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map {
                pool.submit<String> {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    provider.userAgent()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { assertEquals(expected, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun provider_fallsBackAndCachesWhenSupplierThrowsErrorOrRuntimeException() {
        val errorCalls = AtomicInteger(0)
        val errorProvider = WebViewUserAgentProvider {
            errorCalls.incrementAndGet()
            throw UnsatisfiedLinkError("WebView native library unavailable")
        }

        assertEquals(DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA, errorProvider.userAgent())
        assertEquals(DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA, errorProvider.userAgent())
        assertEquals(1, errorCalls.get())

        val runtimeCalls = AtomicInteger(0)
        val runtimeProvider = WebViewUserAgentProvider {
            runtimeCalls.incrementAndGet()
            throw IllegalStateException("Missing WebView package")
        }

        assertEquals(DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA, runtimeProvider.userAgent())
        assertEquals(DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA, runtimeProvider.userAgent())
        assertEquals(1, runtimeCalls.get())
    }

    @Test
    fun provider_fallsBackAndCachesWhenSupplierReturnsBlank() {
        val calls = AtomicInteger(0)
        val provider = WebViewUserAgentProvider {
            calls.incrementAndGet()
            "   "
        }

        assertEquals(DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA, provider.userAgent())
        assertEquals(DefaultUserAgentProvider.DEFAULT_MOBILE_CHROME_UA, provider.userAgent())
        assertEquals(1, calls.get())
    }

    @Test
    fun hiltAnnotationsAndUserAgentModuleBindingPointToWebViewUserAgentProvider() {
        assertTrue(WebViewUserAgentProvider::class.java.isAnnotationPresent(Singleton::class.java))
        val injectCtor = WebViewUserAgentProvider::class.java.getDeclaredConstructor(Context::class.java)
        assertTrue(injectCtor.isAnnotationPresent(Inject::class.java))

        val bindMethod = UserAgentModule::class.java.getDeclaredMethod(
            "bindUserAgentProvider",
            WebViewUserAgentProvider::class.java,
        )
        assertTrue(bindMethod.isAnnotationPresent(Binds::class.java))
        assertTrue(bindMethod.isAnnotationPresent(Singleton::class.java))
    }
}
