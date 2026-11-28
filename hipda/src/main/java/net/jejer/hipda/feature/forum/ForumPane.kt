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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import net.jejer.hipda.ui.designsystem.motion.rememberHiMotionCoroutineScope
import net.jejer.hipda.ui.designsystem.state.*
import net.jejer.hipda.ui.designsystem.topbar.rememberHiScrollToTopAction
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
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val sourceMatched = uiState.source == source
    val headerTitle = when (source) {
        ForumListSource.TodayNew -> stringResource(R.string.shell_sidebar_today_new)
        is ForumListSource.Forum -> ForumMetadataRegistry.resolve(source.fid).fullName
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
    val scrollToTop = rememberHiScrollToTopAction(listState) { topBarScrollState.expand() }
    val latestSelectedTypeId by rememberUpdatedState(uiState.selectedTypeId)
    val onSelectTypeId = remember(viewModel, listState, topBarScrollState, coroutineScope) {
        { typeId: String? ->
            val normalized = typeId?.trim()?.takeIf { it.isNotEmpty() }
            if (normalized != latestSelectedTypeId) {
                viewModel.selectTypeId(normalized)
                topBarScrollState.expand()
                coroutineScope.launch { listState.scrollToItem(0) }
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().nestedScroll(topBarScrollState.nestedScrollConnection)) {
        val compactRow = maxWidth < FORUM_COMPACT_ROW_WIDTH_THRESHOLD
        val widthPx = constraints.maxWidth.toFloat()
        val defaultTopBarHeightPx = with(LocalDensity.current) { topBarExpandedHeight.toPx() }
        val topBarHeightRef = remember { floatArrayOf(0f) }
        val motionScope = rememberHiMotionCoroutineScope()
        val latestReducedMotion by rememberUpdatedState(HiTheme.isReducedMotion)
        val latestOnSelectSource by rememberUpdatedState(onSelectSource)
        val latestWidthPx by rememberUpdatedState(widthPx)
        val latestFollowed by rememberUpdatedState(uiState.followedForums)
        val swipeState = remember(source, viewModel, motionScope) {
            val enterSign = viewModel.consumeSwipeHandoff(source).takeIf { chromeSpec.showForumTabs }
            val initPx = if (enterSign != null && widthPx > 0f && !latestReducedMotion) enterSign * widthPx else 0f
            ForumSwipeState(initPx, motionScope) { latestReducedMotion }
        }
        LaunchedEffect(swipeState) { swipeState.startPendingEnterIfNeeded() }
        LaunchedEffect(chromeSpec.showForumTabs, swipeState) { if (!chromeSpec.showForumTabs) { viewModel.clearSwipeHandoff(); swipeState.resetImmediate() } }
        DisposableEffect(swipeState) { onDispose { swipeState.resetImmediate() } }
        val onTopBarSelectSource = remember(viewModel, swipeState) {
            { target: ForumListSource -> viewModel.clearSwipeHandoff(); swipeState.resetImmediate(); latestOnSelectSource(target) }
        }
        val swipeContainerModifier = if (chromeSpec.showForumTabs) {
            Modifier.fillMaxSize().clipToBounds().forumHorizontalSwipe(
                swipeState = swipeState,
                getWidthPx = { latestWidthPx },
                getTopBarBottomPx = { ((topBarHeightRef[0].takeIf { it > 0f } ?: defaultTopBarHeightPx) - topBarScrollState.collapseOffsetPx).coerceAtLeast(0f) },
                getNeighbor = { step -> adjacentForumSource(source, latestFollowed, step) },
                onCommitSwitch = { neighbor, enterSign ->
                    if (enterSign != 0f) viewModel.armSwipeHandoff(neighbor, enterSign) else viewModel.clearSwipeHandoff()
                    viewModel.bindSource(neighbor)
                    latestOnSelectSource(neighbor)
                },
            ).graphicsLayer { translationX = swipeState.offsetPx }
        } else Modifier.fillMaxSize()

        val showChallenge = sourceMatched && uiState.items.isEmpty() && (uiState.challengeState !is ChallengeState.Idle || uiState.challengeFailure != null)

        Box(modifier = swipeContainerModifier) {
            when {
                !sourceMatched -> Box(Modifier.fillMaxSize())
                showChallenge -> Box(Modifier.fillMaxSize().padding(listContentPadding), Alignment.Center) {
                    ChallengeStatusView(uiState.challengeState, viewModel::retry, failure = uiState.challengeFailure, errorDetails = uiState.error?.details)
                }
                uiState.isLoading && uiState.items.isEmpty() -> ForumListSkeleton(compactRow, listContentPadding)
                uiState.error != null && uiState.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(listContentPadding), Alignment.Center) {
                    uiState.error.let { err -> HiErrorState(stringResource(err.messageRes), details = err.details, action = err.toStateAction(viewModel::retry, onOpenLogin)) }
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
                        val dividerStartIndent = if (compactRow) 12.dp else 68.dp
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().then(glassSourceModifier), contentPadding = listContentPadding) {
                            itemsIndexed(items = uiState.items, key = { _, item -> item.tid }) { index, item ->
                                Column(Modifier.animateItem(HiMotion.effectsDefault(), HiMotion.spatialDefault(), HiMotion.exit(HiMotion.effectsDefault()))) {
                                    val fid = (uiState.source as? ForumListSource.Forum)?.fid ?: 0
                                    val navArgs = ThreadNavArgs(item.tid, item.summary.title, fid, if (fid > 0) headerTitle else item.summary.typeName, item.summary.author)
                                    val isSelected = item.tid == selectedTid
                                    val nextIsSelected = selectedTid != null && uiState.items.getOrNull(index + 1)?.tid == selectedTid
                                    ThreadListRow(
                                        item = item,
                                        selected = isSelected,
                                        compactLayout = compactRow,
                                        referenceDate = uiState.referenceDate,
                                        onClick = { onOpenThread(navArgs) },
                                        onLongClick = { onOpenThread(navArgs.copy(openLastPage = true)) },
                                    )
                                    if (!isSelected && !nextIsSelected) {
                                        HorizontalDivider(Modifier.padding(start = dividerStartIndent), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                                    } else {
                                        Spacer(Modifier.height(0.5.dp))
                                    }
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
        }

        NewThreadsPill(
            count = if (sourceMatched) uiState.newThreadsCount else 0,
            onClick = {
                viewModel.dismissNewThreadsPill()
                scrollToTop()
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topBarExpandedHeight + 8.dp)
                .graphicsLayer { translationY = -topBarScrollState.collapseOffsetPx },
        )

        ForumTopBar(
            title = headerTitle,
            source = source,
            followedForums = uiState.followedForums,
            sortByPostTime = uiState.sortByPostTime,
            showForumTabs = chromeSpec.showForumTabs,
            glassState = topBarGlassState,
            isScrolled = { sourceMatched && uiState.items.isNotEmpty() && isListScrolled },
            scrollState = topBarScrollState,
            onSelectSource = onTopBarSelectSource,
            onNewThreadClick = onNewThreadClick,
            onSelectSortByPostTime = viewModel::setSortByPostTime,
            onManageForumsClick = onManageForumsClick,
            modifier = Modifier.align(Alignment.TopStart).onSizeChanged { topBarHeightRef[0] = it.height.toFloat() },
            selectedTypeId = if (sourceMatched) uiState.selectedTypeId else null,
            onSelectTypeId = onSelectTypeId,
            onScrollToTop = scrollToTop,
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
