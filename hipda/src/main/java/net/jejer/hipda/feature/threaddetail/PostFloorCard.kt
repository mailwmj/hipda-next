package net.jejer.hipda.feature.threaddetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.jejer.hipda.R
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.currentHiImageLoader
import net.jejer.hipda.feature.threaddetail.body.PostBodyRenderer
import net.jejer.hipda.feature.threaddetail.interact.floorHighlight
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadPoll
import net.jejer.hipda.ui.designsystem.HiShapeTokens

internal val PostMaxContentWidth = 680.dp

/**
 * 帖子单楼层卡片（最大宽 `680dp` 居中；闭合 `R-E2a R2-S2` 将作者区热区扩至「头像+作者名/时间」≥ 48dp 并合并完整用户名语义；
 * 接入跳楼高亮、长按菜单触感与 1 楼投票插槽）。
 */
@Composable
internal fun PostFloorCard(
    item: ThreadDetailFloorItem,
    isOp: Boolean,
    listState: LazyListState,
    onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit,
    onAuthorClick: (uid: String, username: String) -> Unit,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null,
    onJumpToFloor: ((targetPid: String, targetTid: String?) -> Unit)? = null,
    onLongPress: ((PostFloor) -> Unit)? = null,
    onWarningClick: ((PostFloor) -> Unit)? = null,
    highlightToken: Long? = null,
    onHighlightFinished: (Long) -> Unit = {},
    pollSlot: (@Composable (ThreadPoll) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val floor = item.floor
    val haptic = LocalHapticFeedback.current
    val latestFloor by rememberUpdatedState(floor)
    val latestOnLongPress by rememberUpdatedState(onLongPress)
    val longClickModifier = if (onLongPress != null && !item.isAuthorBlocked) {
        val longClickLabel = stringResource(R.string.thread_detail_floor_menu_action)
        Modifier
            .pointerInput(floor.postId) {
                detectTapGestures(
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        latestOnLongPress?.invoke(latestFloor)
                    },
                )
            }
            .semantics {
                onLongClick(label = longClickLabel) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    latestOnLongPress?.invoke(latestFloor)
                    true
                }
            }
    } else {
        Modifier
    }

    val opBadge = stringResource(R.string.thread_detail_badge_op)
    val authorAccessibilityLabel = if (isOp) "${floor.author}，$opBadge，${floor.timePost}" else "${floor.author}，${floor.timePost}"
    val authorClickModifier = if (floor.uid.isNotBlank()) {
        Modifier
            .clip(HiShapeTokens.Small)
            .semantics(mergeDescendants = true) { contentDescription = authorAccessibilityLabel }
            .clickable(role = Role.Button) { onAuthorClick(floor.uid, floor.author) }
    } else {
        Modifier.semantics(mergeDescendants = true) { contentDescription = authorAccessibilityLabel }
    }

    Column(
        modifier = modifier
            .widthIn(max = PostMaxContentWidth)
            .fillMaxWidth()
            .floorHighlight(highlightToken = highlightToken, onHighlightFinished = onHighlightFinished)
            .then(longClickModifier)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .then(authorClickModifier)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FloorAvatar(
                    author = floor.author,
                    avatarUrl = if (item.isAuthorBlocked) null else floor.avatarUrl,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(floor.author, style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (isOp) {
                            Surface(shape = HiShapeTokens.Small, color = cs.primaryContainer, contentColor = cs.onPrimaryContainer) {
                                Text(opBadge, style = MaterialTheme.typography.labelSmall, color = cs.onPrimaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                            }
                        }
                    }
                    Text(floor.timePost, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (floor.isWarned && !item.isAuthorBlocked) {
                WarnedFloorBadge(
                    floorNumber = floor.floor,
                    clickable = onWarningClick != null && floor.uid.isNotBlank(),
                    onClick = { onWarningClick?.invoke(floor) },
                )
            } else {
                Text(stringResource(R.string.common_floor_badge, floor.floor), style = MaterialTheme.typography.labelMedium, color = cs.primary, maxLines = 1)
            }
        }

        Spacer(Modifier.height(4.dp))

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (item.isAuthorBlocked) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = HiShapeTokens.Small, color = cs.surfaceContainerLow) {
                    Text(stringResource(R.string.common_floor_blocked, floor.author), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
                }
            } else {
                PostBodyRenderer(
                    blocks = floor.blocks,
                    floorPostId = floor.postId,
                    listState = listState,
                    onOpenImageViewer = onOpenImageViewer,
                    onLinkClick = onLinkClick,
                    onJumpToFloor = onJumpToFloor,
                )
                if (floor.floor == 1 && floor.poll != null && pollSlot != null) {
                    pollSlot(floor.poll!!)
                }
                floor.postStatus?.trim()?.takeIf { it.isNotEmpty() }?.let { status ->
                    Text(status, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WarnedFloorBadge(floorNumber: Int, clickable: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val description = stringResource(R.string.thread_detail_warning_badge_description, floorNumber)
    val clickMod = if (clickable) {
        Modifier.clickable(remember { MutableInteractionSource() }, LocalIndication.current, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Box(
        modifier = Modifier.defaultMinSize(48.dp, 48.dp).clip(HiShapeTokens.Small)
            .semantics(mergeDescendants = true) { contentDescription = description }.then(clickMod),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Surface(shape = HiShapeTokens.Small, color = cs.errorContainer, contentColor = cs.onErrorContainer, border = BorderStroke(0.5.dp, cs.error.copy(alpha = 0.55f))) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = cs.onErrorContainer, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.common_floor_badge, floorNumber), style = MaterialTheme.typography.labelMedium, color = cs.onErrorContainer, maxLines = 1)
            }
        }
    }
}

@Composable
private fun FloorAvatar(author: String, avatarUrl: String?) {
    val cs = MaterialTheme.colorScheme
    val initial = author.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(HiShapeTokens.Full)
            .background(cs.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics {},
        )
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(model = avatarUrl, contentDescription = null, imageLoader = currentHiImageLoader(), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
