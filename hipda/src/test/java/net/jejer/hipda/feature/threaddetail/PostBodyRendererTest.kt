package net.jejer.hipda.feature.threaddetail

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import net.jejer.hipda.emoji.EmojiMap
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.threaddetail.body.PostBodyRenderer
import net.jejer.hipda.feature.threaddetail.body.ResolvedForumEmoji
import net.jejer.hipda.feature.threaddetail.body.buildPostTextBlock
import net.jejer.hipda.feature.threaddetail.body.emojiInlineId
import net.jejer.hipda.feature.threaddetail.body.resolveForumEmoji
import net.jejer.hipda.feature.threaddetail.body.resolveSmallImageDrawable
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose 原生正文渲染与 `C1b#2` 表情映射测试（消费 `B8` 仓内脱敏真机样本，100% 离线）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h1200dp-xhdpi")
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

        // 1. thread_p1 样本含 3 套表情（default_lol / coolmonkey_01 / grapeman_08 / grapeman_18）
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

        // 2. 用 buildPostTextBlock 渲染全部真机样本 Text 块，确保无异常且 inlineSpecs 全部匹配 EmojiMap
        val lightBuiltP1 = p1.floors.flatMap { it.blocks }.filterIsInstance<ContentBlock.Text>().map { block ->
            buildPostTextBlock(
                spans = block.spans,
                baseFontSize = 16.sp,
                backgroundColor = lightScheme.surface,
                defaultTextColor = lightScheme.onSurface,
                linkColor = lightScheme.primary,
                fallbackMutedColor = lightScheme.onSurfaceVariant,
                darkTheme = false,
            )
        }
        val collectedInlineIds = lightBuiltP1.flatMap { it.inlineSpecs.keys }.toSet()
        for (key in p1Keys) {
            assertTrue("真机样本表情 $key 应生成 InlineTextContent 映射", emojiInlineId(key) in collectedInlineIds)
        }

        // 3. thread_p2 第 51 楼含补充平面 Emoji "好的👌"（已由 B8 解码）且无实体残留，含 coolmonkey_16
        val floor51Built = p2.floors.first().blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { block ->
            buildPostTextBlock(
                spans = block.spans,
                baseFontSize = 16.sp,
                backgroundColor = darkScheme.surface,
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

        // 4. 未知表情键安全回退至可读占位文本，不抛异常
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
        assertTrue(emojiInlineId("default_smile") in built.inlineSpecs)
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
        val openedImages = mutableListOf<ImageViewerLaunchArgs>()
        val openedLinks = mutableListOf<String>()
        val authorOnlySample = ThreadDetailParser.parse(
            html = LegacyTestSupport.loadSample("thread_detail/thread_author_only.html"),
            tid = "2602293",
            forumBaseUrl = "https://www.4d4y.com/forum/",
        )
        val sampleQuote = authorOnlySample.floors.flatMap { it.blocks }.filterIsInstance<ContentBlock.Quote>().first()
        val sampleGoTo = authorOnlySample.floors.flatMap { it.blocks }.filterIsInstance<ContentBlock.GoToFloor>().first()

        val blocks = listOf(
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

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val listState = rememberLazyListState()
                Box(Modifier.fillMaxWidth()) {
                    PostBodyRenderer(
                        blocks = blocks,
                        floorPostId = "post_1001",
                        listState = listState,
                        onOpenImageViewer = { openedImages += it },
                        onLinkClick = { openedLinks += it },
                    )
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
        composeRule.onNodeWithText("点击加载图片").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("spec.pdf").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("提示: 作者被禁止或删除 内容自动屏蔽").assertIsDisplayed()

        assertEquals(listOf(ImageViewerLaunchArgs(urls = listOf("https://img.example.invalid/full.jpg"))), openedImages)
        assertEquals(listOf("https://www.4d4y.com/forum/attachment.php?aid=1001"), openedLinks)
    }
}
