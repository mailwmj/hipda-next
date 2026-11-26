package net.jejer.hipda.feature.messages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import javax.inject.Inject
import javax.inject.Singleton
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.message.NotifyRepository
import net.jejer.hipda.data.message.SmsRepository
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.feature.common.MessagesPaneRenderer
import net.jejer.hipda.feature.common.SmsConversationArgs
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.shell.ShellSidebarEntry

/** [MessagesPaneRenderer] 生产实现：零耦合接入 `E-slots` 外壳插槽，由 `di/MessagesUiModule` `@Binds @Singleton` 暴露。 */
@Singleton
class MessagesPaneRendererImpl @Inject constructor(
    private val smsRepository: SmsRepository,
    private val notifyRepository: NotifyRepository,
    private val authRepository: AuthRepository,
    private val siteAccessGateway: SiteAccessGateway,
) : MessagesPaneRenderer {

    @Composable
    override fun ListContent(
        entry: ShellSidebarEntry,
        isSidebar: Boolean,
        showBack: Boolean,
        onBack: (() -> Unit)?,
        selectedConversationUid: String?,
        onSelectMessageEntry: (ShellSidebarEntry) -> Unit,
        onOpenConversation: (SmsConversationArgs) -> Unit,
        onOpenThread: (ThreadNavArgs) -> Unit,
        onOpenUserProfile: (uid: String, username: String) -> Unit,
        onOpenLogin: () -> Unit,
        contentPadding: PaddingValues,
        listState: LazyListState,
    ) {
        val factory = remember(smsRepository, notifyRepository, authRepository, siteAccessGateway) {
            viewModelFactory { initializer { MessagesListViewModel(smsRepository, notifyRepository, authRepository, siteAccessGateway) } }
        }
        val vm: MessagesListViewModel = viewModel(key = "messages_list", factory = factory)
        MessagesListScreen(entry, isSidebar, showBack, onBack, selectedConversationUid, onSelectMessageEntry, onOpenConversation, onOpenThread, onOpenUserProfile, onOpenLogin, contentPadding, listState, vm)
    }

    @Composable
    override fun ConversationContent(
        args: SmsConversationArgs,
        showBack: Boolean,
        onBack: () -> Unit,
        onOpenUserProfile: (uid: String, username: String) -> Unit,
        onOpenLogin: () -> Unit,
        contentPadding: PaddingValues,
    ) {
        val currentAccount by authRepository.currentAccount.collectAsStateWithLifecycle()
        val factory = remember(smsRepository, authRepository, siteAccessGateway) {
            viewModelFactory { initializer { SmsConversationViewModel(smsRepository, authRepository, siteAccessGateway) } }
        }
        val vmKey = "sms_conversation:${currentAccount?.uid.orEmpty()}:${args.uid.ifBlank { args.username }}"
        val vm: SmsConversationViewModel = viewModel(key = vmKey, factory = factory)
        SmsConversationScreen(args, showBack, onBack, onOpenUserProfile, onOpenLogin, contentPadding, vm)
    }
}
