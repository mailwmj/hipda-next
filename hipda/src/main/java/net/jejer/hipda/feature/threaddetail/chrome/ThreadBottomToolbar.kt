package net.jejer.hipda.feature.threaddetail.chrome

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.feature.common.SafeLinkPolicy
import net.jejer.hipda.feature.routing.AndroidExternalLinkOpener
import net.jejer.hipda.feature.routing.ExternalLinkOpener
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.HiIconSwitch
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.glass.HiGlassRegion
import net.jejer.hipda.ui.designsystem.glass.HiGlassState
import net.jejer.hipda.ui.designsystem.glass.HiGlassStyle
import net.jejer.hipda.ui.designsystem.glass.HiGlassSurface
import net.jejer.hipda.ui.designsystem.motion.*

/** 帖子详情底部一整条液态玻璃工具条基础高度（与触控区最小高对齐）。 */
val ThreadBottomToolbarHeight: Dp = 48.dp

/** 工具条距底部手势区/窗口底边间距（`product-design.md` §5）。 */
val ThreadBottomToolbarBottomGap: Dp = 8.dp

/** 供楼层 `LazyColumn` 固定预留的底部留白，不随内联楼层/跳页条展开收起、抑制底栏或 IME 弹出跳动。 */
val ThreadBottomToolbarClearance: Dp = ThreadBottomToolbarHeight + ThreadBottomToolbarBottomGap + 8.dp

private val ToolbarHorizontalMargin: Dp = 16.dp
private val ToolbarCollapsedMaxWidth: Dp = 560.dp
private val ToolbarInnerStartPadding: Dp = 6.dp
private val ToolbarInnerEndPadding: Dp = 4.dp
private val ReplySlotVisualHeight: Dp = 36.dp
private val RightActionsWidth: Dp = ThreadBottomToolbarHeight * 2

