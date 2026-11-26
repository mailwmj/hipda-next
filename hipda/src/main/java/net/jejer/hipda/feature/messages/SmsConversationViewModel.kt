package net.jejer.hipda.feature.messages

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.data.auth.*
import net.jejer.hipda.data.message.*
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.parser.message.SmsMessageItem
import net.jejer.hipda.ui.challenge.ChallengeFailureKind

enum class SmsBubbleDeliveryStatus { Delivered, Sending, Failed }

@Immutable
data class SmsBubbleUiItem(
    val id: String,
    val uid: String,
    val author: String,
    val avatarUrl: String?,
    val time: String,
    val contentHtml: String,
    val rawMessage: String? = null,
    val isMine: Boolean,
    val deliveryStatus: SmsBubbleDeliveryStatus = SmsBubbleDeliveryStatus.Delivered,
)

@Immutable
data class DeletedSmsSnapshot(val index: Int, val item: SmsBubbleUiItem)

@Immutable
data class SmsConversationUiState(
    val peerUid: String = "",
    val peerUsername: String = "",
    val messages: List<SmsBubbleUiItem> = emptyList(),
    val inputText: String = "",
    val cooldownSeconds: Int = 0,
    val hasLoadedOnce: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isClearing: Boolean = false,
    val error: UiError? = null,
    val challengeFailure: ChallengeFailureKind? = null,
    val challengeState: ChallengeState = ChallengeState.Idle,
    val scrollToBottomEventId: Long = 0L,
) {
    val canSend: Boolean get() = inputText.isNotBlank() && cooldownSeconds <= 0
}

@Immutable
sealed interface SmsConversationEvent {
    data class CooldownBlocked(val remainingSeconds: Int) : SmsConversationEvent
    data class ShowError(val error: UiError) : SmsConversationEvent
    data object ConversationCleared : SmsConversationEvent
}

