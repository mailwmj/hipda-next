package net.jejer.hipda.feature.compose

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.post.PostRepository
import net.jejer.hipda.data.post.PostSubmitRequest
import net.jejer.hipda.data.post.PostSubmitResult
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.feature.common.UiErrorMapper
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage

/** 快速回复内联错误状态（遵循 `copy.md` §错误与校验：有按钮仅写原因，冷却提示写原因与等待秒数）。 */
@Immutable
data class QuickReplyInlineError(
    val uiError: UiError? = null,
    val cooldownMessageSeconds: Int? = null,
)

@Immutable
data class QuickReplyUiState(
    val target: QuickReplyTarget = QuickReplyTarget(tid = ""),
    val text: String = "",
    val textSyncVersion: Long = 0L,
    val isLoggedIn: Boolean = false,
    val isSubmitting: Boolean = false,
    val cooldownSeconds: Int = 0,
    val hadPersistedDraft: Boolean = false,
    val showDraftRestoredBanner: Boolean = false,
    val inlineError: QuickReplyInlineError? = null,
) {
    val canSend: Boolean
        get() = isLoggedIn && !isSubmitting && cooldownSeconds <= 0 && text.isNotBlank()
}

@Immutable
sealed interface QuickReplyEvent {
    @Immutable
    data class Submitted(val tid: String, val redirectedPage: ThreadDetailPage?) : QuickReplyEvent

    @Immutable
    data class DraftSaved(val draftKey: DraftKey) : QuickReplyEvent
}

