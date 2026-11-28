package net.jejer.hipda.ui.designsystem.topbar

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.hiTopSafeBar
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.HiMotion

/**
 * 顶栏标题/空白区单击滚回顶部修饰符（无水波纹，不抢占子按钮/输入框/Tab 的点击事件）。
 */
@Composable
fun Modifier.hiTopBarTapToScrollTop(
    onScrollToTop: () -> Unit,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    val interactionSource = remember { MutableInteractionSource() }
    val onClickLabel = stringResource(R.string.common_action_scroll_to_top)
    return clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = true,
        onClickLabel = onClickLabel,
        onClick = onScrollToTop,
    )
}

/** 列表滚回首项（`reducedMotion == true` 时一帧到位，否则平滑滚动）。 */
suspend fun LazyListState.hiScrollToTop(reducedMotion: Boolean) {
    if (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) return
    if (reducedMotion) scrollToItem(0) else animateScrollToItem(0)
}

/** 常规可滚容器滚回顶部（`reducedMotion == true` 时一帧到位，否则按 `HiMotion.spatialDefault()` 平滑滚动）。 */
suspend fun ScrollState.hiScrollToTop(reducedMotion: Boolean) {
    if (value == 0) return
    if (reducedMotion) scrollTo(0) else animateScrollTo(0, HiMotion.spatialDefault())
}

/** 记住稳定的滚回顶部回调，供列表页及详情页顶栏统一复用。 */
@Composable
fun rememberHiScrollToTopAction(
    listState: LazyListState,
    onBeforeScroll: (() -> Unit)? = null,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val reducedMotion by rememberUpdatedState(HiTheme.isReducedMotion)
    val latestBeforeScroll by rememberUpdatedState(onBeforeScroll)
    return remember(listState, scope) {
        {
            latestBeforeScroll?.invoke()
            scope.launch { listState.hiScrollToTop(reducedMotion) }
        }
    }
}

/** 记住稳定的 [ScrollState] 滚回顶部回调。 */
@Composable
fun rememberHiScrollToTopAction(
    scrollState: ScrollState,
    onBeforeScroll: (() -> Unit)? = null,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val reducedMotion by rememberUpdatedState(HiTheme.isReducedMotion)
    val latestBeforeScroll by rememberUpdatedState(onBeforeScroll)
    return remember(scrollState, scope) {
        {
            latestBeforeScroll?.invoke()
            scope.launch { scrollState.hiScrollToTop(reducedMotion) }
        }
    }
}

/**
 * 设计系统统一纯色顶栏（零毛玻璃，`surface → surfaceContainer` 随滚动渐变）：
 * - 点按标题与空白区域触发 [onScrollToTop]，返回箭头、右侧动作按钮与底部插槽各自独立消费事件不误触发滚顶。
 */
@Composable
fun HiSolidTopBar(
    title: String,
    isScrolled: Boolean,
    modifier: Modifier = Modifier,
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    onScrollToTop: (() -> Unit)? = null,
    titleHorizontalPadding: Dp = 0.dp,
    endPadding: Dp = 8.dp,
    trailingAction: (@Composable RowScope.() -> Unit)? = null,
    bottomSlot: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val barColor by animateColorAsState(
        targetValue = if (isScrolled) cs.surfaceContainer else cs.surface,
        animationSpec = HiMotion.effectsDefault(),
        label = "HiSolidTopBarColor",
    )
    val hasBack = showBack && onBack != null

    Column(modifier.fillMaxWidth().background(barColor)) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.hiTopSafeBar))
        HiCappedFontScale {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(start = if (hasBack) 4.dp else 16.dp, end = endPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasBack) {
                    val backInteraction = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = onBack,
                        interactionSource = backInteraction,
                        modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(backInteraction),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_action_back),
                            tint = cs.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                val titleModifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (onScrollToTop != null) Modifier.hiTopBarTapToScrollTop(onScrollToTop, enabled = isScrolled) else Modifier)
                    .padding(horizontal = titleHorizontalPadding)
                Box(modifier = titleModifier, contentAlignment = Alignment.CenterStart) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                trailingAction?.invoke(this)
            }
        }
        bottomSlot?.invoke(this)
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
    }
}
