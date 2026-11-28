package net.jejer.hipda.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import net.jejer.hipda.ui.designsystem.hiHorizontalSafeInsets
import net.jejer.hipda.ui.designsystem.hiTopSafeBar
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.feedback.HiRollingNumber
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiExpandVertically
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut
import net.jejer.hipda.ui.designsystem.motion.hiScaleIn
import net.jejer.hipda.ui.designsystem.motion.hiScaleOut
import net.jejer.hipda.ui.designsystem.motion.hiShrinkVertically
import net.jejer.hipda.ui.designsystem.motion.hiSlideInHorizontally
import net.jejer.hipda.ui.designsystem.motion.hiSlideOutHorizontally

private val SidebarFixedBarHeight: Dp = 56.dp
private val SidebarBottomFixedBarHeight: Dp = 48.dp
private val SidebarSlotSize: Dp = 48.dp
private val SidebarPillSize: Dp = 44.dp
private val SidebarDividerSectionHeight: Dp = 9.dp
private val SidebarModalShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
private const val SidebarModalScrimAlpha: Float = 0.32f
private const val SidebarModalDismissDragFraction: Float = 0.35f
private const val SidebarModalDismissVelocityPxPerSec: Float = -600f
private const val UnreadBadgeOverflowLimit: Int = 99

private val SidebarCollapseVectorIcon: ImageVector by lazy {
    ImageVector.Builder("HiSidebar.Collapse", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(5.5f, 4f); horizontalLineTo(18.5f); arcTo(2.5f, 2.5f, 0f, false, true, 21f, 6.5f)
            verticalLineTo(17.5f); arcTo(2.5f, 2.5f, 0f, false, true, 18.5f, 20f); horizontalLineTo(5.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 3f, 17.5f); verticalLineTo(6.5f); arcTo(2.5f, 2.5f, 0f, false, true, 5.5f, 4f); close()
            moveTo(9.5f, 4f); verticalLineTo(20f); moveTo(16.5f, 9.5f); lineTo(14f, 12f); lineTo(16.5f, 14.5f)
        }
    }.build()
}

/** 左侧栏账号展示信息。 */
@Immutable
data class HiSidebarAccount(
    val username: String? = null,
    val avatarLabel: String? = null,
) {
    val isLoggedIn: Boolean
        get() = !username.isNullOrBlank()
}

/**
 * 左侧栏展示状态（供 [HiAdaptiveShell] 与业务页 `E1`/`E3`/`E8–E12` 接入）。
 *
 * - [isCollapsedPref] 为 `null` 时由外壳按横竖屏内部记住收起偏好；非 `null` 时受外部设置驱动。
 */
@Immutable
data class HiSidebarState(
    val selectedEntry: ShellSidebarEntry = ShellSidebarEntry.TodayNew,
    val followedForumIds: List<Int> = ForumMetadataRegistry.DefaultFollowedForumIds,
    val forumNames: Map<Int, String> = emptyMap(),
    val unreadSmsCount: Int = 0,
    val unreadNotifyCount: Int = 0,
    val account: HiSidebarAccount = HiSidebarAccount(),
    val isCollapsedPref: Boolean? = null,
    val isFullScreen: Boolean = false,
) {
    /** 合并短消息与提醒未读总数（供窄屏/铰链底部胶囊“消息”角标同步展示）。 */
    val totalUnreadCount: Int
        get() = unreadSmsCount.coerceAtLeast(0) + unreadNotifyCount.coerceAtLeast(0)
}

/**
 * ≥ 600dp 常驻左侧栏（取代旧版 80dp 毛玻璃导航轨，product-design.md §3.2、§5）。
 *
 * 纯色 `surfaceContainerLow` 主体与未滚动顶部固定区，右侧 `0.5dp outlineVariant` 细分隔线，无毛玻璃；
 * 仅使用 Compose / Material 3 标准容器（`Column`/`Row`/`LazyColumn`/`Surface`），无自定义 `Layout`。
 */
