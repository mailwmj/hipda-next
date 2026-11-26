package net.jejer.hipda.feature.compose

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import net.jejer.hipda.R
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.post.*
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.feature.common.UiErrorMapper
import net.jejer.hipda.parser.post.PrePostInfo
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.ui.shell.ForumMetadata
import net.jejer.hipda.ui.shell.ForumMetadataRegistry

/** 完整编辑器模式（涵盖发新帖、回复/引用与 E6b 编辑已发表楼层）。 */
@Immutable
sealed interface FullEditorMode {
    @Immutable data class NewThread(val initialFid: Int? = null) : FullEditorMode
    @Immutable sealed interface Reply : FullEditorMode { val tid: String; val fid: Int; val threadAuthor: String? }
    @Immutable data class ReplyThread(override val tid: String, override val fid: Int = 0, override val threadAuthor: String? = null) : Reply
    @Immutable data class ReplyPost(override val tid: String, override val fid: Int = 0, val targetPid: String, val targetAuthor: String? = null, val targetFloor: Int? = null, override val threadAuthor: String? = null) : Reply
    @Immutable data class QuotePost(override val tid: String, override val fid: Int = 0, val targetPid: String, val targetAuthor: String? = null, val targetFloor: Int? = null, override val threadAuthor: String? = null) : Reply
    @Immutable data class EditPost(val fid: Int, val tid: String, val pid: String, val page: Int = 1, val isFirstFloor: Boolean = false, val floorNumber: Int? = null, val author: String? = null) : FullEditorMode
}

/** 完整编辑器打开请求（由 `ForumPane` 发新帖、`QuickReplySheet` 交接、`ThreadDetailPane` 编辑楼层或单例 `FullEditorRequestBus` 发出）。 */
@Immutable
sealed interface FullEditorRequest {
    @Immutable data class NewThread(val initialFid: Int? = null) : FullEditorRequest
    @Immutable data class Reply(val handoff: FullEditorHandoff) : FullEditorRequest
    @Immutable data class EditPost(val fid: Int, val tid: String, val pid: String, val page: Int = 1, val isFirstFloor: Boolean = false, val floorNumber: Int? = null, val author: String? = null) : FullEditorRequest

    companion object {
        fun fromHandoff(handoff: FullEditorHandoff): FullEditorRequest =
            handoff.editPid?.trim()?.takeIf { it.isNotEmpty() }?.let { pid ->
                EditPost(handoff.fid, handoff.tid.trim(), pid, handoff.editPage.coerceAtLeast(1), handoff.isFirstFloor, handoff.quoteFloor, handoff.quoteAuthor)
            } ?: Reply(handoff)
    }
}

/** 编辑楼层线上原文基准快照（用于判定 `还原原文` 显隐、计算 `deletedAttachments` 与退出草稿差异比较）。 */
@Immutable data class EditOriginalSnapshot(val subject: String, val typeId: String, val serializedBody: String, val imageIds: List<String>)

/** 点击 `还原原文` 后触发可撤销 Snackbar 的独立令牌事件。 */
@Immutable data class RestoreOriginalSnackbarEvent(val id: Long, val tokenId: Long)

private data class RestoreOriginalUndoToken(val id: Long, val sessionId: Long, val subject: String, val selectedTypeId: String, val body: EditorBodyDocument, val uploads: List<UploadAttachmentItem>, val showDraftRestoredBanner: Boolean)

@Immutable
sealed interface UploadAttachmentStatus {
    data object Compressing : UploadAttachmentStatus
    @Immutable data class Uploading(val fraction: Float) : UploadAttachmentStatus
    @Immutable data class Uploaded(val imageId: String) : UploadAttachmentStatus
    @Immutable data class Failed(val reason: String? = null) : UploadAttachmentStatus
}

@Immutable
data class UploadAttachmentItem(val localId: String, val contentUri: Uri? = null, val status: UploadAttachmentStatus = UploadAttachmentStatus.Compressing) {
    val imageIdOrNull: String? get() = (status as? UploadAttachmentStatus.Uploaded)?.imageId
    val isInProgress: Boolean get() = status is UploadAttachmentStatus.Compressing || status is UploadAttachmentStatus.Uploading
    val isFailed: Boolean get() = status is UploadAttachmentStatus.Failed
}

enum class FullEditorValidationError(@param:StringRes val messageRes: Int) {
    SelectForum(R.string.compose_validation_select_forum),
    SelectCategory(R.string.compose_validation_select_category),
    EnterTitle(R.string.compose_validation_enter_title),
    TitleTooShort(R.string.compose_validation_title_min_length),
    ContentTooShort(R.string.compose_validation_content_min_length),
}

@Immutable
data class FullEditorInlineError(val uiError: UiError? = null, val cooldownMessageSeconds: Int? = null, val customMessage: String? = null)

@Immutable
data class FullEditorUiState(
    val isOpen: Boolean = false,
    val sessionId: Long = 0L,
    val mode: FullEditorMode = FullEditorMode.NewThread(null),
    val selectedFid: Int? = null,
    val availableForums: List<ForumMetadata> = ForumMetadataRegistry.BuiltInForums,
    val typeOptions: Map<String, String> = emptyMap(),
    val selectedTypeId: String = "0",
    val subject: String = "",
    val body: EditorBodyDocument = EditorBodyDocument.empty(),
    val bodySyncVersion: Long = 0L,
    val focusRequestVersion: Long = 0L,
    val uploads: List<UploadAttachmentItem> = emptyList(),
    val uploadBatchTotal: Int = 0,
    val uploadBatchCompleted: Int = 0,
    val prePostInfo: PrePostInfo? = null,
    val isPrefetchingPrePost: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isSubmitting: Boolean = false,
    val cooldownSeconds: Int = 0,
    val hadPersistedDraft: Boolean = false,
    val showDraftRestoredBanner: Boolean = false,
    val validationError: FullEditorValidationError? = null,
    val inlineError: FullEditorInlineError? = null,
    val pendingImagePickerTrigger: Long = 0L,
    val editOriginal: EditOriginalSnapshot? = null,
    val isLoadingEditOriginal: Boolean = false,
    val editLoadError: FullEditorInlineError? = null,
    val restoreSnackbarEvent: RestoreOriginalSnackbarEvent? = null,
    val snackbarResetSeq: Long = 0L,
) {
    val isNewThread: Boolean get() = mode is FullEditorMode.NewThread
    val isEditPost: Boolean get() = mode is FullEditorMode.EditPost
    val hasSelectableCategories: Boolean get() = typeOptions.keys.any { it.isNotBlank() && it != "0" }
    val showsEditSubjectField: Boolean
        get() = (mode as? FullEditorMode.EditPost)?.isFirstFloor == true || !editOriginal?.subject.isNullOrEmpty() || hasSelectableCategories
    val uploadedImageIds: List<String> get() = uploads.mapNotNull { it.imageIdOrNull }
    val insertedImageIds: Set<String> get() = body.insertedImageIds()
    val hasActiveUploads: Boolean get() = uploads.any { it.isInProgress }
    val hasFailedUploads: Boolean get() = uploads.any { it.isFailed }
    val effectiveCooldownSeconds: Int get() = if (isEditPost) 0 else cooldownSeconds
    val canRestoreOriginal: Boolean get() = isEditPost && editOriginal != null && FullEditorViewModel.isDifferentFromEditOriginal(this)
    val newEditAttachmentIds: List<String>
        get() = if (!isEditPost) uploadedImageIds else editOriginal?.imageIds?.toSet().orEmpty().let { orig -> uploadedImageIds.filter { it !in orig } }
    val deletedEditAttachmentIds: List<String>
        get() = if (!isEditPost) emptyList() else uploadedImageIds.toSet().let { cur -> editOriginal?.imageIds.orEmpty().filter { it !in cur } }
    val canTapSend: Boolean get() = isLoggedIn && !isSubmitting && effectiveCooldownSeconds <= 0 && (!isEditPost || (editOriginal != null && !isLoadingEditOriginal))
    val uploadSummaryProgress: Pair<Int, Int>?
        get() = if (!hasActiveUploads) null else maxOf(uploadBatchTotal, uploads.count { it.isInProgress }).takeIf { it > 0 }?.let { (uploadBatchCompleted + 1).coerceIn(1, it) to it }
}