/** 底部一体化工具条内左侧轻回复槽微底色（守 WCAG 4.5:1 对比度）。 */
internal fun resolveReplySlotBackground(colorScheme: ColorScheme, darkTheme: Boolean): Color =
    colorScheme.onSurface.copy(alpha = if (darkTheme) 0.08f else 0.05f)

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
 * 帖子详情底部一整条液态玻璃工具条（点击当前楼层 `{N}楼` 原地展开 [InlinePageJumpBar] 呈现顶楼/最新回复/楼层或页码切换）：
 * - [suppressBottomGlass] 为 `true` 时整块卸载外层 `HiGlassRegion`（闭合 `B4-E4 ②`）；
 * - 正常态单个 `HiGlassRegion` + 单个 `HiGlassSurface`（1 个 `HiGlassMarker`、1 次 `drawBackdrop` pass），仅自身外层承载 `imePadding()`；
 * - 左区柔和内凹轻回复槽 + 当前楼层可点按钮 `{N}楼` + `收藏` + `更多`（顶部分组收纳 `只看楼主`）。
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
    currentFloor: Int = 1,
    totalFloors: Int = currentFloor.coerceAtLeast(1),
    isAuthorOnly: Boolean = false,
    isAtTop: Boolean = page <= 1 && currentFloor <= 1,
    isAtBottom: Boolean = if (lastPage > 1) page >= lastPage else currentFloor >= totalFloors.coerceAtLeast(1),
    onToggleAuthorOnly: () -> Unit = {},
    onJumpToFirstFloor: () -> Unit = { onJumpToPage(1) },
    onJumpToLatestReply: () -> Unit = { onJumpToPage(lastPage.coerceAtLeast(1)) },
    onJumpToFloorNumber: (Int) -> Unit = {},
) {
    // 闭合 R-BATCH5 B4-E4 ②：suppressBottomGlass=true 时必须整块卸载外层 HiGlassRegion，禁止留在树上仅隐藏子内容
    if (suppressBottomGlass) return

    val safeLastPage = lastPage.coerceAtLeast(1)
    val safePage = page.coerceIn(1, safeLastPage)
    val safeFloor = currentFloor.coerceAtLeast(1)
    val safeTotalFloors = totalFloors.coerceAtLeast(1)
    val hasJumpSlider = safeLastPage > 1 || safeTotalFloors > 1
    var moreMenuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(isJumpBarExpanded) { if (isJumpBarExpanded) moreMenuExpanded = false }
    BackHandler(enabled = isJumpBarExpanded) { onExpandedChange(false) }

    val cs = MaterialTheme.colorScheme
    val darkTheme = HiTheme.state.darkTheme
    val replyLabel = stringResource(R.string.thread_detail_toolbar_reply)
    val favoriteDesc = stringResource(if (isFavorited) R.string.thread_detail_toolbar_unfavorite else R.string.thread_detail_toolbar_favorite)
    val moreDesc = stringResource(R.string.thread_detail_toolbar_more)
    val floorLabel = stringResource(R.string.common_floor_badge, safeFloor)
    val capsuleDesc = stringResource(R.string.thread_detail_floor_capsule_description_jumpable, safeFloor)
    val replyTextStyle = MaterialTheme.typography.labelLarge
    val tabularLabelStyle = MaterialTheme.typography.labelLarge.let { style -> remember(style) { style.copy(fontFeatureSettings = "tnum") } }
    val replySlotBg = remember(cs, darkTheme) { resolveReplySlotBackground(cs, darkTheme) }

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
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    val availableWidth = maxWidth
                    val containerWidth = availableWidth + ToolbarHorizontalMargin * 2
                    val density = LocalDensity.current
                    val textMeasurer = rememberTextMeasurer()

                    val replyTextWidthDp = remember(replyLabel, replyTextStyle, density) {
                        with(density) { textMeasurer.measure(replyLabel, style = replyTextStyle, maxLines = 1).size.width.toDp() }
                    }
                    val floorTextWidthDp = remember(floorLabel, tabularLabelStyle, density) {
                        with(density) { textMeasurer.measure(floorLabel, style = tabularLabelStyle, maxLines = 1).size.width.toDp() }
                    }

                    val collapsedBarWidth = availableWidth.coerceAtMost(ToolbarCollapsedMaxWidth)
                    val floorButtonWidth = (floorTextWidthDp + 20.dp).coerceAtLeast(ThreadBottomToolbarHeight)
                    val innerHorizontalPadding = ToolbarInnerStartPadding + ToolbarInnerEndPadding
                    val minFullReplyWidth = 18.dp + 8.dp + replyTextWidthDp + 28.dp
                    val remainingReplyWidth = collapsedBarWidth - innerHorizontalPadding - floorButtonWidth - RightActionsWidth
                    val isCompactReply = remainingReplyWidth < minFullReplyWidth

                    val reducedMotion = HiTheme.isReducedMotion
                    val isTwoRowJumpBar = hasJumpSlider && containerWidth < InlinePageJumpCompactBreakpoint
                    val expandedJumpHeight = if (isTwoRowJumpBar) InlinePageJumpTwoRowHeight else InlinePageJumpSingleRowHeight
                    val targetWidth = if (isJumpBarExpanded) availableWidth else collapsedBarWidth
                    val targetHeight = if (isJumpBarExpanded) expandedJumpHeight else ThreadBottomToolbarHeight
                    val sizeSpec = if (isJumpBarExpanded) {
                        HiMotion.spatialDefault(Dp.VisibilityThreshold)
                    } else {
                        HiMotion.exit(HiMotion.spatialDefault(Dp.VisibilityThreshold))
                    }
                    val animatedWidth by animateDpAsState(targetWidth, sizeSpec, label = "ThreadToolbarUnifiedWidth")
                    val animatedHeight by animateDpAsState(targetHeight, sizeSpec, label = "ThreadToolbarUnifiedHeight")
                    val effectiveWidth = if (reducedMotion) targetWidth else animatedWidth
                    val effectiveHeight = if (reducedMotion) targetHeight else animatedHeight

                    // 整块底部工具条（收起态与展开态内联楼层/跳页条）共用单个 HiGlassRegion + 单个 HiGlassSurface（1 Marker，1 drawBackdrop pass）
                    HiGlassRegion(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        HiGlassSurface(
                            state = glassState,
                            style = HiGlassStyle.Toolbar,
                            shape = if (isJumpBarExpanded && isTwoRowJumpBar) HiShapeTokens.Large else HiShapeTokens.Full,
                            floating = true,
                            modifier = Modifier.width(effectiveWidth).height(effectiveHeight),
                        ) {
                            AnimatedContent(
                                targetState = isJumpBarExpanded,
                                transitionSpec = {
                                    (
                                        hiFadeIn(HiMotion.effectsFast()) togetherWith
                                            hiFadeOut(HiMotion.exit(HiMotion.effectsFast()))
                                        ).using(null)
                                },
                                contentAlignment = Alignment.Center,
                                label = "ThreadBottomToolbarContent",
                            ) { expanded ->
                                if (expanded) {
                                    Box(Modifier.requiredSize(availableWidth, expandedJumpHeight), contentAlignment = Alignment.Center) {
                                        InlinePageJumpBar(
                                            currentPage = safePage,
                                            lastPage = safeLastPage,
                                            containerWidth = containerWidth,
                                            onJumpToPage = { targetPage -> onExpandedChange(false); onJumpToPage(targetPage) },
                                            onCollapse = { onExpandedChange(false) },
                                            currentFloor = safeFloor,
                                            totalFloors = safeTotalFloors,
                                            isAtTop = isAtTop,
                                            isAtBottom = isAtBottom,
                                            onJumpToFirstFloor = { onExpandedChange(false); onJumpToFirstFloor() },
                                            onJumpToLatestReply = { onExpandedChange(false); onJumpToLatestReply() },
                                            onJumpToFloorNumber = { targetFloor -> onExpandedChange(false); onJumpToFloorNumber(targetFloor) },
                                        )
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(start = ToolbarInnerStartPadding, end = ToolbarInnerEndPadding),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        // 1. 左侧柔和内凹轻输入回复槽（极窄自动退为居中 48dp 纯图标）
                                        val replyInteraction = remember { MutableInteractionSource() }
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .hiPressScale(replyInteraction)
                                                .clip(HiShapeTokens.Full)
                                                .clickable(replyInteraction, LocalIndication.current, role = Role.Button, onClick = onReplyClick),
                                            contentAlignment = Alignment.CenterStart,
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(ReplySlotVisualHeight)
                                                    .clip(HiShapeTokens.Full)
                                                    .background(replySlotBg)
                                                    .padding(horizontal = if (isCompactReply) 0.dp else 14.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = if (isCompactReply) Arrangement.Center else Arrangement.Start,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Edit,
                                                    contentDescription = if (isCompactReply) replyLabel else null,
                                                    tint = cs.onSurface,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                if (!isCompactReply) {
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(replyLabel, style = replyTextStyle, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false)
                                                }
                                            }
                                        }

                                        // 2. 当前楼层入口（单/多页恒可点，点按原地展开顶楼/最新回复/楼层与页码切换）
                                        val floorInteraction = remember { MutableInteractionSource() }
                                        Box(
                                            modifier = Modifier
                                                .widthIn(min = ThreadBottomToolbarHeight)
                                                .fillMaxHeight()
                                                .hiPressScale(floorInteraction)
                                                .clip(HiShapeTokens.Full)
                                                .clickable(floorInteraction, LocalIndication.current, role = Role.Button) { onExpandedChange(true) }
                                                .semantics(mergeDescendants = true) { contentDescription = capsuleDesc }
                                                .padding(horizontal = 10.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = floorLabel,
                                                style = tabularLabelStyle,
                                                color = cs.onSurfaceVariant,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.clearAndSetSemantics {},
                                            )
                                        }

                                        // 3. 收藏
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

                                        // 4. 更多（顶部分组收纳「只看楼主 / 看全部」）
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
                                                expanded = moreMenuExpanded,
                                                isAuthorOnly = isAuthorOnly,
                                                isAttention = isAttention,
                                                onDismiss = { moreMenuExpanded = false },
                                                onToggleAuthorOnly = onToggleAuthorOnly,
                                                onRefresh = onRefreshClick,
                                                onToggleAttention = onToggleAttention,
                                                onCopyLink = onCopyLinkClick,
                                                onOpenInBrowser = onOpenInBrowserClick,
                                                onShare = onShareClick,
                                            )
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
    isAuthorOnly: Boolean,
    isAttention: Boolean,
    onDismiss: () -> Unit,
    onToggleAuthorOnly: () -> Unit,
    onRefresh: () -> Unit,
    onToggleAttention: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onShare: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        ThreadMoreMenuItem(
            stringResource(if (isAuthorOnly) R.string.thread_detail_action_view_all else R.string.thread_detail_action_author_only),
        ) {
            onDismiss()
            onToggleAuthorOnly()
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
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
