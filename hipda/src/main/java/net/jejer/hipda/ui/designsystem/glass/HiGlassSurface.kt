package net.jejer.hipda.ui.designsystem.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme

/** 毛玻璃表面视觉令牌，含导航胶囊默认档与帖子详情工具条预留档（底层为 Kyant `backdrop` 液态玻璃）。 */
@Immutable
object HiGlassTokens {
    // 导航胶囊默认档（轻透 + 边缘光学折射）
    val BlurRadius: Dp = 10.dp
    const val LightBlurTintAlpha: Float = 0.45f
    const val DarkBlurTintAlpha: Float = 0.46f
    const val BlurSaturation: Float = 1.18f
    val RefractionHeight: Dp = 16.dp
    val RefractionAmount: Dp = 24.dp
    const val DepthEffect: Boolean = false
    const val ChromaticAberration: Boolean = false
    const val VibrancyEnabled: Boolean = true

    // 帖子详情工具条预留档（护读 + 克制折射）
    val ToolbarBlurRadius: Dp = 16.dp
    const val ToolbarLightBlurTintAlpha: Float = 0.60f
    const val ToolbarDarkBlurTintAlpha: Float = 0.68f
    const val ToolbarBlurSaturation: Float = 1.08f
    val ToolbarRefractionHeight: Dp = 12.dp
    val ToolbarRefractionAmount: Dp = 24.dp

    // 降级态（无模糊）
    const val DegradedSurfaceAlpha: Float = 0.94f

    // 描边：激活态为左上→右下渐变高光，浅色先垫 0.5dp 暗轮廓、再叠 0.75dp 白高光；降级态为 outlineVariant 细线
    val BorderWidth: Dp = 0.75.dp
    val DegradedBorderWidth: Dp = 0.5.dp
    const val LightBorderHighlightTopLeftAlpha: Float = 0.85f
    const val LightBorderHighlightMidAlpha: Float = 0.28f
    const val LightBorderHighlightBottomRightAlpha: Float = 0.45f
    const val LightBorderOutlineAlpha: Float = 0.06f
    const val DarkBorderHighlightTopLeftAlpha: Float = 0.24f
    const val DarkBorderHighlightMidAlpha: Float = 0.05f
    const val DarkBorderHighlightBottomRightAlpha: Float = 0.12f
    const val DefaultBorderHighlightStrength: Float = 1f

    // 导航胶囊半透明选中指示器淡底
    const val IndicatorAlpha: Float = 0.12f

    // 悬浮外阴影
    val FloatingShadowElevation: Dp = 4.dp
    const val LightShadowAlpha: Float = 0.07f
    const val DarkShadowAlpha: Float = 0.18f

    const val MaxGlassSurfacesOnScreen: Int = 3
}

/**
 * 毛玻璃主题风格配置，由 [HiTheme] 通过 [LocalHiGlassStyle] 提供默认值。
 *
 * 生产组件只读取 [LocalHiGlassStyle] 或显式传入预置档（如 [Toolbar]）。
 * 注：Kyant `backdrop 2.0.1` 不支持噪点特效，不设噪点参数。
 */
