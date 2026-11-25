package net.jejer.hipda.feature.threaddetail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.jejer.hipda.R
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.ui.challenge.ChallengeStatusView
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.glass.hiGlassSource
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut
import net.jejer.hipda.ui.designsystem.state.*

private enum class DetailBodySlot { CHALLENGE, SKELETON, ERROR, EMPTY, CONTENT }

/** 帖子详情核心完整面板（M5 `E2a`，严格保持 `E0` 锁定顶层签名与 `floor:$postId` 主键，顶栏与列表为严格兄弟玻璃拓扑）。 */
@Composable
fun ThreadDetailPane(
    args: ThreadNavArgs,
    chromeSpec: DetailChromeSpec,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onOpenReply: (ReplyRequest) -> Unit = {},
    onOpenUserProfile: (uid: String, username: String) -> Unit = { _, _ -> },
    viewModel: ThreadDetailViewModel = viewModel(key = "thread:${args.tid}"),
) {
    LaunchedEffect(args.tid) { viewModel.bindArgs(args) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val detailGlassState = rememberHiGlassState()
    val isScrolledState = remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val isReducedMotion = HiTheme.isReducedMotion
    val contentAlpha = remember { Animatable(1f) }
    val initialRevision = remember { uiState.contentRevision }

    LaunchedEffect(uiState.contentRevision) {
        if (uiState.contentRevision == initialRevision) return@LaunchedEffect
        if (isReducedMotion) {
            contentAlpha.snapTo(1f)
        } else {
            contentAlpha.snapTo(0f)
            contentAlpha.animateTo(1f, animationSpec = HiMotion.effectsDefault())
        }
    }

    val pendingScroll = uiState.pendingScroll
    LaunchedEffect(pendingScroll?.id, uiState.floors) {
        if (pendingScroll == null || uiState.isLoading) return@LaunchedEffect
        val idx = pendingScroll.postId?.let { pid -> uiState.floors.indexOfFirst { it.postId == pid }.takeIf { it >= 0 } } ?: 0
        val matched = pendingScroll.postId == null || uiState.floors.getOrNull(idx)?.postId == pendingScroll.postId
        listState.scrollToItem(idx, if (matched) pendingScroll.scrollOffset else 0)
        viewModel.consumePendingScroll(pendingScroll.id)
    }

    val cs = MaterialTheme.colorScheme
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val hasActiveFilter = uiState.filter !is ThreadDetailFilterUi.All
    val topClearance = statusBarTop + ThreadDetailTopBarHeight + if (hasActiveFilter) FilterCapsuleBarHeight else 0.dp
    val showChallenge = uiState.floors.isEmpty() && (uiState.challengeState !is ChallengeState.Idle || uiState.challengeFailure != null)
    val bodySlot = when {
        showChallenge -> DetailBodySlot.CHALLENGE
        uiState.isLoading && uiState.floors.isEmpty() -> DetailBodySlot.SKELETON
        uiState.error != null && uiState.floors.isEmpty() -> DetailBodySlot.ERROR
        !uiState.isLoading && uiState.floors.isEmpty() -> DetailBodySlot.EMPTY
        else -> DetailBodySlot.CONTENT
    }

    Surface(modifier = modifier.fillMaxSize(), color = cs.background, contentColor = cs.onBackground) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // 严格兄弟节点 1：正文内容区（仅 LazyColumn 挂载 detailGlassState，内含不透明底色防止未模糊文字穿透）
                AnimatedContent(
                    targetState = bodySlot,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = { (hiFadeIn() togetherWith hiFadeOut()).using(null) },
                    contentAlignment = Alignment.TopCenter,
                    label = "ThreadDetailBodySlot",
                ) { slot ->
                    when (slot) {
                        DetailBodySlot.CHALLENGE -> Box(Modifier.fillMaxSize().padding(top = topClearance), Alignment.Center) {
                            ChallengeStatusView(uiState.challengeState, viewModel::retry, failure = uiState.challengeFailure, errorDetails = uiState.error?.details)
                        }
                        DetailBodySlot.SKELETON -> ThreadDetailSkeleton(topClearance)
                        DetailBodySlot.ERROR -> Box(Modifier.fillMaxSize().padding(top = topClearance), Alignment.Center) {
                            uiState.error?.let { err ->
                                HiErrorState(stringResource(err.messageRes), details = err.details, action = err.toStateAction(viewModel::retry, onOpenLogin))
                            }
                        }
                        DetailBodySlot.EMPTY -> Box(Modifier.fillMaxSize().padding(top = topClearance), Alignment.Center) {
                            val action = if (hasActiveFilter) {
                                HiStateAction(stringResource(R.string.thread_detail_action_view_all), viewModel::clearFilter)
                            } else {
                                HiStateAction(stringResource(R.string.common_action_refresh)) { viewModel.retry() }
                            }
                            HiEmptyState(stringResource(R.string.common_empty_floors), action = action)
                        }
                        DetailBodySlot.CONTENT -> LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .hiGlassSource(detailGlassState)
                                .background(cs.background)
                                .graphicsLayer { alpha = contentAlpha.value },
                            contentPadding = PaddingValues(top = topClearance, bottom = if (uiState.lastPage > 1) 8.dp else contentPadding.calculateBottomPadding() + 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            items(items = uiState.floors, key = { "floor:${it.postId}" }) { item ->
                                val isOp = item.floor.floor == 1 || (uiState.threadAuthorId != null && item.floor.uid == uiState.threadAuthorId)
                                PostFloorCard(item, isOp, listState, onOpenImageViewer, onOpenUserProfile)
                                HorizontalDivider(Modifier.widthIn(max = PostMaxContentWidth).fillMaxWidth(), color = cs.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }

                // 严格兄弟节点 2：透明液态玻璃顶栏（HiGlassMarker == 1）+ 过滤胶囊
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                    ThreadDetailTopBar(
                        title = uiState.title.ifBlank { args.title },
                        authorName = uiState.threadAuthorName ?: args.author,
                        chromeSpec = chromeSpec,
                        isAuthorOnly = uiState.filter is ThreadDetailFilterUi.AuthorOnly,
                        isScrolledState = isScrolledState,
                        glassState = detailGlassState,
                        onBack = onBack,
                        onToggleAuthorOnly = {
                            val anchorPid = uiState.floors.getOrNull(listState.firstVisibleItemIndex)?.postId
                            viewModel.toggleAuthorOnly(anchorPid, listState.firstVisibleItemScrollOffset)
                        },
                    )
                    FilterCapsuleBar(uiState.filter, viewModel::clearFilter)
                }
            }

            if (uiState.lastPage > 1) {
                ThreadDetailPaginationBar(uiState.page, uiState.lastPage, uiState.isLoading, contentPadding.calculateBottomPadding(), viewModel::previousPage, viewModel::nextPage)
            }
        }
    }
}

@Composable
private fun ThreadDetailSkeleton(topPadding: Dp) {
    Box(Modifier.fillMaxSize().padding(top = topPadding), Alignment.TopCenter) {
        Column(Modifier.widthIn(max = PostMaxContentWidth).fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            repeat(4) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HiSkeletonBlock(36.dp, 36.dp, shape = HiShapeTokens.Full)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { HiSkeletonBlock(110.dp, 14.dp); HiSkeletonBlock(84.dp, 12.dp) }
                    }
                    HiSkeletonBlock(Dp.Unspecified, 16.dp, modifier = Modifier.fillMaxWidth())
                    HiSkeletonBlock(Dp.Unspecified, 16.dp, modifier = Modifier.fillMaxWidth(0.82f))
                    HiSkeletonBlock(Dp.Unspecified, 16.dp, modifier = Modifier.fillMaxWidth(0.55f))
                }
            }
        }
    }
}

@Composable
private fun ThreadDetailPaginationBar(
    page: Int,
    lastPage: Int,
    isLoading: Boolean,
    bottomPadding: Dp,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    HorizontalDivider(color = cs.outlineVariant)
    Row(
        modifier = Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 12.dp, vertical = 4.dp).padding(bottom = bottomPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPreviousPage, enabled = page > 1 && !isLoading, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(stringResource(R.string.common_page_prev)) }
        Text(stringResource(R.string.common_page_indicator, page, lastPage), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        TextButton(onClick = onNextPage, enabled = page < lastPage && !isLoading, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(stringResource(R.string.common_page_next)) }
    }
}
