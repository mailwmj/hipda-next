package net.jejer.hipda.ui.shell

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.jejer.hipda.testing.assertFrameContinuity
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HiShellScrollAndPressTest {

    // ---- (a) 向下拖动 N px 逐帧跟手，向上拖动等量回到 0，不改布局尺寸 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun scrollLinked_bottomPill_tracksDownAndUpDragFrameByFrame() = withAnimatorDurationScale(1f) {
        var touchSlop = 0f
        setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { _, contentPadding ->
                    ScrollableItems(count = 60, contentPadding = contentPadding)
                }
            }
        }
        waitForIdle()

        val rootBottom = onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val baseBounds = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot
        val baseTop = baseBounds.top
        val baseHeight = baseBounds.height
        val hideDistance = rootBottom - baseTop
        assertTrue("可隐藏距离应 > 100px，实际 $hideDistance", hideDistance > 100f)

        val stepPx = 20f
        val steps = 4 // 总拖动 80px < hideDistance (~144px)
        mainClock.autoAdvance = false
        try {
            onNodeWithTag("scroll_list").performTouchInput {
                down(center)
                moveBy(Offset(0f, -touchSlop))
            }
            mainClock.advanceTimeByFrame()
            val startTop = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top
            assertEquals("越过 touchSlop 瞬间位移应为 0", baseTop, startTop, 1f)

            for (i in 1..steps) {
                onNodeWithTag("scroll_list").performTouchInput {
                    moveBy(Offset(0f, -stepPx))
                }
                mainClock.advanceTimeByFrame()
                val bounds = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot
                assertEquals(
                    "向下拖动第 $i 步（累计 ${i * stepPx}px）胶囊位移应逐帧跟手",
                    baseTop + i * stepPx,
                    bounds.top,
                    1f,
                )
                assertEquals("隐藏过程只用 graphicsLayer，不得改胶囊布局高度", baseHeight, bounds.height, 0.5f)
            }

            for (i in (steps - 1) downTo 0) {
                onNodeWithTag("scroll_list").performTouchInput {
                    moveBy(Offset(0f, +stepPx))
                }
                mainClock.advanceTimeByFrame()
                val bounds = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot
                assertEquals(
                    "向上拖动回退至累计 ${i * stepPx}px 时胶囊位移应逐帧跟手",
                    baseTop + i * stepPx,
                    bounds.top,
                    1f,
                )
            }

            onNodeWithTag("scroll_list").performTouchInput {
                advanceEventTime(200L)
                up()
            }
        } finally {
            mainClock.autoAdvance = true
        }
        waitForIdle()
        assertEquals(baseTop, onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun scrollLinked_compactPill_tracksDownwardDrag() = withAnimatorDurationScale(1f) {
        var touchSlop = 0f
        setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { _, contentPadding ->
                    ScrollableItems(count = 60, contentPadding = contentPadding)
                }
            }
        }
        waitForIdle()

        val baseTop = onNodeWithTag(HiShellTestTags.CompactPill).fetchSemanticsNode().boundsInRoot.top
        onNodeWithTag("scroll_list").performTouchInput {
            down(center)
            moveBy(Offset(0f, -touchSlop))
            moveBy(Offset(0f, -40f))
        }
        waitForIdle()
        val draggedTop = onNodeWithTag(HiShellTestTags.CompactPill).fetchSemanticsNode().boundsInRoot.top
        assertEquals("紧凑胶囊向下拖动 40px 后位移应正好为 40px", baseTop + 40f, draggedTop, 1f)

        onNodeWithTag("scroll_list").performTouchInput {
            advanceEventTime(200L)
            up()
        }
        waitForIdle()
    }

    @Test
    @Config(qualifiers = "w900dp-h1120dp-xhdpi")
    fun scrollLinked_rail_neverHidesOnScroll() = withAnimatorDurationScale(1f) {
        var touchSlop = 0f
        setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { _, contentPadding ->
                    ScrollableItems(count = 60, contentPadding = contentPadding)
                }
            }
        }
        waitForIdle()

        val baseBounds = onNodeWithTag(HiShellTestTags.NavigationRail).fetchSemanticsNode().boundsInRoot
        onNodeWithTag("scroll_list").performTouchInput {
            down(center)
            moveBy(Offset(0f, -touchSlop))
            moveBy(Offset(0f, -300f))
            advanceEventTime(200L)
            up()
        }
        waitForIdle()
        assertEquals(baseBounds, onNodeWithTag(HiShellTestTags.NavigationRail).fetchSemanticsNode().boundsInRoot)
        onNodeWithTag(HiShellTestTags.NavigationRail).assertIsDisplayed()
    }

    // ---- (b) > 50% 吸附到全隐，<= 50% 吸附到全显；逐帧连续；减少动态效果一帧到位 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun scrollLinked_snapThresholdFrameContinuityAndReducedMotion() {
        // 1) 正常动效：> 50% 吸附全隐，<= 50% 吸附全显，且均逐帧连续
        withAnimatorDurationScale(1f) {
            var touchSlop = 0f
            var selected by mutableStateOf(ShellDestination.Forum)
            var listState: LazyListState? = null
            setContent {
                touchSlop = LocalViewConfiguration.current.touchSlop
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    HiAdaptiveShell(
                        selected = selected,
                        onSelect = { selected = it },
                    ) { _, contentPadding ->
                        val state = rememberLazyListState()
                        listState = state
                        ScrollableItems(count = 60, contentPadding = contentPadding, listState = state)
                    }
                }
            }
            waitForIdle()

            val rootBottom = onRoot().fetchSemanticsNode().boundsInRoot.bottom
            val baseTop = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top
            val hideDistance = rootBottom - baseTop

            // > 50%（65%）松手且无 fling → 连续吸附到全隐
            onNodeWithTag("scroll_list").performTouchInput {
                down(center)
                moveBy(Offset(0f, -touchSlop))
                moveBy(Offset(0f, -(hideDistance * 0.65f)))
                advanceEventTime(200L)
                moveBy(Offset.Zero)
            }
            waitForIdle()
            val listOffsetBeforeHideUp = requireNotNull(listState).firstVisibleItemScrollOffset
            val beforeHideSnapY = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top
            assertTrue("松手前偏移应 > 50%", beforeHideSnapY - baseTop > hideDistance * 0.55f)

            val hideSnap = assertFrameContinuity(
                nodeProvider = { onNodeWithTag(HiShellTestTags.BottomPill) },
                frameCount = 24,
                maxStepDp = 20.dp,
                minTotalTravelDp = 15.dp,
                onFrame = { frame ->
                    if (frame == 0) {
                        onNodeWithTag("scroll_list").performTouchInput { up() }
                    }
                },
            )
            assertEquals("确认无 fling：松手后列表不应继续惯性滚动", listOffsetBeforeHideUp, requireNotNull(listState).firstVisibleItemScrollOffset)
            assertTrue("吸附动画应跨越多个中间帧", hideSnap.positionsPx.map { it.y }.distinct().size >= 5)
            assertEquals("超过 50% 松手应吸附到全隐", rootBottom, hideSnap.positionsPx.last().y, 1.5f)
            onNodeWithTag(HiShellTestTags.BottomPill).assertIsNotDisplayed()

            // 切目的地复位至全显
            mainClock.autoAdvance = true
            selected = ShellDestination.Messages
            waitForIdle()
            assertEquals(baseTop, onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top, 1.5f)

            // <= 50%（恰好 50% 以内，48%）松手且无 fling → 连续吸附回全显
            onNodeWithTag("scroll_list").performTouchInput {
                down(center)
                moveBy(Offset(0f, -touchSlop))
                moveBy(Offset(0f, -(hideDistance * 0.48f)))
                advanceEventTime(200L)
                moveBy(Offset.Zero)
            }
            waitForIdle()
            val listOffsetBeforeShowUp = requireNotNull(listState).firstVisibleItemScrollOffset
            val beforeShowSnapY = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top
            assertTrue("松手前偏移应 <= 50%", beforeShowSnapY - baseTop <= hideDistance * 0.50f)

            val showSnap = assertFrameContinuity(
                nodeProvider = { onNodeWithTag(HiShellTestTags.BottomPill) },
                frameCount = 24,
                maxStepDp = 25.dp,
                minTotalTravelDp = 25.dp,
                onFrame = { frame ->
                    if (frame == 0) {
                        onNodeWithTag("scroll_list").performTouchInput { up() }
                    }
                },
            )
            assertEquals("确认无 fling：松手后列表不应继续惯性滚动", listOffsetBeforeShowUp, requireNotNull(listState).firstVisibleItemScrollOffset)
            assertTrue("吸附回全显应跨越多个中间帧", showSnap.positionsPx.map { it.y }.distinct().size >= 5)
            assertEquals("不超过 50% 松手应吸附回全显", baseTop, showSnap.positionsPx.last().y, 1.5f)
            onNodeWithTag(HiShellTestTags.BottomPill).assertIsDisplayed()
        }

        // 2) 减少动态效果：松手后一帧到位（> 50% 全隐，<= 50% 全显）
        withAnimatorDurationScale(0f) {
            var touchSlop = 0f
            var selected by mutableStateOf(ShellDestination.Forum)
            setContent {
                touchSlop = LocalViewConfiguration.current.touchSlop
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    HiAdaptiveShell(
                        selected = selected,
                        onSelect = { selected = it },
                    ) { _, contentPadding ->
                        ScrollableItems(count = 60, contentPadding = contentPadding)
                    }
                }
            }
            waitForIdle()

            val rootBottom = onRoot().fetchSemanticsNode().boundsInRoot.bottom
            val baseTop = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top
            val hideDistance = rootBottom - baseTop

            onNodeWithTag("scroll_list").performTouchInput {
                down(center)
                moveBy(Offset(0f, -touchSlop))
                moveBy(Offset(0f, -(hideDistance * 0.65f)))
                advanceEventTime(200L)
                moveBy(Offset.Zero)
            }
            waitForIdle()

            mainClock.autoAdvance = false
            try {
                onNodeWithTag("scroll_list").performTouchInput { up() }
                mainClock.advanceTimeByFrame()
                val snappedTop = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top
                assertEquals("减少动态效果下 > 50% 松手应在一帧内到位至全隐", rootBottom, snappedTop, 1.5f)
            } finally {
                mainClock.autoAdvance = true
            }

            selected = ShellDestination.Messages
            waitForIdle()
            onNodeWithTag("scroll_list").performTouchInput {
                down(center)
                moveBy(Offset(0f, -touchSlop))
                moveBy(Offset(0f, -(hideDistance * 0.48f)))
                advanceEventTime(200L)
                moveBy(Offset.Zero)
            }
            waitForIdle()

            mainClock.autoAdvance = false
            try {
                onNodeWithTag("scroll_list").performTouchInput { up() }
                mainClock.advanceTimeByFrame()
                val snappedTop = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top
                assertEquals("减少动态效果下 <= 50% 松手应在一帧内到位至全显", baseTop, snappedTop, 1.5f)
            } finally {
                mainClock.autoAdvance = true
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun scrollLinked_exactFiftyPercentBoundarySnapsToVisible() = withAnimatorDurationScale(0f) {
        var barState: ShellBarState? = null
        var scope: CoroutineScope? = null
        setContent {
            scope = rememberCoroutineScope()
            val state = rememberShellBarState()
            barState = state
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                    barState = state,
                ) { _, contentPadding ->
                    ScrollableItems(count = 60, contentPadding = contentPadding)
                }
            }
        }
        waitForIdle()

        val state = requireNotNull(barState)
        val maxOffset = state.maxHiddenOffsetPx
        assertTrue("maxHiddenOffsetPx 应 > 0", maxOffset > 0f)

        // 精确 50.0%：应吸附到 0（全显）
        state.nestedScrollConnection.onPostScroll(
            consumed = Offset(0f, -(maxOffset * 0.5f)),
            available = Offset.Zero,
            source = NestedScrollSource.UserInput,
        )
        assertEquals(0.5f, state.hiddenFraction, 0.0001f)
        requireNotNull(scope).launch {
            state.nestedScrollConnection.onPostFling(
                consumed = Velocity.Zero,
                available = Velocity.Zero,
            )
        }
        waitForIdle()
        assertEquals("精确 50% 应吸附到全显", 0f, state.hiddenOffsetPx, 0.01f)

        // 50.1%（严格 > 50%）：应吸附到 maxHiddenOffsetPx（全隐）
        state.nestedScrollConnection.onPostScroll(
            consumed = Offset(0f, -(maxOffset * 0.501f)),
            available = Offset.Zero,
            source = NestedScrollSource.UserInput,
        )
        requireNotNull(scope).launch {
            state.nestedScrollConnection.onPostFling(
                consumed = Velocity.Zero,
                available = Velocity.Zero,
            )
        }
        waitForIdle()
        assertEquals("超过 50% 应吸附到全隐", maxOffset, state.hiddenOffsetPx, 0.01f)
    }

    // ---- (c) 内容短于一屏时，向下拖动不会让胶囊隐藏 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun scrollLinked_shortContentDoesNotHidePill() = withAnimatorDurationScale(1f) {
        var touchSlop = 0f
        setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { _, contentPadding ->
                    ScrollableItems(count = 2, contentPadding = contentPadding)
                }
            }
        }
        waitForIdle()

        val baseTop = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top
        onNodeWithTag("scroll_list").performTouchInput {
            down(center)
            moveBy(Offset(0f, -touchSlop))
            moveBy(Offset(0f, -300f))
        }
        waitForIdle()
        assertEquals(
            "内容短于一屏时拖动中胶囊也不应隐藏",
            baseTop,
            onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top,
            1f,
        )

        onNodeWithTag("scroll_list").performTouchInput {
            advanceEventTime(200L)
            up()
        }
        waitForIdle()
        assertEquals(baseTop, onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top, 1f)
        onNodeWithTag(HiShellTestTags.BottomPill).assertIsDisplayed()
    }

    // ---- (d) 胶囊全隐时切换一级目的地，胶囊回到可见 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun destinationSwitch_whenPillFullyHidden_restoresPillVisible() = withAnimatorDurationScale(1f) {
        var touchSlop = 0f
        var selected by mutableStateOf(ShellDestination.Forum)
        setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = selected,
                    onSelect = { selected = it },
                ) { _, contentPadding ->
                    ScrollableItems(count = 60, contentPadding = contentPadding)
                }
            }
        }
        waitForIdle()

        val rootBottom = onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val baseTop = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top
        val hideDistance = rootBottom - baseTop

        onNodeWithTag("scroll_list").performTouchInput {
            down(center)
            moveBy(Offset(0f, -touchSlop))
            moveBy(Offset(0f, -(hideDistance + 60f)))
            advanceEventTime(200L)
            up()
        }
        waitForIdle()
        assertEquals(rootBottom, onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top, 1.5f)
        onNodeWithTag(HiShellTestTags.BottomPill).assertIsNotDisplayed()

        val restoreResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag(HiShellTestTags.BottomPill) },
            frameCount = 24,
            maxStepDp = 40.dp,
            minTotalTravelDp = 50.dp,
            onFrame = { frame ->
                if (frame == 0) selected = ShellDestination.Search
            },
        )
        assertEquals("切换一级目的地后胶囊应回到可见", baseTop, restoreResult.positionsPx.last().y, 1.5f)
        onNodeWithTag(HiShellTestTags.BottomPill).assertIsDisplayed()
    }

    // ---- (e) 注入 IME insets 时同一帧内不可见；移除后回到可见 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun imeInsets_hidesPillInSameFrame_andRestoresWhenRemoved() = withAnimatorDurationScale(1f) {
        var hostView: View? = null
        setContent {
            hostView = LocalView.current
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { _, contentPadding ->
                    ScrollableItems(count = 60, contentPadding = contentPadding)
                }
            }
        }
        waitForIdle()

        val rootBottom = onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val baseTop = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top
        val imeHeightPx = with(density) { 280.dp.roundToPx() }
        val view = requireNotNull(hostView)

        mainClock.autoAdvance = false
        try {
            val imeOpenInsets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeHeightPx))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .build()
            runOnUiThread {
                ViewCompat.dispatchApplyWindowInsets(view, imeOpenInsets)
            }
            mainClock.advanceTimeByFrame()
            val hiddenTop = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top
            assertEquals("注入 IME insets 时胶囊应在同一帧内完全隐藏（0ms）", rootBottom, hiddenTop, 1.5f)
            onNodeWithTag(HiShellTestTags.BottomPill).assertIsNotDisplayed()
        } finally {
            mainClock.autoAdvance = true
        }

        // 移除 IME insets 后，胶囊经 spatialDefault 回到可见
        val imeClosedInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
            .setVisible(WindowInsetsCompat.Type.ime(), false)
            .build()
        val restoreResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag(HiShellTestTags.BottomPill) },
            frameCount = 24,
            maxStepDp = 40.dp,
            minTotalTravelDp = 50.dp,
            onFrame = { frame ->
                if (frame == 0) {
                    runOnUiThread {
                        ViewCompat.dispatchApplyWindowInsets(view, imeClosedInsets)
                    }
                }
            },
        )
        assertEquals("移除 IME insets 后胶囊应回到可见", baseTop, restoreResult.positionsPx.last().y, 1.5f)
        onNodeWithTag(HiShellTestTags.BottomPill).assertIsDisplayed()

        // 仅注入非零 WindowInsets.ime（不显式调 setVisible）也应在同一帧内隐藏
        mainClock.autoAdvance = false
        try {
            val imeHeightOnlyInsets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeHeightPx))
                .build()
            runOnUiThread {
                ViewCompat.dispatchApplyWindowInsets(view, imeHeightOnlyInsets)
            }
            mainClock.advanceTimeByFrame()
            assertEquals(
                "非零 WindowInsets.ime 应在同一帧内隐藏胶囊",
                rootBottom,
                onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top,
                1.5f,
            )
            onNodeWithTag(HiShellTestTags.BottomPill).assertIsNotDisplayed()
        } finally {
            mainClock.autoAdvance = true
        }
    }

    // ---- (f) 60 项列表滚到底，最后一项底边 <= 胶囊顶边（411、700）与 Rail 底边 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun contentPadding_bottomPill_w411_lastItemCompletelyVisibleAbovePill() =
        verifyLastItemAbovePill(navBarInsetDp = 24)

    @Test
    @Config(qualifiers = "w700dp-h900dp-xhdpi")
    fun contentPadding_bottomPill_w700_lastItemCompletelyVisibleAbovePill() =
        verifyLastItemAbovePill(navBarInsetDp = 24)

    private fun verifyLastItemAbovePill(navBarInsetDp: Int) = withAnimatorDurationScale(1f) {
        var hostView: View? = null
        var listState: LazyListState? = null
        var scope: CoroutineScope? = null
        var observedBottomPaddingDp = 0f
        setContent {
            hostView = LocalView.current
            scope = rememberCoroutineScope()
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { _, contentPadding ->
                    observedBottomPaddingDp = contentPadding.calculateBottomPadding().value
                    val state = rememberLazyListState()
                    listState = state
                    ScrollableItems(count = 60, contentPadding = contentPadding, listState = state)
                }
            }
        }
        waitForIdle()

        val navBarInsetPx = with(density) { navBarInsetDp.dp.roundToPx() }
        val insetsCompat = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBarInsetPx))
            .build()
        runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insetsCompat)
        }
        waitForIdle()

        val restingPaddingDp = observedBottomPaddingDp
        requireNotNull(scope).launch {
            requireNotNull(listState).scrollToItem(59)
        }
        waitForIdle()

        val pillTop = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top
        val lastItemBottom = onNodeWithTag("item_59").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(
            "滚到底时最后一项底边 ($lastItemBottom) 应 <= 胶囊完全可见时顶边 ($pillTop)",
            lastItemBottom <= pillTop + 1f,
        )

        // 先回到中间再向下拖动使胶囊全隐，验证内容留白不随胶囊收起变化
        requireNotNull(scope).launch {
            requireNotNull(listState).scrollToItem(0)
        }
        waitForIdle()
        onNodeWithTag("scroll_list").performTouchInput {
            down(center)
            moveBy(Offset(0f, -300f))
            advanceEventTime(200L)
            up()
        }
        waitForIdle()
        assertEquals("内容底部留白不应随胶囊收起变化", restingPaddingDp, observedBottomPaddingDp, 0.01f)
    }

    @Test
    @Config(qualifiers = "w900dp-h1120dp-xhdpi")
    fun contentPadding_rail_w900_lastItemAboveNavigationBarInset() = withAnimatorDurationScale(1f) {
        var hostView: View? = null
        var listState: LazyListState? = null
        var scope: CoroutineScope? = null
        setContent {
            hostView = LocalView.current
            scope = rememberCoroutineScope()
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { _, contentPadding ->
                    val state = rememberLazyListState()
                    listState = state
                    ScrollableItems(count = 60, contentPadding = contentPadding, listState = state)
                }
            }
        }
        waitForIdle()

        val navBarInsetPx = with(density) { 48.dp.roundToPx() }
        val insetsCompat = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBarInsetPx))
            .build()
        runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(requireNotNull(hostView), insetsCompat)
        }
        waitForIdle()

        requireNotNull(scope).launch {
            requireNotNull(listState).scrollToItem(59)
        }
        waitForIdle()

        val rootBottom = onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val lastItemBottom = onNodeWithTag("item_59").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(
            "Rail 下滚到底最后一项底边 ($lastItemBottom) 应 <= 窗口底边 - 底部手势区 (${rootBottom - navBarInsetPx})",
            lastItemBottom <= rootBottom - navBarInsetPx + 1f,
        )
    }

    // ---- (g) 导航项按下缩放稳定在 0.97（±0.001），松开回到 1.0，不触发触感 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun navItemPress_bottomPill_scalesTo097AndReboundsTo1WithoutHaptics() =
        verifyNavItemPressScale(ShellDestination.Messages)

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun navItemPress_compactPill_scalesTo097AndReboundsTo1WithoutHaptics() =
        verifyNavItemPressScale(ShellDestination.Search)

    @Test
    @Config(qualifiers = "w900dp-h1120dp-xhdpi")
    fun navItemPress_rail_scalesTo097AndReboundsTo1WithoutHaptics() =
        verifyNavItemPressScale(ShellDestination.Mine)

    private fun verifyNavItemPressScale(target: ShellDestination) = withAnimatorDurationScale(1f) {
        var hapticCalls = 0
        val fakeHaptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                hapticCalls++
            }
        }
        var selected by mutableStateOf(ShellDestination.Forum)
        setContent {
            CompositionLocalProvider(LocalHapticFeedback provides fakeHaptics) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    HiAdaptiveShell(
                        selected = selected,
                        onSelect = { selected = it },
                    ) { _, contentPadding ->
                        ScrollableItems(count = 10, contentPadding = contentPadding)
                    }
                }
            }
        }
        waitForIdle()

        val itemTag = HiShellTestTags.navItem(target)
        val restingNode = onNodeWithTag(itemTag).fetchSemanticsNode()
        assertTrue("导航项基线宽度应 > 0", restingNode.size.width > 0)
        assertEquals(1.0f, restingNode.boundsInRoot.width / restingNode.size.width, 0.001f)

        onNodeWithTag(itemTag).performTouchInput { down(center) }
        waitForIdle()
        val pressedNode = onNodeWithTag(itemTag).fetchSemanticsNode()
        val pressedScaleX = pressedNode.boundsInRoot.width / pressedNode.size.width
        val pressedScaleY = pressedNode.boundsInRoot.height / pressedNode.size.height
        assertEquals("按下后横向缩放应稳定在 0.97（误差 0.001）", 0.97f, pressedScaleX, 0.001f)
        assertEquals("按下后纵向缩放应稳定在 0.97（误差 0.001）", 0.97f, pressedScaleY, 0.001f)

        onNodeWithTag(itemTag).performTouchInput { up() }
        waitForIdle()
        val releasedNode = onNodeWithTag(itemTag).fetchSemanticsNode()
        val releasedScaleX = releasedNode.boundsInRoot.width / releasedNode.size.width
        val releasedScaleY = releasedNode.boundsInRoot.height / releasedNode.size.height
        assertEquals("松开后横向缩放应回到 1.0（误差 0.001）", 1.0f, releasedScaleX, 0.001f)
        assertEquals("松开后纵向缩放应回到 1.0（误差 0.001）", 1.0f, releasedScaleY, 0.001f)
        assertEquals("导航项按压不应触发触感", 0, hapticCalls)
    }

    // ---- (h) 滚动隐藏与恢复全过程毛玻璃计数始终为 1 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun glassSurfaceCount_remainsExactlyOneAcrossScrollAndHiddenStates() = withAnimatorDurationScale(1f) {
        var touchSlop = 0f
        setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { _, contentPadding ->
                    ScrollableItems(count = 60, contentPadding = contentPadding)
                }
            }
        }
        waitForIdle()
        assertExactOneGlassSurface()

        onNodeWithTag("scroll_list").performTouchInput {
            down(center)
            moveBy(Offset(0f, -touchSlop))
            moveBy(Offset(0f, -300f))
            advanceEventTime(200L)
            up()
        }
        waitForIdle()
        assertExactOneGlassSurface()
    }

    private fun ComposeUiTest.assertExactOneGlassSurface() {
        assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        val count = onAllNodes(
            matcher = SemanticsMatcher.expectValue(HiGlassMarker, true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        assertEquals("外壳毛玻璃计数应始终为 1", 1, count)
    }
}

@androidx.compose.runtime.Composable
private fun ScrollableItems(
    count: Int,
    contentPadding: PaddingValues,
    listState: LazyListState = rememberLazyListState(),
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("scroll_list"),
        contentPadding = contentPadding,
    ) {
        items(count) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .testTag("item_$index"),
            ) {
                Text(text = "Item #$index")
            }
        }
    }
}
