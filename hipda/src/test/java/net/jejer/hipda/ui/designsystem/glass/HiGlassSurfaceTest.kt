package net.jejer.hipda.ui.designsystem.glass

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.hiColorSchemeFromSeed
import org.junit.Assert.assertEquals
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
    fun resolveStyleSpec_matchesProductDesignParameters() {
        val lightScheme = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = false)
        val darkScheme = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = true)

        val lightBlur = resolveHiGlassStyleSpec(
            colorScheme = lightScheme,
            darkTheme = false,
            blurEnabled = true,
            floating = true,
        )
        assertEquals(24.dp, lightBlur.blurRadius)
        assertEquals(lightScheme.surface.copy(alpha = 0.70f), lightBlur.activeTintColor)
        assertEquals(lightScheme.surface.copy(alpha = 0.94f), lightBlur.degradedSurfaceColor)
        assertEquals(0.5.dp, lightBlur.borderWidth)
        assertEquals(Color.White.copy(alpha = 0.40f), lightBlur.borderColor)
        assertTrue(lightBlur.shadowElevation > 0.dp)

        val darkBlur = resolveHiGlassStyleSpec(
            colorScheme = darkScheme,
            darkTheme = true,
            blurEnabled = true,
            floating = false,
        )
        assertEquals(24.dp, darkBlur.blurRadius)
        assertEquals(darkScheme.surface.copy(alpha = 0.60f), darkBlur.activeTintColor)
        assertEquals(Color.White.copy(alpha = 0.12f), darkBlur.borderColor)
        assertEquals(0.dp, darkBlur.shadowElevation)

        val lightDegraded = resolveHiGlassStyleSpec(
            colorScheme = lightScheme,
            darkTheme = false,
            blurEnabled = false,
            floating = true,
        )
        assertEquals(lightScheme.surface.copy(alpha = 0.94f), lightDegraded.degradedSurfaceColor)
        assertEquals(lightScheme.outlineVariant, lightDegraded.borderColor)
    }

    @Test
    fun assertGlassSurfaceCountAtMost_passesWhenTwoSurfacesRendered() {
        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.BlurEnabled) {
                    val glassState = rememberHiGlassState()
                    Column {
                        HiGlassSurface(state = glassState, modifier = Modifier.size(160.dp, 48.dp)) {
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
}
