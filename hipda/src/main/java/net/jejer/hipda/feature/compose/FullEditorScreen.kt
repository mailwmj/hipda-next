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
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Face
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
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.*
import net.jejer.hipda.ui.designsystem.motion.*
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner
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
    if (registerBackHandler) {
        key(uiState.sessionId) { BackHandler(enabled = uiState.isOpen) { latestHandleBack() } }
    }

    val scrollState = rememberScrollState()
    val isScrolled by remember { derivedStateOf { scrollState.value > 0 } }
    val cs = MaterialTheme.colorScheme
    val titleText = resolveEditorTitle(uiState.mode)
    val emojiPanelHeightDp = if (isEmojiPanelOpen && effectiveEmojiSlot != null) calculateEmojiPanelHeight(lastObservedImeDp, windowHeightDp) else 0.dp
    val navBarBottomDp = if (isModalCard) 0.dp else with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val previewUriByImageId = remember(uiState.uploads) {
        buildMap { uiState.uploads.forEach { u -> val id = u.imageIdOrNull; val uri = u.contentUri; if (id != null && uri != null) put(id, uri) } }
    }

    Column(
        modifier = modifier.fillMaxSize().background(cs.surface)
            .let { if (!isModalCard && !isEmojiPanelOpen) it.imePadding() else it }
            .semantics { paneTitle = titleText; isTraversalGroup = true },
    ) {
        FullEditorTopBar(
            title = titleText, subtitle = resolveEditorSubtitle(uiState.mode), isScrolled = isScrolled, applyStatusBarInset = !isModalCard,
            isSubmitting = uiState.isSubmitting, cooldownSeconds = uiState.cooldownSeconds, canTapSend = uiState.canTapSend,
            onBackClick = closeEditor, onSendClick = viewModel::submit,
        )
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)) {
            if (!uiState.isLoggedIn) EditorGuestBanner(onOpenLogin)
            AnimatedVisibility(uiState.showDraftRestoredBanner, enter = hiExpandVertically(expandFrom = Alignment.Top) + hiFadeIn(), exit = hiShrinkVertically(shrinkTowards = Alignment.Top) + hiFadeOut()) {
                EditorDraftRestoredBanner(viewModel::clearRestoredDraft)
            }
            if (uiState.isNewThread) {
                NewThreadMetaSection(
                    uiState.selectedFid, uiState.availableForums, uiState.typeOptions, uiState.hasSelectableCategories,
                    uiState.selectedTypeId, uiState.subject, uiState.validationError,
                    viewModel::selectForum, viewModel::selectCategory, viewModel::onSubjectChanged,
                )
            }
            ImageUploadStrip(
                uiState.uploads, uiState.insertedImageIds, uiState.uploadSummaryProgress,
                viewModel::onInsertUploadedImage, viewModel::retryUpload, viewModel::removeUpload,
            )
            EditorRichBodySection(
                uiState.body, uiState.bodySyncVersion, previewUriByImageId, resolveBodyPlaceholder(uiState.mode),
                viewModel::onTextBlockChanged, viewModel::onTextBlockSelectionChanged, viewModel::onRemoveInlineImageBlock, viewModel::onBackspace,
            )
            if (uiState.validationError == FullEditorValidationError.ContentTooShort) {
                Text(stringResource(FullEditorValidationError.ContentTooShort.messageRes), style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            uiState.inlineError?.let { EditorInlineErrorRow(it, viewModel::submit, onOpenLogin) }
            Spacer(Modifier.height(16.dp))
        }
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
        FullEditorBottomToolbar(
            hasEmojiSlot = effectiveEmojiSlot != null, isEmojiPanelOpen = isEmojiPanelOpen,
            bottomInset = if (imeBottomDp > 0.dp || (isEmojiPanelOpen && effectiveEmojiSlot != null)) 0.dp else navBarBottomDp,
            onInsertImageClick = launchImagePicker,
            onToggleEmojiClick = {
                if (isEmojiPanelOpen) { isEmojiPanelOpen = false; keyboardController?.show() }
                else { keyboardController?.hide(); isEmojiPanelOpen = true }
            },
        )
        if (isEmojiPanelOpen && effectiveEmojiSlot != null) {
            val emojiHost = remember(emojiPanelHeightDp, windowHeightDp, viewModel, keyboardController) {
                QuickReplyEmojiPanelHost(emojiPanelHeightDp, windowHeightDp, viewModel::onInsertEmoji, viewModel::onBackspace) {
                    isEmojiPanelOpen = false; keyboardController?.show()
                }
            }
            Box(Modifier.fillMaxWidth().height(emojiPanelHeightDp).background(cs.surfaceContainerLow)) { effectiveEmojiSlot(emojiHost) }
        }
    }
}

