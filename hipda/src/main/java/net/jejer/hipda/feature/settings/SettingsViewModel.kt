package net.jejer.hipda.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.service.NotificationScheduler

@Immutable
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val imageCacheSizeBytes: Long = 0L,
    val isClearingImageCache: Boolean = false,
    val isClearingWebCache: Boolean = false,
    val errorLogsText: String? = null,
    val isLoadingLogs: Boolean = false,
)

/** 5 组设置与管理版块 ViewModel：所有更改实时写入 [SettingsRepository]，通知开关联动 [NotificationScheduler]。 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val notificationScheduler: NotificationScheduler,
    private val cacheManager: SettingsCacheManager,
    private val errorLogStore: SettingsErrorLogStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { settingsRepository.settings.collect { s -> _uiState.update { it.copy(settings = s) } } }
        refreshImageCacheSize()
    }

    fun setThemeMode(mode: ThemeMode) = persist { settingsRepository.setThemeMode(mode) }
    fun setPureBlack(enabled: Boolean) = persist { settingsRepository.setPureBlack(enabled) }
    fun setThemeSeed(seed: String) = persist { settingsRepository.setThemeSeed(seed) }
    fun setFontStep(step: Int) = persist { settingsRepository.setFontStep(step) }
    fun setLineSpacing(spacing: LineSpacing) = persist { settingsRepository.setLineSpacing(spacing) }
    fun setShowStickyThreads(show: Boolean) = persist { settingsRepository.setShowStickyThreads(show) }
    fun setTailEnabled(enabled: Boolean) = persist { settingsRepository.setTailEnabled(enabled) }
    fun setTailText(text: String) = persist { settingsRepository.setTailText(text.trim()) }
    fun setTailUrl(rawUrl: String): Boolean = SettingsUrlValidator.validateTailUrl(rawUrl)?.let { valid ->
        persist { settingsRepository.setTailUrl(valid) }; true
    } ?: false

    fun addFollowedForum(fid: Int) {
        val cur = _uiState.value.settings.followedForums
        if (fid > 0 && fid !in cur) persist { settingsRepository.setFollowedForums(cur + fid) }
    }

    fun removeFollowedForum(fid: Int) {
        val cur = _uiState.value.settings.followedForums
        if (fid in cur) persist { settingsRepository.setFollowedForums(cur.filterNot { it == fid }) }
    }

    fun moveFollowedForum(fromIndex: Int, toIndex: Int) {
        val cur = _uiState.value.settings.followedForums
        if (fromIndex in cur.indices && toIndex in cur.indices && fromIndex != toIndex) {
            persist { settingsRepository.setFollowedForums(cur.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }) }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        notificationScheduler.syncSchedule(enabled)
        persist { settingsRepository.setNotificationsEnabled(enabled) }
    }

    fun setSilentModeEnabled(enabled: Boolean) = persist { settingsRepository.setSilentModeEnabled(enabled) }
    fun setSilentBeginMinutes(minutes: Int) = persist { settingsRepository.setSilentBeginMinutes(minutes) }
    fun setSilentEndMinutes(minutes: Int) = persist { settingsRepository.setSilentEndMinutes(minutes) }
    fun setForumServer(rawServer: String): Boolean = SettingsUrlValidator.validateForumServer(rawServer)?.let { valid ->
        persist { settingsRepository.setForumServer(valid) }; true
    } ?: false

    fun setImageCacheLimitMb(limitMb: Int) = persist { settingsRepository.setImageCacheLimitMb(limitMb) }

    fun refreshImageCacheSize() {
        viewModelScope.launch {
            val size = runCatching { cacheManager.getImageCacheSizeBytes() }.getOrDefault(0L)
            _uiState.update { it.copy(imageCacheSizeBytes = size) }
        }
    }

    fun clearImageCache(onCleared: (formattedSize: String) -> Unit) {
        if (_uiState.value.isClearingImageCache) return
        _uiState.update { it.copy(isClearingImageCache = true) }
        viewModelScope.launch {
            val freed = try { cacheManager.clearImageCache() } catch (e: CancellationException) { throw e } catch (_: Exception) { 0L }
                .also {
                    val remaining = runCatching { cacheManager.getImageCacheSizeBytes() }.getOrDefault(0L)
                    _uiState.update { s -> s.copy(isClearingImageCache = false, imageCacheSizeBytes = remaining) }
                }
            onCleared(formatCacheSizeBytes(freed))
        }
    }

    fun clearWebCache(onCleared: (formattedSize: String) -> Unit) {
        if (_uiState.value.isClearingWebCache) return
        _uiState.update { it.copy(isClearingWebCache = true) }
        viewModelScope.launch {
            val freed = try { cacheManager.clearWebCache() } catch (e: CancellationException) { throw e } catch (_: Exception) { 0L }
                .also { _uiState.update { s -> s.copy(isClearingWebCache = false) } }
            onCleared(formatCacheSizeBytes(freed))
        }
    }

    fun loadErrorLogs(onLoaded: ((String) -> Unit)? = null) {
        _uiState.update { it.copy(isLoadingLogs = true) }
        viewModelScope.launch {
            val sanitized = try { ErrorLogSanitizer.sanitize(errorLogStore.readSanitizedLogs()) } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
            _uiState.update { it.copy(isLoadingLogs = false, errorLogsText = sanitized) }
            onLoaded?.invoke(sanitized)
        }
    }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch { try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) {} }
    }
}
