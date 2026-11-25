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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.blur.HazeBlurDefaults
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import java.io.File
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.hiColorSchemeFromSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun defaultTokensAndPresets_matchGlassReportRecommendations() {
        assertEquals(10.dp, HiGlassTokens.BlurRadius)
        assertEquals(0.04f, HiGlassTokens.NoiseFactor, 0.0001f)
        assertEquals(0.45f, HiGlassTokens.LightBlurTintAlpha, 0.0001f)
        assertEquals(0.46f, HiGlassTokens.DarkBlurTintAlpha, 0.0001f)
        assertEquals(1.18f, HiGlassTokens.BlurSaturation, 0.0001f)
        assertEquals(0.07f, HiGlassTokens.LightShadowAlpha, 0.0001f)
        assertEquals(0.18f, HiGlassTokens.DarkShadowAlpha, 0.0001f)
        assertEquals(0.12f, HiGlassTokens.IndicatorAlpha, 0.0001f)
        assertEquals(0.94f, HiGlassTokens.DegradedSurfaceAlpha, 0.0001f)

        assertEquals(10.dp, HiGlassStyle.Navigation.blurRadius)
        assertEquals(0.45f, HiGlassStyle.Navigation.lightTintAlpha, 0.0001f)
        assertEquals(0.46f, HiGlassStyle.Navigation.darkTintAlpha, 0.0001f)
        assertEquals(1.18f, HiGlassStyle.Navigation.saturation, 0.0001f)
        assertEquals(0.04f, HiGlassStyle.Navigation.noiseFactor, 0.0001f)
        assertEquals(0.12f, HiGlassStyle.Navigation.indicatorAlpha, 0.0001f)

        assertEquals(16.dp, HiGlassStyle.Toolbar.blurRadius)
        assertEquals(0.60f, HiGlassStyle.Toolbar.lightTintAlpha, 0.0001f)
        assertEquals(0.68f, HiGlassStyle.Toolbar.darkTintAlpha, 0.0001f)
        assertEquals(1.08f, HiGlassStyle.Toolbar.saturation, 0.0001f)
        assertEquals(0.04f, HiGlassStyle.Toolbar.noiseFactor, 0.0001f)
    }

    @Test
    fun resolveStyleSpec_matchesLightDarkPureBlackAndDegradedValues() {
        val lightScheme = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = false)
        val darkScheme = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = true)
        val pureBlackScheme = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = true, pureBlack = true)

        val lightBlur = resolveHiGlassStyleSpec(
            colorScheme = lightScheme,
            darkTheme = false,
            blurEnabled = true,
            floating = true,
        )
        assertEquals(10.dp, lightBlur.blurRadius)
        assertEquals(0.04f, lightBlur.noiseFactor, 0.0001f)
        assertEquals(1.18f, lightBlur.saturation, 0.0001f)
        assertEquals(lightScheme.surfaceContainerLowest, lightBlur.surfaceColor)
        assertEquals(lightScheme.surfaceContainerLowest.copy(alpha = 0.45f), lightBlur.activeTintColor)
        assertEquals(lightScheme.surface.copy(alpha = 0.94f), lightBlur.degradedSurfaceColor)
        assertEquals(HiGlassTokens.BorderWidth, lightBlur.borderWidth)
        assertEquals(
            listOf(
                Color.White.copy(alpha = HiGlassTokens.LightBorderHighlightTopLeftAlpha),
                Color.White.copy(alpha = HiGlassTokens.LightBorderHighlightMidAlpha),
                Color.White.copy(alpha = HiGlassTokens.LightBorderHighlightBottomRightAlpha),
            ),
            lightBlur.borderHighlightColors,
        )
        assertEquals(Color.Black.copy(alpha = HiGlassTokens.LightBorderOutlineAlpha), lightBlur.borderOutlineColor)
        assertEquals(HiGlassTokens.FloatingShadowElevation, lightBlur.shadowElevation)
        assertEquals(Color.Black.copy(alpha = 0.07f), lightBlur.shadowColor)

        val darkBlur = resolveHiGlassStyleSpec(
            colorScheme = darkScheme,
            darkTheme = true,
            blurEnabled = true,
            floating = false,
        )
        assertEquals(10.dp, darkBlur.blurRadius)
        assertEquals(darkScheme.surfaceContainer, darkBlur.surfaceColor)
        assertEquals(darkScheme.surfaceContainer.copy(alpha = 0.46f), darkBlur.activeTintColor)
        assertEquals(
            listOf(
                Color.White.copy(alpha = HiGlassTokens.DarkBorderHighlightTopLeftAlpha),
                Color.White.copy(alpha = HiGlassTokens.DarkBorderHighlightMidAlpha),
                Color.White.copy(alpha = HiGlassTokens.DarkBorderHighlightBottomRightAlpha),
            ),
            darkBlur.borderHighlightColors,
        )
        assertEquals(Color.Transparent, darkBlur.borderOutlineColor)
        assertEquals(0.dp, darkBlur.shadowElevation)
        assertEquals(Color.Black.copy(alpha = 0.18f), darkBlur.shadowColor)

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

        val lightDegraded = resolveHiGlassStyleSpec(
            colorScheme = lightScheme,
            darkTheme = false,
            blurEnabled = false,
            floating = true,
        )
        assertEquals(lightScheme.surface.copy(alpha = 0.94f), lightDegraded.degradedSurfaceColor)
        assertEquals(HiGlassTokens.DegradedBorderWidth, lightDegraded.borderWidth)
        assertEquals(lightScheme.outlineVariant, lightDegraded.borderColor)
        assertTrue(lightDegraded.borderHighlightColors.isEmpty())
        assertEquals(Color.Transparent, lightDegraded.borderOutlineColor)
    }

    @Test
    fun toHazeConfigAndBlurStyle_passesSaturationNoiseBlurAndTintIntoHaze() {
        val lightScheme = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = false)
        val darkScheme = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = true)

        val navSpec = resolveHiGlassStyleSpec(
            colorScheme = lightScheme,
            darkTheme = false,
            blurEnabled = true,
            style = HiGlassStyle.Navigation,
        )
        val navConfig = navSpec.toHazeConfig()
        assertTrue(navConfig.blurEnabled)
        assertEquals(10.dp, navConfig.blurRadius)
        assertEquals(0.04f, navConfig.noiseFactor, 0.0001f)
        assertEquals(lightScheme.surfaceContainerLowest, navConfig.backgroundColor)
        assertEquals(2, navConfig.colorEffects.size)

        val expectedNavSatEffect = HazeColorEffect.colorFilter(
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.18f) }),
        )
        val expectedToolbarSatEffect = HazeColorEffect.colorFilter(
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.08f) }),
        )
        assertEquals(expectedNavSatEffect, navConfig.colorEffects[0])
        assertNotEquals(expectedToolbarSatEffect, navConfig.colorEffects[0])
        assertEquals(HazeColorEffect.tint(navSpec.activeTintColor), navConfig.colorEffects[1])
        assertEquals(HazeColorEffect.tint(navSpec.degradedSurfaceColor), navConfig.fallbackColorEffect)

        // 同时验证 toHazeBlurStyle() 在 Haze 2.1.0 内部解析后的实际值与 HiGlassHazeConfig 完全一致
        val resolvedNav = resolveHazeStyleForTest(navConfig.toHazeBlurStyle())
        assertEquals(true, resolvedNav.blurEnabled)
        assertEquals(10f, resolvedNav.blurRadiusDp, 0.001f)
        assertEquals(0.04f, resolvedNav.noiseFactor, 0.0001f)
        assertEquals(navConfig.colorEffects, resolvedNav.colorEffects)
        assertEquals(navConfig.fallbackColorEffect, resolvedNav.fallbackColorEffect)

        // 工具条档验证
        val toolbarSpec = resolveHiGlassStyleSpec(
            colorScheme = darkScheme,
            darkTheme = true,
            blurEnabled = true,
            style = HiGlassStyle.Toolbar,
        )
        val toolbarConfig = toolbarSpec.toHazeConfig()
        assertEquals(16.dp, toolbarConfig.blurRadius)
        assertEquals(0.04f, toolbarConfig.noiseFactor, 0.0001f)
        assertEquals(darkScheme.surfaceContainer, toolbarConfig.backgroundColor)
        assertEquals(2, toolbarConfig.colorEffects.size)
        assertEquals(expectedToolbarSatEffect, toolbarConfig.colorEffects[0])
        assertEquals(
            HazeColorEffect.tint(darkScheme.surfaceContainer.copy(alpha = 0.68f)),
            toolbarConfig.colorEffects[1],
        )
        val resolvedToolbar = resolveHazeStyleForTest(toolbarConfig.toHazeBlurStyle())
        assertEquals(16f, resolvedToolbar.blurRadiusDp, 0.001f)
        assertEquals(toolbarConfig.colorEffects, resolvedToolbar.colorEffects)

        // 饱和度 == 1.0f 时省略 ColorFilter，保留单 tint 走单 pass 优化
        val neutralSatSpec = resolveHiGlassStyleSpec(
            colorScheme = lightScheme,
            darkTheme = false,
            blurEnabled = true,
            style = HiGlassStyle.Navigation.copy(saturation = 1.0f),
        )
        val neutralConfig = neutralSatSpec.toHazeConfig()
        assertEquals(1, neutralConfig.colorEffects.size)
        assertEquals(HazeColorEffect.tint(neutralSatSpec.activeTintColor), neutralConfig.colorEffects[0])
        val resolvedNeutral = resolveHazeStyleForTest(neutralConfig.toHazeBlurStyle())
        assertEquals(neutralConfig.colorEffects, resolvedNeutral.colorEffects)
    }

    private data class ResolvedHazeSnapshot(
        val blurEnabled: Boolean,
        val blurRadiusDp: Float,
        val noiseFactor: Float,
        val colorEffects: List<HazeColorEffect>,
        val fallbackColorEffect: HazeColorEffect?,
    )

    private fun resolveHazeStyleForTest(style: HazeBlurStyle): ResolvedHazeSnapshot {
        val styleKt = Class.forName("dev.chrisbanes.haze.blur.HazeBlurStyleKt")
        val resolveMethod = styleKt.getDeclaredMethod(
            "resolveHazeBlurStyle",
            HazeBlurStyle::class.java,
            HazeBlurStyle::class.java,
        )
        val resolved = resolveMethod.invoke(null, HazeBlurDefaults.style, style)
        val cls = resolved.javaClass
        val blurEnabled = cls.getMethod("getBlurEnabled").invoke(resolved) as Boolean
        val blurRadiusDp = cls.methods.first { it.name.startsWith("getBlurRadius") }.invoke(resolved) as Float
        val noiseFactor = cls.getMethod("getNoiseFactor").invoke(resolved) as Float
        @Suppress("UNCHECKED_CAST")
        val colorEffects = cls.getMethod("getColorEffects").invoke(resolved) as List<HazeColorEffect>
        val fallback = cls.getMethod("getFallbackColorEffect").invoke(resolved) as HazeColorEffect?
        return ResolvedHazeSnapshot(
            blurEnabled = blurEnabled,
            blurRadiusDp = blurRadiusDp,
            noiseFactor = noiseFactor,
            colorEffects = colorEffects,
            fallbackColorEffect = fallback,
        )
    }

    @Test
    fun hiTheme_providesDefaultGlassStyleViaCompositionLocalAndAllowsOverride() {
        var defaultGlass: HiGlassStyle? = null
        var themeProvidedGlass: HiGlassStyle? = null
        var localOverriddenGlass: HiGlassStyle? = null
        val customStyle = HiGlassStyle.Navigation.copy(
            blurRadius = 14.dp,
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
    fun assertGlassSurfaceCountAtMost_passesWhenTwoSurfacesRendered() {
        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.BlurEnabled) {
                    val glassState = rememberHiGlassState()
                    Column {
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

        composeRule.assertGlassSurfaceCountAtMost(2)
    }

    @Test
    fun assertGlassSurfaceCountAtMost_failsWhenThreeSurfacesRendered() {
        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.BlurEnabled) {
                    val glassState = rememberHiGlassState()
                    Column {
                        HiGlassSurface(state = glassState, modifier = Modifier.size(160.dp, 48.dp)) {
                            Text("工具条")
                        }
                        HiGlassSurface(state = glassState, modifier = Modifier.size(200.dp, 64.dp)) {
                            Text("导航胶囊")
                        }
                        HiGlassSurface(state = glassState, modifier = Modifier.size(160.dp, 48.dp)) {
                            Text("多余的第 3 块毛玻璃")
                        }
                    }
                }
            }
        }

        try {
            composeRule.assertGlassSurfaceCountAtMost(2)
            fail("Expected AssertionError when 3 HiGlassSurface nodes are rendered")
        } catch (expected: AssertionError) {
            assertTrue(
                "Error message should mention 2 and 3, was: ${expected.message}",
                expected.message?.contains("2") == true && expected.message?.contains("3") == true,
            )
        }
    }

    @Test
    fun degradedSurface_meetsWcag4_5ContrastAcrossAll20PresetsAndWorstCaseBackdrops() {
        HiColorPresets.ALL.forEach { preset ->
            listOf(
                Triple("light", hiColorSchemeFromSeed(preset.color, darkTheme = false), false),
                Triple("dark", hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = false), true),
                Triple("pureBlack", hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = true), true),
            ).forEach { (mode, scheme, darkTheme) ->
                val spec = resolveHiGlassStyleSpec(
                    colorScheme = scheme,
                    darkTheme = darkTheme,
                    blurEnabled = false,
                )
                val worstBackdrop = if (darkTheme) Color.White else Color.Black
                listOf("bg" to scheme.background, "worstBackdrop" to worstBackdrop).forEach { (bgName, backdrop) ->
                    assertWcagContrastAtLeast(
                        minRatio = 4.5f,
                        surface = spec.degradedSurfaceColor,
                        foreground = scheme.onSurface,
                        backdrop = backdrop,
                        label = "${preset.id}/$mode/$bgName onSurface",
                    )
                    assertWcagContrastAtLeast(
                        minRatio = 4.5f,
                        surface = spec.degradedSurfaceColor,
                        foreground = scheme.onSurfaceVariant,
                        backdrop = backdrop,
                        label = "${preset.id}/$mode/$bgName onSurfaceVariant",
                    )
                    assertWcagContrastAtLeast(
                        minRatio = 4.5f,
                        surface = spec.degradedSurfaceColor,
                        foreground = scheme.primary,
                        backdrop = backdrop,
                        label = "${preset.id}/$mode/$bgName primary",
                    )
                }
            }
        }
    }

    @Test
    fun degradedMode_detachesHazeBlurAndReleasesSourceDemand() {
        var envState by mutableStateOf(GlassEnvironmentState.BlurEnabled)
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

        val hazeState = requireNotNull(capturedGlassState).hazeState
        val demandGetter = hazeState.javaClass.methods.first { it.name.startsWith("getHasSourceDemand") }
        assertTrue("Active blur should demand haze source", demandGetter.invoke(hazeState) as Boolean)
        composeRule.assertAllGlassSurfacesBlurActive(true)

        envState = GlassEnvironmentState(setOf(GlassDegradationReason.POWER_SAVE_MODE))
        composeRule.waitForIdle()
        assertEquals(
            "Degraded mode must detach hazeBlur so HazeSourceNode releases offscreen layer",
            false,
            demandGetter.invoke(hazeState) as Boolean,
        )
        composeRule.assertAllGlassSurfacesBlurActive(false)
    }
}
