package net.jejer.hipda.ui.challenge

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiScreenshotTestSupport
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val MOCK_WEBVIEW_TAG = "challenge_screenshot_webview_slot"

/**
 * 可见验证面板与原地提示组件 Roborazzi 截图基线（前缀 `challenge_`，共 7 张）：
 * - 底部面板 411dp × 浅色/深色（2 张）
 * - 居中对话框 900dp × 浅色/深色（2 张，验证居中且宽 ≤ 480dp）
 * - 原地提示三态 411dp 浅色（3 张：checking / incomplete / rejected）
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class ChallengePanelScreenshotTest {

    // region Step 2h: 411dp bottom sheet (light & dark)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun challenge_panel_w411_light() = capturePanel(
        name = "challenge_panel_w411_light",
        themeMode = HiThemeMode.LIGHT,
        expectDialog = false,
    )

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun challenge_panel_w411_dark() = capturePanel(
        name = "challenge_panel_w411_dark",
        themeMode = HiThemeMode.DARK,
        expectDialog = false,
    )

    // endregion

    // region Step 2i: 900dp centered dialog (light & dark)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun challenge_dialog_w900_light() = capturePanel(
        name = "challenge_dialog_w900_light",
        themeMode = HiThemeMode.LIGHT,
        expectDialog = true,
    )

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun challenge_dialog_w900_dark() = capturePanel(
        name = "challenge_dialog_w900_dark",
        themeMode = HiThemeMode.DARK,
        expectDialog = true,
    )

    // endregion

    // region Step 2j: 411dp in-place status three states (light)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun challenge_status_checking_w411_light() = captureStatus(
        name = "challenge_status_checking_w411_light",
        state = ChallengeState.Solving(startedAtMs = 0L),
        nowMs = 2_500L,
        expectedText = "正在检查安全环境…",
    )

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun challenge_status_incomplete_w411_light() = captureStatus(
        name = "challenge_status_incomplete_w411_light",
        state = ChallengeState.Cooldown(untilMs = 30_000L, rejected = false),
        nowMs = 1_000L,
        expectedText = "安全验证未完成",
    )

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun challenge_status_rejected_w411_light() = captureStatus(
        name = "challenge_status_rejected_w411_light",
        state = ChallengeState.Cooldown(untilMs = 60_000L, rejected = true),
        nowMs = 1_000L,
        expectedText = "验证未通过",
    )

    // endregion

    private fun capturePanel(
        name: String,
        themeMode: HiThemeMode,
        expectDialog: Boolean,
    ) = withAnimatorDurationScale(0f) {
        setContent {
            HiTheme(
                themeMode = themeMode,
                seed = HiColorPresets.Brand.toSeed(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    MockForumBackdrop()
                    ChallengePanelOverlay(
                        visible = true,
                        onRefresh = {},
                        onCancel = {},
                        webViewSlot = { MockChallengeWebViewPlaceholder() },
                    )
                }
            }
        }
        waitForIdle()

        onNodeWithText("安全验证").assertIsDisplayed()
        onNodeWithText("请完成页面上的验证以继续访问").assertIsDisplayed()
        onNodeWithText("刷新").assertIsDisplayed()
        onNodeWithText("取消").assertIsDisplayed()

        // Must NOT use frosted glass (product-design.md §5)
        assertGlassSurfaceCountAtMost(0)

        // Touch targets >= 48dp for both buttons
        val minTouchPx = with(density) { 48.dp.toPx() } - 0.5f
        for (label in listOf("刷新", "取消")) {
            val node = onNodeWithText(label).fetchSemanticsNode()
            assertTrue("$label width must be >= 48dp", node.touchBoundsInRoot.width >= minTouchPx)
            assertTrue("$label height must be >= 48dp", node.touchBoundsInRoot.height >= minTouchPx)
        }

        val rootBounds = onRoot().fetchSemanticsNode().boundsInRoot
        val slotBounds = onNodeWithTag(MOCK_WEBVIEW_TAG).fetchSemanticsNode().boundsInRoot
        if (expectDialog) {
            val maxDialogWidthPx = with(density) { 480.dp.toPx() }
            assertTrue(
                "Dialog width must be <= 480dp, actual=${with(density) { slotBounds.width.toDp() }}",
                slotBounds.width <= maxDialogWidthPx + 1f,
            )
            val leftMargin = slotBounds.left - rootBounds.left
            val rightMargin = rootBounds.right - slotBounds.right
            assertTrue(
                "Dialog must be centered: left=$leftMargin, right=$rightMargin",
                abs(leftMargin - rightMargin) <= with(density) { 2.dp.toPx() },
            )
        } else {
            assertTrue(
                "Bottom sheet must span window width: slot=${slotBounds.width} vs root=${rootBounds.width}",
                abs(slotBounds.width - rootBounds.width) <= with(density) { 2.dp.toPx() },
            )
        }

        onRoot().captureHiSnapshot(name)
    }

    private fun captureStatus(
        name: String,
        state: ChallengeState,
        nowMs: Long,
        expectedText: String,
    ) = withAnimatorDurationScale(0f) {
        setContent {
            HiTheme(
                themeMode = HiThemeMode.LIGHT,
                seed = HiColorPresets.Brand.toSeed(),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChallengeStatusView(
                            state = state,
                            onRetry = {},
                            clock = { nowMs },
                        )
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithText(expectedText).assertIsDisplayed()
        onRoot().captureHiSnapshot(name)
    }
}

@Composable
private fun MockForumBackdrop() {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = cs.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Discovery",
                style = MaterialTheme.typography.titleLarge,
                color = cs.onBackground,
            )
            repeat(5) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp),
                    shape = HiShapeTokens.Medium,
                    color = cs.surfaceContainerLow,
                ) {}
            }
        }
    }
}

@Composable
private fun MockChallengeWebViewPlaceholder() {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(MOCK_WEBVIEW_TAG)
            .background(cs.surface)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "www.4d4y.com",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
            )
            Text(
                text = "Checking if the site connection is secure",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = HiShapeTokens.Small,
                color = cs.surfaceContainerLowest,
                border = BorderStroke(1.dp, cs.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Spacer(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(HiShapeTokens.Small)
                            .background(cs.surfaceContainerHighest),
                    )
                    Text(
                        text = "Verify you are human",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface,
                    )
                }
            }
        }
    }
}
