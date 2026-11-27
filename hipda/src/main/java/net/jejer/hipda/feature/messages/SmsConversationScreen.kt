package net.jejer.hipda.feature.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.emoji.EmojiMap
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.feature.compose.ComposeVectorIcons
import net.jejer.hipda.feature.compose.QuickReplyEmojiPanelHost
import net.jejer.hipda.feature.compose.emoji.EmojiPanelSlotBox
import net.jejer.hipda.feature.compose.emoji.previousEmojiOrGraphemeOffset
import net.jejer.hipda.feature.compose.emoji.rememberEmojiPanelController
import net.jejer.hipda.feature.compose.rememberResolvedEmojiPanelSlot
import net.jejer.hipda.feature.threaddetail.body.InlineDrawableSpec
import net.jejer.hipda.feature.threaddetail.body.ResolvedForumEmoji
import net.jejer.hipda.feature.threaddetail.body.buildInlineTextContentMap
import net.jejer.hipda.feature.threaddetail.body.resolveForumEmoji
import net.jejer.hipda.ui.designsystem.*
import net.jejer.hipda.ui.designsystem.feedback.*
import net.jejer.hipda.ui.designsystem.motion.*
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner

private const val SMS_EMOJI_SCALE: Float = 1.4f
private val BUBBLE_MAX_WIDTH = 296.dp

