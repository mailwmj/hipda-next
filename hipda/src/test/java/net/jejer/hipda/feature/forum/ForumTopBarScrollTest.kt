package net.jejer.hipda.feature.forum

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.IdentifiableRecomposeScope
import androidx.compose.runtime.tooling.LocalInspectionTables
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.robolectric.annotation.GraphicsMode
import java.util.Collections
import java.util.IdentityHashMap
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

    /**
     * 验收 5（闭合 R-E1 S1）：挂载真实 ForumPane，通过 Recomposer.observe + CompositionObserver
     * 同时监控外层宿主与所有内层作用域（ForumPane / BoxWithConstraints / ForumTopBar / ForumTopBarBackground /
     * ForumTopBarUpperRow / BalancedForumTabRow / 已见 ThreadListRow），验证持续滚动折叠与展开期间 0 冗余重组。
     */
    @OptIn(ComposeToolingApi::class, ExperimentalComposeRuntimeApi::class)
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun continuousScroll_topBarAndRowsHaveZeroRedundantRecompositions() = withAnimatorDurationScale(1f) {
        val vm = createForumViewModel(itemCount = 50)
        val hostCounter = RecompositionCounter("ForumPaneHost")
        val inspectionTables = Collections.newSetFromMap(IdentityHashMap<CompositionData, Boolean>())
        var listState: LazyListState? = null
        var touchSlop = 0f

        setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            currentComposer.collectParameterInformation()
            inspectionTables.add(currentComposer.compositionData)
            CompositionLocalProvider(LocalInspectionTables provides inspectionTables) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val state = rememberLazyListState()
                    listState = state
                    hostCounter.Track()
                    ForumPane(
                        source = ForumListSource.Forum(2),
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = true),
                        selectedTid = null,
                        onSelectSource = {},
                        onOpenThread = {},
                        onOpenLogin = {},
                        listState = state,
                        viewModel = vm,
                    )
                }
            }
        }
        waitForIdle()

        val lazyState = requireNotNull(listState)
        val runningRecomposers = Recomposer.runningRecomposers.value
        assertTrue("测试环境应存在运行中的 Recomposer 以观察内层重组作用域", runningRecomposers.isNotEmpty())
        val titleRowHeightPx = with(density) { FORUM_TOP_BAR_TITLE_ROW_HEIGHT.toPx() }

        // 先将列表定位到中段（远离顶部与底部自动续载阈值），让 isScrolled=true 首帧稳定
        runOnIdle { lazyState.requestScrollToItem(index = 6, scrollOffset = 0) }
        waitForIdle()
        val expandedTabTop = onNodeWithText("今日新帖").fetchSemanticsNode().boundsInRoot.top

        // 校验 LocalInspectionTables 确实能解析真实 ForumPane 及其所有内层 Composable 的 sourceInfo（防止断言空转假绿）
        fun collectAllSourceInfos(): Set<String> {
            val out = LinkedHashSet<String>()
            fun walk(data: CompositionData) {
                data.compositionGroups.forEach { group ->
                    group.sourceInfo?.let(out::add)
                    walk(group)
                }
            }
            inspectionTables.forEach(::walk)
            return out
        }
        val allSourceInfos = collectAllSourceInfos()
        val guardedInnerNames = listOf(
            "ForumPane",
            "ForumTopBar",
            "ForumTopBarBackground",
            "ForumTopBarUpperRow",
            "BalancedForumTabRow",
            "ForumTabItem",
            "NewThreadsPill",
            "ThreadListRow",
        )
        guardedInnerNames.forEach { name ->
            assertTrue("InspectionTables 必须覆盖真实内层作用域 $name", allSourceInfos.any { name in it })
        }

        fun resolveSourceInfo(scope: RecomposeScope): String? {
            val id = (scope as? IdentifiableRecomposeScope)?.identity ?: return null
            return inspectionTables.firstNotNullOfOrNull { it.find(id)?.sourceInfo }
        }

        val trackedHandles = mutableListOf<CompositionObserverHandle>()
        val existingCompositions = Collections.newSetFromMap(IdentityHashMap<ObservableComposition, Boolean>())
        val forumReenteredScopes = Collections.synchronizedList(mutableListOf<String>())
        val forumInvalidatedScopes = Collections.synchronizedList(mutableListOf<String>())
        var recording = false

        val registrationObserver = object : CompositionRegistrationObserver {
            override fun onCompositionRegistered(composition: ObservableComposition) {
                if (!recording) existingCompositions.add(composition)
                val isExisting = composition in existingCompositions
                trackedHandles += composition.setObserver(
                    object : CompositionObserver {
                        override fun onBeginComposition(composition: ObservableComposition) = Unit
                        override fun onScopeEnter(scope: RecomposeScope) {
                            if (!recording) return
                            val info = resolveSourceInfo(scope) ?: return
                            val isForumUiScope = guardedInnerNames.any { it != "ThreadListRow" && it in info }
                            val isExistingRowScope = isExisting && "ThreadListRow" in info
                            if (isForumUiScope || isExistingRowScope) {
                                forumReenteredScopes += info
                            }
                        }
                        override fun onReadInScope(scope: RecomposeScope, value: Any) = Unit
                        override fun onScopeExit(scope: RecomposeScope) = Unit
                        override fun onEndComposition(composition: ObservableComposition) = Unit
                        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {
                            if (!recording) return
                            val info = resolveSourceInfo(scope) ?: return
                            val isForumUiScope = guardedInnerNames.any { it != "ThreadListRow" && it in info }
                            val isExistingRowScope = isExisting && "ThreadListRow" in info
                            if (isForumUiScope || isExistingRowScope) {
                                forumInvalidatedScopes += "$info<-${value?.javaClass?.simpleName}"
                            }
                        }
                        override fun onScopeDisposed(scope: RecomposeScope) = Unit
                    },
                )
            }

            override fun onCompositionUnregistered(composition: ObservableComposition) = Unit
        }
        val registrationHandles = runningRecomposers.map { it.observe(registrationObserver) }

        try {
            val hostCountBeforeScroll = hostCounter.count
            recording = true

            // 连续 6 步向下拖动折叠上行
            onNode(verticalScrollMatcher()).performTouchInput {
                down(center)
                moveBy(Offset(0f, -touchSlop))
                repeat(6) { moveBy(Offset(0f, -32f), delayMillis = 16L) }
                advanceEventTime(200L)
                moveBy(Offset.Zero)
                up()
            }
            waitForIdle()
            val collapsedTabTop = onNodeWithText("今日新帖").fetchSemanticsNode().boundsInRoot.top
            assertEquals("中段持续下滑应真正折叠顶栏上行（防止静止假绿）", expandedTabTop - titleRowHeightPx, collapsedTabTop, 2f)

            // 连续 6 步向上拖动重新展开上行（停留在中段不触顶）
            onNode(verticalScrollMatcher()).performTouchInput {
                down(center)
                moveBy(Offset(0f, touchSlop))
                repeat(6) { moveBy(Offset(0f, 32f), delayMillis = 16L) }
                advanceEventTime(200L)
                moveBy(Offset.Zero)
                up()
            }
            waitForIdle()
            val reExpandedTabTop = onNodeWithText("今日新帖").fetchSemanticsNode().boundsInRoot.top
            assertEquals("中段持续上滑应真正恢复顶栏上行展开", expandedTabTop, reExpandedTabTop, 2f)
            assertTrue("整个往返过程应停留在中段避免跨过 (0,0) 临界点", lazyState.firstVisibleItemIndex >= 1)

            assertEquals("持续滚动折叠与展开期间 ForumPane 外层宿主不得冗余重组", hostCountBeforeScroll, hostCounter.count)
            assertTrue(
                "持续滚动折叠与展开期间 ForumPane/ForumTopBar 及全部内层作用域不得失效，实际=$forumInvalidatedScopes",
                forumInvalidatedScopes.isEmpty(),
            )
            assertTrue(
                "持续滚动折叠与展开期间 ForumPane/ForumTopBar 及已见 ThreadListRow 内层作用域不得重入，实际=$forumReenteredScopes",
                forumReenteredScopes.isEmpty(),
            )
        } finally {
            recording = false
            trackedHandles.forEach { it.dispose() }
            registrationHandles.forEach { it?.dispose() }
        }
    }

    /** #3：论坛顶栏单击标题/空白区滚回顶部（含减动效 0ms 单帧到位），点击发新帖/更多动作按钮不误触发滚顶。 */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun forumTopBar_tapTitleScrollsToTopInNormalAndReducedMotion_actionsDoNotScroll() = withAnimatorDurationScale(0f) {
        val vm = createForumViewModel(itemCount = 40)
        var listState: LazyListState? = null
        var newThreadClicks = 0

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val state = rememberLazyListState()
                listState = state
                ForumPane(
                    source = ForumListSource.Forum(2),
                    chromeSpec = ForumChromeSpec(showForumTabs = false, useGlassTopBar = true),
                    selectedTid = null,
                    onSelectSource = {},
                    onOpenThread = {},
                    onOpenLogin = {},
                    listState = state,
                    onNewThreadClick = { newThreadClicks++ },
                    viewModel = vm,
                )
            }
        }
        waitForIdle()

        val state = requireNotNull(listState)
        runOnIdle { state.requestScrollToItem(14, 40) }
        waitForIdle()
        assertTrue(state.firstVisibleItemIndex >= 14)

        // 点击右侧「发新帖」按钮不得误触发滚顶
        onNodeWithContentDescription("发新帖").performClick()
        waitForIdle()
        assertEquals(1, newThreadClicks)
        assertTrue("点击发新帖按钮不得触发滚顶", state.firstVisibleItemIndex >= 14)

        // 单击顶栏标题（减动效 0ms）：一帧滚回 (0, 0)
        mainClock.autoAdvance = false
        try {
            onNodeWithText("Discovery").performClick()
            mainClock.advanceTimeByFrame()
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
            assertEquals("单击顶栏标题不得误触发发新帖", 1, newThreadClicks)
        } finally {
            mainClock.autoAdvance = true
        }
    }

    /** U1 验收 1：论坛顶栏无标签态高严格 56dp，静止态底线透明度为 0f（像素零色差），滚动后随动效平滑淡入显示。 */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun forumTopBar_height56dp_andBottomDividerHiddenAtRestFadesInOnScroll() = withAnimatorDurationScale(1f) {
        var scrolled by mutableStateOf(false)
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val scrollState = rememberForumTopBarScrollState(collapsible = false)
                Box(Modifier.fillMaxWidth().testTag("forum_top_bar_host")) {
                    ForumTopBar(
                        title = "Discovery",
                        source = ForumListSource.Forum(2),
                        followedForums = listOf(2, 6, 7),
                        sortByPostTime = false,
                        showForumTabs = false,
                        glassState = null,
                        isScrolled = { scrolled },
                        scrollState = scrollState,
                        onSelectSource = {},
                        onNewThreadClick = {},
                        onSelectSortByPostTime = {},
                        onManageForumsClick = {},
                    )
                }
            }
        }
        waitForIdle()

        val barBounds = onNodeWithTag("forum_top_bar_host").fetchSemanticsNode().boundsInRoot
        assertEquals("论坛顶栏（无标签态）高度必须严格为 56dp", 56f, with(density) { barBounds.height.toDp().value }, 0.5f)

        fun bottomLineMaxChannelDelta(): Int {
            val bmp = onNodeWithTag("forum_top_bar_host").captureToImage().asAndroidBitmap()
            val yDivider = bmp.height - 1
            val yAbove = (bmp.height - 4).coerceAtLeast(0)
            val xProbe = bmp.width / 2
            val pDiv = bmp.getPixel(xProbe, yDivider)
            val pAbove = bmp.getPixel(xProbe, yAbove)
            return maxOf(
                kotlin.math.abs(android.graphics.Color.red(pDiv) - android.graphics.Color.red(pAbove)),
                kotlin.math.abs(android.graphics.Color.green(pDiv) - android.graphics.Color.green(pAbove)),
                kotlin.math.abs(android.graphics.Color.blue(pDiv) - android.graphics.Color.blue(pAbove)),
            )
        }

        assertEquals("静止态论坛顶栏底线透明度必须为 0f（底缘与顶栏底色完全一致）", 0, bottomLineMaxChannelDelta())

        mainClock.autoAdvance = false
        try {
            scrolled = true
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            val midDelta = bottomLineMaxChannelDelta()
            mainClock.advanceTimeBy(1_000L)
            val settledDelta = bottomLineMaxChannelDelta()
            assertTrue("滚动后底线应渐显（settledDelta=$settledDelta 应 >= 10）", settledDelta >= 10)
            assertTrue("渐显过程中应平滑淡入而非突跳（midDelta=$midDelta, settledDelta=$settledDelta）", midDelta in 1 until settledDelta)
        } finally {
            mainClock.autoAdvance = true
        }
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

    private fun createForumViewModel(itemCount: Int = 30): ForumViewModel {
        val itemsByFid = mapOf(
            2 to (1..itemCount).map { sampleSummary("20$it", "Discovery 滚动验证帖子 $it") },
            6 to (1..itemCount).map { sampleSummary("60$it", "Buy & Sell 帖子 $it") },
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
