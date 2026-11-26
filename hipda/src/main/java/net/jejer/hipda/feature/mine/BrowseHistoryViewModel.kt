package net.jejer.hipda.feature.mine

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.jejer.hipda.data.local.BrowseHistoryDao
import net.jejer.hipda.data.local.BrowseHistoryEntity

private const val BROWSE_HISTORY_OBSERVE_LIMIT: Int = 500

/** 单次删除或清空浏览历史的独立撤销令牌（捕获被删记录与前驱锚点，仅最新令牌可撤销）。 */
@Immutable
data class BrowseHistoryUndoToken(val id: Long, val entities: List<BrowseHistoryEntity>, val anchorTid: String?)

@Immutable
data class BrowseHistoryUiState(val items: List<BrowseHistoryEntity> = emptyList(), val isLoading: Boolean = true)

sealed interface BrowseHistorySnackbarEvent {
    data class Deleted(val token: BrowseHistoryUndoToken) : BrowseHistorySnackbarEvent
    data object Dismiss : BrowseHistorySnackbarEvent
}

/** 浏览历史 ViewModel：纯离线读取 Room `BrowseHistoryDao`，支持单条删除与一键清空及唯一最新令牌撤销恢复。 */
class BrowseHistoryViewModel(private val browseHistoryDao: BrowseHistoryDao) : ViewModel() {
    private val _snackbarEvents = MutableSharedFlow<BrowseHistorySnackbarEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val opMutex = Mutex()
    private val undoManager = UndoTokenManager<BrowseHistoryUndoToken>()

    val snackbarEvents: SharedFlow<BrowseHistorySnackbarEvent> = _snackbarEvents.asSharedFlow()

    val uiState: StateFlow<BrowseHistoryUiState> = browseHistoryDao.observeRecent(BROWSE_HISTORY_OBSERVE_LIMIT)
        .map { BrowseHistoryUiState(items = it, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), BrowseHistoryUiState())

    fun deleteItem(entity: BrowseHistoryEntity) {
        val current = uiState.value.items
        val idx = current.indexOfFirst { it.tid == entity.tid }
        val id = undoManager.nextId()
        val token = BrowseHistoryUndoToken(id, listOf(entity), if (idx > 0) current[idx - 1].tid else null)
        undoManager.issue(token, id)
        _snackbarEvents.tryEmit(BrowseHistorySnackbarEvent.Deleted(token))
        viewModelScope.launch { opMutex.withLock { browseHistoryDao.delete(entity.tid) } }
    }

    fun clearAll() {
        val tokenId = undoManager.invalidate()
        viewModelScope.launch {
            opMutex.withLock {
                val snapshot = browseHistoryDao.observeRecent(BROWSE_HISTORY_OBSERVE_LIMIT).first()
                if (snapshot.isEmpty()) { _snackbarEvents.tryEmit(BrowseHistorySnackbarEvent.Dismiss); return@withLock }
                browseHistoryDao.trimTo(0)
                if (undoManager.isLatest(tokenId)) {
                    val token = BrowseHistoryUndoToken(tokenId, snapshot, null)
                    undoManager.issue(token, tokenId)
                    _snackbarEvents.tryEmit(BrowseHistorySnackbarEvent.Deleted(token))
                }
            }
        }
    }

    fun undoDelete(tokenId: Long) {
        val token = undoManager.consume(tokenId) ?: return
        viewModelScope.launch { opMutex.withLock { token.entities.forEach { browseHistoryDao.restore(it) } } }
    }

    fun expireUndo(tokenId: Long) = undoManager.expire(tokenId)
}
