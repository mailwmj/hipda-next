package net.jejer.hipda.feature.compose

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val effectiveEmojiSlot = rememberResolvedEmojiPanelSlot(emojiPanelSlot)
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val windowHeightDp = LocalWindowInfo.current.containerDpSize.height
    var isEmojiPanelOpen by remember { mutableStateOf(false) }
    var lastObservedImeDp by remember { mutableStateOf(0.dp) }
    val imeBottomDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    LaunchedEffect(imeBottomDp) { if (imeBottomDp > 0.dp) { lastObservedImeDp = imeBottomDp; if (isEmojiPanelOpen) isEmojiPanelOpen = false } }

    val imagePickerLauncher = if (LocalActivityResultRegistryOwner.current != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> if (uris.isNotEmpty()) viewModel.onPickImages(uris) }
    } else null
    val launchImagePicker: () -> Unit = { imagePickerLauncher?.launch("image/*") }
    LaunchedEffect(uiState.pendingImagePickerTrigger) {
        val trigger = uiState.pendingImagePickerTrigger
        if (trigger > 0L) { viewModel.consumeImagePickerTrigger(trigger); launchImagePicker() }
    }

    val closeEditor: () -> Unit = { isEmojiPanelOpen = false; keyboardController?.hide(); viewModel.requestClose(emitSavedSnackbar = true) }
    val handleBack: () -> Unit = { if (isEmojiPanelOpen) isEmojiPanelOpen = false else closeEditor() }
    val latestHandleBack by rememberUpdatedState(handleBack)
    LaunchedEffect(Unit) { onRegisterBackAction { latestHandleBack() } }
    if (registerBackHandler) key(uiState.sessionId) { BackHandler(enabled = uiState.isOpen) { latestHandleBack() } }

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
    val emojiPanelHeightDp = if (isEmojiPanelOpen && effectiveEmojiSlot != null) calculateEmojiPanelHeight(lastObservedImeDp, windowHeightDp) else 0.dp
    val navBarBottomDp = if (isModalCard) 0.dp else with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val previewUriByImageId = remember(uiState.uploads) { buildMap { uiState.uploads.forEach { u -> val id = u.imageIdOrNull; val uri = u.contentUri; if (id != null && uri != null) put(id, uri) } } }
    val isEditWaitingOriginal = uiState.isEditPost && uiState.editOriginal == null

    Column(
        modifier = modifier.fillMaxSize().background(cs.surface).let { if (!isModalCard && !isEmojiPanelOpen) it.imePadding() else it }
            .semantics { paneTitle = titleText; isTraversalGroup = true },
    ) {
        FullEditorTopBar(titleText, resolveEditorSubtitle(uiState.mode), isScrolled, !isModalCard, uiState.isSubmitting, uiState.effectiveCooldownSeconds, uiState.canTapSend, uiState.canRestoreOriginal, closeEditor, viewModel::restoreOriginal, viewModel::submit)
        if (isEditWaitingOriginal) {
            EditOriginalStatusArea(uiState.isLoadingEditOriginal, uiState.editLoadError, viewModel::retryEditLoad, onOpenLogin, Modifier.weight(1f).fillMaxWidth())
            return@Column
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)) {
            if (!uiState.isLoggedIn) EditorGuestBanner(onOpenLogin)
            AnimatedVisibility(uiState.showDraftRestoredBanner, enter = hiExpandVertically(expandFrom = Alignment.Top) + hiFadeIn(), exit = hiShrinkVertically(shrinkTowards = Alignment.Top) + hiFadeOut()) {
                EditorDraftRestoredBanner(viewModel::clearRestoredDraft)
            }
            if (uiState.isNewThread) {
                NewThreadMetaSection(uiState.selectedFid, uiState.availableForums, uiState.typeOptions, uiState.hasSelectableCategories, uiState.selectedTypeId, uiState.subject, uiState.bodySyncVersion, uiState.validationError, viewModel::selectForum, viewModel::selectCategory, viewModel::onSubjectChanged)
            } else if (uiState.isEditPost && uiState.showsEditSubjectField) {
                EditPostMetaSection(uiState.typeOptions, uiState.hasSelectableCategories, uiState.selectedTypeId, uiState.subject, uiState.bodySyncVersion, uiState.validationError, viewModel::selectCategory, viewModel::onSubjectChanged)
            }
            ImageUploadStrip(uiState.uploads, uiState.insertedImageIds, uiState.uploadSummaryProgress, viewModel::onInsertUploadedImage, viewModel::retryUpload, viewModel::removeUpload)
            EditorRichBodySection(uiState.body, uiState.bodySyncVersion, uiState.focusRequestVersion, previewUriByImageId, resolveBodyPlaceholder(uiState.mode), viewModel::onTextBlockChanged, viewModel::onTextBlockSelectionChanged, viewModel::onRemoveInlineImageBlock, viewModel::onBackspace)
            if (uiState.validationError == FullEditorValidationError.ContentTooShort) {
                Text(stringResource(FullEditorValidationError.ContentTooShort.messageRes), style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            uiState.inlineError?.let { EditorInlineErrorRow(it, viewModel::submit, onOpenLogin) }
            Spacer(Modifier.height(16.dp))
        }
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
        FullEditorBottomToolbar(
            effectiveEmojiSlot != null, isEmojiPanelOpen, if (imeBottomDp > 0.dp || (isEmojiPanelOpen && effectiveEmojiSlot != null)) 0.dp else navBarBottomDp, launchImagePicker,
        ) { if (isEmojiPanelOpen) { isEmojiPanelOpen = false; keyboardController?.show() } else { keyboardController?.hide(); isEmojiPanelOpen = true } }
        if (isEmojiPanelOpen && effectiveEmojiSlot != null) {
            val emojiHost = remember(emojiPanelHeightDp, windowHeightDp, viewModel, keyboardController) {
                QuickReplyEmojiPanelHost(emojiPanelHeightDp, windowHeightDp, viewModel::onInsertEmoji, viewModel::onBackspace) { isEmojiPanelOpen = false; keyboardController?.show() }
            }
            Box(Modifier.fillMaxWidth().height(emojiPanelHeightDp).background(cs.surfaceContainerLow)) { effectiveEmojiSlot(emojiHost) }
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
    LaunchedEffect(syncVersion, text) {
        val verBumped = syncVersion != lastVer; val externalTxt = text != lastTxt; lastVer = syncVersion
        if (verBumped || externalTxt) {
            lastTxt = text; lastSel = selection
            if (fieldState.text.toString() != text || (applySelectionOnSync && verBumped && fieldState.selection != selection)) {
                fieldState.edit { if (asCharSequence().toString() != text) replace(0, length, text); if (applySelectionOnSync && verBumped) this.selection = selection }
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
    title: String, subtitle: String?, isScrolled: Boolean, applyStatusBarInset: Boolean,
    isSubmitting: Boolean, cooldownSeconds: Int, canTapSend: Boolean, showRestoreOriginal: Boolean,
    onBackClick: () -> Unit, onRestoreOriginalClick: () -> Unit, onSendClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val barBg by animateColorAsState(if (isScrolled) cs.surfaceContainer else cs.surface, HiMotion.effectsDefault(), label = "FullEditorTopBarColor")
    Column(Modifier.fillMaxWidth().background(barBg)) {
        if (applyStatusBarInset) Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        HiCappedFontScale {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val backInt = remember { MutableInteractionSource() }
                IconButton(onBackClick, Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(backInt), interactionSource = backInt) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_action_back), tint = cs.onSurface)
                }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
                EditorSendButton(isSubmitting, cooldownSeconds, canTapSend, onSendClick)
            }
        }
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
    }
}

@Composable
private fun EditorSendButton(isSubmitting: Boolean, cooldownSeconds: Int, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val sendLabel = stringResource(R.string.compose_action_send)
    val desc = when { isSubmitting -> stringResource(R.string.compose_status_sending); cooldownSeconds > 0 -> stringResource(R.string.compose_cooldown_send_cd, cooldownSeconds); else -> sendLabel }
    Button(onClick, Modifier.defaultMinSize(72.dp, 48.dp).hiPressScale(interaction).semantics { contentDescription = desc }, enabled, HiShapeTokens.Full, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp), interactionSource = interaction) {
        if (isSubmitting) HiLoadingSpinner(Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onSurface.copy(alpha = 0.72f), trackColor = cs.onSurface.copy(alpha = 0.20f))
        else AnimatedContent(
            targetState = cooldownSeconds > 0, contentAlignment = Alignment.Center, label = "FullEditorSendCooldown",
            transitionSpec = { ((hiScaleIn(HiMotion.spatialFast()) + hiFadeIn(HiMotion.effectsFast())) togetherWith (hiScaleOut(HiMotion.exit(HiMotion.spatialFast())) + hiFadeOut(HiMotion.exit(HiMotion.effectsFast())))).using(null) },
        ) { cooling ->
            if (cooling) HiRollingNumber(cooldownSeconds.coerceAtLeast(1), style = MaterialTheme.typography.labelLarge, color = cs.onSurface.copy(alpha = 0.78f))
            else Text(sendLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
    }
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
private fun NewThreadMetaSection(selectedFid: Int?, availableForums: List<ForumMetadata>, typeOptions: Map<String, String>, hasSelectableCategories: Boolean, selectedTypeId: String, subject: String, syncVersion: Long, validationError: FullEditorValidationError?, onSelectForum: (Int) -> Unit, onSelectCategory: (String) -> Unit, onSubjectChanged: (String) -> Unit) {
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
        EditorSubjectField(subject, syncVersion, validationError, onSubjectChanged)
    }
    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
}

@Composable
private fun EditPostMetaSection(typeOptions: Map<String, String>, hasSelectableCategories: Boolean, selectedTypeId: String, subject: String, syncVersion: Long, validationError: FullEditorValidationError?, onSelectCategory: (String) -> Unit, onSubjectChanged: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasSelectableCategories) {
            val catOptions = remember(typeOptions) { typeOptions.entries.filter { it.key.isNotBlank() && it.key != "0" }.map { it.key to it.value } }
            EditorDropdownField(stringResource(R.string.compose_field_category), catOptions.firstOrNull { it.first == selectedTypeId }?.second, validationError == FullEditorValidationError.SelectCategory, catOptions, selectedTypeId, onSelectCategory, Modifier.fillMaxWidth())
            if (validationError == FullEditorValidationError.SelectCategory) {
                Text(stringResource(validationError.messageRes), style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
        }
        EditorSubjectField(subject, syncVersion, validationError, onSubjectChanged)
    }
    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
}

@Composable
private fun EditorSubjectField(subject: String, syncVersion: Long, validationError: FullEditorValidationError?, onSubjectChanged: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val subjectHint = stringResource(R.string.compose_field_subject_hint)
    val subjectStyle = MaterialTheme.typography.titleMedium.copy(color = cs.onSurface)
    val subjectFieldState = rememberSyncedTextFieldState(text = subject, syncVersion = syncVersion, onTextChanged = { txt, _ -> onSubjectChanged(txt) })
    val titleErr = validationError == FullEditorValidationError.EnterTitle || validationError == FullEditorValidationError.TitleTooShort
    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(HiShapeTokens.Small).background(cs.surfaceContainerLow).border(1.dp, if (titleErr) cs.error else cs.outlineVariant, HiShapeTokens.Small).padding(horizontal = 12.dp, vertical = 12.dp), Alignment.CenterStart) {
        BasicTextField(
            subjectFieldState, Modifier.fillMaxWidth().semantics { contentDescription = subjectHint }, textStyle = subjectStyle, cursorBrush = SolidColor(cs.primary),
            lineLimits = TextFieldLineLimits.MultiLine(1, 2),
            decorator = { inner -> Box(Modifier.fillMaxWidth()) { if (subjectFieldState.text.isEmpty()) Text(subjectHint, style = subjectStyle, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() } },
        )
    }
    if (titleErr) Text(stringResource(validationError.messageRes), style = MaterialTheme.typography.bodySmall, color = cs.error)
}

@Composable
private fun EditorRichBodySection(document: EditorBodyDocument, syncVersion: Long, focusRequestVersion: Long, previewUriByImageId: Map<String, Uri>, placeholder: String, onTextBlockChanged: (String, String, Int, Int) -> Unit, onTextBlockSelectionChanged: (String, Int, Int) -> Unit, onRemoveInlineImage: (String) -> Unit, onBackspaceAtBlockStart: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bodyStyle = HiTheme.typography.postBody.copy(color = cs.onSurface)
    val latestOnBs by rememberUpdatedState(onBackspaceAtBlockStart)
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
                        val fieldState = rememberSyncedTextFieldState(block.text, syncVersion, targetSel, isActive, { txt, sel -> onTextBlockChanged(block.id, txt, sel.start, sel.end) }, { sel -> onTextBlockSelectionChanged(block.id, sel.start, sel.end) }, { focused })
                        LaunchedEffect(focusRequestVersion, isActive) { if (focusRequestVersion > 0L && isActive) runCatching { focusRequester.requestFocus() } }
                        BasicTextField(
                            fieldState,
                            Modifier.fillMaxWidth().heightIn(min = if (isLast) 140.dp else 32.dp).focusRequester(focusRequester)
                                .onFocusChanged { st -> focused = st.isFocused; if (st.isFocused) onTextBlockSelectionChanged(block.id, fieldState.selection.start, fieldState.selection.end) }
                                .onPreviewKeyEvent { ev -> if (hasPrevImg && ev.type == KeyEventType.KeyDown && ev.key == Key.Backspace && fieldState.selection == TextRange(0)) { latestOnBs(); true } else false }
                                .semantics { contentDescription = placeholder },
                            textStyle = bodyStyle, cursorBrush = SolidColor(cs.primary), lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = if (isLast) 5 else 1),
                            decorator = { inner -> Box(Modifier.fillMaxWidth()) { if (document.blocks.size == 1 && fieldState.text.isEmpty()) Text(placeholder, style = bodyStyle, color = cs.onSurfaceVariant); inner() } },
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
private fun resolveEditorTitle(mode: FullEditorMode): String = stringResource(
    when (mode) {
        is FullEditorMode.NewThread -> R.string.compose_title_new_thread
        is FullEditorMode.QuotePost -> R.string.compose_title_quote_reply
        is FullEditorMode.Reply -> R.string.compose_title_reply
        is FullEditorMode.EditPost -> R.string.compose_title_edit_post
    },
)

@Composable
private fun resolveEditorSubtitle(mode: FullEditorMode): String? = when (mode) {
    is FullEditorMode.NewThread -> null
    is FullEditorMode.QuotePost -> if (mode.targetFloor != null && !mode.targetAuthor.isNullOrBlank()) stringResource(R.string.compose_quote_placeholder_floor_user, mode.targetFloor, mode.targetAuthor) else mode.targetAuthor?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.compose_reply_placeholder_user, it) }
    is FullEditorMode.ReplyPost -> mode.targetAuthor?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.compose_reply_placeholder_user, it) }
    is FullEditorMode.ReplyThread -> mode.threadAuthor?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.compose_reply_placeholder_user, it) }
    is FullEditorMode.EditPost -> if (mode.floorNumber != null && !mode.author.isNullOrBlank()) stringResource(R.string.thread_detail_floor_title, mode.floorNumber, mode.author) else null
}

@Composable
private fun resolveBodyPlaceholder(mode: FullEditorMode): String =
    if (mode is FullEditorMode.EditPost) stringResource(R.string.compose_field_body_hint)
    else resolveEditorSubtitle(mode) ?: stringResource(R.string.compose_field_body_hint)

private val UNSAFE_ERROR_PATTERNS = Regex("""(?:Exception|Throwable|HTTP\s*[45]\d{2}|\[attachimg\])""", RegexOption.IGNORE_CASE)
