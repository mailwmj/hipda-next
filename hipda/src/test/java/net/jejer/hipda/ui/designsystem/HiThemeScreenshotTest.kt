package net.jejer.hipda.ui.designsystem

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val SPECIMEN_PHONE_411_QUALIFIER = "w411dp-h1260dp-xhdpi"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class HiThemeScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = SPECIMEN_PHONE_411_QUALIFIER)
    fun specimen_w411_light_step3() = captureSpecimen(
        name = "theme_specimen_w411_light_step3",
        themeMode = HiThemeMode.LIGHT,
        pureBlack = false,
        fontSizeStep = HiFontSizeStep.STEP_3,
    )

    @Test
    @Config(qualifiers = SPECIMEN_PHONE_411_QUALIFIER)
    fun specimen_w411_light_step5() = captureSpecimen(
        name = "theme_specimen_w411_light_step5",
        themeMode = HiThemeMode.LIGHT,
        pureBlack = false,
        fontSizeStep = HiFontSizeStep.STEP_5,
    )

    @Test
    @Config(qualifiers = SPECIMEN_PHONE_411_QUALIFIER)
    fun specimen_w411_dark_step3() = captureSpecimen(
        name = "theme_specimen_w411_dark_step3",
        themeMode = HiThemeMode.DARK,
        pureBlack = true,
        fontSizeStep = HiFontSizeStep.STEP_3,
    )

    @Test
    @Config(qualifiers = SPECIMEN_PHONE_411_QUALIFIER)
    fun specimen_w411_dark_step5() = captureSpecimen(
        name = "theme_specimen_w411_dark_step5",
        themeMode = HiThemeMode.DARK,
        pureBlack = false,
        fontSizeStep = HiFontSizeStep.STEP_5,
    )

    @Test
    @Config(qualifiers = SPECIMEN_PHONE_411_QUALIFIER)
    fun specimen_w411_pureBlack_step3() = captureSpecimen(
        name = "theme_specimen_w411_pure_black_step3",
        themeMode = HiThemeMode.DARK,
        pureBlack = true,
        fontSizeStep = HiFontSizeStep.STEP_3,
    )

    @Test
    @Config(qualifiers = SPECIMEN_PHONE_411_QUALIFIER)
    fun specimen_w411_pureBlack_step5() = captureSpecimen(
        name = "theme_specimen_w411_pure_black_step5",
        themeMode = HiThemeMode.DARK,
        pureBlack = true,
        fontSizeStep = HiFontSizeStep.STEP_5,
    )

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun specimen_w900_light_step3() = captureSpecimen(
        name = "theme_specimen_w900_light_step3",
        themeMode = HiThemeMode.LIGHT,
        pureBlack = false,
        fontSizeStep = HiFontSizeStep.STEP_3,
    )

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun specimen_w900_light_step5() = captureSpecimen(
        name = "theme_specimen_w900_light_step5",
        themeMode = HiThemeMode.LIGHT,
        pureBlack = false,
        fontSizeStep = HiFontSizeStep.STEP_5,
    )

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun specimen_w900_dark_step3() = captureSpecimen(
        name = "theme_specimen_w900_dark_step3",
        themeMode = HiThemeMode.DARK,
        pureBlack = false,
        fontSizeStep = HiFontSizeStep.STEP_3,
    )

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun specimen_w900_dark_step5() = captureSpecimen(
        name = "theme_specimen_w900_dark_step5",
        themeMode = HiThemeMode.DARK,
        pureBlack = false,
        fontSizeStep = HiFontSizeStep.STEP_5,
    )

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun specimen_w900_pureBlack_step3() = captureSpecimen(
        name = "theme_specimen_w900_pure_black_step3",
        themeMode = HiThemeMode.DARK,
        pureBlack = true,
        fontSizeStep = HiFontSizeStep.STEP_3,
    )

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun specimen_w900_pureBlack_step5() = captureSpecimen(
        name = "theme_specimen_w900_pure_black_step5",
        themeMode = HiThemeMode.DARK,
        pureBlack = true,
        fontSizeStep = HiFontSizeStep.STEP_5,
    )

    private fun captureSpecimen(
        name: String,
        themeMode: HiThemeMode,
        pureBlack: Boolean,
        fontSizeStep: HiFontSizeStep,
    ) {
        val state = ThemeSpecimenState(
            themeMode = themeMode,
            pureBlack = pureBlack,
            seed = HiColorPresets.Brand.toSeed(),
            fontSizeStep = fontSizeStep,
            lineHeightPreset = HiLineHeightPreset.STANDARD,
        )
        composeRule.setContent {
            HiTheme(
                themeMode = state.themeMode,
                pureBlack = state.pureBlack,
                seed = state.seed,
                fontSizeStep = state.fontSizeStep,
                lineHeightPreset = state.lineHeightPreset,
            ) {
                ThemeSpecimenScreen(
                    state = state,
                    onStateChange = {},
                )
            }
        }
        composeRule.onRoot().captureHiSnapshot(name)
    }
}
