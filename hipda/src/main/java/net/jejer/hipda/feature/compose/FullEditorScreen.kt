package net.jejer.hipda.feature.compose

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.LocalPaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.feature.compose.emoji.*
import net.jejer.hipda.ui.designsystem.*
import net.jejer.hipda.ui.designsystem.feedback.*
import net.jejer.hipda.ui.designsystem.motion.*
import net.jejer.hipda.ui.designsystem.state.HiErrorState
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner
import net.jejer.hipda.ui.designsystem.state.HiStateAction
import net.jejer.hipda.ui.shell.ForumMetadata
import net.jejer.hipda.ui.shell.ForumMetadataRegistry

/** 完整编辑器界面（纯色表面 0 `HiGlassMarker`，底部工具栏仅提供 `插入图片` 与可选 `E7` 表情插槽，不加 `引用`/`粗体`）。 */
@Composable
fun FullEditorScreen(
    viewModel: FullEditorViewModel,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
    isModalCard: Boolean = false,
    registerBackHandler: Boolean = true,
    onRegisterBackAction: ((() -> Unit)?) -> Unit = {},
    emojiPanelSlot: (@Composable (QuickReplyEmojiPanelHost) -> Unit)? = null,
) = FullEditorScreenContent(
    viewModel = viewModel,
    onOpenLogin = onOpenLogin,
    modifier = modifier,
    isModalCard = isModalCard,
    registerBackHandler = registerBackHandler,
    onRegisterBackAction = onRegisterBackAction,
    emojiPanelSlot = emojiPanelSlot,
    externalConsumedFocusVersion = null,
)

@Immutable
private sealed interface EditorFocusTarget {
    data object Subject : EditorFocusTarget
    data class Body(val blockId: String) : EditorFocusTarget
}

@Immutable
private data class PendingFocusRestore(val version: Long, val target: EditorFocusTarget)

@Stable
private class EditorFocusTracker(initialPlacement: FullEditorPlacement) {
    var lastPlacement: FullEditorPlacement = initialPlacement
    var focusedTarget: EditorFocusTarget? = null
    var subjectSelection: TextRange? = null
    var pendingRestore: PendingFocusRestore? = null
    private var nextVersion: Long = 0L

    fun onFocusChanged(target: EditorFocusTarget, isFocused: Boolean) {
        if (isFocused) focusedTarget = target
        else if (focusedTarget == target) focusedTarget = null
    }

    fun onSubjectFocusChanged(isFocused: Boolean, selection: TextRange) {
        onFocusChanged(EditorFocusTarget.Subject, isFocused)
        if (isFocused) subjectSelection = selection
    }

    fun onPlacement(placement: FullEditorPlacement): PendingFocusRestore? {
        if (placement != lastPlacement) {
            lastPlacement = placement
            val target = focusedTarget ?: pendingRestore?.target
            if (target != null) pendingRestore = PendingFocusRestore(++nextVersion, target)
        }
        return pendingRestore
    }

    fun consume(version: Long) {
        if (pendingRestore?.version == version) pendingRestore = null
    }
}