/** 短消息对话页（纯色顶栏 + 气泡列表 + 纯色输入条，零毛玻璃）：乐观发送、失败重发/锚点撤销、15 秒冷却、表情与安全链接。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsConversationScreen(
    args: SmsConversationArgs, showBack: Boolean, onBack: () -> Unit,
    onOpenUserProfile: (uid: String, username: String) -> Unit, onOpenLogin: () -> Unit,
    contentPadding: PaddingValues, viewModel: SmsConversationViewModel, modifier: Modifier = Modifier,
    emojiPanelSlot: (@Composable (QuickReplyEmojiPanelHost) -> Unit)? = null,
) {
    LaunchedEffect(args.uid, args.username) { viewModel.bindConversation(args) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val effectiveEmojiSlot = rememberResolvedEmojiPanelSlot(emojiPanelSlot)
    val hasEmojiSlot = effectiveEmojiSlot != null
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarController = LocalPaneSnackbarController.current
    val resources = LocalResources.current
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val inputFocusRequester = remember { FocusRequester() }
    val uriHandler = LocalUriHandler.current
    val onSafeLinkClick: (String) -> Unit = remember(uriHandler) { { rawUrl -> sanitizeMessageLinkUrl(rawUrl)?.let { runCatching { uriHandler.openUri(it) } } } }

    var inputFieldValue by remember(args.uid, args.username) {
        mutableStateOf(TextFieldValue(uiState.inputText, TextRange(uiState.inputText.length)))
    }
    LaunchedEffect(uiState.inputText) {
        if (uiState.inputText != inputFieldValue.text) {
            inputFieldValue = TextFieldValue(uiState.inputText, TextRange(uiState.inputText.length))
        }
    }

    var activeSnackbarJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(uiState.scrollToBottomEventId) {
        if (uiState.scrollToBottomEventId > 0L && uiState.messages.isNotEmpty()) listState.scrollToItem(uiState.messages.lastIndex)
    }
    LaunchedEffect(viewModel, snackbarController, resources) {
        viewModel.events.collectLatest { event ->
            activeSnackbarJob?.cancel()
            activeSnackbarJob = null
            when (event) {
                is SmsConversationEvent.CooldownBlocked ->
                    snackbarController.show(resources.getString(R.string.messages_error_cooldown, event.remainingSeconds), PaneSnackbarTarget.DetailPane)
                is SmsConversationEvent.ShowError ->
                    snackbarController.showMessageUiError(event.error, resources, PaneSnackbarTarget.DetailPane, viewModel::refresh, onOpenLogin)
            }
        }
    }

    val isScrolled by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    var showClearDialog by remember { mutableStateOf(false) }
    val layoutDir = LocalLayoutDirection.current
    val peerName = uiState.peerUsername.ifBlank { args.username }
    val cs = MaterialTheme.colorScheme
    val canShowInputBar = uiState.error?.action != UiErrorAction.Login

    BoxWithConstraints(modifier.fillMaxSize().background(cs.surface)) {
        val windowHeightDp = maxHeight
        val emojiController = rememberEmojiPanelController(
            windowHeightDp = windowHeightDp,
            onShowKeyboard = { runCatching { inputFocusRequester.requestFocus() }; keyboardController?.show() },
            onHideKeyboard = { keyboardController?.hide() },
        )
        LaunchedEffect(args.uid, args.username, canShowInputBar) { emojiController.close() }
        val isEmojiPanelOpen = emojiController.isEmojiModeActive && hasEmojiSlot && canShowInputBar

        Column(Modifier.fillMaxSize()) {
            var menuExpanded by remember { mutableStateOf(false) }
            MessagesSolidTopBarFrame(
                title = peerName, showBack = showBack, onBack = onBack, isScrolled = isScrolled,
                onTitleClick = if (args.uid.isNotBlank()) ({ onOpenUserProfile(args.uid, peerName) }) else null,
                actions = {
                    if (args.uid.isNotBlank() && !uiState.isClearing) {
                        Box {
                            val moreInteraction = remember { MutableInteractionSource() }
                            IconButton(onClick = { menuExpanded = true }, interactionSource = moreInteraction, modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(moreInteraction)) {
                                Icon(Icons.Rounded.MoreVert, stringResource(R.string.messages_action_more), tint = cs.onSurface)
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, modifier = Modifier.background(cs.surfaceContainerHigh)) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.messages_action_clear), color = cs.error) }, onClick = { menuExpanded = false; showClearDialog = true })
                            }
                        }
                    }
                },
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                MessagePaneStateBox(
                    uiState.messages.isEmpty(), uiState.isLoading, uiState.hasLoadedOnce, uiState.error, uiState.challengeState, uiState.challengeFailure,
                    stringResource(R.string.common_empty_sms), PaddingValues(0.dp), viewModel::refresh, onOpenLogin,
                    skeleton = { MessagesListSkeleton(compact = false, contentPadding = PaddingValues(0.dp)) },
                ) {
                    PullToRefreshBox(isRefreshing = uiState.isRefreshing, onRefresh = { viewModel.refresh() }, modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState, modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp + contentPadding.calculateStartPadding(layoutDir), 12.dp, 12.dp + contentPadding.calculateEndPadding(layoutDir), 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(uiState.messages, key = { it.id }) { bubble ->
                                SmsBubbleRow(
                                    item = bubble,
                                    onLinkClick = onSafeLinkClick,
                                    onCopy = {
                                        val copiedLabel = resources.getString(R.string.messages_action_copy)
                                        if (copySmsBubblePlainTextToClipboard(context, copiedLabel, bubble.rawMessage ?: bubble.contentHtml, isPlainText = bubble.rawMessage != null)) {
                                            activeSnackbarJob?.cancel()
                                            activeSnackbarJob = coroutineScope.launch {
                                                snackbarController.show(resources.getString(R.string.messages_snackbar_copied), PaneSnackbarTarget.DetailPane)
                                            }
                                        }
                                    },
                                    onResend = { viewModel.resendMessage(bubble.id) },
                                    onDelete = {
                                        val token = viewModel.deleteFailedMessage(bubble.id) ?: return@SmsBubbleRow
                                        activeSnackbarJob?.cancel()
                                        activeSnackbarJob = coroutineScope.launch {
                                            snackbarController.show(resources.getString(R.string.messages_snackbar_deleted), PaneSnackbarTarget.DetailPane, resources.getString(R.string.feedback_action_undo)) {
                                                viewModel.restoreDeletedMessage(token)
                                            }
                                        }
                                    },
                                    modifier = Modifier.animateItem(HiMotion.effectsDefault(), HiMotion.spatialDefault(), HiMotion.exit(HiMotion.effectsDefault())),
                                )
                            }
                        }
                    }
                }
            }

            if (canShowInputBar) {
                val inputBarBottomPadding = if (isEmojiPanelOpen) 0.dp else (contentPadding.calculateBottomPadding() - emojiController.bottomSlotHeightDp).coerceAtLeast(0.dp)
                SmsSolidInputBar(
                    inputValue = inputFieldValue,
                    cooldownSeconds = uiState.cooldownSeconds,
                    canSend = uiState.canSend,
                    bottomPadding = inputBarBottomPadding,
                    hasEmojiSlot = hasEmojiSlot,
                    isEmojiPanelOpen = isEmojiPanelOpen,
                    focusRequester = inputFocusRequester,
                    onInputChange = { next ->
                        inputFieldValue = next
                        if (next.text != uiState.inputText) viewModel.onInputChange(next.text)
                    },
                    onInputPress = { if (emojiController.isEmojiModeActive) emojiController.switchToKeyboard() },
                    onToggleEmoji = { if (hasEmojiSlot) emojiController.toggle() },
                    onSend = viewModel::sendMessage,
                )
                val emojiHost = remember(emojiController.panelContentHeightDp, windowHeightDp, viewModel, emojiController) {
                    QuickReplyEmojiPanelHost(
                        keyboardHeightDp = emojiController.panelContentHeightDp,
                        windowHeightDp = windowHeightDp,
                        onInsertEmoji = { code ->
                            val cur = inputFieldValue
                            val s = cur.selection.min.coerceIn(0, cur.text.length)
                            val e = cur.selection.max.coerceIn(0, cur.text.length)
                            val nextText = cur.text.substring(0, s) + code + cur.text.substring(e)
                            inputFieldValue = TextFieldValue(nextText, TextRange(s + code.length))
                            viewModel.onInputChange(nextText)
                        },
                        onBackspace = {
                            val cur = inputFieldValue
                            val s = cur.selection.min.coerceIn(0, cur.text.length)
                            val e = cur.selection.max.coerceIn(0, cur.text.length)
                            val next = when {
                                s != e -> TextFieldValue(cur.text.substring(0, s) + cur.text.substring(e), TextRange(s))
                                s > 0 -> previousEmojiOrGraphemeOffset(cur.text, s).let { prev ->
                                    TextFieldValue(cur.text.substring(0, prev) + cur.text.substring(s), TextRange(prev))
                                }
                                else -> cur
                            }
                            if (next != cur) {
                                inputFieldValue = next
                                viewModel.onInputChange(next.text)
                            }
                        },
                        onClose = { emojiController.close() },
                    )
                }
                EmojiPanelSlotBox(
                    controller = emojiController,
                    emojiPanelSlot = effectiveEmojiSlot,
                    host = emojiHost,
                    registerBackHandler = hasEmojiSlot,
                )
            }
        }
    }

    if (showClearDialog) ClearConversationConfirmDialog(peerName, { showClearDialog = false }) { showClearDialog = false; activeSnackbarJob?.cancel(); viewModel.clearConversation() }
}

/** 提取短消息气泡纯文本（剥离 HTML 标签并将论坛表情转回规范代码），专供 [ClipData.newPlainText] 复制。 */
fun extractSmsBubblePlainText(content: String, isPlainText: Boolean): String {
    if (content.isBlank()) return ""
    val parsed = if (isPlainText) parseSmsBubblePlainText(content) else parseSmsBubbleContent(content)
    return parsed.segments.filterIsInstance<SmsBubbleSegment.Text>().mapNotNull { seg ->
        buildString {
            for (part in seg.parts) when (part) {
                is SmsTextPart.Plain -> append(part.text)
                is SmsTextPart.Link -> append(part.label)
                is SmsTextPart.Emoji -> append(part.code ?: part.imageSrc?.let(EmojiMap::codeForImageSrc) ?: part.imageSrc?.let { "[$it]" }.orEmpty())
                SmsTextPart.LineBreak -> append('\n')
            }
        }.trim().takeIf { it.isNotEmpty() }
    }.joinToString("\n")
}

