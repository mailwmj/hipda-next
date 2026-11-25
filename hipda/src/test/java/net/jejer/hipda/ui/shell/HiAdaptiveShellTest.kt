package net.jejer.hipda.ui.shell

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import net.jejer.hipda.testing.RecompositionCounter
import net.jejer.hipda.testing.assertFrameContinuity
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
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
                ) { destination ->
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

    // ---- (b) 选中指示器可打断：中途切回原目的地，逐帧位置连续 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun selectionIndicator_bottomPill_interruptibleAndContinuous() = withAnimatorDurationScale(1f) {
        var selected by mutableStateOf(ShellDestination.Forum)
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = selected,
                    onSelect = { selected = it },
                ) { destination ->
                    Box(Modifier.fillMaxSize()) {
                        Text(destination.name)
                    }
                }
            }
        }
        waitForIdle()

        val normalResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true) },
            frameCount = 24,
            maxStepDp = 120.dp,
            minTotalTravelDp = 100.dp,
            onFrame = { frame ->
                if (frame == 0) {
                    selected = ShellDestination.Mine
                }
            },
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
                if (frame == 0) {
                    selected = ShellDestination.Mine
                } else if (frame == 4) {
                    selected = ShellDestination.Forum
                }
            },
        )
        val peakX = reversalResult.positionsPx.maxOf { it.x }
        val finalX = reversalResult.positionsPx.last().x
        assertTrue("打断前应已明显右移（start=$startX, peak=$peakX）", peakX > startX + with(density) { 20.dp.toPx() })
        assertTrue("打断后应回落向起点（peak=$peakX, final=$finalX）", finalX < peakX - with(density) { 15.dp.toPx() })
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun selectionIndicator_compactPill_positionAndWidthInterruptibleAndContinuous() = withAnimatorDurationScale(1f) {
        var selected by mutableStateOf(ShellDestination.Forum)
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = selected,
                    onSelect = { selected = it },
                ) { destination ->
                    Box(Modifier.fillMaxSize()) {
                        Text(destination.name)
                    }
                }
            }
        }
        waitForIdle()

        val normalResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true) },
            frameCount = 24,
            maxStepDp = 120.dp,
            minTotalTravelDp = 60.dp,
            onFrame = { frame ->
                if (frame == 0) {
                    selected = ShellDestination.Mine
                }
            },
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
                if (frame == 0) {
                    selected = ShellDestination.Mine
                } else if (frame == 4) {
                    selected = ShellDestination.Forum
                }
            },
        )
        val peakX = reversalResult.positionsPx.maxOf { it.x }
        val finalX = reversalResult.positionsPx.last().x
        assertTrue("紧凑胶囊打断前应已明显右移（start=$startX, peak=$peakX）", peakX > startX + with(density) { 15.dp.toPx() })
        assertTrue("紧凑胶囊打断后应回落向起点（peak=$peakX, final=$finalX）", finalX < peakX - with(density) { 10.dp.toPx() })
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
                ) { destination ->
                    Box(Modifier.fillMaxSize()) {
                        Text(destination.name)
                    }
                }
            }
        }
        waitForIdle()

        val normalResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true) },
            frameCount = 24,
            maxStepDp = 120.dp,
            minTotalTravelDp = 100.dp,
            onFrame = { frame ->
                if (frame == 0) {
                    selected = ShellDestination.Mine
                }
            },
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
                if (frame == 0) {
                    selected = ShellDestination.Mine
                } else if (frame == 4) {
                    selected = ShellDestination.Forum
                }
            },
        )
        val peakY = reversalResult.positionsPx.maxOf { it.y }
        val finalY = reversalResult.positionsPx.last().y
        assertTrue("导航轨打断前应已明显下移（start=$startY, peak=$peakY）", peakY > startY + with(density) { 20.dp.toPx() })
        assertTrue("导航轨打断后应回落向起点（peak=$peakY, final=$finalY）", finalY < peakY - with(density) { 15.dp.toPx() })
    }

    // ---- (c) 减少动态效果：scale=0f 时指示器与内容一帧到位 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun reducedMotion_indicatorAndContentFinishImmediately() {
        // 对照组（scale = 1f）：推进首帧动画后，指示器仍在途中，旧内容仍在退场
        withAnimatorDurationScale(1f) {
            var selected by mutableStateOf(ShellDestination.Forum)
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    HiAdaptiveShell(
                        selected = selected,
                        onSelect = { selected = it },
                    ) { destination ->
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
            mainClock.advanceTimeByFrame() // 重组并派发动画命令
            mainClock.advanceTimeByFrame() // 动画首帧（t=0）
            mainClock.advanceTimeByFrame() // 动画次帧（t=16ms）
            waitForIdle()

            val indicatorX = onNodeWithTag(HiShellTestTags.SelectionIndicator, useUnmergedTree = true)
                .fetchSemanticsNode().positionInRoot.x
            assertTrue(
                "正常缩放下 16ms 后指示器应仍在途中（indicatorX=$indicatorX, mineX=$mineX）",
                abs(indicatorX - mineX) > with(density) { 20.dp.toPx() },
            )
        }

        // 减少动态效果（scale = 0f）：指示器和内容在首动画帧一步到位，无残留中间节点
        withAnimatorDurationScale(0f) {
            var selected by mutableStateOf(ShellDestination.Forum)
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    HiAdaptiveShell(
                        selected = selected,
                        onSelect = { selected = it },
                    ) { destination ->
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
            mainClock.advanceTimeByFrame() // 重组并派发动画命令
            mainClock.advanceTimeByFrame() // 动画首帧：scale=0 直接跳到终点
            mainClock.advanceTimeByFrame() // 提交最终布局状态
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
        verifyUnselectedItemsSkipRecomposition()

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun destinationSwitch_unselectedNavItemsDoNotRecompose_compactPill() =
        verifyUnselectedItemsSkipRecomposition()

    @Test
    @Config(qualifiers = "w900dp-h1120dp-xhdpi")
    fun destinationSwitch_unselectedNavItemsDoNotRecompose_rail() =
        verifyUnselectedItemsSkipRecomposition()

    private fun verifyUnselectedItemsSkipRecomposition() = withAnimatorDurationScale(1f) {
        val counters = ShellDestination.entries.associateWith { RecompositionCounter("NavItem_${it.name}") }
        var selected by mutableStateOf(ShellDestination.Forum)
        val onSelectStable: (ShellDestination) -> Unit = { selected = it }

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                CompositionLocalProvider(
                    LocalShellItemCompositionObserver provides { destination ->
                        counters.getValue(destination).record()
                    },
                ) {
                    HiAdaptiveShell(
                        selected = selected,
                        onSelect = onSelectStable,
                    ) { destination ->
                        Box(Modifier.fillMaxSize().testTag("content_${destination.name}"))
                    }
                }
            }
        }
        waitForIdle()
        ShellDestination.entries.forEach { dest ->
            counters.getValue(dest).assertEquals(1)
            counters.getValue(dest).assertRecompositionsAtMost(0)
        }

        // Forum -> Search：Messages 与 Mine 均保持未选中，不应发生任何重组
        selected = ShellDestination.Search
        waitForIdle()

        counters.getValue(ShellDestination.Forum).assertEquals(2)
        counters.getValue(ShellDestination.Search).assertEquals(2)
        counters.getValue(ShellDestination.Messages).assertEquals(1)
        counters.getValue(ShellDestination.Messages).assertRecompositionsAtMost(0)
        counters.getValue(ShellDestination.Mine).assertEquals(1)
        counters.getValue(ShellDestination.Mine).assertRecompositionsAtMost(0)

        // Search -> Mine：Forum 与 Messages 均保持未选中，不应发生任何重组
        selected = ShellDestination.Mine
        waitForIdle()

        counters.getValue(ShellDestination.Forum).assertEquals(2)
        counters.getValue(ShellDestination.Messages).assertEquals(1)
        counters.getValue(ShellDestination.Messages).assertRecompositionsAtMost(0)
        counters.getValue(ShellDestination.Search).assertEquals(3)
        counters.getValue(ShellDestination.Mine).assertEquals(2)
    }

    // ---- (e) 无障碍与形态几何断言 ----

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun accessibilityAndGeometry_bottomPill() = withAnimatorDurationScale(1f) {
        var selected by mutableStateOf(ShellDestination.Forum)
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = selected,
                    onSelect = { selected = it },
                ) { destination ->
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

        // 点击“搜索”应切换选中语义
        onNodeWithTag(HiShellTestTags.navItem(ShellDestination.Search)).performClick()
        waitForIdle()
        assertEquals(ShellDestination.Search, selected)
        assertNavItemsAccessible(selected = ShellDestination.Search, expectUnselectedLabels = true)
    }

    @Test
    @Config(qualifiers = "w700dp-h900dp-xhdpi")
    fun bottomPill_mediumWidthIsCenteredAndCappedAt420dp() = withAnimatorDurationScale(1f) {
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { _ ->
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
                ) { _ ->
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
    fun accessibilityAndGeometry_compactPill() = withAnimatorDurationScale(1f) {
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { _ ->
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        waitForIdle()

        val pillBounds = onNodeWithTag(HiShellTestTags.CompactPill).fetchSemanticsNode().boundsInRoot
        val pillHeightDp = with(density) { pillBounds.height.toDp() }
        assertTrue("紧凑胶囊最小高度应 ≥ 48dp，实际 $pillHeightDp", pillHeightDp >= 47.5.dp)
        assertNavItemsAccessible(selected = ShellDestination.Forum, expectUnselectedLabels = false)
    }

    @Test
    @Config(qualifiers = "w900dp-h1120dp-xhdpi")
    fun accessibilityAndGeometry_navigationRailDockedLeft80dp() = withAnimatorDurationScale(1f) {
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Mine,
                    onSelect = {},
                ) { _ ->
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

        assertNavItemsAccessible(selected = ShellDestination.Mine, expectUnselectedLabels = true)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.assertNavItemsAccessible(
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
}
