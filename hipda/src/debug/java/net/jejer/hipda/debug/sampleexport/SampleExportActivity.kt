package net.jejer.hipda.debug.sampleexport

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import net.jejer.hipda.R
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.cookie.SiteCookieStore
import net.jejer.hipda.data.network.webview.WebViewCookieBridge
import net.jejer.hipda.ui.designsystem.HiTheme

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SampleExportEntryPoint {
    fun siteAccessGateway(): SiteAccessGateway
    fun siteCookieStore(): SiteCookieStore
    fun userAgentProvider(): UserAgentProvider
    fun webViewCookieBridge(): WebViewCookieBridge
}

fun createSampleExportShareIntent(context: Context, zipFile: File): Intent {
    val authority = "${context.packageName}${SampleExportConstants.PROVIDER_AUTHORITY_SUFFIX}"
    val uri = FileProvider.getUriForFile(context, authority, zipFile)
    return Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(zipFile.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** Debug 样本导出工具页（仅注册在 `src/debug/AndroidManifest.xml`，不进入 release 包）。 */
class SampleExportActivity : ComponentActivity() {

    private val entryPoint: SampleExportEntryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, SampleExportEntryPoint::class.java)
    }

    private val viewModel: SampleExportViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SampleExportViewModel(
                    siteAccessGateway = entryPoint.siteAccessGateway(),
                    siteCookieStore = entryPoint.siteCookieStore(),
                    webViewCookieBridge = entryPoint.webViewCookieBridge(),
                    exportDir = File(filesDir, SampleExportConstants.EXPORT_DIR_NAME),
                ) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            HiTheme {
                if (state.showLoginWebView) {
                    SampleLoginWebViewOverlay(
                        userAgentProvider = entryPoint.userAgentProvider(),
                        onPageNavigationChanged = {
                            viewModel.syncCookiesFromWebView(showFeedback = false)
                        },
                        onCompleteLogin = {
                            viewModel.syncCookiesFromWebView(
                                showFeedback = true,
                                closeWebViewAfterSync = true,
                            )
                        },
                        onClose = viewModel::closeLoginWebView,
                    )
                } else {
                    SampleExportScreen(
                        state = state,
                        onBack = ::finish,
                        onOpenLogin = viewModel::openLoginWebView,
                        onSyncCookies = { viewModel.syncCookiesFromWebView(showFeedback = true) },
                        onIncludePersonalHistoryChange = viewModel::onIncludePersonalHistoryChange,
                        onCustomTidChange = viewModel::onCustomTidChange,
                        onStartExport = viewModel::startBatchExport,
                        onShareZip = ::shareLatestZip,
                        onRetryItem = viewModel::retryItem,
                        onConsumeSnackbar = viewModel::consumeFeedback,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshLoginStatus()
    }

    private fun shareLatestZip() {
        val zipFile = viewModel.uiState.value.exportedZipFile ?: return
        runCatching {
            val intent = createSampleExportShareIntent(this, zipFile)
            startActivity(Intent.createChooser(intent, getString(R.string.sample_export_share_chooser_title)))
        }.onFailure {
            viewModel.showShareErrorFeedback()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, SampleExportActivity::class.java)
    }
}
