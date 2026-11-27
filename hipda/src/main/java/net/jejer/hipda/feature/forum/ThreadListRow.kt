package net.jejer.hipda.feature.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.time.LocalDate
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
private const val STICKY_PIN_INLINE_ID: String = "sticky_pin"

private val StickyPinVectorIcon: ImageVector by lazy {
    ImageVector.Builder("ThreadList.StickyPin", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(14.5f, 3.5f); lineTo(20.5f, 9.5f); lineTo(19f, 11f); lineTo(18f, 10f)
            lineTo(14.5f, 13.5f); lineTo(16.5f, 15.5f); lineTo(15f, 17f); lineTo(11.8f, 13.8f)
            lineTo(4.5f, 19.5f); lineTo(10.2f, 12.2f); lineTo(7f, 9f); lineTo(8.5f, 7.5f)
            lineTo(10.5f, 9.5f); lineTo(14f, 6f); lineTo(13f, 5f); close()
        }
    }.build()
}

fun resolveThreadTitleColor(colorScheme: ColorScheme, isVisited: Boolean, darkTheme: Boolean): Color = when {
    !isVisited -> colorScheme.onSurface
    darkTheme -> colorScheme.onSurfaceVariant.copy(alpha = THREAD_TITLE_READ_DARK_ALPHA)
    else -> colorScheme.onSurfaceVariant
}

fun resolveThreadRowBackground(colorScheme: ColorScheme, selected: Boolean): Color =
    if (selected) colorScheme.secondaryContainer.copy(alpha = THREAD_ROW_SELECTED_ALPHA) else colorScheme.surface

/** 论坛帖子列表项：去色块噪音、内联置顶图钉与「新」溢出兜底、作者全宽不截断、精简绝对时间与内缩圆角选中底。 */
@Composable
fun ThreadListRow(
    item: ThreadListItem,
    selected: Boolean,
    compactLayout: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    referenceDate: LocalDate? = null,
) {
    val cs = MaterialTheme.colorScheme
    val darkTheme = HiTheme.state.darkTheme
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val longClickLabel = if (onLongClick != null) stringResource(R.string.common_action_open_last_page) else null
    val stickyLabel = stringResource(R.string.forum_badge_sticky)
    val pollLabel = stringResource(R.string.forum_badge_poll)
    val newLabel = stringResource(R.string.forum_badge_new)
    val yesterdayLabel = stringResource(R.string.forum_time_yesterday)
    val summary = item.summary
    val titleColor = resolveThreadTitleColor(cs, item.isVisited, darkTheme)
    val titleStyle = if (item.isVisited) HiTheme.typography.threadTitleRead else HiTheme.typography.threadTitle
    val bodySmall = MaterialTheme.typography.bodySmall
    val newLabelStyle = MaterialTheme.typography.labelSmall
    val newSpanStyle = remember(newLabelStyle, cs.primary) { newLabelStyle.toSpanStyle().copy(color = cs.primary) }
    val metaNumericStyle = remember(bodySmall) { bodySmall.copy(fontFeatureSettings = "tnum") }

    var lastMeasuredWidthPx by remember(item.tid, summary.title, summary.isSticky, summary.isNew, titleStyle) { mutableIntStateOf(-1) }
    var newBadgeOverflowed by remember(item.tid, summary.title, summary.isSticky, summary.isNew, titleStyle) { mutableStateOf(false) }
    val showNewInline = summary.isNew && !newBadgeOverflowed
    val showNewInMeta = summary.isNew && newBadgeOverflowed

    val inlineContent = remember(summary.isSticky, stickyLabel, cs.onSurfaceVariant) {
        if (!summary.isSticky) emptyMap()
        else mapOf(
            STICKY_PIN_INLINE_ID to InlineTextContent(Placeholder(14.sp, 14.sp, PlaceholderVerticalAlign.TextCenter)) {
                Icon(StickyPinVectorIcon, contentDescription = stickyLabel, tint = cs.onSurfaceVariant, modifier = Modifier.fillMaxSize())
            },
        )
    }
    val titleAnnotated = remember(summary.title, summary.isSticky, showNewInline, newLabel, newSpanStyle) {
        buildAnnotatedString {
            if (summary.isSticky) { appendInlineContent(STICKY_PIN_INLINE_ID, " "); append(" ") }
            append(summary.title)
            if (showNewInline) { append("  "); withStyle(newSpanStyle) { append(newLabel) } }
        }
    }
    val authorMetaText = remember(summary.isPoll, summary.typeName, summary.author, pollLabel) {
        buildList(3) {
            if (summary.isPoll) add(pollLabel)
            summary.typeName?.trim()?.takeIf { it.isNotEmpty() && (!summary.isPoll || it != pollLabel) }?.let(::add)
            summary.author.trim().takeIf(String::isNotEmpty)?.let(::add)
        }.joinToString(" · ")
    }
    val countText = remember(summary.replyCountText, summary.viewCountText, compactLayout) {
        formatThreadCountText(summary.replyCountText, summary.viewCountText, compactLayout)
    }
    val rawTimeText = summary.timeUpdate?.takeIf(String::isNotBlank) ?: summary.timeCreate
    val timeText = remember(rawTimeText, referenceDate, yesterdayLabel) {
        if (referenceDate != null) formatThreadTime(rawTimeText, referenceDate, yesterdayLabel) else rawTimeText.trim()
    }

    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(HiShapeTokens.Medium)
                .background(if (selected) resolveThreadRowBackground(cs, true) else Color.Transparent)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = hiListItemPressIndication(),
                    onClick = onClick,
                    onLongClickLabel = longClickLabel,
                    onLongClick = onLongClick?.let { cb -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); cb() } },
                )
                .padding(horizontal = if (compactLayout) 4.dp else 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (!compactLayout) ThreadAuthorAvatar(summary.avatarUrl, summary.author, 40.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = titleAnnotated,
                    inlineContent = inlineContent,
                    style = titleStyle,
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { result ->
                        if (!summary.isNew) return@Text
                        val widthPx = result.layoutInput.constraints.maxWidth
                        if (widthPx != lastMeasuredWidthPx) {
                            lastMeasuredWidthPx = widthPx
                            newBadgeOverflowed = if (newBadgeOverflowed) false else result.hasVisualOverflow
                        } else if (!newBadgeOverflowed && result.hasVisualOverflow) {
                            newBadgeOverflowed = true
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (compactLayout) 6.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (compactLayout) ThreadAuthorAvatar(summary.avatarUrl, summary.author, 20.dp)
                    if (showNewInMeta) Text(newLabel, style = newLabelStyle, color = cs.primary, maxLines = 1, softWrap = false)
                    Text(authorMetaText, style = bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (countText.isNotEmpty() || timeText.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (countText.isNotEmpty()) Text(countText, style = metaNumericStyle, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
                            if (countText.isNotEmpty() && timeText.isNotEmpty()) Text("·", style = bodySmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
                            if (timeText.isNotEmpty()) Text(timeText, style = metaNumericStyle, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
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

private fun formatThreadCountText(reply: String, view: String, compactLayout: Boolean): String =
    if (compactLayout) reply.trim() else listOf(reply.trim(), view.trim()).filter(String::isNotEmpty).joinToString("/")
