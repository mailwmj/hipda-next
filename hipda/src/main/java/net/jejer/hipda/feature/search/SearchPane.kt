package net.jejer.hipda.feature.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jejer.hipda.R
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.data.search.ResolvedForumLink
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.feature.mine.*
import net.jejer.hipda.ui.challenge.ChallengeStatusView
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.feedback.hiListItemPressIndication
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.*
import net.jejer.hipda.ui.designsystem.state.*
import net.jejer.hipda.ui.shell.ForumMetadataRegistry

@Immutable
internal data class SearchCallbacks(
    val onQueryChange: (String) -> Unit = {}, val onClearQuery: () -> Unit = {}, val onSubmit: () -> Unit = {},
    val onToggleFilter: (SearchFilterSection) -> Unit = {}, val onSelectForum: (Int?) -> Unit = {},
    val onSelectFullText: (Boolean) -> Unit = {}, val onAuthorChange: (String) -> Unit = {},
    val onOpenResolvedLink: () -> Unit = {}, val onClickHistory: (String) -> Unit = {},
    val onDeleteHistory: (String) -> Unit = {}, val onRefresh: () -> Unit = {}, val onLoadMore: () -> Unit = {},
    val onRetry: (AccessPriority) -> Unit = {}, val onClearFilters: () -> Unit = {},
    val onOpenThread: (ThreadNavArgs) -> Unit = {}, val onOpenLogin: () -> Unit = {},
)

/** 新版搜索页（product-design.md §3、§7.7；纯色顶栏，零毛玻璃）。 */
@Composable
fun SearchPane(
    viewModel: SearchViewModel, showBack: Boolean, onBack: (() -> Unit)?,
    onOpenThread: (ThreadNavArgs) -> Unit, onOpenForum: (fid: Int) -> Unit,
    onOpenUserProfile: (uid: String, username: String) -> Unit, onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp),
    listState: LazyListState = rememberLazyListState(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalPaneSnackbarController.current
    val resources = LocalResources.current

    LaunchedEffect(viewModel, snackbar, resources) {
        viewModel.snackbarEvents.collect { ev ->
            when (ev) {
                is SearchSnackbarEvent.HistoryDeleted -> snackbar.show(
                    resources.getString(R.string.search_snackbar_deleted), PaneSnackbarTarget.ContentArea, resources.getString(R.string.feedback_action_undo),
                ) { viewModel.restoreHistory(ev.entry) }
                is SearchSnackbarEvent.RefreshError -> showErrorSnackbar(snackbar, resources, ev.error, viewModel::refresh, onOpenLogin)
            }
        }
    }

    val callbacks = remember(viewModel, onOpenThread, onOpenForum, onOpenUserProfile, onOpenLogin) {
        SearchCallbacks(
            onQueryChange = viewModel::onQueryChange, onClearQuery = viewModel::clearQuery,
            onSubmit = { viewModel.submitSearch() }, onToggleFilter = viewModel::toggleFilterSection,
            onSelectForum = viewModel::selectForum, onSelectFullText = viewModel::selectFullText,
            onAuthorChange = viewModel::onAuthorChange,
            onOpenResolvedLink = { viewModel.uiState.value.let { dispatchAllowedForumLink(it.query, it.forumServer, onOpenThread, onOpenForum, onOpenUserProfile) } },
            onClickHistory = viewModel::submitHistoryQuery, onDeleteHistory = viewModel::deleteHistory,
            onRefresh = { viewModel.refresh() }, onLoadMore = { viewModel.loadMore() },
            onRetry = viewModel::retry, onClearFilters = viewModel::clearFiltersAndRetry,
            onOpenThread = onOpenThread, onOpenLogin = onOpenLogin,
        )
    }
    SearchScreenContent(uiState, modifier, callbacks, showBack, onBack, contentPadding, listState)
}

