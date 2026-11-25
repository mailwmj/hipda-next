package net.jejer.hipda.feature.forum

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.ui.challenge.ChallengeStatusView
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.glass.hiGlassSource
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.state.*
import net.jejer.hipda.ui.shell.ForumMetadataRegistry
import net.jejer.hipda.ui.shell.ShellContentScope

private const val FORUM_PREFETCH_THRESHOLD: Int = 4

/** 完整论坛/今日新帖列表面板（保持 `E0` 锁定顶层签名，透明顶栏与 `LazyColumn` 严格兄弟拓扑）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForumPane(
    source: ForumListSource,
    chromeSpec: ForumChromeSpec,
    selectedTid: String?,
    onSelectSource: (ForumListSource) -> Unit,
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
    LaunchedEffect(source) { viewModel.bindSource(source) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val headerTitle = when (val s = uiState.source) {
        ForumListSource.TodayNew -> stringResource(R.string.shell_sidebar_today_new)
        is ForumListSource.Forum -> ForumMetadataRegistry.resolve(s.fid).fullName
    }

    val snackbarController = LocalPaneSnackbarController.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel, snackbarController, resources) {
        viewModel.refreshErrors.collect { err ->
            val onAction = err.action?.let { act ->
                { when (act) { UiErrorAction.Retry -> viewModel.retry(AccessPriority.UserBlocking); UiErrorAction.Login -> onOpenLogin(); UiErrorAction.Reverify -> viewModel.retry(AccessPriority.UserExplicit) } }
            }
            snackbarController.show(resources.getString(err.messageRes), PaneSnackbarTarget.ContentArea, err.action?.let { resources.getString(it.labelRes) }, onAction)
        }
    }

    val topBarScrollState = rememberForumTopBarScrollState(collapsible = chromeSpec.showForumTabs)
    LaunchedEffect(source) { topBarScrollState.expand() }

    val topBarGlassState = if (!chromeSpec.useGlassTopBar) null
        else shellContentScope?.shellGlassState ?: chromeSpec.externalGlassState ?: rememberHiGlassState()

    val topBarExpandedHeight = forumTopBarExpandedHeight(chromeSpec.showForumTabs)
    val layoutDir = LocalLayoutDirection.current
    val listContentPadding = remember(contentPadding, topBarExpandedHeight, layoutDir) {
        PaddingValues(contentPadding.calculateStartPadding(layoutDir), topBarExpandedHeight + contentPadding.calculateTopPadding(), contentPadding.calculateEndPadding(layoutDir), contentPadding.calculateBottomPadding())
    }
    var isListScrolled by remember(listState) {
        mutableStateOf(Snapshot.withoutReadObservation { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 })
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
            .distinctUntilChanged()
            .collect { isListScrolled = it }
    }
    val coroutineScope = rememberCoroutineScope()
    val reducedMotion = HiTheme.isReducedMotion

    BoxWithConstraints(modifier = modifier.fillMaxSize().nestedScroll(topBarScrollState.nestedScrollConnection)) {
        val compactRow = maxWidth < FORUM_COMPACT_ROW_WIDTH_THRESHOLD
        val showChallenge = uiState.items.isEmpty() && (uiState.challengeState !is ChallengeState.Idle || uiState.challengeFailure != null)

        when {
            showChallenge -> Box(Modifier.fillMaxSize().padding(listContentPadding), Alignment.Center) {
                ChallengeStatusView(uiState.challengeState, viewModel::retry, failure = uiState.challengeFailure, errorDetails = uiState.error?.details)
            }
            uiState.isLoading && uiState.items.isEmpty() -> ForumListSkeleton(compactRow, listContentPadding)
            uiState.error != null && uiState.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(listContentPadding), Alignment.Center) {
                uiState.error!!.let { err -> HiErrorState(stringResource(err.messageRes), details = err.details, action = err.toStateAction(viewModel::retry, onOpenLogin)) }
            }
            !uiState.isLoading && uiState.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(listContentPadding), Alignment.Center) {
                HiEmptyState(stringResource(R.string.common_empty_threads), action = HiStateAction(stringResource(R.string.common_action_refresh)) { viewModel.refresh() })
            }
            else -> {
                val pullState = rememberPullToRefreshState()
                val glassSourceModifier = if (shellContentScope?.shellGlassState != null) {
                    with(shellContentScope) { Modifier.shellGlassSource() }
                } else {
                    (chromeSpec.externalGlassState ?: topBarGlassState)?.let(Modifier::hiGlassSource) ?: Modifier
                }
                val latestUiState by rememberUpdatedState(uiState)
                LaunchedEffect(listState, uiState.source) {
                    snapshotFlow {
                        val s = latestUiState
                        if (!s.hasMore || s.isLoading || s.isRefreshing || s.isLoadingMore || s.error != null) return@snapshotFlow false
                        if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) return@snapshotFlow false
                        val info = listState.layoutInfo
                        info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - FORUM_PREFETCH_THRESHOLD
                    }.distinctUntilChanged().collect { if (it) viewModel.loadMore() }
                }

                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    state = pullState,
                    modifier = Modifier.fillMaxSize(),
                    indicator = { PullToRefreshDefaults.Indicator(pullState, uiState.isRefreshing, Modifier.align(Alignment.TopCenter).padding(top = topBarExpandedHeight)) },
                ) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().then(glassSourceModifier), contentPadding = listContentPadding) {
                        items(items = uiState.items, key = { it.tid }) { item ->
                            Column(Modifier.animateItem(HiMotion.effectsDefault(), HiMotion.spatialDefault(), HiMotion.exit(HiMotion.effectsDefault()))) {
                                ThreadListRow(item, item.tid == selectedTid, compactRow, onClick = {
                                    val fid = (uiState.source as? ForumListSource.Forum)?.fid ?: 0
                                    onOpenThread(ThreadNavArgs(item.tid, item.summary.title, fid, if (fid > 0) headerTitle else item.summary.typeName, item.summary.author))
                                })
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                            }
                        }
                        item(key = "forum_footer") {
                            when {
                                uiState.isLoadingMore -> HiListFooter(HiFooterState.Loading)
                                uiState.error != null -> HiListFooter(HiFooterState.Failed { viewModel.loadMore() })
                                uiState.hasMore -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), Alignment.Center) {
                                    TextButton(onClick = { viewModel.loadMore() }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                                        Text(stringResource(R.string.common_action_load_more))
                                    }
                                }
                                else -> HiListFooter(HiFooterState.End)
                            }
                        }
                    }
                }
            }
        }

        NewThreadsPill(
            count = uiState.newThreadsCount,
            onClick = {
                viewModel.dismissNewThreadsPill()
                topBarScrollState.expand()
                coroutineScope.launch { if (reducedMotion) listState.scrollToItem(0) else listState.animateScrollToItem(0) }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topBarExpandedHeight + 8.dp)
                .graphicsLayer { translationY = -topBarScrollState.collapseOffsetPx },
        )

        ForumTopBar(
            title = headerTitle,
            source = uiState.source,
            followedForums = uiState.followedForums,
            sortByPostTime = uiState.sortByPostTime,
            showForumTabs = chromeSpec.showForumTabs,
            glassState = topBarGlassState,
            isScrolled = { uiState.items.isNotEmpty() && isListScrolled },
            scrollState = topBarScrollState,
            onSelectSource = onSelectSource,
            onNewThreadClick = onNewThreadClick,
            onSelectSortByPostTime = viewModel::setSortByPostTime,
            onManageForumsClick = onManageForumsClick,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

@Composable
private fun ForumListSkeleton(compactLayout: Boolean, contentPadding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = if (compactLayout) 12.dp else 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        repeat(6) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                if (!compactLayout) HiSkeletonBlock(40.dp, 40.dp, HiShapeTokens.Full)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HiSkeletonBlock(Dp.Unspecified, 18.dp, modifier = Modifier.fillMaxWidth(0.92f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (compactLayout) HiSkeletonBlock(20.dp, 20.dp, HiShapeTokens.Full)
                        HiSkeletonBlock(76.dp, 14.dp)
                        HiSkeletonBlock(48.dp, 14.dp)
                    }
                }
            }
        }
    }
}
