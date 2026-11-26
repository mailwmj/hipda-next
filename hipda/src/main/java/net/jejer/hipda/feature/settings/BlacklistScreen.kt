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

/** 黑名单管理 ViewModel：监听登录态与黑名单仓库，移出支持 0ms 原地进度圈、收起与撤销恢复。 */
class BlacklistViewModel(
    private val authRepository: AuthRepository,
    private val blacklistRepository: BlacklistRepository,
) : ViewModel() {

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
                _uiState.update { if (!loggedIn) BlacklistUiState(isLoggedIn = false) else it.copy(isLoggedIn = true, error = null) }
                if (loggedIn) sync(AccessPriority.UserBlocking)
            }
        }
        viewModelScope.launch {
            blacklistRepository.blockedUsernames.collect { blocked ->
                _uiState.update { cur ->
                    if (!cur.isLoggedIn) cur
                    else cur.copy(orderedUsernames = cur.orderedUsernames + blocked.filterNot { it in cur.orderedUsernames }, activeBlockedSet = blocked)
                }
            }
        }
    }

    fun sync(priority: AccessPriority = AccessPriority.UserBlocking) {
        if (!_uiState.value.isLoggedIn || _uiState.value.isSyncing) return
        _uiState.update { it.copy(isSyncing = true, error = null) }
        viewModelScope.launch {
            val result = try { blacklistRepository.syncBlacklist(priority) } catch (e: CancellationException) { throw e } catch (_: Exception) { UserError.NetworkError("") }
            when (result) {
                is BlacklistSyncResult.Success -> _uiState.update { it.copy(isSyncing = false, hasSynced = true, orderedUsernames = result.usernames.toList(), activeBlockedSet = result.usernames, error = null) }
                is UserError -> _uiState.update { it.copy(isSyncing = false, hasSynced = true, error = mapUserErrorToUiError(result)) }
            }
        }
    }

    fun removeUser(username: String, onRemoved: () -> Unit, onFailed: (UiError) -> Unit) {
        if (username in _uiState.value.removingUsernames) return
        _uiState.update { it.copy(removingUsernames = it.removingUsernames + username) }
        viewModelScope.launch {
            val result = try { blacklistRepository.removeFromBlacklist(username) } catch (e: CancellationException) { throw e } catch (_: Exception) { UserError.NetworkError("") }
                .also { _uiState.update { s -> s.copy(removingUsernames = s.removingUsernames - username) } }
            when (result) {
                is BlacklistMutationResult.Success -> onRemoved()
                is UserError -> onFailed(mapUserErrorToUiError(result))
            }
        }
    }

    fun restoreUser(username: String, onFailed: (UiError) -> Unit) {
        viewModelScope.launch {
            val result = try { blacklistRepository.addToBlacklist(username) } catch (e: CancellationException) { throw e } catch (_: Exception) { UserError.NetworkError("") }
            if (result is UserError) onFailed(mapUserErrorToUiError(result))
        }
    }
}

/** 黑名单管理页（纯色顶栏零毛玻璃；未登录原地提示登录；移除原地 0ms 进度圈 + 收起 + 可撤销 Snackbar）。 */
@Composable
fun BlacklistScreen(
    viewModel: BlacklistViewModel,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalPaneSnackbarController.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val removedFmt = stringResource(R.string.settings_blacklist_removed_snackbar)
    val undoLabel = stringResource(R.string.settings_action_undo)
    fun showErr(err: UiError) { scope.launch { snackbar.show(context.getString(err.messageRes), PaneSnackbarTarget.ContentArea) } }

    BlacklistScreenContent(
        uiState = uiState, onBack = onBack, onOpenLogin = onOpenLogin, onRetry = viewModel::sync,
        onRemoveUser = { username ->
            viewModel.removeUser(
                username = username,
                onRemoved = { scope.launch { snackbar.show(String.format(removedFmt, username), PaneSnackbarTarget.ContentArea, undoLabel) { viewModel.restoreUser(username, ::showErr) } } },
                onFailed = ::showErr,
            )
        },
        contentPadding = contentPadding, modifier = modifier,
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
                        BlacklistUserRow(username, username in uiState.removingUsernames) { onRemoveUser(username) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlacklistUserRow(username: String, isRemoving: Boolean, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val removingCd = stringResource(R.string.settings_blacklist_removing_cd)
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(HiShapeTokens.Full).background(cs.secondaryContainer), Alignment.Center) {
            Text(username.trim().firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleSmall, color = cs.onSecondaryContainer)
        }
        Text(username, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = onRemove, enabled = !isRemoving, shape = HiShapeTokens.Small, interactionSource = interaction,
            modifier = Modifier.defaultMinSize(104.dp, 48.dp).hiPressScale(interaction).then(if (isRemoving) Modifier.semantics { contentDescription = removingCd } else Modifier),
        ) {
            if (isRemoving) HiLoadingSpinner(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.settings_blacklist_remove), maxLines = 1)
        }
    }
}
