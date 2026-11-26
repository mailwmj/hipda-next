package net.jejer.hipda.feature.messages

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.*
import net.jejer.hipda.data.message.*
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.parser.message.SmsConversationItem
import net.jejer.hipda.ui.challenge.ChallengeFailureKind
import net.jejer.hipda.ui.shell.ShellSidebarEntry

@Immutable
data class NewSmsInlineError(
    val messageRes: Int, val serverMessage: String? = null, val cooldownSeconds: Int? = null,
    val requiresLogin: Boolean = false, val requiresReverify: Boolean = false,
)

@Immutable
data class NewSmsComposeUiState(
    val isVisible: Boolean = false, val recipient: String = "", val body: String = "",
    val recipientErrorRes: Int? = null, val bodyErrorRes: Int? = null,
    val isSending: Boolean = false, val cooldownSeconds: Int = 0, val sendError: NewSmsInlineError? = null,
)

@Immutable
data class SmsTabUiState(
    val items: List<SmsConversationItem> = emptyList(),
    val hasLoadedOnce: Boolean = false, val isLoading: Boolean = false, val isRefreshing: Boolean = false,
    val clearingUid: String? = null, val error: UiError? = null, val challengeFailure: ChallengeFailureKind? = null,
)

@Immutable
data class NotifyTabUiState(
    val entries: List<NotifyEntry> = emptyList(),
    val hasLoadedOnce: Boolean = false, val isLoading: Boolean = false, val isRefreshing: Boolean = false,
    val isMarkingAllRead: Boolean = false, val error: UiError? = null, val challengeFailure: ChallengeFailureKind? = null,
)

@Immutable
data class MessagesListUiState(
    val isLoggedIn: Boolean = false, val smsUnreadCount: Int = 0, val notifyUnreadCount: Int = 0,
    val sms: SmsTabUiState = SmsTabUiState(), val notify: NotifyTabUiState = NotifyTabUiState(),
    val challengeState: ChallengeState = ChallengeState.Idle,
    val compose: NewSmsComposeUiState = NewSmsComposeUiState(),
)

@Immutable
sealed interface MessagesListEvent {
    data object AllNotificationsMarkedRead : MessagesListEvent
    data object RequireLogin : MessagesListEvent
    @Immutable data class ShowError(val error: UiError) : MessagesListEvent
}

