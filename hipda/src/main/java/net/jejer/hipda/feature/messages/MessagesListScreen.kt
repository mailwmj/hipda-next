package net.jejer.hipda.feature.messages

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.flow.distinctUntilChanged
import net.jejer.hipda.R
import net.jejer.hipda.data.message.NotifyEntry
import net.jejer.hipda.data.network.access.*
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.parser.message.*
import net.jejer.hipda.ui.challenge.*
import net.jejer.hipda.ui.designsystem.*
import net.jejer.hipda.ui.designsystem.feedback.*
import net.jejer.hipda.ui.designsystem.motion.*
import net.jejer.hipda.ui.designsystem.state.*
import net.jejer.hipda.ui.shell.ShellSidebarEntry
import org.jsoup.Jsoup

private val TOP_BAR_ROW_HEIGHT = 56.dp
private val COMPACT_AVATAR_THRESHOLD = 300.dp
private const val ROW_SELECTED_ALPHA = 0.36f
private const val READ_TITLE_DARK_ALPHA = 0.74f
private const val NOTIFY_PREFETCH_THRESHOLD = 4

/** 消息列表主界面（纯色顶栏，零毛玻璃）：单栏含 `短消息 | 提醒` 分段标签与未读点，侧栏态隐藏标签行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesListScreen(
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
    viewModel: MessagesListViewModel,
    modifier: Modifier = Modifier,
) {
    val activeEntry = normalizeMessageEntry(entry)
    LaunchedEffect(activeEntry) { viewModel.bindEntry(activeEntry) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarController = LocalPaneSnackbarController.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel, snackbarController, resources, activeEntry) {
        viewModel.events.collect { event ->
            when (event) {
                MessagesListEvent.AllNotificationsMarkedRead ->
                    snackbarController.show(resources.getString(R.string.messages_snackbar_all_read), PaneSnackbarTarget.ContentArea)
                is MessagesListEvent.ShowError ->
                    snackbarController.showMessageUiError(event.error, resources, PaneSnackbarTarget.ContentArea, { viewModel.retry(activeEntry, it) }, onOpenLogin)
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
        if (target == ShellSidebarEntry.Notify) {
            val st = uiState.notify
            val latestHasMore by rememberUpdatedState(st.hasMore)
            LaunchedEffect(effectiveNotifyListState) {
                snapshotFlow {
                    if (!latestHasMore) return@snapshotFlow false
                    val info = effectiveNotifyListState.layoutInfo
                    info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - NOTIFY_PREFETCH_THRESHOLD
                }.distinctUntilChanged().collect { if (it) viewModel.loadMoreNotifications() }
            }
            MessageListPaneFrame(
                st.entries.isEmpty(), st.isLoading, st.isRefreshing, st.hasLoadedOnce, st.error, uiState.challengeState, st.challengeFailure,
                stringResource(R.string.common_empty_notify), effectiveNotifyListState, listBottomPadding,
                { viewModel.refresh(ShellSidebarEntry.Notify) }, { viewModel.retry(ShellSidebarEntry.Notify, it) }, onOpenLogin,
            ) { compact ->
                items(st.displayedEntries, key = { it.key }) { item ->
                    Column(Modifier.animateItem(HiMotion.effectsDefault(), HiMotion.spatialDefault(), HiMotion.exit(HiMotion.effectsDefault()))) {
                        NotifyEntryRow(item, compact) { viewModel.onNotificationOpened(item); dispatchNotificationTarget(item, onOpenThread, onOpenUserProfile) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                    }
                }
                item(key = "notify_list_footer") {
                    if (st.hasMore) Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), Alignment.Center) {
                        TextButton(onClick = viewModel::loadMoreNotifications, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(stringResource(R.string.common_action_load_more)) }
                    } else HiListFooter(HiFooterState.End)
                }
            }
        } else {
            val st = uiState.sms
            val uidCounts = remember(st.items) { st.items.groupingBy { it.uid }.eachCount() }
            MessageListPaneFrame(
                st.items.isEmpty(), st.isLoading, st.isRefreshing, st.hasLoadedOnce, st.error, uiState.challengeState, st.challengeFailure,
                stringResource(R.string.common_empty_sms), listState, listBottomPadding,
                { viewModel.refresh(ShellSidebarEntry.Sms) }, { viewModel.retry(ShellSidebarEntry.Sms, it) }, onOpenLogin,
            ) { compact ->
                itemsIndexed(st.items, key = { idx, item -> if (item.uid.isNotBlank() && uidCounts[item.uid] == 1) item.uid else "${item.uid}:${item.time}:$idx" }) { _, item ->
                    Column(Modifier.animateItem(HiMotion.effectsDefault(), HiMotion.spatialDefault(), HiMotion.exit(HiMotion.effectsDefault()))) {
                        SmsConversationRow(
                            item, !selectedConversationUid.isNullOrBlank() && item.uid == selectedConversationUid, compact, st.clearingUid == item.uid,
                            { viewModel.markConversationLocallyRead(item.uid); onOpenConversation(SmsConversationArgs(item.uid, item.author)) },
                            { clearDialogTarget = item },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                    }
                }
                item(key = "sms_list_footer") { HiListFooter(HiFooterState.End) }
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
                }
            },
            belowRow = {
                if (!isSidebar) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp)) {
                        val tabs = listOf(
                            Triple(ShellSidebarEntry.Sms, stringResource(R.string.shell_sidebar_sms), uiState.smsUnreadCount > 0),
                            Triple(ShellSidebarEntry.Notify, stringResource(R.string.shell_sidebar_notify), uiState.notifyUnreadCount > 0),
                        )
                        tabs.forEachIndexed { idx, (tabEntry, label, hasUnread) ->
                            val interaction = remember { MutableInteractionSource() }
                            SegmentedButton(
                                selected = activeEntry == tabEntry, onClick = { onSelectMessageEntry(tabEntry) },
                                shape = SegmentedButtonDefaults.itemShape(idx, tabs.size), interactionSource = interaction, icon = {},
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
}

@Composable
internal fun MessagesSolidTopBarFrame(
    title: String,
    showBack: Boolean,
    onBack: (() -> Unit)?,
    isScrolled: Boolean,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    belowRow: @Composable ColumnScope.() -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val bgColor by animateColorAsState(if (isScrolled) cs.surfaceContainer else cs.surface, HiMotion.effectsDefault(), label = "MessagesTopBarBg")
    Column(Modifier.fillMaxWidth().background(bgColor)) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
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

@Composable
internal fun MessagePaneStateBox(
    isEmpty: Boolean, isLoading: Boolean, hasLoadedOnce: Boolean, error: UiError?,
    challengeState: ChallengeState, challengeFailure: ChallengeFailureKind?, emptyMessage: String,
    contentPadding: PaddingValues, onRetry: (AccessPriority) -> Unit, onOpenLogin: () -> Unit,
    skeleton: @Composable () -> Unit, content: @Composable () -> Unit,
) {
    when {
        isEmpty && (challengeState !is ChallengeState.Idle || challengeFailure != null) ->
            Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) { ChallengeStatusView(challengeState, onRetry, failure = challengeFailure, errorDetails = error?.details) }
        (isLoading || !hasLoadedOnce) && isEmpty && error == null -> skeleton()
        error != null && isEmpty -> Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) {
            HiErrorState(stringResource(error.messageRes), details = error.details, action = error.toStateAction(onRetry, onOpenLogin))
        }
        isEmpty -> Box(Modifier.fillMaxSize().padding(contentPadding), Alignment.Center) { HiEmptyState(emptyMessage) }
        else -> content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageListPaneFrame(
    isEmpty: Boolean, isLoading: Boolean, isRefreshing: Boolean, hasLoadedOnce: Boolean, error: UiError?,
    challengeState: ChallengeState, challengeFailure: ChallengeFailureKind?, emptyMessage: String,
    listState: LazyListState, contentPadding: PaddingValues,
    onRefresh: () -> Unit, onRetry: (AccessPriority) -> Unit, onOpenLogin: () -> Unit,
    itemsContent: LazyListScope.(compact: Boolean) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < COMPACT_AVATAR_THRESHOLD
        MessagePaneStateBox(
            isEmpty, isLoading, hasLoadedOnce, error, challengeState, challengeFailure, emptyMessage, contentPadding, onRetry, onOpenLogin,
            skeleton = { MessagesListSkeleton(compact, contentPadding) },
        ) {
            PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) { itemsContent(compact) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmsConversationRow(item: SmsConversationItem, selected: Boolean, compact: Boolean, isClearing: Boolean, onClick: () -> Unit, onClearClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    var menuExpanded by remember { mutableStateOf(false) }
    val stateLabel = stringResource(if (item.isNew) R.string.messages_state_unread else R.string.messages_state_read)
    val titleColor = if (item.isNew) cs.onSurface else resolveReadTextColor(cs, HiTheme.state.darkTheme)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) cs.secondaryContainer.copy(alpha = ROW_SELECTED_ALPHA) else cs.surface)
            .semantics { stateDescription = stateLabel }
            .combinedClickable(interaction, hiListItemPressIndication(), onClick = onClick, onLongClick = { menuExpanded = true })
            .padding(start = if (compact) 12.dp else 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
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
        Box {
            val moreInteraction = remember { MutableInteractionSource() }
            IconButton(onClick = { menuExpanded = true }, enabled = !isClearing, interactionSource = moreInteraction, modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(moreInteraction)) {
                if (isClearing) HiLoadingSpinner(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.MoreVert, stringResource(R.string.messages_action_more), tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, modifier = Modifier.background(cs.surfaceContainerHigh)) {
                DropdownMenuItem(text = { Text(stringResource(R.string.messages_action_clear), color = cs.error) }, onClick = { menuExpanded = false; onClearClick() })
            }
        }
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
    val titleColor = if (unread) cs.onSurface else resolveReadTextColor(cs, HiTheme.state.darkTheme)
    val cleanInfo = remember(item.info) { if ('<' in item.info) Jsoup.parseBodyFragment(item.info).text().trim() else item.info.trim() }

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
            } else if (cleanInfo.isNotEmpty()) {
                Text(cleanInfo, style = MaterialTheme.typography.bodyMedium, color = if (unread) cs.onSurface else cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
        if (safeUrl != null) {
            val request = remember(context, safeUrl, sizePx) { ImageRequest.Builder(context).data(safeUrl).size(sizePx).build() }
            AsyncImage(request, null, imageLoader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun MessagesListSkeleton(compact: Boolean, contentPadding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = if (compact) 12.dp else 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        repeat(6) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                HiSkeletonBlock(if (compact) 36.dp else 40.dp, if (compact) 36.dp else 40.dp, HiShapeTokens.Full)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HiSkeletonBlock(120.dp, 16.dp)
                    HiSkeletonBlock(Dp.Unspecified, 14.dp, modifier = Modifier.fillMaxWidth(0.88f))
                }
            }
        }
    }
}

private fun resolveReadTextColor(cs: ColorScheme, darkTheme: Boolean): Color =
    if (darkTheme) cs.onSurfaceVariant.copy(alpha = READ_TITLE_DARK_ALPHA) else cs.onSurfaceVariant

private fun dispatchNotificationTarget(entry: NotifyEntry, onOpenThread: (ThreadNavArgs) -> Unit, onOpenUserProfile: (uid: String, username: String) -> Unit) {
    val item = entry.item
    val tid = item.tid?.trim()?.takeIf { it.isNotEmpty() }
    if (tid != null) onOpenThread(ThreadNavArgs(tid = tid, title = item.title, author = item.author, targetPid = item.pid?.trim()?.takeIf { it.isNotEmpty() }))
    else if (item.kind == NotifyKind.FRIEND) item.authorUid?.trim()?.takeIf { it.isNotEmpty() }?.let { onOpenUserProfile(it, item.author.orEmpty()) }
}
