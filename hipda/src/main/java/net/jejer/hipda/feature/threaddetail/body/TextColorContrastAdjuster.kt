package net.jejer.hipda.feature.threaddetail.body

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 帖子正文渲染期动态文字颜色对比度校准（tech-plan §5 `T6`、B4-E2a ②）。
 *
 * 原本已满足 WCAG 2.1 `≥ 4.5:1` 的颜色零失真原样返回；不可读颜色在 OKLCH 空间保持色相并二分校准明度与 sRGB 合法色度。
 */
object TextColorContrastAdjuster {

    const val MIN_CONTRAST_RATIO: Float = 4.5f
    private const val SEARCH_SAFETY_MARGIN: Float = 0.08f
    private const val ACHROMATIC_CHROMA_EPSILON: Float = 0.002f
    private val RGB_FUNC_REGEX = Regex("""^rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*[\d.]+)?\s*\)$""")

    fun wcagContrastRatio(first: Color, second: Color): Float {
        val l1 = first.luminance()
        val l2 = second.luminance()
        return (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)
    }

    /** 解析 Discuz `<font color="...">` 的十六进制、`rgb(...)` 与常用 CSS 颜色名；无效输入返回 `null`。 */
    fun parseCssColor(rawColor: String?): Color? {
        val norm = rawColor?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        NAMED_CSS_COLORS[norm]?.let { return Color(it) }
        if (norm.startsWith("#")) return parseHexColor(norm.substring(1))
        val m = RGB_FUNC_REGEX.matchEntire(norm) ?: return null
        val r = m.groupValues[1].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val g = m.groupValues[2].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val b = m.groupValues[3].toIntOrNull()?.coerceIn(0, 255) ?: return null
        return Color(r, g, b)
    }

    /** 解析 [rawColor] 并针对 [background] 校准为可读文字色；无法解析时回退 [fallback]。 */
    fun resolveCssTextColor(
        rawColor: String?,
        background: Color,
        fallback: Color,
        minContrastRatio: Float = MIN_CONTRAST_RATIO,
    ): Color = parseCssColor(rawColor)?.let { adjustForBackground(it, background, minContrastRatio) } ?: fallback

    /** 针对当前主题背景（浅色 / 深色 / OLED 纯黑）动态校准 [foreground]，保证 8-bit sRGB 输出满足 [minContrastRatio]。 */
    fun adjustForBackground(
        foreground: Color,
        background: Color,
        minContrastRatio: Float = MIN_CONTRAST_RATIO,
    ): Color {
        val fg = foreground.copy(alpha = 1f)
        val bg = background.copy(alpha = 1f)
        if (wcagContrastRatio(fg, bg) >= minContrastRatio) return fg

        val blackRatio = wcagContrastRatio(Color.Black, bg)
        val whiteRatio = wcagContrastRatio(Color.White, bg)
        val darken = when {
            blackRatio >= minContrastRatio && whiteRatio < minContrastRatio -> true
            whiteRatio >= minContrastRatio && blackRatio < minContrastRatio -> false
            else -> blackRatio >= whiteRatio
        }

        val targetRatio = minContrastRatio + SEARCH_SAFETY_MARGIN
        val (fgL, fgC, fgH) = fg.toOklch()
        val chroma = if (fgC < ACHROMATIC_CHROMA_EPSILON) 0f else fgC
        val rad = Math.toRadians(fgH.toDouble())
        val cosH = cos(rad).toFloat()
        val sinH = sin(rad).toFloat()

        var lowL = if (darken) 0f else fgL
        var highL = if (darken) fgL else 1f
        repeat(24) {
            val midL = (lowL + highL) * 0.5f
            val readable = wcagContrastRatio(colorAtOklch(midL, chroma, cosH, sinH), bg) >= targetRatio
            if (darken) {
                if (readable) lowL = midL else highL = midL
            } else {
                if (readable) highL = midL else lowL = midL
            }
        }
        val calibrated = colorAtOklch(if (darken) lowL else highL, chroma, cosH, sinH)
        return if (wcagContrastRatio(calibrated, bg) >= minContrastRatio) calibrated else if (darken) Color.Black else Color.White
    }

    private fun parseHexColor(hex: String): Color? = when (hex.length) {
        3 -> {
            val r = hex[0].digitToIntOrNull(16) ?: return null
            val g = hex[1].digitToIntOrNull(16) ?: return null
            val b = hex[2].digitToIntOrNull(16) ?: return null
            Color(r * 17, g * 17, b * 17)
        }
        4 -> parseHexColor("${hex}00")
        6 -> hex.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
        8 -> hex.toLongOrNull(16)?.let { Color(0xFF000000L or (it and 0x00FFFFFFL)) }
        else -> null
    }

