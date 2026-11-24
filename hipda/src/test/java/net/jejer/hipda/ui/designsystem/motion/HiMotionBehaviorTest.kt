package net.jejer.hipda.ui.designsystem.motion

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import net.jejer.hipda.testing.RecompositionCounter
import net.jejer.hipda.testing.assertFrameContinuity
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HiMotionBehaviorTest {

    @Test
    fun hiTheme_readsAndObservesSystemAnimatorDurationScale() = withAnimatorDurationScale(0.5f) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        var observedScale = -1f
        var observedReduced = false

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                observedScale = HiTheme.motionDurationScale.scaleFactor
                observedReduced = HiTheme.isReducedMotion
            }
        }
        waitForIdle()
        assertEquals(0.5f, observedScale, 0.001f)
        assertFalse(observedReduced)

        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        context.contentResolver.notifyChange(uri, null)
        waitForIdle()
        assertEquals(0f, observedScale, 0.001f)
        assertTrue(observedReduced)
    }

    @Test
    fun reducedMotion_transitionsAndSizeAnimationsCompleteInOneFrame() {
        // 对照组：正常缩放 1f 时，1 帧（16ms）后尚未到达 200dp 终点
        withAnimatorDurationScale(1f) {
            var expanded by mutableStateOf(false)
            var currentOffsetDp = 0.dp
            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val x by animateDpAsState(
                        targetValue = if (expanded) 200.dp else 0.dp,
                        animationSpec = HiMotion.spatialDefault(),
                    )
                    currentOffsetDp = x
                    Box(Modifier.offset(x = x).size(24.dp).testTag("moving"))
                }
            }
            mainClock.autoAdvance = false
            expanded = true
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertTrue("正常缩放 1 帧后应处于过渡中途，实际 $currentOffsetDp", currentOffsetDp < 190.dp)
        }

        // 减少动态效果：缩放 0f 时，AnimatedVisibility、animateDpAsState、hiAnimateContentSize 一帧到位
        withAnimatorDurationScale(0f) {
            var active by mutableStateOf(false)
            var currentOffsetDp = 0.dp

            setContent {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val x by animateDpAsState(
                        targetValue = if (active) 200.dp else 0.dp,
                        animationSpec = HiMotion.spatialDefault(),
                    )
                    currentOffsetDp = x
                    Column {
                        Box(Modifier.offset(x = x).size(24.dp).testTag("moving"))
                        Box(
                            Modifier
                                .hiAnimateContentSize()
                                .testTag("resizable")
                                .size(width = 100.dp, height = if (active) 120.dp else 40.dp),
                        )
                        AnimatedVisibility(
                            visible = active,
                            enter = hiFadeIn() + hiSlideInHorizontally(),
                            exit = hiFadeOut() + hiSlideOutHorizontally(),
                        ) {
                            Box(Modifier.size(48.dp).testTag("panel"))
                        }
                    }
                }
            }

            mainClock.autoAdvance = false
            active = true
            mainClock.advanceTimeByFrame()
            waitForIdle()

            assertEquals(200f, currentOffsetDp.value, 0.01f)
            val expectedHeightPx = with(density) { 120.dp.toPx() }
            val actualHeightPx = onNodeWithTag("resizable").fetchSemanticsNode().size.height.toFloat()
            assertEquals(expectedHeightPx, actualHeightPx, 1f)
            onNodeWithTag("panel").assertIsDisplayed()

            // 退场同样 1 帧完成，不残留中间节点
            active = false
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertEquals(0f, currentOffsetDp.value, 0.01f)
            onNodeWithTag("panel").assertDoesNotExist()
        }
    }

    @Test
    fun reducedMotion_doesNotBypassLoadingShowDelayOrHighlightHold() = withAnimatorDurationScale(0f) {
        var loadingShown by mutableStateOf(false)
        var highlightDismissed by mutableStateOf(false)

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                LaunchedEffect(Unit) {
                    delay(HiMotion.loadingShowDelay)
                    loadingShown = true
                }
                LaunchedEffect(Unit) {
                    delay(HiMotion.highlightHold)
                    highlightDismissed = true
                }
            }
        }

        mainClock.autoAdvance = false
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertFalse("loadingShowDelay 不应因动画缩放为 0 而跳过", loadingShown)
        assertFalse("highlightHold 不应因动画缩放为 0 而跳过", highlightDismissed)

        mainClock.advanceTimeBy(120L)
        waitForIdle()
        assertFalse("150ms 前不应提前显示被动加载", loadingShown)

        mainClock.advanceTimeBy(40L)
        waitForIdle()
        assertTrue("超过 150ms 后应显示被动加载", loadingShown)
        assertFalse("1000ms 前高亮仍应保持", highlightDismissed)

        mainClock.advanceTimeBy(900L)
        waitForIdle()
        assertTrue("超过 1000ms 后高亮应结束", highlightDismissed)
    }

    @Test
    fun assertFrameContinuity_tokenDrivenReversalIsContinuous() = withAnimatorDurationScale(1f) {
        var forward by mutableStateOf(false)

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val offsetDp by animateDpAsState(
                    targetValue = if (forward) 200.dp else 0.dp,
                    animationSpec = HiMotion.spatialDefault(),
                )
                Box(
                    Modifier
                        .offset { IntOffset(offsetDp.roundToPx(), 0) }
                        .size(32.dp)
                        .testTag("slider"),
                )
            }
        }

        // 先测量正常单程动画的最大单帧位移
        forward = true
        val normalResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag("slider") },
            frameCount = 24,
            maxStepDp = 60.dp,
        )
        assertTrue("正常动画应产生有效位移", normalResult.maxStepDp > 1.dp)

        // 复位并测试中途反向：第 4 帧反向后，相邻两帧位移不超过正常单帧最大位移
        mainClock.autoAdvance = true
        forward = false
        waitForIdle()

        forward = true
        val reversalResult = assertFrameContinuity(
            nodeProvider = { onNodeWithTag("slider") },
            frameCount = 30,
            maxStepDp = normalResult.maxStepDp + 0.5.dp,
            onFrame = { frame ->
                if (frame == 4) {
                    forward = false
                }
            },
        )
        val peakX = reversalResult.positionsPx.maxOf { it.x }
        val finalX = reversalResult.positionsPx.last().x
        assertTrue("反向前应已明显向右移动，峰值 $peakX px", peakX > with(density) { 20.dp.toPx() })
        assertTrue("反向后应向起点回落，末帧 $finalX px 应小于峰值 $peakX px", finalX < peakX)
    }

    @Test
    fun recompositionCounter_unrelatedStateChangeDoesNotRecomposeChild() = withAnimatorDurationScale(1f) {
        val parentCounter = RecompositionCounter("Parent")
        val childCounter = RecompositionCounter("StableChild")
        var unrelatedTicks by mutableIntStateOf(0)

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                parentCounter.Track()
                Column {
                    Text(text = "ticks=$unrelatedTicks")
                    StableChildRow(title = "固定标题", counter = childCounter)
                }
            }
        }
        waitForIdle()
        parentCounter.assertEquals(1)
        childCounter.assertEquals(1)

        repeat(5) {
            unrelatedTicks++
            waitForIdle()
        }

        parentCounter.assertEquals(6)
        childCounter.assertAtMost(1)
    }

    @Test
    fun hiTheme_fixedSeedDoesNotReadLocalConfiguration() = withAnimatorDurationScale(1f) {
        val baseConfig = Configuration(ApplicationProvider.getApplicationContext<Context>().resources.configuration)
        var currentConfig by mutableStateOf(Configuration(baseConfig).apply { screenWidthDp = 411 })
        val themeBodyCounter = RecompositionCounter("HiThemeFixedSeedBody")
        var firstScheme: ColorScheme? = null
        var latestScheme: ColorScheme? = null

        setContent {
            CompositionLocalProvider(LocalConfiguration provides currentConfig) {
                HiTheme(
                    themeMode = HiThemeMode.LIGHT,
                    pureBlack = false,
                    seed = HiColorPresets.Brand.toSeed(),
                ) {
                    themeBodyCounter.Track()
                    val scheme = MaterialTheme.colorScheme
                    if (firstScheme == null) firstScheme = scheme
                    latestScheme = scheme
                    Box(Modifier.height(10.dp))
                }
            }
        }
        waitForIdle()
        themeBodyCounter.assertEquals(1)

        currentConfig = Configuration(baseConfig).apply { screenWidthDp = 900 }
        waitForIdle()
        currentConfig = Configuration(baseConfig).apply { screenWidthDp = 1280 }
        waitForIdle()

        // 固定种子分支不读 LocalConfiguration，因此配置变化不会触发 HiTheme 重新组合或重算配色
        themeBodyCounter.assertAtMost(1)
        assertSame("固定种子下配色对象应保持同一实例", firstScheme, latestScheme)
    }
}

@Composable
private fun StableChildRow(
    title: String,
    counter: RecompositionCounter,
) {
    counter.Track()
    Text(text = title)
}
