package net.jejer.hipda.ui.shell

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val QUALIFIER_PHONE_411_891 = "w411dp-h891dp-xhdpi"

/**
 * 导航随滚动隐藏与底部留白的 2 张 Roborazzi 截图基线（前缀 `shell_scroll_`）：
 * - 411×891 浅色滚到底（最后一项完全露出、胶囊可见）
 * - 411×891 浅色胶囊已隐藏的中间状态（滚动后静止于全隐）
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class HiShellScrollScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = QUALIFIER_PHONE_411_891)
    fun shell_scroll_w411_h891_bottom_light() {
        var listState: LazyListState? = null
        var scope: CoroutineScope? = null
        composeRule.setContent {
            scope = rememberCoroutineScope()
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { destination, contentPadding ->
                    val state = rememberLazyListState()
                    listState = state
                    ShellScrollSpecimenContent(
                        destination = destination,
                        contentPadding = contentPadding,
                        listState = state,
                        badge = "411×891 · 滚到底",
                    )
                }
            }
        }
        composeRule.waitForIdle()

        requireNotNull(scope).launch {
            requireNotNull(listState).scrollToItem(59)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HiShellTestTags.BottomPill).assertIsDisplayed()
        val pillTop = composeRule.onNodeWithTag(HiShellTestTags.BottomPill).fetchSemanticsNode().boundsInRoot.top
        val lastBottom = composeRule.onNodeWithTag("scroll_specimen_59").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("滚到底最后一项底边 ($lastBottom) 应 <= 胶囊顶边 ($pillTop)", lastBottom <= pillTop + 1f)
        assertSingleGlassSurface()

        composeRule.onRoot().captureHiSnapshot("shell_scroll_w411_h891_bottom_light")
    }

    @Test
    @Config(qualifiers = QUALIFIER_PHONE_411_891)
    fun shell_scroll_w411_h891_hidden_light() {
        var touchSlop = 0f
        composeRule.setContent {
            touchSlop = LocalViewConfiguration.current.touchSlop
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { destination, contentPadding ->
                    ShellScrollSpecimenContent(
                        destination = destination,
                        contentPadding = contentPadding,
                        listState = rememberLazyListState(),
                        badge = "411×891 · 胶囊全隐",
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("scroll_specimen_list").performTouchInput {
            down(center)
            moveBy(Offset(0f, -touchSlop))
            moveBy(Offset(0f, -420f))
            advanceEventTime(200L)
            up()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HiShellTestTags.BottomPill).assertIsNotDisplayed()
        assertSingleGlassSurface()

        composeRule.onRoot().captureHiSnapshot("shell_scroll_w411_h891_hidden_light")
    }

    private fun assertSingleGlassSurface() {
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        val count = composeRule.onAllNodes(
            matcher = SemanticsMatcher.expectValue(HiGlassMarker, true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        assertEquals("外壳截图应恰好包含 1 块毛玻璃", 1, count)
    }
}

@Composable
private fun ShellScrollSpecimenContent(
    destination: ShellDestination,
    contentPadding: PaddingValues,
    listState: LazyListState,
    badge: String,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(cs.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(destination.labelRes),
                style = MaterialTheme.typography.titleLarge,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .clip(HiShapeTokens.Small)
                    .background(cs.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onPrimaryContainer,
                    maxLines = 1,
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("scroll_specimen_list"),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = 12.dp + contentPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(60) { index ->
                val accentBg = when (index % 3) {
                    0 -> cs.primaryContainer
                    1 -> cs.tertiaryContainer
                    else -> cs.secondaryContainer
                }
                val accentFg = when (index % 3) {
                    0 -> cs.onPrimaryContainer
                    1 -> cs.onTertiaryContainer
                    else -> cs.onSecondaryContainer
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("scroll_specimen_$index")
                        .clip(HiShapeTokens.Medium)
                        .background(cs.surfaceContainerLow)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(HiShapeTokens.Full)
                            .background(accentBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = accentFg,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (index == 59) "第 60 条 · 列表最后一项完全露出验证" else "Discovery · 长列表滚动示例 #${index + 1}",
                            style = HiTheme.typography.threadTitle,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "内容延伸至底部毛玻璃导航胶囊下方滚动，滚到底时末项完整露出。",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
