package net.jejer.hipda.feature.threaddetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.jejer.hipda.R
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.currentHiImageLoader
import net.jejer.hipda.feature.threaddetail.body.PostBodyRenderer
import net.jejer.hipda.ui.designsystem.HiShapeTokens

internal val PostMaxContentWidth = 680.dp

/** 帖子单楼层卡片（最大宽 `680dp` 居中；圆形头像经 `LocalHiImageLoader` 加载；支持楼主标识与黑名单遮蔽态）。 */
@Composable
internal fun PostFloorCard(
    item: ThreadDetailFloorItem,
    isOp: Boolean,
    listState: LazyListState,
    onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit,
    onAuthorClick: (uid: String, username: String) -> Unit,
    modifier: Modifier = Modifier,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val floor = item.floor

    Column(
        modifier = modifier.widthIn(max = PostMaxContentWidth).fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FloorAvatar(
                author = floor.author,
                avatarUrl = if (item.isAuthorBlocked) null else floor.avatarUrl,
                onClick = if (floor.uid.isNotBlank()) ({ onAuthorClick(floor.uid, floor.author) }) else null,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(floor.author, style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (isOp) {
                        Surface(shape = HiShapeTokens.Small, color = cs.primaryContainer, contentColor = cs.onPrimaryContainer) {
                            Text(stringResource(R.string.thread_detail_badge_op), style = MaterialTheme.typography.labelSmall, color = cs.onPrimaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                    }
                }
                Text(floor.timePost, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(stringResource(R.string.common_floor_badge, floor.floor), style = MaterialTheme.typography.labelMedium, color = cs.primary, maxLines = 1)
        }

        if (item.isAuthorBlocked) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = HiShapeTokens.Small, color = cs.surfaceContainerLow) {
                Text(stringResource(R.string.common_floor_blocked, floor.author), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
        } else {
            PostBodyRenderer(floor.blocks, floor.postId, listState, onOpenImageViewer, onLinkClick = onLinkClick)
            floor.postStatus?.trim()?.takeIf { it.isNotEmpty() }?.let { status ->
                Text(status, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FloorAvatar(author: String, avatarUrl: String?, onClick: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val initial = author.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(HiShapeTokens.Full)
            .background(cs.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(model = avatarUrl, contentDescription = null, imageLoader = currentHiImageLoader(), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
