package net.jejer.hipda.ui.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** 主题明暗模式，默认跟随系统（product-design.md §5、§7.10）。 */
enum class HiThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** 主题配色种子：Android 12+ 默认跟随壁纸，或使用预设/自定义种子色。 */
@Immutable
sealed interface HiThemeSeed {
    /** 跟随系统壁纸取色；Android 11 及以下或取色不可用时回退至 HiPDA 品牌色。 */
    data object Wallpaper : HiThemeSeed

    /** 指定单个种子色，按统一 Material 3 色阶规则生成整套配色。 */
    @Immutable
    data class ColorSeed(
        val id: String,
        val color: Color,
    ) : HiThemeSeed
}

/** 预设主题色条目，记录旧版主题色出处。 */
@Immutable
data class HiColorPreset(
    val id: String,
    val label: String,
    val color: Color,
    val legacySource: String,
) {
    fun toSeed(): HiThemeSeed.ColorSeed = HiThemeSeed.ColorSeed(id = id, color = color)
}

/**
 * 品牌色与预设主题色常量表。
 *
 * 出处核对：
 * - 品牌色 `#33B6EA`：`hipda/src/main/res/values/colors.xml`（`@color/hipda`、`@color/colorAccent`），
 *   并在 `styles.xml`（`ThemeLight`）与 `values-night/styles.xml`（`ThemeDark`）中作为默认强调色。
 * - 旧版默认浅色主题：`styles.xml`（`ThemeLight` 默认 `@color/md_grey_700`；`ThemeLight.White`
 *   默认 `@color/md_grey_100`，`HiSettingsHelper.LIGHT_THEMES` 首项 `"white"` 使用 `@color/md_grey_200`）。
 * - 旧版深色/纯黑主题：`values-night/styles.xml`（`ThemeDark` `@color/theme_dark` `#2A2A2A`、
 *   `ThemeBlack` `@color/theme_black` `#1A1A1A` + `window_background` `#000000`），对应新版的 `pureBlack` 开关。
 * - 旧版全部彩色与中性预设：`styles.xml`（`ThemeLight.*`）与 `HiSettingsHelper.LIGHT_THEMES`。
 */
object HiColorPresets {
    val Brand = HiColorPreset(
        id = "hipda",
        label = "HiPDA 蓝",
        color = Color(0xFF33B6EA),
        legacySource = "res/values/colors.xml:@color/hipda (@color/colorAccent)",
    )

