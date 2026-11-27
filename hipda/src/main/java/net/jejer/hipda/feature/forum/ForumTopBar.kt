package net.jejer.hipda.feature.forum

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.ForumListSource
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.hiTopSafeBar
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.glass.HiGlassState
import net.jejer.hipda.ui.designsystem.glass.HiGlassStyle
import net.jejer.hipda.ui.designsystem.glass.HiGlassSurface
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.motion.rememberHiMotionCoroutineScope
import net.jejer.hipda.ui.designsystem.topbar.hiTopBarTapToScrollTop

internal val FORUM_TOP_BAR_TITLE_ROW_HEIGHT: Dp = 56.dp
private const val TopBarSnapEpsilonPx: Float = 0.5f

@Immutable
internal data class BuyAndSellCategoryOption(val typeId: String, val labelRes: Int)

internal val BUY_AND_SELL_CATEGORIES: List<BuyAndSellCategoryOption> = listOf(
    BuyAndSellCategoryOption("", R.string.forum_bs_type_all),
    BuyAndSellCategoryOption("1", R.string.forum_bs_type_phone),
    BuyAndSellCategoryOption("2", R.string.forum_bs_type_pda),
    BuyAndSellCategoryOption("3", R.string.forum_bs_type_laptop),
    BuyAndSellCategoryOption("4", R.string.forum_bs_type_wireless),
    BuyAndSellCategoryOption("5", R.string.forum_bs_type_camera),
    BuyAndSellCategoryOption("6", R.string.forum_bs_type_mp3),
    BuyAndSellCategoryOption("7", R.string.forum_bs_type_accessories),
    BuyAndSellCategoryOption("8", R.string.forum_bs_type_other),
)

private val FilterListVectorIcon: ImageVector by lazy {
    ImageVector.Builder("ForumTopBar.FilterList", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4f, 6f); horizontalLineTo(20f)
            moveTo(7f, 12f); horizontalLineTo(17f)
            moveTo(10f, 18f); horizontalLineTo(14f)
        }
    }.build()
}

/** 论坛顶栏上行折叠与吸顶标签行联动状态（基于标准 `Animatable`，仅影响视觉偏移，不改 `LazyColumn` 固定 `contentPadding(top)`，闭合 B4-E1 ②）。 */
@Stable
class ForumTopBarScrollState internal constructor(
    private val scope: CoroutineScope,
    private val isReducedMotion: () -> Boolean,
) {
    private val offsetAnim = Animatable(0f)
    var maxCollapseOffsetPx: Float by mutableFloatStateOf(0f)
        private set
    val collapseOffsetPx: Float get() = offsetAnim.value
    val collapseFraction: Float get() = if (maxCollapseOffsetPx > 0f) (collapseOffsetPx / maxCollapseOffsetPx).coerceIn(0f, 1f) else 0f

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (maxCollapseOffsetPx <= 0f) return Offset.Zero
            val scrollUp = if (consumed.y > 0f || available.y > 0f) consumed.y.coerceAtLeast(0f) + available.y.coerceAtLeast(0f) else 0f
            val deltaY = if (scrollUp > 0f) scrollUp else consumed.y
            if (deltaY != 0f) snapOffsetTo((collapseOffsetPx - deltaY).coerceIn(0f, maxCollapseOffsetPx))
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (maxCollapseOffsetPx > 0f) animateOffsetTo(if (collapseOffsetPx > maxCollapseOffsetPx * 0.5f) maxCollapseOffsetPx else 0f)
            return Velocity.Zero
        }
    }

    fun expand() { if (maxCollapseOffsetPx > 0f || collapseOffsetPx != 0f) animateOffsetTo(0f) }

    internal fun syncMaxCollapseOffsetPx(newMaxPx: Float) {
        val clamped = newMaxPx.coerceAtLeast(0f)
        if (clamped == maxCollapseOffsetPx) return
        maxCollapseOffsetPx = clamped
        offsetAnim.updateBounds(0f, clamped)
        if (clamped == 0f || collapseOffsetPx > clamped) snapOffsetTo(collapseOffsetPx.coerceIn(0f, clamped))
    }

    private fun snapOffsetTo(target: Float) { scope.launch(start = CoroutineStart.UNDISPATCHED) { offsetAnim.snapTo(target) } }

    private fun animateOffsetTo(target: Float) {
        val clamped = target.coerceIn(0f, maxCollapseOffsetPx)
        if (isReducedMotion() || abs(collapseOffsetPx - clamped) < TopBarSnapEpsilonPx) { snapOffsetTo(clamped); return }
        scope.launch { offsetAnim.animateTo(clamped, HiMotion.spatialDefault(visibilityThreshold = TopBarSnapEpsilonPx)) }
    }
}

