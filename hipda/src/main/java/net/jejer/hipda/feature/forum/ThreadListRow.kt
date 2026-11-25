package net.jejer.hipda.feature.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import net.jejer.hipda.R
import net.jejer.hipda.data.threadlist.ThreadListItem
import net.jejer.hipda.feature.common.currentHiImageLoader
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.hiListItemPressIndication

/** 窄中栏紧凑副行排版阈值（product-design.md §4.2：< 300dp 取消独立大头像列）。 */
val FORUM_COMPACT_ROW_WIDTH_THRESHOLD: Dp = 300.dp

/** 深色与 OLED 纯黑下已读标题弱化系数（校准 V-C1：全 20 套预设下兼顾视觉弱化与 WCAG ≥ 4.5:1）。 */
const val THREAD_TITLE_READ_DARK_ALPHA: Float = 0.74f

internal const val THREAD_ROW_SELECTED_ALPHA: Float = 0.36f

fun resolveThreadTitleColor(colorScheme: ColorScheme, isVisited: Boolean, darkTheme: Boolean): Color = when {
    !isVisited -> colorScheme.onSurface
    darkTheme -> colorScheme.onSurfaceVariant.copy(alpha = THREAD_TITLE_READ_DARK_ALPHA)
    else -> colorScheme.onSurfaceVariant
}

fun resolveThreadRowBackground(colorScheme: ColorScheme, selected: Boolean): Color =
    if (selected) colorScheme.secondaryContainer.copy(alpha = THREAD_ROW_SELECTED_ALPHA) else colorScheme.surface

/** 论坛帖子列表项：`< 300dp` 紧凑副行排布、V-C1 已读对比度校准、圆形头像经 `LocalHiImageLoader` 按显示尺寸加载、即时按压状态层。 */
@Composable
fun ThreadListRow(
    item: ThreadListItem,
    selected: Boolean,
    compactLayout: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val darkTheme = HiTheme.state.darkTheme
    val interaction = remember { MutableInteractionSource() }
    val summary = item.summary
    val titleColor = resolveThreadTitleColor(cs, item.isVisited, darkTheme)
    val countText = remember(summary.replyCountText, summary.viewCountText) {
        formatReplyViewText(summary.replyCountText, summary.viewCountText)
    }
    val timeText = summary.timeUpdate?.takeIf(String::isNotBlank) ?: summary.timeCreate

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(resolveThreadRowBackground(cs, selected))
            .clickable(interactionSource = interaction, indication = hiListItemPressIndication(), onClick = onClick)
            .padding(horizontal = if (compactLayout) 12.dp else 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (!compactLayout) ThreadAuthorAvatar(summary.avatarUrl, summary.author, 40.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = summary.title,
                style = if (item.isVisited) HiTheme.typography.threadTitleRead else HiTheme.typography.threadTitle,
                color = titleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary.isSticky || summary.isPoll || summary.withPic || summary.isNew || !summary.typeName.isNullOrBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (summary.isSticky) ThreadBadge(stringResource(R.string.forum_badge_sticky), cs.primaryContainer, cs.onPrimaryContainer)
                    if (summary.isPoll) ThreadBadge(stringResource(R.string.forum_badge_poll), cs.tertiaryContainer, cs.onTertiaryContainer)
                    if (summary.withPic) ThreadBadge(stringResource(R.string.forum_badge_pic), cs.secondaryContainer, cs.onSecondaryContainer)
                    if (summary.isNew) ThreadBadge(stringResource(R.string.forum_badge_new), cs.primaryContainer, cs.onPrimaryContainer)
                    summary.typeName?.takeIf(String::isNotBlank)?.let { ThreadBadge(it, cs.surfaceContainerHigh, cs.onSurfaceVariant) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (compactLayout) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (compactLayout) ThreadAuthorAvatar(summary.avatarUrl, summary.author, 20.dp)
                Text(summary.author, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                if (countText.isNotEmpty()) Text(countText, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
                if (timeText.isNotEmpty()) Text(timeText, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun ThreadBadge(label: String, containerColor: Color, contentColor: Color) {
    Box(Modifier.clip(HiShapeTokens.Small).background(containerColor).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = contentColor, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ThreadAuthorAvatar(avatarUrl: String?, author: String, size: Dp) {
    val cs = MaterialTheme.colorScheme
    val initial = remember(author) { author.trim().firstOrNull()?.uppercase() ?: "?" }
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val context = LocalContext.current
    val imageLoader = currentHiImageLoader()

    Box(Modifier.size(size).clip(HiShapeTokens.Full).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(initial, style = if (size >= 32.dp) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall, color = cs.onSecondaryContainer, maxLines = 1, softWrap = false)
        if (!avatarUrl.isNullOrBlank()) {
            val request = remember(context, avatarUrl, sizePx) { ImageRequest.Builder(context).data(avatarUrl).size(sizePx).build() }
            AsyncImage(model = request, contentDescription = null, imageLoader = imageLoader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun formatReplyViewText(reply: String, view: String): String {
    val r = reply.trim(); val v = view.trim()
    return when {
        r.isNotEmpty() && v.isNotEmpty() -> "$r/$v"
        r.isNotEmpty() -> r
        v.isNotEmpty() -> v
        else -> ""
    }
}
