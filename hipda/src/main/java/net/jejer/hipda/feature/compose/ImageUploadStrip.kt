package net.jejer.hipda.feature.compose

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.currentHiImageLoader
import net.jejer.hipda.ui.designsystem.*
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.*
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner

/** 横向滚动多图上传条（§7.4：压缩/确定进度/汇总 `正在上传 {current}/{total}`/单图失败重试/未插图角标/点击插入/`×` 取消移除）。 */
@Composable
fun ImageUploadStrip(
    uploads: List<UploadAttachmentItem>, insertedImageIds: Set<String>, summaryProgress: Pair<Int, Int>?,
    onClickUploaded: (localId: String) -> Unit, onRetryFailed: (localId: String) -> Unit, onRemove: (localId: String) -> Unit, modifier: Modifier = Modifier,
) {
    AnimatedVisibility(uploads.isNotEmpty(), modifier.fillMaxWidth(), hiExpandVertically(expandFrom = Alignment.Top) + hiFadeIn(), hiShrinkVertically(shrinkTowards = Alignment.Top) + hiFadeOut()) {
        val cs = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().background(cs.surfaceContainerLow).padding(top = 8.dp, bottom = 6.dp)) {
            if (summaryProgress != null) HiCappedFontScale {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                    HiLoadingSpinner(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.compose_upload_summary, summaryProgress.first, summaryProgress.second), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(uploads, key = { it.localId }) { item ->
                    UploadThumbnailTile(item, item.imageIdOrNull?.let { it in insertedImageIds } == true, { onClickUploaded(item.localId) }, { onRetryFailed(item.localId) }, { onRemove(item.localId) })
                }
            }
        }
    }
}

@Composable
private fun UploadThumbnailTile(item: UploadAttachmentItem, isInserted: Boolean, onClickUploaded: () -> Unit, onRetryFailed: () -> Unit, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val retryCd = stringResource(R.string.compose_upload_failed_retry_cd)
    val insertCd = stringResource(R.string.compose_action_insert_image)
    val tileClickModifier = when (item.status) {
        is UploadAttachmentStatus.Uploaded -> Modifier.hiPressScale(interaction).clickable(interaction, null, role = Role.Button, onClick = onClickUploaded).semantics { contentDescription = insertCd }
        is UploadAttachmentStatus.Failed -> Modifier.hiPressScale(interaction).clickable(interaction, null, role = Role.Button, onClick = onRetryFailed).semantics { contentDescription = retryCd }
        else -> Modifier
    }
    Box(Modifier.size(86.dp)) {
        Box(Modifier.align(Alignment.BottomStart).size(72.dp).clip(HiShapeTokens.Medium).background(cs.surfaceContainerHighest).border(0.75.dp, cs.outlineVariant, HiShapeTokens.Medium).then(tileClickModifier), Alignment.Center) {
            ThumbnailPreviewImage(item.contentUri, 72, Modifier.fillMaxSize())
            when (val st = item.status) {
                UploadAttachmentStatus.Compressing -> Box(Modifier.fillMaxSize().background(cs.scrim.copy(alpha = 0.45f)), Alignment.Center) {
                    HiLoadingSpinner(Modifier.size(26.dp), strokeWidth = 2.5.dp, color = cs.surface, trackColor = cs.surface.copy(alpha = 0.30f))
                }
                is UploadAttachmentStatus.Uploading -> Box(Modifier.fillMaxSize().background(cs.scrim.copy(alpha = 0.45f)), Alignment.Center) {
                    CircularProgressIndicator({ st.fraction.coerceIn(0f, 1f) }, Modifier.size(28.dp), cs.surface, 2.5.dp, cs.surface.copy(alpha = 0.30f))
                }
                is UploadAttachmentStatus.Failed -> Box(Modifier.fillMaxSize().background(cs.errorContainer.copy(alpha = 0.85f)), Alignment.Center) {
                    Icon(Icons.Rounded.Refresh, null, tint = cs.onErrorContainer, modifier = Modifier.size(26.dp))
                }
                is UploadAttachmentStatus.Uploaded -> if (!isInserted) HiCappedFontScale {
                    Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(HiShapeTokens.Small).background(cs.primaryContainer.copy(alpha = 0.94f)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                        Text(stringResource(R.string.compose_upload_uninserted_badge), style = MaterialTheme.typography.labelSmall, color = cs.onPrimaryContainer, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        RemoveCornerButton(22.dp, 14.dp, onRemove, Modifier.align(Alignment.TopEnd))
    }
}

/** 正文内可视化缩略图块：替代 `[attachimg]` 标签，严禁向用户暴露源码文本。 */
@Composable
fun EditorInlineImageBlock(imageId: String, previewUri: Uri?, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val imageCd = stringResource(R.string.compose_upload_inline_image_cd)
    Box(modifier.fillMaxWidth().padding(vertical = 6.dp).height(136.dp).clip(HiShapeTokens.Medium).background(cs.surfaceContainerHigh).border(0.75.dp, cs.outlineVariant, HiShapeTokens.Medium).semantics { contentDescription = imageCd }, Alignment.Center) {
        ThumbnailPreviewImage(previewUri, 240, Modifier.fillMaxSize())
        RemoveCornerButton(26.dp, 16.dp, onRemove, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun RemoveCornerButton(badgeSize: Dp, iconSize: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val removeCd = stringResource(R.string.compose_upload_remove_cd)
    IconButton(onClick, modifier.defaultMinSize(48.dp, 48.dp).semantics { contentDescription = removeCd }) {
        Box(Modifier.size(badgeSize).clip(CircleShape).background(cs.inverseSurface.copy(alpha = 0.85f)), Alignment.Center) {
            Icon(Icons.Rounded.Close, null, tint = cs.inverseOnSurface, modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
private fun ThumbnailPreviewImage(contentUri: Uri?, sizeDp: Int, modifier: Modifier = Modifier) {
    if (contentUri != null) {
        val context = LocalContext.current
        val sizePx = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
        val request = remember(context, contentUri, sizePx) { ImageRequest.Builder(context).data(contentUri).size(sizePx).build() }
        AsyncImage(model = request, contentDescription = null, imageLoader = currentHiImageLoader(), contentScale = ContentScale.Crop, modifier = modifier)
    } else Box(modifier, Alignment.Center) {
        Icon(ComposeVectorIcons.InsertImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
    }
}
