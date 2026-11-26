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
import kotlinx.coroutines.flow.collectLatest
import net.jejer.hipda.R
import net.jejer.hipda.data.favorite.FavoriteKind
import net.jejer.hipda.feature.common.*

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
        viewModel.snackbarEvents.collectLatest { ev ->
            when (ev) {
                is FavoritesSnackbarEvent.Removed -> {
                    val msgRes = if (ev.token.kind == FavoriteKind.FAVORITE) R.string.mine_snackbar_unfavorited else R.string.mine_snackbar_unattentioned
                    snackbar.show(resources.getString(msgRes), PaneSnackbarTarget.ContentArea, resources.getString(R.string.feedback_action_undo)) { viewModel.undoRemove(ev.token.id) }
                    viewModel.expireUndo(ev.token.id)
                }
                is FavoritesSnackbarEvent.ActionError -> showErrorSnackbar(snackbar, resources, ev.error, { viewModel.refresh(priority = it) }, onOpenLogin)
            }
        }
    }

    FavoritesScreenContent(uiState, modifier, showBack, onBack, contentPadding, listState, vm = viewModel, onOpenThread = onOpenThread, onOpenLogin = onOpenLogin)
}

@Composable
internal fun FavoritesScreenContent(
    uiState: FavoritesUiState, modifier: Modifier = Modifier, showBack: Boolean = false, onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    favoritesListState: LazyListState = rememberLazyListState(),
    attentionListState: LazyListState = rememberLazyListState(),
    vm: FavoritesViewModel? = null,
    onOpenThread: (ThreadNavArgs) -> Unit = {}, onOpenLogin: () -> Unit = {},
) {
    val title = stringResource(R.string.shell_sidebar_favorites_and_attention)
    val isFav = uiState.selectedKind == FavoriteKind.FAVORITE
    val activeListState = if (isFav) favoritesListState else attentionListState
    val isScrolled by rememberListScrolled(activeListState)
    val removeDesc = stringResource(if (isFav) R.string.mine_action_unfavorite else R.string.mine_action_unattention)

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).semantics { paneTitle = title; isTraversalGroup = true }) {
        MineSolidTopBar(title, isScrolled, showBack, onBack, bottomSlot = {
            MineSegmentedTabs(listOf(stringResource(R.string.mine_tab_favorites), stringResource(R.string.mine_tab_attention)), if (isFav) 0 else 1, onSelect = {
                vm?.selectKind(if (it == 0) FavoriteKind.FAVORITE else FavoriteKind.ATTENTION)
            })
        })
        MinePaginatedList(
            uiState.tabState(uiState.selectedKind), uiState.challengeState,
            stringResource(if (isFav) R.string.mine_empty_favorites else R.string.mine_empty_attention),
            activeListState, contentPadding, { "${uiState.selectedKind.name}:${it.tid}" },
            { vm?.refresh(uiState.selectedKind) }, { vm?.loadMore(uiState.selectedKind) },
            { vm?.retry(uiState.selectedKind, it) }, onOpenLogin, Modifier.weight(1f), uiState.isSignedIn,
        ) { item ->
            MineThreadRow(item.title, forumName = item.forumName, time = item.time, dismissDesc = removeDesc, onDismiss = { vm?.removeItem(uiState.selectedKind, item) }) {
                onOpenThread(ThreadNavArgs(item.tid, item.title, forumName = item.forumName))
            }
        }
    }
}
