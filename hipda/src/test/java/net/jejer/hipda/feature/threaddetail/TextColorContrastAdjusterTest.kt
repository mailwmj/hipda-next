package net.jejer.hipda.feature.threaddetail

import androidx.compose.ui.graphics.Color
import net.jejer.hipda.feature.threaddetail.body.TextColorContrastAdjuster
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.hiColorSchemeFromSeed
import net.jejer.hipda.ui.designsystem.glass.wcagContrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 闭合 B4-E2a ②：校验 `#000000`、`#000080`、`#FFFF00`、`#FFFFFF` 及全部 20 主题预设在
 * 浅色 / 深色 / OLED 纯黑（`#000000`）背景下的动态文字调色均满足 WCAG 2.1 `≥ 4.5:1`，
 * 且对原本可读的正常色保持零失真。
 */
class TextColorContrastAdjusterTest {

    @Test
    fun extremeColors_acrossLightDarkAndOledPureBlack_allMeetWcag4_5() {
        val extremeColors = listOf(
            "#000000" to Color(0xFF000000),
            "#000080" to Color(0xFF000080),
            "#FFFF00" to Color(0xFFFFFF00),
            "#FFFFFF" to Color(0xFFFFFFFF),
        )
        val lightSurface = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = false, pureBlack = false).surface
        val darkSurface = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = true, pureBlack = false).surface
        val oledSurface = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = true, pureBlack = true).surface
        assertEquals("OLED 纯黑背景必须严格为 #000000", Color.Black, oledSurface)

        val backgrounds = listOf(
            "light" to lightSurface,
            "dark" to darkSurface,
            "oled_pure_black" to oledSurface,
        )

        for ((bgName, bg) in backgrounds) {
            for ((hex, fg) in extremeColors) {
                val adjusted = TextColorContrastAdjuster.adjustForBackground(fg, bg)
                val ratio = wcagContrastRatio(adjusted, bg)
                assertTrue(
                    "[$bgName] 前景色 $hex 校准后输出 $adjusted 对比度=$ratio 低于 4.5:1",
                    ratio >= 4.5f,
                )
                val viaCss = TextColorContrastAdjuster.resolveCssTextColor(hex, bg, fallback = Color.Gray)
                assertEquals(adjusted, viaCss)
            }
        }
    }

    @Test
    fun all20Presets_extremeAndDiscuzPaletteColors_allMeetWcag4_5AcrossThreeThemes() {
        val discuzColors = listOf(
            "#000000", "#000080", "#FFFF00", "#FFFFFF",
            "red", "blue", "green", "orange", "purple", "navy",
            "gray", "silver", "yellow", "cyan", "magenta", "lemonchiffon",
            "darkslategray", "indigo", "darkgreen", "pink",
        )
        for (preset in HiColorPresets.ALL) {
            val schemes = listOf(
                "light" to hiColorSchemeFromSeed(preset.color, darkTheme = false, pureBlack = false),
                "dark" to hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = false),
                "oled" to hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = true),
            )
            for ((mode, scheme) in schemes) {
                for (raw in discuzColors) {
                    val resolved = TextColorContrastAdjuster.resolveCssTextColor(raw, scheme.surface, scheme.onSurface)
                    val ratio = wcagContrastRatio(resolved, scheme.surface)
                    assertTrue(
                        "[${preset.id}/$mode] 颜色 $raw 校准后 $resolved 对比度=$ratio < 4.5",
                        ratio >= 4.5f,
                    )
                }
            }
        }
    }

    @Test
    fun alreadyReadableColors_arePreservedWithoutDistortion() {
        val lightBg = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = false).surface
        val darkBg = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = true).surface
        val oledBg = Color.Black

        val readableOnLight = listOf(
            Color(0xFF000000),
            Color(0xFF000080),
            Color(0xFFB71C1C),
            Color(0xFF1B5E20),
            Color(0xFF4A148C),
        )
        for (color in readableOnLight) {
            assertTrue(wcagContrastRatio(color, lightBg) >= 4.5f)
            assertEquals("浅色下原本可读颜色 $color 不应失真", color, TextColorContrastAdjuster.adjustForBackground(color, lightBg))
        }

        val readableOnDarkAndOled = listOf(
            Color(0xFFFFFFFF),
            Color(0xFFFFFF00),
            Color(0xFF80DEEA),
            Color(0xFFFFB74D),
            Color(0xFFA5D6A7),
        )
        for (color in readableOnDarkAndOled) {
            assertTrue(wcagContrastRatio(color, darkBg) >= 4.5f)
            assertEquals("深色下原本可读颜色 $color 不应失真", color, TextColorContrastAdjuster.adjustForBackground(color, darkBg))
            assertEquals("OLED 下原本可读颜色 $color 不应失真", color, TextColorContrastAdjuster.adjustForBackground(color, oledBg))
        }
    }

    @Test
    fun adjustedChromaticColors_preserveDominantHueFamily() {
        val lightBg = Color.White
        val oledBg = Color.Black

        // 白底红字（原对比度约 4.0:1）应做最小必要压暗：ratio ∈ [4.5, 5.5] 且保持鲜明红色通道优势，不得过度压成近黑
        val redOnLight = TextColorContrastAdjuster.adjustForBackground(Color(0xFFFF0000), lightBg)
        val redRatio = wcagContrastRatio(redOnLight, lightBg)
        assertTrue("白底红字校准后对比度应在 4.5..5.5 避免过度压暗，实际 $redRatio ($redOnLight)", redRatio in 4.5f..5.5f)
        val redChannelGap = redOnLight.red - maxOf(redOnLight.green, redOnLight.blue)
        assertTrue("白底红字主副通道差应 ≥ 0.25，实际 $redChannelGap ($redOnLight)", redChannelGap >= 0.25f)

        // 白底黄字应压暗为可读深黄/橄榄色（ratio ∈ [4.5, 5.5]，红绿通道显著高于蓝通道），不漂移为灰/蓝
        val yellowOnLight = TextColorContrastAdjuster.adjustForBackground(Color(0xFFFFFF00), lightBg)
        val yellowRatio = wcagContrastRatio(yellowOnLight, lightBg)
        assertTrue("白底黄字校准后对比度应在 4.5..5.5 避免过度压暗，实际 $yellowRatio ($yellowOnLight)", yellowRatio in 4.5f..5.5f)
        val yellowChannelGap = minOf(yellowOnLight.red, yellowOnLight.green) - yellowOnLight.blue
        assertTrue("白底黄字主副通道差应 ≥ 0.25，实际 $yellowChannelGap ($yellowOnLight)", yellowChannelGap >= 0.25f)

        // 纯黑底深蓝字应提亮为可读亮蓝（ratio ∈ [4.5, 5.5]，蓝通道显著高于红绿通道），不漂移为纯灰/白
        val navyOnOled = TextColorContrastAdjuster.adjustForBackground(Color(0xFF000080), oledBg)
        val navyRatio = wcagContrastRatio(navyOnOled, oledBg)
        assertTrue("黑底深蓝校准后对比度应在 4.5..5.5 避免过度漂白，实际 $navyRatio ($navyOnOled)", navyRatio in 4.5f..5.5f)
        val navyChannelGap = navyOnOled.blue - maxOf(navyOnOled.red, navyOnOled.green)
        assertTrue("黑底深蓝主副通道差应 ≥ 0.25，实际 $navyChannelGap ($navyOnOled)", navyChannelGap >= 0.25f)
    }

    @Test
    fun parseCssColor_supportsHexShorthandRgbNamedAndInvalidFallback() {
        assertEquals(Color(0xFFFF0000), TextColorContrastAdjuster.parseCssColor("#ff0000"))
        assertEquals(Color(0xFFFF0000), TextColorContrastAdjuster.parseCssColor("#F00"))
        assertEquals(Color(0xFF123400), TextColorContrastAdjuster.parseCssColor("#1234"))
        assertEquals(Color(0xFF000080), TextColorContrastAdjuster.parseCssColor("  NAVY "))
        assertEquals(Color(0xFF4B0082), TextColorContrastAdjuster.parseCssColor("indigo"))
        assertEquals(Color(0xFF0A141E), TextColorContrastAdjuster.parseCssColor("rgb(10, 20, 30)"))
        assertEquals(Color(0xFF0A141E), TextColorContrastAdjuster.parseCssColor("rgba(10, 20, 30, 0.5)"))

        assertNull(TextColorContrastAdjuster.parseCssColor(null))
        assertNull(TextColorContrastAdjuster.parseCssColor("   "))
        assertNull(TextColorContrastAdjuster.parseCssColor("not-a-color"))
        assertNull(TextColorContrastAdjuster.parseCssColor("#zzzzzz"))

        val fallback = Color(0xFF222222)
        assertEquals(fallback, TextColorContrastAdjuster.resolveCssTextColor("invalid", Color.White, fallback))
    }
}
