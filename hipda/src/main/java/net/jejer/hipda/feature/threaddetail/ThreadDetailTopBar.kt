package net.jejer.hipda.feature.threaddetail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import net.jejer.hipda.ui.designsystem.hiTopSafeBar
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.glass.HiGlassState
import net.jejer.hipda.ui.designsystem.glass.HiGlassStyle
import net.jejer.hipda.ui.designsystem.glass.HiGlassSurface
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.topbar.hiTopBarTapToScrollTop

/** 帖子详情透明顶栏固定高度（不含状态栏内边距），与 `FORUM_TOP_BAR_TITLE_ROW_HEIGHT` 严格对齐 48dp。 */
internal val ThreadDetailTopBarHeight: Dp = 48.dp

/**
 * 帖子详情透明极简顶栏（严格 1 个 `HiGlassStyle.TopBar` 玻璃区域，未滚动纯 `surface` 无底线，向下滚动以
 * `HiMotion.effectsDefault()` 渐进显露玻璃与 `0.5dp` 底线；单行标题 + `48dp` 无底纯图标窗口级动作，零嵌套玻璃；
 * 「只看楼主」已收纳至底部工具条「更多」菜单，单栏右侧彻底清爽、三栏仅保留「全屏阅读 / 显示列表」）。
 */
@Composable
internal fun ThreadDetailTopBar(
    title: String,
    @Suppress("UNUSED_PARAMETER") authorName: String? = null,
    chromeSpec: DetailChromeSpec,
    @Suppress("UNUSED_PARAMETER") isAuthorOnly: Boolean = false,
    isScrolledState: State<Boolean>,
    glassState: HiGlassState,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onToggleAuthorOnly: () -> Unit = {},
    modifier: Modifier = Modifier,
    onScrollToTop: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
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
                        if (reveal > 0.001f) 0.5.dp.toPx().let { h ->
                            drawRect(dividerColor.copy(alpha = dividerColor.alpha * reveal), Offset(0f, size.height - h), Size(size.width, h))
                        }
                    }
                    .windowInsetsPadding(WindowInsets.hiTopSafeBar),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ThreadDetailTopBarHeight)
                        .padding(
                            start = if (chromeSpec.showBack) 4.dp else 16.dp,
                            end = if (chromeSpec.fullScreenState != null) 4.dp else 16.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (chromeSpec.showBack) {
                        TopBarActionButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_action_back), false, onBack)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(if (onScrollToTop != null) Modifier.hiTopBarTapToScrollTop(onScrollToTop, enabled = isScrolledState.value) else Modifier)
                            .padding(start = if (chromeSpec.showBack) 4.dp else 0.dp, end = if (chromeSpec.fullScreenState != null) 8.dp else 0.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    chromeSpec.fullScreenState?.let { fs ->
                        TopBarActionButton(
                            icon = if (fs.isFullScreen) TopBarVectorIcons.ShowList else TopBarVectorIcons.HideList,
                            contentDescription = stringResource(if (fs.isFullScreen) R.string.shell_detail_show_list else R.string.shell_detail_fullscreen),
                            active = fs.isFullScreen,
                            onClick = fs.onToggle,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBarActionButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(48.dp)
            .hiPressScale(interaction)
            .clip(HiShapeTokens.Full)
            .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (active) cs.primary else cs.onSurface, modifier = Modifier.size(22.dp))
    }
}

private object TopBarVectorIcons {
    private val strokeBrush = SolidColor(Color.Black)
    private fun stroked(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder("ThreadDetail.$name", 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = strokeBrush, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()

    val HideList: ImageVector = stroked("HideList") {
        moveTo(5.5f, 4.5f); horizontalLineTo(18.5f); quadTo(20.5f, 4.5f, 20.5f, 6.5f)
        verticalLineTo(17.5f); quadTo(20.5f, 19.5f, 18.5f, 19.5f)
        horizontalLineTo(5.5f); quadTo(3.5f, 19.5f, 3.5f, 17.5f)
        verticalLineTo(6.5f); quadTo(3.5f, 4.5f, 5.5f, 4.5f); close()
        moveTo(9.5f, 4.5f); verticalLineTo(19.5f)
        moveTo(15.5f, 9.5f); lineTo(13f, 12f); lineTo(15.5f, 14.5f)
    }

    val ShowList: ImageVector = ImageVector.Builder("ThreadDetail.ShowList", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = strokeBrush) {
            moveTo(5.5f, 4.5f); horizontalLineTo(9.5f); verticalLineTo(19.5f)
            horizontalLineTo(5.5f); quadTo(3.5f, 19.5f, 3.5f, 17.5f)
            verticalLineTo(6.5f); quadTo(3.5f, 4.5f, 5.5f, 4.5f); close()
        }
        path(stroke = strokeBrush, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(5.5f, 4.5f); horizontalLineTo(18.5f); quadTo(20.5f, 4.5f, 20.5f, 6.5f)
            verticalLineTo(17.5f); quadTo(20.5f, 19.5f, 18.5f, 19.5f)
            horizontalLineTo(5.5f); quadTo(3.5f, 19.5f, 3.5f, 17.5f)
            verticalLineTo(6.5f); quadTo(3.5f, 4.5f, 5.5f, 4.5f); close()
            moveTo(13.5f, 9.5f); lineTo(16f, 12f); lineTo(13.5f, 14.5f)
        }
    }.build()
}