@Composable
fun HiNavigationSidebar(
    state: HiSidebarState,
    expanded: Boolean,
    mineGroupExpanded: Boolean,
    onToggleSidebar: () -> Unit,
    onToggleMineGroup: () -> Unit,
    onCollapsedMineClick: () -> Unit,
    onSelectEntry: (ShellSidebarEntry) -> Unit,
    onAccountClick: () -> Unit,
    onManageForumsClick: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val cs = MaterialTheme.colorScheme
    val sidebarPaneTitle = stringResource(R.string.shell_pane_sidebar_title)
    // 展开态与收起态各自持有独立 LazyListState，防止 AnimatedContent 过渡帧双 LazyColumn 共用同一 state；
    // R-C4d S2：仅在 expanded 实际翻转时同步滚动位，跳过首次挂载避免重置已恢复或外部传入的偏移。
    val initialExpanded = remember { expanded }
    val alternateListState = rememberLazyListState()
    val expandedListState = if (initialExpanded) listState else alternateListState
    val collapsedListState = if (initialExpanded) alternateListState else listState
    var previousExpanded by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (expanded == previousExpanded) return@LaunchedEffect
        previousExpanded = expanded
        if (expanded) {
            expandedListState.scrollToItem(
                collapsedListState.firstVisibleItemIndex,
                collapsedListState.firstVisibleItemScrollOffset,
            )
        } else {
            collapsedListState.scrollToItem(
                expandedListState.firstVisibleItemIndex,
                expandedListState.firstVisibleItemScrollOffset,
            )
        }
    }
    HiCappedFontScale {
        Row(
            modifier = modifier
                .testTag(HiShellTestTags.NavigationSidebar)
                .fillMaxHeight()
                .background(cs.surfaceContainerLow)
                .clipToBounds()
                .semantics {
                    isTraversalGroup = true
                    paneTitle = sidebarPaneTitle
                },
        ) {
            AnimatedContent(
                targetState = expanded,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                transitionSpec = {
                    (
                        hiFadeIn(animationSpec = HiMotion.effectsDefault()) togetherWith
                            hiFadeOut(animationSpec = HiMotion.exit(HiMotion.effectsDefault()))
                        ).using(null)
                },
                label = "HiNavigationSidebarContent",
            ) { isExpanded ->
                SidebarColumnBody(
                    state = state,
                    expanded = isExpanded,
                    mineGroupExpanded = mineGroupExpanded,
                    listState = if (isExpanded) expandedListState else collapsedListState,
                    onToggleSidebar = onToggleSidebar,
                    onToggleMineGroup = onToggleMineGroup,
                    onCollapsedMineClick = onCollapsedMineClick,
                    onSelectEntry = onSelectEntry,
                    onAccountClick = onAccountClick,
                    onManageForumsClick = onManageForumsClick,
                )
            }
            Box(Modifier.width(0.5.dp).fillMaxHeight().background(cs.outlineVariant.copy(alpha = 0.6f)))
        }
    }
}

/**
 * 600–839dp 宽度下的 240dp 模态浮层侧栏（product-design.md §4.1、§5、§6.3）。
 *
 * 仅由顶部 `展开侧栏` 按钮或 `我的` 图标触发，不设左缘右滑手势；展开后支持内区左滑/点罩/返回/`Escape` 关闭。
 */
