package net.jejer.hipda.data.network.webview

import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import net.jejer.hipda.data.network.DefaultUserAgentProvider
import net.jejer.hipda.data.network.UserAgentProvider

private const val DESKTOP_PLATFORM = "Linux"
private const val DESKTOP_ARCHITECTURE = "x86"
private const val DESKTOP_BITNESS = 64
private const val GREASE_BRAND = "Not_A Brand"
private const val GREASE_MAJOR_VERSION = "24"
private const val GREASE_FULL_VERSION = "24.0.0.0"

/**
 * 构建与桌面版 Linux Chrome UA 一致的 [UserAgentMetadata]：
 * `platform="Linux"`、`mobile=false`、`architecture="x86"`、`bitness=64`、`model=""`、
 * `platformVersion=""`，品牌版本与 [userAgent] 中的 Chrome 主版本一致。
 */
fun buildDesktopUserAgentMetadata(
    userAgent: String,
    includeDesktopFormFactor: Boolean = isFormFactorsFeatureSupported(),
): UserAgentMetadata {
    val majorVersion = extractChromeMajorVersion(userAgent)
        ?: DefaultUserAgentProvider.DEFAULT_CHROME_MAJOR_VERSION
    val fullVersion = "$majorVersion.0.0.0"

    val brands = listOf(
        UserAgentMetadata.BrandVersion.Builder()
            .setBrand("Chromium")
            .setMajorVersion(majorVersion)
            .setFullVersion(fullVersion)
            .build(),
        UserAgentMetadata.BrandVersion.Builder()
            .setBrand("Google Chrome")
            .setMajorVersion(majorVersion)
            .setFullVersion(fullVersion)
            .build(),
        UserAgentMetadata.BrandVersion.Builder()
            .setBrand(GREASE_BRAND)
            .setMajorVersion(GREASE_MAJOR_VERSION)
            .setFullVersion(GREASE_FULL_VERSION)
            .build(),
    )

    return UserAgentMetadata.Builder()
        .setBrandVersionList(brands)
        .setFullVersion(fullVersion)
        .setPlatform(DESKTOP_PLATFORM)
        .setPlatformVersion("")
        .setArchitecture(DESKTOP_ARCHITECTURE)
        .setModel("")
        .setMobile(false)
        .setBitness(DESKTOP_BITNESS)
        .setWow64(false)
        .apply {
            runCatching {
                if (includeDesktopFormFactor &&
                    WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)
                ) {
                    setFormFactors(listOf(UserAgentMetadata.FORM_FACTOR_DESKTOP))
                }
            }
        }
        .build()
}

/**
 * 统一 WebView 桌面身份配置入口：供挑战页 WebView（`ChallengeActivity`）与调试登录 WebView
 * （`SampleLoginWebView`）共用。
 *
 * 始终先写入 [WebSettings.setUserAgentString]；当运行环境支持
 * [WebViewFeature.USER_AGENT_METADATA] 时，同步通过 [WebSettingsCompat.setUserAgentMetadata]
 * 对齐 `navigator.userAgentData` 与 `Sec-CH-UA-*` Client Hints；不支持时仅设置 UA，不崩溃。
 */
fun applyUnifiedDesktopUserAgent(
    settings: WebSettings,
    userAgent: String,
) {
    applyUnifiedDesktopUserAgent(
        settings = settings,
        userAgent = userAgent,
        metadataBridge = AndroidWebViewUserAgentMetadataBridge,
    )
}

/** [applyUnifiedDesktopUserAgent] 的 [UserAgentProvider] 重载。 */
fun applyUnifiedDesktopUserAgent(
    settings: WebSettings,
    userAgentProvider: UserAgentProvider,
) {
    applyUnifiedDesktopUserAgent(
        settings = settings,
        userAgent = userAgentProvider.userAgent(),
    )
}

internal interface WebViewUserAgentMetadataBridge {
    fun isMetadataSupported(): Boolean
    fun isFormFactorsSupported(): Boolean
    fun setUserAgentMetadata(settings: WebSettings, metadata: UserAgentMetadata)
}

internal object AndroidWebViewUserAgentMetadataBridge : WebViewUserAgentMetadataBridge {
    override fun isMetadataSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)

    override fun isFormFactorsSupported(): Boolean = isFormFactorsFeatureSupported()

    override fun setUserAgentMetadata(settings: WebSettings, metadata: UserAgentMetadata) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        WebSettingsCompat.setUserAgentMetadata(settings, metadata)
    }
}

internal fun applyUnifiedDesktopUserAgent(
    settings: WebSettings,
    userAgent: String,
    metadataBridge: WebViewUserAgentMetadataBridge,
) {
    val effectiveUa = userAgent.ifBlank { DefaultUserAgentProvider.DEFAULT_DESKTOP_CHROME_UA }
    settings.userAgentString = effectiveUa

    val metadataSupported = runCatching { metadataBridge.isMetadataSupported() }.getOrDefault(false)
    if (!metadataSupported) return

    val formFactorsSupported = runCatching { metadataBridge.isFormFactorsSupported() }.getOrDefault(false)
    runCatching {
        val metadata = buildDesktopUserAgentMetadata(
            userAgent = effectiveUa,
            includeDesktopFormFactor = formFactorsSupported,
        )
        metadataBridge.setUserAgentMetadata(settings, metadata)
    }
}

private fun isFormFactorsFeatureSupported(): Boolean =
    runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)
    }.getOrDefault(false)
