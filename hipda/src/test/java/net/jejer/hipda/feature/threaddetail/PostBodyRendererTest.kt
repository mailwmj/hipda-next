package net.jejer.hipda.feature.threaddetail

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import net.jejer.hipda.emoji.EmojiMap
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.threaddetail.body.PostBodyRenderer
import net.jejer.hipda.feature.threaddetail.body.ResolvedForumEmoji
import net.jejer.hipda.feature.threaddetail.body.buildPostTextBlock
import net.jejer.hipda.feature.threaddetail.body.resolveForumEmoji
import net.jejer.hipda.feature.threaddetail.body.resolveSmallImageDrawable
import net.jejer.hipda.feature.threaddetail.body.sanitizePostUrl
import net.jejer.hipda.feature.threaddetail.media.ImageAspectRatioCache
import net.jejer.hipda.feature.threaddetail.media.OfflineTestImageLoader
import net.jejer.hipda.feature.threaddetail.media.captureTestNetworkState
import net.jejer.hipda.feature.threaddetail.media.configureTestNetworkState
import net.jejer.hipda.feature.threaddetail.media.restoreTestNetworkState
import net.jejer.hipda.parser.LegacyTestSupport
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.TextFormat
import net.jejer.hipda.parser.threaddetail.ThreadDetailParser
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.wcagContrastRatio
import net.jejer.hipda.ui.designsystem.hiColorSchemeFromSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose 原生正文渲染、`C1b#2` 表情映射与 `B1` 链接 scheme 白名单安全测试（100% 离线）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h2400dp-xhdpi")
class PostBodyRendererTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun realSamplesP1AndP2_emojiKeysHitEmojiMapInLightAndDarkAndDecodeSupplementaryEmoji() {
        val p1 = ThreadDetailParser.parse(
            html = LegacyTestSupport.loadSample("thread_detail/thread_p1.html"),
            tid = "2602293",
            forumBaseUrl = "https://www.4d4y.com/forum/",
        )
        val p2 = ThreadDetailParser.parse(
            html = LegacyTestSupport.loadSample("thread_detail/thread_p2.html"),
            tid = "2602293",
            forumBaseUrl = "https://www.4d4y.com/forum/",
        )
        val lightScheme = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = false)
        val darkScheme = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = true)

        val p1EmojiSpans = p1.floors
            .flatMap { it.blocks }
            .filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
            .filterIsInstance<InlineSpan.Emoji>()
        val p1Keys = p1EmojiSpans.map { it.normalizedKey }.toSet()
        assertEquals(setOf("default_lol", "coolmonkey_01", "grapeman_08", "grapeman_18"), p1Keys)

        for (key in p1Keys) {
            val lightRes = resolveForumEmoji(key, darkTheme = false) as ResolvedForumEmoji.Drawable
            val darkRes = resolveForumEmoji(key, darkTheme = true) as ResolvedForumEmoji.Drawable
            assertEquals(EmojiMap.drawableForImageSrc(key, dark = false), lightRes.drawableResId)
            assertEquals(EmojiMap.drawableForImageSrc(key, dark = true), darkRes.drawableResId)
            assertEquals(EmojiMap.codeForImageSrc(key), lightRes.code)
            if (key.startsWith("coolmonkey_") || key.startsWith("grapeman_")) {
                assertNotEquals("$key 浅深色应使用不同资源", lightRes.drawableResId, darkRes.drawableResId)
            }
        }

        val lightBuiltP1 = p1.floors.flatMap { it.blocks }.filterIsInstance<ContentBlock.Text>().map { block ->
            buildPostTextBlock(
                spans = block.spans,
                baseFontSize = 16.sp,
                backgroundColor = lightScheme.background,
                defaultTextColor = lightScheme.onSurface,
                linkColor = lightScheme.primary,
                fallbackMutedColor = lightScheme.onSurfaceVariant,
                darkTheme = false,
            )
        }
        val collectedInlineIds = lightBuiltP1.flatMap { it.inlineSpecs.keys }.toSet()
        for (key in p1Keys) {
            assertTrue("真机样本表情 $key 应生成 InlineTextContent 映射", "emoji:$key" in collectedInlineIds)
        }

        val floor51Built = p2.floors.first().blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { block ->
            buildPostTextBlock(
                spans = block.spans,
                baseFontSize = 16.sp,
                backgroundColor = darkScheme.background,
                defaultTextColor = darkScheme.onSurface,
                linkColor = darkScheme.primary,
                fallbackMutedColor = darkScheme.onSurfaceVariant,
                darkTheme = true,
            ).annotatedString.text
        }
        assertTrue("补充平面 Emoji 应完整保留，实际：$floor51Built", floor51Built.contains("好的\uD83D\uDC4C"))
        assertFalse("不应残留未解码数字实体", floor51Built.contains("&#128076;"))

        val p2Emoji = resolveForumEmoji("coolmonkey_16", darkTheme = true) as ResolvedForumEmoji.Drawable
        assertEquals(EmojiMap.drawableForImageSrc("coolmonkey_16", dark = true), p2Emoji.drawableResId)
        assertEquals("{:2_56:}", p2Emoji.code)

        val unknown = resolveForumEmoji("custom_missing_99", darkTheme = false)
        assertEquals(ResolvedForumEmoji.FallbackText("[custom_missing_99]"), unknown)
        assertEquals(ResolvedForumEmoji.FallbackText("[表情]"), resolveForumEmoji("  ", darkTheme = true))
        assertNotNull(resolveSmallImageDrawable(ThreadDetailParser.SMALL_IMAGE_WIN_KEY))
    }

    @Test
    fun buildPostTextBlock_appliesInlineStylesLinksEmailsColorsAndUnknownEmojiFallback() {
        val oledBg = Color.Black
        val spans = listOf(
            InlineSpan.Plain("粗斜划", TextFormat(bold = true, italic = true, underline = true, strikethrough = true)),
            InlineSpan.Plain("小字", TextFormat(smallFont = true)),
            InlineSpan.Plain("黑底黑字", TextFormat(rawColor = "#000000")),
            InlineSpan.Plain("黑底深蓝", TextFormat(rawColor = "#000080")),
            InlineSpan.LineBreak,
            InlineSpan.Link("外链", "https://example.com/post", isSmallFont = false),
            InlineSpan.Plain(" "),
            InlineSpan.Link("站内帖", "https://www.4d4y.com/forum/viewthread.php?tid=2602293", isSmallFont = true),
            InlineSpan.Plain(" "),
            InlineSpan.Email("qa@example.com"),
            InlineSpan.Emoji("default_smile", "images/smilies/default/smile.gif"),
            InlineSpan.Emoji("unknown_key_77", "images/smilies/unknown/77.gif"),
            InlineSpan.SmallImage(ThreadDetailParser.SMALL_IMAGE_WIN_KEY, "attachments/win.png"),
        )
        val built = buildPostTextBlock(
            spans = spans,
            baseFontSize = 16.sp,
            backgroundColor = oledBg,
            defaultTextColor = Color.White,
            linkColor = Color(0xFF7AD0FF),
            fallbackMutedColor = Color.Gray,
            darkTheme = true,
            onLinkClick = {},
        )

        val text = built.annotatedString.text
        assertTrue(text.contains("粗斜划"))
        assertTrue(text.contains("黑底黑字"))
        assertTrue(text.contains("外链"))
        assertTrue(text.contains("站内帖"))
        assertTrue(text.contains("qa@example.com"))
        assertTrue("未知表情应回退文本占位", text.contains("[unknown_key_77]"))
        assertTrue("emoji:default_smile" in built.inlineSpecs)
        assertTrue(built.inlineSpecs.keys.any { it.startsWith("small_img:") })

        val boldStyle = built.annotatedString.spanStyles.first { it.item.fontWeight == FontWeight.Bold }.item
        assertEquals(FontStyle.Italic, boldStyle.fontStyle)
        assertEquals(
            TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)),
            boldStyle.textDecoration,
        )

        val smallStyle = built.annotatedString.spanStyles.first { it.item.fontSize != 16.sp && it.item.fontSize.value > 0f }.item
        assertTrue("小字号应小于正文字号", smallStyle.fontSize.value < 16f)

        val blackOnOledStart = text.indexOf("黑底黑字")
        val blackAdjusted = built.annotatedString.spanStyles.first { it.start == blackOnOledStart }.item.color
        assertTrue("OLED 黑底黑字对比度必须 ≥ 4.5", wcagContrastRatio(blackAdjusted, oledBg) >= 4.5f)

        val navyOnOledStart = text.indexOf("黑底深蓝")
        val navyAdjusted = built.annotatedString.spanStyles.first { it.start == navyOnOledStart }.item.color
        assertTrue("OLED 黑底深蓝对比度必须 ≥ 4.5", wcagContrastRatio(navyAdjusted, oledBg) >= 4.5f)

        val links = built.annotatedString.getLinkAnnotations(0, text.length)
            .map { (it.item as LinkAnnotation.Url).url }
        assertEquals(
            listOf(
                "https://example.com/post",
                "https://www.4d4y.com/forum/viewthread.php?tid=2602293",
                "mailto:qa@example.com",
            ),
            links,
        )
    }

    @Test
    fun postBodyRenderer_rendersAllSixContentBlocksAndRoutesImageToPostImageBlock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val savedNetwork = captureTestNetworkState(context)
        ImageAspectRatioCache.Default.clear()
        val offlineLoader = OfflineTestImageLoader.success(width = 400, height = 300)
        val openedImages = mutableListOf<ImageViewerLaunchArgs>()
        val openedLinks = mutableListOf<String>()
        val authorOnlySample = ThreadDetailParser.parse(
            html = LegacyTestSupport.loadSample("thread_detail/thread_author_only.html"),
            tid = "2602293",
            forumBaseUrl = "https://www.4d4y.com/forum/",
        )
        val sampleQuote = authorOnlySample.floors.flatMap { it.blocks }.filterIsInstance<ContentBlock.Quote>().first()
        val sampleGoTo = authorOnlySample.floors.flatMap { it.blocks }.filterIsInstance<ContentBlock.GoToFloor>().first()

        val baseBlocks = listOf(
            sampleQuote,
            sampleGoTo,
            ContentBlock.Text(
                listOf(
                    InlineSpan.Plain("原生正文含表情 "),
                    InlineSpan.Emoji("default_lol", "images/smilies/default/lol.gif"),
                    InlineSpan.Emoji("coolmonkey_01", "images/smilies/coolmonkey/01.gif"),
                    InlineSpan.Emoji("grapeman_08", "images/smilies/grapeman/08.gif"),
                    InlineSpan.Plain(" 未知表情 "),
                    InlineSpan.Emoji("missing_x", "images/smilies/missing/x.gif"),
                ),
            ),
            ContentBlock.Image(fullUrl = "https://img.example.invalid/full.jpg", thumbUrl = null, fileSizeBytes = 2048L),
            ContentBlock.Attachment(url = "https://www.4d4y.com/forum/attachment.php?aid=1001", title = "spec.pdf", description = "12 KB"),
            ContentBlock.Notice("提示: 作者被禁止或删除 内容自动屏蔽"),
        )
        var showMeteredImage by mutableStateOf(false)

        try {
            configureTestNetworkState(
                context,
                metered = false,
                restrictStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
            )
            composeRule.setContent {
                CompositionLocalProvider(LocalHiImageLoader provides offlineLoader) {
                    HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                        val listState = rememberLazyListState()
                        val renderedBlocks = if (showMeteredImage) {
                            baseBlocks + ContentBlock.Image(
                                fullUrl = "https://img.example.invalid/metered.jpg",
                                thumbUrl = null,
                                fileSizeBytes = 2048L,
                            )
                        } else {
                            baseBlocks
                        }
                        Box(Modifier.fillMaxWidth()) {
                            PostBodyRenderer(
                                blocks = renderedBlocks,
                                floorPostId = "post_1001",
                                listState = listState,
                                onOpenImageViewer = { openedImages += it },
                                onLinkClick = { openedLinks += it },
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithText(sampleQuote.authorAndTime).assertIsDisplayed()
            composeRule.onNodeWithText(sampleGoTo.label).assertIsDisplayed()
            composeRule.onNodeWithContentDescription(":lol:").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("{:2_41:}").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("{:3_64:}").assertIsDisplayed()
            composeRule.onNodeWithText("原生正文含表情", substring = true).assertIsDisplayed()
            composeRule.onNodeWithText("[missing_x]", substring = true).assertIsDisplayed()
            assertEquals(listOf("https://img.example.invalid/full.jpg"), offlineLoader.requestedUrls)
            composeRule.onNode(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
                .assertIsDisplayed()
                .performClick()
            composeRule.onNodeWithText("spec.pdf").assertIsDisplayed().performClick()
            composeRule.onNodeWithText("提示: 作者被禁止或删除 内容自动屏蔽").assertIsDisplayed()

            assertEquals(1, openedImages.size)
            assertEquals(listOf("https://img.example.invalid/full.jpg"), openedImages.single().urls)
            val bounds = openedImages.single().sourceBoundsInWindow
            assertNotNull("点击已加载图片必须上报非空 sourceBoundsInWindow", bounds)
            assertTrue("sourceBoundsInWindow 尺寸必须非零，实际：$bounds", bounds!!.width > 0f && bounds.height > 0f)
            assertEquals(listOf("https://www.4d4y.com/forum/attachment.php?aid=1001"), openedLinks)

            // 切至流量节省 + 计费网络，验证新增图片块按大小显示省流文案且不自动发包
            configureTestNetworkState(
                context,
                metered = true,
                restrictStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            )
            showMeteredImage = true
            composeRule.waitForIdle()
            composeRule.onNodeWithText("点击加载图片（2 KB）").assertIsDisplayed()
            assertEquals(listOf("https://img.example.invalid/full.jpg"), offlineLoader.requestedUrls)
        } finally {
            restoreTestNetworkState(context, savedNetwork)
            ImageAspectRatioCache.Default.clear()
        }
    }

    @Test
    fun sanitizePostUrl_allowsOnlyHttpHttpsAndValidatedMailto() {
        assertEquals("http://example.com/a", sanitizePostUrl("http://example.com/a"))
        assertEquals("https://www.4d4y.com/forum/viewthread.php?tid=2602293", sanitizePostUrl("https://www.4d4y.com/forum/viewthread.php?tid=2602293"))
        assertEquals("http://example.com/path", sanitizePostUrl("HTTP://EXAMPLE.COM/path"))
        assertEquals("https://example.com/q?a=1", sanitizePostUrl("HTTPS://example.com/q?a=1"))
        assertEquals("mailto:qa@example.com", sanitizePostUrl("mailto:qa@example.com"))
        assertEquals("mailto:qa@example.com", sanitizePostUrl("MAILTO:qa@example.com"))

        val rejected = listOf(
            null, "", "   ",
            "javascript:alert(1)", "javascript:;", "JAVASCRIPT:history.back()", " JavaScript:alert(1)", "\tjavascript:void(0)",
            "intent://scan/#Intent;scheme=zxing;end", "INTENT://evil/#Intent;end", " intent://x",
            "file:///data/user/0/net.jejer.hipda.next/files/secret", "FILE:///etc/hosts", " file:///etc/passwd",
            "content://media/external/images/media/1", "CONTENT://settings/system", " content://a/b",
            "data:text/html,<script>alert(1)</script>", "DATA:text/plain;base64,QQ==", " data:text/html,hi",
            "viewthread.php?tid=2602293", "#top", "//example.com/path", "example.com",
            "mailto:", "mailto:not-an-email", "mailto:a@b", "mailto:a@b.com?bcc=evil@x.com",
            "mailto:javascript:alert(1)", "mailto:foo bar@example.com", " mailto:qa@example.com",
            " http://example.com/leading-space", "https://example.com/trailing-space ",
            "tel:10086", "sms:10086", "ftp://example.com/file", "http://", "https://",
        )
        for (raw in rejected) {
            assertNull("危险或非法 URL [$raw] 必须被白名单拦截", sanitizePostUrl(raw))
        }
    }

    @Test
    fun dangerousSchemes_renderAsPlainTextWithoutLinkAnnotationAndNeverInvokeLocalUriHandler() {
        val dangerousLinks = listOf(
            "危险-js-小写" to "javascript:alert(1)",
            "危险-js-分号" to "javascript:;",
            "危险-js-大写" to "JAVASCRIPT:history.back()",
            "危险-js-前导空白" to " JavaScript:alert(1)",
            "危险-intent-小写" to "intent://scan/#Intent;scheme=zxing;end",
            "危险-intent-大写" to "INTENT://evil/#Intent;end",
            "危险-file-小写" to "file:///data/user/0/net.jejer.hipda.next/files/secret",
            "危险-file-前导空白" to " File:///etc/hosts",
            "危险-content-小写" to "content://media/external/images/media/1",
            "危险-content-大写" to "CONTENT://settings/system",
            "危险-data-小写" to "data:text/html,<script>alert(1)</script>",
            "危险-data-前导空白" to " Data:text/html,hi",
            "危险-无scheme-相对路径" to "viewthread.php?tid=2602293",
            "危险-无scheme-锚点" to "#top",
            "危险-无scheme-协议相对" to "//example.com/a",
            "危险-非法mailto" to "mailto:not-an-email?bcc=evil@x.com",
        )
        val dangerousAttachments = listOf(
            "恶意附件-js" to "javascript:alert(1)",
            "恶意附件-intent" to "intent://evil/#Intent;end",
            "恶意附件-file" to "file:///etc/hosts",
            "恶意附件-前导空白" to " JavaScript:void(0)",
        )

        // 1. 渲染层断言：每种危险 scheme 均不生成任何 LinkAnnotation，退化为纯文本
        for ((label, url) in dangerousLinks) {
            val built = buildPostTextBlock(
                spans = listOf(InlineSpan.Link(label, url)),
                baseFontSize = 16.sp,
                backgroundColor = Color.White,
                defaultTextColor = Color.Black,
                linkColor = Color.Blue,
                fallbackMutedColor = Color.Gray,
                darkTheme = false,
                onLinkClick = null,
            )
            assertEquals("危险链接 [$url] 文本应保留", label, built.annotatedString.text)
            assertTrue(
                "危险链接 [$url] 不得生成 LinkAnnotation",
                built.annotatedString.getLinkAnnotations(0, built.annotatedString.length).isEmpty(),
            )
        }
        val badEmailBuilt = buildPostTextBlock(
            spans = listOf(InlineSpan.Email("bad@email?bcc=evil@x.com")),
            baseFontSize = 16.sp,
            backgroundColor = Color.White,
            defaultTextColor = Color.Black,
            linkColor = Color.Blue,
            fallbackMutedColor = Color.Gray,
            darkTheme = false,
            onLinkClick = null,
        )
        assertTrue(
            "非法邮箱不得生成 LinkAnnotation",
            badEmailBuilt.annotatedString.getLinkAnnotations(0, badEmailBuilt.annotatedString.length).isEmpty(),
        )

        // 2. 点击层断言：生产默认 onLinkClick = null 路径下逐个点击，LocalUriHandler 调用次数恒为 0
        val openedUris = mutableListOf<String>()
        val recordingUriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUris += uri
            }
        }
        val blocks = buildList<ContentBlock> {
            for ((label, url) in dangerousLinks) {
                add(ContentBlock.Text(listOf(InlineSpan.Link(label, url))))
            }
            add(ContentBlock.Text(listOf(InlineSpan.Email("bad@email?bcc=evil@x.com"))))
            for ((title, url) in dangerousAttachments) {
                add(ContentBlock.Attachment(url = url, title = title, description = null))
            }
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides recordingUriHandler) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val listState = rememberLazyListState()
                    Box(Modifier.fillMaxWidth()) {
                        PostBodyRenderer(
                            blocks = blocks,
                            floorPostId = "post_sec_1",
                            listState = listState,
                            onOpenImageViewer = {},
                            onLinkClick = null,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        for ((label, url) in dangerousLinks) {
            composeRule.onNodeWithText(label).assertIsDisplayed().performTouchInput { click(Offset(12f, centerY)) }
            assertEquals("点击危险链接 [$url] 后 LocalUriHandler 调用次数必须为 0", 0, openedUris.size)
        }
        composeRule.onNodeWithText("bad@email?bcc=evil@x.com").assertIsDisplayed().performTouchInput { click(Offset(12f, centerY)) }
        assertEquals("点击非法邮箱后 LocalUriHandler 调用次数必须为 0", 0, openedUris.size)
        for ((title, url) in dangerousAttachments) {
            composeRule.onNodeWithText(title).assertIsDisplayed().performClick()
            assertEquals("点击危险附件 [$url] 后 LocalUriHandler 调用次数必须为 0", 0, openedUris.size)
        }
    }

    @Test
    fun validHttpHttpsAndMailtoLinks_generateLinkAnnotationsAndInvokeLocalUriHandler() {
        val openedUris = mutableListOf<String>()
        val recordingUriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUris += uri
            }
        }
        val blocks = listOf(
            ContentBlock.Text(listOf(InlineSpan.Link("合法HTTP外链", "http://example.com/http-page"))),
            ContentBlock.Text(listOf(InlineSpan.Link("合法HTTPS站内", "https://www.4d4y.com/forum/viewthread.php?tid=2602293"))),
            ContentBlock.Text(listOf(InlineSpan.Link("合法Mailto链接", "mailto:support@example.com"))),
            ContentBlock.Text(listOf(InlineSpan.Email("qa@example.com"))),
            ContentBlock.Attachment(url = "https://www.4d4y.com/forum/attachment.php?aid=2048", title = "合法附件.pdf", description = "10 KB"),
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides recordingUriHandler) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val listState = rememberLazyListState()
                    Box(Modifier.fillMaxWidth()) {
                        PostBodyRenderer(
                            blocks = blocks,
                            floorPostId = "post_sec_2",
                            listState = listState,
                            onOpenImageViewer = {},
                            onLinkClick = null,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("合法HTTP外链").assertIsDisplayed().performTouchInput { click(Offset(12f, centerY)) }
        composeRule.onNodeWithText("合法HTTPS站内").assertIsDisplayed().performTouchInput { click(Offset(12f, centerY)) }
        composeRule.onNodeWithText("合法Mailto链接").assertIsDisplayed().performTouchInput { click(Offset(12f, centerY)) }
        composeRule.onNodeWithText("qa@example.com").assertIsDisplayed().performTouchInput { click(Offset(12f, centerY)) }
        composeRule.onNodeWithText("合法附件.pdf").assertIsDisplayed().performClick()

        assertEquals(
            listOf(
                "http://example.com/http-page",
                "https://www.4d4y.com/forum/viewthread.php?tid=2602293",
                "mailto:support@example.com",
                "mailto:qa@example.com",
                "https://www.4d4y.com/forum/attachment.php?aid=2048",
            ),
            openedUris,
        )
    }
}