/** 消息页（短消息会话列表 + 提醒列表）ViewModel：管理双标签、未读角标、新建短消息、清空会话、提醒按单条已读与独立全部已读。 */
@HiltViewModel
class MessagesListViewModel internal constructor(
    private val smsRepository: SmsRepository,
    private val notifyRepository: NotifyRepository,
    private val authRepository: AuthRepository,
    private val siteAccessGateway: SiteAccessGateway,
    private val clockMs: () -> Long,
) : ViewModel() {

    @Inject
    constructor(smsRepository: SmsRepository, notifyRepository: NotifyRepository, authRepository: AuthRepository, siteAccessGateway: SiteAccessGateway) :
        this(smsRepository, notifyRepository, authRepository, siteAccessGateway, System::currentTimeMillis)

    private var authGeneration = 0
    private var activeTab: ShellSidebarEntry = ShellSidebarEntry.Sms
    private var smsLoadJob: Job? = null
    private var notifyLoadJob: Job? = null
    private var composeSendJob: Job? = null
    private var smsRequestedInGen = false
    private var notifyRequestedInGen = false

    private val isSignedIn: Boolean get() = authRepository.currentAccount.value != null
    private val smsState = MutableStateFlow(if (isSignedIn) SmsTabUiState() else signedOutSms())
    private val notifyState = MutableStateFlow(if (isSignedIn) NotifyTabUiState() else signedOutNotify())
    private val composeState = MutableStateFlow(NewSmsComposeUiState(cooldownSeconds = smsRepository.getRemainingCooldownSeconds().coerceAtLeast(0)))
    private val _events = MutableSharedFlow<MessagesListEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<MessagesListEvent> = _events.asSharedFlow()

    val uiState: StateFlow<MessagesListUiState> = combine(
        authRepository.currentAccount, notifyRepository.smsUnreadCount, notifyRepository.notifyUnreadCount,
        notifyRepository.notifications, smsState, notifyState, siteAccessGateway.challengeState, composeState,
    ) { v ->
        val account = v[0] as? SavedAccount
        @Suppress("UNCHECKED_CAST")
        val repoEntries = v[3] as List<NotifyEntry>
        val notify = v[5] as NotifyTabUiState
        val visible = if (account == null || notify.error?.action == UiErrorAction.Login) emptyList() else repoEntries
        MessagesListUiState(account != null, v[1] as Int, v[2] as Int, v[4] as SmsTabUiState, notify.copy(entries = visible), v[6] as ChallengeState, v[7] as NewSmsComposeUiState)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000L),
        MessagesListUiState(isSignedIn, notifyRepository.smsUnreadCount.value, notifyRepository.notifyUnreadCount.value, smsState.value, notifyState.value, siteAccessGateway.challengeState.value, composeState.value),
    )

    init {
        viewModelScope.launch {
            smsRepository.cooldownUntilMs.collectLatest { untilMs ->
                if (untilMs <= 0L) { composeState.update { it.copy(cooldownSeconds = 0) }; return@collectLatest }
                while (true) {
                    val remaining = smsRepository.getRemainingCooldownSeconds().coerceAtLeast(0)
                    composeState.update { it.copy(cooldownSeconds = remaining) }
                    if (remaining <= 0) break
                    delay(nextCooldownTickDelayMs(untilMs, clockMs()))
                }
            }
        }
        viewModelScope.observeMessageAuthChanges(
            authRepository, { smsState.value.error?.action == UiErrorAction.Login || notifyState.value.error?.action == UiErrorAction.Login || composeState.value.sendError?.requiresLogin == true },
        ) { uidChanged, signedIn ->
            authGeneration++; smsLoadJob?.cancel(); notifyLoadJob?.cancel(); composeSendJob?.cancel()
            smsRequestedInGen = false; notifyRequestedInGen = false
            val cd = smsRepository.getRemainingCooldownSeconds().coerceAtLeast(0)
            if (signedIn) {
                if (uidChanged) composeState.value = NewSmsComposeUiState(cooldownSeconds = cd)
                else composeState.update { it.copy(isSending = false, cooldownSeconds = cd, sendError = if (it.sendError?.requiresLogin == true) null else it.sendError) }
                smsState.value = SmsTabUiState(); notifyState.value = NotifyTabUiState(); loadTab(activeTab, false, AccessPriority.UserBlocking)
            } else {
                composeState.value = NewSmsComposeUiState()
                applySignedOutState()
            }
        }
    }

    fun onNewSmsClick() {
        if (!isSignedIn) { _events.tryEmit(MessagesListEvent.RequireLogin); return }
        if (!composeState.value.isVisible) composeState.value = NewSmsComposeUiState(isVisible = true, cooldownSeconds = smsRepository.getRemainingCooldownSeconds().coerceAtLeast(0))
    }

    fun dismissNewSmsDialog() {
        if (!composeState.value.isSending) composeState.update { NewSmsComposeUiState(cooldownSeconds = it.cooldownSeconds) }
    }

    fun onNewSmsRecipientChange(value: String) {
        if (!composeState.value.isSending) composeState.update { it.copy(recipient = value, recipientErrorRes = null, sendError = null) }
    }

    fun onNewSmsBodyChange(value: String) {
        if (!composeState.value.isSending) composeState.update { it.copy(body = value, bodyErrorRes = null, sendError = null) }
    }

    fun sendNewSms() {
        val cur = composeState.value
        if (!cur.isVisible || cur.isSending) return
        if (!isSignedIn) { composeState.value = NewSmsComposeUiState(); _events.tryEmit(MessagesListEvent.RequireLogin); return }
        val recipient = cur.recipient.trim()
        val body = cur.body.trim()
        val recipientErr = if (recipient.isEmpty()) R.string.messages_new_sms_recipient_empty else null
        val bodyErr = if (body.isEmpty()) R.string.messages_new_sms_content_empty else null
        if (recipientErr != null || bodyErr != null) { composeState.update { it.copy(recipientErrorRes = recipientErr, bodyErrorRes = bodyErr, sendError = null) }; return }
        val activeCooldown = maxOf(cur.cooldownSeconds, smsRepository.getRemainingCooldownSeconds().coerceAtLeast(0))
        if (activeCooldown > 0) { composeState.update { it.copy(cooldownSeconds = activeCooldown, sendError = NewSmsInlineError(R.string.messages_error_cooldown, cooldownSeconds = activeCooldown)) }; return }
        val gen = authGeneration
        val priority = if (cur.sendError?.requiresReverify == true) AccessPriority.UserExplicit else AccessPriority.UserBlocking
        composeState.update { it.copy(isSending = true, recipientErrorRes = null, bodyErrorRes = null, sendError = null) }
        composeSendJob = viewModelScope.launch {
            val outcome = smsRepository.sendSms(uid = null, username = recipient, message = body, priority = priority)
            if (authGeneration != gen) return@launch
            when (outcome) {
                SmsSendOutcome.Sent -> {
                    composeState.value = NewSmsComposeUiState(cooldownSeconds = smsRepository.getRemainingCooldownSeconds().coerceAtLeast(0))
                    loadTab(ShellSidebarEntry.Sms, isRefresh = smsState.value.items.isNotEmpty(), priority = AccessPriority.UserBlocking)
                }
                is SmsSendOutcome.CooldownActive -> {
                    val secs = outcome.remainingSeconds.coerceAtLeast(1)
                    composeState.update { it.copy(isSending = false, cooldownSeconds = secs, sendError = NewSmsInlineError(R.string.messages_error_cooldown, cooldownSeconds = secs)) }
                }
                else -> composeState.update { it.copy(isSending = false, sendError = mapNewSmsSendFailure(outcome)) }
            }
        }
    }

    fun bindEntry(entry: ShellSidebarEntry) {
        activeTab = normalizeMessageEntry(entry)
        if (!isSignedIn) { applySignedOutState(); return }
        if (if (activeTab == ShellSidebarEntry.Notify) !notifyRequestedInGen else !smsRequestedInGen) loadTab(activeTab, false, AccessPriority.UserBlocking)
    }

    fun refresh(entry: ShellSidebarEntry, priority: AccessPriority = AccessPriority.UserBlocking, forceFullLoad: Boolean = false) {
        activeTab = normalizeMessageEntry(entry)
        if (!isSignedIn) { applySignedOutState(); return }
        val hasItems = !forceFullLoad && if (activeTab == ShellSidebarEntry.Notify) uiState.value.notify.entries.isNotEmpty() else smsState.value.items.isNotEmpty()
        loadTab(activeTab, hasItems, priority)
    }

    fun markConversationLocallyRead(uid: String) {
        val trimmed = uid.trim().ifEmpty { return }
        smsState.update { cur -> cur.copy(items = cur.items.map { if (it.uid == trimmed && it.isNew) it.copy(isNew = false) else it }) }
    }

    fun clearConversation(uid: String) {
        val trimmed = uid.trim()
        if (trimmed.isEmpty() || smsState.value.clearingUid != null) return
        val gen = authGeneration
        smsState.update { it.copy(clearingUid = trimmed) }
        viewModelScope.launch {
            val outcome = smsRepository.clearConversation(trimmed, AccessPriority.UserBlocking)
            if (authGeneration != gen) return@launch
            if (outcome == SmsClearOutcome.Cleared) smsState.update { it.copy(items = it.items.filterNot { item -> item.uid == trimmed }, clearingUid = null) }
            else { smsState.update { it.copy(clearingUid = null) }; mapClearFailure(outcome)?.let { _events.tryEmit(MessagesListEvent.ShowError(it)) } }
        }
    }

    fun onNotificationOpened(entry: NotifyEntry) {
        val gen = authGeneration
        viewModelScope.launch { if (authGeneration == gen) notifyRepository.markNotificationRead(entry.key) }
    }

    fun markAllNotificationsRead() {
        if (notifyState.value.isMarkingAllRead || !isSignedIn) return
        val gen = authGeneration
        notifyState.update { it.copy(isMarkingAllRead = true) }
        viewModelScope.launch {
            notifyRepository.markAllNotificationsRead()
            if (authGeneration != gen) return@launch
            notifyState.update { it.copy(isMarkingAllRead = false) }
            _events.tryEmit(MessagesListEvent.AllNotificationsMarkedRead)
        }
    }

    private fun loadTab(entry: ShellSidebarEntry, isRefresh: Boolean, priority: AccessPriority) {
        if (entry == ShellSidebarEntry.Sms) {
            smsRequestedInGen = true; smsLoadJob?.cancel()
            val had = smsState.value.items.isNotEmpty()
            smsState.update { it.copy(isLoading = !isRefresh && !had, isRefreshing = isRefresh && had, error = if (had) it.error else null, challengeFailure = if (had) it.challengeFailure else null) }
            smsLoadJob = launchTabFetch({ smsRepository.getConversations(false, priority) }, { smsState.value.items.isNotEmpty() },
                { page -> smsState.update { it.copy(items = page.items, hasLoadedOnce = true, isLoading = false, isRefreshing = false, error = null, challengeFailure = null) } },
                { err, ch, ex, auth -> smsState.update { it.copy(hasLoadedOnce = true, isLoading = false, isRefreshing = false, error = if (!ex || auth) err else null, challengeFailure = if (!ex) ch else null, items = if (auth) emptyList() else it.items) } },
            )
        } else {
            notifyRequestedInGen = true; notifyLoadJob?.cancel()
            val had = uiState.value.notify.entries.isNotEmpty()
            notifyState.update { it.copy(isLoading = !isRefresh && !had, isRefreshing = isRefresh && had, error = if (had) it.error else null, challengeFailure = if (had) it.challengeFailure else null) }
            notifyLoadJob = launchTabFetch({ notifyRepository.getNotifications(priority) }, { uiState.value.notify.entries.isNotEmpty() },
                { notifyState.update { it.copy(hasLoadedOnce = true, isLoading = false, isRefreshing = false, error = null, challengeFailure = null) } },
                { err, ch, ex, auth -> notifyState.update { it.copy(hasLoadedOnce = true, isLoading = false, isRefreshing = false, error = if (!ex || auth) err else null, challengeFailure = if (!ex) ch else null) } },
            )
        }
    }

    private fun <T> launchTabFetch(
        fetch: suspend () -> MessageResult<T>, hasItems: () -> Boolean,
        onSuccess: (T) -> Unit, onFailure: (UiError, ChallengeFailureKind?, Boolean, Boolean) -> Unit,
    ): Job {
        val gen = authGeneration
        return viewModelScope.launch {
            val res = fetch()
            if (authGeneration != gen) return@launch
            if (res is MessageResult.Success) onSuccess(res.data)
            else {
                val (uiError, challenge) = mapMessageResultFailure(res) ?: return@launch
                val existing = hasItems()
                val isAuth = res is MessageResult.AuthRequired
                onFailure(uiError, challenge, existing, isAuth)
                if (existing && !isAuth) _events.tryEmit(MessagesListEvent.ShowError(uiError))
            }
        }
    }

    private fun applySignedOutState() { smsState.value = signedOutSms(); notifyState.value = signedOutNotify() }
    private fun signedOutSms() = SmsTabUiState(hasLoadedOnce = true, error = UiErrorMapper.mapAuthRequired(AuthRequiredReason.NOT_LOGGED_IN))
    private fun signedOutNotify() = NotifyTabUiState(hasLoadedOnce = true, error = UiErrorMapper.mapAuthRequired(AuthRequiredReason.NOT_LOGGED_IN))

    private companion object {
        fun nextCooldownTickDelayMs(untilMs: Long, nowMs: Long): Long =
            (untilMs - nowMs).let { raw -> if (raw !in 1L..30_000L) 250L else (raw % 1_000L).let { if (it == 0L) 1_000L else it } }

        fun mapNewSmsSendFailure(outcome: SmsSendOutcome): NewSmsInlineError = when (outcome) {
            is SmsSendOutcome.Rejected -> NewSmsInlineError(R.string.messages_status_send_failed, serverMessage = outcome.message.trim().ifEmpty { null })
            is SmsSendOutcome.AuthRequired -> NewSmsInlineError(UiErrorMapper.mapAuthRequired(outcome.reason ?: AuthRequiredReason.RELOGIN_FAILED).messageRes, requiresLogin = true)
            is SmsSendOutcome.ChallengeRequired -> NewSmsInlineError(if (outcome.rejected) R.string.challenge_status_rejected else R.string.challenge_status_incomplete, requiresReverify = true)
            is SmsSendOutcome.Error -> when (outcome.cause) {
                is ChallengeAbandonedException -> NewSmsInlineError(R.string.challenge_status_incomplete, requiresReverify = true)
                is IOException -> NewSmsInlineError(R.string.common_error_network)
                else -> NewSmsInlineError(R.string.messages_status_send_failed)
            }
            SmsSendOutcome.FormhashMissing -> NewSmsInlineError(R.string.messages_status_send_failed)
            is SmsSendOutcome.CooldownActive -> NewSmsInlineError(R.string.messages_error_cooldown, cooldownSeconds = outcome.remainingSeconds.coerceAtLeast(1))
            SmsSendOutcome.Sent -> NewSmsInlineError(R.string.messages_status_send_failed)
        }
    }
}

internal fun normalizeMessageEntry(entry: ShellSidebarEntry): ShellSidebarEntry =
    if (entry == ShellSidebarEntry.Notify) ShellSidebarEntry.Notify else ShellSidebarEntry.Sms
