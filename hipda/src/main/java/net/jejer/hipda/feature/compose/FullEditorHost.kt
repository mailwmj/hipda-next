package net.jejer.hipda.feature.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.PaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.shell.ForumMetadataRegistry
import net.jejer.hipda.ui.shell.ShellPaneLayout
import net.jejer.hipda.ui.shell.ShellPaneLayoutMode

const val FULL_EDITOR_RIGHT_PANE_MIN_WIDTH_DP: Float = 560f
const val FULL_EDITOR_MAX_WIDTH_DP: Float = 680f
const val FULL_EDITOR_MODAL_HORIZONTAL_MARGIN_DP: Float = 48f
private const val FULL_EDITOR_COMPACT_HEIGHT_DP: Float = 480f
private const val FULL_EDITOR_SCRIM_ALPHA: Float = 0.40f

/** 完整编辑器多栏投放形态（严格遵循 `product-design.md` §4.2）。 */
enum class FullEditorPlacement { FullScreen, CenteredModal, RightPane }

fun resolveFullEditorPlacement(
    paneLayout: ShellPaneLayout,
    windowHeightDp: Float,
    effectiveRightPaneWidthDp: Float = paneLayout.rightPaneWidthDp.toFloat(),
): FullEditorPlacement = when {
    windowHeightDp < FULL_EDITOR_COMPACT_HEIGHT_DP -> FullEditorPlacement.FullScreen
    paneLayout.layoutMode in setOf(ShellPaneLayoutMode.SinglePane, ShellPaneLayoutMode.CompactSinglePane, ShellPaneLayoutMode.HingeFallbackSinglePane) ->
        FullEditorPlacement.FullScreen
    paneLayout.layoutMode == ShellPaneLayoutMode.SidebarSinglePane || paneLayout.layoutMode == ShellPaneLayoutMode.HingeTwoPane ->
        FullEditorPlacement.CenteredModal
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
    fun enqueue(request: FullEditorRequest) { _pendingRequest.value = PendingFullEditorRequest(seq.incrementAndGet(), request) }
    fun consume(id: Long) { val cur = _pendingRequest.value; if (cur?.id == id) _pendingRequest.compareAndSet(cur, null) }
}

@Composable
fun rememberHiltFullEditorRequestBus(): FullEditorRequestBus? {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        if (appContext is GeneratedComponentManagerHolder) {
            runCatching { EntryPointAccessors.fromApplication(appContext, ComposeSlotsEntryPoint::class.java).fullEditorLauncher().orElse(null) as? FullEditorRequestBus }.getOrNull()
        } else null
    }
}

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
)

val FullEditorSavedStateSaver: Saver<FullEditorSavedState, Any> = listSaver(
    save = { s ->
        if (!s.isOpen) emptyList() else when (val m = s.mode) {
            is FullEditorMode.NewThread -> listOf("new", "", m.initialFid ?: -1, "", "", -1, "")
            is FullEditorMode.ReplyThread -> listOf("reply", m.tid, m.fid, "", "", -1, m.threadAuthor.orEmpty())
            is FullEditorMode.ReplyPost -> listOf("reply_post", m.tid, m.fid, m.targetPid, m.targetAuthor.orEmpty(), m.targetFloor ?: -1, m.threadAuthor.orEmpty())
            is FullEditorMode.QuotePost -> listOf("quote", m.tid, m.fid, m.targetPid, m.targetAuthor.orEmpty(), m.targetFloor ?: -1, m.threadAuthor.orEmpty())
        } + listOf(s.selectedFid ?: -1, s.selectedTypeId, s.subject, s.serializedBody, s.selectionStart, s.selectionEnd, s.uploadedImageIds, s.hadPersistedDraft, s.showDraftRestoredBanner)
    },
    restore = { r ->
        if (r.size < 16) FullEditorSavedState() else {
            val tid = r[1] as String; val fid = r[2] as Int; val pid = r[3] as String
            val author = (r[4] as String).ifEmpty { null }; val floor = (r[5] as Int).takeIf { it > 0 }; val op = (r[6] as String).ifEmpty { null }
            val mode = when (r[0] as String) {
                "reply" -> FullEditorMode.ReplyThread(tid, fid, op)
                "reply_post" -> FullEditorMode.ReplyPost(tid, fid, pid, author, floor, op)
                "quote" -> FullEditorMode.QuotePost(tid, fid, pid, author, floor, op)
                else -> FullEditorMode.NewThread(fid.takeIf { it > 0 })
            }
            @Suppress("UNCHECKED_CAST")
            FullEditorSavedState(true, mode, (r[7] as Int).takeIf { it > 0 }, r[8] as String, r[9] as String, r[10] as String, r[11] as Int, r[12] as Int, (r[13] as? List<String>).orEmpty(), r[14] as Boolean, r[15] as Boolean)
        }
    },
)

