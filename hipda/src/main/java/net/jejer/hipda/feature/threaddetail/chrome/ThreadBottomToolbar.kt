package net.jejer.hipda.feature.threaddetail.chrome

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.SafeLinkPolicy
import net.jejer.hipda.feature.routing.AndroidExternalLinkOpener
import net.jejer.hipda.feature.routing.ExternalLinkOpener
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.HiIconSwitch
import net.jejer.hipda.ui.designsystem.feedback.HiRollingNumber
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.glass.HiGlassRegion
import net.jejer.hipda.ui.designsystem.glass.HiGlassState
import net.jejer.hipda.ui.designsystem.glass.HiGlassStyle
import net.jejer.hipda.ui.designsystem.glass.HiGlassSurface
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.motion.*

/** 帖子详情底部三段工具条基础高度（与触控区最小高对齐）。 */
val ThreadBottomToolbarHeight: Dp = 48.dp

/** 工具条距底部手势区/窗口底边间距（`product-design.md` §5）。 */
val ThreadBottomToolbarBottomGap: Dp = 8.dp

/** 供楼层 `LazyColumn` 固定预留的底部留白，不随内联跳页展开/收起、抑制底栏或 IME 弹出跳动。 */
val ThreadBottomToolbarClearance: Dp = ThreadBottomToolbarHeight + ThreadBottomToolbarBottomGap + 8.dp

private val ToolbarHorizontalMargin: Dp = 16.dp
private val ToolbarSegmentGap: Dp = 8.dp
private val ToolbarCollapsedMaxWidth: Dp = 560.dp
private val RightCapsuleWidth: Dp = 96.dp
private const val PROGRESS_FILL_LIGHT_ALPHA: Float = 0.48f
private const val PROGRESS_FILL_DARK_ALPHA: Float = 0.44f

/** 中段阅读进度胶囊的填充色与前景文字色解析结果（遵守 `V-C4d` 对比度契约）。 */
@Immutable
data class ProgressCapsuleColors(val fillColor: Color, val contentColor: Color)

/** 解析阅读进度胶囊半透明进度填充色与前景文字色（`V-C4d`：全 20 套预设 × 浅/深/OLED 对比度 ≥ 4.5:1）。 */
fun resolveProgressCapsuleColors(colorScheme: ColorScheme, darkTheme: Boolean): ProgressCapsuleColors {
    val fillAlpha = if (darkTheme) {
        PROGRESS_FILL_DARK_ALPHA.coerceAtMost(HiGlassTokens.ProgressFillContainerDarkMaxAlpha)
    } else {
        PROGRESS_FILL_LIGHT_ALPHA.coerceAtMost(HiGlassTokens.ProgressFillContainerLightMaxAlpha)
    }
    return ProgressCapsuleColors(colorScheme.primaryContainer.copy(alpha = fillAlpha), colorScheme.onSurface)
}

/** 外部链接 scheme 安全白名单（`DISPATCH.md` §4）：委托至 [SafeLinkPolicy.sanitizeHttpUrl]，仅允许无 `rawUserInfo` 的 `http`/`https` URL。 */
fun sanitizeExternalHttpUrl(rawUrl: String?): String? = SafeLinkPolicy.sanitizeHttpUrl(rawUrl)

/** 根据论坛服务器地址、帖子 `tid` 与当前页码构造经 [sanitizeExternalHttpUrl] 校验的帖子链接。 */
fun buildSafeThreadUrl(forumServer: String, tid: String, page: Int = 1): String? {
    val cleanTid = tid.trim()
    if (cleanTid.isEmpty() || !cleanTid.all { it in '0'..'9' }) return null
    val trimmedServer = forumServer.trim().trimEnd('/')
    if (trimmedServer.isEmpty()) return null
    val forumBase = if (trimmedServer.endsWith("/forum", ignoreCase = true)) "$trimmedServer/" else "$trimmedServer/forum/"
    val resolvedPage = page.coerceAtLeast(1)
    val raw = if (resolvedPage > 1) "${forumBase}viewthread.php?tid=$cleanTid&page=$resolvedPage" else "${forumBase}viewthread.php?tid=$cleanTid"
    return sanitizeExternalHttpUrl(raw)
}