    private fun Color.toOklch(): Triple<Float, Float, Float> {
        val r = srgbToLinear(red)
        val g = srgbToLinear(green)
        val b = srgbToLinear(blue)
        val l = cbrt((0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b).toDouble()).toFloat()
        val m = cbrt((0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b).toDouble()).toFloat()
        val s = cbrt((0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b).toDouble()).toFloat()
        val okL = (0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s).coerceIn(0f, 1f)
        val okA = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        val okB = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
        return Triple(okL, hypot(okA, okB), ((Math.toDegrees(atan2(okB.toDouble(), okA.toDouble())).toFloat()) + 360f) % 360f)
    }

    private fun colorAtOklch(okL: Float, targetChroma: Float, cosH: Float, sinH: Float): Color {
        if (okL <= 0f) return Color.Black
        if (okL >= 1f) return Color.White
        if (targetChroma <= 0f) {
            val ch = quantizeSrgb(linearToSrgb(okL * okL * okL))
            return Color(ch, ch, ch, 1f)
        }
        val c = clampChromaToSrgb(okL, targetChroma, cosH, sinH)
        val (r, g, b) = oklabToLinearRgb(okL, c * cosH, c * sinH)
        return Color(quantizeSrgb(linearToSrgb(r)), quantizeSrgb(linearToSrgb(g)), quantizeSrgb(linearToSrgb(b)), 1f)
    }

    private fun clampChromaToSrgb(okL: Float, targetChroma: Float, cosH: Float, sinH: Float): Float {
        if (inSrgbGamut(okL, targetChroma * cosH, targetChroma * sinH)) return targetChroma
        var low = 0f
        var high = targetChroma
        repeat(14) {
            val mid = (low + high) * 0.5f
            if (inSrgbGamut(okL, mid * cosH, mid * sinH)) low = mid else high = mid
        }
        return low
    }

    private fun inSrgbGamut(okL: Float, okA: Float, okB: Float): Boolean {
        val (r, g, b) = oklabToLinearRgb(okL, okA, okB)
        return r in 0f..1f && g in 0f..1f && b in 0f..1f
    }

    private fun oklabToLinearRgb(okL: Float, okA: Float, okB: Float): Triple<Float, Float, Float> {
        val l = (okL + 0.3963377774f * okA + 0.2158037573f * okB).let { it * it * it }
        val m = (okL - 0.1055613458f * okA - 0.0638541728f * okB).let { it * it * it }
        val s = (okL - 0.0894841775f * okA - 1.2914855480f * okB).let { it * it * it }
        return Triple(
            +4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
            -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
            -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s,
        )
    }

    private fun srgbToLinear(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    private fun linearToSrgb(c: Float): Float = c.coerceIn(0f, 1f).let { if (it <= 0.0031308f) it * 12.92f else 1.055f * it.pow(1f / 2.4f) - 0.055f }
    private fun quantizeSrgb(c: Float): Float = (c.coerceIn(0f, 1f) * 255f).roundToInt() / 255f

    private val NAMED_CSS_COLORS: Map<String, Long> = mapOf(
        "aqua" to 0xFF00FFFFL, "black" to 0xFF000000L, "blue" to 0xFF0000FFL, "brown" to 0xFFA52A2AL,
        "cyan" to 0xFF00FFFFL, "darkblue" to 0xFF00008BL, "darkgray" to 0xFFA9A9A9L, "darkgreen" to 0xFF006400L,
        "darkgrey" to 0xFFA9A9A9L, "darkorange" to 0xFFFF8C00L, "darkorchid" to 0xFF9932CCL, "darkred" to 0xFF8B0000L,
        "darkslateblue" to 0xFF483D8BL, "darkslategray" to 0xFF2F4F4FL, "deepskyblue" to 0xFF00BFFFL, "dimgray" to 0xFF696969L,
        "fuchsia" to 0xFFFF00FFL, "gold" to 0xFFFFD700L, "gray" to 0xFF808080L, "green" to 0xFF008000L,
        "grey" to 0xFF808080L, "indigo" to 0xFF4B0082L, "khaki" to 0xFFF0E68CL, "lemonchiffon" to 0xFFFFFACDL,
        "lightblue" to 0xFFADD8E6L, "lightgray" to 0xFFD3D3D3L, "lime" to 0xFF00FF00L, "magenta" to 0xFFFF00FFL,
        "maroon" to 0xFF800000L, "mediumturquoise" to 0xFF48D1CCL, "navy" to 0xFF000080L, "olive" to 0xFF808000L,
        "orange" to 0xFFFFA500L, "palegreen" to 0xFF98FB98L, "pink" to 0xFFFFC0CBL, "plum" to 0xFFDDA0DDL,
        "purple" to 0xFF800080L, "red" to 0xFFFF0000L, "royalblue" to 0xFF4169E1L, "sandybrown" to 0xFFF4A460L,
        "seagreen" to 0xFF2E8B57L, "sienna" to 0xFFA0522DL, "silver" to 0xFFC0C0C0L, "slategray" to 0xFF708090L,
        "teal" to 0xFF008080L, "wheat" to 0xFFF5DEB3L, "white" to 0xFFFFFFFFL, "yellow" to 0xFFFFFF00L,
        "yellowgreen" to 0xFF9ACD32L,
    )
}