/** 将短消息气泡纯文本严格以 `ClipData.newPlainText` 写入系统剪贴板；内容为空时安全返回 `false`。 */
fun copySmsBubblePlainTextToClipboard(context: Context, label: String, content: String, isPlainText: Boolean): Boolean {
    val plainText = extractSmsBubblePlainText(content, isPlainText).ifEmpty { return false }
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    return runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(label, plainText)) }.isSuccess
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmsBubbleRow(item: SmsBubbleUiItem, onLinkClick: (String) -> Unit, onCopy: () -> Unit, onResend: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val bubbleBg = if (item.isMine) cs.primaryContainer else cs.surfaceContainerHigh
    val bubbleFg = if (item.isMine) cs.onPrimaryContainer else cs.onSurface
    var copyMenuExpanded by remember(item.id) { mutableStateOf(false) }
    val bubbleInteraction = remember { MutableInteractionSource() }
    val copyLabel = stringResource(R.string.messages_action_copy)
    val rawContent = item.rawMessage ?: item.contentHtml
    val isPlainText = item.rawMessage != null
    val canCopy = remember(rawContent, isPlainText) { extractSmsBubblePlainText(rawContent, isPlainText).isNotEmpty() }

    Row(modifier.fillMaxWidth(), horizontalArrangement = if (item.isMine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!item.isMine) MessageAvatar(item.avatarUrl, item.author, 32.dp, Modifier.padding(end = 8.dp))
        else SmsDeliveryStatusSlot(item.deliveryStatus, onResend, onDelete)
        Column(horizontalAlignment = if (item.isMine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                Surface(shape = HiShapeTokens.Medium, color = bubbleBg, contentColor = bubbleFg, modifier = Modifier.widthIn(max = BUBBLE_MAX_WIDTH)) {
                    val bodyModifier = if (canCopy) {
                        Modifier.combinedClickable(
                            interactionSource = bubbleInteraction, indication = hiListItemPressIndication(), onClick = {}, onLongClickLabel = copyLabel, hapticFeedbackEnabled = false,
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); copyMenuExpanded = true },
                        ).padding(horizontal = 12.dp, vertical = 9.dp)
                    } else {
                        Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                    }
                    SmsBubbleBody(rawContent, bubbleFg, cs.primary, onLinkClick, bodyModifier, isPlainText = isPlainText)
                }
                if (canCopy) {
                    DropdownMenu(expanded = copyMenuExpanded, onDismissRequest = { copyMenuExpanded = false }, modifier = Modifier.background(cs.surfaceContainerHigh)) {
                        DropdownMenuItem(text = { Text(copyLabel) }, onClick = { copyMenuExpanded = false; onCopy() })
                    }
                }
            }
            if (item.time.isNotBlank()) Text(item.time, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun SmsDeliveryStatusSlot(status: SmsBubbleDeliveryStatus, onResend: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    when (status) {
        SmsBubbleDeliveryStatus.Delivered -> Unit
        SmsBubbleDeliveryStatus.Sending -> {
            val cd = stringResource(R.string.messages_status_sending)
            Box(Modifier.padding(end = 6.dp).size(24.dp).semantics { contentDescription = cd }, Alignment.Center) { HiLoadingSpinner(Modifier.size(16.dp), strokeWidth = 2.dp) }
        }
        SmsBubbleDeliveryStatus.Failed -> {
            var menuExpanded by remember { mutableStateOf(false) }
            val cd = stringResource(R.string.messages_status_send_failed)
            val interaction = remember { MutableInteractionSource() }
            Box(Modifier.padding(end = 2.dp)) {
                IconButton(onClick = { menuExpanded = true }, interactionSource = interaction, modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(interaction).semantics { contentDescription = cd }) {
                    Box(Modifier.size(22.dp).clip(HiShapeTokens.Full).background(cs.error), Alignment.Center) {
                        Text("!", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = cs.onError, modifier = Modifier.clearAndSetSemantics {})
                    }
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, modifier = Modifier.background(cs.surfaceContainerHigh)) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.messages_action_resend)) }, onClick = { menuExpanded = false; onResend() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.messages_action_delete), color = cs.error) }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
    }
}

