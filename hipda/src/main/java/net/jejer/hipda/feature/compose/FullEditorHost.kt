package net.jejer.hipda.feature.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.shell.*

const val FULL_EDITOR_RIGHT_PANE_MIN_WIDTH_DP: Float = 560f
const val FULL_EDITOR_MAX_WIDTH_DP: Float = 680f
const val FULL_EDITOR_MODAL_HORIZONTAL_MARGIN_DP: Float = 48f

/** 完整编辑器多栏投放形态（严格遵循 `product-design.md` §4.2）。 */
enum class FullEditorPlacement { FullScreen, CenteredModal, RightPane }

fun resolveFullEditorPlacement(paneLayout: ShellPaneLayout, windowHeightDp: Float, effectiveRightPaneWidthDp: Float = paneLayout.rightPaneWidthDp.toFloat()): FullEditorPlacement = when {
    windowHeightDp < 480f -> FullEditorPlacement.FullScreen
    paneLayout.layoutMode in setOf(ShellPaneLayoutMode.SinglePane, ShellPaneLayoutMode.CompactSinglePane, ShellPaneLayoutMode.HingeFallbackSinglePane) -> FullEditorPlacement.FullScreen
    paneLayout.layoutMode == ShellPaneLayoutMode.SidebarSinglePane || paneLayout.layoutMode == ShellPaneLayoutMode.HingeTwoPane -> FullEditorPlacement.CenteredModal
    effectiveRightPaneWidthDp >= FULL_EDITOR_RIGHT_PANE_MIN_WIDTH_DP -> FullEditorPlacement.RightPane
    else -> FullEditorPlacement.CenteredModal
}

fun calculateCenteredModalWidthDp(windowWidthDp: Float): Dp =
    minOf((windowWidthDp - FULL_EDITOR_MODAL_HORIZONTAL_MARGIN_DP).coerceAtLeast(320f), FULL_EDITOR_MAX_WIDTH_DP).dp

@Immutable
data class PendingFullEditorRequest(val id: Long, val request: FullEditorRequest)

/** 单例完整编辑器请求总线（实现 [FullEditorLauncher]，不持有 Activity 或 Composable 引用）。 */
@Singleton
class FullEditorRequestBus @Inject constructor() : FullEditorLauncher {
    private val seq = AtomicLong(0L)
    private val _pendingRequest = MutableStateFlow<PendingFullEditorRequest?>(null)
    val pendingRequest: StateFlow<PendingFullEditorRequest?> = _pendingRequest.asStateFlow()

    override fun open(handoff: FullEditorHandoff) = enqueue(FullEditorRequest.fromHandoff(handoff))
    fun openNewThread(initialFid: Int? = null) = enqueue(FullEditorRequest.NewThread(initialFid))
    fun openEditPost(fid: Int, tid: String, pid: String, page: Int = 1, isFirstFloor: Boolean = false, floorNumber: Int? = null, author: String? = null) =
        enqueue(FullEditorRequest.EditPost(fid, tid, pid, page, isFirstFloor, floorNumber, author))
    fun enqueue(request: FullEditorRequest) { _pendingRequest.value = PendingFullEditorRequest(seq.incrementAndGet(), request) }
    fun consume(id: Long) { val cur = _pendingRequest.value; if (cur?.id == id) _pendingRequest.compareAndSet(cur, null) }
}

/** 宿主级编辑器结果通道：兼容 `SharedFlow<FullEditorReplySubmitted>`，并供首楼整帖删除通知刷新列表。 */
@OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
internal class FullEditorHostChannel(
    private val delegate: MutableSharedFlow<FullEditorReplySubmitted>,
    private val refreshForumProvider: () -> Unit,
) : SharedFlow<FullEditorReplySubmitted> by delegate {
    fun emit(event: FullEditorReplySubmitted) { delegate.tryEmit(event) }
    fun notifyThreadDeleted() { refreshForumProvider() }
}

@Composable
fun rememberHiltFullEditorRequestBus(): FullEditorRequestBus? =
    rememberComposeSlotsEntryPoint()?.fullEditorLauncher()?.orElse(null) as? FullEditorRequestBus

