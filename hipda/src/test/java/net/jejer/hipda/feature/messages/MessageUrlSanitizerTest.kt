package net.jejer.hipda.feature.messages

import android.app.Application
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import java.util.concurrent.CopyOnWriteArrayList
import net.jejer.hipda.emoji.EmojiMap
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 短消息链接/图片 scheme 白名单安全反例测试与 `:emoji` C1b 表情渲染测试（100% 离线）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h2400dp-xhdpi")
class MessageUrlSanitizerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sanitizeMessageLinkUrl_allowsOnlyHttpHttpsAndValidatedMailto() {
        assertEquals("http://example.com/a", sanitizeMessageLinkUrl("http://example.com/a"))
        assertEquals("https://www.4d4y.com/forum/viewthread.php?tid=2602293", sanitizeMessageLinkUrl("https://www.4d4y.com/forum/viewthread.php?tid=2602293"))
        assertEquals("http://example.com/path", sanitizeMessageLinkUrl("HTTP://EXAMPLE.COM/path"))
        assertEquals("https://example.com/q?a=1", sanitizeMessageLinkUrl("HTTPS://example.com/q?a=1"))
        assertEquals("mailto:qa@example.com", sanitizeMessageLinkUrl("mailto:qa@example.com"))
        assertEquals("mailto:qa@example.com", sanitizeMessageLinkUrl("MAILTO:qa@example.com"))