    val Red = HiColorPreset("red", "赤红", Color(0xFFD32F2F), "res/values/styles.xml:ThemeLight.Red (@color/md_red_700)")
    val Pink = HiColorPreset("pink", "玫红", Color(0xFFC2185B), "res/values/styles.xml:ThemeLight.Pink (@color/md_pink_700)")
    val Purple = HiColorPreset("purple", "紫罗兰", Color(0xFF7B1FA2), "res/values/styles.xml:ThemeLight.Purple (@color/md_purple_700)")
    val DeepPurple = HiColorPreset("deep_purple", "深紫", Color(0xFF512DA8), "res/values/styles.xml:ThemeLight.DeepPurple (@color/md_deep_purple_700)")
    val Indigo = HiColorPreset("indigo", "靛蓝", Color(0xFF303F9F), "res/values/styles.xml:ThemeLight.Indigo (@color/md_indigo_700)")
    val Blue = HiColorPreset("blue", "海蓝", Color(0xFF1976D2), "res/values/styles.xml:ThemeLight.Blue (@color/md_blue_700)")
    val LightBlue = HiColorPreset("light_blue", "天蓝", Color(0xFF0288D1), "res/values/styles.xml:ThemeLight.LightBlue (@color/md_light_blue_700)")
    val Cyan = HiColorPreset("cyan", "青色", Color(0xFF0097A7), "res/values/styles.xml:ThemeLight.Cyan (@color/md_cyan_700)")
    val Teal = HiColorPreset("teal", "蓝绿", Color(0xFF00796B), "res/values/styles.xml:ThemeLight.Teal (@color/md_teal_700)")
    val Green = HiColorPreset("green", "森绿", Color(0xFF388E3C), "res/values/styles.xml:ThemeLight.Green (@color/md_green_700)")
    val LightGreen = HiColorPreset("light_green", "浅绿", Color(0xFF689F38), "res/values/styles.xml:ThemeLight.LightGreen (@color/md_light_green_700)")
    val Lime = HiColorPreset("lime", "青柠", Color(0xFFAFB42B), "res/values/styles.xml:ThemeLight.Lime (@color/md_lime_700)")
    val Yellow = HiColorPreset("yellow", "明黄", Color(0xFFFBC02D), "res/values/styles.xml:ThemeLight.Yellow (@color/md_yellow_700)")
    val Amber = HiColorPreset("amber", "琥珀", Color(0xFFFFA000), "res/values/styles.xml:ThemeLight.Amber (@color/md_amber_700)")
    val Orange = HiColorPreset("orange", "橙色", Color(0xFFF57C00), "res/values/styles.xml:ThemeLight.Orange (@color/md_orange_700)")
    val DeepOrange = HiColorPreset("deep_orange", "深橙", Color(0xFFE64A19), "res/values/styles.xml:ThemeLight.DeepOrange (@color/md_deep_orange_700)")
    val Brown = HiColorPreset("brown", "棕褐", Color(0xFF5D4037), "res/values/styles.xml:ThemeLight.Brown (@color/md_brown_700)")
    val Grey = HiColorPreset("grey", "石墨灰", Color(0xFF616161), "res/values/styles.xml:ThemeLight / ThemeLight.Grey (@color/md_grey_700)")
    val BlueGrey = HiColorPreset("blue_grey", "蓝灰", Color(0xFF455A64), "res/values/styles.xml:ThemeLight.BlueGrey (@color/md_blue_grey_700)")

    val ALL: List<HiColorPreset> = listOf(
        Brand,
        Red, Pink, Purple, DeepPurple, Indigo,
        Blue, LightBlue, Cyan, Teal, Green,
        LightGreen, Lime, Yellow, Amber, Orange,
        DeepOrange, Brown, Grey, BlueGrey,
    )

    fun findById(id: String): HiColorPreset? = ALL.firstOrNull { it.id == id }
}

// Material 3 canonical error palette tokens (independent of seed hue).
private val ErrorLight = Color(0xFFBA1A1A)
private val OnErrorLight = Color(0xFFFFFFFF)
private val ErrorContainerLight = Color(0xFFFFDAD6)
private val OnErrorContainerLight = Color(0xFF410002)
private val ErrorDark = Color(0xFFFFB4AB)
private val OnErrorDark = Color(0xFF690005)
private val ErrorContainerDark = Color(0xFF93000A)
private val OnErrorContainerDark = Color(0xFFFFDAD6)

/**
 * 从单个种子色按 Material 3 Tonal Spot 规则生成整套浅色或深色 [ColorScheme]。
 * 基于 OKLCH 感知均匀色空间与 CIE L* 明度阶梯映射，不依赖旧版 material-components。
 */
