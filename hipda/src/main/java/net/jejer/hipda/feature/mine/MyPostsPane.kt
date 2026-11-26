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
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.designsystem.topbar.rememberHiScrollToTopAction
import net.jejer.hipda.ui.shell.ShellSidebarEntry

/** 「我的帖子 / 我的回复」页：单栏下显示 `主题 | 回复` 双标签，宽屏侧栏态隐藏重复标签行，回复项携带 `targetPid`。 */
@Composable
fun MyPostsPane(
    viewModel: MyPostsViewModel, entry: ShellSidebarEntry, isSidebar: Boolean, showBack: Boolean,
    onBack: (() -> Unit)?, onSelectMineEntry: (ShellSidebarEntry) -> Unit,
    onOpenThread: (ThreadNavArgs) -> Unit, onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(0.dp),
    listState: LazyListState = rememberLazyListState(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalPaneSnackbarController.current
    val resources = LocalResources.current
    val entryTab = if (entry == ShellSidebarEntry.MyReplies) MyPostsTab.Replies else MyPostsTab.Threads

    LaunchedEffect(entry, isSidebar) { viewModel.selectTab(entryTab) }
    LaunchedEffect(viewModel, snackbar, resources) {
        viewModel.refreshErrors.collectLatest { err -> showErrorSnackbar(snackbar, resources, err, { viewModel.refresh(priority = it) }, onOpenLogin) }
    }

    val otherListState = rememberLazyListState()
    val activeTab = if (isSidebar) entryTab else uiState.selectedTab
    val isThreads = activeTab == MyPostsTab.Threads
    val activeListState = if (isThreads) (if (entryTab == MyPostsTab.Threads) listState else otherListState) else (if (entryTab == MyPostsTab.Replies) listState else otherListState)
    val title = stringResource(if (isSidebar && !isThreads) R.string.shell_sidebar_my_replies else R.string.shell_sidebar_my_threads)
    val isScrolled by rememberListScrolled(activeListState)
    val scrollToTop = rememberHiScrollToTopAction(activeListState)
    val openLastPageLabel = stringResource(R.string.common_action_open_last_page)

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).semantics { paneTitle = title; isTraversalGroup = true }) {
        MineSolidTopBar(title, isScrolled, showBack, onBack, onScrollToTop = scrollToTop, bottomSlot = if (!isSidebar) {
            {
                MineSegmentedTabs(listOf(stringResource(R.string.mine_tab_threads), stringResource(R.string.mine_tab_replies)), if (isThreads) 0 else 1, onSelect = {
                    val tab = if (it == 0) MyPostsTab.Threads else MyPostsTab.Replies
                    viewModel.selectTab(tab)
                    onSelectMineEntry(if (tab == MyPostsTab.Replies) ShellSidebarEntry.MyReplies else ShellSidebarEntry.MyThreads)
                })
            }
        } else null)
        MinePaginatedList(
            uiState.tabState(activeTab), uiState.challengeState,
            stringResource(if (isThreads) R.string.mine_empty_my_threads else R.string.mine_empty_my_replies),
            activeListState, contentPadding, MyPostRow::key, { viewModel.refresh(activeTab) },
            { viewModel.loadMore(activeTab) }, { viewModel.retry(activeTab, it) }, onOpenLogin, Modifier.weight(1f), uiState.isSignedIn,
        ) { item ->
            val navArgs = ThreadNavArgs(item.tid, item.title, forumName = item.forumName, targetPid = item.pid)
            MineThreadRow(
                title = item.title,
                preview = item.preview,
                forumName = item.forumName,
                time = item.time,
                onLongClickLabel = openLastPageLabel,
                onLongClick = { onOpenThread(navArgs.copy(openLastPage = true)) },
            ) {
                onOpenThread(navArgs)
            }
        }
    }
}