@Immutable
data class FullEditorSavedState(
    val isOpen: Boolean = false,
    val mode: FullEditorMode = FullEditorMode.NewThread(null),
    val selectedFid: Int? = null,
    val selectedTypeId: String = "0",
    val subject: String = "",
    val serializedBody: String = "",
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val uploadedImageIds: List<String> = emptyList(),
    val hadPersistedDraft: Boolean = false,
    val showDraftRestoredBanner: Boolean = false,
    val activeTextBlockOrdinal: Int = 0,
    val editOriginalSubject: String? = null,
    val editOriginalTypeId: String = "0",
    val editOriginalSerializedBody: String = "",
    val editOriginalImageIds: List<String> = emptyList(),
)

val FullEditorSavedStateSaver: Saver<FullEditorSavedState, Any> = listSaver(
    save = { s ->
        if (!s.isOpen) emptyList() else when (val m = s.mode) {
            is FullEditorMode.NewThread -> listOf("new", "", m.initialFid ?: -1, "", "", -1, "")
            is FullEditorMode.ReplyThread -> listOf("reply", m.tid, m.fid, "", "", -1, m.threadAuthor.orEmpty())
            is FullEditorMode.ReplyPost -> listOf("reply_post", m.tid, m.fid, m.targetPid, m.targetAuthor.orEmpty(), m.targetFloor ?: -1, m.threadAuthor.orEmpty())
            is FullEditorMode.QuotePost -> listOf("quote", m.tid, m.fid, m.targetPid, m.targetAuthor.orEmpty(), m.targetFloor ?: -1, m.threadAuthor.orEmpty())
            is FullEditorMode.EditPost -> listOf("edit", m.tid, m.fid, m.pid, m.author.orEmpty(), m.floorNumber ?: -1, "${m.page}:${if (m.isFirstFloor) 1 else 0}")
        } + listOf(
            s.selectedFid ?: -1, s.selectedTypeId, s.subject, s.serializedBody, s.selectionStart, s.selectionEnd,
            s.uploadedImageIds, s.hadPersistedDraft, s.showDraftRestoredBanner, s.activeTextBlockOrdinal,
            s.editOriginalSubject.orEmpty(), s.editOriginalSubject != null, s.editOriginalTypeId, s.editOriginalSerializedBody, s.editOriginalImageIds,
        )
    },
    restore = { r ->
        if (r.size < 16) FullEditorSavedState() else {
            val tid = r[1] as String; val fid = r[2] as Int; val pid = r[3] as String
            val author = (r[4] as String).ifEmpty { null }; val floor = (r[5] as Int).takeIf { it > 0 }; val op = (r[6] as String).ifEmpty { null }
            val mode = when (r[0] as String) {
                "reply" -> FullEditorMode.ReplyThread(tid, fid, op)
                "reply_post" -> FullEditorMode.ReplyPost(tid, fid, pid, author, floor, op)
                "quote" -> FullEditorMode.QuotePost(tid, fid, pid, author, floor, op)
                "edit" -> {
                    val parts = op.orEmpty().split(':')
                    FullEditorMode.EditPost(fid.coerceAtLeast(0), tid, pid, parts.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: 1, parts.getOrNull(1) == "1", floor, author)
                }
                else -> FullEditorMode.NewThread(fid.takeIf { it > 0 })
            }
            val hasOrig = (r.getOrNull(18) as? Boolean) == true
            @Suppress("UNCHECKED_CAST")
            FullEditorSavedState(
                true, mode, (r[7] as Int).takeIf { it > 0 }, r[8] as String, r[9] as String, r[10] as String, r[11] as Int, r[12] as Int,
                (r[13] as? List<String>).orEmpty(), r[14] as Boolean, r[15] as Boolean, (r.getOrNull(16) as? Int)?.coerceAtLeast(0) ?: 0,
                if (hasOrig) (r.getOrNull(17) as? String).orEmpty() else null, (r.getOrNull(19) as? String) ?: "0",
                (r.getOrNull(20) as? String).orEmpty(), (r.getOrNull(21) as? List<String>).orEmpty(),
            )
        }
    },
)

/** 宿主级完整编辑器连接控制器：封装 `rememberSaveable` 保活、单例总线消费、事件反馈与跨投放形态 `movableContentOf`。 */
@Stable
class FullEditorHostController internal constructor(
    val isVisible: Boolean, val sessionId: Long, val placement: FullEditorPlacement,
    val replyResults: SharedFlow<FullEditorReplySubmitted>, val open: (FullEditorRequest) -> Unit,
    val handleBack: () -> Unit, val renderInPlacement: @Composable (FullEditorPlacement, Float) -> Unit,
) {
    val isInRightPane: Boolean get() = isVisible && placement == FullEditorPlacement.RightPane
    val isFullScreen: Boolean get() = isVisible && placement == FullEditorPlacement.FullScreen
}

