package net.jejer.hipda.data.message

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthRequiredException
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.ForumHttpException
import net.jejer.hipda.data.auth.SilentReloginFailureReason
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeAbandonedException
import net.jejer.hipda.data.network.access.ChallengeIncompleteException
import net.jejer.hipda.data.network.access.ChallengeRejectedException
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.message.NotifyParser
import net.jejer.hipda.parser.message.SmsParser

@Singleton
class DefaultNotifyRepository internal constructor(
    private val forumHttpClient: ForumHttpClient,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val readStateStore: NotifyReadStateStore,
    private val nowMs: () -> Long,
    private val scope: CoroutineScope,
) : NotifyRepository {

    @Inject
    constructor(
        forumHttpClient: ForumHttpClient,
        settingsRepository: SettingsRepository,
        authRepository: AuthRepository,
        readStateStore: NotifyReadStateStore,
    ) : this(
        forumHttpClient = forumHttpClient,
        settingsRepository = settingsRepository,
        authRepository = authRepository,
        readStateStore = readStateStore,
        nowMs = System::currentTimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private data class InternalState(
        val activeOwnerUid: String? = null,
        val hydratedOwnerUid: String? = null,
        val hasSignedOut: Boolean = false,
        val accountGeneration: Long = 0L,
        val readKeys: Set<String> = emptySet(),
        val localUnreadKeys: Set<String> = emptySet(),
        val loadedEntries: List<NotifyEntry> = emptyList(),
        val lastServerNotifyCount: Int = 0,
        val suppressedServerNotifyCount: Int = 0,
        val unreadSmsUids: Set<String> = emptySet(),
        val smsUnreadCount: Int = 0,
    ) {
        val effectiveServerNotifyCount: Int
            get() = (lastServerNotifyCount - suppressedServerNotifyCount).coerceAtLeast(0)

        val notifyUnreadCount: Int
            get() = maxOf(localUnreadKeys.size, effectiveServerNotifyCount)
    }

    private val stateLock = Any()
    private val storeMutex = Mutex()
    private var state: InternalState = InternalState(
        activeOwnerUid = authRepository.currentAccount.value?.uid,
    )

    private val _smsUnreadCount = MutableStateFlow(0)
    override val smsUnreadCount: StateFlow<Int> = _smsUnreadCount.asStateFlow()

    private val _notifyUnreadCount = MutableStateFlow(0)
    override val notifyUnreadCount: StateFlow<Int> = _notifyUnreadCount.asStateFlow()

    private val _notifications = MutableStateFlow<List<NotifyEntry>>(emptyList())
    override val notifications: StateFlow<List<NotifyEntry>> = _notifications.asStateFlow()

    init {
        scope.launch {
            authRepository.currentAccount
                .map { it?.uid }
                .distinctUntilChanged()
                .collect { newUid ->
                    onAccountObserved(newUid)
                }
        }
    }

    override suspend fun getNotifications(
        priority: AccessPriority,
    ): MessageResult<NotifyFeed> {
        val (startUid, startGen) = ensureAccountSynchronized()
        val avatarBaseUrl = deriveAvatarBaseUrl(settingsRepository.settings.first().forumServer)
        val response = try {
            forumHttpClient.getHtml(
                relativePath = NOTIFY_PATH,
                priority = priority,
                requireAuth = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return mapExceptionToMessageResult(e)
        }

        val page = NotifyParser.parseNotifyList(response.html, avatarBaseUrl = avatarBaseUrl)
        if (!page.hasFeedContainer && !page.meta.errorMessage.isNullOrBlank()) {
            return MessageResult.Error(page.meta.errorMessage!!)
        }

        val (currentUid, currentGen) = ensureAccountSynchronized()
        if (!isInFlightResponseValid(startUid, startGen, currentUid, currentGen, page.meta.session.uid)) {
            return MessageResult.AuthRequired(null)
        }

        val keyedItems = page.items.map { item -> NotifyEntry.computeKey(item) to item }
        val effectiveOwnerUid = currentUid ?: page.meta.session.uid

        return storeMutex.withLock {
            syncAccountChangeIfNeeded()
            if (!isGenerationCurrent(currentGen)) {
                return@withLock MessageResult.AuthRequired(null)
            }

            val persisted = if (!effectiveOwnerUid.isNullOrBlank()) {
                readStateStore.syncLoadedFeed(effectiveOwnerUid, keyedItems.map { it.first })
            } else {
                AccountNotifyReadState(
                    readKeys = emptyList(),
                    unreadKeys = keyedItems.map { it.first }.distinct(),
                )
            }
            val readSet = persisted.readKeys.toSet()
            val entries = keyedItems.map { (key, item) ->
                NotifyEntry(
                    key = key,
                    item = item,
                    isRead = key in readSet,
                )
            }

            val committed = synchronized(stateLock) {
                if (state.accountGeneration != currentGen) {
                    false
                } else {
                    updateStateLocked(
                        state.copy(
                            activeOwnerUid = state.activeOwnerUid ?: effectiveOwnerUid,
                            hydratedOwnerUid = effectiveOwnerUid ?: state.hydratedOwnerUid,
                            readKeys = readSet,
                            localUnreadKeys = persisted.unreadKeys.toSet(),
                            loadedEntries = entries,
                            lastServerNotifyCount = 0,
                            suppressedServerNotifyCount = 0,
                        ),
                    )
                    true
                }
            }
            if (!committed) {
                MessageResult.AuthRequired(null)
            } else {
                MessageResult.Success(
                    NotifyFeed(
                        entries = entries,
                        meta = page.meta,
                        hasFeedContainer = page.hasFeedContainer,
                    ),
                )
            }
        }
    }

    override suspend fun markNotificationRead(key: String) {
        val trimmedKey = key.trim()
        if (trimmedKey.isEmpty()) return

        val (ownerUid, gen) = ensureAccountSynchronized()
        storeMutex.withLock {
            syncAccountChangeIfNeeded()
            if (!isGenerationCurrent(gen)) return@withLock

            val effectiveUid = ownerUid ?: synchronized(stateLock) { state.activeOwnerUid }
            val persisted = if (!effectiveUid.isNullOrBlank()) {
                readStateStore.markRead(effectiveUid, trimmedKey)
            } else {
                null
            }

            synchronized(stateLock) {
                if (state.accountGeneration != gen) return@synchronized
                val wasUnread = trimmedKey in state.localUnreadKeys ||
                    (trimmedKey !in state.readKeys && state.loadedEntries.any { it.key == trimmedKey && !it.isRead })
                val newReadKeys = persisted?.readKeys?.toSet() ?: (state.readKeys + trimmedKey)
                val newUnreadKeys = persisted?.unreadKeys?.toSet() ?: (state.localUnreadKeys - trimmedKey)
                val newEntries = state.loadedEntries.map { entry ->
                    if (entry.key == trimmedKey && !entry.isRead) entry.copy(isRead = true) else entry
                }
                val newServerCount = if (wasUnread && state.lastServerNotifyCount > 0) {
                    (state.lastServerNotifyCount - 1).coerceAtLeast(0)
                } else {
                    state.lastServerNotifyCount
                }
                updateStateLocked(
                    state.copy(
                        readKeys = newReadKeys,
                        localUnreadKeys = newUnreadKeys,
                        loadedEntries = newEntries,
                        lastServerNotifyCount = newServerCount,
                    ),
                )
            }
        }
    }

    override suspend fun markAllNotificationsRead() {
        val (ownerUid, gen) = ensureAccountSynchronized()
        storeMutex.withLock {
            syncAccountChangeIfNeeded()
            val snapshot = synchronized(stateLock) {
                if (state.accountGeneration != gen) return@withLock
                state
            }
            val knownKeys = (snapshot.loadedEntries.map { it.key } + snapshot.localUnreadKeys).distinct()
            val serverCountToSuppress = snapshot.lastServerNotifyCount
            val effectiveUid = ownerUid ?: snapshot.activeOwnerUid
            val persisted = if (!effectiveUid.isNullOrBlank()) {
                readStateStore.markAllRead(
                    ownerUid = effectiveUid,
                    knownKeys = knownKeys,
                    suppressedServerCount = serverCountToSuppress,
                    timestampMs = nowMs(),
                )
            } else {
                null
            }

            synchronized(stateLock) {
                if (state.accountGeneration != gen) return@synchronized
                val newReadKeys = persisted?.readKeys?.toSet() ?: (state.readKeys + knownKeys)
                val newEntries = state.loadedEntries.map { entry ->
                    if (entry.isRead) entry else entry.copy(isRead = true)
                }
                updateStateLocked(
                    state.copy(
                        readKeys = newReadKeys,
                        localUnreadKeys = emptySet(),
                        loadedEntries = newEntries,
                        suppressedServerNotifyCount = serverCountToSuppress,
                    ),
                )
            }
        }
    }

    override suspend fun pollUnreadCounts(
        priority: AccessPriority,
    ): NotificationPollResult {
        val (startUid, startGen) = ensureAccountSynchronized()
        val response = try {
            forumHttpClient.getHtml(
                relativePath = NEW_PM_PATH,
                priority = priority,
                requireAuth = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: ChallengeAbandonedException) {
            return NotificationPollResult.ChallengeAbandoned
        } catch (e: AuthRequiredException) {
            val failedRelogin = e.reloginResult as? SilentReloginResult.Failed
            if (failedRelogin?.reason == SilentReloginFailureReason.CHALLENGE_ABANDONED) {
                return NotificationPollResult.ChallengeAbandoned
            }
            return NotificationPollResult.AuthRequired(reason = e.reason)
        } catch (e: ChallengeIncompleteException) {
            return NotificationPollResult.Failed(message = e.message ?: "安全验证未完成", cause = e)
        } catch (e: ChallengeRejectedException) {
            return NotificationPollResult.Failed(message = e.message ?: "安全验证未通过", cause = e)
        } catch (e: ForumHttpException) {
            return NotificationPollResult.Failed(message = e.message ?: "轮询提醒失败", cause = e)
        } catch (e: IOException) {
            return NotificationPollResult.Failed(message = e.message ?: "网络请求失败", cause = e)
        }

        val smsPage = SmsParser.parseSmsList(response.html)
        val unreadSmsItems = smsPage.items
        val smsCount = unreadSmsItems.size
        val unreadSmsUids = unreadSmsItems.mapNotNull { it.uid.trim().ifEmpty { null } }.toSet()
        val latestUnreadSms = unreadSmsItems.singleOrNull()
        val promptCounts = smsPage.meta.promptCounts

        val (currentUid, currentGen) = ensureAccountSynchronized()
        if (!isInFlightResponseValid(startUid, startGen, currentUid, currentGen, smsPage.meta.session.uid)) {
            return NotificationPollResult.AuthRequired(null)
        }

        val effectiveOwnerUid = currentUid ?: smsPage.meta.session.uid
        if (promptCounts.notifyTotal == 0 && !effectiveOwnerUid.isNullOrBlank()) {
            val needsClear = synchronized(stateLock) { state.suppressedServerNotifyCount > 0 }
            if (needsClear) {
                storeMutex.withLock {
                    if (isGenerationCurrent(currentGen)) {
                        readStateStore.clearSuppressedServerCount(effectiveOwnerUid)
                    }
                }
            }
        }

        val snapshot = synchronized(stateLock) {
            if (state.accountGeneration != currentGen) {
                return NotificationPollResult.AuthRequired(null)
            }
            val serverNotify = promptCounts.notifyTotal.coerceAtLeast(0)
            val nextSuppressed = if (serverNotify == 0) 0 else state.suppressedServerNotifyCount
            val nextState = state.copy(
                activeOwnerUid = state.activeOwnerUid ?: effectiveOwnerUid,
                unreadSmsUids = unreadSmsUids,
                smsUnreadCount = smsCount,
                lastServerNotifyCount = serverNotify,
                suppressedServerNotifyCount = nextSuppressed,
            )
            updateStateLocked(nextState)
            UnreadSnapshot(
                smsUnreadCount = nextState.smsUnreadCount,
                notifyUnreadCount = nextState.notifyUnreadCount,
                latestUnreadSms = latestUnreadSms,
                promptCounts = promptCounts,
            )
        }
        return NotificationPollResult.Success(snapshot)
    }

    override fun updateFromPageMeta(promptCounts: PromptCounts) {
        syncAccountChangeIfNeeded()
        synchronized(stateLock) {
            if (state.activeOwnerUid == null && state.hasSignedOut) return
            val serverNotify = promptCounts.notifyTotal.coerceAtLeast(0)
            val nextSuppressed = if (serverNotify == 0) 0 else state.suppressedServerNotifyCount
            val nextSmsCount = if (promptCounts.pm > 0) {
                maxOf(state.smsUnreadCount, promptCounts.pm)
            } else {
                state.smsUnreadCount
            }
            updateStateLocked(
                state.copy(
                    lastServerNotifyCount = serverNotify,
                    suppressedServerNotifyCount = nextSuppressed,
                    smsUnreadCount = nextSmsCount,
                ),
            )
        }
    }

    override fun updateSmsUnreadState(unreadConversationUids: Set<String>, unreadCount: Int) {
        syncAccountChangeIfNeeded()
        synchronized(stateLock) {
            if (state.activeOwnerUid == null && state.hasSignedOut) return
            val normalizedUids = unreadConversationUids
                .mapNotNull { it.trim().ifEmpty { null } }
                .toSet()
            val resolvedCount = maxOf(normalizedUids.size, unreadCount.coerceAtLeast(0))
            updateStateLocked(
                state.copy(
                    unreadSmsUids = normalizedUids,
                    smsUnreadCount = resolvedCount,
                ),
            )
        }
    }

    override fun markSmsConversationRead(uid: String, hadUnreadMessages: Boolean) {
        val trimmedUid = uid.trim()
        if (trimmedUid.isEmpty()) return
        syncAccountChangeIfNeeded()
        synchronized(stateLock) {
            val currentUids = state.unreadSmsUids
            when {
                trimmedUid in currentUids -> {
                    val remaining = currentUids - trimmedUid
                    val nextCount = if (state.smsUnreadCount > currentUids.size) {
                        (state.smsUnreadCount - 1).coerceAtLeast(remaining.size)
                    } else {
                        remaining.size
                    }
                    updateStateLocked(
                        state.copy(
                            unreadSmsUids = remaining,
                            smsUnreadCount = nextCount,
                        ),
                    )
                }

                hadUnreadMessages && state.smsUnreadCount > 0 -> {
                    updateStateLocked(
                        state.copy(smsUnreadCount = (state.smsUnreadCount - 1).coerceAtLeast(0)),
                    )
                }
            }
        }
    }

    private suspend fun onAccountObserved(newUid: String?) {
        val (targetUid, gen, needsHydration) = switchAccountIfNeededLocked(newUid)
        if (needsHydration && targetUid != null) {
            hydrateAccount(targetUid, gen)
        }
    }

    private suspend fun ensureAccountSynchronized(): Pair<String?, Long> {
        val currentUid = authRepository.currentAccount.value?.uid
        val (targetUid, gen, needsHydration) = switchAccountIfNeededLocked(currentUid)
        if (needsHydration && targetUid != null) {
            hydrateAccount(targetUid, gen)
        }
        return targetUid to gen
    }

    private fun syncAccountChangeIfNeeded() {
        val currentUid = authRepository.currentAccount.value?.uid
        switchAccountIfNeededLocked(currentUid)
    }

    /**
     * Transitions active account state:
     * - `A -> null` (logout) or `A -> B` (switch): increments generation and clears all in-memory
     *   counts and entries immediately so no data leaks across accounts or after logout.
     * - `null -> A` (cold-start hydration before `AuthRepository` finishes reading storage, or
     *   login after a logout that already cleared memory): binds `activeOwnerUid = A` without
     *   discarding cold-start state and schedules persistence hydration for `A`.
     */
    private fun switchAccountIfNeededLocked(newUid: String?): Triple<String?, Long, Boolean> =
        synchronized(stateLock) {
            val previousUid = state.activeOwnerUid
            when {
                previousUid == newUid -> Triple(
                    newUid,
                    state.accountGeneration,
                    newUid != null && state.hydratedOwnerUid != newUid,
                )

                previousUid == null && newUid != null && !state.hasSignedOut -> {
                    // Cold-start hydration before AuthRepository finishes initial credential load.
                    updateStateLocked(
                        state.copy(
                            activeOwnerUid = newUid,
                            hydratedOwnerUid = null,
                        ),
                    )
                    Triple(newUid, state.accountGeneration, true)
                }

                else -> {
                    // Explicit logout (A -> null), post-logout login (null -> B), or switch (A -> B).
                    val nextGen = state.accountGeneration + 1L
                    updateStateLocked(
                        InternalState(
                            activeOwnerUid = newUid,
                            hydratedOwnerUid = null,
                            hasSignedOut = newUid == null,
                            accountGeneration = nextGen,
                        ),
                    )
                    Triple(newUid, nextGen, newUid != null)
                }
            }
        }

    private fun isInFlightResponseValid(
        startUid: String?,
        startGen: Long,
        currentUid: String?,
        currentGen: Long,
        responseSessionUid: String?,
    ): Boolean {
        if (currentGen != startGen) return false
        if (startUid != null && currentUid != startUid) return false
        if (startUid == null && currentUid != null &&
            responseSessionUid != null && responseSessionUid != currentUid
        ) {
            return false
        }
        return true
    }

    private suspend fun hydrateAccount(ownerUid: String, expectedGen: Long) {
        storeMutex.withLock {
            val shouldLoad = synchronized(stateLock) {
                state.accountGeneration == expectedGen &&
                    state.activeOwnerUid == ownerUid &&
                    state.hydratedOwnerUid != ownerUid
            }
            if (!shouldLoad) return@withLock

            val persisted = readStateStore.getState(ownerUid)
            synchronized(stateLock) {
                if (state.accountGeneration != expectedGen || state.activeOwnerUid != ownerUid) {
                    return@synchronized
                }
                val mergedRead = state.readKeys + persisted.readKeys
                val mergedUnread = (state.localUnreadKeys + persisted.unreadKeys) - mergedRead
                val updatedEntries = if (state.loadedEntries.isEmpty()) {
                    state.loadedEntries
                } else {
                    state.loadedEntries.map { entry ->
                        if (!entry.isRead && entry.key in mergedRead) entry.copy(isRead = true) else entry
                    }
                }
                updateStateLocked(
                    state.copy(
                        hydratedOwnerUid = ownerUid,
                        readKeys = mergedRead,
                        localUnreadKeys = mergedUnread,
                        loadedEntries = updatedEntries,
                        suppressedServerNotifyCount = maxOf(
                            state.suppressedServerNotifyCount,
                            persisted.suppressedServerNotifyCount,
                        ),
                    ),
                )
            }
        }
    }

    private fun isGenerationCurrent(expectedGen: Long): Boolean = synchronized(stateLock) {
        state.accountGeneration == expectedGen
    }

    private fun updateStateLocked(next: InternalState) {
        state = next
        _smsUnreadCount.value = next.smsUnreadCount
        _notifyUnreadCount.value = next.notifyUnreadCount
        _notifications.value = next.loadedEntries
    }

    companion object {
        private const val NOTIFY_PATH = "notice.php"
        private const val NEW_PM_PATH = "pm.php?filter=newpm"
    }
}

internal fun <T> mapExceptionToMessageResult(e: Exception): MessageResult<T> = when (e) {
    is ChallengeAbandonedException -> MessageResult.ChallengeAbandoned
    is AuthRequiredException -> {
        val failedRelogin = e.reloginResult as? SilentReloginResult.Failed
        if (failedRelogin?.reason == SilentReloginFailureReason.CHALLENGE_ABANDONED) {
            MessageResult.ChallengeAbandoned
        } else {
            MessageResult.AuthRequired(reason = e.reason)
        }
    }

    is ChallengeIncompleteException -> MessageResult.ChallengeRequired(
        cooldownUntilMs = e.cooldownUntilMs,
        rejected = false,
    )

    is ChallengeRejectedException -> MessageResult.ChallengeRequired(
        cooldownUntilMs = e.cooldownUntilMs,
        rejected = true,
    )

    is ForumHttpException -> MessageResult.Error(
        message = e.message ?: "请求失败",
        cause = e,
    )

    is IOException -> MessageResult.Error(
        message = e.message ?: "网络请求失败",
        cause = e,
    )

    else -> MessageResult.Error(
        message = e.message ?: "未知错误",
        cause = e,
    )
}
