package net.jejer.hipda.data.network.webview

import android.webkit.CookieManager
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin abstraction over Android WebView's [CookieManager] and [CookieManagerCompat] so
 * [WebViewCookieBridge] stays testable without relying on Robolectric's limited
 * `ShadowCookieManager`.
 */
interface WebViewCookieAccess {
    fun isGetCookieInfoSupported(): Boolean
    fun getCookieInfo(url: String): List<String>
    fun getCookie(url: String): String?
    fun setCookie(url: String, value: String)
    fun flush()
}

/**
 * Production [WebViewCookieAccess] delegating directly to [CookieManager] / [CookieManagerCompat].
 *
 * Resolves [CookieManager.getInstance] lazily inside each method instead of in the constructor so
 * Hilt singleton assembly never initializes WebView on the main thread.
 */
@Singleton
class AndroidWebViewCookieAccess @Inject constructor() : WebViewCookieAccess {

    override fun isGetCookieInfoSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)

    override fun getCookieInfo(url: String): List<String> {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) {
            return emptyList()
        }
        return CookieManagerCompat.getCookieInfo(CookieManager.getInstance(), url)
    }

    override fun getCookie(url: String): String? =
        CookieManager.getInstance().getCookie(url)

    override fun setCookie(url: String, value: String) {
        CookieManager.getInstance().setCookie(url, value)
    }

    override fun flush() {
        CookieManager.getInstance().flush()
    }
}
