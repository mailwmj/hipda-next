package net.jejer.hipda.feature.messages

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.jejer.hipda.data.auth.*
import net.jejer.hipda.data.message.*
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.parser.message.SmsMessageItem
import net.jejer.hipda.ui.challenge.ChallengeFailureKind

enum class SmsBubbleDeliveryStatus { Delivered, Sending, Failed }

@Immutable
data class SmsBubbleUiItem(
    val id: String, val author: String, val avatarUrl: String?, val time: String,
    val contentHtml: String, val rawMessage: String? = null, val isMine: Boolean,
    val deliveryStatus: SmsBubbleDeliveryStatus = SmsBubbleDeliveryStatus.Delivered,
)

/** 单次可撤销删除令牌：捕获唯一令牌号、被删气泡与前驱邻居 id 锚点（严禁按下标恢复）。 */
@Immutable
data class DeletedSmsUndoToken(val tokenId: Long, val item: SmsBubbleUiItem, val anchorPrevId: String?)

@Immutable
data class SmsConversationUiState(
    val peerUid: String = "", val peerUsername: String = "", val messages: List<SmsBubbleUiItem> = emptyList(),
    val inputText: String = "", val cooldownSeconds: Int = 0, val hasLoadedOnce: Boolean = false,
    val isLoading: Boolean = false, val isRefreshing: Boolean = false, val isClearing: Boolean = false,
    val error: UiError? = null, val challengeFailure: ChallengeFailureKind? = null,
    val challengeState: ChallengeState = ChallengeState.Idle, val scrollToBottomEventId: Long = 0L,
) {
    val canSend: Boolean get() = inputText.isNotBlank() && cooldownSeconds <= 0 && hasLoadedOnce && !isLoading
}

@Immutable
sealed interface SmsConversationEvent {
    data class CooldownBlocked(val remainingSeconds: Int) : SmsConversationEvent
    data class ShowError(val error: UiError) : SmsConversationEvent
}

