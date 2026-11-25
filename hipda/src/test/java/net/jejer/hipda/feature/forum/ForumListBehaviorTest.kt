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
import androidx.compose.ui.test.performClick
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

        val compactTitleBounds = composeRule.onNodeWithText(sample.summary.title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val compactMetaTime = composeRule.onNodeWithText("5分钟前", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val compactPanePx = with(composeRule.density) { 274.dp.toPx() }
        val compactTitleLeftDp = with(composeRule.density) { compactTitleBounds.left.toDp() }
        assertTrue("274dp 窄中栏下标题左侧不应有 40dp 大头像列占位，实际 left=$compactTitleLeftDp", compactTitleLeftDp <= 16.dp)
        assertTrue("274dp 窄中栏下时间标签不得溢出右边界", compactMetaTime.right <= compactPanePx + 1f)
        composeRule.onNodeWithText("42/1280", useUnmergedTree = true).assertIsDisplayed()

        // 切到 380dp 标准宽：左侧应出现 40dp 独立头像列，标题起始位置显著右移
        paneWidthDp = 380.dp
        composeRule.waitForIdle()
        val wideTitleLeftDp = with(composeRule.density) {
            composeRule.onNodeWithText(sample.summary.title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left.toDp()
        }
        assertTrue("380dp 标准宽下标题应让出左侧 40dp 头像列，实际 left=$wideTitleLeftDp", wideTitleLeftDp >= 60.dp)
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
                val items = currentRaw.map { ThreadListItem(it, false) }
                stateFor(query).value = ThreadListLoadState(query, items, 1, 2, true, 1, emptyMeta(), false)
                return ThreadListBatchResult(query, query.sortByPostTime == true, currentRaw, items, 1, 2, true, 1, emptyMeta())
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
