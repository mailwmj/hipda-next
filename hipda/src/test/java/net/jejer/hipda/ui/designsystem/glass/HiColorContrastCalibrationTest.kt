package net.jejer.hipda.ui.designsystem.glass

import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.pow
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.hiColorSchemeFromSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-C1 R1 对比度校准回归测试：
 * - 全部 20 个预设色 × 浅色 / 深色 / 纯黑下，`surfaceDim` 上的 `primary` / `tertiary` 对比度 ≥ 4.5:1；
 * - 全部 20 个预设色 × 深色 / 纯黑下，`surfaceVariant` 与 `outline` 对比度 ≥ 3.0:1；
 * - 校准后有彩度色阶的输出相对亮度精确匹配目标 CIE L*。
 */
class HiColorContrastCalibrationTest {

    @Test
    fun all20Presets_primaryAndTertiaryOnSurfaceDim_meet4_5Contrast() {
        assertEquals(20, HiColorPresets.ALL.size)

        HiColorPresets.ALL.forEach { preset ->
            listOf(
                "light" to hiColorSchemeFromSeed(preset.color, darkTheme = false, pureBlack = false),
                "dark" to hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = false),
                "pureBlack" to hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = true),
            ).forEach { (mode, scheme) ->
                assertWcagContrastAtLeast(
                    minRatio = 4.5f,
                    surface = scheme.surfaceDim,
                    foreground = scheme.primary,
                    label = "${preset.id}/$mode surfaceDim/primary",
                )
                assertWcagContrastAtLeast(
                    minRatio = 4.5f,
                    surface = scheme.surfaceDim,
                    foreground = scheme.tertiary,
                    label = "${preset.id}/$mode surfaceDim/tertiary",
                )
            }
        }
    }

    @Test
    fun all20Presets_darkSurfaceVariantAndOutline_meet3_0Contrast() {
        assertEquals(20, HiColorPresets.ALL.size)

        HiColorPresets.ALL.forEach { preset ->
            listOf(
                "dark" to hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = false),
                "pureBlack" to hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = true),
            ).forEach { (mode, scheme) ->
                assertWcagContrastAtLeast(
                    minRatio = 3.0f,
                    surface = scheme.surfaceVariant,
                    foreground = scheme.outline,
                    label = "${preset.id}/$mode surfaceVariant/outline",
                )
            }
        }
    }

    @Test
    fun oklchToneCalibration_matchesTargetCieLightnessLuminance() {
        val targetY40 = ((40f + 16f) / 116f).pow(3)
        val targetY80 = ((80f + 16f) / 116f).pow(3)
        val targetY61 = ((61f + 16f) / 116f).pow(3)

        HiColorPresets.ALL.forEach { preset ->
            val light = hiColorSchemeFromSeed(preset.color, darkTheme = false)
            val dark = hiColorSchemeFromSeed(preset.color, darkTheme = true)

            assertTrue(
                "${preset.id} light primary luminance ${light.primary.luminance()} drifted from $targetY40",
                abs(light.primary.luminance() - targetY40) <= 0.01f,
            )
            assertTrue(
                "${preset.id} dark primary luminance ${dark.primary.luminance()} drifted from $targetY80",
                abs(dark.primary.luminance() - targetY80) <= 0.01f,
            )
            assertTrue(
                "${preset.id} dark outline luminance ${dark.outline.luminance()} drifted from $targetY61",
                abs(dark.outline.luminance() - targetY61) <= 0.01f,
            )
        }
    }
}