/** 严格仅使用 `ClipData.newPlainText` 复制经白名单校验的帖子纯文本链接。 */
fun copyThreadLinkPlainText(context: Context, label: String, rawUrl: String?): Boolean {
    val safeUrl = sanitizeExternalHttpUrl(rawUrl) ?: return false
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    return runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(label, safeUrl)) }.isSuccess
}

/** 仅允许经 [sanitizeExternalHttpUrl] 校验的 `http`/`https` URL 经 [AndroidExternalLinkOpener] 交给系统浏览器，危险 scheme 直接拦截。 */
fun openExternalHttpUrl(
    context: Context,
    rawUrl: String?,
    externalLinkOpener: ExternalLinkOpener? = null,
    startIntent: (Intent) -> Unit = { context.startActivity(it) },
): Boolean {
    val safeUrl = sanitizeExternalHttpUrl(rawUrl) ?: return false
    return (externalLinkOpener ?: AndroidExternalLinkOpener(context, startIntent)).open(safeUrl)
}

/** 仅允许经 [sanitizeExternalHttpUrl] 校验的 `http`/`https` URL 构造系统纯文本分享 Intent，危险 scheme 直接拦截。 */
fun shareExternalHttpUrl(
    context: Context,
    title: String,
    rawUrl: String?,
    chooserTitle: String,
    startIntent: (Intent) -> Unit = { context.startActivity(it) },
): Boolean {
    val safeUrl = sanitizeExternalHttpUrl(rawUrl) ?: return false
    val cleanTitle = title.trim()
    val payload = if (cleanTitle.isNotEmpty()) "$cleanTitle\n$safeUrl" else safeUrl
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        if (cleanTitle.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, cleanTitle)
        putExtra(Intent.EXTRA_TEXT, payload)
    }
    val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching { startIntent(chooser) }.isSuccess
}

/**
 * 帖子详情底部三段式液态玻璃工具条（含中段原地展开 [InlinePageJumpBar]）：
 * - [suppressBottomGlass] 为 `true` 时整块卸载外层 `HiGlassRegion`（闭合 `B4-E4 ②`）；
 * - 正常态单个 `HiGlassRegion`（1 个 `HiGlassMarker`）包裹 3 枚 `HiGlassStyle.Toolbar` 胶囊，仅自身外层承载 `imePadding()`。
 */