@Composable
internal fun FullEditorScreenContent(
    viewModel: FullEditorViewModel,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
    isModalCard: Boolean = false,
    placement: FullEditorPlacement = if (isModalCard) FullEditorPlacement.CenteredModal else FullEditorPlacement.FullScreen,
    registerBackHandler: Boolean = true,
    onRegisterBackAction: ((() -> Unit)?) -> Unit = {},
    emojiPanelSlot: (@Composable (QuickReplyEmojiPanelHost) -> Unit)? = null,
    externalConsumedFocusVersion: MutableLongState? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val localConsumedFocusVersion = rememberSaveable(uiState.sessionId) { mutableLongStateOf(0L) }
    val consumedFocusVersionState = externalConsumedFocusVersion ?: localConsumedFocusVersion
    val focusTracker = remember(uiState.sessionId) { EditorFocusTracker(placement) }
    val pendingFocusRestore = focusTracker.onPlacement(placement)
    SideEffect {
        if (consumedFocusVersionState.longValue > uiState.focusRequestVersion) {
            consumedFocusVersionState.longValue = uiState.focusRequestVersion
        }
    }
    val effectiveEmojiSlot = rememberResolvedEmojiPanelSlot(emojiPanelSlot)
    val hasEmojiSlot = effectiveEmojiSlot != null
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    var activeBodyFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }

    val imagePickerLauncher = if (LocalActivityResultRegistryOwner.current != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> if (uris.isNotEmpty()) viewModel.onPickImages(uris) }
    } else null
    val launchImagePicker: () -> Unit = { imagePickerLauncher?.launch("image/*") }
    LaunchedEffect(uiState.pendingImagePickerTrigger) {
        val trigger = uiState.pendingImagePickerTrigger
        if (trigger > 0L) { viewModel.consumeImagePickerTrigger(trigger); launchImagePicker() }
    }

    val scrollState = rememberScrollState()
    val isScrolled by remember { derivedStateOf { scrollState.value > 0 } }
    val cs = MaterialTheme.colorScheme
    val snackbarController = LocalPaneSnackbarController.current
    val snackbarScope = rememberCoroutineScope()
    val restoredMsg = stringResource(R.string.compose_snackbar_original_restored)
    val undoLabel = stringResource(R.string.compose_action_undo)
    val activeRestoreSnackbarJob = remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(uiState.snackbarResetSeq) { activeRestoreSnackbarJob.value?.cancel(); activeRestoreSnackbarJob.value = null }
    DisposableEffect(Unit) { onDispose { activeRestoreSnackbarJob.value?.cancel() } }
    val restoreEvent = uiState.restoreSnackbarEvent
    LaunchedEffect(restoreEvent?.id) {
        val ev = restoreEvent ?: return@LaunchedEffect
        viewModel.consumeRestoreSnackbarEvent(ev.id)
        activeRestoreSnackbarJob.value?.cancel()
        activeRestoreSnackbarJob.value = snackbarScope.launch {
            snackbarController.show(restoredMsg, if (isModalCard) PaneSnackbarTarget.ContentArea else PaneSnackbarTarget.DetailPane, undoLabel) {
                viewModel.undoRestoreOriginal(ev.tokenId)
            }
        }
    }

    val titleText = resolveEditorTitle(uiState.mode)
    val previewUriByImageId = remember(uiState.uploads) { buildMap { uiState.uploads.forEach { u -> val id = u.imageIdOrNull; val uri = u.contentUri; if (id != null && uri != null) put(id, uri) } } }
    val isEditWaitingOriginal = uiState.isEditPost && uiState.editOriginal == null

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val windowHeightDp = maxHeight
        val statusBarTopDp = with(density) { WindowInsets.hiTopSafeBar.getTop(density).toDp() }
        val emojiController = rememberEmojiPanelController(
            windowHeightDp = windowHeightDp,
            onShowKeyboard = { runCatching { activeBodyFocusRequester?.requestFocus() }; keyboardController?.show() },
            onHideKeyboard = { keyboardController?.hide() },
        )
        val navBarBottomDp = emojiController.navBarBottomDp
        val bottomSlotHeightDp = if (isEditWaitingOriginal) 0.dp else emojiController.bottomSlotHeightDp
        LaunchedEffect(uiState.isOpen, uiState.sessionId, isEditWaitingOriginal) { emojiController.close() }
        val isEmojiPanelOpen = emojiController.isEmojiModeActive && hasEmojiSlot && !isEditWaitingOriginal

        val closeEditor: () -> Unit = { emojiController.close(); keyboardController?.hide(); viewModel.requestClose(emitSavedSnackbar = true) }
        val handleBack: () -> Unit = { if (emojiController.isBackInterceptActive) emojiController.close() else closeEditor() }
        val latestHandleBack by rememberUpdatedState(handleBack)
        LaunchedEffect(Unit) { onRegisterBackAction { latestHandleBack() } }
        if (registerBackHandler) key(uiState.sessionId) { BackHandler(enabled = uiState.isOpen) { latestHandleBack() } }

        val onInputPress: () -> Unit = { if (emojiController.isEmojiModeActive) emojiController.switchToKeyboard() }
        val emojiHost = remember(emojiController.panelContentHeightDp, windowHeightDp, viewModel, emojiController) {
            QuickReplyEmojiPanelHost(
                keyboardHeightDp = emojiController.panelContentHeightDp,
                windowHeightDp = windowHeightDp,
                onInsertEmoji = viewModel::onInsertEmoji,
                onBackspace = viewModel::onBackspace,
                onClose = { emojiController.close() },
            )
        }
        val visualBodyPlaceholder = resolveBodyVisualPlaceholder(uiState.mode)
        val bodyContentDescription = visualBodyPlaceholder ?: titleText
        val editorInterior: @Composable (Modifier) -> Unit = { colModifier ->
            Column(colModifier.semantics { paneTitle = titleText; isTraversalGroup = true }) {
                FullEditorTopBar(titleText, isScrolled, isModalCard, uiState.isSubmitting, uiState.effectiveCooldownSeconds, uiState.canTapSend, uiState.hasSendableContent, uiState.canRestoreOriginal, closeEditor, viewModel::restoreOriginal, viewModel::submit)
                if (isEditWaitingOriginal) {
                    EditOriginalStatusArea(uiState.isLoadingEditOriginal, uiState.editLoadError, viewModel::retryEditLoad, onOpenLogin, Modifier.weight(1f).fillMaxWidth())
                    return@Column
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)) {
                    if (!uiState.isLoggedIn) EditorGuestBanner(onOpenLogin)
                    AnimatedVisibility(uiState.showDraftRestoredBanner, enter = hiExpandVertically(expandFrom = Alignment.Top) + hiFadeIn(), exit = hiShrinkVertically(shrinkTowards = Alignment.Top) + hiFadeOut()) {
                        EditorDraftRestoredBanner(viewModel::clearRestoredDraft)
                    }
                    val shouldFocusSubject = uiState.isNewThread && uiState.subject.isBlank()
                    val showAutoFocusKeyboard: () -> Unit = { if (!emojiController.isEmojiModeActive) keyboardController?.show() }
                    val afterMoveFocusRestore: () -> Unit = { if (emojiController.isEmojiModeActive) keyboardController?.hide() }
                    if (uiState.isNewThread) {
                        NewThreadMetaSection(uiState.selectedFid, uiState.availableForums, uiState.typeOptions, uiState.hasSelectableCategories, uiState.selectedTypeId, uiState.subject, uiState.bodySyncVersion, uiState.focusRequestVersion, consumedFocusVersionState, uiState.isLoggedIn, shouldFocusSubject, uiState.validationError, viewModel::selectForum, viewModel::selectCategory, viewModel::onSubjectChanged, onInputPress, showAutoFocusKeyboard, focusTracker, pendingFocusRestore, afterMoveFocusRestore)
                    } else if (uiState.isEditPost && uiState.showsEditSubjectField) {
                        EditPostMetaSection(uiState.typeOptions, uiState.hasSelectableCategories, uiState.selectedTypeId, uiState.subject, uiState.bodySyncVersion, uiState.validationError, viewModel::selectCategory, viewModel::onSubjectChanged, onInputPress, focusTracker, pendingFocusRestore, afterMoveFocusRestore)
                    }
                    ImageUploadStrip(uiState.uploads, uiState.insertedImageIds, uiState.uploadSummaryProgress, viewModel::onInsertUploadedImage, viewModel::retryUpload, viewModel::removeUpload)
                    EditorRichBodySection(uiState.body, uiState.bodySyncVersion, uiState.focusRequestVersion, consumedFocusVersionState, uiState.isLoggedIn, shouldFocusSubject, previewUriByImageId, visualBodyPlaceholder, bodyContentDescription, viewModel::onTextBlockChanged, viewModel::onTextBlockSelectionChanged, viewModel::onRemoveInlineImageBlock, viewModel::onBackspace, { activeBodyFocusRequester = it }, onInputPress, showAutoFocusKeyboard, focusTracker, pendingFocusRestore, afterMoveFocusRestore)
                    LaunchedEffect(pendingFocusRestore?.version) { pendingFocusRestore?.let { focusTracker.consume(it.version) } }
                    if (uiState.validationError == FullEditorValidationError.ContentTooShort) {
                        Text(stringResource(FullEditorValidationError.ContentTooShort.messageRes), style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    uiState.inlineError?.let { EditorInlineErrorRow(it, viewModel::submit, onOpenLogin) }
                    Spacer(Modifier.height(16.dp))
                }
                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
                FullEditorBottomToolbar(
                    hasEmojiSlot, isEmojiPanelOpen,
                    if (isModalCard || bottomSlotHeightDp > 0.dp || isEmojiPanelOpen) 0.dp else navBarBottomDp,
                    launchImagePicker,
                ) { if (hasEmojiSlot) emojiController.toggle() }
            }
        }

        Column(Modifier.fillMaxSize()) {
            if (isModalCard) {
                val cardShape = if (bottomSlotHeightDp > 0.dp) HiShapeTokens.Large.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)) else HiShapeTokens.Large
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(
                        top = statusBarTopDp + 24.dp,
                        bottom = if (bottomSlotHeightDp > 0.dp) 0.dp else navBarBottomDp + 24.dp,
                    ),
                    contentAlignment = if (bottomSlotHeightDp > 0.dp) Alignment.BottomCenter else Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().fillMaxHeight().heightIn(max = 720.dp),
                        shape = cardShape, color = cs.surface, contentColor = cs.onSurface, tonalElevation = 2.dp,
                        border = BorderStroke(0.5.dp, cs.outlineVariant.copy(alpha = 0.5f)),
                    ) { editorInterior(Modifier.fillMaxSize().background(cs.surface)) }
                }
            } else {
                editorInterior(Modifier.weight(1f).fillMaxWidth().background(cs.surface))
            }
            if (!isEditWaitingOriginal) {
                EmojiPanelSlotBox(
                    controller = emojiController,
                    emojiPanelSlot = effectiveEmojiSlot,
                    host = emojiHost,
                    registerBackHandler = registerBackHandler && hasEmojiSlot,
                )
            }
        }
    }
}

