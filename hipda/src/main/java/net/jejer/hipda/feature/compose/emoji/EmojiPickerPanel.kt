package net.jejer.hipda.feature.compose.emoji

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import javax.inject.Inject
import javax.inject.Singleton
import net.jejer.hipda.R
import net.jejer.hipda.emoji.EmojiGroup
import net.jejer.hipda.emoji.EmojiMap
import net.jejer.hipda.feature.compose.QuickReplyEmojiPanelHost
import net.jejer.hipda.feature.compose.QuickReplyEmojiPanelRenderer
import net.jejer.hipda.feature.compose.previousGraphemeOffset
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.hiFadeIn
import net.jejer.hipda.ui.designsystem.motion.hiFadeOut

private val TAB_BAR_HEIGHT: Dp = 48.dp
private val TAB_ITEM_WIDTH: Dp = 56.dp
private val TAB_PILL_WIDTH: Dp = 44.dp
private val TAB_PILL_HEIGHT: Dp = 34.dp
private val TAB_INDICATOR_WIDTH: Dp = 24.dp
private val TAB_INDICATOR_HEIGHT: Dp = 2.5.dp
private val TAB_ICON_SIZE: Dp = 24.dp
private val GRID_ICON_SIZE: Dp = 28.dp

/** 表情面板渲染器实现：由 `di/ComposeEmojiModule` `@Binds @Singleton` 绑定至 [QuickReplyEmojiPanelRenderer]。 */
@Singleton
class EmojiPanelRendererImpl @Inject constructor() : QuickReplyEmojiPanelRenderer {
    @Composable
    override fun Content(host: QuickReplyEmojiPanelHost, modifier: Modifier) {
        EmojiPickerPanel(host = host, modifier = modifier)
    }
}

private val FORUM_EMOJI_CODES_BY_LENGTH: List<String> by lazy {
    EmojiMap.groups.flatMap { it.codes }.sortedByDescending { it.length }
}

/** 回删定位：光标前紧邻完整论坛表情代码（按 [EmojiMap] 最长优先匹配，含 `:)` 等短码）时整枚删除，否则按字形簇回退 1 格。 */
fun previousEmojiOrGraphemeOffset(text: CharSequence, cursor: Int): Int {
    val safeCursor = cursor.coerceIn(0, text.length)
    if (safeCursor <= 0) return 0
    for (code in FORUM_EMOJI_CODES_BY_LENGTH) {
        val start = safeCursor - code.length
        if (start >= 0 && text.regionMatches(start, code, 0, code.length)) return start
    }
    return previousGraphemeOffset(text, safeCursor)
}

/** 将 `:emoji` 分类映射到 `:hipda` 本地化无障碍字符串资源（闭合 `C1b#3`，不读取硬编码 `EmojiGroup.name`）。 */
@StringRes
fun resolveEmojiGroupLabelRes(group: EmojiGroup): Int? = when (group.iconCode) {
    ":)" -> R.string.emoji_group_default
    "{:2_41:}" -> R.string.emoji_group_monkey
    "{:3_57:}" -> R.string.emoji_group_dumb
    else -> null
}

