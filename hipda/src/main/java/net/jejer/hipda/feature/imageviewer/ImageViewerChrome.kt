package net.jejer.hipda.feature.imageviewer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.hiTopSafeBar
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.state.HiLoadingSpinner

/** 看图浮层专用纯色半透明暗底（严格零 `HiGlassSurface`）。 */
internal object ImageViewerPalette {
    val ScrimColor = Color.Black
    val BarBackground = Color(0xBD121316)
    val PillBackground = Color(0xCC1B1D22)
    val PillActiveBackground = Color(0xE6283344)
    val MenuSurface = Color(0xF21B1D22)
    val BorderHairline = Color(0x24FFFFFF)
    val ForegroundPrimary = Color(0xFFF5F6F8)
    val ForegroundSecondary = Color(0xFFC5C9D1)
    val ForegroundDisabled = Color(0x66F5F6F8)
    val AccentProgress = Color(0xFF7CB7FF)
    val ProgressTrack = Color(0x47FFFFFF)
}

@Composable
internal fun ImageViewerTopBar(
    title: String?,
    currentPage: Int,
    totalPages: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeDesc = stringResource(R.string.image_viewer_action_close)
    val pageText = stringResource(R.string.image_viewer_page_indicator, currentPage + 1, totalPages.coerceAtLeast(1))
    val closeInteraction = remember { MutableInteractionSource() }
    Column(modifier.fillMaxWidth().background(ImageViewerPalette.BarBackground).windowInsetsPadding(WindowInsets.hiTopSafeBar)) {
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(HiShapeTokens.Full)
                    .hiPressScale(closeInteraction)
                    .clickable(closeInteraction, indication = LocalIndication.current, role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = closeDesc },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, null, tint = ImageViewerPalette.ForegroundPrimary, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(4.dp))
            Text(
                text = title.orEmpty(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = ImageViewerPalette.ForegroundPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Surface(shape = HiShapeTokens.Full, color = ImageViewerPalette.PillBackground, border = BorderStroke(0.5.dp, ImageViewerPalette.BorderHairline)) {
                Text(pageText, style = MaterialTheme.typography.labelLarge, color = ImageViewerPalette.ForegroundPrimary, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            Spacer(Modifier.width(8.dp))
        }
        HorizontalDivider(thickness = 0.5.dp, color = ImageViewerPalette.BorderHairline)
    }
}

@Composable
internal fun ImageViewerBottomBar(
    downloadState: OriginalDownloadState,
    actionsEnabled: Boolean,
    isSaving: Boolean,
    onOriginalClick: () -> Unit,
    onSaveClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fg = if (actionsEnabled) ImageViewerPalette.ForegroundPrimary else ImageViewerPalette.ForegroundDisabled
    val cancelDesc = stringResource(R.string.image_viewer_loading_cancel)
    val isDownloading = downloadState is OriginalDownloadState.Downloading
    val isCompleted = downloadState is OriginalDownloadState.Completed
    Column(modifier.fillMaxWidth().background(ImageViewerPalette.BarBackground).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalDivider(thickness = 0.5.dp, color = ImageViewerPalette.BorderHairline)
        Row(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewerPillButton(
                enabled = actionsEnabled && !isCompleted,
                containerColor = if (isDownloading) ImageViewerPalette.PillActiveBackground else ImageViewerPalette.PillBackground,
                semanticDescription = if (isDownloading) cancelDesc else null,
                onClick = onOriginalClick,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                when (downloadState) {
                    is OriginalDownloadState.Downloading -> {
                        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            val p = downloadState.progress
                            if (p != null) {
                                CircularProgressIndicator({ p.coerceIn(0f, 1f) }, Modifier.size(22.dp), ImageViewerPalette.AccentProgress, 2.5.dp, ImageViewerPalette.ProgressTrack)
                            } else {
                                HiLoadingSpinner(Modifier.size(22.dp), 2.5.dp, ImageViewerPalette.AccentProgress, ImageViewerPalette.ProgressTrack)
                            }
                            Icon(Icons.Rounded.Close, null, tint = ImageViewerPalette.ForegroundPrimary, modifier = Modifier.size(12.dp))
                        }
                        val pct = downloadState.progress?.let { "${(it * 100f).toInt().coerceIn(0, 100)}%" }
                        Text(pct ?: stringResource(R.string.image_viewer_action_view_original), style = MaterialTheme.typography.labelLarge, color = ImageViewerPalette.ForegroundPrimary, maxLines = 1)
                    }
                    is OriginalDownloadState.Completed -> {
                        Icon(Icons.Rounded.Check, null, tint = ImageViewerPalette.AccentProgress, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.image_viewer_original_loaded), style = MaterialTheme.typography.labelLarge, color = ImageViewerPalette.ForegroundSecondary, maxLines = 1)
                    }
                    else -> Text(stringResource(R.string.image_viewer_action_view_original), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
                }
            }
            ViewerPillButton(actionsEnabled && !isSaving, onSaveClick) {
                Icon(ImageViewerIcons.Download, null, tint = fg, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.image_viewer_action_save), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
            }
            ViewerPillButton(actionsEnabled, onShareClick) {
                Icon(Icons.Rounded.Share, null, tint = fg, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.image_viewer_action_share), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ViewerPillButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = ImageViewerPalette.PillBackground,
    semanticDescription: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        shape = HiShapeTokens.Full,
        color = containerColor,
        border = BorderStroke(0.5.dp, ImageViewerPalette.BorderHairline),
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(HiShapeTokens.Full)
            .hiPressScale(interaction)
            .then(if (semanticDescription != null) Modifier.semantics(mergeDescendants = true) { contentDescription = semanticDescription } else Modifier)
            .clickable(interaction, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically, content)
    }
}

/** 左上角 GIF 角标：点一下切播停，动效遵循 `HiMotion.effectsFast()`，触控区 `≥ 48dp`。 */
@Composable
internal fun ImageViewerGifBadge(isPlaying: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val badgeBg by animateColorAsState(if (isPlaying) ImageViewerPalette.PillActiveBackground else ImageViewerPalette.PillBackground, HiMotion.effectsFast(), label = "gifBadgeBg")
    val iconAlpha by animateFloatAsState(if (isPlaying) 1f else 0.85f, HiMotion.effectsFast(), label = "gifBadgeAlpha")
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .hiPressScale(interaction)
            .toggleable(isPlaying, interaction, indication = LocalIndication.current, role = Role.Button, onValueChange = { onToggle() }),
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(shape = HiShapeTokens.Small, color = badgeBg, border = BorderStroke(0.5.dp, ImageViewerPalette.BorderHairline)) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), Arrangement.spacedBy(4.dp), Alignment.CenterVertically) {
                Icon(if (isPlaying) ImageViewerIcons.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(16.dp).graphicsLayer { alpha = iconAlpha }, ImageViewerPalette.ForegroundPrimary)
                Text(stringResource(R.string.image_viewer_gif_badge), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = ImageViewerPalette.ForegroundPrimary)
            }
        }
    }
}

