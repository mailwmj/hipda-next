package net.jejer.hipda.feature.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.Extras
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jejer.hipda.R
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.hiListItemPressIndication
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut
import net.jejer.hipda.ui.designsystem.state.*
import net.jejer.hipda.ui.designsystem.topbar.HiSolidTopBar
import net.jejer.hipda.ui.designsystem.topbar.rememberHiScrollToTopAction

/** 用户资料页（纯色顶栏零毛玻璃；固定资料头 + `资料 | TA 的帖子` 双标签同级交叉淡入淡出）。 */
@Composable
fun UserProfileScreen(
    viewModel: UserProfileViewModel,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenThread: (ThreadNavArgs) -> Unit,
    onOpenSmsConversation: (SmsConversationArgs) -> Unit,
    onOpenLogin: () -> Unit,
    contentPadding: PaddingValues,
    onOpenExternalLink: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalPaneSnackbarController.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val addedFmt = stringResource(R.string.profile_blacklist_added)
    val removedFmt = stringResource(R.string.profile_blacklist_removed)
    val undoLabel = stringResource(R.string.settings_action_undo)
    val loginLabel = stringResource(R.string.common_action_login)
    val loginPrompt = stringResource(R.string.settings_action_login_prompt)
    var activeSnackbarJob by remember { mutableStateOf<Job?>(null) }
    fun showDetailSnackbar(msg: String, action: String? = null, onAction: (() -> Unit)? = null) {
        activeSnackbarJob?.cancel()
        activeSnackbarJob = scope.launch { snackbar.show(msg, PaneSnackbarTarget.DetailPane, action, onAction) }
    }

    UserProfileScreenContent(
        uiState, showBack, onBack, viewModel::selectTab, viewModel::loadProfile, viewModel::loadThreadsFirstPage, viewModel::loadNextThreadsPage,
        onOpenSms = { onOpenSmsConversation(SmsConversationArgs(uiState.uid, uiState.displayUsername.ifBlank { uiState.uid })) },
        onToggleBlacklist = {
            activeSnackbarJob?.cancel(); activeSnackbarJob = null
            viewModel.toggleBlacklist(
                onSuccess = { token ->
                    showDetailSnackbar(String.format(if (token.wasAdded) addedFmt else removedFmt, token.username), undoLabel) {
                        viewModel.undoBlacklistMutation(token) { showDetailSnackbar(context.getString(it.messageRes)) }
                    }
                },
                onError = { err -> if (err.action == UiErrorAction.Login) showDetailSnackbar(loginPrompt, loginLabel, onOpenLogin) else showDetailSnackbar(context.getString(err.messageRes)) },
            )
        },
        onOpenThread, onOpenLogin, contentPadding, onOpenExternalLink, modifier,
        onShowMessage = { msg -> showDetailSnackbar(msg) },
    )
}

private val AvatarReloadNonceKey = Extras.Key(default = 0)