/** 短消息对话 ViewModel（§7.3/§7.5）：乐观发送、失败重发/删除撤销、15 秒冷却倒计时（期间可编辑输入）与清空会话。 */
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
        viewModelScope.observeMessageAuthChanges(authRepository, { _uiState.value.error?.action == UiErrorAction.Login }) { _, signedIn ->
            val cur = _uiState.value
            if (cur.peerUid.isEmpty() && cur.peerUsername.isEmpty()) return@observeMessageAuthChanges
            conversationGeneration++; loadJob?.cancel()
            if (signedIn) loadConversation(false, AccessPriority.UserBlocking) else applySignedOut(cur.peerUid, cur.peerUsername)
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
        conversationGeneration++; loadJob?.cancel()
        if (authRepository.currentAccount.value == null) { applySignedOut(targetUid, targetName); return }
        _uiState.update { it.copy(peerUid = targetUid, peerUsername = targetName, messages = emptyList(), hasLoadedOnce = targetUid.isEmpty(), isLoading = targetUid.isNotEmpty(), isRefreshing = false, error = null, challengeFailure = null) }
        if (targetUid.isNotEmpty()) loadConversation(false, AccessPriority.UserBlocking)
    }

    fun onInputChange(value: String) = _uiState.update { it.copy(inputText = value) }
    fun appendEmojiCode(code: String) = _uiState.update { it.copy(inputText = it.inputText + code) }

    fun refresh(priority: AccessPriority = AccessPriority.UserBlocking) {
        val cur = _uiState.value
        if (cur.peerUid.isBlank()) return
        if (authRepository.currentAccount.value == null) { applySignedOut(cur.peerUid, cur.peerUsername); return }
        loadConversation(cur.messages.isNotEmpty(), priority)
    }

    fun retry(priority: AccessPriority = AccessPriority.UserBlocking) = refresh(priority)

    fun sendMessage() {
        val snap = _uiState.value
        val text = snap.inputText.trim().ifEmpty { return }
        if (checkCooldownBlocked(snap.cooldownSeconds)) return
        val account = authRepository.currentAccount.value
        val localId = "local:${localIdSeq.incrementAndGet()}"
        val bubble = SmsBubbleUiItem(localId, account?.uid.orEmpty(), account?.username.orEmpty(), null, "", escapeHtmlText(text), text, isMine = true, deliveryStatus = SmsBubbleDeliveryStatus.Sending)
        _uiState.update { it.copy(inputText = "", messages = it.messages + bubble, scrollToBottomEventId = scrollEventSeq.incrementAndGet()) }
        dispatchSend(localId, text, conversationGeneration)
    }

    fun resendMessage(bubbleId: String) {
        val cur = _uiState.value
        val target = cur.messages.firstOrNull { it.id == bubbleId && it.deliveryStatus == SmsBubbleDeliveryStatus.Failed } ?: return
        val msg = target.rawMessage?.takeIf { it.isNotBlank() } ?: return
        if (checkCooldownBlocked(cur.cooldownSeconds)) return
        updateBubbleStatus(bubbleId, SmsBubbleDeliveryStatus.Sending)
        dispatchSend(bubbleId, msg, conversationGeneration)
    }

    fun deleteFailedMessage(bubbleId: String): DeletedSmsSnapshot? {
        var removed: DeletedSmsSnapshot? = null
        _uiState.update { cur ->
            val idx = cur.messages.indexOfFirst { it.id == bubbleId && it.deliveryStatus == SmsBubbleDeliveryStatus.Failed }
            if (idx < 0) return@update cur
            removed = DeletedSmsSnapshot(idx, cur.messages[idx])
            cur.copy(messages = cur.messages.toMutableList().apply { removeAt(idx) })
        }
        return removed
    }

    fun restoreDeletedMessage(snapshot: DeletedSmsSnapshot) = _uiState.update { cur ->
        if (cur.messages.any { it.id == snapshot.item.id }) cur
        else cur.copy(messages = cur.messages.toMutableList().apply { add(snapshot.index.coerceIn(0, size), snapshot.item) })
    }

    fun clearConversation() {
        val cur = _uiState.value
        if (cur.peerUid.isBlank() || cur.isClearing) return
        val gen = conversationGeneration
        _uiState.update { it.copy(isClearing = true) }
        viewModelScope.launch {
            val outcome = runCatchingExceptCancel { smsRepository.clearConversation(cur.peerUid, AccessPriority.UserBlocking) }.getOrElse { SmsClearOutcome.Error(it.message.orEmpty(), it) }
            if (conversationGeneration != gen) return@launch
            if (outcome == SmsClearOutcome.Cleared) {
                _uiState.update { it.copy(messages = emptyList(), isClearing = false) }
                _events.tryEmit(SmsConversationEvent.ConversationCleared)
            } else {
                _uiState.update { it.copy(isClearing = false) }
                mapClearFailure(outcome)?.let { _events.tryEmit(SmsConversationEvent.ShowError(it)) }
            }
        }
    }

    private fun applySignedOut(peerUid: String, peerUsername: String) = _uiState.update {
        it.copy(peerUid = peerUid, peerUsername = peerUsername, messages = emptyList(), hasLoadedOnce = true, isLoading = false, isRefreshing = false, error = UiErrorMapper.mapAuthRequired(AuthRequiredReason.NOT_LOGGED_IN), challengeFailure = null)
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
        viewModelScope.launch {
            val outcome = runCatchingExceptCancel { smsRepository.sendSms(uid, username, message, AccessPriority.UserBlocking) }.getOrElse { SmsSendOutcome.Error(it.message.orEmpty(), it) }
            if (conversationGeneration != expectedGen) return@launch
            when (outcome) {
                SmsSendOutcome.Sent -> {
                    val cooldown = smsRepository.getRemainingCooldownSeconds().coerceAtLeast(0)
                    _uiState.update { cur -> cur.copy(messages = cur.messages.map { if (it.id == localId) it.copy(deliveryStatus = SmsBubbleDeliveryStatus.Delivered) else it }, cooldownSeconds = maxOf(cur.cooldownSeconds, cooldown)) }
                }
                is SmsSendOutcome.CooldownActive -> {
                    updateBubbleStatus(localId, SmsBubbleDeliveryStatus.Failed)
                    _uiState.update { it.copy(cooldownSeconds = outcome.remainingSeconds.coerceAtLeast(0)) }
                    _events.tryEmit(SmsConversationEvent.CooldownBlocked(outcome.remainingSeconds))
                }
                is SmsSendOutcome.AuthRequired -> {
                    updateBubbleStatus(localId, SmsBubbleDeliveryStatus.Failed)
                    _events.tryEmit(SmsConversationEvent.ShowError(UiErrorMapper.mapAuthRequired(outcome.reason ?: AuthRequiredReason.RELOGIN_FAILED)))
                }
                else -> updateBubbleStatus(localId, SmsBubbleDeliveryStatus.Failed)
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
            val res = runCatchingExceptCancel { smsRepository.getConversationDetail(peerUid, priority) }.getOrElse { MessageResult.Error(it.message.orEmpty(), it) }
            if (conversationGeneration != gen) return@launch
            if (res is MessageResult.Success) {
                val serverBubbles = res.data.messages.mapIndexed { idx, m -> m.toBubble(idx) }
                _uiState.update { cur ->
                    val merged = serverBubbles + cur.messages.filter { it.deliveryStatus != SmsBubbleDeliveryStatus.Delivered }
                    cur.copy(messages = merged, hasLoadedOnce = true, isLoading = false, isRefreshing = false, error = null, challengeFailure = null, scrollToBottomEventId = if (!isRefresh && merged.isNotEmpty()) scrollEventSeq.incrementAndGet() else cur.scrollToBottomEventId)
                }
            } else {
                val mapped = mapMessageResultFailure(res) ?: return@launch
                val existing = _uiState.value.messages.isNotEmpty()
                val isAuth = res is MessageResult.AuthRequired
                _uiState.update { it.copy(hasLoadedOnce = true, isLoading = false, isRefreshing = false, error = if (!existing || isAuth) mapped.uiError else null, challengeFailure = if (!existing) mapped.challengeFailure else null, messages = if (isAuth) emptyList() else it.messages) }
                if (existing && !isAuth) _events.tryEmit(SmsConversationEvent.ShowError(mapped.uiError))
            }
        }
    }

    private companion object {
        fun SmsMessageItem.toBubble(index: Int) = SmsBubbleUiItem("server:$index:$uid:$time:${contentHtml.hashCode()}", uid, author, avatarUrl, time, contentHtml, isMine = isMine)
        fun nextCooldownTickDelayMs(untilMs: Long, nowMs: Long): Long =
            (untilMs - nowMs).let { raw -> if (raw !in 1L..30_000L) 250L else (raw % 1_000L).let { if (it == 0L) 1_000L else it } }
    }
}
