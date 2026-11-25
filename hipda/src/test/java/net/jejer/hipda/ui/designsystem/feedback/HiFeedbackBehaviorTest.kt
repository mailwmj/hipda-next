package net.jejer.hipda.ui.designsystem.feedback

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextStyle
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class HiFeedbackBehaviorTest {

    private fun leafDigit(digit: Char, rootTag: String = "rolling"): SemanticsMatcher =
        hasText(digit.toString()) and hasTestTag(rootTag).not()

    private fun ComposeUiTest.digitNodesSortedLeftToRight(rootTag: String = "rolling"): List<SemanticsNode> =
        onAllNodes(
            SemanticsMatcher("digit leaf") { node ->
                node.config.contains(SemanticsProperties.Text) &&
                    node.config.getOrElse(SemanticsProperties.TestTag) { "" } != rootTag
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().sortedBy { it.positionInRoot.x }

    private fun ImageBitmap.centerPixel(): Color {
        val map = toPixelMap()
        return map[map.width / 2, map.height / 2]
    }

    private fun ImageBitmap.countDarkPixels(threshold: Float = 0.5f): Int {
        val map = toPixelMap()
        var count = 0
        for (y in 0 until map.height) {
            for (x in 0 until map.width) {
                if (map[x, y].luminance() < threshold) count++
            }
        }
        return count
    }

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
            val restY = onNode(leafDigit('9'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

            // 9 -> 10：十位 '1' 与个位 '0' 都从下方往上滚动，旧个位 '9' 向上滚出
            mainClock.autoAdvance = false
            number = 10
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()

            val tensSamplesUp = mutableListOf<Float>()
            val onesInSamplesUp = mutableListOf<Float>()
            val onesOutSamplesUp = mutableListOf<Float>()
            repeat(4) {
                mainClock.advanceTimeByFrame()
                tensSamplesUp += onNode(leafDigit('1'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
                onesInSamplesUp += onNode(leafDigit('0'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
                onesOutSamplesUp += onNode(leafDigit('9'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
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
            val settled10 = digitNodesSortedLeftToRight()
            assertEquals(2, settled10.size)
            assertEquals(restY, settled10[0].positionInRoot.y, 1f)
            assertEquals(restY, settled10[1].positionInRoot.y, 1f)
            onNode(leafDigit('9'), useUnmergedTree = true).assertDoesNotExist()

            // 10 -> 11：只有变化的个位滚动，未变化的十位 '1'（左侧）不动
            mainClock.autoAdvance = false
            number = 11
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            val unchangedTensYs = mutableListOf<Float>()
            val changedOnesInYs = mutableListOf<Float>()
            repeat(4) {
                mainClock.advanceTimeByFrame()
                val onesNodes = onAllNodes(leafDigit('1'), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .sortedBy { it.positionInRoot.x }
                assertEquals("10->11 应同时存在十位 '1' 与个位新 '1'", 2, onesNodes.size)
                unchangedTensYs += onesNodes.first().positionInRoot.y
                changedOnesInYs += onesNodes.last().positionInRoot.y
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
                tensSamplesDown += onNode(leafDigit('1'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
                onesInSamplesDown += onNode(leafDigit('9'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
                onesOutSamplesDown += onNode(leafDigit('0'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
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
            val settled9 = digitNodesSortedLeftToRight()
            assertEquals(1, settled9.size)
            assertEquals(restY, settled9[0].positionInRoot.y, 1f)
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
            assertEquals(2, digitNodesSortedLeftToRight().size)
            val restY = digitNodesSortedLeftToRight().first().positionInRoot.y

            mainClock.autoAdvance = false
            number = 100
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            val hundredsFirstY = onNode(leafDigit('1'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
            mainClock.advanceTimeByFrame()
            val hundredsNextY = onNode(leafDigit('1'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
            assertTrue(
                "99->100 百位新位应从下方出现并向上移动：first=$hundredsFirstY next=$hundredsNextY rest=$restY",
                hundredsFirstY > restY && hundredsNextY < hundredsFirstY,
            )

            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag("rolling").assertTextEquals("100")
            val settled100 = digitNodesSortedLeftToRight()
            assertEquals(3, settled100.size)
            settled100.forEach { node ->
                assertEquals(restY, node.positionInRoot.y, 1f)
            }
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
            val restY = onNode(leafDigit('9'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

            mainClock.autoAdvance = false
            number = 10
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame() // 仅推进 1 帧即到位

            onNodeWithTag("rolling").assertTextEquals("10")
            val settled = digitNodesSortedLeftToRight()
            assertEquals(2, settled.size)
            assertEquals(restY, settled[0].positionInRoot.y, 0.5f)
            assertEquals(restY, settled[1].positionInRoot.y, 0.5f)
            onNode(leafDigit('9'), useUnmergedTree = true).assertDoesNotExist()
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
            nodeProvider = { onNode(leafDigit('5'), useUnmergedTree = true) },
            frameCount = 4,
            maxStepDp = 24.dp,
            minTotalTravelDp = 2.dp,
            onFrame = { frame ->
                if (frame == 0) number = 6
            },
        )

        mainClock.autoAdvance = true
        number = 5
        waitForIdle()

        // 2) 动画到一半（第 3 帧）改回原值 5，验证全过程逐帧连续并平滑弹回原位
        val reversal = assertFrameContinuity(
            nodeProvider = { onNode(leafDigit('5'), useUnmergedTree = true) },
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
            nodeProvider = { onNode(leafDigit('9'), useUnmergedTree = true) },
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
    fun rollingNumber_fiveToSixToFourInterruption_doesNotOverwriteExitingDigit() =
        withAnimatorDurationScale(1f) {
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
            val restY = onNode(leafDigit('5'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

            // 5 -> 6 滚动到一半
            mainClock.autoAdvance = false
            number = 6
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            val midY5 = onNode(leafDigit('5'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
            val midY6 = onNode(leafDigit('6'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
            assertTrue("5 应已向上离开 restY：midY5=$midY5 restY=$restY", midY5 < restY - 1f)
            assertTrue("6 应处于 restY 下方半程：midY6=$midY6 restY=$restY", midY6 > restY + 1f)

            // 半程改为异值 4（减少）：新数字 4 应从顶部边缘进入而非覆盖 5 的半程位置，旧目标 6 反向向下滑出
            number = 4
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()

            val enterY4 = onNode(leafDigit('4'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
            assertTrue(
                "新数字 4 应从顶部边缘进入（更上方），不能原地覆盖 5 的半程位置：enterY4=$enterY4 midY5=$midY5",
                enterY4 < midY5 - 1f,
            )

            // 验证新数字 4 逐帧连续滑入至 restY，且旧目标 6 向下滑出（不向上跳回）
            val y6AfterInterrupt = onNode(leafDigit('6'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
            val enterContinuity = assertFrameContinuity(
                nodeProvider = { onNode(leafDigit('4'), useUnmergedTree = true) },
                frameCount = 18,
                maxStepDp = 24.dp,
                minTotalTravelDp = 4.dp,
            )
            assertTrue("打断后 6 应保持在 restY 下方并向下退出：y6=$y6AfterInterrupt restY=$restY", y6AfterInterrupt > restY)
            assertEquals(restY, enterContinuity.positionsPx.last().y, 1.5f)

            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag("rolling").assertTextEquals("4")
            assertEquals(restY, onNode(leafDigit('4'), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y, 1f)
            onNode(leafDigit('5'), useUnmergedTree = true).assertDoesNotExist()
            onNode(leafDigit('6'), useUnmergedTree = true).assertDoesNotExist()
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
            onNodeWithTag("badge").assertDoesNotExist()

            // 0 -> 3：内部数字节点位于 scaleIn 图层内，可观测从中心放大的中间宽度
            mainClock.autoAdvance = false
            count = 3
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()

            val enterWidths = mutableListOf<Float>()
            repeat(6) {
                mainClock.advanceTimeByFrame()
                enterWidths += onNodeWithText("3").fetchSemanticsNode().boundsInRoot.width
            }
            mainClock.autoAdvance = true
            waitForIdle()
            val settledWidth = onNodeWithText("3").fetchSemanticsNode().boundsInRoot.width
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
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            val shrinkingWidth = onNodeWithText("3").fetchSemanticsNode().boundsInRoot.width
            assertTrue(
                "3->0 退场中途应已缩小：shrinkingWidth=$shrinkingWidth settledWidth=$settledWidth",
                shrinkingWidth < settledWidth,
            )
            onNodeWithText("0").assertDoesNotExist()

            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag("badge").assertDoesNotExist()
            onNodeWithText("3").assertDoesNotExist()
        }

    @Test
    fun pressScale_pressesTo097AndReboundsTo1_listItemPressNeverScalesAndDrawsStateLayer() =
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
                                .background(Color.White)
                                .hiListItemPress(listInteractionSource, color = Color.Black)
                                .testTag("listTarget"),
                        )
                        Box(
                            Modifier
                                .size(100.dp)
                                .background(Color.White)
                                .clickable(
                                    interactionSource = null,
                                    indication = hiListItemPressIndication(color = Color.Black),
                                    onClick = {},
                                )
                                .testTag("clickableListTarget"),
                        )
                    }
                }
            }
            waitForIdle()

            val basePressWidth = onNodeWithTag("pressTarget").fetchSemanticsNode().boundsInRoot.width
            val baseListWidth = onNodeWithTag("listTarget").fetchSemanticsNode().boundsInRoot.width
            val baseClickableWidth = onNodeWithTag("clickableListTarget").fetchSemanticsNode().boundsInRoot.width
            val restingListPixel = onNodeWithTag("listTarget").captureToImage().centerPixel()
            assertTrue("未按下时列表项应为纯白背景：$restingListPixel", restingListPixel.red > 0.99f)

            val press = PressInteraction.Press(Offset.Zero)
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

            // 列表项按压状态层像素断言：10% 黑色叠加在白色上后约 0.90 亮度
            val pressedListPixel = onNodeWithTag("listTarget").captureToImage().centerPixel()
            assertTrue(
                "按下后列表项应绘制状态层使像素变暗：$pressedListPixel",
                pressedListPixel.red in 0.80f..0.95f,
            )

            // 松开弹回 1.0，状态层退回透明（纯白）
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
            val releasedListPixel = onNodeWithTag("listTarget").captureToImage().centerPixel()
            assertTrue("松开后状态层应消失恢复纯白：$releasedListPixel", releasedListPixel.red > 0.99f)

            // 验证 clickable(interactionSource = null, indication = hiListItemPressIndication()) 免手建 InteractionSource
            onNodeWithTag("clickableListTarget").performTouchInput { down(center) }
            waitForIdle()
            assertEquals(
                1.0f,
                onNodeWithTag("clickableListTarget").fetchSemanticsNode().boundsInRoot.width / baseClickableWidth,
                0.0001f,
            )
            val clickablePressedPixel = onNodeWithTag("clickableListTarget").captureToImage().centerPixel()
            assertTrue(
                "IndicationNodeFactory 按下后应绘制状态层：$clickablePressedPixel",
                clickablePressedPixel.red in 0.80f..0.95f,
            )
            onNodeWithTag("clickableListTarget").performTouchInput { up() }
            waitForIdle()
            assertTrue(onNodeWithTag("clickableListTarget").captureToImage().centerPixel().red > 0.99f)
        }

    @Test
    fun snackbarHost_respectsBottomClearance_risesFromBelow_capsMaxWidth_a11yTimeout_andActionPerforms() {
        // 1) 正常动效：逐帧从下方升起，底边距离容器底部 == bottomClearance，同层内容不动，动作回调 ActionPerformed
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
            val liveRegionMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

            // 采样入场逐帧位移：确认从下方升起（bottom 递减）
            mainClock.autoAdvance = false
            requireNotNull(scope).launch {
                result = hostState.showSnackbar(
                    message = "已收藏",
                    actionLabel = "撤销",
                    duration = SnackbarDuration.Indefinite,
                )
            }
            mainClock.advanceTimeByFrame()
            val enterBottoms = mutableListOf<Float>()
            repeat(5) {
                mainClock.advanceTimeByFrame()
                enterBottoms += onNode(liveRegionMatcher).fetchSemanticsNode().boundsInRoot.bottom
            }

            mainClock.autoAdvance = true
            waitForIdle()

            val containerBounds = onNodeWithTag("container").fetchSemanticsNode().boundsInRoot
            val snackbarBounds = onNode(liveRegionMatcher).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Snackbar 应从下方升起（y 递减至落位）：enterBottoms=$enterBottoms settled=${snackbarBounds.bottom}",
                enterBottoms.first() > snackbarBounds.bottom + 2f && enterBottoms.last() < enterBottoms.first(),
            )
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

        // 2) 减少动态效果：缩放 0f 下，首动画帧即到最终 bottomClearance 位置
        withAnimatorDurationScale(0f) {
            val hostState = SnackbarHostState()
            val clearance = 80.dp
            var scope: CoroutineScope? = null

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    scope = rememberCoroutineScope()
                    Box(Modifier.size(width = 411.dp, height = 600.dp).testTag("container")) {
                        HiSnackbarHost(hostState = hostState, bottomClearance = clearance)
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
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()

            val containerBounds = onNodeWithTag("container").fetchSemanticsNode().boundsInRoot
            val snackbarBounds = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
                .fetchSemanticsNode().boundsInRoot
            val actualClearanceDp = with(density) { (containerBounds.bottom - snackbarBounds.bottom).toDp() }
            assertEquals(clearance.value, actualClearanceDp.value, 1f)
        }

        // 3) 大屏 600dp 最大宽度约束 + 无障碍超时参数 containsIcons == withDismissAction + 自动关闭
        withAnimatorDurationScale(1f) {
            val hostState = SnackbarHostState()
            var scope: CoroutineScope? = null
            var result: SnackbarResult? = null
            val recordedContainsIcons = mutableListOf<Boolean>()
            val fakeAccessibilityManager = object : AccessibilityManager {
                override fun calculateRecommendedTimeoutMillis(
                    originalTimeoutMillis: Long,
                    containsIcons: Boolean,
                    containsText: Boolean,
                    containsControls: Boolean,
                ): Long {
                    recordedContainsIcons += containsIcons
                    return originalTimeoutMillis
                }
            }

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    CompositionLocalProvider(LocalAccessibilityManager provides fakeAccessibilityManager) {
                        scope = rememberCoroutineScope()
                        Box(Modifier.size(width = 900.dp, height = 600.dp)) {
                            HiSnackbarHost(hostState = hostState, bottomClearance = 72.dp)
                        }
                    }
                }
            }
            waitForIdle()

            mainClock.autoAdvance = false
            requireNotNull(scope).launch {
                result = hostState.showSnackbar(
                    message = "链接已复制",
                    withDismissAction = false,
                    duration = SnackbarDuration.Short,
                )
            }
            mainClock.advanceTimeBy(500L)
            val wideSnackbarWidthDp = with(density) {
                onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
                    .fetchSemanticsNode().boundsInRoot.width.toDp()
            }
            assertTrue("平板宽屏下 Snackbar 宽度应限制在 600dp 内：$wideSnackbarWidthDp", wideSnackbarWidthDp <= 600.dp)
            assertEquals("withDismissAction=false 时 containsIcons 应为 false", listOf(false), recordedContainsIcons)

            mainClock.advanceTimeBy(4500L)
            mainClock.autoAdvance = true
            waitForIdle()
            assertEquals(SnackbarResult.Dismissed, result)
            onNodeWithText("链接已复制").assertDoesNotExist()

            // 带关闭图标时 containsIcons 应为 true
            requireNotNull(scope).launch {
                hostState.showSnackbar(
                    message = "可关闭提示",
                    withDismissAction = true,
                    duration = SnackbarDuration.Indefinite,
                )
            }
            waitForIdle()
            assertEquals(listOf(false, true), recordedContainsIcons)
            assertFalse(recordedContainsIcons.first())
            assertTrue(recordedContainsIcons.last())
        }
    }

    @Test
    fun rollingNumber_doesNotRecomposeOnUnrelatedParentStateChange() = withAnimatorDurationScale(1f) {
        val parentCounter = RecompositionCounter("Parent")
        val rollingHostCounter = RecompositionCounter("HiRollingNumberHost")
        var unrelatedTicks by mutableIntStateOf(0)
        var number by mutableIntStateOf(42)

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val numberStyle = MaterialTheme.typography.titleMedium
                parentCounter.Track()
                Column {
                    Text(text = "ticks=$unrelatedTicks")
                    TrackedRollingNumberHost(
                        value = number,
                        style = numberStyle,
                        counter = rollingHostCounter,
                    )
                }
            }
        }
        waitForIdle()
        parentCounter.assertEquals(1)
        rollingHostCounter.assertEquals(1)
        rollingHostCounter.assertRecompositionsAtMost(0)

        repeat(5) {
            unrelatedTicks++
            waitForIdle()
        }

        parentCounter.assertEquals(6)
        assertEquals(5, parentCounter.recompositionCount)
        rollingHostCounter.assertEquals(1)
        rollingHostCounter.assertRecompositionsAtMost(0)

        // 对照：value 真实变化时应正常触发一次重组
        number = 43
        waitForIdle()
        rollingHostCounter.assertEquals(2)
    }

    @Test
    fun iconSwitch_concurrentDualScale_strokeCheck_midStrokePixels_haptics_andVerticalSlideTransitions() =
        withAnimatorDurationScale(1f) {
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
                        Column(Modifier.padding(16.dp)) {
                            HiIconSwitch(
                                target = if (favorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (favorited) "已收藏" else "未收藏",
                                modifier = Modifier.testTag("iconSwitch"),
                            )
                            HiStrokeCheck(
                                visible = checkVisible,
                                color = Color.Black,
                                strokeWidth = 4.dp,
                                size = 48.dp,
                                modifier = Modifier
                                    .background(Color.White)
                                    .testTag("strokeCheck"),
                                contentDescription = "完成",
                            )
                        }
                    }
                }
            }
            waitForIdle()

            // 1) 图标切换：切换中途同时存在新旧两个图标，一个缩小、一个放大
            val settledIconWidth = onNodeWithTag("iconSwitch")
                .fetchSemanticsNode().children.single().boundsInRoot.width
            mainClock.autoAdvance = false
            favorited = true
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            val frame2Widths = onNodeWithTag("iconSwitch")
                .fetchSemanticsNode().children.map { it.boundsInRoot.width }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            val frame4Widths = onNodeWithTag("iconSwitch")
                .fetchSemanticsNode().children.map { it.boundsInRoot.width }
            assertEquals("切换中途应同时存在新旧两个图标节点", 2, frame2Widths.size)
            assertEquals("切换中途第 4 帧仍应同时存在新旧两个图标节点", 2, frame4Widths.size)
            assertTrue(
                "旧图标应缩小、新图标应放大并并发进行：frame2=$frame2Widths frame4=$frame4Widths full=$settledIconWidth",
                frame4Widths[0] < frame2Widths[0] && frame4Widths[1] > frame2Widths[1],
            )
            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithContentDescription("已收藏").assertIsDisplayed()
            assertEquals(1, onNodeWithTag("iconSwitch").fetchSemanticsNode().children.size)

            // 2) 描线对勾：隐藏时 0 墨迹，半程有部分描线像素，终态像素完整且显著多于半程，退场回 0
            val hiddenPixels = onNodeWithTag("strokeCheck").captureToImage().countDarkPixels()
            assertEquals("隐藏时描线对勾不应绘制像素", 0, hiddenPixels)

            mainClock.autoAdvance = false
            checkVisible = true
            Snapshot.sendApplyNotifications()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame() // t=16ms 半程描线
            val midPixels = onNodeWithTag("strokeCheck").captureToImage().countDarkPixels()

            mainClock.autoAdvance = true
            waitForIdle()
            val fullPixels = onNodeWithTag("strokeCheck").captureToImage().countDarkPixels()
            assertTrue(
                "描线对勾半程像素应介于 0 与终态之间：hidden=$hiddenPixels mid=$midPixels full=$fullPixels",
                midPixels in 1 until fullPixels,
            )

            checkVisible = false
            waitForIdle()
            assertEquals("退场完成后描线像素应回 0", 0, onNodeWithTag("strokeCheck").captureToImage().countDarkPixels())

            // 3) 触感确认回调 HapticFeedbackType.Confirm
            requireNotNull(haptics).confirm()
            assertEquals(listOf(HapticFeedbackType.Confirm), recordedHaptics)

            // 4) HiMotionTransitions 追加的纵向滑动函数可用
            assertNotNull(hiSlideInVertically())
            assertNotNull(hiSlideOutVertically())
        }
}

@Composable
private fun TrackedRollingNumberHost(
    value: Int,
    style: TextStyle,
    counter: RecompositionCounter,
) {
    counter.Track()
    HiRollingNumber(value = value, style = style)
}
