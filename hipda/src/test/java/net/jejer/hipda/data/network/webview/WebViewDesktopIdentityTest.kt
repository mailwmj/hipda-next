package net.jejer.hipda.data.network.webview

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.UserAgentMetadata
import java.io.File
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jejer.hipda.data.network.DefaultUserAgentProvider
import net.jejer.hipda.data.network.HttpClientModule
import net.jejer.hipda.data.network.InMemorySiteCookieStore
import net.jejer.hipda.data.network.TransportInterceptor
import net.jejer.hipda.debug.sampleexport.configureSampleLoginWebView
import net.jejer.hipda.ui.challenge.AndroidChallengeWebViewHost
import net.jejer.hipda.ui.challenge.ChallengeActivity
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttp
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboWebSettings

/**
 * 覆盖 A5c 桌面身份闭环验收：
 * 1. 挑战页 WebView（`ChallengeActivity`）、调试登录 WebView（`SampleLoginWebView`）、OkHttp 请求三处 UA 字符串完全一致；
 * 2. `UserAgentMetadata` 不可用时不崩溃，可用时通过假 `WebSettings` 断言 `platform="Linux"`、`mobile=false` 与品牌版本一致；
 * 3. `src/main` 与 `src/debug` 源码扫描零 `Mobile Safari` 字面量，且两个 WebView 均走统一配置入口。
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [34],
)
class WebViewDesktopIdentityTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        OkHttp.initialize(application)
    }

    @Test
    fun challengeWebView_loginWebView_andOkHttpRequest_shareExactSameDesktopUserAgent() = runBlocking {
        val rawDeviceWebViewUa =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005; wv) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
                "Chrome/136.0.7103.60 Mobile Safari/537.36"
        val expectedDesktopUa =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
        val userAgentProvider = WebViewUserAgentProvider { rawDeviceWebViewUa }

        // 1. 挑战页 WebView（ChallengeActivity）
        val host = AndroidChallengeWebViewHost(application)
        val acquireDeferred = async(Dispatchers.Default) {
            host.acquire("https://www.4d4y.com/forum/".toHttpUrl(), userAgentProvider.userAgent())
        }
        val launchIntent = awaitStartedActivityIntent()
        val challengeController = Robolectric.buildActivity(ChallengeActivity::class.java, launchIntent).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val challengeHandle = awaitWithMainLooper(acquireDeferred)
        val challengeUa = requireNotNull(challengeHandle.activityBinding).webView.settings.userAgentString
        challengeHandle.close()
        shadowOf(Looper.getMainLooper()).idle()
        challengeController.destroy()

        // 2. 登录 WebView（SampleLoginWebView）
        val loginWebView = WebView(application)
        configureSampleLoginWebView(
            webView = loginWebView,
            userAgentProvider = userAgentProvider,
            onPageNavigationChanged = {},
        )
        val loginUa = loginWebView.settings.userAgentString

        // 3. OkHttp 请求（BrowserHeaderInterceptor via HttpClientModule）
        val server = MockWebServer()
        server.start()
        val okHttpClient = HttpClientModule.provideOkHttpClient(
            siteCookieStore = InMemorySiteCookieStore(),
            userAgentProvider = userAgentProvider,
            transportInterceptor = TransportInterceptor(
                delegateFactory = { null },
                isMainThread = { false },
            ),
        )
        val recordedRequest = try {
            server.enqueue(MockResponse.Builder().code(200).body("ok").build())
            okHttpClient.newCall(Request.Builder().url(server.url("/forum/forumdisplay.php?fid=2")).build())
                .execute()
                .use { response -> assertEquals(200, response.code) }
            server.takeRequest()
        } finally {
            okHttpClient.dispatcher.cancelAll()
            okHttpClient.dispatcher.executorService.shutdownNow()
            okHttpClient.connectionPool.evictAll()
            server.close()
        }
        val okHttpUa = recordedRequest.headers["User-Agent"]

        // 三处 UA 完全相同，且均为桌面 Linux Chrome 身份
        assertEquals(expectedDesktopUa, challengeUa)
        assertEquals(expectedDesktopUa, loginUa)
        assertEquals(expectedDesktopUa, okHttpUa)
        assertEquals(challengeUa, loginUa)
        assertEquals(loginUa, okHttpUa)
        assertFalse(challengeUa.contains("Android", ignoreCase = true))
        assertFalse(challengeUa.contains("Mobile", ignoreCase = true))
        assertFalse(challengeUa.contains("; wv", ignoreCase = true))
        assertFalse(challengeUa.contains("Version/4.0", ignoreCase = true))

        // BrowserHeaderInterceptor 不发送 Sec-CH-UA-*，也不额外新增
        assertNull(recordedRequest.headers["Sec-CH-UA"])
        assertNull(recordedRequest.headers["Sec-CH-UA-Mobile"])
        assertNull(recordedRequest.headers["Sec-CH-UA-Platform"])
    }

    @Test
    fun applyUnifiedDesktopUserAgent_whenMetadataUnavailable_setsUaOnlyAndDoesNotCrash() {
        val desktopUa =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        val fakeSettings = FakeWebSettings()
        val fakeBridge = RecordingMetadataBridge(metadataSupported = false)

        applyUnifiedDesktopUserAgent(
            settings = fakeSettings,
            userAgent = desktopUa,
            metadataBridge = fakeBridge,
        )

        assertEquals(desktopUa, fakeSettings.userAgentString)
        assertEquals(0, fakeBridge.setCalls)
        assertNull(fakeSettings.recordedMetadata)

        // 同时验证真实入口（AndroidWebViewUserAgentMetadataBridge）在无 WebView 边界实现时不崩
        val realSettings = FakeWebSettings()
        applyUnifiedDesktopUserAgent(realSettings, desktopUa)
        assertEquals(desktopUa, realSettings.userAgentString)
    }

    @Test
    fun applyUnifiedDesktopUserAgent_whenMetadataAvailable_setsLinuxNonMobileAndMatchingBrandVersionsOnFakeWebSettings() {
        val desktopUa =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
        val fakeSettings = FakeWebSettings()
        val fakeBridge = RecordingMetadataBridge(metadataSupported = true)

        applyUnifiedDesktopUserAgent(
            settings = fakeSettings,
            userAgent = desktopUa,
            metadataBridge = fakeBridge,
        )

        assertEquals(desktopUa, fakeSettings.userAgentString)
        assertEquals(1, fakeBridge.setCalls)
        assertSame(fakeSettings, fakeBridge.lastSettings)

        val metadata = requireNotNull(fakeSettings.recordedMetadata)
        assertEquals("Linux", metadata.platform)
        assertFalse("Desktop metadata must set mobile=false", metadata.isMobile)
        assertEquals("", metadata.platformVersion)
        assertEquals("x86", metadata.architecture)
        assertEquals("", metadata.model)
        assertEquals(64, metadata.bitness)
        assertFalse(metadata.isWow64)
        assertEquals("136.0.0.0", metadata.fullVersion)

        val brandsByName = metadata.brandVersionList.associateBy { it.brand }
        val chromium = requireNotNull(brandsByName["Chromium"])
        assertEquals("136", chromium.majorVersion)
        assertEquals("136.0.0.0", chromium.fullVersion)

        val chrome = requireNotNull(brandsByName["Google Chrome"])
        assertEquals("136", chrome.majorVersion)
        assertEquals("136.0.0.0", chrome.fullVersion)

        val grease = requireNotNull(brandsByName["Not_A Brand"])
        assertEquals("24", grease.majorVersion)
        assertEquals("24.0.0.0", grease.fullVersion)

        // 验证另一 Chrome 主版本也会动态同步，而非写死
        val anotherSettings = FakeWebSettings()
        val anotherUa =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        applyUnifiedDesktopUserAgent(anotherSettings, anotherUa, fakeBridge)
        val anotherMetadata = requireNotNull(anotherSettings.recordedMetadata)
        assertEquals("141.0.0.0", anotherMetadata.fullVersion)
        assertEquals("141", anotherMetadata.brandVersionList.first { it.brand == "Google Chrome" }.majorVersion)
    }

    @Test
    fun applyUnifiedDesktopUserAgent_handlesBlankUaAndMetadataExceptionsWithoutCrashing() {
        val throwingSetBridge = RecordingMetadataBridge(
            metadataSupported = true,
            throwOnSet = UnsupportedOperationException("WebView boundary rejected metadata"),
        )
        val settingsAfterSetFailure = FakeWebSettings()
        applyUnifiedDesktopUserAgent(settingsAfterSetFailure, "", throwingSetBridge)
        assertEquals(DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA, settingsAfterSetFailure.userAgentString)
        assertEquals(1, throwingSetBridge.setCalls)

        val throwingFeatureBridge = object : WebViewUserAgentMetadataBridge {
            override fun isMetadataSupported(): Boolean = throw IllegalStateException("WebViewFactory unavailable")
            override fun isFormFactorsSupported(): Boolean = false
            override fun setUserAgentMetadata(settings: WebSettings, metadata: UserAgentMetadata) {
                fail("setUserAgentMetadata must not be called when feature check throws")
            }
        }
        val settingsAfterFeatureFailure = FakeWebSettings()
        val desktopUa =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
        applyUnifiedDesktopUserAgent(settingsAfterFeatureFailure, desktopUa, throwingFeatureBridge)
        assertEquals(desktopUa, settingsAfterFeatureFailure.userAgentString)
    }

    @Test
    fun sourceScan_mainAndDebugContainZeroMobileSafariLiterals_andWebViewsUseSharedEntry() {
        val moduleDir = resolveHipdaModuleDir()
        val sourceRoots = listOf(
            File(moduleDir, "src/main"),
            File(moduleDir, "src/debug"),
        )
        sourceRoots.forEach { dir ->
            assertTrue("目录必须存在：${dir.absolutePath}", dir.isDirectory)
        }

        val sourceFiles = sourceRoots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .sortedBy { it.path }
                .toList()
        }
        assertTrue("应扫描到源码文件", sourceFiles.isNotEmpty())

        val violations = sourceFiles.flatMap { file ->
            val relPath = file.relativeTo(moduleDir).invariantSeparatorsPath
            findMobileSafariOccurrences(relPath, file.readText(Charsets.UTF_8))
        }
        if (violations.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("src/main 与 src/debug 严禁出现 Mobile Safari 字面量（共 ${violations.size} 处）：")
                    violations.forEach { appendLine("  - $it") }
                },
            )
        }

        // 自证：把真实 UserAgentProvider.kt 内存改坏 1 行加入 Mobile Safari，扫描测试立即变红
        val uaProviderFile = File(moduleDir, "src/main/java/net/jejer/hipda/data/network/UserAgentProvider.kt")
        val mutatedUaProvider = uaProviderFile.readText(Charsets.UTF_8)
            .replace("Safari/537.36", "Mobile Safari/537.36")
        assertTrue(
            "改坏一行加入 Mobile Safari 时扫描必须检出违规",
            findMobileSafariOccurrences("UserAgentProvider.kt", mutatedUaProvider).isNotEmpty(),
        )

        // 确认挑战页 WebView 与登录 WebView 均走同一个设置入口，不直接裸写 userAgentString =
        val webViewCallers = listOf(
            File(moduleDir, "src/main/java/net/jejer/hipda/ui/challenge/ChallengeActivity.kt"),
            File(moduleDir, "src/debug/java/net/jejer/hipda/debug/sampleexport/SampleLoginWebView.kt"),
        )
        for (caller in webViewCallers) {
            val content = caller.readText(Charsets.UTF_8)
            assertTrue("${caller.name} 必须调用统一入口 applyUnifiedDesktopUserAgent", content.contains("applyUnifiedDesktopUserAgent("))
            assertFalse(
                "${caller.name} 不得绕过统一入口直接赋值 userAgentString",
                Regex("""\buserAgentString\s*=""").containsMatchIn(content),
            )
        }
    }

    private suspend fun awaitStartedActivityIntent(): Intent {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            val next = shadowOf(application).nextStartedActivity
            if (next != null) return next
            delay(10)
        }
        error("ChallengeActivity launch intent was not dispatched")
    }

    private suspend fun <T> awaitWithMainLooper(deferred: Deferred<T>): T {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (deferred.isCompleted) return deferred.await()
            delay(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return deferred.await()
    }

    private class FakeWebSettings : RoboWebSettings() {
        var recordedMetadata: UserAgentMetadata? = null
    }

    private class RecordingMetadataBridge(
        private val metadataSupported: Boolean,
        private val formFactorsSupported: Boolean = false,
        private val throwOnSet: Throwable? = null,
    ) : WebViewUserAgentMetadataBridge {
        var setCalls: Int = 0
        var lastSettings: WebSettings? = null

        override fun isMetadataSupported(): Boolean = metadataSupported

        override fun isFormFactorsSupported(): Boolean = formFactorsSupported

        override fun setUserAgentMetadata(settings: WebSettings, metadata: UserAgentMetadata) {
            setCalls++
            lastSettings = settings
            throwOnSet?.let { throw it }
            if (settings is FakeWebSettings) {
                settings.recordedMetadata = metadata
            }
        }
    }

    companion object {
        private fun resolveHipdaModuleDir(): File {
            val candidates = listOf(File("."), File("hipda"))
            return candidates.firstOrNull { dir ->
                File(dir, "src/main").isDirectory && File(dir, "src/debug").isDirectory
            } ?: error("无法定位 hipda 模块目录（当前目录：${File(".").absolutePath}）")
        }

        private fun findMobileSafariOccurrences(relativePath: String, content: String): List<String> =
            content.lineSequence()
                .mapIndexedNotNull { index, line ->
                    if (line.contains("Mobile Safari")) "$relativePath:${index + 1}: ${line.trim()}" else null
                }
                .toList()
    }
}
