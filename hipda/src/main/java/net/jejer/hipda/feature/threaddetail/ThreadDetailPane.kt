package net.jejer.hipda.feature.threaddetail

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.data.favorite.FavoriteKind
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.feature.compose.*
import net.jejer.hipda.feature.routing.DefaultPostLinkDispatcher
import net.jejer.hipda.feature.threaddetail.chrome.ReadingPositionAnchor
import net.jejer.hipda.feature.threaddetail.chrome.ThreadBottomToolbar
import net.jejer.hipda.feature.threaddetail.chrome.ThreadBottomToolbarClearance
import net.jejer.hipda.feature.threaddetail.chrome.buildSafeThreadUrl
import net.jejer.hipda.feature.threaddetail.chrome.copyThreadLinkPlainText
import net.jejer.hipda.feature.threaddetail.chrome.openExternalHttpUrl
import net.jejer.hipda.feature.threaddetail.chrome.shareExternalHttpUrl
import net.jejer.hipda.feature.threaddetail.interact.FloorContextMenuOverlay
import net.jejer.hipda.feature.threaddetail.interact.FloorDeleteConfirmDialog
import net.jejer.hipda.feature.threaddetail.interact.FloorTextSelectionDialog
import net.jejer.hipda.feature.threaddetail.interact.FloorWarningDialog
import net.jejer.hipda.feature.threaddetail.interact.ThreadPollCard
import net.jejer.hipda.feature.threaddetail.interact.copyFloorPlainTextToClipboard
import net.jejer.hipda.feature.threaddetail.interact.shareFloorPlainText
import net.jejer.hipda.feature.threaddetail.media.AboveViewportHeightCompensationAuthority
import net.jejer.hipda.feature.threaddetail.media.LocalAboveViewportHeightCompensationAuthority
import net.jejer.hipda.feature.threaddetail.media.LocalThreadImageGallery
import net.jejer.hipda.feature.threaddetail.media.extractThreadGalleryItems
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.ui.challenge.ChallengeStatusView
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.hiTopSafeBar
import net.jejer.hipda.ui.designsystem.glass.hiGlassSource
import net.jejer.hipda.ui.designsystem.glass.rememberHiGlassState
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut
import net.jejer.hipda.ui.designsystem.state.*
import net.jejer.hipda.ui.designsystem.topbar.rememberHiScrollToTopAction

private enum class DetailBodySlot { CHALLENGE, SKELETON, ERROR, EMPTY, CONTENT }

private const val FLOOR_KEY_PREFIX = "floor:"
private const val KEY_PREPEND_HEADER = "thread_prepend_header"
private const val KEY_APPEND_FOOTER = "thread_append_footer"
private const val PREFETCH_FLOOR_THRESHOLD = 5
private const val LANDING_ANCHOR_WINDOW_MS = 5_000L

private fun firstVisibleFloor(listState: LazyListState): LazyListItemInfo? {
    val visible = listState.layoutInfo.visibleItemsInfo
    return visible.firstOrNull { it.offset + it.size > 0 && (it.key as? String)?.startsWith(FLOOR_KEY_PREFIX) == true }
        ?: visible.firstOrNull { (it.key as? String)?.startsWith(FLOOR_KEY_PREFIX) == true }
}

/**
 * 详情列表同帧单一滚动权威控制器（闭合 `R-BATCH5 B4-E2a ①`、`V-E2b`、`R-E2b S3`、`R-E2d S2-R2` 与 `V-E2c F1/F2/F3`）：
 * - 全文件唯一持有 `LazyListState` 滚动写入权限（向上插页锚定、首楼越顶图片高度补偿、`PendingScrollTarget` 首帧同步跳楼/回原楼、
 *   跳楼落定窗口目标锚定补偿、头部失败行露出）；
 * - 以不可变 `LazyListLayoutInfo` 引用作为未重测帧纪元，同一帧内统一仲裁向上插页与越顶图片高度变化，绝不跨帧重复叠加；
 * - 跨会话（`!sameSession`）同步清空帧纪元状态、`pendingFloorKey` 与跳楼落定锚点；
 * - 跳楼与退出过滤回原楼严格按稳定键 `floor:$targetPid` 在 `LazyColumn` 首帧测量前同步落位（不依赖滞后一帧的 `LaunchedEffect`）。
 */
private class PrependAnchorController(initialGeneration: Long, initialHeader: Boolean, initialEpoch: Long) {
    private var handledGeneration: Long = initialGeneration
    private var lastShowHeader: Boolean = initialHeader
    private var lastResetEpoch: Long = initialEpoch
    private var handledPendingScrollId: Long = -1L
    private var pendingFloorKey: String? = null
    private var pendingTargetOffsetPx: Int = 0
    private var activeLayoutInfo: LazyListLayoutInfo? = null
    private var activeAnchorKey: String? = null
    private var activeTargetIndex: Int = -1
    private var activeTargetScrollOffset: Int = 0
    private var activeImageDeltaFloorKey: String? = null
    private var activeImageDeltaPx: Int = 0

    // F3：跳楼落定窗口锚点——在用户手动滚动/外部滚位改变/5s 超时/新导航之前，把目标楼层当作锚点，
    // 当上方视口内图片变高推挤目标楼层时，由本单一权威同步补偿偏移，使目标楼层保持在原位。
    private var landingAnchorKey: String? = null
    private var landingTargetOffsetPx: Int? = null
    private var landingDeadlineUptimeMs: Long = 0L
    private var lastSettledFirstIndex: Int = -1
    private var lastSettledFirstOffset: Int = -1
    private var isApplyingLandingDelta: Boolean = false

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

