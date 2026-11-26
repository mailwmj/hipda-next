package net.jejer.hipda.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.user.*
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.state.*

/** 单次可撤销移出黑名单操作的独立令牌（记录目标用户名与前一个可见邻居锚点，不按下标恢复）。 */
@Immutable
data class BlacklistUndoToken(val id: Long, val username: String, val previousNeighbor: String?)

@Immutable
data class BlacklistUiState(
    val isLoggedIn: Boolean = false,
    val isSyncing: Boolean = false,
    val hasSynced: Boolean = false,
    val orderedUsernames: List<String> = emptyList(),
    val activeBlockedSet: Set<String> = emptySet(),
    val removingUsernames: Set<String> = emptySet(),
    val error: UiError? = null,
)

/** 黑名单管理 ViewModel：监听登录态与黑名单仓库，移出支持 0ms 原地进度圈、收起与按邻居锚点的令牌化撤销恢复。 */
class BlacklistViewModel(
    private val authRepository: AuthRepository,
    private val blacklistRepository: BlacklistRepository,
) : ViewModel() {

    private var latestUndoTokenId = 0L
    private var activeUndoToken: BlacklistUndoToken? = null
    private var pendingRestoreToken: BlacklistUndoToken? = null

    private val _uiState = MutableStateFlow(
        BlacklistUiState(
            isLoggedIn = authRepository.currentAccount.value != null,
            orderedUsernames = blacklistRepository.blockedUsernames.value.toList(),
            activeBlockedSet = blacklistRepository.blockedUsernames.value,
        ),
    )
    val uiState: StateFlow<BlacklistUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.currentAccount.collect { account ->
                val loggedIn = account != null
                if (!loggedIn) { activeUndoToken = null; pendingRestoreToken = null }
                _uiState.update { if (!loggedIn) BlacklistUiState(isLoggedIn = false) else it.copy(isLoggedIn = true, error = null) }
                if (loggedIn) sync(AccessPriority.UserBlocking)
            }
        }
        viewModelScope.launch {
            blacklistRepository.blockedUsernames.collect { blocked ->
                _uiState.update { cur ->
                    if (!cur.isLoggedIn) return@update cur
                    val restore = pendingRestoreToken?.takeIf { it.username in blocked }
                    val nextOrdered = if (restore != null) { pendingRestoreToken = null; insertAtAnchor(cur.orderedUsernames, blocked, restore) }
                    else cur.orderedUsernames + blocked.filterNot { it in cur.orderedUsernames }
                    cur.copy(orderedUsernames = nextOrdered, activeBlockedSet = blocked)
                }
            }
        }
    }

    fun sync(priority: AccessPriority = AccessPriority.UserBlocking) {
        if (!_uiState.value.isLoggedIn || _uiState.value.isSyncing) return
        _uiState.update { it.copy(isSyncing = true, error = null) }
        viewModelScope.launch {
            when (val res = runCatchingResult { blacklistRepository.syncBlacklist(priority) }) {
                is BlacklistSyncResult.Success -> _uiState.update { it.copy(isSyncing = false, hasSynced = true, orderedUsernames = res.usernames.toList(), activeBlockedSet = res.usernames, error = null) }
                is UserError -> _uiState.update { it.copy(isSyncing = false, hasSynced = true, error = mapUserErrorToUiError(res)) }
            }
        }
    }

    fun removeUser(username: String, onRemoved: (BlacklistUndoToken) -> Unit, onFailed: (UiError) -> Unit) {
        val state = _uiState.value
        if (username in state.removingUsernames) return
        activeUndoToken = null
        val visible = state.orderedUsernames.filter { it in state.activeBlockedSet }
        val prevNeighbor = visible.indexOf(username).let { idx -> if (idx > 0) visible[idx - 1] else null }
        val token = BlacklistUndoToken(++latestUndoTokenId, username, prevNeighbor)
        _uiState.update { it.copy(removingUsernames = it.removingUsernames + username) }
        viewModelScope.launch {
            val res = runCatchingResult { blacklistRepository.removeFromBlacklist(username) }.also { _uiState.update { s -> s.copy(removingUsernames = s.removingUsernames - username) } }
            when (res) {
                is BlacklistMutationResult.Success -> if (token.id == latestUndoTokenId) { activeUndoToken = token; onRemoved(token) }
                is UserError -> onFailed(mapUserErrorToUiError(res))
            }
        }
    }

    fun restoreUser(token: BlacklistUndoToken, onFailed: (UiError) -> Unit) {
        if (activeUndoToken?.id != token.id) return
        activeUndoToken = null
        pendingRestoreToken = token
        viewModelScope.launch {
            when (val res = runCatchingResult { blacklistRepository.addToBlacklist(token.username) }) {
                is BlacklistMutationResult.Success -> _uiState.update { cur ->
                    val applied = if (pendingRestoreToken?.id == token.id) { pendingRestoreToken = null; insertAtAnchor(cur.orderedUsernames, cur.activeBlockedSet + token.username, token) } else cur.orderedUsernames
                    cur.copy(orderedUsernames = applied, activeBlockedSet = cur.activeBlockedSet + token.username)
                }
                is UserError -> { if (pendingRestoreToken?.id == token.id) pendingRestoreToken = null; onFailed(mapUserErrorToUiError(res)) }
            }
        }
    }

    private inline fun <T> runCatchingResult(block: () -> T): Any? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { UserError.NetworkError("") }

    private fun insertAtAnchor(currentOrdered: List<String>, activeBlocked: Set<String>, token: BlacklistUndoToken): List<String> {
        val visibleWithout = currentOrdered.filter { it in activeBlocked && it != token.username }
        val base = visibleWithout + activeBlocked.filterNot { it in visibleWithout || it == token.username }
        val insertIdx = token.previousNeighbor?.let { prev -> base.indexOf(prev).let { if (it >= 0) it + 1 else 0 } } ?: 0
        return base.toMutableList().apply { add(insertIdx, token.username) }
    }
}

