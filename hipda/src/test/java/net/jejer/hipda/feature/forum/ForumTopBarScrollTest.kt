package net.jejer.hipda.feature.forum

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
import net.jejer.hipda.testing.RecompositionCounter
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.designsystem.glass.hiGlassSource
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.shell.HiAdaptiveShell
import net.jejer.hipda.ui.shell.ShellDestination
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ForumTopBarScrollTest {

    /**
     * 验收 2（B4-E1 ②）：
     * (a) 列表向下滚动折叠上行后，版块标签行仍保持可见吸顶；略微上划立即重新展开上行；
     * (b) 展开态与完全折叠态下 LazyColumn 的 contentPadding(top)（beforeContentPadding）差值严格 == 0dp。
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun scrollDownCollapsesUpperRow_keepsTabRowSticky_zeroTopPaddingDiff_andUpScrollExpands() =
        withAnimatorDurationScale(1f) {
            val vm = createForumViewModel()
            var listState: LazyListState? = null
            var touchSlop = 0f
            var activeSource by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))

            setContent {
                touchSlop = LocalViewConfiguration.current.touchSlop
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val state = rememberLazyListState()
                    listState = state
                    ForumPane(
                        source = activeSource,
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = true),
                        selectedTid = null,
                        onSelectSource = { activeSource = it },
                        onOpenThread = {},
                        onOpenLogin = {},
                        listState = state,
                        viewModel = vm,
                    )
                }
            }
            waitForIdle()

            val lazyState = requireNotNull(listState)
            val expandedTopPaddingPx = lazyState.layoutInfo.beforeContentPadding
            val expandedTabTop = onNodeWithText("今日新帖").fetchSemanticsNode().boundsInRoot.top
            val titleRowHeightPx = with(density) { FORUM_TOP_BAR_TITLE_ROW_HEIGHT.toPx() }
            assertTrue("展开态 contentPadding(top) 应涵盖完整顶栏高度", expandedTopPaddingPx >= titleRowHeightPx)

            // 向下滚动列表以完全折叠上行标题与动作行
            onNode(verticalScrollMatcher()).performTouchInput {
                down(center)
                moveBy(Offset(0f, -touchSlop))
                moveBy(Offset(0f, -(titleRowHeightPx + 120f)))
                advanceEventTime(200L)
                moveBy(Offset.Zero)
                up()
            }
            waitForIdle()

            // (a) 上行收起后，版块标签行吸顶且保持可见
            val collapsedTabBounds = onNodeWithText("今日新帖").fetchSemanticsNode().boundsInRoot
            onNodeWithText("今日新帖").assertIsDisplayed()
            onNodeWithContentDescription("管理版块").assertIsDisplayed()
            assertEquals(
                "上行完全折叠后标签行应上移恰好等于上行高度并吸顶",
                expandedTabTop - titleRowHeightPx,
                collapsedTabBounds.top,
                2f,
            )
            // 上行标题按钮已移出可视裁切区
            val collapsedNewThreadBottom = onNodeWithContentDescription("发新帖").fetchSemanticsNode().boundsInRoot.bottom
            assertTrue("完全折叠后上行动作应已收起，实际 bottom=$collapsedNewThreadBottom", collapsedNewThreadBottom <= 1f)

            // (b) 展开态与完全折叠态下 LazyColumn 的 contentPadding(top) 差值严格 == 0dp
            val collapsedTopPaddingPx = lazyState.layoutInfo.beforeContentPadding
            assertEquals(
                "B4-E1 ②：展开态与折叠态 LazyColumn contentPadding(top) 差值必须严格为 0px",
                expandedTopPaddingPx,
                collapsedTopPaddingPx,
            )

            // 略微上划立即重新展开上行
            onNode(verticalScrollMatcher()).performTouchInput {
                down(center)
                moveBy(Offset(0f, touchSlop))
                moveBy(Offset(0f, titleRowHeightPx + 40f))
                advanceEventTime(200L)
                moveBy(Offset.Zero)
                up()
            }
            waitForIdle()

            val reExpandedTabTop = onNodeWithText("今日新帖").fetchSemanticsNode().boundsInRoot.top
            assertEquals("略微上划后上行应重新展开，标签行回到原位", expandedTabTop, reExpandedTabTop, 2f)
            onNodeWithContentDescription("发新帖").assertIsDisplayed()
            assertEquals(expandedTopPaddingPx, lazyState.layoutInfo.beforeContentPadding)

            // 折叠态下切换版块标签自动恢复展开
            onNode(verticalScrollMatcher()).performTouchInput {
                down(center)
                moveBy(Offset(0f, -touchSlop))
                moveBy(Offset(0f, -(titleRowHeightPx + 120f)))
                advanceEventTime(200L)
                moveBy(Offset.Zero)
                up()
            }
            waitForIdle()
            assertEquals(expandedTabTop - titleRowHeightPx, onNodeWithText("今日新帖").fetchSemanticsNode().boundsInRoot.top, 2f)

            onNodeWithText("Buy & Sell").performClick()
            waitForIdle()
            assertEquals("切换版块后上行应自动恢复展开", expandedTabTop, onNodeWithText("今日新帖").fetchSemanticsNode().boundsInRoot.top, 2f)
        }

    /** 验收 2 (c)：减少动态效果下顶栏上行折叠吸附与展开 0ms 一帧到位，无残留中间态。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun reducedMotion_topBarSnapCollapseAndExpandCompleteInOneFrame() = withAnimatorDurationScale(0f) {
        val vm = createForumViewModel()
        var touchSlop = 0f

        setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ForumPane(
                    source = ForumListSource.Forum(2),
                    chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = true),
                    selectedTid = null,
                    onSelectSource = {},
                    onOpenThread = {},
                    onOpenLogin = {},
                    viewModel = vm,
                )
            }
        }
        waitForIdle()

        val titleRowHeightPx = with(density) { FORUM_TOP_BAR_TITLE_ROW_HEIGHT.toPx() }
        val expandedTabTop = onNodeWithText("今日新帖").fetchSemanticsNode().boundsInRoot.top

        // 向下拖动 65%（未满 100%）松手：减动效下首帧即吸附至 100% 完全折叠
        onNode(verticalScrollMatcher()).performTouchInput {
            down(center)
            moveBy(Offset(0f, -touchSlop))
            moveBy(Offset(0f, -(titleRowHeightPx * 0.65f)))
            advanceEventTime(200L)
            moveBy(Offset.Zero)
        }
        waitForIdle()

        mainClock.autoAdvance = false
        try {
            onNode(verticalScrollMatcher()).performTouchInput { up() }
            mainClock.advanceTimeByFrame()
            val snappedTop = onNodeWithText("今日新帖").fetchSemanticsNode().boundsInRoot.top
            assertEquals(
                "减动效下 > 50% 松手必须一帧到位至完全折叠，无残留中间态",
                expandedTabTop - titleRowHeightPx,
                snappedTop,
                1.5f,
            )
        } finally {
            mainClock.autoAdvance = true
        }

        // 再向上拖回 65% 松手：减动效下首帧即吸附回 0% 完全展开
        onNode(verticalScrollMatcher()).performTouchInput {
            down(center)
            moveBy(Offset(0f, touchSlop))
            moveBy(Offset(0f, titleRowHeightPx * 0.65f))
            advanceEventTime(200L)
            moveBy(Offset.Zero)
        }
        waitForIdle()

        mainClock.autoAdvance = false
        try {
            onNode(verticalScrollMatcher()).performTouchInput { up() }
            mainClock.advanceTimeByFrame()
            val expandedBackTop = onNodeWithText("今日新帖").fetchSemanticsNode().boundsInRoot.top
            assertEquals(
                "减动效下 <= 50% 松手必须一帧到位至完全展开",
                expandedTabTop,
                expandedBackTop,
                1.5f,
            )
        } finally {
            mainClock.autoAdvance = true
        }
    }

    /** 验收 5：单栏与外壳胶囊协同满足 HiGlassMarker ≤ 2、顶栏与 LazyColumn 严格兄弟拓扑、铰链双栏顶栏 0 玻璃节点。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun glassTopology_singlePaneAtMostTwoStrictSibling_andHingeTopBarZeroGlass() = withAnimatorDurationScale(0f) {
        val vm = createForumViewModel()
        var useGlassTopBar by mutableStateOf(true)

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                if (useGlassTopBar) {
                    HiAdaptiveShell(
                        selected = ShellDestination.Forum,
                        onSelect = {},
                        managesGlassSource = { it == ShellDestination.Forum },
                    ) { _, padding ->
                        ForumPane(
                            source = ForumListSource.Forum(2),
                            chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = true, externalGlassState = shellGlassState),
                            selectedTid = null,
                            onSelectSource = {},
                            onOpenThread = {},
                            onOpenLogin = {},
                            contentPadding = padding,
                            shellContentScope = this,
                            viewModel = vm,
                        )
                    }
                } else {
                    ForumPane(
                        source = ForumListSource.Forum(2),
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                        selectedTid = null,
                        onSelectSource = {},
                        onOpenThread = {},
                        onOpenLogin = {},
                        viewModel = vm,
                    )
                }
            }
        }
        waitForIdle()

        // 1. 单栏 + 外壳底部胶囊：玻璃区域总数严格 <= 2（顶栏 1 + 胶囊 1）
        assertGlassSurfaceCountAtMost(2)
        val glassNodes = onAllNodes(SemanticsMatcher.expectValue(HiGlassMarker, true), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("单栏论坛页 + 底部胶囊应恰好 2 个玻璃区域", 2, glassNodes.size)

        // 2. 顶栏与 LazyColumn 严格兄弟节点：LayerBackdrop 仅在 LazyColumn 上，且绝非任一 HiGlassMarker 的祖先或后代
        val scrollNode = onNode(verticalScrollMatcher()).fetchSemanticsNode()
        val scrollHasBackdrop = scrollNode.layoutInfo.getModifierInfo().any {
            it.modifier.javaClass.name.contains("LayerBackdrop", ignoreCase = true)
        }
        assertTrue("LazyColumn 必须挂载 LayerBackdrop 采样源", scrollHasBackdrop)
        glassNodes.forEach { glassNode ->
            assertFalse(
                "顶栏/胶囊玻璃节点不得是 LazyColumn 的后代（严禁循环采样）",
                isDescendantOf(candidate = glassNode.layoutInfo, ancestor = scrollNode.layoutInfo),
            )
            assertFalse(
                "LazyColumn 不得是玻璃节点的后代（严禁循环采样）",
                isDescendantOf(candidate = scrollNode.layoutInfo, ancestor = glassNode.layoutInfo),
            )
        }

        // 3. 铰链双栏（useGlassTopBar = false）：顶栏 0 玻璃节点
        useGlassTopBar = false
        Snapshot.sendApplyNotifications()
        waitForIdle()
        assertGlassSurfaceCountAtMost(0)
        onNodeWithText("今日新帖").assertIsDisplayed()
    }

    /** 验收 5：列表持续滚动折叠/展开期间，顶栏与列表项 0 冗余重组。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun continuousScroll_topBarAndRowsHaveZeroRedundantRecompositions() = withAnimatorDurationScale(1f) {
        val topBarCounter = RecompositionCounter("ForumTopBar")
        val rowCounters = LinkedHashMap<String, RecompositionCounter>()
        val itemsList = (1..40).map { idx -> ThreadListItem(sampleSummary("30$idx", "性能门禁帖子标题 $idx"), isVisited = idx % 3 == 0) }
        var touchSlop = 0f

        setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val listState = rememberLazyListState()
                val topBarScrollState = rememberForumTopBarScrollState(collapsible = true)
                val glassState = rememberHiGlassState()
                val topPadding = forumTopBarExpandedHeight(showForumTabs = true)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(topBarScrollState.nestedScrollConnection),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .hiGlassSource(glassState)
                            .testTag("perf_list"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = topPadding),
                    ) {
                        items(items = itemsList, key = { it.tid }) { item ->
                            val counter = rowCounters.getOrPut(item.tid) { RecompositionCounter("Row-${item.tid}") }
                            counter.Track()
                            ThreadListRow(
                                item = item,
                                selected = false,
                                compactLayout = false,
                                onClick = {},
                            )
                        }
                    }
                    TrackedTopBar(
                        counter = topBarCounter,
                        glassState = glassState,
                        scrollState = topBarScrollState,
                    )
                }
            }
        }
        waitForIdle()

        val topBarCountAfterInit = topBarCounter.count
        val rowCountsBeforeScroll = rowCounters.mapValues { it.value.count }

        // 连续多步向下拖动滚动再向上拖回
        onNodeWithTag("perf_list").performTouchInput {
            down(center)
            moveBy(Offset(0f, -touchSlop))
            repeat(6) {
                moveBy(Offset(0f, -40f), delayMillis = 16L)
            }
            advanceEventTime(200L)
            up()
        }
        waitForIdle()

        onNodeWithTag("perf_list").performTouchInput {
            down(center)
            moveBy(Offset(0f, touchSlop))
            repeat(6) {
                moveBy(Offset(0f, 40f), delayMillis = 16L)
            }
            advanceEventTime(200L)
            up()
        }
        waitForIdle()

        assertEquals("持续滚动折叠与展开期间 ForumTopBar 不得发生冗余重组", topBarCountAfterInit, topBarCounter.count)
        // 仍在视口内的已有行不得因滚动位移而重组
        rowCountsBeforeScroll.forEach { (tid, beforeCount) ->
            val afterCount = rowCounters.getValue(tid).count
            assertEquals("列表项 $tid 在持续滚动中不得发生冗余重组", beforeCount, afterCount)
        }
    }

    @Composable
    private fun TrackedTopBar(
        counter: RecompositionCounter,
        glassState: net.jejer.hipda.ui.designsystem.glass.HiGlassState,
        scrollState: ForumTopBarScrollState,
    ) {
        counter.Track()
        ForumTopBar(
            title = "Discovery",
            source = ForumListSource.Forum(2),
            followedForums = listOf(2, 6, 7),
            sortByPostTime = false,
            showForumTabs = true,
            glassState = glassState,
            isScrolled = { true },
            scrollState = scrollState,
            onSelectSource = {},
            onNewThreadClick = {},
            onSelectSortByPostTime = {},
            onManageForumsClick = {},
        )
    }

    private fun verticalScrollMatcher(): SemanticsMatcher =
        SemanticsMatcher("hasVerticalScrollAxisRange") { SemanticsProperties.VerticalScrollAxisRange in it.config }

    private fun isDescendantOf(candidate: LayoutInfo, ancestor: LayoutInfo): Boolean {
        var cur: LayoutInfo? = candidate
        while (cur != null) {
            if (cur === ancestor) return true
            cur = cur.parentInfo
        }
        return false
    }

    private fun createForumViewModel(): ForumViewModel {
        val itemsByFid = mapOf(
            2 to (1..30).map { sampleSummary("20$it", "Discovery 滚动验证帖子 $it") },
            6 to (1..30).map { sampleSummary("60$it", "Buy & Sell 帖子 $it") },
        )
        val states = LinkedHashMap<ThreadListQuery, MutableStateFlow<ThreadListLoadState>>()
        val threadRepo = object : ThreadListRepository {
            private fun stateFor(q: ThreadListQuery) = states.getOrPut(q) { MutableStateFlow(ThreadListLoadState(query = q)) }
            override suspend fun loadPage(query: ThreadListQuery, startPage: Int, existingTids: Set<String>, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority) =
                refresh(query, minVisibleItems, maxAutoBackfillPages, priority)
            override suspend fun refresh(query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority): ThreadListLoadResult {
                val raw = itemsByFid[query.fid].orEmpty()
                val items = raw.map { ThreadListItem(it, false) }
                stateFor(query).value = ThreadListLoadState(query, items, 1, 2, true, 1, emptyMeta(), false)
                return ThreadListBatchResult(query, query.sortByPostTime == true, raw, items, 1, 2, true, 1, emptyMeta())
            }
            override suspend fun loadMore(query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority) =
                refresh(query, minVisibleItems, maxAutoBackfillPages, priority)
            override fun observeThreadList(query: ThreadListQuery): Flow<ThreadListLoadState> = stateFor(query)
            override fun observeFiltered(rawThreads: Flow<List<ThreadSummary>>) = rawThreads.map { l -> l.map { ThreadListItem(it, false) } }
        }
        val searchRepo = object : SearchRepository {
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
        val settingsFlow = MutableStateFlow(AppSettings())
        val settingsRepo = object : SettingsRepository {
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
        val account = SavedAccount("42", "HiUser", "0".repeat(32), lastLoginAt = 1L)
        val authRepo = object : AuthRepository {
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
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        return ForumViewModel(threadRepo, searchRepo, settingsRepo, authRepo, gateway)
    }

    private fun emptyMeta() = DiscuzPageMeta(DiscuzSessionInfo(true, "42", "HiUser"), "fh", null, PromptCounts())

    private fun sampleSummary(tid: String, title: String) = ThreadSummary(
        tid = tid,
        title = title,
        titleColor = null,
        typeName = null,
        isSticky = false,
        isNew = false,
        isPoll = false,
        author = "作者$tid",
        authorId = tid,
        avatarUrl = null,
        timeCreate = "2026-11-26",
        timeUpdate = "刚刚",
        lastPostAuthor = "回复者",
        replyCountText = "8",
        viewCountText = "88",
        withPic = false,
        maxPage = 1,
    )
}
