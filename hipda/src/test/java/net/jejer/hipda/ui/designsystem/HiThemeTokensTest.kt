package net.jejer.hipda.ui.designsystem

import android.app.Application
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HiThemeTokensTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fontSizeSteps_match14To18SpWithDefault16Sp() {
        val expectedSp = listOf(14f, 15f, 16f, 17f, 18f)
        assertEquals(expectedSp, HiFontSizeStep.entries.map { it.baseSp })
        assertEquals(HiFontSizeStep.STEP_3, HiFontSizeStep.DEFAULT)
        assertEquals(16f, HiFontSizeStep.DEFAULT.baseSp, 0.001f)
        assertEquals(HiFontSizeStep.STEP_1, HiFontSizeStep.fromLevel(1))
        assertEquals(HiFontSizeStep.STEP_5, HiFontSizeStep.fromLevel(5))
        assertEquals(HiFontSizeStep.DEFAULT, HiFontSizeStep.fromLevel(99))

        HiFontSizeStep.entries.forEach { step ->
            val tokens = hiTypographyTokens(fontSizeStep = step)
            assertEquals(step.baseSp.sp, tokens.postBody.fontSize)
            assertEquals(step.baseSp.sp, tokens.threadTitle.fontSize)
            assertEquals(step.baseSp.sp, tokens.materialTypography.bodyLarge.fontSize)
            assertEquals(step.baseSp.sp, tokens.materialTypography.titleMedium.fontSize)
            assertEquals(FontFamily.Default, tokens.postBody.fontFamily)
            assertEquals(FontFamily.Default, tokens.threadTitle.fontFamily)
        }
    }

    @Test
    fun lineHeightPresets_matchPostBodyAndListTitleSpec() {
        assertEquals(HiLineHeightPreset.STANDARD, HiLineHeightPreset.DEFAULT)
        assertEquals(1.5f, HiLineHeightPreset.COMPACT.multiplier, 0.001f)
        assertEquals(1.65f, HiLineHeightPreset.STANDARD.multiplier, 0.001f)
        assertEquals(1.8f, HiLineHeightPreset.RELAXED.multiplier, 0.001f)

        HiLineHeightPreset.entries.forEach { preset ->
            val tokens = hiTypographyTokens(
                fontSizeStep = HiFontSizeStep.STEP_3,
                lineHeightPreset = preset,
            )
            assertEquals(16f * preset.multiplier, tokens.postBody.lineHeight.value, 0.01f)
            assertEquals(16f * 1.4f, tokens.threadTitle.lineHeight.value, 0.01f)
            assertEquals(FontWeight.Medium, tokens.threadTitle.fontWeight)
            assertEquals(FontWeight.Normal, tokens.threadTitleRead.fontWeight)
        }
    }

    @Test
    fun chromeFontScaleCap_limitsScaleAt125Percent() {
        val baseStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)

        val normal = baseStyle.capFontScale(currentFontScale = 1.0f)
        assertEquals(12f, normal.fontSize.value, 0.001f)
        assertEquals(16f, normal.lineHeight.value, 0.001f)

        val atLimit = baseStyle.capFontScale(currentFontScale = 1.25f)
        assertEquals(12f, atLimit.fontSize.value, 0.001f)

        val oversized = baseStyle.capFontScale(currentFontScale = 1.5f)
        assertEquals(12f * 1.25f, oversized.fontSize.value * 1.5f, 0.01f)
        assertEquals(16f * 1.25f, oversized.lineHeight.value * 1.5f, 0.01f)
    }

    @Test
    fun hiCappedFontScale_clampsLocalDensityInCompose() {
        var observedHigh = 0f
        var observedNormal = 0f
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 1.6f)) {
                HiCappedFontScale {
                    observedHigh = LocalDensity.current.fontScale
                }
            }
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 1.1f)) {
                HiCappedFontScale {
                    observedNormal = LocalDensity.current.fontScale
                }
            }
        }
        assertEquals(HI_CHROME_MAX_FONT_SCALE, observedHigh, 0.001f)
        assertEquals(1.1f, observedNormal, 0.001f)
    }

    @Test
    fun pureBlack_setsBlackBackgroundAndDarkensContainersInDarkModeOnly() {
        val standardDark = hiColorSchemeFromSeed(
            seedColor = HiColorPresets.Brand.color,
            darkTheme = true,
            pureBlack = false,
        )
        val pureBlackDark = hiColorSchemeFromSeed(
            seedColor = HiColorPresets.Brand.color,
            darkTheme = true,
            pureBlack = true,
        )
        val lightWithPureBlackFlag = hiColorSchemeFromSeed(
            seedColor = HiColorPresets.Brand.color,
            darkTheme = false,
            pureBlack = true,
        )

        assertEquals(Color.Black, pureBlackDark.background)
        assertEquals(Color.Black, pureBlackDark.surface)
        assertEquals(Color.Black, pureBlackDark.surfaceContainerLowest)
        assertNotEquals(Color.Black, standardDark.background)
        assertNotEquals(Color.Black, lightWithPureBlackFlag.background)

        assertTrue(pureBlackDark.surfaceContainerLow.luminance() < standardDark.surfaceContainerLow.luminance())
        assertTrue(pureBlackDark.surfaceContainer.luminance() < standardDark.surfaceContainer.luminance())
        assertTrue(pureBlackDark.surfaceContainerHigh.luminance() < standardDark.surfaceContainerHigh.luminance())
        assertTrue(pureBlackDark.surfaceContainerHighest.luminance() < standardDark.surfaceContainerHighest.luminance())

        assertTrue(pureBlackDark.surfaceContainerLow.luminance() < pureBlackDark.surfaceContainer.luminance())
        assertTrue(pureBlackDark.surfaceContainer.luminance() < pureBlackDark.surfaceContainerHigh.luminance())
        assertTrue(pureBlackDark.surfaceContainerHigh.luminance() < pureBlackDark.surfaceContainerHighest.luminance())
    }

    @Test
    fun differentSeeds_produceDistinctPrimaryColors() {
        val brandLight = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = false)
        val redLight = hiColorSchemeFromSeed(HiColorPresets.Red.color, darkTheme = false)
        val greenLight = hiColorSchemeFromSeed(HiColorPresets.Green.color, darkTheme = false)
        val purpleDark = hiColorSchemeFromSeed(HiColorPresets.Purple.color, darkTheme = true)
        val orangeDark = hiColorSchemeFromSeed(HiColorPresets.Orange.color, darkTheme = true)

        assertNotEquals(brandLight.primary, redLight.primary)
        assertNotEquals(brandLight.primary, greenLight.primary)
        assertNotEquals(redLight.primary, greenLight.primary)
        assertNotEquals(purpleDark.primary, orangeDark.primary)
    }

    @Test
    fun allPresets_haveLegacyProvenanceAndMeetWcagContrast() {
        assertEquals(Color(0xFF33B6EA), HiColorPresets.Brand.color)
        assertTrue(HiColorPresets.ALL.size >= 18)

        HiColorPresets.ALL.forEach { preset ->
            assertTrue("Missing legacy source for ${preset.id}", preset.legacySource.isNotBlank())
            listOf(
                hiColorSchemeFromSeed(preset.color, darkTheme = false, pureBlack = false),
                hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = false),
                hiColorSchemeFromSeed(preset.color, darkTheme = true, pureBlack = true),
            ).forEach { scheme ->
                assertContrastAtLeast(4.5f, scheme.primary, scheme.onPrimary, "${preset.id}: primary/onPrimary")
                assertContrastAtLeast(4.5f, scheme.primaryContainer, scheme.onPrimaryContainer, "${preset.id}: primaryContainer")
                assertContrastAtLeast(4.5f, scheme.surface, scheme.onSurface, "${preset.id}: surface/onSurface")
                assertContrastAtLeast(4.5f, scheme.surfaceContainerHighest, scheme.onSurface, "${preset.id}: surfaceHighest/onSurface")
                assertContrastAtLeast(4.5f, scheme.surfaceContainer, scheme.onSurfaceVariant, "${preset.id}: surfaceContainer/onSurfaceVariant")
            }
        }
    }

    @Test
    fun shapes_matchFourTierCornerSpec() {
        assertEquals(RoundedCornerShape(8.dp), HiShapeTokens.Small)
        assertEquals(RoundedCornerShape(12.dp), HiShapeTokens.Medium)
        assertEquals(RoundedCornerShape(20.dp), HiShapeTokens.Large)
        assertEquals(CircleShape, HiShapeTokens.Full)
        assertEquals(HiShapeTokens.Small, HiShapes.small)
        assertEquals(HiShapeTokens.Medium, HiShapes.medium)
        assertEquals(HiShapeTokens.Large, HiShapes.large)
        assertEquals(HiShapeTokens.Large, HiShapes.extraLarge)
    }

    @Test
    fun hiTheme_appliesPureBlackSeedAndDynamicColorPaths() {
        var pureBlackScheme: ColorScheme? = null
        var redScheme: ColorScheme? = null
        var tealScheme: ColorScheme? = null
        var wallpaperScheme: ColorScheme? = null

        composeRule.setContent {
            HiTheme(
                themeMode = HiThemeMode.DARK,
                pureBlack = true,
                seed = HiColorPresets.Brand.toSeed(),
            ) {
                pureBlackScheme = MaterialTheme.colorScheme
            }
            HiTheme(
                themeMode = HiThemeMode.LIGHT,
                pureBlack = false,
                seed = HiColorPresets.Red.toSeed(),
            ) {
                redScheme = MaterialTheme.colorScheme
            }
            HiTheme(
                themeMode = HiThemeMode.LIGHT,
                pureBlack = false,
                seed = HiColorPresets.Teal.toSeed(),
            ) {
                tealScheme = MaterialTheme.colorScheme
            }
            HiTheme(
                themeMode = HiThemeMode.LIGHT,
                pureBlack = false,
                seed = HiThemeSeed.Wallpaper,
            ) {
                wallpaperScheme = MaterialTheme.colorScheme
            }
        }

        val pureBlack = requireNotNull(pureBlackScheme)
        val red = requireNotNull(redScheme)
        val teal = requireNotNull(tealScheme)
        val wallpaper = requireNotNull(wallpaperScheme)
        assertEquals(Color.Black, pureBlack.background)
        assertEquals(Color.Black, pureBlack.surface)
        assertNotEquals(red.primary, teal.primary)
        assertNotEquals(Color.Unspecified, wallpaper.primary)
    }

    @Test
    @Config(sdk = [30])
    fun hiTheme_wallpaperFallbackUsesBrandColorPreAndroid12() {
        var wallpaperScheme: ColorScheme? = null
        val expectedBrand = hiColorSchemeFromSeed(HiColorPresets.Brand.color, darkTheme = false)

        composeRule.setContent {
            HiTheme(
                themeMode = HiThemeMode.LIGHT,
                pureBlack = false,
                seed = HiThemeSeed.Wallpaper,
            ) {
                wallpaperScheme = MaterialTheme.colorScheme
            }
        }

        val resolved = requireNotNull(wallpaperScheme)
        assertEquals(expectedBrand.primary, resolved.primary)
        assertEquals(expectedBrand.surface, resolved.surface)
    }

    private fun assertContrastAtLeast(minRatio: Float, background: Color, foreground: Color, label: String) {
        val l1 = background.luminance()
        val l2 = foreground.luminance()
        val ratio = (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)
        assertTrue("$label contrast was $ratio (< $minRatio)", ratio >= minRatio)
    }
}
