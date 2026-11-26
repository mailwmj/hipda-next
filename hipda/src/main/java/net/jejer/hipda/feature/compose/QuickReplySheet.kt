package net.jejer.hipda.feature.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.min
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.HiIconSwitch
import net.jejer.hipda.ui.designsystem.feedback.HiRollingNumber
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.glass.HiGlassState
import net.jejer.hipda.ui.designsystem.glass.HiGlassStyle
import net.jejer.hipda.ui.designsystem.glass.HiGlassSurface
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.motion.*
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner

val QuickReplyMaxHeight: Dp = 300.dp
const val QuickReplyMaxHeightFraction: Float = 0.60f
val QuickReplyCompactNetHeightThreshold: Dp = 180.dp
val QuickReplyNormalWindowMinHeight: Dp = 480.dp
val QuickReplyHandleHeight: Dp = 32.dp
private val QuickReplySheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private val QuickReplyDragDismissThreshold: Dp = 72.dp
private val QuickReplyDragExpandThreshold: Dp = 56.dp
private val QuickReplyMaxUpwardDrag: Dp = 84.dp
private val QuickReplyTightSheetThreshold: Dp = 164.dp
private val DefaultSheetFallbackHeight: Dp = 220.dp
private const val DISMISS_VELOCITY_DP_PER_SEC = 720f
private const val EXPAND_VELOCITY_DP_PER_SEC = -720f

@Immutable
data class QuickReplyLayoutMetrics(
    val visibleNetHeightDp: Dp,
    val maxSheetHeightDp: Dp,
    val isCompactShortScreen: Boolean,
    val shouldLiftUnderlyingList: Boolean,
)

/** 按 §7.3 计算可视净高、钳制最大高度 `min(300dp, netHeight * 60%)` 及矮屏紧凑/列表上移开关。 */
fun calculateQuickReplyLayoutMetrics(paneHeightDp: Dp, statusBarTopDp: Dp, imeBottomDp: Dp, navBarBottomDp: Dp): QuickReplyLayoutMetrics {
    val reservedBottom = if (imeBottomDp > navBarBottomDp) imeBottomDp else navBarBottomDp
    val netHeight = (paneHeightDp - statusBarTopDp - reservedBottom).coerceAtLeast(0.dp)
    val maxSheet = minOf(QuickReplyMaxHeight, netHeight * QuickReplyMaxHeightFraction)
    val compact = netHeight < QuickReplyCompactNetHeightThreshold
    val liftList = paneHeightDp >= QuickReplyNormalWindowMinHeight && netHeight >= QuickReplyCompactNetHeightThreshold
    return QuickReplyLayoutMetrics(netHeight, maxSheet, compact, liftList)
}

/** 按 §7.4 计算表情面板高度：优先复用最近键盘高度，缺失时按窗口高比例回退。 */
fun calculateEmojiPanelHeight(lastKeyboardHeightDp: Dp, windowHeightDp: Dp): Dp {
    if (lastKeyboardHeightDp > 0.dp) return lastKeyboardHeightDp
    val ratio = if (windowHeightDp < QuickReplyNormalWindowMinHeight) 0.35f else 0.45f
    return min(280f, windowHeightDp.value * ratio).coerceAtLeast(0f).dp
}

private enum class SheetMotionPhase { Entering, Open, Dismissing, Expanding }
private enum class SendButtonVisualState { Ready, Submitting, Cooldown }

