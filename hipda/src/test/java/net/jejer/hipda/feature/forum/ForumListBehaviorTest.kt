package net.jejer.hipda.feature.forum

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import java.time.LocalDate
import kotlin.math.abs
import net.jejer.hipda.feature.common.ThreadNavArgs
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.search.MyRepliesResult
import net.jejer.hipda.data.search.MyThreadsResult
import net.jejer.hipda.data.search.RemovedSearchHistoryEntry
import net.jejer.hipda.data.search.ResolvedForumLink
import net.jejer.hipda.data.search.SearchRepository
import net.jejer.hipda.data.search.SearchResult
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.data.threadlist.ThreadListBatchResult
import net.jejer.hipda.data.threadlist.ThreadListError
import net.jejer.hipda.data.threadlist.ThreadListItem
import net.jejer.hipda.data.threadlist.ThreadListLoadResult
import net.jejer.hipda.data.threadlist.ThreadListLoadState
import net.jejer.hipda.data.threadlist.ThreadListQuery
import net.jejer.hipda.data.threadlist.ThreadListRepository
import net.jejer.hipda.feature.common.ForumChromeSpec
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.threadlist.ThreadSummary
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassRenderTier
import net.jejer.hipda.ui.designsystem.glass.HiGlassStyle
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.assertWcagContrastAtLeast
import net.jejer.hipda.ui.designsystem.glass.resolveHiGlassStyleSpec
import net.jejer.hipda.ui.designsystem.glass.wcagContrastRatio
import net.jejer.hipda.ui.designsystem.hiColorSchemeFromSeed
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ForumListBehaviorTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 验收 4（V-C1）：全 20 套主题预设 × 浅色 / 深色 / OLED 纯黑 × 常规/选中底色下，
     * 已读与未读标题对比度均 >= 4.5:1，且深色与纯黑下已读对比度显著低于未读以保证清晰视觉区分。
     */
    @Test
    fun vc1ContrastCalibration_all20PresetsLightDarkOled_meetWcag4_5AndClearDarkSeparation() {
        assertEquals(20, HiColorPresets.ALL.size)
        HiColorPresets.ALL.forEach { preset ->
            listOf(
                Triple("light", hiColorSchemeFromSeed(preset.color, darkTheme = false, pureBlack = false), false),
                Triple("dark", hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = false), true),
                Triple("pureBlack", hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = true), true),
            ).forEach { (mode, scheme, darkTheme) ->
                val unreadColor = resolveThreadTitleColor(scheme, isVisited = false, darkTheme = darkTheme)
                val readColor = resolveThreadTitleColor(scheme, isVisited = true, darkTheme = darkTheme)

                listOf(
                    "surface" to resolveThreadRowBackground(scheme, selected = false),
                    "selected" to resolveThreadRowBackground(scheme, selected = true),
                ).forEach { (bgName, rawBg) ->
                    val bg = if (rawBg.alpha < 1f) rawBg.compositeOver(scheme.surface) else rawBg
                    assertWcagContrastAtLeast(4.5f, bg, unreadColor, "${preset.id}/$mode/$bgName unread")
                    assertWcagContrastAtLeast(4.5f, bg, readColor, "${preset.id}/$mode/$bgName read")
                }

                // 在常规 surface 底色上验证深色与纯黑模式的视觉弱化区分度（校准 V-C1）
                val unreadRatio = wcagContrastRatio(scheme.surface, unreadColor.compositeOver(scheme.surface))
                val readRatio = wcagContrastRatio(scheme.surface, readColor.compositeOver(scheme.surface))
                assertTrue("${preset.id}/$mode 已读对比度($readRatio)应低于未读($unreadRatio)", readRatio < unreadRatio)
                if (darkTheme) {
                    assertTrue(
                        "${preset.id}/$mode 深色/OLED 已读($readRatio)相对未读($unreadRatio)弱化区分度不足",
                        unreadRatio - readRatio >= 5.0f && readRatio / unreadRatio <= 0.60f,
                    )
                }
            }
        }
    }

    /** 验收 4：荧光笔峰值 alpha 严守 HiGlassTokens 上限、严禁 #FFB003、叠在 TopBar 玻璃上对 onSurface 对比度 >= 4.5:1。 */
    @Test
    fun tabHighlighter_respectsTokenAlphaCeilingForbidsKudoYellowAndMeetsWcag4_5() {
        val forbiddenKudoYellow = Color(0xFFFFB003)
        HiColorPresets.ALL.forEach { preset ->
            listOf(
                Triple("light", hiColorSchemeFromSeed(preset.color, darkTheme = false, pureBlack = false), false),
                Triple("dark", hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = false), true),
                Triple("pureBlack", hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = true), true),
            ).forEach { (mode, scheme, darkTheme) ->
                val colors = resolveTabHighlighterColors(scheme, darkTheme)
                assertTrue("荧光笔渐变至少包含 2 个停靠色", colors.size >= 2)
                val maxAllowed = if (darkTheme) {
                    HiGlassTokens.TabHighlighterContainerDarkMaxAlpha
                } else {
                    HiGlassTokens.TabHighlighterContainerLightMaxAlpha
                }
                val peakColor = colors.maxBy { it.alpha }
                assertTrue("${preset.id}/$mode 峰值 alpha=${peakColor.alpha} 超上限 $maxAllowed", peakColor.alpha <= maxAllowed + 0.0001f)
                colors.forEach { c ->
                    assertNotEquals("严禁使用 kudo 品牌黄 #FFB003", forbiddenKudoYellow.copy(alpha = c.alpha), c)
                }
                val topBarBg = resolveHiGlassStyleSpec(
                    colorScheme = scheme,
                    darkTheme = darkTheme,
                    renderTier = GlassRenderTier.REFRACTION_AND_BLUR,
                    style = HiGlassStyle.TopBar,
                ).activeTintColor.compositeOver(scheme.surface)
                assertWcagContrastAtLeast(
                    minRatio = 4.5f,
                    surface = peakColor,
                    foreground = scheme.onSurface,
                    backdrop = topBarBg,
                    label = "${preset.id}/$mode TopBar+TabHighlighter onSurface",
                )
            }
        }
    }

    /** 验收 4：窄中栏 < 300dp（274dp）取消独立 40dp 大头像列，标题获全宽且副行无溢出；宽栏 >= 300dp（380dp）保留 40dp 头像列。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun narrowMiddlePaneUnder300dp_usesCompactSubRowWithoutLargeAvatarColumn() {
        val sample = ThreadListItem(
            summary = ThreadSummary(
                tid = "777",
                title = "窄中栏布局验证：长标题在 274dp 中栏下应占满整行宽度不被左侧大头像列挤占",
                titleColor = null,
                typeName = "讨论",
                isSticky = true,
                isNew = false,
                isPoll = false,
                author = "超长用户名测试用户ABCDE",
                authorId = "1",
                avatarUrl = null,
                timeCreate = "2026-11-26",
                timeUpdate = "5分钟前",
                lastPostAuthor = "回复者",
                replyCountText = "42",
                viewCountText = "1280",
                withPic = true,
                maxPage = 2,
            ),
            isVisited = false,
        )
        var paneWidthDp by mutableStateOf(274.dp)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                Box(Modifier.width(paneWidthDp)) {
                    ThreadListRow(
                        item = sample,
                        selected = false,
                        compactLayout = paneWidthDp < FORUM_COMPACT_ROW_WIDTH_THRESHOLD,
                        onClick = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val compactTitleBounds = composeRule.onNodeWithText(sample.summary.title, substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val compactMetaTime = composeRule.onNodeWithText("5分钟前", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val compactPanePx = with(composeRule.density) { 274.dp.toPx() }
        val compactTitleLeftDp = with(composeRule.density) { compactTitleBounds.left.toDp() }
        assertTrue("274dp 窄中栏下标题左侧不应有 40dp 大头像列占位，实际 left=$compactTitleLeftDp", compactTitleLeftDp <= 16.dp)
        assertTrue("274dp 窄中栏下时间标签不得溢出右边界", compactMetaTime.right <= compactPanePx + 1f)
        composeRule.onNodeWithText("42", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("42/1280", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("图", useUnmergedTree = true).assertDoesNotExist()

        // 切到 380dp 标准宽：左侧应出现 40dp 独立头像列，标题起始位置显著右移，且恢复 reply/view 显示
        paneWidthDp = 380.dp
        composeRule.waitForIdle()
        val wideTitleLeftDp = with(composeRule.density) {
            composeRule.onNodeWithText(sample.summary.title, substring = true, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left.toDp()
        }
        assertTrue("380dp 标准宽下标题应让出左侧 40dp 头像列，实际 left=$wideTitleLeftDp", wideTitleLeftDp >= 60.dp)
        composeRule.onNodeWithText("42/1280", useUnmergedTree = true).assertIsDisplayed()
    }

    /** U1 验收 3：去「图」标与徽标行、置顶内联图钉、投票/分类入元信息行、短标题内联「新」/2 行溢出「新」回落元信息行首、作者吃满剩余宽。 */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun threadListRow_denoisedBadges_inlineStickyAndNewOverflowFallback_andFullWidthAuthor() {
        val refDate = LocalDate.of(2026, 11, 28)
        val shortNewItem = ThreadListItem(
            summary = ThreadSummary(
                tid = "801",
                title = "短标题新帖测试",
                titleColor = null,
                typeName = "手机",
                isSticky = true,
                isNew = true,
                isPoll = true,
                author = "long_author_1",
                authorId = "1",
                avatarUrl = null,
                timeCreate = "2026-11-28 09:05",
                timeUpdate = "2026-11-28 09:05",
                lastPostAuthor = "回复者",
                replyCountText = "8",
                viewCountText = "120",
                withPic = true,
                maxPage = 1,
            ),
            isVisited = false,
        )
        val longOverflowNewItem = ThreadListItem(
            summary = shortNewItem.summary.copy(
                tid = "802",
                title = "这是一条非常非常长的测试帖子标题用于触发两行省略截断并验证尾部新字徽标自动回落到下方元信息行首完整可见不被裁切",
                isSticky = false,
                isPoll = false,
                typeName = null,
                author = "long_author_1",
            ),
            isVisited = false,
        )
        val dupPollItem = ThreadListItem(
            summary = shortNewItem.summary.copy(
                tid = "803",
                title = "投票分类去重测试",
                isSticky = false,
                isNew = false,
                isPoll = true,
                typeName = "投票",
                author = "poll_host",
            ),
            isVisited = false,
        )
        var currentItem by mutableStateOf(shortNewItem)
        var paneWidthDp by mutableStateOf(274.dp)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                Box(Modifier.width(paneWidthDp)) {
                    ThreadListRow(
                        item = currentItem,
                        selected = false,
                        compactLayout = paneWidthDp < FORUM_COMPACT_ROW_WIDTH_THRESHOLD,
                        referenceDate = refDate,
                        onClick = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. withPic=true 不渲染「图」；isSticky=true 内联图钉且无独立「置顶」文本；isNew=true 不渲染「新」标签；isPoll + typeName 合入元信息行；绝对时间今日输出 HH:mm
        composeRule.onNodeWithText("图", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("置顶", useUnmergedTree = true).assertDoesNotExist()
        val pinNode = composeRule.onNodeWithContentDescription("置顶", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val shortTitleNode = composeRule.onNodeWithText("短标题新帖测试", substring = true, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        assertTrue("置顶图钉应内联在标题首行垂直范围内", pinNode.boundsInRoot.top >= shortTitleNode.boundsInRoot.top - 2f && pinNode.boundsInRoot.bottom <= shortTitleNode.boundsInRoot.bottom + 2f)
        val shortTitleText = shortTitleNode.config[SemanticsProperties.Text].joinToString("") { it.text }
        assertFalse("已取消列表「新」标签，短标题末尾不得附加「新」：$shortTitleText", shortTitleText.endsWith("新"))
        composeRule.onNodeWithText("新", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("投票 · 手机 · long_author_1", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("09:05", useUnmergedTree = true).assertIsDisplayed()

        // 2. 超长 2 行截断标题 + isNew=true：不渲染「新」标签；作者名不因 Spacer 被腰斩
        currentItem = longOverflowNewItem
        composeRule.waitForIdle()
        val longTitleNode = composeRule.onNodeWithText("这是一条非常非常长", substring = true, useUnmergedTree = true).fetchSemanticsNode()
        val titleLayouts = mutableListOf<TextLayoutResult>()
        longTitleNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(titleLayouts)
        assertTrue("超长标题在 274dp 下必须触发 2 行视觉溢出", titleLayouts.single().hasVisualOverflow)
        composeRule.onNodeWithText("新", useUnmergedTree = true).assertDoesNotExist()
        val authorNode = composeRule.onNodeWithText("long_author_1", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val authorLayouts = mutableListOf<TextLayoutResult>()
        authorNode.config[SemanticsActions.GetTextLayoutResult].action?.invoke(authorLayouts)
        assertFalse("去掉 Spacer(weight(1f)) 后，13 字符作者名在 274dp 窄中栏应完整显示不截断", authorLayouts.single().hasVisualOverflow)

        // 3. 投票 + 同名分类「投票」去重，不出现「投票 · 投票」
        currentItem = dupPollItem
        composeRule.waitForIdle()
        composeRule.onNodeWithText("投票 · poll_host", useUnmergedTree = true).assertIsDisplayed()
    }

    /** U5a 验收 4：回复数前 14dp 气泡图标 + 2dp 间距、无「·」且与时间间距 8dp、今日新帖空回复数不显示图标、窄栏 < 300dp 只显示回复数带图标无截断。 */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun u5a_threadListRow_replyCountHas14dpBubbleIcon_noDotBeforeTime_hidesIconWhenEmpty_andNarrowPaneNoClip() {
        val refDate = LocalDate.of(2026, 11, 28)
        val normalItem = ThreadListItem(
            summary = ThreadSummary(
                tid = "851",
                title = "回复数图标与间距校验帖",
                titleColor = null,
                typeName = "讨论",
                isSticky = false,
                isNew = false,
                isPoll = true,
                author = "linus_hi",
                authorId = "1",
                avatarUrl = null,
                timeCreate = "2026-11-28 09:05",
                timeUpdate = "2026-11-28 09:05",
                lastPostAuthor = "回复者",
                replyCountText = "42",
                viewCountText = "1280",
                withPic = false,
                maxPage = 1,
            ),
            isVisited = false,
        )
        val todayNewItem = ThreadListItem(
            summary = normalItem.summary.copy(
                tid = "852",
                title = "今日新帖空回复数校验",
                replyCountText = "",
                viewCountText = "",
            ),
            isVisited = false,
        )
        var currentItem by mutableStateOf(normalItem)
        var paneWidthDp by mutableStateOf(411.dp)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                Box(Modifier.width(paneWidthDp).background(MaterialTheme.colorScheme.surface)) {
                    ThreadListRow(
                        item = currentItem,
                        selected = false,
                        compactLayout = paneWidthDp < FORUM_COMPACT_ROW_WIDTH_THRESHOLD,
                        referenceDate = refDate,
                        onClick = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val density = composeRule.density

        fun hasNonBgPixels(bmp: Bitmap, leftPx: Int, topPx: Int, rightPx: Int, bottomPx: Int, bg: Int): Boolean {
            for (y in topPx.coerceIn(0, bmp.height - 1) until bottomPx.coerceIn(1, bmp.height)) {
                for (x in leftPx.coerceIn(0, bmp.width - 1) until rightPx.coerceIn(1, bmp.width)) {
                    val p = bmp.getPixel(x, y)
                    val d = maxOf(
                        abs(android.graphics.Color.red(p) - android.graphics.Color.red(bg)),
                        abs(android.graphics.Color.green(p) - android.graphics.Color.green(bg)),
                        abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(bg)),
                    )
                    if (d >= 12) return true
                }
            }
            return false
        }

        // 1. 常规宽 411dp：有回复数 → 左侧 14dp 气泡图标 + 2dp 间距，无「·」文本节点，与时间间距 8dp，作者行仍保留「 · 」
        composeRule.onNodeWithText("投票 · 讨论 · linus_hi", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("回复数与时间之间不得存在「·」文本节点", 0, composeRule.onAllNodesWithText("·", useUnmergedTree = true).fetchSemanticsNodes().size)
        val countBounds = composeRule.onNodeWithText("42/1280", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val timeBounds = composeRule.onNodeWithText("09:05", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val countToTimeGapDp = with(density) { (timeBounds.left - countBounds.right).toDp().value }
        assertEquals("回复数与时间之间间距必须为 8dp", 8f, countToTimeGapDp, 1.0f)

        val bmpNormal = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val surfaceBg = bmpNormal.getPixel(2, 2)
        val iconWidthPx = with(density) { 14.dp.toPx() }
        val iconGapPx = with(density) { 2.dp.toPx() }
        val iconRightPx = (countBounds.left - iconGapPx).roundToInt()
        val iconLeftPx = (iconRightPx - iconWidthPx).roundToInt()
        val iconTopPx = (countBounds.center.y - iconWidthPx / 2f).roundToInt()
        val iconBottomPx = (countBounds.center.y + iconWidthPx / 2f).roundToInt()
        assertTrue(
            "回复数左侧 14dp 区域必须渲染气泡图标像素",
            hasNonBgPixels(bmpNormal, iconLeftPx, iconTopPx, iconRightPx, iconBottomPx, surfaceBg),
        )

        // 2. 今日新帖（回复数为空）：不渲染气泡图标、无「·」
        currentItem = todayNewItem
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("·", useUnmergedTree = true).fetchSemanticsNodes().size)
        val todayTimeBounds = composeRule.onNodeWithText("09:05", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val bmpToday = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val todayBg = bmpToday.getPixel(2, 2)
        val emptyLeftCheckRight = (todayTimeBounds.left - iconGapPx).roundToInt()
        val emptyLeftCheckLeft = (emptyLeftCheckRight - iconWidthPx).roundToInt()
        assertFalse(
            "今日新帖回复数为空时，时间左侧不得显示气泡图标",
            hasNonBgPixels(bmpToday, emptyLeftCheckLeft, iconTopPx, emptyLeftCheckRight, iconBottomPx, todayBg),
        )

        // 3. 窄栏 274dp (< 300dp)：只显示回复数「42」+ 14dp 气泡图标 + 8dp 间距 + 时间，不截断不溢出
        currentItem = normalItem
        paneWidthDp = 274.dp
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("·", useUnmergedTree = true).fetchSemanticsNodes().size)
        composeRule.onNodeWithText("42/1280", useUnmergedTree = true).assertDoesNotExist()
        val narrowCountBounds = composeRule.onNodeWithText("42", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val narrowTimeBounds = composeRule.onNodeWithText("09:05", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(8f, with(density) { (narrowTimeBounds.left - narrowCountBounds.right).toDp().value }, 1.0f)
        assertTrue("窄栏下时间不得溢出 274dp 右边界", narrowTimeBounds.right <= with(density) { 274.dp.toPx() } + 1f)
        val bmpNarrow = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val narrowBg = bmpNarrow.getPixel(2, 2)
        val nIconRight = (narrowCountBounds.left - iconGapPx).roundToInt()
        val nIconLeft = (nIconRight - iconWidthPx).roundToInt()
        val nIconTop = (narrowCountBounds.center.y - iconWidthPx / 2f).roundToInt()
        val nIconBottom = (narrowCountBounds.center.y + iconWidthPx / 2f).roundToInt()
        assertTrue(
            "窄栏 274dp 下回复数左侧仍必须渲染 14dp 气泡图标",
            hasNonBgPixels(bmpNarrow, nIconLeft, nIconTop, nIconRight, nIconBottom, narrowBg),
        )
    }

    /** U1 验收 3：选中行具 8dp 水平内缩圆角底且紧邻上下分隔线隐藏，普通分隔线缩进至标题起点（标准 68dp / 紧凑 12dp）。 */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun forumPane_selectedRowInsetRoundedPill_hidesAdjacentDividers_andIndentsNormalDividers() {
        val harness = UiHarness()
        var paneWidthDp by mutableStateOf(411.dp)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                Box(Modifier.width(paneWidthDp).background(MaterialTheme.colorScheme.surface)) {
                    ForumPane(
                        source = ForumListSource.Forum(2),
                        chromeSpec = ForumChromeSpec(showForumTabs = false, useGlassTopBar = false),
                        selectedTid = "1002",
                        onSelectSource = {},
                        onOpenThread = {},
                        onOpenLogin = {},
                        viewModel = harness.viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        fun pixelDelta(a: Int, b: Int): Int = maxOf(
            abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
            abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)),
        )

        fun maxDeltaAcrossBand(bmp: Bitmap, xPx: Int, yStart: Int, yEnd: Int, bg: Int): Int {
            var best = 0
            for (y in yStart.coerceIn(0, bmp.height - 1)..yEnd.coerceIn(0, bmp.height - 1)) {
                val d = pixelDelta(bmp.getPixel(xPx.coerceIn(0, bmp.width - 1), y), bg)
                if (d > best) best = d
            }
            return best
        }

        // 标准宽 411dp（缩进 68dp）
        val bmpWide = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val densityPx = composeRule.density.density
        val row1 = composeRule.onNodeWithText("论坛列表测试帖 1").fetchSemanticsNode().boundsInRoot
        val row2 = composeRule.onNodeWithText("论坛列表测试帖 2").fetchSemanticsNode().boundsInRoot
        val row3 = composeRule.onNodeWithText("论坛列表测试帖 3").fetchSemanticsNode().boundsInRoot
        val row4 = composeRule.onNodeWithText("论坛列表测试帖 4").fetchSemanticsNode().boundsInRoot

        val surfaceBg = bmpWide.getPixel((2 * densityPx).roundToInt(), row1.center.y.roundToInt())
        val selectedOuterMarginPixel = bmpWide.getPixel((2 * densityPx).roundToInt(), row2.center.y.roundToInt())
        val selectedPillInteriorPixel = bmpWide.getPixel((14 * densityPx).roundToInt(), (row2.top + 8 * densityPx).roundToInt())
        assertEquals("选中行外侧 8dp 缩进区应保持纯 surface 底色", 0, pixelDelta(selectedOuterMarginPixel, surfaceBg))
        assertTrue("选中行内缩胶囊内部应呈 secondaryContainer 着色底", pixelDelta(selectedPillInteriorPixel, surfaceBg) >= 6)

        // 选中行上方（row1-row2 交界）与下方（row2-row3 交界）分隔线必须隐藏
        val xDividerZone = (200 * densityPx).roundToInt()
        val aboveSelectedDelta = maxDeltaAcrossBand(bmpWide, xDividerZone, (row1.bottom - 1).roundToInt(), (row2.top - 1).roundToInt(), surfaceBg)
        val belowSelectedDelta = maxDeltaAcrossBand(bmpWide, xDividerZone, (row2.bottom + 1).roundToInt(), (row3.top - 1).roundToInt(), surfaceBg)
        assertEquals("选中行上一行下方分隔线应隐藏", 0, aboveSelectedDelta)
        assertEquals("选中行自身下方分隔线应隐藏", 0, belowSelectedDelta)

        // 普通行（row3-row4 交界）分隔线存在且左侧缩进 68dp（x=24dp 无分隔线，x=200dp 有分隔线）
        val normalSeamStart = (row3.bottom - 1).roundToInt()
        val normalSeamEnd = (row4.top + 1).roundToInt()
        val normalBeforeIndent = maxDeltaAcrossBand(bmpWide, (24 * densityPx).roundToInt(), normalSeamStart, normalSeamEnd, surfaceBg)
        val normalAfterIndent = maxDeltaAcrossBand(bmpWide, xDividerZone, normalSeamStart, normalSeamEnd, surfaceBg)
        assertEquals("标准宽普通分隔线在 68dp 缩进左侧不得绘制", 0, normalBeforeIndent)
        assertTrue("标准宽普通分隔线在 68dp 缩进右侧必须可见（delta=$normalAfterIndent）", normalAfterIndent >= 8)

        // 切至紧凑宽 274dp（缩进 12dp）
        paneWidthDp = 274.dp
        composeRule.waitForIdle()
        val bmpCompact = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val cRow3 = composeRule.onNodeWithText("论坛列表测试帖 3").fetchSemanticsNode().boundsInRoot
        val cRow4 = composeRule.onNodeWithText("论坛列表测试帖 4").fetchSemanticsNode().boundsInRoot
        val cSurfaceBg = bmpCompact.getPixel((2 * densityPx).roundToInt(), cRow3.center.y.roundToInt())
        val cSeamStart = (cRow3.bottom - 1).roundToInt()
        val cSeamEnd = (cRow4.top + 1).roundToInt()
        assertEquals("紧凑宽普通分隔线在 12dp 缩进左侧不得绘制", 0, maxDeltaAcrossBand(bmpCompact, (4 * densityPx).roundToInt(), cSeamStart, cSeamEnd, cSurfaceBg))
        assertTrue("紧凑宽普通分隔线在 12dp 缩进右侧必须可见", maxDeltaAcrossBand(bmpCompact, (100 * densityPx).roundToInt(), cSeamStart, cSeamEnd, cSurfaceBg) >= 8)
    }

    /** 验收 4：更多排序菜单读写 SettingsRepository，今日新帖下不展示排序菜单；发新帖/管理版块回调正常触发。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun sortMenuAndActions_persistSortInForumAndHideSortInTodayNew() {
        val harness = UiHarness()
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))
        var newThreadClicks = 0
        var manageForumsClicks = 0

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ForumPane(
                    source = source,
                    chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = true),
                    selectedTid = null,
                    onSelectSource = { source = it },
                    onOpenThread = {},
                    onOpenLogin = {},
                    onNewThreadClick = { newThreadClicks += 1 },
                    onManageForumsClick = { manageForumsClicks += 1 },
                    viewModel = harness.viewModel,
                )
            }
        }
        composeRule.waitForIdle()

        // 发新帖与管理版块回调
        composeRule.onNodeWithContentDescription("发新帖").performClick()
        composeRule.onNodeWithContentDescription("管理版块").performClick()
        assertEquals(1, newThreadClicks)
        assertEquals(1, manageForumsClicks)

        // 版块下打开「更多」菜单切换「按发帖排序」
        composeRule.onNodeWithContentDescription("更多").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("按回复排序").assertIsDisplayed()
        composeRule.onNodeWithText("按发帖排序").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertTrue("点击按发帖排序必须写入 SettingsRepository", harness.settingsFlow.value.isSortByPostTime(2))
        assertEquals(true, harness.lastRefreshQuery?.sortByPostTime)

        // 切至「今日新帖」：不展示无意义的版块排序入口
        composeRule.onNodeWithText("今日新帖").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("更多").assertDoesNotExist()
    }

    /** #1：Buy & Sell（fid=6）显示 48×48dp「分类」按钮、展开全部 9 项旧版文案、选「手机」传 typeId="1" 并回顶、选「全部」恢复 null；fid=2/今日新帖隐藏。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun buyAndSellCategoryFilter_onlyInFid6_selectsPhoneUpdatesAndScrollsTop_thenResetsAll() {
        val harness = UiHarness()
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))
        var listState: LazyListState? = null

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val state = rememberLazyListState()
                listState = state
                ForumPane(
                    source = source,
                    chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = true),
                    selectedTid = null,
                    onSelectSource = { source = it },
                    onOpenThread = {},
                    onOpenLogin = {},
                    listState = state,
                    viewModel = harness.viewModel,
                )
            }
        }
        composeRule.waitForIdle()

        // fid=2 不显示「分类」
        composeRule.onNodeWithContentDescription("分类").assertDoesNotExist()

        // 切至「今日新帖」不显示「分类」
        source = ForumListSource.TodayNew
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("分类").assertDoesNotExist()

        // 切至 fid=6（Buy & Sell）：显示 48×48dp「分类」按钮
        source = ForumListSource.Forum(6)
        composeRule.waitForIdle()
        val filterBtn = composeRule.onNodeWithContentDescription("分类").assertIsDisplayed()
        val min48Px = with(composeRule.density) { 48.dp.toPx() }
        val btnSize = filterBtn.fetchSemanticsNode().size
        assertTrue("分类按钮宽必须 >= 48dp", btnSize.width >= min48Px - 1f)
        assertTrue("分类按钮高必须 >= 48dp", btnSize.height >= min48Px - 1f)

        // 先向下滚动，再点选第 2 项「手机」(typeId = "1")
        val lazyState = requireNotNull(listState)
        composeRule.runOnIdle { lazyState.requestScrollToItem(10) }
        composeRule.waitForIdle()
        assertTrue(lazyState.firstVisibleItemIndex >= 10)

        filterBtn.performClick()
        composeRule.waitForIdle()
        val expectedLabels = listOf(
            "全部", "手机", "掌上电脑", "笔记本电脑", "无线产品",
            "数码相机、摄像机", "MP3随身听", "各类配件", "其他好玩的",
        )
        expectedLabels.forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }

        composeRule.onNodeWithText("手机").performClick()
        composeRule.waitForIdle()
        assertEquals(ThreadListQuery(fid = 6, sortByPostTime = false, typeId = "1"), harness.lastRefreshQuery)
        assertEquals("选分类后列表应回到顶部", 0, lazyState.firstVisibleItemIndex)
        composeRule.onNodeWithText("手机分类测试帖 1").assertIsDisplayed()
        assertEquals(
            "选中非全部时应提供语义状态提示",
            "手机",
            composeRule.onNodeWithContentDescription("分类").fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )

        // 再次展开并选「全部」恢复 typeId = null
        composeRule.onNodeWithContentDescription("分类").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("全部").performClick()
        composeRule.waitForIdle()
        assertEquals(ThreadListQuery(fid = 6, sortByPostTime = false, typeId = null), harness.lastRefreshQuery)
        composeRule.onNodeWithText("论坛列表测试帖 1").assertIsDisplayed()

        // 切回 fid=2：隐藏按钮且绝不透传 typeId
        source = ForumListSource.Forum(2)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("分类").assertDoesNotExist()
        assertTrue(harness.refreshQueries.filter { it.fid == 2 }.all { it.typeId == null })
    }

    /** #4：版块列表与今日新帖项单击 openLastPage=false、长按 openLastPage=true。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun threadListRows_clickOpensNormally_andLongClickOpensLastPage() {
        val harness = UiHarness()
        val opened = mutableListOf<ThreadNavArgs>()
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ForumPane(
                    source = source,
                    chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = true),
                    selectedTid = null,
                    onSelectSource = { source = it },
                    onOpenThread = opened::add,
                    onOpenLogin = {},
                    viewModel = harness.viewModel,
                )
            }
        }
        composeRule.waitForIdle()

        val row = composeRule.onNodeWithText("论坛列表测试帖 1").assertIsDisplayed()
        row.performClick()
        composeRule.waitForIdle()
        assertEquals(1, opened.size)
        assertFalse("单击应保持 openLastPage=false", opened.last().openLastPage)

        row.performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals(2, opened.size)
        assertTrue("长按帖子列表项必须触发 openLastPage=true", opened.last().openLastPage)
        assertEquals("1001", opened.last().tid)
    }

    /** 验收 3 / §3.1 / §10.1：BalancedForumTabRow 少项等分居中、多项横向滑动，且所有标签与管理版块触控区 >= 48dp。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun balancedForumTabRow_adaptsLayoutAndEnforces48dpTouchTargets() {
        var followed by mutableStateOf(listOf(2, 6, 7))
        var selected by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                BalancedForumTabRow(
                    selectedSource = selected,
                    followedForums = followed,
                    onSelectSource = { selected = it },
                    onManageForumsClick = {},
                )
            }
        }
        composeRule.waitForIdle()

        val min48Px = with(composeRule.density) { 48.dp.toPx() }
        val tabNodes = composeRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
        ).fetchSemanticsNodes()
        assertEquals(4, tabNodes.size)
        tabNodes.forEach { node ->
            assertTrue("每个版块标签触控高必须 >= 48dp，实际 ${node.size.height}px", node.size.height >= min48Px - 1f)
        }
        val manageBtn = composeRule.onNodeWithContentDescription("管理版块").fetchSemanticsNode()
        assertTrue("管理版块按钮触控高必须 >= 48dp", manageBtn.size.height >= min48Px - 1f)
        assertTrue("管理版块按钮触控宽必须 >= 48dp", manageBtn.size.width >= min48Px - 1f)
        composeRule.onNodeWithText("Discovery").assertIsSelected()

        // 扩展到 9 个版块触发横向滑动并自动滚入选中项
        followed = listOf(2, 6, 7, 59, 12, 57, 63, 5, 25)
        selected = ForumListSource.Forum(25)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("吃喝玩乐").assertIsSelected().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("管理版块").assertIsDisplayed()
    }

    /** 验收 4：刷新顶部新增 3 条新帖展示「有 3 条新帖」胶囊，点击滚回顶部并清除胶囊；底部自动续载与重试脚部正常。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun refreshNewThreadsPillAndAutoLoadMoreFooter_workEndToEnd() {
        val harness = UiHarness()
        var listState: LazyListState? = null

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val state = rememberLazyListState()
                listState = state
                ForumPane(
                    source = ForumListSource.Forum(2),
                    chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = true),
                    selectedTid = null,
                    onSelectSource = {},
                    onOpenThread = {},
                    onOpenLogin = {},
                    listState = state,
                    viewModel = harness.viewModel,
                )
            }
        }
        composeRule.waitForIdle()

        val lazyState = requireNotNull(listState)
        // 先滚至第 8 条再触发带 3 条新帖的刷新
        composeRule.runOnIdle { lazyState.requestScrollToItem(8) }
        composeRule.waitForIdle()
        assertTrue(lazyState.firstVisibleItemIndex >= 8)

        harness.prependNewThreadsOnNextRefresh(3)
        composeRule.runOnIdle { harness.viewModel.refresh() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("有 3 条新帖").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals("点击新帖胶囊后列表应滚回顶部", 0, lazyState.firstVisibleItemIndex)
        composeRule.onNodeWithText("有 3 条新帖").assertDoesNotExist()

        // 滚到底部触发自动续载下一页；失败后整行「加载失败，点击重试」可点重试并进入「没有更多了」
        harness.failNextLoadMore = true
        composeRule.runOnIdle { lazyState.requestScrollToItem(harness.viewModel.uiState.value.items.lastIndex) }
        composeRule.waitForIdle()

        assertEquals("滚至底部应自动触发 1 次 loadMore", 1, harness.loadMoreCalls.get())
        composeRule.onNodeWithText("加载失败，点击重试").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals("点击失败脚部应再次触发 loadMore 重试", 2, harness.loadMoreCalls.get())
        // 续载新增帖子将脚部向下推了一格，滚至最底验证「没有更多了」展示
        composeRule.runOnIdle { lazyState.requestScrollToItem(harness.viewModel.uiState.value.items.size) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("没有更多了").assertIsDisplayed()
    }

    /** 闭合 V-E1 F1：无外层 Surface 提供 LocalContentColor 时，浅色 / 深色 / OLED 纯黑三种主题下骨架块相对背景均有明显色差（delta >= 12 且可见像素 > 1000）。 */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun skeleton_lightDarkAndPureBlack_renderVisibleBlocksOverBackground() {
        val pendingGate = CompletableDeferred<Unit>()
        val harness = UiHarness(holdInitialRefresh = pendingGate)
        var themeMode by mutableStateOf(HiThemeMode.LIGHT)
        var pureBlack by mutableStateOf(false)

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                HiTheme(themeMode = themeMode, pureBlack = pureBlack, seed = HiColorPresets.Brand.toSeed()) {
                    // 刻意使用 Box.background 而非 Surface，复现 HiAdaptiveShell 未提供 LocalContentColor 的真实场景
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        ForumPane(
                            source = ForumListSource.Forum(2),
                            chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = true),
                            selectedTid = null,
                            onSelectSource = {},
                            onOpenThread = {},
                            onOpenLogin = {},
                            viewModel = harness.viewModel,
                        )
                    }
                }
            }

            listOf(
                Triple("light", HiThemeMode.LIGHT, false),
                Triple("dark", HiThemeMode.DARK, false),
                Triple("pureBlack", HiThemeMode.DARK, true),
            ).forEach { (label, mode, oled) ->
                themeMode = mode
                pureBlack = oled
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.mainClock.advanceTimeByFrame()

                val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
                val (maxDelta, visiblePixels) = measureSkeletonContrast(bitmap, expectBrighter = mode == HiThemeMode.DARK)
                assertTrue(
                    "V-E1 F1：$label 主题下骨架块与背景色差不足（maxDelta=$maxDelta, visiblePixels=$visiblePixels），期望 delta >= 12 且像素 > 1000",
                    maxDelta >= 12 && visiblePixels > 1_000,
                )
            }
        } finally {
            pendingGate.complete(Unit)
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun measureSkeletonContrast(bitmap: Bitmap, expectBrighter: Boolean): Pair<Int, Int> {
        val density = composeRule.density.density
        val xStart = (16 * density).roundToInt().coerceIn(0, bitmap.width - 1)
        val xEnd = (395 * density).roundToInt().coerceIn(xStart + 1, bitmap.width)
        val yStart = (112 * density).roundToInt().coerceIn(0, bitmap.height - 1)
        val yEnd = (520 * density).roundToInt().coerceIn(yStart + 1, bitmap.height)
        val bgPixel = bitmap.getPixel(xEnd - 2, yEnd - 2)
        val bgR = android.graphics.Color.red(bgPixel)
        val bgG = android.graphics.Color.green(bgPixel)
        val bgB = android.graphics.Color.blue(bgPixel)
        var maxDelta = 0
        var visiblePixels = 0
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                val p = bitmap.getPixel(x, y)
                val dr = android.graphics.Color.red(p) - bgR
                val dg = android.graphics.Color.green(p) - bgG
                val db = android.graphics.Color.blue(p) - bgB
                val delta = if (expectBrighter) maxOf(dr, dg, db) else maxOf(-dr, -dg, -db)
                if (delta > maxDelta) maxDelta = delta
                if (delta >= 12) visiblePixels++
            }
        }
        return maxDelta to visiblePixels
    }

    private class UiHarness(
        private val holdInitialRefresh: CompletableDeferred<Unit>? = null,
    ) {
        val settingsFlow = MutableStateFlow(AppSettings())
        val loadMoreCalls = AtomicInteger(0)
        val refreshQueries = java.util.concurrent.CopyOnWriteArrayList<ThreadListQuery>()
        @Volatile var lastRefreshQuery: ThreadListQuery? = null
        @Volatile var failNextLoadMore: Boolean = false
        private var pendingNewThreads = 0
        private val states = LinkedHashMap<ThreadListQuery, MutableStateFlow<ThreadListLoadState>>()
        private var currentRaw = (1..30).map { idx ->
            sampleSummary((1000 + idx).toString(), "论坛列表测试帖 $idx")
        }

        fun prependNewThreadsOnNextRefresh(count: Int) {
            pendingNewThreads = count
        }

        private fun stateFor(q: ThreadListQuery) = states.getOrPut(q) { MutableStateFlow(ThreadListLoadState(query = q)) }

        private val threadRepo = object : ThreadListRepository {
            override suspend fun loadPage(query: ThreadListQuery, startPage: Int, existingTids: Set<String>, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority) =
                refresh(query, minVisibleItems, maxAutoBackfillPages, priority)

            override suspend fun refresh(query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority): ThreadListLoadResult {
                refreshQueries += query
                lastRefreshQuery = query
                if (holdInitialRefresh != null) {
                    stateFor(query).value = ThreadListLoadState(query, emptyList(), 0, 0, false, 0, null, isLoading = true)
                    holdInitialRefresh.await()
                }
                if (pendingNewThreads > 0) {
                    val added = (1..pendingNewThreads).map { i ->
                        sampleSummary((9000 + i).toString(), "最新到来的帖子 $i")
                    }
                    currentRaw = added + currentRaw
                    pendingNewThreads = 0
                }
                val rawForQuery = if (query.fid == 6 && query.typeId == "1") {
                    (1..20).map { idx -> sampleSummary((6100 + idx).toString(), "手机分类测试帖 $idx") }
                } else {
                    currentRaw
                }
                val items = rawForQuery.map { ThreadListItem(it, false) }
                stateFor(query).value = ThreadListLoadState(query, items, 1, 2, true, 1, emptyMeta(), false)
                return ThreadListBatchResult(query, query.sortByPostTime == true, rawForQuery, items, 1, 2, true, 1, emptyMeta())
            }

            override suspend fun loadMore(query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority): ThreadListLoadResult {
                val seq = loadMoreCalls.incrementAndGet()
                if (failNextLoadMore) {
                    failNextLoadMore = false
                    val err = ThreadListError.NetworkError("boom")
                    stateFor(query).update { it.copy(isLoadingMore = false, error = err) }
                    return err
                }
                val extra = listOf(sampleSummary((2000 + seq).toString(), "第二页续载帖子 $seq"))
                currentRaw = (currentRaw + extra).distinctBy(ThreadSummary::tid)
                val items = currentRaw.map { ThreadListItem(it, false) }
                stateFor(query).value = ThreadListLoadState(query, items, 2, 2, false, 1, emptyMeta(), false, false, null)
                return ThreadListBatchResult(query, query.sortByPostTime == true, extra, items, 2, 2, false, 1, emptyMeta())
            }

            override fun observeThreadList(query: ThreadListQuery): Flow<ThreadListLoadState> = stateFor(query)
            override fun observeFiltered(rawThreads: Flow<List<ThreadSummary>>) = rawThreads.map { l -> l.map { ThreadListItem(it, false) } }
        }

        private val searchRepo = object : SearchRepository {
            override val searchHistory: Flow<List<String>> = MutableStateFlow(emptyList())
            override suspend fun search(query: String, author: String, forumId: Int?, fullText: Boolean, page: Int, searchId: String?, priority: AccessPriority, recordHistory: Boolean) =
                getNewPosts(page, searchId, priority)
            override suspend fun getNewPosts(page: Int, searchId: String?, priority: AccessPriority) =
                SearchResult.Success(page, false, SearchPage("sid-1", 1, emptyList(), emptyMeta()))
            override suspend fun getMyThreads(page: Int, priority: AccessPriority): MyThreadsResult = throw UnsupportedOperationException()
            override suspend fun getMyReplies(page: Int, priority: AccessPriority): MyRepliesResult = throw UnsupportedOperationException()
            override suspend fun recordSearchHistory(query: String) = emptyList<String>()
            override suspend fun removeSearchHistory(query: String): RemovedSearchHistoryEntry? = null
            override suspend fun restoreSearchHistory(query: String, index: Int) = emptyList<String>()
            override suspend fun clearSearchHistory() = Unit
            override suspend fun resolveForumLink(rawInput: String): ResolvedForumLink? = null
        }

        private val settingsRepo = object : SettingsRepository {
            override val settings: Flow<AppSettings> = settingsFlow
            override suspend fun setThemeMode(themeMode: ThemeMode) = Unit
            override suspend fun setPureBlack(enabled: Boolean) = Unit
            override suspend fun setThemeSeed(themeSeed: String) = Unit
            override suspend fun setFontStep(step: Int) = Unit
            override suspend fun setLineSpacing(lineSpacing: LineSpacing) = Unit
            override suspend fun setFollowedForums(forumIds: List<Int>) = Unit
            override suspend fun setShowStickyThreads(show: Boolean) = Unit
            override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) =
                settingsFlow.update { it.copy(sortByPostTime = it.sortByPostTime + (fid to sortByPostTime)) }
            override suspend fun setTailEnabled(enabled: Boolean) = Unit
            override suspend fun setTailText(text: String) = Unit
            override suspend fun setTailUrl(url: String) = Unit
            override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
            override suspend fun setSilentModeEnabled(enabled: Boolean) = Unit
            override suspend fun setSilentBeginMinutes(minutes: Int) = Unit
            override suspend fun setSilentEndMinutes(minutes: Int) = Unit
            override suspend fun setForumServer(server: String) = Unit
            override suspend fun setImageCacheLimitMb(limitMb: Int) = Unit
            override suspend fun setPaneSplitPortrait(split: PaneSplit) = Unit
            override suspend fun setPaneSplitLandscape(split: PaneSplit) = Unit
            override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) = Unit
            override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) = Unit
        }

        private val account = SavedAccount("42", "HiUser", "0".repeat(32), lastLoginAt = 1L)
        private val authRepo = object : AuthRepository {
            override val currentAccount: StateFlow<SavedAccount?> = MutableStateFlow(account).asStateFlow()
            override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOf(account)).asStateFlow()
            override val authState: StateFlow<AuthState> = MutableStateFlow<AuthState>(AuthState.SignedIn(account)).asStateFlow()
            override val sessionEpoch: Long = 1L
            override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) = LoginResult.Failure("offline")
            override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
            override suspend fun logout() = Unit
            override suspend fun removeAccount(uid: String) = Unit
            override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
        }

        val viewModel = ForumViewModel(
            threadListRepository = threadRepo,
            searchRepository = searchRepo,
            settingsRepository = settingsRepo,
            authRepository = authRepo,
            siteAccessGateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled }),
        )

        private fun emptyMeta() = DiscuzPageMeta(DiscuzSessionInfo(true, "42", "HiUser"), "fh", null, PromptCounts())
        private fun sampleSummary(tid: String, title: String) = ThreadSummary(
            tid, title, null, null, false, false, false, "作者$tid", tid, null,
            "2026-11-26", "刚刚", "回复者", "9", "90", false, 1,
        )
    }
}
