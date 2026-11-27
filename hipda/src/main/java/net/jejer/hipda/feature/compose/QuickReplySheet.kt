package net.jejer.hipda.feature.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.BreakIterator
import kotlinx.coroutines.*
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.feature.compose.emoji.*
import net.jejer.hipda.ui.designsystem.*
import net.jejer.hipda.ui.designsystem.feedback.*
import net.jejer.hipda.ui.designsystem.glass.*
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
fun calculateEmojiPanelHeight(lastKeyboardHeightDp: Dp, windowHeightDp: Dp): Dp =
    resolveEmojiPanelHeight(lastKeyboardHeightDp, windowHeightDp)

private enum class SheetMotionPhase { Entering, Open, Dismissing, Expanding }

/** 纯函数计算的面板各槽位显隐与锁定像素高度计划（R-E5 B1 / 第 2 轮精简：标准 `Column` 零 `SubcomposeLayout`）。 */
@Immutable
internal data class SheetSlotPlan(
    val useCompactRow: Boolean = false,
    val showHandle: Boolean = false,
    val showContext: Boolean = false,
    val showExpandInContext: Boolean = false,
    val expandInBottomBar: Boolean = false,
    val showDraft: Boolean = false,
    val showErrorInline: Boolean = false,
    val shedErrorToSnackbar: Boolean = false,
    val topSpacerDp: Dp = 0.dp,
    val contextHeightDp: Dp = 28.dp,
    val draftHeightDp: Dp = 56.dp,
    val errorHeightDp: Dp = 48.dp,
    val bottomBarHeightDp: Dp = 60.dp,
    val inputMinHeightDp: Dp = 0.dp,
    val inputMaxHeightDp: Dp = 0.dp,
    val compactVerticalPaddingDp: Dp = 0.dp,
    val compactRowMaxHeightDp: Dp = 0.dp,
)

