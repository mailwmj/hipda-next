package net.jejer.hipda.feature.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.hiFullscreenOverlaySafePadding
import net.jejer.hipda.ui.designsystem.feedback.hiListItemPressIndication
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner

private val ACCOUNT_MODAL_WIDTH_BREAKPOINT = 600.dp
private val ACCOUNT_MODAL_HEIGHT_BREAKPOINT = 480.dp
private val ACCOUNT_MAX_WIDTH = 480.dp
private const val ACCOUNT_SCRIM_ALPHA = 0.40f

@Immutable
data class AccountManagementUiState(
    val currentAccount: SavedAccount? = null,
    val savedAccounts: List<SavedAccount> = emptyList(),
    val switchingUid: String? = null,
    val removingUid: String? = null,
    val isLoggingOut: Boolean = false,
    val isClearingCredentials: Boolean = false,
    @param:StringRes val errorRes: Int? = null,
    val errorArgs: List<Any> = emptyList(),
) {
    val isBusy: Boolean get() = switchingUid != null || removingUid != null || isLoggingOut || isClearingCredentials
}

/** 正式账号管理 ViewModel：切号免确认、退出登录免确认保留凭据、清除登录信息删除保存密码并退出。 */
class AccountManagementViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AccountManagementUiState(authRepository.currentAccount.value, mergeAccounts(authRepository.savedAccounts.value, authRepository.currentAccount.value)),
    )
    val uiState: StateFlow<AccountManagementUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(authRepository.currentAccount, authRepository.savedAccounts) { cur, saved -> cur to mergeAccounts(saved, cur) }
                .collect { (cur, merged) -> _uiState.update { it.copy(currentAccount = cur, savedAccounts = merged) } }
        }
    }

    fun switchAccount(uid: String, snackbar: PaneSnackbarController, switchedTemplate: String, onDone: () -> Unit) {
        val s = _uiState.value
        if (s.isBusy || s.currentAccount?.uid == uid) return
        _uiState.update { it.copy(switchingUid = uid, errorRes = null, errorArgs = emptyList()) }
        viewModelScope.launch {
            val result = try { authRepository.switchAccount(uid) } catch (e: CancellationException) { throw e } catch (_: Exception) { LoginResult.NetworkError("") }
                .also { _uiState.update { cur -> cur.copy(switchingUid = null) } }
            when (result) {
                is LoginResult.Success -> { onDone(); snackbar.show(String.format(switchedTemplate, result.account.username), PaneSnackbarTarget.ContentArea) }
                else -> {
                    val mapped = UiErrorMapper.mapLoginResult(result)
                    _uiState.update {
                        it.copy(
                            errorRes = mapped?.messageRes ?: R.string.common_error_load_failed,
                            errorArgs = mapped?.formatArgs.orEmpty(),
                        )
                    }
                }
            }
        }
    }

    fun logout(snackbar: PaneSnackbarController, loggedOutMessage: String, onDone: () -> Unit) =
        runAccountAction({ copy(isLoggingOut = it) }, snackbar, loggedOutMessage, onDone) { authRepository.logout() }

    fun clearCredentialsAndLogout(uid: String, snackbar: PaneSnackbarController, loggedOutMessage: String, onDone: () -> Unit) =
        runAccountAction({ copy(isClearingCredentials = it) }, snackbar, loggedOutMessage, onDone) { authRepository.removeAccount(uid) }

    fun removeSavedAccount(account: SavedAccount, snackbar: PaneSnackbarController, removedTemplate: String) {
        val s = _uiState.value
        if (s.isBusy || s.currentAccount?.uid == account.uid) return
        _uiState.update { it.copy(removingUid = account.uid, errorRes = null, errorArgs = emptyList()) }
        viewModelScope.launch {
            val ok = try {
                authRepository.removeAccount(account.uid)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(errorRes = R.string.common_error_load_failed, errorArgs = emptyList()) }
                false
            }.also { _uiState.update { cur -> cur.copy(removingUid = null) } }
            if (ok) snackbar.show(String.format(removedTemplate, account.username), PaneSnackbarTarget.ContentArea)
        }
    }

    private fun runAccountAction(markBusy: AccountManagementUiState.(Boolean) -> AccountManagementUiState, snackbar: PaneSnackbarController, doneMessage: String, onDone: () -> Unit, block: suspend () -> Unit) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.markBusy(true).copy(errorRes = null, errorArgs = emptyList()) }
        viewModelScope.launch {
            val ok = try { block(); true } catch (e: CancellationException) { throw e } catch (_: Exception) { _uiState.update { it.copy(errorRes = R.string.common_error_load_failed, errorArgs = emptyList()) }; false }
                .also { _uiState.update { it.markBusy(false) } }
            if (ok) { onDone(); snackbar.show(doneMessage, PaneSnackbarTarget.ContentArea) }
        }
    }

    private companion object {
        fun mergeAccounts(saved: List<SavedAccount>, current: SavedAccount?): List<SavedAccount> =
            if (current == null || saved.any { it.uid == current.uid }) saved else listOf(current) + saved
    }
}

