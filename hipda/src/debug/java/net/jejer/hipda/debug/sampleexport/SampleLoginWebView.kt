package net.jejer.hipda.debug.sampleexport

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import net.jejer.hipda.R
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.ui.designsystem.HiShapeTokens

/**
 * 配置内置登录 WebView：
 * - 显式统一 UA 为 [UserAgentProvider.userAgent]（落实 R-A5a）；
 * - 开启 JS、DOM Storage 及一/三方 Cookie；
 * - 限制可导航协议为 http/https/about，并在页面导航变化时回调以触发 Cookie 同步。
 */
@SuppressLint("SetJavaScriptEnabled")
fun configureSampleLoginWebView(
    webView: WebView,
    userAgentProvider: UserAgentProvider,
    onPageNavigationChanged: (String?) -> Unit,
): WebView = webView.apply {
    settings.apply {
        userAgentString = userAgentProvider.userAgent()
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
    }
    runCatching {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url?.scheme?.lowercase(Locale.ROOT) ?: return false
            return scheme != "http" && scheme != "https" && scheme != "about"
        }

        override fun onPageFinished(view: WebView?, url: String?) = onPageNavigationChanged(url)

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) = onPageNavigationChanged(url)

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            destroyWebViewSafely(view)
            return true
        }
    }
}

private object DetachedLoginWebViewClient : WebViewClient() {
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean = true
}

private fun destroyWebViewSafely(webView: WebView?) {
    if (webView == null) return
    runCatching {
        webView.stopLoading()
        webView.webViewClient = DetachedLoginWebViewClient
        webView.webChromeClient = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }
}

@Composable
internal fun SampleLoginWebViewOverlay(
    userAgentProvider: UserAgentProvider,
    onPageNavigationChanged: (String?) -> Unit,
    onCompleteLogin: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentNavCallback = rememberUpdatedState(onPageNavigationChanged)
    val webView = remember(context, userAgentProvider) {
        configureSampleLoginWebView(
            webView = WebView(context),
            userAgentProvider = userAgentProvider,
            onPageNavigationChanged = { url -> currentNavCallback.value(url) },
        ).apply { loadUrl(SampleExportConstants.LOGIN_URL_STRING) }
    }

    BackHandler(onBack = onClose)
    DisposableEffect(webView) { onDispose { destroyWebViewSafely(webView) } }

    Surface(
        modifier = modifier.fillMaxSize().navigationBarsPadding().imePadding(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.sample_export_login_webview_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = onCompleteLogin,
                            shape = HiShapeTokens.Small,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        ) { Text(stringResource(R.string.sample_export_login_webview_done)) }
                        TextButton(
                            onClick = onClose,
                            shape = HiShapeTokens.Small,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        ) { Text(stringResource(R.string.sample_export_login_webview_close)) }
                    }
                }
            }
            HorizontalDivider()
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}
