package net.jejer.hipda.ui.designsystem.state

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
class LoadingVisibilityControllerTest {

    @Test
    fun passiveLoad_finishesIn100ms_neverShows() = runTest {
        val controller = LoadingVisibilityController(backgroundScope)
        val emissions = mutableListOf<Boolean>()
        val collectJob = backgroundScope.launch {
            controller.visible.collect { emissions += it }
        }
        runCurrent()

        controller.update(isLoading = true, passive = true)
        advanceTimeBy(100L)
        runCurrent()
        assertFalse("100ms 时不应显示被动加载指示", controller.visible.value)

        controller.update(isLoading = false, passive = true)
        advanceTimeBy(500L)
        runCurrent()
        assertFalse("100ms 内结束后始终不应显示", controller.visible.value)
        assertTrue("整个周期内不得发出 true：$emissions", emissions.none { it })

        collectJob.cancel()
    }

    @Test
    fun passiveLoad_finishesAt200ms_showsAt150msAndHidesAt400ms() = runTest {
        val controller = LoadingVisibilityController(backgroundScope)

        controller.update(isLoading = true, passive = true)

        advanceTimeBy(149L)
        runCurrent()
        assertFalse("149ms 时不应提前显示", controller.visible.value)

        advanceTimeBy(1L) // t = 150ms
        runCurrent()
        assertTrue("150ms 时应显示被动加载指示", controller.visible.value)

        advanceTimeBy(50L) // t = 200ms
        runCurrent()
        controller.update(isLoading = false, passive = true)
        runCurrent()
        assertTrue("200ms 结束时仍处于最短停留期内，应保持显示", controller.visible.value)

        advanceTimeBy(199L) // t = 399ms
        runCurrent()
        assertTrue("399ms 时仍未满 250ms 最短停留，应保持显示", controller.visible.value)

        advanceTimeBy(1L) // t = 400ms
        runCurrent()
        assertFalse("400ms（150ms + 250ms）时应隐藏", controller.visible.value)
    }

    @Test
    fun passiveLoad_restartsWhileVisible_doesNotFlicker() = runTest {
        val controller = LoadingVisibilityController(backgroundScope)
        val history = mutableListOf<Pair<Long, Boolean>>()
        val collectJob = backgroundScope.launch {
            controller.visible.collect { history += testScheduler.currentTime to it }
        }
        runCurrent()

        // t = 0: 开始第一次被动加载
        controller.update(isLoading = true, passive = true)
        advanceTimeBy(150L) // t = 150ms: 显示
        runCurrent()
        assertTrue(controller.visible.value)

        // t = 200ms: 第一次加载结束，仍处于最短停留期
        advanceTimeBy(50L)
        runCurrent()
        controller.update(isLoading = false, passive = true)
        runCurrent()
        assertTrue(controller.visible.value)

        // t = 300ms: 最短停留期内重新开始加载
        advanceTimeBy(100L)
        runCurrent()
        controller.update(isLoading = true, passive = true)
        runCurrent()
        assertTrue(controller.visible.value)

        // t = 400ms: 最短停留期到期，但新加载仍在进行，不应闪烁隐藏
        advanceTimeBy(100L)
        runCurrent()
        assertTrue("显示期间重新开始加载后在 400ms 不应隐藏", controller.visible.value)

        // t = 500ms: 第二次加载结束，此时已连续显示 350ms >= 250ms，立即隐藏
        advanceTimeBy(100L)
        runCurrent()
        controller.update(isLoading = false, passive = true)
        runCurrent()
        assertFalse("二次加载结束后应隐藏", controller.visible.value)

        // 校验状态变化历史：初始 false -> 150ms true -> 500ms false，中间无抖动
        assertEquals(
            listOf(0L to false, 150L to true, 500L to false),
            history,
        )
        collectJob.cancel()
    }

    @Test
    fun activeOperation_showsAt0msAndHidesImmediately() = runTest {
        val controller = LoadingVisibilityController(backgroundScope)

        controller.update(isLoading = true, passive = false)
        runCurrent()
        assertTrue("主动操作应在 0ms 立即显示", controller.visible.value)

        advanceTimeBy(50L)
        runCurrent()
        controller.update(isLoading = false, passive = false)
        runCurrent()
        assertFalse("主动操作结束后应立即隐藏（不受 250ms 最短停留约束）", controller.visible.value)
    }

