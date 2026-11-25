package net.jejer.hipda.ui.shell

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 防同类回归参数化测试：覆盖全部 4 种 [ShellNavigationMode]（`BottomPill` / `CompactPill` / `SidebarCollapsed` / `SidebarExpanded`）
 * × 宽度 200..1280dp（步长 20dp）× 高度 {40, 300, 411, 800}dp × 字号缩放 {1.0, 1.3}。
 *
 * 断言所有组合均不抛异常、核心导航项可见且位于容器内互不重叠，并在物理空间足以容纳 48dp 目标时严格满足 ≥ 48dp 触摸目标。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w1280dp-h800dp-xhdpi")
class HiShellBoundsGuardTest(
    private val mode: ShellNavigationMode,
    private val heightDp: Int,
    private val fontScale: Float,
) {

    companion object {
        private val HEIGHTS = listOf(40, 300, 411, 800)
        private val FONT_SCALES = listOf(1.0f, 1.3f)
        private val WIDTHS = (200..1280 step 20).toList()

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}_h{1}_font{2}")
        fun data(): Collection<Array<Any>> = buildList {
            for (mode in ShellNavigationMode.entries) {
                for (height in HEIGHTS) {
                    for (scale in FONT_SCALES) {
                        add(arrayOf(mode, height, scale))
                    }
                }
            }
        }
    }

    @Test
    fun rendersAcrossAllWidthsWithoutCrashClippingOrOverlap() = withAnimatorDurationScale(1f) {
        var currentWidthDp by mutableIntStateOf(WIDTHS.first())

        setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = fontScale)) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val glassState = rememberHiGlassState()
                    Box(
                        modifier = Modifier
                            .size(width = currentWidthDp.dp, height = heightDp.dp)
                            .testTag("guard_container"),
                    ) {
                        when (mode) {
                            ShellNavigationMode.SidebarCollapsed -> HiNavigationSidebar(
                                state = HiSidebarState(
                                    selectedEntry = ShellSidebarEntry.TodayNew,
                                    unreadSmsCount = 3,
                                    unreadNotifyCount = 120,
                                ),
                                expanded = false,
                                mineGroupExpanded = false,
                                onToggleSidebar = {},
                                onToggleMineGroup = {},
                                onCollapsedMineClick = {},
                                onSelectEntry = {},
                                onAccountClick = {},
                                onManageForumsClick = {},
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .width(SHELL_SIDEBAR_COLLAPSED_WIDTH_DP.dp),
                            )

                            ShellNavigationMode.SidebarExpanded -> {
                                val sidebarWidth = (currentWidthDp * 0.20f)
                                    .toInt()
                                    .coerceIn(SHELL_SIDEBAR_EXPANDED_MIN_WIDTH_DP, SHELL_SIDEBAR_EXPANDED_MAX_WIDTH_DP)
                                    .coerceAtMost(currentWidthDp)
                                HiNavigationSidebar(
                                    state = HiSidebarState(
                                        selectedEntry = ShellSidebarEntry.TodayNew,
                                        unreadSmsCount = 3,
                                        unreadNotifyCount = 120,
                                        account = HiSidebarAccount(username = "HiPDA"),
                                    ),
                                    expanded = true,
                                    mineGroupExpanded = heightDp >= SHELL_MINE_GROUP_AUTO_EXPAND_HEIGHT_DP,
                                    onToggleSidebar = {},
                                    onToggleMineGroup = {},
                                    onCollapsedMineClick = {},
                                    onSelectEntry = {},
                                    onAccountClick = {},
                                    onManageForumsClick = {},
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .width(sidebarWidth.dp),
                                )
                            }

                            ShellNavigationMode.BottomPill,
                            ShellNavigationMode.CompactPill,
                            -> HiNavigationPill(
                                selected = ShellDestination.Forum,
                                onSelect = {},
                                glassState = glassState,
                                compact = mode == ShellNavigationMode.CompactPill,
                                showUnselectedLabels = showLabelForUnselected(currentWidthDp.toFloat()),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(
                                        start = HiNavigationPillHorizontalMargin,
                                        end = HiNavigationPillHorizontalMargin,
                                        bottom = HiNavigationPillBottomGap,
                                    ),
                            )
                        }
                    }
                }
            }
        }

        val tolerancePx = 2f
        val sidebarTopEntries = listOf(
            ShellSidebarEntry.TodayNew,
            ShellSidebarEntry.Search,
            ShellSidebarEntry.Sms,
            ShellSidebarEntry.Notify,
        )

        for (widthDp in WIDTHS) {
            currentWidthDp = widthDp
            waitForIdle()

            val containerBounds = onNodeWithTag("guard_container").fetchSemanticsNode().boundsInRoot
            val enoughHeightForTouchTarget = heightDp >= 300

            if (mode.isSidebar) {
                val sidebarBounds = onNodeWithTag(HiShellTestTags.NavigationSidebar).fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "mode=$mode ${widthDp}x${heightDp} 侧栏应在容器水平范围内",
                    sidebarBounds.left >= containerBounds.left - tolerancePx &&
                        sidebarBounds.right <= containerBounds.right + tolerancePx,
                )
                if (!enoughHeightForTouchTarget) continue

                val toggleBounds = onNodeWithTag(HiShellTestTags.SidebarToggleButton).fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "mode=$mode ${widthDp}x${heightDp} 折叠按钮宽度应 ≥ 48dp",
                    with(density) { toggleBounds.width.toDp() } >= 47.5.dp,
                )
                assertTrue(
                    "mode=$mode ${widthDp}x${heightDp} 折叠按钮高度应 ≥ 48dp",
                    with(density) { toggleBounds.height.toDp() } >= 47.5.dp,
                )

                val visibleEntryBounds = sidebarTopEntries.mapNotNull { entry ->
                    val node = onAllNodes(
                        androidx.compose.ui.test.hasTestTag(HiShellTestTags.sidebarEntry(entry)),
                    ).fetchSemanticsNodes().firstOrNull()
                    node?.let { entry to it.boundsInRoot }
                }
                assertTrue("mode=$mode ${widthDp}x${heightDp} 应渲染主功能入口", visibleEntryBounds.size >= 3)
                visibleEntryBounds.forEach { (entry, bounds) ->
                    val tag = "mode=$mode, ${widthDp}x${heightDp}dp, fontScale=$fontScale, entry=$entry"
                    assertTrue("$tag 宽度应 > 0", bounds.width > 0f)
                    assertTrue("$tag 高度应 > 0", bounds.height > 0f)
                    assertTrue(
                        "$tag 水平越界: [${bounds.left}, ${bounds.right}] vs [${sidebarBounds.left}, ${sidebarBounds.right}]",
                        bounds.left >= sidebarBounds.left - tolerancePx && bounds.right <= sidebarBounds.right + tolerancePx,
                    )
                    assertTrue(
                        "$tag 触摸目标宽度应 ≥ 48dp",
                        with(density) { bounds.width.toDp() } >= 47.5.dp,
                    )
                    assertTrue(
                        "$tag 触摸目标高度应 ≥ 44dp（含 48dp 行高槽位）",
                        with(density) { bounds.height.toDp() } >= 43.5.dp,
                    )
                }
                for (i in 0 until visibleEntryBounds.lastIndex) {
                    val (aEntry, a) = visibleEntryBounds[i]
                    val (bEntry, b) = visibleEntryBounds[i + 1]
                    assertTrue(
                        "mode=$mode ${widthDp}x${heightDp} $aEntry/$bEntry 纵向重叠: ${a.bottom} > ${b.top}",
                        a.bottom <= b.top + tolerancePx,
                    )
                }
            } else {
                val itemBounds = ShellDestination.entries.map { dest ->
                    dest to onNodeWithTag(HiShellTestTags.navItem(dest)).fetchSemanticsNode().boundsInRoot
                }
                val enoughWidthForTouchTarget = widthDp >= 240

                itemBounds.forEach { (dest, bounds) ->
                    val tag = "mode=$mode, ${widthDp}x${heightDp}dp, fontScale=$fontScale, item=${dest.name}"
                    assertTrue("$tag 宽度应 > 0，实际 ${bounds.width}", bounds.width > 0f)
                    assertTrue("$tag 高度应 > 0，实际 ${bounds.height}", bounds.height > 0f)
                    assertTrue(
                        "$tag 超出容器水平边界：item=[${bounds.left}, ${bounds.right}] vs container=[${containerBounds.left}, ${containerBounds.right}]",
                        bounds.left >= containerBounds.left - tolerancePx && bounds.right <= containerBounds.right + tolerancePx,
                    )
                    assertTrue(
                        "$tag 超出容器垂直边界：item=[${bounds.top}, ${bounds.bottom}] vs container=[${containerBounds.top}, ${containerBounds.bottom}]",
                        bounds.top >= containerBounds.top - tolerancePx && bounds.bottom <= containerBounds.bottom + tolerancePx,
                    )

                    val itemWidthDp = with(density) { bounds.width.toDp() }
                    val itemHeightDp = with(density) { bounds.height.toDp() }
                    if (enoughWidthForTouchTarget) {
                        assertTrue("$tag 触摸目标宽度应 ≥ 48dp，实际 $itemWidthDp", itemWidthDp >= 47.5.dp)
                    }
                    if (enoughHeightForTouchTarget) {
                        assertTrue("$tag 触摸目标高度应 ≥ 48dp，实际 $itemHeightDp", itemHeightDp >= 47.5.dp)
                    }
                }

                for (i in 0 until itemBounds.lastIndex) {
                    val (aDest, a) = itemBounds[i]
                    val (bDest, b) = itemBounds[i + 1]
                    val tag = "mode=$mode, ${widthDp}x${heightDp}dp, fontScale=$fontScale, ${aDest.name}/${bDest.name}"
                    assertTrue("$tag 横向重叠：${a.right} > ${b.left}", a.right <= b.left + tolerancePx)
                }
            }
        }
    }
}