@Composable
internal fun HiNavigationSidebarModalOverlay(
    visible: Boolean,
    state: HiSidebarState,
    mineGroupExpanded: Boolean,
    onDismiss: () -> Unit,
    onToggleMineGroup: () -> Unit,
    onSelectEntry: (ShellSidebarEntry) -> Unit,
    onAccountClick: () -> Unit,
    onManageForumsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 仅在抽屉打开时挂载并以 visible 为 key 重新注册，确保每次展开均位于 OnBackPressedDispatcher LIFO 栈顶（浮层 > 位置栈 > 宿主返回）
    if (visible) {
        key(visible) {
            BackHandler(enabled = true, onBack = onDismiss)
        }
    }

    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val sheetWidthPx = with(density) { SHELL_SIDEBAR_MODAL_WIDTH_DP.dp.toPx() }
    val dragOffsetPx = remember { Animatable(0f) }
    val drawerFocusRequester = remember { FocusRequester() }
    val sidebarPaneTitle = stringResource(R.string.shell_pane_sidebar_title)

    LaunchedEffect(visible) {
        if (visible) {
            dragOffsetPx.snapTo(0f)
            runCatching { drawerFocusRequester.requestFocus() }
        }
    }

    val dragState = rememberDraggableState { delta ->
        val target = (dragOffsetPx.value + delta).coerceIn(-sheetWidthPx, 0f)
        scope.launch { dragOffsetPx.snapTo(target) }
    }
    val onDragStopped: suspend kotlinx.coroutines.CoroutineScope.(Float) -> Unit = { velocity ->
        if (
            dragOffsetPx.value < -sheetWidthPx * SidebarModalDismissDragFraction ||
            velocity < SidebarModalDismissVelocityPxPerSec
        ) {
            onDismiss()
        } else {
            scope.launch { dragOffsetPx.animateTo(0f, animationSpec = HiMotion.spatialSnap()) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (visible && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = hiFadeIn(HiMotion.effectsDefault()),
            exit = hiFadeOut(HiMotion.exit(HiMotion.effectsDefault())),
            label = "HiSidebarModalScrim",
        ) {
            Box(
                modifier = Modifier
                    .testTag(HiShellTestTags.SidebarModalScrim)
                    .fillMaxSize()
                    .background(cs.scrim.copy(alpha = SidebarModalScrimAlpha))
                    .modalDismissDrag(dragState, onDragStopped)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = hiSlideInHorizontally(HiMotion.spatialDefault()) { -it } + hiFadeIn(HiMotion.effectsDefault()),
            exit = hiSlideOutHorizontally(HiMotion.exit(HiMotion.spatialDefault())) { -it } +
                hiFadeOut(HiMotion.exit(HiMotion.effectsDefault())),
            modifier = Modifier.align(Alignment.CenterStart),
            label = "HiSidebarModalSheet",
        ) {
            HiCappedFontScale {
                Surface(
                    modifier = Modifier
                        .graphicsLayer { translationX = dragOffsetPx.value }
                        .testTag(HiShellTestTags.SidebarModalDrawer)
                        .width(SHELL_SIDEBAR_MODAL_WIDTH_DP.dp)
                        .fillMaxHeight()
                        .focusRequester(drawerFocusRequester)
                        .focusProperties { onExit = { cancelFocusChange() } }
                        .focusGroup()
                        .focusable()
                        .modalDismissDrag(dragState, onDragStopped)
                        .semantics {
                            isTraversalGroup = true
                            paneTitle = sidebarPaneTitle
                        },
                    shape = SidebarModalShape,
                    color = cs.surfaceContainerLow,
                    shadowElevation = 4.dp,
                ) {
                    SidebarColumnBody(
                        state = state,
                        expanded = true,
                        mineGroupExpanded = mineGroupExpanded,
                        listState = rememberLazyListState(),
                        onToggleSidebar = onDismiss,
                        onToggleMineGroup = onToggleMineGroup,
                        onCollapsedMineClick = {},
                        onSelectEntry = { entry -> onDismiss(); onSelectEntry(entry) },
                        onAccountClick = { onDismiss(); onAccountClick() },
                        onManageForumsClick = { onDismiss(); onManageForumsClick() },
                        modifier = Modifier.windowInsetsPadding(WindowInsets.hiHorizontalSafeInsets.only(WindowInsetsSides.Start)),
                    )
                }
            }
        }
    }
}

private fun Modifier.modalDismissDrag(
    dragState: DraggableState,
    onDragStopped: suspend kotlinx.coroutines.CoroutineScope.(Float) -> Unit,
): Modifier = draggable(
    orientation = Orientation.Horizontal,
    state = dragState,
    onDragStopped = onDragStopped,
)

@Composable
private fun SidebarColumnBody(
    state: HiSidebarState,
    expanded: Boolean,
    mineGroupExpanded: Boolean,
    listState: LazyListState,
    onToggleSidebar: () -> Unit,
    onToggleMineGroup: () -> Unit,
    onCollapsedMineClick: () -> Unit,
    onSelectEntry: (ShellSidebarEntry) -> Unit,
    onAccountClick: () -> Unit,
    onManageForumsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val isScrolled by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val canScrollDown by remember(listState) {
        derivedStateOf { listState.canScrollForward }
    }
    val topBarBg by animateColorAsState(
        targetValue = if (isScrolled) cs.surfaceContainer else cs.surfaceContainerLow,
        animationSpec = HiMotion.effectsDefault(),
        label = "SidebarTopBarBg",
    )
    val topBarDividerAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 1f else 0f,
        animationSpec = HiMotion.effectsDefault(),
        label = "SidebarTopBarDividerAlpha",
    )
    val collapsedBottomDividerAlpha by animateFloatAsState(
        targetValue = if (canScrollDown) 1f else 0f,
        animationSpec = HiMotion.effectsDefault(),
        label = "SidebarBottomDividerAlpha",
    )
    val forums = remember(state.followedForumIds, state.forumNames) {
        ForumMetadataRegistry.resolveAll(state.followedForumIds, state.forumNames)
    }
    val collapsedDisplays = remember(forums) { resolveCollapsedForumDisplays(forums) }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部固定区（56dp，与中/右栏顶栏水平对齐；静止无底线，滚动后渐变 surfaceContainer + 0.5dp 底线）
        Box(
            modifier = Modifier
                .testTag(HiShellTestTags.SidebarTopBar)
                .fillMaxWidth()
                .background(topBarBg)
                .windowInsetsPadding(WindowInsets.hiTopSafeBar),
        ) {
            if (expanded) {
                SidebarExpandedTopBar(state.account, onAccountClick, onToggleSidebar)
            } else {
                SidebarCollapsedSlot(
                    tag = HiShellTestTags.SidebarToggleButton,
                    tooltip = stringResource(R.string.shell_sidebar_expand),
                    selected = false,
                    isTab = false,
                    onClick = onToggleSidebar,
                    slotHeight = SidebarFixedBarHeight,
                ) {
                    Icon(Icons.Rounded.Menu, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
            }
            HorizontalDivider(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = topBarDividerAlpha },
                thickness = 0.5.dp,
                color = cs.outlineVariant.copy(alpha = 0.6f),
            )
        }

        // 中间可滚动区：上方 5 行功能入口 + 9dp 分隔线 + 关注版块列表
        LazyColumn(
            state = listState,
            modifier = Modifier
                .testTag(HiShellTestTags.SidebarList)
                .weight(1f)
                .fillMaxWidth(),
        ) {
            item(key = "today_new") {
                SidebarNavigationRow(
                    label = stringResource(R.string.shell_sidebar_today_new),
                    selectedIcon = Icons.Rounded.Home,
                    unselectedIcon = Icons.Outlined.Home,
                    selected = state.selectedEntry == ShellSidebarEntry.TodayNew,
                    expanded = expanded,
                    unreadCount = 0,
                    tag = HiShellTestTags.sidebarEntry(ShellSidebarEntry.TodayNew),
                    onClick = { onSelectEntry(ShellSidebarEntry.TodayNew) },
                )
            }
            item(key = "search") {
                SidebarNavigationRow(
                    label = stringResource(R.string.shell_sidebar_search),
                    selectedIcon = Icons.Rounded.Search,
                    unselectedIcon = Icons.Outlined.Search,
                    selected = state.selectedEntry == ShellSidebarEntry.Search,
                    expanded = expanded,
                    unreadCount = 0,
                    tag = HiShellTestTags.sidebarEntry(ShellSidebarEntry.Search),
                    onClick = { onSelectEntry(ShellSidebarEntry.Search) },
                )
            }
            item(key = "sms") {
                SidebarNavigationRow(
                    label = stringResource(R.string.shell_sidebar_sms),
                    selectedIcon = Icons.Rounded.Email,
                    unselectedIcon = Icons.Outlined.Email,
                    selected = state.selectedEntry == ShellSidebarEntry.Sms,
                    expanded = expanded,
                    unreadCount = state.unreadSmsCount,
                    tag = HiShellTestTags.sidebarEntry(ShellSidebarEntry.Sms),
                    onClick = { onSelectEntry(ShellSidebarEntry.Sms) },
                )
            }
            item(key = "notify") {
                SidebarNavigationRow(
                    label = stringResource(R.string.shell_sidebar_notify),
                    selectedIcon = Icons.Rounded.Notifications,
                    unselectedIcon = Icons.Outlined.Notifications,
                    selected = state.selectedEntry == ShellSidebarEntry.Notify,
                    expanded = expanded,
                    unreadCount = state.unreadNotifyCount,
                    tag = HiShellTestTags.sidebarEntry(ShellSidebarEntry.Notify),
                    onClick = { onSelectEntry(ShellSidebarEntry.Notify) },
                )
            }
            item(key = "mine_group") {
                SidebarMineGroupItem(
                    selectedEntry = state.selectedEntry,
                    expanded = expanded,
                    mineGroupExpanded = mineGroupExpanded,
                    onToggleMineGroup = onToggleMineGroup,
                    onCollapsedMineClick = onCollapsedMineClick,
                    onSelectEntry = onSelectEntry,
                )
            }
            item(key = "forums_divider") {
                Box(Modifier.fillMaxWidth().height(SidebarDividerSectionHeight), Alignment.Center) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = if (expanded) 12.dp else 10.dp),
                        thickness = 1.dp,
                        color = cs.outlineVariant,
                    )
                }
            }

            if (expanded) {
                item(key = "forums_header") { SidebarForumsHeader(onManageForumsClick) }
                if (forums.isEmpty()) {
                    item(key = "forums_empty") { SidebarEmptyForumsGuide(onManageForumsClick) }
                } else {
                    items(items = forums, key = { "forum_${it.fid}" }) { forum ->
                        SidebarExpandedForumRow(
                            forum = forum,
                            selected = state.selectedEntry == ShellSidebarEntry.Forum(forum.fid),
                            onClick = { onSelectEntry(ShellSidebarEntry.Forum(forum.fid)) },
                        )
                    }
                }
            } else {
                items(items = forums, key = { "forum_${it.fid}" }) { forum ->
                    SidebarCollapsedForumItem(
                        forum = forum,
                        display = collapsedDisplays.getValue(forum.fid),
                        selected = state.selectedEntry == ShellSidebarEntry.Forum(forum.fid),
                        onClick = { onSelectEntry(ShellSidebarEntry.Forum(forum.fid)) },
                    )
                }
                item(key = "collapsed_manage_forums") {
                    SidebarCollapsedSlot(
                        tag = HiShellTestTags.SidebarManageForumsButton,
                        tooltip = stringResource(R.string.shell_sidebar_manage_forums),
                        selected = false,
                        isTab = false,
                        onClick = onManageForumsClick,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    }
                }
                item(key = "collapsed_settings") {
                    SidebarNavigationRow(
                        label = stringResource(R.string.shell_sidebar_settings),
                        selectedIcon = Icons.Rounded.Settings,
                        unselectedIcon = Icons.Outlined.Settings,
                        selected = state.selectedEntry == ShellSidebarEntry.Settings,
                        expanded = false,
                        unreadCount = 0,
                        tag = HiShellTestTags.sidebarEntry(ShellSidebarEntry.Settings),
                        onClick = { onSelectEntry(ShellSidebarEntry.Settings) },
                    )
                }
            }
        }

        // 底部固定区（48dp，展开态固定“设置”+ 1dp 顶线；收起态固定圆形账号头像）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
        ) {
            if (expanded) {
                HorizontalDivider(thickness = 1.dp, color = cs.outlineVariant)
                Box(Modifier.fillMaxWidth().heightIn(min = SidebarBottomFixedBarHeight), contentAlignment = Alignment.Center) {
                    SidebarNavigationRow(
                        label = stringResource(R.string.shell_sidebar_settings),
                        selectedIcon = Icons.Rounded.Settings,
                        unselectedIcon = Icons.Outlined.Settings,
                        selected = state.selectedEntry == ShellSidebarEntry.Settings,
                        expanded = true,
                        unreadCount = 0,
                        tag = HiShellTestTags.sidebarEntry(ShellSidebarEntry.Settings),
                        onClick = { onSelectEntry(ShellSidebarEntry.Settings) },
                    )
                }
            } else {
                HorizontalDivider(
                    modifier = Modifier.graphicsLayer { alpha = collapsedBottomDividerAlpha },
                    thickness = 1.dp,
                    color = cs.outlineVariant,
                )
                val accountLabel = state.account.username?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.shell_sidebar_login)
                SidebarCollapsedSlot(
                    tag = HiShellTestTags.SidebarAccountRow,
                    tooltip = accountLabel,
                    selected = false,
                    isTab = false,
                    onClick = onAccountClick,
                    slotHeight = SidebarBottomFixedBarHeight,
                ) {
                    SidebarAvatarCircle(account = state.account, size = 32.dp)
                }
            }
        }
    }
}