@Composable
private fun EditOriginalStatusArea(isLoading: Boolean, error: FullEditorInlineError?, onRetry: () -> Unit, onOpenLogin: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, Alignment.Center) {
        if (isLoading) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HiLoadingSpinner(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                Text(stringResource(R.string.state_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val msg = resolveEditorErrorMessage(LocalContext.current, error) ?: stringResource(R.string.common_error_load_failed)
            val action = when (error?.uiError?.action) {
                UiErrorAction.Login -> HiStateAction(stringResource(R.string.common_action_login), onOpenLogin)
                UiErrorAction.Reverify -> HiStateAction(stringResource(R.string.challenge_action_retry), onRetry)
                UiErrorAction.Retry, null -> if (error?.uiError?.messageRes == R.string.compose_error_real_name_required) null else HiStateAction(stringResource(R.string.common_action_retry), onRetry)
            }
            HiErrorState(message = msg, action = action)
        }
    }
}

/** 标题框与正文文本块共用的 `TextFieldState` 双向同步器：外部变更即时上屏，用户输入与 IME 组词零打断。 */
@Composable
private fun rememberSyncedTextFieldState(
    text: String, syncVersion: Long = 0L, selection: TextRange = TextRange(text.length), applySelectionOnSync: Boolean = false,
    onTextChanged: (String, TextRange) -> Unit, onSelectionChanged: ((TextRange) -> Unit)? = null, isFocused: () -> Boolean = { false },
): TextFieldState {
    val fieldState = rememberTextFieldState(initialText = text, initialSelection = selection)
    val latestOnText by rememberUpdatedState(onTextChanged)
    val latestOnSel by rememberUpdatedState(onSelectionChanged)
    var lastTxt by remember { mutableStateOf(text) }
    var lastSel by remember { mutableStateOf(selection) }
    var lastVer by remember { mutableStateOf(syncVersion) }
    LaunchedEffect(syncVersion, text, selection) {
        val verBumped = syncVersion != lastVer; val externalTxt = text != lastTxt; val externalSel = applySelectionOnSync && selection != lastSel; lastVer = syncVersion
        if (verBumped || externalTxt || externalSel) {
            lastTxt = text; lastSel = selection
            if (fieldState.text.toString() != text || (applySelectionOnSync && fieldState.selection != selection)) {
                fieldState.edit {
                    if (asCharSequence().toString() != text) replace(0, length, text)
                    if (applySelectionOnSync && this.selection != selection) this.selection = selection
                }
            }
        }
    }
    LaunchedEffect(fieldState) {
        snapshotFlow { fieldState.text.toString() to fieldState.selection }.collect { (txt, sel) ->
            if (txt != lastTxt) { lastTxt = txt; lastSel = sel; latestOnText(txt, sel) }
            else if (latestOnSel != null && isFocused() && sel != lastSel) { lastSel = sel; latestOnSel?.invoke(sel) }
        }
    }
    return fieldState
}

@Composable
private fun FullEditorTopBar(
    title: String, isScrolled: Boolean, isModalCard: Boolean,
    isSubmitting: Boolean, cooldownSeconds: Int, canTapSend: Boolean, hasSendableContent: Boolean, showRestoreOriginal: Boolean,
    onBackClick: () -> Unit, onRestoreOriginalClick: () -> Unit, onSendClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val barBg by animateColorAsState(if (isScrolled) cs.surfaceContainer else cs.surface, HiMotion.effectsDefault(), label = "FullEditorTopBarColor")
    val navIcon = if (isModalCard) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack
    val navCd = stringResource(if (isModalCard) R.string.compose_action_close else R.string.common_action_back)
    Column(Modifier.fillMaxWidth().background(barBg)) {
        if (!isModalCard) Spacer(Modifier.windowInsetsTopHeight(WindowInsets.hiTopSafeBar))
        HiCappedFontScale {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val backInt = remember { MutableInteractionSource() }
                IconButton(onBackClick, Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(backInt), interactionSource = backInt) {
                    Icon(navIcon, navCd, tint = cs.onSurface)
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                if (showRestoreOriginal) {
                    val restoreInt = remember { MutableInteractionSource() }
                    TextButton(
                        onClick = onRestoreOriginalClick,
                        enabled = !isSubmitting,
                        shape = HiShapeTokens.Small,
                        interactionSource = restoreInt,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(restoreInt),
                    ) {
                        Text(stringResource(R.string.compose_action_restore_original), maxLines = 1, softWrap = false)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                EditorSendButton(isSubmitting, cooldownSeconds, canTapSend, hasSendableContent, onSendClick)
            }
        }
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
    }
}

@Composable
private fun EditorSendButton(isSubmitting: Boolean, cooldownSeconds: Int, enabled: Boolean, hasSendableContent: Boolean, onClick: () -> Unit) {
    ComposeSendCapsuleButton(isSubmitting = isSubmitting, cooldownSeconds = cooldownSeconds, enabled = enabled, filled = hasSendableContent, onClick = onClick)
}

@Composable
private fun <K> EditorDropdownField(label: String, selectedLabel: String?, isError: Boolean, options: List<Pair<K, String>>, selectedKey: K?, onSelect: (K) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(HiShapeTokens.Small).background(cs.surfaceContainerLow)
                .border(1.dp, if (isError) cs.error else cs.outlineVariant, HiShapeTokens.Small).hiPressScale(interaction)
                .clickable(interaction, null, role = Role.DropdownList) { expanded = true }.padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics { contentDescription = if (selectedLabel != null) "$label: $selectedLabel" else label },
            Arrangement.SpaceBetween, Alignment.CenterVertically,
        ) {
            Text(selectedLabel ?: label, style = MaterialTheme.typography.bodyMedium, color = if (selectedLabel != null) cs.onSurface else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ArrowDropDown, null, tint = cs.onSurfaceVariant)
        }
        DropdownMenu(expanded, { expanded = false }, Modifier.background(cs.surfaceContainerHigh).heightIn(max = 320.dp)) {
            options.forEach { (key, text) ->
                DropdownMenuItem({ Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }, { expanded = false; onSelect(key) }, trailingIcon = if (key == selectedKey) ({ Icon(Icons.Rounded.Check, null, tint = cs.primary, modifier = Modifier.size(18.dp)) }) else null)
            }
        }
    }
}

@Composable
private fun NewThreadMetaSection(selectedFid: Int?, availableForums: List<ForumMetadata>, typeOptions: Map<String, String>, hasSelectableCategories: Boolean, selectedTypeId: String, subject: String, syncVersion: Long, focusRequestVersion: Long, consumedFocusVersionState: MutableLongState, isLoggedIn: Boolean, shouldFocusSubject: Boolean, validationError: FullEditorValidationError?, onSelectForum: (Int) -> Unit, onSelectCategory: (String) -> Unit, onSubjectChanged: (String) -> Unit, onInputPress: () -> Unit = {}, onShowKeyboard: () -> Unit = {}, focusTracker: EditorFocusTracker? = null, pendingFocusRestore: PendingFocusRestore? = null, onAfterFocusRestore: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val forumOptions = remember(availableForums) { availableForums.map { it.fid to it.fullName } }
    val selectedForumName = selectedFid?.takeIf { it > 0 }?.let { ForumMetadataRegistry.resolve(it).fullName }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
            EditorDropdownField(stringResource(R.string.compose_field_forum), selectedForumName, validationError == FullEditorValidationError.SelectForum, forumOptions, selectedFid, onSelectForum, Modifier.weight(1f))
            if (hasSelectableCategories) {
                val catOptions = remember(typeOptions) { typeOptions.entries.filter { it.key.isNotBlank() && it.key != "0" }.map { it.key to it.value } }
                EditorDropdownField(stringResource(R.string.compose_field_category), catOptions.firstOrNull { it.first == selectedTypeId }?.second, validationError == FullEditorValidationError.SelectCategory, catOptions, selectedTypeId, onSelectCategory, Modifier.weight(0.75f))
            }
        }
        if (validationError == FullEditorValidationError.SelectForum || validationError == FullEditorValidationError.SelectCategory) {
            Text(stringResource(validationError.messageRes), style = MaterialTheme.typography.bodySmall, color = cs.error)
        }
        EditorSubjectField(subject, syncVersion, validationError, onSubjectChanged, onInputPress, focusRequestVersion, consumedFocusVersionState, isLoggedIn, shouldFocusSubject, onShowKeyboard, focusTracker, pendingFocusRestore, onAfterFocusRestore)
    }
    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
}

