package net.jejer.hipda.feature.search

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.feature.mine.*
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.feedback.*
import net.jejer.hipda.ui.designsystem.motion.*
import net.jejer.hipda.ui.designsystem.state.HiStateAction
import net.jejer.hipda.ui.shell.ForumMetadataRegistry

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
        viewModel.snackbarEvents.collectLatest { ev ->
            when (ev) {
                is SearchSnackbarEvent.HistoryDeleted -> {
                    snackbar.show(resources.getString(R.string.search_snackbar_deleted), PaneSnackbarTarget.ContentArea, resources.getString(R.string.feedback_action_undo)) { viewModel.undoDeleteHistory(ev.token.id) }
                    viewModel.expireHistoryUndo(ev.token.id)
                }
                is SearchSnackbarEvent.RefreshError -> showErrorSnackbar(snackbar, resources, ev.error, viewModel::refresh, onOpenLogin)
            }
        }
    }
    SearchScreenContent(uiState, modifier, showBack, onBack, contentPadding, listState, viewModel, onOpenThread, onOpenLogin) {
        viewModel.uiState.value.let { dispatchAllowedForumLink(it.query, it.forumServer, onOpenThread, onOpenForum, onOpenUserProfile) }
    }
}

@Composable
internal fun SearchScreenContent(
    uiState: SearchUiState, modifier: Modifier = Modifier,
    showBack: Boolean = false, onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    listState: LazyListState = rememberLazyListState(),
    vm: SearchViewModel? = null, onOpenThread: (ThreadNavArgs) -> Unit = {},
    onOpenLogin: () -> Unit = {}, onOpenResolvedLink: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme; val searchPaneTitle = stringResource(R.string.shell_nav_search)
    val isScrolled by rememberListScrolled(listState)
    val topBarColor by animateColorAsState(if (isScrolled) cs.surfaceContainer else cs.surface, HiMotion.effectsDefault(), label = "SearchTopBarColor")
    val clearDesc = stringResource(R.string.search_action_clear_input)

    Column(modifier.fillMaxSize().background(cs.surface).semantics { paneTitle = searchPaneTitle; isTraversalGroup = true }) {
        Column(Modifier.fillMaxWidth().background(topBarColor)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Row(Modifier.fillMaxWidth().padding(start = if (showBack && onBack != null) 4.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showBack && onBack != null) MineIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_action_back), onBack, cs.onSurface, 24.dp)
                TextField(
                    value = uiState.query, onValueChange = { vm?.onQueryChange(it) }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.common_search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
                    trailingIcon = if (uiState.query.isNotEmpty()) { { MineIconButton(Icons.Rounded.Close, clearDesc, { vm?.clearQuery() }) } } else null,
                    shape = HiShapeTokens.Full,
                    colors = TextFieldDefaults.colors(focusedContainerColor = cs.surfaceContainerHigh, unfocusedContainerColor = cs.surfaceContainerHigh, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm?.submitSearch() }),
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                )
            }
            SearchFilterCapsulesAndPanel(uiState, clearDesc, vm)
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
        }
        if (uiState.resolvedLink != null) {
            val linkInt = remember { MutableInteractionSource() }
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(cs.primaryContainer.copy(alpha = 0.28f)).clickable(linkInt, hiListItemPressIndication(), onClick = onOpenResolvedLink).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.search_open_link), style = MaterialTheme.typography.titleSmall, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
        }
        if (!uiState.hasSubmitted) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SearchInitialContent(uiState.recentHistory, { vm?.submitHistoryQuery(it) }, { vm?.deleteHistory(it) }, rememberListBottomPadding(contentPadding), listState)
            }
        } else {
            MinePaginatedList(
                uiState.results, uiState.challengeState, stringResource(R.string.search_empty_results), listState, contentPadding,
                { "${it.tid.orEmpty()}:${it.pid.orEmpty()}:${it.title}" }, { vm?.refresh() }, { vm?.loadMore() }, { vm?.retry(it) }, onOpenLogin,
                Modifier.weight(1f), emptyAction = if (uiState.hasActiveFilters) HiStateAction(stringResource(R.string.search_action_clear_filters)) { vm?.clearFiltersAndRetry() } else null,
            ) { item ->
                MineThreadRow(item.title, item.snippet, item.forumName, item.author, item.time) {
                    val tid = item.tid?.takeIf(String::isNotBlank) ?: return@MineThreadRow
                    onOpenThread(ThreadNavArgs(tid, item.title, forumName = item.forumName, author = item.author, targetPid = item.pid?.takeIf(String::isNotBlank)))
                }
            }
        }
    }
}

@Composable
private fun SearchFilterCapsule(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, shape = HiShapeTokens.Full, interactionSource = interaction, modifier = Modifier.hiPressScale(interaction))
}