fun hiColorSchemeFromSeed(
    seedColor: Color,
    darkTheme: Boolean,
    pureBlack: Boolean = false,
): ColorScheme {
    val (_, seedChroma, seedHue) = seedColor.toOklch()
    val isAchromatic = seedChroma < 0.015f
    val primaryChroma = if (isAchromatic) 0f else seedChroma.coerceIn(0.08f, 0.19f)
    val secondaryChroma = if (isAchromatic) 0f else (primaryChroma * 0.36f).coerceAtMost(0.065f)
    val tertiaryHue = (seedHue + 60f) % 360f
    val tertiaryChroma = if (isAchromatic) 0f else (primaryChroma * 0.60f).coerceIn(0.055f, 0.115f)
    val neutralChroma = if (isAchromatic) 0f else (primaryChroma * 0.085f).coerceAtMost(0.013f)
    val neutralVariantChroma = if (isAchromatic) 0f else (primaryChroma * 0.15f).coerceAtMost(0.024f)

    fun p(tone: Float) = oklchTone(tone, primaryChroma, seedHue)
    fun s(tone: Float) = oklchTone(tone, secondaryChroma, seedHue)
    fun t(tone: Float) = oklchTone(tone, tertiaryChroma, tertiaryHue)
    fun n(tone: Float) = oklchTone(tone, neutralChroma, seedHue)
    fun nv(tone: Float) = oklchTone(tone, neutralVariantChroma, seedHue)

    val base = if (!darkTheme) {
        lightColorScheme(
            primary = p(40f),
            onPrimary = Color.White,
            primaryContainer = p(90f),
            onPrimaryContainer = p(10f),
            inversePrimary = p(80f),
            secondary = s(40f),
            onSecondary = Color.White,
            secondaryContainer = s(90f),
            onSecondaryContainer = s(10f),
            tertiary = t(40f),
            onTertiary = Color.White,
            tertiaryContainer = t(90f),
            onTertiaryContainer = t(10f),
            background = n(98f),
            onBackground = n(10f),
            surface = n(98f),
            onSurface = n(10f),
            surfaceVariant = nv(90f),
            onSurfaceVariant = nv(30f),
            surfaceTint = p(40f),
            inverseSurface = n(20f),
            inverseOnSurface = n(95f),
            error = ErrorLight,
            onError = OnErrorLight,
            errorContainer = ErrorContainerLight,
            onErrorContainer = OnErrorContainerLight,
            outline = nv(50f),
            outlineVariant = nv(80f),
            scrim = Color.Black,
            surfaceBright = n(98f),
            surfaceDim = n(87f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = n(96f),
            surfaceContainer = n(94f),
            surfaceContainerHigh = n(92f),
            surfaceContainerHighest = n(90f),
        )
    } else {
        darkColorScheme(
            primary = p(80f),
            onPrimary = p(20f),
            primaryContainer = p(30f),
            onPrimaryContainer = p(90f),
            inversePrimary = p(40f),
            secondary = s(80f),
            onSecondary = s(20f),
            secondaryContainer = s(30f),
            onSecondaryContainer = s(90f),
            tertiary = t(80f),
            onTertiary = t(20f),
            tertiaryContainer = t(30f),
            onTertiaryContainer = t(90f),
            background = n(6f),
            onBackground = n(90f),
            surface = n(6f),
            onSurface = n(90f),
            surfaceVariant = nv(30f),
            onSurfaceVariant = nv(80f),
            surfaceTint = p(80f),
            inverseSurface = n(90f),
            inverseOnSurface = n(20f),
            error = ErrorDark,
            onError = OnErrorDark,
            errorContainer = ErrorContainerDark,
            onErrorContainer = OnErrorContainerDark,
            outline = nv(61f),
            outlineVariant = nv(30f),
            scrim = Color.Black,
            surfaceBright = n(24f),
            surfaceDim = n(6f),
            surfaceContainerLowest = n(4f),
            surfaceContainerLow = n(10f),
            surfaceContainer = n(12f),
            surfaceContainerHigh = n(17f),
            surfaceContainerHighest = n(22f),
        )
    }

    return if (darkTheme && pureBlack) base.toPureBlack() else base
}

/** 将任意深色 [ColorScheme]（含动态壁纸取色）映射为 OLED 纯黑表面层次。 */
fun ColorScheme.toPureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = surfaceContainerLow.atOklchTone(4f),
    surfaceContainer = surfaceContainer.atOklchTone(8f),
    surfaceContainerHigh = surfaceContainerHigh.atOklchTone(12f),
    surfaceContainerHighest = surfaceContainerHighest.atOklchTone(16f),
    surfaceBright = surfaceBright.atOklchTone(18f),
    surfaceVariant = surfaceVariant.atOklchTone(18f),
    outlineVariant = outlineVariant.atOklchTone(20f),
    scrim = Color.Black,
)

private fun Color.atOklchTone(tone: Float): Color {
    val (_, c, h) = toOklch()
    return oklchTone(tone = tone, targetChroma = c, hueDegrees = h)
}

private data class Oklch(val l: Float, val c: Float, val h: Float)