/** 毛玻璃跟手弹出快速回复面板（§5、§6.3、§7.2、§7.3）：复用 `detailGlassState`、`HiGlassStyle.Sheet`，仅占所在详情栏宽度。 */
@Composable
fun QuickReplySheet(
    viewModel: QuickReplyViewModel,
    glassState: HiGlassState,
    onDismissFinished: () -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
    onExpandToFullEditor: ((FullEditorHandoff) -> Unit)? = null,
    emojiPanelSlot: (@Composable (QuickReplyEmojiPanelHost) -> Unit)? = null,
    onOccupiedBottomChanged: (occupiedBottomDp: Dp, shouldLiftList: Boolean) -> Unit = { _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val slots = rememberResolvedComposeSlots(onExpandToFullEditor, emojiPanelSlot)
    val effectiveExpand = slots.onExpandToFullEditor
    val effectiveEmojiSlot = slots.emojiPanelSlot
    val canExpandToFullEditor = effectiveExpand != null
    val hasEmojiPanel = effectiveEmojiSlot != null

    val reducedMotion = HiTheme.isReducedMotion
    val density = LocalDensity.current
    val scope = rememberHiMotionCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val inputFocusRequester = remember { FocusRequester() }
    val textFieldState = rememberTextFieldState(initialText = uiState.text)
    val inputScrollState = rememberScrollState()

    val latestOnDismissFinished by rememberUpdatedState(onDismissFinished)
    val latestOnExpand by rememberUpdatedState(effectiveExpand)
    val latestReducedMotion by rememberUpdatedState(reducedMotion)
    val latestCanExpand by rememberUpdatedState(canExpandToFullEditor)
    val latestOnOccupiedChanged by rememberUpdatedState(onOccupiedBottomChanged)

    val fallbackHeightPx = with(density) { DefaultSheetFallbackHeight.toPx() }
    var phase by remember { mutableStateOf(SheetMotionPhase.Entering) }
    var measuredSheetHeightPx by remember { mutableFloatStateOf(fallbackHeightPx) }
    val offsetYPx = remember { Animatable(if (reducedMotion) 0f else fallbackHeightPx) }
    var isEmojiPanelOpen by remember { mutableStateOf(false) }
    var lastObservedImeDp by remember { mutableStateOf(0.dp) }

    val dismissThresholdPx = with(density) { QuickReplyDragDismissThreshold.toPx() }
    val expandThresholdPx = with(density) { QuickReplyDragExpandThreshold.toPx() }
    val maxUpwardDragPx = with(density) { QuickReplyMaxUpwardDrag.toPx() }
    val dismissVelocityPx = with(density) { DISMISS_VELOCITY_DP_PER_SEC.dp.toPx() }
    val expandVelocityPx = with(density) { EXPAND_VELOCITY_DP_PER_SEC.dp.toPx() }

    var lastSyncedText by remember { mutableStateOf(textFieldState.text.toString()) }
    LaunchedEffect(uiState.textSyncVersion) {
        lastSyncedText = uiState.text
        if (textFieldState.text.toString() != uiState.text) textFieldState.setTextAndPlaceCursorAtEnd(uiState.text)
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect { current ->
            if (current != lastSyncedText) {
                lastSyncedText = current
                viewModel.onTextChanged(current)
            }
        }
    }
    LaunchedEffect(Unit) {
        if (latestReducedMotion) offsetYPx.snapTo(0f) else offsetYPx.animateTo(0f, animationSpec = HiMotion.spatialDefault())
        if (phase == SheetMotionPhase.Entering) phase = SheetMotionPhase.Open
    }
    LaunchedEffect(uiState.target.tid, uiState.isLoggedIn, isEmojiPanelOpen) {
        if (uiState.isLoggedIn && !isEmojiPanelOpen && phase != SheetMotionPhase.Dismissing) {
            runCatching { inputFocusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    val requestDismiss: (Float) -> Unit = remember(viewModel, keyboardController, focusManager, scope, fallbackHeightPx) {
        { initialVelocity ->
            if (phase != SheetMotionPhase.Dismissing && phase != SheetMotionPhase.Expanding) {
                phase = SheetMotionPhase.Dismissing
                isEmojiPanelOpen = false
                keyboardController?.hide()
                focusManager.clearFocus(force = false)
                viewModel.onDismissSession(emitSavedSnackbar = true)
                val targetY = measuredSheetHeightPx.coerceAtLeast(fallbackHeightPx)
                if (latestReducedMotion) {
                    scope.launch { offsetYPx.snapTo(targetY) }
                    latestOnDismissFinished()
                } else {
                    scope.launch {
                        offsetYPx.animateTo(targetY, HiMotion.exit(HiMotion.spatialDefault()), initialVelocity.coerceAtLeast(0f))
                        latestOnDismissFinished()
                    }
                }
            }
        }
    }

    val requestExpandToFullEditor: (Boolean, Float) -> Unit = remember(viewModel, textFieldState, keyboardController, scope, maxUpwardDragPx) {
        { openImagePicker, initialVelocity ->
            val handler = latestOnExpand
            if (handler != null && phase != SheetMotionPhase.Dismissing && phase != SheetMotionPhase.Expanding) {
                phase = SheetMotionPhase.Expanding
                isEmojiPanelOpen = false
                keyboardController?.hide()
                val text = textFieldState.text.toString()
                val sel = textFieldState.selection
                val handoff = viewModel.onHandoffToFullEditor(text, sel.start, sel.end, openImagePicker)
                if (latestReducedMotion) {
                    handler(handoff); latestOnDismissFinished()
                } else {
                    scope.launch {
                        offsetYPx.animateTo(-maxUpwardDragPx * 0.5f, HiMotion.spatialDefault(), initialVelocity.coerceAtMost(0f))
                        handler(handoff); latestOnDismissFinished()
                    }
                }
            }
        }
    }

    val onDragDelta: (Float, Boolean) -> Unit = remember(scope, maxUpwardDragPx, fallbackHeightPx) {
        { deltaY, allowUpwardExpand ->
            if (phase != SheetMotionPhase.Dismissing && phase != SheetMotionPhase.Expanding) {
                val minOffset = if (allowUpwardExpand && latestCanExpand) -maxUpwardDragPx else 0f
                val maxOffset = measuredSheetHeightPx.coerceAtLeast(fallbackHeightPx)
                val next = (offsetYPx.value + deltaY).coerceIn(minOffset, maxOffset)
                scope.launch { offsetYPx.snapTo(next) }
            }
        }
    }

    val onDragSettled: (Float, Boolean) -> Unit = remember(scope, dismissThresholdPx, expandThresholdPx, dismissVelocityPx, expandVelocityPx) {
        { velocityY, allowUpwardExpand ->
            if (phase != SheetMotionPhase.Dismissing && phase != SheetMotionPhase.Expanding) {
                val cur = offsetYPx.value
                when {
                    cur >= dismissThresholdPx || (cur > 0f && velocityY >= dismissVelocityPx) -> requestDismiss(velocityY)
                    allowUpwardExpand && latestCanExpand && (cur <= -expandThresholdPx || (cur < 0f && velocityY <= expandVelocityPx)) ->
                        requestExpandToFullEditor(false, velocityY)
                    else -> scope.launch {
                        if (latestReducedMotion) offsetYPx.snapTo(0f) else offsetYPx.animateTo(0f, HiMotion.spatialSnap(), velocityY)
                    }
                }
            }
        }
    }

    val headerDragState = rememberDraggableState { delta -> onDragDelta(delta, true) }
    val inputNestedScroll = remember(inputScrollState, textFieldState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || !textFieldState.selection.collapsed) return Offset.Zero
                val curOffset = offsetYPx.value
                if (curOffset > 0f && available.y < 0f) {
                    val consumedY = available.y.coerceAtLeast(-curOffset)
                    onDragDelta(consumedY, false)
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || !textFieldState.selection.collapsed) return Offset.Zero
                if (available.y > 0f && inputScrollState.value == 0) {
                    onDragDelta(available.y, false)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offsetYPx.value > 0f) {
                    onDragSettled(available.y, false)
                    return Velocity(0f, available.y)
                }
                return Velocity.Zero
            }
        }
    }

    BackHandler(enabled = true) { if (isEmojiPanelOpen) isEmojiPanelOpen = false else requestDismiss(0f) }

    val contextLabel = resolveReplyContextLabel(uiState.target)
    val collapseCd = stringResource(R.string.compose_quick_reply_collapse_cd)
    val cs = MaterialTheme.colorScheme
    DisposableEffect(Unit) { onDispose { latestOnOccupiedChanged(0.dp, false) } }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val paneHeightDp = maxHeight
        val statusBarTopDp = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        val imeBottomDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
        val navBarBottomDp = maxOf(with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }, bottomInset)
        LaunchedEffect(imeBottomDp) {
            if (imeBottomDp > 0.dp) {
                lastObservedImeDp = imeBottomDp
                if (isEmojiPanelOpen) isEmojiPanelOpen = false
            }
        }
        val emojiPanelHeightDp = if (isEmojiPanelOpen && hasEmojiPanel) calculateEmojiPanelHeight(lastObservedImeDp, paneHeightDp) else 0.dp
        val effectiveImeOrEmojiDp = if (isEmojiPanelOpen && hasEmojiPanel) emojiPanelHeightDp else imeBottomDp
        val metrics = calculateQuickReplyLayoutMetrics(paneHeightDp, statusBarTopDp, effectiveImeOrEmojiDp, navBarBottomDp)
        val isTightSheet = !metrics.isCompactShortScreen && metrics.maxSheetHeightDp < QuickReplyTightSheetThreshold
        val totalOccupiedBottomDp = if (phase == SheetMotionPhase.Dismissing) 0.dp else with(density) { measuredSheetHeightPx.toDp() } + effectiveImeOrEmojiDp
        LaunchedEffect(totalOccupiedBottomDp, metrics.shouldLiftUnderlyingList) {
            latestOnOccupiedChanged(totalOccupiedBottomDp, metrics.shouldLiftUnderlyingList)
        }

        val dismissProgress = (offsetYPx.value / measuredSheetHeightPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxSize()
                .background(cs.scrim.copy(alpha = HiGlassTokens.SheetScrimAlpha * (1f - dismissProgress)))
                .clickable(remember { MutableInteractionSource() }, indication = null) { requestDismiss(0f) },
        )
        if (metrics.isCompactShortScreen) {
            Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; stateDescription = contextLabel })
        }

        val outerBottomPadding = if (isEmojiPanelOpen && hasEmojiPanel) 0.dp else imeBottomDp
        val innerBottomPadding = if (imeBottomDp > 0.dp || (isEmojiPanelOpen && hasEmojiPanel)) 8.dp else (navBarBottomDp + 8.dp)

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = outerBottomPadding)
                .graphicsLayer { translationY = offsetYPx.value }
                .semantics { paneTitle = contextLabel; isTraversalGroup = true },
        ) {
            HiGlassSurface(
                state = glassState, style = HiGlassStyle.Sheet, shape = QuickReplySheetShape, floating = true,
                contentAlignment = Alignment.TopStart,
                modifier = Modifier.fillMaxWidth().heightIn(max = metrics.maxSheetHeightDp)
                    .onSizeChanged { if (it.height > 0) measuredSheetHeightPx = it.height.toFloat() },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    if (!metrics.isCompactShortScreen) {
                        QuickReplyHeaderZone(
                            contextLabel, collapseCd, isTightSheet, canExpandToFullEditor, headerDragState,
                            onDragStopped = { onDragSettled(it, true) },
                            onCollapseClick = { requestDismiss(0f) },
                            onExpandClick = { requestExpandToFullEditor(false, 0f) },
                        )
                    }
                    when {
                        !uiState.isLoggedIn -> QuickReplyGuestPrompt(metrics.isCompactShortScreen, innerBottomPadding, onOpenLogin)
                        metrics.isCompactShortScreen || isTightSheet -> {
                            uiState.inlineError?.takeIf { metrics.maxSheetHeightDp >= 96.dp }?.let {
                                QuickReplyInlineErrorStrip(it, compact = true, onRetry = viewModel::submit, onOpenLogin = onOpenLogin)
                            }
                            val vPad = if (metrics.maxSheetHeightDp < 64.dp) 0.dp else 6.dp
                            Row(
                                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = vPad, bottom = vPad),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                QuickReplyInputBox(textFieldState, inputScrollState, contextLabel, inputFocusRequester, 40.dp, inputNestedScroll, Modifier.weight(1f))
                                if (canExpandToFullEditor && metrics.isCompactShortScreen) {
                                    QuickReplyToolIconButton(ComposeVectorIcons.ExpandFullScreen, stringResource(R.string.compose_action_expand_fullscreen)) {
                                        requestExpandToFullEditor(false, 0f)
                                    }
                                }
                                QuickReplySendButton(uiState.isSubmitting, uiState.cooldownSeconds, uiState.canSend, viewModel::submit)
                            }
                        }
                        else -> {
                            if (uiState.showDraftRestoredBanner) QuickReplyDraftRestoredBanner(viewModel::clearRestoredDraft)
                            QuickReplyInputBox(
                                textFieldState, inputScrollState, contextLabel, inputFocusRequester, 64.dp, inputNestedScroll,
                                Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            uiState.inlineError?.let { QuickReplyInlineErrorStrip(it, compact = false, onRetry = viewModel::submit, onOpenLogin = onOpenLogin) }
                            QuickReplyBottomActionBar(
                                canExpandToFullEditor, hasEmojiPanel, isEmojiPanelOpen, uiState.isSubmitting, uiState.cooldownSeconds, uiState.canSend, innerBottomPadding,
                                onInsertImageClick = { requestExpandToFullEditor(true, 0f) },
                                onToggleEmojiClick = {
                                    if (isEmojiPanelOpen) {
                                        isEmojiPanelOpen = false; runCatching { inputFocusRequester.requestFocus() }; keyboardController?.show()
                                    } else {
                                        keyboardController?.hide(); isEmojiPanelOpen = true
                                    }
                                },
                                onSendClick = viewModel::submit,
                            )
                        }
                    }
                }
            }
            if (isEmojiPanelOpen && effectiveEmojiSlot != null) {
                val emojiHost = remember(emojiPanelHeightDp, paneHeightDp, textFieldState, keyboardController) {
                    QuickReplyEmojiPanelHost(
                        keyboardHeightDp = emojiPanelHeightDp, windowHeightDp = paneHeightDp,
                        onInsertEmoji = { code ->
                            textFieldState.edit {
                                val s = selection.min
                                if (s != selection.max) delete(s, selection.max)
                                insert(s, code)
                                selection = TextRange(s + code.length)
                            }
                        },
                        onBackspace = {
                            textFieldState.edit {
                                val s = selection.min
                                if (s != selection.max) {
                                    delete(s, selection.max)
                                    selection = TextRange(s)
                                } else if (s > 0) {
                                    delete(s - 1, s)
                                    selection = TextRange(s - 1)
                                }
                            }
                        },
                        onClose = { isEmojiPanelOpen = false; keyboardController?.show() },
                    )
                }
                Box(Modifier.fillMaxWidth().height(emojiPanelHeightDp).background(cs.surfaceContainerLow)) { effectiveEmojiSlot(emojiHost) }
            }
        }
    }
}