@Composable
private fun EditPostMetaSection(typeOptions: Map<String, String>, hasSelectableCategories: Boolean, selectedTypeId: String, subject: String, syncVersion: Long, validationError: FullEditorValidationError?, onSelectCategory: (String) -> Unit, onSubjectChanged: (String) -> Unit, onInputPress: () -> Unit = {}, focusTracker: EditorFocusTracker? = null, pendingFocusRestore: PendingFocusRestore? = null, onAfterFocusRestore: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasSelectableCategories) {
            val catOptions = remember(typeOptions) { typeOptions.entries.filter { it.key.isNotBlank() && it.key != "0" }.map { it.key to it.value } }
            EditorDropdownField(stringResource(R.string.compose_field_category), catOptions.firstOrNull { it.first == selectedTypeId }?.second, validationError == FullEditorValidationError.SelectCategory, catOptions, selectedTypeId, onSelectCategory, Modifier.fillMaxWidth())
            if (validationError == FullEditorValidationError.SelectCategory) {
                Text(stringResource(validationError.messageRes), style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
        }
        EditorSubjectField(subject, syncVersion, validationError, onSubjectChanged, onInputPress, focusTracker = focusTracker, pendingFocusRestore = pendingFocusRestore, onAfterFocusRestore = onAfterFocusRestore)
    }
    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
}

