package net.jejer.hipda.ui.designsystem.glass

import android.app.Application
import android.app.UiModeManager
import android.content.ContentResolver
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowUIModeManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GlassEnvironmentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun normalEnvironment_api33AndAbove_enablesRefractionAndBlur() {
        for (sdk in listOf(Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.UPSIDE_DOWN_CAKE, Build.VERSION_CODES.BAKLAVA)) {
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
            assertTrue("API $sdk should enable refraction", state.refractionEnabled)
            assertEquals("API $sdk should be REFRACTION_AND_BLUR", GlassRenderTier.REFRACTION_AND_BLUR, state.renderTier)
            assertTrue("API $sdk should have no degradation reasons", state.reasons.isEmpty())
        }
    }

    @Test
    fun normalEnvironment_api31To32_enablesBlurOnlyWithoutRefraction() {
        for (sdk in listOf(Build.VERSION_CODES.S, Build.VERSION_CODES.S_V2)) {
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
            assertFalse("API $sdk must disable refraction", state.refractionEnabled)
            assertEquals("API $sdk should be BLUR_ONLY", GlassRenderTier.BLUR_ONLY, state.renderTier)
            assertEquals(GlassEnvironmentState.BlurOnly, state)
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
            assertFalse("API $sdk should disable refraction", state.refractionEnabled)
            assertEquals("API $sdk should be SOLID_DEGRADED", GlassRenderTier.SOLID_DEGRADED, state.renderTier)
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
        assertFalse(state.refractionEnabled)
        assertEquals(GlassRenderTier.SOLID_DEGRADED, state.renderTier)
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
        assertFalse(state.refractionEnabled)
        assertEquals(GlassRenderTier.SOLID_DEGRADED, state.renderTier)
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
        assertFalse(state.refractionEnabled)
        assertEquals(GlassRenderTier.SOLID_DEGRADED, state.renderTier)
        assertEquals(setOf(GlassDegradationReason.HIGH_CONTRAST_TEXT), state.reasons)
    }

    @Test
    @Config(sdk = [31])
    fun androidSignalSource_api31_reportsBlurOnlyTier() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val env = GlassEnvironment(AndroidGlassEnvironmentSignalSource(context))
        val state = env.currentState()
        assertTrue(state.blurEnabled)
        assertFalse(state.refractionEnabled)
        assertEquals(GlassRenderTier.BLUR_ONLY, state.renderTier)
        assertTrue(state.reasons.isEmpty())
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stateIn_defaultWhileSubscribed_releasesUpstreamListenerWhenNoCollectors() = runTest {
        var activeSubscriptions = 0
        val baseSignals = GlassEnvironmentSignals(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            isPowerSaveMode = false,
            isReduceTransparencyEnabled = false,
            isHighContrastTextEnabled = false,
        )
        val trackedSource = object : GlassEnvironmentSignalSource {
            override fun currentSignals(): GlassEnvironmentSignals = baseSignals
            override fun observeSignals(): Flow<GlassEnvironmentSignals> = callbackFlow {
                activeSubscriptions += 1
                trySend(baseSignals)
                awaitClose { activeSubscriptions -= 1 }
            }
        }

        val stateFlow = GlassEnvironment(trackedSource).stateIn(backgroundScope)
        runCurrent()
        assertEquals("Should not subscribe upstream without active collectors", 0, activeSubscriptions)

        val collector = launch { stateFlow.collect {} }
        runCurrent()
        assertEquals("Should subscribe upstream once a collector attaches", 1, activeSubscriptions)

        collector.cancel()
        runCurrent()
        assertEquals("Should keep upstream alive during 5s stop timeout", 1, activeSubscriptions)

        advanceTimeBy(5_001L)
        runCurrent()
        assertEquals("Should release upstream listener after 5s with no collectors", 0, activeSubscriptions)
    }

    @Test
    fun androidSignalSource_settingsSecurityException_fallsBackToFalseWithoutCrash() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val restrictedReader = object : SettingsIntReader {
            override fun getGlobalInt(resolver: ContentResolver, key: String, default: Int): Int =
                throw SecurityException("Global setting $key not readable")

            override fun getSystemInt(resolver: ContentResolver, key: String, default: Int): Int =
                throw SecurityException("System setting $key not readable")

            override fun getSecureInt(resolver: ContentResolver, key: String, default: Int): Int =
                throw SecurityException("Secure setting $key not readable")
        }

        val env = GlassEnvironment(
            AndroidGlassEnvironmentSignalSource(
                context = context,
                sdkIntProvider = { Build.VERSION_CODES.UPSIDE_DOWN_CAKE },
                settingsReader = restrictedReader,
            ),
        )
        val state = env.currentState()
        assertTrue(state.blurEnabled)
        assertTrue(state.reasons.isEmpty())
    }

    @Test
    fun androidSignalSource_contentObserver_emitsOnGlobalAndSecureSettingsChanges() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val resolver = context.contentResolver
        val blurUri = Settings.Global.getUriFor(AndroidGlassEnvironmentSignalSource.SETTING_DISABLE_WINDOW_BLURS)
        val highContrastUri = Settings.Secure.getUriFor(
            AndroidGlassEnvironmentSignalSource.SETTING_HIGH_TEXT_CONTRAST_ENABLED,
        )

        val env = GlassEnvironment(AndroidGlassEnvironmentSignalSource(context))
        try {
            env.observeState().test {
                assertTrue(awaitItem().blurEnabled)

                Settings.Global.putInt(resolver, AndroidGlassEnvironmentSignalSource.SETTING_DISABLE_WINDOW_BLURS, 1)
                resolver.notifyChange(blurUri, null)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val reduceTransparency = awaitItem()
                assertFalse(reduceTransparency.blurEnabled)
                assertEquals(setOf(GlassDegradationReason.REDUCE_TRANSPARENCY), reduceTransparency.reasons)

                Settings.Global.putInt(resolver, AndroidGlassEnvironmentSignalSource.SETTING_DISABLE_WINDOW_BLURS, 0)
                resolver.notifyChange(blurUri, null)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertTrue(awaitItem().blurEnabled)

                Settings.Secure.putInt(
                    resolver,
                    AndroidGlassEnvironmentSignalSource.SETTING_HIGH_TEXT_CONTRAST_ENABLED,
                    1,
                )
                resolver.notifyChange(highContrastUri, null)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val highContrast = awaitItem()
                assertFalse(highContrast.blurEnabled)
                assertEquals(setOf(GlassDegradationReason.HIGH_CONTRAST_TEXT), highContrast.reasons)

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            Settings.Global.putInt(resolver, AndroidGlassEnvironmentSignalSource.SETTING_DISABLE_WINDOW_BLURS, 0)
            Settings.Secure.putInt(
                resolver,
                AndroidGlassEnvironmentSignalSource.SETTING_HIGH_TEXT_CONTRAST_ENABLED,
                0,
            )
        }
    }

    @Test
    fun androidSignalSource_api34UiModeContrastListener_updatesObserveState() = runTest {
        // Robolectric 4.18.1 shadows UiModeManager.setContrast + ContrastChangeListener (API 34+),
        // whereas ShadowAccessibilityManager does not yet shadow API 36 isHighContrastTextEnabled.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val uiModeManager = context.getSystemService(UiModeManager::class.java)
        val shadowUiMode = Shadow.extract<ShadowUIModeManager>(uiModeManager)
        shadowUiMode.setContrast(0.0f)

        val env = GlassEnvironment(AndroidGlassEnvironmentSignalSource(context))
        try {
            env.observeState().test {
                assertTrue(awaitItem().blurEnabled)

                shadowUiMode.setContrast(0.5f)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val mediumContrast = awaitItem()
                assertFalse(mediumContrast.blurEnabled)
                assertEquals(setOf(GlassDegradationReason.HIGH_CONTRAST_TEXT), mediumContrast.reasons)

                shadowUiMode.setContrast(0.0f)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val recovered = awaitItem()
                assertTrue(recovered.blurEnabled)
                assertTrue(recovered.reasons.isEmpty())

                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            shadowUiMode.setContrast(0.0f)
        }
    }
}
