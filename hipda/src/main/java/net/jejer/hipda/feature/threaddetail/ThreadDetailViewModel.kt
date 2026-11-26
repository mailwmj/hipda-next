package net.jejer.hipda.feature.threaddetail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.favorite.FavoriteError
import net.jejer.hipda.data.favorite.FavoriteKind
import net.jejer.hipda.data.favorite.FavoriteMutationResult
import net.jejer.hipda.data.favorite.FavoriteRepository
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.threaddetail.*
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.feature.common.UiErrorMapper
import net.jejer.hipda.feature.threaddetail.chrome.PositionStack
import net.jejer.hipda.feature.threaddetail.chrome.ReadingPositionAnchor
import net.jejer.hipda.feature.threaddetail.chrome.ReadingPositionController
import net.jejer.hipda.feature.threaddetail.chrome.ReadingPositionSnapshot
import net.jejer.hipda.feature.threaddetail.chrome.toReadingFilterTriple
import net.jejer.hipda.feature.threaddetail.chrome.toSnapshot
import net.jejer.hipda.feature.threaddetail.chrome.toThreadDetailFilterUi
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
sealed interface ThreadDetailSnackbarEvent {
    val id: Long

    @Immutable
    data class FavoriteUndoable(
        override val id: Long,
        val tokenId: Long,
        val kind: FavoriteKind,
        val tid: String,
        val added: Boolean,
    ) : ThreadDetailSnackbarEvent
}

private data class FavoriteUndoToken(
    val id: Long,
    val kind: FavoriteKind,
    val tid: String,
    val previousActive: Boolean,
    val targetActive: Boolean,
)

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
    val isFavorited: Boolean = false,
    val isAttention: Boolean = false,
    val canPopPositionStack: Boolean = false,
    val forumServer: String = AppSettings.DEFAULT_FORUM_SERVER,
    val snackbarEvent: ThreadDetailSnackbarEvent? = null,
    val snackbarResetSeq: Long = 0L,
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
    val isFavorited: Boolean = false,
    val isAttention: Boolean = false,
    val canPopPositionStack: Boolean = false,
    val forumServer: String = AppSettings.DEFAULT_FORUM_SERVER,
    val snackbarEvent: ThreadDetailSnackbarEvent? = null,
    val snackbarResetSeq: Long = 0L,
)

/**
 * 帖子详情 ViewModel（按 `tid` 托管保活；支持双向连续加载、单飞防重、头尾独立错误重试、
 * 本页/跨页精确跳楼与高亮、`只看楼主`/`只看此人` 过滤、1 楼投票提交刷新、带首轮恢复门闩的阅读位置记忆、
 * 返回位置栈、收藏/关注可撤销切换与登录自动重载，并保留 2/3 参兼容构造）。
 */