@Composable
internal fun SearchScreenContent(
    uiState: SearchUiState, modifier: Modifier = Modifier, callbacks: SearchCallbacks = SearchCallbacks(),
    showBack: Boolean = false, onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    listState: LazyListState = rememberLazyListState(),
) {
    val cs = MaterialTheme.colorScheme
    val searchPaneTitle = stringResource(R.string.search_title)
    val isScrolled by rememberListScrolled(listState)
    val topBarColor by animateColorAsState(if (isScrolled) cs.surfaceContainer else cs.surface, HiMotion.effectsDefault(), label = "SearchTopBarColor")
    val listBottomPadding = rememberListBottomPadding(contentPadding)

    Column(modifier.fillMaxSize().background(cs.surface).semantics { paneTitle = searchPaneTitle; isTraversalGroup = true }) {
        Column(Modifier.fillMaxWidth().background(topBarColor)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            SearchBarRow(uiState.query, showBack && onBack != null, onBack, callbacks)
            SearchFilterCapsuleRow(uiState, callbacks.onToggleFilter)
            SearchCollapsibleFilterPanel(uiState, callbacks)
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
        }
        uiState.resolvedLink?.let { link ->
            OpenForumLinkRow(link, callbacks.onOpenResolvedLink)
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val showChallenge = uiState.items.isEmpty() && (uiState.challengeState !is ChallengeState.Idle || uiState.challengeFailure != null)
            when {
                !uiState.hasSubmitted -> SearchInitialContent(uiState.recentHistory, callbacks.onClickHistory, callbacks.onDeleteHistory, listBottomPadding, listState)
                showChallenge -> Box(Modifier.fillMaxSize().padding(listBottomPadding), Alignment.Center) {
                    ChallengeStatusView(uiState.challengeState, callbacks.onRetry, failure = uiState.challengeFailure, errorDetails = uiState.error?.details)
                }
                uiState.isLoading && uiState.items.isEmpty() -> MineListSkeleton(listBottomPadding)
                uiState.error != null && uiState.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(listBottomPadding), Alignment.Center) {
                    HiErrorState(stringResource(uiState.error.messageRes), details = uiState.error.details, action = uiState.error.toStateAction(callbacks.onRetry, callbacks.onOpenLogin))
                }
                !uiState.isLoading && uiState.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(listBottomPadding), Alignment.Center) {
                    HiEmptyState(
                        stringResource(R.string.search_empty_results),
                        action = if (uiState.hasActiveFilters) HiStateAction(stringResource(R.string.search_action_clear_filters), callbacks.onClearFilters) else null,
                    )
                }
                else -> SearchResultsList(uiState, listState, listBottomPadding, callbacks)
            }
        }
    }
}

@Composable
private fun SearchBarRow(query: String, showBack: Boolean, onBack: (() -> Unit)?, callbacks: SearchCallbacks) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = if (showBack) 4.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showBack && onBack != null) {
            val backInt = remember { MutableInteractionSource() }
            IconButton(onClick = onBack, interactionSource = backInt, modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(backInt)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_action_back), tint = cs.onSurface)
            }
        }
        TextField(
            value = query, onValueChange = callbacks.onQueryChange, singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
            trailingIcon = if (query.isNotEmpty()) { { ClearInputButton(callbacks.onClearQuery, 20.dp) } } else null,
            shape = HiShapeTokens.Full,
            colors = TextFieldDefaults.colors(focusedContainerColor = cs.surfaceContainerHigh, unfocusedContainerColor = cs.surfaceContainerHigh, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { callbacks.onSubmit() }),
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
        )
    }
}

