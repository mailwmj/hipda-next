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
)

private data class FilterReturnAnchor(val page: Int, val postId: String?, val scrollOffset: Int)

private data class DetailMetaState(
    val tid: String = "",
    val title: String = "",
    val forumName: String? = null,
    val threadAuthorId: String? = null,
    val threadAuthorName: String? = null,
    val page: Int = 1,
    val requestedPage: Int = 1,
    val lastPage: Int = 1,
    val filter: ThreadDetailFilterUi = ThreadDetailFilterUi.All,
    val isLoading: Boolean = false,
    val isAuthRequired: Boolean = false,
    val error: UiError? = null,
    val challengeFailure: ChallengeFailureKind? = null,
    val pendingScroll: PendingScrollTarget? = null,
    val expectedFloorCount: Int? = null,
    val expectedFirstPostId: String? = null,
)

/**
 * 帖子详情 ViewModel（按 `tid` 托管保活；支持 `只看楼主`/退出回原楼、登录成功后从 `AuthRequired` 自动重载，
 * 并保留与 `E0` 一致的 2 参次构造函数）。
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
    private var preFilterAnchor: FilterReturnAnchor? = null
    private var scrollTokenSeq: Long = 0L
    private var authVersion: Long = 0L

    private val rawFloors = MutableStateFlow<List<PostFloor>>(emptyList())
    private val metaState = MutableStateFlow(DetailMetaState())

    val uiState: StateFlow<ThreadDetailUiState> = combine(
        metaState,
        threadDetailRepository.observeFloors(rawFloors),
        siteAccessGateway.challengeState,
    ) { m, floors, challenge ->
        val synced = m.expectedFloorCount == null || (
            floors.size == m.expectedFloorCount &&
                (m.expectedFirstPostId == null || floors.firstOrNull()?.postId == m.expectedFirstPostId)
            )
        ThreadDetailUiState(
            tid = m.tid,
            title = m.title,
            threadAuthorId = m.threadAuthorId,
            threadAuthorName = m.threadAuthorName,
            page = m.page,
            lastPage = m.lastPage,
            filter = m.filter,
            floors = floors,
            isLoading = m.isLoading || (!synced && m.error == null),
            error = m.error,
            challengeFailure = m.challengeFailure,
            challengeState = challenge,
            pendingScroll = if (synced) m.pendingScroll else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ThreadDetailUiState())

    fun bindArgs(args: ThreadNavArgs) {
        if (boundTid == args.tid) return
        boundTid = args.tid
        preFilterAnchor = null
        rawFloors.value = emptyList()
        metaState.value = DetailMetaState(
            tid = args.tid,
            title = args.title,
            forumName = args.forumName,
            threadAuthorName = args.author?.takeIf { it.isNotBlank() },
            isLoading = true,
        )
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

    fun toggleAuthorOnly(anchorPostId: String? = null, anchorScrollOffset: Int = 0) {
        val cur = metaState.value
        if (cur.tid.isBlank()) return
        if (cur.filter is ThreadDetailFilterUi.AuthorOnly) {
            clearFilter()
            return
        }
        if (cur.filter is ThreadDetailFilterUi.All) {
            preFilterAnchor = FilterReturnAnchor(cur.page, anchorPostId ?: rawFloors.value.firstOrNull()?.postId, anchorScrollOffset.coerceAtLeast(0))
        }
        loadInternal(1, ThreadDetailFilterUi.AuthorOnly(cur.threadAuthorId), AccessPriority.UserBlocking, scrollToTop = true)
    }

    fun clearFilter() {
        val cur = metaState.value
        if (cur.tid.isBlank() || cur.filter is ThreadDetailFilterUi.All) return
        val anchor = preFilterAnchor
        preFilterAnchor = null
        loadInternal(
            page = anchor?.page ?: 1,
            filter = ThreadDetailFilterUi.All,
            priority = AccessPriority.UserBlocking,
            scrollToTop = anchor == null,
            restoreAnchor = anchor,
        )
    }

    fun consumePendingScroll(tokenId: Long) {
        metaState.update { if (it.pendingScroll?.id == tokenId) it.copy(pendingScroll = null) else it }
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
        metaState.update { it.copy(requestedPage = targetPage, filter = filter, isLoading = true, isAuthRequired = false, error = null, challengeFailure = null, pendingScroll = null) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            when (val res = threadDetailRepository.loadPage(cur.tid, targetPage, filter.toRepositoryFilter(), forumName = cur.forumName, priority = priority)) {
                is ThreadDetailLoadResult.Success -> {
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
                    rawFloors.value = floors
                    metaState.update {
                        it.copy(
                            title = res.title.ifBlank { it.title },
                            threadAuthorId = opUid ?: it.threadAuthorId,
                            threadAuthorName = opName ?: it.threadAuthorName,
                            page = res.pageNumber,
                            requestedPage = res.pageNumber,
                            lastPage = res.lastPage.coerceAtLeast(1),
                            filter = resolvedFilter,
                            isLoading = false,
                            isAuthRequired = false,
                            error = null,
                            challengeFailure = null,
                            pendingScroll = scrollTarget,
                            expectedFloorCount = floors.size,
                            expectedFirstPostId = floors.firstOrNull()?.postId,
                        )
                    }
                }
                is ThreadDetailError -> {
                    if (res is ThreadDetailError.AuthRequired && !authRetryAttempted && authVersion != startedAuthVersion) {
                        loadInternal(targetPage, filter, priority, scrollToTop, restoreAnchor, authRetryAttempted = true)
                        return@launch
                    }
                    rawFloors.value = emptyList()
                    metaState.update {
                        it.copy(
                            isLoading = false,
                            isAuthRequired = res is ThreadDetailError.AuthRequired,
                            error = UiErrorMapper.map(res),
                            challengeFailure = (res as? ThreadDetailError.ChallengeRequired)?.let { c -> if (c.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete },
                            expectedFloorCount = 0,
                            expectedFirstPostId = null,
                        )
                    }
                }
            }
        }
    }
}
