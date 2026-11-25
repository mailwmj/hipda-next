package net.jejer.hipda.data.search

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
import net.jejer.hipda.parser.simplelist.MyRepliesPage
import net.jejer.hipda.parser.simplelist.MyReplyItem
import net.jejer.hipda.parser.simplelist.MyThreadItem
import net.jejer.hipda.parser.simplelist.MyThreadsPage

/**
 * Captures the removed query and its original list position so Snackbar Undo can restore it in place.
 */
data class RemovedSearchHistoryEntry(
    val query: String,
    val index: Int,
)

/**
 * Shared failure hierarchy returned by [SearchRepository] operations.
 */
sealed interface SearchError :
    SearchResult,
    MyThreadsResult,
    MyRepliesResult {

    data class AuthRequired(
        val reason: AuthRequiredReason,
        val reloginResult: SilentReloginResult? = null,
        val message: String = "",
    ) : SearchError

    data class ChallengeRequired(
        val cooldownUntilMs: Long? = null,
        val rejected: Boolean = false,
        val abandoned: Boolean = false,
    ) : SearchError

    data class NetworkError(
        val message: String,
        val statusCode: Int? = null,
        val cause: Throwable? = null,
    ) : SearchError

    data class SiteError(
        val message: String,
    ) : SearchError

    data class InvalidPage(
        val message: String,
    ) : SearchError

    data class InvalidInput(
        val message: String,
    ) : SearchError
}

/** Result of title/full-text search (`search.php`) or today's new posts (`search.php?srchfrom=86400`). */
sealed interface SearchResult {
    data class Success(
        val page: Int,
        val fullText: Boolean,
        val searchPage: SearchPage,
    ) : SearchResult {
        val items: List<SearchItem> get() = searchPage.items
        val searchId: String? get() = searchPage.searchId
        val maxPage: Int get() = searchPage.maxPage
        val hasNextPage: Boolean get() = page < searchPage.maxPage && searchPage.items.isNotEmpty()
    }
}

/** Result of loading "我的帖子" (`my.php?item=threads`). */
sealed interface MyThreadsResult {
    data class Success(
        val page: Int,
        val threadsPage: MyThreadsPage,
    ) : MyThreadsResult {
        val items: List<MyThreadItem> get() = threadsPage.items
        val maxPage: Int get() = threadsPage.maxPage
        val hasNextPage: Boolean get() = page < threadsPage.maxPage && threadsPage.items.isNotEmpty()
    }
}

/** Result of loading "我的回复" (`my.php?item=posts`). */
sealed interface MyRepliesResult {
    data class Success(
        val page: Int,
        val repliesPage: MyRepliesPage,
    ) : MyRepliesResult {
        val items: List<MyReplyItem> get() = repliesPage.items
        val maxPage: Int get() = repliesPage.maxPage
        val hasNextPage: Boolean get() = page < repliesPage.maxPage && repliesPage.items.isNotEmpty()
    }
}

internal fun mapSearchIoException(exception: IOException): SearchError = when (exception) {
    is AuthRequiredException -> SearchError.AuthRequired(
        reason = exception.reason,
        reloginResult = exception.reloginResult,
        message = exception.message.orEmpty(),
    )

    is ChallengeIncompleteException -> SearchError.ChallengeRequired(
        cooldownUntilMs = exception.cooldownUntilMs,
        rejected = false,
        abandoned = false,
    )

    is ChallengeRejectedException -> SearchError.ChallengeRequired(
        cooldownUntilMs = exception.cooldownUntilMs,
        rejected = true,
        abandoned = false,
    )

    is ChallengeAbandonedException -> SearchError.ChallengeRequired(
        cooldownUntilMs = null,
        rejected = false,
        abandoned = true,
    )

    is ForumHttpException -> SearchError.NetworkError(
        message = exception.message ?: "HTTP ${exception.statusCode}",
        statusCode = exception.statusCode,
        cause = exception,
    )

    else -> SearchError.NetworkError(
        message = exception.message ?: "网络连接失败",
        statusCode = null,
        cause = exception,
    )
}