@Composable
private fun EditorSubjectField(subject: String, syncVersion: Long, validationError: FullEditorValidationError?, onSubjectChanged: (String) -> Unit, onInputPress: () -> Unit = {}, focusRequestVersion: Long = 0L, consumedFocusVersionState: MutableLongState? = null, isLoggedIn: Boolean = false, shouldFocus: Boolean = false, onShowKeyboard: () -> Unit = {}, focusTracker: EditorFocusTracker? = null, pendingFocusRestore: PendingFocusRestore? = null, onAfterFocusRestore: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val subjectHint = stringResource(R.string.compose_field_subject_hint)
    val subjectStyle = MaterialTheme.typography.titleMedium.copy(color = cs.onSurface)
    var focused by remember { mutableStateOf(false) }
    val subjectFieldState = rememberSyncedTextFieldState(
        text = subject,
        syncVersion = syncVersion,
        onTextChanged = { txt, sel -> focusTracker?.subjectSelection = sel; onSubjectChanged(txt) },
        onSelectionChanged = { sel -> focusTracker?.subjectSelection = sel },
        isFocused = { focused },
    )
    val subjectInteraction = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }
    val latestOnInputPress by rememberUpdatedState(onInputPress)
    val latestAfterRestore by rememberUpdatedState(onAfterFocusRestore)
    LaunchedEffect(subjectInteraction) { subjectInteraction.interactions.collect { if (it is PressInteraction.Press) latestOnInputPress() } }
    LaunchedEffect(focusRequestVersion) {
        val consumed = consumedFocusVersionState?.longValue ?: 0L
        if (isLoggedIn && focusRequestVersion > consumed && shouldFocus) {
            consumedFocusVersionState?.longValue = focusRequestVersion
            runCatching { focusRequester.requestFocus() }
            onShowKeyboard()
        }
    }
    LaunchedEffect(pendingFocusRestore?.version) {
        val restore = pendingFocusRestore ?: return@LaunchedEffect
        if (restore.target == EditorFocusTarget.Subject) {
            focusTracker?.consume(restore.version)
            focusTracker?.subjectSelection?.let { saved ->
                val clamped = TextRange(saved.start.coerceIn(0, subjectFieldState.text.length), saved.end.coerceIn(0, subjectFieldState.text.length))
                if (subjectFieldState.selection != clamped) subjectFieldState.edit { selection = clamped }
            }
            runCatching { focusRequester.requestFocus() }
            latestAfterRestore()
        }
    }
    val titleErr = validationError == FullEditorValidationError.EnterTitle || validationError == FullEditorValidationError.TitleTooShort
    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(HiShapeTokens.Small).background(cs.surfaceContainerLow).border(1.dp, if (titleErr) cs.error else cs.outlineVariant, HiShapeTokens.Small).padding(horizontal = 12.dp, vertical = 12.dp), Alignment.CenterStart) {
        BasicTextField(
            subjectFieldState, Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { st -> focused = st.isFocused; focusTracker?.onSubjectFocusChanged(st.isFocused, subjectFieldState.selection) }.semantics { contentDescription = subjectHint }, textStyle = subjectStyle, cursorBrush = SolidColor(cs.primary),
            interactionSource = subjectInteraction, lineLimits = TextFieldLineLimits.MultiLine(1, 2),
            decorator = { inner -> Box(Modifier.fillMaxWidth()) { if (subjectFieldState.text.isEmpty()) Text(subjectHint, style = subjectStyle, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() } },
        )
    }
    if (titleErr) Text(stringResource(validationError.messageRes), style = MaterialTheme.typography.bodySmall, color = cs.error)
}

