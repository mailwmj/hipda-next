package net.jejer.hipda.feature.profile

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.user.*
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.settings.mapUserErrorToUiError
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.ui.designsystem.state.HiLoadState

enum class UserProfileTab { Info, Threads }

/** 用户资料页单次可撤销拉黑/移出操作的独立令牌。 */
@Immutable
data class ProfileBlacklistUndoToken(val id: Long, val username: String, val wasAdded: Boolean)

@Immutable
data class UserProfileUiState(
    val uid: String,
    val displayUsername: String,
    val avatarUrl: String? = null,
    val isOnline: Boolean = false,
    val headerStats: List<String> = emptyList(),
    val selectedTab: UserProfileTab = UserProfileTab.Info,
    val profileLoadState: HiLoadState<ParsedProfileDetails> = HiLoadState.Loading,
    val profileError: UiError? = null,
    val threadsLoadState: HiLoadState<List<SearchItem>> = HiLoadState.Loading,
    val threadsError: UiError? = null,
    val hasLoadedThreads: Boolean = false,
    val hasNextThreadsPage: Boolean = false,
    val isLoadingNextThreadsPage: Boolean = false,
    val nextThreadsPageFailed: Boolean = false,
    val isBlocked: Boolean = false,
    val isBlacklistMutating: Boolean = false,
)

