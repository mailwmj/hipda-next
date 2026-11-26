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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import org.junit.Assert.assertEquals
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
}
