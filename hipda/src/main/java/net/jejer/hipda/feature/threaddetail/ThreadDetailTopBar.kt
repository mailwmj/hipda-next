package net.jejer.hipda.feature.threaddetail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.DetailChromeSpec
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.glass.HiGlassState
import net.jejer.hipda.ui.designsystem.glass.HiGlassStyle
import net.jejer.hipda.ui.designsystem.glass.HiGlassSurface
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.topbar.hiTopBarTapToScrollTop

/** 帖子详情透明顶栏固定高度（不含状态栏内边距），供楼层 `LazyColumn` 顶部固定 `contentPadding` 对齐。 */
internal val ThreadDetailTopBarHeight: Dp = 60.dp

/**
 * 帖子详情透明极简顶栏（严格 1 个 `HiGlassStyle.TopBar` 玻璃区域，未滚动纯 `surface`，向下滚动以
 * `HiMotion.effectsDefault()` 渐进显露玻璃；左右 `40dp` 轻底纯色圆钮包裹于 `48×48dp` 触控区，零嵌套玻璃）。
 */
@Composable
internal fun ThreadDetailTopBar(
    title: String,
    authorName: String?,
    chromeSpec: DetailChromeSpec,
    isAuthorOnly: Boolean,
    isScrolledState: State<Boolean>,
    glassState: HiGlassState,
    onBack: () -> Unit,
    onToggleAuthorOnly: () -> Unit,
    modifier: Modifier = Modifier,
    onScrollToTop: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val darkTheme = HiTheme.state.darkTheme
    val glassRevealState = animateFloatAsState(if (isScrolledState.value) 1f else 0f, HiMotion.effectsDefault(), label = "DetailTopBarGlass")
    val surfaceColor = cs.surface
    val dividerColor = cs.outlineVariant

    HiGlassSurface(
        state = glassState,
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        floating = false,
        style = HiGlassStyle.TopBar,
        contentAlignment = Alignment.TopStart,
    ) {
        HiCappedFontScale {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val reveal = glassRevealState.value.coerceIn(0f, 1f)
                        if (reveal < 0.999f) drawRect(surfaceColor.copy(alpha = 1f - reveal))
                        if (reveal > 0.001f) HiGlassTokens.TopBarActionButtonBorderWidth.toPx().let { h ->
                            drawRect(dividerColor.copy(alpha = dividerColor.alpha * reveal), Offset(0f, size.height - h), Size(size.width, h))
                        }
                    }
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(ThreadDetailTopBarHeight).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (chromeSpec.showBack) {
                        TopBarCircleActionButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_action_back), false, darkTheme, onBack)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .then(if (onScrollToTop != null) Modifier.hiTopBarTapToScrollTop(onScrollToTop) else Modifier)
                            .padding(horizontal = if (chromeSpec.showBack) 4.dp else 8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        authorName?.trim()?.takeIf { it.isNotEmpty() }?.let { opName ->
                            Text("${stringResource(R.string.thread_detail_badge_op)} · $opName", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    chromeSpec.fullScreenState?.let { fs ->
                        TopBarCircleActionButton(
                            icon = if (fs.isFullScreen) TopBarVectorIcons.ShowList else TopBarVectorIcons.FullScreen,
                            contentDescription = stringResource(if (fs.isFullScreen) R.string.shell_detail_show_list else R.string.shell_detail_fullscreen),
                            active = fs.isFullScreen,
                            darkTheme = darkTheme,
                            onClick = fs.onToggle,
                        )
                    }
                    TopBarCircleActionButton(
                        icon = if (isAuthorOnly) Icons.Rounded.Person else Icons.Outlined.Person,
                        contentDescription = stringResource(if (isAuthorOnly) R.string.thread_detail_action_view_all else R.string.thread_detail_action_author_only),
                        active = isAuthorOnly,
                        darkTheme = darkTheme,
                        onClick = onToggleAuthorOnly,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBarCircleActionButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    darkTheme: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val fillAlpha = if (darkTheme) HiGlassTokens.TopBarActionButtonDarkAlpha else HiGlassTokens.TopBarActionButtonLightAlpha

    Box(
        modifier = Modifier
            .size(48.dp)
            .hiPressScale(interaction)
            .clip(HiShapeTokens.Full)
            .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(HiShapeTokens.Full)
                .background(cs.surfaceContainerHigh.copy(alpha = fillAlpha))
                .border(HiGlassTokens.TopBarActionButtonBorderWidth, if (active) cs.primary else cs.outlineVariant, HiShapeTokens.Full),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = if (active) cs.primary else cs.onSurface, modifier = Modifier.size(20.dp))
        }
    }
}

private object TopBarVectorIcons {
    private val strokeBrush = SolidColor(Color.Black)
    private fun stroked(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder("ThreadDetail.$name", 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = strokeBrush, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()

    val FullScreen: ImageVector = stroked("FullScreen") {
        moveTo(9f, 4f); horizontalLineTo(4f); verticalLineTo(9f); moveTo(4f, 4f); lineTo(10f, 10f)
        moveTo(15f, 20f); horizontalLineTo(20f); verticalLineTo(15f); moveTo(20f, 20f); lineTo(14f, 14f)
    }
    val ShowList: ImageVector = stroked("ShowList") {
        moveTo(4f, 5f); horizontalLineTo(20f); verticalLineTo(19f); horizontalLineTo(4f); close()
        moveTo(10f, 5f); verticalLineTo(19f)
    }
}
