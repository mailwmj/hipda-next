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

/** 用户资料页 ViewModel：`资料` 与 `TA 的帖子` 各自维护独立加载/空/错态，黑名单操作 0ms 原地进度圈并可撤销。 */
class UserProfileViewModel(
    private val uid: String,
    initialUsername: String,
    private val userRepository: UserRepository,
    private val blacklistRepository: BlacklistRepository,
) : ViewModel() {

    private var profileFormhash: String? = null
    private var threadsSearchId: String? = null
    private var threadsPage: Int = 0
    private var loadingProfile: Boolean = false
    private var loadingThreads: Boolean = false

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
            val result = try { userRepository.getUserProfile(uid, priority) } catch (e: CancellationException) { throw e } catch (_: Exception) { UserError.NetworkError("") }
                .also { loadingProfile = false }
            when (result) {
                is UserProfileResult.Success -> {
                    val p = result.profile
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
                is UserError -> mapUserErrorToUiError(result).let { mapped -> _uiState.update { it.copy(profileLoadState = HiLoadState.Error("", mapped.details), profileError = mapped) } }
            }
        }
    }

    fun loadThreadsFirstPage(priority: AccessPriority = AccessPriority.UserBlocking) {
        if (loadingThreads) return
        loadingThreads = true; threadsSearchId = null; threadsPage = 0
        _uiState.update { it.copy(threadsLoadState = HiLoadState.Loading, threadsError = null, isLoadingNextThreadsPage = false, nextThreadsPageFailed = false) }
        viewModelScope.launch {
            val result = try { userRepository.getUserThreads(uid, 1, null, priority) } catch (e: CancellationException) { throw e } catch (_: Exception) { UserError.NetworkError("") }
                .also { loadingThreads = false }
            when (result) {
                is UserThreadsResult.Success -> {
                    threadsSearchId = result.searchId; threadsPage = result.page
                    _uiState.update {
                        it.copy(
                            threadsLoadState = if (result.items.isEmpty()) HiLoadState.Empty else HiLoadState.Content(result.items),
                            threadsError = null, hasLoadedThreads = true, hasNextThreadsPage = result.hasNextPage,
                            isLoadingNextThreadsPage = false, nextThreadsPageFailed = false,
                        )
                    }
                }
                is UserError -> mapUserErrorToUiError(result).let { mapped ->
                    _uiState.update { it.copy(threadsLoadState = HiLoadState.Error("", mapped.details), threadsError = mapped, hasLoadedThreads = true, hasNextThreadsPage = false, isLoadingNextThreadsPage = false) }
                }
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
            val result = try { userRepository.getUserThreads(uid, threadsPage + 1, threadsSearchId) } catch (e: CancellationException) { throw e } catch (_: Exception) { UserError.NetworkError("") }
                .also { loadingThreads = false }
            when (result) {
                is UserThreadsResult.Success -> {
                    threadsSearchId = result.searchId ?: threadsSearchId; threadsPage = result.page
                    val keys = existing.map { "${it.tid}:${it.pid}" }.toHashSet()
                    _uiState.update { it.copy(threadsLoadState = HiLoadState.Content(existing + result.items.filter { keys.add("${it.tid}:${it.pid}") }), hasNextThreadsPage = result.hasNextPage, isLoadingNextThreadsPage = false) }
                }
                is UserError -> _uiState.update { it.copy(isLoadingNextThreadsPage = false, nextThreadsPageFailed = true) }
            }
        }
    }

    fun toggleBlacklist(onSuccess: (added: Boolean, username: String) -> Unit, onError: (UiError) -> Unit) {
        val s = _uiState.value
        val target = s.displayUsername.trim()
        if (s.isBlacklistMutating || target.isEmpty()) return
        val adding = !s.isBlocked
        _uiState.update { it.copy(isBlacklistMutating = true) }
        mutateBlacklist(adding, target, onComplete = { _uiState.update { cur -> cur.copy(isBlacklistMutating = false) } }, onSuccess = { onSuccess(adding, it) }, onError = onError)
    }

    fun undoBlacklistMutation(wasAdded: Boolean, username: String, onError: (UiError) -> Unit) =
        mutateBlacklist(adding = !wasAdded, target = username, onComplete = {}, onSuccess = {}, onError = onError)

    private fun mutateBlacklist(adding: Boolean, target: String, onComplete: () -> Unit, onSuccess: (String) -> Unit, onError: (UiError) -> Unit) {
        viewModelScope.launch {
            val result = try { if (adding) blacklistRepository.addToBlacklist(target, profileFormhash) else blacklistRepository.removeFromBlacklist(target, profileFormhash) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { UserError.NetworkError("") }
                .also { onComplete() }
            when (result) {
                is BlacklistMutationResult.Success -> onSuccess(result.username)
                is UserError -> onError(mapUserErrorToUiError(result))
            }
        }
    }
}
