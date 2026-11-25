package net.jejer.hipda.data.network

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Table-driven tests for [selectProvider] across Android API 33/34/36 and provider combinations.
 */
@RunWith(Parameterized::class)
class CronetEngineSelectorTest(
    private val caseName: String,
    private val sdkInt: Int,
    private val candidates: List<ProviderInfo>,
    private val expectedName: String?,
) {

    @Test
    fun selectProvider_matchesExpectedPriority() {
        val selected = selectProvider(candidates, sdkInt)
        assertEquals(caseName, expectedName, selected?.name)
    }

    companion object {
        private val httpEngineEnabled = ProviderInfo(PROVIDER_NAME_HTTP_ENGINE, isEnabled = true)
        private val httpEngineDisabled = ProviderInfo(PROVIDER_NAME_HTTP_ENGINE, isEnabled = false)
        private val playServicesEnabled = ProviderInfo(PROVIDER_NAME_PLAY_SERVICES, isEnabled = true)
        private val playServicesDisabled = ProviderInfo(PROVIDER_NAME_PLAY_SERVICES, isEnabled = false)
        private val appPackagedEnabled = ProviderInfo(PROVIDER_NAME_APP_PACKAGED, isEnabled = true)
        private val fallbackEnabled = ProviderInfo(PROVIDER_NAME_FALLBACK, isEnabled = true)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any?>> = listOf(
            // API 33: HttpEngine must never be chosen even when reported enabled (e.g. Mainline backport)
            arrayOf(
                "API 33: HttpEngine + PlayServices + Fallback -> PlayServices",
                33,
                listOf(httpEngineEnabled, playServicesEnabled, fallbackEnabled),
                PROVIDER_NAME_PLAY_SERVICES,
            ),
            arrayOf(
                "API 33: HttpEngine + Fallback only -> null",
                33,
                listOf(httpEngineEnabled, fallbackEnabled),
                null,
            ),
            arrayOf(
                "API 33: PlayServices disabled + Fallback -> null",
                33,
                listOf(playServicesDisabled, fallbackEnabled),
                null,
            ),
            arrayOf(
                "API 33: Fallback only -> null",
                33,
                listOf(fallbackEnabled),
                null,
            ),
            arrayOf(
                "API 33: App-Packaged + Fallback -> null (no bundled native Cronet)",
                33,
                listOf(appPackagedEnabled, fallbackEnabled),
                null,
            ),
            arrayOf(
                "API 33: PlayServices enabled only -> PlayServices",
                33,
                listOf(playServicesEnabled),
                PROVIDER_NAME_PLAY_SERVICES,
            ),
            // API 34: HttpEngine preferred over PlayServices regardless of list order
            arrayOf(
                "API 34: PlayServices before HttpEngine -> HttpEngine",
                34,
                listOf(playServicesEnabled, httpEngineEnabled, fallbackEnabled),
                PROVIDER_NAME_HTTP_ENGINE,
            ),
            arrayOf(
                "API 34: HttpEngine disabled + PlayServices enabled -> PlayServices",
                34,
                listOf(httpEngineDisabled, playServicesEnabled, fallbackEnabled),
                PROVIDER_NAME_PLAY_SERVICES,
            ),
            arrayOf(
                "API 34: HttpEngine enabled only -> HttpEngine",
                34,
                listOf(httpEngineEnabled),
                PROVIDER_NAME_HTTP_ENGINE,
            ),
            arrayOf(
                "API 34: Fallback only -> null",
                34,
                listOf(fallbackEnabled),
                null,
            ),
            arrayOf(
                "API 34: Both HttpEngine and PlayServices disabled + Fallback -> null",
                34,
                listOf(httpEngineDisabled, playServicesDisabled, fallbackEnabled),
                null,
            ),
            arrayOf(
                "API 34: App-Packaged + Fallback -> null",
                34,
                listOf(appPackagedEnabled, fallbackEnabled),
                null,
            ),
            // API 36: Same priority as API 34
            arrayOf(
                "API 36: All providers enabled -> HttpEngine",
                36,
                listOf(appPackagedEnabled, playServicesEnabled, httpEngineEnabled, fallbackEnabled),
                PROVIDER_NAME_HTTP_ENGINE,
            ),
            arrayOf(
                "API 36: HttpEngine missing + PlayServices enabled -> PlayServices",
                36,
                listOf(playServicesEnabled, fallbackEnabled),
                PROVIDER_NAME_PLAY_SERVICES,
            ),
            arrayOf(
                "API 36: Fallback only -> null",
                36,
                listOf(fallbackEnabled),
                null,
            ),
            arrayOf(
                "API 36: Empty candidates -> null",
                36,
                emptyList<ProviderInfo>(),
                null,
            ),
        )
    }
}