@Immutable
sealed interface FullEditorEvent {
    @Immutable data class NewThreadSubmitted(val fid: Int, val tid: String, val title: String) : FullEditorEvent
    @Immutable data class ReplySubmitted(val tid: String, val redirectedPage: ThreadDetailPage?) : FullEditorEvent
    @Immutable data class PostEdited(val tid: String, val pid: String, val page: Int, val isFirstFloor: Boolean, val redirectedPage: ThreadDetailPage?) : FullEditorEvent
    @Immutable data class DraftSaved(val draftKey: DraftKey, val isReply: Boolean) : FullEditorEvent
    data object Closed : FullEditorEvent
}

/** 完整编辑器 ViewModel（发新帖 R-D1、回复/引用零丢失交接、多图并发上传与取消、草稿自动保存与 R-D6 S2、冷却与提交）。 */
@HiltViewModel
class FullEditorViewModel internal constructor(
    private val postRepository: PostRepository,
    private val imageUploadRepository: ImageUploadRepository,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val clockMs: () -> Long,
) : ViewModel() {

    @Inject
    constructor(postRepository: PostRepository, imageUploadRepository: ImageUploadRepository, authRepository: AuthRepository, settingsRepository: SettingsRepository) :
        this(postRepository, imageUploadRepository, authRepository, settingsRepository, System::currentTimeMillis)

    private val _uiState = MutableStateFlow(
        FullEditorUiState(
            isLoggedIn = resolveComposeLoggedIn(authRepository.currentAccount.value, authRepository.authState.value),
            cooldownSeconds = postRepository.getRemainingCooldownSeconds().coerceAtLeast(0),
        ),
    )
    val uiState: StateFlow<FullEditorUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<FullEditorEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<FullEditorEvent> = _events.asSharedFlow()

    private val uploadIdSeq = AtomicLong(0L)
    private val uploadJobs = LinkedHashMap<String, Job>()
    private var openDraftJob: Job? = null
    private var prePostJob: Job? = null
    private var submitJob: Job? = null
    private var localCooldownJob: Job? = null
    private var draftMutationJob: Job? = null
    private var sessionToken: Long = 0L
    private var lastSubmittedSignature: String? = null
    private var restoreUndoTokenSeq: Long = 0L
    private var restoreSnackbarSeq: Long = 0L
    private var snackbarResetSeq: Long = 0L
    private var activeRestoreUndoToken: RestoreOriginalUndoToken? = null

    init {
        viewModelScope.launch {
            var wasLoggedIn = _uiState.value.isLoggedIn
            combine(authRepository.currentAccount, authRepository.authState, ::resolveComposeLoggedIn).collect { loggedIn ->
                val becameLoggedIn = !wasLoggedIn && loggedIn
                wasLoggedIn = loggedIn
                _uiState.update { cur ->
                    val nextErr = if (loggedIn && cur.inlineError?.uiError?.action == UiErrorAction.Login) null else cur.inlineError
                    val nextEditErr = if (loggedIn && cur.editLoadError?.uiError?.action == UiErrorAction.Login) null else cur.editLoadError
                    if (cur.isLoggedIn == loggedIn && cur.inlineError == nextErr && cur.editLoadError == nextEditErr) cur
                    else cur.copy(isLoggedIn = loggedIn, inlineError = nextErr, editLoadError = nextEditErr)
                }
                val snap = _uiState.value
                if (becameLoggedIn && snap.isOpen && snap.prePostInfo == null && prePostJob?.isActive != true) {
                    if (snap.isEditPost) loadEditPostSession(sessionToken, preserveRestoredInput = snap.editOriginal != null)
                    else prefetchPrePostIfEligible(sessionToken)
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                val ordered = buildOrderedForums(s.followedForums)
                _uiState.update { if (it.availableForums == ordered) it else it.copy(availableForums = ordered) }
            }
        }
        viewModelScope.launch {
            postRepository.cooldownUntilMs.collectLatest { untilMs ->
                localCooldownJob?.cancel(); localCooldownJob = null
                if (untilMs <= 0L) updateCooldown(0) else runCooldownTickerLoop(untilMs, clockMs, postRepository::getRemainingCooldownSeconds, ::updateCooldown)
            }
        }
    }

    /** 打开完整编辑器：同草稿键幂等保活并聚焦；不同草稿键先按退出规则保存/清理前序草稿再切换会话。 */
    fun open(request: FullEditorRequest) {
        val cur = _uiState.value
        if (cur.isOpen) {
            if (isSameSessionTarget(cur, request)) {
                val pick = (request as? FullEditorRequest.Reply)?.handoff?.openImagePickerImmediately == true
                _uiState.update { it.copy(focusRequestVersion = it.focusRequestVersion + 1L, pendingImagePickerTrigger = if (pick) it.pendingImagePickerTrigger + 1L else it.pendingImagePickerTrigger) }
                return
            }
            persistOrCleanDraft(cur, emitSavedSnackbar = true)
        }
        cancelSessionJobs()
        val token = ++sessionToken
        lastSubmittedSignature = null
        activeRestoreUndoToken = null
        when (request) {
            is FullEditorRequest.NewThread -> {
                val fid = request.initialFid?.takeIf { it > 0 }
                resetSession(isOpen = true, sessionId = token, mode = FullEditorMode.NewThread(fid), selectedFid = fid)
                if (fid != null) { loadNewThreadDraft(fid, token, overwriteOnlyIfEmpty = false); prefetchPrePostIfEligible(token) }
            }
            is FullEditorRequest.Reply -> {
                val h = request.handoff
                val tid = h.tid.trim()
                val qPid = h.quotePid?.trim()?.takeIf { it.isNotEmpty() }
                val rPid = h.replyPid?.trim()?.takeIf { it.isNotEmpty() }
                val mode = when {
                    qPid != null -> FullEditorMode.QuotePost(tid, h.fid, qPid, h.quoteAuthor, h.quoteFloor, h.threadAuthor)
                    rPid != null -> FullEditorMode.ReplyPost(tid, h.fid, rPid, h.quoteAuthor, h.quoteFloor, h.threadAuthor)
                    else -> FullEditorMode.ReplyThread(tid, h.fid, h.threadAuthor)
                }
                val doc = EditorBodyDocument.parse(h.text, h.selectionStart, h.selectionEnd)
                resetSession(
                    isOpen = true, sessionId = token, mode = mode, selectedFid = h.fid.takeIf { it > 0 },
                    body = doc, uploads = toUploadedItems(doc.insertedImageIds()),
                    hadPersistedDraft = h.hadPersistedDraft, showDraftBanner = h.showDraftRestoredBanner && h.text.isNotBlank(),
                    bumpPickerTrigger = h.openImagePickerImmediately,
                )
                syncReplyDraftMeta(tid, handoffHasContent = h.text.isNotBlank(), token = token)
                prefetchPrePostIfEligible(token)
            }
            is FullEditorRequest.EditPost -> {
                val mode = FullEditorMode.EditPost(request.fid.coerceAtLeast(0), request.tid.trim(), request.pid.trim(), request.page.coerceAtLeast(1), request.isFirstFloor, request.floorNumber, request.author)
                resetSession(isOpen = true, sessionId = token, mode = mode, selectedFid = mode.fid.takeIf { it > 0 }, isLoadingEditOriginal = true)
                loadEditPostSession(token, preserveRestoredInput = false)
            }
        }
    }

    /** 进程回收重建后无损恢复打开态与输入内容（仅当 ViewModel 为全新未打开实例时生效）。 */
    fun restoreSavedStateIfFresh(saved: FullEditorSavedState?) {
        if (saved == null || !saved.isOpen || _uiState.value.isOpen) return
        val token = ++sessionToken
        val doc = EditorBodyDocument.parse(saved.serializedBody).selectTextBlockByOrdinal(saved.activeTextBlockOrdinal, saved.selectionStart, saved.selectionEnd)
        val restoredOrig = saved.editOriginalSubject?.takeIf { saved.mode is FullEditorMode.EditPost }?.let {
            EditOriginalSnapshot(it, saved.editOriginalTypeId.ifBlank { "0" }, saved.editOriginalSerializedBody, saved.editOriginalImageIds)
        }
        resetSession(
            isOpen = true, sessionId = token, mode = saved.mode, selectedFid = saved.selectedFid?.takeIf { it > 0 },
            selectedTypeId = saved.selectedTypeId.ifBlank { "0" }, subject = saved.subject, body = doc,
            uploads = toUploadedItems((saved.uploadedImageIds + doc.insertedImageIds()).distinct()),
            hadPersistedDraft = saved.hadPersistedDraft, showDraftBanner = saved.showDraftRestoredBanner,
            editOriginal = restoredOrig, isLoadingEditOriginal = saved.mode is FullEditorMode.EditPost && restoredOrig == null,
        )
        if (saved.mode is FullEditorMode.EditPost) loadEditPostSession(token, preserveRestoredInput = restoredOrig != null) else prefetchPrePostIfEligible(token)
    }

    fun exportSavedState(): FullEditorSavedState = _uiState.value.let { s ->
        FullEditorSavedState(s.isOpen, s.mode, s.selectedFid, s.selectedTypeId, s.subject, s.body.serialize(), s.body.activeSelectionStart, s.body.activeSelectionEnd, s.uploadedImageIds, s.hadPersistedDraft, s.showDraftRestoredBanner, s.body.activeTextBlockOrdinal(), s.editOriginal?.subject, s.editOriginal?.typeId ?: "0", s.editOriginal?.serializedBody.orEmpty(), s.editOriginal?.imageIds.orEmpty())
    }

    fun retryEditLoad() {
        val snap = _uiState.value
        if (snap.isOpen && snap.isEditPost && snap.editOriginal == null && prePostJob?.isActive != true) loadEditPostSession(sessionToken, preserveRestoredInput = false)
    }

    /** 顶栏「还原原文」：将标题/分类/正文/已有图片换回线上原文，并生成独立撤销令牌（立即作废前一次撤销令牌）。 */
    fun restoreOriginal() {
        val cur = _uiState.value
        val orig = cur.editOriginal ?: return
        if (!cur.isOpen || !cur.isEditPost || cur.isSubmitting || !cur.canRestoreOriginal) return
        cancelUploadJobs()
        val tokenId = ++restoreUndoTokenSeq
        activeRestoreUndoToken = RestoreOriginalUndoToken(tokenId, cur.sessionId, cur.subject, cur.selectedTypeId, cur.body, cur.uploads.filter { it.imageIdOrNull != null }, cur.showDraftRestoredBanner)
        val nextReset = ++snackbarResetSeq; val nextEventId = ++restoreSnackbarSeq
        _uiState.update { state ->
            state.copy(subject = orig.subject, selectedTypeId = orig.typeId, body = EditorBodyDocument.parse(orig.serializedBody), bodySyncVersion = state.bodySyncVersion + 1L, uploads = toUploadedItems(orig.imageIds), uploadBatchTotal = 0, uploadBatchCompleted = 0, showDraftRestoredBanner = false, validationError = null, inlineError = null, restoreSnackbarEvent = RestoreOriginalSnackbarEvent(nextEventId, tokenId), snackbarResetSeq = nextReset)
        }
    }

    /** 撤销本次「还原原文」：严格按独立令牌恢复点击前的标题、分类、正文与图片集合；过期/失效令牌静默忽略。 */
    fun undoRestoreOriginal(tokenId: Long) {
        val cur = _uiState.value
        val token = activeRestoreUndoToken?.takeIf { it.id == tokenId && it.sessionId == cur.sessionId } ?: return
        if (!cur.isOpen || cur.isSubmitting) return
        activeRestoreUndoToken = null
        _uiState.update { it.copy(subject = token.subject, selectedTypeId = token.selectedTypeId, body = token.body, bodySyncVersion = it.bodySyncVersion + 1L, uploads = token.uploads, showDraftRestoredBanner = token.showDraftRestoredBanner, validationError = null, inlineError = null, restoreSnackbarEvent = null) }
    }

    fun consumeRestoreSnackbarEvent(eventId: Long) = _uiState.update { cur -> if (cur.restoreSnackbarEvent?.id == eventId) cur.copy(restoreSnackbarEvent = null) else cur }

    fun consumeImagePickerTrigger(triggerId: Long) = _uiState.update { if (it.pendingImagePickerTrigger == triggerId) it.copy(pendingImagePickerTrigger = 0L) else it }

    /** 发新帖选择版块（R-D1：仅正整数 `fid > 0` 建 `DraftKey.newThread(fid)` 读草稿并预取分类与上传参数）。 */
    fun selectForum(fid: Int) {
        if (fid <= 0 || !_uiState.value.isNewThread || (_uiState.value.selectedFid == fid && _uiState.value.prePostInfo != null)) return
        openDraftJob?.cancel(); prePostJob?.cancel()
        val token = sessionToken
        _uiState.update { cur ->
            val clearVal = cur.validationError == FullEditorValidationError.SelectForum || cur.validationError == FullEditorValidationError.SelectCategory
            cur.copy(selectedFid = fid, typeOptions = emptyMap(), selectedTypeId = "0", prePostInfo = null, hadPersistedDraft = false, showDraftRestoredBanner = false, validationError = if (clearVal) null else cur.validationError, inlineError = null)
        }
        loadNewThreadDraft(fid, token, overwriteOnlyIfEmpty = true)
        prefetchPrePostIfEligible(token)
    }

    fun selectCategory(typeId: String) = _uiState.update { cur -> cur.copy(selectedTypeId = typeId, validationError = cur.validationError.takeUnless { it == FullEditorValidationError.SelectCategory }) }

    fun onSubjectChanged(newSubject: String) = _uiState.update { cur ->
        if (cur.subject == newSubject) cur
        else cur.copy(subject = newSubject, validationError = cur.validationError.takeUnless { it == FullEditorValidationError.EnterTitle || it == FullEditorValidationError.TitleTooShort }, inlineError = clearRetryError(cur.inlineError))
    }

    fun onTextBlockChanged(blockId: String, newText: String, selectionStart: Int, selectionEnd: Int) = _uiState.update { cur ->
        val next = cur.body.updateTextBlock(blockId, newText, selectionStart, selectionEnd)
        if (next == cur.body) cur
        else cur.copy(body = next, bodySyncVersion = if (next.blocks.size != cur.body.blocks.size) cur.bodySyncVersion + 1L else cur.bodySyncVersion, validationError = cur.validationError.takeUnless { it == FullEditorValidationError.ContentTooShort }, inlineError = clearRetryError(cur.inlineError))
    }

    fun onTextBlockSelectionChanged(blockId: String, selectionStart: Int, selectionEnd: Int) = _uiState.update { cur ->
        val next = cur.body.updateSelection(blockId, selectionStart, selectionEnd)
        if (next == cur.body) cur else cur.copy(body = next)
    }

    fun onInsertEmoji(code: String) = updateBodyAndBump(true) { it.insertTextAtCursor(code) }
    fun onBackspace() = updateBodyAndBump(false) { it.backspaceAtCursor() }
    fun onRemoveInlineImageBlock(blockId: String) = updateBodyAndBump(false) { it.removeImageBlock(blockId) }
    fun onInsertUploadedImage(localId: String) { _uiState.value.uploads.firstOrNull { it.localId == localId }?.imageIdOrNull?.let { id -> updateBodyAndBump(true) { it.insertImageAtCursor(id) } } }

    /** 系统多图选择返回：校验 `content://` 白名单后并发压缩上传（默认 `original = false`，闭合 R-D6 S1）。 */
    fun onPickImages(uris: List<Uri>) {
        if (uris.isEmpty() || !_uiState.value.isOpen) return
        val (valid, invalid) = uris.partition(::isAllowedUploadContentUri)
        if (invalid.isNotEmpty()) _uiState.update { it.copy(inlineError = FullEditorInlineError(UiError(R.string.compose_upload_invalid_uri))) }
        if (valid.isEmpty()) return
        val snap = _uiState.value
        if (!snap.isLoggedIn) { showLoginError(); return }
        if (snap.isNewThread && (snap.selectedFid ?: 0) <= 0) { _uiState.update { it.copy(validationError = FullEditorValidationError.SelectForum) }; return }
        val items = valid.map { UploadAttachmentItem(nextUploadLocalId(), contentUri = it) }
        enqueueUploadBatch(items, keepError = invalid.isNotEmpty()) { it + items }
    }

    fun retryUpload(localId: String) {
        val snap = _uiState.value
        val item = snap.uploads.firstOrNull { it.localId == localId }?.takeIf { it.isFailed && it.contentUri != null } ?: return
        if (!snap.isLoggedIn) { showLoginError(); return }
        val reset = item.copy(status = UploadAttachmentStatus.Compressing)
        enqueueUploadBatch(listOf(reset), keepError = false) { list -> list.map { if (it.localId == localId) reset else it } }
    }

    fun removeUpload(localId: String) {
        uploadJobs.remove(localId)?.cancel()
        _uiState.update { cur ->
            val removed = cur.uploads.firstOrNull { it.localId == localId } ?: return@update cur
            val remaining = cur.uploads.filterNot { it.localId == localId }
            val nextBody = removed.imageIdOrNull?.let { cur.body.removeImagesByImageId(it) } ?: cur.body
            val active = remaining.any { it.isInProgress }
            cur.copy(
                uploads = remaining, body = nextBody, bodySyncVersion = if (nextBody != cur.body) cur.bodySyncVersion + 1L else cur.bodySyncVersion,
                uploadBatchTotal = if (active) maxOf(1, cur.uploadBatchTotal - if (removed.isInProgress) 1 else 0) else 0,
                uploadBatchCompleted = if (active) cur.uploadBatchCompleted else 0, inlineError = clearUploadHintError(cur.inlineError),
            )
        }
    }

    /** 点横条「清空」：清零表单并显式调用 `deleteDraft`（闭合 R-D6 S2）。 */
    fun clearRestoredDraft() {
        cancelUploadJobs()
        activeRestoreUndoToken = null
        val key = currentDraftKey()
        val nextReset = ++snackbarResetSeq
        _uiState.update { cur ->
            cur.copy(
                subject = "", selectedTypeId = "0", body = EditorBodyDocument.empty(), bodySyncVersion = cur.bodySyncVersion + 1L,
                uploads = emptyList(), uploadBatchTotal = 0, uploadBatchCompleted = 0,
                hadPersistedDraft = false, showDraftRestoredBanner = false, validationError = null, inlineError = null,
                restoreSnackbarEvent = null, snackbarResetSeq = nextReset,
            )
        }
        if (key != null) enqueueDraftMutation { postRepository.deleteDraft(key) }
    }

    fun discardSavedDraft(key: DraftKey) = enqueueDraftMutation { postRepository.deleteDraft(key) }

    /** 统一退出与草稿保存/清理出口（闭合 R-D6 S2 与 R-D1）。 */
    fun requestClose(emitSavedSnackbar: Boolean = true) {
        val snap = _uiState.value
        if (!snap.isOpen) return
        cancelSessionJobs()
        activeRestoreUndoToken = null
        val savedEvent = persistOrCleanDraft(snap, emitSavedSnackbar = false)
        resetSession(isOpen = false, resetSnackbar = true)
        if (emitSavedSnackbar && savedEvent != null) _events.tryEmit(savedEvent)
        _events.tryEmit(FullEditorEvent.Closed)
    }

    fun submit() {
        val snap = _uiState.value
        if (!snap.isOpen || snap.isSubmitting || submitJob?.isActive == true) return
        if (snap.isEditPost && (snap.editOriginal == null || snap.isLoadingEditOriginal)) return
        if (!snap.isLoggedIn) { showLoginError(); return }
        if (snap.hasActiveUploads) { _uiState.update { it.copy(inlineError = FullEditorInlineError(UiError(R.string.compose_upload_in_progress_hint))) }; return }
        if (snap.hasFailedUploads) { _uiState.update { it.copy(inlineError = FullEditorInlineError(UiError(R.string.compose_upload_has_failed_hint))) }; return }
        validateForSubmit(snap)?.let { err -> _uiState.update { it.copy(validationError = err, inlineError = null) }; return }
        if (!snap.isEditPost) {
            val remCd = maxOf(snap.cooldownSeconds, postRepository.getRemainingCooldownSeconds().coerceAtLeast(0))
            if (remCd > 0) { applyCooldownFailure(remCd); return }
        }

        val serialized = snap.body.serialize()
        val uploadedIds = snap.uploadedImageIds
        val request = buildSubmitRequest(snap, serialized, uploadedIds, currentDraftKey(snap)) ?: return
        val sig = buildContentSignature(snap.subject, serialized, uploadedIds)
        val token = sessionToken
        _uiState.update { it.copy(isSubmitting = true, validationError = null, inlineError = null) }
        submitJob = viewModelScope.launch {
            val result = runCatching { postRepository.submitPost(request) }.onFailure { if (it is CancellationException) throw it }.getOrElse { PostSubmitResult.NetworkError(it.message.orEmpty(), it) }
            if (token != sessionToken) return@launch
            when (result) {
                is PostSubmitResult.Success -> {
                    lastSubmittedSignature = sig
                    activeRestoreUndoToken = null
                    cancelSessionJobs()
                    val nextCd = if (snap.isEditPost) postRepository.getRemainingCooldownSeconds().coerceAtLeast(0)
                    else maxOf(_uiState.value.cooldownSeconds, postRepository.getRemainingCooldownSeconds().coerceAtLeast(0))
                    resetSession(isOpen = false, cooldownSeconds = nextCd, resetSnackbar = true)
                    when (val m = snap.mode) {
                        is FullEditorMode.NewThread -> _events.tryEmit(FullEditorEvent.NewThreadSubmitted(requireNotNull(snap.selectedFid), result.tid, snap.subject.trim()))
                        is FullEditorMode.Reply -> _events.tryEmit(FullEditorEvent.ReplySubmitted(m.tid, result.redirectedPage))
                        is FullEditorMode.EditPost -> _events.tryEmit(FullEditorEvent.PostEdited(m.tid, m.pid, m.page, m.isFirstFloor, result.redirectedPage))
                    }
                }
                is PostSubmitResult.CooldownActive -> { _uiState.update { it.copy(isSubmitting = false) }; applyCooldownFailure(result.remainingSeconds.coerceAtLeast(1)) }
                else -> _uiState.update { it.copy(isSubmitting = false, inlineError = mapSubmitFailure(result)) }
            }
        }
    }

    override fun onCleared() { if (_uiState.value.isOpen) requestClose(emitSavedSnackbar = false); super.onCleared() }

    private fun enqueueUploadBatch(items: List<UploadAttachmentItem>, keepError: Boolean, updateList: (List<UploadAttachmentItem>) -> List<UploadAttachmentItem>) {
        _uiState.update { cur ->
            val active = cur.hasActiveUploads
            cur.copy(
                uploads = updateList(cur.uploads),
                uploadBatchTotal = if (active) cur.uploadBatchTotal + items.size else items.size,
                uploadBatchCompleted = if (active) cur.uploadBatchCompleted else 0,
                inlineError = if (keepError) cur.inlineError else clearUploadHintError(cur.inlineError),
            )
        }
        items.forEach { startUploadJob(it, sessionToken) }
    }

    private fun persistOrCleanDraft(snap: FullEditorUiState, emitSavedSnackbar: Boolean): FullEditorEvent.DraftSaved? {
        val key = currentDraftKey(snap) ?: return null
        val serialized = snap.body.serialize()
        val uploadedIds = snap.uploadedImageIds
        val isEmpty = snap.subject.isBlank() && snap.body.isVisuallyEmpty() && uploadedIds.isEmpty()
        if (snap.isEditPost) {
            if (snap.editOriginal == null) return null
            val matchesOriginal = !isDifferentFromEditOriginal(snap)
            if (isEmpty || matchesOriginal || buildContentSignature(snap.subject, serialized, uploadedIds) == lastSubmittedSignature) {
                if (snap.hadPersistedDraft) enqueueDraftMutation { postRepository.deleteDraft(key) }
                return null
            }
            val typeToSave = if (snap.hasSelectableCategories) snap.selectedTypeId else ""
            enqueueDraftMutation { postRepository.saveDraft(key, snap.subject, typeToSave, serialized, null, uploadedIds) }
            return FullEditorEvent.DraftSaved(key, isReply = true).also { if (emitSavedSnackbar) _events.tryEmit(it) }
        }
        if (isEmpty || buildContentSignature(snap.subject, serialized, uploadedIds) == lastSubmittedSignature) {
            if (snap.hadPersistedDraft) enqueueDraftMutation { postRepository.deleteDraft(key) }
            return null
        }
        val quotePid = (snap.mode as? FullEditorMode.QuotePost)?.targetPid
        enqueueDraftMutation { postRepository.saveDraft(key, snap.subject, if (snap.isNewThread) snap.selectedTypeId else "", serialized, quotePid, uploadedIds) }
        return FullEditorEvent.DraftSaved(key, isReply = !snap.isNewThread).also { if (emitSavedSnackbar) _events.tryEmit(it) }
    }

    private fun startUploadJob(item: UploadAttachmentItem, token: Long) {
        val uri = item.contentUri ?: return
        uploadJobs.remove(item.localId)?.cancel()
        uploadJobs[item.localId] = viewModelScope.launch {
            try {
                val prePost = awaitOrFetchPrePost(token)
                if (token != sessionToken) return@launch
                val uid = prePost?.uid?.takeIf { it.isNotBlank() } ?: authRepository.currentAccount.value?.uid.orEmpty()
                val hash = prePost?.hash?.takeIf { it.isNotBlank() }.orEmpty()
                if (uid.isBlank() || hash.isBlank()) { markUploadTerminal(item.localId, UploadAttachmentStatus.Failed(), token); return@launch }
                imageUploadRepository.uploadImage(ImageUploadInput.ContentUri(uri), uid, hash, prePost?.maxUploadFileSizeBytes).collect { p ->
                    if (token != sessionToken) return@collect
                    when (p) {
                        ImageUploadProgress.Compressing -> updateUploadStatus(item.localId, UploadAttachmentStatus.Compressing)
                        is ImageUploadProgress.Uploading -> updateUploadStatus(item.localId, UploadAttachmentStatus.Uploading(p.fraction))
                        is ImageUploadProgress.Succeeded -> markUploadTerminal(item.localId, UploadAttachmentStatus.Uploaded(p.imageId), token)
                        is ImageUploadProgress.Failed -> {
                            if (p.error is ImageUploadError.AuthRequired) _uiState.update { it.copy(inlineError = FullEditorInlineError(UiErrorMapper.mapAuthRequired(p.error.reason))) }
                            markUploadTerminal(item.localId, UploadAttachmentStatus.Failed(p.error.message), token)
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { if (token == sessionToken) markUploadTerminal(item.localId, UploadAttachmentStatus.Failed(e.message), token) }
            finally { uploadJobs.remove(item.localId) }
        }
    }

    private suspend fun awaitOrFetchPrePost(token: Long): PrePostInfo? {
        _uiState.value.prePostInfo?.let { return it }
        prePostJob?.join()
        if (token != sessionToken) return null
        _uiState.value.prePostInfo?.let { return it }
        return fetchPrePostAndApply(token, reportAuthError = true)
    }

    private fun prefetchPrePostIfEligible(token: Long) {
        val snap = _uiState.value
        if (!snap.isOpen || !snap.isLoggedIn || buildPrePostRequest(snap) == null) return
        prePostJob?.cancel()
        _uiState.update { it.copy(isPrefetchingPrePost = true) }
        prePostJob = viewModelScope.launch { fetchPrePostAndApply(token, reportAuthError = false) }
    }

    private suspend fun fetchPrePostAndApply(token: Long, reportAuthError: Boolean): PrePostInfo? {
        val req = buildPrePostRequest(_uiState.value) ?: return null
        val res = runCatching { postRepository.fetchPrePost(req) }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        if (token != sessionToken) return null
        return when (res) {
            is PrePostResult.Success -> {
                val info = res.info
                _uiState.update { cur ->
                    val opts = if (cur.isNewThread) info.typeOptions else cur.typeOptions
                    val infoType = info.typeId
                    val nextType = when {
                        !cur.isNewThread -> cur.selectedTypeId
                        cur.selectedTypeId.isNotBlank() && cur.selectedTypeId != "0" && cur.selectedTypeId in opts -> cur.selectedTypeId
                        !infoType.isNullOrBlank() && infoType in opts -> infoType
                        else -> "0"
                    }
                    cur.copy(prePostInfo = info, isPrefetchingPrePost = false, typeOptions = opts, selectedTypeId = nextType)
                }
                info
            }
            is PrePostResult.AuthRequired -> {
                _uiState.update { it.copy(isPrefetchingPrePost = false, inlineError = if (reportAuthError) FullEditorInlineError(UiErrorMapper.mapAuthRequired(res.reason)) else it.inlineError) }
                null
            }
            else -> { _uiState.update { it.copy(isPrefetchingPrePost = false) }; null }
        }
    }

    private fun launchDraftRead(key: DraftKey?, token: Long, onLoaded: (DraftEntity) -> Unit) {
        if (key == null) return
        openDraftJob?.cancel()
        openDraftJob = viewModelScope.launch {
            draftMutationJob?.join()
            val draft = runCatching { postRepository.observeDraft(key).first() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            if (token != sessionToken || !_uiState.value.isOpen || draft == null) return@launch
            onLoaded(draft)
        }
    }

    private fun loadNewThreadDraft(fid: Int, token: Long, overwriteOnlyIfEmpty: Boolean) = launchDraftRead(safeNewThreadDraftKey(fid), token) { draft ->
        if (_uiState.value.selectedFid != fid || (draft.subject.isBlank() && draft.content.isBlank() && draft.attachments.isEmpty())) return@launchDraftRead
        _uiState.update { cur ->
            if (cur.selectedFid != fid) return@update cur
            val applySub = !overwriteOnlyIfEmpty || cur.subject.isBlank()
            val applyBody = !overwriteOnlyIfEmpty || cur.body.isVisuallyEmpty()
            val body = if (applyBody) EditorBodyDocument.parse(draft.content) else cur.body
            val extra = toUploadedItems((draft.attachments + body.insertedImageIds()).distinct().filterNot { it in cur.uploadedImageIds })
            cur.copy(
                subject = if (applySub) draft.subject else cur.subject,
                selectedTypeId = if (applySub && draft.typeId.isNotBlank()) draft.typeId else cur.selectedTypeId,
                body = body, bodySyncVersion = if (applyBody) cur.bodySyncVersion + 1L else cur.bodySyncVersion,
                uploads = cur.uploads + extra, hadPersistedDraft = true, showDraftRestoredBanner = applySub || applyBody || extra.isNotEmpty(),
            )
        }
    }

    /** 回复交接以 `handoff.text` 为权威正文（绝不覆盖正文）；仅补齐 `hadPersistedDraft` 与非空交接时的已存附件。 */
    private fun syncReplyDraftMeta(tid: String, handoffHasContent: Boolean, token: Long) = launchDraftRead(safeReplyDraftKey(tid), token) { draft ->
        if (draft.content.isBlank() && draft.attachments.isEmpty()) return@launchDraftRead
        _uiState.update { cur ->
            val extra = if (handoffHasContent) toUploadedItems(draft.attachments.filterNot { it in cur.uploadedImageIds }) else emptyList()
            cur.copy(uploads = cur.uploads + extra, hadPersistedDraft = true)
        }
    }

    private fun loadEditPostSession(token: Long, preserveRestoredInput: Boolean) {
        val snap = _uiState.value
        val mode = snap.mode as? FullEditorMode.EditPost ?: return
        if (!snap.isLoggedIn) {
            if (!preserveRestoredInput) _uiState.update { it.copy(isLoadingEditOriginal = false, editLoadError = FullEditorInlineError(UiError(R.string.thread_detail_login_to_operate, action = UiErrorAction.Login))) }
            return
        }
        prePostJob?.cancel()
        if (!preserveRestoredInput) _uiState.update { it.copy(isLoadingEditOriginal = true, editLoadError = null) }
        prePostJob = viewModelScope.launch {
            val res = runCatching { postRepository.fetchPrePost(PrePostRequest.EditPost(mode.fid, mode.tid, mode.pid, mode.page)) }
                .onFailure { if (it is CancellationException) throw it }.getOrElse { PrePostResult.NetworkError(it.message.orEmpty(), it) }
            if (token != sessionToken || !_uiState.value.isOpen) return@launch
            if (res !is PrePostResult.Success) {
                _uiState.update { cur -> cur.copy(isLoadingEditOriginal = false, isPrefetchingPrePost = false, editLoadError = if (preserveRestoredInput) cur.editLoadError else mapPrePostFailure(res)) }
                return@launch
            }
            val info = res.info
            val origDoc = EditorBodyDocument.parse(info.text)
            val origType = info.typeId?.takeIf { it.isNotBlank() && it in info.typeOptions } ?: "0"
            val origImages = (info.existingImages + origDoc.insertedImageIds()).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val original = EditOriginalSnapshot(info.subject.orEmpty(), origType, origDoc.serialize(), origImages)
            if (preserveRestoredInput) {
                _uiState.update { cur -> cur.copy(prePostInfo = info, typeOptions = info.typeOptions, editOriginal = cur.editOriginal ?: original, isLoadingEditOriginal = false, isPrefetchingPrePost = false, editLoadError = null) }
                return@launch
            }
            draftMutationJob?.join()
            val draft = safeEditDraftKey(mode.pid)?.let { key -> runCatching { postRepository.observeDraft(key).first() }.onFailure { if (it is CancellationException) throw it }.getOrNull() }
            if (token != sessionToken || !_uiState.value.isOpen) return@launch
            val restored = draft?.takeIf { it.subject.isNotBlank() || it.content.isNotBlank() || it.attachments.isNotEmpty() }
            val doc = restored?.let { EditorBodyDocument.parse(it.content) } ?: origDoc
            val typeId = restored?.typeId?.takeIf { it.isNotBlank() && it in info.typeOptions } ?: origType
            val images = restored?.let { (it.attachments + doc.insertedImageIds()).map { id -> id.trim() }.filter { id -> id.isNotEmpty() }.distinct() } ?: origImages
            _uiState.update { cur ->
                cur.copy(
                    prePostInfo = info, typeOptions = info.typeOptions, editOriginal = original,
                    subject = restored?.subject ?: original.subject, selectedTypeId = typeId, body = doc, bodySyncVersion = cur.bodySyncVersion + 1L,
                    uploads = toUploadedItems(images), hadPersistedDraft = restored != null, showDraftRestoredBanner = restored != null,
                    isLoadingEditOriginal = false, isPrefetchingPrePost = false, editLoadError = null,
                )
            }
        }
    }

    private fun resetSession(
        isOpen: Boolean, sessionId: Long = _uiState.value.sessionId, mode: FullEditorMode = _uiState.value.mode,
        selectedFid: Int? = null, selectedTypeId: String = "0", subject: String = "",
        body: EditorBodyDocument = EditorBodyDocument.empty(), uploads: List<UploadAttachmentItem> = emptyList(),
        hadPersistedDraft: Boolean = false, showDraftBanner: Boolean = false, cooldownSeconds: Int? = null, bumpPickerTrigger: Boolean = false,
        editOriginal: EditOriginalSnapshot? = null, isLoadingEditOriginal: Boolean = false, resetSnackbar: Boolean = false,
    ) = _uiState.update { cur ->
        val nextReset = if (resetSnackbar) ++snackbarResetSeq else cur.snackbarResetSeq
        cur.copy(
            isOpen = isOpen, sessionId = sessionId, mode = mode, selectedFid = selectedFid, typeOptions = emptyMap(), selectedTypeId = selectedTypeId,
            subject = subject, body = body, bodySyncVersion = cur.bodySyncVersion + 1L, uploads = uploads, uploadBatchTotal = 0, uploadBatchCompleted = 0,
            prePostInfo = null, isPrefetchingPrePost = false, isSubmitting = false,
            cooldownSeconds = cooldownSeconds ?: maxOf(cur.cooldownSeconds, postRepository.getRemainingCooldownSeconds().coerceAtLeast(0)),
            hadPersistedDraft = hadPersistedDraft, showDraftRestoredBanner = showDraftBanner, validationError = null, inlineError = null,
            pendingImagePickerTrigger = if (bumpPickerTrigger) cur.pendingImagePickerTrigger + 1L else 0L,
            editOriginal = editOriginal, isLoadingEditOriginal = isLoadingEditOriginal, editLoadError = null,
            restoreSnackbarEvent = null, snackbarResetSeq = nextReset,
        )
    }

    private fun updateBodyAndBump(clearContentErr: Boolean, transform: (EditorBodyDocument) -> EditorBodyDocument) = _uiState.update { cur ->
        val next = transform(cur.body)
        if (next == cur.body) cur else cur.copy(body = next, bodySyncVersion = cur.bodySyncVersion + 1L, validationError = if (clearContentErr) cur.validationError.takeUnless { it == FullEditorValidationError.ContentTooShort } else cur.validationError)
    }

    private fun showLoginError() = _uiState.update { it.copy(inlineError = FullEditorInlineError(UiError(R.string.thread_detail_login_to_operate, action = UiErrorAction.Login))) }
    private fun toUploadedItems(ids: Collection<String>): List<UploadAttachmentItem> = ids.map { UploadAttachmentItem(nextUploadLocalId(), status = UploadAttachmentStatus.Uploaded(it)) }

    private fun updateUploadStatus(localId: String, status: UploadAttachmentStatus) = _uiState.update { cur ->
        val idx = cur.uploads.indexOfFirst { it.localId == localId }
        if (idx < 0 || cur.uploads[idx].status == status) cur else cur.copy(uploads = cur.uploads.toMutableList().apply { this[idx] = this[idx].copy(status = status) })
    }

    private fun markUploadTerminal(localId: String, status: UploadAttachmentStatus, token: Long) {
        if (token != sessionToken) return
        _uiState.update { cur ->
            val idx = cur.uploads.indexOfFirst { it.localId == localId }
            if (idx < 0) return@update cur
            val next = cur.uploads.toMutableList().apply { this[idx] = this[idx].copy(status = status) }
            val active = next.any { it.isInProgress }
            cur.copy(uploads = next, uploadBatchCompleted = if (active) cur.uploadBatchCompleted + 1 else 0, uploadBatchTotal = if (active) cur.uploadBatchTotal else 0, inlineError = if (!active && next.none { it.isFailed }) clearUploadHintError(cur.inlineError) else cur.inlineError)
        }
    }

    private fun cancelSessionJobs() { openDraftJob?.cancel(); openDraftJob = null; prePostJob?.cancel(); prePostJob = null; submitJob?.cancel(); submitJob = null; cancelUploadJobs() }
    private fun cancelUploadJobs() { uploadJobs.values.forEach { it.cancel() }; uploadJobs.clear() }

    private fun updateCooldown(seconds: Int) = _uiState.update { cur ->
        val nextErr = if (seconds <= 0 && cur.inlineError?.cooldownMessageSeconds != null) null else cur.inlineError
        if (cur.cooldownSeconds == seconds && cur.inlineError == nextErr) cur else cur.copy(cooldownSeconds = seconds, inlineError = nextErr)
    }

    private fun applyCooldownFailure(seconds: Int) {
        val safeSec = seconds.coerceAtLeast(1)
        _uiState.update { it.copy(cooldownSeconds = maxOf(it.cooldownSeconds, safeSec), inlineError = FullEditorInlineError(cooldownMessageSeconds = safeSec)) }
        if (postRepository.cooldownUntilMs.value <= clockMs()) {
            localCooldownJob?.cancel()
            val untilMs = clockMs() + safeSec * 1_000L
            localCooldownJob = viewModelScope.launch { runCooldownTickerLoop(untilMs, clockMs, { ((untilMs - clockMs()).takeIf { it > 0L }?.let { (it + 999L) / 1_000L } ?: 0L).toInt() }, ::updateCooldown) }
        }
    }

    private fun enqueueDraftMutation(action: suspend () -> Unit) { draftMutationJob = viewModelScope.enqueueSerialDraftMutation(draftMutationJob, action) }
    private fun nextUploadLocalId(): String = "att_${uploadIdSeq.incrementAndGet()}"
    private fun currentDraftKey(state: FullEditorUiState = _uiState.value): DraftKey? = when (val m = state.mode) {
        is FullEditorMode.Reply -> safeReplyDraftKey(m.tid)
        is FullEditorMode.EditPost -> safeEditDraftKey(m.pid)
        is FullEditorMode.NewThread -> state.selectedFid?.takeIf { it > 0 }?.let(::safeNewThreadDraftKey)
    }

    companion object {
        const val MIN_TITLE_CODE_POINTS: Int = 6
        const val MIN_CONTENT_CODE_POINTS: Int = 6

        internal fun safeNewThreadDraftKey(fid: Int): DraftKey? = if (fid <= 0) null else runCatching { DraftKey.newThread(fid.toString()) }.getOrNull()

        internal fun isDifferentFromEditOriginal(state: FullEditorUiState): Boolean {
            val orig = state.editOriginal ?: return false
            if (state.hasActiveUploads || state.hasFailedUploads) return true
            val curSubject = if (state.showsEditSubjectField) state.subject else orig.subject
            val curTypeId = if (state.hasSelectableCategories) state.selectedTypeId.ifBlank { "0" } else orig.typeId
            return curSubject != orig.subject || curTypeId != orig.typeId ||
                state.body.serialize() != orig.serializedBody || state.uploadedImageIds.toSet() != orig.imageIds.toSet()
        }

        private fun isSameSessionTarget(cur: FullEditorUiState, req: FullEditorRequest): Boolean {
            val curKey = when (val m = cur.mode) {
                is FullEditorMode.Reply -> safeReplyDraftKey(m.tid)
                is FullEditorMode.EditPost -> safeEditDraftKey(m.pid)
                is FullEditorMode.NewThread -> cur.selectedFid?.takeIf { it > 0 }?.let(::safeNewThreadDraftKey)
            }
            val reqKey = when (req) {
                is FullEditorRequest.NewThread -> req.initialFid?.takeIf { it > 0 }?.let(::safeNewThreadDraftKey)
                is FullEditorRequest.Reply -> safeReplyDraftKey(req.handoff.tid)
                is FullEditorRequest.EditPost -> safeEditDraftKey(req.pid)
            }
            return if (curKey != null && reqKey != null) curKey == reqKey
            else if (cur.mode is FullEditorMode.EditPost && req is FullEditorRequest.EditPost) cur.mode.pid == req.pid.trim()
            else cur.isNewThread && req is FullEditorRequest.NewThread && (req.initialFid ?: 0) <= 0
        }

        internal fun validateForSubmit(state: FullEditorUiState): FullEditorValidationError? {
            when (val m = state.mode) {
                is FullEditorMode.NewThread -> {
                    if ((state.selectedFid ?: 0) <= 0) return FullEditorValidationError.SelectForum
                    if (state.hasSelectableCategories && (state.selectedTypeId.isBlank() || state.selectedTypeId == "0")) return FullEditorValidationError.SelectCategory
                    val trimmed = state.subject.trim()
                    if (trimmed.isEmpty()) return FullEditorValidationError.EnterTitle
                    if (trimmed.codePointCount(0, trimmed.length) < MIN_TITLE_CODE_POINTS) return FullEditorValidationError.TitleTooShort
                }
                is FullEditorMode.EditPost -> {
                    if (m.isFirstFloor) {
                        if (state.hasSelectableCategories && (state.selectedTypeId.isBlank() || state.selectedTypeId == "0")) return FullEditorValidationError.SelectCategory
                        val trimmed = state.subject.trim()
                        if (trimmed.isEmpty()) return FullEditorValidationError.EnterTitle
                        val origTrimmed = state.editOriginal?.subject?.trim().orEmpty()
                        val origLen = origTrimmed.codePointCount(0, origTrimmed.length)
                        if (trimmed.codePointCount(0, trimmed.length) < MIN_TITLE_CODE_POINTS && (trimmed != origTrimmed || origLen >= MIN_TITLE_CODE_POINTS)) {
                            return FullEditorValidationError.TitleTooShort
                        }
                    }
                    return if (state.body.isVisuallyEmpty() && state.uploadedImageIds.isEmpty()) FullEditorValidationError.ContentTooShort else null
                }
                is FullEditorMode.Reply -> Unit
            }
            return if (state.body.plainTextTrimmedCodePoints() < MIN_CONTENT_CODE_POINTS && state.uploadedImageIds.isEmpty()) FullEditorValidationError.ContentTooShort else null
        }

        private fun buildPrePostRequest(state: FullEditorUiState): PrePostRequest? = when (val m = state.mode) {
            is FullEditorMode.NewThread -> state.selectedFid?.takeIf { it > 0 }?.let(PrePostRequest::NewThread)
            is FullEditorMode.ReplyThread -> m.tid.takeIf { it.isNotBlank() }?.let { PrePostRequest.ReplyThread(it, quickReply = false) }
            is FullEditorMode.ReplyPost -> m.tid.takeIf { it.isNotBlank() }?.let { PrePostRequest.ReplyPost(it, m.targetPid) }
            is FullEditorMode.QuotePost -> m.tid.takeIf { it.isNotBlank() }?.let { PrePostRequest.QuotePost(it, m.targetPid) }
            is FullEditorMode.EditPost -> m.tid.takeIf { it.isNotBlank() && m.pid.isNotBlank() }?.let { PrePostRequest.EditPost(m.fid, it, m.pid, m.page) }
        }

        private fun buildSubmitRequest(s: FullEditorUiState, content: String, ids: List<String>, key: DraftKey?): PostSubmitRequest? = when (val m = s.mode) {
            is FullEditorMode.NewThread -> s.selectedFid?.takeIf { it > 0 }?.let { PostSubmitRequest.NewThread(it, s.subject.trim(), content, s.selectedTypeId.ifBlank { "0" }, key, ids, prePostInfo = s.prePostInfo) }
            is FullEditorMode.ReplyThread -> PostSubmitRequest.ReplyThread(m.tid, content, quickReply = false, key, ids, prePostInfo = s.prePostInfo)
            is FullEditorMode.ReplyPost -> PostSubmitRequest.ReplyPost(m.tid, m.targetPid, content, key, ids, prePostInfo = s.prePostInfo)
            is FullEditorMode.QuotePost -> PostSubmitRequest.QuotePost(m.tid, m.targetPid, content, key, ids, prePostInfo = s.prePostInfo)
            is FullEditorMode.EditPost -> PostSubmitRequest.EditPost(
                fid = m.fid, tid = m.tid, pid = m.pid, content = content,
                subject = if (s.showsEditSubjectField) s.subject.trim() else s.editOriginal?.subject?.takeIf { it.isNotEmpty() },
                typeId = if (s.hasSelectableCategories) s.selectedTypeId.ifBlank { "0" } else s.editOriginal?.typeId?.takeIf { it.isNotEmpty() && it != "0" },
                page = m.page, draftKey = key, newAttachments = s.newEditAttachmentIds, deletedAttachments = s.deletedEditAttachmentIds, prePostInfo = s.prePostInfo,
            )
        }

        private fun mapPrePostFailure(result: PrePostResult): FullEditorInlineError = when (result) {
            is PrePostResult.AuthRequired -> FullEditorInlineError(UiErrorMapper.mapAuthRequired(result.reason))
            is PrePostResult.ChallengeRequired -> FullEditorInlineError(UiError(if (result.rejected) R.string.challenge_status_rejected else R.string.challenge_status_incomplete, action = UiErrorAction.Reverify))
            PrePostResult.RealNameBindRequired -> FullEditorInlineError(UiError(R.string.compose_error_real_name_required))
            is PrePostResult.SiteError -> FullEditorInlineError(UiError(R.string.common_error_load_failed, result.message.trim().ifEmpty { null }, UiErrorAction.Retry), customMessage = result.message.trim().ifEmpty { null })
            is PrePostResult.NetworkError -> FullEditorInlineError(UiError(R.string.common_error_network, result.message.trim().ifEmpty { null }, UiErrorAction.Retry))
            else -> FullEditorInlineError(UiError(R.string.common_error_load_failed, action = UiErrorAction.Retry))
        }

        private fun mapSubmitFailure(result: PostSubmitResult): FullEditorInlineError =
            FullEditorInlineError(mapPostSubmitFailureUiError(result), customMessage = (result as? PostSubmitResult.SiteError)?.message?.trim()?.ifEmpty { null })

        private fun buildOrderedForums(followedFids: List<Int>): List<ForumMetadata> {
            val seen = LinkedHashSet<Int>()
            return buildList { followedFids.forEach { if (it > 0 && seen.add(it)) add(ForumMetadataRegistry.resolve(it)) }; ForumMetadataRegistry.BuiltInForums.forEach { if (seen.add(it.fid)) add(it) } }
        }

        private fun buildContentSignature(subject: String, body: String, ids: List<String>): String = "${subject.trim()}\u0000${body.trim()}\u0000${ids.joinToString(",")}"
        private fun clearRetryError(err: FullEditorInlineError?): FullEditorInlineError? = if (err?.uiError?.action == UiErrorAction.Retry) null else err
        private fun clearUploadHintError(err: FullEditorInlineError?): FullEditorInlineError? =
            if (err?.uiError?.messageRes in setOf(R.string.compose_upload_in_progress_hint, R.string.compose_upload_has_failed_hint, R.string.compose_upload_invalid_uri)) null else err
    }
}