private fun Color.toOklch(): Oklch {
    val r = srgbToLinear(red)
    val g = srgbToLinear(green)
    val b = srgbToLinear(blue)
    val l = cbrt((0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b).toDouble()).toFloat()
    val m = cbrt((0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b).toDouble()).toFloat()
    val s = cbrt((0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b).toDouble()).toFloat()
    val okL = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
    val okA = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
    val okB = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    val chroma = hypot(okA, okB)
    val hue = ((Math.toDegrees(atan2(okB.toDouble(), okA.toDouble())).toFloat()) + 360f) % 360f
    return Oklch(okL, chroma, hue)
}

private fun oklchTone(tone: Float, targetChroma: Float, hueDegrees: Float): Color {
    if (tone <= 0f) return Color.Black
    if (tone >= 100f) return Color.White
    val targetLuminance = if (tone <= 8f) tone / 903.2963f else ((tone + 16f) / 116f).pow(3)
    if (targetChroma <= 0f) {
        val g = linearToSrgb(targetLuminance)
        return Color(g, g, g, 1f)
    }

    val rad = Math.toRadians(hueDegrees.toDouble())
    val cosH = cos(rad).toFloat()
    val sinH = sin(rad).toFloat()

    // OKLab 的 L 与相对亮度 Y 在高彩度下存在轻微偏移；按目标 Y 对 okL 做二分微调，
    // 确保所有色相在 sRGB 色域裁剪后的输出亮度精确匹配目标 CIE L*。
    var lowL = 0f
    var highL = 1f
    repeat(20) {
        val midL = (lowL + highL) * 0.5f
        val chroma = clampChromaToSrgb(midL, targetChroma, cosH, sinH)
        val (r, g, b) = oklabToLinearRgb(midL, chroma * cosH, chroma * sinH)
        val luminance = linearRelativeLuminance(r, g, b)
        if (luminance < targetLuminance) lowL = midL else highL = midL
    }

    val calibratedL = (lowL + highL) * 0.5f
    val finalChroma = clampChromaToSrgb(calibratedL, targetChroma, cosH, sinH)
    val (r, g, b) = oklabToLinearRgb(calibratedL, finalChroma * cosH, finalChroma * sinH)
    return Color(linearToSrgb(r), linearToSrgb(g), linearToSrgb(b), 1f)
}

private fun clampChromaToSrgb(okL: Float, targetChroma: Float, cosH: Float, sinH: Float): Float {
    if (isInSrgbGamut(okL, targetChroma * cosH, targetChroma * sinH)) return targetChroma
    var low = 0f
    var high = targetChroma
    repeat(16) {
        val mid = (low + high) * 0.5f
        if (isInSrgbGamut(okL, mid * cosH, mid * sinH)) low = mid else high = mid
    }
    return low
}

private fun linearRelativeLuminance(r: Float, g: Float, b: Float): Float =
    0.2126f * r.coerceIn(0f, 1f) + 0.7152f * g.coerceIn(0f, 1f) + 0.0722f * b.coerceIn(0f, 1f)

private fun isInSrgbGamut(okL: Float, okA: Float, okB: Float): Boolean {
    val (r, g, b) = oklabToLinearRgb(okL, okA, okB)
    return r in 0f..1f && g in 0f..1f && b in 0f..1f
}

private fun oklabToLinearRgb(okL: Float, okA: Float, okB: Float): Triple<Float, Float, Float> {
    val lCbrt = okL + 0.3963377774f * okA + 0.2158037573f * okB
    val mCbrt = okL - 0.1055613458f * okA - 0.0638541728f * okB
    val sCbrt = okL - 0.0894841775f * okA - 1.2914855480f * okB
    val l = lCbrt * lCbrt * lCbrt
    val m = mCbrt * mCbrt * mCbrt
    val s = sCbrt * sCbrt * sCbrt
    return Triple(
        +4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
        -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
        -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s,
    )
}

private fun srgbToLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

private fun linearToSrgb(c: Float): Float {
    val clamped = c.coerceIn(0f, 1f)
    return if (clamped <= 0.0031308f) clamped * 12.92f else 1.055f * clamped.pow(1f / 2.4f) - 0.055f
}
