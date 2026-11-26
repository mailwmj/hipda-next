package net.jejer.hipda.feature.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jejer.hipda.R
import net.jejer.hipda.data.favorite.FavoriteKind
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.parser.simplelist.FavoriteThreadItem

/** 「收藏与关注」页面：纯色顶栏 + `收藏 | 关注` 双标签（各自保留滚动位）+ 即时移除与撤销恢复。 */
@Composable
fun FavoritesPane(
    viewModel: FavoritesViewModel, showBack: Boolean, onBack: (() -> Unit)?,
    onOpenThread: (ThreadNavArgs) -> Unit, onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp),
    listState: LazyListState = rememberLazyListState(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalPaneSnackbarController.current
    val resources = LocalResources.current

    LaunchedEffect(viewModel, snackbar, resources) {
        viewModel.snackbarEvents.collect { ev ->
            when (ev) {
                is FavoritesSnackbarEvent.Removed -> snackbar.show(
                    resources.getString(if (ev.kind == FavoriteKind.FAVORITE) R.string.mine_snackbar_unfavorited else R.string.mine_snackbar_unattentioned),
                    PaneSnackbarTarget.ContentArea, resources.getString(R.string.feedback_action_undo),
                ) { viewModel.restoreItem(ev.kind, ev.item, ev.originalIndex) }
                is FavoritesSnackbarEvent.ActionError -> showErrorSnackbar(snackbar, resources, ev.error, { p -> viewModel.refresh(priority = p) }, onOpenLogin)
            }
        }
    }

    FavoritesScreenContent(
        uiState, modifier, showBack, onBack, viewModel::selectKind, { viewModel.refresh(uiState.selectedKind) },
        { viewModel.loadMore(uiState.selectedKind) }, { viewModel.retry(uiState.selectedKind, it) },
        { viewModel.removeItem(uiState.selectedKind, it) }, onOpenThread, onOpenLogin, contentPadding, listState,
    )
}

@Composable
internal fun FavoritesScreenContent(
    uiState: FavoritesUiState, modifier: Modifier = Modifier, showBack: Boolean = false, onBack: (() -> Unit)? = null,
    onSelectKind: (FavoriteKind) -> Unit = {}, onRefresh: () -> Unit = {}, onLoadMore: () -> Unit = {},
    onRetry: (AccessPriority) -> Unit = {}, onRemoveItem: (FavoriteThreadItem) -> Unit = {},
    onOpenThread: (ThreadNavArgs) -> Unit = {}, onOpenLogin: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
    favoritesListState: LazyListState = rememberLazyListState(),
    attentionListState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(R.string.mine_menu_favorites)
    val isFav = uiState.selectedKind == FavoriteKind.FAVORITE
    val activeListState = if (isFav) favoritesListState else attentionListState
    val isScrolled by rememberListScrolled(activeListState)
    val removeDesc = stringResource(if (isFav) R.string.mine_action_unfavorite else R.string.mine_action_unattention)

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).semantics { paneTitle = title; isTraversalGroup = true }) {
        MineSolidTopBar(
            title = title, isScrolled = isScrolled, showBack = showBack, onBack = onBack,
            bottomSlot = {
                MineSegmentedTabs(
                    listOf(stringResource(R.string.mine_tab_favorites), stringResource(R.string.mine_tab_attention)),
                    selectedIndex = if (isFav) 0 else 1,
                    onSelect = { onSelectKind(if (it == 0) FavoriteKind.FAVORITE else FavoriteKind.ATTENTION) },
                )
            },
        )
        MinePaginatedList(
            uiState.isSignedIn, uiState.tabState(uiState.selectedKind), uiState.challengeState,
            stringResource(if (isFav) R.string.mine_empty_favorites else R.string.mine_empty_attention),
            activeListState, contentPadding, { "${uiState.selectedKind.name}:${it.tid}" },
            onRefresh, onLoadMore, onRetry, onOpenLogin,
            itemContent = { item ->
                MineDismissibleRow(item.title, item.forumName, time = item.time, dismissDesc = removeDesc, onClick = { onOpenThread(ThreadNavArgs(item.tid, item.title, forumName = item.forumName)) }, onDismiss = { onRemoveItem(item) })
            },
            modifier = Modifier.weight(1f),
        )
    }
}
