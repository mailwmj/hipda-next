package net.jejer.hipda.data.favorite

import java.io.IOException
import net.jejer.hipda.data.auth.AuthRequiredException
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.ForumHttpException
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.network.access.ChallengeAbandonedException
import net.jejer.hipda.data.network.access.ChallengeIncompleteException
import net.jejer.hipda.data.network.access.ChallengeRejectedException
import net.jejer.hipda.parser.simplelist.FavoriteThreadItem
import net.jejer.hipda.parser.simplelist.FavoritesListPage

/**
 * Distinguishes Discuz! favorites ("收藏") from attention ("关注") lists.
 */
enum class FavoriteKind(val itemParam: String, val formSubmitField: String) {
    FAVORITE(itemParam = "favorites", formSubmitField = "favsubmit"),
    ATTENTION(itemParam = "attention", formSubmitField = "attentionsubmit"),
}

/**
 * Persisted local cache snapshot of a single account's favorite and attention thread IDs.
 */
data class FavoriteCacheSnapshot(
    val ownerUid: String? = null,
    val favoriteTids: Set<String> = emptySet(),
    val attentionTids: Set<String> = emptySet(),
    val lastFavoriteSyncedAtMs: Long? = null,
    val lastAttentionSyncedAtMs: Long? = null,
) {
    fun tidsFor(kind: FavoriteKind): Set<String> = when (kind) {
        FavoriteKind.FAVORITE -> favoriteTids
        FavoriteKind.ATTENTION -> attentionTids
    }
}

/**
 * Shared failure hierarchy returned by [FavoriteRepository] operations.
 */
sealed interface FavoriteError :
    FavoriteListResult,
    FavoriteSyncResult,
    FavoriteMutationResult {

    data class AuthRequired(
        val reason: AuthRequiredReason,
        val reloginResult: SilentReloginResult? = null,
        val message: String = "",
    ) : FavoriteError

    data class ChallengeRequired(
        val cooldownUntilMs: Long? = null,
        val rejected: Boolean = false,
        val abandoned: Boolean = false,
    ) : FavoriteError

    data class NetworkError(
        val message: String,
        val statusCode: Int? = null,
        val cause: Throwable? = null,
    ) : FavoriteError

    data class SiteError(
        val message: String,
    ) : FavoriteError

    data class InvalidPage(
        val message: String,
    ) : FavoriteError

    data class InvalidInput(
        val message: String,
    ) : FavoriteError

    data class StorageError(
        val message: String,
        val cause: Throwable? = null,
    ) : FavoriteError
}

/** Result of loading a page of favorites or attention threads (`my.php?item={kind}&type=thread`). */
sealed interface FavoriteListResult {
    data class Success(
        val kind: FavoriteKind,
        val page: Int,
        val listPage: FavoritesListPage,
    ) : FavoriteListResult {
        val items: List<FavoriteThreadItem> get() = listPage.items
        val maxPage: Int get() = listPage.maxPage
        val formhash: String? get() = listPage.meta.formhash
        val hasNextPage: Boolean get() = page < listPage.maxPage && listPage.items.isNotEmpty()
    }
}

/** Result of synchronizing up to `maxPages` of server favorite/attention thread IDs into local cache. */
sealed interface FavoriteSyncResult {
    data class Success(
        val kind: FavoriteKind,
        val tids: Set<String>,
        val pagesSynced: Int,
        val lastPage: Int,
        val syncedAtMs: Long,
    ) : FavoriteSyncResult
}

/** Result of adding or removing a thread from favorites or attention. */
sealed interface FavoriteMutationResult {
    data class Success(
        val kind: FavoriteKind,
        val tid: String,
        val message: String,
        val alreadyApplied: Boolean = false,
    ) : FavoriteMutationResult
}

internal fun normalizeFavoriteTid(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.isEmpty() || !trimmed.all { it in '0'..'9' }) return null
    val numeric = trimmed.toLongOrNull() ?: return null
    return if (numeric > 0L) trimmed else null
}

internal fun mapFavoriteIoException(exception: IOException): FavoriteError = when (exception) {
    is AuthRequiredException -> FavoriteError.AuthRequired(
        reason = exception.reason,
        reloginResult = exception.reloginResult,
        message = exception.message.orEmpty(),
    )

    is ChallengeIncompleteException -> FavoriteError.ChallengeRequired(
        cooldownUntilMs = exception.cooldownUntilMs,
        rejected = false,
        abandoned = false,
    )

    is ChallengeRejectedException -> FavoriteError.ChallengeRequired(
        cooldownUntilMs = exception.cooldownUntilMs,
        rejected = true,
        abandoned = false,
    )

    is ChallengeAbandonedException -> FavoriteError.ChallengeRequired(
        cooldownUntilMs = null,
        rejected = false,
        abandoned = true,
    )

    is ForumHttpException -> FavoriteError.NetworkError(
        message = exception.message ?: "HTTP ${exception.statusCode}",
        statusCode = exception.statusCode,
        cause = exception,
    )

    else -> FavoriteError.NetworkError(
        message = exception.message ?: "网络连接失败",
        statusCode = null,
        cause = exception,
    )
}
