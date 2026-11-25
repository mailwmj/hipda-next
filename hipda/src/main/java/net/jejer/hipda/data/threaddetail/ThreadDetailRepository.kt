package net.jejer.hipda.data.threaddetail

import kotlinx.coroutines.flow.Flow
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.parser.threaddetail.PostFloor

/**
 * Repository for loading thread detail pages, maintaining browse history and reading positions,
 * reactively applying blacklist floor masking/filtering, and submitting first-floor poll votes.
 */
interface ThreadDetailRepository {

    /**
     * Loads a thread page via `viewthread.php?tid={tid}&page={page}[&authorid={uid}]`.
     *
     * When [filter] is [ThreadDetailFilter.AuthorOnly] with `authorId == null`, fetches page 1
     * first to discover the OP UID via `ThreadDetailParser.parseThreadAuthorId`, then requests
     * the filtered page.
     */
    suspend fun loadPage(
        tid: String,
        page: Int = 1,
        filter: ThreadDetailFilter = ThreadDetailFilter.All,
        recordHistory: Boolean = true,
        forumName: String? = null,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): ThreadDetailLoadResult

    /**
     * Loads the last page of [tid] via `redirect.php?goto=lastpost&from=fastpost&tid={tid}`.
     */
    suspend fun loadLastPage(
        tid: String,
        recordHistory: Boolean = true,
        forumName: String? = null,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): ThreadDetailLoadResult

    /**
     * Locates and loads the page containing [pid]:
     * - When [tid] is provided: `redirect.php?goto=findpost&pid={pid}&ptid={tid}`;
     * - When [tid] is `null` or blank: `gotopost.php?pid={pid}`.
     */
    suspend fun loadByPostId(
        pid: String,
        tid: String? = null,
        recordHistory: Boolean = true,
        forumName: String? = null,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): ThreadDetailLoadResult

    /** Reads the saved reading position for [tid], or `null` if none exists or [tid] is invalid. */
    suspend fun getReadingPosition(tid: String): ReadingPositionEntity?

    /** Saves the current reading position and active filter state for [tid]. */
    suspend fun saveReadingPosition(
        tid: String,
        page: Int,
        pid: String,
        offsetPx: Int,
        filterMode: ReadingFilterMode = ReadingFilterMode.NONE,
        filterUid: String? = null,
        filterUserName: String? = null,
    )

    /**
     * Reactively combines [floorsFlow] with `BlacklistRepository.blockedUsernames` to recalculate
     * [ThreadDetailFloorItem.isAuthorBlocked] immediately with zero network requests.
     */
    fun observeFloors(
        floorsFlow: Flow<List<PostFloor>>,
    ): Flow<List<ThreadDetailFloorItem>>

    /**
     * Reactively combines [floorsFlow] with `BlacklistRepository.blockedUsernames` and omits
     * floors authored by blocked users with zero network requests.
     */
    fun observeVisibleFloors(
        floorsFlow: Flow<List<PostFloor>>,
    ): Flow<List<ThreadDetailFloorItem>>

    /**
     * Synchronously maps [floors] to [ThreadDetailFloorItem] using [blockedUsernames] (or the
     * current `BlacklistRepository.blockedUsernames` snapshot when `null`).
     */
    fun mapFloorsWithBlacklist(
        floors: List<PostFloor>,
        blockedUsernames: Set<String>? = null,
    ): List<ThreadDetailFloorItem>

    /**
     * Synchronously filters out floors authored by users in [blockedUsernames] (or the current
     * `BlacklistRepository.blockedUsernames` snapshot when `null`).
     */
    fun filterBlockedFloors(
        floors: List<PostFloor>,
        blockedUsernames: Set<String>? = null,
    ): List<PostFloor>

    /**
     * Submits a first-floor poll vote (`POST misc.php?action=votepoll&...&inajax=1`) using GBK
     * form fields with deduplicated, lexicographically sorted `pollanswers[]`, then automatically
     * refreshes page 1 to return the updated `ThreadDetailPage` and `ThreadPoll`.
     */
    suspend fun submitPollVote(
        fid: Int,
        tid: String,
        formhash: String,
        optionIds: List<String>,
        maxChoices: Int? = null,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): PollVoteResult
}
