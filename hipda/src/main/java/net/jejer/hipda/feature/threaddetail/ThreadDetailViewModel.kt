package net.jejer.hipda.feature.threaddetail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.threaddetail.*
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.feature.common.UiErrorMapper
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadPoll
import net.jejer.hipda.ui.challenge.ChallengeFailureKind

/** 帖子详情只看过滤态（支持 `只看楼主` 与 `只看此人`）。 */
@Immutable
sealed interface ThreadDetailFilterUi {
    data object All : ThreadDetailFilterUi
    @Immutable data class AuthorOnly(val authorId: String? = null) : ThreadDetailFilterUi
    @Immutable data class UserOnly(val uid: String, val username: String) : ThreadDetailFilterUi

    fun toRepositoryFilter(): ThreadDetailFilter = when (this) {
        All -> ThreadDetailFilter.All
        is AuthorOnly -> ThreadDetailFilter.AuthorOnly(authorId)
        is UserOnly -> ThreadDetailFilter.UserOnly(uid)
    }
}

@Immutable
data class PendingScrollTarget(
    val id: Long,
    val postId: String?,
    val scrollOffset: Int = 0,
    val highlightPostId: String? = null,
)

@Immutable
data class HighlightedFloor(val postId: String, val tokenId: Long)

@Immutable
data class ThreadDetailNotice(val id: Long, val error: UiError)

@Immutable
data class ThreadDetailUiState(
    val tid: String = "",
    val title: String = "",
    val threadAuthorId: String? = null,
    val threadAuthorName: String? = null,
    val page: Int = 1,
    val lastPage: Int = 1,
    val filter: ThreadDetailFilterUi = ThreadDetailFilterUi.All,
    val floors: List<ThreadDetailFloorItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val challengeFailure: ChallengeFailureKind? = null,
    val challengeState: ChallengeState = ChallengeState.Idle,
    val pendingScroll: PendingScrollTarget? = null,
    val firstLoadedPage: Int = 1,
    val lastLoadedPage: Int = 1,
    val isPrepending: Boolean = false,
    val isAppending: Boolean = false,
    val prependError: UiError? = null,
    val appendError: UiError? = null,
    val prependGeneration: Long = 0L,
    val resetEpoch: Long = 0L,
    val fid: Int = 0,
    val currentUserUid: String? = null,
    val highlightedFloor: HighlightedFloor? = null,
    val isPollSubmitting: Boolean = false,
    val pollError: UiError? = null,
    val notice: ThreadDetailNotice? = null,
) {
    val hasPreviousPage: Boolean get() = floors.isNotEmpty() && firstLoadedPage > 1
    val hasNextPage: Boolean get() = floors.isNotEmpty() && lastLoadedPage < lastPage
    val showPrependHeader: Boolean get() = floors.isNotEmpty() && (isPrepending || prependError != null)
    val isLoggedIn: Boolean get() = !currentUserUid.isNullOrBlank()
}

private data class FilterReturnAnchor(val page: Int, val postId: String?, val scrollOffset: Int)

private data class DetailMetaState(
    val tid: String = "",
    val fid: Int = 0,
    val title: String = "",
    val forumName: String? = null,
    val threadAuthorId: String? = null,
    val threadAuthorName: String? = null,
    val page: Int = 1,
    val requestedPage: Int = 1,
    val firstLoadedPage: Int = 1,
    val lastLoadedPage: Int = 1,
    val lastPage: Int = 1,
    val filter: ThreadDetailFilterUi = ThreadDetailFilterUi.All,
    val rawFloors: List<PostFloor> = emptyList(),
    val isLoading: Boolean = false,
    val isPrepending: Boolean = false,
    val isAppending: Boolean = false,
    val isAuthRequired: Boolean = false,
    val error: UiError? = null,
    val prependError: UiError? = null,
    val appendError: UiError? = null,
    val challengeFailure: ChallengeFailureKind? = null,
    val pendingScroll: PendingScrollTarget? = null,
    val prependGeneration: Long = 0L,
    val resetEpoch: Long = 0L,
    val currentUserUid: String? = null,
    val highlightedFloor: HighlightedFloor? = null,
    val isPollSubmitting: Boolean = false,
    val pollError: UiError? = null,
    val notice: ThreadDetailNotice? = null,
)

