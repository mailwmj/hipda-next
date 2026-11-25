package net.jejer.hipda.data.threaddetail

import java.io.IOException
import net.jejer.hipda.data.auth.AuthRequiredException
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.ForumHttpException
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.network.access.ChallengeAbandonedException
import net.jejer.hipda.data.network.access.ChallengeIncompleteException
import net.jejer.hipda.data.network.access.ChallengeRejectedException
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.parser.threaddetail.ThreadPoll

/**
 * Immutable floor presentation item carrying a stable [postId] key and live blacklist masking
 * state for lossless migration across single-pane, icon-rail, hinge dual-pane, and three-pane
 * layouts (`E2a`–`E2c`, `E3`).
 */
data class ThreadDetailFloorItem(
    val floor: PostFloor,
    val isAuthorBlocked: Boolean,
) {
    val postId: String get() = floor.postId
}

/**
 * Author filtering strategy for thread detail loading.
 */
sealed interface ThreadDetailFilter {
    /** Load all floors in the thread. */
    data object All : ThreadDetailFilter

    /**
     * Load only floors authored by the thread starter (OP).
     * When [authorId] is `null`, the repository resolves it from page 1 via
     * `ThreadDetailParser.parseThreadAuthorId` before fetching the filtered page.
     */
    data class AuthorOnly(
        val authorId: String? = null,
    ) : ThreadDetailFilter

    /** Load only floors authored by the user identified by [uid]. */
    data class UserOnly(
        val uid: String,
    ) : ThreadDetailFilter
}

/**
 * Shared failure hierarchy returned by [ThreadDetailRepository].
 *
 * Implements both [ThreadDetailLoadResult] and [PollVoteResult] so callers can handle failures
 * exhaustively per operation or delegate all errors through a single mapper.
 */
sealed interface ThreadDetailError :
    ThreadDetailLoadResult,
    PollVoteResult {

    data class AuthRequired(
        val reason: AuthRequiredReason,
        val reloginResult: SilentReloginResult? = null,
        val message: String = "",
    ) : ThreadDetailError

    data class ChallengeRequired(
        val cooldownUntilMs: Long? = null,
        val rejected: Boolean = false,
        val abandoned: Boolean = false,
    ) : ThreadDetailError

    data class NetworkError(
        val message: String,
        val statusCode: Int? = null,
        val cause: Throwable? = null,
    ) : ThreadDetailError

    data class SiteError(
        val message: String,
    ) : ThreadDetailError

    data class InvalidPage(
        val message: String,
    ) : ThreadDetailError

    data class InvalidInput(
        val message: String,
    ) : ThreadDetailError
}

/** Result of loading a thread detail page (paginated, last page, or post lookup). */
sealed interface ThreadDetailLoadResult {
    data class Success(
        val page: ThreadDetailPage,
        val filter: ThreadDetailFilter = ThreadDetailFilter.All,
        val threadAuthorId: String? = null,
        val forumName: String? = null,
        val targetPostId: String? = null,
    ) : ThreadDetailLoadResult {
        val tid: String? get() = page.tid
        val fid: Int get() = page.fid
        val title: String get() = page.title
        val pageNumber: Int get() = page.page
        val lastPage: Int get() = page.lastPage
        val hasNextPage: Boolean get() = page.page < page.lastPage
        val poll: ThreadPoll? get() = page.floors.firstOrNull { it.floor == 1 }?.poll
    }
}

/** Result of submitting a first-floor poll vote and refreshing page 1 with updated poll counts. */
sealed interface PollVoteResult {
    data class Success(
        val refreshedPage: ThreadDetailPage,
        val poll: ThreadPoll?,
        val message: String = "",
    ) : PollVoteResult
}

internal fun mapThreadDetailIoException(exception: IOException): ThreadDetailError = when (exception) {
    is AuthRequiredException -> ThreadDetailError.AuthRequired(
        reason = exception.reason,
        reloginResult = exception.reloginResult,
        message = exception.message.orEmpty(),
    )

    is ChallengeIncompleteException -> ThreadDetailError.ChallengeRequired(
        cooldownUntilMs = exception.cooldownUntilMs,
        rejected = false,
        abandoned = false,
    )

    is ChallengeRejectedException -> ThreadDetailError.ChallengeRequired(
        cooldownUntilMs = exception.cooldownUntilMs,
        rejected = true,
        abandoned = false,
    )

    is ChallengeAbandonedException -> ThreadDetailError.ChallengeRequired(
        cooldownUntilMs = null,
        rejected = false,
        abandoned = true,
    )

    is ForumHttpException -> ThreadDetailError.NetworkError(
        message = exception.message ?: "HTTP ${exception.statusCode}",
        statusCode = exception.statusCode,
        cause = exception,
    )

    else -> ThreadDetailError.NetworkError(
        message = exception.message ?: "网络连接失败",
        statusCode = null,
        cause = exception,
    )
}