@Composable
private fun SidebarExpandedTopBar(
    account: HiSidebarAccount,
    onAccountClick: () -> Unit,
    onCollapseClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val displayName = account.username?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.shell_sidebar_login)
    val accountInteraction = remember { MutableInteractionSource() }
    val collapseInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SidebarFixedBarHeight)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = SidebarSlotSize)
                .hiPressScale(accountInteraction)
                .testTag(HiShellTestTags.SidebarAccountRow)
                .clip(HiShapeTokens.Medium)
                .clickable(accountInteraction, LocalIndication.current, onClick = onAccountClick)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SidebarAvatarCircle(account = account, size = 28.dp)
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelLarge,
                color = cs.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(SidebarSlotSize)
                .hiPressScale(collapseInteraction)
                .testTag(HiShellTestTags.SidebarToggleButton)
                .clip(HiShapeTokens.Full)
                .clickable(collapseInteraction, LocalIndication.current, role = Role.Button, onClick = onCollapseClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SidebarCollapseVectorIcon,
                contentDescription = stringResource(R.string.shell_sidebar_collapse),
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SidebarNavigationRow(
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    selected: Boolean,
    expanded: Boolean,
    unreadCount: Int,
    tag: String,
    onClick: () -> Unit,
    indented: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val accessibleDesc = if (unreadCount > 0) stringResource(R.string.shell_sidebar_unread_cd, label, unreadCount) else label
    val iconColor = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant
    val textColor = if (selected) cs.onSecondaryContainer else cs.onSurface

    if (expanded) {
        SidebarExpandedPillRow(
            tag = tag,
            selected = selected,
            isTab = true,
            startPadding = if (indented) 24.dp else 12.dp,
            semanticsExtra = { if (unreadCount > 0) contentDescription = accessibleDesc },
            onClick = onClick,
        ) {
            Icon(if (selected) selectedIcon else unselectedIcon, null, tint = iconColor, modifier = Modifier.size(20.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (unreadCount > 0) {
                SidebarUnreadBadge(unreadCount, compact = false, modifier = Modifier.clearAndSetSemantics {})
            }
        }
    } else {
        SidebarCollapsedSlot(tag = tag, tooltip = accessibleDesc, selected = selected, isTab = true, onClick = onClick) {
            Icon(if (selected) selectedIcon else unselectedIcon, null, tint = iconColor, modifier = Modifier.size(22.dp))
            if (unreadCount > 0) {
                SidebarUnreadBadge(
                    count = unreadCount,
                    compact = true,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp).clearAndSetSemantics {},
                )
            }
        }
    }
}

@Composable
private fun SidebarMineGroupItem(
    selectedEntry: ShellSidebarEntry,
    expanded: Boolean,
    mineGroupExpanded: Boolean,
    onToggleMineGroup: () -> Unit,
    onCollapsedMineClick: () -> Unit,
    onSelectEntry: (ShellSidebarEntry) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val mineLabel = stringResource(R.string.shell_sidebar_mine)
    val childSelected = selectedEntry.isMineGroupChild

    if (!expanded) {
        SidebarCollapsedSlot(
            tag = HiShellTestTags.SidebarMineGroup,
            tooltip = mineLabel,
            selected = childSelected,
            isTab = true,
            onClick = onCollapsedMineClick,
        ) {
            Icon(
                imageVector = if (childSelected) Icons.Rounded.Person else Icons.Outlined.Person,
                contentDescription = null,
                tint = if (childSelected) cs.onSecondaryContainer else cs.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        return
    }

    val headerSelected = childSelected && !mineGroupExpanded
    val chevronRotation by animateFloatAsState(
        targetValue = if (mineGroupExpanded) 180f else 0f,
        animationSpec = HiMotion.spatialFast(),
        label = "SidebarMineChevron",
    )
    val toggleStateDesc = stringResource(
        if (mineGroupExpanded) R.string.shell_sidebar_collapse else R.string.shell_sidebar_expand,
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        SidebarExpandedPillRow(
            tag = HiShellTestTags.SidebarMineGroup,
            selected = headerSelected,
            isTab = false,
            semanticsExtra = { stateDescription = toggleStateDesc },
            onClick = onToggleMineGroup,
        ) {
            Icon(
                imageVector = if (headerSelected) Icons.Rounded.Person else Icons.Outlined.Person,
                contentDescription = null,
                tint = if (headerSelected) cs.onSecondaryContainer else cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = mineLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (headerSelected) cs.onSecondaryContainer else cs.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = if (headerSelected) cs.onSecondaryContainer else cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = chevronRotation },
            )
        }

        AnimatedVisibility(
            visible = mineGroupExpanded,
            enter = hiExpandVertically(HiMotion.spatialDefault(), expandFrom = Alignment.Top) +
                hiFadeIn(HiMotion.effectsDefault()),
            exit = hiShrinkVertically(HiMotion.exit(HiMotion.spatialDefault()), shrinkTowards = Alignment.Top) +
                hiFadeOut(HiMotion.exit(HiMotion.effectsDefault())),
            label = "SidebarMineGroupChildren",
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val children = listOf(
                    Triple(ShellSidebarEntry.FavoritesAndAttention, R.string.shell_sidebar_favorites_and_attention, Icons.Rounded.Favorite to Icons.Outlined.FavoriteBorder),
                    Triple(ShellSidebarEntry.MyThreads, R.string.shell_sidebar_my_threads, Icons.AutoMirrored.Rounded.List to Icons.AutoMirrored.Rounded.List),
                    Triple(ShellSidebarEntry.MyReplies, R.string.shell_sidebar_my_replies, Icons.AutoMirrored.Rounded.Send to Icons.AutoMirrored.Rounded.Send),
                    Triple(ShellSidebarEntry.History, R.string.shell_sidebar_history, Icons.Rounded.DateRange to Icons.Outlined.DateRange),
                )
                children.forEach { (entry, labelRes, icons) ->
                    SidebarNavigationRow(
                        label = stringResource(labelRes),
                        selectedIcon = icons.first,
                        unselectedIcon = icons.second,
                        selected = selectedEntry == entry,
                        expanded = true,
                        unreadCount = 0,
                        tag = HiShellTestTags.sidebarEntry(entry),
                        onClick = { onSelectEntry(entry) },
                        indented = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun SidebarForumsHeader(onManageForumsClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SidebarSlotSize)
            .padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.shell_sidebar_forums_title),
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .size(SidebarSlotSize)
                .hiPressScale(interaction)
                .testTag(HiShellTestTags.SidebarManageForumsButton)
                .clip(HiShapeTokens.Full)
                .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onManageForumsClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.shell_sidebar_manage_forums),
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SidebarEmptyForumsGuide(onManageForumsClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.shell_sidebar_empty_forums),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onManageForumsClick, shape = HiShapeTokens.Small) {
            Text(text = stringResource(R.string.shell_sidebar_manage_forums))
        }
    }
}

@Composable
private fun SidebarExpandedForumRow(
    forum: ForumMetadata,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val iconColor = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant
    SidebarExpandedPillRow(
        tag = HiShellTestTags.sidebarEntry(ShellSidebarEntry.Forum(forum.fid)),
        selected = selected,
        isTab = true,
        onClick = onClick,
    ) {
        val icon = forum.icon
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(HiShapeTokens.Small)
                    .background(if (selected) cs.primary else cs.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = forum.badgeMonogram,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        Text(
            text = forum.fullName,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) cs.onSecondaryContainer else cs.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SidebarCollapsedForumItem(
    forum: ForumMetadata,
    display: CollapsedForumDisplay,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val contentTint = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant
    SidebarCollapsedSlot(
        tag = HiShellTestTags.sidebarEntry(ShellSidebarEntry.Forum(forum.fid)),
        tooltip = forum.fullName,
        selected = selected,
        isTab = true,
        onClick = onClick,
    ) {
        when (display) {
            is CollapsedForumDisplay.IconOnly -> Icon(
                imageVector = display.icon,
                contentDescription = null,
                tint = contentTint,
                modifier = Modifier.size(22.dp),
            )

            is CollapsedForumDisplay.MonogramBadge -> Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(HiShapeTokens.Small)
                    .background(if (selected) cs.primary else cs.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = display.badgeText,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

@Composable
private fun SidebarExpandedPillRow(
    tag: String,
    selected: Boolean,
    isTab: Boolean,
    onClick: () -> Unit,
    startPadding: Dp = 12.dp,
    semanticsExtra: (androidx.compose.ui.semantics.SemanticsPropertyReceiver.() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val clickModifier = if (isTab) {
        Modifier.selectable(
            selected = selected,
            interactionSource = interaction,
            indication = LocalIndication.current,
            role = Role.Tab,
            onClick = onClick,
        )
    } else {
        Modifier.clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            role = Role.Button,
            onClick = onClick,
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SidebarSlotSize)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SidebarPillSize)
                .hiPressScale(interaction)
                .testTag(tag)
                .clip(HiShapeTokens.Medium)
                .background(if (selected) cs.secondaryContainer else Color.Transparent)
                .then(
                    if (semanticsExtra != null) Modifier.semantics(mergeDescendants = true, semanticsExtra) else Modifier,
                )
                .then(clickModifier)
                .padding(start = startPadding, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SidebarCollapsedSlot(
    tag: String,
    tooltip: String,
    selected: Boolean,
    isTab: Boolean,
    onClick: () -> Unit,
    slotHeight: Dp = SidebarSlotSize,
    content: @Composable BoxScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val clickModifier = if (isTab) {
        Modifier.selectable(
            selected = selected,
            interactionSource = interaction,
            indication = LocalIndication.current,
            role = Role.Tab,
            onClick = onClick,
        )
    } else {
        Modifier.clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            role = Role.Button,
            onClick = onClick,
        )
    }
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = slotHeight),
        contentAlignment = Alignment.Center,
    ) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.End),
            tooltip = { PlainTooltip { Text(tooltip) } },
            state = rememberTooltipState(),
        ) {
            Box(
                modifier = Modifier
                    .size(SidebarSlotSize)
                    .hiPressScale(interaction)
                    .testTag(tag)
                    .semantics(mergeDescendants = true) { contentDescription = tooltip }
                    .then(clickModifier),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(SidebarPillSize)
                        .clip(HiShapeTokens.Medium)
                        .background(if (selected) cs.secondaryContainer else Color.Transparent),
                    contentAlignment = Alignment.Center,
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun SidebarAvatarCircle(account: HiSidebarAccount, size: Dp) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(size)
            .clip(HiShapeTokens.Full)
            .background(if (account.isLoggedIn) cs.primaryContainer else cs.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val initial = (account.avatarLabel ?: account.username)?.trim()?.take(1)?.uppercase()
        if (!initial.isNullOrEmpty()) {
            Text(
                text = initial,
                style = MaterialTheme.typography.labelMedium,
                color = cs.onPrimaryContainer,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.clearAndSetSemantics {},
            )
        } else {
            Icon(Icons.Rounded.Person, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(size * 0.6f))
        }
    }
}

@Composable
internal fun SidebarUnreadBadge(
    count: Int,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val visible = count > 0
    val visibleState = remember { MutableTransitionState(visible) }
    visibleState.targetState = visible
    var displayedCount by remember { mutableIntStateOf(count.coerceAtLeast(1)) }
    if (visible) displayedCount = count
    if (!visibleState.currentState && !visibleState.targetState) return

    val cs = MaterialTheme.colorScheme
    AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = hiScaleIn(HiMotion.spatialFast(), 0f, TransformOrigin.Center) + hiFadeIn(HiMotion.effectsFast()),
        exit = hiScaleOut(HiMotion.exit(HiMotion.spatialFast()), 0f, TransformOrigin.Center) +
            hiFadeOut(HiMotion.exit(HiMotion.effectsFast())),
        label = "SidebarUnreadBadge",
    ) {
        val minSize = if (compact) 16.dp else 18.dp
        val textStyle = if (compact) {
            MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp)
        } else {
            MaterialTheme.typography.labelSmall
        }
        Box(
            modifier = Modifier
                .widthIn(min = minSize)
                .heightIn(min = minSize)
                .clip(HiShapeTokens.Full)
                .background(cs.error)
                .padding(horizontal = if (compact) 4.dp else 5.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (displayedCount > UnreadBadgeOverflowLimit) {
                Text(text = "99+", style = textStyle, color = cs.onError, maxLines = 1, softWrap = false)
            } else {
                HiRollingNumber(value = displayedCount, style = textStyle, color = cs.onError)
            }
        }
    }
}
