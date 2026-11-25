package net.jejer.hipda.feature.forum

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.threadlist.*
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.common.UiErrorMapper
import net.jejer.hipda.ui.challenge.ChallengeFailureKind
import net.jejer.hipda.ui.shell.ForumMetadataRegistry

@Immutable
data class ForumUiState(
    val fid: Int = AppSettings.DEFAULT_FOLLOWED_FORUMS.first(),
    val forumName: String = ForumMetadataRegistry.resolve(AppSettings.DEFAULT_FOLLOWED_FORUMS.first()).fullName,
    val followedForums: List<Int> = AppSettings.DEFAULT_FOLLOWED_FORUMS,
    val sortByPostTime: Boolean = false,
    val items: List<ThreadListItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: UiError? = null,
    val challengeFailure: ChallengeFailureKind? = null,
    val challengeState: ChallengeState = ChallengeState.Idle,
)

/** 最薄真实版块列表 ViewModel（遵守 `R-D4 S1`，显式传入 `AppSettings.isSortByPostTime(fid)`）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ForumViewModel @Inject constructor(
    private val threadListRepository: ThreadListRepository,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val siteAccessGateway: SiteAccessGateway,
) : ViewModel() {

    private val defaultFid = AppSettings.DEFAULT_FOLLOWED_FORUMS.first()
    private val activeFid = MutableStateFlow(defaultFid)
    private val latestSettings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val activeQuery: StateFlow<ThreadListQuery> = combine(activeFid, latestSettings) { fid, s ->
        ThreadListQuery(fid = fid, sortByPostTime = s.isSortByPostTime(fid))
    }.distinctUntilChanged().stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ThreadListQuery(defaultFid, AppSettings().isSortByPostTime(defaultFid)),
    )
    private val initializedQueries = HashSet<ThreadListQuery>()

    val uiState: StateFlow<ForumUiState> = combine(
        activeQuery.flatMapLatest(threadListRepository::observeThreadList),
        latestSettings,
        siteAccessGateway.challengeState,
    ) { load, settings, challengeState ->
        val fid = load.query.fid
        val rawErr = load.error
        ForumUiState(
            fid = fid,
            forumName = ForumMetadataRegistry.resolve(fid).fullName,
            followedForums = settings.followedForums,
            sortByPostTime = load.query.sortByPostTime ?: settings.isSortByPostTime(fid),
            items = load.items,
            isLoading = load.isLoading,
            isLoadingMore = load.isLoadingMore,
            hasMore = load.hasMore,
            error = rawErr?.let(UiErrorMapper::map),
            challengeFailure = (rawErr as? ThreadListError.ChallengeRequired)?.let { if (it.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete },
            challengeState = challengeState,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ForumUiState())

    init {
        viewModelScope.launch { activeQuery.collect { q -> if (initializedQueries.add(q)) threadListRepository.refresh(q) } }
        viewModelScope.launch {
            var lastUid = authRepository.currentAccount.value?.uid
            authRepository.currentAccount.map { it?.uid }.distinctUntilChanged().collect { uid ->
                if (uid != lastUid) {
                    lastUid = uid
                    initializedQueries.clear()
                    val q = activeQuery.value
                    initializedQueries.add(q)
                    threadListRepository.refresh(q)
                }
            }
        }
    }

    fun bindForum(fid: Int) { if (activeFid.value != fid) activeFid.value = fid }
    fun refresh(priority: AccessPriority = AccessPriority.UserBlocking) {
        val q = activeQuery.value
        initializedQueries.add(q)
        viewModelScope.launch { threadListRepository.refresh(q, priority = priority) }
    }
    fun loadMore(priority: AccessPriority = AccessPriority.UserBlocking) {
        val cur = uiState.value
        if (!cur.isLoading && !cur.isLoadingMore && cur.hasMore) {
            viewModelScope.launch { threadListRepository.loadMore(activeQuery.value, priority = priority) }
        }
    }
    fun retry(priority: AccessPriority = AccessPriority.UserBlocking) = refresh(priority)
}
