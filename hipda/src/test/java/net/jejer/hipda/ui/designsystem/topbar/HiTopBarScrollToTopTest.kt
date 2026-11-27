package net.jejer.hipda.ui.designsystem.topbar

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.feature.forum.ForumTopBar
import net.jejer.hipda.feature.forum.ForumTopBarScrollState
import net.jejer.hipda.feature.forum.rememberForumTopBarScrollState
import net.jejer.hipda.feature.search.SearchScreenContent
import net.jejer.hipda.feature.search.SearchUiState
import net.jejer.hipda.feature.threaddetail.ThreadDetailTopBar
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class HiTopBarScrollToTopTest {

    @Test
    fun hiSolidTopBar_tappingTitleScrollsLazyListToTop_andButtonsDoNotTriggerScroll() =
        withAnimatorDurationScale(1f) {
            var listState: LazyListState? = null
            var backClicks = 0
            var actionClicks = 0

            setContent {
                HiTheme(HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val state = rememberLazyListState()
                    listState = state
                    val scrollToTop = rememberHiScrollToTopAction(state)
                    Column(Modifier.fillMaxSize()) {
                        HiSolidTopBar(
                            title = "统一纯色顶栏",
                            isScrolled = state.firstVisibleItemIndex > 0,
                            showBack = true,
                            onBack = { backClicks++ },
                            onScrollToTop = scrollToTop,
                            trailingAction = {
                                TextButton(onClick = { actionClicks++ }) {
                                    Text("动作")
                                }
                            },
                        )
                        LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth()) {
                            items(40) { idx ->
                                Text("行 $idx", modifier = Modifier.fillMaxWidth().height(56.dp))
                            }
                        }
                    }
                }
            }
            waitForIdle()

            val state = requireNotNull(listState)
            runOnIdle { state.requestScrollToItem(15, 24) }
            waitForIdle()
            assertTrue("列表应先滚至中段", state.firstVisibleItemIndex >= 15)

            // 点击返回箭头与右侧动作按钮均不得误触发滚顶
            onNodeWithContentDescription("返回").assertIsDisplayed().performClick()
            onNodeWithText("动作").assertIsDisplayed().performClick()
            waitForIdle()
            assertEquals(1, backClicks)
            assertEquals(1, actionClicks)
            assertTrue("点击返回或右侧动作按钮不得误触发滚回顶部", state.firstVisibleItemIndex >= 15)

            // 单击顶栏标题/空白区滚回首项 (0, 0)
            onNodeWithText("统一纯色顶栏").performClick()
            waitForIdle()
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
            assertEquals("滚顶不得误触返回回调", 1, backClicks)
            assertEquals("滚顶不得误触动作回调", 1, actionClicks)
        }

    @Test
    fun hiSolidTopBar_reducedMotionScrollsLazyListAndScrollStateInOneFrame() =
        withAnimatorDurationScale(0f) {
            var listState: LazyListState? = null
            var scrollState: androidx.compose.foundation.ScrollState? = null
            var mode by mutableIntStateOf(0)

            setContent {
                HiTheme(HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    if (mode == 0) {
                        val state = rememberLazyListState()
                        listState = state
                        Column(Modifier.fillMaxSize()) {
                            HiSolidTopBar(
                                title = "减动效列表",
                                isScrolled = state.firstVisibleItemIndex > 0,
                                onScrollToTop = rememberHiScrollToTopAction(state),
                            )
                            LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth()) {
                                items(40) { idx ->
                                    Text("条目 $idx", modifier = Modifier.fillMaxWidth().height(56.dp))
                                }
                            }
                        }
                    } else {
                        val state = rememberScrollState()
                        scrollState = state
                        Column(Modifier.fillMaxSize()) {
                            HiSolidTopBar(
                                title = "减动效滚动容器",
                                isScrolled = state.value > 0,
                                onScrollToTop = rememberHiScrollToTopAction(state),
                            )
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(state)) {
                                repeat(40) { idx ->
                                    Text("块 $idx", modifier = Modifier.fillMaxWidth().height(56.dp))
                                }
                            }
                        }
                    }
                }
            }
            waitForIdle()

            // 1. LazyListState 减动效 0ms 单帧滚顶
            val lazy = requireNotNull(listState)
            runOnIdle { lazy.requestScrollToItem(18, 30) }
            waitForIdle()
            assertTrue(lazy.firstVisibleItemIndex >= 18)

            mainClock.autoAdvance = false
            try {
                onNodeWithText("减动效列表").performClick()
                mainClock.advanceTimeByFrame()
                assertEquals("减动效下应一帧滚回 index == 0", 0, lazy.firstVisibleItemIndex)
                assertEquals("减动效下应一帧滚回 offset == 0", 0, lazy.firstVisibleItemScrollOffset)
            } finally {
                mainClock.autoAdvance = true
            }

            // 2. ScrollState 减动效 0ms 单帧滚顶
            mode = 1
            waitForIdle()
            val scroller = requireNotNull(scrollState)
            runOnIdle { kotlinx.coroutines.runBlocking { scroller.scrollTo(600) } }
            waitForIdle()
            assertTrue(scroller.value >= 500)

            mainClock.autoAdvance = false
            try {
                onNodeWithText("减动效滚动容器").performClick()
                mainClock.advanceTimeByFrame()
                assertEquals("减动效下 ScrollState 应一帧滚回 0", 0, scroller.value)
            } finally {
                mainClock.autoAdvance = true
            }
        }

    @Test
    fun scrollToTopClickSemantics_hiddenAtTop_enabledWhenScrolled_andEnabledWhenForumCollapsed() =
        withAnimatorDurationScale(0f) {
            val hasScrollToTopLabel = SemanticsMatcher("hasScrollToTopOnClickLabel") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "回到顶部"
            }

            // 1. HiSolidTopBar：顶部无“回到顶部”语义，滚动后出现，滚回顶部后消失
            var listState: LazyListState? = null
            setContent {
                HiTheme(HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val state = rememberLazyListState()
                    listState = state
                    val isScrolled = state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0
                    Column(Modifier.fillMaxSize()) {
                        HiSolidTopBar(
                            title = "顶部语义测试",
                            isScrolled = isScrolled,
                            onScrollToTop = rememberHiScrollToTopAction(state),
                        )
                        LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth()) {
                            items(30) { idx ->
                                Text("项目 $idx", modifier = Modifier.fillMaxWidth().height(56.dp))
                            }
                        }
                    }
                }
            }
            waitForIdle()
            assertEquals("列表在顶部时语义树不得暴露回到顶部标签", 0, onAllNodes(hasScrollToTopLabel).fetchSemanticsNodes().size)
            assertNull(onNodeWithText("顶部语义测试").fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick))

            val lazy = requireNotNull(listState)
            runOnIdle { lazy.requestScrollToItem(8, 12) }
            waitForIdle()
            assertEquals("列表滚动后应暴露回到顶部标签", 1, onAllNodes(hasScrollToTopLabel).fetchSemanticsNodes().size)
            assertEquals("回到顶部", onNodeWithText("顶部语义测试").fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label)

            onNodeWithText("顶部语义测试").performClick()
            waitForIdle()
            assertEquals(0, lazy.firstVisibleItemIndex)
            assertEquals("滚回顶部后应再次移除回到顶部语义", 0, onAllNodes(hasScrollToTopLabel).fetchSemanticsNodes().size)
        }

    @Test
    fun forumDetailAndSearchTopBars_exposeScrollToTopSemanticsOnlyWhenScrolledOrCollapsed() =
        withAnimatorDurationScale(0f) {
            val hasScrollToTopLabel = SemanticsMatcher("hasScrollToTopOnClickLabel") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "回到顶部"
            }
            var mode by mutableIntStateOf(0)
            var forumScrolled by mutableStateOf(false)
            var forumTopBarScrollState: ForumTopBarScrollState? = null
            var forumScrollClicks = 0
            val detailScrolledState = mutableStateOf(false)
            var searchListState: LazyListState? = null

            setContent {
                HiTheme(HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    when (mode) {
                        0 -> {
                            val topBarState = rememberForumTopBarScrollState(collapsible = true)
                            forumTopBarScrollState = topBarState
                            ForumTopBar(
                                title = "Discovery",
                                source = ForumListSource.Forum(2),
                                followedForums = listOf(2, 6),
                                sortByPostTime = false,
                                showForumTabs = true,
                                glassState = null,
                                isScrolled = { forumScrolled },
                                scrollState = topBarState,
                                onSelectSource = {},
                                onNewThreadClick = {},
                                onSelectSortByPostTime = {},
                                onManageForumsClick = {},
                                onScrollToTop = {
                                    forumScrollClicks++
                                    topBarState.expand()
                                },
                            )
                        }
                        1 -> {
                            val glassState = rememberHiGlassState()
                            ThreadDetailTopBar(
                                title = "帖子顶栏测试",
                                authorName = "OP",
                                chromeSpec = DetailChromeSpec(showBack = true),
                                isAuthorOnly = false,
                                isScrolledState = detailScrolledState,
                                glassState = glassState,
                                onBack = {},
                                onToggleAuthorOnly = {},
                                onScrollToTop = {},
                            )
                        }
                        else -> {
                            val state = rememberLazyListState()
                            searchListState = state
                            SearchScreenContent(
                                uiState = SearchUiState(
                                    recentHistory = (1..30).map { "历史记录 $it" },
                                ),
                                listState = state,
                            )
                        }
                    }
                }
            }
            waitForIdle()

            // 1. 论坛页：顶部且未折叠时无语义；滚动后出现；顶部但 collapseOffsetPx > 0 时也出现
            assertEquals("论坛顶栏未滚动且未折叠时不得暴露回到顶部", 0, onAllNodes(hasScrollToTopLabel).fetchSemanticsNodes().size)

            forumScrolled = true
            waitForIdle()
            assertEquals("论坛列表滚动后应暴露回到顶部", 1, onAllNodes(hasScrollToTopLabel).fetchSemanticsNodes().size)

            forumScrolled = false
            waitForIdle()
            assertEquals("论坛列表回到顶部且未折叠时应隐藏回到顶部", 0, onAllNodes(hasScrollToTopLabel).fetchSemanticsNodes().size)

            val barState = requireNotNull(forumTopBarScrollState)
            runOnIdle {
                barState.nestedScrollConnection.onPostScroll(
                    consumed = Offset(0f, -20f),
                    available = Offset.Zero,
                    source = NestedScrollSource.UserInput,
                )
            }
            waitForIdle()
            assertTrue("collapseOffsetPx 应 > 0", barState.collapseOffsetPx > 0f)
            assertEquals("论坛顶栏折叠偏移 > 0 时必须暴露回到顶部", 1, onAllNodes(hasScrollToTopLabel).fetchSemanticsNodes().size)

            onNode(hasScrollToTopLabel).performClick()
            waitForIdle()
            assertEquals(1, forumScrollClicks)
            assertFalse("展开后 collapseOffsetPx 应归零", barState.collapseOffsetPx > 0f)
            assertEquals("展开回零后应再次隐藏回到顶部", 0, onAllNodes(hasScrollToTopLabel).fetchSemanticsNodes().size)

            // 2. 帖子详情顶栏：顶部无语义，滚动后出现
            mode = 1
            waitForIdle()
            assertEquals("帖子详情顶栏未滚动时不得暴露回到顶部", 0, onAllNodes(hasScrollToTopLabel).fetchSemanticsNodes().size)
            detailScrolledState.value = true
            waitForIdle()
            assertEquals("帖子详情顶栏滚动后应暴露回到顶部", 1, onAllNodes(hasScrollToTopLabel).fetchSemanticsNodes().size)

            // 3. 搜索页顶栏：顶部无语义，滚动后出现
            mode = 2
            waitForIdle()
            assertEquals("搜索页列表在顶部时不得暴露回到顶部", 0, onAllNodes(hasScrollToTopLabel).fetchSemanticsNodes().size)
            val sState = requireNotNull(searchListState)
            runOnIdle { sState.requestScrollToItem(10, 12) }
            waitForIdle()
            assertEquals("搜索页列表滚动后应暴露回到顶部", 1, onAllNodes(hasScrollToTopLabel).fetchSemanticsNodes().size)
        }
}
