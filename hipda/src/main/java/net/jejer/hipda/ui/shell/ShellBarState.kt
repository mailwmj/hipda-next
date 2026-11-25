package net.jejer.hipda.ui.shell

import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.rememberHiMotionCoroutineScope

private const val OffsetSnapEpsilonPx: Float = 0.5f

/**
 * 自适应外壳底部导航栏的滚动显隐状态（product-design.md §6.3“栏随滚动”）。
 *
 * - [hiddenOffsetPx] 表示向下滑出屏幕的位移（范围 `0..maxHiddenOffsetPx`）；
 * - 拖动与惯性滚动期间逐帧跟手，不跑动画；停止滚动时按是否超过 50% 以 [HiMotion.spatialDefault] 吸附；
 * - 键盘可见时立即置为全隐（0ms），收起或切换一级目的地时回到可见；
 * - 仅由 `graphicsLayer { translationY = ... }` 读取，避免内容布局跳动与滚动期间的无效重组。
 */
@Stable
class ShellBarState internal constructor(
    private val scope: CoroutineScope,
    private val isReducedMotion: () -> Boolean,
) {
    /** 底部胶囊可隐藏的最大纵向位移（像素），等于胶囊高度 + 8dp + 底部手势区 + 阴影半径（4dp）；导航轨形态下为 `0f`。 */
    var maxHiddenOffsetPx: Float by mutableFloatStateOf(0f)
        private set

    /** 当前已隐藏的纵向位移（像素），范围 `0f..maxHiddenOffsetPx`。 */
    var hiddenOffsetPx: Float by mutableFloatStateOf(0f)
        private set

    /** 当前已隐藏比例（`0f` 全显，`1f` 全隐）。 */
    val hiddenFraction: Float
        get() = if (maxHiddenOffsetPx > 0f) (hiddenOffsetPx / maxHiddenOffsetPx).coerceIn(0f, 1f) else 0f

    private var settleJob: Job? = null
    private var currentVelocity: Float = 0f
    private var isImeHidden: Boolean = false

    /** 挂在外壳内容容器上的嵌套滚动连接：向下消费滚动时隐藏，向上滚动时出现，停滚时按 50% 阈值吸附。 */
    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (maxHiddenOffsetPx <= 0f || isImeHidden) return Offset.Zero
            if (available.y != 0f) cancelSettle()
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (maxHiddenOffsetPx <= 0f || isImeHidden) return Offset.Zero
            // 仅在内容实际向下滚动（consumed.y < 0）时隐藏，避免短于一屏的内容向下拖动导致误隐藏；
            // 向上滚动时包含触顶后的剩余位移，确保随时可把胶囊拉回可见。
            val scrollUp = if (consumed.y > 0f || available.y > 0f) {
                consumed.y.coerceAtLeast(0f) + available.y.coerceAtLeast(0f)
            } else {
                0f
            }
            val deltaY = if (scrollUp > 0f) scrollUp else consumed.y
            if (deltaY != 0f) {
                cancelSettle()
                hiddenOffsetPx = (hiddenOffsetPx - deltaY).coerceIn(0f, maxHiddenOffsetPx)
            }
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (maxHiddenOffsetPx > 0f && !isImeHidden) {
                val target = if (hiddenOffsetPx > maxHiddenOffsetPx * 0.5f) maxHiddenOffsetPx else 0f
                animateOffsetTo(target)
            }
            return Velocity.Zero
        }
    }

    /** 将底部导航胶囊恢复至完全可见（键盘可见期间忽略）。 */
    fun show() {
        if (isImeHidden || maxHiddenOffsetPx <= 0f) return
        animateOffsetTo(0f)
    }

    internal fun syncLayout(newMaxHiddenOffsetPx: Float, imeVisible: Boolean) {
        val clampedMax = newMaxHiddenOffsetPx.coerceAtLeast(0f)
        val oldMax = maxHiddenOffsetPx
        if (clampedMax != oldMax) {
            maxHiddenOffsetPx = clampedMax
            when {
                clampedMax == 0f -> {
                    cancelSettle()
                    hiddenOffsetPx = 0f
                }
                isImeHidden || (oldMax > 0f && hiddenOffsetPx >= oldMax - OffsetSnapEpsilonPx && settleJob == null) -> {
                    hiddenOffsetPx = clampedMax
                }
                else -> hiddenOffsetPx = hiddenOffsetPx.coerceIn(0f, clampedMax)
            }
        }
        if (imeVisible) {
            isImeHidden = true
            cancelSettle()
            hiddenOffsetPx = maxHiddenOffsetPx
        } else if (isImeHidden) {
            isImeHidden = false
            show()
        }
    }

    private fun animateOffsetTo(target: Float) {
        val clamped = target.coerceIn(0f, maxHiddenOffsetPx)
        val velocity = currentVelocity
        cancelSettle(resetVelocity = false)
        if (isReducedMotion() || abs(hiddenOffsetPx - clamped) < OffsetSnapEpsilonPx) {
            hiddenOffsetPx = clamped
            currentVelocity = 0f
            return
        }
        val start = hiddenOffsetPx
        settleJob = scope.launch {
            try {
                animate(
                    initialValue = start,
                    targetValue = clamped,
                    initialVelocity = velocity,
                    animationSpec = HiMotion.spatialDefault(visibilityThreshold = OffsetSnapEpsilonPx),
                ) { value, vel ->
                    hiddenOffsetPx = value.coerceIn(0f, maxHiddenOffsetPx)
                    currentVelocity = vel
                }
                hiddenOffsetPx = clamped
            } finally {
                if (settleJob == coroutineContext[Job]) {
                    currentVelocity = 0f
                    settleJob = null
                }
            }
        }
    }

    private fun cancelSettle(resetVelocity: Boolean = true) {
        settleJob?.cancel()
        settleJob = null
        if (resetVelocity) currentVelocity = 0f
    }
}

/** 创建并记住绑定当前动效时长缩放的 [ShellBarState]。 */
@Composable
fun rememberShellBarState(): ShellBarState {
    val scope = rememberHiMotionCoroutineScope()
    val reducedMotion by rememberUpdatedState(HiTheme.isReducedMotion)
    return remember(scope) { ShellBarState(scope = scope, isReducedMotion = { reducedMotion }) }
}