internal fun resolveSheetSlotPlan(
    density: Density, maxSheetHeightDp: Dp, isLoggedIn: Boolean, isCompactShortScreen: Boolean,
    canExpandToFullEditor: Boolean, hasDraftBanner: Boolean, hasInlineError: Boolean, inlineErrorHasAction: Boolean,
    bottomBarBottomPaddingDp: Dp, oneLineBodyPx: Int, ctxLinePx: Int, smallLinePx: Int,
    suppressContextRow: Boolean = false,
): SheetSlotPlan = with(density) {
    val maxH = maxSheetHeightDp.roundToPx().coerceAtLeast(0)
    val touch48Px = 48.dp.roundToPx()
    val handlePx = QuickReplyHandleHeight.roundToPx()
    val slimCtxPx = maxOf(28.dp.roundToPx(), ctxLinePx)
    val fullCtxPx = maxOf(touch48Px, ctxLinePx)
    val draftPx = maxOf(touch48Px, smallLinePx) + 8.dp.roundToPx()
    val errorPx = if (inlineErrorHasAction) maxOf(touch48Px, smallLinePx) else (smallLinePx + 8.dp.roundToPx())
    val bottomBarPx = touch48Px + 4.dp.roundToPx() + bottomBarBottomPaddingDp.roundToPx()
    val minStandardInputPx = oneLineBodyPx + 30.dp.roundToPx()
    val preferredStandardInputPx = maxOf(minStandardInputPx, 72.dp.roundToPx())
    val compactMinContentPx = maxOf(touch48Px, oneLineBodyPx + 14.dp.roundToPx())
    val topPadPx = 6.dp.roundToPx()

    if (!isLoggedIn) {
        val guestPx = touch48Px + 12.dp.roundToPx() + if (isCompactShortScreen) 0 else bottomBarBottomPaddingDp.roundToPx()
        var rem = (maxH - guestPx).coerceAtLeast(0)
        val showCtx = rem >= slimCtxPx; if (showCtx) rem -= slimCtxPx
        return SheetSlotPlan(showHandle = !isCompactShortScreen && rem >= handlePx, showContext = showCtx, contextHeightDp = slimCtxPx.toDp())
    }

    val twoRowFitsBase = maxH >= bottomBarPx + minStandardInputPx + topPadPx
    val twoRowFitsError = !hasInlineError || maxH >= bottomBarPx + minStandardInputPx + errorPx + topPadPx
    val compactFitsError = !hasInlineError || maxH >= compactMinContentPx + errorPx
    val useCompact = !twoRowFitsBase || (hasInlineError && !twoRowFitsError && compactFitsError)
    val compactVPadPx = if (useCompact) ((maxH - compactMinContentPx - if (hasInlineError && compactFitsError) errorPx else 0).coerceAtLeast(0) / 2).coerceAtMost(6.dp.roundToPx()) else 0
    var rem = (maxH - if (useCompact) (compactMinContentPx + compactVPadPx * 2) else (bottomBarPx + minStandardInputPx)).coerceAtLeast(0)
    val showErr = hasInlineError && rem >= errorPx; if (showErr) rem -= errorPx
    val showFullCtx = !suppressContextRow && !useCompact && canExpandToFullEditor && rem >= fullCtxPx
    val showCtx = !suppressContextRow && (showFullCtx || rem >= slimCtxPx)
    val ctxH = if (showFullCtx) fullCtxPx else slimCtxPx; if (showCtx) rem -= ctxH
    val showDraft = hasDraftBanner && !isCompactShortScreen && rem >= draftPx; if (showDraft) rem -= draftPx
    val showHandle = !isCompactShortScreen && rem >= handlePx; if (showHandle) rem -= handlePx
    val topSpacerPx = if (!useCompact && !showHandle && !showCtx && !showDraft) minOf(topPadPx, rem) else 0
    val chromePx = (if (showHandle) handlePx else 0) + (if (showCtx) ctxH else 0) + (if (showDraft) draftPx else 0) + (if (showErr) errorPx else 0)
    val inputMaxPx = if (useCompact) 0 else (maxH - chromePx - topSpacerPx - bottomBarPx).coerceAtLeast(minStandardInputPx.coerceAtMost(maxH))
    SheetSlotPlan(
        useCompactRow = useCompact, showHandle = showHandle, showContext = showCtx, showExpandInContext = showFullCtx,
        expandInBottomBar = !useCompact && canExpandToFullEditor && !showFullCtx, showDraft = showDraft,
        showErrorInline = showErr, shedErrorToSnackbar = hasInlineError && !showErr, topSpacerDp = topSpacerPx.toDp(),
        contextHeightDp = ctxH.toDp(), draftHeightDp = draftPx.toDp(), errorHeightDp = errorPx.toDp(), bottomBarHeightDp = bottomBarPx.toDp(),
        inputMinHeightDp = minOf(preferredStandardInputPx, inputMaxPx).toDp(), inputMaxHeightDp = inputMaxPx.toDp(),
        compactVerticalPaddingDp = compactVPadPx.toDp(),
        compactRowMaxHeightDp = if (useCompact) (maxH - chromePx).coerceAtLeast(compactMinContentPx.coerceAtMost(maxH)).toDp() else 0.dp,
    )
}