@Immutable
data class HiGlassStyle(
    val blurRadius: Dp = HiGlassTokens.BlurRadius,
    val lightTintAlpha: Float = HiGlassTokens.LightBlurTintAlpha,
    val darkTintAlpha: Float = HiGlassTokens.DarkBlurTintAlpha,
    val saturation: Float = HiGlassTokens.BlurSaturation,
    val refractionHeight: Dp = HiGlassTokens.RefractionHeight,
    val refractionAmount: Dp = HiGlassTokens.RefractionAmount,
    val depthEffect: Boolean = HiGlassTokens.DepthEffect,
    val chromaticAberration: Boolean = HiGlassTokens.ChromaticAberration,
    val vibrancyEnabled: Boolean = HiGlassTokens.VibrancyEnabled,
    val borderWidth: Dp = HiGlassTokens.BorderWidth,
    val borderHighlightStrength: Float = HiGlassTokens.DefaultBorderHighlightStrength,
    val indicatorAlpha: Float = HiGlassTokens.IndicatorAlpha,
    val floatingShadowElevation: Dp = HiGlassTokens.FloatingShadowElevation,
    val lightShadowAlpha: Float = HiGlassTokens.LightShadowAlpha,
    val darkShadowAlpha: Float = HiGlassTokens.DarkShadowAlpha,
) {
    companion object {
        /** 导航胶囊默认档：10dp 模糊、16dp/24dp 折射、vibrancy 活色、0.45/0.46 着色、1.18 饱和度。 */
        val Navigation: HiGlassStyle = HiGlassStyle()

        /** 帖子详情底部工具条预留档：16dp 模糊、12dp/24dp 折射、vibrancy 活色、0.60/0.68 着色、1.08 饱和度。 */
        val Toolbar: HiGlassStyle = HiGlassStyle(
            blurRadius = HiGlassTokens.ToolbarBlurRadius,
            lightTintAlpha = HiGlassTokens.ToolbarLightBlurTintAlpha,
            darkTintAlpha = HiGlassTokens.ToolbarDarkBlurTintAlpha,
            saturation = HiGlassTokens.ToolbarBlurSaturation,
            refractionHeight = HiGlassTokens.ToolbarRefractionHeight,
            refractionAmount = HiGlassTokens.ToolbarRefractionAmount,
        )
    }
}

/** 当前主题生效的毛玻璃风格令牌；使用 [compositionLocalOf] 以在动态变更时仅重组读取方。 */
val LocalHiGlassStyle = compositionLocalOf { HiGlassStyle.Navigation }

/** 标记一个毛玻璃区域节点（独立 [HiGlassSurface] 或 [HiGlassRegion]），供断言“同屏同时最多 3 个玻璃区域”。 */
val HiGlassMarker: SemanticsPropertyKey<Boolean> = SemanticsPropertyKey(
    name = "HiGlassMarker",
    mergePolicy = { parentValue, childValue -> parentValue ?: childValue },
)

/** 标记该 [HiGlassSurface] 当前处于实时模糊/折射态（`true`）或 94% 纯色降级态（`false`）。 */
val HiGlassBlurActive: SemanticsPropertyKey<Boolean> = SemanticsPropertyKey(
    name = "HiGlassBlurActive",
    mergePolicy = { parentValue, childValue -> parentValue ?: childValue },
)

private val LocalInsideHiGlassRegion = staticCompositionLocalOf { false }

/**
 * 将同一玻璃视觉区域内共享采样源的多个 [HiGlassSurface]（例如帖子详情底部三段式工具条的左/中/右三枚胶囊）
 * 归并为一个“玻璃区域”计数单元：区域容器本身仅挂 1 个 [HiGlassMarker]，内部各子表面不再重复计数；
 * 玻璃区域内的轻底按钮请直接使用普通半透明容器，无需套用 [HiGlassSurface]。
 */
@Composable
fun HiGlassRegion(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val alreadyInRegion = LocalInsideHiGlassRegion.current
    Box(
        modifier = modifier.then(
            if (alreadyInRegion) Modifier else Modifier.semantics { this[HiGlassMarker] = true },
        ),
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalInsideHiGlassRegion provides true) {
            content()
        }
    }
}

/**
 * 毛玻璃内容源与浮层之间的协调状态；封装底层 Kyant [LayerBackdrop] 及按需离屏录制计数。
 *
 * **防循环采样铁律**：挂载 [hiGlassSource] 的内容源必须与 [HiGlassSurface] 保持严格兄弟节点关系，
 * 绝不可父子嵌套，否则 RenderThread（`libhwui.so`）会因循环采样触发 Native Crash。
 */
@Stable
class HiGlassState internal constructor(
    internal val layerBackdrop: LayerBackdrop?,
) {
    constructor() : this(null)

    private var activeBlurSurfaceCount by mutableIntStateOf(0)

    /** 是否存在至少一个激活模糊/折射态的 [HiGlassSurface] 需要采样背景。 */
    val hasSourceDemand: Boolean
        get() = activeBlurSurfaceCount > 0

    internal fun acquireSourceDemand() {
        activeBlurSurfaceCount += 1
    }

    internal fun releaseSourceDemand() {
        activeBlurSurfaceCount = (activeBlurSurfaceCount - 1).coerceAtLeast(0)
    }
}