@Composable
private fun FullEditorTopBar(
    title: String, subtitle: String?, isScrolled: Boolean, applyStatusBarInset: Boolean,
    isSubmitting: Boolean, cooldownSeconds: Int, canTapSend: Boolean, onBackClick: () -> Unit, onSendClick: () -> Unit,
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
    val desc = when {
        isSubmitting -> stringResource(R.string.compose_status_sending)
        cooldownSeconds > 0 -> stringResource(R.string.compose_cooldown_send_cd, cooldownSeconds)
        else -> sendLabel
    }
    Button(
        onClick = onClick, enabled = enabled, interactionSource = interaction, shape = HiShapeTokens.Full,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        modifier = Modifier.defaultMinSize(72.dp, 48.dp).hiPressScale(interaction).semantics { contentDescription = desc },
    ) {
        if (isSubmitting) {
            HiLoadingSpinner(Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onSurface.copy(alpha = 0.72f), trackColor = cs.onSurface.copy(alpha = 0.20f))
        } else {
            AnimatedContent(
                targetState = cooldownSeconds > 0, contentAlignment = Alignment.Center, label = "FullEditorSendCooldown",
                transitionSpec = { ((hiScaleIn(HiMotion.spatialFast()) + hiFadeIn(HiMotion.effectsFast())) togetherWith (hiScaleOut(HiMotion.exit(HiMotion.spatialFast())) + hiFadeOut(HiMotion.exit(HiMotion.effectsFast())))).using(null) },
            ) { cooling ->
                if (cooling) HiRollingNumber(cooldownSeconds.coerceAtLeast(1), style = MaterialTheme.typography.labelLarge, color = cs.onSurface.copy(alpha = 0.78f))
                else Text(sendLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun <K> EditorDropdownField(
    label: String, selectedLabel: String?, placeholder: String, isError: Boolean,
    options: List<Pair<K, String>>, selectedKey: K?, onSelect: (K) -> Unit, modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(HiShapeTokens.Small).background(cs.surfaceContainerLow)
                .border(1.dp, if (isError) cs.error else cs.outlineVariant, HiShapeTokens.Small)
                .hiPressScale(interaction).clickable(interaction, null, role = Role.DropdownList) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics { contentDescription = if (selectedLabel != null) "$label: $selectedLabel" else label },
            Arrangement.SpaceBetween, Alignment.CenterVertically,
        ) {
            Text(selectedLabel ?: placeholder, style = MaterialTheme.typography.bodyMedium, color = if (selectedLabel != null) cs.onSurface else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ArrowDropDown, null, tint = cs.onSurfaceVariant)
        }
        DropdownMenu(expanded, { expanded = false }, Modifier.background(cs.surfaceContainerHigh).heightIn(max = 320.dp)) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = if (key == selectedKey) ({ Icon(Icons.Rounded.Check, null, tint = cs.primary, modifier = Modifier.size(18.dp)) }) else null,
                    onClick = { expanded = false; onSelect(key) },
                )
            }
        }
    }
}