@Composable
private fun ClearInputButton(onClick: () -> Unit, iconSize: androidx.compose.ui.unit.Dp) {
    val interaction = remember { MutableInteractionSource() }
    IconButton(onClick = onClick, interactionSource = interaction, modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(interaction)) {
        Icon(Icons.Rounded.Close, stringResource(R.string.search_action_clear_input), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun SearchFilterCapsuleRow(uiState: SearchUiState, onToggle: (SearchFilterSection) -> Unit) {
    val forumLabel = uiState.selectedForumId?.let { "${stringResource(R.string.search_filter_forum)} · ${ForumMetadataRegistry.resolve(it).fullName}" } ?: stringResource(R.string.search_filter_forum)
    val scopeLabel = stringResource(if (uiState.fullText) R.string.search_filter_fulltext else R.string.search_filter_title)
    val authorLabel = uiState.author.trim().takeIf(String::isNotEmpty)?.let { "${stringResource(R.string.search_filter_author)} · $it" } ?: stringResource(R.string.search_filter_author)
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        item("f") { SearchFilterCapsule(forumLabel, uiState.selectedForumId != null || uiState.expandedFilterSection == SearchFilterSection.Forum) { onToggle(SearchFilterSection.Forum) } }
        item("s") { SearchFilterCapsule(scopeLabel, uiState.fullText || uiState.expandedFilterSection == SearchFilterSection.Scope) { onToggle(SearchFilterSection.Scope) } }
        item("a") { SearchFilterCapsule(authorLabel, uiState.author.isNotBlank() || uiState.expandedFilterSection == SearchFilterSection.Author) { onToggle(SearchFilterSection.Author) } }
    }
}

@Composable
private fun SearchFilterCapsule(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, shape = HiShapeTokens.Full, interactionSource = interaction, modifier = Modifier.hiPressScale(interaction))
}

@Composable
private fun SearchCollapsibleFilterPanel(uiState: SearchUiState, callbacks: SearchCallbacks) {
    var displayed by remember { mutableStateOf(uiState.expandedFilterSection ?: SearchFilterSection.Forum) }
    uiState.expandedFilterSection?.let { displayed = it }
    AnimatedVisibility(
        visible = uiState.expandedFilterSection != null,
        enter = hiExpandVertically(HiMotion.spatialDefault(), expandFrom = Alignment.Top) + hiFadeIn(),
        exit = hiShrinkVertically(HiMotion.exit(HiMotion.spatialDefault()), shrinkTowards = Alignment.Top) + hiFadeOut(),
    ) {
        val cs = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = HiShapeTokens.Medium, color = cs.surfaceContainerLow, border = BorderStroke(0.5.dp, cs.outlineVariant)) {
            when (displayed) {
                SearchFilterSection.Forum -> {
                    val ordered = remember(uiState.followedForums) {
                        val set = uiState.followedForums.toSet()
                        ForumMetadataRegistry.resolveAll(uiState.followedForums) + ForumMetadataRegistry.BuiltInForums.filterNot { it.fid in set }
                    }
                    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item("all") { SearchFilterCapsule(stringResource(R.string.search_filter_all_forums), uiState.selectedForumId == null) { callbacks.onSelectForum(null) } }
                        items(ordered, key = { it.fid }) { f -> SearchFilterCapsule(f.fullName, uiState.selectedForumId == f.fid) { callbacks.onSelectForum(f.fid) } }
                    }
                }
                SearchFilterSection.Scope -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchFilterCapsule(stringResource(R.string.search_filter_title), !uiState.fullText) { callbacks.onSelectFullText(false) }
                    SearchFilterCapsule(stringResource(R.string.search_filter_fulltext), uiState.fullText) { callbacks.onSelectFullText(true) }
                }
                SearchFilterSection.Author -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = uiState.author, onValueChange = callbacks.onAuthorChange, singleLine = true,
                        label = { Text(stringResource(R.string.search_filter_author)) },
                        trailingIcon = if (uiState.author.isNotEmpty()) { { ClearInputButton({ callbacks.onAuthorChange("") }, 18.dp) } } else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { callbacks.onSubmit() }),
                        modifier = Modifier.weight(1f),
                    )
                    val submitInt = remember { MutableInteractionSource() }
                    FilledTonalButton(onClick = callbacks.onSubmit, shape = HiShapeTokens.Small, interactionSource = submitInt, modifier = Modifier.defaultMinSize(64.dp, 48.dp).hiPressScale(submitInt)) {
                        Text(stringResource(R.string.search_action_submit))
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenForumLinkRow(link: AllowedForumLink, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val subtitle = when (val t = link.target) {
        is ResolvedForumLink.Forum -> stringResource(R.string.search_link_forum, ForumMetadataRegistry.resolve(t.fid).fullName)
        is ResolvedForumLink.Thread -> stringResource(R.string.search_link_thread, t.tid)
        is ResolvedForumLink.ThreadLastPage -> stringResource(R.string.search_link_thread, t.tid)
        is ResolvedForumLink.Post -> stringResource(R.string.search_link_thread, t.tid.orEmpty())
        is ResolvedForumLink.User -> stringResource(R.string.search_link_user, t.uid)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).background(cs.primaryContainer.copy(alpha = 0.28f))
            .clickable(interaction, hiListItemPressIndication(), onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = cs.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.search_open_link), style = MaterialTheme.typography.titleSmall, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SearchInitialContent(recentHistory: List<String>, onClickHistory: (String) -> Unit, onDeleteHistory: (String) -> Unit, contentPadding: PaddingValues, listState: LazyListState) {
    if (recentHistory.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item("recent_header") {
            Text(stringResource(R.string.search_recent_title), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp).semantics { heading() })
        }
        items(items = recentHistory, key = { "history:$it" }) { query ->
            val rowInt = remember { MutableInteractionSource() }; val delInt = remember { MutableInteractionSource() }
            Column(Modifier.animateItem(HiMotion.effectsDefault(), HiMotion.spatialDefault(), HiMotion.exit(HiMotion.effectsDefault()))) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(rowInt, hiListItemPressIndication()) { onClickHistory(query) }.padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(query, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onDeleteHistory(query) }, interactionSource = delInt, modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(delInt)) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.search_action_delete_history), tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f), thickness = 0.5.dp, modifier = Modifier.padding(start = 46.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchResultsList(uiState: SearchUiState, listState: LazyListState, contentPadding: PaddingValues, callbacks: SearchCallbacks) {
    MinePrefetchEffect(listState, { uiState.hasMore && !uiState.isLoading && !uiState.isRefreshing && !uiState.isLoadingMore && uiState.error == null }, callbacks.onLoadMore)
    PullToRefreshBox(isRefreshing = uiState.isRefreshing, onRefresh = callbacks.onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            items(items = uiState.items, key = { "${it.tid.orEmpty()}:${it.pid.orEmpty()}:${it.title}" }) { item ->
                MineThreadRow(item.title, item.snippet, item.forumName, item.author, item.time) {
                    val tid = item.tid?.takeIf(String::isNotBlank) ?: return@MineThreadRow
                    callbacks.onOpenThread(ThreadNavArgs(tid, item.title, forumName = item.forumName, author = item.author, targetPid = item.pid?.takeIf(String::isNotBlank)))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
            }
            item("search_footer") { MineListFooterItem(uiState.isLoadingMore, uiState.error != null, uiState.hasMore, callbacks.onLoadMore) }
        }
    }
}