@Composable
private fun SearchFilterCapsulesAndPanel(uiState: SearchUiState, clearDesc: String, vm: SearchViewModel?) {
    val forumBase = stringResource(R.string.shell_sidebar_forums_title); val authorBase = stringResource(R.string.search_filter_author)
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        item("f") { SearchFilterCapsule(uiState.selectedForumId?.let { "$forumBase · ${ForumMetadataRegistry.resolve(it).fullName}" } ?: forumBase, uiState.selectedForumId != null || uiState.expandedFilterSection == SearchFilterSection.Forum) { vm?.toggleFilterSection(SearchFilterSection.Forum) } }
        item("s") { SearchFilterCapsule(stringResource(if (uiState.fullText) R.string.search_filter_fulltext else R.string.search_filter_title), uiState.fullText || uiState.expandedFilterSection == SearchFilterSection.Scope) { vm?.toggleFilterSection(SearchFilterSection.Scope) } }
        item("a") { SearchFilterCapsule(uiState.author.trim().takeIf(String::isNotEmpty)?.let { "$authorBase · $it" } ?: authorBase, uiState.author.isNotBlank() || uiState.expandedFilterSection == SearchFilterSection.Author) { vm?.toggleFilterSection(SearchFilterSection.Author) } }
    }
    var displayed by remember { mutableStateOf(uiState.expandedFilterSection ?: SearchFilterSection.Forum) }
    uiState.expandedFilterSection?.let { displayed = it }
    AnimatedVisibility(uiState.expandedFilterSection != null, enter = hiExpandVertically(HiMotion.spatialDefault(), expandFrom = Alignment.Top) + hiFadeIn(), exit = hiShrinkVertically(HiMotion.exit(HiMotion.spatialDefault()), shrinkTowards = Alignment.Top) + hiFadeOut()) {
        val cs = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = HiShapeTokens.Medium, color = cs.surfaceContainerLow, border = BorderStroke(0.5.dp, cs.outlineVariant)) {
            when (displayed) {
                SearchFilterSection.Forum -> {
                    val ordered = remember(uiState.followedForums) {
                        val set = uiState.followedForums.toSet()
                        ForumMetadataRegistry.resolveAll(uiState.followedForums) + ForumMetadataRegistry.BuiltInForums.filterNot { it.fid in set }
                    }
                    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item("all") { SearchFilterCapsule(stringResource(R.string.search_filter_all_forums), uiState.selectedForumId == null) { vm?.selectForum(null) } }
                        items(ordered, key = { it.fid }) { f -> SearchFilterCapsule(f.fullName, uiState.selectedForumId == f.fid) { vm?.selectForum(f.fid) } }
                    }
                }
                SearchFilterSection.Scope -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchFilterCapsule(stringResource(R.string.search_filter_title), !uiState.fullText) { vm?.selectFullText(false) }
                    SearchFilterCapsule(stringResource(R.string.search_filter_fulltext), uiState.fullText) { vm?.selectFullText(true) }
                }
                SearchFilterSection.Author -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = uiState.author, onValueChange = { vm?.onAuthorChange(it) }, singleLine = true,
                        label = { Text(authorBase) },
                        trailingIcon = if (uiState.author.isNotEmpty()) { { MineIconButton(Icons.Rounded.Close, clearDesc, { vm?.onAuthorChange("") }, iconSize = 18.dp) } } else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm?.submitSearch() }),
                        modifier = Modifier.weight(1f),
                    )
                    val submitInt = remember { MutableInteractionSource() }
                    FilledTonalButton(onClick = { vm?.submitSearch() }, shape = HiShapeTokens.Small, interactionSource = submitInt, modifier = Modifier.defaultMinSize(64.dp, 48.dp).hiPressScale(submitInt)) {
                        Text(stringResource(R.string.search_action_submit))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchInitialContent(recentHistory: List<String>, onClickHistory: (String) -> Unit, onDeleteHistory: (String) -> Unit, contentPadding: PaddingValues, listState: LazyListState) {
    if (recentHistory.isEmpty()) return
    val cs = MaterialTheme.colorScheme; val deleteDesc = stringResource(R.string.search_action_delete_history)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item("recent_header") { Text(stringResource(R.string.search_recent_title), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp).semantics { heading() }) }
        items(items = recentHistory, key = { "history:$it" }) { query ->
            val rowInt = remember { MutableInteractionSource() }
            Column(Modifier.animateItem(HiMotion.effectsDefault(), HiMotion.spatialDefault(), HiMotion.exit(HiMotion.effectsDefault()))) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(rowInt, hiListItemPressIndication()) { onClickHistory(query) }.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(query, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    MineIconButton(Icons.Rounded.Close, deleteDesc, { onDeleteHistory(query) }, iconSize = 18.dp)
                }
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f), thickness = 0.5.dp, modifier = Modifier.padding(start = 46.dp))
            }
        }
    }
}
