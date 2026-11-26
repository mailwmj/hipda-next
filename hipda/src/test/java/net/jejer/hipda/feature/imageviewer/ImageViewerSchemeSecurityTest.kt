package net.jejer.hipda.feature.imageviewer

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import okhttp3.Call
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E14 验收项 4：全链路严格 `http`/`https` scheme 白名单反例测试。
 *
 * 覆盖 `file:`、`content:`、`android.resource:`、`javascript:`、`intent:`、`data:`、`mailto:`、
 * 大小写/空白/Unicode 空白/ISO 控制字符绕过及空 host：
 * - URL 策略单元测试；
 * - 看图 UI（含缩略图、主图、原图下载、保存、分享）发起 0 次 Coil 请求、0 次下载请求、0 次保存分享；
 * - `OkHttpOriginalImageDownloader` 与 `AndroidImageViewerMediaActions` 直接调用层同样 0 请求拒绝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ImageViewerSchemeSecurityTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val dangerousUrls = listOf(
        "file:///etc/hosts",
        "FILE:///etc/hosts",
        " file:///etc/hosts",
        "content://media/external/images/1",
        "CONTENT://media/external/images/1",
        "android.resource://net.jejer.hipda/drawable/ic_launcher",
        "ANDROID.RESOURCE://net.jejer.hipda/1",
        "javascript:alert(1)",
        "JavaScript:alert(1)",
        "javascript:alert(1)\n",
        "intent://scan/#Intent;scheme=zxing;end",
        "INTENT://scan/#Intent;end",
        "data:image/png;base64,iVBORw0KGgo=",
        "DATA:image/png;base64,AAA",
        "mailto:user@example.com",
        "MAILTO:user@example.com",
        " https://img.example.invalid/space_lead.jpg",
        "https://img.example.invalid/space_trail.jpg ",
        "\u200Bhttps://img.example.invalid/zwsp.jpg",
        "\u00A0https://img.example.invalid/nbsp.jpg",
        "\thttps://img.example.invalid/tab.jpg",
        "https://img.example.invalid/null\u0000.jpg",
        "https://img.example.invalid/crlf\r\n.jpg",
        "http://",
        "https://",
        "https:///missing_host.jpg",
        "https://:8080/empty_host.jpg",
        "//evil.example.invalid/protocol_relative.jpg",
        "relative/path/pic.jpg",
        "",
    )

    @Test
    fun sanitizeViewerImageUrl_blocksAllDangerousSchemesAndAllowsHttpHttps() {
        for (bad in dangerousUrls) {
            assertNull("危险或畸形 URL 必须被白名单拦截: '$bad'", sanitizeViewerImageUrl(bad))
            assertFalse("危险 URL 不得被判为 GIF: '$bad'", isGifUrl(bad))
        }
        assertNotNull(sanitizeViewerImageUrl("https://img.example.invalid/photo.jpg"))
        assertNotNull(sanitizeViewerImageUrl("http://img.example.invalid/photo.png"))
        assertEquals(
            "http://img.example.invalid/UPPER.jpg",
            sanitizeViewerImageUrl("HTTP://IMG.EXAMPLE.INVALID/UPPER.jpg"),
        )
    }

    @Test
    fun downloaderAndMediaActions_issueZeroHttpOrPlatformCallsForAllDangerousUrls() = runTest {
        var httpCalls = 0
        val countingFactory = Call.Factory { _: Request ->
            httpCalls++
            throw AssertionError("严禁对非法 URL 创建 HTTP Call")
        }
        val context: Context = ApplicationProvider.getApplicationContext()
        val downloader = OkHttpOriginalImageDownloader(
            callFactory = countingFactory,
            cacheDir = context.cacheDir,
        )
        val mediaActions = AndroidImageViewerMediaActions(
            context = context,
            originalDownloader = downloader,
        )

        for (bad in dangerousUrls) {
            val emissions = downloader.download(bad).toList()
            assertEquals("非法 URL 必须直接发 Failed: $bad", listOf(OriginalDownloadState.Failed), emissions)
            assertFalse("非法 URL 保存必须返回 false: $bad", mediaActions.saveImage(bad))
            assertFalse("非法 URL 分享必须返回 false: $bad", mediaActions.shareImage(bad))
        }
        assertEquals("全套危险 scheme 必须发起 0 次 HTTP Call", 0, httpCalls)
    }

    @Test
    fun overlay_blocksEveryDangerousUrlWithZeroCoilAndZeroDownloadCalls() {
        val loader = ViewerTestImageLoader.solid()
        val downloader = FakeOriginalImageDownloader()
        val mediaActions = FakeImageViewerMediaActions()
        val snackbars = RecordingSnackbarController()

        composeRule.setContent {
            CompositionLocalProvider(
                LocalHiImageLoader provides loader,
                LocalPaneSnackbarController provides snackbars,
            ) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(urls = dangerousUrls, initialIndex = 0),
                        onDismiss = {},
                        originalDownloader = downloader,
                        mediaActions = mediaActions,
                        thumbnailUrls = dangerousUrls,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals("非法 URL 必须发起 0 次 Coil 请求", 0, loader.requestCount)
        assertEquals("非法 URL 必须发起 0 次原图下载", 0, downloader.requestedUrls.size)
        composeRule.onNodeWithText("图片加载失败").assertIsDisplayed()
        composeRule.onNodeWithText("查看原图").assertIsNotEnabled()
        composeRule.onNodeWithText("保存图片").assertIsNotEnabled()
        composeRule.onNodeWithText("分享").assertIsNotEnabled()

        // 强行点击底栏按钮亦不得触发下载、保存或分享
        composeRule.onNodeWithText("查看原图").performClick()
        composeRule.onNodeWithText("保存图片").performClick()
        composeRule.onNodeWithText("分享").performClick()
        composeRule.waitForIdle()

        assertEquals(0, loader.requestCount)
        assertEquals(0, downloader.requestedUrls.size)
        assertEquals(0, mediaActions.savedUrls.size)
        assertEquals(0, mediaActions.sharedUrls.size)
    }

    @Test
    fun overlay_blocksDangerousThumbnailUrlWhileLoadingValidMainUrl() {
        val loader = ViewerTestImageLoader.solid()
        val downloader = FakeOriginalImageDownloader()
        val mediaActions = FakeImageViewerMediaActions()
        val validUrl = "https://img.example.invalid/safe_hd.jpg"

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(urls = listOf(validUrl), initialIndex = 0),
                        onDismiss = {},
                        originalDownloader = downloader,
                        mediaActions = mediaActions,
                        thumbnailUrls = listOf("android.resource://net.jejer.hipda/1"),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals("危险缩略图 URL 必须被过滤，仅加载合法主图", listOf(validUrl), loader.requestedUrls)
    }
}