internal suspend fun evictProfileAvatarCache(imageLoader: ImageLoader, avatarUrl: String) {
    imageLoader.memoryCache?.let { memCache ->
        val matchingKeys = memCache.keys.filter { it.key == avatarUrl }
        matchingKeys.forEach { memCache.remove(it) }
        memCache.remove(MemoryCache.Key(avatarUrl))
    }
    withContext(Dispatchers.IO) {
        imageLoader.diskCache?.remove(avatarUrl)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreenContent(
    uiState: UserProfileUiState,
    showBack: Boolean,
    onBack: () -> Unit,
    onSelectTab: (UserProfileTab) -> Unit,
    onRetryProfile: (AccessPriority) -> Unit,
    onRetryThreads: (AccessPriority) -> Unit,
    onLoadMoreThreads: () -> Unit,
    onOpenSms: () -> Unit,
    onToggleBlacklist: () -> Unit,
    onOpenThread: (ThreadNavArgs) -> Unit,
    onOpenLogin: () -> Unit,
    contentPadding: PaddingValues,
    onOpenExternalLink: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    infoListState: LazyListState = rememberLazyListState(),
    threadsListState: LazyListState = rememberLazyListState(),
    onShowMessage: ((String) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val snackbar = LocalPaneSnackbarController.current
    val scope = rememberCoroutineScope()
    var localSnackbarJob by remember { mutableStateOf<Job?>(null) }
    val showMessage: (String) -> Unit = onShowMessage ?: { msg ->
        localSnackbarJob?.cancel()
        localSnackbarJob = scope.launch { snackbar.show(msg, PaneSnackbarTarget.DetailPane) }
    }
    val safeLinkOpener: (String) -> Unit = remember(onOpenExternalLink, uriHandler) {
        { rawUrl -> ProfileUrlSanitizer.sanitizeProfileLinkUrl(rawUrl)?.let { safe -> if (onOpenExternalLink != null) onOpenExternalLink(safe) else runCatching { uriHandler.openUri(safe) } } }
    }
    val activeListState = if (uiState.selectedTab == UserProfileTab.Info) infoListState else threadsListState
    val scrolled by remember(activeListState) { derivedStateOf { activeListState.firstVisibleItemIndex > 0 || activeListState.firstVisibleItemScrollOffset > 0 } }
    val scrollToTop = rememberHiScrollToTopAction(activeListState)
    val title = uiState.displayUsername.ifBlank { stringResource(R.string.profile_uid_format, uiState.uid) }

    Column(modifier.fillMaxSize().background(cs.surface)) {
        HiSolidTopBar(
            title = title,
            isScrolled = scrolled,
            showBack = showBack,
            onBack = if (showBack) onBack else null,
            onScrollToTop = scrollToTop,
            titleHorizontalPadding = if (showBack) 4.dp else 0.dp,
            endPadding = if (showBack) 4.dp else 16.dp,
        )
        ProfileFixedHeader(uiState, onOpenSms, onToggleBlacklist, showMessage)
        PrimaryTabRow(if (uiState.selectedTab == UserProfileTab.Info) 0 else 1, containerColor = cs.surface, contentColor = cs.primary) {
            Tab(uiState.selectedTab == UserProfileTab.Info, { onSelectTab(UserProfileTab.Info) }, Modifier.defaultMinSize(minHeight = 48.dp), text = { Text(stringResource(R.string.profile_tab_info)) })
            Tab(uiState.selectedTab == UserProfileTab.Threads, { onSelectTab(UserProfileTab.Threads) }, Modifier.defaultMinSize(minHeight = 48.dp), text = { Text(stringResource(R.string.profile_tab_threads)) })
        }
        AnimatedContent(uiState.selectedTab, Modifier.weight(1f).fillMaxWidth(), transitionSpec = { (hiFadeIn() togetherWith hiFadeOut()).using(null) }, label = "UserProfileTabContent") { tab ->
            when (tab) {
                UserProfileTab.Info -> ProfileInfoTab(uiState, infoListState, contentPadding, onRetryProfile, onOpenLogin, safeLinkOpener)
                UserProfileTab.Threads -> ProfileThreadsTab(uiState, threadsListState, contentPadding, onRetryThreads, onLoadMoreThreads, onOpenThread, onOpenLogin)
            }
        }
    }
}

@Composable
private fun ProfileFixedHeader(
    uiState: UserProfileUiState,
    onOpenSms: () -> Unit,
    onToggleBlacklist: () -> Unit,
    onShowMessage: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val displayName = uiState.displayUsername.ifBlank { stringResource(R.string.profile_uid_format, uiState.uid) }
    val safeAvatar = remember(uiState.avatarUrl) { ProfileUrlSanitizer.sanitizeProfileImageUrl(uiState.avatarUrl) }
    val sizePx = with(LocalDensity.current) { 64.dp.roundToPx() }
    val context = LocalContext.current
    val imageLoader = currentHiImageLoader()
    val scope = rememberCoroutineScope()
    val refreshedMsg = stringResource(R.string.profile_avatar_refreshed)
    val notSetMsg = stringResource(R.string.profile_avatar_not_set)
    val avatarDesc = stringResource(R.string.profile_avatar_content_desc)
    val refreshAvatarLabel = stringResource(R.string.profile_action_refresh_avatar)
    var avatarReloadNonce by remember(safeAvatar) { mutableIntStateOf(0) }
    var lastAvatarPainter by remember(safeAvatar) { mutableStateOf<Painter?>(null) }
    val avatarInt = remember { MutableInteractionSource() }

    Column(Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .hiPressScale(avatarInt)
                    .clip(HiShapeTokens.Full)
                    .background(cs.primaryContainer)
                    .semantics { contentDescription = avatarDesc }
                    .clickable(
                        interactionSource = avatarInt,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = refreshAvatarLabel,
                    ) {
                        if (safeAvatar == null) {
                            onShowMessage(notSetMsg)
                        } else {
                            scope.launch {
                                evictProfileAvatarCache(imageLoader, safeAvatar)
                                avatarReloadNonce += 1
                                onShowMessage(refreshedMsg)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(displayName.trim().firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.headlineSmall, color = cs.onPrimaryContainer)
                if (safeAvatar != null) {
                    val avatarRequest = remember(context, safeAvatar, sizePx, avatarReloadNonce) {
                        ImageRequest.Builder(context)
                            .data(safeAvatar)
                            .size(sizePx)
                            .memoryCacheKey(safeAvatar)
                            .diskCacheKey(safeAvatar)
                            .apply {
                                if (avatarReloadNonce > 0) {
                                    memoryCachePolicy(CachePolicy.WRITE_ONLY)
                                    diskCachePolicy(CachePolicy.WRITE_ONLY)
                                    extras[AvatarReloadNonceKey] = avatarReloadNonce
                                }
                            }
                            .build()
                    }
                    key(avatarReloadNonce) {
                        AsyncImage(
                            model = avatarRequest,
                            contentDescription = null,
                            imageLoader = imageLoader,
                            modifier = Modifier.fillMaxSize(),
                            placeholder = lastAvatarPainter,
                            onSuccess = { lastAvatarPainter = it.painter },
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(displayName, style = MaterialTheme.typography.titleLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (uiState.isOnline) Surface(shape = HiShapeTokens.Full, color = cs.primaryContainer) { Text(stringResource(R.string.profile_status_online), style = MaterialTheme.typography.labelSmall, color = cs.onPrimaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
                }
                Text((listOf(stringResource(R.string.profile_uid_format, uiState.uid)) + uiState.headerStats).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val smsInt = remember { MutableInteractionSource() }
            FilledTonalButton(onOpenSms, Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).hiPressScale(smsInt), shape = HiShapeTokens.Small, interactionSource = smsInt) { Text(stringResource(R.string.profile_action_sms), maxLines = 1) }
            val blInt = remember { MutableInteractionSource() }
            OutlinedButton(onToggleBlacklist, Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).hiPressScale(blInt), enabled = !uiState.isBlacklistMutating && uiState.displayUsername.isNotBlank(), shape = HiShapeTokens.Small, interactionSource = blInt) {
                if (uiState.isBlacklistMutating) HiLoadingSpinner(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(if (uiState.isBlocked) R.string.profile_action_remove_blacklist else R.string.profile_action_add_blacklist), maxLines = 1)
            }
        }
    }
}

@Composable
private fun ProfileInfoTab(uiState: UserProfileUiState, listState: LazyListState, contentPadding: PaddingValues, onRetry: (AccessPriority) -> Unit, onOpenLogin: () -> Unit, onLinkClick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    HiContentState(
        state = uiState.profileLoadState, modifier = Modifier.fillMaxSize(),
        skeleton = { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { HiSkeletonBlock(120.dp, 18.dp); HiSkeletonBlock(240.dp, 16.dp); HiSkeletonBlock(200.dp, 16.dp) } },
        empty = { HiEmptyState(stringResource(R.string.profile_empty_info)) },
        error = { err -> HiErrorState(stringResource(uiState.profileError?.messageRes ?: R.string.common_error_load_failed), action = uiState.profileError?.toStateAction(onRetry, onOpenLogin), details = err.details) },
    ) { parsed ->
        val context = LocalContext.current
        val imageLoader = currentHiImageLoader()
        LazyColumn(state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(parsed.sections, key = { idx, it -> "${idx}:${it.title.orEmpty()}" }) { _, section ->
                Surface(Modifier.fillMaxWidth(), shape = HiShapeTokens.Medium, color = cs.surfaceContainerLow, border = BorderStroke(0.5.dp, cs.outlineVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        section.title?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.semantics { heading() }) }
                        section.lines.forEach { line ->
                            val annotated = remember(line, cs.primary, onLinkClick) { ProfileUrlSanitizer.buildSafeAnnotatedText(line, cs.primary, onLinkClick) }
                            if (annotated.isNotEmpty()) Text(annotated, style = HiTheme.typography.postBody, color = cs.onSurface, modifier = Modifier.fillMaxWidth())
                            remember(line) { ProfileUrlSanitizer.extractSafeSignatureImageUrls(line) }.forEach { imgUrl ->
                                AsyncImage(remember(context, imgUrl) { ImageRequest.Builder(context).data(imgUrl).build() }, null, imageLoader, Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(HiShapeTokens.Small), contentScale = ContentScale.Fit)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileThreadsTab(uiState: UserProfileUiState, listState: LazyListState, contentPadding: PaddingValues, onRetry: (AccessPriority) -> Unit, onLoadMore: () -> Unit, onOpenThread: (ThreadNavArgs) -> Unit, onOpenLogin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    HiContentState(
        state = uiState.threadsLoadState, modifier = Modifier.fillMaxSize(),
        skeleton = { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) { repeat(4) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { HiSkeletonBlock(260.dp, 18.dp); HiSkeletonBlock(140.dp, 14.dp) } } } },
        empty = { HiEmptyState(stringResource(R.string.profile_empty_threads)) },
        error = { err -> HiErrorState(stringResource(uiState.threadsError?.messageRes ?: R.string.common_error_load_failed), action = uiState.threadsError?.toStateAction(onRetry, onOpenLogin), details = err.details) },
    ) { items ->
        val shouldLoadMore by remember(listState, uiState.hasNextThreadsPage, uiState.isLoadingNextThreadsPage, uiState.nextThreadsPageFailed) {
            derivedStateOf {
                val total = listState.layoutInfo.totalItemsCount
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                uiState.hasNextThreadsPage && !uiState.isLoadingNextThreadsPage && !uiState.nextThreadsPageFailed && total > 0 && last >= total - 3
            }
        }
        LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) onLoadMore() }

        val haptics = LocalHapticFeedback.current
        val openLastPageLabel = stringResource(R.string.common_action_open_last_page)
        LazyColumn(state = listState, contentPadding = PaddingValues(top = 4.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(items, key = { idx, it -> "${idx}:${it.tid.orEmpty()}:${it.pid.orEmpty()}" }) { _, item ->
                val tid = item.tid?.trim().orEmpty()
                val navArgs = ThreadNavArgs(tid, item.title, forumName = item.forumName, author = item.author ?: uiState.displayUsername.ifBlank { null }, targetPid = item.pid)
                Column(
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = hiListItemPressIndication(),
                            enabled = tid.isNotEmpty(),
                            onClick = { onOpenThread(navArgs) },
                            onLongClickLabel = openLastPageLabel,
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onOpenThread(navArgs.copy(openLastPage = true))
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(item.title, style = HiTheme.typography.threadTitle, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(item.forumName?.trim()?.takeIf(String::isNotEmpty), item.time?.trim()?.takeIf(String::isNotEmpty)).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
                }
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.45f), thickness = 0.5.dp)
            }
            item("threads_footer") {
                val footer = when {
                    uiState.isLoadingNextThreadsPage -> HiFooterState.Loading
                    uiState.nextThreadsPageFailed -> HiFooterState.Failed(onLoadMore)
                    !uiState.hasNextThreadsPage -> HiFooterState.End
                    else -> null
                }
                if (footer != null) HiListFooter(footer)
            }
        }
    }
}
