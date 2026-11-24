package net.jejer.hipda.ui.designsystem.glass

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GlassEnvironmentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun normalEnvironment_api31AndAbove_enablesBlur() {
        for (sdk in listOf(Build.VERSION_CODES.S, Build.VERSION_CODES.UPSIDE_DOWN_CAKE, Build.VERSION_CODES.BAKLAVA)) {
            val env = GlassEnvironment(
                FakeGlassEnvironmentSignalSource(
                    GlassEnvironmentSignals(
                        sdkInt = sdk,
                        isPowerSaveMode = false,
                        isReduceTransparencyEnabled = false,
                        isHighContrastTextEnabled = false,
                    ),
                ),
            )
            val state = env.currentState()
            assertTrue("API $sdk should enable blur", state.blurEnabled)
            assertTrue("API $sdk should have no degradation reasons", state.reasons.isEmpty())
        }
    }

    @Test
    fun api30OrLower_degradesWithUnsupportedApiLevel() {
        for (sdk in listOf(Build.VERSION_CODES.O, Build.VERSION_CODES.Q, Build.VERSION_CODES.R)) {
            val env = GlassEnvironment(
                FakeGlassEnvironmentSignalSource(
                    GlassEnvironmentSignals(
                        sdkInt = sdk,
                        isPowerSaveMode = false,
                        isReduceTransparencyEnabled = false,
                        isHighContrastTextEnabled = false,
                    ),
                ),
            )
            val state = env.currentState()
            assertFalse("API $sdk should disable blur", state.blurEnabled)
            assertEquals(setOf(GlassDegradationReason.UNSUPPORTED_API_LEVEL), state.reasons)
        }
    }

    @Test
    fun powerSaveMode_degradesWithPowerSaveReason() {
        val env = GlassEnvironment(
            FakeGlassEnvironmentSignalSource(
                GlassEnvironmentSignals(
                    sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    isPowerSaveMode = true,
                    isReduceTransparencyEnabled = false,
                    isHighContrastTextEnabled = false,
                ),
            ),
        )
        val state = env.currentState()
        assertFalse(state.blurEnabled)
        assertEquals(setOf(GlassDegradationReason.POWER_SAVE_MODE), state.reasons)
    }

    @Test
    fun reduceTransparency_degradesWithReduceTransparencyReason() {
        val env = GlassEnvironment(
            FakeGlassEnvironmentSignalSource(
                GlassEnvironmentSignals(
                    sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    isPowerSaveMode = false,
                    isReduceTransparencyEnabled = true,
                    isHighContrastTextEnabled = false,
                ),
            ),
        )
        val state = env.currentState()
        assertFalse(state.blurEnabled)
        assertEquals(setOf(GlassDegradationReason.REDUCE_TRANSPARENCY), state.reasons)
    }

    @Test
    fun highContrastText_degradesWithHighContrastReason() {
        val env = GlassEnvironment(
            FakeGlassEnvironmentSignalSource(
                GlassEnvironmentSignals(
                    sdkInt = Build.VERSION_CODES.BAKLAVA,
                    isPowerSaveMode = false,
                    isReduceTransparencyEnabled = false,
                    isHighContrastTextEnabled = true,
                ),
            ),
        )
        val state = env.currentState()
        assertFalse(state.blurEnabled)
        assertEquals(setOf(GlassDegradationReason.HIGH_CONTRAST_TEXT), state.reasons)
    }

    @Test
    fun observeState_combinesMultipleReasonsAndRecoversOnlyWhenAllClear() = runTest {
        val fakeSource = FakeGlassEnvironmentSignalSource()
        val env = GlassEnvironment(fakeSource)

        env.observeState().test {
            assertEquals(GlassEnvironmentState.BlurEnabled, awaitItem())

            fakeSource.update { it.copy(isPowerSaveMode = true) }
            val powerSave = awaitItem()
            assertFalse(powerSave.blurEnabled)
            assertEquals(setOf(GlassDegradationReason.POWER_SAVE_MODE), powerSave.reasons)

            // Same state should be deduplicated by distinctUntilChanged
            fakeSource.update { it.copy(isPowerSaveMode = true) }
            expectNoEvents()

            fakeSource.update { it.copy(isReduceTransparencyEnabled = true, isHighContrastTextEnabled = true) }
            val multi = awaitItem()
            assertFalse(multi.blurEnabled)
            assertEquals(
                setOf(
                    GlassDegradationReason.POWER_SAVE_MODE,
                    GlassDegradationReason.REDUCE_TRANSPARENCY,
                    GlassDegradationReason.HIGH_CONTRAST_TEXT,
                ),
                multi.reasons,
            )

            fakeSource.update { it.copy(isPowerSaveMode = false) }
            val stillDegraded = awaitItem()
            assertFalse(stillDegraded.blurEnabled)
            assertEquals(
                setOf(
                    GlassDegradationReason.REDUCE_TRANSPARENCY,
                    GlassDegradationReason.HIGH_CONTRAST_TEXT,
                ),
                stillDegraded.reasons,
            )

            fakeSource.update {
                it.copy(isReduceTransparencyEnabled = false, isHighContrastTextEnabled = false)
            }
            val recovered = awaitItem()
            assertTrue(recovered.blurEnabled)
            assertTrue(recovered.reasons.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    @Config(sdk = [30])
    fun androidSignalSource_api30_reportsUnsupportedApiLevel() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val env = GlassEnvironment(AndroidGlassEnvironmentSignalSource(context))
        val state = env.currentState()
        assertFalse(state.blurEnabled)
        assertTrue(GlassDegradationReason.UNSUPPORTED_API_LEVEL in state.reasons)
    }

    @Test
    fun androidSignalSource_powerSaveBroadcast_updatesObserveState() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val powerManager = context.getSystemService(PowerManager::class.java)
        val shadowPowerManager = Shadows.shadowOf(powerManager)
        shadowPowerManager.setIsPowerSaveMode(false)

        val env = GlassEnvironment(AndroidGlassEnvironmentSignalSource(context))
        env.observeState().test {
            val initial = awaitItem()
            assertTrue(initial.blurEnabled)

            shadowPowerManager.setIsPowerSaveMode(true)
            context.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val degraded = awaitItem()
            assertFalse(degraded.blurEnabled)
            assertEquals(setOf(GlassDegradationReason.POWER_SAVE_MODE), degraded.reasons)

            shadowPowerManager.setIsPowerSaveMode(false)
            context.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val recovered = awaitItem()
            assertTrue(recovered.blurEnabled)
            assertTrue(recovered.reasons.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun androidSignalSource_settingsKeys_detectReduceTransparencyAndHighContrast() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val resolver = context.contentResolver
        val env = GlassEnvironment(AndroidGlassEnvironmentSignalSource(context))

        try {
            assertTrue(env.currentState().blurEnabled)

            Settings.Global.putInt(resolver, AndroidGlassEnvironmentSignalSource.SETTING_DISABLE_WINDOW_BLURS, 1)
            assertEquals(setOf(GlassDegradationReason.REDUCE_TRANSPARENCY), env.currentState().reasons)
            Settings.Global.putInt(resolver, AndroidGlassEnvironmentSignalSource.SETTING_DISABLE_WINDOW_BLURS, 0)

            Settings.Global.putInt(resolver, AndroidGlassEnvironmentSignalSource.SETTING_REDUCE_TRANSPARENCY, 1)
            assertEquals(setOf(GlassDegradationReason.REDUCE_TRANSPARENCY), env.currentState().reasons)
            Settings.Global.putInt(resolver, AndroidGlassEnvironmentSignalSource.SETTING_REDUCE_TRANSPARENCY, 0)

            Settings.Secure.putInt(
                resolver,
                AndroidGlassEnvironmentSignalSource.SETTING_HIGH_TEXT_CONTRAST_ENABLED,
                1,
            )
            assertEquals(setOf(GlassDegradationReason.HIGH_CONTRAST_TEXT), env.currentState().reasons)
        } finally {
            Settings.Global.putInt(resolver, AndroidGlassEnvironmentSignalSource.SETTING_DISABLE_WINDOW_BLURS, 0)
            Settings.Global.putInt(resolver, AndroidGlassEnvironmentSignalSource.SETTING_REDUCE_TRANSPARENCY, 0)
            Settings.Secure.putInt(
                resolver,
                AndroidGlassEnvironmentSignalSource.SETTING_HIGH_TEXT_CONTRAST_ENABLED,
                0,
            )
        }
    }

    @Test
    fun provideGlassEnvironment_recomposesSurfaceWhenSignalSourceChanges() {
        val fakeSource = FakeGlassEnvironmentSignalSource()
        composeRule.setContent {
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                ProvideGlassEnvironment(signalSource = fakeSource) {
                    val glassState = rememberHiGlassState()
                    HiGlassSurface(
                        state = glassState,
                        modifier = Modifier.size(120.dp, 64.dp),
                    ) {}
                }
            }
        }

        composeRule.assertAllGlassSurfacesBlurActive(true)

        fakeSource.update { it.copy(isPowerSaveMode = true) }
        composeRule.waitForIdle()
        composeRule.assertAllGlassSurfacesBlurActive(false)

        fakeSource.update { it.copy(isPowerSaveMode = false) }
        composeRule.waitForIdle()
        composeRule.assertAllGlassSurfacesBlurActive(true)
    }
}