/** 代理对与 Emoji 簇安全回删定位：优先按字形簇回退，兜底按 Unicode 码点回退，绝不残留孤立高代理项（R-E5 S1）。 */
internal fun previousGraphemeOffset(text: CharSequence, cursor: Int): Int {
    val safeCursor = cursor.coerceIn(0, text.length)
    if (safeCursor <= 0) return 0
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text.toString())
    val prev = iterator.preceding(safeCursor)
    return if (prev != BreakIterator.DONE) prev else Character.offsetByCodePoints(text, safeCursor, -1)
}

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
    var isDragging by remember { mutableStateOf(false) }
    var dragOffsetYPx by remember { mutableFloatStateOf(0f) }
    val closeEmojiPanelAction = remember { mutableStateOf<() -> Unit>({}) }
    val onInputPressedAction = remember { mutableStateOf<() -> Unit>({}) }
    val inputInteractionSource = remember { MutableInteractionSource() }
    LaunchedEffect(inputInteractionSource) {
        inputInteractionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) onInputPressedAction.value()
        }
    }

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
    LaunchedEffect(uiState.target.tid, uiState.isLoggedIn) {
        if (!uiState.isLoggedIn) {
            closeEmojiPanelAction.value()
        } else if (phase != SheetMotionPhase.Dismissing) {
            runCatching { inputFocusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    val launchTerminalTransition: (SheetMotionPhase, Float, AnimationSpec<Float>, Float, () -> Unit) -> Unit = remember(scope) {
        { targetPhase, targetY, spec, velocity, onComplete ->
            phase = targetPhase
            closeEmojiPanelAction.value()
            keyboardController?.hide()
            val startY = if (isDragging) dragOffsetYPx else offsetYPx.value
            if (latestReducedMotion) {
                isDragging = false; dragOffsetYPx = 0f
                scope.launch(start = CoroutineStart.UNDISPATCHED) { offsetYPx.snapTo(targetY) }
                onComplete()
            } else {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    var completed = false
                    try {
                        offsetYPx.snapTo(startY); isDragging = false; dragOffsetYPx = 0f
                        offsetYPx.animateTo(targetY, spec, velocity)
                        completed = true
                    } finally {
                        isDragging = false; dragOffsetYPx = 0f
                        if (completed || scope.coroutineContext[Job]?.isActive == true) onComplete()
                        else phase = SheetMotionPhase.Open
                    }
                }
            }
        }
    }

    val requestDismiss: (Float) -> Unit = remember(viewModel, focusManager, fallbackHeightPx, launchTerminalTransition) {
        { initialVelocity ->
            if (phase != SheetMotionPhase.Dismissing && phase != SheetMotionPhase.Expanding) {
                focusManager.clearFocus(force = false)
                viewModel.onDismissSession(emitSavedSnackbar = true)
                val targetY = measuredSheetHeightPx.coerceAtLeast(fallbackHeightPx)
                launchTerminalTransition(SheetMotionPhase.Dismissing, targetY, HiMotion.exit(HiMotion.spatialDefault()), initialVelocity.coerceAtLeast(0f)) {
                    latestOnDismissFinished()
                }
            }
        }
    }

    val requestExpandToFullEditor: (Boolean, Float) -> Unit = remember(viewModel, textFieldState, maxUpwardDragPx, launchTerminalTransition) {
        { openImagePicker, initialVelocity ->
            val handler = latestOnExpand
            if (handler != null && phase != SheetMotionPhase.Dismissing && phase != SheetMotionPhase.Expanding) {
                val sel = textFieldState.selection
                val handoff = viewModel.onHandoffToFullEditor(textFieldState.text.toString(), sel.start, sel.end, openImagePicker)
                launchTerminalTransition(SheetMotionPhase.Expanding, -maxUpwardDragPx * 0.5f, HiMotion.spatialDefault(), initialVelocity.coerceAtMost(0f)) {
                    handler(handoff); latestOnDismissFinished()
                }
            }
        }
    }

    val onDragDelta: (Float, Boolean) -> Unit = remember(scope, maxUpwardDragPx, fallbackHeightPx) {
        { deltaY, allowUpwardExpand ->
            if (phase != SheetMotionPhase.Dismissing && phase != SheetMotionPhase.Expanding) {
                val wasDragging = isDragging
                val minOffset = if (allowUpwardExpand && latestCanExpand) -maxUpwardDragPx else 0f
                val maxOffset = measuredSheetHeightPx.coerceAtLeast(fallbackHeightPx)
                val base = if (wasDragging) dragOffsetYPx else offsetYPx.value
                dragOffsetYPx = (base + deltaY).coerceIn(minOffset, maxOffset)
                isDragging = true
                if (!wasDragging && offsetYPx.isRunning) {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { offsetYPx.stop() }
                }
            }
        }
    }

    val onDragSettled: (Float, Boolean) -> Unit = remember(scope, dismissThresholdPx, expandThresholdPx, dismissVelocityPx, expandVelocityPx) {
        { velocityY, allowUpwardExpand ->
            if (phase != SheetMotionPhase.Dismissing && phase != SheetMotionPhase.Expanding) {
                val cur = if (isDragging) dragOffsetYPx else offsetYPx.value
                when {
                    cur >= dismissThresholdPx || (cur > 0f && velocityY >= dismissVelocityPx) -> requestDismiss(velocityY)
                    allowUpwardExpand && latestCanExpand && (cur <= -expandThresholdPx || (cur < 0f && velocityY <= expandVelocityPx)) ->
                        requestExpandToFullEditor(false, velocityY)
                    else -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            offsetYPx.snapTo(cur); isDragging = false; dragOffsetYPx = 0f
                            if (latestReducedMotion) offsetYPx.snapTo(0f) else offsetYPx.animateTo(0f, HiMotion.spatialSnap(), velocityY)
                        } finally {
                            isDragging = false; dragOffsetYPx = 0f
                        }
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
                val curOffset = if (isDragging) dragOffsetYPx else offsetYPx.value
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
                val curOffset = if (isDragging) dragOffsetYPx else offsetYPx.value
                if (curOffset > 0f) {
                    onDragSettled(available.y, false)
                    return Velocity(0f, available.y)
                }
                return Velocity.Zero
            }
        }
    }

    BackHandler(enabled = true) { requestDismiss(0f) }

    val contextLabel = resolveReplyContextLabel(uiState.target)
    val collapseCd = stringResource(R.string.compose_quick_reply_collapse_cd)
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val snackbarController = LocalPaneSnackbarController.current
    val textMeasurer = rememberTextMeasurer()
    val postBodyStyle = HiTheme.typography.postBody
    val labelLargeStyle = MaterialTheme.typography.labelLarge
    val bodySmallStyle = MaterialTheme.typography.bodySmall
    val oneLineBodyPx = remember(postBodyStyle, density) { textMeasurer.measure("测", style = postBodyStyle).size.height }
    val ctxLinePx = remember(labelLargeStyle, density) { textMeasurer.measure("测", style = labelLargeStyle).size.height }
    val smallLinePx = remember(bodySmallStyle, density) { textMeasurer.measure("测", style = bodySmallStyle).size.height }
    DisposableEffect(Unit) { onDispose { latestOnOccupiedChanged(0.dp, false) } }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val paneHeightDp = maxHeight
        val statusBarTopDp = with(density) { WindowInsets.hiTopSafeBar.getTop(density).toDp() }
        val emojiController = rememberEmojiPanelController(
            windowHeightDp = paneHeightDp,
            onShowKeyboard = { runCatching { inputFocusRequester.requestFocus() }; keyboardController?.show() },
            onHideKeyboard = { keyboardController?.hide() },
        )
        val navBarBottomDp = maxOf(emojiController.navBarBottomDp, bottomInset)
        SideEffect {
            closeEmojiPanelAction.value = { emojiController.close() }
            onInputPressedAction.value = { if (emojiController.isEmojiModeActive) emojiController.switchToKeyboard() }
        }
        val isEmojiPanelOpen = emojiController.isEmojiModeActive && hasEmojiPanel
        val onToggleEmoji: () -> Unit = { if (hasEmojiPanel) emojiController.toggle() }
        val bottomOccupiedDp = emojiController.bottomSlotHeightDp
        val metrics = calculateQuickReplyLayoutMetrics(paneHeightDp, statusBarTopDp, bottomOccupiedDp, navBarBottomDp)
        val innerBottomPadding = if (bottomOccupiedDp > 0.dp || isEmojiPanelOpen) 8.dp else (navBarBottomDp + 8.dp)
        val suppressContextRow = uiState.isLoggedIn && uiState.target.mode == QuickReplyMode.ReplyThread
        val plan = resolveSheetSlotPlan(
            density, metrics.maxSheetHeightDp, uiState.isLoggedIn, metrics.isCompactShortScreen, canExpandToFullEditor,
            uiState.showDraftRestoredBanner, uiState.inlineError != null, uiState.inlineError?.uiError?.action != null,
            innerBottomPadding, oneLineBodyPx, ctxLinePx, smallLinePx, suppressContextRow = suppressContextRow,
        )
        val inputPlaceholder = if (plan.showContext) stringResource(R.string.compose_reply_body_hint) else contextLabel
        LaunchedEffect(uiState.inlineError, plan.shedErrorToSnackbar) {
            val err = uiState.inlineError
            if (err != null && plan.shedErrorToSnackbar) {
                val resolved = resolveInlineErrorContent(context, err) ?: return@LaunchedEffect
                snackbarController.show(
                    message = resolved.first, target = PaneSnackbarTarget.DetailPane, actionLabel = resolved.second,
                    onAction = when (err.uiError?.action) {
                        UiErrorAction.Login -> onOpenLogin
                        UiErrorAction.Retry, UiErrorAction.Reverify -> viewModel::submit
                        null -> null
                    },
                )
            }
        }
        val totalOccupiedBottomDp = if (phase == SheetMotionPhase.Dismissing) 0.dp else with(density) { measuredSheetHeightPx.toDp() } + bottomOccupiedDp
        LaunchedEffect(totalOccupiedBottomDp, metrics.shouldLiftUnderlyingList) {
            latestOnOccupiedChanged(totalOccupiedBottomDp, metrics.shouldLiftUnderlyingList)
        }

        val effectiveOffsetYPx = if (isDragging) dragOffsetYPx else offsetYPx.value
        val dismissProgress = (effectiveOffsetYPx / measuredSheetHeightPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxSize()
                .background(cs.scrim.copy(alpha = HiGlassTokens.SheetScrimAlpha * (1f - dismissProgress)))
                .clickable(remember { MutableInteractionSource() }, indication = null) { requestDismiss(0f) },
        )
        if (!plan.showContext) {
            Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; stateDescription = contextLabel })
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter).fillMaxWidth()
                .graphicsLayer { translationY = effectiveOffsetYPx }
                .semantics { paneTitle = contextLabel; isTraversalGroup = true },
        ) {
            HiGlassSurface(
                state = glassState, style = HiGlassStyle.Sheet, shape = QuickReplySheetShape, floating = true,
                contentAlignment = Alignment.TopStart,
                modifier = Modifier.fillMaxWidth().heightIn(max = metrics.maxSheetHeightDp)
                    .onSizeChanged { if (it.height > 0) measuredSheetHeightPx = it.height.toFloat() },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    if (plan.showHandle) QuickReplyDragHandle(collapseCd, headerDragState, { onDragSettled(it, true) }) { requestDismiss(0f) }
                    if (plan.showContext) QuickReplyContextRow(contextLabel, plan.showExpandInContext, plan.contextHeightDp, headerDragState, { onDragSettled(it, true) }) { requestExpandToFullEditor(false, 0f) }
                    if (plan.showDraft) QuickReplyDraftRestoredBanner(plan.draftHeightDp, viewModel::clearRestoredDraft)
                    when {
                        !uiState.isLoggedIn -> QuickReplyGuestPrompt(metrics.isCompactShortScreen, innerBottomPadding, onOpenLogin)
                        plan.useCompactRow -> {
                            if (plan.showErrorInline) uiState.inlineError?.let { QuickReplyInlineErrorStrip(it, compact = true, plan.errorHeightDp, viewModel::submit, onOpenLogin) }
                            Row(
                                Modifier.fillMaxWidth().heightIn(max = plan.compactRowMaxHeightDp)
                                    .padding(start = 8.dp, end = 8.dp, top = plan.compactVerticalPaddingDp, bottom = plan.compactVerticalPaddingDp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                QuickReplyInputBox(textFieldState, inputScrollState, inputPlaceholder, inputFocusRequester, 40.dp, 6.dp, inputNestedScroll, inputInteractionSource, Modifier.weight(1f))
                                if (canExpandToFullEditor) QuickReplyToolIconButton(ComposeVectorIcons.InsertImage, stringResource(R.string.compose_action_insert_image)) { requestExpandToFullEditor(true, 0f) }
                                if (hasEmojiPanel) QuickReplyEmojiToggleButton(isEmojiPanelOpen, onToggleEmoji)
                                if (canExpandToFullEditor) QuickReplyToolIconButton(ComposeVectorIcons.ExpandFullScreen, stringResource(R.string.compose_action_expand_fullscreen)) { requestExpandToFullEditor(false, 0f) }
                                Spacer(Modifier.width(4.dp))
                                QuickReplySendButton(uiState.isSubmitting, uiState.cooldownSeconds, uiState.canSend, viewModel::submit)
                            }
                        }
                        else -> {
                            if (plan.topSpacerDp > 0.dp) Spacer(Modifier.height(plan.topSpacerDp))
                            QuickReplyInputBox(
                                textFieldState, inputScrollState, inputPlaceholder, inputFocusRequester, 44.dp, 10.dp, inputNestedScroll, inputInteractionSource,
                                Modifier.fillMaxWidth().heightIn(min = plan.inputMinHeightDp, max = plan.inputMaxHeightDp).padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            if (plan.showErrorInline) uiState.inlineError?.let { QuickReplyInlineErrorStrip(it, compact = false, plan.errorHeightDp, viewModel::submit, onOpenLogin) }
                            QuickReplyBottomActionBar(
                                plan.expandInBottomBar, canExpandToFullEditor, hasEmojiPanel, isEmojiPanelOpen,
                                uiState.isSubmitting, uiState.cooldownSeconds, uiState.canSend, innerBottomPadding, plan.bottomBarHeightDp,
                                { requestExpandToFullEditor(false, 0f) }, { requestExpandToFullEditor(true, 0f) }, onToggleEmoji, viewModel::submit,
                            )
                        }
                    }
                }
            }
            val emojiHost = remember(emojiController.panelContentHeightDp, paneHeightDp, textFieldState, emojiController) {
                QuickReplyEmojiPanelHost(
                    keyboardHeightDp = emojiController.panelContentHeightDp, windowHeightDp = paneHeightDp,
                    onInsertEmoji = { code -> textFieldState.edit { val s = selection.min; if (s != selection.max) delete(s, selection.max); insert(s, code); selection = TextRange(s + code.length) } },
                    onBackspace = {
                        textFieldState.edit {
                            val s = selection.min
                            if (s != selection.max) { delete(s, selection.max); selection = TextRange(s) }
                            else if (s > 0) { val prev = previousEmojiOrGraphemeOffset(asCharSequence(), s); delete(prev, s); selection = TextRange(prev) }
                        }
                    },
                    onClose = { emojiController.close() },
                )
            }
            EmojiPanelSlotBox(
                controller = emojiController,
                emojiPanelSlot = effectiveEmojiSlot,
                host = emojiHost,
                registerBackHandler = hasEmojiPanel,
            )
        }
    }
}