@Composable
private fun NewThreadMetaSection(
    selectedFid: Int?, availableForums: List<ForumMetadata>, typeOptions: Map<String, String>, hasSelectableCategories: Boolean,
    selectedTypeId: String, subject: String, validationError: FullEditorValidationError?,
    onSelectForum: (Int) -> Unit, onSelectCategory: (String) -> Unit, onSubjectChanged: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val forumOptions = remember(availableForums) { availableForums.map { it.fid to it.fullName } }
    val selectedForumName = selectedFid?.takeIf { it > 0 }?.let { ForumMetadataRegistry.resolve(it).fullName }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
            val forumLabel = stringResource(R.string.compose_field_forum)
            EditorDropdownField(
                forumLabel, selectedForumName, forumLabel,
                validationError == FullEditorValidationError.SelectForum, forumOptions, selectedFid, onSelectForum, Modifier.weight(1f),
            )
            if (hasSelectableCategories) {
                val catLabel = stringResource(R.string.compose_field_category)
                val catOptions = remember(typeOptions) { typeOptions.entries.filter { it.key.isNotBlank() && it.key != "0" }.map { it.key to it.value } }
                EditorDropdownField(
                    catLabel, catOptions.firstOrNull { it.first == selectedTypeId }?.second,
                    catLabel, validationError == FullEditorValidationError.SelectCategory,
                    catOptions, selectedTypeId, onSelectCategory, Modifier.weight(0.75f),
                )
            }
        }
        if (validationError == FullEditorValidationError.SelectForum || validationError == FullEditorValidationError.SelectCategory) {
            Text(stringResource(validationError.messageRes), style = MaterialTheme.typography.bodySmall, color = cs.error)
        }
        val subjectHint = stringResource(R.string.compose_field_subject_hint)
        val subjectStyle = MaterialTheme.typography.titleMedium.copy(color = cs.onSurface)
        val subjectFieldState = rememberTextFieldState(initialText = subject)
        var lastSynced by remember { mutableStateOf(subject) }
        LaunchedEffect(subject) {
            if (subject != lastSynced) {
                lastSynced = subject
                if (subjectFieldState.text.toString() != subject) subjectFieldState.edit { replace(0, length, subject) }
            }
        }
        LaunchedEffect(subjectFieldState) {
            snapshotFlow { subjectFieldState.text.toString() }.collect { cur -> if (cur != lastSynced) { lastSynced = cur; onSubjectChanged(cur) } }
        }
        val titleErr = validationError == FullEditorValidationError.EnterTitle || validationError == FullEditorValidationError.TitleTooShort
        Box(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(HiShapeTokens.Small).background(cs.surfaceContainerLow)
                .border(1.dp, if (titleErr) cs.error else cs.outlineVariant, HiShapeTokens.Small).padding(horizontal = 12.dp, vertical = 12.dp),
            Alignment.CenterStart,
        ) {
            BasicTextField(
                state = subjectFieldState, textStyle = subjectStyle, cursorBrush = SolidColor(cs.primary),
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 2),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = subjectHint },
                decorator = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (subjectFieldState.text.isEmpty()) Text(subjectHint, style = subjectStyle, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
        }
        if (titleErr) Text(stringResource(validationError.messageRes), style = MaterialTheme.typography.bodySmall, color = cs.error)
    }
    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
}

@Composable
private fun EditorRichBodySection(
    document: EditorBodyDocument, syncVersion: Long, previewUriByImageId: Map<String, Uri>, placeholder: String,
    onTextBlockChanged: (String, String, Int, Int) -> Unit, onTextBlockSelectionChanged: (String, Int, Int) -> Unit,
    onRemoveInlineImage: (String) -> Unit, onBackspaceAtBlockStart: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bodyStyle = HiTheme.typography.postBody.copy(color = cs.onSurface)
    val latestOnText by rememberUpdatedState(onTextBlockChanged)
    val latestOnSel by rememberUpdatedState(onTextBlockSelectionChanged)
    val latestOnBs by rememberUpdatedState(onBackspaceAtBlockStart)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        document.blocks.forEachIndexed { index, block ->
            key(block.id) {
                when (block) {
                    is EditorBodyBlock.Text -> {
                        val isActive = block.id == document.activeTextBlockId
                        val isLast = index == document.blocks.lastIndex
                        val hasPrevImg = index > 0 && document.blocks[index - 1] is EditorBodyBlock.Image
                        val initSel = if (isActive) TextRange(document.activeSelectionStart.coerceIn(0, block.text.length), document.activeSelectionEnd.coerceIn(0, block.text.length)) else TextRange(block.text.length)
                        val fieldState = rememberTextFieldState(initialText = block.text, initialSelection = initSel)
                        var lastTxt by remember { mutableStateOf(block.text) }
                        var lastSel by remember { mutableStateOf(initSel) }
                        var focused by remember { mutableStateOf(false) }
                        LaunchedEffect(syncVersion, block.id) {
                            val targetSel = if (isActive) TextRange(document.activeSelectionStart.coerceIn(0, block.text.length), document.activeSelectionEnd.coerceIn(0, block.text.length)) else TextRange(block.text.length)
                            lastTxt = block.text; lastSel = targetSel
                            if (fieldState.text.toString() != block.text || (isActive && fieldState.selection != targetSel)) {
                                fieldState.edit { if (asCharSequence().toString() != block.text) replace(0, length, block.text); selection = targetSel }
                            }
                        }
                        LaunchedEffect(fieldState) {
                            snapshotFlow { fieldState.text.toString() to fieldState.selection }.collect { (txt, sel) ->
                                if (txt != lastTxt) { lastTxt = txt; lastSel = sel; latestOnText(block.id, txt, sel.start, sel.end) }
                                else if (focused && sel != lastSel) { lastSel = sel; latestOnSel(block.id, sel.start, sel.end) }
                            }
                        }
                        BasicTextField(
                            state = fieldState, textStyle = bodyStyle, cursorBrush = SolidColor(cs.primary),
                            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = if (isLast) 5 else 1),
                            modifier = Modifier.fillMaxWidth().heightIn(min = if (isLast) 140.dp else 32.dp)
                                .onFocusChanged { st -> focused = st.isFocused; if (st.isFocused) { lastSel = fieldState.selection; latestOnSel(block.id, lastSel.start, lastSel.end) } }
                                .onPreviewKeyEvent { ev ->
                                    if (hasPrevImg && ev.type == KeyEventType.KeyDown && ev.key == Key.Backspace && fieldState.selection == TextRange(0)) {
                                        latestOnBs(); true
                                    } else false
                                }
                                .semantics { contentDescription = placeholder },
                            decorator = { inner ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (document.blocks.size == 1 && fieldState.text.isEmpty()) Text(placeholder, style = bodyStyle, color = cs.onSurfaceVariant)
                                    inner()
                                }
                            },
                        )
                    }
                    is EditorBodyBlock.Image -> EditorInlineImageBlock(block.imageId, previewUriByImageId[block.imageId], { onRemoveInlineImage(block.id) })
                }
            }
        }
    }
}

