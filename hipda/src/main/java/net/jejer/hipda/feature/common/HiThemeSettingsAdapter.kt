package net.jejer.hipda.feature.common

import androidx.compose.runtime.Immutable
import net.jejer.hipda.data.settings.*
import net.jejer.hipda.ui.designsystem.*

@Immutable
data class HiThemeConfig(
    val themeMode: HiThemeMode,
    val pureBlack: Boolean,
    val seed: HiThemeSeed,
    val fontSizeStep: HiFontSizeStep,
    val lineHeightPreset: HiLineHeightPreset,
)

/** 无损适配层：将 [AppSettings] 映射到设计系统 `HiTheme` 参数。 */
object HiThemeSettingsAdapter {
    fun toThemeConfig(settings: AppSettings): HiThemeConfig = HiThemeConfig(
        themeMode = when (settings.themeMode) {
            ThemeMode.SYSTEM -> HiThemeMode.SYSTEM
            ThemeMode.LIGHT -> HiThemeMode.LIGHT
            ThemeMode.DARK -> HiThemeMode.DARK
        },
        pureBlack = settings.pureBlack,
        seed = resolveThemeSeed(settings.themeSeed),
        fontSizeStep = resolveFontSizeStep(settings.fontStep),
        lineHeightPreset = when (settings.lineSpacing) {
            LineSpacing.COMPACT -> HiLineHeightPreset.COMPACT
            LineSpacing.STANDARD -> HiLineHeightPreset.STANDARD
            LineSpacing.LOOSE -> HiLineHeightPreset.RELAXED
        },
    )

    fun resolveThemeSeed(themeSeed: String): HiThemeSeed {
        val trimmed = themeSeed.trim()
        if (trimmed.equals(AppSettings.THEME_SEED_WALLPAPER, ignoreCase = true)) return HiThemeSeed.Wallpaper
        return HiColorPresets.findById(trimmed)?.toSeed() ?: HiColorPresets.Brand.toSeed()
    }

    /** `AppSettings.fontStep` 为 0..4（默认 2），映射至 `HiFontSizeStep.level` 1..5（默认 STEP_3）。 */
    fun resolveFontSizeStep(fontStep: Int): HiFontSizeStep =
        HiFontSizeStep.fromLevel(fontStep.coerceIn(AppSettings.MIN_FONT_STEP, AppSettings.MAX_FONT_STEP) + 1)
}
