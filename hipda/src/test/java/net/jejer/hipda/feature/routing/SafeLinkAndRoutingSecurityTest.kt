package net.jejer.hipda.feature.routing

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.feature.common.AttachmentDownloadHandler
import net.jejer.hipda.feature.common.LocalAttachmentDownloadHandler
import net.jejer.hipda.feature.common.LocalPostLinkDispatcher
import net.jejer.hipda.feature.common.PostLinkDispatcher
import net.jejer.hipda.feature.common.SafeLinkPolicy
import net.jejer.hipda.feature.messages.sanitizeMessageLinkUrl
import net.jejer.hipda.feature.profile.ProfileUrlSanitizer
import net.jejer.hipda.feature.search.sanitizeAllowedForumUrl
import net.jejer.hipda.feature.threaddetail.body.PostBodyRenderer
import net.jejer.hipda.feature.threaddetail.body.sanitizePostUrl
import net.jejer.hipda.feature.threaddetail.chrome.sanitizeExternalHttpUrl
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 全路径 Scheme 白名单与危险 Scheme 反例硬阻断测试（验收 1）：
 * 覆盖 `SafeLinkPolicy`、`NextAppDeepLinkRouter`、`PostLinkDispatcher`、`AndroidExternalLinkOpener`、
 * `OkHttpAttachmentDownloader` 与 `PostBodyRenderer`（含附件明确拒绝合法 `mailto:`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h2400dp-xhdpi")
class SafeLinkAndRoutingSecurityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun safeLinkPolicy_deepLinkRouter_postLinkDispatcher_andExternalOpener_blockAllDangerousSchemes() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val startedIntents = mutableListOf<Intent>()
        val externalOpener = AndroidExternalLinkOpener(context) { startedIntents += it }

        var navCalls = 0
        var externalFailedCalls = 0
        val dispatcher = DefaultPostLinkDispatcher(
            forumServerProvider = { AppSettings.DEFAULT_FORUM_SERVER },
            onOpenForum = { navCalls++ },
            onOpenThread = { _, _ -> navCalls++ },
            onOpenThreadLastPage = { navCalls++ },
            onOpenPost = { _, _ -> navCalls++ },
            onOpenUserProfile = { navCalls++ },
            externalLinkOpener = externalOpener,
            onExternalOpenFailed = { externalFailedCalls++ },
        )

        for (bad in ALL_DANGEROUS_URLS) {
            assertNull("SafeLinkPolicy.sanitizeLinkUrl 必须拦截 [$bad]", SafeLinkPolicy.sanitizeLinkUrl(bad))
            assertNull("SafeLinkPolicy.sanitizeHttpUrl 必须拦截 [$bad]", SafeLinkPolicy.sanitizeHttpUrl(bad))
            assertNull("parseDeepLinkUrl 必须拦截 [$bad]", parseDeepLinkUrl(bad))

            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(bad))
            assertNull("parseIncomingIntent(ACTION_VIEW data) 必须拦截 [$bad]", parseIncomingIntent(viewIntent))

            val extraIntent = Intent(Intent.ACTION_VIEW).putExtra(EXTRA_DEEP_LINK_URL, bad)
            assertNull("parseIncomingIntent(EXTRA_DEEP_LINK_URL) 必须拦截 [$bad]", parseIncomingIntent(extraIntent))

            assertFalse("DefaultPostLinkDispatcher 必须拦截 [$bad]", dispatcher.dispatch(bad))
            assertFalse("AndroidExternalLinkOpener 必须拦截 [$bad]", externalOpener.open(bad))
        }

        // DeepLink 与 http-only 档额外拒绝合法 mailto:
        for (mailto in VALID_MAILTO_URLS) {
            assertNull("SafeLinkPolicy.sanitizeHttpUrl 必须拒绝 mailto [$mailto]", SafeLinkPolicy.sanitizeHttpUrl(mailto))
            assertNull("parseDeepLinkUrl 必须拒绝 mailto [$mailto]", parseDeepLinkUrl(mailto))
            assertNull("parseIncomingIntent 必须拒绝 mailto [$mailto]", parseIncomingIntent(Intent(Intent.ACTION_VIEW, Uri.parse(mailto))))
        }

        assertEquals("危险 scheme 绝不能触发任何应用内导航", 0, navCalls)
        assertEquals("危险 scheme 绝不能触发任何 startActivity", 0, startedIntents.size)
        assertEquals("危险 scheme 拦截不应误触发外部打开失败回调", 0, externalFailedCalls)
    }

    @Test
    fun allFiveDelegatedSanitizers_blockRawUserInfoObfuscationAndDangerousSchemes() {
        for (spoofed in RAW_USER_INFO_OBFUSCATION_URLS) {
            assertNull("SafeLinkPolicy.sanitizeLinkUrl 必须拦截 user@ 混淆 [$spoofed]", SafeLinkPolicy.sanitizeLinkUrl(spoofed))
            assertNull("SafeLinkPolicy.sanitizeHttpUrl 必须拦截 user@ 混淆 [$spoofed]", SafeLinkPolicy.sanitizeHttpUrl(spoofed))
            assertNull("PostBodyRenderer.sanitizePostUrl 必须拦截 user@ 混淆 [$spoofed]", sanitizePostUrl(spoofed))
            assertNull("ForumLinkGuard 必须拦截 user@ 混淆 [$spoofed]", sanitizeAllowedForumUrl(spoofed))
            assertNull("MessageUrlSanitizer 必须拦截 user@ 混淆 [$spoofed]", sanitizeMessageLinkUrl(spoofed))
            assertNull("ProfileUrlSanitizer 必须拦截 user@ 混淆 [$spoofed]", ProfileUrlSanitizer.sanitizeProfileLinkUrl(spoofed))
            assertNull("ThreadBottomToolbar.sanitizeExternalHttpUrl 必须拦截 user@ 混淆 [$spoofed]", sanitizeExternalHttpUrl(spoofed))
        }
        // 合法 URL 在 5 处委托入口仍正常放行
        val validThreadUrl = "https://www.4d4y.com/forum/viewthread.php?tid=2602293"
        assertEquals(validThreadUrl, sanitizePostUrl(validThreadUrl))
        assertEquals(validThreadUrl, sanitizeAllowedForumUrl(validThreadUrl))
        assertEquals(validThreadUrl, sanitizeMessageLinkUrl(validThreadUrl))
        assertEquals(validThreadUrl, ProfileUrlSanitizer.sanitizeProfileLinkUrl(validThreadUrl))
        assertEquals(validThreadUrl, sanitizeExternalHttpUrl(validThreadUrl))
    }

    @Test
    fun attachmentDownloader_blocksAllDangerousSchemesAndValidMailtoWithZeroNetworkRequests() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val requestCount = AtomicInteger(0)
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                    requestCount.incrementAndGet()
                    return MockResponse(code = 200, body = "should-not-download")
                }
            }
            val saveCalls = AtomicInteger(0)
            val recordingSaver = object : AttachmentSaver {
                override suspend fun save(fileName: String, mimeType: String, writeTo: suspend (OutputStream) -> Long): SavedAttachment? {
                    saveCalls.incrementAndGet()
                    return null
                }
            }
            val downloader = OkHttpAttachmentDownloader(
                callFactory = OkHttpClient(),
                saver = recordingSaver,
                hasLegacyStoragePermission = { true },
                sdkInt = 34,
            )

            val allBlockedForAttachment = ALL_DANGEROUS_URLS + VALID_MAILTO_URLS
            for (bad in allBlockedForAttachment) {
                val res = downloader.download(bad, "evil.pdf")
                assertEquals("附件下载器必须拦截 [$bad]", AttachmentDownloadResult.InvalidUrl, res)
            }
            assertEquals("拦截的附件 URL 必须产生 0 次网络请求", 0, requestCount.get())
            assertEquals("拦截的附件 URL 必须产生 0 次磁盘保存调用", 0, saveCalls.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun postBodyRenderer_withRoutingLocals_blocksDangerousLinksAndMailtoAttachmentsWithZeroCalls() {
        val dispatchedLinks = mutableListOf<String>()
        val downloadedAttachments = mutableListOf<Pair<String, String?>>()
        val openedUris = mutableListOf<String>()

        val recordingDispatcher = PostLinkDispatcher { dispatchedLinks += it; true }
        val recordingDownloader = AttachmentDownloadHandler { url, name -> downloadedAttachments += url to name }
        val recordingUriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUris += uri
            }
        }

        val blocks = listOf(
            ContentBlock.Text(listOf(InlineSpan.Link("恶意JS链接", "javascript:alert(1)"))),
            ContentBlock.Text(listOf(InlineSpan.Link("恶意Intent链接", "INTENT://evil/#Intent;end"))),
            ContentBlock.Text(listOf(InlineSpan.Link("恶意File链接", "file:///etc/passwd"))),
            ContentBlock.Text(listOf(InlineSpan.Link("恶意Market链接", "market://details?id=evil"))),
            ContentBlock.Text(listOf(InlineSpan.Link("恶意AndroidRes链接", "android.resource://net.jejer.hipda.next/raw/a"))),
            ContentBlock.Text(listOf(InlineSpan.Link("畸形Mailto链接", "mailto:qa@example.com?bcc=evil@x.com"))),
            ContentBlock.Quote(authorAndTime = "引用作者", contentText = "危险引用 javascript:alert(2) content://media/1", contentHtml = "", targetPid = null, targetTid = null),
            ContentBlock.Attachment(url = "mailto:qa@example.com", title = "邮箱附件必须拒收.pdf", description = "mailto"),
            ContentBlock.Attachment(url = "javascript:alert(3)", title = "JS附件必须拒收.zip", description = "js"),
            ContentBlock.Attachment(url = "file:///etc/hosts", title = "File附件必须拒收.txt", description = "file"),
            ContentBlock.Attachment(url = " https://www.4d4y.com/forum/attachment.php?aid=1", title = "前导空格附件.pdf", description = "space"),
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalPostLinkDispatcher provides recordingDispatcher,
                LocalAttachmentDownloadHandler provides recordingDownloader,
                LocalUriHandler provides recordingUriHandler,
            ) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxWidth()) {
                        PostBodyRenderer(
                            blocks = blocks,
                            floorPostId = "post_sec_routing",
                            listState = rememberLazyListState(),
                            onOpenImageViewer = {},
                            onLinkClick = null,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        listOf("恶意JS链接", "恶意Intent链接", "恶意File链接", "恶意Market链接", "恶意AndroidRes链接", "畸形Mailto链接").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed().performTouchInput { click(Offset(12f, centerY)) }
        }
        listOf("邮箱附件必须拒收.pdf", "JS附件必须拒收.zip", "File附件必须拒收.txt", "前导空格附件.pdf").forEach { title ->
            composeRule.onNodeWithText(title).assertIsDisplayed().performClick()
        }

        assertEquals("危险正文链接必须 0 分发", 0, dispatchedLinks.size)
        assertEquals("危险与 mailto 附件必须 0 下载", 0, downloadedAttachments.size)
        assertEquals("任何危险输入均不得回落 UriHandler", 0, openedUris.size)
    }

    companion object {
        val VALID_MAILTO_URLS: List<String> = listOf(
            "mailto:qa@example.com",
            "MAILTO:support@example.org",
            "MailTo:user.name+tag@sub.example.com",
        )

        val RAW_USER_INFO_OBFUSCATION_URLS: List<String> = listOf(
            "https://user@www.4d4y.com/forum/viewthread.php?tid=1",
            "https://www.4d4y.com@evil.com/forum/viewthread.php?tid=1",
            "http://4d4y.com@evil.com/forum/viewthread.php?tid=1",
            "https://user:pass@www.4d4y.com/forum/viewthread.php?tid=1",
            "https://%75ser@www.4d4y.com/forum/viewthread.php?tid=1",
            "https://user:@www.4d4y.com/forum/viewthread.php?tid=1",
            "https://:pass@www.4d4y.com/forum/viewthread.php?tid=1",
            "https://:@www.4d4y.com/forum/viewthread.php?tid=1",
            "https://@www.4d4y.com/forum/viewthread.php?tid=1",
            "https://www.4d4y.com\\@evil.com/forum/viewthread.php?tid=1",
            "https://user%40www.4d4y.com/forum/viewthread.php?tid=1",
        )

        val ALL_DANGEROUS_URLS: List<String> = listOf(
            "",
            "   ",
            // javascript:
            "javascript:alert(1)",
            "javascript:;",
            "JAVASCRIPT:history.back()",
            "JaVaScRiPt:void(0)",
            " javascript:alert(1)",
            "\tjavascript:alert(1)",
            "java\nscript:alert(1)",
            "java\u0000script:alert(1)",
            "\u200Bjavascript:alert(1)",
            "\uFEFFjavascript:alert(1)",
            // intent:
            "intent://scan/#Intent;scheme=zxing;end",
            "INTENT://www.4d4y.com/forum/viewthread.php?tid=1#Intent;scheme=https;end",
            " intent://evil/#Intent;end",
            // file:
            "file:///etc/passwd",
            "FILE:///data/user/0/net.jejer.hipda.next/files/secret",
            " file:///etc/hosts",
            // content:
            "content://media/external/images/media/1",
            "CONTENT://settings/system",
            "\rcontent://a/b",
            // data:
            "data:text/html,<script>alert(1)</script>",
            "DATA:text/plain;base64,QQ==",
            " data:text/html,hi",
            // tel / vbscript / blob / about / market / android.resource / ftp / sms
            "tel:10086",
            "TEL:+8610086",
            "vbscript:msgbox(1)",
            "VBSCRIPT:alert(1)",
            "blob:https://www.4d4y.com/550e8400-e29b-41d4-a716-446655440000",
            "BLOB:https://example.com/1",
            "about:blank",
            "ABOUT:blank",
            "market://details?id=com.example.evil",
            "MARKET://search?q=test",
            "android.resource://net.jejer.hipda.next/raw/secret",
            "ANDROID.RESOURCE://net.jejer.hipda.next/drawable/ic",
            "ftp://example.com/file.zip",
            "sms:10086",
            // whitespace / control / zero-width / empty host / protocol-relative / userinfo
            " https://www.4d4y.com/forum/viewthread.php?tid=1",
            "https://www.4d4y.com/forum/viewthread.php?tid=1 ",
            "https://www.4d4y.com/forum/viewthread.php?tid=1\n",
            "https://www.4d4y.com/forum/\rviewthread.php?tid=1",
            "https://www.4d4y.com/forum/viewthread.php?tid=1\u0000",
            "https://www.4d4y.com/forum/view thread.php?tid=1",
            "\u00A0https://www.4d4y.com/forum/viewthread.php?tid=1",
            "\u200Bhttps://www.4d4y.com/forum/viewthread.php?tid=1",
            "http://",
            "https://",
            "http:///nohost",
            "https://\\nohost",
            "//www.4d4y.com/forum/viewthread.php?tid=1",
            "viewthread.php?tid=1",
            "/forum/viewthread.php?tid=1",
            "https://www.4d4y.com@evil.com/forum/viewthread.php?tid=1",
            "https://user:pass@example.com/path",
            "https://@example.com/path",
            // malformed mailto:
            "mailto:",
            "mailto:not-an-email",
            "mailto:a@b",
            "mailto:qa@example.com?subject=pwn",
            "mailto:qa@example.com?bcc=evil@x.com",
            "mailto:qa@example.com#frag",
            "mailto:qa@example.com\r\nBcc:evil@x.com",
            "mailto:a..b@example.com",
            "mailto:.a@example.com",
            "mailto:a.@example.com",
            "mailto:foo bar@example.com",
            " mailto:qa@example.com",
        ) + RAW_USER_INFO_OBFUSCATION_URLS
    }
}
