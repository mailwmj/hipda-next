package net.jejer.hipda.feature.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import javax.inject.Inject
import javax.inject.Singleton
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner

private val ACCOUNT_MENU_MAX_WIDTH = 420.dp
private const val ACCOUNT_MENU_SCRIM_ALPHA = 0.40f

/**
 * [LoginOverlayRenderer] 生产实现：零耦合接入 `E0` 外壳插槽。
 * - [LoginOverlayMode.Login]：承载完整 [LoginScreen]；
 * - [LoginOverlayMode.AccountMenu]：`E12` 落地前临时退出登录出口（点击 `退出登录` 无二次确认，退登后发 `已退出登录`）。
 */
@Singleton
class LoginOverlayRendererImpl @Inject constructor(
    private val authRepository: AuthRepository,
    private val siteAccessGateway: SiteAccessGateway,
) : LoginOverlayRenderer {

    @Composable
    override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
        val factory = remember(authRepository, siteAccessGateway) {
            viewModelFactory { initializer { LoginViewModel(authRepository, siteAccessGateway) } }
        }
        val viewModel: LoginViewModel = viewModel(factory = factory)
        when (mode) {
            LoginOverlayMode.Login -> LoginScreen(
                onDismiss = { viewModel.resetForm(); onDismiss() },
                onLoginSuccess = { viewModel.resetForm(); onLoginSuccess() },
                viewModel = viewModel,
            )

            LoginOverlayMode.AccountMenu -> {
                val account by viewModel.currentAccount.collectAsStateWithLifecycle()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarController = LocalPaneSnackbarController.current
                val loggedOutMessage = stringResource(R.string.login_snackbar_logged_out)
                val logoutFailedMessage = stringResource(R.string.common_error_load_failed)
                AccountMenuOverlay(
                    account = account,
                    isLoggingOut = uiState.isLoggingOut,
                    onLogout = { viewModel.logout(snackbarController, loggedOutMessage, logoutFailedMessage, onDismiss) },
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
internal fun AccountMenuOverlay(
    account: SavedAccount?,
    isLoggingOut: Boolean,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val title = stringResource(R.string.shell_sidebar_account)
    BackHandler(onBack = onDismiss)

    Box(modifier = modifier.fillMaxSize()) {
        val scrimInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(cs.scrim.copy(alpha = ACCOUNT_MENU_SCRIM_ALPHA))
                .clickable(scrimInteraction, indication = null, enabled = !isLoggingOut, onClick = onDismiss),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .systemBarsPadding()
                .padding(24.dp)
                .widthIn(max = ACCOUNT_MENU_MAX_WIDTH)
                .fillMaxWidth()
                .semantics { paneTitle = title; isTraversalGroup = true },
            shape = HiShapeTokens.Large,
            color = cs.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = cs.onSurface, modifier = Modifier.semantics { heading() })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val displayName = account?.username.orEmpty()
                    val avatarLetter = displayName.trim().firstOrNull()?.uppercase() ?: "?"
                    Box(Modifier.size(44.dp).clip(HiShapeTokens.Full).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                        Text(avatarLetter, style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(displayName, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.login_account_current), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                }
                HorizontalDivider(color = cs.outlineVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    val closeInteraction = remember { MutableInteractionSource() }
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isLoggingOut,
                        shape = HiShapeTokens.Small,
                        interactionSource = closeInteraction,
                        modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(closeInteraction),
                    ) { Text(stringResource(R.string.login_action_close)) }
                    val logoutInteraction = remember { MutableInteractionSource() }
                    FilledTonalButton(
                        onClick = onLogout,
                        enabled = !isLoggingOut,
                        shape = HiShapeTokens.Small,
                        interactionSource = logoutInteraction,
                        modifier = Modifier.defaultMinSize(88.dp, 48.dp).hiPressScale(logoutInteraction),
                    ) {
                        if (isLoggingOut) HiLoadingSpinner(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.login_action_logout))
                    }
                }
            }
        }
    }
}
