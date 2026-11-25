package net.jejer.hipda.data.user

import net.jejer.hipda.data.network.access.AccessPriority

/**
 * Loads user profile details and paginated "TA 的帖子" listings over [net.jejer.hipda.data.auth.ForumHttpClient].
 */
interface UserRepository {

    /**
     * Fetches and parses the profile page (`space.php?uid={uid}`).
     */
    suspend fun getUserProfile(
        uid: String,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): UserProfileResult

    /**
     * Fetches a page of threads authored by [uid].
     *
     * When [searchId] is null/blank, queries `search.php?srchfid=all&srchfrom=0&searchsubmit=yes&srchuid={uid}`;
     * when [searchId] is provided, queries `search.php?searchid={searchId}&orderby=lastpost&ascdesc=desc&searchsubmit=yes`.
     * Appends `&page={page}` only when `page > 1`.
     *
     * Returns [UserThreadsResult.Success] with empty items (not an error) when the user has not
     * published any threads.
     */
    suspend fun getUserThreads(
        uid: String,
        page: Int = 1,
        searchId: String? = null,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): UserThreadsResult
}
