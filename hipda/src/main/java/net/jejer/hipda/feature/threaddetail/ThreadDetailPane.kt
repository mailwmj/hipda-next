package net.jejer.hipda.feature.threaddetail

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.jejer.hipda.R
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.feature.threaddetail.media.AboveViewportHeightCompensationAuthority
import net.jejer.hipda.feature.threaddetail.media.LocalAboveViewportHeightCompensationAuthority
import net.jejer.hipda.ui.challenge.ChallengeStatusView
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.glass.hiGlassSource
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut
import net.jejer.hipda.ui.designsystem.state.*

private enum class DetailBodySlot { CHALLENGE, SKELETON, ERROR, EMPTY, CONTENT }

private const val FLOOR_KEY_PREFIX = "floor:"
private const val KEY_PREPEND_HEADER = "thread_prepend_header"
private const val KEY_APPEND_FOOTER = "thread_append_footer"
private const val PREFETCH_FLOOR_THRESHOLD = 5

private fun firstVisibleFloor(listState: LazyListState): LazyListItemInfo? {
    val visible = listState.layoutInfo.visibleItemsInfo
    return visible.firstOrNull { it.offset + it.size > 0 && (it.key as? String)?.startsWith(FLOOR_KEY_PREFIX) == true }
        ?: visible.firstOrNull { (it.key as? String)?.startsWith(FLOOR_KEY_PREFIX) == true }
}

/**
 * 详情列表同帧单一滚动权威控制器（闭合 `R-BATCH5 B4-E2a ①` 与 `V-E2b` E2b+E2d 同帧竞态）：
 * - 严禁依赖滞后一帧的 `LaunchedEffect` 回滚；
 * - 以不可变 `LazyListLayoutInfo` 引用作为未重测帧纪元，同一帧内统一仲裁向上插页锚定与首楼越顶图片
 *   高度变化补偿：无论先插页后图变高、先图变高后插页、还是同楼层多图同帧完成，均合成唯一最终
 *   `(targetIndex, targetOffset)`，既不丢失补偿也不跨帧重复补偿；
 * - 若原首可见楼层上方露出了顶部状态行（`offset > 0`），先同帧落位至 `offset = 0`，再于本帧
 *   `onGloballyPositioned` 通过 `dispatchRawDelta` 同步回滚差值并触发同帧重测，保证合入首帧偏差 `≤ 1dp`。
 */
private class PrependAnchorController(initialGeneration: Long, initialHeader: Boolean, initialEpoch: Long) {
    private var handledGeneration: Long = initialGeneration
    private var lastShowHeader: Boolean = initialHeader
    private var lastResetEpoch: Long = initialEpoch
    private var pendingFloorKey: String? = null
    private var pendingTargetOffsetPx: Int = 0
    private var activeLayoutInfo: LazyListLayoutInfo? = null
    private var activeAnchorKey: String? = null
    private var activeTargetIndex: Int = -1
    private var activeTargetScrollOffset: Int = 0
    private var activeImageDeltaFloorKey: String? = null
    private var activeImageDeltaPx: Int = 0

    private fun clearPending() {
        pendingFloorKey = null
        pendingTargetOffsetPx = 0
    }

    private fun clearFrameAuthorityState() {
        activeAnchorKey = null
        activeTargetIndex = -1
        activeTargetScrollOffset = 0
        activeImageDeltaFloorKey = null
        activeImageDeltaPx = 0
    }

    /** 每次 `measureAndLayout` 产生新的 `LazyListLayoutInfo` 实例后自动失效上一帧增量，彻底杜绝跨帧重复补偿。 */
    private fun ensureLayoutEpoch(layoutInfo: LazyListLayoutInfo) {
        if (activeLayoutInfo !== layoutInfo) {
            activeLayoutInfo = layoutInfo
            clearFrameAuthorityState()
        }
    }