private fun resolveInlineErrorContent(context: android.content.Context, error: QuickReplyInlineError): Pair<String, String?>? {
    val msg = when {
        error.cooldownMessageSeconds != null -> context.getString(R.string.compose_error_cooldown, error.cooldownMessageSeconds)
        error.uiError != null -> context.getString(error.uiError.messageRes)
        else -> return null
    }
    val actionLabel = when (error.uiError?.action) {
        UiErrorAction.Retry -> context.getString(R.string.common_action_retry)
        UiErrorAction.Login -> context.getString(R.string.common_action_login)
        UiErrorAction.Reverify -> context.getString(R.string.challenge_action_retry)
        null -> null
    }
    return msg to actionLabel
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
private fun QuickReplyDragHandle(collapseCd: String, dragState: DraggableState, onDragStopped: (Float) -> Unit, onCollapseClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().height(QuickReplyHandleHeight)
            .draggable(dragState, Orientation.Vertical, onDragStopped = { onDragStopped(it) })
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onCollapseClick)
            .semantics { contentDescription = collapseCd; customActions = listOf(CustomAccessibilityAction(collapseCd) { onCollapseClick(); true }) },
        Alignment.Center,
    ) { Box(Modifier.size(36.dp, 4.dp).clip(HiShapeTokens.Full).background(cs.onSurfaceVariant.copy(alpha = 0.42f))) }
}

