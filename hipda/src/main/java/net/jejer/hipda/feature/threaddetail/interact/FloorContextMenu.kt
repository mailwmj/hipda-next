package net.jejer.hipda.feature.threaddetail.interact

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.emoji.EmojiMap
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.feedback.hiListItemPressIndication
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.*

private const val MENU_SCRIM_ALPHA = 0.36f
private val MenuSheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

/** 从楼层结构化内容块提取纯文本（零 HTML 标签），专供 [ClipData.newPlainText] 复制。 */
fun extractFloorPlainText(floor: PostFloor): String = buildList {
    for (block in floor.blocks) {
        when (block) {
            is ContentBlock.Text -> buildString {
                for (span in block.spans) {
                    when (span) {
                        is InlineSpan.Plain -> append(span.text)
                        is InlineSpan.Link -> append(span.label)
                        is InlineSpan.Email -> append(span.email)
                        is InlineSpan.Emoji -> append(EmojiMap.codeForImageSrc(span.normalizedKey) ?: "[${span.normalizedKey}]")
                        is InlineSpan.SmallImage -> Unit
                        InlineSpan.LineBreak -> append('\n')
                    }
                }
            }.trim().takeIf { it.isNotEmpty() }?.let(::add)
            is ContentBlock.Quote -> listOfNotNull(block.authorAndTime.trim().takeIf { it.isNotEmpty() }, block.contentText.trim().takeIf { it.isNotEmpty() })
                .joinToString("\n").takeIf { it.isNotEmpty() }?.let(::add)
            is ContentBlock.GoToFloor -> block.label.trim().takeIf { it.isNotEmpty() }?.let(::add)
            is ContentBlock.Attachment -> block.title.trim().takeIf { it.isNotEmpty() }?.let(::add)
            is ContentBlock.Notice -> block.message.trim().takeIf { it.isNotEmpty() }?.let(::add)
            is ContentBlock.Image -> Unit
        }
    }
}.joinToString("\n")

/** 将楼层正文严格以 `ClipData.newPlainText` 写入系统剪贴板（闭合 `R-E2a R2-S2`）。 */
fun copyFloorPlainTextToClipboard(context: Context, label: String, floor: PostFloor): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    return runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(label, extractFloorPlainText(floor))) }.isSuccess
}

/**
 * 帖子楼层长按菜单浮层（`product-design.md` §7.3、§11，`copy.md`）：
 * 固定提供 `引用回复`/`回复`/`只看此人`/`复制`；仅本人且已登录时显示 `编辑`/`删除`（不提供多余负向入口）；未登录点击回复原地引导登录。
 */
@Composable
fun FloorContextMenuOverlay(
    floor: PostFloor?,
    isLoggedIn: Boolean,
    currentUserUid: String?,
    onQuoteReply: (PostFloor) -> Unit,
    onReply: (PostFloor) -> Unit,
    onFilterByUser: (PostFloor) -> Unit,
    onCopy: (PostFloor) -> Unit,
    onOpenLogin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (PostFloor) -> Unit = {},
    onDelete: (PostFloor) -> Unit = {},
) {
    val visible = floor != null
    val displayedFloor = remember { mutableStateOf<PostFloor?>(null) }.apply { if (floor != null) value = floor }.value
    var showLoginPrompt by remember { mutableStateOf(false) }

    LaunchedEffect(floor?.postId, isLoggedIn) { if (floor == null || isLoggedIn) showLoginPrompt = false }

    BackHandler(enabled = visible, onBack = onDismiss)
    val transition = updateTransition(targetState = visible, label = "FloorContextMenu")
    if (!visible && !transition.currentState && !transition.isRunning) return

    Box(modifier = modifier.fillMaxSize()) {
        transition.AnimatedVisibility(
            visible = { it },
            enter = hiFadeIn(HiMotion.effectsSlow()),
            exit = hiFadeOut(HiMotion.exit(HiMotion.effectsSlow())),
        ) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = MENU_SCRIM_ALPHA))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }

        transition.AnimatedVisibility(
            visible = { it },
            modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 520.dp).fillMaxWidth(),
            enter = hiSlideInVertically(HiMotion.spatialDefault()) { it },
            exit = hiSlideOutVertically(HiMotion.exit(HiMotion.spatialDefault())) { it },
        ) {
            val activeFloor = displayedFloor ?: return@AnimatedVisibility
            val cs = MaterialTheme.colorScheme
            val title = stringResource(R.string.thread_detail_floor_title, activeFloor.floor, activeFloor.author)
            val isOwnFloor = isLoggedIn && !currentUserUid.isNullOrBlank() && currentUserUid == activeFloor.uid

            Surface(
                modifier = Modifier.fillMaxWidth().semantics { paneTitle = title; isTraversalGroup = true },
                shape = MenuSheetShape,
                color = cs.surfaceContainerHigh,
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
                    Text(
                        text = title, style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                    HorizontalDivider(color = cs.outlineVariant)

                    FloorMenuActionRow(stringResource(R.string.thread_detail_menu_quote_reply)) {
                        if (isLoggedIn) { onDismiss(); onQuoteReply(activeFloor) } else showLoginPrompt = true
                    }
                    FloorMenuActionRow(stringResource(R.string.thread_detail_menu_reply)) {
                        if (isLoggedIn) { onDismiss(); onReply(activeFloor) } else showLoginPrompt = true
                    }
                    if (activeFloor.uid.isNotBlank()) {
                        FloorMenuActionRow(stringResource(R.string.thread_detail_menu_user_only)) { onDismiss(); onFilterByUser(activeFloor) }
                    }
                    FloorMenuActionRow(stringResource(R.string.thread_detail_menu_copy)) { onDismiss(); onCopy(activeFloor) }
                    if (isOwnFloor) {
                        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))
                        FloorMenuActionRow(stringResource(R.string.thread_detail_menu_edit)) { onDismiss(); onEdit(activeFloor) }
                        FloorMenuActionRow(stringResource(R.string.thread_detail_menu_delete), isDestructive = true) { onDismiss(); onDelete(activeFloor) }
                    }

                    AnimatedVisibility(
                        visible = showLoginPrompt && !isLoggedIn,
                        enter = hiExpandVertically(expandFrom = Alignment.Top) + hiFadeIn(),
                        exit = hiShrinkVertically(shrinkTowards = Alignment.Top) + hiFadeOut(),
                    ) {
                        val loginInteraction = remember { MutableInteractionSource() }
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = HiShapeTokens.Small, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.thread_detail_login_to_operate), style = MaterialTheme.typography.bodyMedium, color = cs.onSecondaryContainer, modifier = Modifier.weight(1f).padding(end = 12.dp))
                                FilledTonalButton(
                                    onClick = { onDismiss(); onOpenLogin() },
                                    interactionSource = loginInteraction,
                                    shape = HiShapeTokens.Small,
                                    modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 48.dp).hiPressScale(loginInteraction),
                                ) { Text(stringResource(R.string.common_action_login)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloorMenuActionRow(label: String, isDestructive: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(remember { MutableInteractionSource() }, hiListItemPressIndication(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (isDestructive) cs.error else cs.onSurface)
    }
}