@Composable
fun ThreadBottomToolbar(
    page: Int,
    lastPage: Int,
    isFavorited: Boolean,
    isAttention: Boolean,
    glassState: HiGlassState,
    suppressBottomGlass: Boolean,
    isJumpBarExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onReplyClick: () -> Unit,
    onJumpToPage: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onRefreshClick: () -> Unit,
    onToggleAttention: () -> Unit,
    onCopyLinkClick: () -> Unit,
    onOpenInBrowserClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    // 闭合 R-BATCH5 B4-E4 ②：suppressBottomGlass=true 时必须整块卸载外层 HiGlassRegion，禁止留在树上仅隐藏子胶囊
    if (suppressBottomGlass) return

    val safeLastPage = lastPage.coerceAtLeast(1)
    val safePage = page.coerceIn(1, safeLastPage)
    var moreMenuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(isJumpBarExpanded) { if (isJumpBarExpanded) moreMenuExpanded = false }
    BackHandler(enabled = isJumpBarExpanded) { onExpandedChange(false) }

    val cs = MaterialTheme.colorScheme
    val darkTheme = HiTheme.state.darkTheme
    val progressColors = remember(cs, darkTheme) { resolveProgressCapsuleColors(cs, darkTheme) }
    val progressFractionState = animateFloatAsState(
        targetValue = (safePage.toFloat() / safeLastPage.toFloat()).coerceIn(0f, 1f),
        animationSpec = HiMotion.effectsDefault(),
        label = "ThreadProgressFill",
    )

    val replyLabel = stringResource(R.string.thread_detail_toolbar_reply)
    val favoriteDesc = stringResource(if (isFavorited) R.string.thread_detail_toolbar_unfavorite else R.string.thread_detail_toolbar_favorite)
    val moreDesc = stringResource(R.string.thread_detail_toolbar_more)
    val capsuleDesc = stringResource(R.string.thread_detail_page_capsule_description, safePage, safeLastPage)
    val fullPageMeasureSample = stringResource(R.string.common_page_indicator, safeLastPage, safeLastPage)
    val compactPageMeasureSample = stringResource(R.string.thread_detail_page_indicator_compact, safeLastPage, safeLastPage)
    val tabularLabelStyle = MaterialTheme.typography.labelLarge.let { style -> remember(style) { style.copy(fontFeatureSettings = "tnum") } }

    Box(modifier = modifier.fillMaxSize()) {
        if (isJumpBarExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onExpandedChange(false) },
            )
        }

        val imeBottomDp = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        val effectiveBottomInset = maxOf(bottomInset, imeBottomDp)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = effectiveBottomInset + ThreadBottomToolbarBottomGap)
                .padding(horizontal = ToolbarHorizontalMargin),
            contentAlignment = Alignment.BottomCenter,
        ) {
            HiCappedFontScale {
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                    val availableWidth = maxWidth
                    val containerWidth = availableWidth + ToolbarHorizontalMargin * 2
                    val density = LocalDensity.current
                    val textMeasurer = rememberTextMeasurer()

                    val replyTextWidthDp = remember(replyLabel, tabularLabelStyle, density) {
                        with(density) { textMeasurer.measure(replyLabel, style = tabularLabelStyle, maxLines = 1).size.width.toDp() }
                    }
                    val fullPageTextWidthDp = remember(fullPageMeasureSample, tabularLabelStyle, density) {
                        with(density) { textMeasurer.measure(fullPageMeasureSample, style = tabularLabelStyle, maxLines = 1).size.width.toDp() }
                    }
                    val compactPageTextWidthDp = remember(compactPageMeasureSample, tabularLabelStyle, density) {
                        with(density) { textMeasurer.measure(compactPageMeasureSample, style = tabularLabelStyle, maxLines = 1).size.width.toDp() }
                    }

                    val fullReplyWidth = (18.dp + 6.dp + replyTextWidthDp + 32.dp).coerceAtLeast(88.dp)
                    val fullMiddleMinWidth = (fullPageTextWidthDp + 40.dp).coerceAtLeast((124f * density.fontScale).dp)
                    val compactMiddleMinWidth = (compactPageTextWidthDp + 24.dp).coerceAtLeast(64.dp)
                    val isCompact = fullReplyWidth + ToolbarSegmentGap + fullMiddleMinWidth + ToolbarSegmentGap + RightCapsuleWidth > availableWidth

                    val leftWidth = if (isCompact) ThreadBottomToolbarHeight else fullReplyWidth
                    val collapsedBarWidth = availableWidth.coerceAtMost(ToolbarCollapsedMaxWidth)
                    val collapsedMiddleWidth = (collapsedBarWidth - leftWidth - RightCapsuleWidth - ToolbarSegmentGap * 2).coerceAtLeast(compactMiddleMinWidth)
                    val groupStart = ((availableWidth - collapsedBarWidth) / 2f).coerceAtLeast(0.dp)
                    val collapsedMiddleStart = groupStart + leftWidth + ToolbarSegmentGap
                    val rightStart = collapsedMiddleStart + collapsedMiddleWidth + ToolbarSegmentGap

                    val reducedMotion = HiTheme.isReducedMotion
                    val isCompactJumpBar = containerWidth < InlinePageJumpCompactBreakpoint
                    val expandedJumpHeight = if (isCompactJumpBar) InlinePageJumpTwoRowHeight else InlinePageJumpSingleRowHeight
                    val targetMiddleWidth = if (isJumpBarExpanded) availableWidth else collapsedMiddleWidth
                    val targetMiddleOffsetX = if (isJumpBarExpanded) 0.dp else collapsedMiddleStart
                    val targetMiddleHeight = if (isJumpBarExpanded) expandedJumpHeight else ThreadBottomToolbarHeight
                    val widthSpec = if (isJumpBarExpanded) HiMotion.spatialDefault(Dp.VisibilityThreshold) else HiMotion.exit(HiMotion.spatialDefault(Dp.VisibilityThreshold))
                    val animatedMiddleWidth by animateDpAsState(targetMiddleWidth, widthSpec, label = "ThreadToolbarMiddleWidth")
                    val animatedMiddleOffsetX by animateDpAsState(targetMiddleOffsetX, widthSpec, label = "ThreadToolbarMiddleOffsetX")
                    val animatedMiddleHeight by animateDpAsState(targetMiddleHeight, widthSpec, label = "ThreadToolbarMiddleHeight")
                    val effectiveMiddleWidth = if (reducedMotion) targetMiddleWidth else animatedMiddleWidth
                    val effectiveMiddleOffsetX = if (reducedMotion) targetMiddleOffsetX else animatedMiddleOffsetX
                    val effectiveMiddleHeight = if (reducedMotion) targetMiddleHeight else animatedMiddleHeight

                    // 整块底部工具条（三段胶囊及展开态内联跳页条）共用 1 个 HiGlassRegion（1 个 HiGlassMarker）
                    HiGlassRegion(modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                            // 1. 左段：回复胶囊
                            AnimatedVisibility(
                                visible = !isJumpBarExpanded,
                                modifier = Modifier.offset { IntOffset(groupStart.roundToPx(), 0) },
                                enter = hiFadeIn(HiMotion.effectsFast()) + hiSlideInHorizontally(HiMotion.spatialFast()) { -it / 2 },
                                exit = hiFadeOut(HiMotion.exit(HiMotion.effectsFast())) + hiSlideOutHorizontally(HiMotion.exit(HiMotion.spatialFast())) { -it / 2 },
                                label = "ThreadToolbarReplyCapsule",
                            ) {
                                val replyInteraction = remember { MutableInteractionSource() }
                                HiGlassSurface(
                                    state = glassState, style = HiGlassStyle.Toolbar, shape = HiShapeTokens.Full, floating = true,
                                    modifier = Modifier.width(leftWidth).height(ThreadBottomToolbarHeight).hiPressScale(replyInteraction),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clickable(replyInteraction, LocalIndication.current, role = Role.Button, onClick = onReplyClick)
                                            .padding(horizontal = if (isCompact) 0.dp else 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        Icon(Icons.Rounded.Edit, contentDescription = if (isCompact) replyLabel else null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                                        if (!isCompact) {
                                            Spacer(Modifier.width(6.dp))
                                            Text(replyLabel, style = tabularLabelStyle, color = cs.onSurface, maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }

                            // 2. 右段：收藏 + 更多双操作胶囊
                            AnimatedVisibility(
                                visible = !isJumpBarExpanded,
                                modifier = Modifier.offset { IntOffset(rightStart.roundToPx(), 0) },
                                enter = hiFadeIn(HiMotion.effectsFast()) + hiSlideInHorizontally(HiMotion.spatialFast()) { it / 2 },
                                exit = hiFadeOut(HiMotion.exit(HiMotion.effectsFast())) + hiSlideOutHorizontally(HiMotion.exit(HiMotion.spatialFast())) { it / 2 },
                                label = "ThreadToolbarActionsCapsule",
                            ) {
                                HiGlassSurface(
                                    state = glassState, style = HiGlassStyle.Toolbar, shape = HiShapeTokens.Full, floating = true,
                                    modifier = Modifier.width(RightCapsuleWidth).height(ThreadBottomToolbarHeight),
                                ) {
                                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                        val favInteraction = remember { MutableInteractionSource() }
                                        Box(
                                            modifier = Modifier
                                                .size(ThreadBottomToolbarHeight)
                                                .hiPressScale(favInteraction)
                                                .clip(HiShapeTokens.Full)
                                                .clickable(favInteraction, LocalIndication.current, role = Role.Button, onClick = onToggleFavorite),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            HiIconSwitch(
                                                target = if (isFavorited) Icons.Rounded.Star else ToolbarVectorIcons.StarOutline,
                                                contentDescription = favoriteDesc,
                                                tint = if (isFavorited) cs.primary else cs.onSurface,
                                                iconSize = 22.dp,
                                            )
                                        }

                                        val moreInteraction = remember { MutableInteractionSource() }
                                        Box(Modifier.size(ThreadBottomToolbarHeight), contentAlignment = Alignment.Center) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .hiPressScale(moreInteraction)
                                                    .clip(HiShapeTokens.Full)
                                                    .clickable(moreInteraction, LocalIndication.current, role = Role.Button) { moreMenuExpanded = true },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(Icons.Rounded.MoreVert, contentDescription = moreDesc, tint = cs.onSurface, modifier = Modifier.size(22.dp))
                                            }
                                            ThreadMoreDropdownMenu(
                                                expanded = moreMenuExpanded, isAttention = isAttention,
                                                onDismiss = { moreMenuExpanded = false }, onRefresh = onRefreshClick,
                                                onToggleAttention = onToggleAttention, onCopyLink = onCopyLinkClick,
                                                onOpenInBrowser = onOpenInBrowserClick, onShare = onShareClick,
                                            )
                                        }
                                    }
                                }
                            }

                            // 3. 中段：阅读进度胶囊 ↔ 原地展开内联跳页条
                            HiGlassSurface(
                                state = glassState,
                                style = HiGlassStyle.Toolbar,
                                shape = if (isJumpBarExpanded && isCompactJumpBar) HiShapeTokens.Large else HiShapeTokens.Full,
                                floating = true,
                                modifier = Modifier
                                    .offset { IntOffset(effectiveMiddleOffsetX.roundToPx(), 0) }
                                    .width(effectiveMiddleWidth)
                                    .height(effectiveMiddleHeight),
                            ) {
                                if (isJumpBarExpanded) {
                                    Box(Modifier.requiredSize(availableWidth, expandedJumpHeight), contentAlignment = Alignment.Center) {
                                        InlinePageJumpBar(
                                            currentPage = safePage, lastPage = safeLastPage, containerWidth = containerWidth,
                                            onJumpToPage = { targetPage -> onExpandedChange(false); onJumpToPage(targetPage) },
                                            onCollapse = { onExpandedChange(false) },
                                        )
                                    }
                                } else {
                                    val progressInteraction = remember { MutableInteractionSource() }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(ThreadBottomToolbarHeight)
                                            .hiPressScale(progressInteraction)
                                            .drawBehind {
                                                val fraction = progressFractionState.value.coerceIn(0f, 1f)
                                                if (fraction > 0f) drawRect(progressColors.fillColor, size = Size(size.width * fraction, size.height))
                                            }
                                            .clickable(progressInteraction, LocalIndication.current, role = Role.Button) { onExpandedChange(true) }
                                            .semantics(mergeDescendants = true) { contentDescription = capsuleDesc },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                            if (isCompact) {
                                                HiRollingNumber(safePage, style = tabularLabelStyle, color = progressColors.contentColor)
                                                Text("/$safeLastPage", style = tabularLabelStyle, color = progressColors.contentColor, maxLines = 1, softWrap = false)
                                            } else {
                                                Text("第 ", style = tabularLabelStyle, color = progressColors.contentColor, maxLines = 1, softWrap = false)
                                                HiRollingNumber(safePage, style = tabularLabelStyle, color = progressColors.contentColor)
                                                Text(" / $safeLastPage 页", style = tabularLabelStyle, color = progressColors.contentColor, maxLines = 1, softWrap = false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadMoreDropdownMenu(
    expanded: Boolean,
    isAttention: Boolean,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onToggleAttention: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onShare: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        ThreadMoreMenuItem(stringResource(R.string.common_action_refresh)) { onDismiss(); onRefresh() }
        ThreadMoreMenuItem(stringResource(if (isAttention) R.string.thread_detail_more_unattention else R.string.thread_detail_more_attention)) { onDismiss(); onToggleAttention() }
        ThreadMoreMenuItem(stringResource(R.string.thread_detail_more_copy_link)) { onDismiss(); onCopyLink() }
        ThreadMoreMenuItem(stringResource(R.string.thread_detail_more_open_in_browser)) { onDismiss(); onOpenInBrowser() }
        ThreadMoreMenuItem(stringResource(R.string.thread_detail_more_share)) { onDismiss(); onShare() }
    }
}

@Composable
private fun ThreadMoreMenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label, maxLines = 1) }, onClick = onClick, modifier = Modifier.heightIn(min = 48.dp))
}

private object ToolbarVectorIcons {
    private val strokeBrush = SolidColor(Color.Black)

    val StarOutline: ImageVector = ImageVector.Builder(
        name = "ThreadToolbar.StarOutline", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(stroke = strokeBrush, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 3.2f); lineTo(14.7f, 8.7f); lineTo(20.8f, 9.6f); lineTo(16.4f, 13.9f); lineTo(17.4f, 20f)
            lineTo(12f, 17.1f); lineTo(6.6f, 20f); lineTo(7.6f, 13.9f); lineTo(3.2f, 9.6f); lineTo(9.3f, 8.7f); close()
        }
    }.build()
}
