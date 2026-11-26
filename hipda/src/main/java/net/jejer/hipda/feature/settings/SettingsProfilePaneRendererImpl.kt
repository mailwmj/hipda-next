package net.jejer.hipda.feature.settings

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.ImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.user.BlacklistRepository
import net.jejer.hipda.data.user.UserRepository
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.feature.profile.UserProfileScreen
import net.jejer.hipda.feature.profile.UserProfileViewModel
import net.jejer.hipda.service.NotificationScheduler

/** [SettingsProfilePaneRenderer] 生产实现：由 `SettingsProfileUiModule` `@Binds @Singleton` 零耦合接入外壳。 */
@Singleton
class SettingsProfilePaneRendererImpl @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val notificationScheduler: NotificationScheduler,
    private val imageLoader: ImageLoader,
    private val authRepository: AuthRepository,
    private val blacklistRepository: BlacklistRepository,
    private val userRepository: UserRepository,
) : SettingsProfilePaneRenderer {

    @Composable
    override fun SettingsGroupsContent(
        selectedGroup: SettingsGroup,
        isSidebar: Boolean,
        showBack: Boolean,
        onBack: (() -> Unit)?,
        onSelectGroup: (SettingsGroup) -> Unit,
        contentPadding: PaddingValues,
        listState: LazyListState,
    ) = SettingsGroupsPane(selectedGroup, isSidebar, showBack, onBack, onSelectGroup, contentPadding, listState)

    @Composable
    override fun SettingsDetailContent(
        group: SettingsGroup,
        showBack: Boolean,
        onBack: (() -> Unit)?,
        onOpenThread: (ThreadNavArgs) -> Unit,
        onOpenLogin: () -> Unit,
        contentPadding: PaddingValues,
    ) {
        val settingsFactory = remember(appContext, settingsRepository, notificationScheduler, imageLoader) {
            viewModelFactory { initializer { SettingsViewModel(settingsRepository, notificationScheduler, AndroidSettingsCacheManager(appContext, imageLoader), AndroidSettingsErrorLogStore(appContext)) } }
        }
        val blacklistFactory = remember(authRepository, blacklistRepository) {
            viewModelFactory { initializer { BlacklistViewModel(authRepository, blacklistRepository) } }
        }
        val opener = remember(appContext) { AndroidSystemNotificationSettingsOpener(appContext) }

        SettingsDetailPane(
            group = group,
            viewModel = viewModel(factory = settingsFactory),
            onOpenSystemNotificationSettings = opener::open,
            showBack = showBack,
            onBack = onBack,
            onOpenThread = onOpenThread,
            blacklistContent = { onBlacklistBack, padding -> BlacklistScreen(viewModel(factory = blacklistFactory), onBlacklistBack, onOpenLogin, padding) },
            contentPadding = contentPadding,
        )
    }

    @Composable
    override fun UserProfileContent(
        uid: String,
        username: String,
        showBack: Boolean,
        onBack: () -> Unit,
        onOpenThread: (ThreadNavArgs) -> Unit,
        onOpenSmsConversation: (SmsConversationArgs) -> Unit,
        onOpenLogin: () -> Unit,
        contentPadding: PaddingValues,
    ) {
        val factory = remember(uid, username, userRepository, blacklistRepository) {
            viewModelFactory { initializer { UserProfileViewModel(uid, username, userRepository, blacklistRepository) } }
        }
        UserProfileScreen(viewModel(key = "user_profile:$uid", factory = factory), showBack, onBack, onOpenThread, onOpenSmsConversation, onOpenLogin, contentPadding)
    }
}