    private fun armLandingAnchor(floorKey: String) {
        landingAnchorKey = floorKey
        landingTargetOffsetPx = null
        landingDeadlineUptimeMs = SystemClock.uptimeMillis() + LANDING_ANCHOR_WINDOW_MS
        lastSettledFirstIndex = -1
        lastSettledFirstOffset = -1
    }

    fun clearLandingAnchor() {
        landingAnchorKey = null
        landingTargetOffsetPx = null
        landingDeadlineUptimeMs = 0L
        lastSettledFirstIndex = -1
        lastSettledFirstOffset = -1
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
        if (landingAnchorKey != null) {
            lastSettledFirstIndex = baseIndex
            lastSettledFirstOffset = nextOffset
        }
        listState.requestScrollToItem(index = baseIndex, scrollOffset = nextOffset)
    }

    fun syncBeforeMeasure(
        uiState: ThreadDetailUiState,
        listState: LazyListState,
        onConsumePendingScroll: (Long) -> Unit,
    ) {
        val layoutInfo = listState.layoutInfo
        ensureLayoutEpoch(layoutInfo)
        val genChanged = uiState.prependGeneration != handledGeneration
        val sameSession = uiState.resetEpoch == lastResetEpoch
        val headerChanged = sameSession && uiState.showPrependHeader != lastShowHeader && uiState.prependError == null && uiState.pendingScroll == null
        handledGeneration = uiState.prependGeneration
        lastShowHeader = uiState.showPrependHeader
        lastResetEpoch = uiState.resetEpoch
        if (!sameSession) {
            clearFrameAuthorityState()
            clearPending()
            clearLandingAnchor()
        }

        // F1/F2：在 LazyColumn 首次测量之前同步落地非平滑 PendingScrollTarget（跨页/本页跳楼、退出只看回原楼、翻页/切 tid），
        // 彻底消除首帧先闪第 0 项/原过滤首项/offset=0 再跳动的 1 帧闪烁。
        val pending = uiState.pendingScroll
        if (pending != null && !pending.smooth && !uiState.isLoading && uiState.floors.isNotEmpty() && pending.id != handledPendingScrollId) {
            handledPendingScrollId = pending.id
            clearPending()
            clearFrameAuthorityState()
            val idx = pending.postId?.let { pid -> uiState.floors.indexOfFirst { it.postId == pid }.takeIf { it >= 0 } } ?: 0
            val matched = pending.postId == null || uiState.floors.getOrNull(idx)?.postId == pending.postId
            val targetIndex = (if (uiState.showPrependHeader) 1 else 0) + idx
            val targetOffset = if (matched) pending.scrollOffset.coerceAtLeast(0) else 0
            if (pending.highlightPostId != null && matched) {
                armLandingAnchor("$FLOOR_KEY_PREFIX${pending.highlightPostId}")
            } else {
                clearLandingAnchor()
            }
            listState.requestScrollToItem(index = targetIndex, scrollOffset = targetOffset)
            onConsumePendingScroll(pending.id)
            return
        }

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
            // R-E2b S3：当 anchorItem 恰为当前首可见项时优先读取 listState.firstVisibleItemScrollOffset
            // （已含同帧越顶图片增量或直接偏移更新，避免重复叠加两份 sameFrameImageDelta）。
            val sameItemScrollOffset = anchorItem.index == listState.firstVisibleItemIndex
            val targetScrollOffset = if (sameItemScrollOffset) {
                listState.firstVisibleItemScrollOffset
            } else {
                (-anchorOffset + sameFrameImageDelta).coerceAtLeast(0)
            }
            activeAnchorKey = anchorKey
            activeTargetIndex = newListIndex
            activeTargetScrollOffset = targetScrollOffset
            val primaryKey = visibleItems.firstOrNull { it.offset + it.size > 0 }?.key
            val hasPendingScrollDelta = sameFrameImageDelta != 0 || (sameItemScrollOffset && targetScrollOffset != -anchorOffset)
            if (hasPendingScrollDelta || !(primaryKey == anchorKey && listState.isScrollInProgress)) {
                if (landingAnchorKey != null) {
                    lastSettledFirstIndex = newListIndex
                    lastSettledFirstOffset = targetScrollOffset
                }
                listState.requestScrollToItem(index = newListIndex, scrollOffset = targetScrollOffset)
            }
        } else {
            pendingFloorKey = anchorKey
            pendingTargetOffsetPx = anchorOffset
            activeAnchorKey = anchorKey
            activeTargetIndex = newListIndex
            activeTargetScrollOffset = 0
            if (landingAnchorKey != null) {
                lastSettledFirstIndex = newListIndex
                lastSettledFirstOffset = 0
            }
            listState.requestScrollToItem(index = newListIndex, scrollOffset = 0)
        }
    }

    fun onFloorLayoutPositioned(uiState: ThreadDetailUiState, listState: LazyListState) {
        applyPendingPositiveOffset(listState)
        stabilizeLandingAnchor(uiState, listState)
    }

    private fun applyPendingPositiveOffset(listState: LazyListState) {
        val floorKey = pendingFloorKey ?: return
        val targetOffset = pendingTargetOffsetPx
        if (targetOffset <= 0) { clearPending(); return }
        val currentItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == floorKey } ?: return
        val delta = targetOffset - currentItem.offset
        clearPending()
        if (delta > 0 && listState.canScrollBackward) listState.dispatchRawDelta(-delta.toFloat())
    }

    private fun stabilizeLandingAnchor(uiState: ThreadDetailUiState, listState: LazyListState) {
        val anchorKey = landingAnchorKey ?: return
        if (isApplyingLandingDelta) return
        val pending = uiState.pendingScroll
        if (uiState.isLoading || (pending != null && pending.id != handledPendingScrollId) || uiState.floors.isEmpty()) return
        if (SystemClock.uptimeMillis() > landingDeadlineUptimeMs) {
            clearLandingAnchor()
            return
        }
        val targetPostId = anchorKey.removePrefix(FLOOR_KEY_PREFIX)
        val targetFloorIdx = uiState.floors.indexOfFirst { it.postId == targetPostId }
        if (targetFloorIdx < 0) {
            clearLandingAnchor()
            return
        }
        val targetListIdx = (if (uiState.showPrependHeader) 1 else 0) + targetFloorIdx
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return

        val currentItem = visible.firstOrNull { it.key == anchorKey }
        val baselineOffset = landingTargetOffsetPx
        if (baselineOffset == null) {
            if (currentItem != null) {
                landingTargetOffsetPx = currentItem.offset
                lastSettledFirstIndex = listState.firstVisibleItemIndex
                lastSettledFirstOffset = listState.firstVisibleItemScrollOffset
            }
            return
        }

        if (currentItem != null && currentItem.offset == baselineOffset) {
            lastSettledFirstIndex = listState.firstVisibleItemIndex
            lastSettledFirstOffset = listState.firstVisibleItemScrollOffset
            return
        }

        // 若 (firstVisibleItemIndex, firstVisibleItemScrollOffset) 已在非本控制器驱动下独立改变，说明发生了外部/用户滚动，立即退出落定窗口。
        if (lastSettledFirstIndex >= 0 &&
            (listState.firstVisibleItemIndex != lastSettledFirstIndex || listState.firstVisibleItemScrollOffset != lastSettledFirstOffset)
        ) {
            clearLandingAnchor()
            return
        }

        isApplyingLandingDelta = true
        try {
            repeat(6) {
                val curVisible = listState.layoutInfo.visibleItemsInfo
                if (curVisible.isEmpty()) return
                val item = curVisible.firstOrNull { it.key == anchorKey }
                if (item != null) {
                    val drift = item.offset - baselineOffset
                    if (drift == 0) return
                    val canMove = if (drift > 0) listState.canScrollForward else listState.canScrollBackward
                    if (!canMove) return
                    val consumed = listState.dispatchRawDelta(drift.toFloat())
                    if (abs(consumed) < 0.5f) return
                } else {
                    val last = curVisible.last()
                    val first = curVisible.first()
                    if (last.index < targetListIdx && listState.canScrollForward) {
                        val step = (last.offset + last.size - baselineOffset).coerceAtLeast(120)
                        val consumed = listState.dispatchRawDelta(step.toFloat())
                        if (abs(consumed) < 0.5f) return
                    } else if (first.index > targetListIdx && listState.canScrollBackward) {
                        val step = (baselineOffset - first.offset).coerceAtLeast(120)
                        val consumed = listState.dispatchRawDelta(-step.toFloat())
                        if (abs(consumed) < 0.5f) return
                    } else {
                        return
                    }
                }
            }
        } finally {
            isApplyingLandingDelta = false
            lastSettledFirstIndex = listState.firstVisibleItemIndex
            lastSettledFirstOffset = listState.firstVisibleItemScrollOffset
        }
    }

    /** 统一经由单一滚动权威露出顶部失败重试行。 */
    suspend fun revealPrependErrorHeader(listState: LazyListState) {
        clearPending()
        clearFrameAuthorityState()
        clearLandingAnchor()
        listState.scrollToItem(0, 0)
    }

    /** 统一经由单一滚动权威平滑滚至末页新增回复楼层（减动效 0ms 一步到位）。 */
    suspend fun smoothScrollPendingTarget(
        uiState: ThreadDetailUiState,
        listState: LazyListState,
        reducedMotion: Boolean,
        onConsumePendingScroll: (Long) -> Unit,
    ) {
        val pending = uiState.pendingScroll ?: return
        if (!pending.smooth || uiState.isLoading || uiState.floors.isEmpty() || pending.id == handledPendingScrollId) return
        handledPendingScrollId = pending.id
        clearPending()
        clearFrameAuthorityState()
        clearLandingAnchor()
        val idx = pending.postId?.let { pid -> uiState.floors.indexOfFirst { it.postId == pid }.takeIf { it >= 0 } }
            ?: uiState.floors.lastIndex
        val targetIndex = (if (uiState.showPrependHeader) 1 else 0) + idx
        if (reducedMotion) {
            listState.scrollToItem(targetIndex, 0)
        } else {
            listState.animateScrollToItem(targetIndex, 0)
        }
        onConsumePendingScroll(pending.id)
    }

    /** 常规窗口唤起快速回复面板时按需微调滚动，使正在阅读或被引用的目标楼层露出于面板上方。 */
    suspend fun keepFloorVisibleAboveSheet(
        targetPostId: String?,
        occupiedBottomPx: Float,
        topClearancePx: Float,
        listState: LazyListState,
        reducedMotion: Boolean,
    ) {
        if (occupiedBottomPx <= 0f) return
        val layoutInfo = listState.layoutInfo
        val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
        val safeBottom = viewportHeight - occupiedBottomPx
        if (safeBottom <= topClearancePx + 24f) return
        val visibleFloors = layoutInfo.visibleItemsInfo.filter { (it.key as? String)?.startsWith(FLOOR_KEY_PREFIX) == true }
        val targetItem = targetPostId?.let { pid -> visibleFloors.firstOrNull { it.key == "$FLOOR_KEY_PREFIX$pid" } }
            ?: firstVisibleFloor(listState)
            ?: return
        val itemBottom = (targetItem.offset + targetItem.size).toFloat()
        if (itemBottom <= safeBottom) return
        val maxLiftKeepingTopVisible = (targetItem.offset - topClearancePx).coerceAtLeast(0f)
        val delta = minOf(itemBottom - safeBottom, maxLiftKeepingTopVisible)
        if (delta <= 1f || !listState.canScrollForward) return
        clearLandingAnchor()
        if (reducedMotion) {
            listState.scrollBy(delta)
        } else {
            listState.animateScrollBy(delta, HiMotion.spatialDefault())
        }
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

/** 帖子详情核心完整面板（M5 `E2a`/`E2d`/`E2c`/`E4`/`E5`，严格保持 `E0` 锁定顶层签名、`floor:$postId` 主键与 `PrependAnchorController` 单一滚动权威）。 */
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
    onOpenReply: ((ReplyRequest) -> Unit)? = null,
    onOpenUserProfile: (uid: String, username: String) -> Unit = { _, _ -> },
    viewModel: ThreadDetailViewModel = viewModel(key = "thread:${args.tid}"),
    quickReplyViewModel: QuickReplyViewModel? = null,
    onExpandToFullEditor: ((FullEditorHandoff) -> Unit)? = null,
    emojiPanelSlot: (@Composable (QuickReplyEmojiPanelHost) -> Unit)? = null,
    onThreadDeleted: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val latestUiState by rememberUpdatedState(uiState)
    LaunchedEffect(args.tid, args.targetPid, args.openLastPage, args.navSeq) {
        viewModel.bindArgs(args) { resolveViewportAnchor(latestUiState, listState, fallbackToFirst = true) }
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val reducedMotion = HiTheme.isReducedMotion
    val snackbarController = LocalPaneSnackbarController.current
    val coroutineScope = rememberCoroutineScope()
    val detailGlassState = rememberHiGlassState()
    val activeMenuFloorState = remember(args.tid, uiState.tid) { mutableStateOf<PostFloor?>(null) }
    var activeMenuFloor by activeMenuFloorState
    val activeSelectionFloorState = remember(args.tid, uiState.tid) { mutableStateOf<PostFloor?>(null) }
    var activeSelectionFloor by activeSelectionFloorState
    val activeWarningFloorState = remember(args.tid, uiState.tid) { mutableStateOf<PostFloor?>(null) }
    var activeWarningFloor by activeWarningFloorState
    var isJumpBarExpanded by remember(args.tid) { mutableStateOf(false) }
    var activeReplyTarget by rememberSaveable(args.tid, stateSaver = QuickReplyTargetSaver) { mutableStateOf<QuickReplyTarget?>(null) }
    var boundQuickReplyVm by remember(args.tid, quickReplyViewModel) { mutableStateOf(quickReplyViewModel) }
    var quickReplyOccupiedBottomDp by remember(args.tid) { mutableStateOf(0.dp) }
    var shouldLiftListForQuickReply by remember(args.tid) { mutableStateOf(false) }
    val isQuickReplyOpen = activeReplyTarget != null
    val effectiveSuppressBottomGlass = chromeSpec.suppressBottomGlass || isQuickReplyOpen

    // 返回优先级（Addendum #3）：PositionStack 仅在非空时拦截，且优先于 E3 多栏返回栈，
    // 晚于内联跳页条收起与楼层长按菜单关闭（Compose BackHandler 后进先出）。
    BackHandler(enabled = uiState.canPopPositionStack) {
        viewModel.popPosition()
    }

    DisposableEffect(args.tid, viewModel) {
        onDispose { viewModel.flushReadingPosition() }
    }

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
    val onOpenFloorMenu = remember(activeMenuFloorState) { { floor: PostFloor -> activeMenuFloorState.value = floor } }
    val onOpenFloorWarning = remember(activeWarningFloorState) { { floor: PostFloor -> activeWarningFloorState.value = floor } }
    val onTopBarScrollToTop = rememberHiScrollToTopAction(listState) { anchorController.clearLandingAnchor() }
    val floorPositionedModifier = remember(anchorController, listState) {
        Modifier.onGloballyPositioned { anchorController.onFloorLayoutPositioned(latestUiState, listState) }
    }
    val pagingNestedScroll = remember(viewModel, anchorController) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    anchorController.clearLandingAnchor()
                }
                return Offset.Zero
            }

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

    LaunchedEffect(listState, anchorController) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) anchorController.clearLandingAnchor()
        }
    }

    LaunchedEffect(listState, viewModel) {
        var seenHighlightToken = -1L
        snapshotFlow {
            val s = latestUiState
            val hl = s.highlightedFloor
            if (hl == null || s.isLoading || s.pendingScroll != null) {
                Triple(-1L, false, false)
            } else {
                val visible = listState.layoutInfo.visibleItemsInfo.any { it.key == "$FLOOR_KEY_PREFIX${hl.postId}" }
                Triple(hl.tokenId, visible, true)
            }
        }.collect { (token, visible, active) ->
            if (!active) {
                seenHighlightToken = -1L
            } else if (visible) {
                seenHighlightToken = token
            } else if (seenHighlightToken == token) {
                seenHighlightToken = -1L
                viewModel.onHighlightFinished(token)
            }
        }
    }

    LaunchedEffect(uiState.prependError) {
        if (uiState.prependError != null && uiState.showPrependHeader && listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset == 0) {
            anchorController.revealPrependErrorHeader(listState)
        }
    }

    val activeNotice = uiState.notice
    val activeNoticeMessage = activeNotice?.let { stringResource(it.error.messageRes) }
    val loginActionLabel = stringResource(R.string.common_action_login)
    LaunchedEffect(activeNotice?.id) {
        if (activeNotice == null || activeNoticeMessage == null) return@LaunchedEffect
        val isLoginAction = activeNotice.error.action == UiErrorAction.Login
        viewModel.consumeNotice(activeNotice.id)
        snackbarController.show(
            message = activeNoticeMessage,
            target = PaneSnackbarTarget.DetailPane,
            actionLabel = if (isLoginAction) loginActionLabel else null,
            onAction = if (isLoginAction) onOpenLogin else null,
        )
    }

    val activeSnackbarEvent = uiState.snackbarEvent
    val undoActionLabel = stringResource(R.string.thread_detail_action_undo)
    val favoritedMsg = stringResource(R.string.thread_detail_snackbar_favorited)
    val unfavoritedMsg = stringResource(R.string.thread_detail_snackbar_unfavorited)
    val attentionAddedMsg = stringResource(R.string.thread_detail_snackbar_attention_added)
    val attentionRemovedMsg = stringResource(R.string.thread_detail_snackbar_attention_removed)
    val activeUndoSnackbarJob = remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(uiState.snackbarResetSeq) {
        activeUndoSnackbarJob.value?.cancel()
        activeUndoSnackbarJob.value = null
    }
    LaunchedEffect(activeSnackbarEvent?.id) {
        val event = activeSnackbarEvent ?: return@LaunchedEffect
        viewModel.consumeSnackbarEvent(event.id)
        activeUndoSnackbarJob.value?.cancel()
        activeUndoSnackbarJob.value = coroutineScope.launch {
            when (event) {
                is ThreadDetailSnackbarEvent.FavoriteUndoable -> {
                    val message = when (event.kind) {
                        FavoriteKind.FAVORITE -> if (event.added) favoritedMsg else unfavoritedMsg
                        FavoriteKind.ATTENTION -> if (event.added) attentionAddedMsg else attentionRemovedMsg
                    }
                    snackbarController.show(
                        message = message,
                        target = PaneSnackbarTarget.DetailPane,
                        actionLabel = undoActionLabel,
                        onAction = { viewModel.undoFavoriteMutation(event.tokenId) },
                    )
                }
            }
        }
    }

    LaunchedEffect(listState, viewModel) {
        snapshotFlow {
            val s = latestUiState
            if (s.isLoading || s.pendingScroll != null || s.floors.isEmpty()) return@snapshotFlow null
            resolveViewportAnchor(s, listState, fallbackToFirst = false)
        }.distinctUntilChanged().collect { anchor ->
            anchor?.postId?.let { pid -> viewModel.onReadingPositionChanged(anchor.page, pid, anchor.scrollOffsetPx) }
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
    val statusBarTop = WindowInsets.hiTopSafeBar.asPaddingValues().calculateTopPadding()
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
    val copiedLabel = stringResource(R.string.thread_detail_menu_copy)
    val copiedSnackbarMessage = stringResource(R.string.thread_detail_snackbar_copied)
    val copyLinkLabel = stringResource(R.string.thread_detail_more_copy_link)
    val linkCopiedSnackbarMessage = stringResource(R.string.thread_detail_snackbar_link_copied)
    val noBrowserSnackbarMessage = stringResource(R.string.routing_error_no_browser)
    val externalLinkOpener = (LocalPostLinkDispatcher.current as? DefaultPostLinkDispatcher)?.externalLinkOpener
    val shareChooserTitle = stringResource(R.string.thread_detail_more_share)
    val shareTopicPrefix = stringResource(R.string.thread_detail_share_topic_prefix)
    val shareReplyPrefix = stringResource(R.string.thread_detail_share_reply_prefix)
    val draftSavedMsg = stringResource(R.string.compose_snackbar_draft_saved)
    val discardDraftLabel = stringResource(R.string.compose_action_discard_draft)
    val replySentMsg = stringResource(R.string.compose_snackbar_reply_sent)
    val viewReplyLabel = stringResource(R.string.compose_action_view)
    val bottomInset = contentPadding.calculateBottomPadding()
    val fixedBottomClearance = bottomInset + ThreadBottomToolbarClearance
    val effectiveListBottomClearance = if (isQuickReplyOpen && shouldLiftListForQuickReply) {
        maxOf(fixedBottomClearance, quickReplyOccupiedBottomDp)
    } else {
        fixedBottomClearance
    }

    LaunchedEffect(bodySlot, effectiveSuppressBottomGlass) {
        if (bodySlot != DetailBodySlot.CONTENT || effectiveSuppressBottomGlass) isJumpBarExpanded = false
    }

    LaunchedEffect(uiState.pendingScroll?.id, uiState.isLoading, uiState.floors.size, reducedMotion) {
        if (uiState.pendingScroll?.smooth == true && !uiState.isLoading && uiState.floors.isNotEmpty()) {
            anchorController.smoothScrollPendingTarget(uiState, listState, reducedMotion, viewModel::consumePendingScroll)
        }
    }

    LaunchedEffect(activeReplyTarget, quickReplyOccupiedBottomDp, shouldLiftListForQuickReply, topClearance, reducedMotion) {
        val target = activeReplyTarget
        if (target != null && shouldLiftListForQuickReply && quickReplyOccupiedBottomDp > 0.dp) {
            anchorController.keepFloorVisibleAboveSheet(
                targetPostId = target.targetPid,
                occupiedBottomPx = with(density) { quickReplyOccupiedBottomDp.toPx() },
                topClearancePx = with(density) { topClearance.toPx() },
                listState = listState,
                reducedMotion = reducedMotion,
            )
        }
    }

    val handleReplySubmitted: (net.jejer.hipda.parser.threaddetail.ThreadDetailPage?) -> Unit = { redirectedPage ->
        val snap = latestUiState
        if (isNearLastPageTail(snap, listState)) {
            viewModel.onReplySuccessAtTail(redirectedPage)
        } else {
            val targetPid = redirectedPage?.floors?.lastOrNull()?.postId
            coroutineScope.launch {
                snackbarController.show(
                    message = replySentMsg,
                    target = PaneSnackbarTarget.DetailPane,
                    actionLabel = viewReplyLabel,
                    onAction = {
                        val anchor = resolveViewportAnchor(latestUiState, listState, fallbackToFirst = true)
                        viewModel.jumpToNewReply(targetPid = targetPid, fromAnchor = anchor, redirectedPage = redirectedPage)
                    },
                )
            }
        }
    }

    LaunchedEffect(boundQuickReplyVm) {
        val qrVm = boundQuickReplyVm ?: return@LaunchedEffect
        qrVm.events.collect { event ->
            when (event) {
                is QuickReplyEvent.DraftSaved -> {
                    coroutineScope.launch {
                        snackbarController.show(
                            message = draftSavedMsg,
                            target = PaneSnackbarTarget.DetailPane,
                            actionLabel = discardDraftLabel,
                            onAction = { qrVm.discardSavedDraft(event.draftKey) },
                        )
                    }
                }
                is QuickReplyEvent.Submitted -> {
                    activeReplyTarget = null
                    handleReplySubmitted(event.redirectedPage)
                }
            }
        }
    }

    val fullEditorReplyResults = LocalFullEditorReplyResults.current
    val resolvedComposeSlots = rememberResolvedComposeSlots(onExpandToFullEditor, emojiPanelSlot)
    val latestOnBack by rememberUpdatedState(onBack)
    val latestOnThreadDeleted by rememberUpdatedState(onThreadDeleted)
    val latestFullScreenState by rememberUpdatedState(chromeSpec.fullScreenState)
    LaunchedEffect(fullEditorReplyResults, args.tid) {
        fullEditorReplyResults?.collect { submitted ->
            if (submitted.tid == latestUiState.tid || submitted.tid == args.tid) {
                if (!submitted.editedPid.isNullOrBlank()) {
                    viewModel.onPostEdited(submitted.editedPid, submitted.editedPage, submitted.redirectedPage)
                } else {
                    handleReplySubmitted(submitted.redirectedPage)
                }
            }
        }
    }
    LaunchedEffect(uiState.tid, uiState.isThreadDeleted) {
        if (uiState.isThreadDeleted && uiState.tid.isNotBlank()) {
            val customHandler = latestOnThreadDeleted
            if (customHandler != null) {
                customHandler()
            } else {
                (fullEditorReplyResults as? FullEditorHostChannel)?.notifyThreadDeleted()
                if (latestFullScreenState?.isFullScreen == true) latestFullScreenState?.onToggle()
                latestOnBack()
            }
        }
    }

    val openQuickReplyOrCallback: (QuickReplyTarget, ReplyRequest) -> Unit = { target, legacyRequest ->
        if (onOpenReply != null) {
            onOpenReply(legacyRequest)
        } else {
            isJumpBarExpanded = false
            activeReplyTarget = target
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = cs.background, contentColor = cs.onBackground) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 严格兄弟节点 1：正文内容区（仅 LazyColumn 挂载 detailGlassState，底部固定预留工具条高度不随展开/IME 跳动）
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
                        SideEffect { anchorController.syncBeforeMeasure(uiState, listState, viewModel::consumePendingScroll) }
                        val threadImageGallery = remember(uiState.floors) { extractThreadGalleryItems(uiState.floors) }
                        CompositionLocalProvider(
                            LocalAboveViewportHeightCompensationAuthority provides scrollCompensationAuthority,
                            LocalThreadImageGallery provides threadImageGallery,
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().nestedScroll(pagingNestedScroll).hiGlassSource(detailGlassState).background(cs.background),
                                contentPadding = PaddingValues(top = topClearance, bottom = effectiveListBottomClearance),
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
                                    val highlightToken = uiState.highlightedFloor?.takeIf { it.postId == item.postId }?.tokenId
                                    val floorPollSlot: (@Composable (net.jejer.hipda.parser.threaddetail.ThreadPoll) -> Unit)? =
                                        if (item.floor.floor == 1 && item.floor.poll != null) {
                                            { poll ->
                                                ThreadPollCard(
                                                    poll = poll,
                                                    isLoggedIn = uiState.isLoggedIn,
                                                    isSubmitting = uiState.isPollSubmitting,
                                                    error = uiState.pollError,
                                                    onSubmitVote = { ids -> viewModel.submitPollVote(poll, ids) },
                                                    onOpenLogin = onOpenLogin,
                                                    onClearError = viewModel::clearPollError,
                                                )
                                            }
                                        } else {
                                            null
                                        }
                                    PostFloorCard(
                                        item = item,
                                        isOp = isOp,
                                        listState = listState,
                                        onOpenImageViewer = onOpenImageViewer,
                                        onAuthorClick = onOpenUserProfile,
                                        modifier = floorPositionedModifier,
                                        onJumpToFloor = { targetPid, targetTid ->
                                            viewModel.jumpToFloor(targetPid, targetTid, resolveViewportAnchor(latestUiState, listState, fallbackToFirst = true))
                                        },
                                        onLongPress = onOpenFloorMenu,
                                        onWarningClick = onOpenFloorWarning,
                                        highlightToken = highlightToken,
                                        onHighlightFinished = viewModel::onHighlightFinished,
                                        pollSlot = floorPollSlot,
                                    )
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
                        val anchor = resolveViewportAnchor(uiState, listState, fallbackToFirst = true)
                        viewModel.toggleAuthorOnly(anchor?.postId, anchor?.scrollOffsetPx ?: listState.firstVisibleItemScrollOffset)
                    },
                    onScrollToTop = if (bodySlot == DetailBodySlot.CONTENT) onTopBarScrollToTop else null,
                )
                FilterCapsuleBar(uiState.filter, viewModel::clearFilter)
            }

            // 严格兄弟节点 3：底部三段式液态玻璃工具条与内联跳页条（快速回复面板展开或 suppressBottomGlass == true 时整块卸载 HiGlassRegion）
            if (!effectiveSuppressBottomGlass && bodySlot == DetailBodySlot.CONTENT) {
                ThreadBottomToolbar(
                    page = visiblePage, lastPage = uiState.lastPage, isFavorited = uiState.isFavorited, isAttention = uiState.isAttention,
                    glassState = detailGlassState, suppressBottomGlass = effectiveSuppressBottomGlass,
                    isJumpBarExpanded = isJumpBarExpanded, onExpandedChange = { isJumpBarExpanded = it },
                    onReplyClick = {
                        openQuickReplyOrCallback(
                            QuickReplyTarget(
                                tid = uiState.tid.ifBlank { args.tid },
                                fid = uiState.fid.takeIf { it > 0 } ?: args.fid,
                                mode = QuickReplyMode.ReplyThread,
                                threadAuthor = uiState.threadAuthorName ?: args.author,
                            ),
                            ReplyRequest(tid = uiState.tid),
                        )
                    },
                    onJumpToPage = { targetPage -> viewModel.jumpToPage(targetPage, resolveViewportAnchor(latestUiState, listState, fallbackToFirst = true)) },
                    onToggleFavorite = viewModel::toggleFavorite, onRefreshClick = viewModel::refreshCurrentPage,
                    onToggleAttention = viewModel::toggleAttention,
                    onCopyLinkClick = {
                        val url = buildSafeThreadUrl(uiState.forumServer, uiState.tid, visiblePage)
                        if (copyThreadLinkPlainText(context, copyLinkLabel, url)) {
                            coroutineScope.launch { snackbarController.show(linkCopiedSnackbarMessage, target = PaneSnackbarTarget.DetailPane) }
                        }
                    },
                    onOpenInBrowserClick = {
                        val url = buildSafeThreadUrl(uiState.forumServer, uiState.tid, visiblePage)
                        if (url != null && !openExternalHttpUrl(context, url, externalLinkOpener)) {
                            coroutineScope.launch { snackbarController.show(noBrowserSnackbarMessage, target = PaneSnackbarTarget.DetailPane) }
                        }
                    },
                    onShareClick = {
                        shareExternalHttpUrl(context, uiState.title.ifBlank { args.title }, buildSafeThreadUrl(uiState.forumServer, uiState.tid, visiblePage), shareChooserTitle)
                    },
                    bottomInset = bottomInset,
                )
            }

            // 严格兄弟节点 4：楼层长按菜单（纯色表面，零玻璃标记开销）
            FloorContextMenuOverlay(
                floor = activeMenuFloor,
                isLoggedIn = uiState.isLoggedIn,
                currentUserUid = uiState.currentUserUid,
                onQuoteReply = { floor ->
                    openQuickReplyOrCallback(
                        QuickReplyTarget(
                            tid = uiState.tid.ifBlank { args.tid },
                            fid = uiState.fid.takeIf { it > 0 } ?: args.fid,
                            mode = QuickReplyMode.QuotePost,
                            targetPid = floor.postId,
                            targetAuthor = floor.author,
                            targetFloor = floor.floor,
                            threadAuthor = uiState.threadAuthorName ?: args.author,
                        ),
                        ReplyRequest(tid = uiState.tid, quotePid = floor.postId, quoteAuthor = floor.author, quoteFloor = floor.floor),
                    )
                },
                onReply = { floor ->
                    openQuickReplyOrCallback(
                        QuickReplyTarget(
                            tid = uiState.tid.ifBlank { args.tid },
                            fid = uiState.fid.takeIf { it > 0 } ?: args.fid,
                            mode = QuickReplyMode.ReplyPost,
                            targetPid = floor.postId,
                            targetAuthor = floor.author,
                            targetFloor = floor.floor,
                            threadAuthor = uiState.threadAuthorName ?: args.author,
                        ),
                        ReplyRequest(tid = uiState.tid, quotePid = null, quoteAuthor = floor.author, quoteFloor = floor.floor),
                    )
                },
                onFilterByUser = { floor ->
                    val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "$FLOOR_KEY_PREFIX${floor.postId}" }
                    val anchorOffset = itemInfo?.let { (-it.offset).coerceAtLeast(0) } ?: 0
                    viewModel.filterByUser(floor.uid, floor.author, anchorPostId = floor.postId, anchorScrollOffset = anchorOffset)
                },
                onCopy = { floor ->
                    if (copyFloorPlainTextToClipboard(context, copiedLabel, floor)) {
                        coroutineScope.launch {
                            snackbarController.show(copiedSnackbarMessage, target = PaneSnackbarTarget.DetailPane)
                        }
                    }
                },
                onSelectText = { floor -> activeSelectionFloor = floor },
                onShare = { floor ->
                    val floorHeader = context.getString(R.string.thread_detail_floor_title, floor.floor, floor.author)
                    shareFloorPlainText(
                        context = context,
                        forumServer = latestUiState.forumServer,
                        tid = latestUiState.tid.ifBlank { args.tid },
                        threadTitle = latestUiState.title.ifBlank { args.title },
                        floor = floor,
                        topicPrefix = shareTopicPrefix,
                        replyPrefix = shareReplyPrefix,
                        floorHeader = floorHeader,
                        chooserTitle = shareChooserTitle,
                    )
                },
                onEdit = { floor ->
                    isJumpBarExpanded = false
                    resolvedComposeSlots.onExpandToFullEditor?.invoke(
                        FullEditorHandoff(
                            tid = latestUiState.tid.ifBlank { args.tid },
                            fid = latestUiState.fid.takeIf { it > 0 } ?: args.fid,
                            text = "",
                            selectionStart = 0,
                            selectionEnd = 0,
                            quoteAuthor = floor.author,
                            quoteFloor = floor.floor,
                            editPid = floor.postId,
                            editPage = floor.page.takeIf { it > 0 } ?: latestUiState.page,
                            isFirstFloor = floor.floor == 1,
                        ),
                    )
                },
                onDelete = { floor ->
                    isJumpBarExpanded = false
                    viewModel.requestDeleteFloor(floor)
                },
                onOpenLogin = onOpenLogin,
                onDismiss = { activeMenuFloor = null },
            )

            // 严格兄弟节点 5（详情容器最顶层）：毛玻璃快速回复面板（复用 detailGlassState，与底部工具条互斥守 ≤ 3 玻璃预算）
            if (isQuickReplyOpen) {
                val resolvedQuickReplyVm = quickReplyViewModel ?: viewModel<QuickReplyViewModel>(key = "quick_reply:${args.tid}")
                SideEffect { if (boundQuickReplyVm !== resolvedQuickReplyVm) boundQuickReplyVm = resolvedQuickReplyVm }
                LaunchedEffect(activeReplyTarget, resolvedQuickReplyVm) {
                    val target = activeReplyTarget ?: return@LaunchedEffect
                    resolvedQuickReplyVm.open(target) { pid ->
                        latestUiState.floors.firstOrNull { it.postId == pid }?.let { it.floor.floor to it.floor.author }
                    }
                }
                QuickReplySheet(
                    viewModel = resolvedQuickReplyVm,
                    glassState = detailGlassState,
                    onDismissFinished = { activeReplyTarget = null },
                    onOpenLogin = onOpenLogin,
                    bottomInset = bottomInset,
                    onExpandToFullEditor = onExpandToFullEditor,
                    emojiPanelSlot = emojiPanelSlot,
                    onOccupiedBottomChanged = { occupied, shouldLift ->
                        quickReplyOccupiedBottomDp = occupied
                        shouldLiftListForQuickReply = shouldLift
                    },
                )
            }

            // 严格兄弟节点 6/7/8：单楼文字选择、警告记录与删除确认弹窗（仅打开时挂载，纯色零玻璃，BackHandler 置顶于 LIFO 栈顶）
            FloorTextSelectionDialog(
                floor = activeSelectionFloor,
                onDismiss = { activeSelectionFloor = null },
            )
            FloorWarningDialog(
                floor = activeWarningFloor,
                onLoadWarning = viewModel::loadFloorWarning,
                onDismiss = { activeWarningFloor = null },
            )
            FloorDeleteConfirmDialog(
                state = uiState.deleteDialog,
                onConfirm = viewModel::confirmDeleteFloor,
                onDismiss = viewModel::dismissDeleteFloorDialog,
                onOpenLogin = {
                    viewModel.dismissDeleteFloorDialog()
                    onOpenLogin()
                },
            )
        }
    }
}