@Composable
internal fun SmsBubbleBody(
    contentHtml: String, textColor: Color, linkColor: Color, onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier, isPlainText: Boolean = false,
) {
    val parsed = remember(contentHtml, isPlainText) { if (isPlainText) parseSmsBubblePlainText(contentHtml) else parseSmsBubbleContent(contentHtml) }
    val darkTheme = HiTheme.state.darkTheme
    val bodyStyle = HiTheme.typography.postBody
    val context = LocalContext.current
    val imageLoader = currentHiImageLoader()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (segment in parsed.segments) when (segment) {
            is SmsBubbleSegment.Text -> {
                val built = remember(segment.parts, linkColor, darkTheme, onLinkClick) { buildSmsAnnotatedText(segment.parts, linkColor, darkTheme, onLinkClick) }
                val inlineContent = remember(built.inlineDrawables, bodyStyle.fontSize) { buildInlineTextContentMap(built.inlineDrawables, bodyStyle.fontSize) }
                if (built.annotatedString.isNotEmpty()) Text(built.annotatedString, inlineContent = inlineContent, style = bodyStyle, color = textColor)
            }
            is SmsBubbleSegment.Image -> sanitizeMessageImageUrl(segment.safeUrl)?.let { safeUrl ->
                AsyncImage(remember(context, safeUrl) { ImageRequest.Builder(context).data(safeUrl).build() }, null, imageLoader, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().heightIn(64.dp, 220.dp).clip(HiShapeTokens.Small))
            }
        }
    }
}

@Immutable
internal data class BuiltSmsText(val annotatedString: AnnotatedString, val inlineDrawables: Map<String, InlineDrawableSpec>)

