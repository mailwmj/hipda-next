package net.jejer.hipda.data.user

import kotlinx.coroutines.flow.StateFlow
import net.jejer.hipda.data.network.access.AccessPriority

/**
 * Single source of truth for the active account's blocked usernames (`D4`/`D5`/`E12`/`E13`),
 * backed by server endpoints (`pm.php?action=viewblack|addblack|delblack`) and a local
 * non-encrypted Preferences DataStore cache (`blacklist_cache`).
 */
interface BlacklistRepository {

    /**
     * Reactive set of normalized blocked usernames for the currently active account.
     * Emits an empty set when signed out and isolates data per account UID.
     */
    val blockedUsernames: StateFlow<Set<String>>

    /**
     * Epoch millisecond timestamp of the most recent confirmed server sync for the active account,
     * or `null` if never synced or signed out.
     */
    val lastSyncedAtMs: StateFlow<Long?>

    /**
     * Synchronous read-only check consistent with [blockedUsernames] for the active account.
     */
    fun isBlocked(username: String): Boolean

    /**
     * Fetches `pm.php?action=viewblack` and replaces the active account's local cache only when
     * parsing succeeds cleanly without page/site errors. Preserves existing cache on any failure.
     */
    suspend fun syncBlacklist(
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): BlacklistSyncResult

    /**
     * Adds [username] to the server blacklist (`POST pm.php?action=addblack`), auto-fetching
     * `formhash` from `pm.php?action=viewblack` when [formhash] is null/blank. Updates local cache
     * immediately on success and leaves it untouched on failure.
     */
    suspend fun addToBlacklist(
        username: String,
        formhash: String? = null,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): BlacklistMutationResult

    /**
     * Removes [username] from the server blacklist (`POST pm.php?action=delblack`), auto-fetching
     * `formhash` from `pm.php?action=viewblack` when [formhash] is null/blank. Updates local cache
     * immediately on success and leaves it untouched on failure.
     */
    suspend fun removeFromBlacklist(
        username: String,
        formhash: String? = null,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): BlacklistMutationResult
}