@Composable
fun rememberFullEditorHostController(
    paneLayout: ShellPaneLayout, windowHeightDp: Float, effectiveMiddleWidthDp: Dp, selectedThreadTid: String?,
    snackbarController: PaneSnackbarController, coroutineScope: CoroutineScope,
    onRefreshForum: () -> Unit, onOpenThread: (ThreadNavArgs) -> Unit, onOpenLogin: () -> Unit,
    injectedViewModel: FullEditorViewModel? = null, injectedRequestBus: FullEditorRequestBus? = null,
    emojiPanelSlot: (@Composable (QuickReplyEmojiPanelHost) -> Unit)? = null,
): FullEditorHostController {
    val resources = LocalResources.current
    var savedState by rememberSaveable(stateSaver = FullEditorSavedStateSaver) { mutableStateOf(FullEditorSavedState()) }
    var reqSeq by remember { mutableStateOf(0L) }
    var pendingReq by remember { mutableStateOf<PendingFullEditorRequest?>(null) }
    var mountedVm by remember(injectedViewModel) { mutableStateOf(injectedViewModel) }
    var backAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val latestSelectedTid by rememberUpdatedState(selectedThreadTid)
    val latestRefreshForum by rememberUpdatedState(onRefreshForum)
    val latestOpenThread by rememberUpdatedState(onOpenThread)
    val latestOpenLogin by rememberUpdatedState(onOpenLogin)
    val latestEmojiSlot by rememberUpdatedState(emojiPanelSlot)
    val replyResults = remember {
        FullEditorHostChannel(MutableSharedFlow(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)) { latestRefreshForum() }
    }

    val open: (FullEditorRequest) -> Unit = { req -> reqSeq += 1L; pendingReq = PendingFullEditorRequest(reqSeq, req) }
    val resolvedBus = injectedRequestBus ?: rememberHiltFullEditorRequestBus()
    val busPending = resolvedBus?.pendingRequest?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(busPending?.id) { val item = busPending ?: return@LaunchedEffect; resolvedBus.consume(item.id); open(item.request) }

    val vm: FullEditorViewModel? = if (savedState.isOpen || pendingReq != null || mountedVm != null) {
        (injectedViewModel ?: mountedVm ?: viewModel<FullEditorViewModel>()).also { resolved -> SideEffect { if (mountedVm !== resolved) mountedVm = resolved } }
    } else null
    // 同步恢复保存态，确保进程重建首帧即按正确投放位置挂载 BackHandler（右栏态早于模态抽屉）
    if (vm != null && savedState.isOpen && pendingReq == null) {
        remember(vm) { vm.restoreSavedStateIfFresh(savedState); true }
    }
    val uiState = vm?.uiState?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(vm, pendingReq?.id) {
        val targetVm = vm ?: return@LaunchedEffect
        val p = pendingReq ?: return@LaunchedEffect
        targetVm.open(p.request)
        if (pendingReq?.id == p.id) pendingReq = null
    }
    if (vm != null && uiState?.isOpen == true) SideEffect { val exp = vm.exportSavedState(); if (savedState != exp) savedState = exp }

    LaunchedEffect(vm, snackbarController, resources) {
        val targetVm = vm ?: return@LaunchedEffect
        fun showBar(msgRes: Int, target: PaneSnackbarTarget, actionRes: Int, onAction: () -> Unit) {
            coroutineScope.launch { snackbarController.show(resources.getString(msgRes), target, resources.getString(actionRes), onAction) }
        }
        targetVm.events.collect { ev ->
            when (ev) {
                is FullEditorEvent.DraftSaved -> showBar(R.string.compose_snackbar_draft_saved, if (ev.isReply) PaneSnackbarTarget.DetailPane else PaneSnackbarTarget.ContentArea, R.string.compose_action_discard_draft) { targetVm.discardSavedDraft(ev.draftKey) }
                is FullEditorEvent.NewThreadSubmitted -> {
                    savedState = FullEditorSavedState(); latestRefreshForum()
                    val forumName = ForumMetadataRegistry.resolve(ev.fid).fullName
                    showBar(R.string.compose_snackbar_thread_sent, PaneSnackbarTarget.ContentArea, R.string.compose_action_view) { latestOpenThread(ThreadNavArgs(ev.tid, ev.title, ev.fid, forumName)) }
                }
                is FullEditorEvent.ReplySubmitted -> {
                    savedState = FullEditorSavedState()
                    if (latestSelectedTid == ev.tid) replyResults.emit(FullEditorReplySubmitted(ev.tid, ev.redirectedPage))
                    else showBar(R.string.compose_snackbar_reply_sent, PaneSnackbarTarget.DetailPane, R.string.compose_action_view) { latestOpenThread(ThreadNavArgs(ev.tid, targetPid = ev.redirectedPage?.floors?.lastOrNull()?.postId)) }
                }
                is FullEditorEvent.PostEdited -> {
                    savedState = FullEditorSavedState()
                    if (ev.isFirstFloor) latestRefreshForum()
                    replyResults.emit(FullEditorReplySubmitted(ev.tid, ev.redirectedPage, editedPid = ev.pid, editedPage = ev.page))
                }
                FullEditorEvent.Closed -> savedState = FullEditorSavedState()
            }
        }
    }

    val movableEditor = remember {
        movableContentOf { editorVm: FullEditorViewModel, isModal: Boolean ->
            FullEditorScreen(editorVm, { latestOpenLogin() }, isModalCard = isModal, registerBackHandler = false, onRegisterBackAction = { backAction = it }, emojiPanelSlot = latestEmojiSlot)
        }
    }
    val effectiveRightW = if (paneLayout.isThreePane) (paneLayout.contentWidthDp.dp - effectiveMiddleWidthDp).coerceAtLeast(0.dp) else paneLayout.rightPaneWidthDp.dp
    val placement = resolveFullEditorPlacement(paneLayout, windowHeightDp, effectiveRightW.value)
    val invokeBack: () -> Unit = { backAction?.invoke() ?: vm?.requestClose(true) }
    return FullEditorHostController(
        isVisible = vm != null && uiState?.isOpen == true, sessionId = uiState?.sessionId ?: 0L, placement = placement,
        replyResults = replyResults, open = open, handleBack = invokeBack,
        renderInPlacement = { p, winW ->
            if (vm != null) {
                // 随投放位置挂载 BackHandler：右栏态在 HiAdaptiveShell 内低于抽屉，全屏/居中态在壳层之上且低于看图与登录浮层
                key(uiState?.sessionId ?: 0L) { BackHandler(enabled = uiState?.isOpen == true, onBack = invokeBack) }
                FullEditorPlacementFrame(p, winW, uiState?.isSubmitting == true, invokeBack) { isModal -> movableEditor(vm, isModal) }
            }
        },
    )
}

