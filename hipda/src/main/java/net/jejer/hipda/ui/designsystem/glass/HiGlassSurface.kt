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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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

/** 毛玻璃表面视觉令牌，与 `product-design.md §5` 严格对齐。 */
@Immutable
object HiGlassTokens {
    val BlurRadius: Dp = 24.dp
    const val NoiseFactor: Float = 0f
    const val LightBlurTintAlpha: Float = 0.70f
    const val DarkBlurTintAlpha: Float = 0.60f
    const val DegradedSurfaceAlpha: Float = 0.94f
    val BorderWidth: Dp = 0.5.dp
    const val LightHighlightBorderAlpha: Float = 0.40f
    const val DarkHighlightBorderAlpha: Float = 0.12f
    val FloatingShadowElevation: Dp = 4.dp
    const val LightShadowAlpha: Float = 0.12f
    const val DarkShadowAlpha: Float = 0.28f
    const val MaxGlassSurfacesOnScreen: Int = 2
}

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
    val surfaceColor: Color,
    val activeTintColor: Color,
    val degradedSurfaceColor: Color,
    val borderWidth: Dp,
    val borderColor: Color,
    val shadowElevation: Dp,
    val shadowColor: Color,
)

/** 纯函数解析毛玻璃视觉规格：激活态 24dp 模糊 + 70%/60% 着色 + 0.5dp 白高光；降级态 94% 纯色 + `outlineVariant` 细描边。 */
fun resolveHiGlassStyleSpec(
    colorScheme: ColorScheme,
    darkTheme: Boolean,
    blurEnabled: Boolean,
    floating: Boolean = true,
    surfaceColor: Color = colorScheme.surface,
): HiGlassStyleSpec {
    val tintAlpha = if (darkTheme) HiGlassTokens.DarkBlurTintAlpha else HiGlassTokens.LightBlurTintAlpha
    val borderColor = if (blurEnabled) {
        Color.White.copy(alpha = if (darkTheme) HiGlassTokens.DarkHighlightBorderAlpha else HiGlassTokens.LightHighlightBorderAlpha)
    } else {
        colorScheme.outlineVariant
    }
    val shadowAlpha = if (darkTheme) HiGlassTokens.DarkShadowAlpha else HiGlassTokens.LightShadowAlpha
    return HiGlassStyleSpec(
        blurEnabled = blurEnabled,
        blurRadius = HiGlassTokens.BlurRadius,
        surfaceColor = surfaceColor,
        activeTintColor = surfaceColor.copy(alpha = tintAlpha),
        degradedSurfaceColor = surfaceColor.copy(alpha = HiGlassTokens.DegradedSurfaceAlpha),
        borderWidth = HiGlassTokens.BorderWidth,
        borderColor = borderColor,
        shadowElevation = if (floating) HiGlassTokens.FloatingShadowElevation else 0.dp,
        shadowColor = Color.Black.copy(alpha = shadowAlpha),
    )
}

/**
 * 毛玻璃表面组件，仅用于底部导航胶囊与帖子详情底部工具条（同屏最多 2 块）。
 *
 * 激活态挂载 Haze 2.x 实时模糊；降级态不挂载 `hazeBlur`，直接绘制 94% 不透明纯色背景与细描边，
 * 使底层 `HazeSourceNode` 在省电或降级模式下释放离屏 `GraphicsLayer`、不再每帧录制背景。
 */
@Composable
fun HiGlassSurface(
    state: HiGlassState,
    modifier: Modifier = Modifier,
    shape: Shape = HiShapeTokens.Full,
    floating: Boolean = true,
    environment: GlassEnvironmentState = LocalGlassEnvironmentState.current ?: rememberGlassEnvironmentState().value,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentAlignment: Alignment = Alignment.Center,
    surfaceColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable BoxScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val darkTheme = HiTheme.state.darkTheme
    val blurEnabled = environment.blurEnabled
    val styleSpec = remember(colorScheme, darkTheme, blurEnabled, floating, surfaceColor) {
        resolveHiGlassStyleSpec(colorScheme, darkTheme, blurEnabled, floating, surfaceColor)
    }
    val surfaceModifier = if (styleSpec.blurEnabled) {
        val hazeStyle = remember(styleSpec) {
            HazeBlurStyle {
                blurEnabled(true)
                blurRadius(styleSpec.blurRadius)
                noiseFactor(HiGlassTokens.NoiseFactor)
                backgroundColor(styleSpec.surfaceColor)
                colorEffects(listOf(HazeColorEffect.tint(styleSpec.activeTintColor)))
                fallbackColorEffect(HazeColorEffect.tint(styleSpec.degradedSurfaceColor))
            }
        }
        val hazeInput = remember(state) { HazeInput.Sources(state.hazeState) }
        Modifier.hazeBlur(input = hazeInput, style = hazeStyle)
    } else {
        Modifier.background(styleSpec.degradedSurfaceColor)
    }
    val borderModifier = Modifier.border(styleSpec.borderWidth, styleSpec.borderColor, shape)

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
