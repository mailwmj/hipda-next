package net.jejer.hipda.ui.shell

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Rect as AndroidRect
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.StateRestorationTester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowInfoTrackerDecorator
import androidx.window.layout.WindowLayoutInfo
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.jejer.hipda.testing.RecompositionCounter
import net.jejer.hipda.testing.assertFrameContinuity
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HI_CHROME_MAX_FONT_SCALE
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.PreviewRootContent
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
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
class HiAdaptiveShellTest {

    // ---- (a) 毛玻璃计数：任何形态下不超过 2 块，且外壳自身恰好 1 块 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun glassSurfaceCount_isExactlyOneInBottomPill() = verifyExactShellGlassCount(
        expectedModeTag = HiShellTestTags.BottomPill,
    )

    @Test
    @Config(qualifiers = "w700dp-h900dp-xhdpi")
    fun glassSurfaceCount_isExactlyOneInMediumBottomPill() = verifyExactShellGlassCount(
        expectedModeTag = HiShellTestTags.BottomPill,
    )

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun glassSurfaceCount_isExactlyOneInCompactPill() = verifyExactShellGlassCount(
        expectedModeTag = HiShellTestTags.CompactPill,
    )

    @Test
    @Config(qualifiers = "w900dp-h1120dp-xhdpi")
    fun glassSurfaceCount_isExactlyOneInRail() = verifyExactShellGlassCount(
        expectedModeTag = HiShellTestTags.NavigationRail,
    )

    private fun verifyExactShellGlassCount(expectedModeTag: String) = withAnimatorDurationScale(1f) {
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { destination, _ ->
                    Box(Modifier.fillMaxSize().testTag("dest_${destination.name}"))
                }
            }
        }
        waitForIdle()

        onNodeWithTag(expectedModeTag).assertIsDisplayed()
        assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        val shellGlassCount = onAllNodes(
            matcher = SemanticsMatcher.expectValue(HiGlassMarker, true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        assertEquals("外壳自身应恰好有 1 块毛玻璃", 1, shellGlassCount)
    }

    // ---- (b) 选中指示器可打断：位置与宽度逐帧连续 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun selectionIndicator_bottomPill_interruptibleAndContinuous() = withAnimatorDurationScale(1f) {
        var selected by mutableStateOf(ShellDestination.Forum)
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = selected,
                    onSelect = { selected = it },
                ) { destination, _ ->
                    Box(Modifier.fillMaxSize()) { Text(destination.name) }
                }
            }
        }
        waitForIdle()

        val normalResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true) },
            frameCount = 24,
            maxStepDp = 120.dp,
            minTotalTravelDp = 100.dp,
            onFrame = { frame -> if (frame == 0) selected = ShellDestination.Mine },
        )
        assertTrue("正常切换单帧应有有效位移", normalResult.maxStepDp > 2.dp)

        mainClock.autoAdvance = true
        selected = ShellDestination.Forum
        waitForIdle()
        val startX = onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.x

        val reversalResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true) },
            frameCount = 30,
            maxStepDp = normalResult.maxStepDp + 0.5.dp,
            minTotalTravelDp = 40.dp,
            onFrame = { frame ->
                if (frame == 0) selected = ShellDestination.Mine
                else if (frame == 4) selected = ShellDestination.Forum
            },
        )
        val peakX = reversalResult.positionsPx.maxOf { it.x }
        val finalX = reversalResult.positionsPx.last().x
        assertTrue("打断前应已明显右移（start=$startX, peak=$peakX）", peakX > startX + with(density) { 20.dp.toPx() })
        assertTrue("打断后应回落向起点（peak=$peakX, final=$finalX）", finalX < peakX - with(density) { 15.dp.toPx() })
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun selectionIndicator_compactPill_interruptibleAndContinuous() = withAnimatorDurationScale(1f) {
        var selected by mutableStateOf(ShellDestination.Forum)
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = selected,
                    onSelect = { selected = it },
                ) { destination, _ ->
                    Box(Modifier.fillMaxSize()) { Text(destination.name) }
                }
            }
        }
        waitForIdle()

        val normalResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true) },
            frameCount = 24,
            maxStepDp = 120.dp,
            minTotalTravelDp = 60.dp,
            onFrame = { frame -> if (frame == 0) selected = ShellDestination.Mine },
        )

        mainClock.autoAdvance = true
        selected = ShellDestination.Forum
        waitForIdle()
        val startX = onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.x

        val reversalResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true) },
            frameCount = 30,
            maxStepDp = normalResult.maxStepDp + 0.5.dp,
            minTotalTravelDp = 30.dp,
            onFrame = { frame ->
                if (frame == 0) selected = ShellDestination.Mine
                else if (frame == 4) selected = ShellDestination.Forum
            },
        )
        val peakX = reversalResult.positionsPx.maxOf { it.x }
        val finalX = reversalResult.positionsPx.last().x
        assertTrue("紧凑胶囊打断前应已明显右移（start=$startX, peak=$peakX）", peakX > startX + with(density) { 15.dp.toPx() })
        assertTrue("紧凑胶囊打断后应回落向起点（peak=$peakX, final=$finalX）", finalX < peakX - with(density) { 10.dp.toPx() })
    }

    // ---- S7.3：选中指示器宽度逐帧连续且可打断 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun selectionIndicator_widthAnimatesContinuouslyAndIsInterruptible() = withAnimatorDurationScale(1f) {
        var selected by mutableStateOf(ShellDestination.Forum)
        val itemWidths = listOf(56.dp, 80.dp, 64.dp, 148.dp)

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ShellIndicatorLayout(
                    selected = selected,
                    mode = ShellNavigationMode.CompactPill,
                ) {
                    ShellDestination.entries.forEachIndexed { idx, dest ->
                        Box(
                            modifier = Modifier
                                .width(itemWidths[idx])
                                .height(48.dp)
                                .testTag(HiShellTestTags.navItem(dest)),
                        )
                    }
                }
            }
        }
        waitForIdle()

        // 单程 Forum(56dp) -> Mine(148dp)：记录宽度变化与单帧最大宽度步长
        val normalWidths = sampleIndicatorWidths(
            nodeProvider = { onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true) },
            frameCount = 24,
            onFrame = { frame -> if (frame == 0) selected = ShellDestination.Mine },
        )
        val normalMaxWidthStepDp = maxAdjacentDeltaDp(normalWidths)
        val normalTotalWidthTravelDp = totalDeltaDp(normalWidths)
        assertTrue(
            "正常切换宽度累计变化应 > 80dp，实际 $normalTotalWidthTravelDp",
            normalTotalWidthTravelDp > 80.dp,
        )
        assertTrue("正常切换单帧宽度变化应平滑（> 2dp 且 < 60dp），实际 $normalMaxWidthStepDp", normalMaxWidthStepDp in 2.dp..60.dp)

        // 复位后中途反向：Forum(56dp) -> Mine(148dp)，第 4 帧切回 Forum(56dp)
        mainClock.autoAdvance = true
        selected = ShellDestination.Forum
        waitForIdle()

        val reversalWidths = sampleIndicatorWidths(
            nodeProvider = { onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true) },
            frameCount = 30,
            onFrame = { frame ->
                if (frame == 0) selected = ShellDestination.Mine
                else if (frame == 4) selected = ShellDestination.Forum
            },
        )
        val reversalMaxWidthStepDp = maxAdjacentDeltaDp(reversalWidths)
        assertTrue(
            "打断反向时单帧宽度跳变 $reversalMaxWidthStepDp 超过正常上限 ${normalMaxWidthStepDp + 0.5.dp}",
            reversalMaxWidthStepDp <= normalMaxWidthStepDp + 0.5.dp,
        )
        val startWidthPx = reversalWidths.first()
        val peakWidthPx = reversalWidths.max()
        val finalWidthPx = reversalWidths.last()
        assertTrue("打断前宽度应显著增大（start=$startWidthPx, peak=$peakWidthPx）", peakWidthPx > startWidthPx + with(density) { 20.dp.toPx() })
        assertTrue("打断后宽度应平滑回缩（peak=$peakWidthPx, final=$finalWidthPx）", finalWidthPx < peakWidthPx - with(density) { 15.dp.toPx() })
    }

    @Test
    @Config(qualifiers = "w900dp-h1120dp-xhdpi")
    fun selectionIndicator_rail_verticalInterruptibleAndContinuous() = withAnimatorDurationScale(1f) {
        var selected by mutableStateOf(ShellDestination.Forum)
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = selected,
                    onSelect = { selected = it },
                ) { destination, _ ->
                    Box(Modifier.fillMaxSize()) { Text(destination.name) }
                }
            }
        }
        waitForIdle()

        val normalResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true) },
            frameCount = 24,
            maxStepDp = 120.dp,
            minTotalTravelDp = 100.dp,
            onFrame = { frame -> if (frame == 0) selected = ShellDestination.Mine },
        )

        mainClock.autoAdvance = true
        selected = ShellDestination.Forum
        waitForIdle()
        val startY = onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true)
            .fetchSemanticsNode().positionInRoot.y

        val reversalResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true) },
            frameCount = 30,
            maxStepDp = normalResult.maxStepDp + 0.5.dp,
            minTotalTravelDp = 40.dp,
            onFrame = { frame ->
                if (frame == 0) selected = ShellDestination.Mine
                else if (frame == 4) selected = ShellDestination.Forum
            },
        )
        val peakY = reversalResult.positionsPx.maxOf { it.y }
        val finalY = reversalResult.positionsPx.last().y
        assertTrue("导航轨打断前应已明显下移（start=$startY, peak=$peakY）", peakY > startY + with(density) { 20.dp.toPx() })
        assertTrue("导航轨打断后应回落向起点（peak=$peakY, final=$finalY）", finalY < peakY - with(density) { 15.dp.toPx() })
    }

    // ---- S7.2：目的地切换过程中内容容器与页面零位移 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun destinationSwitch_contentHasZeroDisplacementAcrossAllFrames() = withAnimatorDurationScale(1f) {
        var selected by mutableStateOf(ShellDestination.Forum)
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = selected,
                    onSelect = { selected = it },
                ) { destination, _ ->
                    Box(Modifier.fillMaxSize().testTag("page_${destination.name}")) {
                        Text(destination.name)
                    }
                }
            }
        }
        waitForIdle()

        val initialContainerPos = onNodeWithTag(HiShellTestTags.ContentContainer).fetchSemanticsNode().positionInRoot
        val initialPagePos = onNodeWithTag("page_Forum").fetchSemanticsNode().positionInRoot

        mainClock.autoAdvance = false
        try {
            selected = ShellDestination.Mine
            Snapshot.sendApplyNotifications()
            repeat(24) { frame ->
                mainClock.advanceTimeByFrame()
                val containerPos = onNodeWithTag(HiShellTestTags.ContentContainer).fetchSemanticsNode().positionInRoot
                assertEquals("帧 $frame 内容容器 X 不应发生位移", initialContainerPos.x, containerPos.x, 0.01f)
                assertEquals("帧 $frame 内容容器 Y 不应发生位移", initialContainerPos.y, containerPos.y, 0.01f)
                onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "page_Mine"))
                    .fetchSemanticsNodes()
                    .firstOrNull()
                    ?.let { mineNode ->
                        assertEquals("帧 $frame 新目的地 X 应零位移", initialPagePos.x, mineNode.positionInRoot.x, 0.01f)
                        assertEquals("帧 $frame 新目的地 Y 应零位移", initialPagePos.y, mineNode.positionInRoot.y, 0.01f)
                    }
            }
        } finally {
            mainClock.autoAdvance = true
        }
        onNodeWithTag("page_Mine").assertIsDisplayed()
    }

    // ---- (c) 减少动态效果：scale=0f 时指示器与内容一帧到位 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun reducedMotion_indicatorAndContentFinishImmediately() {
        withAnimatorDurationScale(1f) {
            var selected by mutableStateOf(ShellDestination.Forum)
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    HiAdaptiveShell(
                        selected = selected,
                        onSelect = { selected = it },
                    ) { destination, _ ->
                        Box(Modifier.fillMaxSize().testTag("page_${destination.name}"))
                    }
                }
            }
            waitForIdle()
            val mineX = onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Mine))
                .fetchSemanticsNode().positionInRoot.x

            mainClock.autoAdvance = false
            selected = ShellDestination.Mine
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            waitForIdle()

            val indicatorX = onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true)
                .fetchSemanticsNode().positionInRoot.x
            assertTrue(
                "正常缩放下 16ms 后指示器应仍在途中（indicatorX=$indicatorX, mineX=$mineX）",
                abs(indicatorX - mineX) > with(density) { 20.dp.toPx() },
            )
        }

        withAnimatorDurationScale(0f) {
            var selected by mutableStateOf(ShellDestination.Forum)
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    HiAdaptiveShell(
                        selected = selected,
                        onSelect = { selected = it },
                    ) { destination, _ ->
                        Box(Modifier.fillMaxSize().testTag("page_${destination.name}"))
                    }
                }
            }
            waitForIdle()
            val mineNode = onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Mine)).fetchSemanticsNode()
            val mineX = mineNode.positionInRoot.x
            val mineWidth = mineNode.size.width.toFloat()

            mainClock.autoAdvance = false
            selected = ShellDestination.Mine
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            waitForIdle()

            val indicatorNode = onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true)
                .fetchSemanticsNode()
            assertEquals("减少动态效果下指示器 X 应一步到位", mineX, indicatorNode.positionInRoot.x, 1.5f)
            assertEquals("减少动态效果下指示器宽度应一步到位", mineWidth, indicatorNode.size.width.toFloat(), 1.5f)
            onNodeWithTag("page_Mine").assertIsDisplayed()
            onNodeWithTag("page_Forum").assertDoesNotExist()
        }
    }

    // ---- (d) 重组次数：切换目的地时，未选中的导航项不重组 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun destinationSwitch_unselectedNavItemsDoNotRecompose_bottomPill() =
        verifyUnselectedItemsSkipRecomposition(ShellNavigationMode.BottomPill)

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun destinationSwitch_unselectedNavItemsDoNotRecompose_compactPill() =
        verifyUnselectedItemsSkipRecomposition(ShellNavigationMode.CompactPill)

    @Test
    @Config(qualifiers = "w900dp-h1120dp-xhdpi")
    fun destinationSwitch_unselectedNavItemsDoNotRecompose_rail() =
        verifyUnselectedItemsSkipRecomposition(ShellNavigationMode.Rail)

    private fun verifyUnselectedItemsSkipRecomposition(mode: ShellNavigationMode) = withAnimatorDurationScale(1f) {
        val counters = ShellDestination.entries.associateWith { RecompositionCounter("NavItem_${it.name}") }
        var selected by mutableStateOf(ShellDestination.Forum)
        val onSelectStable: (ShellDestination) -> Unit = { selected = it }

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                Box(
                    modifier = if (mode == ShellNavigationMode.Rail) {
                        Modifier.width(HiNavigationRailWidth).height(400.dp)
                    } else {
                        Modifier.width(411.dp).height(64.dp)
                    },
                ) {
                    ShellIndicatorLayout(
                        selected = selected,
                        mode = mode,
                    ) {
                        ShellDestination.entries.forEach { destination ->
                            val isSelected = destination == selected
                            ObservedShellNavItem(
                                destination = destination,
                                selected = isSelected,
                                showLabel = if (mode == ShellNavigationMode.CompactPill) isSelected else true,
                                mode = mode,
                                onSelect = onSelectStable,
                                counter = counters.getValue(destination),
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
        ShellDestination.entries.forEach { dest ->
            counters.getValue(dest).assertEquals(1)
            counters.getValue(dest).assertRecompositionsAtMost(0)
        }

        selected = ShellDestination.Search
        waitForIdle()

        counters.getValue(ShellDestination.Forum).assertEquals(2)
        counters.getValue(ShellDestination.Search).assertEquals(2)
        counters.getValue(ShellDestination.Messages).assertEquals(1)
        counters.getValue(ShellDestination.Messages).assertRecompositionsAtMost(0)
        counters.getValue(ShellDestination.Mine).assertEquals(1)
        counters.getValue(ShellDestination.Mine).assertRecompositionsAtMost(0)

        selected = ShellDestination.Mine
        waitForIdle()

        counters.getValue(ShellDestination.Forum).assertEquals(2)
        counters.getValue(ShellDestination.Messages).assertEquals(1)
        counters.getValue(ShellDestination.Messages).assertRecompositionsAtMost(0)
        counters.getValue(ShellDestination.Search).assertEquals(3)
        counters.getValue(ShellDestination.Mine).assertEquals(2)
    }

    @Composable
    private fun ObservedShellNavItem(
        destination: ShellDestination,
        selected: Boolean,
        showLabel: Boolean,
        mode: ShellNavigationMode,
        onSelect: (ShellDestination) -> Unit,
        counter: RecompositionCounter,
    ) {
        counter.Track()
        ShellNavItem(
            destination = destination,
            selected = selected,
            showLabel = showLabel,
            mode = mode,
            onSelect = onSelect,
        )
    }

    // ---- (e) 无障碍与形态几何断言 + 视觉打磨验证 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun accessibilityAndGeometry_bottomPill() = withAnimatorDurationScale(1f) {
        var selected by mutableStateOf(ShellDestination.Forum)
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = selected,
                    onSelect = { selected = it },
                ) { destination, _ ->
                    Box(Modifier.fillMaxSize().testTag("page_${destination.name}"))
                }
            }
        }
        waitForIdle()

        assertNavItemsAccessible(selected = ShellDestination.Forum, expectUnselectedLabels = true)

        val pillBounds = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot
        val rootBounds = onRoot().fetchSemanticsNode().boundsInRoot
        val pillWidthDp = with(density) { pillBounds.width.toDp() }
        val pillHeightDp = with(density) { pillBounds.height.toDp() }
        assertTrue("标准胶囊最小高度应 ≥ 64dp，实际 $pillHeightDp", pillHeightDp >= 63.5.dp)
        assertTrue("标准胶囊最大宽度应 ≤ 420dp，实际 $pillWidthDp", pillWidthDp <= 420.5.dp)
        val centerOffsetPx = abs(pillBounds.center.x - rootBounds.center.x)
        assertTrue("标准胶囊应相对窗口水平居中，偏差 $centerOffsetPx px", centerOffsetPx <= with(density) { 1.dp.toPx() })

        onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Search)).performClick()
        waitForIdle()
        assertEquals(ShellDestination.Search, selected)
        assertNavItemsAccessible(selected = ShellDestination.Search, expectUnselectedLabels = true)
    }

    // ---- S7.1：非零 navigationBars inset 下，胶囊底边 = 窗口底 − inset − 8dp ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun bottomPill_respectsNonZeroNavigationBarsInset() = withAnimatorDurationScale(1f) {
        var hostView: View? = null
        setContent {
            hostView = LocalView.current
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { _, _ ->
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()

        val navBarInsetPx = with(density) { 48.dp.roundToPx() }
        val insetsCompat = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBarInsetPx))
            .build()
        runOnUiThread {
            val view = requireNotNull(hostView)
            ViewCompat.dispatchApplyWindowInsets(view, insetsCompat)
        }
        waitForIdle()

        val rootBounds = onRoot().fetchSemanticsNode().boundsInRoot
        val pillBounds = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot
        val expectedBottomPx = rootBounds.bottom - navBarInsetPx - with(density) { HiNavigationPillBottomGap.toPx() }
        assertEquals(
            "胶囊底边应等于窗口底边减去 navigationBars inset 与 8dp",
            expectedBottomPx,
            pillBounds.bottom,
            1.5f,
        )
    }

    @Test
    @Config(qualifiers = "w700dp-h900dp-xhdpi")
    fun bottomPill_mediumWidthIsCenteredAndCappedAt420dp() = withAnimatorDurationScale(1f) {
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { _, _ ->
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()

        val pillBounds = onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot
        val rootBounds = onRoot().fetchSemanticsNode().boundsInRoot
        val pillWidthDp = with(density) { pillBounds.width.toDp() }
        assertEquals("700dp 窗口下胶囊宽度应钳制在 420dp", 420f, pillWidthDp.value, 1f)
        val centerOffsetPx = abs(pillBounds.center.x - rootBounds.center.x)
        assertTrue("700dp 窗口下胶囊应相对整个窗口居中，偏差 $centerOffsetPx px", centerOffsetPx <= with(density) { 1.dp.toPx() })
    }

    @Test
    @Config(qualifiers = "w340dp-h800dp-xhdpi")
    fun accessibility_narrowWindowShowsTextOnlyForSelected() = withAnimatorDurationScale(1f) {
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Messages,
                    onSelect = {},
                ) { _, _ ->
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()

        onNodeWithTag(HiShellTestTags.BottomPill).assertIsDisplayed()
        assertNavItemsAccessible(selected = ShellDestination.Messages, expectUnselectedLabels = false)
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun accessibilityAndGeometry_compactPill_symmetricVisualIconSpacing() = withAnimatorDurationScale(1f) {
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { _, _ ->
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()

        val pillBounds = onNodeWithTag(HiShellTestTags.CompactPill).fetchSemanticsNode().boundsInRoot
        val pillHeightDp = with(density) { pillBounds.height.toDp() }
        assertTrue("紧凑胶囊最小高度应 ≥ 48dp，实际 $pillHeightDp", pillHeightDp >= 47.5.dp)
        assertNavItemsAccessible(selected = ShellDestination.Forum, expectUnselectedLabels = false)

        // 视觉打磨 3：选中指示器右缘到相邻图标的视觉间距与其余未选中图标间距一致（均为 24dp）
        val indicatorBounds = onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val messagesBounds = onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Messages))
            .fetchSemanticsNode().boundsInRoot
        val searchBounds = onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Search))
            .fetchSemanticsNode().boundsInRoot
        val halfIconPx = with(density) { 12.dp.toPx() }
        val indicatorToMessagesIconPx = (messagesBounds.center.x - halfIconPx) - indicatorBounds.right
        val messagesToSearchIconPx = (searchBounds.center.x - halfIconPx) - (messagesBounds.center.x + halfIconPx)
        assertEquals(
            "选中指示器右缘到相邻图标的视觉间距应与未选中图标间距一致",
            messagesToSearchIconPx,
            indicatorToMessagesIconPx,
            with(density) { 1.dp.toPx() },
        )
    }

    @Test
    @Config(qualifiers = "w900dp-h1120dp-xhdpi")
    fun accessibilityAndGeometry_navigationRailDockedLeft80dpWithIconCapsuleIndicator() = withAnimatorDurationScale(1f) {
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Mine,
                    onSelect = {},
                ) { _, _ ->
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()

        val railBounds = onNodeWithTag(HiShellTestTags.NavigationRail).fetchSemanticsNode().boundsInRoot
        val railWidthDp = with(density) { railBounds.width.toDp() }
        val railLeftDp = with(density) { railBounds.left.toDp() }
        assertEquals("导航轨应贴左", 0f, railLeftDp.value, 0.5f)
        assertEquals("导航轨宽度应为 80dp", 80f, railWidthDp.value, 1f)

        // 视觉打磨 1：导航轨选中指示器为包裹图标的横向小胶囊（56×32dp）
        val indicatorBounds = onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals("导航轨指示器宽应为 56dp", 56f, with(density) { indicatorBounds.width.toDp().value }, 1f)
        assertEquals("导航轨指示器高应为 32dp", 32f, with(density) { indicatorBounds.height.toDp().value }, 1f)

        assertNavItemsAccessible(selected = ShellDestination.Mine, expectUnselectedLabels = true)
    }

    // ---- S7.4：Rail 形态下 fontScale = 2.0 被钳制到 1.25 ----

    @Test
    @Config(qualifiers = "w900dp-h1120dp-xhdpi")
    fun navigationRail_clampsFontScaleAt125PercentWhenSystemScaleIs200() = withAnimatorDurationScale(1f) {
        var fontScaleState by mutableStateOf(1.25f)
        var observedOuterScale = 0f

        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = fontScaleState)) {
                observedOuterScale = LocalDensity.current.fontScale
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    HiAdaptiveShell(
                        selected = ShellDestination.Forum,
                        onSelect = {},
                    ) { _, _ ->
                        Box(Modifier.fillMaxSize()) {
                            Text(
                                text = "对照组正文文字",
                                modifier = Modifier.testTag("uncapped_body_text"),
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()

        assertEquals(HI_CHROME_MAX_FONT_SCALE, observedOuterScale, 0.001f)
        val railItemHeightAt125 = onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Forum))
            .fetchSemanticsNode().size.height
        val uncappedHeightAt125 = onNodeWithTag("uncapped_body_text").fetchSemanticsNode().size.height

        fontScaleState = 2.0f
        waitForIdle()

        assertEquals(2.0f, observedOuterScale, 0.001f)
        val railItemHeightAt200 = onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Forum))
            .fetchSemanticsNode().size.height
        val uncappedHeightAt200 = onNodeWithTag("uncapped_body_text").fetchSemanticsNode().size.height

        assertTrue(
            "对照组正文在 2.0 倍缩放下应明显高于 1.25 倍（1.25x=$uncappedHeightAt125, 2.0x=$uncappedHeightAt200）",
            uncappedHeightAt200 > uncappedHeightAt125,
        )
        assertEquals(
            "Rail 导航项在 2.0 倍系统缩放下应被钳制到与 1.25 倍完全一致的高度",
            railItemHeightAt125,
            railItemHeightAt200,
        )
    }

    // ---- S7.5 + 阻断 2：StateRestorationTester 证明预览外壳演示态与选中目的地重建后保持 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun previewShellDemo_restoresVisibilityAndSelectedDestinationAfterStateRestoration() = withAnimatorDurationScale(1f) {
        val restorationTester = StateRestorationTester(this)
        restorationTester.setContent {
            PreviewRootContent()
        }
        waitForIdle()

        // 进入外壳演示并切换选中目的地到“搜索”
        onNodeWithText("外壳").performClick()
        waitForIdle()
        onNodeWithText("返回样张").assertIsDisplayed()
        onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Search)).performClick()
        waitForIdle()
        assertEquals(
            true,
            onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Search))
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected),
        )

        // 模拟配置变化 / 进程回收重建
        restorationTester.emulateSaveAndRestore()
        waitForIdle()

        onNodeWithText("返回样张").assertIsDisplayed()
        assertEquals(
            "重建后仍应保持在“搜索”目的地",
            true,
            onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Search))
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected),
        )
        assertEquals(
            "重建后“论坛”不应被重置为选中",
            false,
            onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Forum))
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected),
        )
    }

    // ---- S6 + S7.6：真实 FoldingFeature 替身验证竖直分隔铰链 → 紧凑胶囊、水平桌面姿态 → 不变 ----

    @Test
    @Config(qualifiers = "w700dp-h900dp-xhdpi")
    fun foldingFeature_verticalSeparatingHingeUsesCompactPill() = withFakeFoldingFeature(
        FakeFoldingFeature(
            bounds = AndroidRect(700, 0, 700, 1800),
            orientation = FoldingFeature.Orientation.VERTICAL,
            state = FoldingFeature.State.HALF_OPENED,
            isSeparating = true,
        ),
    ) {
        withAnimatorDurationScale(1f) {
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    HiAdaptiveShell(selected = ShellDestination.Forum, onSelect = {}) { _, _ ->
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
            waitForIdle()
            onNodeWithTag(HiShellTestTags.CompactPill).assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w700dp-h900dp-xhdpi")
    fun foldingFeature_horizontalTabletopHingeKeepsBottomPill() = withFakeFoldingFeature(
        FakeFoldingFeature(
            bounds = AndroidRect(0, 900, 1400, 900),
            orientation = FoldingFeature.Orientation.HORIZONTAL,
            state = FoldingFeature.State.HALF_OPENED,
            isSeparating = true,
        ),
    ) {
        withAnimatorDurationScale(1f) {
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    HiAdaptiveShell(selected = ShellDestination.Forum, onSelect = {}) { _, _ ->
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
            waitForIdle()
            onNodeWithTag(HiShellTestTags.BottomPill).assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w900dp-h1120dp-xhdpi")
    fun foldingFeature_horizontalTabletopHingeKeepsRailOnExpandedWidth() = withFakeFoldingFeature(
        FakeFoldingFeature(
            bounds = AndroidRect(0, 1120, 1800, 1120),
            orientation = FoldingFeature.Orientation.HORIZONTAL,
            state = FoldingFeature.State.HALF_OPENED,
            isSeparating = true,
        ),
    ) {
        withAnimatorDurationScale(1f) {
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    HiAdaptiveShell(selected = ShellDestination.Forum, onSelect = {}) { _, _ ->
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
            waitForIdle()
            onNodeWithTag(HiShellTestTags.NavigationRail).assertIsDisplayed()
        }
    }

    // ---- 局部辅助 ----

    private fun ComposeUiTest.sampleIndicatorWidths(
        nodeProvider: () -> SemanticsNodeInteraction,
        frameCount: Int,
        onFrame: (Int) -> Unit,
    ): List<Float> {
        val prev = mainClock.autoAdvance
        mainClock.autoAdvance = false
        val widths = ArrayList<Float>(frameCount + 1)
        try {
            for (frame in 0..frameCount) {
                widths += nodeProvider().fetchSemanticsNode().size.width.toFloat()
                if (frame < frameCount) {
                    onFrame(frame)
                    Snapshot.sendApplyNotifications()
                    mainClock.advanceTimeByFrame()
                }
            }
        } finally {
            mainClock.autoAdvance = prev
        }
        return widths
    }

    private fun ComposeUiTest.maxAdjacentDeltaDp(samplesPx: List<Float>): Dp {
        var maxPx = 0f
        for (i in 1 until samplesPx.size) {
            val delta = abs(samplesPx[i] - samplesPx[i - 1])
            if (delta > maxPx) maxPx = delta
        }
        return with(density) { maxPx.toDp() }
    }

    private fun ComposeUiTest.totalDeltaDp(samplesPx: List<Float>): Dp {
        var totalPx = 0f
        for (i in 1 until samplesPx.size) {
            totalPx += abs(samplesPx[i] - samplesPx[i - 1])
        }
        return with(density) { totalPx.toDp() }
    }

    private fun ComposeUiTest.assertNavItemsAccessible(
        selected: ShellDestination,
        expectUnselectedLabels: Boolean,
    ) {
        val expectedLabels = mapOf(
            ShellDestination.Forum to "论坛",
            ShellDestination.Messages to "消息",
            ShellDestination.Search to "搜索",
            ShellDestination.Mine to "我的",
        )
        ShellDestination.entries.forEach { destination ->
            val node = onNodeWithTag(HiShellTestTags.navItem(destination)).fetchSemanticsNode()
            val widthDp = with(density) { node.size.width.toDp() }
            val heightDp = with(density) { node.size.height.toDp() }
            assertTrue("${destination.name} 触摸目标宽度应 ≥ 48dp，实际 $widthDp", widthDp >= 47.5.dp)
            assertTrue("${destination.name} 触摸目标高度应 ≥ 48dp，实际 $heightDp", heightDp >= 47.5.dp)

            assertEquals("${destination.name} 应带 Role.Tab", Role.Tab, node.config.getOrNull(SemanticsProperties.Role))
            val isSelected = destination == selected
            assertEquals(
                "${destination.name} 选中语义不匹配",
                isSelected,
                node.config.getOrNull(SemanticsProperties.Selected),
            )

            val expectedLabel = expectedLabels.getValue(destination)
            val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            val contentDescs = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            val hasAccessibleName = expectedLabel in texts || expectedLabel in contentDescs
            assertTrue(
                "${destination.name} 缺少文本或内容描述 '$expectedLabel'（texts=$texts, desc=$contentDescs）",
                hasAccessibleName,
            )

            if (isSelected || expectUnselectedLabels) {
                assertTrue("${destination.name} 应显示文本 '$expectedLabel'（texts=$texts）", expectedLabel in texts)
            } else {
                assertFalse("${destination.name} 窄窗/紧凑态未选中时不应显示文字（texts=$texts）", texts.isNotEmpty())
                assertTrue("${destination.name} 无文字时应有内容描述 '$expectedLabel'", expectedLabel in contentDescs)
            }
        }
    }

    @Suppress("RestrictedApi")
    private fun withFakeFoldingFeature(feature: FoldingFeature, block: () -> Unit) {
        val layoutInfo = WindowLayoutInfo(listOf(feature))
        val decorator = object : WindowInfoTrackerDecorator {
            override fun decorate(tracker: WindowInfoTracker): WindowInfoTracker = object : WindowInfoTracker {
                override fun windowLayoutInfo(activity: Activity): Flow<WindowLayoutInfo> = flowOf(layoutInfo)
                override fun windowLayoutInfo(context: Context): Flow<WindowLayoutInfo> = flowOf(layoutInfo)
            }
        }
        WindowInfoTracker.overrideDecorator(decorator)
        try {
            block()
        } finally {
            WindowInfoTracker.reset()
        }
    }

    private class FakeFoldingFeature(
        override val bounds: AndroidRect,
        override val orientation: FoldingFeature.Orientation,
        override val state: FoldingFeature.State,
        override val isSeparating: Boolean,
        override val occlusionType: FoldingFeature.OcclusionType = FoldingFeature.OcclusionType.NONE,
    ) : FoldingFeature
}
