package net.jejer.hipda.feature.messages

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.collectLatest
import net.jejer.hipda.R
import net.jejer.hipda.data.message.NotifyEntry
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.feature.forum.FORUM_COMPACT_ROW_WIDTH_THRESHOLD
import net.jejer.hipda.feature.forum.resolveThreadRowBackground
import net.jejer.hipda.feature.forum.resolveThreadTitleColor
import net.jejer.hipda.parser.message.*
import net.jejer.hipda.ui.challenge.*
import net.jejer.hipda.ui.designsystem.*
import net.jejer.hipda.ui.designsystem.feedback.*
import net.jejer.hipda.ui.designsystem.motion.*
import net.jejer.hipda.ui.designsystem.state.*
import net.jejer.hipda.ui.shell.ShellSidebarEntry

private val TOP_BAR_ROW_HEIGHT = 48.dp

/** 消息列表主界面（纯色顶栏，零毛玻璃）：单栏含 `短消息 | 提醒` 分段标签与未读点，侧栏态隐藏标签行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesListScreen(
    entry: ShellSidebarEntry, isSidebar: Boolean, showBack: Boolean, onBack: (() -> Unit)?,
    selectedConversationUid: String?, onSelectMessageEntry: (ShellSidebarEntry) -> Unit,
    onOpenConversation: (SmsConversationArgs) -> Unit, onOpenThread: (ThreadNavArgs) -> Unit,
    onOpenUserProfile: (uid: String, username: String) -> Unit, onOpenLogin: () -> Unit,
    contentPadding: PaddingValues, listState: LazyListState, viewModel: MessagesListViewModel,
    modifier: Modifier = Modifier,
) {
    val activeEntry = normalizeMessageEntry(entry)
    LaunchedEffect(activeEntry) { viewModel.bindEntry(activeEntry) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarController = LocalPaneSnackbarController.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel, snackbarController, resources, activeEntry) {
        viewModel.events.collectLatest { event ->
            when (event) {
                MessagesListEvent.AllNotificationsMarkedRead ->
                    snackbarController.show(resources.getString(R.string.messages_snackbar_all_read), PaneSnackbarTarget.ContentArea)
                MessagesListEvent.RequireLogin ->
                    snackbarController.show(resources.getString(R.string.messages_login_to_operate), PaneSnackbarTarget.ContentArea, resources.getString(R.string.common_action_login), onOpenLogin)
                is MessagesListEvent.ShowError ->
                    snackbarController.showMessageUiError(event.error, resources, PaneSnackbarTarget.ContentArea, { viewModel.refresh(activeEntry, it, forceFullLoad = true) }, onOpenLogin)
            }
        }
    }

    val notifyOnlyListState = rememberLazyListState()
    val effectiveNotifyListState = if (isSidebar) listState else notifyOnlyListState
    val activeListState = if (activeEntry == ShellSidebarEntry.Notify) effectiveNotifyListState else listState
    val isScrolled by remember(activeListState) { derivedStateOf { activeListState.firstVisibleItemIndex > 0 || activeListState.firstVisibleItemScrollOffset > 0 } }
    var clearDialogTarget by remember { mutableStateOf<SmsConversationItem?>(null) }
    val layoutDir = LocalLayoutDirection.current
    val listBottomPadding = remember(contentPadding, layoutDir) {
        PaddingValues(start = contentPadding.calculateStartPadding(layoutDir), end = contentPadding.calculateEndPadding(layoutDir), bottom = contentPadding.calculateBottomPadding())
    }
    val cs = MaterialTheme.colorScheme
    val topTitle = when {
        !isSidebar -> stringResource(R.string.shell_nav_messages)
        activeEntry == ShellSidebarEntry.Notify -> stringResource(R.string.shell_sidebar_notify)
        else -> stringResource(R.string.shell_sidebar_sms)
    }
    val canMarkAllRead = uiState.isLoggedIn && !uiState.notify.isMarkingAllRead && (uiState.notifyUnreadCount > 0 || uiState.notify.entries.any { !it.isRead })

    val renderTab: @Composable (ShellSidebarEntry) -> Unit = { target ->
        val isNotify = target == ShellSidebarEntry.Notify
        val tabState = if (isNotify) effectiveNotifyListState else listState
        val isEmpty = if (isNotify) uiState.notify.entries.isEmpty() else uiState.sms.items.isEmpty()
        val isLoading = if (isNotify) uiState.notify.isLoading else uiState.sms.isLoading
        val isRefreshing = if (isNotify) uiState.notify.isRefreshing else uiState.sms.isRefreshing
        val hasLoadedOnce = if (isNotify) uiState.notify.hasLoadedOnce else uiState.sms.hasLoadedOnce
        val error = if (isNotify) uiState.notify.error else uiState.sms.error
        val chFailure = if (isNotify) uiState.notify.challengeFailure else uiState.sms.challengeFailure
        val emptyMsg = stringResource(if (isNotify) R.string.common_empty_notify else R.string.common_empty_sms)
        val uidCounts = remember(uiState.sms.items) { uiState.sms.items.groupingBy { it.uid }.eachCount() }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < FORUM_COMPACT_ROW_WIDTH_THRESHOLD
            MessagePaneStateBox(
                isEmpty, isLoading, hasLoadedOnce, error, uiState.challengeState, chFailure, emptyMsg, listBottomPadding,
                { viewModel.refresh(target, it, forceFullLoad = true) }, onOpenLogin, skeleton = { MessagesListSkeleton(compact, listBottomPadding) },
            ) {
                PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = { viewModel.refresh(target) }, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(state = tabState, modifier = Modifier.fillMaxSize(), contentPadding = listBottomPadding) {
                        if (isNotify) {
                            items(uiState.notify.entries, key = { it.key }) { item ->
                                Column(Modifier.animateItem(HiMotion.effectsDefault(), HiMotion.spatialDefault(), HiMotion.exit(HiMotion.effectsDefault()))) {
                                    NotifyEntryRow(item, compact) { viewModel.onNotificationOpened(item); dispatchNotificationTarget(item, onOpenThread, onOpenUserProfile) }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                                }
                            }
                        } else {
                            itemsIndexed(uiState.sms.items, key = { idx, item -> if (item.uid.isNotBlank() && uidCounts[item.uid] == 1) item.uid else "${item.uid}:${item.time}:$idx" }) { _, item ->
                                Column(Modifier.animateItem(HiMotion.effectsDefault(), HiMotion.spatialDefault(), HiMotion.exit(HiMotion.effectsDefault()))) {
                                    SmsConversationRow(
                                        item, !selectedConversationUid.isNullOrBlank() && item.uid == selectedConversationUid, compact, uiState.sms.clearingUid == item.uid,
                                        { viewModel.markConversationLocallyRead(item.uid); onOpenConversation(SmsConversationArgs(item.uid, item.author)) },
                                        { clearDialogTarget = item },
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                                }
                            }
                        }
                        item(key = if (isNotify) "notify_list_footer" else "sms_list_footer") { HiListFooter(HiFooterState.End) }
                    }
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(cs.surface)) {
        MessagesSolidTopBarFrame(
            title = topTitle, showBack = showBack, onBack = onBack, isScrolled = isScrolled,
            actions = {
                if (activeEntry == ShellSidebarEntry.Notify) {
                    val interaction = remember { MutableInteractionSource() }
                    TextButton(onClick = viewModel::markAllNotificationsRead, enabled = canMarkAllRead, interactionSource = interaction, shape = HiShapeTokens.Small, modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(interaction)) {
                        Text(stringResource(R.string.messages_action_mark_all_read), maxLines = 1, softWrap = false)
                    }
                } else {
                    val interaction = remember { MutableInteractionSource() }
                    IconButton(onClick = viewModel::onNewSmsClick, interactionSource = interaction, modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(interaction)) {
                        Icon(Icons.Rounded.Edit, stringResource(R.string.messages_action_new_sms), tint = cs.onSurface, modifier = Modifier.size(22.dp))
                    }
                }
            },
            belowRow = {
                if (!isSidebar) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp)) {
                        listOf(ShellSidebarEntry.Sms, ShellSidebarEntry.Notify).forEachIndexed { idx, tabEntry ->
                            val isNotify = tabEntry == ShellSidebarEntry.Notify
                            val label = stringResource(if (isNotify) R.string.shell_sidebar_notify else R.string.shell_sidebar_sms)
                            val hasUnread = if (isNotify) uiState.notifyUnreadCount > 0 else uiState.smsUnreadCount > 0
                            val interaction = remember { MutableInteractionSource() }
                            SegmentedButton(
                                selected = activeEntry == tabEntry, onClick = { onSelectMessageEntry(tabEntry) },
                                shape = SegmentedButtonDefaults.itemShape(idx, 2), interactionSource = interaction, icon = {},
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp).hiPressScale(interaction),
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                    AnimatedVisibility(
                                        visible = hasUnread, label = "TabUnreadDot",
                                        enter = hiScaleIn(HiMotion.spatialFast()) + hiFadeIn(HiMotion.effectsFast()),
                                        exit = hiScaleOut(HiMotion.exit(HiMotion.spatialFast())) + hiFadeOut(HiMotion.exit(HiMotion.effectsFast())),
                                    ) { Box(Modifier.size(7.dp).clip(HiShapeTokens.Full).background(cs.error)) }
                                }
                            }
                        }
                    }
                }
            },
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (isSidebar) renderTab(activeEntry)
            else AnimatedContent(
                targetState = activeEntry, modifier = Modifier.fillMaxSize(), label = "MessagesTabContent",
                transitionSpec = { (hiFadeIn(HiMotion.effectsDefault()) togetherWith hiFadeOut(HiMotion.exit(HiMotion.effectsDefault()))).using(null) },
            ) { renderTab(it) }
        }
    }

    clearDialogTarget?.let { target ->
        ClearConversationConfirmDialog(target.author, { clearDialogTarget = null }) { clearDialogTarget = null; viewModel.clearConversation(target.uid) }
    }
    if (uiState.compose.isVisible) {
        NewSmsDialog(uiState.compose, viewModel::onNewSmsRecipientChange, viewModel::onNewSmsBodyChange, viewModel::sendNewSms, viewModel::dismissNewSmsDialog, onOpenLogin)
    }
}

@Composable
internal fun MessagesSolidTopBarFrame(
    title: String, showBack: Boolean, onBack: (() -> Unit)?, isScrolled: Boolean,
    onTitleClick: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, belowRow: @Composable ColumnScope.() -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val bgColor by animateColorAsState(if (isScrolled) cs.surfaceContainer else cs.surface, HiMotion.effectsDefault(), label = "MessagesTopBarBg")
    Column(Modifier.fillMaxWidth().background(bgColor)) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.hiTopSafeBar))
        HiCappedFontScale {
            Row(Modifier.fillMaxWidth().height(TOP_BAR_ROW_HEIGHT).padding(start = if (showBack && onBack != null) 4.dp else 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showBack && onBack != null) {
                    val backInteraction = remember { MutableInteractionSource() }
                    IconButton(onClick = onBack, interactionSource = backInteraction, modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(backInteraction)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_action_back), tint = cs.onSurface)
                    }
                }
                Text(
                    text = title, style = MaterialTheme.typography.titleLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick).padding(vertical = 10.dp) else Modifier),
                )
                actions()
            }
            belowRow()
        }
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
    }
}

/** 复用设计系统 [HiContentState]（保留 150ms 防闪延迟）并统一接入安全验证状态视图。 */
@Composable
internal fun MessagePaneStateBox(
    isEmpty: Boolean, isLoading: Boolean, hasLoadedOnce: Boolean, error: UiError?,
    challengeState: ChallengeState, challengeFailure: ChallengeFailureKind?, emptyMessage: String,
    contentPadding: PaddingValues, onRetry: (AccessPriority) -> Unit, onOpenLogin: () -> Unit,
    skeleton: @Composable () -> Unit, content: @Composable () -> Unit,
) {
    if (isEmpty && (challengeState !is ChallengeState.Idle || challengeFailure != null)) {
        Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) { ChallengeStatusView(challengeState, onRetry, failure = challengeFailure, errorDetails = error?.details) }
        return
    }
    val loadState: HiLoadState<Unit> = when {
        !isEmpty -> HiLoadState.Content(Unit)
        error != null -> HiLoadState.Error(stringResource(error.messageRes), error.details)
        isLoading || !hasLoadedOnce -> HiLoadState.Loading
        else -> HiLoadState.Empty
    }
    HiContentState(
        state = loadState, modifier = Modifier.fillMaxSize(), passive = true, skeleton = skeleton,
        empty = { Box(Modifier.padding(contentPadding)) { HiEmptyState(emptyMessage) } },
        error = { err -> Box(Modifier.padding(contentPadding)) { HiErrorState(err.message, details = err.details, action = error?.toStateAction(onRetry, onOpenLogin)) } },
        content = { content() },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmsConversationRow(item: SmsConversationItem, selected: Boolean, compact: Boolean, isClearing: Boolean, onClick: () -> Unit, onClearClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    var menuExpanded by remember { mutableStateOf(false) }
    val stateLabel = stringResource(if (item.isNew) R.string.messages_state_unread else R.string.messages_state_read)
    val titleColor = resolveThreadTitleColor(cs, isVisited = !item.isNew, darkTheme = HiTheme.state.darkTheme)
    val clearMenu: @Composable () -> Unit = {
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, modifier = Modifier.background(cs.surfaceContainerHigh)) {
            DropdownMenuItem(text = { Text(stringResource(R.string.messages_action_clear), color = cs.error) }, onClick = { menuExpanded = false; onClearClick() })
        }
    }

    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(resolveThreadRowBackground(cs, selected))
                .semantics { stateDescription = stateLabel }
                .combinedClickable(interaction, hiListItemPressIndication(), onClick = onClick, onLongClick = { if (!isClearing) menuExpanded = true })
                .padding(start = if (compact) 12.dp else 16.dp, top = 12.dp, bottom = 12.dp, end = if (compact) 12.dp else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            MessageAvatar(item.avatarUrl, item.author, if (compact) 36.dp else 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.isNew) Box(Modifier.size(8.dp).clip(HiShapeTokens.Full).background(cs.primary))
                    Text(item.author, style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (item.isNew) FontWeight.SemiBold else FontWeight.Normal), color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (item.time.isNotBlank()) Text(item.time, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
                Text(item.summary, style = MaterialTheme.typography.bodyMedium, color = if (item.isNew) cs.onSurface else cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!compact) {
                Box {
                    val moreInteraction = remember { MutableInteractionSource() }
                    IconButton(onClick = { menuExpanded = true }, enabled = !isClearing, interactionSource = moreInteraction, modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(moreInteraction)) {
                        if (isClearing) HiLoadingSpinner(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.MoreVert, stringResource(R.string.messages_action_more), tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    clearMenu()
                }
            }
        }
        if (compact) clearMenu()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotifyEntryRow(entry: NotifyEntry, compact: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val item = entry.item
    val unread = !entry.isRead
    val interaction = remember { MutableInteractionSource() }
    val stateLabel = stringResource(if (unread) R.string.messages_state_unread else R.string.messages_state_read)
    val titleColor = resolveThreadTitleColor(cs, isVisited = !unread, darkTheme = HiTheme.state.darkTheme)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surface)
            .semantics { stateDescription = stateLabel }
            .combinedClickable(interaction, hiListItemPressIndication(), onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top,
    ) {
        val avatarSize = if (compact) 36.dp else 40.dp
        if (item.author != null || item.avatarUrl != null) MessageAvatar(item.avatarUrl, item.author.orEmpty(), avatarSize)
        else Box(Modifier.size(avatarSize).clip(HiShapeTokens.Full).background(cs.secondaryContainer), Alignment.Center) {
            Icon(Icons.Rounded.Notifications, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (unread) Box(Modifier.size(8.dp).clip(HiShapeTokens.Full).background(cs.primary))
                Text(item.title.ifBlank { item.author.orEmpty() }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal), color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (!item.quoteOriginal.isNullOrBlank() || !item.quoteReply.isNullOrBlank()) {
                item.quoteOriginal?.trim()?.takeIf { it.isNotEmpty() }?.let { orig ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(HiShapeTokens.Small).background(cs.surfaceContainerLow)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(cs.primary.copy(alpha = 0.65f)))
                        Text(orig, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                    }
                }
                item.quoteReply?.trim()?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (unread) cs.onSurface else cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            } else if (item.info.isNotBlank()) {
                Text(item.info.trim(), style = MaterialTheme.typography.bodyMedium, color = if (unread) cs.onSurface else cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            val authorText = item.author?.trim().orEmpty()
            val timeText = item.time?.trim().orEmpty()
            if (authorText.isNotEmpty() || timeText.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (authorText.isNotEmpty()) Text(authorText, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
                    if (timeText.isNotEmpty()) Text(timeText, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
internal fun ClearConversationConfirmDialog(username: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, shape = HiShapeTokens.Large,
        title = { Text(stringResource(R.string.messages_clear_confirm_title, username)) },
        text = { Text(stringResource(R.string.messages_clear_confirm_message)) },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.defaultMinSize(48.dp, 48.dp)) { Text(stringResource(R.string.messages_action_cancel)) } },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.defaultMinSize(48.dp, 48.dp)) { Text(stringResource(R.string.messages_action_clear), color = MaterialTheme.colorScheme.error) } },
    )
}

@Composable
internal fun MessageAvatar(avatarUrl: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val initial = remember(name) { name.trim().firstOrNull()?.uppercase() ?: "?" }
    val safeUrl = remember(avatarUrl) { sanitizeMessageImageUrl(avatarUrl) }
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val context = LocalContext.current
    val imageLoader = currentHiImageLoader()
    Box(modifier.size(size).clip(HiShapeTokens.Full).background(cs.secondaryContainer), Alignment.Center) {
        Text(initial, style = if (size >= 32.dp) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer, maxLines = 1, softWrap = false)
        if (safeUrl != null) AsyncImage(remember(context, safeUrl, sizePx) { ImageRequest.Builder(context).data(safeUrl).size(sizePx).build() }, null, imageLoader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun MessagesListSkeleton(compact: Boolean, contentPadding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = if (compact) 12.dp else 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        repeat(6) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                HiSkeletonBlock(if (compact) 36.dp else 40.dp, if (compact) 36.dp else 40.dp, HiShapeTokens.Full)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { HiSkeletonBlock(120.dp, 16.dp); HiSkeletonBlock(Dp.Unspecified, 14.dp, modifier = Modifier.fillMaxWidth(0.88f)) }
            }
        }
    }
}

private fun dispatchNotificationTarget(entry: NotifyEntry, onOpenThread: (ThreadNavArgs) -> Unit, onOpenUserProfile: (uid: String, username: String) -> Unit) {
    val item = entry.item
    val tid = item.tid?.trim()?.takeIf { it.isNotEmpty() }
    if (tid != null) onOpenThread(ThreadNavArgs(tid = tid, title = item.title, author = item.author, targetPid = item.pid?.trim()?.takeIf { it.isNotEmpty() }))
    else if (item.kind == NotifyKind.FRIEND) item.authorUid?.trim()?.takeIf { it.isNotEmpty() }?.let { onOpenUserProfile(it, item.author.orEmpty()) }
}