/** 短消息对话 ViewModel（§7.3/§7.5）：显式串行乐观发送、本地/服务端水位对齐、失败队尾重发/锚点撤销、15 秒冷却、切号隔离与对端用户名回填。 */
@HiltViewModel
class SmsConversationViewModel internal constructor(
    private val smsRepository: SmsRepository,
    private val authRepository: AuthRepository,
    private val siteAccessGateway: SiteAccessGateway,
    private val clockMs: () -> Long,
) : ViewModel() {

    @Inject
    constructor(smsRepository: SmsRepository, authRepository: AuthRepository, siteAccessGateway: SiteAccessGateway) :
        this(smsRepository, authRepository, siteAccessGateway, System::currentTimeMillis)

    private val localIdSeq = AtomicLong(0L)
    private val scrollEventSeq = AtomicLong(0L)
    private val undoTokenSeq = AtomicLong(0L)
    private val sendMutex = Mutex()
    private val reconciledLocalIds = mutableSetOf<String>()
    private var activeUndoTokenId: Long? = null
    private var conversationGeneration = 0
    private var loadJob: Job? = null

    private val _uiState = MutableStateFlow(
        SmsConversationUiState(cooldownSeconds = smsRepository.getRemainingCooldownSeconds().coerceAtLeast(0), challengeState = siteAccessGateway.challengeState.value),
    )
    val uiState: StateFlow<SmsConversationUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SmsConversationEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<SmsConversationEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            siteAccessGateway.challengeState.collect { ch -> _uiState.update { it.copy(challengeState = ch, challengeFailure = if (ch is ChallengeState.Solving) null else it.challengeFailure) } }
        }
        viewModelScope.launch {
            smsRepository.cooldownUntilMs.collectLatest { untilMs ->
                if (untilMs <= 0L) { _uiState.update { it.copy(cooldownSeconds = 0) }; return@collectLatest }
                while (true) {
                    val remaining = smsRepository.getRemainingCooldownSeconds().coerceAtLeast(0)
                    _uiState.update { it.copy(cooldownSeconds = remaining) }
                    if (remaining <= 0) break
                    delay(nextCooldownTickDelayMs(untilMs, clockMs()))
                }
            }
        }
        viewModelScope.observeMessageAuthChanges(authRepository, { _uiState.value.error?.action == UiErrorAction.Login }) { uidChanged, signedIn ->
            val cur = _uiState.value
            if (cur.peerUid.isEmpty() && cur.peerUsername.isEmpty()) return@observeMessageAuthChanges
            resetGeneration()
            when {
                !signedIn -> applySignedOut(cur.peerUid, cur.peerUsername)
                uidChanged -> { resetForLoad(cur.peerUid, cur.peerUsername); if (cur.peerUid.isNotEmpty()) loadConversation(false, AccessPriority.UserBlocking) }
                else -> loadConversation(false, AccessPriority.UserBlocking)
            }
        }
    }

    fun bindConversation(args: SmsConversationArgs) {
        val targetUid = args.uid.trim()
        val targetName = args.username.trim()
        val cur = _uiState.value
        if (cur.peerUid == targetUid && (targetUid.isNotEmpty() || cur.peerUsername == targetName) && cur.hasLoadedOnce) {
            if (targetName.isNotEmpty() && cur.peerUsername != targetName) _uiState.update { it.copy(peerUsername = targetName) }
            return
        }
        resetGeneration()
        if (authRepository.currentAccount.value == null) { applySignedOut(targetUid, targetName); return }
        resetForLoad(targetUid, targetName)
        if (targetUid.isNotEmpty()) loadConversation(false, AccessPriority.UserBlocking)
    }

    fun onInputChange(value: String) = _uiState.update { it.copy(inputText = value) }

    fun refresh(priority: AccessPriority = AccessPriority.UserBlocking) {
        val cur = _uiState.value
        if (cur.peerUid.isBlank()) return
        if (authRepository.currentAccount.value == null) { applySignedOut(cur.peerUid, cur.peerUsername); return }
        loadConversation(cur.messages.isNotEmpty(), priority)
    }

    fun sendMessage() {
        val snap = _uiState.value
        if (!snap.hasLoadedOnce || snap.isLoading) return
        val text = snap.inputText.trim().ifEmpty { return }
        if (checkCooldownBlocked(snap.cooldownSeconds)) return
        val localId = "$LOCAL_ID_PREFIX${localIdSeq.incrementAndGet()}"
        val bubble = SmsBubbleUiItem(localId, authRepository.currentAccount.value?.username.orEmpty(), null, "", text, text, isMine = true, deliveryStatus = SmsBubbleDeliveryStatus.Sending)
        _uiState.update { it.copy(inputText = "", messages = it.messages + bubble, scrollToBottomEventId = scrollEventSeq.incrementAndGet()) }
        dispatchSend(localId, text, conversationGeneration)
    }

    fun resendMessage(bubbleId: String) {
        val cur = _uiState.value
        val target = cur.messages.firstOrNull { it.id == bubbleId && it.deliveryStatus == SmsBubbleDeliveryStatus.Failed } ?: return
        val msg = target.rawMessage?.takeIf { it.isNotBlank() } ?: return
        if (checkCooldownBlocked(cur.cooldownSeconds)) return
        _uiState.update { state ->
            state.copy(messages = state.messages.filterNot { it.id == bubbleId } + target.copy(deliveryStatus = SmsBubbleDeliveryStatus.Sending), scrollToBottomEventId = scrollEventSeq.incrementAndGet())
        }
        dispatchSend(bubbleId, msg, conversationGeneration)
    }

    fun deleteFailedMessage(bubbleId: String): DeletedSmsUndoToken? {
        var token: DeletedSmsUndoToken? = null
        _uiState.update { cur ->
            val idx = cur.messages.indexOfFirst { it.id == bubbleId && it.deliveryStatus == SmsBubbleDeliveryStatus.Failed }
            if (idx < 0) return@update cur
            val nextToken = DeletedSmsUndoToken(undoTokenSeq.incrementAndGet(), cur.messages[idx], cur.messages.getOrNull(idx - 1)?.id)
            activeUndoTokenId = nextToken.tokenId
            token = nextToken
            cur.copy(messages = cur.messages.toMutableList().apply { removeAt(idx) })
        }
        return token
    }

    fun restoreDeletedMessage(token: DeletedSmsUndoToken) {
        if (activeUndoTokenId != token.tokenId) return
        activeUndoTokenId = null
        _uiState.update { cur ->
            if (cur.messages.any { it.id == token.item.id }) return@update cur
            val serverEnd = cur.messages.indexOfLast { !it.id.startsWith(LOCAL_ID_PREFIX) } + 1
            val insertAt = when (val prevId = token.anchorPrevId) {
                null -> serverEnd
                else -> cur.messages.indexOfFirst { it.id == prevId }.let { if (it >= 0) maxOf(it + 1, serverEnd) else cur.messages.size }
            }
            cur.copy(messages = cur.messages.toMutableList().apply { add(insertAt.coerceIn(0, size), token.item) })
        }
    }

    fun clearConversation() {
        val cur = _uiState.value
        if (cur.peerUid.isBlank() || cur.isClearing) return
        val gen = conversationGeneration
        _uiState.update { it.copy(isClearing = true) }
        viewModelScope.launch {
            val outcome = smsRepository.clearConversation(cur.peerUid, AccessPriority.UserBlocking)
            if (conversationGeneration != gen) return@launch
            if (outcome == SmsClearOutcome.Cleared) {
                reconciledLocalIds.clear(); activeUndoTokenId = null
                _uiState.update { it.copy(messages = emptyList(), isClearing = false) }
            } else {
                _uiState.update { it.copy(isClearing = false) }
                mapClearFailure(outcome)?.let { _events.tryEmit(SmsConversationEvent.ShowError(it)) }
            }
        }
    }

    private fun resetGeneration() { conversationGeneration++; loadJob?.cancel(); reconciledLocalIds.clear(); activeUndoTokenId = null }

    private fun resetForLoad(peerUid: String, peerUsername: String) = _uiState.update {
        it.copy(
            peerUid = peerUid, peerUsername = peerUsername, messages = emptyList(), inputText = "",
            cooldownSeconds = smsRepository.getRemainingCooldownSeconds().coerceAtLeast(0),
            hasLoadedOnce = peerUid.isEmpty(), isLoading = peerUid.isNotEmpty(), isRefreshing = false, isClearing = false, error = null, challengeFailure = null,
        )
    }

    private fun applySignedOut(peerUid: String, peerUsername: String) = _uiState.update {
        it.copy(
            peerUid = peerUid, peerUsername = peerUsername, messages = emptyList(), inputText = "", cooldownSeconds = 0,
            hasLoadedOnce = true, isLoading = false, isRefreshing = false, isClearing = false,
            error = UiErrorMapper.mapAuthRequired(AuthRequiredReason.NOT_LOGGED_IN), challengeFailure = null,
        )
    }

    private fun checkCooldownBlocked(currentSeconds: Int): Boolean {
        val active = maxOf(currentSeconds, smsRepository.getRemainingCooldownSeconds().coerceAtLeast(0))
        if (active <= 0) return false
        _uiState.update { it.copy(cooldownSeconds = active) }
        _events.tryEmit(SmsConversationEvent.CooldownBlocked(active))
        return true
    }

    private fun dispatchSend(localId: String, message: String, expectedGen: Int) {
        val uid = _uiState.value.peerUid.ifBlank { null }
        val username = _uiState.value.peerUsername.ifBlank { null }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sendMutex.withLock {
                if (conversationGeneration != expectedGen) return@withLock
                val outcome = smsRepository.sendSms(uid, username, message, AccessPriority.UserBlocking)
                if (conversationGeneration != expectedGen) return@withLock
                val reconciled = reconciledLocalIds.remove(localId)
                when (outcome) {
                    SmsSendOutcome.Sent -> {
                        val cd = smsRepository.getRemainingCooldownSeconds().coerceAtLeast(0)
                        _uiState.update { cur ->
                            cur.copy(messages = if (reconciled) cur.messages else cur.messages.map { if (it.id == localId) it.copy(deliveryStatus = SmsBubbleDeliveryStatus.Delivered) else it }, cooldownSeconds = maxOf(cur.cooldownSeconds, cd))
                        }
                    }
                    is SmsSendOutcome.CooldownActive -> {
                        if (!reconciled) updateBubbleStatus(localId, SmsBubbleDeliveryStatus.Failed)
                        _uiState.update { it.copy(cooldownSeconds = outcome.remainingSeconds.coerceAtLeast(0)) }
                        _events.tryEmit(SmsConversationEvent.CooldownBlocked(outcome.remainingSeconds))
                    }
                    is SmsSendOutcome.AuthRequired -> {
                        if (!reconciled) updateBubbleStatus(localId, SmsBubbleDeliveryStatus.Failed)
                        _events.tryEmit(SmsConversationEvent.ShowError(UiErrorMapper.mapAuthRequired(outcome.reason ?: AuthRequiredReason.RELOGIN_FAILED)))
                    }
                    else -> if (!reconciled) updateBubbleStatus(localId, SmsBubbleDeliveryStatus.Failed)
                }
            }
        }
    }

    private fun updateBubbleStatus(bubbleId: String, status: SmsBubbleDeliveryStatus) =
        _uiState.update { cur -> cur.copy(messages = cur.messages.map { if (it.id == bubbleId) it.copy(deliveryStatus = status) else it }) }

    private fun loadConversation(isRefresh: Boolean, priority: AccessPriority) {
        val peerUid = _uiState.value.peerUid.ifBlank { return }
        val gen = conversationGeneration
        loadJob?.cancel()
        val had = _uiState.value.messages.isNotEmpty()
        _uiState.update { it.copy(isLoading = !isRefresh && !had, isRefreshing = isRefresh && had, error = if (had) it.error else null, challengeFailure = if (had) it.challengeFailure else null) }
        loadJob = viewModelScope.launch {
            val res = smsRepository.getConversationDetail(peerUid, priority)
            if (conversationGeneration != gen) return@launch
            if (res is MessageResult.Success) {
                val serverBubbles = res.data.messages.mapIndexed { idx, m -> m.toBubble(idx) }
                val backfilledPeer = res.data.messages.firstOrNull { !it.isMine && it.author.isNotBlank() }?.author.orEmpty()
                _uiState.update { cur ->
                    val merged = reconcileServerAndLocalBubbles(cur.messages, serverBubbles, reconciledLocalIds)
                    cur.copy(
                        peerUsername = cur.peerUsername.ifBlank { backfilledPeer }, messages = merged, hasLoadedOnce = true, isLoading = false, isRefreshing = false, error = null, challengeFailure = null,
                        scrollToBottomEventId = if (!isRefresh && merged.isNotEmpty()) scrollEventSeq.incrementAndGet() else cur.scrollToBottomEventId,
                    )
                }
            } else {
                val (uiError, challenge) = mapMessageResultFailure(res) ?: return@launch
                val existing = _uiState.value.messages.isNotEmpty()
                val isAuth = res is MessageResult.AuthRequired
                _uiState.update { it.copy(hasLoadedOnce = true, isLoading = false, isRefreshing = false, error = if (!existing || isAuth) uiError else null, challengeFailure = if (!existing) challenge else null, messages = if (isAuth) emptyList() else it.messages) }
                if (existing && !isAuth) _events.tryEmit(SmsConversationEvent.ShowError(uiError))
            }
        }
    }

    private companion object {
        const val LOCAL_ID_PREFIX = "local:"
        fun SmsMessageItem.toBubble(index: Int) = SmsBubbleUiItem("server:$index:$uid:$time:${contentHtml.hashCode()}", author, avatarUrl, time, contentHtml, isMine = isMine)
        fun nextCooldownTickDelayMs(untilMs: Long, nowMs: Long): Long =
            (untilMs - nowMs).let { raw -> if (raw !in 1L..30_000L) 250L else (raw % 1_000L).let { if (it == 0L) 1_000L else it } }

        /** 对齐服务端列表与本地 `local:*` 气泡：仅用新增服务端尾段（水位之后）按规范正文去重，既不吞已确认消息，也不产生双份气泡。 */
        fun reconcileServerAndLocalBubbles(cur: List<SmsBubbleUiItem>, incoming: List<SmsBubbleUiItem>, reconciled: MutableSet<String>): List<SmsBubbleUiItem> {
            val prevServer = cur.filterNot { it.id.startsWith(LOCAL_ID_PREFIX) }
            val prevKeys = prevServer.map(::watermarkKey)
            val newKeys = incoming.map(::watermarkKey)
            val server = if (newKeys.size < prevKeys.size && prevKeys.take(newKeys.size) == newKeys) prevServer else incoming
            val serverKeys = if (server === prevServer) prevKeys else newKeys
            val overlap = (minOf(prevKeys.size, serverKeys.size) downTo 1).firstOrNull { len -> prevKeys.subList(prevKeys.size - len, prevKeys.size) == serverKeys.subList(0, len) } ?: 0
            val newMine = server.drop(overlap).filter { it.isMine }.map { normalizeSmsCompareText(it.contentHtml, false) }.toMutableList()
            val kept = cur.filter { it.id.startsWith(LOCAL_ID_PREFIX) }.filter { local ->
                if (local.deliveryStatus == SmsBubbleDeliveryStatus.Failed) return@filter true
                val idx = newMine.indexOf(normalizeSmsCompareText(local.rawMessage ?: local.contentHtml, local.rawMessage != null))
                if (idx >= 0) { newMine.removeAt(idx); if (local.deliveryStatus == SmsBubbleDeliveryStatus.Sending) reconciled += local.id; false } else true
            }
            return server + kept
        }

        fun watermarkKey(item: SmsBubbleUiItem) = Triple(item.isMine, item.time, normalizeSmsCompareText(item.contentHtml, false))
    }
}
