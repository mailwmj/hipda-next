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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewUserAgentProviderTest {

    private fun assertDesktopLinuxChromeUa(actual: String, expectedMajor: String) {
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$expectedMajor.0.0.0 Safari/537.36",
            actual,
        )
        assertFalse("Desktop UA must not contain Android: $actual", actual.contains("Android", ignoreCase = true))
        assertFalse("Desktop UA must not contain Mobile: $actual", actual.contains("Mobile", ignoreCase = true))
        assertFalse("Desktop UA must not contain ; wv: $actual", actual.contains("; wv", ignoreCase = true))
        assertFalse("Desktop UA must not contain Version/4.0: $actual", actual.contains("Version/4.0", ignoreCase = true))
    }

    @Test
    fun normalizeWebViewUserAgent_convertsAndroid14PixelWebViewUaToDesktopKeepingChromeMajor() {
        val raw =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/131.0.6778.200 Mobile Safari/537.36"

        assertDesktopLinuxChromeUa(normalizeWebViewUserAgent(raw), expectedMajor = "131")
    }

    @Test
    fun normalizeWebViewUserAgent_convertsAndroid10ReducedWebViewUaToDesktopKeepingChromeMajor() {
        val raw =
            "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/134.0.0.0 Mobile Safari/537.36"

        assertDesktopLinuxChromeUa(normalizeWebViewUserAgent(raw), expectedMajor = "134")
    }

    @Test
    fun normalizeWebViewUserAgent_convertsTabletOrFoldableWebViewUaWithoutMobileToken() {
        val raw =
            "Mozilla/5.0 (Linux; Android 14; Pixel Fold Build/AP1A.240505.004; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/135.0.7049.111 Safari/537.36"

        assertDesktopLinuxChromeUa(normalizeWebViewUserAgent(raw), expectedMajor = "135")
    }

    @Test
    fun normalizeWebViewUserAgent_convertsMidParenthesisWvAndMixedCaseTokens() {
        val midWv =
            "Mozilla/5.0 (Linux; Android 14; wv; Pixel 8 Build/AP2A.240805.005) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/132.0.0.0 Mobile Safari/537.36"
        assertDesktopLinuxChromeUa(normalizeWebViewUserAgent(midWv), expectedMajor = "132")

        val mixedCase =
            "  Mozilla/5.0 (Linux; Android 14; WVGA-Device ;  WV )  AppleWebKit/537.36 " +
                "(KHTML, like Gecko)   version/4.0   chrome/136.0.7103.60   Mobile Safari/537.36  "
        assertDesktopLinuxChromeUa(normalizeWebViewUserAgent(mixedCase), expectedMajor = "136")
    }

    @Test
    fun normalizeWebViewUserAgent_isIdempotentForDesktopChromeUa() {
        val desktopUa =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

        assertEquals(desktopUa, normalizeWebViewUserAgent(desktopUa))
        assertEquals(desktopUa, normalizeWebViewUserAgent(normalizeWebViewUserAgent(desktopUa)))
    }

    @Test
    fun normalizeWebViewUserAgent_fallsBackToDefaultDesktopUaWhenBlankOrMissingValidChromeVersion() {
        val samples = listOf(
            "",
            "   \t\n  ",
            "; wv Version/4.0",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 HeadlessChrome/139.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/0.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/134beta Mobile Safari/537.36",
        )
        for (sample in samples) {
            val result = normalizeWebViewUserAgent(sample)
            assertEquals("Unexpected fallback for sample: [$sample]", DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA, result)
            assertDesktopLinuxChromeUa(result, expectedMajor = DefaultUserAgentProvider.DEFAULT_CHROME_MAJOR_VERSION)
        }
    }

    @Test
    fun defaultUserAgentProvider_returnsDesktopLinuxChromeFormat() {
        val ua = DefaultUserAgentProvider().userAgent()
        assertEquals(DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA, ua)
        assertDesktopLinuxChromeUa(ua, expectedMajor = DefaultUserAgentProvider.DEFAULT_CHROME_MAJOR_VERSION)
    }

    @Test
    fun provider_loadsLazilyOnCallingThreadAndCachesDesktopResult() {
        val calls = AtomicInteger(0)
        val supplierThread = AtomicReference<String>()
        val rawUa =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8; wv) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/132.0.6834.163 Mobile Safari/537.36"
        val expectedUa =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

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
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

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

        assertEquals(DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA, errorProvider.userAgent())
        assertEquals(DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA, errorProvider.userAgent())
        assertEquals(1, errorCalls.get())

        val runtimeCalls = AtomicInteger(0)
        val runtimeProvider = WebViewUserAgentProvider {
            runtimeCalls.incrementAndGet()
            throw IllegalStateException("Missing WebView package")
        }

        assertEquals(DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA, runtimeProvider.userAgent())
        assertEquals(DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA, runtimeProvider.userAgent())
        assertEquals(1, runtimeCalls.get())
    }

    @Test
    fun provider_fallsBackAndCachesWhenSupplierReturnsBlank() {
        val calls = AtomicInteger(0)
        val provider = WebViewUserAgentProvider {
            calls.incrementAndGet()
            "   "
        }

        assertEquals(DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA, provider.userAgent())
        assertEquals(DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA, provider.userAgent())
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
