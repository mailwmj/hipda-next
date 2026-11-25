package net.jejer.hipda.parser

import android.app.Application
import android.content.Context
import net.jejer.hipda.bean.ContentAbs
import net.jejer.hipda.bean.ContentAttach
import net.jejer.hipda.bean.ContentGoToFloor
import net.jejer.hipda.bean.ContentImg
import net.jejer.hipda.bean.ContentQuote
import net.jejer.hipda.bean.ContentText
import net.jejer.hipda.bean.DetailBean
import net.jejer.hipda.bean.HiSettingsHelper
import net.jejer.hipda.cache.SignatureContainer
import net.jejer.hipda.emoji.EmojiMap
import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailParser
import net.jejer.hipda.utils.HiParserThreadDetail
import net.jejer.hipda.utils.HiUtils
import org.jsoup.Jsoup
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
 * 帖子详情旧 `HiParserThreadDetail` 样本回归与 `:core-parser` 新解析器逐字段等价测试（100% 离线）。
 *
 * 覆盖：
 * 1. 3 份真机脱敏样本（`thread_p1.html`、`thread_p2.html`、`thread_author_only.html`）全楼层逐字段等价；
 * 2. 样本与合成表情 `normalizedKey` 均在 `:emoji` `EmojiMap`（深/浅色与 code 反查）中命中；
 * 3. T6 原始 `<font color=...>` 透传（对照旧 `ColorHelper` 浅色模式过滤白字）；
 * 4. 全部合成夹具（1 楼单/多选投票 + `div.specialmsg`、锁定、警告、编辑状态、跳楼、引用隐藏节点、
 *    行内/文末图片与附件、`SmallImages`、标题回退 `div#threadtitle`、无 `#postlist`）；
 * 5. 调用前后 `doc.outerHtml()` 零变异守卫。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ThreadDetailParserEquivalenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = LegacyTestSupport.initLegacyEnvironment()
    }

    @Test
    fun realSampleThreadP1_legacyAndNewAreFieldByFieldEquivalentAndSmiliesMatchEmojiMap() {
        val html = LegacyTestSupport.loadSample("thread_detail/thread_p1.html")
        val legacyDoc = Jsoup.parse(html)
        val newDoc = Jsoup.parse(html)
        val newBeforeHtml = newDoc.outerHtml()

        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacyAuthorId = HiParserThreadDetail.getThreadAuthorId(legacyDoc)
        val legacy = HiParserThreadDetail.parse(context, legacyDoc, "2602293")
        assertNotNull(legacy)

        val newAuthorId = ThreadDetailParser.parseThreadAuthorId(newDoc)
        val newPage = ThreadDetailParser.parse(
            doc = newDoc,
            tid = "2602293",
            avatarBaseUrl = HiUtils.AvatarBaseUrl,
            forumBaseUrl = HiUtils.BaseUrl,
        )

        // 零 DOM 副作用（同时证实旧解析器确实删掉了 div#nav a 与 i.pstatus）
        assertEquals("ThreadDetailParser must not mutate Document DOM", newBeforeHtml, newDoc.outerHtml())
        assertEquals(0, legacyDoc.select("div#nav a").size)
        assertTrue(newDoc.select("div#nav a").size >= 2)

        assertTrue(newPage.hasPostList)
        assertEquals(legacyAuthorId, newAuthorId)
        assertEquals(legacy!!.tid, newPage.tid)
        assertEquals(legacy.fid, newPage.fid)
        assertEquals(legacy.title, newPage.title)
        assertEquals(legacy.page, newPage.page)
        assertEquals(legacy.lastPage, newPage.lastPage)
        assertEquals(HiSettingsHelper.getInstance().maxPostsInPage, newPage.postsPerPageEstimate)
        assertEquals(DiscuzCommonParser.parsePageMeta(newDoc), newPage.meta)
        assertEquals(legacy.count, newPage.floors.size)
        assertEquals(50, newPage.floors.size)

        for (i in 0 until legacy.count) {
            assertFloorEquivalent(legacy.all[i], newPage.floors[i], contextLabel = "thread_p1[$i]")
        }

        // S5：1 楼真机图文混排顺序（Text -> Image -> Text）与旧版一致
        val floor1Kinds = newPage.floors[0].blocks.map { it::class.simpleName }
        assertEquals(listOf("Text", "Image", "Text"), floor1Kinds)

        // S2：第 49 楼（post_53529463）旧版将 font[size=2] 内回跳 <a> 与破损 BBCode 拼进 time；新版净化为纯时间 "21:17"
        val legacyFloor49Quote = legacy.getPostInPage("53529463").contents
            .let { c -> (0 until c.size).map { c.get(it) }.filterIsInstance<ContentQuote>().single() }
        val newFloor49Quote = newPage.findFloorByPostId("53529463")!!
            .blocks.filterIsInstance<ContentBlock.Quote>().single()
        assertTrue(legacyFloor49Quote.time.contains("back.gif"))
        assertEquals("p84102 发表于 21:17", newFloor49Quote.authorAndTime)
        assertEquals("p84102", newFloor49Quote.author)
        assertEquals("21:17", newFloor49Quote.time)

        // 核验样本中全部表情 normalizedKey 均可直接命中 :emoji EmojiMap
        val smileyKeys = collectSmileyKeys(newPage.floors)
        assertEquals(setOf("default_lol", "coolmonkey_01", "grapeman_08", "grapeman_18"), smileyKeys)
        for (key in smileyKeys) {
            assertNotNull("EmojiMap light drawable missing for $key", EmojiMap.drawableForImageSrc(key, dark = false))
            assertNotNull("EmojiMap dark drawable missing for $key", EmojiMap.drawableForImageSrc(key, dark = true))
            assertNotNull("EmojiMap code missing for $key", EmojiMap.codeForImageSrc(key))
        }
    }

    @Test
    fun realSampleThreadP2_legacyAndNewAreEquivalentWithDoubleEscapedEmojiDecoded() {
        val html = LegacyTestSupport.loadSample("thread_detail/thread_p2.html")
        val legacyDoc = Jsoup.parse(html)
        val newDoc = Jsoup.parse(html)
        val newBeforeHtml = newDoc.outerHtml()

        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacyAuthorId = HiParserThreadDetail.getThreadAuthorId(legacyDoc)
        val legacy = HiParserThreadDetail.parse(context, legacyDoc, "2602293")
        assertNotNull(legacy)

        val newAuthorId = ThreadDetailParser.parseThreadAuthorId(newDoc)
        val newPage = ThreadDetailParser.parse(
            doc = newDoc,
            tid = "2602293",
            avatarBaseUrl = HiUtils.AvatarBaseUrl,
            forumBaseUrl = HiUtils.BaseUrl,
        )

        assertEquals(newBeforeHtml, newDoc.outerHtml())
        assertTrue(newPage.hasPostList)
        assertEquals(legacyAuthorId, newAuthorId)
        assertEquals(legacy!!.tid, newPage.tid)
        assertEquals(legacy.fid, newPage.fid)
        assertEquals(legacy.title, newPage.title)
        assertEquals(legacy.page, newPage.page)
        assertEquals(legacy.lastPage, newPage.lastPage)
        assertEquals(HiSettingsHelper.getInstance().maxPostsInPage, newPage.postsPerPageEstimate)
        assertEquals(50, newPage.postsPerPageEstimate)
        assertEquals(legacy.count, newPage.floors.size)

        for (i in 0 until legacy.count) {
            assertFloorEquivalent(legacy.all[i], newPage.floors[i], contextLabel = "thread_p2[$i]")
        }

        // A1 S4：51 楼二次转义 Emoji —— 旧解析器残留 &#128076;，新解析器正确解码为 👌
        val legacyFloor51Html = legacy.all[0].contents.get(0).content
        assertTrue(legacyFloor51Html.contains("&#128076;"))
        val newFloor51Text = newPage.floors[0].blocks
            .filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
            .filterIsInstance<InlineSpan.Plain>()
            .joinToString("") { it.text }
        assertTrue(newFloor51Text.contains("好的\uD83D\uDC4C"))
        assertFalse(newFloor51Text.contains("&#128076;"))

        // 第 2 页表情命中 EmojiMap
        val smileyKeys = collectSmileyKeys(newPage.floors)
        assertEquals(setOf("coolmonkey_16"), smileyKeys)
        assertNotNull(EmojiMap.drawableForImageSrc("coolmonkey_16", dark = false))
        assertNotNull(EmojiMap.drawableForImageSrc("coolmonkey_16", dark = true))
        assertEquals("{:2_56:}", EmojiMap.codeForImageSrc("coolmonkey_16"))
    }

    @Test
    fun realSampleThreadAuthorOnly_legacyAndNewAreFieldByFieldEquivalent() {
        val html = LegacyTestSupport.loadSample("thread_detail/thread_author_only.html")
        val legacyDoc = Jsoup.parse(html)
        val newDoc = Jsoup.parse(html)
        val newBeforeHtml = newDoc.outerHtml()

        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacyAuthorId = HiParserThreadDetail.getThreadAuthorId(legacyDoc)
        val legacy = HiParserThreadDetail.parse(context, legacyDoc, "2602293")
        assertNotNull(legacy)

        val newAuthorId = ThreadDetailParser.parseThreadAuthorId(newDoc)
        val newPage = ThreadDetailParser.parse(
            doc = newDoc,
            tid = "2602293",
            avatarBaseUrl = HiUtils.AvatarBaseUrl,
            forumBaseUrl = HiUtils.BaseUrl,
        )

        assertEquals(newBeforeHtml, newDoc.outerHtml())
        assertTrue(newPage.hasPostList)
        assertEquals(legacyAuthorId, newAuthorId)
        assertEquals(legacy!!.tid, newPage.tid)
        assertEquals(legacy.fid, newPage.fid)
        assertEquals(legacy.title, newPage.title)
        assertEquals(legacy.page, newPage.page)
        assertEquals(legacy.lastPage, newPage.lastPage)
        assertNull(newPage.postsPerPageEstimate)
        assertEquals(legacy.count, newPage.floors.size)
        assertEquals(15, newPage.floors.size)

        for (i in 0 until legacy.count) {
            assertFloorEquivalent(legacy.all[i], newPage.floors[i], contextLabel = "thread_author_only[$i]")
        }
    }

    @Test
    fun syntheticPollFixtures_singleMultiAndSpecialMsg_legacyAndNewAreFieldByFieldEquivalent() {
        // 1. 多选 form#poll + div.specialmsg 正文
        val multiLegacyDoc = Jsoup.parse(SYNTHETIC_MULTI_POLL_HTML)
        val multiNewDoc = Jsoup.parse(SYNTHETIC_MULTI_POLL_HTML)
        val multiBefore = multiNewDoc.outerHtml()

        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacyMulti = HiParserThreadDetail.parse(context, multiLegacyDoc, "99001")!!
        val newMulti = ThreadDetailParser.parse(
            doc = multiNewDoc,
            tid = "99001",
            avatarBaseUrl = HiUtils.AvatarBaseUrl,
            forumBaseUrl = HiUtils.BaseUrl,
        )

        assertEquals(multiBefore, multiNewDoc.outerHtml())
        assertEquals(0, multiLegacyDoc.select("form#poll").size)
        assertEquals(1, multiNewDoc.select("form#poll").size)

        val oldPoll = legacyMulti.all[0].poll!!
        val newPoll = newMulti.floors[0].poll!!
        assertEquals(oldPoll.title, newPoll.combinedTitleHtml)
        assertEquals(oldPoll.formhash, newPoll.formhash)
        assertEquals(oldPoll.maxAnswer, newPoll.maxChoices)
        assertEquals(oldPoll.footer, newPoll.footer)
        assertEquals(oldPoll.pollOptions.size, newPoll.options.size)
        for (i in oldPoll.pollOptions.indices) {
            assertEquals(oldPoll.pollOptions[i].optionId.ifEmpty { null }, newPoll.options[i].optionId)
            assertEquals(oldPoll.pollOptions[i].text, newPoll.options[i].text)
            assertEquals(oldPoll.pollOptions[i].rates.ifEmpty { null }, newPoll.options[i].ratesText)
        }
        assertTrue(newMulti.floors[0].blocks.filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
            .filterIsInstance<InlineSpan.Plain>()
            .any { it.text.contains("投票帖正文") })

        // 2. 单选 form#poll
        val singleLegacyDoc = Jsoup.parse(SYNTHETIC_SINGLE_POLL_HTML)
        val singleNewDoc = Jsoup.parse(SYNTHETIC_SINGLE_POLL_HTML)
        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacySingle = HiParserThreadDetail.parse(context, singleLegacyDoc, "99002")!!
        val newSingle = ThreadDetailParser.parse(
            doc = singleNewDoc,
            tid = "99002",
            avatarBaseUrl = HiUtils.AvatarBaseUrl,
            forumBaseUrl = HiUtils.BaseUrl,
        )
        assertEquals(legacySingle.all[0].poll!!.maxAnswer, newSingle.floors[0].poll!!.maxChoices)
        assertEquals(1, newSingle.floors[0].poll!!.maxChoices)
        assertEquals(legacySingle.all[0].poll!!.title, newSingle.floors[0].poll!!.combinedTitleHtml)
        assertEquals(legacySingle.all[0].poll!!.footer, newSingle.floors[0].poll!!.footer)
        assertEquals(legacySingle.all[0].poll!!.pollOptions.map { it.text }, newSingle.floors[0].poll!!.options.map { it.text })
    }

    @Test
    fun syntheticRichContentAndEdgeCases_legacyAndNewAreEquivalentWithT6ColorAndC1b2Emoji() {
        val legacyDoc = Jsoup.parse(SYNTHETIC_RICH_THREAD_HTML)
        val newDoc = Jsoup.parse(SYNTHETIC_RICH_THREAD_HTML)
        val newBefore = newDoc.outerHtml()

        LegacyTestSupport.suppressBackgroundNotifyFetch()
        val legacy = HiParserThreadDetail.parse(context, legacyDoc, "99100")!!
        val newPage = ThreadDetailParser.parse(
            doc = newDoc,
            tid = "99100",
            avatarBaseUrl = HiUtils.AvatarBaseUrl,
            forumBaseUrl = HiUtils.BaseUrl,
        )

        assertEquals(newBefore, newDoc.outerHtml())
        assertTrue(newPage.hasPostList)
        assertEquals(legacy.fid, newPage.fid)
        assertEquals(legacy.title, newPage.title)
        assertEquals("回退标题文本", newPage.title)
        assertEquals(legacy.count, newPage.floors.size)
        assertEquals(4, newPage.floors.size)

        for (i in 0 until legacy.count) {
            assertFloorEquivalent(legacy.all[i], newPage.floors[i], contextLabel = "synthetic[$i]")
        }

        // 1 楼核对 T6 原始颜色透传：浅色主题下旧 ColorHelper 会过滤 #ffffff，新解析器必须原样保留 rawColor="#ffffff"
        val floor1LegacyHtml = legacy.all[0].contents.content
        assertFalse("Legacy light-mode ColorHelper strips #ffffff", floor1LegacyHtml.contains("#ffffff"))
        val floor1Spans = newPage.floors[0].blocks
            .filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
        val whiteSpan = floor1Spans.filterIsInstance<InlineSpan.Plain>().firstOrNull { it.text.contains("白色文字") }
        assertNotNull(whiteSpan)
        assertEquals("#ffffff", whiteSpan!!.format.rawColor)

        // 核对 C1b#2 合成表情键命中 :emoji EmojiMap
        val emojiKeys = floor1Spans.filterIsInstance<InlineSpan.Emoji>().map { it.normalizedKey }
        assertEquals(listOf("default_smile", "coolmonkey_01", "grapeman_08"), emojiKeys)
        for (key in emojiKeys) {
            assertNotNull(EmojiMap.drawableForImageSrc(key, dark = false))
            assertNotNull(EmojiMap.drawableForImageSrc(key, dark = true))
        }

        // 核对 SmallImages 内置小图标记与小字号旧域名替换
        assertTrue(floor1Spans.any { it is InlineSpan.SmallImage && it.key == ThreadDetailParser.SMALL_IMAGE_WIN_KEY })
        assertTrue(floor1Spans.any {
            it is InlineSpan.Link &&
                it.isSmallFont &&
                it.url == "https://www.4d4y.com/forum/viewthread.php?tid=1579403"
        })
        assertTrue(floor1Spans.any { it is InlineSpan.Email && it.email == "qa@example.com" })

        // 核对警告标记、锁定楼层、缺失正文、空正文
        assertTrue(newPage.floors[0].isWarned)
        assertEquals("本帖最后由 RichUser 于 2026-11-25 14:00 编辑", newPage.floors[0].postStatus)
        assertEquals(listOf(ContentBlock.Notice("提示: 作者被禁止或删除 内容自动屏蔽")), newPage.floors[1].blocks)
        assertEquals(listOf(ContentBlock.Notice(ThreadDetailParser.NOTICE_CONTENT_NOT_FOUND)), newPage.floors[2].blocks)
        assertEquals(listOf(ContentBlock.Notice(ThreadDetailParser.NOTICE_EMPTY_CONTENT)), newPage.floors[3].blocks)

        // 无 #postlist 异常页：旧返回 null，新 hasPostList=false
        val missingPostListHtml = """<html><body><div id="wrap"><div class="alert_error">主题不存在</div></div></body></html>"""
        LegacyTestSupport.suppressBackgroundNotifyFetch()
        assertNull(HiParserThreadDetail.parse(context, Jsoup.parse(missingPostListHtml), "99101"))
        val newMissing = ThreadDetailParser.parse(missingPostListHtml, tid = "99101")
        assertFalse(newMissing.hasPostList)
        assertTrue(newMissing.floors.isEmpty())
        assertEquals("主题不存在", newMissing.meta.errorMessage)
    }

    private fun assertFloorEquivalent(oldFloor: DetailBean, newFloor: PostFloor, contextLabel: String) {
        assertEquals("$contextLabel postId", oldFloor.postId, newFloor.postId)
        assertEquals("$contextLabel floor", oldFloor.floor, newFloor.floor)
        assertEquals("$contextLabel page", oldFloor.page, newFloor.page)
        assertEquals("$contextLabel uid", oldFloor.uid, newFloor.uid)
        assertEquals("$contextLabel author", oldFloor.author, newFloor.author)
        assertEquals("$contextLabel avatarUrl", oldFloor.avatarUrl.ifEmpty { null }, newFloor.avatarUrl)
        assertEquals("$contextLabel timePost", oldFloor.timePost, newFloor.timePost)
        assertEquals("$contextLabel isWarned", oldFloor.isWarned, newFloor.isWarned)
        assertEquals("$contextLabel postStatus", oldFloor.postStatus?.trim()?.ifEmpty { null }, newFloor.postStatus)
        if (newFloor.signature != null) {
            assertEquals("$contextLabel signature", SignatureContainer.getSignature(oldFloor.uid).trim(), newFloor.signature)
        }

        val oldBlocks = (0 until oldFloor.contents.size).map { oldFloor.contents.get(it) }

        // 1. 图片块逐一核对 fullUrl / thumbUrl / fileSizeBytes
        val oldImgs = oldBlocks.filterIsInstance<ContentImg>()
        val newImgs = newFloor.blocks.filterIsInstance<ContentBlock.Image>()
        assertEquals("$contextLabel image count", oldImgs.size, newImgs.size)
        for (i in oldImgs.indices) {
            assertEquals("$contextLabel img[$i].fullUrl", oldImgs[i].content, newImgs[i].fullUrl)
            assertEquals("$contextLabel img[$i].thumbUrl", oldImgs[i].thumbUrl.ifEmpty { null }, newImgs[i].thumbUrl)
            assertEquals("$contextLabel img[$i].fileSize", oldImgs[i].fileSize, newImgs[i].fileSizeBytes)
        }

        // 2. 引用块逐一核对 author / targetTid / targetPid / contentHtml 清洗一致，并校验 S2 净化语义
        val oldQuotes = oldBlocks.filterIsInstance<ContentQuote>()
        val newQuotes = newFloor.blocks.filterIsInstance<ContentBlock.Quote>()
        assertEquals("$contextLabel quote count", oldQuotes.size, newQuotes.size)
        for (i in oldQuotes.indices) {
            assertEquals("$contextLabel quote[$i].targetTid", oldQuotes[i].tid.ifEmpty { null }, newQuotes[i].targetTid)
            assertEquals("$contextLabel quote[$i].targetPid", oldQuotes[i].postId.ifEmpty { null }, newQuotes[i].targetPid)
            assertEquals("$contextLabel quote[$i].author", oldQuotes[i].author, newQuotes[i].author)
            // S2 修复：新版从 font[size=2] 剔除 <a> 与残余回跳 BBCode 尾缀，因此新 contentHtml + authorAndTime 为旧 content 前缀；
            // 当旧版 time 无回跳残留时完全相等
            val newCombined = newQuotes[i].contentHtml + newQuotes[i].authorAndTime
            assertTrue(
                "$contextLabel quote[$i].content expected legacy '${oldQuotes[i].content}' to start with '$newCombined'",
                oldQuotes[i].content.startsWith(newCombined),
            )
            if (oldQuotes[i].content == newCombined) {
                assertEquals("$contextLabel quote[$i].time", oldQuotes[i].time, newQuotes[i].time)
            }
        }

        // 3. 跳楼标记逐一核对 label / targetTid / targetPid / floor / author
        val oldGotos = oldBlocks.filterIsInstance<ContentGoToFloor>()
        val newGotos = newFloor.blocks.filterIsInstance<ContentBlock.GoToFloor>()
        assertEquals("$contextLabel goto count", oldGotos.size, newGotos.size)
        for (i in oldGotos.indices) {
            assertEquals("$contextLabel goto[$i].label", oldGotos[i].content, newGotos[i].label)
            assertEquals("$contextLabel goto[$i].tid", oldGotos[i].tid.ifEmpty { null }, newGotos[i].targetTid)
            assertEquals("$contextLabel goto[$i].pid", oldGotos[i].postId.ifEmpty { null }, newGotos[i].targetPid)
            assertEquals("$contextLabel goto[$i].floor", oldGotos[i].floor, newGotos[i].floor)
            assertEquals("$contextLabel goto[$i].author", oldGotos[i].author, newGotos[i].author)
        }

        // 4. 附件块逐一核对 url / title / description
        val oldAttaches = oldBlocks.filterIsInstance<ContentAttach>()
        val newAttaches = newFloor.blocks.filterIsInstance<ContentBlock.Attachment>()
        assertEquals("$contextLabel attach count", oldAttaches.size, newAttaches.size)
        for (i in oldAttaches.indices) {
            val expectedContent = buildString {
                append("<a href=\"").append(newAttaches[i].url).append("\">")
                append(newAttaches[i].title).append("</a>")
                if (!newAttaches[i].description.isNullOrEmpty()) {
                    append("    ").append(newAttaches[i].description)
                }
            }
            assertEquals("$contextLabel attach[$i].content", oldAttaches[i].content.trim(), expectedContent.trim())
        }

        // 5. Notice 块与旧版 Gray ContentText 一致
        val newNotices = newFloor.blocks.filterIsInstance<ContentBlock.Notice>()
        if (newNotices.isNotEmpty()) {
            val oldNoticeTexts = oldBlocks.filterIsInstance<ContentText>().map { it.content }
            for (notice in newNotices) {
                assertTrue(
                    "$contextLabel notice '${notice.message}' not found in legacy texts $oldNoticeTexts",
                    oldNoticeTexts.any { it.contains(notice.message) },
                )
            }
        }

        // 6. 表情数量与旧 ContentText 中 <img src="...images/smilies/..."> 一致
        val oldSmileyCount = oldBlocks.filterIsInstance<ContentText>()
            .sumOf { Jsoup.parseBodyFragment(it.content).select("img[src*=images/smilies/]").size }
        val newSmileyCount = newFloor.blocks.filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
            .count { it is InlineSpan.Emoji }
        assertEquals("$contextLabel smiley count", oldSmileyCount, newSmileyCount)

        // 7. 正文链接与邮箱集合与旧 ContentText 中 <a href=...> 一致
        val oldLinkHrefs = oldBlocks.filterIsInstance<ContentText>()
            .flatMap { Jsoup.parseBodyFragment(it.content).select("a[href]").map { a -> a.attr("href") } }
        val newLinkHrefs = newFloor.blocks.filterIsInstance<ContentBlock.Text>()
            .flatMap { it.spans }
            .mapNotNull { span ->
                when (span) {
                    is InlineSpan.Link -> span.url
                    is InlineSpan.Email -> "mailto:${span.email}"
                    else -> null
                }
            }
        assertEquals("$contextLabel inline link hrefs", oldLinkHrefs, newLinkHrefs)

        // 8. 非 Notice 楼层的纯文本正文等价（旧 ContentText 经二次数字实体解码并归一化空白后与新 Text 块一致）
        if (newNotices.isEmpty()) {
            val oldPlainNormalized = normalizeTextForComparison(
                net.jejer.hipda.parser.common.DiscuzTextDecoder.decodeDoubleEscapedEntities(
                    oldBlocks.filterIsInstance<ContentText>()
                        .joinToString("") { Jsoup.parseBodyFragment(it.content).text() },
                ),
            )
            val newPlainNormalized = normalizeTextForComparison(
                newFloor.blocks.filterIsInstance<ContentBlock.Text>()
                    .flatMap { it.spans }
                    .joinToString("") { span ->
                        when (span) {
                            is InlineSpan.Plain -> span.text
                            is InlineSpan.Link -> span.label
                            is InlineSpan.Email -> span.email
                            is InlineSpan.Emoji, is InlineSpan.SmallImage, InlineSpan.LineBreak -> ""
                        }
                    },
            )
            assertEquals("$contextLabel plain text", oldPlainNormalized, newPlainNormalized)
        }
    }

    private fun normalizeTextForComparison(raw: String): String =
        raw.replace('\u00a0', ' ').replace(Regex("\\s+"), "")

    private fun collectSmileyKeys(floors: List<PostFloor>): Set<String> = floors
        .flatMap { it.blocks }
        .filterIsInstance<ContentBlock.Text>()
        .flatMap { it.spans }
        .filterIsInstance<InlineSpan.Emoji>()
        .map { it.normalizedKey }
        .toSet()

    companion object {
        private val SYNTHETIC_MULTI_POLL_HTML = """
            <html><body><div id="wrap">
              <div id="nav"><a href="index.php">4D4Y</a> » <a href="forumdisplay.php?fid=7">Geek Talks</a> » 多选投票测试帖</div>
              <div id="postlist"><div id="post_70001"><table><tbody><tr>
                <td class="postauthor"><div class="postinfo"><a href="space.php?uid=12345">PollAuthor</a></div></td>
                <td class="postcontent">
                  <div class="postinfo"><strong><a href="javascript:;"><em>1</em><sup>#</sup></a></strong>
                    <div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-20 12:00</em></div></div>
                  </div>
                  <div class="defaultpost"><div class="postmessage">
                    <div class="specialmsg">
                      <table><tbody><tr><td class="t_msgfont">投票帖正文</td></tr></tbody></table>
                      <form id="poll" method="post" action="misc.php?action=votepoll">
                        <input type="hidden" name="formhash" value="abcd9999" />
                        <div class="pollinfo">多选投票: ( 最多可选 2 项 ) , 共有 20 人参与投票 <a href="misc.php?action=viewvote">查看投票参与人</a></div>
                        <p class="polltimer">距结束还有: <strong>3 天</strong></p>
                        <div class="pollchart"><table><tbody>
                          <tr><td class="selector"><input type="checkbox" value="101" /></td><td class="polloption"><label>选项A</label></td></tr>
                          <tr><td></td><td><em>60.00% (12)</em></td></tr>
                          <tr><td class="selector"><input type="checkbox" value="102" /></td><td class="polloption"><label>选项B</label></td></tr>
                          <tr><td></td><td><em>40.00% (8)</em></td></tr>
                          <tr><td colspan="2"><button type="submit">提交</button> 投票后结果可见</td></tr>
                        </tbody></table></div>
                      </form>
                    </div>
                  </div></div>
                </td>
              </tr></tbody></table></div></div>
            </div></body></html>
        """.trimIndent()

        private val SYNTHETIC_SINGLE_POLL_HTML = """
            <html><body><div id="wrap">
              <div id="nav"><a href="index.php">4D4Y</a> » <a href="forumdisplay.php?fid=2">Discovery</a> » 单选投票测试帖</div>
              <div id="postlist"><div id="post_70002"><table><tbody><tr>
                <td class="postauthor"><div class="postinfo"><a href="space.php?uid=12346">SingleOP</a></div></td>
                <td class="postcontent">
                  <div class="postinfo"><strong><a href="javascript:;"><em>1</em><sup>#</sup></a></strong>
                    <div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-20 13:00</em></div></div>
                  </div>
                  <div class="defaultpost"><div class="postmessage">
                    <div class="t_msgfontfix"><table><tbody><tr><td class="t_msgfont">请单选</td></tr></tbody></table></div>
                    <form id="poll" method="post">
                      <input type="hidden" name="formhash" value="12345678" />
                      <div class="pollinfo">单选投票 , 共有 5 人参与投票</div>
                      <div class="pollchart"><table><tbody>
                        <tr><td class="selector"><input type="radio" value="201" /></td><td class="polloption"><label>赞成</label></td></tr>
                        <tr><td class="selector"><input type="radio" value="202" /></td><td class="polloption"><label>反对</label></td></tr>
                        <tr><td colspan="2"><button type="submit">提交</button> 仅限单选</td></tr>
                      </tbody></table></div>
                    </form>
                  </div></div>
                </td>
              </tr></tbody></table></div></div>
            </div></body></html>
        """.trimIndent()

        // 注意：div#postlist 内紧贴无空白节点，兼容旧解析器 postsEL.childNodeSize()/child(i)
        private val SYNTHETIC_RICH_THREAD_HTML = (
            """<html><body><div id="wrap">""" +
                """<div id="nav"><a href="index.php">4D4Y</a> » <a href="forumdisplay.php?fid=6">Buy &amp; Sell</a> »</div>""" +
                """<div id="threadtitle"><h1><a href="forumdisplay.php?fid=6&amp;typeid=1">[手机]</a> 回退标题文本</h1></div>""" +
                """<div id="postlist">""" +
                """<div id="post_80001"><table><tbody><tr>""" +
                """<td class="postauthor"><div class="postinfo"><a href="space.php?uid=30001">RichUser</a></div></td>""" +
                """<td class="postcontent">""" +
                """<div class="postinfo"><strong><a href="javascript:;"><em>1</em><sup>#</sup></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 14:00</em></div></div></div>""" +
                """<span class="postratings"><a href="misc.php?action=viewwarning&amp;tid=99100&amp;uid=30001">警告</a></span>""" +
                """<div class="defaultpost"><div class="postmessage"><div class="t_msgfontfix"><table><tbody><tr><td class="t_msgfont">""" +
                """<i class="pstatus"> 本帖最后由 RichUser 于 2026-11-25 14:00 编辑 </i>""" +
                """<div class="quote"><blockquote><font size="2"><font color="#999999">OrigUser 发表于 2026-11-24 10:00</font><a href="redirect.php?goto=findpost&amp;pid=79001&amp;ptid=99100">回跳</a></font><br>被引用内容<span style="display: none">隐藏内容</span></blockquote></div>""" +
                """<strong>回复 <a href="redirect.php?goto=findpost&amp;pid=79002&amp;ptid=99100">3#</a> <i>TargetAuthor</i></strong><br>""" +
                """正文含 <font color="#ffffff">白色文字</font> 与 <font color="#ff0000">红色文字</font> 联系 qa@example.com """ +
                """<a href="https://www.hi-pda.com/forum/viewthread.php?tid=1579403"><font size="1">HiPDA小尾巴</font></a> """ +
                """<img src="https://img02.4d4y.com/forum/images/smilies/default/smile.gif" />""" +
                """<img src="https://img02.4d4y.com/forum/images/smilies/coolmonkey/01.gif" />""" +
                """<img src="https://img02.4d4y.com/forum/images/smilies/grapeman/08.gif" />""" +
                """<img src="https://img02.4d4y.com/forum/attachments/day_140621/1406211752793e731a4fec8f7b.png" />""" +
                """<img src="https://img02.4d4y.com/forum/images/default/attachimg.gif" />""" +
                """<img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" />""" +
                """<img id="aimg_501" src="attachments/day_260101/photo.thumb.jpg" file="attachments/day_260101/photo_mid.jpg" onclick="zoom(this, 'attachments/day_260101/photo_full.jpg')" />""" +
                """<div class="t_attach" id="aimg_501_menu">下载 (200 KB)</div>""" +
                """<img src="https://cdn.example.invalid/ext.png" />""" +
                """<span><a href="attachment.php?aid=1001">doc.pdf</a></span>(12 KB)""" +
                """<a href="attachment.php?aid=1002">raw.zip</a>""" +
                """</td></tr></tbody></table>""" +
                """<div class="postattachlist"><dl class="t_attachlist attachimg"><dt><em>(10 KB)</em></dt><dd><img src="attachments/day_260101/dl.thumb.jpg" file="attachments/day_260101/dl.jpg" /></dd></dl></div>""" +
                """</div>""" +
                """<dl class="t_attachlist"><dd><p class="attachname"><a href="attachment.php?aid=1003">end.txt</a> (1 KB)</p></dd></dl>""" +
                """</div></div></td></tr><tr><td class="postbottom"><div class="signatures">测试签名档</div></td></tr></tbody></table></div>""" +
                """<div id="post_80002"><table><tbody><tr>""" +
                """<td class="postauthor"><div class="postinfo"><a href="space.php?uid=30002">LockedUser</a></div></td>""" +
                """<td class="postcontent"><div class="postinfo"><strong><a><em>2</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 14:01</em></div></div></div>""" +
                """<div class="defaultpost"><div class="postmessage"><div class="locked">提示: <em>作者被禁止或删除 内容自动屏蔽</em></div></div></div></td></tr></tbody></table></div>""" +
                """<div id="post_80003"><table><tbody><tr>""" +
                """<td class="postauthor"><div class="postinfo"><a href="space.php?uid=30003">MissingUser</a></div></td>""" +
                """<td class="postcontent"><div class="postinfo"><strong><a><em>3</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 14:02</em></div></div></div>""" +
                """<div class="defaultpost"><div class="postmessage"></div></div></td></tr></tbody></table></div>""" +
                """<div id="post_80004"><table><tbody><tr>""" +
                """<td class="postauthor"><div class="postinfo"><a href="space.php?uid=30004">EmptyUser</a></div></td>""" +
                """<td class="postcontent"><div class="postinfo"><strong><a><em>4</em></a></strong><div class="posterinfo"><div class="authorinfo"><em>发表于 2026-11-25 14:03</em></div></div></div>""" +
                """<div class="defaultpost"><div class="postmessage"><div class="t_msgfontfix"><table><tbody><tr><td class="t_msgfont"></td></tr></tbody></table></div></div></div></td></tr></tbody></table></div>""" +
                """</div></div></body></html>"""
            )
    }
}