/** 纯色完整编辑器承载容器（0 `HiGlassMarker`）。 */
@Composable
fun FullEditorPlacementFrame(placement: FullEditorPlacement, windowWidthDp: Float, isSubmitting: Boolean, onRequestClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable (isModalCard: Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    when (placement) {
        FullEditorPlacement.FullScreen -> Surface(modifier.fillMaxSize(), color = cs.surface, contentColor = cs.onSurface) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))) { content(false) }
        }
        FullEditorPlacement.CenteredModal -> Box(modifier.fillMaxSize()) {
            val scrimInt = remember { MutableInteractionSource() }
            Box(Modifier.fillMaxSize().background(cs.scrim.copy(alpha = 0.40f)).clickable(scrimInt, null, enabled = !isSubmitting, onClick = onRequestClose))
            Surface(
                Modifier.align(Alignment.Center).systemBarsPadding().imePadding().padding(vertical = 24.dp).width(calculateCenteredModalWidthDp(windowWidthDp)).fillMaxHeight().heightIn(max = 720.dp).clip(HiShapeTokens.Large),
                shape = HiShapeTokens.Large, color = cs.surface, contentColor = cs.onSurface, tonalElevation = 2.dp,
            ) { content(true) }
        }
        FullEditorPlacement.RightPane -> Surface(modifier.fillMaxSize(), color = cs.surface, contentColor = cs.onSurface) {
            Box(Modifier.fillMaxSize(), Alignment.TopCenter) { Box(Modifier.widthIn(max = FULL_EDITOR_MAX_WIDTH_DP.dp).fillMaxWidth().fillMaxHeight()) { content(false) } }
        }
    }
}
