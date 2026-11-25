package net.jejer.hipda.data.user

import java.io.IOException
import net.jejer.hipda.data.auth.AuthRequiredException
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.ForumHttpException
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.network.access.ChallengeAbandonedException
import net.jejer.hipda.data.network.access.ChallengeIncompleteException
import net.jejer.hipda.data.network.access.ChallengeRejectedException
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.parser.search.SearchPage
import net.jejer.hipda.parser.user.UserProfile

/**
 * Persisted local cache snapshot of a single account's blacklist.
 */
data class BlacklistSnapshot(
    val ownerUid: String? = null,
    val usernames: Set<String> = emptySet(),
    val lastSyncedAtMs: Long? = null,
)

/**
 * Shared failure hierarchy returned by [UserRepository] and [BlacklistRepository].
 *
 * Implements all four result interfaces so callers can either handle specific failure categories
 * exhaustively or route all errors through a single shared error mapper.
 */
sealed interface UserError :
    UserProfileResult,
    UserThreadsResult,
    BlacklistSyncResult,
    BlacklistMutationResult {

    data class AuthRequired(
        val reason: AuthRequiredReason,
        val reloginResult: SilentReloginResult? = null,
        val message: String = "",
    ) : UserError

    data class ChallengeRequired(
        val cooldownUntilMs: Long? = null,
        val rejected: Boolean = false,
        val abandoned: Boolean = false,
    ) : UserError

    data class NetworkError(
        val message: String,
        val statusCode: Int? = null,
        val cause: Throwable? = null,
    ) : UserError

    data class SiteError(
        val message: String,
    ) : UserError

    data class InvalidPage(
        val message: String,
    ) : UserError

    data class InvalidInput(
        val message: String,
    ) : UserError
}

/** Result of loading a user profile via `space.php?uid={uid}`. */
sealed interface UserProfileResult {
    data class Success(
        val profile: UserProfile,
    ) : UserProfileResult
}

/** Result of loading a page of "TA 的帖子" via `search.php`. */
sealed interface UserThreadsResult {
    data class Success(
        val uid: String,
        val page: Int,
        val searchPage: SearchPage,
    ) : UserThreadsResult {
        val items: List<SearchItem> get() = searchPage.items
        val searchId: String? get() = searchPage.searchId
        val maxPage: Int get() = searchPage.maxPage
        val hasNextPage: Boolean get() = page < searchPage.maxPage && searchPage.items.isNotEmpty()
    }
}

/** Result of synchronizing the server blacklist via `pm.php?action=viewblack`. */
sealed interface BlacklistSyncResult {
    data class Success(
        val snapshot: BlacklistSnapshot,
        val isEmptyConfirmed: Boolean,
    ) : BlacklistSyncResult {
        val usernames: Set<String> get() = snapshot.usernames
        val lastSyncedAtMs: Long? get() = snapshot.lastSyncedAtMs
    }
}

/** Result of adding or removing a single username from the blacklist. */
sealed interface BlacklistMutationResult {
    data class Success(
        val username: String,
        val blockedUsernames: Set<String>,
    ) : BlacklistMutationResult
}

internal fun normalizeBlacklistUsername(raw: String?): String? =
    raw?.trim()?.takeIf { it.isNotEmpty() }

internal fun mapUserIoException(exception: IOException): UserError = when (exception) {
    is AuthRequiredException -> UserError.AuthRequired(
        reason = exception.reason,
        reloginResult = exception.reloginResult,
        message = exception.message.orEmpty(),
    )

    is ChallengeIncompleteException -> UserError.ChallengeRequired(
        cooldownUntilMs = exception.cooldownUntilMs,
        rejected = false,
        abandoned = false,
    )

    is ChallengeRejectedException -> UserError.ChallengeRequired(
        cooldownUntilMs = exception.cooldownUntilMs,
        rejected = true,
        abandoned = false,
    )

    is ChallengeAbandonedException -> UserError.ChallengeRequired(
        cooldownUntilMs = null,
        rejected = false,
        abandoned = true,
    )

    is ForumHttpException -> UserError.NetworkError(
        message = exception.message ?: "HTTP ${exception.statusCode}",
        statusCode = exception.statusCode,
        cause = exception,
    )

    else -> UserError.NetworkError(
        message = exception.message ?: "网络连接失败",
        statusCode = null,
        cause = exception,
    )
}
