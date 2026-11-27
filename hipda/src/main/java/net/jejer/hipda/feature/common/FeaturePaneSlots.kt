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

/** 构造帖子内单张缩略图的稳定坐标键：`floorPostId + 楼内图片序号`。 */
fun imageViewerBoundsKey(floorPostId: String, imageIndexInFloor: Int): String =
    "$floorPostId#$imageIndexInFloor"

/** 实时缩略图窗口坐标注册表：由 `PostImageBlock` 持续上报，看图退场时按逐页唯一键实时查询，避免上方内容撑高或重复 URL 时飞回错误坐标。 */
@Stable
class ImageViewerBoundsRegistry {
    private val coordsByKey = LinkedHashMap<String, LayoutCoordinates>()
    private val duplicateSlots = HashMap<String, MutableList<Any>>()

    fun update(key: String, coords: LayoutCoordinates) {
        if (!coords.isAttached) return
        coordsByKey[key] = coords
    }

    fun remove(key: String, coords: LayoutCoordinates?) {
        if (coords != null && coordsByKey[key] === coords) {
            coordsByKey.remove(key)
        }
    }

    fun isRegistered(key: String): Boolean = coordsByKey.containsKey(key)

    /** 同一楼层内同一 URL 多次出现时，按组合提交顺序分配稳定出现序号，映射到精确的楼内图片序号。 */
    internal fun registerOccurrence(groupKey: String, owner: Any): Int {
        val list = duplicateSlots.getOrPut(groupKey) { ArrayList(2) }
        val existing = list.indexOfFirst { it === owner }
        if (existing >= 0) return existing
        list.add(owner)
        return list.lastIndex
    }

    internal fun unregisterOccurrence(groupKey: String, owner: Any) {
        val list = duplicateSlots[groupKey] ?: return
        list.removeAll { it === owner }
        if (list.isEmpty()) duplicateSlots.remove(groupKey)
    }

    /** 若 [key] 对应缩略图当前仍在树内且完整可见，返回实时窗口 `Rect`；已离屏或被裁切则返回 `null` 走原地淡出兜底。 */
    fun resolveVisibleBounds(key: String): Rect? {
        val coords = coordsByKey[key] ?: return null
        if (!coords.isAttached) return null
        val w = coords.size.width.toFloat()
        val h = coords.size.height.toFloat()
        if (w <= 0f || h <= 0f) return null
        val visible = coords.boundsInWindow()
        if (visible.width < w - 2f || visible.height < h - 2f) return null
        val topLeft = coords.positionInWindow()
        return Rect(topLeft, Size(w, h))
    }

    /** 绑定打开瞬间的已追踪键集合，构造按页实时解析缩略图窗口坐标的回调（已追踪但离屏时返回 `null`，绝不回退过期坐标）。 */
    fun createResolver(args: ImageViewerLaunchArgs): (Int) -> Rect? {
        val keys = args.urls.indices.map { idx -> args.boundsKeyForPage(idx) }
        val trackedAtOpen = keys.map { key -> key != null && isRegistered(key) }
        return { idx ->
            val key = keys.getOrNull(idx)
            when {
                key != null && trackedAtOpen.getOrElse(idx) { false } -> resolveVisibleBounds(key)
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