/** 宿主级完整编辑器连接控制器：封装 `rememberSaveable` 保活、单例总线消费、事件反馈与跨投放形态 `movableContentOf`。 */
@Stable
class FullEditorHostController internal constructor(
    val isVisible: Boolean,
    val sessionId: Long,
    val placement: FullEditorPlacement,
    val replyResults: SharedFlow<FullEditorReplySubmitted>,
    val open: (FullEditorRequest) -> Unit,
    val handleBack: () -> Unit,
    val renderInPlacement: @Composable (FullEditorPlacement, Float) -> Unit,
) {
    val isInRightPane: Boolean get() = isVisible && placement == FullEditorPlacement.RightPane
    val isFullScreen: Boolean get() = isVisible && placement == FullEditorPlacement.FullScreen
}

@Composable
fun rememberFullEditorHostController(
    paneLayout: ShellPaneLayout,
    windowHeightDp: Float,
    effectiveMiddleWidthDp: Dp,
    selectedThreadTid: String?,
    snackbarController: PaneSnackbarController,
    coroutineScope: CoroutineScope,
    onRefreshForum: () -> Unit,
    onOpenThread: (ThreadNavArgs) -> Unit,
    onOpenLogin: () -> Unit,
    injectedViewModel: FullEditorViewModel? = null,
    injectedRequestBus: FullEditorRequestBus? = null,
    emojiPanelSlot: (@Composable (QuickReplyEmojiPanelHost) -> Unit)? = null,
): FullEditorHostController {
    val resources = LocalResources.current
    var savedState by rememberSaveable(stateSaver = FullEditorSavedStateSaver) { mutableStateOf(FullEditorSavedState()) }
    var reqSeq by remember { mutableStateOf(0L) }
    var pendingReq by remember { mutableStateOf<PendingFullEditorRequest?>(null) }
    var mountedVm by remember(injectedViewModel) { mutableStateOf(injectedViewModel) }
    var backAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val replyResults = remember { MutableSharedFlow<FullEditorReplySubmitted>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST) }

    val open: (FullEditorRequest) -> Unit = { req -> reqSeq += 1L; pendingReq = PendingFullEditorRequest(reqSeq, req) }
    val resolvedBus = injectedRequestBus ?: rememberHiltFullEditorRequestBus()
    val busPending = resolvedBus?.pendingRequest?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(busPending?.id) {
        val item = busPending ?: return@LaunchedEffect
        resolvedBus.consume(item.id)
        open(item.request)
    }

    val mountedUiState = mountedVm?.uiState?.collectAsStateWithLifecycle()?.value
    val activeVm: FullEditorViewModel? = if (savedState.isOpen || pendingReq != null || mountedUiState?.isOpen == true) {
        (injectedViewModel ?: mountedVm ?: viewModel<FullEditorViewModel>()).also { vm ->
            SideEffect { if (mountedVm !== vm) mountedVm = vm }
        }
    } else null
    LaunchedEffect(activeVm, pendingReq?.id) {
        val vm = activeVm ?: return@LaunchedEffect
        val p = pendingReq
        if (p != null) {
            vm.open(p.request)
            if (pendingReq?.id == p.id) pendingReq = null
        } else if (savedState.isOpen) {
            vm.restoreSavedStateIfFresh(savedState)
        }
    }
    val activeUiState = if (activeVm === mountedVm) mountedUiState else activeVm?.uiState?.collectAsStateWithLifecycle()?.value
    if (activeVm != null && activeUiState?.isOpen == true) {
        SideEffect { val exp = activeVm.exportSavedState(); if (savedState != exp) savedState = exp }
    }

    val latestSelectedTid by rememberUpdatedState(selectedThreadTid)
    val latestRefreshForum by rememberUpdatedState(onRefreshForum)
    val latestOpenThread by rememberUpdatedState(onOpenThread)
    val latestOpenLogin by rememberUpdatedState(onOpenLogin)
    val latestEmojiSlot by rememberUpdatedState(emojiPanelSlot)

    LaunchedEffect(mountedVm, snackbarController, resources) {
        val vm = mountedVm ?: return@LaunchedEffect
        vm.events.collect { ev ->
            when (ev) {
                is FullEditorEvent.DraftSaved -> {
                    savedState = FullEditorSavedState()
                    val target = if (ev.isReply) PaneSnackbarTarget.DetailPane else PaneSnackbarTarget.ContentArea
                    coroutineScope.launch {
                        snackbarController.show(resources.getString(R.string.compose_snackbar_draft_saved), target, resources.getString(R.string.compose_action_discard_draft)) { vm.discardSavedDraft(ev.draftKey) }
                    }
                }
                is FullEditorEvent.NewThreadSubmitted -> {
                    savedState = FullEditorSavedState()
                    latestRefreshForum()
                    val forumName = ForumMetadataRegistry.resolve(ev.fid).fullName
                    coroutineScope.launch {
                        snackbarController.show(resources.getString(R.string.compose_snackbar_thread_sent), PaneSnackbarTarget.ContentArea, resources.getString(R.string.compose_action_view)) {
                            latestOpenThread(ThreadNavArgs(ev.tid, ev.title, ev.fid, forumName))
                        }
                    }
                }
                is FullEditorEvent.ReplySubmitted -> {
                    savedState = FullEditorSavedState()
                    if (latestSelectedTid == ev.tid) {
                        replyResults.tryEmit(FullEditorReplySubmitted(ev.tid, ev.redirectedPage))
                    } else {
                        val pid = ev.redirectedPage?.floors?.lastOrNull()?.postId
                        coroutineScope.launch {
                            snackbarController.show(resources.getString(R.string.compose_snackbar_reply_sent), PaneSnackbarTarget.DetailPane, resources.getString(R.string.compose_action_view)) {
                                latestOpenThread(ThreadNavArgs(ev.tid, targetPid = pid))
                            }
                        }
                    }
                }
                FullEditorEvent.Closed -> savedState = FullEditorSavedState()
            }
        }
    }

    val movableEditor = remember {
        movableContentOf { vm: FullEditorViewModel, isModal: Boolean ->
            FullEditorScreen(
                viewModel = vm, onOpenLogin = { latestOpenLogin() }, isModalCard = isModal,
                registerBackHandler = false, onRegisterBackAction = { backAction = it }, emojiPanelSlot = latestEmojiSlot,
            )
        }
    }
    val effectiveRightW = if (paneLayout.isThreePane) (paneLayout.contentWidthDp.dp - effectiveMiddleWidthDp).coerceAtLeast(0.dp) else paneLayout.rightPaneWidthDp.dp
    val placement = resolveFullEditorPlacement(paneLayout, windowHeightDp, effectiveRightW.value)
    val isVisible = activeVm != null && activeUiState?.isOpen == true
    val invokeBack: () -> Unit = { backAction?.invoke() ?: activeVm?.requestClose(true) }

    return FullEditorHostController(
        isVisible = isVisible, sessionId = activeUiState?.sessionId ?: 0L, placement = placement,
        replyResults = replyResults, open = open, handleBack = invokeBack,
        renderInPlacement = { p, winW ->
            if (activeVm != null) {
                FullEditorPlacementFrame(p, winW, activeUiState?.isSubmitting == true, invokeBack) { isModal ->
                    movableEditor(activeVm, isModal)
                }
            }
        },
    )
}

