package net.jejer.hipda.feature.messages

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.*
import net.jejer.hipda.data.message.*
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.parser.message.SmsConversationItem
import net.jejer.hipda.ui.challenge.ChallengeFailureKind
import net.jejer.hipda.ui.shell.ShellSidebarEntry

internal const val NOTIFY_PAGE_SIZE: Int = 20

@Immutable
data class SmsTabUiState(
    val items: List<SmsConversationItem> = emptyList(),
    val hasLoadedOnce: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val clearingUid: String? = null,
    val error: UiError? = null,
    val challengeFailure: ChallengeFailureKind? = null,
)

@Immutable
data class NotifyTabUiState(
    val entries: List<NotifyEntry> = emptyList(),
    val visibleCount: Int = NOTIFY_PAGE_SIZE,
    val hasLoadedOnce: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isMarkingAllRead: Boolean = false,
    val error: UiError? = null,
    val challengeFailure: ChallengeFailureKind? = null,
) {
    val displayedEntries: List<NotifyEntry> get() = if (entries.size <= visibleCount) entries else entries.take(visibleCount)
    val hasMore: Boolean get() = visibleCount < entries.size
}

@Immutable
data class MessagesListUiState(
    val isLoggedIn: Boolean = false,
    val smsUnreadCount: Int = 0,
    val notifyUnreadCount: Int = 0,
    val sms: SmsTabUiState = SmsTabUiState(),
    val notify: NotifyTabUiState = NotifyTabUiState(),
    val challengeState: ChallengeState = ChallengeState.Idle,
)

@Immutable
sealed interface MessagesListEvent {
    data object AllNotificationsMarkedRead : MessagesListEvent
    @Immutable data class ShowError(val error: UiError) : MessagesListEvent
}

