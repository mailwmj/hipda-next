package net.jejer.hipda.feature.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import javax.inject.Inject
import javax.inject.Singleton
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.feature.common.LoginOverlayMode
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.settings.AccountManagementOverlay
import net.jejer.hipda.feature.settings.AccountManagementViewModel

/**
 * [LoginOverlayRenderer] 生产实现：零耦合接入外壳插槽。
 * - [LoginOverlayMode.Login]：承载完整 [LoginScreen]；
 * - [LoginOverlayMode.AccountMenu]：承载正式 [AccountManagementOverlay]（支持在浮层内切换到 `Login` 添加账号）。
 */
@Singleton
class LoginOverlayRendererImpl @Inject constructor(
    private val authRepository: AuthRepository,
    private val siteAccessGateway: SiteAccessGateway,
) : LoginOverlayRenderer {

    @Composable
    override fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit) {
        var activeMode by rememberSaveable(mode) { mutableStateOf(mode) }
        val loginFactory = remember(authRepository, siteAccessGateway) {
            viewModelFactory { initializer { LoginViewModel(authRepository, siteAccessGateway) } }
        }
        val accountFactory = remember(authRepository) {
            viewModelFactory { initializer { AccountManagementViewModel(authRepository) } }
        }
        when (activeMode) {
            LoginOverlayMode.Login -> {
                val viewModel: LoginViewModel = viewModel(factory = loginFactory)
                LoginScreen(
                    onDismiss = {
                        viewModel.resetForm()
                        if (mode == LoginOverlayMode.AccountMenu) {
                            activeMode = LoginOverlayMode.AccountMenu
                        } else {
                            onDismiss()
                        }
                    },
                    onLoginSuccess = {
                        viewModel.resetForm()
                        onLoginSuccess()
                    },
                    viewModel = viewModel,
                )
            }

            LoginOverlayMode.AccountMenu -> {
                val accountViewModel: AccountManagementViewModel = viewModel(factory = accountFactory)
                AccountManagementOverlay(
                    viewModel = accountViewModel,
                    onAddAccount = { activeMode = LoginOverlayMode.Login },
                    onDismiss = onDismiss,
                )
            }
        }
    }
}
