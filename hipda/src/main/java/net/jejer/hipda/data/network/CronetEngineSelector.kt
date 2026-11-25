package net.jejer.hipda.data.network

import com.google.android.gms.net.CronetProviderInstaller
import org.chromium.net.CronetProvider

/** Descriptor of a discovered Cronet provider used by [selectProvider]. */
data class ProviderInfo(
    val name: String,
    val isEnabled: Boolean,
)

const val HTTP_ENGINE_MIN_SDK: Int = 34
const val PROVIDER_NAME_HTTP_ENGINE: String = CronetProvider.PROVIDER_NAME_HTTPENGINE_NATIVE
const val PROVIDER_NAME_PLAY_SERVICES: String = CronetProviderInstaller.PROVIDER_NAME
const val PROVIDER_NAME_FALLBACK: String = CronetProvider.PROVIDER_NAME_FALLBACK
const val PROVIDER_NAME_APP_PACKAGED: String = CronetProvider.PROVIDER_NAME_APP_PACKAGED

/**
 * Selects the highest-priority Chrome-compatible Cronet provider for [sdkInt]:
 * 1. System HttpEngine ([PROVIDER_NAME_HTTP_ENGINE]) when `sdkInt >= 34`.
 * 2. Google Play Services Cronet ([PROVIDER_NAME_PLAY_SERVICES]).
 * 3. `null` to fall back to OkHttp's own transport.
 *
 * Never selects [PROVIDER_NAME_FALLBACK] (`JavaCronetProvider`, backed by HttpURLConnection) or
 * [PROVIDER_NAME_APP_PACKAGED] (no embedded Cronet native libraries are packaged in this APK).
 */
fun selectProvider(candidates: List<ProviderInfo>, sdkInt: Int): ProviderInfo? {
    if (sdkInt >= HTTP_ENGINE_MIN_SDK) {
        candidates.firstOrNull { it.isEnabled && it.name == PROVIDER_NAME_HTTP_ENGINE }
            ?.let { return it }
    }
    return candidates.firstOrNull { it.isEnabled && it.name == PROVIDER_NAME_PLAY_SERVICES }
}