/** 消息页（短消息会话列表 + 提醒列表）ViewModel：管理双标签、未读角标、清空会话、提醒按单条已读与独立全部已读。 */
@HiltViewModel
class MessagesListViewModel @Inject constructor(
    private val smsRepository: SmsRepository,
    private val notifyRepository: NotifyRepository,
    private val authRepository: AuthRepository,
    private val siteAccessGateway: SiteAccessGateway,
) : ViewModel() {

    private var authGeneration = 0
    private var activeTab: ShellSidebarEntry = ShellSidebarEntry.Sms
    private var smsLoadJob: Job? = null
    private var notifyLoadJob: Job? = null
    private var smsRequestedInGen = false
    private var notifyRequestedInGen = false

    private val isSignedIn: Boolean get() = authRepository.currentAccount.value != null
    private val smsState = MutableStateFlow(if (isSignedIn) SmsTabUiState() else signedOutSms())
    private val notifyState = MutableStateFlow(if (isSignedIn) NotifyTabUiState() else signedOutNotify())
    private val _events = MutableSharedFlow<MessagesListEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<MessagesListEvent> = _events.asSharedFlow()

    val uiState: StateFlow<MessagesListUiState> = combine(
        authRepository.currentAccount, notifyRepository.smsUnreadCount, notifyRepository.notifyUnreadCount,
        notifyRepository.notifications, smsState, notifyState, siteAccessGateway.challengeState,
    ) { v ->
        val account = v[0] as? SavedAccount
        @Suppress("UNCHECKED_CAST")
        val repoEntries = v[3] as List<NotifyEntry>
        val notify = v[5] as NotifyTabUiState
        val merged = if (account == null) emptyList() else if (repoEntries.isNotEmpty() || notify.entries.isEmpty()) repoEntries else notify.entries
        MessagesListUiState(account != null, v[1] as Int, v[2] as Int, v[4] as SmsTabUiState, notify.copy(entries = merged), v[6] as ChallengeState)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000L),
        MessagesListUiState(isSignedIn, notifyRepository.smsUnreadCount.value, notifyRepository.notifyUnreadCount.value, smsState.value, notifyState.value, siteAccessGateway.challengeState.value),
    )

    init {
        viewModelScope.observeMessageAuthChanges(
            authRepository, { smsState.value.error?.action == UiErrorAction.Login || notifyState.value.error?.action == UiErrorAction.Login },
        ) { _, signedIn ->
            authGeneration++
            smsLoadJob?.cancel(); notifyLoadJob?.cancel()
            smsRequestedInGen = false; notifyRequestedInGen = false
            if (signedIn) { smsState.value = SmsTabUiState(); notifyState.value = NotifyTabUiState(); loadTab(activeTab, false, AccessPriority.UserBlocking) }
            else applySignedOutState()
        }
    }

    fun bindEntry(entry: ShellSidebarEntry) {
        activeTab = normalizeMessageEntry(entry)
        if (!isSignedIn) { applySignedOutState(); return }
        val needLoad = if (activeTab == ShellSidebarEntry.Notify) !notifyRequestedInGen else !smsRequestedInGen
        if (needLoad) loadTab(activeTab, false, AccessPriority.UserBlocking)
    }

    fun refresh(entry: ShellSidebarEntry, priority: AccessPriority = AccessPriority.UserBlocking) {
        activeTab = normalizeMessageEntry(entry)
        if (!isSignedIn) { applySignedOutState(); return }
        val hasItems = if (activeTab == ShellSidebarEntry.Notify) uiState.value.notify.entries.isNotEmpty() else smsState.value.items.isNotEmpty()
        loadTab(activeTab, hasItems, priority)
    }

    fun retry(entry: ShellSidebarEntry, priority: AccessPriority = AccessPriority.UserBlocking) {
        activeTab = normalizeMessageEntry(entry)
        if (!isSignedIn) { applySignedOutState(); return }
        loadTab(activeTab, false, priority)
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
            val outcome = runCatchingExceptCancel { smsRepository.clearConversation(trimmed, AccessPriority.UserBlocking) }.getOrElse { SmsClearOutcome.Error(it.message.orEmpty(), it) }
            if (authGeneration != gen) return@launch
            if (outcome == SmsClearOutcome.Cleared) smsState.update { it.copy(items = it.items.filterNot { item -> item.uid == trimmed }, clearingUid = null) }
            else { smsState.update { it.copy(clearingUid = null) }; mapClearFailure(outcome)?.let { _events.tryEmit(MessagesListEvent.ShowError(it)) } }
        }
    }

    fun onNotificationOpened(entry: NotifyEntry) {
        val gen = authGeneration
        viewModelScope.launch {
            if (authGeneration != gen) return@launch
            notifyRepository.markNotificationRead(entry.key)
            notifyState.update { cur -> cur.copy(entries = cur.entries.map { if (it.key == entry.key) it.copy(isRead = true) else it }) }
        }
    }

    fun markAllNotificationsRead() {
        if (notifyState.value.isMarkingAllRead || !isSignedIn) return
        val gen = authGeneration
        notifyState.update { it.copy(isMarkingAllRead = true) }
        viewModelScope.launch {
            runCatchingExceptCancel { notifyRepository.markAllNotificationsRead() }.fold(
                onSuccess = {
                    if (authGeneration != gen) return@launch
                    notifyState.update { cur -> cur.copy(isMarkingAllRead = false, entries = cur.entries.map { if (it.isRead) it else it.copy(isRead = true) }) }
                    _events.tryEmit(MessagesListEvent.AllNotificationsMarkedRead)
                },
                onFailure = { err ->
                    if (authGeneration != gen) return@launch
                    notifyState.update { it.copy(isMarkingAllRead = false) }
                    _events.tryEmit(MessagesListEvent.ShowError(UiErrorMapper.map(err)))
                },
            )
        }
    }

    fun loadMoreNotifications() {
        val total = uiState.value.notify.entries.size
        notifyState.update { cur -> if (cur.visibleCount >= total) cur else cur.copy(visibleCount = (cur.visibleCount + NOTIFY_PAGE_SIZE).coerceAtMost(total)) }
    }

    private fun loadTab(entry: ShellSidebarEntry, isRefresh: Boolean, priority: AccessPriority) {
        if (entry == ShellSidebarEntry.Sms) {
            smsRequestedInGen = true
            smsLoadJob?.cancel()
            val had = smsState.value.items.isNotEmpty()
            smsState.update { it.copy(isLoading = !isRefresh && !had, isRefreshing = isRefresh && had, error = if (had) it.error else null, challengeFailure = if (had) it.challengeFailure else null) }
            smsLoadJob = launchTabFetch(
                fetch = { smsRepository.getConversations(false, priority) },
                hasItems = { smsState.value.items.isNotEmpty() },
                onSuccess = { page -> smsState.update { it.copy(items = page.items, hasLoadedOnce = true, isLoading = false, isRefreshing = false, error = null, challengeFailure = null) } },
                onFailure = { mapped, existing, isAuth -> smsState.update { it.copy(hasLoadedOnce = true, isLoading = false, isRefreshing = false, error = if (!existing || isAuth) mapped.uiError else null, challengeFailure = if (!existing) mapped.challengeFailure else null, items = if (isAuth) emptyList() else it.items) } },
            )
        } else {
            notifyRequestedInGen = true
            notifyLoadJob?.cancel()
            val had = uiState.value.notify.entries.isNotEmpty()
            notifyState.update { it.copy(isLoading = !isRefresh && !had, isRefreshing = isRefresh && had, error = if (had) it.error else null, challengeFailure = if (had) it.challengeFailure else null) }
            notifyLoadJob = launchTabFetch(
                fetch = { notifyRepository.getNotifications(priority) },
                hasItems = { uiState.value.notify.entries.isNotEmpty() },
                onSuccess = { page -> notifyState.update { cur -> cur.copy(entries = page.entries, visibleCount = if (isRefresh) maxOf(cur.visibleCount, NOTIFY_PAGE_SIZE) else NOTIFY_PAGE_SIZE, hasLoadedOnce = true, isLoading = false, isRefreshing = false, error = null, challengeFailure = null) } },
                onFailure = { mapped, existing, isAuth -> notifyState.update { it.copy(hasLoadedOnce = true, isLoading = false, isRefreshing = false, error = if (!existing || isAuth) mapped.uiError else null, challengeFailure = if (!existing) mapped.challengeFailure else null, entries = if (isAuth) emptyList() else it.entries) } },
            )
        }
    }

    private fun <T> launchTabFetch(
        fetch: suspend () -> MessageResult<T>,
        hasItems: () -> Boolean,
        onSuccess: (T) -> Unit,
        onFailure: (MappedMessageFailure, existing: Boolean, isAuth: Boolean) -> Unit,
    ): Job {
        val gen = authGeneration
        return viewModelScope.launch {
            val res = runCatchingExceptCancel { fetch() }.getOrElse { MessageResult.Error(it.message.orEmpty(), it) }
            if (authGeneration != gen) return@launch
            if (res is MessageResult.Success) onSuccess(res.data)
            else {
                val mapped = mapMessageResultFailure(res) ?: return@launch
                val existing = hasItems()
                val isAuth = res is MessageResult.AuthRequired
                onFailure(mapped, existing, isAuth)
                if (existing && !isAuth) _events.tryEmit(MessagesListEvent.ShowError(mapped.uiError))
            }
        }
    }

    private fun applySignedOutState() { smsState.value = signedOutSms(); notifyState.value = signedOutNotify() }
    private fun signedOutSms() = SmsTabUiState(hasLoadedOnce = true, error = UiErrorMapper.mapAuthRequired(AuthRequiredReason.NOT_LOGGED_IN))
    private fun signedOutNotify() = NotifyTabUiState(hasLoadedOnce = true, error = UiErrorMapper.mapAuthRequired(AuthRequiredReason.NOT_LOGGED_IN))
}

internal fun normalizeMessageEntry(entry: ShellSidebarEntry): ShellSidebarEntry =
    if (entry == ShellSidebarEntry.Notify) ShellSidebarEntry.Notify else ShellSidebarEntry.Sms
