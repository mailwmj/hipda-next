package net.jejer.hipda.ui.designsystem.glass

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.hiColorSchemeFromSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HiGlassSurfaceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun defaultTokensAndPresets_matchBackdropLiquidGlassConfig() {
        // 2026-11-27 Z Fold8 真机定稿：四组统一用同一组参数，同屏实时玻璃上限维持 3
        assertEquals(11.9.dp, HiGlassTokens.BlurRadius)
        assertEquals(0.66f, HiGlassTokens.LightBlurTintAlpha, 0.0001f)
        assertEquals(0.70f, HiGlassTokens.DarkBlurTintAlpha, 0.0001f)
        assertEquals(1.06f, HiGlassTokens.BlurSaturation, 0.0001f)
        assertEquals(8.dp, HiGlassTokens.RefractionHeight)
        assertEquals(16.dp, HiGlassTokens.RefractionAmount)
        assertFalse(HiGlassTokens.DepthEffect)
        assertFalse(HiGlassTokens.ChromaticAberration)
        assertTrue(HiGlassTokens.VibrancyEnabled)
        assertEquals(0f, HiGlassTokens.DefaultBorderHighlightStrength, 0.0001f)
        assertEquals(0.07f, HiGlassTokens.LightShadowAlpha, 0.0001f)
        assertEquals(0.18f, HiGlassTokens.DarkShadowAlpha, 0.0001f)
        assertEquals(0.12f, HiGlassTokens.IndicatorAlpha, 0.0001f)
        assertEquals(0.94f, HiGlassTokens.DegradedSurfaceAlpha, 0.0001f)
        assertEquals(0.5.dp, HiGlassTokens.DegradedBorderWidth)
        assertEquals(3, HiGlassTokens.MaxGlassSurfacesOnScreen)

        assertEquals(11.9.dp, HiGlassTokens.TopBarBlurRadius)
        assertEquals(0.66f, HiGlassTokens.TopBarLightBlurTintAlpha, 0.0001f)
        assertEquals(0.70f, HiGlassTokens.TopBarDarkBlurTintAlpha, 0.0001f)
        assertEquals(1.06f, HiGlassTokens.TopBarBlurSaturation, 0.0001f)
        assertEquals(8.dp, HiGlassTokens.TopBarRefractionHeight)
        assertEquals(16.dp, HiGlassTokens.TopBarRefractionAmount)

        assertEquals(11.9.dp, HiGlassTokens.ToolbarBlurRadius)
        assertEquals(0.66f, HiGlassTokens.ToolbarLightBlurTintAlpha, 0.0001f)
        assertEquals(0.70f, HiGlassTokens.ToolbarDarkBlurTintAlpha, 0.0001f)
        assertEquals(1.06f, HiGlassTokens.ToolbarBlurSaturation, 0.0001f)
        assertEquals(8.dp, HiGlassTokens.ToolbarRefractionHeight)
        assertEquals(16.dp, HiGlassTokens.ToolbarRefractionAmount)

        assertEquals(11.9.dp, HiGlassTokens.SheetBlurRadius)
        assertEquals(11.9.dp, HiGlassTokens.SheetDarkBlurRadius)
        assertEquals(0.66f, HiGlassTokens.SheetLightBlurTintAlpha, 0.0001f)
        assertEquals(0.70f, HiGlassTokens.SheetDarkBlurTintAlpha, 0.0001f)
        assertEquals(1.06f, HiGlassTokens.SheetBlurSaturation, 0.0001f)
        assertEquals(8.dp, HiGlassTokens.SheetRefractionHeight)
        assertEquals(16.dp, HiGlassTokens.SheetRefractionAmount)
        assertEquals(1.dp, HiGlassTokens.SheetBorderWidth)
        assertEquals(0.35f, HiGlassTokens.SheetScrimAlpha, 0.0001f)
        assertTrue(HiGlassTokens.SheetScrimAlpha in 0.32f..0.38f)

        listOf(
            "Navigation" to HiGlassStyle.Navigation,
            "TopBar" to HiGlassStyle.TopBar,
            "Toolbar" to HiGlassStyle.Toolbar,
            "Sheet" to HiGlassStyle.Sheet,
        ).forEach { (name, style) ->
            assertEquals("$name blurRadius", 11.9.dp, style.blurRadius)
            assertEquals("$name refractionHeight", 8.dp, style.refractionHeight)
            assertEquals("$name refractionAmount", 16.dp, style.refractionAmount)
            assertFalse("$name depthEffect", style.depthEffect)
            assertFalse("$name chromaticAberration", style.chromaticAberration)
            assertTrue("$name vibrancyEnabled", style.vibrancyEnabled)
            assertEquals("$name lightTintAlpha", 0.66f, style.lightTintAlpha, 0.0001f)
            assertEquals("$name darkTintAlpha", 0.70f, style.darkTintAlpha, 0.0001f)
            assertEquals("$name saturation", 1.06f, style.saturation, 0.0001f)
            assertEquals("$name borderHighlightStrength", 0f, style.borderHighlightStrength, 0.0001f)
            assertEquals("$name indicatorAlpha", 0.12f, style.indicatorAlpha, 0.0001f)
        }
        assertNull(HiGlassStyle.Navigation.darkBlurRadius)
        assertNull(HiGlassStyle.TopBar.darkBlurRadius)
        assertNull(HiGlassStyle.Toolbar.darkBlurRadius)
        assertEquals(11.9.dp, HiGlassStyle.Sheet.darkBlurRadius)
        assertEquals(1.dp, HiGlassStyle.Sheet.borderWidth)

        // 叠加层峰值 alpha 令牌区间校验（§5 L230–232, L271–274）
        assertTrue(HiGlassTokens.TabHighlighterContainerLightMaxAlpha <= 0.55f)
        assertTrue(HiGlassTokens.TabHighlighterContainerDarkMaxAlpha <= 0.45f)
        assertTrue(HiGlassTokens.TabHighlighterPrimaryLightMaxAlpha <= 0.22f)
        assertTrue(HiGlassTokens.TabHighlighterPrimaryDarkMaxAlpha <= 0.24f)
        assertTrue(HiGlassTokens.ProgressFillContainerLightMaxAlpha <= 0.50f)
        assertTrue(HiGlassTokens.ProgressFillContainerDarkMaxAlpha <= 0.45f)
        assertTrue(HiGlassTokens.ProgressFillPrimaryLightMaxAlpha <= 0.22f)
        assertTrue(HiGlassTokens.ProgressFillPrimaryDarkMaxAlpha <= 0.24f)
        assertTrue(HiGlassTokens.TopBarActionButtonLightAlpha in 0.55f..0.65f)
        assertTrue(HiGlassTokens.TopBarActionButtonDarkAlpha in 0.60f..0.70f)
        assertEquals(0.5.dp, HiGlassTokens.TopBarActionButtonBorderWidth)
        assertEquals(1.5f, HiGlassTokens.VibrancySaturationFactor, 0.0001f)
    }

    @Test
    fun resolveStyleSpec_matchesLightDarkPureBlackBlurOnlyAndDegradedValues() {
        val lightScheme = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = false)
        val darkScheme = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = true)
        val pureBlackScheme = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = true, pureBlack = true)

        val lightRefraction = resolveHiGlassStyleSpec(
            colorScheme = lightScheme,
            darkTheme = false,
            renderTier = GlassRenderTier.REFRACTION_AND_BLUR,
            floating = true,
        )
        assertEquals(GlassRenderTier.REFRACTION_AND_BLUR, lightRefraction.renderTier)
        assertTrue(lightRefraction.blurEnabled)
        assertTrue(lightRefraction.refractionEnabled)
        assertEquals(11.9.dp, lightRefraction.blurRadius)
        assertEquals(8.dp, lightRefraction.refractionHeight)
        assertEquals(16.dp, lightRefraction.refractionAmount)
        assertTrue(lightRefraction.vibrancyEnabled)
        assertEquals(1.06f, lightRefraction.saturation, 0.0001f)
        assertEquals(lightScheme.surfaceContainerLowest, lightRefraction.surfaceColor)
        assertEquals(lightScheme.surfaceContainerLowest.copy(alpha = 0.66f), lightRefraction.activeTintColor)
        assertEquals(lightScheme.surface.copy(alpha = 0.94f), lightRefraction.degradedSurfaceColor)
        assertEquals(HiGlassTokens.BorderWidth, lightRefraction.borderWidth)
        assertTrue(
            "默认 borderHighlightStrength == 0f 时激活态高光描边 alpha 必须全为 0",
            lightRefraction.borderHighlightColors.all { it.alpha == 0f },
        )
        assertEquals(Color.Transparent, lightRefraction.borderOutlineColor)
        assertEquals(HiGlassTokens.FloatingShadowElevation, lightRefraction.shadowElevation)
        assertEquals(Color.Black.copy(alpha = 0.07f), lightRefraction.shadowColor)

        val lightExplicitBorder = resolveHiGlassStyleSpec(
            colorScheme = lightScheme,
            darkTheme = false,
            renderTier = GlassRenderTier.REFRACTION_AND_BLUR,
            style = HiGlassStyle.Navigation.copy(borderHighlightStrength = 1f),
        )
        assertEquals(
            listOf(
                Color.White.copy(alpha = HiGlassTokens.LightBorderHighlightTopLeftAlpha),
                Color.White.copy(alpha = HiGlassTokens.LightBorderHighlightMidAlpha),
                Color.White.copy(alpha = HiGlassTokens.LightBorderHighlightBottomRightAlpha),
            ),
            lightExplicitBorder.borderHighlightColors,
        )
        assertEquals(Color.Black.copy(alpha = HiGlassTokens.LightBorderOutlineAlpha), lightExplicitBorder.borderOutlineColor)

        val darkBlurOnly = resolveHiGlassStyleSpec(
            colorScheme = darkScheme,
            darkTheme = true,
            renderTier = GlassRenderTier.BLUR_ONLY,
            floating = false,
        )
        assertEquals(GlassRenderTier.BLUR_ONLY, darkBlurOnly.renderTier)
        assertTrue(darkBlurOnly.blurEnabled)
        assertFalse(darkBlurOnly.refractionEnabled)
        assertEquals(11.9.dp, darkBlurOnly.blurRadius)
        assertEquals(darkScheme.surfaceContainer, darkBlurOnly.surfaceColor)
        assertEquals(darkScheme.surfaceContainer.copy(alpha = 0.70f), darkBlurOnly.activeTintColor)
        assertTrue(darkBlurOnly.borderHighlightColors.all { it.alpha == 0f })
        assertEquals(Color.Transparent, darkBlurOnly.borderOutlineColor)
        assertEquals(0.dp, darkBlurOnly.shadowElevation)
        assertEquals(Color.Black.copy(alpha = 0.18f), darkBlurOnly.shadowColor)

        val pureBlackBlur = resolveHiGlassStyleSpec(
            colorScheme = pureBlackScheme,
            darkTheme = true,
            blurEnabled = true,
        )
        assertEquals(pureBlackScheme.surfaceContainer, pureBlackBlur.surfaceColor)
        assertTrue(
            "PureBlack glass base color should use surfaceContainer instead of pure #000000",
            pureBlackBlur.surfaceColor != Color.Black,
        )

        val scaledBorderSpec = resolveHiGlassStyleSpec(
            colorScheme = darkScheme,
            darkTheme = true,
            blurEnabled = true,
            style = HiGlassStyle.Navigation.copy(borderHighlightStrength = 1.5f),
        )
        assertEquals(
            Color.White.copy(alpha = HiGlassTokens.DarkBorderHighlightTopLeftAlpha * 1.5f),
            scaledBorderSpec.borderHighlightColors.first(),
        )

        // 降级路径（94% 纯色 + 0.5dp outlineVariant 描边）不受 borderHighlightStrength == 0f 影响
        listOf(
            "Navigation" to HiGlassStyle.Navigation,
            "TopBar" to HiGlassStyle.TopBar,
            "Toolbar" to HiGlassStyle.Toolbar,
            "Sheet" to HiGlassStyle.Sheet,
        ).forEach { (presetName, presetStyle) ->
            listOf(
                Triple("light", lightScheme, false),
                Triple("dark", darkScheme, true),
                Triple("pureBlack", pureBlackScheme, true),
            ).forEach { (mode, scheme, isDark) ->
                val degraded = resolveHiGlassStyleSpec(
                    colorScheme = scheme,
                    darkTheme = isDark,
                    renderTier = GlassRenderTier.SOLID_DEGRADED,
                    floating = true,
                    style = presetStyle,
                )
                assertFalse("$presetName/$mode degraded blurEnabled", degraded.blurEnabled)
                assertFalse("$presetName/$mode degraded refractionEnabled", degraded.refractionEnabled)
                assertEquals("$presetName/$mode degradedSurfaceColor", scheme.surface.copy(alpha = 0.94f), degraded.degradedSurfaceColor)
                assertEquals("$presetName/$mode degraded borderWidth", HiGlassTokens.DegradedBorderWidth, degraded.borderWidth)
                assertEquals("$presetName/$mode degraded borderColor", scheme.outlineVariant, degraded.borderColor)
                assertTrue("$presetName/$mode degraded borderColor alpha > 0", degraded.borderColor.alpha > 0f)
                assertTrue("$presetName/$mode degraded borderHighlightColors empty", degraded.borderHighlightColors.isEmpty())
                assertEquals("$presetName/$mode degraded borderOutlineColor", Color.Transparent, degraded.borderOutlineColor)
            }
        }

        // Sheet 档位亮/暗模糊统一为 11.9dp 与 0.66/0.70 着色
        val lightSheet = resolveHiGlassStyleSpec(
            colorScheme = lightScheme,
            darkTheme = false,
            renderTier = GlassRenderTier.REFRACTION_AND_BLUR,
            style = HiGlassStyle.Sheet,
        )
        assertEquals(11.9.dp, lightSheet.blurRadius)
        assertEquals(1.dp, lightSheet.borderWidth)
        assertEquals(lightScheme.surfaceContainerLowest.copy(alpha = 0.66f), lightSheet.activeTintColor)

        val darkSheet = resolveHiGlassStyleSpec(
            colorScheme = darkScheme,
            darkTheme = true,
            renderTier = GlassRenderTier.REFRACTION_AND_BLUR,
            style = HiGlassStyle.Sheet,
        )
        assertEquals(11.9.dp, darkSheet.blurRadius)
        assertEquals(1.dp, darkSheet.borderWidth)
        assertEquals(darkScheme.surfaceContainer.copy(alpha = 0.70f), darkSheet.activeTintColor)
    }

    @Test
    fun toBackdropConfig_coversAllRenderTiersAndRefractionTokens() {
        val lightScheme = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = false)
        val darkScheme = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = true)

        // 1) API 33+ 导航默认档（11.9dp 模糊、8/16dp 折射、vibrancy + 1.06 饱和）
        val navSpec = resolveHiGlassStyleSpec(
            colorScheme = lightScheme,
            darkTheme = false,
            renderTier = GlassRenderTier.REFRACTION_AND_BLUR,
            style = HiGlassStyle.Navigation,
        )
        val navRefraction = navSpec.toBackdropConfig(GlassRenderTier.REFRACTION_AND_BLUR)
        assertEquals(GlassRenderTier.REFRACTION_AND_BLUR, navRefraction.renderTier)
        assertTrue(navRefraction.backdropEnabled)
        assertTrue(navRefraction.refractionEnabled)
        assertEquals(11.9.dp, navRefraction.blurRadius)
        assertEquals(8.dp, navRefraction.refractionHeight)
        assertEquals(16.dp, navRefraction.refractionAmount)
        assertFalse(navRefraction.depthEffect)
        assertFalse(navRefraction.chromaticAberration)
        assertTrue(navRefraction.vibrancyEnabled)
        assertEquals(1.06f, navRefraction.saturation, 0.0001f)
        assertEquals(1.5f * 1.06f, navRefraction.effectiveSaturation, 0.0001f)
        assertEquals(navSpec.activeTintColor, navRefraction.tintColor)
        assertEquals(navSpec.degradedSurfaceColor, navRefraction.fallbackColor)

        // 1b) TopBar 与 Sheet 使用统一定稿参数（亮/暗均为 11.9dp / 1.06 饱和）
        val topBarConfig = resolveHiGlassStyleSpec(
            colorScheme = lightScheme,
            darkTheme = false,
            renderTier = GlassRenderTier.REFRACTION_AND_BLUR,
            style = HiGlassStyle.TopBar,
        ).toBackdropConfig()
        assertEquals(11.9.dp, topBarConfig.blurRadius)
        assertEquals(8.dp, topBarConfig.refractionHeight)
        assertEquals(16.dp, topBarConfig.refractionAmount)
        assertEquals(1.5f * 1.06f, topBarConfig.effectiveSaturation, 0.0001f)

        val darkSheetConfig = resolveHiGlassStyleSpec(
            colorScheme = darkScheme,
            darkTheme = true,
            renderTier = GlassRenderTier.REFRACTION_AND_BLUR,
            style = HiGlassStyle.Sheet,
        ).toBackdropConfig()
        assertEquals(11.9.dp, darkSheetConfig.blurRadius)
        assertEquals(1.06f, darkSheetConfig.saturation, 0.0001f)
        assertEquals(1.5f * 1.06f, darkSheetConfig.effectiveSaturation, 0.0001f)

        // 2) API 33+ 自定义景深与色散开关（工具条档）
        val customToolbarSpec = resolveHiGlassStyleSpec(
            colorScheme = darkScheme,
            darkTheme = true,
            renderTier = GlassRenderTier.REFRACTION_AND_BLUR,
            style = HiGlassStyle.Toolbar.copy(
                refractionHeight = 20.dp,
                refractionAmount = 36.dp,
                depthEffect = true,
                chromaticAberration = true,
                vibrancyEnabled = false,
            ),
        )
        val customToolbarConfig = customToolbarSpec.toBackdropConfig(GlassRenderTier.REFRACTION_AND_BLUR)
        assertEquals(11.9.dp, customToolbarConfig.blurRadius)
        assertEquals(20.dp, customToolbarConfig.refractionHeight)
        assertEquals(36.dp, customToolbarConfig.refractionAmount)
        assertTrue(customToolbarConfig.depthEffect)
        assertTrue(customToolbarConfig.chromaticAberration)
        assertFalse(customToolbarConfig.vibrancyEnabled)
        assertEquals(1.06f, customToolbarConfig.saturation, 0.0001f)
        assertEquals(darkScheme.surfaceContainer.copy(alpha = 0.70f), customToolbarConfig.tintColor)

        // 3) API 31–32 仅模糊档：严禁开启 lens 折射（高度/强度/景深/色散均归零关闭）
        val blurOnlyConfig = customToolbarSpec.toBackdropConfig(GlassRenderTier.BLUR_ONLY)
        assertEquals(GlassRenderTier.BLUR_ONLY, blurOnlyConfig.renderTier)
        assertTrue(blurOnlyConfig.backdropEnabled)
        assertFalse("API 31–32 BLUR_ONLY must never enable lens refraction", blurOnlyConfig.refractionEnabled)
        assertEquals(11.9.dp, blurOnlyConfig.blurRadius)
        assertEquals(0.dp, blurOnlyConfig.refractionHeight)
        assertEquals(0.dp, blurOnlyConfig.refractionAmount)
        assertFalse(blurOnlyConfig.depthEffect)
        assertFalse(blurOnlyConfig.chromaticAberration)
        assertFalse(blurOnlyConfig.vibrancyEnabled)
        assertEquals(1.06f, blurOnlyConfig.saturation, 0.0001f)
        assertEquals(customToolbarSpec.activeTintColor, blurOnlyConfig.tintColor)
        assertEquals(customToolbarSpec.degradedSurfaceColor, blurOnlyConfig.fallbackColor)

        // 4) API ≤ 30 / 系统降级档：关闭全部 backdrop 效果，纯色回退 94%
        val degradedConfig = navSpec.toBackdropConfig(GlassRenderTier.SOLID_DEGRADED)
        assertEquals(GlassRenderTier.SOLID_DEGRADED, degradedConfig.renderTier)
        assertFalse(degradedConfig.backdropEnabled)
        assertFalse(degradedConfig.refractionEnabled)
        assertEquals(0.dp, degradedConfig.blurRadius)
        assertEquals(0.dp, degradedConfig.refractionHeight)
        assertEquals(0.dp, degradedConfig.refractionAmount)
        assertFalse(degradedConfig.depthEffect)
        assertFalse(degradedConfig.chromaticAberration)
        assertFalse(degradedConfig.vibrancyEnabled)
        assertEquals(1f, degradedConfig.saturation, 0.0001f)
        assertEquals(navSpec.degradedSurfaceColor, degradedConfig.tintColor)
        assertEquals(navSpec.degradedSurfaceColor, degradedConfig.fallbackColor)

        // 5) 折射高度或强度为 0dp 时自动跳过 lens
        val zeroRefractionSpec = resolveHiGlassStyleSpec(
            colorScheme = lightScheme,
            darkTheme = false,
            renderTier = GlassRenderTier.REFRACTION_AND_BLUR,
            style = HiGlassStyle.Navigation.copy(refractionHeight = 0.dp),
        )
        assertFalse(zeroRefractionSpec.toBackdropConfig().refractionEnabled)
    }

    @Test
    fun hiTheme_providesDefaultGlassStyleViaCompositionLocalAndAllowsOverride() {
        var defaultGlass: HiGlassStyle? = null
        var themeProvidedGlass: HiGlassStyle? = null
        var localOverriddenGlass: HiGlassStyle? = null
        val customStyle = HiGlassStyle.Navigation.copy(
            blurRadius = 14.dp,
            refractionHeight = 20.dp,
            refractionAmount = 32.dp,
            saturation = 1.25f,
            indicatorAlpha = 0.20f,
        )

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                defaultGlass = HiTheme.glass
                CompositionLocalProvider(LocalHiGlassStyle provides customStyle) {
                    localOverriddenGlass = HiTheme.glass
                }
            }
            HiTheme(
                themeMode = HiThemeMode.DARK,
                seed = HiColorPresets.Brand.toSeed(),
                glassStyle = HiGlassStyle.Toolbar,
            ) {
                themeProvidedGlass = HiTheme.glass
            }
        }

        assertEquals(HiGlassStyle.Navigation, defaultGlass)
        assertEquals(customStyle, localOverriddenGlass)
        assertEquals(HiGlassStyle.Toolbar, themeProvidedGlass)
    }

    @Test
    fun mainSources_doNotReferenceDebugGlassTuningOrPreviewSymbols() {
        val moduleDir = listOf(File("."), File("hipda")).firstOrNull {
            File(it, "src/main").isDirectory && File(it, "src/debug").isDirectory
        } ?: error("无法定位 hipda 模块目录（当前目录：${File(".").absolutePath}）")

        val forbiddenSymbols = listOf(
            "GlassTuningPanel",
            "formatGlassStyleParams",
            "GlassColorBackdropBanner",
            "PreviewActivity",
            "PreviewRootContent",
            "ShellPreviewScreen",
            "ThemeSpecimenScreen",
            "ThemeSpecimenState",
            "GlassPreviewMode",
            "preview_glass_tune_",
            "preview_glass_backdrop_",
        )

        val mainDir = File(moduleDir, "src/main")
        val sourceFiles = mainDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java" || it.extension == "xml") }
            .sortedBy { it.path }
            .toList()
        assertTrue("应扫描到 src/main 源文件", sourceFiles.isNotEmpty())

        val violations = mutableListOf<String>()
        sourceFiles.forEach { file ->
            val rel = file.relativeTo(moduleDir).invariantSeparatorsPath
            file.readLines().forEachIndexed { idx, line ->
                forbiddenSymbols.forEach { symbol ->
                    if (line.contains(symbol)) {
                        violations += "$rel:${idx + 1} 引用了 debug 符号 $symbol"
                    }
                }
            }
        }
        assertTrue(
            "src/main 不得包含任何 debug 调参面板或预览代码引用，实际命中：\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }

    @Test
    fun assertGlassSurfaceCountAtMost_passesWhenThreeSurfacesRendered() {
        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    val glassState = rememberHiGlassState()
                    Column {
                        HiGlassSurface(
                            state = glassState,
                            modifier = Modifier.size(160.dp, 48.dp),
                            style = HiGlassStyle.Toolbar,
                        ) {
                            Text("顶栏标签")
                        }
                        HiGlassSurface(
                            state = glassState,
                            modifier = Modifier.size(160.dp, 48.dp),
                            style = HiGlassStyle.Toolbar,
                        ) {
                            Text("底部工具条")
                        }
                        HiGlassSurface(state = glassState, modifier = Modifier.size(200.dp, 64.dp)) {
                            Text("导航胶囊")
                        }
                    }
                }
            }
        }

        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
    }

    @Test
    fun assertGlassSurfaceCountAtMost_failsWhenFourSurfacesRendered() {
        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    val glassState = rememberHiGlassState()
                    Column {
                        HiGlassSurface(state = glassState, modifier = Modifier.size(160.dp, 48.dp)) {
                            Text("第 1 块")
                        }
                        HiGlassSurface(state = glassState, modifier = Modifier.size(160.dp, 48.dp)) {
                            Text("第 2 块")
                        }
                        HiGlassSurface(state = glassState, modifier = Modifier.size(200.dp, 64.dp)) {
                            Text("第 3 块")
                        }
                        HiGlassSurface(state = glassState, modifier = Modifier.size(160.dp, 48.dp)) {
                            Text("多余的第 4 块毛玻璃")
                        }
                    }
                }
            }
        }

        try {
            composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
            fail("Expected AssertionError when 4 glass regions are rendered")
        } catch (expected: AssertionError) {
            assertTrue(
                "Error message should mention 3 and 4, was: ${expected.message}",
                expected.message?.contains("3") == true && expected.message?.contains("4") == true,
            )
        }
    }

    @Test
    fun hiGlassRegion_countsSegmentedToolbarAsSingleGlassRegion() {
        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    val glassState = rememberHiGlassState()
                    Column {
                        // 区域 1：透明吸顶顶栏
                        HiGlassSurface(
                            state = glassState,
                            modifier = Modifier.size(200.dp, 48.dp),
                            style = HiGlassStyle.Toolbar,
                        ) {
                            Text("顶栏")
                        }
                        // 区域 2：三段式底部工具条（3 枚胶囊共享同一区域，仅计为 1 个玻璃区域）
                        HiGlassRegion {
                            Column {
                                HiGlassSurface(
                                    state = glassState,
                                    modifier = Modifier.size(80.dp, 48.dp),
                                    style = HiGlassStyle.Toolbar,
                                ) {
                                    Text("回复")
                                }
                                HiGlassSurface(
                                    state = glassState,
                                    modifier = Modifier.size(120.dp, 48.dp),
                                    style = HiGlassStyle.Toolbar,
                                ) {
                                    Text("第 2 / 8 页")
                                }
                                HiGlassSurface(
                                    state = glassState,
                                    modifier = Modifier.size(80.dp, 48.dp),
                                    style = HiGlassStyle.Toolbar,
                                ) {
                                    Text("收藏/更多")
                                }
                            }
                        }
                        // 区域 3：底部导航胶囊
                        HiGlassSurface(state = glassState, modifier = Modifier.size(200.dp, 64.dp)) {
                            Text("导航胶囊")
                        }
                    }
                }
            }
        }

        // 共 5 个 HiGlassSurface 节点但归属 3 个玻璃区域，应通过上限 3 断言，且 5 个表面均处于模糊激活态
        val regionCount = composeRule.onAllNodes(
            matcher = SemanticsMatcher.expectValue(HiGlassMarker, true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        val surfaceCount = composeRule.onAllNodes(
            matcher = SemanticsMatcher.keyIsDefined(HiGlassBlurActive),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        assertEquals("三段工具条应合并为 1 个区域，总计 3 个玻璃区域", 3, regionCount)
        assertEquals("底层仍包含 5 个 HiGlassSurface 表面", 5, surfaceCount)
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        composeRule.assertAllGlassSurfacesBlurActive(true)
    }

    @Test
    fun degradedSurface_meetsWcag4_5ContrastAcrossAll20PresetsAndWorstCaseBackdrops() {
        val allPresets = listOf(
            "Navigation" to HiGlassStyle.Navigation,
            "TopBar" to HiGlassStyle.TopBar,
            "Toolbar" to HiGlassStyle.Toolbar,
            "Sheet" to HiGlassStyle.Sheet,
        )
        HiColorPresets.ALL.forEach { preset ->
            listOf(
                Triple("light", hiColorSchemeFromSeed(preset.color, darkTheme = false), false),
                Triple("dark", hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = false), true),
                Triple("pureBlack", hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = true), true),
            ).forEach { (mode, scheme, darkTheme) ->
                val worstBackdrop = if (darkTheme) Color.White else Color.Black
                allPresets.forEach { (styleName, style) ->
                    val spec = resolveHiGlassStyleSpec(
                        colorScheme = scheme,
                        darkTheme = darkTheme,
                        renderTier = GlassRenderTier.SOLID_DEGRADED,
                        style = style,
                    )
                    listOf("bg" to scheme.background, "worstBackdrop" to worstBackdrop).forEach { (bgName, backdrop) ->
                        assertWcagContrastAtLeast(
                            minRatio = 4.5f,
                            surface = spec.degradedSurfaceColor,
                            foreground = scheme.onSurface,
                            backdrop = backdrop,
                            label = "${preset.id}/$mode/$styleName/$bgName onSurface",
                        )
                        assertWcagContrastAtLeast(
                            minRatio = 4.5f,
                            surface = spec.degradedSurfaceColor,
                            foreground = scheme.onSurfaceVariant,
                            backdrop = backdrop,
                            label = "${preset.id}/$mode/$styleName/$bgName onSurfaceVariant",
                        )
                        assertWcagContrastAtLeast(
                            minRatio = 4.5f,
                            surface = spec.degradedSurfaceColor,
                            foreground = scheme.primary,
                            backdrop = backdrop,
                            label = "${preset.id}/$mode/$styleName/$bgName primary",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun degradedMode_detachesBackdropAndReleasesSourceRecordingDemand() {
        var envState by mutableStateOf(GlassEnvironmentState.RefractionAndBlur)
        var capturedGlassState: HiGlassState? = null

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = envState) {
                    val glassState = rememberHiGlassState()
                    capturedGlassState = glassState
                    Box(modifier = Modifier.size(200.dp, 120.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .hiGlassSource(glassState)
                                .background(Color.Cyan),
                        )
                        HiGlassSurface(
                            state = glassState,
                            modifier = Modifier.size(160.dp, 64.dp),
                        ) {
                            Text("导航胶囊")
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val state = requireNotNull(capturedGlassState)
        val layerBackdrop = requireNotNull(state.layerBackdrop)
        val coordinatesGetter = layerBackdrop.javaClass.methods.first { it.name.startsWith("getLayerCoordinates") }
        assertTrue("Active refraction/blur should demand backdrop recording", state.hasSourceDemand)
        assertNotNull(
            "Active layerBackdrop should attach coordinates for recording",
            coordinatesGetter.invoke(layerBackdrop),
        )
        composeRule.assertAllGlassSurfacesBlurActive(true)

        // 切换至省电降级：释放需求并卸载 LayerBackdropNode，停止离屏录制
        envState = GlassEnvironmentState(setOf(GlassDegradationReason.POWER_SAVE_MODE))
        composeRule.waitForIdle()
        assertFalse("Degraded mode must release backdrop recording demand", state.hasSourceDemand)
        assertNull(
            "Degraded mode must detach LayerBackdropNode so coordinates are cleared and recording stops",
            coordinatesGetter.invoke(layerBackdrop),
        )
        composeRule.assertAllGlassSurfacesBlurActive(false)

        // 切换至 API 31 仅模糊档：重新激活录制但不开启折射
        envState = GlassEnvironmentState.BlurOnly
        composeRule.waitForIdle()
        assertTrue("Blur-only mode should re-enable backdrop recording demand", state.hasSourceDemand)
        assertNotNull(
            "Blur-only mode should re-attach layerBackdrop coordinates",
            coordinatesGetter.invoke(layerBackdrop),
        )
        composeRule.assertAllGlassSurfacesBlurActive(true)
    }

    @Test
    @Config(sdk = [35])
    fun api35RefractionTier_capsuleAndRectangleShape_renderRealLensWithoutCrash() {
        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    val glassState = rememberHiGlassState()
                    Box(modifier = Modifier.size(240.dp, 160.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .hiGlassSource(glassState)
                                .background(Color(0xFF1E88E5)),
                        )
                        Column {
                            HiGlassSurface(
                                state = glassState,
                                modifier = Modifier.size(200.dp, 48.dp),
                                shape = RectangleShape,
                                style = HiGlassStyle.TopBar,
                            ) {
                                Text("矩形顶栏折射")
                            }
                            HiGlassSurface(
                                state = glassState,
                                modifier = Modifier.size(200.dp, 48.dp),
                                shape = HiShapeTokens.Large,
                                style = HiGlassStyle.Sheet,
                            ) {
                                Text("快速回复面板")
                            }
                            HiGlassSurface(
                                state = glassState,
                                modifier = Modifier.size(200.dp, 64.dp),
                                shape = HiShapeTokens.Full,
                                style = HiGlassStyle.Navigation.copy(chromaticAberration = true),
                            ) {
                                Text("胶囊折射色散")
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        composeRule.assertAllGlassSurfacesBlurActive(true)
    }

    /**
     * 闭合 R-G2 S1：父组件无关重组（例如切 Tab 导致 contentColor/label 变化）时，
     * `HiGlassSurface` 必须复用同一缓存的 `DrawBackdropElement` 实例（避免新建 `ShapeProvider` 触发 `invalidateDrawCache`）；
     * 仅当 `style` / `backdropConfig` 真实变更时才重建。
     */
    @Test
    fun drawBackdropModifier_isCachedAcrossUnrelatedRecompositionAndUpdatesOnStyleChange() {
        var contentColor by mutableStateOf(Color.Black)
        var style by mutableStateOf(HiGlassStyle.Navigation)
        var labelIndex by mutableIntStateOf(0)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    val glassState = rememberHiGlassState()
                    Box(modifier = Modifier.size(240.dp, 120.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .hiGlassSource(glassState)
                                .background(Color.Cyan),
                        )
                        HiGlassSurface(
                            state = glassState,
                            modifier = Modifier.size(200.dp, 64.dp),
                            style = style,
                            contentColor = contentColor,
                        ) {
                            Text("标签 #$labelIndex")
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        fun findDrawBackdropElement(): Modifier {
            val node = composeRule.onAllNodes(
                matcher = SemanticsMatcher.keyIsDefined(HiGlassBlurActive),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().single()
            return node.layoutInfo.getModifierInfo()
                .map { it.modifier }
                .first { it.javaClass.name == "com.kyant.backdrop.DrawBackdropElement" }
        }

        val initialElement = findDrawBackdropElement()

        // 触发 HiGlassSurface 自身重组（改变 contentColor 与子文本），但 backdropConfig/shape/state 不变
        contentColor = Color.DarkGray
        labelIndex = 1
        composeRule.waitForIdle()
        val afterUnrelatedRecomp = findDrawBackdropElement()
        assertSame(
            "无关父重组不得重建 DrawBackdropElement（防止触发 DrawBackdropNode.invalidateDrawCache）",
            initialElement,
            afterUnrelatedRecomp,
        )

        // 修改模糊参数改变 backdropConfig 后，应重建为新的 DrawBackdropElement
        style = HiGlassStyle.Navigation.copy(blurRadius = 16.dp)
        composeRule.waitForIdle()
        val afterStyleChange = findDrawBackdropElement()
        assertNotSame(
            "backdropConfig 变化时应更新为新的 DrawBackdropElement 实例",
            initialElement,
            afterStyleChange,
        )
    }

    @Test
    fun degradedSurface_stillAttachesBorderModifierWhenBorderHighlightStrengthIsZero() {
        var envState by mutableStateOf(GlassEnvironmentState.RefractionAndBlur)
        var style by mutableStateOf(HiGlassStyle.Navigation)

        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = envState) {
                    val glassState = rememberHiGlassState()
                    Box(modifier = Modifier.size(220.dp, 120.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .hiGlassSource(glassState)
                                .background(Color.Cyan),
                        )
                        HiGlassSurface(
                            state = glassState,
                            modifier = Modifier.size(180.dp, 64.dp),
                            style = style,
                        ) {
                            Text("描边检测")
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        fun countBorderModifiers(): Int {
            val node = composeRule.onAllNodes(
                matcher = SemanticsMatcher.keyIsDefined(HiGlassBlurActive),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().single()
            return node.layoutInfo.getModifierInfo()
                .count { it.modifier.javaClass.name.contains("Border") }
        }

        // 1) 激活态默认 borderHighlightStrength == 0f：不绘制描边高光
        assertEquals("激活态 border=0f 时不应绘制描边高光", 0, countBorderModifiers())

        // 2) 降级态（即使 borderHighlightStrength == 0f）：仍必须保留 0.5dp outlineVariant 描边
        envState = GlassEnvironmentState(setOf(GlassDegradationReason.POWER_SAVE_MODE))
        composeRule.waitForIdle()
        assertEquals("降级态不受 border=0f 影响，必须继续绘制描边", 1, countBorderModifiers())

        // 3) 激活态显式调高 borderHighlightStrength > 0f：恢复绘制描边高光（浅色含高光+暗轮廓）
        envState = GlassEnvironmentState.RefractionAndBlur
        style = HiGlassStyle.Navigation.copy(borderHighlightStrength = 1f)
        composeRule.waitForIdle()
        assertTrue("显式开启 borderHighlightStrength > 0 时应绘制描边", countBorderModifiers() >= 1)
    }
}