@Composable
private fun QuickReplyContextRow(contextLabel: String, showExpandButton: Boolean, rowHeight: Dp, dragState: DraggableState, onDragStopped: (Float) -> Unit, onExpandClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    HiCappedFontScale {
        Row(
            Modifier.fillMaxWidth().draggable(dragState, Orientation.Vertical, onDragStopped = { onDragStopped(it) })
                .height(rowHeight).padding(start = 16.dp, end = if (showExpandButton) 4.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(contextLabel, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            if (showExpandButton) QuickReplyToolIconButton(ComposeVectorIcons.ExpandFullScreen, stringResource(R.string.compose_action_expand_fullscreen), onExpandClick)
        }
    }
}

@Composable
private fun QuickReplyDraftRestoredBanner(rowHeight: Dp, onClear: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    HiCappedFontScale {
        Row(
            Modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 16.dp, vertical = 4.dp).clip(HiShapeTokens.Small)
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
    focusRequester: FocusRequester, minHeight: Dp, verticalInnerPadding: Dp,
    nestedScrollConnection: NestedScrollConnection, interactionSource: MutableInteractionSource, modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val textStyle = HiTheme.typography.postBody.copy(color = cs.onSurface)
    Box(
        modifier = modifier.heightIn(min = minHeight).clip(HiShapeTokens.Medium)
            .background(cs.surfaceContainerHigh.copy(alpha = 0.72f))
            .nestedScroll(nestedScrollConnection).verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = verticalInnerPadding),
    ) {
        BasicTextField(
            state = textFieldState, textStyle = textStyle, cursorBrush = SolidColor(cs.primary),
            interactionSource = interactionSource,
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
private fun QuickReplyInlineErrorStrip(error: QuickReplyInlineError, compact: Boolean, rowHeight: Dp, onRetry: () -> Unit, onOpenLogin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (message, actionLabel) = resolveInlineErrorContent(LocalContext.current, error) ?: return
    val action = error.uiError?.action
    HiCappedFontScale {
        Row(Modifier.fillMaxWidth().height(rowHeight).padding(horizontal = if (compact) 12.dp else 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = cs.error, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (actionLabel != null && action != null) {
                TextButton(onClick = { if (action == UiErrorAction.Login) onOpenLogin() else onRetry() }, shape = HiShapeTokens.Small, modifier = Modifier.defaultMinSize(48.dp, 48.dp)) { Text(actionLabel) }
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
    showExpand: Boolean, showInsertImage: Boolean, showEmojiToggle: Boolean, isEmojiPanelOpen: Boolean, isSubmitting: Boolean,
    cooldownSeconds: Int, canSend: Boolean, bottomPadding: Dp, rowHeight: Dp,
    onExpandClick: () -> Unit, onInsertImageClick: () -> Unit, onToggleEmojiClick: () -> Unit, onSendClick: () -> Unit,
) {
    HiCappedFontScale {
        Row(Modifier.fillMaxWidth().height(rowHeight).padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding), verticalAlignment = Alignment.CenterVertically) {
            if (showInsertImage) QuickReplyToolIconButton(ComposeVectorIcons.InsertImage, stringResource(R.string.compose_action_insert_image), onInsertImageClick)
            if (showEmojiToggle) QuickReplyEmojiToggleButton(isEmojiPanelOpen, onToggleEmojiClick)
            if (showExpand) QuickReplyToolIconButton(ComposeVectorIcons.ExpandFullScreen, stringResource(R.string.compose_action_expand_fullscreen), onExpandClick)
            Spacer(Modifier.weight(1f))
            QuickReplySendButton(isSubmitting, cooldownSeconds, canSend, onSendClick)
        }
    }
}

@Composable
private fun QuickReplyEmojiToggleButton(isEmojiPanelOpen: Boolean, onClick: () -> Unit) {
    val emojiCd = stringResource(if (isEmojiPanelOpen) R.string.compose_action_keyboard else R.string.compose_action_emoji)
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(48.dp).hiPressScale(interaction).clip(HiShapeTokens.Full).clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick), Alignment.Center) {
        HiIconSwitch(if (isEmojiPanelOpen) ComposeVectorIcons.Keyboard else Icons.Rounded.Face, emojiCd, tint = MaterialTheme.colorScheme.onSurface, iconSize = 22.dp)
    }
}

@Composable
private fun QuickReplySendButton(isSubmitting: Boolean, cooldownSeconds: Int, enabled: Boolean, onClick: () -> Unit) {
    ComposeSendCapsuleButton(isSubmitting = isSubmitting, cooldownSeconds = cooldownSeconds, enabled = enabled, filled = true, onClick = onClick)
}

/** 统一发送胶囊按钮（U3：视觉高度固定 32dp，外层以 `minimumInteractiveComponentSize()` 保证 ≥ 48dp 触控区）。 */
@Composable
internal fun ComposeSendCapsuleButton(
    isSubmitting: Boolean,
    cooldownSeconds: Int,
    enabled: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val sendLabel = stringResource(R.string.compose_action_send)
    val semanticDesc = when {
        isSubmitting -> stringResource(R.string.compose_status_sending)
        cooldownSeconds > 0 -> stringResource(R.string.compose_cooldown_send_cd, cooldownSeconds)
        else -> sendLabel
    }
    val targetContainer = when {
        !enabled -> cs.onSurface.copy(alpha = 0.12f)
        filled -> cs.primary
        else -> Color.Transparent
    }
    val targetContent = when {
        !enabled -> cs.onSurface.copy(alpha = 0.38f)
        filled -> cs.onPrimary
        else -> cs.primary
    }
    val containerColor by animateColorAsState(targetContainer, HiMotion.effectsFast(), label = "ComposeSendBg")
    val contentColor by animateColorAsState(targetContent, HiMotion.effectsFast(), label = "ComposeSendFg")
    Box(
        modifier = modifier
            .hiPressScale(interaction)
            .semantics { contentDescription = semanticDesc }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = HiShapeTokens.Full,
            color = containerColor,
            contentColor = contentColor,
            modifier = Modifier
                .height(32.dp)
                .defaultMinSize(minWidth = 56.dp)
                .clip(HiShapeTokens.Full)
                .indication(interaction, LocalIndication.current),
        ) {
            Row(
                modifier = Modifier.padding(PaddingValues(horizontal = 14.dp, vertical = 0.dp)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (isSubmitting) {
                    HiLoadingSpinner(Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.onSurface.copy(alpha = 0.72f), trackColor = cs.onSurface.copy(alpha = 0.2f))
                } else {
                    AnimatedContent(
                        targetState = cooldownSeconds > 0,
                        contentAlignment = Alignment.Center,
                        label = "ComposeSendCooldown",
                        transitionSpec = {
                            ((hiScaleIn(HiMotion.spatialFast()) + hiFadeIn(HiMotion.effectsFast())) togetherWith
                                (hiScaleOut(HiMotion.exit(HiMotion.spatialFast())) + hiFadeOut(HiMotion.exit(HiMotion.effectsFast())))).using(null)
                        },
                    ) { cooling ->
                        if (cooling) HiRollingNumber(cooldownSeconds.coerceAtLeast(1), style = MaterialTheme.typography.labelLarge, color = cs.onSurface.copy(alpha = 0.78f))
                        else Text(sendLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                    }
                }
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

internal object ComposeVectorIcons {
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