@Composable
fun rememberForumTopBarScrollState(collapsible: Boolean): ForumTopBarScrollState {
    val scope = rememberHiMotionCoroutineScope()
    val reducedMotion by rememberUpdatedState(HiTheme.isReducedMotion)
    val state = remember(scope) { ForumTopBarScrollState(scope) { reducedMotion } }
    val maxPx = with(LocalDensity.current) { if (collapsible) FORUM_TOP_BAR_TITLE_ROW_HEIGHT.toPx() else 0f }
    SideEffect { state.syncMaxCollapseOffsetPx(maxPx) }
    return state
}

@Composable
internal fun forumTopBarExpandedHeight(showForumTabs: Boolean): Dp {
    val density = LocalDensity.current
    val statusBar = with(density) { WindowInsets.hiTopSafeBar.getTop(density).toDp() }
    return statusBar + FORUM_TOP_BAR_TITLE_ROW_HEIGHT + if (showForumTabs) FORUM_TAB_ROW_HEIGHT else 0.dp
}

/** 论坛透明吸顶顶栏（严格兄弟玻璃拓扑）：`glassState != null` 渲染 1 块 `HiGlassStyle.TopBar` 玻璃，否则纯色 `surface → surfaceContainer`。 */
@Composable
fun ForumTopBar(
    title: String,
    source: ForumListSource,
    followedForums: List<Int>,
    sortByPostTime: Boolean,
    showForumTabs: Boolean,
    glassState: HiGlassState?,
    isScrolled: () -> Boolean,
    scrollState: ForumTopBarScrollState,
    onSelectSource: (ForumListSource) -> Unit,
    onNewThreadClick: () -> Unit,
    onSelectSortByPostTime: (Boolean) -> Unit,
    onManageForumsClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedTypeId: String? = null,
    onSelectTypeId: (String?) -> Unit = {},
    onScrollToTop: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val showCategoryFilter = source == ForumListSource.Forum(BUY_AND_SELL_FID)
    Box(modifier = modifier.fillMaxWidth().clipToBounds()) {
        ForumTopBarBackground(glassState, isScrolled, Modifier.matchParentSize().graphicsLayer { translationY = -scrollState.collapseOffsetPx })
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.hiTopSafeBar))
            Box(Modifier.fillMaxWidth().clipToBounds()) {
                Column(Modifier.fillMaxWidth().graphicsLayer { translationY = -scrollState.collapseOffsetPx }) {
                    ForumTopBarUpperRow(
                        title = title,
                        showCategoryFilter = showCategoryFilter,
                        selectedTypeId = if (showCategoryFilter) selectedTypeId else null,
                        showSortMenu = source is ForumListSource.Forum,
                        sortByPostTime = sortByPostTime,
                        scrollState = scrollState,
                        onSelectTypeId = onSelectTypeId,
                        onNewThreadClick = onNewThreadClick,
                        onSelectSortByPostTime = onSelectSortByPostTime,
                        onScrollToTop = onScrollToTop,
                    )
                    if (showForumTabs) BalancedForumTabRow(source, followedForums, onSelectSource, onManageForumsClick)
                    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun ForumTopBarBackground(glassState: HiGlassState?, isScrolled: () -> Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val scrolled = isScrolled()
    if (glassState != null) {
        val glassAlpha = animateFloatAsState(if (scrolled) 1f else 0f, HiMotion.effectsDefault(), label = "ForumTopBarGlassAlpha")
        val surfaceColor = cs.surface
        Box(modifier) {
            HiGlassSurface(state = glassState, style = HiGlassStyle.TopBar, shape = RectangleShape, floating = false, modifier = Modifier.matchParentSize().graphicsLayer { alpha = glassAlpha.value }) {}
            Box(Modifier.matchParentSize().drawBehind {
                val solidAlpha = (1f - glassAlpha.value).coerceIn(0f, 1f)
                if (solidAlpha > 0.001f) drawRect(surfaceColor.copy(alpha = solidAlpha))
            })
        }
    } else {
        val solidColor = animateColorAsState(if (scrolled) cs.surfaceContainer else cs.surface, HiMotion.effectsDefault(), label = "ForumTopBarSolidColor")
        Box(modifier.drawBehind { drawRect(solidColor.value) })
    }
}

@Composable
private fun ForumTopBarUpperRow(
    title: String,
    showCategoryFilter: Boolean,
    selectedTypeId: String?,
    showSortMenu: Boolean,
    sortByPostTime: Boolean,
    scrollState: ForumTopBarScrollState,
    onSelectTypeId: (String?) -> Unit,
    onNewThreadClick: () -> Unit,
    onSelectSortByPostTime: (Boolean) -> Unit,
    onScrollToTop: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    var menuExpanded by remember { mutableStateOf(false) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    val normalizedSelectedTypeId = selectedTypeId?.trim().orEmpty()
    val activeCategory = remember(normalizedSelectedTypeId) {
        BUY_AND_SELL_CATEGORIES.firstOrNull { it.typeId == normalizedSelectedTypeId } ?: BUY_AND_SELL_CATEGORIES.first()
    }
    val activeCategoryLabel = stringResource(activeCategory.labelRes)

    HiCappedFontScale {
        Row(
            modifier = Modifier.fillMaxWidth().height(FORUM_TOP_BAR_TITLE_ROW_HEIGHT)
                .graphicsLayer { alpha = (1f - scrollState.collapseFraction).coerceIn(0f, 1f) }
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (onScrollToTop != null) Modifier.hiTopBarTapToScrollTop(onScrollToTop) else Modifier),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showCategoryFilter) {
                Box {
                    val filterInteraction = remember { MutableInteractionSource() }
                    val isFiltered = normalizedSelectedTypeId.isNotEmpty()
                    IconButton(
                        onClick = { categoryMenuExpanded = true },
                        interactionSource = filterInteraction,
                        modifier = Modifier
                            .defaultMinSize(48.dp, 48.dp)
                            .hiPressScale(filterInteraction)
                            .semantics { stateDescription = activeCategoryLabel },
                    ) {
                        Box(
                            modifier = if (isFiltered) {
                                Modifier.size(36.dp).clip(HiShapeTokens.Full).background(cs.primaryContainer)
                            } else {
                                Modifier.size(36.dp)
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = FilterListVectorIcon,
                                contentDescription = stringResource(R.string.forum_action_filter_type),
                                tint = if (isFiltered) cs.onPrimaryContainer else cs.onSurface,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = categoryMenuExpanded,
                        onDismissRequest = { categoryMenuExpanded = false },
                        modifier = Modifier.background(cs.surfaceContainerHigh),
                    ) {
                        BUY_AND_SELL_CATEGORIES.forEach { category ->
                            val isSelected = category.typeId == normalizedSelectedTypeId
                            DropdownMenuItem(
                                text = { Text(stringResource(category.labelRes)) },
                                trailingIcon = if (isSelected) ({ Icon(Icons.Rounded.Check, null, tint = cs.primary, modifier = Modifier.size(18.dp)) }) else null,
                                onClick = {
                                    categoryMenuExpanded = false
                                    onSelectTypeId(category.typeId.ifEmpty { null })
                                },
                            )
                        }
                    }
                }
            }
            val newThreadInteraction = remember { MutableInteractionSource() }
            IconButton(onClick = onNewThreadClick, interactionSource = newThreadInteraction, modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(newThreadInteraction)) {
                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.forum_action_new_thread), tint = cs.onSurface, modifier = Modifier.size(22.dp))
            }
            if (showSortMenu) {
                Box {
                    val moreInteraction = remember { MutableInteractionSource() }
                    IconButton(onClick = { menuExpanded = true }, interactionSource = moreInteraction, modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(moreInteraction)) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.forum_action_more), tint = cs.onSurface, modifier = Modifier.size(22.dp))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, modifier = Modifier.background(cs.surfaceContainerHigh)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.forum_sort_by_reply)) },
                            trailingIcon = if (!sortByPostTime) ({ Icon(Icons.Rounded.Check, null, tint = cs.primary, modifier = Modifier.size(18.dp)) }) else null,
                            onClick = { menuExpanded = false; if (sortByPostTime) onSelectSortByPostTime(false) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.forum_sort_by_post)) },
                            trailingIcon = if (sortByPostTime) ({ Icon(Icons.Rounded.Check, null, tint = cs.primary, modifier = Modifier.size(18.dp)) }) else null,
                            onClick = { menuExpanded = false; if (!sortByPostTime) onSelectSortByPostTime(true) },
                        )
                    }
                }
            }
        }
    }
}