/** 正式账号管理浮层（接管 `LoginOverlayMode.AccountMenu`，收口 `R-E15 S2`；零毛玻璃）。 */
@Composable
fun AccountManagementOverlay(viewModel: AccountManagementViewModel, onAddAccount: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalPaneSnackbarController.current
    val switchedFmt = stringResource(R.string.settings_account_switched)
    val loggedOutMsg = stringResource(R.string.settings_account_logged_out)
    val removedFmt = stringResource(R.string.settings_account_removed)
    AccountManagementContent(
        uiState = uiState,
        onSwitchAccount = { viewModel.switchAccount(it, snackbar, switchedFmt, onDismiss) },
        onAddAccount = onAddAccount,
        onLogout = { viewModel.logout(snackbar, loggedOutMsg, onDismiss) },
        onClearCredentials = { viewModel.clearCredentialsAndLogout(it, snackbar, loggedOutMsg, onDismiss) },
        onDismiss = onDismiss,
        onRemoveSavedAccount = { viewModel.removeSavedAccount(it, snackbar, removedFmt) },
        modifier = modifier,
    )
}

@Composable
fun AccountManagementContent(
    uiState: AccountManagementUiState,
    onSwitchAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onLogout: () -> Unit,
    onClearCredentials: (String) -> Unit,
    onDismiss: () -> Unit,
    onRemoveSavedAccount: (SavedAccount) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val windowSize = LocalWindowInfo.current.containerDpSize
    val isModalCard = windowSize.width >= ACCOUNT_MODAL_WIDTH_BREAKPOINT && windowSize.height >= ACCOUNT_MODAL_HEIGHT_BREAKPOINT
    val title = stringResource(R.string.settings_account_title)
    var confirmClear by remember { mutableStateOf<SavedAccount?>(null) }
    var confirmRemoveSaved by remember { mutableStateOf<SavedAccount?>(null) }
    BackHandler(enabled = !uiState.isBusy, onBack = onDismiss)

    val panel: @Composable (Modifier) -> Unit = { mod ->
        val navInt = remember { MutableInteractionSource() }
        val btnMod = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(navInt)
        Column(mod) {
            Row(Modifier.fillMaxWidth().padding(horizontal = if (isModalCard) 20.dp else 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isModalCard) IconButton(onDismiss, btnMod, enabled = !uiState.isBusy, interactionSource = navInt) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_action_back)) }
                Text(title, style = MaterialTheme.typography.titleLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = if (isModalCard) 0.dp else 8.dp).semantics { heading() })
                if (isModalCard) IconButton(onDismiss, btnMod, enabled = !uiState.isBusy, interactionSource = navInt) { Icon(Icons.Rounded.Close, stringResource(R.string.settings_action_close)) }
            }
            HorizontalDivider(color = cs.outlineVariant)
            Box(if (isModalCard) Modifier.fillMaxWidth() else Modifier.weight(1f).fillMaxWidth(), Alignment.TopCenter) {
                Column((if (isModalCard) Modifier else Modifier.widthIn(max = ACCOUNT_MAX_WIDTH)).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    uiState.errorRes?.let { errRes ->
                        val errText = if (uiState.errorArgs.isEmpty()) stringResource(errRes) else stringResource(errRes, *uiState.errorArgs.toTypedArray())
                        Text(errText, style = MaterialTheme.typography.bodyMedium, color = cs.error, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    uiState.savedAccounts.forEach { account ->
                        val isCurrent = account.uid == uiState.currentAccount?.uid
                        Surface(shape = HiShapeTokens.Medium, color = if (isCurrent) cs.secondaryContainer.copy(alpha = 0.55f) else cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 60.dp)
                                    .clickable(remember { MutableInteractionSource() }, hiListItemPressIndication(), enabled = !isCurrent && !uiState.isBusy) { onSwitchAccount(account.uid) }
                                    .padding(start = 14.dp, end = if (isCurrent) 14.dp else 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(Modifier.size(40.dp).clip(HiShapeTokens.Full).background(if (isCurrent) cs.primaryContainer else cs.surfaceContainerHigh), Alignment.Center) {
                                    Text(account.username.trim().firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium, color = if (isCurrent) cs.onPrimaryContainer else cs.onSurface)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(account.username, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (isCurrent) Text(stringResource(R.string.settings_account_current), style = MaterialTheme.typography.bodySmall, color = cs.primary)
                                }
                                when {
                                    uiState.switchingUid == account.uid || uiState.removingUid == account.uid ->
                                        Box(Modifier.size(48.dp), Alignment.Center) { HiLoadingSpinner(Modifier.size(20.dp), strokeWidth = 2.dp) }
                                    !isCurrent -> {
                                        val removeInt = remember(account.uid) { MutableInteractionSource() }
                                        IconButton(
                                            onClick = { confirmRemoveSaved = account },
                                            modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(removeInt),
                                            enabled = !uiState.isBusy,
                                            interactionSource = removeInt,
                                        ) {
                                            Icon(Icons.Rounded.Close, stringResource(R.string.settings_account_remove_cd, account.username), tint = cs.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val addInt = remember { MutableInteractionSource() }
                    FilledTonalButton(onAddAccount, Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).hiPressScale(addInt), enabled = !uiState.isBusy, shape = HiShapeTokens.Small, interactionSource = addInt) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.settings_account_add))
                    }
                    uiState.currentAccount?.let { active ->
                        val logoutInt = remember { MutableInteractionSource() }
                        OutlinedButton(onLogout, Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).hiPressScale(logoutInt), enabled = !uiState.isBusy, shape = HiShapeTokens.Small, interactionSource = logoutInt) {
                            if (uiState.isLoggingOut) HiLoadingSpinner(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.settings_account_logout))
                        }
                        Spacer(Modifier.height(8.dp)); HorizontalDivider(color = cs.outlineVariant)
                        val clearInt = remember { MutableInteractionSource() }
                        TextButton({ confirmClear = active }, Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).hiPressScale(clearInt), enabled = !uiState.isBusy, shape = HiShapeTokens.Small, colors = ButtonDefaults.textButtonColors(contentColor = cs.error), interactionSource = clearInt) {
                            if (uiState.isClearingCredentials) HiLoadingSpinner(Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.error) else Text(stringResource(R.string.settings_account_clear))
                        }
                    }
                }
            }
        }
    }

    if (isModalCard) {
        Box(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(cs.scrim.copy(alpha = ACCOUNT_SCRIM_ALPHA)).clickable(remember { MutableInteractionSource() }, null, enabled = !uiState.isBusy, onClick = onDismiss))
            Surface(Modifier.align(Alignment.Center).hiFullscreenOverlaySafePadding(includeTop = true, includeBottomNav = true).padding(24.dp).widthIn(max = ACCOUNT_MAX_WIDTH).fillMaxWidth().semantics { paneTitle = title; isTraversalGroup = true }, shape = HiShapeTokens.Large, color = cs.surfaceContainerHigh, shadowElevation = 6.dp) {
                panel(Modifier.fillMaxWidth())
            }
        }
    } else {
        Surface(modifier.fillMaxSize().semantics { paneTitle = title; isTraversalGroup = true }, color = cs.surface) {
            panel(Modifier.fillMaxSize().hiFullscreenOverlaySafePadding(includeTop = true, includeBottomNav = true))
        }
    }

    confirmClear?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!uiState.isBusy) confirmClear = null }, shape = HiShapeTokens.Large,
            title = { Text(stringResource(R.string.settings_account_clear_confirm_title, target.username)) },
            dismissButton = { TextButton({ confirmClear = null }, Modifier.defaultMinSize(48.dp, 48.dp), enabled = !uiState.isBusy) { Text(stringResource(R.string.settings_action_cancel)) } },
            confirmButton = { TextButton({ confirmClear = null; onClearCredentials(target.uid) }, Modifier.defaultMinSize(48.dp, 48.dp), enabled = !uiState.isBusy, colors = ButtonDefaults.textButtonColors(contentColor = cs.error)) { Text(stringResource(R.string.settings_account_clear_confirm_action)) } },
        )
    }

    confirmRemoveSaved?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!uiState.isBusy) confirmRemoveSaved = null }, shape = HiShapeTokens.Large,
            title = { Text(stringResource(R.string.settings_account_remove_confirm_title, target.username)) },
            dismissButton = { TextButton({ confirmRemoveSaved = null }, Modifier.defaultMinSize(48.dp, 48.dp), enabled = !uiState.isBusy) { Text(stringResource(R.string.settings_action_cancel)) } },
            confirmButton = { TextButton({ confirmRemoveSaved = null; onRemoveSavedAccount(target) }, Modifier.defaultMinSize(48.dp, 48.dp), enabled = !uiState.isBusy, colors = ButtonDefaults.textButtonColors(contentColor = cs.error)) { Text(stringResource(R.string.settings_account_clear_confirm_action)) } },
        )
    }
}
