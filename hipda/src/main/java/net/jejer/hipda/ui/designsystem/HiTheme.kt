package net.jejer.hipda.ui.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.LocalHiMotionDurationScale
import net.jejer.hipda.ui.designsystem.motion.ProvideHiMotionDurationScale

/** 圆角 4 档令牌：小 8dp、中 12dp、大 20dp、全圆角（product-design.md §5）。 */
@Immutable
object HiShapeTokens {
    val Small: RoundedCornerShape = RoundedCornerShape(8.dp)
    val Medium: RoundedCornerShape = RoundedCornerShape(12.dp)
    val Large: RoundedCornerShape = RoundedCornerShape(20.dp)
    val Full: Shape = CircleShape
}

/** 映射到 Material 3 的形状表（extraLarge 保持 20dp，保证底部面板与对话框圆角统一为大 20dp）。 */
val HiShapes: Shapes = Shapes(
    extraSmall = HiShapeTokens.Small,
    small = HiShapeTokens.Small,
    medium = HiShapeTokens.Medium,
    large = HiShapeTokens.Large,
    extraLarge = HiShapeTokens.Large,
)

/** 当前生效的主题解析状态，供组件与降级分支读取。 */
@Immutable
data class HiThemeState(
    val themeMode: HiThemeMode,
    val darkTheme: Boolean,
    val pureBlack: Boolean,
    val seed: HiThemeSeed,
)

val LocalHiTypographyTokens = staticCompositionLocalOf { hiTypographyTokens() }

val LocalHiThemeState = staticCompositionLocalOf {
    HiThemeState(
        themeMode = HiThemeMode.SYSTEM,
        darkTheme = false,
        pureBlack = false,
        seed = HiThemeSeed.Wallpaper,
    )
}

/** 便捷访问器，供后续设计系统组件和页面调用。 */
object HiTheme {
    val typography: HiTypographyTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalHiTypographyTokens.current

    val state: HiThemeState
        @Composable
        @ReadOnlyComposable
        get() = LocalHiThemeState.current

    val shapes: HiShapeTokens = HiShapeTokens

    val motionDurationScale: MotionDurationScale
        @Composable
        @ReadOnlyComposable
        get() = LocalHiMotionDurationScale.current

    val isReducedMotion: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalHiMotionDurationScale.current.scaleFactor == 0f
}

/**
 * HiPDA Next 统一主题入口。
 *
 * - Android 12+ 且 [seed] 为 [HiThemeSeed.Wallpaper] 时，使用系统动态配色；否则按 [seed] 生成 M3 配色。
 * - [pureBlack] 仅在深色模式下生效，将 `background` 与 `surface` 设为纯黑 `#000000` 并压暗其余容器色。
 * - 根部读取并监听系统 `Settings.Global.ANIMATOR_DURATION_SCALE`，通过 [LocalHiMotionDurationScale] 暴露。
 */
@Composable
fun HiTheme(
    themeMode: HiThemeMode = HiThemeMode.SYSTEM,
    pureBlack: Boolean = false,
    seed: HiThemeSeed = HiThemeSeed.Wallpaper,
    fontSizeStep: HiFontSizeStep = HiFontSizeStep.DEFAULT,
    lineHeightPreset: HiLineHeightPreset = HiLineHeightPreset.DEFAULT,
    motionDurationScale: MotionDurationScale? = null,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        HiThemeMode.SYSTEM -> isSystemInDarkTheme()
        HiThemeMode.LIGHT -> false
        HiThemeMode.DARK -> true
    }
    val colorScheme: ColorScheme = if (seed is HiThemeSeed.Wallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        remember(darkTheme, pureBlack, context, configuration) {
            val base = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (darkTheme && pureBlack) base.toPureBlack() else base
        }
    } else {
        remember(seed, darkTheme, pureBlack) {
            val resolvedSeed = when (seed) {
                HiThemeSeed.Wallpaper -> HiColorPresets.Brand.color
                is HiThemeSeed.ColorSeed -> seed.color
            }
            hiColorSchemeFromSeed(
                seedColor = resolvedSeed,
                darkTheme = darkTheme,
                pureBlack = pureBlack,
            )
        }
    }
    val typographyTokens = remember(fontSizeStep, lineHeightPreset) {
        hiTypographyTokens(fontSizeStep = fontSizeStep, lineHeightPreset = lineHeightPreset)
    }
    val themeState = remember(themeMode, darkTheme, pureBlack, seed) {
        HiThemeState(
            themeMode = themeMode,
            darkTheme = darkTheme,
            pureBlack = darkTheme && pureBlack,
            seed = seed,
        )
    }

    ProvideHiMotionDurationScale(motionDurationScale = motionDurationScale) {
        CompositionLocalProvider(
            LocalHiTypographyTokens provides typographyTokens,
            LocalHiThemeState provides themeState,
        ) {
            MaterialTheme(
                colorScheme = colorScheme,
                motionScheme = HiMotion.motionScheme,
                typography = typographyTokens.materialTypography,
                shapes = HiShapes,
                content = content,
            )
        }
    }
}