@Composable
fun rememberHiGlassState(): HiGlassState {
    val layerBackdrop = rememberLayerBackdrop()
    return remember(layerBackdrop) { HiGlassState(layerBackdrop) }
}

/**
 * 标记会被毛玻璃采样的内容区（如滚动列表与正文内容区）。
 *
 * 仅当关联 [HiGlassState] 存在激活模糊态消费者时才挂载 `layerBackdrop` 录制离屏图层；
 * 降级态下自动卸下录制节点，避免每帧无谓的离屏拷贝开销。
 * 必须与 [HiGlassSurface] 保持严格兄弟节点关系，严禁父子嵌套。
 */
fun Modifier.hiGlassSource(
    state: HiGlassState,
    @Suppress("UNUSED_PARAMETER") zIndex: Float = 0f,
    @Suppress("UNUSED_PARAMETER") key: Any? = null,
): Modifier {
    val backdrop = state.layerBackdrop
    return if (backdrop != null && state.hasSourceDemand) {
        layerBackdrop(backdrop)
    } else {
        this
    }
}

/** 解析后的毛玻璃视觉规格，供 [HiGlassSurface] 渲染与单元测试核验参数及对比度。 */
@Immutable
data class HiGlassStyleSpec(
    val renderTier: GlassRenderTier,
    val blurEnabled: Boolean,
    val refractionEnabled: Boolean,
    val blurRadius: Dp,
    val refractionHeight: Dp,
    val refractionAmount: Dp,
    val depthEffect: Boolean,
    val chromaticAberration: Boolean,
    val vibrancyEnabled: Boolean,
    val saturation: Float,
    val surfaceColor: Color,
    val activeTintColor: Color,
    val degradedSurfaceColor: Color,
    val borderWidth: Dp,
    val borderColor: Color,
    val borderHighlightColors: List<Color>,
    val borderOutlineColor: Color,
    val shadowElevation: Dp,
    val shadowColor: Color,
)

/**
 * 纯函数解析毛玻璃视觉规格（支持四路档位）：
 * - 激活态（[GlassRenderTier.REFRACTION_AND_BLUR] / [GlassRenderTier.BLUR_ONLY]）底色默认浅色取
 *   `surfaceContainerLowest`、深色取 `surfaceContainer`，配左上→右下渐变高光描边；
 * - 降级态（[GlassRenderTier.SOLID_DEGRADED]）默认保留 `surface`（94% 不透明）+ `outlineVariant` 细描边，
 *   守住 WCAG 4.5:1 对比度；
 * - 显式传入非 [Color.Unspecified] 的 [surfaceColor] 时，激活态与降级态均尊重该底色。
 */
