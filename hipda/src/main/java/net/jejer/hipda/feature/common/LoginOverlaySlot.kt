package net.jejer.hipda.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/** 登录/账号覆盖层展示模式：未登录展示登录页，已登录在 `E12` 落地前提供临时退登出口。 */
enum class LoginOverlayMode { Login, AccountMenu }

/** 零耦合登录/账号覆盖层插槽：由 `AppShellBindingModule` `@BindsOptionalOf` 暴露，供 `E15` 自动接入。 */
@Immutable
interface LoginOverlayRenderer {
    @Composable
    fun Content(mode: LoginOverlayMode, onDismiss: () -> Unit, onLoginSuccess: () -> Unit)
}