/** 用户资料页 ViewModel：`资料` 与 `TA 的帖子` 各自维护独立加载/空/错态，黑名单操作 0ms 原地进度圈并按令牌撤销。 */
class UserProfileViewModel(
    private val uid: String,
    initialUsername: String,
    private val userRepository: UserRepository,
    private val blacklistRepository: BlacklistRepository,
) : ViewModel() {

    private var profileFormhash: String? = null
    private var threadsSearchId: String? = null
    private var threadsPage = 0
    private var loadingProfile = false
    private var loadingThreads = false
    private var latestUndoTokenId = 0L
    private var activeUndoToken: ProfileBlacklistUndoToken? = null

    private val initialName = initialUsername.trim()
    private val _uiState = MutableStateFlow(UserProfileUiState(uid, initialName, isBlocked = initialName.isNotEmpty() && blacklistRepository.isBlocked(initialName)))
    val uiState: StateFlow<UserProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            blacklistRepository.blockedUsernames.collect { blocked ->
                _uiState.update { cur -> cur.copy(isBlocked = cur.displayUsername.isNotEmpty() && cur.displayUsername in blocked) }
            }
        }
        loadProfile()
    }

    fun selectTab(tab: UserProfileTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        if (tab == UserProfileTab.Threads && !_uiState.value.hasLoadedThreads && !loadingThreads) loadThreadsFirstPage()
    }

    fun loadProfile(priority: AccessPriority = AccessPriority.UserBlocking) {
        if (loadingProfile) return
        loadingProfile = true
        _uiState.update { it.copy(profileLoadState = HiLoadState.Loading, profileError = null) }
        viewModelScope.launch {
            val res = runUserCall { userRepository.getUserProfile(uid, priority) }.also { loadingProfile = false }
            when (res) {
                is UserProfileResult.Success -> {
                    val p = res.profile
                    profileFormhash = p.formhash
                    val name = p.username?.trim()?.takeIf(String::isNotEmpty) ?: _uiState.value.displayUsername
                    val parsed = parseProfileDetails(p.detail)
                    _uiState.update {
                        it.copy(
                            displayUsername = name, avatarUrl = ProfileUrlSanitizer.sanitizeProfileImageUrl(p.avatarUrl),
                            isOnline = p.isOnline, headerStats = parsed.headerStats,
                            profileLoadState = if (parsed.sections.isEmpty()) HiLoadState.Empty else HiLoadState.Content(parsed),
                            profileError = null, isBlocked = name.isNotEmpty() && name in blacklistRepository.blockedUsernames.value,
                        )
                    }
                }
                is UserError -> mapUserErrorToUiError(res).let { mapped -> _uiState.update { it.copy(profileLoadState = HiLoadState.Error("", mapped.details), profileError = mapped) } }
            }
        }
    }

    fun loadThreadsFirstPage(priority: AccessPriority = AccessPriority.UserBlocking) {
        if (loadingThreads) return
        loadingThreads = true; threadsSearchId = null; threadsPage = 0
        _uiState.update { it.copy(threadsLoadState = HiLoadState.Loading, threadsError = null, isLoadingNextThreadsPage = false, nextThreadsPageFailed = false) }
        viewModelScope.launch {
            val res = runUserCall { userRepository.getUserThreads(uid, 1, null, priority) }.also { loadingThreads = false }
            when (res) {
                is UserThreadsResult.Success -> {
                    threadsSearchId = res.searchId; threadsPage = res.page
                    _uiState.update { it.copy(threadsLoadState = if (res.items.isEmpty()) HiLoadState.Empty else HiLoadState.Content(res.items), threadsError = null, hasLoadedThreads = true, hasNextThreadsPage = res.hasNextPage, isLoadingNextThreadsPage = false, nextThreadsPageFailed = false) }
                }
                is UserError -> mapUserErrorToUiError(res).let { mapped -> _uiState.update { it.copy(threadsLoadState = HiLoadState.Error("", mapped.details), threadsError = mapped, hasLoadedThreads = true, hasNextThreadsPage = false, isLoadingNextThreadsPage = false) } }
            }
        }
    }

    fun loadNextThreadsPage() {
        val s = _uiState.value
        val existing = (s.threadsLoadState as? HiLoadState.Content<List<SearchItem>>)?.value ?: return
        if (loadingThreads || s.isLoadingNextThreadsPage || !s.hasNextThreadsPage) return
        loadingThreads = true
        _uiState.update { it.copy(isLoadingNextThreadsPage = true, nextThreadsPageFailed = false) }
        viewModelScope.launch {
            val res = runUserCall { userRepository.getUserThreads(uid, threadsPage + 1, threadsSearchId) }.also { loadingThreads = false }
            when (res) {
                is UserThreadsResult.Success -> {
                    threadsSearchId = res.searchId ?: threadsSearchId; threadsPage = res.page
                    val keys = existing.map { "${it.tid}:${it.pid}" }.toHashSet()
                    _uiState.update { it.copy(threadsLoadState = HiLoadState.Content(existing + res.items.filter { keys.add("${it.tid}:${it.pid}") }), hasNextThreadsPage = res.hasNextPage, isLoadingNextThreadsPage = false) }
                }
                is UserError -> _uiState.update { it.copy(isLoadingNextThreadsPage = false, nextThreadsPageFailed = true) }
            }
        }
    }

    fun toggleBlacklist(onSuccess: (ProfileBlacklistUndoToken) -> Unit, onError: (UiError) -> Unit) {
        val s = _uiState.value
        val target = s.displayUsername.trim()
        if (s.isBlacklistMutating || target.isEmpty()) return
        activeUndoToken = null
        val adding = !s.isBlocked
        val tokenId = ++latestUndoTokenId
        _uiState.update { it.copy(isBlacklistMutating = true) }
        mutateBlacklist(adding, target, { _uiState.update { cur -> cur.copy(isBlacklistMutating = false) } }, { confirmed ->
            if (tokenId == latestUndoTokenId) ProfileBlacklistUndoToken(tokenId, confirmed, adding).also { activeUndoToken = it; onSuccess(it) }
        }, onError)
    }

    fun undoBlacklistMutation(token: ProfileBlacklistUndoToken, onError: (UiError) -> Unit) {
        if (activeUndoToken?.id != token.id) return
        activeUndoToken = null
        mutateBlacklist(!token.wasAdded, token.username, {}, {}, onError)
    }

    private fun mutateBlacklist(adding: Boolean, target: String, onComplete: () -> Unit, onSuccess: (String) -> Unit, onError: (UiError) -> Unit) {
        viewModelScope.launch {
            val res = runUserCall { if (adding) blacklistRepository.addToBlacklist(target, profileFormhash) else blacklistRepository.removeFromBlacklist(target, profileFormhash) }.also { onComplete() }
            when (res) {
                is BlacklistMutationResult.Success -> onSuccess(res.username)
                is UserError -> onError(mapUserErrorToUiError(res))
            }
        }
    }

    private inline fun <T> runUserCall(block: () -> T): Any? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { UserError.NetworkError("") }
}
