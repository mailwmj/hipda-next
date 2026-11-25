package net.jejer.hipda.data.threadlist

import java.io.IOException
import net.jejer.hipda.data.auth.AuthRequiredException
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.ForumHttpException
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.network.access.ChallengeAbandonedException
import net.jejer.hipda.data.network.access.ChallengeIncompleteException
import net.jejer.hipda.data.network.access.ChallengeRejectedException
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.threadlist.ThreadSummary

/**
 * Immutable thread row item ready for presentation (`E1`/`E3`), carrying a stable [tid] primary key
 * guaranteed to pass `DiscuzUrlHelper.isValidId`.
 */
data class ThreadListItem(
    val summary: ThreadSummary,
    val isVisited: Boolean,
) {
    val tid: String get() = summary.tid
}

/**
 * Identifies a forum thread list view (`fid`, optional sort override, optional Discuz! `typeId`).
 *
 * When [sortByPostTime] is `null`, the repository resolves it from `AppSettings.isSortByPostTime(fid)`.
 */
data class ThreadListQuery(
    val fid: Int,
    val sortByPostTime: Boolean? = null,
    val typeId: String? = null,
)

/**
 * Explicit reason why an auto-backfill batch terminated.
 */
enum class ThreadListBackfillStopReason {
    /** Visible items collected in this batch reached `minVisibleItems`. */
    TARGET_REACHED,

    /** Reached the server's last available page (`fetchedPage >= lastPage`). */
    REACHED_LAST_PAGE,

    /** Server returned an empty page (`threads.isEmpty()` or `!hasThreadRows`). */
    EMPTY_PAGE,

    /** Hit the configured `maxAutoBackfillPages` safety limit to guarantee termination. */
    SAFETY_LIMIT_REACHED,

    /** A subsequent backfill page failed after earlier page(s) yielded visible threads. */
    PARTIAL_ERROR,
}

/**
 * Sealed outcome of loading a thread list batch ([ThreadListBatchResult] or [ThreadListError]).
 */
sealed interface ThreadListLoadResult

/**
 * Shared failure hierarchy returned by [ThreadListRepository].
 */
sealed interface ThreadListError : ThreadListLoadResult {
    data class AuthRequired(
        val reason: AuthRequiredReason,
        val reloginResult: SilentReloginResult? = null,
        val message: String = "",
    ) : ThreadListError

    data class ChallengeRequired(
        val cooldownUntilMs: Long? = null,
        val rejected: Boolean = false,
        val abandoned: Boolean = false,
    ) : ThreadListError

    data class NetworkError(
        val message: String,
        val statusCode: Int? = null,
        val cause: Throwable? = null,
    ) : ThreadListError

    data class SiteError(
        val message: String,
    ) : ThreadListError

    data class InvalidInput(
        val message: String,
    ) : ThreadListError
}

/**
 * Result of a single batch load (`loadPage` / `refresh` / `loadMore`), including auto-backfill
 * progress, deduplicated valid raw threads, filtered visible items, and page metadata.
 */
data class ThreadListBatchResult(
    val query: ThreadListQuery,
    val resolvedSortByPostTime: Boolean,
    val rawThreads: List<ThreadSummary>,
    val items: List<ThreadListItem>,
    val fetchedPage: Int,
    val lastPage: Int,
    val hasMore: Boolean,
    val pagesFetchedInBatch: Int,
    val meta: DiscuzPageMeta,
    val stopReason: ThreadListBackfillStopReason = ThreadListBackfillStopReason.TARGET_REACHED,
    val partialError: ThreadListError? = null,
) : ThreadListLoadResult

/**
 * Repository-managed reactive snapshot for a [ThreadListQuery], suitable for cross-layout
 * state preservation (`E1`/`E3` single-pane / rail / three-pane / hinge-dual-pane).
 */
data class ThreadListLoadState(
    val query: ThreadListQuery,
    val items: List<ThreadListItem> = emptyList(),
    val fetchedPage: Int = 0,
    val lastPage: Int = 0,
    val hasMore: Boolean = false,
    val pagesFetchedInBatch: Int = 0,
    val meta: DiscuzPageMeta? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: ThreadListError? = null,
    val stopReason: ThreadListBackfillStopReason? = null,
)

internal fun mapThreadListIoException(exception: IOException): ThreadListError = when (exception) {
    is AuthRequiredException -> ThreadListError.AuthRequired(
        reason = exception.reason,
        reloginResult = exception.reloginResult,
        message = exception.message.orEmpty(),
    )

    is ChallengeIncompleteException -> ThreadListError.ChallengeRequired(
        cooldownUntilMs = exception.cooldownUntilMs,
        rejected = false,
        abandoned = false,
    )

    is ChallengeRejectedException -> ThreadListError.ChallengeRequired(
        cooldownUntilMs = exception.cooldownUntilMs,
        rejected = true,
        abandoned = false,
    )

    is ChallengeAbandonedException -> ThreadListError.ChallengeRequired(
        cooldownUntilMs = null,
        rejected = false,
        abandoned = true,
    )

    is ForumHttpException -> ThreadListError.NetworkError(
        message = exception.message ?: "HTTP ${exception.statusCode}",
        statusCode = exception.statusCode,
        cause = exception,
    )

    else -> ThreadListError.NetworkError(
        message = exception.message ?: "网络连接失败",
        statusCode = null,
        cause = exception,
    )
}
