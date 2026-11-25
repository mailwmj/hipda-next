package net.jejer.hipda.feature.threaddetail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.threaddetail.*
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.common.UiErrorMapper
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.ui.challenge.ChallengeFailureKind

/** 帖子详情只看过滤态（`E2a` 落地 `只看楼主`，预留 `UserOnly` 供 Wave 3 `E2c` 复用）。 */
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
data class PendingScrollTarget(val id: Long, val postId: String?, val scrollOffset: Int = 0)

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
) {
    val hasPreviousPage: Boolean get() = floors.isNotEmpty() && firstLoadedPage > 1
    val hasNextPage: Boolean get() = floors.isNotEmpty() && lastLoadedPage < lastPage
    val showPrependHeader: Boolean get() = floors.isNotEmpty() && (isPrepending || prependError != null)
}

private data class FilterReturnAnchor(val page: Int, val postId: String?, val scrollOffset: Int)

private data class DetailMetaState(
    val tid: String = "",
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
)

/**
 * 帖子详情 ViewModel（按 `tid` 托管保活；支持双向连续加载、单飞防重、头尾独立错误重试、
 * `只看楼主`/退出回原楼、登录成功后从 `AuthRequired` 自动重载，并保留与 `E0` 一致的 2 参主构造函数）。
 */
@HiltViewModel
class ThreadDetailViewModel(
    private val threadDetailRepository: ThreadDetailRepository,
    private val siteAccessGateway: SiteAccessGateway,
) : ViewModel() {

    @Inject
    constructor(
        threadDetailRepository: ThreadDetailRepository,
        siteAccessGateway: SiteAccessGateway,
        authRepository: AuthRepository,
    ) : this(threadDetailRepository, siteAccessGateway) {
        observeAuthRecovery(authRepository)
    }

    private var boundTid: String? = null
    private var loadJob: Job? = null
    private var prependJob: Job? = null
    private var appendJob: Job? = null
    private var preFilterAnchor: FilterReturnAnchor? = null
    private var scrollTokenSeq: Long = 0L
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
        )
    }.filterNotNull().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ThreadDetailUiState())

    fun bindArgs(args: ThreadNavArgs) {
        if (boundTid == args.tid) return
        boundTid = args.tid
        preFilterAnchor = null
        cancelPagingJobs()
        val nextEpoch = ++sessionGeneration
        metaState.update { prev ->
            DetailMetaState(
                tid = args.tid, title = args.title, forumName = args.forumName,
                threadAuthorName = args.author?.takeIf { it.isNotBlank() },
                isLoading = true, prependGeneration = prev.prependGeneration, resetEpoch = nextEpoch,
            )
        }
        loadInternal(page = 1, filter = ThreadDetailFilterUi.All, priority = AccessPriority.UserBlocking)
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
        if (cur.filter is ThreadDetailFilterUi.All) {
            val anchorFloor = anchorPostId?.let { pid -> cur.rawFloors.firstOrNull { it.postId == pid } } ?: cur.rawFloors.firstOrNull()
            preFilterAnchor = FilterReturnAnchor(anchorFloor?.page ?: cur.page, anchorPostId ?: anchorFloor?.postId, anchorScrollOffset.coerceAtLeast(0))
        }
        loadInternal(1, ThreadDetailFilterUi.AuthorOnly(cur.threadAuthorId), AccessPriority.UserBlocking, scrollToTop = true)
    }

    fun clearFilter() {
        val cur = metaState.value
        if (cur.tid.isBlank() || cur.filter is ThreadDetailFilterUi.All) return
        val anchor = preFilterAnchor
        preFilterAnchor = null
        loadInternal(anchor?.page ?: 1, ThreadDetailFilterUi.All, AccessPriority.UserBlocking, scrollToTop = anchor == null, restoreAnchor = anchor)
    }

    fun consumePendingScroll(tokenId: Long) {
        metaState.update { if (it.pendingScroll?.id == tokenId) it.copy(pendingScroll = null) else it }
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
            if (prepend) {
                cur.copy(
                    title = res.title.ifBlank { cur.title }, threadAuthorId = opUid ?: cur.threadAuthorId, threadAuthorName = opName ?: cur.threadAuthorName,
                    firstLoadedPage = minOf(cur.firstLoadedPage, targetPage, res.pageNumber).coerceAtLeast(1), lastPage = resolvedLastPage,
                    rawFloors = merged, isPrepending = false, prependError = null, prependGeneration = cur.prependGeneration + 1L,
                )
            } else {
                val clampedEnd = res.pageNumber < targetPage && res.pageNumber >= res.lastPage
                val nextLast = if (clampedEnd) res.lastPage.coerceAtLeast(cur.lastLoadedPage) else maxOf(cur.lastLoadedPage, targetPage, res.pageNumber)
                val finalLastPage = if (clampedEnd) res.lastPage.coerceAtLeast(1) else maxOf(resolvedLastPage, nextLast)
                cur.copy(
                    title = res.title.ifBlank { cur.title }, threadAuthorId = opUid ?: cur.threadAuthorId, threadAuthorName = opName ?: cur.threadAuthorName,
                    lastLoadedPage = nextLast.coerceAtMost(finalLastPage), lastPage = finalLastPage,
                    rawFloors = merged, isAppending = false, appendError = null,
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
                challengeFailure = null, pendingScroll = null, resetEpoch = generation,
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
                        restoreAnchor != null -> PendingScrollTarget(++scrollTokenSeq, restoreAnchor.postId, restoreAnchor.scrollOffset)
                        scrollToTop -> PendingScrollTarget(++scrollTokenSeq, null, 0)
                        else -> null
                    }
                    val resolvedPage = res.pageNumber.coerceAtLeast(1)
                    val resolvedLastPage = res.lastPage.coerceAtLeast(resolvedPage)
                    metaState.update {
                        it.copy(
                            title = res.title.ifBlank { it.title }, threadAuthorId = opUid ?: it.threadAuthorId, threadAuthorName = opName ?: it.threadAuthorName,
                            page = resolvedPage, requestedPage = resolvedPage, firstLoadedPage = resolvedPage, lastLoadedPage = resolvedPage,
                            lastPage = resolvedLastPage, filter = resolvedFilter, rawFloors = floors,
                            isLoading = false, isPrepending = false, isAppending = false, isAuthRequired = false,
                            error = null, prependError = null, appendError = null, challengeFailure = null, pendingScroll = scrollTarget,
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
}
