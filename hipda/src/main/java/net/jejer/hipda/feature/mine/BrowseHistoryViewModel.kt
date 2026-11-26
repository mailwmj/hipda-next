package net.jejer.hipda.feature.mine

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.data.local.BrowseHistoryDao
import net.jejer.hipda.data.local.BrowseHistoryEntity

private const val BROWSE_HISTORY_OBSERVE_LIMIT: Int = 500

@Immutable
data class BrowseHistoryUiState(val items: List<BrowseHistoryEntity> = emptyList(), val isLoading: Boolean = true)

sealed interface BrowseHistorySnackbarEvent {
    data class Deleted(val entities: List<BrowseHistoryEntity>) : BrowseHistorySnackbarEvent
}

/** 浏览历史 ViewModel：纯离线读取 Room `BrowseHistoryDao`，支持单条删除与一键清空及完整撤销恢复。 */
class BrowseHistoryViewModel(private val browseHistoryDao: BrowseHistoryDao) : ViewModel() {
    private val _snackbarEvents = MutableSharedFlow<BrowseHistorySnackbarEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val snackbarEvents: SharedFlow<BrowseHistorySnackbarEvent> = _snackbarEvents.asSharedFlow()

    val uiState: StateFlow<BrowseHistoryUiState> = browseHistoryDao.observeRecent(BROWSE_HISTORY_OBSERVE_LIMIT)
        .map { BrowseHistoryUiState(items = it, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), BrowseHistoryUiState())

    fun deleteItem(entity: BrowseHistoryEntity) {
        viewModelScope.launch {
            browseHistoryDao.delete(entity.tid)
            _snackbarEvents.tryEmit(BrowseHistorySnackbarEvent.Deleted(listOf(entity)))
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            val snapshot = uiState.value.items.ifEmpty { browseHistoryDao.observeRecent(BROWSE_HISTORY_OBSERVE_LIMIT).first() }
            if (snapshot.isEmpty()) return@launch
            browseHistoryDao.trimTo(0)
            _snackbarEvents.tryEmit(BrowseHistorySnackbarEvent.Deleted(snapshot))
        }
    }

    fun restoreItems(entities: List<BrowseHistoryEntity>) {
        if (entities.isEmpty()) return
        viewModelScope.launch { entities.forEach { browseHistoryDao.restore(it) } }
    }
}
