package net.jejer.hipda.feature.forum

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.floor
import kotlin.math.roundToInt
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.shell.ForumMetadataRegistry

internal val FORUM_TAB_ROW_HEIGHT: Dp = 48.dp
private val TabHorizontalPadding: Dp = 12.dp
private val TabMinWidth: Dp = 48.dp
private const val SelectedTabScale: Float = 1.07f

@Immutable
internal data class ForumTabEntry(val source: ForumListSource, val label: String)

/** 版块标签荧光笔渐变色：优先 `primaryContainer`，峰值 alpha 严守 `HiGlassTokens.TabHighlighter*` 上限（向下 8-bit 量化避免超界），禁用 `#FFB003`。 */
fun resolveTabHighlighterColors(colorScheme: ColorScheme, darkTheme: Boolean): List<Color> {
    val rawPeak = if (darkTheme) HiGlassTokens.TabHighlighterContainerDarkMaxAlpha else HiGlassTokens.TabHighlighterContainerLightMaxAlpha
    val peak = floor(rawPeak * 255f) / 255f
    val base = colorScheme.primaryContainer
    return listOf(base.copy(alpha = peak * 0.65f), base.copy(alpha = peak), base.copy(alpha = peak), base.copy(alpha = peak * 0.72f))
}

/** 自适应吸顶版块标签行：首位固定「今日新帖」+ 关注版块全名 + 右侧固定「管理版块」48dp 钮，放得下等分、放不下横滑，荧光笔选中高光。 */
@Composable
fun BalancedForumTabRow(
    selectedSource: ForumListSource,
    followedForums: List<Int>,
    onSelectSource: (ForumListSource) -> Unit,
    onManageForumsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val todayLabel = stringResource(R.string.shell_sidebar_today_new)
    val tabs = remember(todayLabel, followedForums) {
        buildList(followedForums.size + 1) {
            add(ForumTabEntry(ForumListSource.TodayNew, todayLabel))
            followedForums.forEach { fid -> add(ForumTabEntry(ForumListSource.Forum(fid), ForumMetadataRegistry.resolve(fid).fullName)) }
        }
    }

    HiCappedFontScale {
        val cs = MaterialTheme.colorScheme
        val darkTheme = HiTheme.state.darkTheme
        val highlighterColors = remember(cs, darkTheme) { resolveTabHighlighterColors(cs, darkTheme) }
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val measureStyle = remember { TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold) }

        Row(
            modifier = modifier.fillMaxWidth().heightIn(min = FORUM_TAB_ROW_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val availableWidth = maxWidth
                val tabMinWidths = remember(tabs, measureStyle, density) {
                    tabs.map { entry ->
                        val px = textMeasurer.measure(entry.label, style = measureStyle, maxLines = 1).size.width
                        max(with(density) { (px * SelectedTabScale).toDp() } + TabHorizontalPadding * 2, TabMinWidth)
                    }
                }
                val fitBalanced = tabs.isNotEmpty() && (tabMinWidths.maxOrNull() ?: TabMinWidth) * tabs.size <= availableWidth

                if (fitBalanced) {
                    Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                        tabs.forEach { entry ->
                            ForumTabItem(entry, entry.source == selectedSource, highlighterColors, { onSelectSource(entry.source) }, Modifier.weight(1f))
                        }
                    }
                } else {
                    val scrollState = rememberScrollState()
                    val tabBounds = remember { mutableStateMapOf<ForumListSource, Pair<Int, Int>>() }
                    val viewportPx = with(density) { availableWidth.roundToPx() }
                    val reducedMotion = HiTheme.isReducedMotion

                    LaunchedEffect(selectedSource, viewportPx, tabBounds[selectedSource]) {
                        val (leftPx, widthPx) = tabBounds[selectedSource] ?: return@LaunchedEffect
                        val target = (leftPx + widthPx / 2 - viewportPx / 2).coerceIn(0, scrollState.maxValue)
                        if (reducedMotion) scrollState.scrollTo(target)
                        else scrollState.animateScrollTo(target, animationSpec = HiMotion.spatialFast())
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState).padding(horizontal = 4.dp).selectableGroup(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEach { entry ->
                            ForumTabItem(
                                entry = entry,
                                selected = entry.source == selectedSource,
                                highlighterColors = highlighterColors,
                                onClick = { onSelectSource(entry.source) },
                                modifier = Modifier.onPlaced { tabBounds[entry.source] = it.positionInParent().x.roundToInt() to it.size.width },
                            )
                        }
                    }
                }
            }

            val manageInteraction = remember { MutableInteractionSource() }
            IconButton(
                onClick = onManageForumsClick,
                interactionSource = manageInteraction,
                modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(manageInteraction),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.shell_sidebar_manage_forums), tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun ForumTabItem(
    entry: ForumTabEntry,
    selected: Boolean,
    highlighterColors: List<Color>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val cs = MaterialTheme.colorScheme
    val scaleState = animateFloatAsState(if (selected) SelectedTabScale else 1f, animationSpec = HiMotion.spatialFast(), label = "ForumTabScale")
    val highlightState = animateFloatAsState(if (selected) 1f else 0f, animationSpec = HiMotion.effectsFast(), label = "ForumTabHighlight")

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = TabMinWidth, minHeight = FORUM_TAB_ROW_HEIGHT)
            .hiPressScale(interaction)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = TabHorizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = entry.label,
            style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
            color = if (selected) cs.onSurface else cs.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .graphicsLayer { val s = scaleState.value; scaleX = s; scaleY = s }
                .drawWithCache {
                    val overhang = 2.dp.toPx()
                    val brush = Brush.horizontalGradient(highlighterColors, startX = -overhang, endX = size.width + overhang)
                    val radius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    onDrawBehind {
                        val alpha = highlightState.value
                        if (alpha > 0.001f) {
                            drawRoundRect(
                                brush = brush,
                                topLeft = Offset(-overhang, size.height * 0.52f),
                                size = Size(size.width + overhang * 2f, size.height * 0.42f),
                                cornerRadius = radius,
                                alpha = alpha,
                            )
                        }
                    }
                },
        )
    }
}
