package net.jejer.hipda.data.message

import kotlinx.coroutines.flow.StateFlow
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.parser.message.SmsDetailPage
import net.jejer.hipda.parser.message.SmsListPage

/**
 * Repository for private messages (conversation list, conversation detail, 15s cooldown send,
 * and conversation clearing).
 */
interface SmsRepository {

    /**
     * Epoch timestamp (ms) until which sending is locked in cooldown (`0L` when inactive).
     */
    val cooldownUntilMs: StateFlow<Long>

    /**
     * Remaining cooldown seconds rounded UP (`product-design.md §7.3`): `0ms -> 0`, `1..1000ms -> 1`.
     */
    fun getRemainingCooldownSeconds(): Int

    /**
     * Loads private message conversations (`pm.php?filter=privatepm` or `pm.php?filter=newpm`).
     */
    suspend fun getConversations(
        unreadOnly: Boolean = false,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): MessageResult<SmsListPage>

    /**
     * Loads the conversation detail with [uid] (`pm.php?daterange=5&uid={uid}`).
     */
    suspend fun getConversationDetail(
        uid: String,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): MessageResult<SmsDetailPage>

    /**
     * Sends a private message to [uid] or [username] with 15-second cooldown protection.
     */
    suspend fun sendSms(
        uid: String?,
        username: String?,
        message: String,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): SmsSendOutcome

    /**
     * Clears the conversation with [uid] (`GET pm.php?action=del&uid={uid}&filter=privatepm`).
     */
    suspend fun clearConversation(
        uid: String,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): SmsClearOutcome
}