@Composable
private fun EditorRichBodySection(document: EditorBodyDocument, syncVersion: Long, focusRequestVersion: Long, consumedFocusVersionState: MutableLongState, isLoggedIn: Boolean, preferSubjectFocus: Boolean, previewUriByImageId: Map<String, Uri>, visualPlaceholder: String?, bodyContentDescription: String, onTextBlockChanged: (String, String, Int, Int) -> Unit, onTextBlockSelectionChanged: (String, Int, Int) -> Unit, onRemoveInlineImage: (String) -> Unit, onBackspaceAtBlockStart: () -> Unit, onActiveFocusRequester: (FocusRequester) -> Unit = {}, onInputPress: () -> Unit = {}, onShowKeyboard: () -> Unit = {}, focusTracker: EditorFocusTracker? = null, pendingFocusRestore: PendingFocusRestore? = null, onAfterFocusRestore: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val bodyStyle = HiTheme.typography.postBody.copy(color = cs.onSurface)
    val latestOnBs by rememberUpdatedState(onBackspaceAtBlockStart)
    val latestOnInputPress by rememberUpdatedState(onInputPress)
    val latestAfterRestore by rememberUpdatedState(onAfterFocusRestore)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        document.blocks.forEachIndexed { index, block ->
            key(block.id) {
                when (block) {
                    is EditorBodyBlock.Text -> {
                        val isActive = block.id == document.activeTextBlockId
                        val isLast = index == document.blocks.lastIndex
                        val hasPrevImg = index > 0 && document.blocks[index - 1] is EditorBodyBlock.Image
                        val targetSel = if (isActive) TextRange(document.activeSelectionStart.coerceIn(0, block.text.length), document.activeSelectionEnd.coerceIn(0, block.text.length)) else TextRange(block.text.length)
                        var focused by remember { mutableStateOf(false) }
                        val focusRequester = remember { FocusRequester() }
                        val blockInteraction = remember { MutableInteractionSource() }
                        LaunchedEffect(blockInteraction) { blockInteraction.interactions.collect { if (it is PressInteraction.Press) latestOnInputPress() } }
                        if (isActive) SideEffect { onActiveFocusRequester(focusRequester) }
                        val fieldState = rememberSyncedTextFieldState(block.text, syncVersion, targetSel, isActive, { txt, sel -> onTextBlockChanged(block.id, txt, sel.start, sel.end) }, { sel -> onTextBlockSelectionChanged(block.id, sel.start, sel.end) }, { focused })
                        LaunchedEffect(focusRequestVersion, isActive) {
                            if (isLoggedIn && focusRequestVersion > consumedFocusVersionState.longValue && isActive && !preferSubjectFocus) {
                                consumedFocusVersionState.longValue = focusRequestVersion
                                runCatching { focusRequester.requestFocus() }
                                onShowKeyboard()
                            }
                        }
                        LaunchedEffect(pendingFocusRestore?.version) {
                            val restore = pendingFocusRestore ?: return@LaunchedEffect
                            if (restore.target == EditorFocusTarget.Body(block.id)) {
                                focusTracker?.consume(restore.version)
                                if (fieldState.selection != targetSel) fieldState.edit { selection = targetSel }
                                runCatching { focusRequester.requestFocus() }
                                latestAfterRestore()
                            }
                        }
                        BasicTextField(
                            fieldState,
                            Modifier.fillMaxWidth().heightIn(min = if (isLast) 140.dp else 32.dp).focusRequester(focusRequester)
                                .onFocusChanged { st -> focused = st.isFocused; focusTracker?.onFocusChanged(EditorFocusTarget.Body(block.id), st.isFocused); if (st.isFocused) { onActiveFocusRequester(focusRequester); onTextBlockSelectionChanged(block.id, fieldState.selection.start, fieldState.selection.end) } }
                                .onPreviewKeyEvent { ev -> if (hasPrevImg && ev.type == KeyEventType.KeyDown && ev.key == Key.Backspace && fieldState.selection == TextRange(0)) { latestOnBs(); true } else false }
                                .semantics { contentDescription = bodyContentDescription },
                            textStyle = bodyStyle, cursorBrush = SolidColor(cs.primary), interactionSource = blockInteraction, lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = if (isLast) 5 else 1),
                            decorator = { inner -> Box(Modifier.fillMaxWidth()) { if (!visualPlaceholder.isNullOrEmpty() && document.blocks.size == 1 && fieldState.text.isEmpty()) Text(visualPlaceholder, style = bodyStyle, color = cs.onSurfaceVariant); inner() } },
                        )
                    }
                    is EditorBodyBlock.Image -> EditorInlineImageBlock(block.imageId, previewUriByImageId[block.imageId], { onRemoveInlineImage(block.id) })
                }
            }
        }
    }
}

