package net.jejer.hipda.data.threadlist

import kotlinx.coroutines.flow.Flow
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.parser.threadlist.ThreadSummary

/**
 * Repository for forum thread lists (`forumdisplay.php`) providing:
 * - Auth-aware fetching (authenticated single-flight silent re-login vs guest public browsing);
 * - R-B7 S1 invalid `tid` filtering and cross-page deduplication;
 * - Continuous auto-backfill when sticky/blacklist filtering yields fewer than `minVisibleItems`,
 *   replacing the legacy 5-page failure limit while enforcing hard termination guards;
 * - Reactive zero-request re-filtering and visited-state updates backed by `SettingsRepository`,
 *   `BlacklistRepository`, and `BrowseHistoryDao`.
 */
interface ThreadListRepository {

    /**
     * Loads one batch starting at [startPage], automatically backfilling subsequent server pages
     * until [minVisibleItems] newly visible items are collected or a hard stop condition fires
     * (last page, empty page, or [maxAutoBackfillPages]).
     */
    suspend fun loadPage(
        query: ThreadListQuery,
        startPage: Int = 1,
        existingTids: Set<String> = emptySet(),
        minVisibleItems: Int = DEFAULT_MIN_VISIBLE_ITEMS,
        maxAutoBackfillPages: Int = DEFAULT_MAX_AUTO_BACKFILL_PAGES,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): ThreadListLoadResult

    /**
     * Refreshes [query] from page 1 and publishes the updated snapshot to [observeThreadList].
     */
    suspend fun refresh(
        query: ThreadListQuery,
        minVisibleItems: Int = DEFAULT_MIN_VISIBLE_ITEMS,
        maxAutoBackfillPages: Int = DEFAULT_MAX_AUTO_BACKFILL_PAGES,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): ThreadListLoadResult

    /**
     * Loads the next batch for [query] starting from `fetchedPage + 1` (or page 1 if not loaded),
     * deduplicating against previously fetched threads and publishing to [observeThreadList].
     */
    suspend fun loadMore(
        query: ThreadListQuery,
        minVisibleItems: Int = DEFAULT_MIN_VISIBLE_ITEMS,
        maxAutoBackfillPages: Int = DEFAULT_MAX_AUTO_BACKFILL_PAGES,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): ThreadListLoadResult

    /**
     * Observes the repository-managed [ThreadListLoadState] for [query], reactively recomputing
     * sticky filtering, blacklist filtering, and visited flags with zero extra network requests.
     */
    fun observeThreadList(query: ThreadListQuery): Flow<ThreadListLoadState>

    /**
     * Reactively filters and maps an external [rawThreads] stream using live `showStickyThreads`,
     * `BlacklistRepository.blockedUsernames`, and `BrowseHistoryDao.observeVisitedTids()` with
     * zero network requests.
     */
    fun observeFiltered(rawThreads: Flow<List<ThreadSummary>>): Flow<List<ThreadListItem>>

    companion object {
        const val DEFAULT_MIN_VISIBLE_ITEMS: Int = 10
        const val DEFAULT_MAX_AUTO_BACKFILL_PAGES: Int = 20
    }
}