        for (raw in ALL_DANGEROUS_LINK_URLS) {
            assertNull("危险或非法链接 [$raw] 必须被白名单拦截", sanitizeMessageLinkUrl(raw))
        }
    }

    @Test
    fun sanitizeMessageImageUrl_allowsOnlyHttpHttpsAndRejectsMailtoAndAllDangerousSchemes() {
        assertEquals("http://img.example.com/a.jpg", sanitizeMessageImageUrl("http://img.example.com/a.jpg"))
        assertEquals("https://img.example.com/b.png", sanitizeMessageImageUrl("HTTPS://IMG.EXAMPLE.COM/b.png"))

        // 图片白名单严格只放行 http / https，mailto 也必须拒绝
        val imageRejected = ALL_DANGEROUS_LINK_URLS + listOf(
            "mailto:qa@example.com",
            "MAILTO:qa@example.com",
        )
        for (raw in imageRejected) {
            assertNull("危险或非法图片 URL [$raw] 必须被拦截", sanitizeMessageImageUrl(raw))
        }
    }

    @Test
    fun dangerousLinksAndImages_renderInertWithZeroUriHandlerAndZeroImageLoaderCalls() {
        val dangerousLinks = listOf(
            "危险-js-小写" to "javascript:alert(1)",
            "危险-js-分号" to "javascript:;",
            "危险-js-大写" to "JAVASCRIPT:history.back()",
            "危险-js-前导空格" to " JavaScript:alert(1)",
            "危险-js-控制字符" to "\u0000javascript:alert(1)",
            "危险-js-换行绕过" to "java\nscript:alert(1)",
            "危险-js-零宽绕过" to "\u200Bjavascript:alert(1)",
            "危险-intent-小写" to "intent://scan/#Intent;scheme=zxing;end",
            "危险-intent-大写" to "INTENT://evil/#Intent;end",
            "危险-intent-前导空白" to " intent://evil/#Intent;end",
            "危险-file-小写" to "file:///data/user/0/net.jejer.hipda.next/files/secret",
            "危险-file-大写" to "FILE:///etc/hosts",
            "危险-file-控制字符" to "\tfile:///etc/passwd",
            "危险-content-小写" to "content://media/external/images/media/1",
            "危险-content-大写" to "CONTENT://settings/system",
            "危险-content-空白" to " content://a/b",
            "危险-data-小写" to "data:text/html,<script>alert(1)</script>",
            "危险-data-大写" to "DATA:text/plain;base64,QQ==",
            "危险-data-空白" to " Data:text/html,hi",
            "危险-非法mailto" to "mailto:bad@example.com?bcc=evil@x.com",
            "危险-相对路径" to "viewthread.php?tid=123",
        )
        val dangerousImages = listOf(
            "javascript:alert(1)",
            "JAVASCRIPT:void(0)",
            "intent://evil/#Intent;end",
            "file:///etc/hosts",
            "FILE:///data/secret.png",
            "content://media/external/images/media/1",
            "data:image/svg+xml,<svg/>",
            "mailto:qa@example.com",
            " https://img.example.invalid/leading-space.png",
            "https://img.example.invalid/\u0007ctrl.png",
        )

        val html = buildString {
            for ((label, href) in dangerousLinks) {
                append("<a href=\"").append(href).append("\">").append(label).append("</a><br>")
            }
            for (src in dangerousImages) {
                append("<img src=\"").append(src).append("\" />")
            }
            append("<script>alert('xss')</script>")
        }

        // 1. 解析层断言：无任何 Link / Image 段，script 已剥离
        val parsed = parseSmsBubbleContent(html)
        assertTrue("危险图片不得进入 Image 段", parsed.segments.none { it is SmsBubbleSegment.Image })
        val textParts = parsed.segments.filterIsInstance<SmsBubbleSegment.Text>().flatMap { it.parts }
        assertTrue("危险链接不得进入 Link 段", textParts.none { it is SmsTextPart.Link })
        val built = buildSmsAnnotatedText(textParts, Color.Blue, darkTheme = false, onLinkClick = {})
        assertTrue(
            "危险链接不得生成 LinkAnnotation",
            built.annotatedString.getLinkAnnotations(0, built.annotatedString.length).isEmpty(),
        )
        assertFalse("script 内容不得渲染", built.annotatedString.text.contains("xss"))

        // 2. 点击层与图片加载层断言：UriHandler 0 次调用，ImageLoader 0 次调用
        val openedUris = mutableListOf<String>()
        val recordingUriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUris += uri
            }
        }
        val requestedImages = CopyOnWriteArrayList<String>()
        val recordingLoader = createRecordingImageLoader(requestedImages)

        try {
            composeRule.setContent {
                CompositionLocalProvider(
                    LocalUriHandler provides recordingUriHandler,
                    LocalHiImageLoader provides recordingLoader,
                ) {
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        Box(Modifier.fillMaxWidth()) {
                            SmsBubbleBody(
                                contentHtml = html,
                                textColor = Color.Black,
                                linkColor = Color.Blue,
                                onLinkClick = { raw -> sanitizeMessageLinkUrl(raw)?.let(recordingUriHandler::openUri) },
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            // 点击含危险文案的文本节点，UriHandler 与 ImageLoader 必须恒为 0
            composeRule.onNodeWithText("危险-js-小写", substring = true)
                .assertIsDisplayed()
                .performTouchInput { click(Offset(12f, 12f)) }
            composeRule.waitForIdle()

            assertEquals("危险链接绝不能触发 UriHandler", 0, openedUris.size)
            assertEquals("危险图片绝不能触发 ImageLoader", 0, requestedImages.size)
        } finally {
            recordingLoader.shutdown()
        }
    }

    @Test
    fun validLinksSafeImagesAndC1bEmojis_renderAndInvokeHandlersProperlyInLightAndDark() {
        val openedUris = mutableListOf<String>()
        val requestedImages = CopyOnWriteArrayList<String>()
        val recordingLoader = createRecordingImageLoader(requestedImages)

        val html = """
            你好&amp;#128512; :) {:2_41:} <img src="images/smilies/grapeman/08.gif" />
            <a href="https://www.4d4y.com/forum/viewthread.php?tid=2602293">看帖链接</a>
            <a href="mailto:support@example.com">联系邮箱</a>
            <img src="https://img.example.invalid/safe_photo.jpg" />
        """.trimIndent()

        // 验证 C1b 表情在深浅色下映射一致（酷猴/呆呆深浅色不同资源）
        val parsed = parseSmsBubbleContent(html)
        val textParts = parsed.segments.filterIsInstance<SmsBubbleSegment.Text>().flatMap { it.parts }
        val lightBuilt = buildSmsAnnotatedText(textParts, Color.Blue, darkTheme = false, onLinkClick = {})
        val darkBuilt = buildSmsAnnotatedText(textParts, Color.Cyan, darkTheme = true, onLinkClick = {})
        assertTrue("补充平面 Emoji 应二次解码为 😀", lightBuilt.annotatedString.text.contains("你好😀"))
        assertEquals(EmojiMap.drawableFor(":)", dark = false), lightBuilt.inlineDrawables["sms_emoji::)"]?.first)
        assertEquals(EmojiMap.drawableFor("{:2_41:}", dark = false), lightBuilt.inlineDrawables["sms_emoji:{:2_41:}"]?.first)
        assertEquals(EmojiMap.drawableFor("{:2_41:}", dark = true), darkBuilt.inlineDrawables["sms_emoji:{:2_41:}"]?.first)
        assertNotEquals(
            "酷猴表情浅深色资源应区分",
            lightBuilt.inlineDrawables["sms_emoji:{:2_41:}"]?.first,
            darkBuilt.inlineDrawables["sms_emoji:{:2_41:}"]?.first,
        )
        assertEquals(EmojiMap.drawableForImageSrc("grapeman_08", dark = true), darkBuilt.inlineDrawables["sms_emoji:grapeman_08"]?.first)

        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalHiImageLoader provides recordingLoader) {
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        Box(Modifier.fillMaxWidth()) {
                            SmsBubbleBody(
                                contentHtml = """<a href="https://example.com/ok">合法外链</a> <img src="images/smilies/default/lol.gif" /> <img src="https://img.example.invalid/safe_photo.jpg" />""",
                                textColor = Color.Black,
                                linkColor = Color.Blue,
                                onLinkClick = { openedUris += it },
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithContentDescription(":lol:").assertIsDisplayed()
            composeRule.onNodeWithText("合法外链", substring = true)
                .assertIsDisplayed()
                .performTouchInput { click(Offset(10f, centerY)) }
            composeRule.waitForIdle()

            assertEquals(listOf("https://example.com/ok"), openedUris)
            assertEquals(listOf("https://img.example.invalid/safe_photo.jpg"), requestedImages.toList())
        } finally {
            recordingLoader.shutdown()
        }
    }

    private fun createRecordingImageLoader(requested: CopyOnWriteArrayList<String>): ImageLoader =
        ImageLoader.Builder(ApplicationProvider.getApplicationContext())
            .components {
                add(
                    Interceptor { chain ->
                        requested += chain.request.data.toString()
                        val result: ImageResult = SuccessResult(
                            image = ColorImage(color = AndroidColor.LTGRAY, width = 120, height = 90),
                            request = chain.request,
                        )
                        result
                    },
                )
            }
            .build()

    private companion object {
        val ALL_DANGEROUS_LINK_URLS: List<String?> = listOf(
            null,
            "",
            "   ",
            "javascript:alert(1)",
            "javascript:;",
            "JAVASCRIPT:history.back()",
            " JavaScript:alert(1)",
            "\tjavascript:void(0)",
            "\u0000javascript:alert(1)",
            "java\nscript:alert(1)",
            "\u200Bjavascript:alert(1)",
            "\u00A0javascript:alert(1)",
            "\uFEFFjavascript:alert(1)",
            "intent://scan/#Intent;scheme=zxing;end",
            "INTENT://evil/#Intent;end",
            " intent://x",
            "\rintent://x",
            "file:///data/user/0/net.jejer.hipda.next/files/secret",
            "FILE:///etc/hosts",
            " file:///etc/passwd",
            "\nfile:///etc/passwd",
            "content://media/external/images/media/1",
            "CONTENT://settings/system",
            " content://a/b",
            "\u0007content://a/b",
            "data:text/html,<script>alert(1)</script>",
            "DATA:text/plain;base64,QQ==",
            " data:text/html,hi",
            "\u001Fdata:text/html,hi",
            "viewthread.php?tid=2602293",
            "#top",
            "//example.com/path",
            "example.com",
            "mailto:",
            "mailto:not-an-email",
            "mailto:a@b",
            "mailto:a@b.com?bcc=evil@x.com",
            "mailto:a..b@example.com",
            "mailto:.a@example.com",
            "mailto:a.@example.com",
            "mailto:javascript:alert(1)",
            "mailto:foo bar@example.com",
            " mailto:qa@example.com",
            "mailto:qa@example.com\r\n",
            " http://example.com/leading-space",
            "https://example.com/trailing-space ",
            "https://example.com/with space",
            "https://example.com/\nnewline",
            "tel:10086",
            "sms:10086",
            "ftp://example.com/file",
            "vbscript:msgbox(1)",
            "blob:https://example.com/id",
            "about:blank",
            "http://",
            "https://",
        )
    }
}
