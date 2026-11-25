package net.jejer.hipda.ui.designsystem.motion

import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.reflect.KClass

/**
 * HiPDA Next 唯一动效令牌表（严格对齐 `docs/design/product-design.md` §6.2）。
 *
 * 本文件是生产代码中唯一允许直接出现 `spring(`、`tween(`、`keyframes(`、`snap(` 与 `durationMillis`
 * 的位置，由 `MotionTokenScanTest` 强制约束。
 */
@Immutable
object HiMotion {
    @Immutable
    @PublishedApi
    internal class SpringTokenCache(
        private val dampingRatio: Float,
        private val stiffness: Float,
    ) {
        val defaultSpec: SpringSpec<Any> = spring(dampingRatio = dampingRatio, stiffness = stiffness)
        private val dpSpec: SpringSpec<Dp> = spring(dampingRatio, stiffness, Dp.VisibilityThreshold)
        private val dpOffsetSpec: SpringSpec<DpOffset> = spring(dampingRatio, stiffness, DpOffset.VisibilityThreshold)
        private val intOffsetSpec: SpringSpec<IntOffset> = spring(dampingRatio, stiffness, IntOffset.VisibilityThreshold)
        private val intSizeSpec: SpringSpec<IntSize> = spring(dampingRatio, stiffness, IntSize.VisibilityThreshold)
        private val offsetSpec: SpringSpec<Offset> = spring(dampingRatio, stiffness, Offset.VisibilityThreshold)
        private val sizeSpec: SpringSpec<Size> = spring(dampingRatio, stiffness, Size.VisibilityThreshold)
        private val rectSpec: SpringSpec<Rect> = spring(dampingRatio, stiffness, Rect.VisibilityThreshold)
        private val intSpec: SpringSpec<Int> = spring(dampingRatio, stiffness, Int.VisibilityThreshold)

        @Suppress("UNCHECKED_CAST")
        fun <T> specFor(type: KClass<*>, visibilityThreshold: T?): SpringSpec<T> {
            if (visibilityThreshold != null) {
                return spring(
                    dampingRatio = dampingRatio,
                    stiffness = stiffness,
                    visibilityThreshold = visibilityThreshold,
                )
            }
            return when (type) {
                Dp::class -> dpSpec
                DpOffset::class -> dpOffsetSpec
                IntOffset::class -> intOffsetSpec
                IntSize::class -> intSizeSpec
                Offset::class -> offsetSpec
                Size::class -> sizeSpec
                Rect::class -> rectSpec
                Int::class -> intSpec
                else -> defaultSpec
            } as SpringSpec<T>
        }
    }

    @PublishedApi
    internal val spatialFastCache = SpringTokenCache(dampingRatio = 0.9f, stiffness = 1400f)

    @PublishedApi
    internal val spatialDefaultCache = SpringTokenCache(dampingRatio = 0.9f, stiffness = 700f)

    @PublishedApi
    internal val spatialSlowCache = SpringTokenCache(dampingRatio = 0.9f, stiffness = 300f)

    @PublishedApi
    internal val spatialSnapCache = SpringTokenCache(dampingRatio = 0.85f, stiffness = 1400f)

    @PublishedApi
    internal val effectsFastCache = SpringTokenCache(dampingRatio = 1.0f, stiffness = 3800f)

    @PublishedApi
    internal val effectsDefaultCache = SpringTokenCache(dampingRatio = 1.0f, stiffness = 1600f)

    @PublishedApi
    internal val effectsSlowCache = SpringTokenCache(dampingRatio = 1.0f, stiffness = 800f)

    /** 小元素位移：选择指示器、开关、图标位移、按钮按压缩放（阻尼比 0.9，刚度 1400）；常用几何类型自动填充默认 `VisibilityThreshold`。 */
    inline fun <reified T> spatialFast(visibilityThreshold: T? = null): SpringSpec<T> =
        spatialFastCache.specFor(T::class, visibilityThreshold)

    /** 标准位移：页面推入推出、面板升降、右栏下钻、展开收起（阻尼比 0.9，刚度 700）；常用几何类型自动填充默认 `VisibilityThreshold`。 */
    inline fun <reified T> spatialDefault(visibilityThreshold: T? = null): SpringSpec<T> =
        spatialDefaultCache.specFor(T::class, visibilityThreshold)

