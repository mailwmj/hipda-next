package net.jejer.hipda.data.message

import kotlinx.coroutines.flow.StateFlow
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.parser.common.PromptCounts

/**
 * Repository for forum notifications, local per-item / mark-all read persistence, strictly
 * separated SMS vs notification unread badge streams, and `D9` background unread polling.
 */
interface NotifyRepository {

    /** Unread private message count (strictly separate from [notifyUnreadCount]). */
    val smsUnreadCount: StateFlow<Int>

    /** Unread forum notification count (strictly separate from [smsUnreadCount]). */
    val notifyUnreadCount: StateFlow<Int>

    /** Latest loaded notifications merged with persisted per-account read state. */
    val notifications: StateFlow<List<NotifyEntry>>

    /**
     * Loads `notice.php` (`requireAuth = true`) and merges with local read state without
     * automatically marking unopened notifications as read (`product-design.md §7.1`).
     */
    suspend fun getNotifications(
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): MessageResult<NotifyFeed>

    /** Persists a single notification [key] as read and updates [notifications] and [notifyUnreadCount]. */
    suspend fun markNotificationRead(key: String)

    /** Persists all currently known notifications as read and sets [notifyUnreadCount] to `0`. */
    suspend fun markAllNotificationsRead()

    /**
     * Polls `pm.php?filter=newpm` (`requireAuth = true`), forwarding [priority] verbatim for `D9`.
     */
    suspend fun pollUnreadCounts(
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): NotificationPollResult

    /**
     * Incrementally syncs header [PromptCounts] parsed by other pages without wiping unopened
     * local unread notifications.
     */
    fun updateFromPageMeta(promptCounts: PromptCounts)

    /** Syncs unread SMS conversation UIDs and count observed from `pm.php` list loads. */
    fun updateSmsUnreadState(
        unreadConversationUids: Set<String>,
        unreadCount: Int = unreadConversationUids.size,
    )

    /** Marks the SMS conversation with [uid] read locally after opening or clearing it. */
    fun markSmsConversationRead(
        uid: String,
        hadUnreadMessages: Boolean = false,
    )
}