@Composable
private fun resolveReplyContextLabel(target: QuickReplyTarget): String = when {
    target.mode == QuickReplyMode.QuotePost && target.targetFloor != null && !target.targetAuthor.isNullOrBlank() ->
        stringResource(R.string.compose_quote_placeholder_floor_user, target.targetFloor, target.targetAuthor)
    !target.targetAuthor.isNullOrBlank() -> stringResource(R.string.compose_reply_placeholder_user, target.targetAuthor)
    !target.threadAuthor.isNullOrBlank() -> stringResource(R.string.compose_reply_placeholder_user, target.threadAuthor)
    else -> stringResource(R.string.compose_reply_placeholder_default)
}

@Composable
private fun QuickReplyHeaderZone(
    contextLabel: String, collapseCd: String, tightMode: Boolean, showExpandButton: Boolean,
    dragState: DraggableState, onDragStopped: (Float) -> Unit, onCollapseClick: () -> Unit, onExpandClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val handleModifier = Modifier
        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onCollapseClick)
        .semantics { contentDescription = collapseCd; customActions = listOf(CustomAccessibilityAction(collapseCd) { onCollapseClick(); true }) }
    Column(Modifier.fillMaxWidth().draggable(dragState, Orientation.Vertical, onDragStopped = { onDragStopped(it) })) {
        if (tightMode) {
            HiCappedFontScale {
                Box(Modifier.fillMaxWidth().height(if (showExpandButton) 48.dp else QuickReplyHandleHeight).padding(start = 16.dp, end = if (showExpandButton) 4.dp else 16.dp)) {
                    Box(Modifier.align(Alignment.TopCenter).width(48.dp).height(QuickReplyHandleHeight).then(handleModifier), Alignment.TopCenter) {
                        Box(Modifier.padding(top = 6.dp).size(36.dp, 4.dp).clip(HiShapeTokens.Full).background(cs.onSurfaceVariant.copy(alpha = 0.42f)))
                    }
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            contextLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        if (showExpandButton) QuickReplyToolIconButton(ComposeVectorIcons.ExpandFullScreen, stringResource(R.string.compose_action_expand_fullscreen), onExpandClick)
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().height(QuickReplyHandleHeight).then(handleModifier), Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).clip(HiShapeTokens.Full).background(cs.onSurfaceVariant.copy(alpha = 0.42f)))
            }
            HiCappedFontScale {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = if (showExpandButton) 48.dp else 28.dp).padding(start = 16.dp, end = if (showExpandButton) 4.dp else 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        contextLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (showExpandButton) QuickReplyToolIconButton(ComposeVectorIcons.ExpandFullScreen, stringResource(R.string.compose_action_expand_fullscreen), onExpandClick)
                }
            }
        }
    }
}

