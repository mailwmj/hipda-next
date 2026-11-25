package net.jejer.hipda.ui.designsystem.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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

    /**
     * CI 单测 (b)（product-design.md §5 L271–274）：
     * 全 20 套主题预设 × 浅色 / 深色 / OLED 纯黑 × 4 种标准主题背景（background / surface / surfaceContainerLow / surfaceContainer），
     * 断言四档模糊激活态（Navigation / TopBar / Toolbar / Sheet）及叠加层峰值 alpha（选中指示器、顶栏轻底圆钮、
     * 版块荧光笔、阅读进度填充）上全部前景角色（onSurface / onSurfaceVariant / primary）WCAG 2.1 对比度 ≥ 4.5:1。
     */
    @Test
    fun activeGlassFourPresetsAndOverlays_meetWcag4_5AcrossAll20PresetsAndStandardBackdrops() {
        assertEquals(20, HiColorPresets.ALL.size)
        val allStyles = listOf(
            "Navigation" to HiGlassStyle.Navigation,
            "TopBar" to HiGlassStyle.TopBar,
            "Toolbar" to HiGlassStyle.Toolbar,
            "Sheet" to HiGlassStyle.Sheet,
        )

        HiColorPresets.ALL.forEach { preset ->
            listOf(
                Triple("light", hiColorSchemeFromSeed(preset.color, darkTheme = false, pureBlack = false), false),
                Triple("dark", hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = false), true),
                Triple("pureBlack", hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = true), true),
            ).forEach { (mode, scheme, darkTheme) ->
                val standardBackdrops = listOf(
                    "background" to scheme.background,
                    "surface" to scheme.surface,
                    "surfaceContainerLow" to scheme.surfaceContainerLow,
                    "surfaceContainer" to scheme.surfaceContainer,
                )
                val foregrounds = listOf(
                    "onSurface" to scheme.onSurface,
                    "onSurfaceVariant" to scheme.onSurfaceVariant,
                    "primary" to scheme.primary,
                )

                standardBackdrops.forEach { (bgName, backdrop) ->
                    // 1) 四档基础激活态表面
                    allStyles.forEach { (styleName, style) ->
                        val spec = resolveHiGlassStyleSpec(
                            colorScheme = scheme,
                            darkTheme = darkTheme,
                            renderTier = GlassRenderTier.REFRACTION_AND_BLUR,
                            style = style,
                        )
                        foregrounds.forEach { (fgName, fg) ->
                            assertWcagContrastAtLeast(
                                minRatio = 4.5f,
                                surface = spec.activeTintColor,
                                foreground = fg,
                                backdrop = backdrop,
                                label = "${preset.id}/$mode/$bgName/$styleName $fgName",
                            )
                        }
                    }

                    // 2) Navigation + 选中指示器（primary @ 0.12）
                    val navComposite = resolveHiGlassStyleSpec(
                        colorScheme = scheme,
                        darkTheme = darkTheme,
                        renderTier = GlassRenderTier.REFRACTION_AND_BLUR,
                        style = HiGlassStyle.Navigation,
                    ).activeTintColor.compositeOver(backdrop)
                    val navIndicator = scheme.primary.copy(alpha = HiGlassTokens.IndicatorAlpha)
                    foregrounds.forEach { (fgName, fg) ->
                        assertWcagContrastAtLeast(
                            minRatio = 4.5f,
                            surface = navIndicator,
                            foreground = fg,
                            backdrop = navComposite,
                            label = "${preset.id}/$mode/$bgName/Navigation+Indicator $fgName",
                        )
                    }

                    // 3) TopBar + 详情顶栏轻底圆形按钮（surfaceContainerHigh @ 0.60/0.65）
                    val topBarComposite = resolveHiGlassStyleSpec(
                        colorScheme = scheme,
                        darkTheme = darkTheme,
                        renderTier = GlassRenderTier.REFRACTION_AND_BLUR,
                        style = HiGlassStyle.TopBar,
                    ).activeTintColor.compositeOver(backdrop)
                    val topBarActionOverlay = scheme.surfaceContainerHigh.copy(
                        alpha = if (darkTheme) {
                            HiGlassTokens.TopBarActionButtonDarkAlpha
                        } else {
                            HiGlassTokens.TopBarActionButtonLightAlpha
                        },
                    )
                    foregrounds.forEach { (fgName, fg) ->
                        assertWcagContrastAtLeast(
                            minRatio = 4.5f,
                            surface = topBarActionOverlay,
                            foreground = fg,
                            backdrop = topBarComposite,
                            label = "${preset.id}/$mode/$bgName/TopBar+ActionBtn $fgName",
                        )
                    }

                    // 4) TopBar + 版块荧光笔峰值 alpha（primaryContainer 0.55/0.45 与 primary 0.22/0.24）
                    val tabHighlightContainer = scheme.primaryContainer.copy(
                        alpha = if (darkTheme) {
                            HiGlassTokens.TabHighlighterContainerDarkMaxAlpha
                        } else {
                            HiGlassTokens.TabHighlighterContainerLightMaxAlpha
                        },
                    )
                    val tabHighlightPrimary = scheme.primary.copy(
                        alpha = if (darkTheme) {
                            HiGlassTokens.TabHighlighterPrimaryDarkMaxAlpha
                        } else {
                            HiGlassTokens.TabHighlighterPrimaryLightMaxAlpha
                        },
                    )
                    listOf(
                        "TabHighlighterContainer" to tabHighlightContainer,
                        "TabHighlighterPrimary" to tabHighlightPrimary,
                    ).forEach { (overlayName, overlayColor) ->
                        assertWcagContrastAtLeast(
                            minRatio = 4.5f,
                            surface = overlayColor,
                            foreground = scheme.onSurface,
                            backdrop = topBarComposite,
                            label = "${preset.id}/$mode/$bgName/TopBar+$overlayName onSurface",
                        )
                    }

                    // 5) Toolbar + 阅读进度填充峰值 alpha（primaryContainer 0.50/0.45 与 primary 0.22/0.24）
                    val toolbarComposite = resolveHiGlassStyleSpec(
                        colorScheme = scheme,
                        darkTheme = darkTheme,
                        renderTier = GlassRenderTier.REFRACTION_AND_BLUR,
                        style = HiGlassStyle.Toolbar,
                    ).activeTintColor.compositeOver(backdrop)
                    val progressContainer = scheme.primaryContainer.copy(
                        alpha = if (darkTheme) {
                            HiGlassTokens.ProgressFillContainerDarkMaxAlpha
                        } else {
                            HiGlassTokens.ProgressFillContainerLightMaxAlpha
                        },
                    )
                    val progressPrimary = scheme.primary.copy(
                        alpha = if (darkTheme) {
                            HiGlassTokens.ProgressFillPrimaryDarkMaxAlpha
                        } else {
                            HiGlassTokens.ProgressFillPrimaryLightMaxAlpha
                        },
                    )
                    listOf(
                        "ProgressContainer" to progressContainer,
                        "ProgressPrimary" to progressPrimary,
                    ).forEach { (overlayName, overlayColor) ->
                        assertWcagContrastAtLeast(
                            minRatio = 4.5f,
                            surface = overlayColor,
                            foreground = scheme.onSurface,
                            backdrop = toolbarComposite,
                            label = "${preset.id}/$mode/$bgName/Toolbar+$overlayName onSurface",
                        )
                    }
                }
            }
        }
    }
}