    fun onAboveViewportImageHeightDelta(floorPostId: String, deltaPx: Int, listState: LazyListState) {
        val layoutInfo = listState.layoutInfo
        ensureLayoutEpoch(layoutInfo)
        val floorKey = "$FLOOR_KEY_PREFIX$floorPostId"
        val floorItem = layoutInfo.visibleItemsInfo.firstOrNull { it.key == floorKey } ?: return

        val baseIndex: Int
        val baseOffset: Int
        if (activeAnchorKey != null) {
            if (activeAnchorKey != floorKey) return
            baseIndex = activeTargetIndex
            baseOffset = activeTargetScrollOffset
        } else {
            if (listState.firstVisibleItemIndex != floorItem.index) return
            baseIndex = floorItem.index
            baseOffset = listState.firstVisibleItemScrollOffset
        }

        if (activeImageDeltaFloorKey == floorKey) {
            activeImageDeltaPx += deltaPx
        } else {
            activeImageDeltaFloorKey = floorKey
            activeImageDeltaPx = deltaPx
        }
        val nextOffset = (baseOffset + deltaPx).coerceAtLeast(0)
        if (activeAnchorKey != null) activeTargetScrollOffset = nextOffset
        listState.requestScrollToItem(index = baseIndex, scrollOffset = nextOffset)
    }

    fun syncBeforeMeasure(uiState: ThreadDetailUiState, listState: LazyListState) {
        val layoutInfo = listState.layoutInfo
        ensureLayoutEpoch(layoutInfo)
        val genChanged = uiState.prependGeneration != handledGeneration
        val sameSession = uiState.resetEpoch == lastResetEpoch
        val headerChanged = sameSession && uiState.showPrependHeader != lastShowHeader && uiState.prependError == null && uiState.pendingScroll == null
        handledGeneration = uiState.prependGeneration
        lastShowHeader = uiState.showPrependHeader
        lastResetEpoch = uiState.resetEpoch
        if (!sameSession) clearFrameAuthorityState()
        if (!genChanged && !headerChanged) return
        if (uiState.floors.isEmpty()) { clearPending(); return }
        val visibleItems = layoutInfo.visibleItemsInfo
        val anchorItem = firstVisibleFloor(listState) ?: return
        val anchorKey = anchorItem.key as String
        val newFloorIndex = uiState.floors.indexOfFirst { it.postId == anchorKey.removePrefix(FLOOR_KEY_PREFIX) }
        if (newFloorIndex < 0) return

        val newListIndex = (if (uiState.showPrependHeader) 1 else 0) + newFloorIndex
        val anchorOffset = anchorItem.offset
        if (anchorOffset <= 0) {
            clearPending()
            val sameFrameImageDelta = if (activeImageDeltaFloorKey == anchorKey) activeImageDeltaPx else 0
            val targetScrollOffset = (-anchorOffset + sameFrameImageDelta).coerceAtLeast(0)
            activeAnchorKey = anchorKey
            activeTargetIndex = newListIndex
            activeTargetScrollOffset = targetScrollOffset
            val primaryKey = visibleItems.firstOrNull { it.offset + it.size > 0 }?.key
            if (sameFrameImageDelta != 0 || !(primaryKey == anchorKey && listState.isScrollInProgress)) {
                listState.requestScrollToItem(index = newListIndex, scrollOffset = targetScrollOffset)
            }
        } else {
            pendingFloorKey = anchorKey
            pendingTargetOffsetPx = anchorOffset
            activeAnchorKey = anchorKey
            activeTargetIndex = newListIndex
            activeTargetScrollOffset = 0
            listState.requestScrollToItem(index = newListIndex, scrollOffset = 0)
        }
    }

    fun applyPendingPositiveOffset(listState: LazyListState) {
        val floorKey = pendingFloorKey ?: return
        val targetOffset = pendingTargetOffsetPx
        if (targetOffset <= 0) { clearPending(); return }
        val currentItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == floorKey } ?: return
        val delta = targetOffset - currentItem.offset
        clearPending()
        if (delta > 0 && listState.canScrollBackward) listState.dispatchRawDelta(-delta.toFloat())
    }
}

private fun resolveFloorIndex(item: LazyListItemInfo?, floors: List<ThreadDetailFloorItem>, headerCount: Int): Int {
    val key = item?.key as? String ?: return -1
    if (!key.startsWith(FLOOR_KEY_PREFIX)) return -1
    val postId = key.removePrefix(FLOOR_KEY_PREFIX)
    val guessed = item.index - headerCount
    return if (floors.getOrNull(guessed)?.postId == postId) guessed else floors.indexOfFirst { it.postId == postId }
}