    @Test
    fun passiveLoad_redundantUpdatesDuringDelay_doNotResetTimer() = runTest {
        val controller = LoadingVisibilityController(backgroundScope)

        controller.update(isLoading = true, passive = true)
        advanceTimeBy(100L)
        runCurrent()
        // 重复通知 isLoading = true 不应重置 150ms 计时
        controller.update(isLoading = true, passive = true)

        advanceTimeBy(50L) // t = 150ms
        runCurrent()
        assertTrue("重复 update(true) 不应重置 150ms 延迟", controller.visible.value)
    }
}

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LoadingVisibilityReducedMotionTest {

    @Test
    fun reducedMotionScaleZero_stillEnforces150msShowDelayAnd250msMinShow() = withAnimatorDurationScale(0f) {
        var isLoading by mutableStateOf(true)
        var observedVisible = false

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val visible by rememberLoadingIndicatorVisible(
                    isLoading = isLoading,
                    passive = true,
                )
                observedVisible = visible
            }
        }

        mainClock.autoAdvance = false
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertFalse("动画缩放为 0 时，初始帧不应立即显示被动加载", observedVisible)

        mainClock.advanceTimeBy(120L)
        waitForIdle()
        assertFalse("动画缩放为 0 时，150ms 前不应提前显示", observedVisible)

        mainClock.advanceTimeBy(40L) // 累计 >= 150ms
        waitForIdle()
        assertTrue("动画缩放为 0 时，超过 150ms 后应显示", observedVisible)

        // 200ms 附近结束加载，仍需维持 250ms 最短停留
        mainClock.advanceTimeBy(40L)
        isLoading = false
        Snapshot.sendApplyNotifications()
        mainClock.advanceTimeByFrame()
        waitForIdle()
        assertTrue("动画缩放为 0 时，未满 250ms 最短停留期仍应保持显示", observedVisible)

        mainClock.advanceTimeBy(250L)
        waitForIdle()
        assertFalse("满 250ms 最短停留期后应隐藏", observedVisible)
    }

    @Test
    fun activeOperation_inCompose_becomesVisibleInSameCompositionPass() = withAnimatorDurationScale(0f) {
        var isLoading by mutableStateOf(false)
        val observedPairs = mutableListOf<Pair<Boolean, Boolean>>()

        setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                val visible by rememberLoadingIndicatorVisible(
                    isLoading = isLoading,
                    passive = false,
                )
                observedPairs += isLoading to visible
            }
        }
        waitForIdle()
        assertEquals(listOf(false to false), observedPairs)

        // 主动操作 false -> true：必须在首轮以 isLoading=true 组合时同帧即为 true（不得先组合出 false 再二次重组）
        observedPairs.clear()
        isLoading = true
        waitForIdle()
        assertEquals(
            "主动操作切到加载中时应在同一组合帧返回 true，而非先返回 false 再二次重组",
            listOf(true to true),
            observedPairs,
        )

        // 主动操作 true -> false：同样在同一组合帧返回 false
        observedPairs.clear()
        isLoading = false
        waitForIdle()
        assertEquals(
            "主动操作结束时应在同一组合帧返回 false",
            listOf(false to false),
            observedPairs,
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LoadingVisibilityStateRestorationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun passiveLoad_afterShowingSkeleton_preservesSkeletonImmediatelyOnStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        var loadState by mutableStateOf<HiLoadState<String>>(HiLoadState.Loading)

        composeRule.mainClock.autoAdvance = false
        restorationTester.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                HiContentState(
                    state = loadState,
                    skeleton = {
                        HiSkeletonBlock(
                            width = 120.dp,
                            height = 24.dp,
                            modifier = Modifier.testTag("restored_skeleton"),
                        )
                    },
                    empty = { HiEmptyState(message = "暂无帖子") },
                    error = { err -> HiErrorState(message = err.message) },
                ) { value ->
                    Text(text = value)
                }
            }
        }

        // 初始 150ms 内不显示骨架
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("restored_skeleton").assertDoesNotExist()

        // 超过 150ms 后骨架已显示
        composeRule.mainClock.advanceTimeBy(160L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("restored_skeleton").assertIsDisplayed()

        // 模拟宿主旋转/重建：不推进时钟（0ms），重建后应立即仍显示骨架，而非退回 150ms 空白
        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("restored_skeleton").assertIsDisplayed()

        // 加载完成并切到内容后，再重建不应误显示骨架
        loadState = HiLoadState.Content("已加载内容")
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已加载内容").assertIsDisplayed()
        composeRule.onNodeWithTag("restored_skeleton").assertDoesNotExist()

        restorationTester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("已加载内容").assertIsDisplayed()
        composeRule.onNodeWithTag("restored_skeleton").assertDoesNotExist()
    }
}