fun resolveHiGlassStyleSpec(
    colorScheme: ColorScheme,
    darkTheme: Boolean,
    renderTier: GlassRenderTier,
    floating: Boolean = true,
    surfaceColor: Color = Color.Unspecified,
    style: HiGlassStyle = HiGlassStyle.Navigation,
): HiGlassStyleSpec {
    val blurEnabled = renderTier != GlassRenderTier.SOLID_DEGRADED
    val refractionEnabled = renderTier == GlassRenderTier.REFRACTION_AND_BLUR
    val resolvedSurfaceColor = when {
        surfaceColor != Color.Unspecified -> surfaceColor
        darkTheme -> colorScheme.surfaceContainer
        else -> colorScheme.surfaceContainerLowest
    }
    val degradedBaseColor = if (surfaceColor != Color.Unspecified) surfaceColor else colorScheme.surface
    val tintAlpha = (if (darkTheme) style.darkTintAlpha else style.lightTintAlpha).coerceIn(0f, 1f)
    val strength = style.borderHighlightStrength.coerceAtLeast(0f)
    val borderHighlightColors = if (blurEnabled) {
        val topLeft = if (darkTheme) {
            HiGlassTokens.DarkBorderHighlightTopLeftAlpha
        } else {
            HiGlassTokens.LightBorderHighlightTopLeftAlpha
        }
        val mid = if (darkTheme) {
            HiGlassTokens.DarkBorderHighlightMidAlpha
        } else {
            HiGlassTokens.LightBorderHighlightMidAlpha
        }
        val bottomRight = if (darkTheme) {
            HiGlassTokens.DarkBorderHighlightBottomRightAlpha
        } else {
            HiGlassTokens.LightBorderHighlightBottomRightAlpha
        }
        listOf(
            Color.White.copy(alpha = (topLeft * strength).coerceIn(0f, 1f)),
            Color.White.copy(alpha = (mid * strength).coerceIn(0f, 1f)),
            Color.White.copy(alpha = (bottomRight * strength).coerceIn(0f, 1f)),
        )
    } else {
        emptyList()
    }
    val borderColor = if (blurEnabled) borderHighlightColors.first() else colorScheme.outlineVariant
    val borderOutlineColor = if (blurEnabled && !darkTheme && strength > 0f) {
        Color.Black.copy(alpha = (HiGlassTokens.LightBorderOutlineAlpha * strength).coerceIn(0f, 1f))
    } else {
        Color.Transparent
    }
    val shadowAlpha = (if (darkTheme) style.darkShadowAlpha else style.lightShadowAlpha).coerceIn(0f, 1f)
    return HiGlassStyleSpec(
        renderTier = renderTier,
        blurEnabled = blurEnabled,
        refractionEnabled = refractionEnabled,
        blurRadius = style.blurRadius.coerceAtLeast(0.dp),
        refractionHeight = style.refractionHeight.coerceAtLeast(0.dp),
        refractionAmount = style.refractionAmount.coerceAtLeast(0.dp),
        depthEffect = style.depthEffect,
        chromaticAberration = style.chromaticAberration,
        vibrancyEnabled = style.vibrancyEnabled,
        saturation = style.saturation.coerceAtLeast(0f),
        surfaceColor = resolvedSurfaceColor,
        activeTintColor = resolvedSurfaceColor.copy(alpha = tintAlpha),
        degradedSurfaceColor = degradedBaseColor.copy(alpha = HiGlassTokens.DegradedSurfaceAlpha),
        borderWidth = if (blurEnabled) style.borderWidth else HiGlassTokens.DegradedBorderWidth,
        borderColor = borderColor,
        borderHighlightColors = borderHighlightColors,
        borderOutlineColor = borderOutlineColor,
        shadowElevation = if (floating) style.floatingShadowElevation else 0.dp,
        shadowColor = Color.Black.copy(alpha = shadowAlpha),
    )
}

/** 兼容旧签名的规格解析重载：`blurEnabled = true` 映射至 [GlassRenderTier.REFRACTION_AND_BLUR]。 */
fun resolveHiGlassStyleSpec(
    colorScheme: ColorScheme,
    darkTheme: Boolean,
    blurEnabled: Boolean,
    floating: Boolean = true,
    surfaceColor: Color = Color.Unspecified,
    style: HiGlassStyle = HiGlassStyle.Navigation,
): HiGlassStyleSpec = resolveHiGlassStyleSpec(
    colorScheme = colorScheme,
    darkTheme = darkTheme,
    renderTier = if (blurEnabled) GlassRenderTier.REFRACTION_AND_BLUR else GlassRenderTier.SOLID_DEGRADED,
    floating = floating,
    surfaceColor = surfaceColor,
    style = style,
)

/**
 * 传给 Kyant `backdrop` 渲染管线的纯配置对象；生产渲染与单元测试共用同一映射路径，
 * 按 [GlassRenderTier] 精确控制是否开启 `drawBackdrop`、`blur`、`lens` 与 `vibrancy`/`colorControls`。
 */
@Immutable
data class HiGlassBackdropConfig(
    val renderTier: GlassRenderTier,
    val blurRadius: Dp,
    val refractionHeight: Dp,
    val refractionAmount: Dp,
    val depthEffect: Boolean,
    val chromaticAberration: Boolean,
    val vibrancyEnabled: Boolean,
    val saturation: Float,
    val tintColor: Color,
    val fallbackColor: Color,
) {
    /** 当前档位是否应挂载 `drawBackdrop` 管线。 */
    val backdropEnabled: Boolean
        get() = renderTier != GlassRenderTier.SOLID_DEGRADED

    /** 当前档位与参数是否应调用 API 33+ `lens()` 折射特效。 */
    val refractionEnabled: Boolean
        get() = renderTier == GlassRenderTier.REFRACTION_AND_BLUR &&
            refractionHeight > 0.dp &&
            refractionAmount > 0.dp
}

