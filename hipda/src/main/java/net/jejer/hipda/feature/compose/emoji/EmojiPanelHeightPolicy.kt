package net.jejer.hipda.feature.compose.emoji

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

val EMOJI_PANEL_FALLBACK_MAX_HEIGHT: Dp = 280.dp
val EMOJI_PANEL_SHORT_WINDOW_THRESHOLD: Dp = 480.dp
const val EMOJI_PANEL_NORMAL_HEIGHT_RATIO: Float = 0.45f
const val EMOJI_PANEL_SHORT_HEIGHT_RATIO: Float = 0.35f
val EMOJI_GRID_MIN_CELL_SIZE: Dp = 48.dp
private val EMOJI_PANEL_MIN_CONTENT_WITH_NAV: Dp = 96.dp

/** 计算扣除底部导航栏 inset 后的净软键盘高度（像素）。 */
fun calculateNetKeyboardHeightPx(imeBottomPx: Int, navBarBottomPx: Int): Int =
    if (imeBottomPx <= 0) 0 else (imeBottomPx - navBarBottomPx.coerceAtLeast(0)).coerceAtLeast(0)

/** 计算扣除底部导航栏 inset 后的净软键盘高度（Dp）。 */
fun calculateNetKeyboardHeightDp(imeBottomPx: Int, navBarBottomPx: Int, density: Density): Dp =
    with(density) { calculateNetKeyboardHeightPx(imeBottomPx, navBarBottomPx).toDp() }

/** 按 §7.4 计算表情面板内容高度：优先沿用记忆净键盘高，缺失时 `≥ 480dp` 取 `min(280dp, 45%)`，`< 480dp` 取 `min(280dp, 35%)`。 */
fun resolveEmojiPanelHeight(rememberedNetImeHeightDp: Dp, windowHeightDp: Dp): Dp {
    if (rememberedNetImeHeightDp > 0.dp) return rememberedNetImeHeightDp
    val safeH = windowHeightDp.value.coerceAtLeast(0f)
    val ratio = if (windowHeightDp < EMOJI_PANEL_SHORT_WINDOW_THRESHOLD) EMOJI_PANEL_SHORT_HEIGHT_RATIO else EMOJI_PANEL_NORMAL_HEIGHT_RATIO
    return min(EMOJI_PANEL_FALLBACK_MAX_HEIGHT.value, safeH * ratio).coerceAtLeast(0f).dp
}

/** 计算底部占位总高：有记忆键盘高时含底部导航栏 inset 以对齐 `WindowInsets.ime`（底缘零位移）；回退态严守 §7.4 上限。 */
fun resolveEmojiPanelTotalHeight(rememberedNetImeHeightDp: Dp, navBarBottomDp: Dp, windowHeightDp: Dp): Dp =
    if (rememberedNetImeHeightDp > 0.dp) rememberedNetImeHeightDp + navBarBottomDp.coerceAtLeast(0.dp)
    else resolveEmojiPanelHeight(0.dp, windowHeightDp)

/** 计算表情面板内部预留的底部导航栏安全区内边距。 */
fun resolveEmojiPanelInnerNavPadding(totalPanelHeightDp: Dp, rememberedNetImeHeightDp: Dp, navBarBottomDp: Dp): Dp {
    val safeNav = navBarBottomDp.coerceAtLeast(0.dp)
    if (safeNav <= 0.dp) return 0.dp
    if (rememberedNetImeHeightDp > 0.dp) return safeNav
    return minOf(safeNav, (totalPanelHeightDp - EMOJI_PANEL_MIN_CONTENT_WITH_NAV).coerceAtLeast(0.dp))
}

/** 按面板可用宽度自适应计算表情网格列数，保证每格触控宽 `≥ minCellSizeDp`（默认 `48dp`）。 */
fun calculateEmojiGridColumns(panelWidthDp: Dp, minCellSizeDp: Dp = EMOJI_GRID_MIN_CELL_SIZE): Int =
    if (minCellSizeDp.value <= 0f) 1 else max(1, (panelWidthDp.value / minCellSizeDp.value).toInt())
