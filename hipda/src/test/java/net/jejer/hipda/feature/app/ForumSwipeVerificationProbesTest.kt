package net.jejer.hipda.feature.app

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
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
import net.jejer.hipda.data.settings.PaneSplit
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
import net.jejer.hipda.feature.forum.FORUM_TOP_BAR_TITLE_ROW_HEIGHT
import net.jejer.hipda.feature.forum.ForumPane
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.forum.adjacentForumSource
import net.jejer.hipda.feature.forum.resolveForumSwipeStep
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.threadlist.ThreadSummary
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.ProvideGlassEnvironment
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import net.jejer.hipda.ui.shell.calculateShellPaneLayout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ForumSwipeVerificationProbesTest {

    private fun paneMatcher(title: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    private fun verticalListMatcher(): SemanticsMatcher =
        SemanticsMatcher("hasVerticalScrollAxisRange") { SemanticsProperties.VerticalScrollAxisRange in it.config }

    private fun tabMatcher(label: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            SemanticsMatcher("tabText==$label") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == label
            }

    private fun ComposeUiTest.findForumVerticalListNode(): SemanticsNode? {
        val inListPane = onAllNodes(
            verticalListMatcher() and hasAnyAncestor(paneMatcher("列表")),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        if (inListPane.isNotEmpty()) return inListPane.first()
        return onAllNodes(verticalListMatcher(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
    }

    // ------------------------------------------------------------------------
    // Probe 0: 纯函数与阈值逻辑核查
    // ------------------------------------------------------------------------

    @Test
    fun probe0_adjacentForumSource_and_resolveForumSwipeStep_contracts() {
        val followed = listOf(2, 6, 7)
        assertEquals(ForumListSource.Forum(2), adjacentForumSource(ForumListSource.TodayNew, followed, +1))
        assertEquals(ForumListSource.Forum(6), adjacentForumSource(ForumListSource.Forum(2), followed, +1))
        assertEquals(ForumListSource.Forum(7), adjacentForumSource(ForumListSource.Forum(6), followed, +1))
        assertNull(adjacentForumSource(ForumListSource.Forum(7), followed, +1))

        assertNull(adjacentForumSource(ForumListSource.TodayNew, followed, -1))
        assertEquals(ForumListSource.TodayNew, adjacentForumSource(ForumListSource.Forum(2), followed, -1))
        assertEquals(ForumListSource.Forum(2), adjacentForumSource(ForumListSource.Forum(6), followed, -1))
        assertEquals(ForumListSource.Forum(6), adjacentForumSource(ForumListSource.Forum(7), followed, -1))

        assertNull(adjacentForumSource(ForumListSource.TodayNew, emptyList(), +1))
        assertNull(adjacentForumSource(ForumListSource.TodayNew, emptyList(), -1))
        assertNull(adjacentForumSource(ForumListSource.Forum(57), followed, +1))
        assertNull(adjacentForumSource(ForumListSource.Forum(57), followed, -1))
        assertNull(adjacentForumSource(ForumListSource.Forum(2), followed, 0))
        assertEquals(ForumListSource.Forum(6), adjacentForumSource(ForumListSource.Forum(2), followed, +5))
        assertEquals(ForumListSource.TodayNew, adjacentForumSource(ForumListSource.Forum(2), followed, -9))

        val width = 1000f
        val fling = 2000f
        val nearZero = 8f
        assertEquals(0, resolveForumSwipeStep(-250f, 0f, width, fling, nearZero))
        assertEquals(+1, resolveForumSwipeStep(-251f, 0f, width, fling, nearZero))
        assertEquals(-1, resolveForumSwipeStep(+251f, 0f, width, fling, nearZero))
        assertEquals(+1, resolveForumSwipeStep(-150f, -2100f, width, fling, nearZero))
        assertEquals(+1, resolveForumSwipeStep(+5f, -2100f, width, fling, nearZero))
        assertEquals(0, resolveForumSwipeStep(-300f, +2500f, width, fling, nearZero))
        assertEquals(0, resolveForumSwipeStep(+300f, -2500f, width, fling, nearZero))
    }

    // ------------------------------------------------------------------------
    // Probe 1: 逐帧核查（左/右滑全过程 boundsInRoot / positionInRoot / 首帧数据）
    // ------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe1_frameByFrame_leftAndRightSwipe_warmAndCold_noZeroFlashAndFirstFrameNewData() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(
                countsByFid = mapOf(2 to 20, 6 to 20, 7 to 20),
                todayNewCount = 15,
            )
            val windowSize = mutableStateOf(DpSize(411.dp, 891.dp))
            setContent {
                MultiPaneResizableWindowHost(windowSize.value) { harness.RenderRoot() }
            }
            waitForIdle()

            // 先预热 fid=6（Buy & Sell），验证带缓存的左右往返切换逐帧
            runOnIdle { harness.hostViewModel.selectForumSource(ForumListSource.Forum(6)) }
            waitForIdle()
            runOnIdle { harness.hostViewModel.selectForumSource(ForumListSource.Forum(2)) }
            waitForIdle()

            val densityPx = density.density
            val widthPx = 411f * densityPx

            data class FrameSample(
                val frame: Int,
                val activeSource: ForumListSource,
                val listPosX: Float?,
                val listBoundsLeft: Float?,
                val listBoundsRight: Float?,
                val hasFid2Item: Boolean,
                val hasFid6Item: Boolean,
                val hasFid7Item: Boolean,
            )

            fun sampleFrame(frame: Int): FrameSample {
                val listNode = findForumVerticalListNode()
                val has2 = onAllNodesWithText("版块2帖 1 [uid=42]", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                val has6 = onAllNodesWithText("版块6帖 1 [uid=42]", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                val has7 = onAllNodesWithText("版块7帖 1 [uid=42]", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                return FrameSample(
                    frame = frame,
                    activeSource = harness.hostViewModel.state.value.activeForumSource,
                    listPosX = listNode?.positionInRoot?.x,
                    listBoundsLeft = listNode?.boundsInRoot?.left,
                    listBoundsRight = listNode?.boundsInRoot?.right,
                    hasFid2Item = has2,
                    hasFid6Item = has6,
                    hasFid7Item = has7,
                )
            }

            // 1A: 左滑 Discovery(2) -> Buy & Sell(6)
            mainClock.autoAdvance = false
            val leftTrace = mutableListOf<FrameSample>()
            try {
                onRoot().performTouchInput {
                    val startY = height * 0.55f
                    down(Offset(width * 0.7f, startY))
                    repeat(8) {
                        moveBy(Offset(-width * 0.05f, 0f))
                        advanceEventTime(16L)
                    }
                    up()
                }
                for (f in 0 until 120) {
                    mainClock.advanceTimeByFrame()
                    val s = sampleFrame(f)
                    leftTrace += s
                    if (s.activeSource == ForumListSource.Forum(6) && s.listPosX != null && abs(s.listPosX) < 0.1f && f > 5) {
                        repeat(2) { extra ->
                            mainClock.advanceTimeByFrame()
                            leftTrace += sampleFrame(f + 1 + extra)
                        }
                        break
                    }
                }
            } finally {
                mainClock.autoAdvance = true
            }
            waitForIdle()

            val exitFramesLeft = leftTrace.filter { it.hasFid2Item && !it.hasFid6Item }
            val enterFramesLeft = leftTrace.filter { it.hasFid6Item }
            assertTrue("左滑应捕获退出阶段帧: $leftTrace", exitFramesLeft.isNotEmpty())
            assertTrue("左滑应捕获进入阶段帧: $leftTrace", enterFramesLeft.isNotEmpty())

            exitFramesLeft.forEach { s ->
                val x = s.listPosX
                if (x != null) {
                    assertTrue("左滑退场旧版块不得回 0，frame=${s.frame} posX=$x trace=$leftTrace", x < -10f)
                }
            }
            val firstEnterIndex = leftTrace.indexOfFirst { it.hasFid6Item }
            val lastExitIndex = leftTrace.indexOfLast { it.hasFid2Item }
            assertTrue("左滑旧版块必须先彻底退场再出现新版块: $leftTrace", lastExitIndex < firstEnterIndex)
            val transitionGapLeft = leftTrace.subList(lastExitIndex + 1, firstEnterIndex)
            assertTrue("左滑切源首帧必须直接显示新版块数据: gap=$transitionGapLeft trace=$leftTrace", transitionGapLeft.isEmpty())
            val firstEnterLeft = enterFramesLeft.first()
            val firstEnterLeftPosX = requireNotNull(firstEnterLeft.listPosX)
            assertTrue(
                "左滑进入首帧 positionInRoot.x 必须在右侧边（> 0.5 * widthPx=${widthPx * 0.5f}），不能在 0 闪现，实际=$firstEnterLeftPosX trace=$leftTrace",
                firstEnterLeftPosX > widthPx * 0.5f,
            )
            val firstVisibleBoundsFrame = enterFramesLeft.firstOrNull { (it.listPosX ?: widthPx) in 1f..(widthPx - 1f) }
            assertTrue(
                "左滑进入过程应有可见态 boundsInRoot.left > widthPx*0.5（或单调收至0），trace=$enterFramesLeft",
                firstVisibleBoundsFrame != null && (firstVisibleBoundsFrame.listBoundsLeft ?: 0f) > widthPx * 0.5f,
            )
            var sawNearZero = false
            enterFramesLeft.forEach { s ->
                val x = s.listPosX ?: return@forEach
                if (x in 1f..(widthPx - 1f)) {
                    assertEquals("可见入场期 boundsInRoot.left 应与 positionInRoot.x 一致", x, s.listBoundsLeft!!, 1f)
                }
                if (abs(x) <= 2f) {
                    sawNearZero = true
                } else if (sawNearZero && x > 10f) {
                    throw AssertionError("新版块在 0 处闪现后又跳回侧边: $enterFramesLeft")
                }
            }
            assertEquals("左滑终态 boundsInRoot.left 必须恰为 0", 0f, enterFramesLeft.last().listBoundsLeft!!, 0.5f)
            assertEquals("左滑终态 positionInRoot.x 必须恰为 0", 0f, enterFramesLeft.last().listPosX!!, 0.5f)

            // 1B: 右滑 Buy & Sell(6) -> Discovery(2)
            mainClock.autoAdvance = false
            val rightTrace = mutableListOf<FrameSample>()
            try {
                onRoot().performTouchInput {
                    val startY = height * 0.55f
                    down(Offset(width * 0.3f, startY))
                    repeat(8) {
                        moveBy(Offset(width * 0.05f, 0f))
                        advanceEventTime(16L)
                    }
                    up()
                }
                for (f in 0 until 120) {
                    mainClock.advanceTimeByFrame()
                    val s = sampleFrame(f)
                    rightTrace += s
                    if (s.activeSource == ForumListSource.Forum(2) && s.listPosX != null && abs(s.listPosX) < 0.1f && f > 5) {
                        repeat(2) { extra ->
                            mainClock.advanceTimeByFrame()
                            rightTrace += sampleFrame(f + 1 + extra)
                        }
                        break
                    }
                }
            } finally {
                mainClock.autoAdvance = true
            }
            waitForIdle()

            val exitFramesRight = rightTrace.filter { it.hasFid6Item && !it.hasFid2Item }
            val enterFramesRight = rightTrace.filter { it.hasFid2Item }
            assertTrue("右滑应捕获退出阶段帧: $rightTrace", exitFramesRight.isNotEmpty())
            assertTrue("右滑应捕获进入阶段帧: $rightTrace", enterFramesRight.isNotEmpty())
            val firstEnterRightIdx = rightTrace.indexOfFirst { it.hasFid2Item }
            val lastExitRightIdx = rightTrace.indexOfLast { it.hasFid6Item }
            assertTrue("右滑旧版块必须先彻底退场再出现新版块: $rightTrace", lastExitRightIdx < firstEnterRightIdx)
            val transitionGapRight = rightTrace.subList(lastExitRightIdx + 1, firstEnterRightIdx)
            assertTrue(
                "右滑切源首帧必须直接显示新版块数据（无空白中间帧）: gap=$transitionGapRight trace=$rightTrace",
                transitionGapRight.isEmpty(),
            )

            exitFramesRight.forEach { s ->
                val x = s.listPosX
                if (x != null) {
                    assertTrue("右滑退场旧版块不得回 0，frame=${s.frame} posX=$x", x > 10f)
                }
            }
            val firstEnterRight = enterFramesRight.first()
            val firstEnterRightX = requireNotNull(firstEnterRight.listPosX)
            assertTrue(
                "右滑进入首帧 positionInRoot.x 必须在左侧边（< -0.5 * widthPx），不能在 0 闪现，实际=$firstEnterRightX trace=$rightTrace",
                firstEnterRightX < -widthPx * 0.5f,
            )
            var sawNearZeroRight = false
            enterFramesRight.forEach { s ->
                val x = s.listPosX ?: return@forEach
                if (abs(x) <= 2f) {
                    sawNearZeroRight = true
                } else if (sawNearZeroRight && x < -10f) {
                    throw AssertionError("右滑新版块在 0 处闪现后又跳回左侧: $enterFramesRight")
                }
            }
            assertEquals("右滑终态 boundsInRoot.left 必须恰为 0", 0f, enterFramesRight.last().listBoundsLeft!!, 0.5f)
            assertEquals("右滑终态 positionInRoot.x 必须恰为 0", 0f, enterFramesRight.last().listPosX!!, 0.5f)

            // 1C: 冷切（未缓存版块 Geek Talks fid=7）：从 fid=6 左滑到未加载过的 fid=7
            runOnIdle { harness.hostViewModel.selectForumSource(ForumListSource.Forum(6)) }
            waitForIdle()
            mainClock.autoAdvance = false
            val coldTrace = mutableListOf<FrameSample>()
            try {
                onRoot().performTouchInput {
                    val startY = height * 0.55f
                    down(Offset(width * 0.7f, startY))
                    repeat(8) {
                        moveBy(Offset(-width * 0.05f, 0f))
                        advanceEventTime(16L)
                    }
                    up()
                }
                for (f in 0 until 120) {
                    mainClock.advanceTimeByFrame()
                    val s = sampleFrame(f)
                    coldTrace += s
                    if (s.activeSource == ForumListSource.Forum(7) && s.listPosX != null && abs(s.listPosX) < 0.1f && f > 5) {
                        break
                    }
                }
            } finally {
                mainClock.autoAdvance = true
            }
            waitForIdle()
            val coldEnter = coldTrace.filter { it.hasFid7Item }
            assertTrue("冷切应捕获进入帧: $coldTrace", coldEnter.isNotEmpty())
            val coldFirstEnterIdx = coldTrace.indexOfFirst { it.hasFid7Item }
            val coldLastExitIdx = coldTrace.indexOfLast { it.hasFid6Item }
            assertTrue("冷切旧版块必须先彻底退场: $coldTrace", coldLastExitIdx < coldFirstEnterIdx)
            assertTrue("冷切首帧 positionInRoot.x 应从右侧进入: $coldTrace", (coldEnter.first().listPosX ?: 0f) > widthPx * 0.5f)
            assertEquals(0f, coldEnter.last().listBoundsLeft!!, 0.5f)
        }

    // ------------------------------------------------------------------------
    // Probe 2: 滚动保持（专测旧数据夹断新 listState）
    // ------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe2b_saveableStateProvider_delayedEmissionOnReturn_neverClampsRestoredListState() =
        withAnimatorDurationScale(1f) {
            val gateFid2Reload = CompletableDeferred<Unit>()
            val harness = ProbeHarness(
                countsByFid = mapOf(2 to 30, 6 to 3, 7 to 30),
                todayNewCount = 2,
                refreshGateByFid = mapOf(2 to gateFid2Reload),
            )
            gateFid2Reload.complete(Unit)

            val listStatesBySource = LinkedHashMap<ForumListSource, LazyListState>()
            var activeSource by mutableStateOf<ForumListSource>(ForumListSource.Forum(2))

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT) {
                    val holder = rememberSaveableStateHolder()
                    holder.SaveableStateProvider(key = forumSaveableKey(activeSource)) {
                        val ls = rememberLazyListState()
                        listStatesBySource[activeSource] = ls
                        ForumPane(
                            source = activeSource,
                            chromeSpec = ForumChromeSpec(showForumTabs = true, useGlassTopBar = false),
                            selectedTid = null,
                            onSelectSource = { activeSource = it },
                            onOpenThread = {},
                            onOpenLogin = {},
                            listState = ls,
                            viewModel = harness.forumViewModel,
                        )
                    }
                }
            }
            waitForIdle()

            val discState = requireNotNull(listStatesBySource[ForumListSource.Forum(2)])
            runOnIdle { discState.requestScrollToItem(10, 42) }
            waitForIdle()
            assertEquals(10, discState.firstVisibleItemIndex)

            onRoot().performTouchInput {
                down(Offset(width * 0.75f, height * 0.55f))
                repeat(8) {
                    moveBy(Offset(-width * 0.055f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals(ForumListSource.Forum(6), activeSource)
            onNodeWithText("版块6帖 3 [uid=42]").assertIsDisplayed()

            mainClock.autoAdvance = false
            try {
                onRoot().performTouchInput {
                    down(Offset(width * 0.25f, height * 0.55f))
                    repeat(8) {
                        moveBy(Offset(width * 0.055f, 0f))
                        advanceEventTime(16L)
                    }
                    up()
                }
                repeat(90) {
                    mainClock.advanceTimeByFrame()
                    if (activeSource == ForumListSource.Forum(2)) {
                        val restored = listStatesBySource[ForumListSource.Forum(2)]
                        if (restored != null) {
                            assertEquals(
                                "回切途中的每一帧 Discovery 的 firstVisibleItemIndex 都不得被旧 3 条数据夹断",
                                10,
                                restored.firstVisibleItemIndex,
                            )
                        }
                    }
                }
            } finally {
                mainClock.autoAdvance = true
            }
            waitForIdle()
            val finalDisc = requireNotNull(listStatesBySource[ForumListSource.Forum(2)])
            assertEquals(10, finalDisc.firstVisibleItemIndex)
            assertEquals(42, finalDisc.firstVisibleItemScrollOffset)
        }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe2_realContainer_discoveryScroll10_swipeToThreeItemForumAndBack_preservesIndex10() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(
                countsByFid = mapOf(2 to 30, 6 to 3, 7 to 30),
                todayNewCount = 2,
            )
            val listStatesBySource = LinkedHashMap<ForumListSource, LazyListState>()

            setContent {
                harness.RenderMultiPaneDirect(onListStateSeen = { src, state ->
                    listStatesBySource[src] = state
                })
            }
            waitForIdle()

            val discState = requireNotNull(listStatesBySource[ForumListSource.Forum(2)])
            runOnIdle { discState.requestScrollToItem(10, 28) }
            waitForIdle()
            assertEquals(10, discState.firstVisibleItemIndex)
            assertEquals(28, discState.firstVisibleItemScrollOffset)

            // 左滑切换到只有 3 条的 Buy & Sell (fid=6)
            onRoot().performTouchInput {
                down(Offset(width * 0.75f, height * 0.55f))
                repeat(8) {
                    moveBy(Offset(-width * 0.055f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals(ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)
            onNode(tabMatcher("Buy & Sell")).assertIsSelected()
            onNodeWithText("版块6帖 1 [uid=42]").assertIsDisplayed()
            onNodeWithText("版块6帖 3 [uid=42]").assertIsDisplayed()

            // 再右滑切回 Discovery (fid=2)
            onRoot().performTouchInput {
                down(Offset(width * 0.25f, height * 0.55f))
                repeat(8) {
                    moveBy(Offset(width * 0.055f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals(ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)
            onNode(tabMatcher("Discovery")).assertIsSelected()
            val discStateAfterBack = requireNotNull(listStatesBySource[ForumListSource.Forum(2)])
            assertEquals(
                "滑到 3 条版块再滑回后 Discovery 必须仍在第 10 项，不能被旧数据夹断",
                10,
                discStateAfterBack.firstVisibleItemIndex,
            )
            assertEquals(28, discStateAfterBack.firstVisibleItemScrollOffset)

            // 再右滑到只有 2 条的 今日新帖 (TodayNew) 再左滑回 Discovery
            onRoot().performTouchInput {
                down(Offset(width * 0.25f, height * 0.55f))
                repeat(8) {
                    moveBy(Offset(width * 0.055f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals(ForumListSource.TodayNew, harness.hostViewModel.state.value.activeForumSource)
            onNode(tabMatcher("今日新帖")).assertIsSelected()
            onNodeWithText("新帖标题 1").assertIsDisplayed()

            onRoot().performTouchInput {
                down(Offset(width * 0.75f, height * 0.55f))
                repeat(8) {
                    moveBy(Offset(-width * 0.055f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals(ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)
            assertEquals(
                "从 2 条今日新帖滑回 Discovery 后仍必须保留第 10 项",
                10,
                requireNotNull(listStatesBySource[ForumListSource.Forum(2)]).firstVisibleItemIndex,
            )
        }

    // ------------------------------------------------------------------------
    // Probe 3: 中断（点标签、侧栏、账号切换重载、旋转重组、ACTION_CANCEL、边界阻尼与空/错误态）
    // ------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe3a_interruptByTabClickAndSidebarSwitch_midDragAndMidExit_resetsOffsetToZero() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(countsByFid = mapOf(2 to 20, 6 to 20, 7 to 20))
            setContent { harness.RenderRoot() }
            waitForIdle()

            val geekTabCenter = onNode(tabMatcher("Geek Talks")).fetchSemanticsNode().boundsInRoot.center

            // 1. 拖到一半（手指仍按住，约 -35%）时多指点击另一标签「Geek Talks」
            mainClock.autoAdvance = false
            try {
                onRoot().performTouchInput {
                    down(0, Offset(width * 0.75f, height * 0.55f))
                    repeat(7) {
                        moveBy(0, Offset(-width * 0.05f, 0f))
                        advanceEventTime(16L)
                    }
                }
                mainClock.advanceTimeByFrame()
                val midDragPosX = requireNotNull(findForumVerticalListNode()?.positionInRoot?.x)
                assertTrue("拖动中列表应有明显左移，实际=$midDragPosX", midDragPosX < -50f)

                onRoot().performTouchInput {
                    down(1, geekTabCenter)
                    advanceEventTime(16L)
                    up(1)
                    advanceEventTime(16L)
                    up(0)
                }
            } finally {
                mainClock.autoAdvance = true
            }
            waitForIdle()
            assertEquals(ForumListSource.Forum(7), harness.hostViewModel.state.value.activeForumSource)
            assertEquals("点标签切源后偏移必须归 0", 0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
            onNodeWithText("版块7帖 1 [uid=42]").assertIsDisplayed()

            // 2. 滑出动画进行到一半（isExiting == true）时，通过侧栏 API 外部切源到 TodayNew
            mainClock.autoAdvance = false
            try {
                onRoot().performTouchInput {
                    down(Offset(width * 0.25f, height * 0.55f))
                    repeat(8) {
                        moveBy(Offset(width * 0.05f, 0f))
                        advanceEventTime(16L)
                    }
                    up()
                }
                mainClock.advanceTimeByFrame()
                mainClock.advanceTimeByFrame()
                runOnUiThread {
                    harness.hostViewModel.selectSidebarEntry(ShellSidebarEntry.TodayNew)
                }
            } finally {
                mainClock.autoAdvance = true
            }
            waitForIdle()
            assertEquals(ForumListSource.TodayNew, harness.hostViewModel.state.value.activeForumSource)
            assertEquals("退场中被侧栏打断切源后偏移必须为 0", 0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
            onNodeWithText("新帖标题 1").assertIsDisplayed()

            // 3. 拖动中点击当前已选中的同一标签（「今日新帖」）也应复位偏移至 0
            val todayTabCenter = onNode(tabMatcher("今日新帖")).fetchSemanticsNode().boundsInRoot.center
            onRoot().performTouchInput {
                down(0, Offset(width * 0.75f, height * 0.55f))
                repeat(5) {
                    moveBy(0, Offset(-width * 0.05f, 0f))
                    advanceEventTime(16L)
                }
                down(1, todayTabCenter)
                advanceEventTime(16L)
                up(1)
                advanceEventTime(16L)
                up(0)
            }
            waitForIdle()
            assertEquals(ForumListSource.TodayNew, harness.hostViewModel.state.value.activeForumSource)
            assertEquals("拖动中点击当前标签后偏移也必须归 0", 0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
        }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe3e_midEnterAnimation_grabAndContinueOrRelease_settlesCleanlyAtZero() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(countsByFid = mapOf(2 to 20, 6 to 20, 7 to 20))
            setContent { harness.RenderRoot() }
            waitForIdle()

            mainClock.autoAdvance = false
            try {
                // 先左滑切换到 fid=6，推进到入场弹簧中途
                onRoot().performTouchInput {
                    down(Offset(width * 0.75f, height * 0.55f))
                    repeat(8) {
                        moveBy(Offset(-width * 0.055f, 0f))
                        advanceEventTime(16L)
                    }
                    up()
                }
                var enteredMid = false
                for (f in 0 until 80) {
                    mainClock.advanceTimeByFrame()
                    val has6 = onAllNodesWithText("版块6帖 1 [uid=42]", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    val posX = findForumVerticalListNode()?.positionInRoot?.x ?: 0f
                    if (has6 && posX in 40f..350f) {
                        enteredMid = true
                        break
                    }
                }
                assertTrue("应捕获到 fid=6 入场中途帧", enteredMid)

                // 在入场中途按住并继续向左推过阈值（从正偏移跨过 0 到 < -25% 屏宽）切换至 fid=7
                onRoot().performTouchInput {
                    down(Offset(width * 0.85f, height * 0.55f))
                    repeat(10) {
                        moveBy(Offset(-width * 0.075f, 0f))
                        advanceEventTime(16L)
                    }
                    up()
                }
            } finally {
                mainClock.autoAdvance = true
            }
            waitForIdle()
            assertEquals(ForumListSource.Forum(7), harness.hostViewModel.state.value.activeForumSource)
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
            onNodeWithText("版块7帖 1 [uid=42]").assertIsDisplayed()
        }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe3b_1_interruptByAccountSwitchReload_midDrag_resetsOffsetToZero() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(countsByFid = mapOf(2 to 20, 6 to 20, 7 to 20))
            setContent { harness.RenderRoot() }
            waitForIdle()
            onNodeWithText("版块2帖 1 [uid=42]").assertIsDisplayed()

            // 1. 拖到一半（-35%，手指仍按住）时账号切换导致重载
            onRoot().performTouchInput {
                down(Offset(width * 0.7f, height * 0.55f))
                repeat(7) {
                    moveBy(Offset(-width * 0.05f, 0f))
                    advanceEventTime(16L)
                }
            }
            waitForIdle()
            val midOffset = findForumVerticalListNode()!!.positionInRoot.x
            assertTrue("账号切换前应处于拖动偏移态，实际=$midOffset", midOffset < -50f)

            runOnIdle { harness.switchAccount(uid = "99", username = "User99") }
            waitForIdle()
            onNodeWithText("版块2帖 1 [uid=99]").assertIsDisplayed()
            val offsetAfterAccountSwitchWhileHeld = findForumVerticalListNode()!!.positionInRoot.x
            assertEquals(
                "账号切换重载后即使未抬手也不得残留半偏移，实际=$offsetAfterAccountSwitchWhileHeld",
                0f,
                offsetAfterAccountSwitchWhileHeld,
                0.5f,
            )

            onRoot().performTouchInput { up() }
            waitForIdle()
            assertEquals(ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
        }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe3b_2_interruptByAccountSwitchReload_midExitAnimation_cancelsStaleSwitchAndResetsOffset() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(countsByFid = mapOf(2 to 20, 6 to 20, 7 to 20))
            setContent { harness.RenderRoot() }
            waitForIdle()
            onNodeWithText("版块2帖 1 [uid=42]").assertIsDisplayed()

            // 2. 退场动画途中账号切换，不应继续把版块切走
            mainClock.autoAdvance = false
            try {
                onRoot().performTouchInput {
                    down(Offset(width * 0.7f, height * 0.55f))
                    repeat(8) {
                        moveBy(Offset(-width * 0.05f, 0f))
                        advanceEventTime(16L)
                    }
                    up()
                }
                mainClock.advanceTimeByFrame()
                mainClock.advanceTimeByFrame()
                harness.switchAccount(uid = "108", username = "User108")
            } finally {
                mainClock.autoAdvance = true
            }
            waitForIdle()
            assertEquals(
                "退场动画途中账号切换重载后不应继续完成过期版块切换",
                ForumListSource.Forum(2),
                harness.hostViewModel.state.value.activeForumSource,
            )
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
            onNodeWithText("版块2帖 1 [uid=108]").assertIsDisplayed()
        }

    @Test
    @Config(qualifiers = "w933dp-h891dp-xhdpi")
    fun probe3c_interruptByRotationWindowResizeAndRecompositionMidDrag_resetsCleanlyNoCrash() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(countsByFid = mapOf(2 to 20, 6 to 20, 7 to 20))
            val windowSize = mutableStateOf(DpSize(411.dp, 891.dp))
            setContent {
                MultiPaneResizableWindowHost(windowSize.value) { harness.RenderRoot() }
            }
            waitForIdle()

            // 1. 411x891 单栏拖到一半 -> 展开旋转到 933x696 三栏（showForumTabs == false）
            onRoot().performTouchInput {
                down(Offset(width * 0.7f, height * 0.55f))
                repeat(6) {
                    moveBy(Offset(-width * 0.05f, 0f))
                    advanceEventTime(16L)
                }
            }
            waitForIdle()
            assertTrue(findForumVerticalListNode()!!.positionInRoot.x < -40f)

            windowSize.value = DpSize(933.dp, 696.dp)
            waitForIdle()
            val listBoundsThreePane = onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot
            val listNodeThreePane = findForumVerticalListNode()!!
            assertEquals(
                "旋转展开至无标签三栏态后列表左缘必须对齐中栏左缘无偏移残留",
                listBoundsThreePane.left,
                listNodeThreePane.positionInRoot.x,
                1f,
            )
            assertEquals(ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)

            onRoot().performTouchInput { up() }
            waitForIdle()
            windowSize.value = DpSize(411.dp, 891.dp)
            waitForIdle()
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
            assertEquals(ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)

            // 2. 拖动中保持 showForumTabs == true（旋转到矮屏 599x400 + 强制重组切深色主题），取消后归 0
            onRoot().performTouchInput {
                down(Offset(width * 0.7f, height * 0.55f))
                repeat(4) {
                    moveBy(Offset(-width * 0.04f, 0f))
                    advanceEventTime(16L)
                }
            }
            windowSize.value = DpSize(599.dp, 400.dp)
            runOnIdle {
                harness.settingsRepo.updateSettings { it.copy(themeMode = ThemeMode.DARK) }
            }
            waitForIdle()
            onRoot().performTouchInput { cancel() }
            waitForIdle()
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
            assertEquals(ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)
        }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe3d_actionCancelOver25Percent_realMotionEventCancel_noNeighborDamping_emptyAndErrorSwipe() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(
                countsByFid = mapOf(2 to 20, 6 to 0, 7 to 20),
                errorFids = setOf(7),
            )
            var rootView: android.view.View? = null
            setContent {
                rootView = LocalView.current
                harness.RenderRoot()
            }
            waitForIdle()

            // 1. 左拖 40% (>25%) 后 Compose cancel()：绝对不能切换，必须归 0
            onRoot().performTouchInput {
                down(Offset(width * 0.75f, height * 0.55f))
                repeat(8) {
                    moveBy(Offset(-width * 0.05f, 0f))
                    advanceEventTime(16L)
                }
                cancel()
            }
            waitForIdle()
            assertEquals(ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)

            // 2. 真实 Android MotionEvent.ACTION_CANCEL 在左拖 40% 后派发给根 View：同样不能切换且偏移归 0
            val view = requireNotNull(rootView)
            val densityPx = density.density
            val downTime = SystemClock.uptimeMillis()
            val startX = 320f * densityPx
            val y = 450f * densityPx
            runOnUiThread {
                val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, y, 0)
                view.dispatchTouchEvent(down)
                down.recycle()
                for (step in 1..8) {
                    val t = downTime + step * 16L
                    val ev = MotionEvent.obtain(downTime, t, MotionEvent.ACTION_MOVE, startX - step * 21f * densityPx, y, 0)
                    view.dispatchTouchEvent(ev)
                    ev.recycle()
                }
            }
            waitForIdle()
            assertTrue("真实拖移 40% 后列表应左移", findForumVerticalListNode()!!.positionInRoot.x < -80f)
            runOnUiThread {
                val cancelEv = MotionEvent.obtain(downTime, downTime + 160L, MotionEvent.ACTION_CANCEL, startX - 168f * densityPx, y, 0)
                view.dispatchTouchEvent(cancelEv)
                cancelEv.recycle()
            }
            waitForIdle()
            assertEquals("ACTION_CANCEL 不得触发切换", ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)
            assertEquals("ACTION_CANCEL 后偏移必须归 0", 0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)

            // 3. 首项 TodayNew 向右拖（无邻居阻尼 ~0.3 倍）松手回弹
            runOnIdle { harness.hostViewModel.selectForumSource(ForumListSource.TodayNew) }
            waitForIdle()
            val dragDistPx = 160f * densityPx
            onRoot().performTouchInput {
                down(Offset(width * 0.25f, height * 0.55f))
                repeat(8) {
                    moveBy(Offset(dragDistPx / 8f, 0f))
                    advanceEventTime(16L)
                }
            }
            waitForIdle()
            val dampedOffset = findForumVerticalListNode()!!.positionInRoot.x
            assertTrue("无邻居方向应明显阻尼（<= 0.45 * drag），实际=$dampedOffset", dampedOffset in 1f..(dragDistPx * 0.45f))
            onRoot().performTouchInput { up() }
            waitForIdle()
            assertEquals(ForumListSource.TodayNew, harness.hostViewModel.state.value.activeForumSource)
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)

            // 4. 空版块（fid=6）与错误态（fid=7）上横滑仍能切走
            runOnIdle { harness.hostViewModel.selectForumSource(ForumListSource.Forum(6)) }
            waitForIdle()
            onNodeWithText("暂无帖子").assertIsDisplayed()
            onRoot().performTouchInput {
                down(Offset(width * 0.75f, height * 0.55f))
                repeat(8) {
                    moveBy(Offset(-width * 0.055f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals("空版块左滑应切至 fid=7", ForumListSource.Forum(7), harness.hostViewModel.state.value.activeForumSource)
            onNodeWithText("网络连接失败", substring = true).assertIsDisplayed()
            onNodeWithText("重试").assertIsDisplayed()

            onRoot().performTouchInput {
                down(Offset(width * 0.25f, height * 0.55f))
                repeat(8) {
                    moveBy(Offset(width * 0.055f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals("错误态右滑应切回 fid=6", ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)
        }

    // ------------------------------------------------------------------------
    // Probe 4: 手势竞争与交互边界
    // ------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe4a_diagonalScrollDySlightlyGreaterThanDx_onlyScrollsList_neverSwipesOrOffsets() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(countsByFid = mapOf(2 to 30, 6 to 30, 7 to 30))
            val listStates = LinkedHashMap<ForumListSource, LazyListState>()
            setContent {
                harness.RenderMultiPaneDirect(onListStateSeen = { src, state -> listStates[src] = state })
            }
            waitForIdle()

            val discState = requireNotNull(listStates[ForumListSource.Forum(2)])
            assertEquals(0, discState.firstVisibleItemIndex)
            val densityPx = density.density

            mainClock.autoAdvance = false
            try {
                onRoot().performTouchInput {
                    down(Offset(width * 0.7f, height * 0.65f))
                    repeat(12) {
                        moveBy(Offset(-12f * densityPx, -15f * densityPx))
                        advanceEventTime(16L)
                        mainClock.advanceTimeByFrame()
                        assertEquals(
                            "斜向滑动(dy略大于dx)全过程不得产生横向列表偏移",
                            0f,
                            findForumVerticalListNode()!!.positionInRoot.x,
                            0.5f,
                        )
                    }
                    up()
                }
            } finally {
                mainClock.autoAdvance = true
            }
            waitForIdle()

            assertEquals(ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)
            assertTrue(
                "斜向(dy>dx)滑动必须正常竖向滚动列表，index=${discState.firstVisibleItemIndex} offset=${discState.firstVisibleItemScrollOffset}",
                discState.firstVisibleItemIndex > 0 || discState.firstVisibleItemScrollOffset > 0,
            )
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
        }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe4b_horizontalSwipeOnRow_neverTriggersClickOrLongClick_whileTapAndLongClickStillWork() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(countsByFid = mapOf(2 to 20, 6 to 20, 7 to 20))
            setContent { harness.RenderRoot() }
            waitForIdle()

            val row2 = onNodeWithText("版块2帖 2 [uid=42]").assertIsDisplayed()
            val rowCenterY = row2.fetchSemanticsNode().boundsInRoot.center.y

            // 1. 快速在行上横滑 40%：切版块且绝不打开帖子
            onRoot().performTouchInput {
                down(Offset(width * 0.75f, rowCenterY))
                repeat(8) {
                    moveBy(Offset(-width * 0.055f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals(ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)
            assertNull("在行上横滑不得触发点击打开帖子", harness.hostViewModel.state.value.selectedThread)

            // 2. 在 fid=6 行上慢速横滑（超过 longPressTimeout > 600ms）不触发长按
            val row6 = onNodeWithText("版块6帖 2 [uid=42]").assertIsDisplayed()
            val row6CenterY = row6.fetchSemanticsNode().boundsInRoot.center.y
            val densityPx = density.density
            onRoot().performTouchInput {
                down(Offset(width * 0.5f, row6CenterY))
                moveBy(Offset(32f * densityPx, 0f))
                advanceEventTime(650L)
                moveBy(Offset(20f * densityPx, 0f))
                advanceEventTime(50L)
                up()
            }
            waitForIdle()
            assertNull("在行上慢速横滑（>600ms）不得触发长按进帖", harness.hostViewModel.state.value.selectedThread)

            // 3. 常规点击与长按仍正常工作
            onNodeWithText("版块6帖 3 [uid=42]").performClick()
            waitForIdle()
            assertEquals("6003", harness.hostViewModel.state.value.selectedThread?.tid)
            assertFalse(harness.hostViewModel.state.value.selectedThread?.openLastPage ?: true)

            onNodeWithContentDescription("返回").performClick()
            waitForIdle()
            onNodeWithText("版块6帖 4 [uid=42]").performTouchInput { longClick() }
            waitForIdle()
            assertEquals("6004", harness.hostViewModel.state.value.selectedThread?.tid)
            assertEquals("6004_25", harness.hostViewModel.state.value.selectedThread?.targetPid)
        }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe4c_pullToRefreshDownwardSwipe_notSwallowed() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(countsByFid = mapOf(2 to 20, 6 to 20, 7 to 20))
            setContent { harness.RenderRoot() }
            waitForIdle()

            val refreshesBefore = harness.refreshCalls.get()
            val densityPx = density.density
            onRoot().performTouchInput {
                down(Offset(width * 0.5f, height * 0.4f))
                repeat(10) {
                    moveBy(Offset(0f, 24f * densityPx))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertTrue(
                "下拉刷新不得被横滑手势吞掉，before=$refreshesBefore after=${harness.refreshCalls.get()}",
                harness.refreshCalls.get() > refreshesBefore,
            )
            assertEquals(ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
        }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe4d_swipeOnTopBarAndTabRow_bothExpandedAndCollapsed_andScrollableTabs_neverSwitches() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(countsByFid = mapOf(2 to 30, 6 to 30, 7 to 30, 59 to 30, 12 to 30, 57 to 30, 63 to 30, 5 to 30, 25 to 30))
            var touchSlop = 0f
            setContent {
                touchSlop = LocalViewConfiguration.current.touchSlop
                harness.RenderRoot()
            }
            waitForIdle()

            val titleMatcher = hasText("Discovery") and SemanticsMatcher("notTab") {
                it.config.getOrNull(SemanticsProperties.Role) != Role.Tab
            }
            val titleBounds = onNode(titleMatcher).fetchSemanticsNode().boundsInRoot
            val tabBounds = onNode(tabMatcher("Buy & Sell")).fetchSemanticsNode().boundsInRoot

            // A. 展开态：顶栏标题上横滑、均衡 4 标签行上横滑都不切换
            onRoot().performTouchInput {
                down(Offset(width * 0.75f, titleBounds.center.y))
                repeat(8) {
                    moveBy(Offset(-width * 0.06f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals("展开态顶栏标题上横滑不得切版块", ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)

            onRoot().performTouchInput {
                down(Offset(width * 0.75f, tabBounds.center.y))
                repeat(8) {
                    moveBy(Offset(-width * 0.06f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals("展开态等分标签行上横滑不得切版块", ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)

            // B. 折叠态：向下滚动折叠上行顶栏，确认「发新帖」已折叠、标签行吸顶
            val titleRowPx = with(density) { FORUM_TOP_BAR_TITLE_ROW_HEIGHT.toPx() }
            onNode(verticalListMatcher()).performTouchInput {
                down(center)
                moveBy(Offset(0f, -touchSlop))
                moveBy(Offset(0f, -(titleRowPx + 140f)))
                advanceEventTime(200L)
                moveBy(Offset.Zero)
                up()
            }
            waitForIdle()
            assertTrue(
                "顶栏上行应已折叠",
                onNodeWithContentDescription("发新帖").fetchSemanticsNode().boundsInRoot.bottom <= 1f,
            )

            val collapsedTabBounds = onNode(tabMatcher("Buy & Sell")).fetchSemanticsNode().boundsInRoot
            onRoot().performTouchInput {
                down(Offset(width * 0.75f, collapsedTabBounds.center.y))
                repeat(8) {
                    moveBy(Offset(-width * 0.06f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals("折叠态吸顶标签行上横滑不得切版块", ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)

            // C. 多标签横向滚动模式下在标签行横滑同样不得切版块
            runOnIdle {
                harness.settingsRepo.updateSettings { it.copy(followedForums = listOf(2, 6, 7, 59, 12, 57, 63, 5, 25)) }
            }
            waitForIdle()
            val scrollableTabBounds = onNode(tabMatcher("Buy & Sell")).fetchSemanticsNode().boundsInRoot
            onRoot().performTouchInput {
                down(Offset(width * 0.7f, scrollableTabBounds.center.y))
                repeat(8) {
                    moveBy(Offset(-width * 0.055f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals("可滚多标签行上横滑不得切版块", ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)

            // D. 折叠态下，在吸顶标签行下方的列表区域横滑仍可正常切换版块
            onRoot().performTouchInput {
                down(Offset(width * 0.75f, collapsedTabBounds.bottom + 80f))
                repeat(8) {
                    moveBy(Offset(-width * 0.055f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals("折叠态下方列表区横滑应正常切到 fid=6", ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)
        }

    @Test
    @Config(qualifiers = "w800dp-h696dp-xhdpi")
    fun probe4e_hingeTwoPane_swipeWorksInListPane_ignoredInDetailPane() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(countsByFid = mapOf(2 to 20, 6 to 20, 7 to 20))
            val densityPx = density.density
            val hingeFlow = flowOf(verticalHingeLayoutInfo(leftDp = 390f, rightDp = 410f, heightDp = 696f, density = densityPx, isSeparating = true))

            withWindowLayoutFlow(hingeFlow) {
                setContent {
                    MultiPaneResizableWindowHost(DpSize(800.dp, 696.dp)) { harness.RenderRoot() }
                }
                waitForIdle()

                onNodeWithText("版块2帖 1 [uid=42]").performClick()
                waitForIdle()
                assertEquals("2001", harness.hostViewModel.state.value.selectedThread?.tid)

                val listPaneBounds = onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot
                val detailPaneBounds = onNode(paneMatcher("正文")).fetchSemanticsNode().boundsInRoot
                onNode(tabMatcher("Buy & Sell")).assertIsDisplayed()

                // 1. 详情栏内横滑不触发版块切换
                onRoot().performTouchInput {
                    val y = detailPaneBounds.center.y
                    down(Offset(detailPaneBounds.left + detailPaneBounds.width * 0.75f, y))
                    repeat(8) {
                        moveBy(Offset(-detailPaneBounds.width * 0.06f, 0f))
                        advanceEventTime(16L)
                    }
                    up()
                }
                waitForIdle()
                assertEquals("铰链双栏详情栏内横滑不得切换版块", ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)

                // 2. 列表栏内横滑可正常切到 Buy & Sell (fid=6)
                onRoot().performTouchInput {
                    val y = listPaneBounds.center.y
                    down(Offset(listPaneBounds.left + listPaneBounds.width * 0.75f, y))
                    repeat(8) {
                        moveBy(Offset(-listPaneBounds.width * 0.06f, 0f))
                        advanceEventTime(16L)
                    }
                    up()
                }
                waitForIdle()
                assertEquals("铰链双栏列表栏内横滑应切换到 fid=6", ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)
                onNodeWithText("版块6帖 1 [uid=42]").assertIsDisplayed()
            }
        }

    @Test
    @Config(qualifiers = "w933dp-h696dp-xhdpi")
    fun probe4f_threePaneSidebarMode_swipeDisabledInListPane_andDividerDragPreserved() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(countsByFid = mapOf(2 to 20, 6 to 20, 7 to 20))
            setContent {
                MultiPaneResizableWindowHost(DpSize(933.dp, 696.dp)) { harness.RenderRoot() }
            }
            waitForIdle()

            onNode(tabMatcher("Buy & Sell") and hasAnyAncestor(paneMatcher("列表"))).assertDoesNotExist()
            val listPaneBounds = onNode(paneMatcher("列表")).fetchSemanticsNode().boundsInRoot
            onRoot().performTouchInput {
                val y = listPaneBounds.center.y
                down(Offset(listPaneBounds.left + listPaneBounds.width * 0.75f, y))
                repeat(8) {
                    moveBy(Offset(-listPaneBounds.width * 0.06f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals("侧栏三栏态下横滑列表不得切版块", ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)
            assertEquals(listPaneBounds.left, findForumVerticalListNode()!!.positionInRoot.x, 1f)
        }

    // ------------------------------------------------------------------------
    // Round 1 Probes (合并态复验轮 1 新增探针：probe5 - probe7)
    // ------------------------------------------------------------------------

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe5_interruptByAccountSwitchReload_midEnterAnimation_and_immediateRedrag_tracksAndSwitches() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(countsByFid = mapOf(2 to 20, 6 to 20, 7 to 20))
            setContent { harness.RenderRoot() }
            waitForIdle()
            onNodeWithText("版块2帖 1 [uid=42]").assertIsDisplayed()

            val widthPx = 411f * density.density

            // 1. 左滑触发切换后，在版块 6 入场动画中途执行账号切换（uid 42 -> 112）
            mainClock.autoAdvance = false
            var sawMidEnter = false
            try {
                onRoot().performTouchInput {
                    down(Offset(width * 0.75f, height * 0.55f))
                    repeat(8) {
                        moveBy(Offset(-width * 0.05f, 0f))
                        advanceEventTime(16L)
                    }
                    up()
                }
                for (f in 0 until 80) {
                    mainClock.advanceTimeByFrame()
                    val src = harness.hostViewModel.state.value.activeForumSource
                    val x = findForumVerticalListNode()?.positionInRoot?.x ?: continue
                    if (src == ForumListSource.Forum(6) && x in (widthPx * 0.15f)..(widthPx * 0.9f)) {
                        sawMidEnter = true
                        harness.switchAccount(uid = "112", username = "User112")
                        break
                    }
                }
            } finally {
                mainClock.autoAdvance = true
            }
            assertTrue("应捕获到 fid=6 的入场中途帧后再触发账号切换", sawMidEnter)
            waitForIdle()
            assertEquals("入场途中账号切换应留在当前 fid=6", ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)
            onNodeWithText("版块6帖 1 [uid=112]").assertIsDisplayed()
            assertEquals("入场途切号后偏移应立刻归零", 0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)

            // 2. 切号后立即再次左拖：先确认 -18% 跟手，再继续拖过阈值切换到 fid=7
            onRoot().performTouchInput {
                down(Offset(width * 0.75f, height * 0.55f))
                repeat(4) {
                    moveBy(Offset(-width * 0.045f, 0f))
                    advanceEventTime(16L)
                }
            }
            waitForIdle()
            val followPosX = findForumVerticalListNode()!!.positionInRoot.x
            assertTrue("切号后立即再拖应正常跟手左移，实际=$followPosX", followPosX < -widthPx * 0.08f)
            onRoot().performTouchInput {
                repeat(5) {
                    moveBy(Offset(-width * 0.05f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals("切号后立即继续拖过阈值应正常切到 fid=7", ForumListSource.Forum(7), harness.hostViewModel.state.value.activeForumSource)
            onNodeWithText("版块7帖 1 [uid=112]").assertIsDisplayed()
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)

            // 3. 拖到一半（-30%）按住时切号（112 -> 113），松手后立即右滑跟手并切回 fid=6
            onRoot().performTouchInput {
                down(Offset(width * 0.7f, height * 0.55f))
                repeat(6) {
                    moveBy(Offset(-width * 0.05f, 0f))
                    advanceEventTime(16L)
                }
            }
            waitForIdle()
            runOnIdle { harness.switchAccount(uid = "113", username = "User113") }
            waitForIdle()
            onNodeWithText("版块7帖 1 [uid=113]").assertIsDisplayed()
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
            onRoot().performTouchInput { up() }
            waitForIdle()

            onRoot().performTouchInput {
                down(Offset(width * 0.25f, height * 0.55f))
                repeat(4) {
                    moveBy(Offset(width * 0.045f, 0f))
                    advanceEventTime(16L)
                }
            }
            waitForIdle()
            val rightFollowPosX = findForumVerticalListNode()!!.positionInRoot.x
            assertTrue("拖中切号抬手后立即右滑应正常跟手，实际=$rightFollowPosX", rightFollowPosX > widthPx * 0.08f)
            onRoot().performTouchInput {
                repeat(5) {
                    moveBy(Offset(width * 0.05f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals("立即反向右滑过阈值应切回 fid=6", ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)
            onNodeWithText("版块6帖 1 [uid=113]").assertIsDisplayed()
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
        }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe6_merged48dpTopBar_expandedAndCollapsed_startTouchFilterBoundaryPlusMinus4dp() =
        withAnimatorDurationScale(1f) {
            val harness = ProbeHarness(countsByFid = mapOf(2 to 30, 6 to 30, 7 to 30))
            var touchSlop = 0f
            setContent {
                touchSlop = LocalViewConfiguration.current.touchSlop
                harness.RenderRoot()
            }
            waitForIdle()

            val densityPx = density.density
            val widthPx = 411f * densityPx
            val fourDpPx = with(density) { 4.dp.toPx() }
            val newThreadBounds = onNodeWithContentDescription("发新帖").fetchSemanticsNode().boundsInRoot
            assertEquals("合并态顶栏发新帖按钮高应为 48dp", 48f, with(density) { newThreadBounds.height.toDp().value }, 1.5f)

            // A. 展开态边界（48dp 顶栏 + 48dp 标签栏 = 96dp）
            val expandedTabBounds = onNode(tabMatcher("Buy & Sell")).fetchSemanticsNode().boundsInRoot
            val expBoundary = expandedTabBounds.bottom
            assertEquals("展开态合并顶栏+标签栏底边应为 96dp", 96f, with(density) { expBoundary.toDp().value }, 1.5f)

            // A1. 边界上方 4dp（仍在标签栏/顶栏区）：拖动不得偏移也不得切换
            onRoot().performTouchInput {
                down(Offset(width * 0.75f, expBoundary - fourDpPx))
                repeat(8) {
                    moveBy(Offset(-width * 0.05f, 0f))
                    advanceEventTime(16L)
                }
            }
            waitForIdle()
            assertEquals("展开态 boundary-4dp 按住拖动列表偏移须为 0", 0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
            onRoot().performTouchInput { up() }
            waitForIdle()
            assertEquals("展开态 boundary-4dp 松手后不得切换版块", ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)

            // A2. 边界下方 4dp（已进入列表区）：立即跟手并可切换 fid=6
            onRoot().performTouchInput {
                down(Offset(width * 0.75f, expBoundary + fourDpPx))
                repeat(4) {
                    moveBy(Offset(-width * 0.045f, 0f))
                    advanceEventTime(16L)
                }
            }
            waitForIdle()
            val expBelowFollowX = findForumVerticalListNode()!!.positionInRoot.x
            assertTrue("展开态 boundary+4dp 必须正常跟手触发横滑，实际=$expBelowFollowX", expBelowFollowX < -widthPx * 0.08f)
            onRoot().performTouchInput {
                repeat(5) {
                    moveBy(Offset(-width * 0.05f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals("展开态 boundary+4dp 拖过阈值应切到 fid=6", ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)

            // B. 折叠态边界（48dp 顶栏完全收起，仅剩 48dp 标签栏）
            val titleRowPx = with(density) { FORUM_TOP_BAR_TITLE_ROW_HEIGHT.toPx() }
            onNode(verticalListMatcher()).performTouchInput {
                down(center)
                moveBy(Offset(0f, -touchSlop))
                moveBy(Offset(0f, -(titleRowPx + 140f)))
                advanceEventTime(200L)
                moveBy(Offset.Zero)
                up()
            }
            waitForIdle()
            assertTrue("上行 48dp 顶栏应已折叠", onNodeWithContentDescription("发新帖").fetchSemanticsNode().boundsInRoot.bottom <= 1f)

            val collapsedTabBounds = onNode(tabMatcher("Buy & Sell")).fetchSemanticsNode().boundsInRoot
            val colBoundary = collapsedTabBounds.bottom
            assertEquals("折叠态吸顶标签栏底边应为 48dp", 48f, with(density) { colBoundary.toDp().value }, 1.5f)

            // B1. 折叠态边界上方 4dp（仍在吸顶标签栏内）：拖动不得偏移也不得切换
            onRoot().performTouchInput {
                down(Offset(width * 0.75f, colBoundary - fourDpPx))
                repeat(8) {
                    moveBy(Offset(-width * 0.05f, 0f))
                    advanceEventTime(16L)
                }
            }
            waitForIdle()
            assertEquals("折叠态 boundary-4dp 按住拖动列表偏移须为 0", 0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
            onRoot().performTouchInput { up() }
            waitForIdle()
            assertEquals("折叠态 boundary-4dp 松手后不得切换版块", ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)

            // B2. 折叠态边界下方 4dp（贴近吸顶标签栏底缘的列表首 4dp）：必须跟手并切到 fid=7
            onRoot().performTouchInput {
                down(Offset(width * 0.75f, colBoundary + fourDpPx))
                repeat(4) {
                    moveBy(Offset(-width * 0.045f, 0f))
                    advanceEventTime(16L)
                }
            }
            waitForIdle()
            val colBelowFollowX = findForumVerticalListNode()!!.positionInRoot.x
            assertTrue("折叠态 boundary+4dp 必须正常跟手触发横滑，实际=$colBelowFollowX", colBelowFollowX < -widthPx * 0.08f)
            onRoot().performTouchInput {
                repeat(5) {
                    moveBy(Offset(-width * 0.05f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals("折叠态 boundary+4dp 拖过阈值应切到 fid=7", ForumListSource.Forum(7), harness.hostViewModel.state.value.activeForumSource)
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
        }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun probe7_mergedU5a_threadRowIcons_coexistWithSwipeTransition_noMisalignment() =
        withAnimatorDurationScale(1f) {
            fun makeCustomSummaries(fid: Int, countLabel: String, viewLabel: String, timeLabel: String, author: String, typeName: String, isPoll: Boolean) =
                listOf(
                    ThreadSummary("${fid}901", "置顶图标联测贴 $fid", null, typeName, true, false, isPoll, author, "1", null, "2026-11-28 $timeLabel", "2026-11-28 $timeLabel", "回复者", countLabel, viewLabel, false, 1),
                ) + (2..20).map { idx ->
                    ThreadSummary("${fid}00$idx", "版块${fid}常规帖 $idx", null, null, false, false, false, "作者$idx", "$idx", null, "2026-11-28 08:00", "2026-11-28 08:00", "回复者", "$idx", "${idx * 10}", false, 1)
                }

            val harness = ProbeHarness(
                customSummariesByFid = mapOf(
                    2 to makeCustomSummaries(2, "42", "1280", "09:05", "linus_hi", "讨论", isPoll = true),
                    6 to makeCustomSummaries(6, "66", "880", "10:15", "seller_a", "交易", isPoll = false),
                ),
            )
            val windowSize = mutableStateOf(DpSize(411.dp, 891.dp))
            setContent {
                MultiPaneResizableWindowHost(windowSize.value) {
                    Box(Modifier.width(windowSize.value.width).fillMaxHeight().testTag("probe7_host")) {
                        harness.RenderRoot()
                    }
                }
            }
            waitForIdle()

            val densityLocal = density
            val widthPx = 411f * densityLocal.density
            val iconWidthPx = with(densityLocal) { 14.dp.toPx() }
            val iconGapPx = with(densityLocal) { 2.dp.toPx() }

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

            fun assertRowMetricsAndBubblePixels(authorLabel: String, countLabel: String, timeLabel: String, phase: String): Triple<Float, Float, Float> {
                assertEquals("$phase 不得出现回复数与时间之间「·」分隔符", 0, onAllNodesWithText("·", useUnmergedTree = true).fetchSemanticsNodes().size)
                val authorBounds = onNodeWithText(authorLabel, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val countBounds = onNodeWithText(countLabel, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val timeBounds = onNodeWithText(timeLabel, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals("$phase 回复数与时间必须同一垂直中心线", countBounds.center.y, timeBounds.center.y, 2.0f)
                assertEquals("$phase 作者与回复数必须同一垂直中心线", authorBounds.center.y, countBounds.center.y, 2.5f)
                val gapDp = with(densityLocal) { (timeBounds.left - countBounds.right).toDp().value }
                assertEquals("$phase 回复数与时间间距须为 8dp", 8f, gapDp, 1.2f)
                assertTrue("$phase 回复数左侧须留足 14dp 气泡图标槽位", countBounds.left - authorBounds.right >= iconWidthPx + iconGapPx)

                val bmp = onRoot().captureToImage().asAndroidBitmap()
                val sampleBgX = ((authorBounds.right + countBounds.left - iconWidthPx - iconGapPx) * 0.5f).roundToInt().coerceIn(0, bmp.width - 1)
                val bg = bmp.getPixel(sampleBgX, countBounds.center.y.roundToInt().coerceIn(0, bmp.height - 1))
                val iconRightPx = (countBounds.left - iconGapPx).roundToInt()
                val iconLeftPx = (iconRightPx - iconWidthPx).roundToInt()
                val iconTopPx = (countBounds.center.y - iconWidthPx / 2f).roundToInt()
                val iconBottomPx = (countBounds.center.y + iconWidthPx / 2f).roundToInt()
                assertTrue(
                    "$phase 回复数左侧 14dp 气泡图标像素须正确渲染且不与文字错位",
                    hasNonBgPixels(bmp, iconLeftPx, iconTopPx, iconRightPx, iconBottomPx, bg),
                )
                return Triple(authorBounds.left, countBounds.left, countBounds.center.y)
            }

            // 1. 静止态：U5a 置顶内联图标 + 14dp 气泡图标 + 8dp 间距无点
            val (restAuthorX, restCountX, restCenterY) = assertRowMetricsAndBubblePixels("投票 · 讨论 · linus_hi", "42/1280", "09:05", "静止态")

            // 2. 横滑拖拽途中（-20% 偏移按住）：整行与 14dp 气泡图标同步平移无错位、不换行、不分离
            onRoot().performTouchInput {
                down(Offset(width * 0.75f, height * 0.6f))
                repeat(4) {
                    moveBy(Offset(-width * 0.05f, 0f))
                    advanceEventTime(16L)
                }
            }
            waitForIdle()
            val dragPosX = findForumVerticalListNode()!!.positionInRoot.x
            assertTrue("拖拽态应明显向左偏移，实际=$dragPosX", dragPosX < -widthPx * 0.1f)
            val (dragAuthorX, dragCountX, dragCenterY) = assertRowMetricsAndBubblePixels("投票 · 讨论 · linus_hi", "42/1280", "09:05", "拖拽偏移态")
            assertEquals("拖拽态作者 X 位移须严格匹配列表平移量", dragPosX, dragAuthorX - restAuthorX, 2.0f)
            assertEquals("拖拽态回复数 X 位移须严格匹配列表平移量", dragPosX, dragCountX - restCountX, 2.0f)
            assertEquals("拖拽态垂直基线不得抖动", restCenterY, dragCenterY, 1.5f)

            // 3. 拖过阈值释放进入 fid=6 入场动画途中 + 落定后：前半程验证作者/标题偏移，临近落定可见窗口内验证右侧 14dp 气泡与 8dp 间距
            mainClock.autoAdvance = false
            var verifiedEarlyMidEnter = false
            var verifiedFullRowMidEnter = false
            val maxRightShiftForFullMetaPx = with(densityLocal) { 12.dp.toPx() }
            try {
                onRoot().performTouchInput {
                    repeat(5) {
                        moveBy(Offset(-width * 0.05f, 0f))
                        advanceEventTime(16L)
                    }
                    up()
                }
                for (f in 0 until 120) {
                    mainClock.advanceTimeByFrame()
                    val posX = findForumVerticalListNode()?.positionInRoot?.x ?: continue
                    val hasSeller = onAllNodesWithText("交易 · seller_a", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    if (hasSeller && posX in (widthPx * 0.20f)..(widthPx * 0.45f) && !verifiedEarlyMidEnter) {
                        val sellerBounds = onNodeWithText("交易 · seller_a", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                        val titleBounds = onNodeWithText("置顶图标联测贴 6", substring = true, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                        val pinBounds = onNodeWithContentDescription("置顶", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                        assertTrue("入场前半程标题、置顶图钉与作者区须同步从右侧移入", titleBounds.left > widthPx * 0.25f && sellerBounds.left > widthPx * 0.25f && pinBounds.left >= titleBounds.left - 1f)
                        verifiedEarlyMidEnter = true
                    }
                    if (hasSeller && posX in 4f..maxRightShiftForFullMetaPx && !verifiedFullRowMidEnter) {
                        assertRowMetricsAndBubblePixels("交易 · seller_a", "66/880", "10:15", "入场动画末尾可见帧(posX=$posX)")
                        verifiedFullRowMidEnter = true
                        break
                    }
                }
            } finally {
                mainClock.autoAdvance = true
            }
            assertTrue("应捕获到 fid=6 入场前半程帧", verifiedEarlyMidEnter)
            assertTrue("应捕获到 fid=6 入场未出右边界过渡帧(posX in 4..${maxRightShiftForFullMetaPx}px)", verifiedFullRowMidEnter)
            waitForIdle()
            assertEquals(ForumListSource.Forum(6), harness.hostViewModel.state.value.activeForumSource)
            assertEquals(0f, findForumVerticalListNode()!!.positionInRoot.x, 0.5f)
            assertRowMetricsAndBubblePixels("交易 · seller_a", "66/880", "10:15", "切换落定态")

            // 4. 紧凑窄栏 (< 300dp)：先左拖 -14%（行内元素全在视口内）验证 14dp 气泡像素与 8dp 间距，再反向右滑切回 fid=2 并验落定态「42」
            windowSize.value = DpSize(274.dp, 891.dp)
            waitForIdle()
            onNodeWithTag("probe7_host").performTouchInput {
                down(Offset(width * 0.75f, height * 0.6f))
                repeat(4) {
                    moveBy(Offset(-width * 0.035f, 0f))
                    advanceEventTime(16L)
                }
            }
            waitForIdle()
            val compactPosX = findForumVerticalListNode()!!.positionInRoot.x
            assertTrue("紧凑窄栏左拖应明显左移，实际=$compactPosX", compactPosX < -40f)
            assertRowMetricsAndBubblePixels("交易 · seller_a", "66", "10:15", "紧凑窄栏左拖偏移态")
            onNodeWithTag("probe7_host").performTouchInput {
                repeat(10) {
                    moveBy(Offset(width * 0.06f, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForIdle()
            assertEquals(ForumListSource.Forum(2), harness.hostViewModel.state.value.activeForumSource)
            assertRowMetricsAndBubblePixels("投票 · 讨论 · linus_hi", "42", "09:05", "紧凑窄栏落定态")
        }

    // ------------------------------------------------------------------------
    // 离线测试替身与挂载辅助（绝不联网）
    // ------------------------------------------------------------------------

    private class ProbeHarness(
        private val countsByFid: Map<Int, Int> = mapOf(2 to 20, 6 to 20, 7 to 20),
        private val todayNewCount: Int = 10,
        private val errorFids: Set<Int> = emptySet(),
        private val refreshGateByFid: Map<Int, CompletableDeferred<Unit>> = emptyMap(),
        private val customSummariesByFid: Map<Int, List<ThreadSummary>> = emptyMap(),
    ) {
        val settingsRepo = RecordingSettingsRepository(AppSettings())
        val currentAccount = MutableStateFlow<SavedAccount?>(
            SavedAccount("42", "User42", "0".repeat(32), lastLoginAt = 1L),
        )
        val authState = MutableStateFlow<AuthState>(AuthState.SignedIn(currentAccount.value!!))
        val sessionEpochFlow = MutableStateFlow(1L)

        val authRepo = object : AuthRepository {
            override val currentAccount: StateFlow<SavedAccount?> = this@ProbeHarness.currentAccount.asStateFlow()
            override val savedAccounts: StateFlow<List<SavedAccount>> = MutableStateFlow(listOfNotNull(currentAccount.value))
            override val authState: StateFlow<AuthState> = this@ProbeHarness.authState.asStateFlow()
            override val sessionEpoch: Long get() = sessionEpochFlow.value
            override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String) = LoginResult.Failure("offline")
            override suspend fun switchAccount(uid: String) = LoginResult.Failure("offline")
            override suspend fun logout() { this@ProbeHarness.currentAccount.value = null }
            override suspend fun removeAccount(uid: String) = Unit
            override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority) = SilentReloginResult.NotLoggedIn
        }

        fun switchAccount(uid: String, username: String) {
            val next = SavedAccount(uid, username, "1".repeat(32), lastLoginAt = System.currentTimeMillis())
            sessionEpochFlow.value += 1L
            currentAccount.value = next
            authState.value = AuthState.SignedIn(next)
        }

        val smsUnread = MutableStateFlow(0)
        val notifyUnread = MutableStateFlow(0)
        val gateway = SiteAccessGateway(OkHttpClient(), ChallengeDetector(), ChallengeSolver { SolveResult.Cancelled })
        val refreshCalls = AtomicInteger(0)

        val threadListRepo = object : ThreadListRepository {
            private val states = ConcurrentHashMap<ThreadListQuery, MutableStateFlow<ThreadListLoadState>>()
            private fun stateFor(q: ThreadListQuery) = states.getOrPut(q) { MutableStateFlow(ThreadListLoadState(query = q)) }

            override suspend fun loadPage(query: ThreadListQuery, startPage: Int, existingTids: Set<String>, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority) =
                refresh(query, minVisibleItems, maxAutoBackfillPages, priority)

            override suspend fun refresh(query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority): ThreadListLoadResult {
                refreshGateByFid[query.fid]?.await()
                refreshCalls.incrementAndGet()
                val uid = currentAccount.value?.uid ?: "guest"
                if (query.fid in errorFids) {
                    val err = ThreadListError.NetworkError("probe-error-${query.fid}")
                    stateFor(query).value = ThreadListLoadState(query, emptyList(), 1, 1, false, 1, offlinePageMeta(), isLoading = false, error = err)
                    return err
                }
                val custom = customSummariesByFid[query.fid]
                val summaries = custom ?: (1..(countsByFid[query.fid] ?: 0)).map { idx ->
                    val tid = (query.fid * 1000 + idx).toString()
                    ThreadSummary(tid, "版块${query.fid}帖 $idx [uid=$uid]", null, null, false, false, false, "作者$idx", "$idx", null, "2026-11-28", "刚刚", "回复$idx", "$idx", "${idx * 10}", false, 1)
                }
                val items = summaries.map { ThreadListItem(it, false) }
                stateFor(query).value = ThreadListLoadState(query, items, 1, 2, false, 1, offlinePageMeta(), isLoading = false, error = null)
                return ThreadListBatchResult(query, query.sortByPostTime == true, summaries, items, 1, 2, false, 1, offlinePageMeta())
            }

            override suspend fun loadMore(query: ThreadListQuery, minVisibleItems: Int, maxAutoBackfillPages: Int, priority: AccessPriority) =
                refresh(query, minVisibleItems, maxAutoBackfillPages, priority)

            override fun observeThreadList(query: ThreadListQuery): Flow<ThreadListLoadState> = stateFor(query)
            override fun observeFiltered(rawThreads: Flow<List<ThreadSummary>>) = rawThreads.map { list -> list.map { ThreadListItem(it, false) } }
        }

        val searchRepo = object : SearchRepository {
            override val searchHistory: Flow<List<String>> = MutableStateFlow(emptyList())
            override suspend fun search(query: String, author: String, forumId: Int?, fullText: Boolean, page: Int, searchId: String?, priority: AccessPriority, recordHistory: Boolean) =
                getNewPosts(page, searchId, priority)
            override suspend fun getNewPosts(page: Int, searchId: String?, priority: AccessPriority): SearchResult {
                val items = (1..todayNewCount).map { idx ->
                    val tid = (9000 + idx).toString()
                    SearchItem(tid, null, "新帖标题 $idx", null, "新帖作者$idx", "$idx", null, "刚刚", "Discovery")
                }
                return SearchResult.Success(page, false, SearchPage("sid-probe", 1, items, offlinePageMeta()))
            }
            override suspend fun getMyThreads(page: Int, priority: AccessPriority): MyThreadsResult = throw UnsupportedOperationException()
            override suspend fun getMyReplies(page: Int, priority: AccessPriority): MyRepliesResult = throw UnsupportedOperationException()
            override suspend fun recordSearchHistory(query: String) = emptyList<String>()
            override suspend fun removeSearchHistory(query: String): RemovedSearchHistoryEntry? = null
            override suspend fun restoreSearchHistory(query: String, index: Int) = emptyList<String>()
            override suspend fun clearSearchHistory() = Unit
            override suspend fun resolveForumLink(rawInput: String): ResolvedForumLink? = null
        }

        val threadDetailRepo = OfflineThreadDetailRepository()
        val hostViewModel = NextAppHostViewModel(settingsRepo, threadDetailRepo)
        val forumViewModel = ForumViewModel(
            threadListRepo, searchRepo, settingsRepo, authRepo, gateway,
            todayProvider = { LocalDate.of(2026, 11, 28) },
        )

        val vmFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostViewModel as T
                modelClass.isAssignableFrom(ForumViewModel::class.java) -> forumViewModel as T
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) -> ThreadDetailViewModel(threadDetailRepo, gateway) as T
                else -> throw IllegalArgumentException("Unexpected ViewModel: $modelClass")
            }
        }

        val parentOwner: ViewModelStoreOwner = object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore = ViewModelStore()
            override val defaultViewModelProviderFactory = vmFactory
        }

        @androidx.compose.runtime.Composable
        fun RenderRoot() {
            ProvideGlassEnvironment(GlassEnvironmentState.RefractionAndBlur) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides parentOwner) {
                    NextAppRoot(
                        settingsFlow = settingsRepo.settings,
                        currentAccountFlow = authRepo.currentAccount,
                        smsUnreadCountFlow = smsUnread.asStateFlow(),
                        notifyUnreadCountFlow = notifyUnread.asStateFlow(),
                        hostViewModel = hostViewModel,
                        forumViewModel = forumViewModel,
                    )
                }
            }
        }

        @androidx.compose.runtime.Composable
        fun RenderMultiPaneDirect(onListStateSeen: (ForumListSource, LazyListState) -> Unit) {
            val collectedState = remember { mutableStateOf(hostViewModel.state.value) }
            LaunchedEffect(hostViewModel) {
                hostViewModel.state.collect { collectedState.value = it }
            }
            val cur = collectedState.value
            HiTheme(themeMode = HiThemeMode.LIGHT) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides parentOwner) {
                    val layout = calculateShellPaneLayout(411f, 891f, hasDetailContent = cur.hasDetailContent)
                    MultiPaneReadingContainer(
                        paneLayout = layout,
                        appState = cur,
                        hostViewModel = hostViewModel,
                        contentPadding = PaddingValues(0.dp),
                        persistedPaneSplit = PaneSplit.DEFAULT,
                        onPaneSplitChange = {},
                        forumContent = { chromeSpec, padding, listState ->
                            onListStateSeen(cur.activeForumSource, listState)
                            ForumPane(
                                source = cur.activeForumSource,
                                chromeSpec = chromeSpec,
                                selectedTid = cur.selectedThread?.tid,
                                onSelectSource = hostViewModel::selectForumSource,
                                onOpenThread = hostViewModel::openThread,
                                onOpenLogin = {},
                                listState = listState,
                                contentPadding = padding,
                                viewModel = forumViewModel,
                            )
                        },
                        detailContent = { _, _, _, _, _ -> },
                    )
                }
            }
        }
    }
}
