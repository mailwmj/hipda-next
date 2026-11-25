package net.jejer.hipda.ui.designsystem.feedback

import android.app.Application
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.jejer.hipda.testing.RecompositionCounter
import net.jejer.hipda.testing.assertFrameContinuity
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.motion.hiSlideInVertically
import net.jejer.hipda.ui.designsystem.motion.hiSlideOutVertically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HiFeedbackBehaviorTest {

    private fun digitMatcher(place: Int, digit: Char): SemanticsMatcher =
        hasText(digit.toString()) and SemanticsMatcher.expectValue(HiRollingDigitPlaceKey, place)

    private fun placeMatcher(place: Int): SemanticsMatcher =
        SemanticsMatcher.expectValue(HiRollingDigitPlaceKey, place)

    @Test
    fun rollingNumber_nineToTenRollsTensAndOnesUpward_tenToNineDownward_unchangedStaysStill() =
        withAnimatorDurationScale(1f) {
            var number by mutableIntStateOf(9)

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.padding(48.dp)) {
                        HiRollingNumber(
                            value = number,
                            modifier = Modifier.testTag("rolling"),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithTag("rolling").assertTextEquals("9")
            val restY = onNode(digitMatcher(place = 0, digit = '9'), useUnmergedTree = true)
                .fetchSemanticsNode().positionInRoot.y

            // 9 -> 10：十位 '1' 与个位 '0' 都从下方往上滚动，旧个位 '9' 向上滚出
            mainClock.autoAdvance = false
            number = 10
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame() // 触发重组并启动动画

            val tensSamplesUp = mutableListOf<Float>()
            val onesInSamplesUp = mutableListOf<Float>()
            val onesOutSamplesUp = mutableListOf<Float>()
            repeat(4) {
                mainClock.advanceTimeByFrame()
                tensSamplesUp += onNode(digitMatcher(place = 1, digit = '1'), useUnmergedTree = true)
                    .fetchSemanticsNode().positionInRoot.y
                onesInSamplesUp += onNode(digitMatcher(place = 0, digit = '0'), useUnmergedTree = true)
                    .fetchSemanticsNode().positionInRoot.y
                onesOutSamplesUp += onNode(digitMatcher(place = 0, digit = '9'), useUnmergedTree = true)
                    .fetchSemanticsNode().positionInRoot.y
            }

            assertTrue(
                "9->10 十位 '1' 应从下方出现并向上滚动（y 递减）：$tensSamplesUp (restY=$restY)",
                tensSamplesUp.first() > restY && tensSamplesUp.last() < tensSamplesUp.first(),
            )
            assertTrue(
                "9->10 个位 '0' 应从下方进入并向上滚动（y 递减）：$onesInSamplesUp (restY=$restY)",
                onesInSamplesUp.first() > restY && onesInSamplesUp.last() < onesInSamplesUp.first(),
            )
            assertTrue(
                "9->10 旧个位 '9' 应向上滚出（y 递减且低于 restY）：$onesOutSamplesUp (restY=$restY)",
                onesOutSamplesUp.last() < restY && onesOutSamplesUp.last() < onesOutSamplesUp.first(),
            )

            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag("rolling").assertTextEquals("10")
            assertEquals(
                restY,
                onNode(digitMatcher(place = 1, digit = '1'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y,
                1f,
            )
            assertEquals(
                restY,
                onNode(digitMatcher(place = 0, digit = '0'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y,
                1f,
            )
            onNode(digitMatcher(place = 0, digit = '9'), useUnmergedTree = true).assertDoesNotExist()

            // 10 -> 11：只有变化的个位滚动，未变化的十位 '1' 不动
            mainClock.autoAdvance = false
            number = 11
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            val unchangedTensYs = mutableListOf<Float>()
            val changedOnesInYs = mutableListOf<Float>()
            repeat(4) {
                mainClock.advanceTimeByFrame()
                unchangedTensYs += onNode(digitMatcher(place = 1, digit = '1'), useUnmergedTree = true)
                    .fetchSemanticsNode().positionInRoot.y
                changedOnesInYs += onNode(digitMatcher(place = 0, digit = '1'), useUnmergedTree = true)
                    .fetchSemanticsNode().positionInRoot.y
            }
            unchangedTensYs.forEach { y ->
                assertEquals("未变化的十位不应滚动：$unchangedTensYs", restY, y, 0.5f)
            }
            assertTrue(
                "10->11 变化的个位 '1' 应向上滚动：$changedOnesInYs",
                changedOnesInYs.first() > restY && changedOnesInYs.last() < changedOnesInYs.first(),
            )

            // 回到 10 并测试 10 -> 9：十位和个位方向向下
            mainClock.autoAdvance = true
            number = 10
            waitForIdle()

            mainClock.autoAdvance = false
            number = 9
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()

            val tensSamplesDown = mutableListOf<Float>()
            val onesInSamplesDown = mutableListOf<Float>()
            val onesOutSamplesDown = mutableListOf<Float>()
            repeat(4) {
                mainClock.advanceTimeByFrame()
                tensSamplesDown += onNode(digitMatcher(place = 1, digit = '1'), useUnmergedTree = true)
                    .fetchSemanticsNode().positionInRoot.y
                onesInSamplesDown += onNode(digitMatcher(place = 0, digit = '9'), useUnmergedTree = true)
                    .fetchSemanticsNode().positionInRoot.y
                onesOutSamplesDown += onNode(digitMatcher(place = 0, digit = '0'), useUnmergedTree = true)
                    .fetchSemanticsNode().positionInRoot.y
            }

            assertTrue(
                "10->9 十位 '1' 应向下滚出（y 递增且大于 restY）：$tensSamplesDown (restY=$restY)",
                tensSamplesDown.last() > restY && tensSamplesDown.last() > tensSamplesDown.first(),
            )
            assertTrue(
                "10->9 个位 '9' 应从上方进入并向下滚动（y 递增）：$onesInSamplesDown (restY=$restY)",
                onesInSamplesDown.first() < restY && onesInSamplesDown.last() > onesInSamplesDown.first(),
            )
            assertTrue(
                "10->9 旧个位 '0' 应向下滚出（y 递增且大于 restY）：$onesOutSamplesDown (restY=$restY)",
                onesOutSamplesDown.last() > restY && onesOutSamplesDown.last() > onesOutSamplesDown.first(),
            )

            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag("rolling").assertTextEquals("9")
            onNode(placeMatcher(place = 1), useUnmergedTree = true).assertDoesNotExist()
            assertEquals(
                restY,
                onNode(digitMatcher(place = 0, digit = '9'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y,
                1f,
            )
        }

    @Test
    fun rollingNumber_ninetyNineToHundredAddsNewDigit_andReducedMotionSnapsInOneFrame() {
        // 1) 99 -> 100：位数从 2 增至 3，百位新位出现并向上滚动到位
        withAnimatorDurationScale(1f) {
            var number by mutableIntStateOf(99)
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.padding(48.dp)) {
                        HiRollingNumber(
                            value = number,
                            modifier = Modifier.testTag("rolling"),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                }
            }
            waitForIdle()
            onNodeWithTag("rolling").assertTextEquals("99")
            onNode(placeMatcher(place = 2), useUnmergedTree = true).assertDoesNotExist()
            val restY = onNode(digitMatcher(place = 0, digit = '9'), useUnmergedTree = true)
                .fetchSemanticsNode().positionInRoot.y

            mainClock.autoAdvance = false
            number = 100
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            val hundredsFirstY = onNode(digitMatcher(place = 2, digit = '1'), useUnmergedTree = true)
                .fetchSemanticsNode().positionInRoot.y
            mainClock.advanceTimeByFrame()
            val hundredsNextY = onNode(digitMatcher(place = 2, digit = '1'), useUnmergedTree = true)
                .fetchSemanticsNode().positionInRoot.y
            assertTrue(
                "99->100 百位新位应从下方出现并向上移动：first=$hundredsFirstY next=$hundredsNextY rest=$restY",
                hundredsFirstY > restY && hundredsNextY < hundredsFirstY,
            )

            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag("rolling").assertTextEquals("100")
            assertEquals(
                restY,
                onNode(digitMatcher(place = 2, digit = '1'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y,
                1f,
            )
            assertEquals(
                restY,
                onNode(digitMatcher(place = 1, digit = '0'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y,
                1f,
            )
            assertEquals(
                restY,
                onNode(digitMatcher(place = 0, digit = '0'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y,
                1f,
            )
        }

        // 2) 减少动态效果：缩放为 0 时数字直接变，一帧到位，不残留旧数字
        withAnimatorDurationScale(0f) {
            var number by mutableIntStateOf(9)
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.padding(48.dp)) {
                        HiRollingNumber(
                            value = number,
                            modifier = Modifier.testTag("rolling"),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                }
            }
            waitForIdle()
            val restY = onNode(digitMatcher(place = 0, digit = '9'), useUnmergedTree = true)
                .fetchSemanticsNode().positionInRoot.y

            mainClock.autoAdvance = false
            number = 10
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame() // 仅推进 1 帧即到位

            onNodeWithTag("rolling").assertTextEquals("10")
            assertEquals(
                restY,
                onNode(digitMatcher(place = 1, digit = '1'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y,
                0.5f,
            )
            assertEquals(
                restY,
                onNode(digitMatcher(place = 0, digit = '0'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y,
                0.5f,
            )
            onNode(digitMatcher(place = 0, digit = '9'), useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun rollingNumber_reversalMidAnimation_isFrameContinuous() = withAnimatorDurationScale(1f) {
        var number by mutableIntStateOf(5)
        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                Box(Modifier.padding(48.dp)) {
                    HiRollingNumber(
                        value = number,
                        modifier = Modifier.testTag("rolling"),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
        }
        waitForIdle()

        // 1) 测量正常前向滚动前 4 帧的最大单帧步长作为连续性基准
        val baseline = assertFrameContinuity(
            nodeProvider = { onNode(digitMatcher(place = 0, digit = '5'), useUnmergedTree = true) },
            frameCount = 4,
            maxStepDp = 24.dp,
            minTotalTravelDp = 2.dp,
            onFrame = { frame ->
                if (frame == 0) {
                    number = 6
                }
            },
        )

        // 复位
        mainClock.autoAdvance = true
        number = 5
        waitForIdle()

        // 2) 动画到一半（第 3 帧）改回原值 5，验证全过程逐帧连续并平滑弹回原位
        val reversal = assertFrameContinuity(
            nodeProvider = { onNode(digitMatcher(place = 0, digit = '5'), useUnmergedTree = true) },
            frameCount = 24,
            maxStepDp = baseline.maxStepDp + 2.dp,
            minTotalTravelDp = 4.dp,
            onFrame = { frame ->
                if (frame == 0) {
                    number = 6
                } else if (frame == 3) {
                    number = 5
                }
            },
        )
        val startY = reversal.positionsPx.first().y
        val peakUpY = reversal.positionsPx.minOf { it.y }
        val endY = reversal.positionsPx.last().y
        assertTrue(
            "打断前应已明显向上位移：startY=$startY peakUpY=$peakUpY",
            startY - peakUpY > with(density) { 2.dp.toPx() },
        )
        assertTrue(
            "打断反向后应平滑回到原位：startY=$startY endY=$endY",
            abs(endY - startY) < with(density) { 1.dp.toPx() },
        )

        // 3) 跨位数打断（9 -> 10 到一半改回 9）：个位 '9' 的横纵位移同样逐帧连续
        mainClock.autoAdvance = true
        number = 9
        waitForIdle()

        val carryReversal = assertFrameContinuity(
            nodeProvider = { onNode(digitMatcher(place = 0, digit = '9'), useUnmergedTree = true) },
            frameCount = 24,
            maxStepDp = baseline.maxStepDp + 6.dp,
            minTotalTravelDp = 4.dp,
            onFrame = { frame ->
                if (frame == 0) {
                    number = 10
                } else if (frame == 3) {
                    number = 9
                }
            },
        )
        assertTrue(
            "9->10->9 打断反向后应平滑回到起点：start=${carryReversal.positionsPx.first()} end=${carryReversal.positionsPx.last()}",
            (carryReversal.positionsPx.last() - carryReversal.positionsPx.first()).getDistance() <
                with(density) { 1.5.dp.toPx() },
        )
    }

    @Test
    fun countBadge_zeroAbsent_zeroToThreeScalesUp_threeToZeroRemovedAfterExit() =
        withAnimatorDurationScale(1f) {
            var count by mutableIntStateOf(0)
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                        HiCountBadge(
                            count = count,
                            modifier = Modifier.testTag("badge"),
                        )
                    }
                }
            }
            waitForIdle()

            // count == 0 时不在语义树中
            onNodeWithTag("badge").assertDoesNotExist()

            // 0 -> 3：应经历从中心放大的过程
            val badgeSurfaceMatcher = SemanticsMatcher.expectValue(HiCountBadgeSurfaceKey, true)
            mainClock.autoAdvance = false
            count = 3
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()

            val enterWidths = mutableListOf<Float>()
            repeat(6) {
                mainClock.advanceTimeByFrame()
                enterWidths += onNode(badgeSurfaceMatcher).fetchSemanticsNode().boundsInRoot.width
            }
            mainClock.autoAdvance = true
            waitForIdle()
            val settledWidth = onNode(badgeSurfaceMatcher).fetchSemanticsNode().boundsInRoot.width
            assertTrue(
                "0->3 应有从中心放大的中间态：enterWidths=$enterWidths settledWidth=$settledWidth",
                enterWidths.first() < settledWidth * 0.85f && enterWidths.last() > enterWidths.first(),
            )
            onNodeWithTag("badge").assertIsDisplayed()
            onNodeWithText("3").assertIsDisplayed()

            // 3 -> 0：先缩小消失（期间不闪烁 0），动画结束后从语义树彻底移除
            mainClock.autoAdvance = false
            count = 0
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame() // 触发重组并启动退场过渡
            mainClock.advanceTimeByFrame() // 退场首帧（t=0ms）
            mainClock.advanceTimeByFrame() // 退场次帧（t=16ms）
            val shrinkingWidth = onNode(badgeSurfaceMatcher).fetchSemanticsNode().boundsInRoot.width
            assertTrue(
                "3->0 退场中途应已缩小：shrinkingWidth=$shrinkingWidth settledWidth=$settledWidth",
                shrinkingWidth < settledWidth,
            )
            onNodeWithText("0").assertDoesNotExist()

            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag("badge").assertDoesNotExist()
            onNode(badgeSurfaceMatcher).assertDoesNotExist()
        }

    @Test
    fun pressScale_pressesTo097AndReboundsTo1_listItemPressNeverScales() =
        withAnimatorDurationScale(1f) {
            val pressInteractionSource = MutableInteractionSource()
            val listInteractionSource = MutableInteractionSource()
            var scope: CoroutineScope? = null

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    scope = rememberCoroutineScope()
                    Column(Modifier.padding(24.dp)) {
                        Box(
                            Modifier
                                .size(100.dp)
                                .hiPressScale(pressInteractionSource)
                                .testTag("pressTarget"),
                        )
                        Box(
                            Modifier
                                .size(100.dp)
                                .hiListItemPress(listInteractionSource)
                                .testTag("listTarget"),
                        )
                    }
                }
            }
            waitForIdle()

            val basePressWidth = onNodeWithTag("pressTarget").fetchSemanticsNode().boundsInRoot.width
            val baseListWidth = onNodeWithTag("listTarget").fetchSemanticsNode().boundsInRoot.width
            val press = PressInteraction.Press(Offset.Zero)

            // 按下立即响应（无 200ms 延迟），最终稳定在 0.97；列表项缩放始终为 1.0
            mainClock.autoAdvance = false
            requireNotNull(scope).launch {
                pressInteractionSource.emit(press)
                listInteractionSource.emit(press)
            }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            val earlyPressScale =
                onNodeWithTag("pressTarget").fetchSemanticsNode().boundsInRoot.width / basePressWidth
            assertTrue("按下后应无延迟立即开始缩放：earlyPressScale=$earlyPressScale", earlyPressScale < 0.999f)
            val earlyListScale =
                onNodeWithTag("listTarget").fetchSemanticsNode().boundsInRoot.width / baseListWidth
            assertEquals("列表项按压过程中缩放始终应为 1.0", 1.0f, earlyListScale, 0.0001f)

            mainClock.autoAdvance = true
            waitForIdle()
            val settledPressScale =
                onNodeWithTag("pressTarget").fetchSemanticsNode().boundsInRoot.width / basePressWidth
            assertEquals("按下后应稳定在 0.97（误差 0.001）", 0.97f, settledPressScale, 0.001f)
            val settledListScale =
                onNodeWithTag("listTarget").fetchSemanticsNode().boundsInRoot.width / baseListWidth
            assertEquals("列表项按下稳定后缩放应为 1.0", 1.0f, settledListScale, 0.0001f)

            // 松开弹回 1.0；列表项依然为 1.0
            val release = PressInteraction.Release(press)
            requireNotNull(scope).launch {
                pressInteractionSource.emit(release)
                listInteractionSource.emit(release)
            }
            waitForIdle()
            val reboundedScale =
                onNodeWithTag("pressTarget").fetchSemanticsNode().boundsInRoot.width / basePressWidth
            assertEquals("松开后应弹回 1.0", 1.0f, reboundedScale, 0.001f)
            val listAfterReleaseScale =
                onNodeWithTag("listTarget").fetchSemanticsNode().boundsInRoot.width / baseListWidth
            assertEquals("列表项松开后缩放仍应为 1.0", 1.0f, listAfterReleaseScale, 0.0001f)
        }

    @Test
    fun snackbarHost_respectsBottomClearance_doesNotShiftSibling_reducedMotionSnaps_andActionPerforms() {
        // 1) 正常动效：底边距离容器底部 == bottomClearance（误差 <= 1dp），同层内容不动，点击动作回调 ActionPerformed
        withAnimatorDurationScale(1f) {
            val hostState = SnackbarHostState()
            val clearance = 72.dp
            var scope: CoroutineScope? = null
            var result: SnackbarResult? = null

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    scope = rememberCoroutineScope()
                    Box(Modifier.size(width = 411.dp, height = 600.dp).testTag("container")) {
                        Box(
                            modifier = Modifier.fillMaxSize().testTag("sibling"),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Text(text = "底层帖子内容", modifier = Modifier.padding(bottom = 16.dp))
                        }
                        HiSnackbarHost(
                            hostState = hostState,
                            bottomClearance = clearance,
                        )
                    }
                }
            }
            waitForIdle()

            val siblingBefore = onNodeWithTag("sibling").fetchSemanticsNode().boundsInRoot
            val textBefore = onNodeWithText("底层帖子内容").fetchSemanticsNode().boundsInRoot

            requireNotNull(scope).launch {
                result = hostState.showSnackbar(
                    message = "已收藏",
                    actionLabel = "撤销",
                    duration = SnackbarDuration.Indefinite,
                )
            }
            waitForIdle()

            val containerBounds = onNodeWithTag("container").fetchSemanticsNode().boundsInRoot
            val snackbarBounds = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
                .fetchSemanticsNode().boundsInRoot
            val actualClearanceDp = with(density) { (containerBounds.bottom - snackbarBounds.bottom).toDp() }
            assertEquals(
                "Snackbar 底边到容器底部的距离应等于 bottomClearance（误差 <= 1dp）",
                clearance.value,
                actualClearanceDp.value,
                1f,
            )

            assertEquals("同层容器位置与尺寸不应被推挤", siblingBefore, onNodeWithTag("sibling").fetchSemanticsNode().boundsInRoot)
            assertEquals("同层内容文本位置不应被推挤", textBefore, onNodeWithText("底层帖子内容").fetchSemanticsNode().boundsInRoot)

            onNodeWithText("撤销").performClick()
            waitForIdle()
            assertEquals(SnackbarResult.ActionPerformed, result)
            onNodeWithText("已收藏").assertDoesNotExist()
        }

        // 2) 减少动态效果：缩放 0f 下，首动画帧即到最终 bottomClearance 位置，无滞后位移
        withAnimatorDurationScale(0f) {
            val hostState = SnackbarHostState()
            val clearance = 80.dp
            var scope: CoroutineScope? = null

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    scope = rememberCoroutineScope()
                    Box(Modifier.size(width = 411.dp, height = 600.dp).testTag("container")) {
                        HiSnackbarHost(
                            hostState = hostState,
                            bottomClearance = clearance,
                        )
                    }
                }
            }
            waitForIdle()

            mainClock.autoAdvance = false
            requireNotNull(scope).launch {
                hostState.showSnackbar(
                    message = "已删除",
                    actionLabel = "撤销",
                    duration = SnackbarDuration.Indefinite,
                )
            }
            mainClock.advanceTimeByFrame() // 触发重组并启动动画
            mainClock.advanceTimeByFrame() // 动画首帧（scale=0 立即到位）
            mainClock.advanceTimeByFrame() // 提交最终布局

            val containerBounds = onNodeWithTag("container").fetchSemanticsNode().boundsInRoot
            val snackbarBounds = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
                .fetchSemanticsNode().boundsInRoot
            val actualClearanceDp = with(density) { (containerBounds.bottom - snackbarBounds.bottom).toDp() }
            assertEquals(
                "减少动态效果下 Snackbar 应一帧到位至 bottomClearance",
                clearance.value,
                actualClearanceDp.value,
                1f,
            )
        }

        // 3) 时长自动消失：SnackbarDuration.Short 到期后自动关闭并返回 Dismissed
        withAnimatorDurationScale(1f) {
            val hostState = SnackbarHostState()
            var scope: CoroutineScope? = null
            var result: SnackbarResult? = null

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    scope = rememberCoroutineScope()
                    Box(Modifier.size(width = 411.dp, height = 600.dp)) {
                        HiSnackbarHost(hostState = hostState, bottomClearance = 72.dp)
                    }
                }
            }
            waitForIdle()

            mainClock.autoAdvance = false
            requireNotNull(scope).launch {
                result = hostState.showSnackbar(
                    message = "链接已复制",
                    duration = SnackbarDuration.Short,
                )
            }
            mainClock.advanceTimeBy(500L)
            onNodeWithText("链接已复制").assertIsDisplayed()

            mainClock.advanceTimeBy(4500L)
            mainClock.autoAdvance = true
            waitForIdle()
            assertEquals(SnackbarResult.Dismissed, result)
            onNodeWithText("链接已复制").assertDoesNotExist()
        }
    }

    @Test
    fun rollingNumber_doesNotRecomposeOnUnrelatedParentStateChange() = withAnimatorDurationScale(1f) {
        val parentCounter = RecompositionCounter("Parent")
        val rollingCounter = RecompositionCounter("HiRollingNumber")
        var unrelatedTicks by mutableIntStateOf(0)
        var number by mutableIntStateOf(42)

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                CompositionLocalProvider(
                    LocalHiRollingNumberRecompositionHook provides { rollingCounter.record() },
                ) {
                    val numberStyle = MaterialTheme.typography.titleMedium
                    parentCounter.Track()
                    Column {
                        Text(text = "ticks=$unrelatedTicks")
                        HiRollingNumber(
                            value = number,
                            style = numberStyle,
                        )
                    }
                }
            }
        }
        waitForIdle()
        parentCounter.assertEquals(1)
        rollingCounter.assertEquals(1)
        rollingCounter.assertRecompositionsAtMost(0)

        repeat(5) {
            unrelatedTicks++
            waitForIdle()
        }

        parentCounter.assertEquals(6)
        assertEquals(5, parentCounter.recompositionCount)
        rollingCounter.assertEquals(1)
        rollingCounter.assertRecompositionsAtMost(0)

        // 对照：当 value 真实变化时，计数器应递增
        number = 43
        waitForIdle()
        rollingCounter.assertEquals(2)
    }

    @Test
    fun iconSwitch_strokeCheck_haptics_andVerticalSlideTransitions() = withAnimatorDurationScale(1f) {
        val recordedHaptics = mutableListOf<HapticFeedbackType>()
        val fakeHapticFeedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                recordedHaptics += hapticFeedbackType
            }
        }
        var favorited by mutableStateOf(false)
        var checkVisible by mutableStateOf(false)
        var haptics: HiHaptics? = null

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                CompositionLocalProvider(LocalHapticFeedback provides fakeHapticFeedback) {
                    haptics = rememberHiHaptics()
                    Column {
                        HiIconSwitch(
                            target = if (favorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (favorited) "已收藏" else "未收藏",
                            modifier = Modifier.testTag("iconSwitch"),
                        )
                        HiStrokeCheck(
                            visible = checkVisible,
                            modifier = Modifier.testTag("strokeCheck"),
                            contentDescription = "完成",
                        )
                    }
                }
            }
        }
        waitForIdle()

        // 1) 图标切换
        onNodeWithContentDescription("未收藏").assertIsDisplayed()
        favorited = true
        waitForIdle()
        onNodeWithContentDescription("已收藏").assertIsDisplayed()

        // 2) 描线对勾状态切换、非零尺寸绘制与进度收敛
        checkVisible = true
        waitForIdle()
        onNodeWithTag("strokeCheck").assertIsDisplayed()
        val strokeNode = onNodeWithTag("strokeCheck").fetchSemanticsNode()
        assertTrue("描线对勾画布尺寸应大于 0：${strokeNode.size}", strokeNode.size.width > 0 && strokeNode.size.height > 0)
        val progressFinal = strokeNode.config[HiStrokeCheckProgressKey]
        assertEquals(1f, progressFinal, 0.01f)

        checkVisible = false
        waitForIdle()
        val progressHidden = onNodeWithTag("strokeCheck")
            .fetchSemanticsNode().config[HiStrokeCheckProgressKey]
        assertEquals(0f, progressHidden, 0.01f)

        // 3) 触感确认回调 HapticFeedbackType.Confirm
        requireNotNull(haptics).confirm()
        assertEquals(listOf(HapticFeedbackType.Confirm), recordedHaptics)

        // 4) HiMotionTransitions 追加的纵向滑动函数可用
        assertNotNull(hiSlideInVertically())
        assertNotNull(hiSlideOutVertically())
    }
}
