package net.jejer.hipda.testing

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MainTestClock
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import net.jejer.hipda.ui.designsystem.motion.HiSystemMotionDurationScale
import org.junit.Assert.assertTrue

/** 逐帧位置连续性采样的结果。 */
data class FrameContinuityResult(
    val positionsPx: List<Offset>,
    val maxStepPx: Float,
    val maxStepDp: Dp,
)

/**
 * 关闭自动时钟推进后逐帧推进 [frameCount] 帧，采样 [nodeProvider] 的根节点坐标并断言
 * 任意相邻两帧位移不超过 [maxStepDp]。
 *
 * @param onFrame 可选回调，在第 `frameIndex` 帧（`0..frameCount`）采样后、推进下一帧前触发，
 *   可用于在动画进行到中途反向或打断（如 `frameIndex == 5` 时切换状态）。
 */
fun ComposeTestRule.assertFrameContinuity(
    nodeProvider: () -> SemanticsNodeInteraction,
    frameCount: Int,
    maxStepDp: Dp,
    onFrame: ((frameIndex: Int) -> Unit)? = null,
): FrameContinuityResult = recordAndAssertFrameContinuity(
    mainClock = mainClock,
    density = density,
    nodeProvider = nodeProvider,
    frameCount = frameCount,
    maxStepDp = maxStepDp,
    onFrame = onFrame,
)

/** [ComposeUiTest] 版本的逐帧位置连续性断言（配合 [withAnimatorDurationScale] 等使用）。 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.assertFrameContinuity(
    nodeProvider: () -> SemanticsNodeInteraction,
    frameCount: Int,
    maxStepDp: Dp,
    onFrame: ((frameIndex: Int) -> Unit)? = null,
): FrameContinuityResult = recordAndAssertFrameContinuity(
    mainClock = mainClock,
    density = density,
    nodeProvider = nodeProvider,
    frameCount = frameCount,
    maxStepDp = maxStepDp,
    onFrame = onFrame,
)

private fun recordAndAssertFrameContinuity(
    mainClock: MainTestClock,
    density: Density,
    nodeProvider: () -> SemanticsNodeInteraction,
    frameCount: Int,
    maxStepDp: Dp,
    onFrame: ((frameIndex: Int) -> Unit)?,
): FrameContinuityResult {
    require(frameCount >= 1) { "frameCount must be >= 1, got $frameCount" }
    require(maxStepDp >= 0.dp) { "maxStepDp must be >= 0.dp, got $maxStepDp" }

    val previousAutoAdvance = mainClock.autoAdvance
    mainClock.autoAdvance = false
    val positions = ArrayList<Offset>(frameCount + 1)
    try {
        for (frame in 0..frameCount) {
            positions += nodeProvider().fetchSemanticsNode().positionInRoot
            if (frame < frameCount) {
                if (onFrame != null) {
                    onFrame(frame)
                    Snapshot.sendApplyNotifications()
                }
                mainClock.advanceTimeByFrame()
            }
        }
    } finally {
        mainClock.autoAdvance = previousAutoAdvance
    }

    var maxObservedPx = 0f
    for (i in 1 until positions.size) {
        val prev = positions[i - 1]
        val curr = positions[i]
        val stepPx = (curr - prev).getDistance()
        if (stepPx > maxObservedPx) {
            maxObservedPx = stepPx
        }
        val stepDp = with(density) { stepPx.toDp() }
        assertTrue(
            "帧 $i 位移不连续：从 $prev 跳到 $curr，位移 $stepDp ($stepPx px) 超过上限 $maxStepDp",
            stepDp <= maxStepDp,
        )
    }

    return FrameContinuityResult(
        positionsPx = positions,
        maxStepPx = maxObservedPx,
        maxStepDp = with(density) { maxObservedPx.toDp() },
    )
}

/**
 * 在 Robolectric 下将系统 [Settings.Global.ANIMATOR_DURATION_SCALE] 设为 [scale] 并运行一段 Compose 测试，
 * 同时通过 [ComposeUiTestConfig.effectContext] 注入实时读取该系统设置的 [androidx.compose.ui.MotionDurationScale]；
 * 测试结束后恢复原系统值。
 */
@OptIn(ExperimentalTestApi::class)
fun withAnimatorDurationScale(
    scale: Float = 0f,
    context: Context = ApplicationProvider.getApplicationContext(),
    block: suspend ComposeUiTest.() -> Unit,
) {
    require(scale.isFinite() && scale >= 0f) {
        "Animator duration scale must be finite and >= 0, got $scale"
    }
    val resolver = context.contentResolver
    val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
    val previousRaw: String? = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
    Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    resolver.notifyChange(uri, null)
    try {
        runComposeUiTest(
            config = ComposeUiTestConfig(
                effectContext = HiSystemMotionDurationScale(context),
            ),
            block = block,
        )
    } finally {
        Settings.Global.putString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousRaw)
        resolver.notifyChange(uri, null)
    }
}

/**
 * 统计单个 Composable 的组合/重组次数并断言上限，用于守住长列表与高频交互性能。
 *
 * 标注 [Stable] 避免作为参数传入子 Composable 时破坏其可跳过性；[Track] 标注
 * [NonRestartableComposable]，确保宿主每次真实执行组合提交时都会准确计数。
 */
@Stable
class RecompositionCounter(
    val label: String = "Composable",
) {
    var count: Int = 0
        private set

    @Composable
    @NonRestartableComposable
    fun Track() {
        SideEffect {
            count++
        }
    }

    fun record() {
        count++
    }

    fun reset() {
        count = 0
    }

    fun assertAtMost(maxRecompositions: Int) {
        assertTrue(
            "$label 重组次数超限：实际 $count 次，允许上限 $maxRecompositions 次",
            count <= maxRecompositions,
        )
    }

    fun assertEquals(expected: Int) {
        org.junit.Assert.assertEquals(
            "$label 重组次数不符合预期",
            expected,
            count,
        )
    }
}