@Composable
private fun EditorDraftRestoredBanner(onClear: () -> Unit) = HiCappedFontScale {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(HiShapeTokens.Small).background(cs.secondaryContainer).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.compose_draft_restored_banner), style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextButton(onClear, Modifier.defaultMinSize(48.dp, 48.dp), shape = HiShapeTokens.Small) { Text(stringResource(R.string.compose_action_clear_draft)) }
    }
}

@Composable
private fun EditorGuestBanner(onOpenLogin: () -> Unit) = HiCappedFontScale {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().background(cs.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 10.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(stringResource(R.string.thread_detail_login_to_operate), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.weight(1f).padding(end = 12.dp))
        FilledTonalButton(onOpenLogin, Modifier.defaultMinSize(72.dp, 48.dp).hiPressScale(interaction), shape = HiShapeTokens.Small, interactionSource = interaction) { Text(stringResource(R.string.common_action_login)) }
    }
}

private fun resolveEditorErrorMessage(ctx: android.content.Context, error: FullEditorInlineError?): String? = when {
    error == null -> null
    error.cooldownMessageSeconds != null -> ctx.getString(R.string.compose_error_cooldown, error.cooldownMessageSeconds)
    !error.customMessage.isNullOrBlank() && !UNSAFE_ERROR_PATTERNS.containsMatchIn(error.customMessage) -> error.customMessage
    error.uiError != null -> ctx.getString(error.uiError.messageRes)
    else -> null
}