@Composable
private fun QuickReplyDraftRestoredBanner(onClear: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    HiCappedFontScale {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(HiShapeTokens.Small)
                .background(cs.secondaryContainer.copy(alpha = 0.78f)).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.compose_draft_restored_banner), style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear, shape = HiShapeTokens.Small, modifier = Modifier.defaultMinSize(48.dp, 48.dp)) {
                Text(stringResource(R.string.compose_action_clear_draft))
            }
        }
    }
}

@Composable
private fun QuickReplyInputBox(
    textFieldState: TextFieldState, scrollState: androidx.compose.foundation.ScrollState, placeholder: String,
    focusRequester: FocusRequester, minHeight: Dp, nestedScrollConnection: NestedScrollConnection, modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val textStyle = HiTheme.typography.postBody.copy(color = cs.onSurface)
    Box(
        modifier = modifier.heightIn(min = minHeight).clip(HiShapeTokens.Medium)
            .background(cs.surfaceContainerHigh.copy(alpha = 0.62f))
            .border(0.75.dp, cs.outlineVariant.copy(alpha = 0.7f), HiShapeTokens.Medium)
            .nestedScroll(nestedScrollConnection).verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            state = textFieldState, textStyle = textStyle, cursorBrush = SolidColor(cs.primary),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).semantics { contentDescription = placeholder },
            decorator = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    if (textFieldState.text.isEmpty()) Text(placeholder, style = textStyle, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun QuickReplyInlineErrorStrip(error: QuickReplyInlineError, compact: Boolean, onRetry: () -> Unit, onOpenLogin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val message = when {
        error.cooldownMessageSeconds != null -> stringResource(R.string.compose_error_cooldown, error.cooldownMessageSeconds)
        error.uiError != null -> stringResource(error.uiError.messageRes)
        else -> return
    }
    val action = error.uiError?.action
    val actionLabel = when (action) {
        UiErrorAction.Retry -> stringResource(R.string.common_action_retry)
        UiErrorAction.Login -> stringResource(R.string.common_action_login)
        UiErrorAction.Reverify -> stringResource(R.string.challenge_action_retry)
        null -> null
    }
    HiCappedFontScale {
        Row(Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = cs.error, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (actionLabel != null && action != null) {
                TextButton(onClick = { if (action == UiErrorAction.Login) onOpenLogin() else onRetry() }, shape = HiShapeTokens.Small, modifier = Modifier.defaultMinSize(48.dp, 48.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun QuickReplyGuestPrompt(compact: Boolean, bottomPadding: Dp, onOpenLogin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    HiCappedFontScale {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = if (compact) 6.dp else 12.dp, bottom = if (compact) 6.dp else bottomPadding),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.thread_detail_login_to_operate), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.weight(1f).padding(end = 12.dp))
            FilledTonalButton(onClick = onOpenLogin, interactionSource = interaction, shape = HiShapeTokens.Small, modifier = Modifier.defaultMinSize(72.dp, 48.dp).hiPressScale(interaction)) {
                Text(stringResource(R.string.common_action_login))
            }
        }
    }
}

@Composable
private fun QuickReplyBottomActionBar(
    showInsertImage: Boolean, showEmojiToggle: Boolean, isEmojiPanelOpen: Boolean, isSubmitting: Boolean,
    cooldownSeconds: Int, canSend: Boolean, bottomPadding: Dp,
    onInsertImageClick: () -> Unit, onToggleEmojiClick: () -> Unit, onSendClick: () -> Unit,
) {
    HiCappedFontScale {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding), verticalAlignment = Alignment.CenterVertically) {
            if (showInsertImage) QuickReplyToolIconButton(ComposeVectorIcons.InsertImage, stringResource(R.string.compose_action_insert_image), onInsertImageClick)
            if (showEmojiToggle) {
                val emojiCd = stringResource(if (isEmojiPanelOpen) R.string.compose_action_keyboard else R.string.compose_action_emoji)
                val interaction = remember { MutableInteractionSource() }
                Box(Modifier.size(48.dp).hiPressScale(interaction).clip(HiShapeTokens.Full).clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onToggleEmojiClick), Alignment.Center) {
                    HiIconSwitch(if (isEmojiPanelOpen) ComposeVectorIcons.Keyboard else Icons.Rounded.Face, emojiCd, tint = MaterialTheme.colorScheme.onSurface, iconSize = 22.dp)
                }
            }
            Spacer(Modifier.weight(1f))
            QuickReplySendButton(isSubmitting, cooldownSeconds, canSend, onSendClick)
        }
    }
}

@Composable
private fun QuickReplySendButton(isSubmitting: Boolean, cooldownSeconds: Int, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val state = when {
        isSubmitting -> SendButtonVisualState.Submitting
        cooldownSeconds > 0 -> SendButtonVisualState.Cooldown
        else -> SendButtonVisualState.Ready
    }
    val sendLabel = stringResource(R.string.compose_action_send)
    val semanticDesc = when (state) {
        SendButtonVisualState.Submitting -> stringResource(R.string.compose_status_sending)
        SendButtonVisualState.Cooldown -> stringResource(R.string.compose_cooldown_send_cd, cooldownSeconds)
        SendButtonVisualState.Ready -> sendLabel
    }
    Button(
        onClick = onClick, enabled = enabled, interactionSource = interaction, shape = HiShapeTokens.Full,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        modifier = Modifier.defaultMinSize(72.dp, 48.dp).hiPressScale(interaction).semantics { contentDescription = semanticDesc },
    ) {
        if (state == SendButtonVisualState.Submitting) {
            HiLoadingSpinner(Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onSurface.copy(alpha = 0.72f), trackColor = cs.onSurface.copy(alpha = 0.2f))
        } else {
            AnimatedContent(
                targetState = state == SendButtonVisualState.Cooldown, contentAlignment = Alignment.Center, label = "QuickReplySendCooldown",
                transitionSpec = { ((hiScaleIn(HiMotion.spatialFast()) + hiFadeIn(HiMotion.effectsFast())) togetherWith (hiScaleOut(HiMotion.exit(HiMotion.spatialFast())) + hiFadeOut(HiMotion.exit(HiMotion.effectsFast())))).using(null) },
            ) { cooling ->
                if (cooling) HiRollingNumber(cooldownSeconds.coerceAtLeast(1), style = MaterialTheme.typography.labelLarge, color = cs.onSurface.copy(alpha = 0.78f))
                else Text(sendLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun QuickReplyToolIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(48.dp).hiPressScale(interaction).clip(HiShapeTokens.Full).clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick), Alignment.Center) {
        Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
    }
}

private object ComposeVectorIcons {
    private val strokeBrush = SolidColor(Color.Black)
    private fun stroked(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder("Compose.$name", 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = strokeBrush, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()

    val ExpandFullScreen: ImageVector = stroked("ExpandFullScreen") {
        moveTo(14f, 4f); horizontalLineTo(20f); verticalLineTo(10f); moveTo(20f, 4f); lineTo(13.5f, 10.5f)
        moveTo(10f, 20f); horizontalLineTo(4f); verticalLineTo(14f); moveTo(4f, 20f); lineTo(10.5f, 13.5f)
    }
    val InsertImage: ImageVector = stroked("InsertImage") {
        moveTo(4f, 6f); horizontalLineTo(20f); verticalLineTo(18f); horizontalLineTo(4f); close()
        moveTo(8.5f, 10.5f); lineTo(8.6f, 10.5f)
        moveTo(5f, 16.5f); lineTo(10f, 12f); lineTo(13.5f, 15f); lineTo(16f, 12.8f); lineTo(19f, 16.5f)
    }
    val Keyboard: ImageVector = stroked("Keyboard") {
        moveTo(3f, 6f); horizontalLineTo(21f); verticalLineTo(18f); horizontalLineTo(3f); close()
        moveTo(6.5f, 10f); horizontalLineTo(7.5f); moveTo(10.5f, 10f); horizontalLineTo(11.5f)
        moveTo(14.5f, 10f); horizontalLineTo(15.5f); moveTo(17.5f, 10f); horizontalLineTo(18f)
        moveTo(8f, 14.5f); horizontalLineTo(16f)
    }
}
