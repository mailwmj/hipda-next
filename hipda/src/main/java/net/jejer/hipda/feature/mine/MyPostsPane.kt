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
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.ThreadNavArgs
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
        viewModel.refreshErrors.collect { err -> showErrorSnackbar(snackbar, resources, err, { viewModel.refresh(priority = it) }, onOpenLogin) }
    }

    val otherListState = rememberLazyListState()
    MyPostsScreenContent(
        uiState = uiState, activeTab = if (isSidebar) entryTab else uiState.selectedTab,
        isSidebar = isSidebar, showBack = showBack, onBack = onBack,
        onSelectTab = { tab -> viewModel.selectTab(tab); onSelectMineEntry(if (tab == MyPostsTab.Replies) ShellSidebarEntry.MyReplies else ShellSidebarEntry.MyThreads) },
        onRefresh = { viewModel.refresh(it) }, onLoadMore = { viewModel.loadMore(it) }, onRetry = viewModel::retry,
        onOpenThread = onOpenThread, onOpenLogin = onOpenLogin, contentPadding = contentPadding,
        threadsListState = if (entryTab == MyPostsTab.Threads) listState else otherListState,
        repliesListState = if (entryTab == MyPostsTab.Replies) listState else otherListState, modifier = modifier,
    )
}

@Composable
internal fun MyPostsScreenContent(
    uiState: MyPostsUiState, modifier: Modifier = Modifier, activeTab: MyPostsTab = uiState.selectedTab,
    isSidebar: Boolean = false, showBack: Boolean = false, onBack: (() -> Unit)? = null,
    onSelectTab: (MyPostsTab) -> Unit = {}, onRefresh: (MyPostsTab) -> Unit = {},
    onLoadMore: (MyPostsTab) -> Unit = {}, onRetry: (MyPostsTab, AccessPriority) -> Unit = { _, _ -> },
    onOpenThread: (ThreadNavArgs) -> Unit = {}, onOpenLogin: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(0.dp),
    threadsListState: LazyListState = rememberLazyListState(),
    repliesListState: LazyListState = rememberLazyListState(),
) {
    val title = stringResource(if (isSidebar && activeTab == MyPostsTab.Replies) R.string.mine_title_my_replies else R.string.mine_menu_my_posts)
    val isThreads = activeTab == MyPostsTab.Threads
    val isScrolled by rememberListScrolled(if (isThreads) threadsListState else repliesListState)

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).semantics { paneTitle = title; isTraversalGroup = true }) {
        MineSolidTopBar(
            title = title, isScrolled = isScrolled, showBack = showBack, onBack = onBack,
            bottomSlot = if (!isSidebar) {
                {
                    MineSegmentedTabs(
                        listOf(stringResource(R.string.mine_tab_threads), stringResource(R.string.mine_tab_replies)),
                        selectedIndex = if (isThreads) 0 else 1,
                        onSelect = { onSelectTab(if (it == 0) MyPostsTab.Threads else MyPostsTab.Replies) },
                    )
                }
            } else null,
        )
        if (isThreads) {
            MinePaginatedList(
                uiState.isSignedIn, uiState.threads, uiState.challengeState, stringResource(R.string.mine_empty_my_threads),
                threadsListState, contentPadding, { "thread:${it.tid}" }, { onRefresh(MyPostsTab.Threads) },
                { onLoadMore(MyPostsTab.Threads) }, { onRetry(MyPostsTab.Threads, it) }, onOpenLogin,
                itemContent = { item -> MineThreadRow(item.title, forumName = item.forumName, time = item.time) { onOpenThread(ThreadNavArgs(item.tid, item.title, forumName = item.forumName)) } },
                modifier = Modifier.weight(1f),
            )
        } else {
            MinePaginatedList(
                uiState.isSignedIn, uiState.replies, uiState.challengeState, stringResource(R.string.mine_empty_my_replies),
                repliesListState, contentPadding, { "reply:${it.tid}:${it.pid}" }, { onRefresh(MyPostsTab.Replies) },
                { onLoadMore(MyPostsTab.Replies) }, { onRetry(MyPostsTab.Replies, it) }, onOpenLogin,
                itemContent = { item -> MineThreadRow(item.title, item.replyText, item.forumName, time = item.time) { onOpenThread(ThreadNavArgs(item.tid, item.title, forumName = item.forumName, targetPid = item.pid.takeIf(String::isNotBlank))) } },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
