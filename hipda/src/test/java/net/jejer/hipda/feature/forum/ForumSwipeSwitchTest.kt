package net.jejer.hipda.feature.forum

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
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
import net.jejer.hipda.data.threadlist.ThreadListBackfillStopReason
import net.jejer.hipda.data.threadlist.ThreadListBatchResult
import net.jejer.hipda.data.threadlist.ThreadListError
import net.jejer.hipda.data.threadlist.ThreadListItem
import net.jejer.hipda.data.threadlist.ThreadListLoadResult
import net.jejer.hipda.data.threadlist.ThreadListLoadState
import net.jejer.hipda.data.threadlist.ThreadListQuery
import net.jejer.hipda.data.threadlist.ThreadListRepository
import net.jejer.hipda.feature.app.forumSaveableKey
import net.jejer.hipda.feature.common.ForumChromeSpec
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.threadlist.ThreadSummary
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ForumSwipeSwitchTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ---- 验收 1：adjacentForumSource 纯函数单测 ----

    @Test
    fun adjacentForumSource_followedOrderBoundariesEmptyAndUnfollowed() {
        val followed = listOf(2, 6, 7)

        // TodayNew 左滑(下一个) -> 2；7 再左滑 -> null；TodayNew 右滑 -> null；6 右滑 -> 2
        assertEquals(ForumListSource.Forum(2), adjacentForumSource(ForumListSource.TodayNew, followed, +1))
        assertEquals(ForumListSource.Forum(6), adjacentForumSource(ForumListSource.Forum(2), followed, +1))
        assertEquals(ForumListSource.Forum(7), adjacentForumSource(ForumListSource.Forum(6), followed, +1))
        assertNull(adjacentForumSource(ForumListSource.Forum(7), followed, +1))

        assertNull(adjacentForumSource(ForumListSource.TodayNew, followed, -1))
        assertEquals(ForumListSource.TodayNew, adjacentForumSource(ForumListSource.Forum(2), followed, -1))
        assertEquals(ForumListSource.Forum(2), adjacentForumSource(ForumListSource.Forum(6), followed, -1))
        assertEquals(ForumListSource.Forum(6), adjacentForumSource(ForumListSource.Forum(7), followed, -1))

        // direction == 0 -> null
        assertNull(adjacentForumSource(ForumListSource.Forum(2), followed, 0))

        // followed = [] 时 TodayNew 两向皆 null
        assertNull(adjacentForumSource(ForumListSource.TodayNew, emptyList(), +1))
        assertNull(adjacentForumSource(ForumListSource.TodayNew, emptyList(), -1))

        // 当前源 fid=57 不在关注中 -> 两向皆 null
        assertNull(adjacentForumSource(ForumListSource.Forum(57), followed, +1))
        assertNull(adjacentForumSource(ForumListSource.Forum(57), followed, -1))
    }

    // ---- 验收 2：手势分流、位移阈值、快甩、首项回弹、竖滑不误触 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun swipeLeft40Percent_slowRelease_callsOnSelectSourceOnceWithNextForum() {
        val harness = SwipeHarness()
        val selectedCalls = mutableListOf<ForumListSource>()
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))
        var touchSlop = 0f

        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(forumSaveableKey(source)) {
                    ForumPane(
                        source = source,
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                        selectedTid = null,
                        onSelectSource = { next ->
                            selectedCalls += next
                            source = next
                        },
                        onOpenThread = {},
                        onOpenLogin = {},
                        listState = rememberLazyListState(),
                        viewModel = harness.viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onRoot().performTouchInput {
            slowHorizontalDrag(startXFraction = 0.78f, deltaWidthFraction = -0.40f, touchSlop = touchSlop)
        }
        composeRule.waitForIdle()

        assertEquals(listOf<ForumListSource>(ForumListSource.Forum(6)), selectedCalls)
        assertEquals(ForumListSource.Forum(6), source)
        composeRule.onNodeWithText("BS 帖子 1").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun swipeLeft10Percent_slowRelease_doesNotSwitchAndReboundsToZero() {
        val harness = SwipeHarness()
        val selectedCalls = mutableListOf<ForumListSource>()
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))
        var touchSlop = 0f

        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(forumSaveableKey(source)) {
                    ForumPane(
                        source = source,
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                        selectedTid = null,
                        onSelectSource = { selectedCalls += it; source = it },
                        onOpenThread = {},
                        onOpenLogin = {},
                        listState = rememberLazyListState(),
                        viewModel = harness.viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val initialLeft = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x
        val widthPx = with(composeRule.density) { 411.dp.toPx() }
        var midLeft = initialLeft

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onRoot().performTouchInput {
                val y = height * 0.55f
                down(Offset(width * 0.65f, y))
                moveBy(Offset(-(touchSlop + 2f), 0f), delayMillis = 16L)
                repeat(4) { moveBy(Offset(-(width * 0.10f) / 4f, 0f), delayMillis = 36L) }
            }
            composeRule.mainClock.advanceTimeByFrame()
            midLeft = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x
            composeRule.onRoot().performTouchInput {
                advanceEventTime(120L)
                up()
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        assertTrue("左拖 10% 期间应有明显跟手位移（mid=${midLeft - initialLeft}px）", initialLeft - midLeft > widthPx * 0.05f)
        assertTrue("左拖 10% 慢放不应触发切换：$selectedCalls", selectedCalls.isEmpty())
        assertEquals(ForumListSource.Forum(2), source)
        val settledLeft = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x
        assertEquals("松手后偏移应回到 0", initialLeft, settledLeft, 0.5f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun fastFlingLeft15Percent_above1000DpPerSec_switchesToNextForum() {
        val harness = SwipeHarness()
        val selectedCalls = mutableListOf<ForumListSource>()
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))
        var touchSlop = 0f

        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(forumSaveableKey(source)) {
                    ForumPane(
                        source = source,
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                        selectedTid = null,
                        onSelectSource = { selectedCalls += it; source = it },
                        onOpenThread = {},
                        onOpenLogin = {},
                        listState = rememberLazyListState(),
                        viewModel = harness.viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onRoot().performTouchInput {
            val y = height * 0.55f
            val distance = width * 0.15f
            down(Offset(width * 0.70f, y))
            moveBy(Offset(-(touchSlop + 2f), 0f), delayMillis = 8L)
            repeat(4) {
                moveBy(Offset(-distance / 4f, 0f), delayMillis = 8L)
            }
            up()
        }
        composeRule.waitForIdle()

        assertEquals(listOf<ForumListSource>(ForumListSource.Forum(6)), selectedCalls)
        assertEquals(ForumListSource.Forum(6), source)
        composeRule.onNodeWithText("BS 帖子 1").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun firstTabTodayNew_dragRight_appliesDampingDoesNotSwitchAndReboundsToZero() {
        val harness = SwipeHarness()
        val selectedCalls = mutableListOf<ForumListSource>()
        var source by mutableStateOf<ForumListSource>(ForumListSource.TodayNew)
        var touchSlop = 0f

        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(forumSaveableKey(source)) {
                    ForumPane(
                        source = source,
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                        selectedTid = null,
                        onSelectSource = { selectedCalls += it; source = it },
                        onOpenThread = {},
                        onOpenLogin = {},
                        listState = rememberLazyListState(),
                        viewModel = harness.viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val initialLeft = composeRule.onNodeWithText("今日新帖 1").fetchSemanticsNode().boundsInRoot.left
        val widthPx = with(composeRule.density) { 411.dp.toPx() }

        composeRule.mainClock.autoAdvance = false
        var dampedDeltaPx = 0f
        try {
            composeRule.onRoot().performTouchInput {
                val y = height * 0.55f
                down(Offset(width * 0.20f, y))
                moveBy(Offset(touchSlop + 2f, 0f), delayMillis = 16L)
                moveBy(Offset(width * 0.40f, 0f), delayMillis = 32L)
            }
            composeRule.mainClock.advanceTimeByFrame()
            dampedDeltaPx = composeRule.onNodeWithText("今日新帖 1").fetchSemanticsNode().boundsInRoot.left - initialLeft

            composeRule.onRoot().performTouchInput {
                advanceEventTime(120L)
                up()
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        // 0.3 倍阻尼：40% 位移对应约 12% 视觉偏移，应明显大于 0 且小于未阻尼位移的一半
        assertTrue(
            "首项向右拖应有 0.3x 阻尼跟手（actual=${dampedDeltaPx}px, width=${widthPx}px）",
            dampedDeltaPx > widthPx * 0.06f && dampedDeltaPx < widthPx * 0.20f,
        )
        assertTrue("首项向右拖不得切换：$selectedCalls", selectedCalls.isEmpty())
        assertEquals(ForumListSource.TodayNew, source)
        val settledLeft = composeRule.onNodeWithText("今日新帖 1").fetchSemanticsNode().boundsInRoot.left
        assertEquals("首项向右拖松手后偏移必须回到 0", initialLeft, settledLeft, 0.5f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun verticalScrollWithSmallHorizontalDrift_doesNotSwitchAndScrollsList_clickAndLongClickIntact() {
        val harness = SwipeHarness()
        val selectedCalls = mutableListOf<ForumListSource>()
        val opened = mutableListOf<ThreadNavArgs>()
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))
        var activeListState: LazyListState? = null
        var touchSlop = 0f

        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(forumSaveableKey(source)) {
                    val state = rememberLazyListState()
                    activeListState = state
                    ForumPane(
                        source = source,
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                        selectedTid = null,
                        onSelectSource = { selectedCalls += it; source = it },
                        onOpenThread = opened::add,
                        onOpenLogin = {},
                        listState = state,
                        viewModel = harness.viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val state = requireNotNull(activeListState)
        assertEquals(0, state.firstVisibleItemIndex)

        composeRule.onRoot().performTouchInput {
            val start = Offset(width * 0.5f, height * 0.72f)
            down(start)
            // 横向漂移小于 touchSlop，竖向超过 slop 连续滚动
            moveBy(Offset(touchSlop * 0.3f, -(touchSlop + 8f)), delayMillis = 16L)
            repeat(8) {
                moveBy(Offset(0f, -80f), delayMillis = 16L)
            }
            advanceEventTime(120L)
            up()
        }
        composeRule.waitForIdle()

        assertTrue("竖向滚动不得触发切换：$selectedCalls", selectedCalls.isEmpty())
        assertTrue(
            "竖向滚动应正常滚动列表，firstVisibleItemIndex=${state.firstVisibleItemIndex}",
            state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0,
        )

        // 单击与长按仍正常工作
        val targetRow = composeRule.onNodeWithText("Discovery 帖子 8").assertIsDisplayed()
        targetRow.performClick()
        composeRule.waitForIdle()
        assertEquals(1, opened.size)
        assertFalse(opened.last().openLastPage)

        targetRow.performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals(2, opened.size)
        assertTrue(opened.last().openLastPage)
    }

    // ---- 验收 4：SaveableStateProvider 滚位保持、标签选中同步、拖半点标签、空/错态横滑、标签行横滑忽略、反向快甩回弹 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun scrollPositionPreservedAcrossSwipeRoundTrip_andTabRowSelectionUpdates() {
        val harness = SwipeHarness()
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))
        val statesBySource = mutableMapOf<ForumListSource, LazyListState>()
        var touchSlop = 0f

        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(forumSaveableKey(source)) {
                    val state = rememberLazyListState()
                    statesBySource[source] = state
                    ForumPane(
                        source = source,
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                        selectedTid = null,
                        onSelectSource = { source = it },
                        onOpenThread = {},
                        onOpenLogin = {},
                        listState = state,
                        viewModel = harness.viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 在 Discovery 滚到第 10 项
        val discoveryInitialState = requireNotNull(statesBySource[ForumListSource.Forum(2)])
        composeRule.runOnIdle { discoveryInitialState.requestScrollToItem(10) }
        composeRule.waitForIdle()
        assertEquals(10, discoveryInitialState.firstVisibleItemIndex)

        // 左滑到 Buy & Sell
        composeRule.onRoot().performTouchInput {
            slowHorizontalDrag(startXFraction = 0.78f, deltaWidthFraction = -0.40f, touchSlop = touchSlop)
        }
        composeRule.waitForIdle()
        assertEquals(ForumListSource.Forum(6), source)
        composeRule.onNode(tabMatcher("Buy & Sell")).assertIsSelected()
        composeRule.onNodeWithText("BS 帖子 1").assertIsDisplayed()

        // 右滑回 Discovery -> 滚位回到第 10 项且标签行同步选中 Discovery
        composeRule.onRoot().performTouchInput {
            slowHorizontalDrag(startXFraction = 0.22f, deltaWidthFraction = +0.40f, touchSlop = touchSlop)
        }
        composeRule.waitForIdle()
        assertEquals(ForumListSource.Forum(2), source)
        composeRule.onNode(tabMatcher("Discovery")).assertIsSelected()
        val discoveryRestoredState = requireNotNull(statesBySource[ForumListSource.Forum(2)])
        assertEquals(
            "滑到 Buy & Sell 再滑回 Discovery 后必须回到第 10 项",
            10,
            discoveryRestoredState.firstVisibleItemIndex,
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun scrollPositionNotClampedWhenReturningFromEmptyForum_andEmptyErrorStatesAllowSwipeAway() {
        val harness = SwipeHarness(
            emptyFids = setOf(6),
            errorFids = setOf(7),
        )
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))
        val statesBySource = mutableMapOf<ForumListSource, LazyListState>()
        var touchSlop = 0f

        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(forumSaveableKey(source)) {
                    val state = rememberLazyListState()
                    statesBySource[source] = state
                    ForumPane(
                        source = source,
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                        selectedTid = null,
                        onSelectSource = { source = it },
                        onOpenThread = {},
                        onOpenLogin = {},
                        listState = state,
                        viewModel = harness.viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Discovery 滚到第 10 项
        composeRule.runOnIdle { requireNotNull(statesBySource[ForumListSource.Forum(2)]).requestScrollToItem(10) }
        composeRule.waitForIdle()
        assertEquals(10, requireNotNull(statesBySource[ForumListSource.Forum(2)]).firstVisibleItemIndex)

        // 左滑到空版块 fid=6（Buy & Sell）
        composeRule.onRoot().performTouchInput {
            slowHorizontalDrag(startXFraction = 0.78f, deltaWidthFraction = -0.40f, touchSlop = touchSlop)
        }
        composeRule.waitForIdle()
        assertEquals(ForumListSource.Forum(6), source)
        composeRule.onNodeWithText("暂无帖子").assertIsDisplayed()

        // 在空版块上继续左滑 -> 能切走到错误态版块 fid=7（Geek Talks）
        composeRule.onRoot().performTouchInput {
            slowHorizontalDrag(startXFraction = 0.78f, deltaWidthFraction = -0.40f, touchSlop = touchSlop)
        }
        composeRule.waitForIdle()
        assertEquals(ForumListSource.Forum(7), source)
        composeRule.onNodeWithText("重试").assertIsDisplayed()

        // 在错误态版块上右滑 -> 能切回空版块 fid=6
        composeRule.onRoot().performTouchInput {
            slowHorizontalDrag(startXFraction = 0.22f, deltaWidthFraction = +0.40f, touchSlop = touchSlop)
        }
        composeRule.waitForIdle()
        assertEquals(ForumListSource.Forum(6), source)

        // 再从空版块右滑回 Discovery -> Discovery 滚动位置不得被空列表夹断，仍为第 10 项
        composeRule.onRoot().performTouchInput {
            slowHorizontalDrag(startXFraction = 0.22f, deltaWidthFraction = +0.40f, touchSlop = touchSlop)
        }
        composeRule.waitForIdle()
        assertEquals(ForumListSource.Forum(2), source)
        assertEquals(
            "从空版块滑回 Discovery 不得用旧空列表夹断新 listState",
            10,
            requireNotNull(statesBySource[ForumListSource.Forum(2)]).firstVisibleItemIndex,
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun midDragTapAnotherTab_resetsOffsetToZeroAndShowsTappedForum() {
        val harness = SwipeHarness()
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))
        var touchSlop = 0f

        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(forumSaveableKey(source)) {
                    ForumPane(
                        source = source,
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                        selectedTid = null,
                        onSelectSource = { source = it },
                        onOpenThread = {},
                        onOpenLogin = {},
                        listState = rememberLazyListState(),
                        viewModel = harness.viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val initialLeft = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x
        val geekTabCenter = composeRule.onNode(tabMatcher("Geek Talks")).fetchSemanticsNode().boundsInRoot.center

        composeRule.mainClock.autoAdvance = false
        try {
            // 1. 拖到一半（手指仍按住，列表已偏移）时点击另一个标签 Geek Talks
            composeRule.onRoot().performTouchInput {
                val y = height * 0.55f
                down(pointerId = 0, Offset(width * 0.78f, y))
                moveBy(pointerId = 0, Offset(-(touchSlop + 2f), 0f), delayMillis = 16L)
                moveBy(pointerId = 0, Offset(-width * 0.25f, 0f), delayMillis = 16L)
            }
            composeRule.mainClock.advanceTimeByFrame()
            val midLeft = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x
            assertTrue("拖动一半时列表应已向左偏移（initial=$initialLeft, mid=$midLeft）", midLeft < initialLeft - 30f)

            composeRule.onRoot().performTouchInput {
                down(pointerId = 1, geekTabCenter)
                advanceEventTime(16L)
                up(pointerId = 1)
                advanceEventTime(16L)
                up(pointerId = 0)
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        assertEquals(ForumListSource.Forum(7), source)
        composeRule.onNode(tabMatcher("Geek Talks")).assertIsSelected()
        val geekLeft = composeRule.onNodeWithText("Geek 帖子 1").assertIsDisplayed().fetchSemanticsNode().positionInRoot.x
        assertEquals("点标签切换后偏移必须归 0", initialLeft, geekLeft, 0.5f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun horizontalSwipeOnTabRowAndTitleBar_doesNotSwitchOrOffsetList() {
        val harness = SwipeHarness()
        val selectedCalls = mutableListOf<ForumListSource>()
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))
        var touchSlop = 0f

        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(forumSaveableKey(source)) {
                    ForumPane(
                        source = source,
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                        selectedTid = null,
                        onSelectSource = { selectedCalls += it; source = it },
                        onOpenThread = {},
                        onOpenLogin = {},
                        listState = rememberLazyListState(),
                        viewModel = harness.viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val initialLeft = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x
        val tabBounds = composeRule.onNode(tabMatcher("Buy & Sell")).fetchSemanticsNode().boundsInRoot

        // 1. 在等分排布（无 horizontalScroll）的标签行上横滑 -> 不切换、无偏移
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onRoot().performTouchInput {
                val y = tabBounds.center.y
                down(Offset(width * 0.78f, y))
                moveBy(Offset(-(touchSlop + 2f), 0f), delayMillis = 16L)
                repeat(4) { moveBy(Offset(-width * 0.12f, 0f), delayMillis = 16L) }
            }
            composeRule.mainClock.advanceTimeByFrame()
            val duringTabDragLeft = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x
            assertEquals("标签行横滑期间列表不得产生任何偏移", initialLeft, duringTabDragLeft, 0.5f)
            composeRule.onRoot().performTouchInput { up() }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        assertTrue("标签行上横滑不得触发版块切换：$selectedCalls", selectedCalls.isEmpty())
        assertEquals(ForumListSource.Forum(2), source)

        // 2. 在顶栏标题行区域横滑 -> 同样不切换、无偏移
        val titleY = (tabBounds.top * 0.5f).coerceAtLeast(8f)
        composeRule.onRoot().performTouchInput {
            down(Offset(width * 0.70f, titleY))
            moveBy(Offset(-(touchSlop + 2f), 0f), delayMillis = 16L)
            repeat(4) { moveBy(Offset(-width * 0.12f, 0f), delayMillis = 16L) }
            up()
        }
        composeRule.waitForIdle()

        assertTrue("顶栏标题行上横滑不得触发版块切换：$selectedCalls", selectedCalls.isEmpty())
        assertEquals(ForumListSource.Forum(2), source)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun dragLeft30Percent_thenFastFlickRight_reboundsWithoutSwitching() {
        val harness = SwipeHarness()
        val selectedCalls = mutableListOf<ForumListSource>()
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))
        var touchSlop = 0f

        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(forumSaveableKey(source)) {
                    ForumPane(
                        source = source,
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                        selectedTid = null,
                        onSelectSource = { selectedCalls += it; source = it },
                        onOpenThread = {},
                        onOpenLogin = {},
                        listState = rememberLazyListState(),
                        viewModel = harness.viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val initialLeft = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x
        val widthPx = with(composeRule.density) { 411.dp.toPx() }
        var beforeFlickOffset = 0f

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onRoot().performTouchInput {
                val y = height * 0.55f
                down(Offset(width * 0.78f, y))
                moveBy(Offset(-(touchSlop + 2f), 0f), delayMillis = 16L)
                // 先左拖 30%
                repeat(6) {
                    moveBy(Offset(-(width * 0.30f) / 6f, 0f), delayMillis = 24L)
                }
            }
            composeRule.mainClock.advanceTimeByFrame()
            beforeFlickOffset = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x - initialLeft
            composeRule.onRoot().performTouchInput {
                // 再向右快甩（速度 > 1000dp/s，但位移仍在左侧约 -24%）
                repeat(3) {
                    moveBy(Offset(+(width * 0.06f) / 3f, 0f), delayMillis = 5L)
                }
                up()
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        assertTrue("反向快甩前应已左拖约 30%（actual=$beforeFlickOffset）", beforeFlickOffset < -widthPx * 0.25f)
        assertTrue("左拖 30% 后向右快甩必须回弹不切换：$selectedCalls", selectedCalls.isEmpty())
        assertEquals(ForumListSource.Forum(2), source)
        val settledLeft = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x
        assertEquals("反向快甩回弹后偏移必须归 0", initialLeft, settledLeft, 0.5f)
    }

    // ---- 验收 5：逐帧（autoAdvance = false）验证零闪帧与严格 0 终态 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun frameByFrame_swipeSwitchHasNoFlashAtZeroForOldOrNewContent_andSettlesExactlyAtZero() {
        val harness = SwipeHarness()
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))
        var touchSlop = 0f

        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(forumSaveableKey(source)) {
                    ForumPane(
                        source = source,
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                        selectedTid = null,
                        onSelectSource = { source = it },
                        onOpenThread = {},
                        onOpenLogin = {},
                        listState = rememberLazyListState(),
                        viewModel = harness.viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val widthPx = with(composeRule.density) { 411.dp.toPx() }
        val baseLeft = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x
        val oldFrames = mutableListOf<Float>()
        val newFrames = mutableListOf<Float>()

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onRoot().performTouchInput {
                val y = height * 0.55f
                down(Offset(width * 0.78f, y))
                moveBy(Offset(-(touchSlop + 2f), 0f), delayMillis = 16L)
                repeat(8) {
                    moveBy(Offset(-(width * 0.40f) / 8f, 0f), delayMillis = 32L)
                }
                advanceEventTime(100L)
            }
            composeRule.mainClock.advanceTimeByFrame()
            val releaseOldOffset = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x - baseLeft
            assertTrue("松手前旧内容应已左移约 40%（actual=$releaseOldOffset）", releaseOldOffset < -widthPx * 0.25f)

            composeRule.onRoot().performTouchInput { up() }

            // 逐帧推进 90 帧（~1.44s），记录每帧旧内容与新内容的水平偏移（positionInRoot.x 不受 clipToBounds 夹断）
            repeat(90) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.onAllNodesWithText("Discovery 帖子 1").fetchSemanticsNodes().firstOrNull()?.let { node ->
                    oldFrames += node.positionInRoot.x - baseLeft
                }
                composeRule.onAllNodesWithText("BS 帖子 1").fetchSemanticsNodes().firstOrNull()?.let { node ->
                    newFrames += node.positionInRoot.x - baseLeft
                }
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        assertEquals(ForumListSource.Forum(6), source)
        assertTrue("应采集到旧版块滑出帧：$oldFrames", oldFrames.isNotEmpty())
        assertTrue("应采集到新版块滑入帧：$newFrames", newFrames.isNotEmpty())

        // 1. 旧版块滑出期间绝不能回到 0 闪帧（必须全程保持 <= 初始松手偏移 + 1px 且单调左移）
        oldFrames.forEachIndexed { idx, offset ->
            assertTrue(
                "旧版块第 $idx 帧不得跳回 0 附近（offset=$offset, frames=$oldFrames）",
                offset < -widthPx * 0.20f,
            )
            if (idx > 0) {
                assertTrue(
                    "旧版块滑出应单调向左（frame[$idx]=$offset, prev=${oldFrames[idx - 1]}）",
                    offset <= oldFrames[idx - 1] + 1f,
                )
            }
        }

        // 2. 新版块首帧必须在右侧侧边出现（严禁首帧为 0 后再跳到右侧）
        val firstNewOffset = newFrames.first()
        assertTrue(
            "新版块首帧必须直接从右侧入场，不得先在 0 处闪现（firstNewOffset=$firstNewOffset, width=$widthPx, frames=${newFrames.take(10)}）",
            firstNewOffset >= widthPx * 0.50f,
        )
        // 新版块入场过程不得出现“回到 0 后又跳回侧边”
        var seenNearZero = false
        newFrames.forEachIndexed { idx, offset ->
            if (abs(offset) <= 4f) {
                seenNearZero = true
            } else if (seenNearZero) {
                assertTrue(
                    "新版块到达 0 附近后不得再跳回侧边（frame[$idx]=$offset, frames=$newFrames）",
                    abs(offset) <= 6f,
                )
            }
        }

        // 3. 过渡结束后偏移恰为 0
        val finalNewOffset = composeRule.onNodeWithText("BS 帖子 1").fetchSemanticsNode().positionInRoot.x - baseLeft
        assertEquals("过渡结束偏移必须恰为 0", 0f, finalNewOffset, 0.5f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun dragCancelledPastThreshold_reboundsToZeroWithoutSwitching() {
        val harness = SwipeHarness()
        val selectedCalls = mutableListOf<ForumListSource>()
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))
        var touchSlop = 0f

        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(forumSaveableKey(source)) {
                    ForumPane(
                        source = source,
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                        selectedTid = null,
                        onSelectSource = { selectedCalls += it; source = it },
                        onOpenThread = {},
                        onOpenLogin = {},
                        listState = rememberLazyListState(),
                        viewModel = harness.viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val initialLeft = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x
        val widthPx = with(composeRule.density) { 411.dp.toPx() }
        var beforeCancelOffset = 0f

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onRoot().performTouchInput {
                val y = height * 0.55f
                down(Offset(width * 0.78f, y))
                moveBy(Offset(-(touchSlop + 2f), 0f), delayMillis = 16L)
                repeat(6) { moveBy(Offset(-(width * 0.40f) / 6f, 0f), delayMillis = 24L) }
            }
            composeRule.mainClock.advanceTimeByFrame()
            beforeCancelOffset = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x - initialLeft
            composeRule.onRoot().performTouchInput { cancel() }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        assertTrue("取消前应已拖动超 25%（actual=$beforeCancelOffset）", beforeCancelOffset < -widthPx * 0.25f)
        assertTrue("拖拽被取消不得切换版块：$selectedCalls", selectedCalls.isEmpty())
        assertEquals(ForumListSource.Forum(2), source)
        val settledLeft = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x
        assertEquals("拖拽取消后偏移必须归 0", initialLeft, settledLeft, 0.5f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun midExitTapAnotherTab_cancelsExitAndShowsTappedForumAtZeroOffset() {
        val harness = SwipeHarness()
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))
        var touchSlop = 0f

        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(forumSaveableKey(source)) {
                    ForumPane(
                        source = source,
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                        selectedTid = null,
                        onSelectSource = { source = it },
                        onOpenThread = {},
                        onOpenLogin = {},
                        listState = rememberLazyListState(),
                        viewModel = harness.viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val initialLeft = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x
        val widthPx = with(composeRule.density) { 411.dp.toPx() }
        var midExitOffset = 0f

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onRoot().performTouchInput {
                slowHorizontalDrag(startXFraction = 0.78f, deltaWidthFraction = -0.40f, touchSlop = touchSlop)
            }
            // 滑出进行中（1 帧后仍处于 Discovery 退场期且尚未切走）
            composeRule.mainClock.advanceTimeByFrame()
            midExitOffset = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x - initialLeft
            composeRule.onNode(tabMatcher("Geek Talks")).performClick()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        assertTrue("点标签时旧列表应正处于滑出偏移态（actual=$midExitOffset）", midExitOffset < -widthPx * 0.25f)
        assertEquals(ForumListSource.Forum(7), source)
        composeRule.onNode(tabMatcher("Geek Talks")).assertIsSelected()
        val geekLeft = composeRule.onNodeWithText("Geek 帖子 1").assertIsDisplayed().fetchSemanticsNode().positionInRoot.x
        assertEquals("退场途中点标签后偏移必须归 0 且不被原滑出目标劫持", initialLeft, geekLeft, 0.5f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun frameByFrame_reverseSwipeRight_entersFromLeftWithoutFlashAndSettlesAtZero() {
        val harness = SwipeHarness()
        var source by mutableStateOf<ForumListSource>(ForumListSource.Forum(6))
        var touchSlop = 0f

        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val holder = rememberSaveableStateHolder()
                holder.SaveableStateProvider(forumSaveableKey(source)) {
                    ForumPane(
                        source = source,
                        chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                        selectedTid = null,
                        onSelectSource = { source = it },
                        onOpenThread = {},
                        onOpenLogin = {},
                        listState = rememberLazyListState(),
                        viewModel = harness.viewModel,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val widthPx = with(composeRule.density) { 411.dp.toPx() }
        val baseLeft = composeRule.onNodeWithText("BS 帖子 1").fetchSemanticsNode().positionInRoot.x
        val oldFrames = mutableListOf<Float>()
        val newFrames = mutableListOf<Float>()

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onRoot().performTouchInput {
                val y = height * 0.55f
                down(Offset(width * 0.22f, y))
                moveBy(Offset(touchSlop + 2f, 0f), delayMillis = 16L)
                repeat(8) { moveBy(Offset((width * 0.40f) / 8f, 0f), delayMillis = 32L) }
                advanceEventTime(100L)
            }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onRoot().performTouchInput { up() }

            repeat(90) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.onAllNodesWithText("BS 帖子 1").fetchSemanticsNodes().firstOrNull()?.let {
                    oldFrames += it.positionInRoot.x - baseLeft
                }
                composeRule.onAllNodesWithText("Discovery 帖子 1").fetchSemanticsNodes().firstOrNull()?.let {
                    newFrames += it.positionInRoot.x - baseLeft
                }
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        assertEquals(ForumListSource.Forum(2), source)
        assertTrue("右滑滑出期间旧内容不得回到 0：$oldFrames", oldFrames.isNotEmpty() && oldFrames.all { it > widthPx * 0.20f })
        assertTrue(
            "右滑切换新版块首帧必须直接从左侧入场，不得先在 0 闪现（first=${newFrames.firstOrNull()}, width=$widthPx）",
            newFrames.isNotEmpty() && newFrames.first() <= -widthPx * 0.50f,
        )
        val finalOffset = composeRule.onNodeWithText("Discovery 帖子 1").fetchSemanticsNode().positionInRoot.x - baseLeft
        assertEquals("过渡结束偏移必须恰为 0", 0f, finalOffset, 0.5f)
    }

    // ---- 辅助工具 ----

    private fun tabMatcher(label: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            SemanticsMatcher("text==$label") { node ->
                node.config.getOrElseNullable(SemanticsProperties.Text) { null }
                    ?.joinToString("") { it.text } == label
            }

    private fun TouchInjectionScope.slowHorizontalDrag(
        startXFraction: Float,
        deltaWidthFraction: Float,
        touchSlop: Float,
    ) {
        val y = height * 0.55f
        val sign = if (deltaWidthFraction < 0f) -1f else 1f
        val totalDelta = width * deltaWidthFraction
        down(Offset(width * startXFraction, y))
        moveBy(Offset(sign * (touchSlop + 2f), 0f), delayMillis = 16L)
        repeat(8) {
            moveBy(Offset(totalDelta / 8f, 0f), delayMillis = 36L)
        }
        advanceEventTime(120L)
        up()
    }

    private class SwipeHarness(
        private val emptyFids: Set<Int> = emptySet(),
        private val errorFids: Set<Int> = emptySet(),
    ) {
        private val states = LinkedHashMap<ThreadListQuery, MutableStateFlow<ThreadListLoadState>>()
        private val settingsFlow = MutableStateFlow(AppSettings(followedForums = listOf(2, 6, 7)))

        private fun stateFor(q: ThreadListQuery) = states.getOrPut(q) { MutableStateFlow(ThreadListLoadState(query = q)) }

        private fun prefixFor(fid: Int): String = when (fid) {
            2 -> "Discovery 帖子"
            6 -> "BS 帖子"
            7 -> "Geek 帖子"
            else -> "版块$fid 帖子"
        }

        private val threadRepo = object : ThreadListRepository {
            override suspend fun loadPage(
                query: ThreadListQuery, startPage: Int, existingTids: Set<String>,
                minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority,
            ) = refresh(query, minVisibleItems, maxAutoBackfillPages, priority)

            override suspend fun refresh(
                query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority,
            ): ThreadListLoadResult {
                if (query.fid in errorFids) {
                    val err = ThreadListError.NetworkError("network-down")
                    stateFor(query).value = ThreadListLoadState(query, emptyList(), 0, 0, false, 0, null, isLoading = false, error = err)
                    return err
                }
                if (query.fid in emptyFids) {
                    stateFor(query).value = ThreadListLoadState(query, emptyList(), 1, 1, false, 1, emptyMeta(), isLoading = false, stopReason = ThreadListBackfillStopReason.EMPTY_PAGE)
                    return ThreadListBatchResult(query, false, emptyList(), emptyList(), 1, 1, false, 1, emptyMeta(), ThreadListBackfillStopReason.EMPTY_PAGE)
                }
                val prefix = prefixFor(query.fid)
                val raw = (1..30).map { idx -> sampleSummary("${query.fid}0${idx.toString().padStart(2, '0')}", "$prefix $idx") }
                val items = raw.map { ThreadListItem(it, false) }
                stateFor(query).value = ThreadListLoadState(query, items, 1, 2, true, 1, emptyMeta(), isLoading = false)
                return ThreadListBatchResult(query, false, raw, items, 1, 2, true, 1, emptyMeta())
            }

            override suspend fun loadMore(
                query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority,
            ) = refresh(query, minVisibleItems, maxAutoBackfillPages, priority)

            override fun observeThreadList(query: ThreadListQuery): Flow<ThreadListLoadState> = stateFor(query)
            override fun observeFiltered(rawThreads: Flow<List<ThreadSummary>>) = rawThreads.map { l -> l.map { ThreadListItem(it, false) } }
        }

        private val searchRepo = object : SearchRepository {
            override val searchHistory: Flow<List<String>> = MutableStateFlow(emptyList())
            override suspend fun search(
                query: String, author: String, forumId: Int?, fullText: Boolean,
                page: Int, searchId: String?, priority: AccessPriority, recordHistory: Boolean,
            ) = getNewPosts(page, searchId, priority)

            override suspend fun getNewPosts(page: Int, searchId: String?, priority: AccessPriority): SearchResult {
                val items = (1..20).map { idx ->
                    SearchItem("90$idx", null, "今日新帖 $idx", null, "作者$idx", "$idx", null, "刚刚", "Discovery")
                }
                return SearchResult.Success(page, false, SearchPage("sid-1", 1, items, emptyMeta()))
            }

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
            override suspend fun setFollowedForums(forumIds: List<Int>) = settingsFlow.update { it.copy(followedForums = forumIds) }
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
            "2026-11-28", "刚刚", "回复者", "9", "90", false, 1,
        )
    }
}