private fun isNearLastPageTail(
    s: ThreadDetailUiState,
    listState: LazyListState,
): Boolean {
    if (s.filter !is ThreadDetailFilterUi.All) return false
    if (s.floors.isEmpty() || s.lastLoadedPage < s.lastPage) return false
    if (!listState.canScrollForward) return true
    val header = if (s.showPrependHeader) 1 else 0
    val lastVisibleFloor = listState.layoutInfo.visibleItemsInfo
        .lastOrNull { (it.key as? String)?.startsWith(FLOOR_KEY_PREFIX) == true }
    val lastVisibleIdx = resolveFloorIndex(lastVisibleFloor, s.floors, header)
    return lastVisibleIdx >= (s.floors.lastIndex - 2).coerceAtLeast(0)
}

private fun resolveViewportAnchor(
    s: ThreadDetailUiState,
    listState: LazyListState,
    fallbackToFirst: Boolean,
): ReadingPositionAnchor? {
    val header = if (s.showPrependHeader) 1 else 0
    val firstFloor = firstVisibleFloor(listState)
    val floorIdx = resolveFloorIndex(firstFloor, s.floors, header)
    val item = s.floors.getOrNull(floorIdx) ?: if (fallbackToFirst) s.floors.firstOrNull() else null
    return item?.let {
        ReadingPositionAnchor(
            page = it.floor.page.takeIf { p -> p > 0 } ?: s.page,
            postId = it.postId,
            scrollOffsetPx = firstFloor?.let { f -> (-f.offset).coerceAtLeast(0) } ?: 0,
            filter = s.filter,
        )
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
