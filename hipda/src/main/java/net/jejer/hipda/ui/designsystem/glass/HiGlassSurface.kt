package net.jejer.hipda.ui.designsystem.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme

/** 毛玻璃表面视觉令牌，含导航胶囊默认档与帖子详情工具条预留档。 */
@Immutable
object HiGlassTokens {
    // 导航胶囊默认档（轻透）
    val BlurRadius: Dp = 10.dp
    const val NoiseFactor: Float = 0.04f
    const val LightBlurTintAlpha: Float = 0.45f
    const val DarkBlurTintAlpha: Float = 0.46f
    const val BlurSaturation: Float = 1.18f

    // 帖子详情工具条预留档（护读）
    val ToolbarBlurRadius: Dp = 16.dp
    const val ToolbarLightBlurTintAlpha: Float = 0.60f
    const val ToolbarDarkBlurTintAlpha: Float = 0.68f
    const val ToolbarBlurSaturation: Float = 1.08f

    // 降级态（无模糊）
    const val DegradedSurfaceAlpha: Float = 0.94f

    // 描边：激活态为左上→右下渐变高光，浅色叠加极淡轮廓暗线；降级态为 outlineVariant 细线
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

    const val MaxGlassSurfacesOnScreen: Int = 2
}

/**
 * 毛玻璃主题风格配置，由 [HiTheme] 通过 [LocalHiGlassStyle] 提供默认值。
 *
 * 生产组件只读取 [LocalHiGlassStyle] 或显式传入预置档（如 [Toolbar]）。
 */
@Immutable
data class HiGlassStyle(
    val blurRadius: Dp = HiGlassTokens.BlurRadius,
    val lightTintAlpha: Float = HiGlassTokens.LightBlurTintAlpha,
    val darkTintAlpha: Float = HiGlassTokens.DarkBlurTintAlpha,
    val saturation: Float = HiGlassTokens.BlurSaturation,
    val noiseFactor: Float = HiGlassTokens.NoiseFactor,
    val borderWidth: Dp = HiGlassTokens.BorderWidth,
    val borderHighlightStrength: Float = HiGlassTokens.DefaultBorderHighlightStrength,
    val indicatorAlpha: Float = HiGlassTokens.IndicatorAlpha,
    val floatingShadowElevation: Dp = HiGlassTokens.FloatingShadowElevation,
    val lightShadowAlpha: Float = HiGlassTokens.LightShadowAlpha,
    val darkShadowAlpha: Float = HiGlassTokens.DarkShadowAlpha,
) {
    companion object {
        /** 导航胶囊默认档：10dp 模糊、0.45/0.46 着色、1.18 饱和度、0.04 噪点。 */
        val Navigation: HiGlassStyle = HiGlassStyle()

        /** 帖子详情底部工具条预留档：16dp 模糊、0.60/0.68 着色、1.08 饱和度。 */
        val Toolbar: HiGlassStyle = HiGlassStyle(
            blurRadius = HiGlassTokens.ToolbarBlurRadius,
            lightTintAlpha = HiGlassTokens.ToolbarLightBlurTintAlpha,
            darkTintAlpha = HiGlassTokens.ToolbarDarkBlurTintAlpha,
            saturation = HiGlassTokens.ToolbarBlurSaturation,
        )
    }
}

/** 当前主题生效的毛玻璃风格令牌；使用 [compositionLocalOf] 以在动态变更时仅重组读取方。 */
val LocalHiGlassStyle = compositionLocalOf { HiGlassStyle.Navigation }

/** 标记一个 [HiGlassSurface] 节点，供测试断言“同屏同时最多 2 块毛玻璃”（product-design.md §10.3）。 */
val HiGlassMarker: SemanticsPropertyKey<Boolean> = SemanticsPropertyKey(
    name = "HiGlassMarker",
    mergePolicy = { parentValue, childValue -> parentValue ?: childValue },
)

/** 标记该 [HiGlassSurface] 当前处于实时模糊态（`true`）或 94% 纯色降级态（`false`）。 */
val HiGlassBlurActive: SemanticsPropertyKey<Boolean> = SemanticsPropertyKey(
    name = "HiGlassBlurActive",
    mergePolicy = { parentValue, childValue -> parentValue ?: childValue },
)

/** 毛玻璃内容源与浮层之间的协调状态；封装底层 Haze 2.x 的 [HazeState]。 */
@Stable
class HiGlassState internal constructor(
    internal val hazeState: HazeState,
) {
    constructor() : this(HazeState())
}

@Composable
fun rememberHiGlassState(): HiGlassState {
    val hazeState = rememberHazeState()
    return remember(hazeState) { HiGlassState(hazeState) }
}

/** 标记会被毛玻璃采样的内容区（如滚动列表与正文内容区）。 */
fun Modifier.hiGlassSource(
    state: HiGlassState,
    zIndex: Float = 0f,
    key: Any? = null,
): Modifier = hazeSource(state = state.hazeState, zIndex = zIndex, key = key)

