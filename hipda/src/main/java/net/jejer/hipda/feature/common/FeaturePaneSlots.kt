package net.jejer.hipda.feature.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
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

/** 实时缩略图窗口坐标注册表：由 `PostImageBlock` 持续上报，看图退场时按 URL 实时查询，避免上方内容撑高后飞回过期坐标。 */
@Stable
class ImageViewerBoundsRegistry {
    private val coordsByUrl = LinkedHashMap<String, LayoutCoordinates>()

    fun update(url: String, coords: LayoutCoordinates) {
        if (!coords.isAttached) return
        coordsByUrl[url] = coords
    }

    fun remove(url: String, coords: LayoutCoordinates?) {
        if (coords == null || coordsByUrl[url] === coords) {
            coordsByUrl.remove(url)
        }
    }

    fun isRegistered(url: String): Boolean = coordsByUrl.containsKey(url)

    /** 若 [url] 对应缩略图当前仍在树内且完整可见，返回实时窗口 `Rect`；已离屏或被裁切则返回 `null` 走原地淡出兜底。 */
    fun resolveVisibleBounds(url: String): Rect? {
        val coords = coordsByUrl[url] ?: return null
        if (!coords.isAttached) return null
        val w = coords.size.width.toFloat()
        val h = coords.size.height.toFloat()
        if (w <= 0f || h <= 0f) return null
        val visible = coords.boundsInWindow()
        if (visible.width < w - 2f || visible.height < h - 2f) return null
        val topLeft = coords.positionInWindow()
        return Rect(topLeft, Size(w, h))
    }

    /** 绑定打开瞬间的已追踪 URL 集合，构造按页实时解析缩略图窗口坐标的回调（已追踪但离屏时返回 `null`，绝不回退过期坐标）。 */
    fun createResolver(args: ImageViewerLaunchArgs): (Int) -> Rect? {
        val trackedAtOpen = args.urls.map { isRegistered(it) }
        return { idx ->
            val url = args.urls.getOrNull(idx)
            when {
                url != null && trackedAtOpen.getOrElse(idx) { false } -> resolveVisibleBounds(url)
                idx == args.initialIndex -> args.sourceBoundsInWindow
                else -> null
            }
        }
    }
}

val LocalImageViewerBoundsRegistry = staticCompositionLocalOf<ImageViewerBoundsRegistry?> { null }

/** 零耦合全屏看图浮层插槽：由 `AppShellBindingModule` `@BindsOptionalOf` 暴露，供 `E14` 绑定。 */
@Immutable
interface ImageViewerRenderer {
    @Composable
    fun Overlay(
        args: ImageViewerLaunchArgs,
        onDismiss: () -> Unit,
    )

    @Composable
    fun Overlay(
        args: ImageViewerLaunchArgs,
        onDismiss: () -> Unit,
        resolveBoundsForIndex: (index: Int) -> Rect?,
    ) {
        Overlay(args = args, onDismiss = onDismiss)
    }
}
