package net.jejer.hipda.feature.app

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import net.jejer.hipda.feature.common.ForumListSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * U5b 验收 3：侧栏态（933×696 三栏，showForumTabs == false）横向拖列表不切换、无偏移。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MultiPaneForumSwipeDisabledTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun paneMatcher(title: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun sidebarThreePane933x696_horizontalDragOnForumList_doesNotSwitchAndHasZeroOffset() {
        val harness = MultiPaneTestHarness()
        var touchSlop = 0f

        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            harness.RenderRoot()
        }
        composeRule.waitForIdle()

        assertEquals(ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)
        val listBounds = composeRule.onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot
        val initialRowLeft = composeRule.onNodeWithText("测试帖子标题 1001").fetchSemanticsNode().positionInRoot.x

        composeRule.mainClock.autoAdvance = false
        try {
            // 在中栏列表区（远离右侧分隔线 48dp 热区）大力向左拖动 50% 中栏宽
            composeRule.onRoot().performTouchInput {
                val startX = listBounds.left + listBounds.width * 0.70f
                val y = listBounds.top + listBounds.height * 0.55f
                down(Offset(startX, y))
                moveBy(Offset(-(touchSlop + 2f), 0f), delayMillis = 16L)
                repeat(6) {
                    moveBy(Offset(-(listBounds.width * 0.50f) / 6f, 0f), delayMillis = 24L)
                }
            }
            composeRule.mainClock.advanceTimeByFrame()
            val midDragRowLeft = composeRule.onNodeWithText("测试帖子标题 1001").fetchSemanticsNode().positionInRoot.x
            assertEquals("侧栏态拖动列表期间不得产生任何水平偏移", initialRowLeft, midDragRowLeft, 0.5f)

            composeRule.onRoot().performTouchInput {
                advanceEventTime(100L)
                up()
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()

        assertEquals(
            "侧栏态（showForumTabs == false）横向拖列表不得切换版块",
            ForumListSource.Forum(2),
            harness.hostViewModel.state.value.activeForumSource,
        )
        val settledRowLeft = composeRule.onNodeWithText("测试帖子标题 1001").fetchSemanticsNode().positionInRoot.x
        assertEquals("松手后列表位置必须保持不变", initialRowLeft, settledRowLeft, 0.5f)
    }
}
