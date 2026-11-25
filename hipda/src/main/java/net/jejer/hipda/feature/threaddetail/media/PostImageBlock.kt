package net.jejer.hipda.feature.threaddetail.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.ui.designsystem.HiShapeTokens

/** 帖子正文图片块（Wave 3 `E2b` 独占并行接缝，闭合 R-BATCH5 B2-2；`E2a` 仅锁定签名并提供最小 4:3 静态占位）。 */
@Composable
fun PostImageBlock(
    image: ContentBlock.Image,
    @Suppress("UNUSED_PARAMETER") floorPostId: String,
    @Suppress("UNUSED_PARAMETER") listState: LazyListState,
    onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val launchArgs = remember(image.fullUrl) { ImageViewerLaunchArgs(urls = listOf(image.fullUrl)) }
    Surface(
        onClick = { onOpenImageViewer(launchArgs) },
        modifier = modifier.fillMaxWidth().aspectRatio(4f / 3f),
        shape = HiShapeTokens.Medium,
        color = cs.surfaceContainerLow,
        border = BorderStroke(0.5.dp, cs.outlineVariant),
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.thread_detail_image_placeholder), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        }
    }
}