private data class ScrollWindow(
    val resetEpoch: Long,
    val prependGeneration: Long,
    val firstFloorIndex: Int,
    val firstOffset: Int,
    val lastFloorIndex: Int,
    val totalFloors: Int,
    val hasPrev: Boolean,
    val hasNext: Boolean,
    val ready: Boolean,
    val hasPendingScroll: Boolean,
)

/** 帖子详情核心完整面板（M5 `E2a`/`E2d`，严格保持 `E0` 锁定顶层签名与 `floor:$postId` 主键，支持双向连续加载与同帧零闪跳锚定）。 */
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
    val latestUiState by rememberUpdatedState(uiState)
    val detailGlassState = rememberHiGlassState()
    val isScrolledState = remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val visiblePage by remember(listState) {
        derivedStateOf {
            val s = latestUiState
            val floorIdx = resolveFloorIndex(firstVisibleFloor(listState), s.floors, if (s.showPrependHeader) 1 else 0)
            (s.floors.getOrNull(floorIdx)?.floor?.page?.takeIf { it > 0 } ?: s.page).coerceIn(1, s.lastPage.coerceAtLeast(1))
        }
    }
    LaunchedEffect(visiblePage) { viewModel.onVisiblePageChanged(visiblePage) }

    val anchorController = remember(listState, uiState.tid) {
        PrependAnchorController(uiState.prependGeneration, uiState.showPrependHeader, uiState.resetEpoch)
    }
    val scrollCompensationAuthority = remember(anchorController, listState) {
        AboveViewportHeightCompensationAuthority { floorPostId, deltaPx ->
            anchorController.onAboveViewportImageHeightDelta(floorPostId, deltaPx, listState)
        }
    }
    val floorPositionedModifier = remember(anchorController, listState) {
        Modifier.onGloballyPositioned { anchorController.applyPendingPositiveOffset(listState) }
    }
    val pagingNestedScroll = remember(viewModel) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val s = latestUiState
                if (!s.isLoading && s.pendingScroll == null && s.floors.isNotEmpty()) {
                    if (available.y > 0f && s.hasPreviousPage) viewModel.loadPreviousPage()
                    else if (available.y < 0f && s.hasNextPage) viewModel.loadNextPage()
                }
                return Offset.Zero
            }
        }
    }

    val pendingScroll = uiState.pendingScroll
    LaunchedEffect(pendingScroll?.id, uiState.floors) {
        if (pendingScroll == null || uiState.isLoading) return@LaunchedEffect
        val idx = pendingScroll.postId?.let { pid -> uiState.floors.indexOfFirst { it.postId == pid }.takeIf { it >= 0 } } ?: 0
        val matched = pendingScroll.postId == null || uiState.floors.getOrNull(idx)?.postId == pendingScroll.postId
        listState.scrollToItem((if (uiState.showPrependHeader) 1 else 0) + idx, if (matched) pendingScroll.scrollOffset else 0)
        viewModel.consumePendingScroll(pendingScroll.id)
    }

    LaunchedEffect(uiState.prependError) {
        if (uiState.prependError != null && uiState.showPrependHeader && listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset == 0) {
            listState.scrollToItem(0, 0)
        }
    }

    LaunchedEffect(listState) {
        var prevEpoch = -1L; var prevPrependGen = -1L; var prevHadPending = false; var prevPos: Pair<Int, Int>? = null
        snapshotFlow {
            val s = latestUiState
            val visible = listState.layoutInfo.visibleItemsInfo
            val header = if (s.showPrependHeader) 1 else 0
            val firstFloor = firstVisibleFloor(listState)
            val lastFloor = visible.lastOrNull { (it.key as? String)?.startsWith(FLOOR_KEY_PREFIX) == true }
            val firstIdx = resolveFloorIndex(firstFloor, s.floors, header)
            ScrollWindow(
                resetEpoch = s.resetEpoch, prependGeneration = s.prependGeneration,
                firstFloorIndex = firstIdx, firstOffset = firstFloor?.let { (-it.offset).coerceAtLeast(0) } ?: 0,
                lastFloorIndex = resolveFloorIndex(lastFloor, s.floors, header), totalFloors = s.floors.size,
                hasPrev = s.hasPreviousPage, hasNext = s.hasNextPage,
                ready = !s.isLoading && s.pendingScroll == null && s.floors.isNotEmpty() && firstIdx >= 0,
                hasPendingScroll = s.pendingScroll != null,
            )
        }.collect { w ->
            if (!w.ready) { if (w.hasPendingScroll) prevHadPending = true; return@collect }
            val curPos = w.firstFloorIndex to w.firstOffset
            if (w.resetEpoch != prevEpoch || prevHadPending || w.prependGeneration != prevPrependGen) {
                prevEpoch = w.resetEpoch; prevPrependGen = w.prependGeneration; prevHadPending = false; prevPos = curPos
                return@collect
            }
            val prev = prevPos; prevPos = curPos
            val movedDown = prev != null && (curPos.first > prev.first || (curPos.first == prev.first && curPos.second > prev.second))
            val movedUp = prev != null && (curPos.first < prev.first || (curPos.first == prev.first && curPos.second < prev.second))
            if (movedDown && w.hasNext && w.lastFloorIndex >= w.totalFloors - 1 - PREFETCH_FLOOR_THRESHOLD) viewModel.loadNextPage()
            if (movedUp && w.hasPrev && w.firstFloorIndex <= PREFETCH_FLOOR_THRESHOLD) viewModel.loadPreviousPage()
        }
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
                        DetailBodySlot.CONTENT -> {
                            SideEffect { anchorController.syncBeforeMeasure(uiState, listState) }
                            CompositionLocalProvider(LocalAboveViewportHeightCompensationAuthority provides scrollCompensationAuthority) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize().nestedScroll(pagingNestedScroll).hiGlassSource(detailGlassState).background(cs.background),
                                    contentPadding = PaddingValues(top = topClearance, bottom = if (uiState.lastPage > 1) 8.dp else contentPadding.calculateBottomPadding() + 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (uiState.showPrependHeader) {
                                        item(key = KEY_PREPEND_HEADER) {
                                            val headState = if (uiState.isPrepending) HiFooterState.Loading else HiFooterState.Failed { viewModel.retryPrepend() }
                                            HiListFooter(headState, modifier = Modifier.widthIn(max = PostMaxContentWidth))
                                        }
                                    }
                                    items(items = uiState.floors, key = { "$FLOOR_KEY_PREFIX${it.postId}" }) { item ->
                                        val isOp = item.floor.floor == 1 || (uiState.threadAuthorId != null && item.floor.uid == uiState.threadAuthorId)
                                        PostFloorCard(item, isOp, listState, onOpenImageViewer, onOpenUserProfile, modifier = floorPositionedModifier)
                                        HorizontalDivider(Modifier.widthIn(max = PostMaxContentWidth).fillMaxWidth(), color = cs.outlineVariant.copy(alpha = 0.5f))
                                    }
                                    val tailFooter = when {
                                        uiState.isAppending -> HiFooterState.Loading
                                        uiState.appendError != null -> HiFooterState.Failed { viewModel.retryAppend() }
                                        !uiState.hasNextPage -> HiFooterState.End
                                        else -> null
                                    }
                                    if (tailFooter != null) {
                                        item(key = KEY_APPEND_FOOTER) { HiListFooter(tailFooter, modifier = Modifier.widthIn(max = PostMaxContentWidth)) }
                                    }
                                }
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
                            val anchorItem = firstVisibleFloor(listState)
                            val anchorPid = (anchorItem?.key as? String)?.removePrefix(FLOOR_KEY_PREFIX) ?: uiState.floors.getOrNull(listState.firstVisibleItemIndex)?.postId
                            viewModel.toggleAuthorOnly(anchorPid, anchorItem?.let { (-it.offset).coerceAtLeast(0) } ?: listState.firstVisibleItemScrollOffset)
                        },
                    )
                    FilterCapsuleBar(uiState.filter, viewModel::clearFilter)
                }
            }

            if (uiState.lastPage > 1) {
                ThreadDetailPaginationBar(
                    page = visiblePage, lastPage = uiState.lastPage, isLoading = uiState.isLoading,
                    bottomPadding = contentPadding.calculateBottomPadding(),
                    onPreviousPage = { viewModel.onVisiblePageChanged(visiblePage); viewModel.previousPage() },
                    onNextPage = { viewModel.onVisiblePageChanged(visiblePage); viewModel.nextPage() },
                )
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
