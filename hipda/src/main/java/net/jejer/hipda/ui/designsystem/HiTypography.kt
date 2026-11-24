package net.jejer.hipda.ui.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/** 导航胶囊、导航轨与底部工具条文字的最大系统缩放倍数（product-design.md §4.1）。 */
const val HI_CHROME_MAX_FONT_SCALE: Float = 1.25f

/** 帖子列表标题行高倍率（product-design.md §5）。 */
const val HI_LIST_TITLE_LINE_HEIGHT_MULTIPLIER: Float = 1.4f

/** 正文与列表标题的 5 档字号（14–18sp，默认第 3 档 16sp，product-design.md §4.1）。 */
enum class HiFontSizeStep(
    val level: Int,
    val baseSp: Float,
) {
    STEP_1(level = 1, baseSp = 14f),
    STEP_2(level = 2, baseSp = 15f),
    STEP_3(level = 3, baseSp = 16f),
    STEP_4(level = 4, baseSp = 17f),
    STEP_5(level = 5, baseSp = 18f);

    val deltaSp: Float
        get() = baseSp - DEFAULT.baseSp

    companion object {
        val DEFAULT: HiFontSizeStep = STEP_3

        fun fromLevel(level: Int): HiFontSizeStep =
            entries.firstOrNull { it.level == level } ?: DEFAULT
    }
}

/** 帖子正文行距三档（1.5 / 1.65 / 1.8，默认标准 1.65，product-design.md §5）。 */
enum class HiLineHeightPreset(val multiplier: Float) {
    COMPACT(1.5f),
    STANDARD(1.65f),
    RELAXED(1.8f);

    companion object {
        val DEFAULT: HiLineHeightPreset = STANDARD
    }
}

/** 面向论坛业务语义的排版令牌，由 [HiTheme] 统一注入。 */
@Immutable
data class HiTypographyTokens(
    val fontSizeStep: HiFontSizeStep,
    val lineHeightPreset: HiLineHeightPreset,
    val threadTitle: TextStyle,
    val threadTitleRead: TextStyle,
    val postBody: TextStyle,
    val materialTypography: Typography,
)

/** 按字号档位和正文行距生成系统字体排版体系与 Material 3 [Typography]。 */
fun hiTypographyTokens(
    fontSizeStep: HiFontSizeStep = HiFontSizeStep.DEFAULT,
    lineHeightPreset: HiLineHeightPreset = HiLineHeightPreset.DEFAULT,
): HiTypographyTokens {
    val base = fontSizeStep.baseSp
    val delta = fontSizeStep.deltaSp
    val threadTitleStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = base.sp,
        lineHeight = (base * HI_LIST_TITLE_LINE_HEIGHT_MULTIPLIER).sp,
    )
    val threadTitleReadStyle = threadTitleStyle.copy(fontWeight = FontWeight.Normal)
    val postBodyStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = base.sp,
        lineHeight = (base * lineHeightPreset.multiplier).sp,
    )
    val typography = Typography(
        headlineMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = (24f + delta).sp,
            lineHeight = ((24f + delta) * 1.3f).sp,
        ),
        headlineSmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = (20f + delta).sp,
            lineHeight = ((20f + delta) * 1.35f).sp,
        ),
        titleLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = (18f + delta).sp,
            lineHeight = ((18f + delta) * 1.35f).sp,
        ),
        titleMedium = threadTitleStyle,
        titleSmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = (14f + delta).sp,
            lineHeight = ((14f + delta) * 1.4f).sp,
        ),
        bodyLarge = postBodyStyle,
        bodyMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = (14f + delta).sp,
            lineHeight = ((14f + delta) * 1.5f).sp,
        ),
        bodySmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Normal,
            fontSize = (12f + delta * 0.5f).sp,
            lineHeight = ((12f + delta * 0.5f) * 1.4f).sp,
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
        labelSmall = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        ),
    )
    return HiTypographyTokens(
        fontSizeStep = fontSizeStep,
        lineHeightPreset = lineHeightPreset,
        threadTitle = threadTitleStyle,
        threadTitleRead = threadTitleReadStyle,
        postBody = postBodyStyle,
        materialTypography = typography,
    )
}

/**
 * 纯函数版本：将单个 [TextStyle] 在给定系统字体缩放下的有效放大倍数限制在 [maxFontScale] 以内。
 * 当系统缩放不超过上限时原样返回。
 */
fun TextStyle.capFontScale(
    currentFontScale: Float,
    maxFontScale: Float = HI_CHROME_MAX_FONT_SCALE,
): TextStyle {
    if (currentFontScale <= maxFontScale || maxFontScale <= 0f) return this
    val factor = maxFontScale / currentFontScale
    return copy(
        fontSize = if (fontSize.isSpecified && fontSize.isSp) (fontSize.value * factor).sp else fontSize,
        lineHeight = if (lineHeight.isSpecified && lineHeight.isSp) (lineHeight.value * factor).sp else lineHeight,
    )
}

/** Composable 扩展：将导航栏或工具条内单个 [TextStyle] 的系统字体放大限制在 1.25 倍以内。 */
@Composable
fun TextStyle.cappedForChrome(maxFontScale: Float = HI_CHROME_MAX_FONT_SCALE): TextStyle =
    capFontScale(currentFontScale = LocalDensity.current.fontScale, maxFontScale = maxFontScale)

/** 将整个导航胶囊、导航轨或底部工具条子树内的字体缩放上限钳制到 [maxFontScale]（默认 1.25 倍）。 */
@Composable
fun HiCappedFontScale(
    maxFontScale: Float = HI_CHROME_MAX_FONT_SCALE,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val cappedScale = density.fontScale.coerceAtMost(maxFontScale)
    if (cappedScale == density.fontScale) {
        content()
    } else {
        CompositionLocalProvider(
            LocalDensity provides Density(density = density.density, fontScale = cappedScale),
            content = content,
        )
    }
}
