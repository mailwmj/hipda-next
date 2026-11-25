package net.jejer.hipda.ui.challenge

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.testing.assertFrameContinuity
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiScreenshotTestSupport
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val SLOT_TEST_TAG = "challenge_motion_webview_slot"

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class ChallengePanelMotionTest {

    // region Step 2e: < 600dp bottom sheet frame continuity & mid-flight interruption

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun bottomSheet_enterExitAndMidFlightInterrupt_isFrameContinuous() = withAnimatorDurationScale(1f) {
        var visible by mutableStateOf(false)

        mainClock.autoAdvance = false
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ChallengePanelOverlay(
                    visible = visible,
                    onRefresh = {},
                    onCancel = { visible = false },
                    webViewSlot = { Box(Modifier.fillMaxSize().testTag(SLOT_TEST_TAG)) },
                )
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithTag(SLOT_TEST_TAG).assertDoesNotExist()

        // 1) Normal enter: trigger visible=true, advance one frame so node mounts, then verify continuity
        visible = true
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame()

        val enterResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag(SLOT_TEST_TAG) },
            frameCount = 24,
            maxStepDp = 120.dp,
            minTotalTravelDp = 40.dp,
        )
        val enterStartY = enterResult.positionsPx.first().y
        val settledY = enterResult.positionsPx.last().y
        assertTrue(
            "Bottom sheet must slide upward into view: startY=$enterStartY, settledY=$settledY",
            settledY < enterStartY - with(density) { 40.dp.toPx() },
        )

        // Reset back to hidden cleanly
        mainClock.autoAdvance = true
        visible = false
        waitForIdle()
        onNodeWithTag(SLOT_TEST_TAG).assertDoesNotExist()

        // 2) Interrupt enter mid-flight at frame 4 -> smooth reversal downward without jump
        mainClock.autoAdvance = false
        visible = true
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame()

        val interruptResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag(SLOT_TEST_TAG) },
            frameCount = 12,
            maxStepDp = enterResult.maxStepDp + 20.dp,
            minTotalTravelDp = 30.dp,
            onFrame = { frame ->
                if (frame == 4) {
                    visible = false
                }
            },
        )
        val highestY = interruptResult.positionsPx.minOf { it.y }
        val finalY = interruptResult.positionsPx.last().y
        assertTrue(
            "Mid-flight interrupt must happen before sheet fully settles: highestY=$highestY vs settledY=$settledY",
            highestY > settledY + with(density) { 10.dp.toPx() },
        )
        assertTrue(
            "After interruption sheet must reverse downward: finalY=$finalY vs highestY=$highestY",
            finalY > highestY + with(density) { 10.dp.toPx() },
        )

        // Finish exit cleanly
        mainClock.autoAdvance = true
        waitForIdle()
        onNodeWithTag(SLOT_TEST_TAG).assertDoesNotExist()
    }

    // endregion

    // region Step 2f: >= 600dp centered dialog (<= 480dp) interruptible enter/exit

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun dialog_centeredMax480dp_enterAndMidFlightInterrupt_isSmooth() = withAnimatorDurationScale(1f) {
        var visible by mutableStateOf(false)

        mainClock.autoAdvance = false
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ChallengePanelOverlay(
                    visible = visible,
                    onRefresh = {},
                    onCancel = { visible = false },
                    webViewSlot = { Box(Modifier.fillMaxSize().testTag(SLOT_TEST_TAG)) },
                )
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithTag(SLOT_TEST_TAG).assertDoesNotExist()

        // 1) Enter and verify scale-in motion + settled centered width <= 480dp
        visible = true
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame()

        val enterResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag(SLOT_TEST_TAG) },
            frameCount = 16,
            maxStepDp = 40.dp,
            minTotalTravelDp = 4.dp,
        )

        mainClock.autoAdvance = true
        waitForIdle()

        val rootBounds = onRoot().fetchSemanticsNode().boundsInRoot
        val slotBounds = onNodeWithTag(SLOT_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val maxDialogWidthPx = with(density) { 480.dp.toPx() }
        assertTrue(
            "Dialog width ${with(density) { slotBounds.width.toDp() }} must be <= 480dp",
            slotBounds.width <= maxDialogWidthPx + 1f,
        )
        val leftMarginPx = slotBounds.left - rootBounds.left
        val rightMarginPx = rootBounds.right - slotBounds.right
        assertTrue(
            "Dialog must be horizontally centered: left=$leftMarginPx, right=$rightMarginPx",
            abs(leftMarginPx - rightMarginPx) <= with(density) { 2.dp.toPx() },
        )

        // Reset hidden
        visible = false
        waitForIdle()
        onNodeWithTag(SLOT_TEST_TAG).assertDoesNotExist()

        // 2) Interrupt enter mid-flight at frame 3 -> reverses smoothly without jumping
        mainClock.autoAdvance = false
        visible = true
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame()

        assertFrameContinuity(
            nodeProvider = { onNodeWithTag(SLOT_TEST_TAG) },
            frameCount = 8,
            maxStepDp = enterResult.maxStepDp + 10.dp,
            minTotalTravelDp = 2.dp,
            onFrame = { frame ->
                if (frame == 3) {
                    visible = false
                }
            },
        )

        mainClock.autoAdvance = true
        waitForIdle()
        onNodeWithTag(SLOT_TEST_TAG).assertDoesNotExist()
    }

    // endregion

    // region Step 2g: Reduced motion (scale = 0f) settles in 1 animation frame

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun bottomSheet_underReducedMotion_entersAndExitsInOneFrame() = assertReducedMotionOneFrame()

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun dialog_underReducedMotion_entersAndExitsInOneFrame() = assertReducedMotionOneFrame()

    private fun assertReducedMotionOneFrame() = withAnimatorDurationScale(0f) {
        var visible by mutableStateOf(false)
        var dismissedCount = 0

        mainClock.autoAdvance = false
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ChallengePanelOverlay(
                    visible = visible,
                    onRefresh = {},
                    onCancel = { visible = false },
                    onDismissed = { dismissedCount++ },
                    webViewSlot = { Box(Modifier.fillMaxSize().testTag(SLOT_TEST_TAG)) },
                )
            }
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onNodeWithTag(SLOT_TEST_TAG).assertDoesNotExist()

        // Enter settles immediately in 1 animation frame with no residual movement
        visible = true
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame() // recompose & launch transition
        mainClock.advanceTimeByFrame() // first animation frame: scale=0 jumps to target
        mainClock.advanceTimeByFrame() // commit final layout
        waitForIdle()

        onNodeWithTag(SLOT_TEST_TAG).assertIsDisplayed()
        val afterOneAnimFrame = onNodeWithTag(SLOT_TEST_TAG).fetchSemanticsNode().boundsInRoot
        repeat(10) { mainClock.advanceTimeByFrame() }
        waitForIdle()
        val settled = onNodeWithTag(SLOT_TEST_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals("No residual horizontal drift under scale=0", afterOneAnimFrame.left, settled.left, 0.5f)
        assertEquals("No residual vertical drift under scale=0", afterOneAnimFrame.top, settled.top, 0.5f)
        assertEquals("No residual width drift under scale=0", afterOneAnimFrame.width, settled.width, 0.5f)
        assertEquals("No residual height drift under scale=0", afterOneAnimFrame.height, settled.height, 0.5f)

        // Exit settles immediately in 1 animation frame and triggers onDismissed
        visible = false
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        waitForIdle()

        onNodeWithTag(SLOT_TEST_TAG).assertDoesNotExist()
        assertEquals("onDismissed should fire once after exit", 1, dismissedCount)
    }

    // endregion

    // region V-A6: ChallengeStatusView Checking -> Incomplete centered stability & smooth height transition

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun statusView_checkingToIncomplete_keepsCenteredTextStableAndAnimatesHeightSmoothly() {
        // 1) Centered container: fading-out "正在检查安全环境…" must not jump vertically on transition
        withAnimatorDurationScale(1f) {
            var state by mutableStateOf<ChallengeState>(ChallengeState.Solving(startedAtMs = 0L))

            mainClock.autoAdvance = false
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            ChallengeStatusView(
                                state = state,
                                onRetry = {},
                                clock = { 2_500L },
                            )
                        }
                    }
                }
            }
            mainClock.advanceTimeByFrame()
            waitForIdle()
            onNodeWithText("正在检查安全环境…").assertIsDisplayed()
            val initialCheckingY = onNodeWithText("正在检查安全环境…").fetchSemanticsNode().positionInRoot.y
            val maxAllowedDriftPx = with(density) { 1.dp.toPx() }

            state = ChallengeState.Cooldown(untilMs = 30_000L, rejected = false)
            Snapshot.sendApplyNotifications()
            var observedFadeFrames = 0
            repeat(20) { frame ->
                mainClock.advanceTimeByFrame()
                val nodes = onAllNodes(androidx.compose.ui.test.hasText("正在检查安全环境…")).fetchSemanticsNodes()
                if (nodes.isNotEmpty()) {
                    observedFadeFrames++
                    val currentY = nodes.first().positionInRoot.y
                    assertTrue(
                        "Frame $frame: centered checking text drifted ${with(density) { abs(currentY - initialCheckingY).toDp() }} (> 1dp)",
                        abs(currentY - initialCheckingY) <= maxAllowedDriftPx,
                    )
                }
            }
            assertTrue("Expected checking text to remain visible across fade-out frames", observedFadeFrames >= 1)
            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithText("安全验证未完成").assertIsDisplayed()
        }

        // 2) Column layout: sibling below ChallengeStatusView transitions smoothly via HiMotion.spatialDefault()
        withAnimatorDurationScale(1f) {
            var state by mutableStateOf<ChallengeState>(ChallengeState.Solving(startedAtMs = 0L))

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxWidth()) {
                            ChallengeStatusView(
                                state = state,
                                onRetry = {},
                                clock = { 2_500L },
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(16.dp)
                                    .testTag("anchor_below_challenge_status"),
                            )
                        }
                    }
                }
            }
            waitForIdle()
            onNodeWithText("正在检查安全环境…").assertIsDisplayed()

            val result = assertFrameContinuity(
                nodeProvider = { onNodeWithTag("anchor_below_challenge_status") },
                frameCount = 24,
                maxStepDp = 24.dp,
                minTotalTravelDp = 30.dp,
                onFrame = { frame ->
                    if (frame == 0) {
                        state = ChallengeState.Cooldown(untilMs = 30_000L, rejected = false)
                    }
                },
            )
            assertTrue(
                "Anchor below ChallengeStatusView should move smoothly downward (maxStep=${result.maxStepDp}, travel=${result.totalTravelDp})",
                result.positionsPx.last().y > result.positionsPx.first().y + with(density) { 30.dp.toPx() },
            )
        }
    }

    // endregion
}