internal fun buildSmsAnnotatedText(parts: List<SmsTextPart>, linkColor: Color, darkTheme: Boolean, onLinkClick: (String) -> Unit): BuiltSmsText {
    val drawables = LinkedHashMap<String, InlineDrawableSpec>()
    val annotated = buildAnnotatedString {
        for (part in parts) when (part) {
            is SmsTextPart.Plain -> append(part.text)
            is SmsTextPart.Link -> {
                val safeUrl = sanitizeMessageLinkUrl(part.safeUrl)
                if (safeUrl == null) append(part.label)
                else withLink(LinkAnnotation.Url(safeUrl, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) { onLinkClick(safeUrl) }) { append(part.label) }
            }
            is SmsTextPart.Emoji -> {
                val resolved = part.imageSrc?.let { resolveForumEmoji(it, darkTheme) }
                val resId = (resolved as? ResolvedForumEmoji.Drawable)?.drawableResId ?: part.code?.let { EmojiMap.drawableFor(it, darkTheme) }
                val desc = (resolved as? ResolvedForumEmoji.Drawable)?.code ?: part.code ?: (resolved as? ResolvedForumEmoji.FallbackText)?.text ?: "[表情]"
                if (resId != null) {
                    val id = "sms_emoji:${part.imageSrc ?: part.code}"
                    drawables.putIfAbsent(id, InlineDrawableSpec(resId, desc, SMS_EMOJI_SCALE))
                    appendInlineContent(id, desc)
                } else append(desc)
            }
            SmsTextPart.LineBreak -> append('\n')
        }
    }
    return BuiltSmsText(annotated, drawables)
}

@Composable
private fun SmsSolidInputBar(
    inputValue: TextFieldValue, cooldownSeconds: Int, canSend: Boolean, bottomPadding: Dp,
    hasEmojiSlot: Boolean, isEmojiPanelOpen: Boolean, focusRequester: FocusRequester,
    onInputChange: (TextFieldValue) -> Unit, onInputPress: () -> Unit, onToggleEmoji: () -> Unit, onSend: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val inCooldown = cooldownSeconds > 0
    val sendCd = if (inCooldown) stringResource(R.string.messages_cooldown_cd, cooldownSeconds) else stringResource(R.string.messages_action_send)
    val inputInteraction = remember { MutableInteractionSource() }
    val latestOnInputPress by rememberUpdatedState(onInputPress)
    LaunchedEffect(inputInteraction) {
        inputInteraction.interactions.collect { if (it is PressInteraction.Press) latestOnInputPress() }
    }

    Column(Modifier.fillMaxWidth().background(cs.surfaceContainerLow).padding(bottom = bottomPadding)) {
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
        Row(
            Modifier.fillMaxWidth().padding(start = if (hasEmojiSlot) 4.dp else 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (hasEmojiSlot) 4.dp else 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (hasEmojiSlot) {
                val emojiCd = stringResource(if (isEmojiPanelOpen) R.string.compose_action_keyboard else R.string.compose_action_emoji)
                val emojiInteraction = remember { MutableInteractionSource() }
                IconButton(
                    onClick = onToggleEmoji, interactionSource = emojiInteraction,
                    modifier = Modifier.padding(bottom = 4.dp).defaultMinSize(48.dp, 48.dp).hiPressScale(emojiInteraction),
                ) {
                    HiIconSwitch(if (isEmojiPanelOpen) ComposeVectorIcons.Keyboard else Icons.Rounded.Face, emojiCd, tint = cs.onSurface, iconSize = 22.dp)
                }
            }
            OutlinedTextField(
                value = inputValue, onValueChange = onInputChange,
                placeholder = { Text(stringResource(R.string.messages_input_hint), maxLines = 1) },
                maxLines = 4, shape = HiShapeTokens.Large, interactionSource = inputInteraction,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
            )
            val sendInteraction = remember { MutableInteractionSource() }
            FilledIconButton(
                onClick = onSend, enabled = canSend, interactionSource = sendInteraction,
                modifier = Modifier.padding(bottom = 4.dp).defaultMinSize(48.dp, 48.dp).hiPressScale(sendInteraction).semantics { contentDescription = sendCd },
            ) {
                AnimatedContent(
                    targetState = inCooldown, label = "SmsSendCooldownButton",
                    transitionSpec = { ((hiScaleIn(HiMotion.spatialFast()) + hiFadeIn(HiMotion.effectsFast())) togetherWith (hiScaleOut(HiMotion.exit(HiMotion.spatialFast())) + hiFadeOut(HiMotion.exit(HiMotion.effectsFast())))).using(null) },
                ) { cooling ->
                    if (cooling) HiRollingNumber(cooldownSeconds.coerceAtLeast(1), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    else Icon(Icons.AutoMirrored.Rounded.Send, null, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