/** 黑名单管理页（纯色顶栏零毛玻璃；未登录原地提示登录；移除原地 0ms 进度圈 + 收起 + 令牌化可撤销 Snackbar）。 */
@Composable
fun BlacklistScreen(viewModel: BlacklistViewModel, onBack: () -> Unit, onOpenLogin: () -> Unit, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalPaneSnackbarController.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val removedFmt = stringResource(R.string.settings_blacklist_removed_snackbar)
    val undoLabel = stringResource(R.string.settings_action_undo)
    var activeSnackbarJob by remember { mutableStateOf<Job?>(null) }
    fun showErr(err: UiError) { activeSnackbarJob?.cancel(); activeSnackbarJob = scope.launch { snackbar.show(context.getString(err.messageRes), PaneSnackbarTarget.ContentArea) } }

    BlacklistScreenContent(
        uiState, onBack, onOpenLogin, viewModel::sync,
        onRemoveUser = { username ->
            activeSnackbarJob?.cancel(); activeSnackbarJob = null
            viewModel.removeUser(username, onRemoved = { token ->
                activeSnackbarJob?.cancel()
                activeSnackbarJob = scope.launch { snackbar.show(String.format(removedFmt, token.username), PaneSnackbarTarget.ContentArea, undoLabel) { viewModel.restoreUser(token, ::showErr) } }
            }, onFailed = ::showErr)
        },
        contentPadding, modifier,
    )
}

@Composable
fun BlacklistScreenContent(
    uiState: BlacklistUiState,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    onRetry: (AccessPriority) -> Unit,
    onRemoveUser: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val scrolled by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    val removingCd = stringResource(R.string.settings_blacklist_removing_cd)

    Column(modifier.fillMaxSize().background(cs.surface)) {
        SettingsSolidTopBar(stringResource(R.string.settings_blacklist_title), scrolled, showBack = true, onBack = onBack)
        if (!uiState.isLoggedIn) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(contentPadding), Alignment.Center) {
                HiEmptyState(stringResource(R.string.common_error_login_required), action = HiStateAction(stringResource(R.string.common_action_login), onOpenLogin))
            }
            return
        }
        val loadState: HiLoadState<List<String>> = when {
            uiState.activeBlockedSet.isNotEmpty() -> HiLoadState.Content(uiState.orderedUsernames)
            uiState.error != null -> HiLoadState.Error(stringResource(uiState.error.messageRes), uiState.error.details)
            uiState.isSyncing && !uiState.hasSynced -> HiLoadState.Loading
            else -> HiLoadState.Empty
        }
        HiContentState(
            state = loadState, modifier = Modifier.weight(1f).fillMaxWidth(),
            skeleton = {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(4) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { HiSkeletonBlock(40.dp, 40.dp, HiShapeTokens.Full); HiSkeletonBlock(140.dp, 18.dp); Spacer(Modifier.weight(1f)); HiSkeletonBlock(96.dp, 36.dp) } }
                }
            },
            empty = { HiEmptyState(stringResource(R.string.settings_blacklist_empty)) },
            error = { err -> HiErrorState(err.message, action = uiState.error?.toStateAction(onRetry, onOpenLogin), details = err.details) },
        ) { ordered ->
            LazyColumn(state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp), modifier = Modifier.fillMaxSize()) {
                items(ordered, key = { it }) { username ->
                    HiExpandableSection(username in uiState.activeBlockedSet) {
                        val isRemoving = username in uiState.removingUsernames
                        val interaction = remember { MutableInteractionSource() }
                        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(40.dp).clip(HiShapeTokens.Full).background(cs.secondaryContainer), Alignment.Center) {
                                Text(username.trim().firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleSmall, color = cs.onSecondaryContainer)
                            }
                            Text(username, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            OutlinedButton(
                                { onRemoveUser(username) },
                                Modifier.defaultMinSize(104.dp, 48.dp).hiPressScale(interaction).then(if (isRemoving) Modifier.semantics { contentDescription = removingCd } else Modifier),
                                enabled = !isRemoving, shape = HiShapeTokens.Small, interactionSource = interaction,
                            ) {
                                if (isRemoving) HiLoadingSpinner(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.settings_blacklist_remove), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
