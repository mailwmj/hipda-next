package net.jejer.hipda.feature.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jejer.hipda.R
import net.jejer.hipda.data.local.BrowseHistoryEntity
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.state.HiEmptyState

/** 「浏览历史」页：纯离线 Room 记录、单条删除与一键清空，立即生效并可经 Snackbar 撤销恢复（零毛玻璃）。 */
@Composable
fun BrowseHistoryPane(
    viewModel: BrowseHistoryViewModel, showBack: Boolean, onBack: (() -> Unit)?,
    onOpenThread: (ThreadNavArgs) -> Unit, modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp), listState: LazyListState = rememberLazyListState(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalPaneSnackbarController.current
    val resources = LocalResources.current

    LaunchedEffect(viewModel, snackbar, resources) {
        viewModel.snackbarEvents.collect { ev ->
            if (ev is BrowseHistorySnackbarEvent.Deleted) {
                snackbar.show(resources.getString(R.string.mine_snackbar_deleted), PaneSnackbarTarget.ContentArea, resources.getString(R.string.feedback_action_undo)) { viewModel.restoreItems(ev.entities) }
            }
        }
    }
    BrowseHistoryScreenContent(uiState, modifier, showBack, onBack, onOpenThread, viewModel::deleteItem, viewModel::clearAll, contentPadding, listState)
}

@Composable
internal fun BrowseHistoryScreenContent(
    uiState: BrowseHistoryUiState, modifier: Modifier = Modifier, showBack: Boolean = false, onBack: (() -> Unit)? = null,
    onOpenThread: (ThreadNavArgs) -> Unit = {}, onDeleteItem: (BrowseHistoryEntity) -> Unit = {},
    onClearAll: () -> Unit = {}, contentPadding: PaddingValues = PaddingValues(0.dp),
    listState: LazyListState = rememberLazyListState(),
) {
    val cs = MaterialTheme.colorScheme
    val title = stringResource(R.string.mine_menu_history)
    val isScrolled by rememberListScrolled(listState)
    val listBottomPadding = rememberListBottomPadding(contentPadding)
    val deleteDesc = stringResource(R.string.mine_action_delete)

    Column(modifier.fillMaxSize().background(cs.surface).semantics { paneTitle = title; isTraversalGroup = true }) {
        MineSolidTopBar(
            title = title, isScrolled = isScrolled, showBack = showBack, onBack = onBack,
            trailingAction = if (uiState.items.isNotEmpty()) {
                {
                    val clearInt = remember { MutableInteractionSource() }
                    TextButton(onClick = onClearAll, interactionSource = clearInt, modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(clearInt)) {
                        Text(stringResource(R.string.mine_action_clear_history))
                    }
                }
            } else null,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                uiState.isLoading && uiState.items.isEmpty() -> MineListSkeleton(listBottomPadding)
                uiState.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(listBottomPadding), Alignment.Center) { HiEmptyState(stringResource(R.string.mine_empty_history)) }
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = listBottomPadding) {
                    items(items = uiState.items, key = { it.tid }) { item ->
                        val formattedTime = remember(item.lastVisitedAt) { formatHistoryTimestamp(item.lastVisitedAt) }
                        Column(Modifier.animateItem(HiMotion.effectsDefault(), HiMotion.spatialDefault(), HiMotion.exit(HiMotion.effectsDefault()))) {
                            MineDismissibleRow(
                                item.title, item.forumName, item.author, formattedTime, deleteDesc,
                                onClick = { onOpenThread(ThreadNavArgs(item.tid, item.title, item.fid.toIntOrNull() ?: 0, item.forumName.takeIf(String::isNotBlank), item.author.takeIf(String::isNotBlank))) },
                                onDismiss = { onDeleteItem(item) },
                            )
                            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}