@Composable
private fun EditorDraftRestoredBanner(onClear: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    HiCappedFontScale {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(HiShapeTokens.Small).background(cs.secondaryContainer).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.compose_draft_restored_banner), style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClear, Modifier.defaultMinSize(48.dp, 48.dp), shape = HiShapeTokens.Small) { Text(stringResource(R.string.compose_action_clear_draft)) }
        }
    }
}

@Composable
private fun EditorGuestBanner(onOpenLogin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    HiCappedFontScale {
        Row(Modifier.fillMaxWidth().background(cs.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 10.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(stringResource(R.string.thread_detail_login_to_operate), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.weight(1f).padding(end = 12.dp))
            FilledTonalButton(onOpenLogin, Modifier.defaultMinSize(72.dp, 48.dp).hiPressScale(interaction), shape = HiShapeTokens.Small, interactionSource = interaction) { Text(stringResource(R.string.common_action_login)) }
        }
    }
}

@Composable
private fun EditorInlineErrorRow(error: FullEditorInlineError, onRetry: () -> Unit, onOpenLogin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val msg = when {
        error.cooldownMessageSeconds != null -> ctx.getString(R.string.compose_error_cooldown, error.cooldownMessageSeconds)
        !error.customMessage.isNullOrBlank() && !UNSAFE_ERROR_PATTERNS.containsMatchIn(error.customMessage) -> error.customMessage
        error.uiError != null -> ctx.getString(error.uiError.messageRes)
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
            Text(msg, style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.weight(1f))
            if (actionLabel != null && action != null) {
                TextButton({ if (action == UiErrorAction.Login) onOpenLogin() else onRetry() }, Modifier.defaultMinSize(48.dp, 48.dp), shape = HiShapeTokens.Small) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun FullEditorBottomToolbar(hasEmojiSlot: Boolean, isEmojiPanelOpen: Boolean, bottomInset: Dp, onInsertImageClick: () -> Unit, onToggleEmojiClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    HiCappedFontScale {
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
}

@Composable
private fun resolveEditorTitle(mode: FullEditorMode): String = stringResource(
    when (mode) {
        is FullEditorMode.NewThread -> R.string.compose_title_new_thread
        is FullEditorMode.QuotePost -> R.string.compose_title_quote_reply
        else -> R.string.compose_title_reply
    },
)

@Composable
private fun resolveEditorSubtitle(mode: FullEditorMode): String? = when (mode) {
    is FullEditorMode.NewThread -> null
    is FullEditorMode.QuotePost -> when {
        mode.targetFloor != null && !mode.targetAuthor.isNullOrBlank() -> stringResource(R.string.compose_quote_placeholder_floor_user, mode.targetFloor, mode.targetAuthor)
        !mode.targetAuthor.isNullOrBlank() -> stringResource(R.string.compose_reply_placeholder_user, mode.targetAuthor)
        else -> null
    }
    is FullEditorMode.ReplyPost -> mode.targetAuthor?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.compose_reply_placeholder_user, it) }
    is FullEditorMode.ReplyThread -> mode.threadAuthor?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.compose_reply_placeholder_user, it) }
}

@Composable
private fun resolveBodyPlaceholder(mode: FullEditorMode): String =
    resolveEditorSubtitle(mode) ?: stringResource(R.string.compose_field_body_hint)

private val UNSAFE_ERROR_PATTERNS = Regex("""(?:Exception|Throwable|HTTP\s*[45]\d{2}|\[attachimg\])""", RegexOption.IGNORE_CASE)