/**
 * 将 [HiGlassStyleSpec] 转为指定 [renderTier] 的 [HiGlassBackdropConfig]：
 * - [GlassRenderTier.REFRACTION_AND_BLUR]（API 33+）：开启模糊 + 折射 + 活色 + 中性着色；
 * - [GlassRenderTier.BLUR_ONLY]（API 31–32）：仅开启模糊 + 活色 + 中性着色，折射归零且禁用 `lens`；
 * - [GlassRenderTier.SOLID_DEGRADED]（API ≤ 30 或系统降级）：关闭全部 backdrop 特效，回退至 94% 纯色。
 */
fun HiGlassStyleSpec.toBackdropConfig(
    renderTier: GlassRenderTier = this.renderTier,
): HiGlassBackdropConfig = when (renderTier) {
    GlassRenderTier.REFRACTION_AND_BLUR -> HiGlassBackdropConfig(
        renderTier = GlassRenderTier.REFRACTION_AND_BLUR,
        blurRadius = blurRadius,
        refractionHeight = refractionHeight,
        refractionAmount = refractionAmount,
        depthEffect = depthEffect,
        chromaticAberration = chromaticAberration,
        vibrancyEnabled = vibrancyEnabled,
        saturation = saturation,
        tintColor = activeTintColor,
        fallbackColor = degradedSurfaceColor,
    )
    GlassRenderTier.BLUR_ONLY -> HiGlassBackdropConfig(
        renderTier = GlassRenderTier.BLUR_ONLY,
        blurRadius = blurRadius,
        refractionHeight = 0.dp,
        refractionAmount = 0.dp,
        depthEffect = false,
        chromaticAberration = false,
        vibrancyEnabled = vibrancyEnabled,
        saturation = saturation,
        tintColor = activeTintColor,
        fallbackColor = degradedSurfaceColor,
    )
    GlassRenderTier.SOLID_DEGRADED -> HiGlassBackdropConfig(
        renderTier = GlassRenderTier.SOLID_DEGRADED,
        blurRadius = 0.dp,
        refractionHeight = 0.dp,
        refractionAmount = 0.dp,
        depthEffect = false,
        chromaticAberration = false,
        vibrancyEnabled = false,
        saturation = 1f,
        tintColor = degradedSurfaceColor,
        fallbackColor = degradedSurfaceColor,
    )
}

private val ZeroCornerRectShape: RoundedCornerShape = RoundedCornerShape(0.dp)

private fun resolveBackdropShape(shape: Shape): Shape =
    if (shape === RectangleShape) ZeroCornerRectShape else shape

private fun supportsLensShape(shape: Shape): Boolean =
    shape is CornerBasedShape

/**
 * 毛玻璃表面组件（同屏最多 3 块），底层基于 Kyant `backdrop` 2.0.1：
 * - Android 13+（[GlassRenderTier.REFRACTION_AND_BLUR]）：`vibrancy`/`colorControls` + `blur` + `lens` 边缘折射；
 * - Android 12–12L（[GlassRenderTier.BLUR_ONLY]）：仅 `vibrancy`/`colorControls` + `blur`，严禁调用 `lens()`；
 * - Android 11 及以下或省电/降低透明度/高对比度（[GlassRenderTier.SOLID_DEGRADED]）：不挂载 `drawBackdrop`，
 *   直接绘制 94% 不透明纯色背景与细描边，并通知 [HiGlassState] 释放 `layerBackdrop` 离屏录制开销。
 */