@Composable
private fun EditorInlineErrorRow(error: FullEditorInlineError, onRetry: () -> Unit, onOpenLogin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val msg = resolveEditorErrorMessage(LocalContext.current, error) ?: return
    val action = error.uiError?.action
    val actionLabel = when (action) { UiErrorAction.Retry -> stringResource(R.string.common_action_retry); UiErrorAction.Login -> stringResource(R.string.common_action_login); UiErrorAction.Reverify -> stringResource(R.string.challenge_action_retry); null -> null }
    HiCappedFontScale {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
            Text(msg, style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.weight(1f))
            if (actionLabel != null && action != null) TextButton({ if (action == UiErrorAction.Login) onOpenLogin() else onRetry() }, Modifier.defaultMinSize(48.dp, 48.dp), shape = HiShapeTokens.Small) { Text(actionLabel) }
        }
    }
}

@Composable
private fun FullEditorBottomToolbar(hasEmojiSlot: Boolean, isEmojiPanelOpen: Boolean, bottomInset: Dp, onInsertImageClick: () -> Unit, onToggleEmojiClick: () -> Unit) = HiCappedFontScale {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(cs.surfaceContainerLow).padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = bottomInset + 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val imgInt = remember { MutableInteractionSource() }
        IconButton(onInsertImageClick, Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(imgInt), interactionSource = imgInt) {
            Icon(ComposeVectorIcons.InsertImage, stringResource(R.string.compose_action_insert_image), tint = cs.onSurface, modifier = Modifier.size(22.dp))
        }
        if (hasEmojiSlot) {
            val emojiCd = stringResource(if (isEmojiPanelOpen) R.string.compose_action_keyboard else R.string.compose_action_emoji)
            val emojiInt = remember { MutableInteractionSource() }
            IconButton(onToggleEmojiClick, Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(emojiInt), interactionSource = emojiInt) {
                HiIconSwitch(target = if (isEmojiPanelOpen) ComposeVectorIcons.Keyboard else Icons.Rounded.Face, contentDescription = emojiCd, tint = cs.onSurface, iconSize = 22.dp)
            }
        }
    }
}

@Composable
private fun resolveEditorTitle(mode: FullEditorMode): String = when (mode) {
    is FullEditorMode.NewThread -> stringResource(R.string.compose_title_new_thread)
    is FullEditorMode.ReplyThread -> mode.threadAuthor?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.compose_reply_placeholder_user, it) }
        ?: stringResource(R.string.compose_title_reply)
    is FullEditorMode.ReplyPost -> mode.targetAuthor?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.compose_reply_placeholder_user, it) }
        ?: stringResource(R.string.compose_title_reply)
    is FullEditorMode.QuotePost -> when {
        mode.targetFloor != null && !mode.targetAuthor.isNullOrBlank() ->
            stringResource(R.string.compose_quote_placeholder_floor_user, mode.targetFloor, mode.targetAuthor)
        !mode.targetAuthor.isNullOrBlank() ->
            stringResource(R.string.compose_reply_placeholder_user, mode.targetAuthor)
        else -> stringResource(R.string.compose_title_quote_reply)
    }
    is FullEditorMode.EditPost -> stringResource(R.string.compose_title_edit_post)
}

@Composable
private fun resolveBodyVisualPlaceholder(mode: FullEditorMode): String? = when (mode) {
    is FullEditorMode.Reply -> null
    is FullEditorMode.NewThread, is FullEditorMode.EditPost -> stringResource(R.string.compose_field_body_hint)
}

private val UNSAFE_ERROR_PATTERNS = Regex("""(?:Exception|Throwable|HTTP\s*[45]\d{2}|\[attachimg\])""", RegexOption.IGNORE_CASE)