/** 纯色完整编辑器承载容器（0 `HiGlassMarker`）。 */
@Composable
fun FullEditorPlacementFrame(
    placement: FullEditorPlacement, windowWidthDp: Float, isSubmitting: Boolean,
    onRequestClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable (isModalCard: Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    when (placement) {
        FullEditorPlacement.FullScreen -> Surface(modifier.fillMaxSize(), color = cs.surface, contentColor = cs.onSurface) { content(false) }
        FullEditorPlacement.CenteredModal -> Box(modifier.fillMaxSize()) {
            val scrimInt = remember { MutableInteractionSource() }
            Box(Modifier.fillMaxSize().background(cs.scrim.copy(alpha = FULL_EDITOR_SCRIM_ALPHA)).clickable(scrimInt, null, enabled = !isSubmitting, onClick = onRequestClose))
            Surface(
                modifier = Modifier.align(Alignment.Center).systemBarsPadding().imePadding().padding(vertical = 24.dp)
                    .width(calculateCenteredModalWidthDp(windowWidthDp)).fillMaxHeight().heightIn(max = 720.dp).clip(HiShapeTokens.Large),
                shape = HiShapeTokens.Large, color = cs.surface, contentColor = cs.onSurface, tonalElevation = 2.dp,
            ) { content(true) }
        }
        FullEditorPlacement.RightPane -> Surface(modifier.fillMaxSize(), color = cs.surface, contentColor = cs.onSurface) {
            Box(Modifier.fillMaxSize(), Alignment.TopCenter) {
                Box(Modifier.widthIn(max = FULL_EDITOR_MAX_WIDTH_DP.dp).fillMaxWidth().fillMaxHeight()) { content(false) }
            }
        }
    }
}