/** 解析后的毛玻璃视觉规格，供 [HiGlassSurface] 渲染与单元测试核验参数及对比度。 */
@Immutable
data class HiGlassStyleSpec(
    val blurEnabled: Boolean,
    val blurRadius: Dp,
    val noiseFactor: Float,
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
 * 纯函数解析毛玻璃视觉规格：
 * - 激活态底色默认浅色取 `surfaceContainerLowest`、深色取 `surfaceContainer`，配左上→右下渐变高光描边；
 * - 降级态默认保留 `surface`（94% 不透明）+ `outlineVariant` 细描边，守住 WCAG 4.5:1 对比度；
 * - 显式传入非 [Color.Unspecified] 的 [surfaceColor] 时，激活态与降级态均尊重该底色。
 */
fun resolveHiGlassStyleSpec(
    colorScheme: ColorScheme,
    darkTheme: Boolean,
    blurEnabled: Boolean,
    floating: Boolean = true,
    surfaceColor: Color = Color.Unspecified,
    style: HiGlassStyle = HiGlassStyle.Navigation,
): HiGlassStyleSpec {
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
        blurEnabled = blurEnabled,
        blurRadius = style.blurRadius.coerceAtLeast(0.dp),
        noiseFactor = style.noiseFactor.coerceIn(0f, 1f),
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

/** 传给 Haze 2.x [HazeBlurStyle] 的结构化配置对象；生产渲染与单元测试共用同一映射路径。 */
@Immutable
data class HiGlassHazeConfig(
    val blurEnabled: Boolean,
    val blurRadius: Dp,
    val noiseFactor: Float,
    val backgroundColor: Color,
    val colorEffects: List<HazeColorEffect>,
    val fallbackColorEffect: HazeColorEffect,
) {
    /** 将当前配置转为 Haze 2.x [HazeBlurStyle]。 */
    fun toHazeBlurStyle(): HazeBlurStyle = HazeBlurStyle {
        blurEnabled(this@HiGlassHazeConfig.blurEnabled)
        blurRadius(this@HiGlassHazeConfig.blurRadius)
        noiseFactor(this@HiGlassHazeConfig.noiseFactor)
        backgroundColor(this@HiGlassHazeConfig.backgroundColor)
        colorEffects(this@HiGlassHazeConfig.colorEffects)
        fallbackColorEffect(this@HiGlassHazeConfig.fallbackColorEffect)
    }
}

/** 将 [HiGlassStyleSpec] 解析为传给 Haze 2.x 的配置对象：先串饱和度滤镜，再叠中性着色。 */
fun HiGlassStyleSpec.toHazeConfig(): HiGlassHazeConfig = HiGlassHazeConfig(
    blurEnabled = blurEnabled,
    blurRadius = blurRadius,
    noiseFactor = noiseFactor,
    backgroundColor = surfaceColor,
    colorEffects = buildList {
        if (saturation != 1f) {
            add(
                HazeColorEffect.colorFilter(
                    ColorFilter.colorMatrix(
                        ColorMatrix().apply { setToSaturation(saturation) },
                    ),
                ),
            )
        }
        add(HazeColorEffect.tint(activeTintColor))
    },
    fallbackColorEffect = HazeColorEffect.tint(degradedSurfaceColor),
)

/** 将 [HiGlassStyleSpec] 转为 Haze 2.x 的 [HazeBlurStyle]。 */
fun HiGlassStyleSpec.toHazeBlurStyle(): HazeBlurStyle = toHazeConfig().toHazeBlurStyle()
/**
 * 毛玻璃表面组件，仅用于底部导航胶囊与帖子详情底部工具条（同屏最多 2 块）。
 *
 * 激活态挂载 Haze 2.x 实时模糊（饱和度提升 + 中性着色 + 噪点 + 左上→右下渐变高光描边）；
 * 降级态不挂载 `hazeBlur`，直接绘制 94% 不透明纯色背景与细描边，使底层 `HazeSourceNode`
 * 释放离屏 `GraphicsLayer`、不再每帧录制背景。
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
    val blurEnabled = environment.blurEnabled
    val styleSpec = remember(colorScheme, darkTheme, blurEnabled, floating, surfaceColor, style) {
        resolveHiGlassStyleSpec(
            colorScheme = colorScheme,
            darkTheme = darkTheme,
            blurEnabled = blurEnabled,
            floating = floating,
            surfaceColor = surfaceColor,
            style = style,
        )
    }
    val surfaceModifier = if (styleSpec.blurEnabled) {
        val hazeStyle = remember(styleSpec) { styleSpec.toHazeBlurStyle() }
        val hazeInput = remember(state) { HazeInput.Sources(state.hazeState) }
        Modifier.hazeBlur(input = hazeInput, style = hazeStyle)
    } else {
        Modifier.background(styleSpec.degradedSurfaceColor)
    }
    val borderModifier = if (styleSpec.blurEnabled && styleSpec.borderHighlightColors.size >= 2) {
        val highlightBrush = remember(styleSpec.borderHighlightColors) {
            Brush.linearGradient(
                colors = styleSpec.borderHighlightColors,
                start = Offset.Zero,
                end = Offset.Infinite,
            )
        }
        val outlineModifier = if (styleSpec.borderOutlineColor.alpha > 0f) {
            Modifier.border(HiGlassTokens.DegradedBorderWidth, styleSpec.borderOutlineColor, shape)
        } else {
            Modifier
        }
        outlineModifier.border(styleSpec.borderWidth, highlightBrush, shape)
    } else {
        Modifier.border(styleSpec.borderWidth, styleSpec.borderColor, shape)
    }

    Box(
        modifier = modifier
            .semantics {
                this[HiGlassMarker] = true
                this[HiGlassBlurActive] = blurEnabled
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
