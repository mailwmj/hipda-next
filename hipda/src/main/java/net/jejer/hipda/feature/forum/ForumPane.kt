package net.jejer.hipda.feature.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.jejer.hipda.R
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.data.threadlist.ThreadListItem
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.ui.challenge.ChallengeStatusView
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.glass.hiGlassSource
import net.jejer.hipda.ui.designsystem.state.*
import net.jejer.hipda.ui.shell.ForumMetadataRegistry
import net.jejer.hipda.ui.shell.ShellContentScope

/** 最薄可运行真实论坛列表面板（供 `E1` 原位加厚，顶层签名在此锁死）。 */
@Composable
fun ForumPane(
    fid: Int,
    chromeSpec: ForumChromeSpec,
    selectedTid: String?,
    onSelectForum: (Int) -> Unit,
    onOpenThread: (ThreadNavArgs) -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    shellContentScope: ShellContentScope? = null,
    onNewThreadClick: () -> Unit = {},
    onManageForumsClick: () -> Unit = {},
    viewModel: ForumViewModel = viewModel(),
) {
    LaunchedEffect(fid) { viewModel.bindForum(fid) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val glassSourceModifier = when {
        shellContentScope?.shellGlassState != null -> with(shellContentScope) { Modifier.shellGlassSource() }
        chromeSpec.externalGlassState != null -> Modifier.hiGlassSource(chromeSpec.externalGlassState)
        else -> Modifier
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.statusBarsPadding()) {
                Text(
                    text = uiState.forumName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (chromeSpec.showForumTabs && uiState.followedForums.isNotEmpty()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(items = uiState.followedForums, key = { it }) { tabFid ->
                            FilterChip(
                                selected = tabFid == uiState.fid,
                                onClick = { onSelectForum(tabFid) },
                                label = { Text(ForumMetadataRegistry.resolve(tabFid).fullName, maxLines = 1) },
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val showChallenge = uiState.items.isEmpty() && (uiState.challengeState !is ChallengeState.Idle || uiState.challengeFailure != null)
            when {
                showChallenge -> ChallengeStatusView(uiState.challengeState, viewModel::retry, failure = uiState.challengeFailure, errorDetails = uiState.error?.details)
                uiState.isLoading && uiState.items.isEmpty() -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(6) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { HiSkeletonBlock(260.dp, 18.dp); HiSkeletonBlock(160.dp, 14.dp) } }
                }
                uiState.error != null && uiState.items.isEmpty() -> uiState.error!!.let { err ->
                    HiErrorState(stringResource(err.messageRes), details = err.details, action = err.toStateAction(viewModel::retry, onOpenLogin))
                }
                !uiState.isLoading && uiState.items.isEmpty() -> HiEmptyState(stringResource(R.string.common_empty_threads), action = HiStateAction(stringResource(R.string.common_action_refresh)) { viewModel.refresh() })
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize().then(glassSourceModifier), contentPadding = contentPadding) {
                    items(items = uiState.items, key = { it.tid }) { item ->
                        ThreadRowItem(item, item.tid == selectedTid) {
                            onOpenThread(ThreadNavArgs(item.tid, item.summary.title, uiState.fid, uiState.forumName, item.summary.author))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    item(key = "forum_footer") {
                        when {
                            uiState.isLoadingMore -> HiListFooter(HiFooterState.Loading)
                            uiState.error != null -> HiListFooter(HiFooterState.Failed { viewModel.loadMore() })
                            uiState.hasMore -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), Alignment.Center) {
                                TextButton(onClick = { viewModel.loadMore() }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(stringResource(R.string.common_action_load_more)) }
                            }
                            else -> HiListFooter(HiFooterState.End)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadRowItem(item: ThreadListItem, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().background(if (selected) cs.secondaryContainer.copy(alpha = 0.45f) else cs.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = item.summary.title,
            style = if (item.isVisited) HiTheme.typography.threadTitleRead else HiTheme.typography.threadTitle,
            color = if (item.isVisited) cs.onSurfaceVariant else cs.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.summary.author, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(item.summary.replyCountText, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Text(item.summary.timeUpdate ?: item.summary.timeCreate, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
        }
    }
}