/** 长按弹出的保存/分享菜单（标准 Material 3 [DropdownMenu]，触控项 ≥ 48dp，零毛玻璃，R-E14 S4）。 */
@Composable
internal fun ImageViewerLongPressMenu(expanded: Boolean, onSave: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    val colors = MenuDefaults.itemColors(textColor = ImageViewerPalette.ForegroundPrimary, leadingIconColor = ImageViewerPalette.ForegroundPrimary)
    val items = listOf(
        Triple(R.string.image_viewer_action_save, ImageViewerIcons.Download) { onDismiss(); onSave() },
        Triple(R.string.image_viewer_action_share, Icons.Rounded.Share) { onDismiss(); onShare() },
        Triple(R.string.image_viewer_action_cancel, Icons.Rounded.Close, onDismiss),
    )
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = ImageViewerPalette.MenuSurface,
        shape = HiShapeTokens.Large,
        border = BorderStroke(0.5.dp, ImageViewerPalette.BorderHairline),
    ) {
        items.forEach { (labelRes, icon, action) ->
            DropdownMenuItem(
                text = { Text(stringResource(labelRes)) },
                leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) },
                colors = colors,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                onClick = action,
            )
        }
    }
}

internal object ImageViewerIcons {
    val Download: ImageVector by lazy {
        ImageVector.Builder("ImageViewerDownload", 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 4f); lineTo(12f, 15f)
                moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
                moveTo(5f, 19f); lineTo(19f, 19f)
            }
        }.build()
    }
    val Pause: ImageVector by lazy {
        ImageVector.Builder("ImageViewerPause", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(7f, 5f); lineTo(10.5f, 5f); lineTo(10.5f, 19f); lineTo(7f, 19f); close()
                moveTo(13.5f, 5f); lineTo(17f, 5f); lineTo(17f, 19f); lineTo(13.5f, 19f); close()
            }
        }.build()
    }
}