@Composable
fun HiGlassSurface(
    state: HiGlassState,
    modifier: Modifier = Modifier,
    shape: Shape = HiShapeTokens.Full,
    floating: Boolean = true,
    environment: GlassEnvironmentState = LocalGlassEnvironmentState.current ?: rememberGlassEnvironmentState().value,
    style: HiGlassStyle = LocalHiGlassStyle.current,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentAlignment: Alignment = Alignment.Center,
    surfaceColor: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val darkTheme = HiTheme.state.darkTheme
    val renderTier = environment.renderTier
    val styleSpec = remember(colorScheme, darkTheme, renderTier, floating, surfaceColor, style) {
        resolveHiGlassStyleSpec(
            colorScheme = colorScheme,
            darkTheme = darkTheme,
            renderTier = renderTier,
            floating = floating,
            surfaceColor = surfaceColor,
            style = style,
        )
    }
    val backdropConfig = remember(styleSpec, renderTier) {
        styleSpec.toBackdropConfig(renderTier)
    }
    val layerBackdrop = state.layerBackdrop
    val useBackdrop = backdropConfig.backdropEnabled && layerBackdrop != null

    DisposableEffect(state, useBackdrop) {
        if (useBackdrop) {
            state.acquireSourceDemand()
            onDispose { state.releaseSourceDemand() }
        } else {
            onDispose {}
        }
    }

    val surfaceModifier = if (useBackdrop) {
        val backdropShape = remember(shape) { resolveBackdropShape(shape) }
        val canApplyLens = remember(backdropShape) { supportsLensShape(backdropShape) }
        Modifier.drawBackdrop(
            backdrop = layerBackdrop,
            shape = { backdropShape },
            effects = {
                if (backdropConfig.vibrancyEnabled) {
                    vibrancy()
                }
                if (backdropConfig.saturation != 1f) {
                    colorControls(saturation = backdropConfig.saturation)
                }
                if (backdropConfig.blurRadius > 0.dp) {
                    blur(backdropConfig.blurRadius.toPx())
                }
                if (backdropConfig.refractionEnabled && canApplyLens) {
                    try {
                        lens(
                            refractionHeight = backdropConfig.refractionHeight.toPx(),
                            refractionAmount = backdropConfig.refractionAmount.toPx(),
                            depthEffect = backdropConfig.depthEffect,
                            chromaticAberration = backdropConfig.chromaticAberration,
                        )
                    } catch (_: IllegalArgumentException) {
                        // RuntimeShader 在不支持 AGSL eval() 的环境（部分定制 ROM 或离线 SkSL 渲染器）
                        // 会抛出 IllegalArgumentException；回退至同档位的纯模糊 + 活色效果，避免崩溃。
                    }
                }
            },
            highlight = null,
            shadow = null,
            onDrawSurface = {
                drawRect(backdropConfig.tintColor)
            },
        )
    } else {
        Modifier.background(backdropConfig.fallbackColor)
    }

    // Modifier.border 在 drawContent() 之后绘制：链上靠前者最后绘制（位于顶层）。
    // 浅色激活态将 0.75dp 白渐变高光置于前、0.5dp 暗轮廓置于后，确保暗轮廓垫底、白高光居上。
    val borderModifier = if (styleSpec.blurEnabled && styleSpec.borderHighlightColors.size >= 2) {
        val highlightBrush = remember(styleSpec.borderHighlightColors) {
            Brush.linearGradient(
                colors = styleSpec.borderHighlightColors,
                start = Offset.Zero,
                end = Offset.Infinite,
            )
        }
        val highlightModifier = Modifier.border(styleSpec.borderWidth, highlightBrush, shape)
        if (styleSpec.borderOutlineColor.alpha > 0f) {
            highlightModifier.border(HiGlassTokens.DegradedBorderWidth, styleSpec.borderOutlineColor, shape)
        } else {
            highlightModifier
        }
    } else {
        Modifier.border(styleSpec.borderWidth, styleSpec.borderColor, shape)
    }

    val insideGlassRegion = LocalInsideHiGlassRegion.current

    Box(
        modifier = modifier
            .semantics {
                if (!insideGlassRegion) {
                    this[HiGlassMarker] = true
                }
                this[HiGlassBlurActive] = styleSpec.blurEnabled
            }
            .then(
                if (floating) {
                    Modifier.shadow(
                        elevation = styleSpec.shadowElevation,
                        shape = shape,
                        clip = false,
                        ambientColor = styleSpec.shadowColor,
                        spotColor = styleSpec.shadowColor,
                    )
                } else Modifier,
            )
            .clip(shape)
            .then(surfaceModifier)
            .then(borderModifier),
        contentAlignment = contentAlignment,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}
