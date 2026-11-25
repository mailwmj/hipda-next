package net.jejer.hipda.feature.threaddetail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.data.threaddetail.*
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.common.UiErrorMapper
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.ui.challenge.ChallengeFailureKind

@Immutable
data class ThreadDetailUiState(
    val tid: String = "",
    val title: String = "",
    val page: Int = 1,
    val lastPage: Int = 1,
    val floors: List<ThreadDetailFloorItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val challengeFailure: ChallengeFailureKind? = null,
    val challengeState: ChallengeState = ChallengeState.Idle,
)

private data class DetailMetaState(
    val tid: String = "",
    val title: String = "",
    val forumName: String? = null,
    val page: Int = 1,
    val lastPage: Int = 1,
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val challengeFailure: ChallengeFailureKind? = null,
)

/** 最薄真实帖子详情 ViewModel（由按 `tid` 托管的 `ViewModelStore` 持有，跨形态与旋转保活）。 */
@HiltViewModel
class ThreadDetailViewModel @Inject constructor(
    private val threadDetailRepository: ThreadDetailRepository,
    private val siteAccessGateway: SiteAccessGateway,
) : ViewModel() {

    private var boundTid: String? = null
    private val rawFloors = MutableStateFlow<List<PostFloor>>(emptyList())
    private val metaState = MutableStateFlow(DetailMetaState())

    val uiState: StateFlow<ThreadDetailUiState> = combine(
        metaState,
        threadDetailRepository.observeFloors(rawFloors),
        siteAccessGateway.challengeState,
    ) { m, floors, challenge ->
        ThreadDetailUiState(m.tid, m.title, m.page, m.lastPage, floors, m.isLoading, m.error, m.challengeFailure, challenge)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ThreadDetailUiState())

    fun bindArgs(args: ThreadNavArgs) {
        if (boundTid == args.tid) return
        boundTid = args.tid
        metaState.value = DetailMetaState(tid = args.tid, title = args.title, forumName = args.forumName, isLoading = true)
        loadPage(1)
    }

    fun loadPage(page: Int, priority: AccessPriority = AccessPriority.UserBlocking) {
        val current = metaState.value
        if (current.tid.isBlank()) return
        metaState.update { it.copy(isLoading = true, error = null, challengeFailure = null) }
        viewModelScope.launch {
            when (val res = threadDetailRepository.loadPage(current.tid, page.coerceAtLeast(1), forumName = current.forumName, priority = priority)) {
                is ThreadDetailLoadResult.Success -> {
                    rawFloors.value = res.page.floors
                    metaState.update {
                        it.copy(title = res.title.ifBlank { it.title }, page = res.pageNumber, lastPage = res.lastPage.coerceAtLeast(1), isLoading = false, error = null, challengeFailure = null)
                    }
                }
                is ThreadDetailError -> metaState.update {
                    it.copy(
                        isLoading = false,
                        error = UiErrorMapper.map(res),
                        challengeFailure = (res as? ThreadDetailError.ChallengeRequired)?.let { c -> if (c.rejected) ChallengeFailureKind.Rejected else ChallengeFailureKind.Incomplete },
                    )
                }
            }
        }
    }

    fun previousPage() { if (metaState.value.page > 1) loadPage(metaState.value.page - 1) }
    fun nextPage() { if (metaState.value.page < metaState.value.lastPage) loadPage(metaState.value.page + 1) }
    fun retry(priority: AccessPriority = AccessPriority.UserBlocking) = loadPage(metaState.value.page, priority)
}