/**
 * 帖子详情 ViewModel（按 `tid` 托管保活；支持双向连续加载、单飞防重、头尾独立错误重试、
 * 本页/跨页精确跳楼与高亮、`只看楼主`/`只看此人` 过滤、1 楼投票提交刷新与登录自动重载，并保留 2 参主构造）。
 */
@HiltViewModel
class ThreadDetailViewModel(
    private val threadDetailRepository: ThreadDetailRepository,
    private val siteAccessGateway: SiteAccessGateway,
) : ViewModel() {

    private var authRepository: AuthRepository? = null

    @Inject
    constructor(
        threadDetailRepository: ThreadDetailRepository,
        siteAccessGateway: SiteAccessGateway,
        authRepository: AuthRepository,
    ) : this(threadDetailRepository, siteAccessGateway) {
        this.authRepository = authRepository
        metaState.update { it.copy(currentUserUid = resolveAuthUid(authRepository.currentAccount.value, authRepository.authState.value)) }
        observeAuthRecovery(authRepository)
    }

    private var boundTid: String? = null
    private var loadJob: Job? = null
    private var prependJob: Job? = null
    private var appendJob: Job? = null
    private var pollJob: Job? = null
    private var preFilterAnchor: FilterReturnAnchor? = null
    private var scrollTokenSeq: Long = 0L
    private var noticeSeq: Long = 0L
    private var authVersion: Long = 0L
    private var sessionGeneration: Long = 0L
    private val inFlightPages = HashSet<Pair<Long, Int>>()

    private val metaState = MutableStateFlow(DetailMetaState())
    private val observedFloors: Flow<List<ThreadDetailFloorItem>> =
        threadDetailRepository.observeFloors(metaState.map { it.rawFloors }.distinctUntilChanged())

    val uiState: StateFlow<ThreadDetailUiState> = combine(metaState, observedFloors, siteAccessGateway.challengeState) { m, floors, challenge ->
        val synced = floors.size == m.rawFloors.size &&
            floors.firstOrNull()?.postId == m.rawFloors.firstOrNull()?.postId &&
            floors.lastOrNull()?.postId == m.rawFloors.lastOrNull()?.postId
        if (!synced) return@combine null
        ThreadDetailUiState(
            tid = m.tid, title = m.title, threadAuthorId = m.threadAuthorId, threadAuthorName = m.threadAuthorName,
            page = m.page, lastPage = m.lastPage, filter = m.filter, floors = floors,
            isLoading = m.isLoading, error = m.error, challengeFailure = m.challengeFailure, challengeState = challenge,
            pendingScroll = m.pendingScroll, firstLoadedPage = m.firstLoadedPage, lastLoadedPage = m.lastLoadedPage,
            isPrepending = m.isPrepending, isAppending = m.isAppending, prependError = m.prependError,
            appendError = m.appendError, prependGeneration = m.prependGeneration, resetEpoch = m.resetEpoch,
            fid = m.fid, currentUserUid = m.currentUserUid, highlightedFloor = m.highlightedFloor,
            isPollSubmitting = m.isPollSubmitting, pollError = m.pollError, notice = m.notice,
        )
    }.filterNotNull().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ThreadDetailUiState())

    fun bindArgs(args: ThreadNavArgs) {
        if (boundTid == args.tid) return
        val isRebind = boundTid != null
        boundTid = args.tid
        preFilterAnchor = null
        cancelPagingJobs()
        pollJob?.cancel(); pollJob = null
        val nextEpoch = ++sessionGeneration
        val activeUid = authRepository?.let { resolveAuthUid(it.currentAccount.value, it.authState.value) }
        metaState.update { prev ->
            DetailMetaState(
                tid = args.tid, fid = args.fid, title = args.title, forumName = args.forumName,
                threadAuthorName = args.author?.takeIf { it.isNotBlank() },
                isLoading = true, prependGeneration = prev.prependGeneration, resetEpoch = nextEpoch,
                currentUserUid = activeUid,
            )
        }
        val initialPid = args.targetPid?.takeIf { it.isNotBlank() }
        if (initialPid != null) {
            loadByPostIdInternal(initialPid, highlight = true)
        } else {
            loadInternal(page = 1, filter = ThreadDetailFilterUi.All, priority = AccessPriority.UserBlocking, scrollToTop = isRebind)
        }
    }

    fun loadPage(page: Int, priority: AccessPriority = AccessPriority.UserBlocking) =
        loadInternal(page, metaState.value.filter, priority, scrollToTop = true)

    fun previousPage() {
        val cur = metaState.value
        if (cur.page > 1) loadInternal(cur.page - 1, cur.filter, AccessPriority.UserBlocking, scrollToTop = true)
    }

    fun nextPage() {
        val cur = metaState.value
        if (cur.page < cur.lastPage) loadInternal(cur.page + 1, cur.filter, AccessPriority.UserBlocking, scrollToTop = true)
    }

    fun retry(priority: AccessPriority = AccessPriority.UserBlocking) =
        loadInternal(metaState.value.requestedPage, metaState.value.filter, priority)

    fun loadPreviousPage(priority: AccessPriority = AccessPriority.UserBlocking) =
        launchDirectionalPage(prepend = true, isRetry = false, priority = priority)

    fun loadNextPage(priority: AccessPriority = AccessPriority.UserBlocking) =
        launchDirectionalPage(prepend = false, isRetry = false, priority = priority)

    fun retryPrepend(priority: AccessPriority = AccessPriority.UserExplicit) =
        launchDirectionalPage(prepend = true, isRetry = true, priority = priority)

    fun retryAppend(priority: AccessPriority = AccessPriority.UserExplicit) =
        launchDirectionalPage(prepend = false, isRetry = true, priority = priority)

    fun onVisiblePageChanged(visiblePage: Int) {
        metaState.update { cur ->
            val clamped = visiblePage.coerceIn(1, cur.lastPage.coerceAtLeast(1))
            if (cur.rawFloors.isEmpty() || cur.isLoading || clamped == cur.page) cur else cur.copy(page = clamped)
        }
    }

    fun toggleAuthorOnly(anchorPostId: String? = null, anchorScrollOffset: Int = 0) {
        val cur = metaState.value
        if (cur.tid.isBlank()) return
        if (cur.filter is ThreadDetailFilterUi.AuthorOnly) {
            clearFilter()
            return
        }
        savePreFilterAnchorIfAll(cur, anchorPostId, anchorScrollOffset)
        loadInternal(1, ThreadDetailFilterUi.AuthorOnly(cur.threadAuthorId), AccessPriority.UserBlocking, scrollToTop = true)
    }

    fun filterByUser(uid: String, username: String, anchorPostId: String? = null, anchorScrollOffset: Int = 0) {
        val cur = metaState.value
        val cleanUid = uid.trim()
        if (cur.tid.isBlank() || cleanUid.isBlank()) return
        savePreFilterAnchorIfAll(cur, anchorPostId, anchorScrollOffset)
        loadInternal(1, ThreadDetailFilterUi.UserOnly(cleanUid, username.ifBlank { cleanUid }), AccessPriority.UserBlocking, scrollToTop = true)
    }

    fun clearFilter() {
        val cur = metaState.value
        if (cur.tid.isBlank() || cur.filter is ThreadDetailFilterUi.All) return
        val anchor = preFilterAnchor
        preFilterAnchor = null
        loadInternal(anchor?.page ?: 1, ThreadDetailFilterUi.All, AccessPriority.UserBlocking, scrollToTop = anchor == null, restoreAnchor = anchor)
    }

    /**
     * 本页或跨页精确跳楼（闭合 `R-BATCH5 B4-E2c`）：
     * - 若 [targetPid] 已在当前已加载楼层区间中，直接发布 `PendingScrollTarget` 经单一滚动权威落位并高亮（0 网络请求）；
     * - 否则调用 `D5.loadByPostId` 加载目标页、更新连续区间，并按稳定键 `floor:$targetPid` 滚入视口后高亮。
     */
    fun jumpToFloor(targetPid: String, targetTid: String? = null) {
        val cur = metaState.value
        val cleanPid = targetPid.trim()
        if (cur.tid.isBlank() || cleanPid.isBlank()) return
        if (cur.rawFloors.any { it.postId == cleanPid }) {
            val token = ++scrollTokenSeq
            val nextEpoch = ++sessionGeneration
            metaState.update {
                it.copy(
                    resetEpoch = nextEpoch,
                    pendingScroll = PendingScrollTarget(token, cleanPid, 0, highlightPostId = cleanPid),
                    highlightedFloor = HighlightedFloor(cleanPid, token),
                )
            }
            return
        }
        val cleanTid = targetTid?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanTid != null && cleanTid != cur.tid) return
        loadByPostIdInternal(cleanPid, highlight = true)
    }

    fun consumePendingScroll(tokenId: Long) {
        metaState.update { cur ->
            val pending = cur.pendingScroll
            if (pending?.id != tokenId) {
                cur
            } else {
                cur.copy(pendingScroll = null)
            }
        }
    }

    fun onHighlightFinished(tokenId: Long) {
        metaState.update { cur ->
            if (cur.highlightedFloor?.tokenId == tokenId) cur.copy(highlightedFloor = null) else cur
        }
    }

    fun consumeNotice(noticeId: Long) {
        metaState.update { cur ->
            if (cur.notice?.id == noticeId) cur.copy(notice = null) else cur
        }
    }

    fun clearPollError() {
        metaState.update { if (it.pollError != null) it.copy(pollError = null) else it }
    }

    fun submitPollVote(poll: ThreadPoll, optionIds: List<String>) {
        val cur = metaState.value
        if (cur.tid.isBlank() || cur.isPollSubmitting || pollJob?.isActive == true) return
        if (cur.currentUserUid.isNullOrBlank()) {
            metaState.update { it.copy(pollError = UiError(R.string.thread_detail_login_to_operate, action = UiErrorAction.Login)) }
            return
        }
        val formhash = poll.formhash?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val maxChoices = poll.maxChoices.coerceAtLeast(1)
        val distinctIds = optionIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinctIds.isEmpty() || distinctIds.size > maxChoices) return

        val generation = sessionGeneration
        val fid = cur.fid
        val tid = cur.tid
        metaState.update { it.copy(isPollSubmitting = true, pollError = null) }
        pollJob = viewModelScope.launch {
            when (val res = threadDetailRepository.submitPollVote(fid, tid, formhash, distinctIds, maxChoices)) {
                is PollVoteResult.Success -> {
                    if (generation != sessionGeneration) return@launch
                    metaState.update { state ->
                        val refreshed = res.refreshedPage
                        val updatedFloors = mergeRefreshedPage1Floors(state.rawFloors, refreshed.floors, res.poll, state.filter)
                        state.copy(
                            fid = refreshed.fid.takeIf { it > 0 } ?: state.fid,
                            title = refreshed.title.ifBlank { state.title },
                            lastPage = if (state.filter is ThreadDetailFilterUi.All) {
                                maxOf(state.lastPage, refreshed.lastPage).coerceAtLeast(1)
                            } else {
                                state.lastPage
                            },
                            rawFloors = updatedFloors,
                            isPollSubmitting = false,
                            pollError = null,
                        )
                    }
                }
                is ThreadDetailError -> {
                    if (generation != sessionGeneration) return@launch
                    val mapped = UiErrorMapper.map(res)
                    val uiError = if (res is ThreadDetailError.AuthRequired) {
                        UiError(R.string.thread_detail_login_to_operate, mapped.details, UiErrorAction.Login)
                    } else {
                        mapped
                    }
                    metaState.update { it.copy(isPollSubmitting = false, pollError = uiError) }
                }
            }
        }
    }

    private fun savePreFilterAnchorIfAll(cur: DetailMetaState, anchorPostId: String?, anchorScrollOffset: Int) {
        if (cur.filter !is ThreadDetailFilterUi.All) return
        val anchorFloor = anchorPostId?.let { pid -> cur.rawFloors.firstOrNull { it.postId == pid } } ?: cur.rawFloors.firstOrNull()
        preFilterAnchor = FilterReturnAnchor(
            page = anchorFloor?.page ?: cur.page,
            postId = anchorPostId ?: anchorFloor?.postId,
            scrollOffset = anchorScrollOffset.coerceAtLeast(0),
        )
    }

    private fun mergeRefreshedPage1Floors(
        currentFloors: List<PostFloor>,
        refreshedPage1Floors: List<PostFloor>,
        refreshedPoll: ThreadPoll?,
        filter: ThreadDetailFilterUi,
    ): List<PostFloor> {
        val resolvedPoll = refreshedPoll ?: refreshedPage1Floors.firstOrNull { it.floor == 1 }?.poll
        // S1：只看楼主/只看此人激活态下投票刷新仅原地更新 1 楼的 poll，绝不混入全帖非目标作者楼层
        if (filter !is ThreadDetailFilterUi.All) {
            if (resolvedPoll == null) return currentFloors
            return currentFloors.map { if (it.floor == 1) it.copy(poll = resolvedPoll) else it }
        }
        if (refreshedPage1Floors.isEmpty()) {
            if (resolvedPoll == null) return currentFloors
            return currentFloors.map { if (it.floor == 1) it.copy(poll = resolvedPoll) else it }
        }
        val normalizedPage1 = if (resolvedPoll != null) {
            refreshedPage1Floors.map { if (it.floor == 1) it.copy(poll = resolvedPoll) else it }
        } else {
            refreshedPage1Floors
        }
        val laterPages = currentFloors.filter { it.page > 1 }
        if (laterPages.isEmpty()) return normalizedPage1
        val page1Ids = normalizedPage1.mapTo(HashSet(normalizedPage1.size)) { it.postId }
        return normalizedPage1 + laterPages.filter { it.postId !in page1Ids }
    }

    private fun cancelPagingJobs() {
        prependJob?.cancel(); prependJob = null
        appendJob?.cancel(); appendJob = null
        inFlightPages.clear()
    }

    private fun launchDirectionalPage(prepend: Boolean, isRetry: Boolean, priority: AccessPriority) {
        val cur = metaState.value
        if (cur.tid.isBlank() || cur.isLoading || cur.rawFloors.isEmpty()) return
        if (prepend && (cur.isPrepending || prependJob?.isActive == true || (!isRetry && cur.prependError != null))) return
        if (!prepend && (cur.isAppending || appendJob?.isActive == true || (!isRetry && cur.appendError != null))) return
        val targetPage = if (prepend) cur.firstLoadedPage - 1 else cur.lastLoadedPage + 1
        val generation = sessionGeneration
        val flightKey = generation to targetPage
        if (targetPage !in 1..cur.lastPage || targetPage in cur.firstLoadedPage..cur.lastLoadedPage || !inFlightPages.add(flightKey)) return

        val filterSnapshot = cur.filter
        metaState.update { if (prepend) it.copy(isPrepending = true, prependError = null) else it.copy(isAppending = true, appendError = null) }
        val job = viewModelScope.launch {
            try {
                val res = threadDetailRepository.loadPage(cur.tid, targetPage, filterSnapshot.toRepositoryFilter(), recordHistory = false, forumName = cur.forumName, priority = priority)
                if (generation != sessionGeneration) return@launch
                when (res) {
                    is ThreadDetailLoadResult.Success -> mergeDirectionalSuccess(prepend, targetPage, res)
                    is ThreadDetailError -> UiErrorMapper.map(res).let { err ->
                        metaState.update { if (prepend) it.copy(isPrepending = false, prependError = err) else it.copy(isAppending = false, appendError = err) }
                    }
                }
            } finally {
                inFlightPages.remove(flightKey)
            }
        }
        if (prepend) prependJob = job else appendJob = job
    }

    private fun mergeDirectionalSuccess(prepend: Boolean, targetPage: Int, res: ThreadDetailLoadResult.Success) {
        metaState.update { cur ->
            val incoming = res.page.floors
            val existingIds = cur.rawFloors.mapTo(HashSet(cur.rawFloors.size + incoming.size)) { it.postId }
            val uniqueNew = incoming.filter { existingIds.add(it.postId) }
            val merged = when {
                uniqueNew.isEmpty() -> cur.rawFloors
                prepend -> uniqueNew + cur.rawFloors
                else -> cur.rawFloors + uniqueNew
            }
            val firstFloor = incoming.firstOrNull { it.floor == 1 }
            val opUid = res.threadAuthorId ?: (res.filter as? ThreadDetailFilter.AuthorOnly)?.authorId ?: firstFloor?.uid?.takeIf { it.isNotBlank() }
            val opName = firstFloor?.author?.takeIf { it.isNotBlank() }
            val resolvedLastPage = maxOf(cur.lastPage, res.lastPage).coerceAtLeast(1)
            val resolvedFid = res.fid.takeIf { it > 0 } ?: cur.fid
            val resolvedUserUid = resolvePageUserUid(res)
            if (prepend) {
                cur.copy(
                    fid = resolvedFid, title = res.title.ifBlank { cur.title },
                    threadAuthorId = opUid ?: cur.threadAuthorId, threadAuthorName = opName ?: cur.threadAuthorName,
                    firstLoadedPage = minOf(cur.firstLoadedPage, targetPage, res.pageNumber).coerceAtLeast(1), lastPage = resolvedLastPage,
                    rawFloors = merged, isPrepending = false, prependError = null, prependGeneration = cur.prependGeneration + 1L,
                    currentUserUid = resolvedUserUid,
                )
            } else {
                val clampedEnd = res.pageNumber < targetPage && res.pageNumber >= res.lastPage
                val nextLast = if (clampedEnd) res.lastPage.coerceAtLeast(cur.lastLoadedPage) else maxOf(cur.lastLoadedPage, targetPage, res.pageNumber)
                val finalLastPage = if (clampedEnd) res.lastPage.coerceAtLeast(1) else maxOf(resolvedLastPage, nextLast)
                cur.copy(
                    fid = resolvedFid, title = res.title.ifBlank { cur.title },
                    threadAuthorId = opUid ?: cur.threadAuthorId, threadAuthorName = opName ?: cur.threadAuthorName,
                    lastLoadedPage = nextLast.coerceAtMost(finalLastPage), lastPage = finalLastPage,
                    rawFloors = merged, isAppending = false, appendError = null, currentUserUid = resolvedUserUid,
                )
            }
        }
    }

    private fun observeAuthRecovery(auth: AuthRepository) {
        var prevAccount = auth.currentAccount.value
        var prevState = auth.authState.value
        var prevEpoch = auth.sessionEpoch
        viewModelScope.launch {
            combine(auth.currentAccount, auth.authState) { account, state -> account to state }
                .drop(1)
                .collect { (account, state) ->
                    val epoch = auth.sessionEpoch
                    val activeUid = resolveAuthUid(account, state)
                    metaState.update { cur ->
                        val nextPollErr = if (activeUid != null && cur.pollError?.action == UiErrorAction.Login) null else cur.pollError
                        if (cur.currentUserUid == activeUid && cur.pollError == nextPollErr) cur else cur.copy(currentUserUid = activeUid, pollError = nextPollErr)
                    }
                    val accountActivated = account != null &&
                        (account.uid != prevAccount?.uid || account.lastLoginAt != prevAccount?.lastLoginAt) &&
                        state !is AuthState.SessionExpired && state !is AuthState.Reauthenticating
                    val signedInTransition = state is AuthState.SignedIn && prevState !is AuthState.SignedIn
                    val epochAdvanced = account != null && epoch != prevEpoch &&
                        state !is AuthState.SignedOut && state !is AuthState.SessionExpired
                    prevAccount = account; prevState = state; prevEpoch = epoch
                    if (accountActivated || signedInTransition || epochAdvanced) {
                        authVersion += 1L
                        val cur = metaState.value
                        if (cur.tid.isNotBlank() && cur.isAuthRequired && !cur.isLoading) {
                            loadInternal(cur.requestedPage, cur.filter, AccessPriority.UserBlocking)
                        }
                    }
                }
        }
    }

    private fun loadByPostIdInternal(targetPid: String, highlight: Boolean) {
        val cur = metaState.value
        if (cur.tid.isBlank()) return
        cancelPagingJobs()
        loadJob?.cancel()
        val generation = ++sessionGeneration
        metaState.update {
            it.copy(
                isLoading = true, isPrepending = false, isAppending = false, isAuthRequired = false,
                error = if (it.rawFloors.isEmpty()) null else it.error,
                prependError = null, appendError = null, challengeFailure = null,
                pendingScroll = null, highlightedFloor = null, resetEpoch = generation,
            )
        }
        loadJob = viewModelScope.launch {
            when (val res = threadDetailRepository.loadByPostId(targetPid, cur.tid, recordHistory = false, forumName = cur.forumName, priority = AccessPriority.UserBlocking)) {
                is ThreadDetailLoadResult.Success -> {
                    if (generation != sessionGeneration) return@launch
                    val floors = res.page.floors
                    val firstFloor = floors.firstOrNull { it.floor == 1 }
                    val opUid = res.threadAuthorId ?: firstFloor?.uid?.takeIf { it.isNotBlank() }
                    val opName = firstFloor?.author?.takeIf { it.isNotBlank() }
                    val resolvedPage = res.pageNumber.coerceAtLeast(1)
                    val resolvedLastPage = res.lastPage.coerceAtLeast(resolvedPage)
                    val resolvedPid = res.targetPostId?.takeIf { pid -> floors.any { it.postId == pid } }
                        ?: targetPid.takeIf { pid -> floors.any { it.postId == pid } }
                    val token = ++scrollTokenSeq
                    val immediateHighlight = if (highlight && resolvedPid != null) HighlightedFloor(resolvedPid, token) else null
                    preFilterAnchor = null
                    metaState.update {
                        it.copy(
                            fid = res.fid.takeIf { f -> f > 0 } ?: it.fid,
                            title = res.title.ifBlank { it.title },
                            threadAuthorId = opUid ?: it.threadAuthorId,
                            threadAuthorName = opName ?: it.threadAuthorName,
                            page = resolvedPage, requestedPage = resolvedPage,
                            firstLoadedPage = resolvedPage, lastLoadedPage = resolvedPage, lastPage = resolvedLastPage,
                            filter = ThreadDetailFilterUi.All, rawFloors = floors,
                            isLoading = false, isPrepending = false, isAppending = false, isAuthRequired = false,
                            error = null, prependError = null, appendError = null, challengeFailure = null,
                            pendingScroll = if (floors.isNotEmpty()) PendingScrollTarget(token, resolvedPid, 0, highlightPostId = immediateHighlight?.postId) else null,
                            highlightedFloor = immediateHighlight,
                            currentUserUid = resolvePageUserUid(res),
                        )
                    }
                }
                is ThreadDetailError -> {
                    if (generation != sessionGeneration) return@launch
                    val mapped = UiErrorMapper.map(res)
                    metaState.update { state ->
                        if (state.rawFloors.isEmpty()) {
                            state.copy(
                                isLoading = false, isAuthRequired = res is ThreadDetailError.AuthRequired,
                                error = mapped,
                                challengeFailure = (res as? ThreadDetailError.ChallengeRequired)?.let { c -> if (c.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete },
                            )
                        } else {
                            state.copy(isLoading = false, notice = ThreadDetailNotice(++noticeSeq, mapped))
                        }
                    }
                }
            }
        }
    }

    private fun loadInternal(
        page: Int,
        filter: ThreadDetailFilterUi,
        priority: AccessPriority,
        scrollToTop: Boolean = false,
        restoreAnchor: FilterReturnAnchor? = null,
        authRetryAttempted: Boolean = false,
    ) {
        val cur = metaState.value
        if (cur.tid.isBlank()) return
        val targetPage = page.coerceAtLeast(1)
        val startedAuthVersion = authVersion
        cancelPagingJobs()
        loadJob?.cancel()
        val generation = ++sessionGeneration
        metaState.update {
            it.copy(
                requestedPage = targetPage, filter = filter, isLoading = true, isPrepending = false, isAppending = false,
                isAuthRequired = false, error = null, prependError = null, appendError = null,
                challengeFailure = null, pendingScroll = null, highlightedFloor = null, resetEpoch = generation,
            )
        }
        loadJob = viewModelScope.launch {
            when (val res = threadDetailRepository.loadPage(cur.tid, targetPage, filter.toRepositoryFilter(), forumName = cur.forumName, priority = priority)) {
                is ThreadDetailLoadResult.Success -> {
                    if (generation != sessionGeneration) return@launch
                    val floors = res.page.floors
                    val firstFloor = floors.firstOrNull { it.floor == 1 }
                    val opUid = res.threadAuthorId ?: (res.filter as? ThreadDetailFilter.AuthorOnly)?.authorId ?: firstFloor?.uid?.takeIf { it.isNotBlank() }
                    val opName = firstFloor?.author?.takeIf { it.isNotBlank() }
                    val resolvedFilter = if (filter is ThreadDetailFilterUi.AuthorOnly) ThreadDetailFilterUi.AuthorOnly(opUid ?: filter.authorId) else filter
                    val scrollTarget = when {
                        floors.isEmpty() -> null
                        restoreAnchor != null -> PendingScrollTarget(++scrollTokenSeq, restoreAnchor.postId, restoreAnchor.scrollOffset)
                        scrollToTop -> PendingScrollTarget(++scrollTokenSeq, null, 0)
                        else -> null
                    }
                    val resolvedPage = res.pageNumber.coerceAtLeast(1)
                    val resolvedLastPage = res.lastPage.coerceAtLeast(resolvedPage)
                    metaState.update {
                        it.copy(
                            fid = res.fid.takeIf { f -> f > 0 } ?: it.fid,
                            title = res.title.ifBlank { it.title }, threadAuthorId = opUid ?: it.threadAuthorId, threadAuthorName = opName ?: it.threadAuthorName,
                            page = resolvedPage, requestedPage = resolvedPage, firstLoadedPage = resolvedPage, lastLoadedPage = resolvedPage,
                            lastPage = resolvedLastPage, filter = resolvedFilter, rawFloors = floors,
                            isLoading = false, isPrepending = false, isAppending = false, isAuthRequired = false,
                            error = null, prependError = null, appendError = null, challengeFailure = null, pendingScroll = scrollTarget,
                            currentUserUid = resolvePageUserUid(res),
                        )
                    }
                }
                is ThreadDetailError -> {
                    if (generation != sessionGeneration) return@launch
                    if (res is ThreadDetailError.AuthRequired && !authRetryAttempted && authVersion != startedAuthVersion) {
                        loadInternal(targetPage, filter, priority, scrollToTop, restoreAnchor, authRetryAttempted = true)
                        return@launch
                    }
                    metaState.update {
                        it.copy(
                            rawFloors = emptyList(), isLoading = false, isPrepending = false, isAppending = false,
                            isAuthRequired = res is ThreadDetailError.AuthRequired, error = UiErrorMapper.map(res),
                            prependError = null, appendError = null,
                            challengeFailure = (res as? ThreadDetailError.ChallengeRequired)?.let { c -> if (c.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete },
                        )
                    }
                }
            }
        }
    }

    private fun resolvePageUserUid(res: ThreadDetailLoadResult.Success): String? {
        authRepository?.let { auth ->
            return resolveAuthUid(auth.currentAccount.value, auth.authState.value)
        }
        val session = res.page.meta.session
        return if (session.isLoggedIn) session.uid?.takeIf { it.isNotBlank() } else null
    }

    private fun resolveAuthUid(account: SavedAccount?, state: AuthState): String? =
        if (account != null && state !is AuthState.SignedOut && state !is AuthState.SessionExpired) {
            account.uid.takeIf { it.isNotBlank() }
        } else {
            null
        }
}