/** 毛玻璃快速回复面板 ViewModel（§7.2 草稿 / `R-D6 S2` 清空旧草稿删除 / §7.3 30 秒冷却与提交流程）。 */
@HiltViewModel
class QuickReplyViewModel internal constructor(
    private val postRepository: PostRepository,
    private val authRepository: AuthRepository,
    private val clockMs: () -> Long,
) : ViewModel() {

    @Inject
    constructor(postRepository: PostRepository, authRepository: AuthRepository) :
        this(postRepository, authRepository, System::currentTimeMillis)

    private val _uiState = MutableStateFlow(
        QuickReplyUiState(
            isLoggedIn = resolveIsLoggedIn(authRepository.currentAccount.value, authRepository.authState.value),
            cooldownSeconds = postRepository.getRemainingCooldownSeconds().coerceAtLeast(0),
        ),
    )
    val uiState: StateFlow<QuickReplyUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<QuickReplyEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<QuickReplyEvent> = _events.asSharedFlow()

    private var openJob: Job? = null
    private var submitJob: Job? = null
    private var localCooldownJob: Job? = null
    private var draftMutationJob: Job? = null
    private var isSessionActive: Boolean = false
    private var sessionToken: Long = 0L
    private var lastSubmittedText: String? = null

    init {
        viewModelScope.launch {
            combine(authRepository.currentAccount, authRepository.authState) { account, state -> resolveIsLoggedIn(account, state) }
                .collect { loggedIn ->
                    _uiState.update { cur ->
                        val nextErr = if (loggedIn && cur.inlineError?.uiError?.action == UiErrorAction.Login) null else cur.inlineError
                        if (cur.isLoggedIn == loggedIn && cur.inlineError == nextErr) cur else cur.copy(isLoggedIn = loggedIn, inlineError = nextErr)
                    }
                }
        }
        viewModelScope.launch {
            postRepository.cooldownUntilMs.collectLatest { untilMs ->
                localCooldownJob?.cancel(); localCooldownJob = null
                if (untilMs <= 0L) updateCooldown(0) else runCooldownTicker(untilMs) { postRepository.getRemainingCooldownSeconds() }
            }
        }
    }

    /** 打开或切换快速回复目标并从 `PostRepository` 恢复 `reply:<tid>` 草稿（如有）。 */
    fun open(target: QuickReplyTarget, resolveFloorInfo: ((pid: String) -> Pair<Int, String>?)? = null) {
        val cleanTid = target.tid.trim()
        val normalized = target.copy(
            tid = cleanTid,
            targetPid = target.targetPid?.trim()?.takeIf { it.isNotEmpty() },
            targetAuthor = target.targetAuthor?.trim()?.takeIf { it.isNotEmpty() },
            threadAuthor = target.threadAuthor?.trim()?.takeIf { it.isNotEmpty() },
        )
        val tidChanged = _uiState.value.target.tid != cleanTid
        if (tidChanged) lastSubmittedText = null
        val myToken = ++sessionToken
        isSessionActive = true
        openJob?.cancel()
        _uiState.update { cur ->
            val keepText = !tidChanged && cur.text.isNotEmpty()
            cur.copy(
                target = normalized,
                text = if (keepText) cur.text else "",
                textSyncVersion = if (keepText) cur.textSyncVersion else cur.textSyncVersion + 1L,
                cooldownSeconds = maxOf(cur.cooldownSeconds, postRepository.getRemainingCooldownSeconds().coerceAtLeast(0)),
                hadPersistedDraft = if (tidChanged) false else cur.hadPersistedDraft,
                showDraftRestoredBanner = if (keepText) cur.showDraftRestoredBanner else false,
                inlineError = null,
            )
        }
        val draftKey = safeReplyDraftKey(cleanTid) ?: return
        openJob = viewModelScope.launch {
            val draft = runCatching { postRepository.observeDraft(draftKey).first() }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (myToken != sessionToken || !isSessionActive) return@launch
            if (draft != null && draft.content.isNotBlank()) {
                _uiState.update { cur ->
                    if (cur.target.tid != cleanTid) return@update cur
                    val applyText = cur.text.isEmpty()
                    val restoredTarget = if (cur.target.mode == QuickReplyMode.ReplyThread && !draft.quotePid.isNullOrBlank()) {
                        val floorInfo = resolveFloorInfo?.invoke(draft.quotePid)
                        cur.target.copy(
                            mode = QuickReplyMode.QuotePost,
                            targetPid = draft.quotePid,
                            targetFloor = floorInfo?.first ?: cur.target.targetFloor,
                            targetAuthor = floorInfo?.second ?: cur.target.targetAuthor,
                        )
                    } else cur.target
                    cur.copy(
                        target = restoredTarget,
                        text = if (applyText) draft.content else cur.text,
                        textSyncVersion = if (applyText) cur.textSyncVersion + 1L else cur.textSyncVersion,
                        hadPersistedDraft = true,
                        showDraftRestoredBanner = applyText || cur.showDraftRestoredBanner,
                    )
                }
            } else {
                _uiState.update { cur -> if (cur.target.tid != cleanTid) cur else cur.copy(hadPersistedDraft = false, showDraftRestoredBanner = false) }
            }
        }
    }

    fun onTextChanged(newText: String) {
        _uiState.update { cur ->
            if (cur.text == newText) cur
            else cur.copy(text = newText, inlineError = if (cur.inlineError?.uiError?.action == UiErrorAction.Retry) null else cur.inlineError)
        }
    }

    /** 点击「已恢复上次草稿」横条右侧 `清空`：清空正文并显式删除旧草稿（闭合 `R-D6 S2`）。 */
    fun clearRestoredDraft() {
        val draftKey = safeReplyDraftKey(_uiState.value.target.tid)
        resetInputState(hadPersistedDraft = false)
        if (draftKey != null) enqueueDraftMutation { postRepository.deleteDraft(draftKey) }
    }

    /** 点击 `已保存草稿` Snackbar 的 `放弃` 按钮：显式删除草稿并清空内存中尚未发出的内容。 */
    fun discardSavedDraft(draftKey: DraftKey) {
        if (safeReplyDraftKey(_uiState.value.target.tid) == draftKey) resetInputState(hadPersistedDraft = false)
        enqueueDraftMutation { postRepository.deleteDraft(draftKey) }
    }

    /**
     * 退出面板统一草稿落盘/清理出口（下拉关闭 / 点暗罩 / 返回键 / 把手无障碍关闭共用）：
     * - 非空且不同于上次已提交内容：静默 `saveDraft` 并触发 `已保存草稿` Snackbar；
     * - 内容为空但曾恢复旧草稿（`hadPersistedDraft == true`）：显式调用 `deleteDraft`（闭合 `R-D6 S2`），不弹提示；
     * - 内容为空且从未有草稿，或与上次已发内容相同：不保存不提示。
     */
    fun onDismissSession(emitSavedSnackbar: Boolean = true) {
        if (!isSessionActive) return
        isSessionActive = false
        openJob?.cancel()
        val snap = _uiState.value
        val draftKey = safeReplyDraftKey(snap.target.tid)
        val trimmed = snap.text.trim()
        if (trimmed.isEmpty() || trimmed == lastSubmittedText) {
            if (snap.hadPersistedDraft && draftKey != null) enqueueDraftMutation { postRepository.deleteDraft(draftKey) }
            resetInputState(hadPersistedDraft = false)
        } else if (draftKey != null) {
            val quotePid = snap.target.targetPid?.takeIf { snap.target.mode == QuickReplyMode.QuotePost }
            enqueueDraftMutation { postRepository.saveDraft(key = draftKey, content = snap.text, quotePid = quotePid) }
            resetInputState(hadPersistedDraft = true)
            if (emitSavedSnackbar) _events.tryEmit(QuickReplyEvent.DraftSaved(draftKey))
        }
    }

    /** 交接至 `E6` 完整编辑器：静默收尾当前会话，不重复保存草稿、不弹 `已保存草稿` 提示。 */
    fun onHandoffToFullEditor(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        openImagePickerImmediately: Boolean,
    ): FullEditorHandoff {
        isSessionActive = false
        openJob?.cancel()
        val target = _uiState.value.target
        val isQuote = target.mode == QuickReplyMode.QuotePost
        val isReplyPost = target.mode == QuickReplyMode.ReplyPost
        val hasFloorCtx = isQuote || isReplyPost
        _uiState.update { it.copy(text = "", textSyncVersion = it.textSyncVersion + 1L, showDraftRestoredBanner = false, inlineError = null) }
        return FullEditorHandoff(
            tid = target.tid,
            fid = target.fid,
            text = text,
            selectionStart = selectionStart.coerceIn(0, text.length),
            selectionEnd = selectionEnd.coerceIn(0, text.length),
            quotePid = target.targetPid?.takeIf { isQuote },
            quoteAuthor = target.targetAuthor?.takeIf { hasFloorCtx },
            quoteFloor = target.targetFloor?.takeIf { hasFloorCtx },
            openImagePickerImmediately = openImagePickerImmediately,
            replyPid = target.targetPid?.takeIf { isReplyPost },
            threadAuthor = target.threadAuthor,
        )
    }

    /** 提交回复/引用：0ms 进入发送中状态，留在本面板直至服务端确认；失败保留全部输入原地提示重试。 */
    fun submit() {
        val snap = _uiState.value
        if (snap.isSubmitting || submitJob?.isActive == true) return
        if (!snap.isLoggedIn) {
            _uiState.update { it.copy(inlineError = QuickReplyInlineError(UiError(R.string.thread_detail_login_to_operate, action = UiErrorAction.Login))) }
            return
        }
        val content = snap.text
        if (content.isBlank()) return
        val remainingCooldown = maxOf(snap.cooldownSeconds, postRepository.getRemainingCooldownSeconds().coerceAtLeast(0))
        if (remainingCooldown > 0) {
            applyCooldownFailure(remainingCooldown)
            return
        }
        val request = buildSubmitRequest(snap.target, content, safeReplyDraftKey(snap.target.tid))
        val submittedTid = snap.target.tid
        val submittedTrimmed = content.trim()
        val myToken = sessionToken
        _uiState.update { it.copy(isSubmitting = true, inlineError = null) }
        submitJob = viewModelScope.launch {
            val result = try {
                postRepository.submitPost(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PostSubmitResult.NetworkError(e.message.orEmpty(), e)
            }
            if (myToken != sessionToken) return@launch
            when (result) {
                is PostSubmitResult.Success -> {
                    lastSubmittedText = submittedTrimmed
                    isSessionActive = false
                    val nextCd = postRepository.getRemainingCooldownSeconds().coerceAtLeast(0)
                    _uiState.update {
                        it.copy(
                            text = "", textSyncVersion = it.textSyncVersion + 1L, isSubmitting = false,
                            cooldownSeconds = maxOf(it.cooldownSeconds, nextCd),
                            hadPersistedDraft = false, showDraftRestoredBanner = false, inlineError = null,
                        )
                    }
                    _events.tryEmit(QuickReplyEvent.Submitted(submittedTid, result.redirectedPage))
                }
                is PostSubmitResult.CooldownActive -> {
                    _uiState.update { it.copy(isSubmitting = false) }
                    applyCooldownFailure(result.remainingSeconds.coerceAtLeast(1))
                }
                else -> _uiState.update { it.copy(isSubmitting = false, inlineError = QuickReplyInlineError(mapSubmitFailure(result))) }
            }
        }
    }

    override fun onCleared() {
        onDismissSession(emitSavedSnackbar = false)
        super.onCleared()
    }

    private fun resetInputState(hadPersistedDraft: Boolean) {
        _uiState.update {
            it.copy(text = "", textSyncVersion = it.textSyncVersion + 1L, hadPersistedDraft = hadPersistedDraft, showDraftRestoredBanner = false, inlineError = null)
        }
    }

    private fun updateCooldown(seconds: Int) {
        _uiState.update { cur ->
            val nextErr = if (seconds <= 0 && cur.inlineError?.cooldownMessageSeconds != null) null else cur.inlineError
            if (cur.cooldownSeconds == seconds && cur.inlineError == nextErr) cur else cur.copy(cooldownSeconds = seconds, inlineError = nextErr)
        }
    }

    private fun applyCooldownFailure(seconds: Int) {
        val safeSec = seconds.coerceAtLeast(1)
        _uiState.update { it.copy(cooldownSeconds = maxOf(it.cooldownSeconds, safeSec), inlineError = QuickReplyInlineError(cooldownMessageSeconds = safeSec)) }
        if (postRepository.cooldownUntilMs.value <= clockMs()) {
            localCooldownJob?.cancel()
            val untilMs = clockMs() + safeSec * 1_000L
            localCooldownJob = viewModelScope.launch {
                runCooldownTicker(untilMs) {
                    val deltaMs = untilMs - clockMs()
                    if (deltaMs <= 0L) 0 else ((deltaMs + 999L) / 1_000L).toInt()
                }
            }
        }
    }

    private suspend fun runCooldownTicker(untilMs: Long, remainingProvider: () -> Int) {
        while (true) {
            val remaining = remainingProvider().coerceAtLeast(0)
            updateCooldown(remaining)
            if (remaining <= 0) break
            delay(nextCooldownTickDelayMs(untilMs, clockMs()))
        }
    }

    private fun enqueueDraftMutation(action: suspend () -> Unit) {
        val prev = draftMutationJob
        draftMutationJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                runCatching { prev?.join(); action() }.onFailure { if (it is CancellationException) throw it }
            }
        }
    }

    companion object {
        internal fun safeReplyDraftKey(tid: String): DraftKey? = runCatching { DraftKey.reply(tid.trim()) }.getOrNull()

        internal fun buildSubmitRequest(target: QuickReplyTarget, content: String, draftKey: DraftKey?): PostSubmitRequest {
            val pid = target.targetPid?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                target.mode == QuickReplyMode.QuotePost && pid != null -> PostSubmitRequest.QuotePost(target.tid, pid, content, draftKey)
                target.mode == QuickReplyMode.ReplyPost && pid != null -> PostSubmitRequest.ReplyPost(target.tid, pid, content, draftKey)
                else -> PostSubmitRequest.ReplyThread(target.tid, content, quickReply = true, draftKey = draftKey)
            }
        }

        internal fun mapSubmitFailure(result: PostSubmitResult): UiError = when (result) {
            is PostSubmitResult.AuthRequired -> UiErrorMapper.mapAuthRequired(result.reason)
            is PostSubmitResult.ChallengeRequired -> UiError(
                if (result.rejected) R.string.challenge_status_rejected else R.string.challenge_status_incomplete,
                result.cooldownUntilMs?.let { "cooldownUntilMs=$it" },
                UiErrorAction.Reverify,
            )
            is PostSubmitResult.SiteError -> UiError(R.string.compose_error_send_failed, result.message.trim().ifEmpty { null }, UiErrorAction.Retry)
            is PostSubmitResult.NetworkError -> UiError(R.string.compose_error_send_failed, result.message.trim().ifEmpty { null }, UiErrorAction.Retry)
            is PostSubmitResult.InvalidInput -> UiError(R.string.compose_error_send_failed, result.message.trim().ifEmpty { null }, UiErrorAction.Retry)
            else -> UiError(R.string.compose_error_send_failed, action = UiErrorAction.Retry)
        }

        private fun resolveIsLoggedIn(account: SavedAccount?, state: AuthState): Boolean =
            account != null && state !is AuthState.SignedOut && state !is AuthState.SessionExpired

        private fun nextCooldownTickDelayMs(untilMs: Long, nowMs: Long): Long =
            (untilMs - nowMs).let { raw -> if (raw !in 1L..35_000L) 250L else (raw % 1_000L).let { if (it == 0L) 1_000L else it } }
    }
}
