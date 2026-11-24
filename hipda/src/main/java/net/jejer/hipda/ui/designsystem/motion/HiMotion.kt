package net.jejer.hipda.ui.designsystem.motion

import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Immutable

/**
 * HiPDA Next 唯一动效令牌表（严格对齐 `docs/design/product-design.md` §6.2）。
 *
 * 本文件是生产代码中唯一允许直接出现 `spring(`、`tween(`、`keyframes(`、`snap(` 与 `durationMillis`
 * 的位置，由 `MotionTokenScanTest` 强制约束。
 */
@Immutable
object HiMotion {
    private val spatialFastSpec: SpringSpec<Any> = spring(dampingRatio = 0.9f, stiffness = 1400f)
    private val spatialDefaultSpec: SpringSpec<Any> = spring(dampingRatio = 0.9f, stiffness = 700f)
    private val spatialSlowSpec: SpringSpec<Any> = spring(dampingRatio = 0.9f, stiffness = 300f)
    private val spatialSnapSpec: SpringSpec<Any> = spring(dampingRatio = 0.85f, stiffness = 1400f)

    private val effectsFastSpec: SpringSpec<Any> = spring(dampingRatio = 1.0f, stiffness = 3800f)
    private val effectsDefaultSpec: SpringSpec<Any> = spring(dampingRatio = 1.0f, stiffness = 1600f)
    private val effectsSlowSpec: SpringSpec<Any> = spring(dampingRatio = 1.0f, stiffness = 800f)

    /** 小元素位移：选择指示器、开关、图标位移、按钮按压缩放（阻尼比 0.9，刚度 1400）。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> spatialFast(visibilityThreshold: T? = null): SpringSpec<T> =
        if (visibilityThreshold == null) {
            spatialFastSpec as SpringSpec<T>
        } else {
            spring(dampingRatio = 0.9f, stiffness = 1400f, visibilityThreshold = visibilityThreshold)
        }

    /** 标准位移：页面推入推出、面板升降、右栏下钻、展开收起（阻尼比 0.9，刚度 700）。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> spatialDefault(visibilityThreshold: T? = null): SpringSpec<T> =
        if (visibilityThreshold == null) {
            spatialDefaultSpec as SpringSpec<T>
        } else {
            spring(dampingRatio = 0.9f, stiffness = 700f, visibilityThreshold = visibilityThreshold)
        }

    /** 大面积布局变化：双栏出现或消失、看图放大和缩回（阻尼比 0.9，刚度 300）。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> spatialSlow(visibilityThreshold: T? = null): SpringSpec<T> =
        if (visibilityThreshold == null) {
            spatialSlowSpec as SpringSpec<T>
        } else {
            spring(dampingRatio = 0.9f, stiffness = 300f, visibilityThreshold = visibilityThreshold)
        }

    /** 松手后的吸附与弹回：分隔线吸附、看图下拉与面板未达阈值弹回（阻尼比 0.85，刚度 1400）。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> spatialSnap(visibilityThreshold: T? = null): SpringSpec<T> =
        if (visibilityThreshold == null) {
            spatialSnapSpec as SpringSpec<T>
        } else {
            spring(dampingRatio = 0.85f, stiffness = 1400f, visibilityThreshold = visibilityThreshold)
        }

    /** 快速非位移变化：颜色/透明度小变化、按压状态层、工具栏显隐（阻尼比 1.0，刚度 3800）。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> effectsFast(visibilityThreshold: T? = null): SpringSpec<T> =
        if (visibilityThreshold == null) {
            effectsFastSpec as SpringSpec<T>
        } else {
            spring(dampingRatio = 1.0f, stiffness = 3800f, visibilityThreshold = visibilityThreshold)
        }

    /** 标准非位移变化：内容替换淡入淡出、顶栏变色（阻尼比 1.0，刚度 1600）。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> effectsDefault(visibilityThreshold: T? = null): SpringSpec<T> =
        if (visibilityThreshold == null) {
            effectsDefaultSpec as SpringSpec<T>
        } else {
            spring(dampingRatio = 1.0f, stiffness = 1600f, visibilityThreshold = visibilityThreshold)
        }

    /** 慢速非位移变化：大面积遮罩、主题颜色切换、高亮淡出（阻尼比 1.0，刚度 800）。 */
    @Suppress("UNCHECKED_CAST")
    fun <T> effectsSlow(visibilityThreshold: T? = null): SpringSpec<T> =
        if (visibilityThreshold == null) {
            effectsSlowSpec as SpringSpec<T>
        } else {
            spring(dampingRatio = 1.0f, stiffness = 800f, visibilityThreshold = visibilityThreshold)
        }

    /**
     * 所有退场动画的统一换算：取对应入场弹簧，刚度 × 2.0，阻尼比改为 1.0（不过冲）。
     */
    fun <T> exit(token: SpringSpec<T>): SpringSpec<T> = spring(
        dampingRatio = 1.0f,
        stiffness = token.stiffness * 2.0f,
        visibilityThreshold = token.visibilityThreshold,
    )

    /** 不确定进度圈转一圈周期（ms）。 */
    const val spinnerPeriodMillis: Int = 1000

    /** 骨架屏明暗呼吸一个周期（ms）。 */
    const val skeletonPeriodMillis: Int = 1500

    /** 被动加载防闪延迟（ms）；不属于动画，不受系统动画时长缩放影响（§6.1）。 */
    const val loadingShowDelay: Long = 150L

    /** 被动加载指示器一旦显示后的最短停留时间（ms）。 */
    const val loadingMinShow: Long = 250L

    /** 引用跳转后目标楼层高亮的停留时间（ms）；不属于动画，不受系统动画时长缩放影响（§6.1）。 */
    const val highlightHold: Long = 1000L

    /** 不确定进度圈周期令牌（1000ms，线性），用于 `infiniteRepeatable`。 */
    fun <T> spinnerPeriod(): DurationBasedAnimationSpec<T> = tween(
        durationMillis = spinnerPeriodMillis,
        easing = LinearEasing,
    )

    /** 骨架屏呼吸周期令牌（1500ms），用于 `infiniteRepeatable`。 */
    fun <T> skeletonPeriod(): DurationBasedAnimationSpec<T> = tween(
        durationMillis = skeletonPeriodMillis,
        easing = FastOutSlowInEasing,
    )

    /** 进度圈无限循环规格（1000ms 线性重启）。 */
    fun <T> spinnerRepeatable(): InfiniteRepeatableSpec<T> = infiniteRepeatable(
        animation = spinnerPeriod(),
        repeatMode = RepeatMode.Restart,
    )

    /** 骨架屏明暗呼吸无限循环规格（1500ms 往返）。 */
    fun <T> skeletonRepeatable(): InfiniteRepeatableSpec<T> = infiniteRepeatable(
        animation = skeletonPeriod(),
        repeatMode = RepeatMode.Reverse,
    )

    /** 与 Material 3 标准动效方案对齐的 `MotionScheme`，供 [net.jejer.hipda.ui.designsystem.HiTheme] 注入。 */
    val motionScheme: MotionScheme = object : MotionScheme {
        override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spatialDefault()
        override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spatialFast()
        override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spatialSlow()
        override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = effectsDefault()
        override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = effectsFast()
        override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = effectsSlow()
    }
}
