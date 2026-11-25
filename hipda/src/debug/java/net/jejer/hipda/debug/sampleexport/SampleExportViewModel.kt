package net.jejer.hipda.debug.sampleexport

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.cookie.SiteCookieStore
import net.jejer.hipda.data.network.webview.WebViewCookieBridge
import okhttp3.HttpUrl

@Immutable
data class SampleExportUiState(
    val isLoggedIn: Boolean = false,
    val showLoginWebView: Boolean = false,
    val includePersonalHistory: Boolean = true,
    val customTidInput: String = "",
    val customTidError: Boolean = false,
    val isRunning: Boolean = false,
    val items: List<SampleExportItemState> = SampleExportManifest.initialItems(),
    val exportedZipFile: File? = null,
    val resolvedTid: String? = null,
    val challengeState: ChallengeState = ChallengeState.Idle,
    @param:StringRes val feedbackMessageRes: Int? = null,
)

class SampleExportViewModel(
    private val siteAccessGateway: SiteAccessGateway,
    private val siteCookieStore: SiteCookieStore,
    private val webViewCookieBridge: WebViewCookieBridge,
    exportDir: File,
    private val baseUrl: HttpUrl = SampleExportConstants.FORUM_BASE_URL,
    private val loginUrl: HttpUrl = SampleExportConstants.LOGIN_URL,
) : ViewModel() {

    private val engine = SampleExportEngine(siteAccessGateway, exportDir, baseUrl)

    private val _uiState = MutableStateFlow(
        SampleExportUiState(
            isLoggedIn = isForumLoggedIn(siteCookieStore, baseUrl),
            items = engine.currentItems(),
        ),
    )
    val uiState: StateFlow<SampleExportUiState> = _uiState.asStateFlow()

    private var activeTaskJob: Job? = null

    init {
        viewModelScope.launch {
            siteAccessGateway.challengeState.collect { state ->
                _uiState.update { it.copy(challengeState = state) }
            }
        }
    }

    fun refreshLoginStatus() {
        _uiState.update { it.copy(isLoggedIn = isForumLoggedIn(siteCookieStore, baseUrl)) }
    }

    fun openLoginWebView() = _uiState.update { it.copy(showLoginWebView = true) }

    fun closeLoginWebView() {
        _uiState.update { it.copy(showLoginWebView = false) }
        refreshLoginStatus()
    }

    fun syncCookiesFromWebView(showFeedback: Boolean = true, closeWebViewAfterSync: Boolean = false) {
        viewModelScope.launch { syncCookiesFromWebViewNow(showFeedback, closeWebViewAfterSync) }
    }

    internal suspend fun syncCookiesFromWebViewNow(showFeedback: Boolean = false, closeWebViewAfterSync: Boolean = false) {
        runCatching { webViewCookieBridge.syncFromWebView(loginUrl) }
        val loggedIn = isForumLoggedIn(siteCookieStore, baseUrl)
        _uiState.update { current ->
            current.copy(
                isLoggedIn = loggedIn,
                showLoginWebView = if (closeWebViewAfterSync) false else current.showLoginWebView,
                feedbackMessageRes = when {
                    !showFeedback -> current.feedbackMessageRes
                    loggedIn -> R.string.sample_export_feedback_synced_logged_in
                    else -> R.string.sample_export_feedback_synced_logged_out
                },
            )
        }
    }

    fun onIncludePersonalHistoryChange(include: Boolean) =
        _uiState.update { it.copy(includePersonalHistory = include) }

    fun onCustomTidChange(raw: String) {
        val trimmed = raw.trim()
        _uiState.update { it.copy(customTidInput = raw, customTidError = trimmed.isNotEmpty() && !trimmed.all(Char::isDigit)) }
    }

    fun startBatchExport() = runExportTask(
        resetList = true,
        emptyZipFeedbackRes = R.string.sample_export_feedback_export_failed,
    ) { state ->
        engine.runAll(state.includePersonalHistory, state.customTidInput, ::applyItemUpdate)
    }

    fun retryItem(id: SampleEntryId) = runExportTask(
        resetList = false,
        emptyZipFeedbackRes = null,
    ) { state ->
        engine.retryEntry(id, state.includePersonalHistory, state.customTidInput, ::applyItemUpdate)
    }

    fun showShareErrorFeedback() =
        _uiState.update { it.copy(feedbackMessageRes = R.string.sample_export_feedback_share_failed) }

    fun consumeFeedback() = _uiState.update { it.copy(feedbackMessageRes = null) }

    private fun runExportTask(
        resetList: Boolean,
        @StringRes emptyZipFeedbackRes: Int?,
        block: suspend (SampleExportUiState) -> SampleExportRunResult,
    ) {
        val snapshot = _uiState.value
        if (snapshot.isRunning || snapshot.customTidError) return
        activeTaskJob?.cancel()
        activeTaskJob = viewModelScope.launch {
            _uiState.update {
                if (resetList) it.copy(isRunning = true, exportedZipFile = null, items = SampleExportManifest.initialItems())
                else it.copy(isRunning = true)
            }
            try {
                val result = block(snapshot)
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        items = result.items,
                        exportedZipFile = result.zipFile,
                        resolvedTid = result.resolvedTid,
                        feedbackMessageRes = if (result.zipFile != null) {
                            R.string.sample_export_feedback_zip_ready
                        } else {
                            emptyZipFeedbackRes ?: it.feedbackMessageRes
                        },
                    )
                }
            } finally {
                _uiState.update { it.copy(isRunning = false) }
            }
        }
    }

    private fun applyItemUpdate(updated: SampleExportItemState) {
        _uiState.update { state ->
            state.copy(items = state.items.map { if (it.spec.id == updated.spec.id) updated else it })
        }
    }
}
