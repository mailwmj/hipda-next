package net.jejer.hipda.data.message

import java.security.MessageDigest
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.message.NotifyItem
import net.jejer.hipda.parser.message.SmsConversationItem

/**
 * Common outcome wrapper for message and notification load operations.
 */
sealed interface MessageResult<out T> {
    data class Success<T>(val data: T) : MessageResult<T>

    data class AuthRequired(val reason: AuthRequiredReason? = null) : MessageResult<Nothing>

    data class ChallengeRequired(
        val cooldownUntilMs: Long? = null,
        val rejected: Boolean = false,
    ) : MessageResult<Nothing>

    data object ChallengeAbandoned : MessageResult<Nothing>

    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : MessageResult<Nothing>
}

/**
 * Result of sending a private message via [SmsRepository.sendSms].
 */
sealed interface SmsSendOutcome {
    data object Sent : SmsSendOutcome

    /** Cooldown is still active; [remainingSeconds] is rounded UP (`1ms..1000ms -> 1`). */
    data class CooldownActive(val remainingSeconds: Int) : SmsSendOutcome

    /** Prefetch page (`pm.php?daterange=1&uid=...`) did not contain a form `formhash`. */
    data object FormhashMissing : SmsSendOutcome

    /** Validation error or server-side rejection returned in AJAX XML; does not start cooldown. */
    data class Rejected(val message: String) : SmsSendOutcome

    data class AuthRequired(val reason: AuthRequiredReason? = null) : SmsSendOutcome

    data class ChallengeRequired(
        val cooldownUntilMs: Long? = null,
        val rejected: Boolean = false,
    ) : SmsSendOutcome

    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : SmsSendOutcome
}

/**
 * Result of clearing a private message conversation via [SmsRepository.clearConversation].
 */
sealed interface SmsClearOutcome {
    data object Cleared : SmsClearOutcome

    data class Rejected(val message: String) : SmsClearOutcome

    data class AuthRequired(val reason: AuthRequiredReason? = null) : SmsClearOutcome

    data class ChallengeRequired(
        val cooldownUntilMs: Long? = null,
        val rejected: Boolean = false,
    ) : SmsClearOutcome

    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : SmsClearOutcome
}

/**
 * A parsed [NotifyItem] paired with its deterministic cross-refresh [key] and local [isRead] state.
 */
data class NotifyEntry(
    val key: String,
    val item: NotifyItem,
    val isRead: Boolean,
) {
    companion object {
        private val HEX_CHARS: CharArray = "0123456789abcdef".toCharArray()

        /**
         * Computes a deterministic key invariant to server-side `isNew` flips and `avatarBaseUrl`
         * changes, using length-prefixed canonical fields to prevent delimiter collisions.
         */
        fun computeKey(item: NotifyItem): String {
            val canonical = buildString {
                appendField(item.kind.name)
                appendField(item.tid.orEmpty().trim())
                appendField(item.pid.orEmpty().trim())
                appendField(item.authorUid.orEmpty().trim())
                appendField(item.author.orEmpty().trim())
                appendField(item.time.orEmpty().trim())
                appendField(item.title.trim())
                appendField(item.info.trim())
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
            val hex = CharArray(32)
            for (i in 0 until 16) {
                val b = digest[i].toInt() and 0xFF
                hex[i * 2] = HEX_CHARS[b ushr 4]
                hex[i * 2 + 1] = HEX_CHARS[b and 0x0F]
            }
            return "${item.kind.name}:${String(hex)}"
        }

        private fun StringBuilder.appendField(value: String) {
            append(value.length).append(':').append(value).append(';')
        }
    }
}

/**
 * Loaded `notice.php` feed merged with local persisted read state.
 */
data class NotifyFeed(
    val entries: List<NotifyEntry>,
    val meta: DiscuzPageMeta,
    val hasFeedContainer: Boolean,
)

/**
 * Snapshot produced by [NotifyRepository.pollUnreadCounts] for WorkManager (`D9`) and UI badges.
 *
 * [smsUnreadCount] and [notifyUnreadCount] are strictly separated per the three-pane sidebar
 * contract (`_reports/D-3pane.md` §5, `BATCH-4.md` §1.4).
 */
data class UnreadSnapshot(
    val smsUnreadCount: Int,
    val notifyUnreadCount: Int,
    val latestUnreadSms: SmsConversationItem? = null,
    val promptCounts: PromptCounts = PromptCounts(),
)

/**
 * Outcome of [NotifyRepository.pollUnreadCounts], explicitly separating background Cloudflare
 * challenge abandonment from authentication and generic failures for `D9`.
 */
sealed interface NotificationPollResult {
    data class Success(val snapshot: UnreadSnapshot) : NotificationPollResult

    data object ChallengeAbandoned : NotificationPollResult

    data class AuthRequired(val reason: AuthRequiredReason? = null) : NotificationPollResult

    data class Failed(
        val message: String,
        val cause: Throwable? = null,
    ) : NotificationPollResult
}

internal fun deriveAvatarBaseUrl(forumServer: String): String {
    val trimmed = forumServer.trim().trimEnd('/')
    val forumBase = if (trimmed.endsWith("/forum", ignoreCase = true)) {
        "$trimmed/"
    } else {
        "$trimmed/forum/"
    }
    return "${forumBase}uc_server/data/avatar/"
}