@HiltViewModel
class ThreadDetailViewModel(
    private val threadDetailRepository: ThreadDetailRepository,
    private val siteAccessGateway: SiteAccessGateway,
) : ViewModel() {

    private var authRepository: AuthRepository? = null
    private var favoriteRepository: FavoriteRepository? = null
    private var settingsRepository: SettingsRepository? = null

    constructor(
        threadDetailRepository: ThreadDetailRepository,
        siteAccessGateway: SiteAccessGateway,
        authRepository: AuthRepository?,
        favoriteRepository: FavoriteRepository? = null,
    ) : this(threadDetailRepository, siteAccessGateway) {
        initOptionalRepositories(authRepository, favoriteRepository, null)
    }

    @Inject
    constructor(
        threadDetailRepository: ThreadDetailRepository,
        siteAccessGateway: SiteAccessGateway,
        authRepository: AuthRepository,
        favoriteRepository: FavoriteRepository,
        settingsRepository: SettingsRepository,
    ) : this(threadDetailRepository, siteAccessGateway) {
        initOptionalRepositories(authRepository, favoriteRepository, settingsRepository)
    }

    private var boundTid: String? = null
    private var bindJob: Job? = null
    private var loadJob: Job? = null
    private var prependJob: Job? = null
    private var appendJob: Job? = null
    private var pollJob: Job? = null
    private var favoriteJob: Job? = null
    private var attentionJob: Job? = null
    private var preFilterAnchor: FilterReturnAnchor? = null
    private var latestObservedAnchor: ReadingPositionAnchor? = null
    private var pendingHydrationSnapshot: ReadingPositionSnapshot? = null
    private val positionStack = PositionStack()
    private val readingPositionController = ReadingPositionController(
        scope = viewModelScope,
        savePosition = { s ->
            threadDetailRepository.saveReadingPosition(s.tid, s.page, s.postId, s.offsetPx, s.filterMode, s.filterUid, s.filterUserName)
        },
    )
    private var scrollTokenSeq: Long = 0L
    private var noticeSeq: Long = 0L
    private var snackbarSeq: Long = 0L
    private var snackbarResetSeq: Long = 0L
    private var undoTokenSeq: Long = 0L
    private var activeUndoToken: FavoriteUndoToken? = null
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
            isFavorited = m.isFavorited, isAttention = m.isAttention,
            canPopPositionStack = m.canPopPositionStack, forumServer = m.forumServer,
            snackbarEvent = m.snackbarEvent, snackbarResetSeq = m.snackbarResetSeq,
        )
    }.filterNotNull().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ThreadDetailUiState())

    private fun initOptionalRepositories(
        authRepo: AuthRepository?,
        favRepo: FavoriteRepository?,
        settingsRepo: SettingsRepository?,
    ) {
        authRepo?.let { auth ->
            authRepository = auth
            metaState.update { it.copy(currentUserUid = resolveAuthUid(auth.currentAccount.value, auth.authState.value)) }
            observeAuthRecovery(auth)
        }
        favRepo?.let { favoriteRepository = it; attachFavoriteRepository(it) }
        settingsRepo?.let { settingsRepository = it; attachSettingsRepository(it) }
    }

    override fun onCleared() {
        readingPositionController.flushIfHydrated()
        super.onCleared()
    }

    fun bindArgs(args: ThreadNavArgs) {
        if (boundTid == args.tid) return
        val isRebind = boundTid != null
        // 切换帖子前仅在旧会话已完成首轮恢复时补写未落盘进度（B4-E4 ①）
        readingPositionController.flushIfHydrated()
        boundTid = args.tid
        preFilterAnchor = null
        latestObservedAnchor = null
        pendingHydrationSnapshot = null
        positionStack.clear()
        bindJob?.cancel(); bindJob = null
        cancelPagingJobs()
        pollJob?.cancel(); pollJob = null
        favoriteJob?.cancel(); favoriteJob = null
        attentionJob?.cancel(); attentionJob = null
        activeUndoToken = null
        readingPositionController.beginSession(args.tid)
        val nextEpoch = ++sessionGeneration
        val activeUid = authRepository?.let { resolveAuthUid(it.currentAccount.value, it.authState.value) }
        val initialFavorited = favoriteRepository?.isFavorite(args.tid) == true
        val initialAttention = favoriteRepository?.isAttention(args.tid) == true
        metaState.update { prev ->
            DetailMetaState(
                tid = args.tid, fid = args.fid, title = args.title, forumName = args.forumName,
                threadAuthorName = args.author?.takeIf { it.isNotBlank() },
                isLoading = true, prependGeneration = prev.prependGeneration, resetEpoch = nextEpoch,
                currentUserUid = activeUid,
                isFavorited = initialFavorited,
                isAttention = initialAttention,
                canPopPositionStack = false,
                forumServer = prev.forumServer,
            )
        }
        val initialPid = args.targetPid?.takeIf { it.isNotBlank() }
        if (initialPid != null) {
            loadByPostIdInternal(initialPid, highlight = true)
        } else {
            bindJob = viewModelScope.launch {
                val saved = threadDetailRepository.getReadingPosition(args.tid)
                if (nextEpoch != sessionGeneration) return@launch
                if (saved != null) {
                    val savedSnapshot = saved.toSnapshot()
                    pendingHydrationSnapshot = savedSnapshot
                    val restoredFilter = saved.toThreadDetailFilterUi()
                    val restoreAnchor = FilterReturnAnchor(
                        page = saved.page.coerceAtLeast(1),
                        postId = saved.pid.takeIf { it.isNotBlank() },
                        scrollOffset = saved.offsetPx.coerceAtLeast(0),
                    )
                    loadInternal(
                        page = restoreAnchor.page,
                        filter = restoredFilter,
                        priority = AccessPriority.UserBlocking,
                        scrollToTop = false,
                        restoreAnchor = restoreAnchor,
                    )
                } else {
                    loadInternal(
                        page = 1,
                        filter = ThreadDetailFilterUi.All,
                        priority = AccessPriority.UserBlocking,
                        scrollToTop = isRebind,
                    )
                }
            }
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

    /** 仅在首轮恢复完成且无待落地滚动时登记当前阅读锚点并驱动去抖保存（闭合 B4-E4 ①）。 */
    fun onReadingPositionChanged(page: Int, postId: String, scrollOffsetPx: Int) {
        val cur = metaState.value
        if (cur.tid.isBlank() || cur.isLoading || cur.pendingScroll != null || cur.rawFloors.isEmpty()) return
        val cleanPid = postId.trim()
        if (cleanPid.isEmpty()) return
        val clampedPage = page.coerceIn(1, cur.lastPage.coerceAtLeast(1))
        val clampedOffset = scrollOffsetPx.coerceAtLeast(0)
        latestObservedAnchor = ReadingPositionAnchor(
            page = clampedPage,
            postId = cleanPid,
            scrollOffsetPx = clampedOffset,
            filter = cur.filter,
        )
        val (filterMode, filterUid, filterUserName) = cur.filter.toReadingFilterTriple()
        readingPositionController.onPositionObserved(
            ReadingPositionSnapshot(
                tid = cur.tid,
                page = clampedPage,
                postId = cleanPid,
                offsetPx = clampedOffset,
                filterMode = filterMode,
                filterUid = filterUid,
                filterUserName = filterUserName,
            ),
        )
    }

    /** 离开帖子时立即刷盘未落盘的阅读进度（首轮恢复未完成时为安全空操作）。 */
    fun flushReadingPosition() {
        readingPositionController.flushIfHydrated()
    }

    /** 刷新当前页（保留当前页码与过滤态）。 */
    fun refreshCurrentPage() {
        val cur = metaState.value
        if (cur.tid.isBlank()) return
        loadInternal(cur.page, cur.filter, AccessPriority.UserExplicit, scrollToTop = false)
    }

    /** 经内联跳页条/页码输入跳至目标页，跳前压入 [PositionStack]。 */
    fun jumpToPage(page: Int, fromAnchor: ReadingPositionAnchor? = null) {
        val cur = metaState.value
        if (cur.tid.isBlank()) return
        pushCurrentAnchorForJump(fromAnchor)
        loadInternal(page.coerceIn(1, cur.lastPage.coerceAtLeast(1)), cur.filter, AccessPriority.UserBlocking, scrollToTop = true)
    }

    /** 查看新回复（按目标 `pid` 或末页定位），跳前压入 [PositionStack]。 */
    fun jumpToNewReply(targetPid: String? = null, fromAnchor: ReadingPositionAnchor? = null) {
        val cur = metaState.value
        if (cur.tid.isBlank()) return
        pushCurrentAnchorForJump(fromAnchor)
        val cleanPid = targetPid?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanPid != null) loadByPostIdInternal(cleanPid, highlight = true) else loadLastPageInternal()
    }

    /**
     * 系统返回优先从 [PositionStack] 出栈并经单一滚动权威恢复跳前锚点：
     * - 若目标页已在当前连续区间且过滤态一致，原地零网络定位回位；
     * - 否则重新加载目标页并在首帧测量前精确落位；
     * - 栈空时返回 `false`，交由外层（`E3` 多栏返回栈或单栏返回）继续处理。
     */
    fun popPosition(): Boolean {
        val anchor = positionStack.pop() ?: return false
        val cur = metaState.value
        val hasRemaining = positionStack.isNotEmpty
        val canRestoreInPlace = anchor.filter == cur.filter &&
            anchor.page in cur.firstLoadedPage..cur.lastLoadedPage &&
            (anchor.postId == null || cur.rawFloors.any { it.postId == anchor.postId })
        if (canRestoreInPlace) {
            cancelPagingJobs()
            val token = ++scrollTokenSeq
            val nextEpoch = ++sessionGeneration
            metaState.update {
                it.copy(
                    page = anchor.page.coerceIn(1, it.lastPage.coerceAtLeast(1)),
                    resetEpoch = nextEpoch,
                    isPrepending = false,
                    isAppending = false,
                    prependError = null,
                    appendError = null,
                    highlightedFloor = null,
                    pendingScroll = PendingScrollTarget(token, anchor.postId, anchor.scrollOffsetPx),
                    canPopPositionStack = hasRemaining,
                )
            }
        } else {
            metaState.update { it.copy(canPopPositionStack = hasRemaining) }
            loadInternal(
                page = anchor.page,
                filter = anchor.filter,
                priority = AccessPriority.UserBlocking,
                scrollToTop = anchor.postId == null && anchor.scrollOffsetPx == 0,
                restoreAnchor = FilterReturnAnchor(anchor.page, anchor.postId, anchor.scrollOffsetPx),
            )
        }
        return true
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
     * 本页或跨页精确跳楼（闭合 `R-BATCH5 B4-E2c` 与 `R-E2c S1-R2`）：
     * - 跳楼前将当前锚点压入 [PositionStack]；
     * - 本页分支在递增 `sessionGeneration` 时同步 [cancelPagingJobs] 并复位 `isPrepending`/`isAppending`，
     *   避免在途预取被世代抛弃后把连续加载永久锁死；
     * - 跨页分支调用 `D5.loadByPostId` 加载目标页并按稳定键 `floor:$targetPid` 滚入视口后高亮。
     */
    fun jumpToFloor(targetPid: String, targetTid: String? = null, fromAnchor: ReadingPositionAnchor? = null) {
        val cur = metaState.value
        val cleanPid = targetPid.trim()
        if (cur.tid.isBlank() || cleanPid.isBlank()) return
        if (cur.rawFloors.any { it.postId == cleanPid }) {
            val currentAnchor = fromAnchor ?: resolveCurrentAnchor(cur)
            if (currentAnchor != null && !(currentAnchor.postId == cleanPid && currentAnchor.scrollOffsetPx == 0)) {
                pushCurrentAnchorForJump(currentAnchor)
            }
            cancelPagingJobs()
            val token = ++scrollTokenSeq
            val nextEpoch = ++sessionGeneration
            metaState.update {
                it.copy(
                    resetEpoch = nextEpoch, isPrepending = false, isAppending = false,
                    prependError = null, appendError = null,
                    pendingScroll = PendingScrollTarget(token, cleanPid, 0, highlightPostId = cleanPid),
                    highlightedFloor = HighlightedFloor(cleanPid, token),
                    canPopPositionStack = positionStack.isNotEmpty,
                )
            }
            return
        }
        val cleanTid = targetTid?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanTid != null && cleanTid != cur.tid) return
        pushCurrentAnchorForJump(fromAnchor)
        loadByPostIdInternal(cleanPid, highlight = true)
    }

    fun toggleFavorite() = toggleFavoriteKind(FavoriteKind.FAVORITE)

    fun toggleAttention() = toggleFavoriteKind(FavoriteKind.ATTENTION)

    /** 按独立撤销令牌恢复对应那一次收藏/关注操作；已确认或失效的旧令牌直接忽略。 */
    fun undoFavoriteMutation(tokenId: Long) {
        val token = activeUndoToken?.takeIf { it.id == tokenId } ?: return
        val cur = metaState.value
        if (cur.tid != token.tid || token.tid.isBlank()) return
        if (cur.currentUserUid.isNullOrBlank()) {
            activeUndoToken = null
            emitLoginNotice()
            return
        }
        enqueueFavoriteMutation(kind = token.kind, tid = token.tid, targetActive = token.previousActive, undoToken = null)
    }

    fun consumeSnackbarEvent(eventId: Long) {
        metaState.update { cur ->
            if (cur.snackbarEvent?.id == eventId) cur.copy(snackbarEvent = null) else cur
        }
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

    private fun loadByPostIdInternal(targetPid: String, highlight: Boolean) = launchSinglePageLoad(
        keepExistingFloorsOnError = true,
        fetch = { cur -> threadDetailRepository.loadByPostId(targetPid, cur.tid, recordHistory = false, forumName = cur.forumName, priority = AccessPriority.UserBlocking) },
        onSuccess = { cur, res ->
            val floors = res.page.floors
            val resolvedPid = res.targetPostId?.takeIf { pid -> floors.any { it.postId == pid } } ?: targetPid.takeIf { pid -> floors.any { it.postId == pid } }
            val token = ++scrollTokenSeq
            val immediateHighlight = if (highlight && resolvedPid != null) HighlightedFloor(resolvedPid, token) else null
            preFilterAnchor = null
            commitSinglePageSuccess(
                curTid = cur.tid, res = res, resolvedFilter = ThreadDetailFilterUi.All,
                pendingScroll = if (floors.isNotEmpty()) PendingScrollTarget(token, resolvedPid, 0, highlightPostId = immediateHighlight?.postId) else null,
                highlightedFloor = immediateHighlight,
            )
        },
    )

    private fun loadLastPageInternal() = launchSinglePageLoad(
        keepExistingFloorsOnError = true,
        fetch = { cur -> threadDetailRepository.loadLastPage(cur.tid, recordHistory = false, forumName = cur.forumName, priority = AccessPriority.UserBlocking) },
        onSuccess = { cur, res ->
            val floors = res.page.floors
            commitSinglePageSuccess(
                curTid = cur.tid, res = res, resolvedFilter = ThreadDetailFilterUi.All,
                pendingScroll = if (floors.isNotEmpty()) PendingScrollTarget(++scrollTokenSeq, floors.lastOrNull()?.postId, 0) else null,
            )
        },
    )

    private fun loadInternal(
        page: Int,
        filter: ThreadDetailFilterUi,
        priority: AccessPriority,
        scrollToTop: Boolean = false,
        restoreAnchor: FilterReturnAnchor? = null,
        authRetryAttempted: Boolean = false,
    ) {
        val targetPage = page.coerceAtLeast(1)
        val startedAuthVersion = authVersion
        launchSinglePageLoad(
            requestedPage = targetPage,
            requestedFilter = filter,
            keepExistingFloorsOnError = false,
            fetch = { cur -> threadDetailRepository.loadPage(cur.tid, targetPage, filter.toRepositoryFilter(), forumName = cur.forumName, priority = priority) },
            onSuccess = { cur, res ->
                val floors = res.page.floors
                val opUid = resolveAuthorUid(res, floors)
                val resolvedFilter = if (filter is ThreadDetailFilterUi.AuthorOnly) ThreadDetailFilterUi.AuthorOnly(opUid ?: filter.authorId) else filter
                val scrollTarget = when {
                    floors.isEmpty() -> null
                    restoreAnchor != null -> PendingScrollTarget(++scrollTokenSeq, restoreAnchor.postId, restoreAnchor.scrollOffset)
                    scrollToTop -> PendingScrollTarget(++scrollTokenSeq, null, 0)
                    else -> null
                }
                commitSinglePageSuccess(cur.tid, res, resolvedFilter, scrollTarget)
            },
            onAuthRetry = {
                if (!authRetryAttempted && authVersion != startedAuthVersion) {
                    loadInternal(targetPage, filter, priority, scrollToTop, restoreAnchor, authRetryAttempted = true)
                    true
                } else {
                    false
                }
            },
        )
    }

    private fun launchSinglePageLoad(
        requestedPage: Int? = null,
        requestedFilter: ThreadDetailFilterUi? = null,
        keepExistingFloorsOnError: Boolean,
        fetch: suspend (DetailMetaState) -> ThreadDetailLoadResult,
        onSuccess: (DetailMetaState, ThreadDetailLoadResult.Success) -> Unit,
        onAuthRetry: (() -> Boolean)? = null,
    ) {
        val cur = metaState.value
        if (cur.tid.isBlank()) return
        cancelPagingJobs()
        loadJob?.cancel()
        val generation = ++sessionGeneration
        metaState.update {
            it.copy(
                requestedPage = requestedPage ?: it.requestedPage,
                filter = requestedFilter ?: it.filter,
                isLoading = true, isPrepending = false, isAppending = false, isAuthRequired = false,
                error = if (keepExistingFloorsOnError && it.rawFloors.isNotEmpty()) it.error else null,
                prependError = null, appendError = null, challengeFailure = null,
                pendingScroll = null, highlightedFloor = null, resetEpoch = generation,
            )
        }
        loadJob = viewModelScope.launch {
            when (val res = fetch(cur)) {
                is ThreadDetailLoadResult.Success -> if (generation == sessionGeneration) onSuccess(cur, res)
                is ThreadDetailError -> {
                    if (generation != sessionGeneration) return@launch
                    if (res is ThreadDetailError.AuthRequired && onAuthRetry?.invoke() == true) return@launch
                    val mapped = UiErrorMapper.map(res)
                    val challengeFail = (res as? ThreadDetailError.ChallengeRequired)?.let { c ->
                        if (c.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete
                    }
                    metaState.update { state ->
                        if (keepExistingFloorsOnError && state.rawFloors.isNotEmpty()) {
                            state.copy(isLoading = false, notice = ThreadDetailNotice(++noticeSeq, mapped))
                        } else {
                            state.copy(
                                rawFloors = emptyList(), isLoading = false, isPrepending = false, isAppending = false,
                                isAuthRequired = res is ThreadDetailError.AuthRequired, error = mapped,
                                prependError = null, appendError = null, challengeFailure = challengeFail,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun resolveAuthorUid(res: ThreadDetailLoadResult.Success, floors: List<PostFloor>): String? =
        res.threadAuthorId ?: (res.filter as? ThreadDetailFilter.AuthorOnly)?.authorId ?: floors.firstOrNull { it.floor == 1 }?.uid?.takeIf { it.isNotBlank() }

    private fun commitSinglePageSuccess(
        curTid: String,
        res: ThreadDetailLoadResult.Success,
        resolvedFilter: ThreadDetailFilterUi,
        pendingScroll: PendingScrollTarget?,
        highlightedFloor: HighlightedFloor? = null,
    ) {
        val floors = res.page.floors
        val opUid = resolveAuthorUid(res, floors)
        val opName = floors.firstOrNull { it.floor == 1 }?.author?.takeIf { it.isNotBlank() }
        val resolvedPage = res.pageNumber.coerceAtLeast(1)
        val resolvedLastPage = res.lastPage.coerceAtLeast(resolvedPage)
        markHydratedIfNeeded(curTid)
        metaState.update {
            it.copy(
                fid = res.fid.takeIf { f -> f > 0 } ?: it.fid,
                title = res.title.ifBlank { it.title }, threadAuthorId = opUid ?: it.threadAuthorId, threadAuthorName = opName ?: it.threadAuthorName,
                page = resolvedPage, requestedPage = resolvedPage, firstLoadedPage = resolvedPage, lastLoadedPage = resolvedPage,
                lastPage = resolvedLastPage, filter = resolvedFilter, rawFloors = floors,
                isLoading = false, isPrepending = false, isAppending = false, isAuthRequired = false,
                error = null, prependError = null, appendError = null, challengeFailure = null,
                pendingScroll = pendingScroll, highlightedFloor = highlightedFloor, currentUserUid = resolvePageUserUid(res),
            )
        }
    }

    private fun attachFavoriteRepository(repo: FavoriteRepository) {
        viewModelScope.launch {
            combine(repo.favoriteTids, repo.attentionTids) { favs, atts -> favs to atts }
                .collect { (favs, atts) ->
                    metaState.update { cur ->
                        val nextFav = if (favoriteJob?.isActive == true) cur.isFavorited else (cur.tid.isNotBlank() && cur.tid in favs)
                        val nextAtt = if (attentionJob?.isActive == true) cur.isAttention else (cur.tid.isNotBlank() && cur.tid in atts)
                        if (cur.isFavorited == nextFav && cur.isAttention == nextAtt) cur else cur.copy(isFavorited = nextFav, isAttention = nextAtt)
                    }
                }
        }
    }

    private fun attachSettingsRepository(repo: SettingsRepository) {
        viewModelScope.launch {
            repo.settings
                .map { it.forumServer.ifBlank { AppSettings.DEFAULT_FORUM_SERVER } }
                .distinctUntilChanged()
                .collect { server -> metaState.update { cur -> if (cur.forumServer == server) cur else cur.copy(forumServer = server) } }
        }
    }

    private fun markHydratedIfNeeded(tid: String) {
        if (!readingPositionController.isHydrated) {
            readingPositionController.markHydrated(tid, pendingHydrationSnapshot)
            pendingHydrationSnapshot = null
        }
    }

    private fun resolveCurrentAnchor(cur: DetailMetaState): ReadingPositionAnchor? {
        if (cur.tid.isBlank() || cur.rawFloors.isEmpty()) return null
        return latestObservedAnchor?.takeIf { it.filter == cur.filter }
            ?: ReadingPositionAnchor(cur.page, cur.rawFloors.firstOrNull()?.postId, 0, cur.filter)
    }

    private fun pushCurrentAnchorForJump(explicitAnchor: ReadingPositionAnchor? = null) {
        val anchor = explicitAnchor ?: resolveCurrentAnchor(metaState.value) ?: return
        positionStack.push(anchor)
        metaState.update { it.copy(canPopPositionStack = positionStack.isNotEmpty) }
    }

    private fun toggleFavoriteKind(kind: FavoriteKind) {
        val cur = metaState.value
        val tid = cur.tid
        if (tid.isBlank()) return
        if (cur.currentUserUid.isNullOrBlank()) {
            emitLoginNotice()
            return
        }
        val previousActive = if (kind == FavoriteKind.FAVORITE) cur.isFavorited else cur.isAttention
        val targetActive = !previousActive
        val token = FavoriteUndoToken(++undoTokenSeq, kind, tid, previousActive, targetActive)
        enqueueFavoriteMutation(kind = kind, tid = tid, targetActive = targetActive, undoToken = token)
    }

    private fun enqueueFavoriteMutation(
        kind: FavoriteKind,
        tid: String,
        targetActive: Boolean,
        undoToken: FavoriteUndoToken?,
    ) {
        val repo = favoriteRepository ?: return
        // 发起新可撤销操作或执行撤销：替换当前有效令牌，旧操作即刻确认并立即关闭上一条 Snackbar
        activeUndoToken = undoToken
        val nextReset = ++snackbarResetSeq
        metaState.update { state ->
            state.copy(
                isFavorited = if (kind == FavoriteKind.FAVORITE) targetActive else state.isFavorited,
                isAttention = if (kind == FavoriteKind.ATTENTION) targetActive else state.isAttention,
                snackbarEvent = null,
                snackbarResetSeq = nextReset,
            )
        }
        val prevJob = if (kind == FavoriteKind.FAVORITE) favoriteJob else attentionJob
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            prevJob?.join()
            val res = if (targetActive) repo.add(kind, tid) else repo.remove(kind, tid)
            val thisJob = coroutineContext.job
            val isLatestForKind = if (kind == FavoriteKind.FAVORITE) favoriteJob === thisJob else attentionJob === thisJob
            val isCurrentToken = undoToken != null && activeUndoToken?.id == undoToken.id
            when (res) {
                is FavoriteMutationResult.Success -> {
                    if (isLatestForKind) syncFavoriteKindFromRepository(kind, tid)
                    if (isCurrentToken) {
                        metaState.update {
                            it.copy(
                                snackbarEvent = ThreadDetailSnackbarEvent.FavoriteUndoable(
                                    id = ++snackbarSeq, tokenId = undoToken.id, kind = kind, tid = tid, added = targetActive,
                                ),
                            )
                        }
                    }
                }
                is FavoriteError -> {
                    if (isCurrentToken) activeUndoToken = null
                    if (isLatestForKind) {
                        syncFavoriteKindFromRepository(kind, tid)
                        emitFavoriteError(res)
                    }
                }
            }
        }
        if (kind == FavoriteKind.FAVORITE) favoriteJob = job else attentionJob = job
        job.start()
    }

    private fun syncFavoriteKindFromRepository(kind: FavoriteKind, tid: String) {
        val repo = favoriteRepository ?: return
        metaState.update { cur ->
            if (cur.tid != tid) cur
            else when (kind) {
                FavoriteKind.FAVORITE -> cur.copy(isFavorited = repo.isFavorite(tid))
                FavoriteKind.ATTENTION -> cur.copy(isAttention = repo.isAttention(tid))
            }
        }
    }

    private fun emitLoginNotice() {
        metaState.update {
            it.copy(notice = ThreadDetailNotice(++noticeSeq, UiError(R.string.thread_detail_login_to_operate, action = UiErrorAction.Login)))
        }
    }

    private fun emitFavoriteError(error: FavoriteError) {
        val uiError = when (error) {
            is FavoriteError.AuthRequired -> UiError(R.string.thread_detail_login_to_operate, action = UiErrorAction.Login)
            is FavoriteError.NetworkError -> UiError(R.string.common_error_network, details = error.message.takeIf { it.isNotBlank() })
            is FavoriteError.ChallengeRequired -> UiError(
                if (error.rejected) R.string.challenge_status_rejected else R.string.challenge_status_incomplete,
            )
            is FavoriteError.SiteError -> UiError(R.string.common_error_load_failed, details = error.message.takeIf { it.isNotBlank() })
            is FavoriteError.InvalidPage -> UiError(R.string.common_error_load_failed, details = error.message.takeIf { it.isNotBlank() })
            is FavoriteError.InvalidInput -> UiError(R.string.common_error_load_failed, details = error.message.takeIf { it.isNotBlank() })
            is FavoriteError.StorageError -> UiError(R.string.common_error_load_failed, details = error.message.takeIf { it.isNotBlank() })
        }
        metaState.update { it.copy(notice = ThreadDetailNotice(++noticeSeq, uiError)) }
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
