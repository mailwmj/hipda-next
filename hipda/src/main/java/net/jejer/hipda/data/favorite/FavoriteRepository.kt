package net.jejer.hipda.data.favorite

import kotlinx.coroutines.flow.StateFlow
import net.jejer.hipda.data.network.access.AccessPriority

/**
 * Repository managing Discuz! favorites ("收藏") and attention ("关注") lists, AJAX add/remove
 * mutations with Snackbar undo support, multi-page cache synchronization, and per-account local
 * thread ID caching (`E11`, thread detail favorite button).
 */
interface FavoriteRepository {

    /** Reactive set of favorited thread IDs for the currently active account (empty when signed out). */
    val favoriteTids: StateFlow<Set<String>>

    /** Reactive set of attention thread IDs for the currently active account (empty when signed out). */
    val attentionTids: StateFlow<Set<String>>

    /** Synchronous check consistent with [favoriteTids] for the active account. */
    fun isFavorite(tid: String): Boolean

    /** Synchronous check consistent with [attentionTids] for the active account. */
    fun isAttention(tid: String): Boolean

    /**
     * Loads a page of [kind] (`my.php?item={favorites|attention}&type=thread`) and merges all valid
     * returned thread IDs into the active account's local cache.
     */
    suspend fun getList(
        kind: FavoriteKind,
        page: Int = 1,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): FavoriteListResult

    /**
     * Synchronizes up to [maxPages] pages (default [DEFAULT_SYNC_MAX_PAGES] = 3) of [kind] via
     * `FavoriteParser.parseFavoriteIdsPage`. Replaces the active account's cache only when every
     * fetched page succeeds; preserves existing cache intact on any midway error.
     */
    suspend fun syncCache(
        kind: FavoriteKind,
        maxPages: Int = DEFAULT_SYNC_MAX_PAGES,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): FavoriteSyncResult

    /**
     * Adds [tid] to [kind] via `GET my.php?item={kind}&action=add&inajax=1&ajaxtarget=favorite_msg&tid={tid}`.
     * Updates [FavoriteCacheStore] and emits immediately on success or idempotent "already added"
     * responses; leaves cache untouched on network, auth, or site errors.
     */
    suspend fun add(
        kind: FavoriteKind,
        tid: String,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): FavoriteMutationResult

    /**
     * Removes [tid] from [kind] via `GET my.php?item={kind}&action=remove&inajax=1&ajaxtarget=favorite_msg&tid={tid}`.
     * Updates [FavoriteCacheStore] and emits immediately on success or idempotent "not in list"
     * responses; leaves cache untouched on network, auth, or site errors.
     */
    suspend fun remove(
        kind: FavoriteKind,
        tid: String,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): FavoriteMutationResult

    /**
     * Removes [tid] from [kind] via list-page GBK form POST (`my.php?item={kind}&type=thread`,
     * `favsubmit`/`attentionsubmit=true`, `delete[]={tid}`, `formhash={formhash}`).
     */
    suspend fun removeByForm(
        kind: FavoriteKind,
        tid: String,
        formhash: String,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): FavoriteMutationResult

    companion object {
        const val DEFAULT_SYNC_MAX_PAGES: Int = 3
    }
}
