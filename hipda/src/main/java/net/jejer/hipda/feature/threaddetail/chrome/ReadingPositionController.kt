package net.jejer.hipda.feature.threaddetail.chrome

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.feature.threaddetail.ThreadDetailFilterUi

/** 帖子阅读位置持久化快照，与 `ReadingPositionDao` / `ThreadDetailRepository.saveReadingPosition` 字段一一对应。 */
@Immutable
data class ReadingPositionSnapshot(
    val tid: String,
    val page: Int,
    val postId: String,
    val offsetPx: Int,
    val filterMode: ReadingFilterMode = ReadingFilterMode.NONE,
    val filterUid: String? = null,
    val filterUserName: String? = null,
) {
    fun normalized(): ReadingPositionSnapshot = copy(
        tid = tid.trim(),
        page = page.coerceAtLeast(1),
        postId = postId.trim(),
        offsetPx = offsetPx.coerceAtLeast(0),
        filterUid = filterUid?.trim()?.takeIf { it.isNotEmpty() },
        filterUserName = filterUserName?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/**
 * 带显式首轮恢复门闩（hydration gate）的阅读位置去抖落盘控制器（闭合 `R-BATCH5 B4-E4 ①` 与 `R-E4 B1`）：
 * - [beginSession] 立即上锁（`isHydrated = false`），首次恢复完成调用 [markHydrated] 前严禁落盘；
 * - 实际落盘经 `NonCancellable` 串行执行，确保退帖 / 三栏切帖 / `onCleared()` 同帧取消 `viewModelScope` 时仍能完整写入 Room。
 */
class ReadingPositionController(
    private val scope: CoroutineScope,
    private val savePosition: suspend (ReadingPositionSnapshot) -> Unit,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS,
) {
    private var activeTid: String? = null
    private var hydrated: Boolean = false
    private var pendingSnapshot: ReadingPositionSnapshot? = null
    private var lastSavedSnapshot: ReadingPositionSnapshot? = null
    private var debounceJob: Job? = null
    private var activeSaveJob: Job? = null

    val isHydrated: Boolean get() = hydrated

    fun beginSession(tid: String) {
        debounceJob?.cancel(); debounceJob = null
        activeTid = tid.trim().takeIf { it.isNotEmpty() }
        hydrated = false; pendingSnapshot = null; lastSavedSnapshot = null
    }

    fun markHydrated(tid: String, initialPersisted: ReadingPositionSnapshot? = null) {
        val cleanTid = tid.trim()
        if (cleanTid.isEmpty() || cleanTid != activeTid) return
        hydrated = true
        lastSavedSnapshot = initialPersisted?.normalized()
    }

    fun onPositionObserved(snapshot: ReadingPositionSnapshot) {
        if (!hydrated) return
        val normalized = snapshot.normalized()
        if (normalized.tid.isEmpty() || normalized.tid != activeTid || normalized.postId.isEmpty()) return
        if (normalized == lastSavedSnapshot) {
            pendingSnapshot = null; debounceJob?.cancel(); debounceJob = null
            return
        }
        pendingSnapshot = normalized
        debounceJob?.cancel()
        debounceJob = scope.launch {
            if (debounceMillis > 0L) delay(debounceMillis)
            if (!hydrated || normalized.tid != activeTid) return@launch
            if (pendingSnapshot == normalized) pendingSnapshot = null
            if (lastSavedSnapshot == normalized) return@launch
            lastSavedSnapshot = normalized
            enqueueNonCancellableSave(normalized).join()
        }
    }

    fun flushIfHydrated() {
        if (!hydrated) return
        val toFlush = pendingSnapshot ?: return
        pendingSnapshot = null
        debounceJob?.cancel(); debounceJob = null
        if (toFlush == lastSavedSnapshot) return
        lastSavedSnapshot = toFlush
        enqueueNonCancellableSave(toFlush)
    }

    fun reset() {
        debounceJob?.cancel(); debounceJob = null
        activeTid = null; hydrated = false; pendingSnapshot = null; lastSavedSnapshot = null
    }

    private fun enqueueNonCancellableSave(snapshot: ReadingPositionSnapshot): Job {
        val prev = activeSaveJob
        val job = scope.launch(NonCancellable) {
            runCatching {
                prev?.join()
                savePosition(snapshot)
            }.onFailure { if (it is CancellationException) throw it }
        }
        activeSaveJob = job
        return job
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS: Long = 400L
    }
}

internal fun ThreadDetailFilterUi.toReadingFilterTriple(): Triple<ReadingFilterMode, String?, String?> = when (this) {
    ThreadDetailFilterUi.All -> Triple(ReadingFilterMode.NONE, null, null)
    is ThreadDetailFilterUi.AuthorOnly -> Triple(ReadingFilterMode.OP, authorId?.takeIf { it.isNotBlank() }, null)
    is ThreadDetailFilterUi.UserOnly -> Triple(ReadingFilterMode.USER, uid.takeIf { it.isNotBlank() }, username.takeIf { it.isNotBlank() })
}

internal fun ReadingPositionEntity.toThreadDetailFilterUi(): ThreadDetailFilterUi = when (filterMode) {
    ReadingFilterMode.NONE -> ThreadDetailFilterUi.All
    ReadingFilterMode.OP -> ThreadDetailFilterUi.AuthorOnly(filterUid?.trim()?.takeIf { it.isNotEmpty() })
    ReadingFilterMode.USER -> {
        val uid = filterUid?.trim()?.takeIf { it.isNotEmpty() }
        if (uid != null) ThreadDetailFilterUi.UserOnly(uid, filterUserName?.trim()?.takeIf { it.isNotEmpty() } ?: uid) else ThreadDetailFilterUi.All
    }
}

internal fun ReadingPositionEntity.toSnapshot(): ReadingPositionSnapshot = ReadingPositionSnapshot(
    tid = tid, page = page.coerceAtLeast(1), postId = pid, offsetPx = offsetPx.coerceAtLeast(0),
    filterMode = filterMode, filterUid = filterUid, filterUserName = filterUserName,
).normalized()
