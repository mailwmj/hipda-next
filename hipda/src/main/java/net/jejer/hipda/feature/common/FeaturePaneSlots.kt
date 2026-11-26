package net.jejer.hipda.feature.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.ui.shell.ShellSidebarEntry

/** 零耦合消息面板插槽：由 `AppShellBindingModule` `@BindsOptionalOf` 暴露，供 `E8` 绑定短消息列表、提醒列表与短消息对话。 */
@Immutable
interface MessagesPaneRenderer {
    @Composable
    fun ListContent(
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
    )

    @Composable
    fun ConversationContent(
        args: SmsConversationArgs,
        showBack: Boolean,
        onBack: () -> Unit,
        onOpenUserProfile: (uid: String, username: String) -> Unit,
        onOpenLogin: () -> Unit,
        contentPadding: PaddingValues,
    )
}

/** 零耦合搜索与「我的」面板插槽：由 `AppShellBindingModule` `@BindsOptionalOf` 暴露，供 `E10` 绑定搜索页、我的菜单与我的子页。 */
@Immutable
interface SearchMinePaneRenderer {
    @Composable
    fun SearchContent(
        showBack: Boolean,
        onBack: (() -> Unit)?,
        onOpenThread: (ThreadNavArgs) -> Unit,
        onOpenForum: (fid: Int) -> Unit,
        onOpenUserProfile: (uid: String, username: String) -> Unit,
        onOpenLogin: () -> Unit,
        contentPadding: PaddingValues,
        listState: LazyListState,
    )

    @Composable
    fun MineMenuContent(
        account: SavedAccount?,
        onAccountClick: () -> Unit,
        onSelectSubEntry: (ShellSidebarEntry) -> Unit,
        contentPadding: PaddingValues,
    )

    @Composable
    fun MineSubPageContent(
        entry: ShellSidebarEntry,
        isSidebar: Boolean,
        showBack: Boolean,
        onBack: (() -> Unit)?,
        onSelectMineEntry: (ShellSidebarEntry) -> Unit,
        onOpenThread: (ThreadNavArgs) -> Unit,
        onOpenLogin: () -> Unit,
        contentPadding: PaddingValues,
        listState: LazyListState,
    )
}

/** 零耦合设置与用户资料面板插槽：由 `AppShellBindingModule` `@BindsOptionalOf` 暴露，供 `E12` 绑定 5 组设置与用户资料页。 */
@Immutable
interface SettingsProfilePaneRenderer {
    @Composable
    fun SettingsGroupsContent(
        selectedGroup: SettingsGroup,
        isSidebar: Boolean,
        showBack: Boolean,
        onBack: (() -> Unit)?,
        onSelectGroup: (SettingsGroup) -> Unit,
        contentPadding: PaddingValues,
        listState: LazyListState,
    )

    @Composable
    fun SettingsDetailContent(
        group: SettingsGroup,
        showBack: Boolean,
        onBack: (() -> Unit)?,
        onOpenThread: (ThreadNavArgs) -> Unit,
        onOpenLogin: () -> Unit,
        contentPadding: PaddingValues,
    )

    @Composable
    fun UserProfileContent(
        uid: String,
        username: String,
        showBack: Boolean,
        onBack: () -> Unit,
        onOpenThread: (ThreadNavArgs) -> Unit,
        onOpenSmsConversation: (SmsConversationArgs) -> Unit,
        onOpenLogin: () -> Unit,
        contentPadding: PaddingValues,
    )
}

/** 零耦合全屏看图浮层插槽：由 `AppShellBindingModule` `@BindsOptionalOf` 暴露，供 `E14` 绑定。 */
@Immutable
interface ImageViewerRenderer {
    @Composable
    fun Overlay(
        args: ImageViewerLaunchArgs,
        onDismiss: () -> Unit,
    )
}
