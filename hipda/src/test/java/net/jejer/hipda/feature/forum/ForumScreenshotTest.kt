package net.jejer.hipda.feature.forum

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Rect as AndroidRect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowInfoTrackerDecorator
import androidx.window.layout.WindowLayoutInfo
import coil3.ImageLoader
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import net.jejer.hipda.R
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
import net.jejer.hipda.data.threadlist.ThreadListBackfillStopReason
import net.jejer.hipda.data.threadlist.ThreadListBatchResult
import net.jejer.hipda.data.threadlist.ThreadListError
import net.jejer.hipda.data.threadlist.ThreadListItem
import net.jejer.hipda.data.threadlist.ThreadListLoadResult
import net.jejer.hipda.data.threadlist.ThreadListLoadState
import net.jejer.hipda.data.threadlist.ThreadListQuery
import net.jejer.hipda.data.threadlist.ThreadListRepository
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.ForumChromeSpec
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.threadlist.ThreadSummary
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiFontSizeStep
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import net.jejer.hipda.ui.designsystem.state.HiEmptyState
import net.jejer.hipda.ui.shell.ForumMetadataRegistry
import net.jejer.hipda.ui.shell.HiAdaptiveShell
import net.jejer.hipda.ui.shell.HiSidebarAccount
import net.jejer.hipda.ui.shell.HiSidebarState
import net.jejer.hipda.ui.shell.ShellDestination
import net.jejer.hipda.ui.shell.ShellPaneLayoutMode
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 论坛页核心 Roborazzi 截图基线（前缀 `forum_`，共 22 张，对齐 `E1.md` 与 `product-design.md` §10.1）：
 * - 6 宽 × 浅/深（12 张）：411×891、559×749、640×1024、749×559（<300dp 紧凑中栏）、933×696（<300dp 紧凑中栏）、1280×800（380dp 标准中栏）
 * - 特殊窗口与滚动折叠专项（4 张）：891×411 横屏矮屏、340×800 窄分屏、411×891 第 5 档字号×1.3、411×891 下滚折叠上行后透明吸顶标签行+荧光笔高光+新帖胶囊
 * - 空 / 错 / 骨架状态（6 张）：411×891 浅色与 933×696 深色
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class ForumScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private enum class ScreenMode { Content, Empty, Error, Skeleton }

    // ---- 6 宽 × 浅/深（12 张） ----

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun forum_w411_h891_light() = captureForum("forum_w411_h891_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun forum_w411_h891_dark() = captureForum("forum_w411_h891_dark", HiThemeMode.DARK)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_PORTRAIT_559_749)
    fun forum_w559_h749_light() = captureForum("forum_w559_h749_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_PORTRAIT_559_749)
    fun forum_w559_h749_dark() = captureForum("forum_w559_h749_dark", HiThemeMode.DARK)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.SIDEBAR_SINGLE_640_1024)
    fun forum_w640_h1024_light() = captureForum("forum_w640_h1024_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.SIDEBAR_SINGLE_640_1024)
    fun forum_w640_h1024_dark() = captureForum("forum_w640_h1024_dark", HiThemeMode.DARK)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun forum_w749_h559_light() = captureForum("forum_w749_h559_light", HiThemeMode.LIGHT, selectedTid = "1002")

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun forum_w749_h559_dark() = captureForum("forum_w749_h559_dark", HiThemeMode.DARK, selectedTid = "1002")

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun forum_w933_h696_light() = captureForum("forum_w933_h696_light", HiThemeMode.LIGHT, selectedTid = "1002")

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun forum_w933_h696_dark() = captureForum("forum_w933_h696_dark", HiThemeMode.DARK, selectedTid = "1002")

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun forum_w1280_h800_light() = captureForum("forum_w1280_h800_light", HiThemeMode.LIGHT, selectedTid = "1002")

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun forum_w1280_h800_dark() = captureForum("forum_w1280_h800_dark", HiThemeMode.DARK, selectedTid = "1002")

    // ---- 特殊窗口与滚动折叠专项（4 张） ----

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.COMPACT_891_411)
    fun forum_w891_h411_landscape_light() = captureForum("forum_w891_h411_landscape_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.NARROW_340_800)
    fun forum_w340_h800_narrow_light() = captureForum(
        name = "forum_w340_h800_narrow_light",
        themeMode = HiThemeMode.LIGHT,
        followedForums = listOf(2, 6, 7, 59, 12, 25),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891, fontScale = 1.3f)
    fun forum_w411_h891_step5_font130_light() = captureForum(
        name = "forum_w411_h891_step5_font130_light",
        themeMode = HiThemeMode.LIGHT,
        fontSizeStep = HiFontSizeStep.STEP_5,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun forum_w411_h891_scrolled_collapsed_light() = captureForum(
        name = "forum_w411_h891_scrolled_collapsed_light",
        themeMode = HiThemeMode.LIGHT,
        scrollToCollapseTopBar = true,
        triggerNewThreadsPill = 3,
    )

    // ---- 空 / 错 / 骨架 411 & 933dp（6 张） ----

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun forum_w411_h891_empty_light() = captureForum("forum_w411_h891_empty_light", HiThemeMode.LIGHT, mode = ScreenMode.Empty)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun forum_w411_h891_error_light() = captureForum("forum_w411_h891_error_light", HiThemeMode.LIGHT, mode = ScreenMode.Error)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun forum_w411_h891_skeleton_light() = captureForum("forum_w411_h891_skeleton_light", HiThemeMode.LIGHT, mode = ScreenMode.Skeleton)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun forum_w933_h696_empty_dark() = captureForum("forum_w933_h696_empty_dark", HiThemeMode.DARK, mode = ScreenMode.Empty)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun forum_w933_h696_error_dark() = captureForum("forum_w933_h696_error_dark", HiThemeMode.DARK, mode = ScreenMode.Error)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun forum_w933_h696_skeleton_dark() = captureForum("forum_w933_h696_skeleton_dark", HiThemeMode.DARK, mode = ScreenMode.Skeleton)

    // ---- 铰链双栏纯色顶栏基线（闭合 V-E1 F2，2 张） ----

    @Test
    @Config(qualifiers = "w860dp-h744dp-xhdpi")
    fun forum_hinge_w860_h744_light() = captureHingeForum("forum_hinge_w860_h744_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = "w860dp-h744dp-xhdpi")
    fun forum_hinge_w860_h744_dark() = captureHingeForum("forum_hinge_w860_h744_dark", HiThemeMode.DARK)

    // ---- M7-G1 Buy & Sell (fid=6) 分类筛选顶栏新增截图（2 张，供 R 目视浅/深色及选中态） ----

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun forum_bs_filter_w411_light() = captureForum(
        name = "forum_bs_filter_w411_light",
        themeMode = HiThemeMode.LIGHT,
        source = ForumListSource.Forum(BUY_AND_SELL_FID),
        initialTypeId = "1",
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun forum_bs_filter_w411_dark() = captureForum(
        name = "forum_bs_filter_w411_dark",
        themeMode = HiThemeMode.DARK,
        source = ForumListSource.Forum(BUY_AND_SELL_FID),
        initialTypeId = null,
    )

    @Suppress("RestrictedApi")
    private fun captureHingeForum(name: String, themeMode: HiThemeMode) {
        val density = 2f
        val hingeFeature = FakeFoldingFeature(
            bounds = AndroidRect((426 * density).roundToInt(), 0, (434 * density).roundToInt(), (744 * density).roundToInt()),
            orientation = FoldingFeature.Orientation.VERTICAL,
            state = FoldingFeature.State.HALF_OPENED,
            isSeparating = true,
        )
        val layoutInfo = WindowLayoutInfo(listOf(hingeFeature))
        val decorator = object : WindowInfoTrackerDecorator {
            override fun decorate(tracker: WindowInfoTracker): WindowInfoTracker = object : WindowInfoTracker {
                override fun windowLayoutInfo(activity: Activity): Flow<WindowLayoutInfo> = flowOf(layoutInfo)
                override fun windowLayoutInfo(context: Context): Flow<WindowLayoutInfo> = flowOf(layoutInfo)
            }
        }
        WindowInfoTracker.overrideDecorator(decorator)
        try {
            captureForum(name = name, themeMode = themeMode, expectHingeSolidTopBar = true)
        } finally {
            WindowInfoTracker.reset()
        }
    }

    private fun captureForum(
        name: String,
        themeMode: HiThemeMode,
        fontSizeStep: HiFontSizeStep = HiFontSizeStep.DEFAULT,
        followedForums: List<Int> = ForumMetadataRegistry.DefaultFollowedForumIds,
        selectedTid: String? = null,
        mode: ScreenMode = ScreenMode.Content,
        scrollToCollapseTopBar: Boolean = false,
        triggerNewThreadsPill: Int = 0,
        expectHingeSolidTopBar: Boolean = false,
        source: ForumListSource = ForumListSource.Forum(2),
        initialTypeId: String? = null,
    ) {
        val harness = ScreenshotHarness(mode, followedForums)
        val offlineLoader = ImageLoader.Builder(ApplicationProvider.getApplicationContext()).build()
        var touchSlop = 0f
        var listState: LazyListState? = null
        var latestLayoutMode: ShellPaneLayoutMode? = null
        var latestUseGlassTopBar: Boolean? = null

        try {
            if (mode == ScreenMode.Skeleton) {
                composeRule.mainClock.autoAdvance = false
            }
            composeRule.setContent {
                touchSlop = LocalViewConfiguration.current.touchSlop
                HiTheme(
                    themeMode = themeMode,
                    seed = HiColorPresets.Brand.toSeed(),
                    fontSizeStep = fontSizeStep,
                ) {
                    CompositionLocalProvider(LocalHiImageLoader provides offlineLoader) {
                        HiAdaptiveShell(
                            selected = ShellDestination.Forum,
                            onSelect = {},
                            sidebarState = HiSidebarState(
                                selectedEntry = if (source is ForumListSource.Forum) ShellSidebarEntry.Forum(source.fid) else ShellSidebarEntry.TodayNew,
                                followedForumIds = followedForums,
                                unreadSmsCount = 2,
                                unreadNotifyCount = 5,
                                account = HiSidebarAccount(username = "HiPDA"),
                            ),
                            managesGlassSource = { it == ShellDestination.Forum },
                        ) { _, padding ->
                            val state = rememberLazyListState()
                            listState = state
                            val chromeSpec = ForumChromeSpec(
                                showForumTabs = !paneLayout.isSidebar,
                                useGlassTopBar = paneLayout.layoutMode != ShellPaneLayoutMode.HingeTwoPane,
                                externalGlassState = shellGlassState,
                            )
                            latestLayoutMode = paneLayout.layoutMode
                            latestUseGlassTopBar = chromeSpec.useGlassTopBar
                            val forumSlot: @Composable (Modifier) -> Unit = { slotModifier ->
                                ForumPane(
                                    source = source,
                                    chromeSpec = chromeSpec,
                                    selectedTid = selectedTid,
                                    onSelectSource = {},
                                    onOpenThread = {},
                                    onOpenLogin = {},
                                    modifier = slotModifier,
                                    listState = state,
                                    contentPadding = padding,
                                    shellContentScope = this,
                                    viewModel = harness.viewModel,
                                )
                            }
                            if (paneLayout.hasDetailPane) {
                                val hingeWidthDp = paneLayout.hingeBoundsDp?.let { (it.right - it.left).coerceAtLeast(0f).dp } ?: 0.dp
                                Row(Modifier.fillMaxSize()) {
                                    forumSlot(Modifier.width(paneLayout.middlePaneWidthDp.dp).fillMaxHeight())
                                    if (hingeWidthDp > 0.dp) {
                                        Spacer(Modifier.width(hingeWidthDp))
                                    } else {
                                        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                                    }
                                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                        HiEmptyState(stringResource(R.string.shell_empty_select_thread))
                                    }
                                }
                            } else {
                                forumSlot(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            if (mode == ScreenMode.Skeleton) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.mainClock.advanceTimeByFrame()
            } else {
                composeRule.waitForIdle()
            }

            if (initialTypeId != null) {
                composeRule.runOnIdle { harness.viewModel.selectTypeId(initialTypeId) }
                composeRule.waitForIdle()
            }

            if (scrollToCollapseTopBar) {
                val collapsePx = with(composeRule.density) { FORUM_TOP_BAR_TITLE_ROW_HEIGHT.toPx() }
                composeRule.onNode(
                    SemanticsMatcher("hasVerticalScrollAxisRange") { SemanticsProperties.VerticalScrollAxisRange in it.config },
                ).performTouchInput {
                    down(center)
                    moveBy(Offset(0f, -touchSlop))
                    moveBy(Offset(0f, -(collapsePx + 140f)))
                    advanceEventTime(200L)
                    moveBy(Offset.Zero)
                    up()
                }
                composeRule.waitForIdle()
            }

            if (triggerNewThreadsPill > 0) {
                harness.prependNewThreads(triggerNewThreadsPill)
                composeRule.runOnIdle { harness.viewModel.refresh() }
                composeRule.waitForIdle()
                // 保持在滚动位置以便同框展示吸顶荧光笔标签行与新帖胶囊
                if (scrollToCollapseTopBar) {
                    val state = requireNotNull(listState)
                    composeRule.runOnIdle { state.requestScrollToItem(3, 24) }
                    composeRule.waitForIdle()
                }
            }

            if (expectHingeSolidTopBar) {
                assertEquals(ShellPaneLayoutMode.HingeTwoPane, latestLayoutMode)
                assertFalse("铰链双栏下论坛顶栏必须禁用玻璃降级为纯色", requireNotNull(latestUseGlassTopBar))
                val glassNodes = composeRule.onAllNodes(
                    SemanticsMatcher.expectValue(HiGlassMarker, true),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                assertEquals("铰链双栏应仅底部 CompactPill 为 1 块玻璃，纯色顶栏 0 玻璃节点", 1, glassNodes.size)
            }
            composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
            composeRule.onRoot().captureHiSnapshot(name)
        } finally {
            if (mode == ScreenMode.Skeleton) {
                composeRule.mainClock.autoAdvance = true
            }
            offlineLoader.shutdown()
        }
    }

    private class ScreenshotHarness(
        private val mode: ScreenMode,
        followedForums: List<Int>,
    ) {
        private val pendingGate = CompletableDeferred<Unit>()
        private val states = LinkedHashMap<ThreadListQuery, MutableStateFlow<ThreadListLoadState>>()
        private var currentItems: List<ThreadListItem> = sampleForumItems()
        private var extraNewCount = 0

        fun prependNewThreads(count: Int) {
            extraNewCount = count
        }

        private fun stateFor(q: ThreadListQuery) = states.getOrPut(q) { MutableStateFlow(ThreadListLoadState(query = q)) }

        private val threadRepo = object : ThreadListRepository {
            override suspend fun loadPage(query: ThreadListQuery, startPage: Int, existingTids: Set<String>, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority) =
                refresh(query, minVisibleItems, maxAutoBackfillPages, priority)

            override suspend fun refresh(query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority): ThreadListLoadResult {
                when (mode) {
                    ScreenMode.Skeleton -> {
                        stateFor(query).value = ThreadListLoadState(query, emptyList(), 0, 0, false, 0, null, isLoading = true)
                        pendingGate.await()
                        return ThreadListError.NetworkError("cancelled")
                    }
                    ScreenMode.Empty -> {
                        stateFor(query).value = ThreadListLoadState(query, emptyList(), 1, 1, false, 1, emptyMeta(), isLoading = false, stopReason = ThreadListBackfillStopReason.EMPTY_PAGE)
                        return ThreadListBatchResult(query, false, emptyList(), emptyList(), 1, 1, false, 1, emptyMeta(), ThreadListBackfillStopReason.EMPTY_PAGE)
                    }
                    ScreenMode.Error -> {
                        val err = ThreadListError.NetworkError("SocketTimeoutException: connect timed out")
                        stateFor(query).value = ThreadListLoadState(query, emptyList(), 0, 0, false, 0, null, isLoading = false, error = err)
                        return err
                    }
                    ScreenMode.Content -> {
                        if (extraNewCount > 0) {
                            val prepended = (1..extraNewCount).map { i ->
                                ThreadListItem(
                                    summary = ThreadSummary(
                                        tid = (9500 + i).toString(),
                                        title = "刚刚发布的新讨论主题 $i：折叠屏液态玻璃体验汇总",
                                        titleColor = null,
                                        typeName = "讨论",
                                        isSticky = false,
                                        isNew = true,
                                        isPoll = false,
                                        author = "新帖用户$i",
                                        authorId = "${900 + i}",
                                        avatarUrl = null,
                                        timeCreate = "2026-11-26",
                                        timeUpdate = "刚刚",
                                        lastPostAuthor = "新帖用户$i",
                                        replyCountText = "$i",
                                        viewCountText = "${i * 15}",
                                        withPic = false,
                                        maxPage = 1,
                                    ),
                                    isVisited = false,
                                )
                            }
                            currentItems = currentItems.take(1) + prepended + currentItems.drop(1)
                            extraNewCount = 0
                        }
                        stateFor(query).value = ThreadListLoadState(query, currentItems, 1, 3, true, 1, emptyMeta(), isLoading = false)
                        return ThreadListBatchResult(query, false, currentItems.map(ThreadListItem::summary), currentItems, 1, 3, true, 1, emptyMeta())
                    }
                }
            }

            override suspend fun loadMore(query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority) =
                refresh(query, minVisibleItems, maxAutoBackfillPages, priority)

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
            override val settings: Flow<AppSettings> = MutableStateFlow(AppSettings(followedForums = followedForums))
            override suspend fun setThemeMode(themeMode: ThemeMode) = Unit
            override suspend fun setPureBlack(enabled: Boolean) = Unit
            override suspend fun setThemeSeed(themeSeed: String) = Unit
            override suspend fun setFontStep(step: Int) = Unit
            override suspend fun setLineSpacing(lineSpacing: LineSpacing) = Unit
            override suspend fun setFollowedForums(forumIds: List<Int>) = Unit
            override suspend fun setShowStickyThreads(show: Boolean) = Unit
            override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) = Unit
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

        private val account = SavedAccount("42", "HiPDA", "0".repeat(32), lastLoginAt = 1L)
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

        private fun emptyMeta() = DiscuzPageMeta(DiscuzSessionInfo(true, "42", "HiPDA"), "fh", null, PromptCounts())

        private fun sampleForumItems(): List<ThreadListItem> = listOf(
            ThreadListItem(
                ThreadSummary("1001", "【公告】2026 冬季版规更新与客户端使用建议汇总", null, "公告", true, false, false, "站务组", "1", null, "2026-11-20", "10分钟前", "站务组", "128", "6.4k", false, 5),
                isVisited = false,
            ),
            ThreadListItem(
                ThreadSummary("1002", "Galaxy Z Fold8 展开态三栏阅读排版与 120Hz 流畅度实测记录", null, "数码", false, true, false, "linus_cn", "2", null, "2026-11-26", "刚刚", "mira", "64", "2180", true, 3),
                isVisited = false,
            ),
            ThreadListItem(
                ThreadSummary("1003", "已读标题对比度校准样例：深色与 OLED 纯黑模式下与未读清晰可辨", null, null, false, false, false, "old_timer", "3", null, "2026-11-25", "18分钟前", "kane", "31", "940", false, 2),
                isVisited = true,
            ),
            ThreadListItem(
                ThreadSummary("1004", "周末大家最常看哪几个版块？来投个票统计下默认关注排序", null, "投票", false, false, true, "poll_host", "4", null, "2026-11-25", "35分钟前", "yuki", "89", "3120", false, 4),
                isVisited = false,
            ),
            ThreadListItem(
                ThreadSummary("1005", "秋日山脊徒步实拍：富士直出色彩与便携三脚架选购心得", null, "摄影", false, false, false, "shutter_bug", "5", null, "2026-11-25", "1小时前", "lens", "45", "1620", true, 2),
                isVisited = true,
            ),
            ThreadListItem(
                ThreadSummary("1006", "低功耗 HomeLab 静音机箱风道改造与硬盘休眠功耗实测", null, "折腾", false, false, false, "nas_builder", "6", null, "2026-11-24", "2小时前", "dev", "72", "2850", true, 3),
                isVisited = false,
            ),
            ThreadListItem(
                ThreadSummary("1007", "手冲耶加雪菲水温分段萃取笔记：浅烘豆风味平衡实践", null, "生活", false, false, false, "coffee_note", "7", null, "2026-11-24", "3小时前", "barista", "19", "710", false, 1),
                isVisited = false,
            ),
            ThreadListItem(
                ThreadSummary("1008", "客制化机械键盘消音棉与卫星轴润脂对比记录（附实测录音）", null, null, false, false, false, "keycap_fan", "8", null, "2026-11-24", "5小时前", "tactile", "53", "1940", true, 2),
                isVisited = true,
            ),
            ThreadListItem(
                ThreadSummary("1009", "长途自驾秋季赏枫路线与沿途补能服务区实测攻略", null, "出行", false, false, false, "roadtrip", "9", null, "2026-11-23", "昨天", "driver", "38", "1450", true, 2),
                isVisited = false,
            ),
            ThreadListItem(
                ThreadSummary("1010", "今年读完最推荐的五本非虚构电子书与墨水屏排版心得", null, "读书", false, false, false, "eink_reader", "10", null, "2026-11-23", "昨天", "page", "27", "880", false, 1),
                isVisited = false,
            ),
            ThreadListItem(
                ThreadSummary("1011", "家庭影院客厅低频驻波测量与简易声学吸音处理记录", null, "影音", false, false, false, "audio_lab", "11", null, "2026-11-22", "前天", "hifi", "41", "1520", false, 2),
                isVisited = false,
            ),
            ThreadListItem(
                ThreadSummary("1012", "冬季慢跑心率区间训练复盘与保暖速干装备分享", null, "运动", false, false, false, "runner_01", "12", null, "2026-11-22", "前天", "pace", "22", "690", false, 1),
                isVisited = true,
            ),
        )
    }

    private class FakeFoldingFeature(
        override val bounds: AndroidRect,
        override val orientation: FoldingFeature.Orientation,
        override val state: FoldingFeature.State,
        override val isSeparating: Boolean,
        override val occlusionType: FoldingFeature.OcclusionType = FoldingFeature.OcclusionType.FULL,
    ) : FoldingFeature
}