/** 论坛表情选择面板（纯色 0 `HiGlassMarker`、`EmojiMap` 深浅切图、`HiMotion.spatialFast()` 滑动指示器、自适应 `≥ 48dp` 网格）。 */
@Composable
fun EmojiPickerPanel(host: QuickReplyEmojiPanelHost, modifier: Modifier = Modifier) {
    val groups = EmojiMap.groups
    if (groups.isEmpty()) return
    val darkTheme = HiTheme.state.darkTheme
    val cs = MaterialTheme.colorScheme
    val panelTitle = stringResource(R.string.emoji_panel_title)
    var selectedGroupIndex by rememberSaveable { mutableIntStateOf(0) }
    val safeIndex = selectedGroupIndex.coerceIn(0, groups.lastIndex)

    Column(
        modifier.fillMaxSize().background(cs.surfaceContainerLow)
            .semantics { paneTitle = panelTitle; isTraversalGroup = true },
    ) {
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
        EmojiCategoryTabBar(groups, safeIndex, darkTheme, { selectedGroupIndex = it }, host.onBackspace)
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.35f), thickness = 0.5.dp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = calculateEmojiGridColumns(maxWidth)
            AnimatedContent(
                targetState = safeIndex,
                transitionSpec = { (hiFadeIn(HiMotion.effectsDefault()) togetherWith hiFadeOut(HiMotion.exit(HiMotion.effectsDefault()))).using(null) },
                label = "EmojiCategoryGrid",
                modifier = Modifier.fillMaxSize(),
            ) { groupIndex ->
                LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(groups[groupIndex].codes, key = { it }) { code ->
                        EmojiGridCell(code, darkTheme) { host.onInsertEmoji(code) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmojiCategoryTabBar(
    groups: List<EmojiGroup>, selectedIndex: Int, darkTheme: Boolean,
    onSelectGroup: (Int) -> Unit, onBackspace: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val targetX = TAB_ITEM_WIDTH * selectedIndex
    val animatedX by animateDpAsState(targetX, HiMotion.spatialFast(), label = "EmojiCategoryIndicatorOffset")
    val effectiveX = if (HiTheme.isReducedMotion) targetX else animatedX

    Row(Modifier.fillMaxWidth().height(TAB_BAR_HEIGHT).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.height(TAB_BAR_HEIGHT)) {
            Box(Modifier.width(TAB_ITEM_WIDTH).height(TAB_BAR_HEIGHT).graphicsLayer { translationX = effectiveX.toPx() }, Alignment.Center) {
                Box(Modifier.size(TAB_PILL_WIDTH, TAB_PILL_HEIGHT).clip(HiShapeTokens.Small).background(cs.secondaryContainer))
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).size(TAB_INDICATOR_WIDTH, TAB_INDICATOR_HEIGHT).clip(HiShapeTokens.Full).background(cs.primary))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                groups.forEachIndexed { index, group ->
                    val labelRes = resolveEmojiGroupLabelRes(group)
                    val label = if (labelRes != null) stringResource(labelRes) else stringResource(R.string.emoji_group_fallback, index + 1)
                    val interaction = remember(group.iconCode) { MutableInteractionSource() }
                    Box(
                        Modifier.size(TAB_ITEM_WIDTH, TAB_BAR_HEIGHT).hiPressScale(interaction).clip(HiShapeTokens.Small)
                            .clickable(interaction, LocalIndication.current, role = Role.Tab) { onSelectGroup(index) }
                            .semantics { contentDescription = label; selected = index == selectedIndex },
                        Alignment.Center,
                    ) {
                        EmojiMap.drawableFor(group.iconCode, dark = darkTheme)?.let { resId ->
                            Image(painterResource(resId), null, contentScale = ContentScale.Fit, modifier = Modifier.size(TAB_ICON_SIZE))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val backspaceCd = stringResource(R.string.emoji_action_backspace)
        val backspaceInt = remember { MutableInteractionSource() }
        Box(
            Modifier.size(48.dp).hiPressScale(backspaceInt).clip(HiShapeTokens.Full)
                .clickable(backspaceInt, LocalIndication.current, role = Role.Button, onClick = onBackspace)
                .semantics { contentDescription = backspaceCd },
            Alignment.Center,
        ) { Icon(EmojiBackspaceIcon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp)) }
    }
}

@Composable
private fun EmojiGridCell(code: String, darkTheme: Boolean, onClick: () -> Unit) {
    val drawableRes = EmojiMap.drawableFor(code, dark = darkTheme) ?: return
    val interaction = remember(code) { MutableInteractionSource() }
    val itemCd = stringResource(R.string.emoji_item_cd, code)
    Box(
        Modifier.fillMaxWidth().defaultMinSize(EMOJI_GRID_MIN_CELL_SIZE, EMOJI_GRID_MIN_CELL_SIZE).height(EMOJI_GRID_MIN_CELL_SIZE)
            .hiPressScale(interaction).clip(HiShapeTokens.Small)
            .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = itemCd },
        Alignment.Center,
    ) { Image(painterResource(drawableRes), null, contentScale = ContentScale.Fit, modifier = Modifier.size(GRID_ICON_SIZE)) }
}

private val EmojiBackspaceIcon: ImageVector = ImageVector.Builder("Emoji.Backspace", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(9f, 6f); horizontalLineTo(20f); verticalLineTo(18f); horizontalLineTo(9f); lineTo(3.5f, 12f); close()
        moveTo(12.2f, 9.5f); lineTo(17.2f, 14.5f); moveTo(17.2f, 9.5f); lineTo(12.2f, 14.5f)
    }
}.build()