    /** 大面积布局变化：双栏出现或消失、看图放大和缩回（阻尼比 0.9，刚度 300）；常用几何类型自动填充默认 `VisibilityThreshold`。 */
    inline fun <reified T> spatialSlow(visibilityThreshold: T? = null): SpringSpec<T> =
        spatialSlowCache.specFor(T::class, visibilityThreshold)

    /** 松手后的吸附与弹回：分隔线吸附、看图下拉与面板未达阈值弹回（阻尼比 0.85，刚度 1400）；常用几何类型自动填充默认 `VisibilityThreshold`。 */
    inline fun <reified T> spatialSnap(visibilityThreshold: T? = null): SpringSpec<T> =
        spatialSnapCache.specFor(T::class, visibilityThreshold)

    /** 快速非位移变化：颜色/透明度小变化、按压状态层、工具栏显隐（阻尼比 1.0，刚度 3800）。 */
    inline fun <reified T> effectsFast(visibilityThreshold: T? = null): SpringSpec<T> =
        effectsFastCache.specFor(T::class, visibilityThreshold)

    /** 标准非位移变化：内容替换淡入淡出、顶栏变色（阻尼比 1.0，刚度 1600）。 */
    inline fun <reified T> effectsDefault(visibilityThreshold: T? = null): SpringSpec<T> =
        effectsDefaultCache.specFor(T::class, visibilityThreshold)

    /** 慢速非位移变化：大面积遮罩、主题颜色切换、高亮淡出（阻尼比 1.0，刚度 800）。 */
    inline fun <reified T> effectsSlow(visibilityThreshold: T? = null): SpringSpec<T> =
        effectsSlowCache.specFor(T::class, visibilityThreshold)

    /**
     * 所有退场动画的统一换算：取对应入场弹簧，刚度 × 2.0，阻尼比改为 1.0（不过冲），并保留原 `visibilityThreshold`。
     */
    fun <T> exit(token: SpringSpec<T>): SpringSpec<T> = spring(
        dampingRatio = 1.0f,
        stiffness = token.stiffness * 2.0f,
        visibilityThreshold = token.visibilityThreshold,
    )

    /** 不确定进度圈转一圈周期（ms）。 */
    const val spinnerPeriodMillis: Int = 1000

    /** 骨架屏明暗呼吸一个完整周期（ms）。 */
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

    /** 骨架屏完整呼吸周期令牌（1500ms），适合 `RepeatMode.Restart` 等单向周期动画。 */
    fun <T> skeletonPeriod(): DurationBasedAnimationSpec<T> = tween(
        durationMillis = skeletonPeriodMillis,
        easing = FastOutSlowInEasing,
    )

    /** 进度圈无限循环规格（1000ms 线性重启）。 */
    fun <T> spinnerRepeatable(): InfiniteRepeatableSpec<T> = infiniteRepeatable(
        animation = spinnerPeriod(),
        repeatMode = RepeatMode.Restart,
    )

    /**
     * 骨架屏明暗呼吸无限循环规格：采用 `RepeatMode.Reverse`，单程取半周期（`skeletonPeriodMillis / 2 = 750ms`），
     * 使一次完整“暗→亮→暗”往返严格为 1500ms（对齐 §6.2）。
     */
    fun <T> skeletonRepeatable(): InfiniteRepeatableSpec<T> = infiniteRepeatable(
        animation = tween(
            durationMillis = skeletonPeriodMillis / 2,
            easing = FastOutSlowInEasing,
        ),
        repeatMode = RepeatMode.Reverse,
    )

    /** 与 Material 3 标准动效方案对齐的 `MotionScheme`，供 [net.jejer.hipda.ui.designsystem.HiTheme] 注入。 */
    val motionScheme: MotionScheme = object : MotionScheme {
        @Suppress("UNCHECKED_CAST")
        override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
            spatialDefaultCache.defaultSpec as FiniteAnimationSpec<T>

        @Suppress("UNCHECKED_CAST")
        override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> =
            spatialFastCache.defaultSpec as FiniteAnimationSpec<T>

        @Suppress("UNCHECKED_CAST")
        override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> =
            spatialSlowCache.defaultSpec as FiniteAnimationSpec<T>

        @Suppress("UNCHECKED_CAST")
        override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> =
            effectsDefaultCache.defaultSpec as FiniteAnimationSpec<T>

        @Suppress("UNCHECKED_CAST")
        override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
            effectsFastCache.defaultSpec as FiniteAnimationSpec<T>

        @Suppress("UNCHECKED_CAST")
        override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> =
            effectsSlowCache.defaultSpec as FiniteAnimationSpec<T>
    }
}
