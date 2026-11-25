package net.jejer.hipda.feature.common

import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiFontSizeStep
import net.jejer.hipda.ui.designsystem.HiLineHeightPreset
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.HiThemeSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiThemeSettingsAdapterTest {

    @Test
    fun defaultAppSettings_mapsLosslesslyToDefaultHiThemeConfig() {
        val config = HiThemeSettingsAdapter.toThemeConfig(AppSettings())
        assertEquals(HiThemeMode.SYSTEM, config.themeMode)
        assertFalse(config.pureBlack)
        assertEquals(HiThemeSeed.Wallpaper, config.seed)
        assertEquals(HiFontSizeStep.STEP_3, config.fontSizeStep)
        assertEquals(HiLineHeightPreset.STANDARD, config.lineHeightPreset)
    }

    @Test
    fun themeModePureBlackAndLineSpacing_mapAllEnumVariants() {
        val darkCompact = HiThemeSettingsAdapter.toThemeConfig(
            AppSettings(
                themeMode = ThemeMode.DARK,
                pureBlack = true,
                lineSpacing = LineSpacing.COMPACT,
            ),
        )
        assertEquals(HiThemeMode.DARK, darkCompact.themeMode)
        assertTrue(darkCompact.pureBlack)
        assertEquals(HiLineHeightPreset.COMPACT, darkCompact.lineHeightPreset)

        val lightLoose = HiThemeSettingsAdapter.toThemeConfig(
            AppSettings(
                themeMode = ThemeMode.LIGHT,
                pureBlack = false,
                lineSpacing = LineSpacing.LOOSE,
            ),
        )
        assertEquals(HiThemeMode.LIGHT, lightLoose.themeMode)
        assertFalse(lightLoose.pureBlack)
        assertEquals(HiLineHeightPreset.RELAXED, lightLoose.lineHeightPreset)
    }

    @Test
    fun themeSeed_handlesWallpaperAllPresetsAndUnknownFallbackToBrand() {
        assertEquals(HiThemeSeed.Wallpaper, HiThemeSettingsAdapter.resolveThemeSeed(AppSettings.THEME_SEED_WALLPAPER))
        assertEquals(HiThemeSeed.Wallpaper, HiThemeSettingsAdapter.resolveThemeSeed("wallpaper"))

        for (preset in HiColorPresets.ALL) {
            assertEquals(preset.toSeed(), HiThemeSettingsAdapter.resolveThemeSeed(preset.id))
        }

        assertEquals(HiColorPresets.Brand.toSeed(), HiThemeSettingsAdapter.resolveThemeSeed("unknown_seed_id"))
        assertEquals(HiColorPresets.Brand.toSeed(), HiThemeSettingsAdapter.resolveThemeSeed(""))
    }

    @Test
    fun fontStep_mapsZeroIndexedStepsAndClampsOutOfRange() {
        assertEquals(HiFontSizeStep.STEP_1, HiThemeSettingsAdapter.resolveFontSizeStep(-2))
        assertEquals(HiFontSizeStep.STEP_1, HiThemeSettingsAdapter.resolveFontSizeStep(0))
        assertEquals(HiFontSizeStep.STEP_2, HiThemeSettingsAdapter.resolveFontSizeStep(1))
        assertEquals(HiFontSizeStep.STEP_3, HiThemeSettingsAdapter.resolveFontSizeStep(2))
        assertEquals(HiFontSizeStep.STEP_4, HiThemeSettingsAdapter.resolveFontSizeStep(3))
        assertEquals(HiFontSizeStep.STEP_5, HiThemeSettingsAdapter.resolveFontSizeStep(4))
        assertEquals(HiFontSizeStep.STEP_5, HiThemeSettingsAdapter.resolveFontSizeStep(99))
    }
}
