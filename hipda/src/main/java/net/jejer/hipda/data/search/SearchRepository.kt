package net.jejer.hipda.data.search

import kotlinx.coroutines.flow.Flow
import net.jejer.hipda.data.network.access.AccessPriority

/**
 * Repository for forum search (title & full-text), today's new posts ("今日新帖"),
 * personal threads/replies ("我的帖子" / "我的回复"), in-site link resolution, and
 * undoable recent search history (`E10` / `E11` / three-pane sidebar `今日新帖`).
 */
interface SearchRepository {

    /** Reactive list of recent search queries ordered newest-first. */
    val searchHistory: Flow<List<String>>

    /**
     * Searches forum posts by title or full text (`search.php`) with GBK URL encoding for
     * [query] and [author]. Returns an empty [SearchResult.Success] (rather than an error) when
     * Discuz! reports zero matching results, and [SearchError.SiteError] for real site errors
     * such as search cooldown rate limits.
     */
    suspend fun search(
        query: String = "",
        author: String = "",
        forumId: Int? = null,
        fullText: Boolean = false,
        page: Int = 1,
        searchId: String? = null,
        priority: AccessPriority = AccessPriority.UserBlocking,
        recordHistory: Boolean = true,
    ): SearchResult

    /**
     * Loads "今日新帖" (`search.php?srchfrom=86400&searchsubmit=yes`, or paginated via [searchId]).
     */
    suspend fun getNewPosts(
        page: Int = 1,
        searchId: String? = null,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): SearchResult

    /** Loads "我的帖子" (`my.php?item=threads&page={page}`, requires authentication). */
    suspend fun getMyThreads(
        page: Int = 1,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): MyThreadsResult

    /** Loads "我的回复" (`my.php?item=posts&page={page}`, requires authentication). */
    suspend fun getMyReplies(
        page: Int = 1,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): MyRepliesResult

    /** Explicitly records a query into [searchHistory]. */
    suspend fun recordSearchHistory(query: String): List<String>

    /** Removes [query] from [searchHistory] and returns its original index for Snackbar Undo. */
    suspend fun removeSearchHistory(query: String): RemovedSearchHistoryEntry?

    /** Restores a previously removed [query] at [index]. */
    suspend fun restoreSearchHistory(query: String, index: Int): List<String>

    /** Clears all entries from [searchHistory]. */
    suspend fun clearSearchHistory()

    /** Resolves [rawInput] as an in-site forum link against the currently configured `forumServer`. */
    suspend fun resolveForumLink(rawInput: String): ResolvedForumLink?
}
