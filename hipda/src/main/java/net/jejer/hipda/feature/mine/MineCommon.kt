package net.jejer.hipda.feature.mine

import android.content.res.Resources
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.jejer.hipda.R
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.favorite.FavoriteError
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.ui.challenge.ChallengeFailureKind
import net.jejer.hipda.ui.challenge.ChallengeStatusView
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.feedback.hiListItemPressIndication
import net.jejer.hipda.ui.designsystem.feedback.hiPressScale
import net.jejer.hipda.ui.designsystem.motion.HiMotion
import net.jejer.hipda.ui.designsystem.state.*

private const val MINE_PREFETCH_THRESHOLD: Int = 4
private val FAVORITE_PERMISSION_KEYWORDS = listOf("无权", "权限", "不允许访问", "等级不够")

@Immutable
data class PagedTabState<T>(
    val items: List<T> = emptyList(), val page: Int = 0, val hasMore: Boolean = false,
    val hasLoadedOnce: Boolean = false, val isLoading: Boolean = false, val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false, val error: UiError? = null, val challengeFailure: ChallengeFailureKind? = null,
) {
    fun markLoading(append: Boolean, isRefresh: Boolean) = copy(isLoading = !append && !isRefresh, isRefreshing = isRefresh, isLoadingMore = append, error = null, challengeFailure = null)
    fun markSuccess(merged: List<T>, page: Int, hasMore: Boolean) = copy(items = merged, page = page, hasMore = hasMore, hasLoadedOnce = true, isLoading = false, isRefreshing = false, isLoadingMore = false, error = null, challengeFailure = null)
    fun markError(keepContent: Boolean, error: UiError, cf: ChallengeFailureKind?) = copy(hasLoadedOnce = true, isLoading = false, isRefreshing = false, isLoadingMore = false, error = if (keepContent) null else error, challengeFailure = if (keepContent) null else cf)
}

internal fun CoroutineScope.observeAuthSession(authRepository: AuthRepository, onChanged: (uid: String?) -> Unit) {
    launch {
        var prevUid: String? = null; var prevEpoch = -1L; var prevSignedIn = false
        combine(authRepository.currentAccount, authRepository.authState) { acc, st -> Triple(acc?.uid, authRepository.sessionEpoch, st is AuthState.SignedIn) }
            .distinctUntilChanged().collect { (uid, epoch, signedIn) ->
                val changed = uid != prevUid || epoch != prevEpoch || (uid != null && signedIn && !prevSignedIn)
                prevUid = uid; prevEpoch = epoch; prevSignedIn = signedIn
                if (changed) onChanged(uid)
            }
    }
}

internal fun mapFavoriteError(error: FavoriteError): UiError = when (error) {
    is FavoriteError.AuthRequired -> UiErrorMapper.mapAuthRequired(error.reason, joinDetails(error.reason.name, error.message, error.reloginResult?.toString()))
    is FavoriteError.ChallengeRequired -> UiError(if (error.rejected) R.string.challenge_status_rejected else R.string.challenge_status_incomplete, joinDetails(if (error.abandoned) "challenge_abandoned" else null, error.cooldownUntilMs?.let { "cooldownUntilMs=$it" }), UiErrorAction.Reverify)
    is FavoriteError.NetworkError -> UiError(if (error.statusCode != null) R.string.common_error_load_failed else R.string.common_error_network, joinDetails(error.statusCode?.let { "HTTP $it" }, error.message, error.cause?.let { "${it::class.java.simpleName}: ${it.message.orEmpty()}" }), UiErrorAction.Retry)
    is FavoriteError.SiteError -> if (FAVORITE_PERMISSION_KEYWORDS.any { it in error.message }) UiError(R.string.common_error_no_forum_permission, error.message.trim().ifEmpty { null }) else UiError(R.string.common_error_load_failed, error.message.trim().ifEmpty { null }, UiErrorAction.Retry)
    is FavoriteError.InvalidPage -> UiError(R.string.common_error_load_failed, error.message.trim().ifEmpty { null }, UiErrorAction.Retry)
    is FavoriteError.InvalidInput -> UiError(R.string.common_error_load_failed, error.message.trim().ifEmpty { null }, UiErrorAction.Retry)
    is FavoriteError.StorageError -> UiError(R.string.common_error_load_failed, joinDetails(error.message, error.cause?.message), UiErrorAction.Retry)
}

private fun joinDetails(vararg parts: String?): String? =
    parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct().joinToString("\n").ifEmpty { null }

internal suspend fun showErrorSnackbar(controller: PaneSnackbarController, resources: Resources, err: UiError, onRetry: (AccessPriority) -> Unit, onOpenLogin: () -> Unit) {
    controller.show(
        resources.getString(err.messageRes), PaneSnackbarTarget.ContentArea, err.action?.let { resources.getString(it.labelRes) },
        err.action?.let { act -> { when (act) { UiErrorAction.Retry -> onRetry(AccessPriority.UserBlocking); UiErrorAction.Login -> onOpenLogin(); UiErrorAction.Reverify -> onRetry(AccessPriority.UserExplicit) } } },
    )
}

@Composable
internal fun rememberListScrolled(listState: LazyListState): State<Boolean> {
    val scrolled = remember(listState) { mutableStateOf(Snapshot.withoutReadObservation { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }) }
    LaunchedEffect(listState) { snapshotFlow { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }.distinctUntilChanged().collect { scrolled.value = it } }
    return scrolled
}

@Composable
internal fun rememberListBottomPadding(contentPadding: PaddingValues): PaddingValues {
    val dir = LocalLayoutDirection.current
    return remember(contentPadding, dir) { PaddingValues(contentPadding.calculateStartPadding(dir), 0.dp, contentPadding.calculateEndPadding(dir), contentPadding.calculateBottomPadding() + 8.dp) }
}

/** 「我的」及其子页共用的纯色顶栏（零毛玻璃，`surface → surfaceContainer` 随滚动渐变）。 */
@Composable
internal fun MineSolidTopBar(title: String, isScrolled: Boolean, showBack: Boolean = false, onBack: (() -> Unit)? = null, trailingAction: (@Composable RowScope.() -> Unit)? = null, bottomSlot: (@Composable ColumnScope.() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val barColor by animateColorAsState(if (isScrolled) cs.surfaceContainer else cs.surface, HiMotion.effectsDefault(), label = "MineSolidTopBarColor")
    Column(Modifier.fillMaxWidth().background(barColor)) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        HiCappedFontScale {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = if (showBack && onBack != null) 4.dp else 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showBack && onBack != null) {
                    val backInt = remember { MutableInteractionSource() }
                    IconButton(onClick = onBack, interactionSource = backInt, modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(backInt)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_action_back), tint = cs.onSurface)
                    }
                }
                Text(title, style = MaterialTheme.typography.titleLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
                trailingAction?.invoke(this)
            }
        }
        bottomSlot?.invoke(this)
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f), thickness = 0.5.dp)
    }
}

/** 顶部双分段标签（`spatialFast` 滑动指示器，`≥ 48dp` 触控区 + `hiPressScale`）。 */
@Composable
internal fun MineSegmentedTabs(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (labels.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val safeIndex = selectedIndex.coerceIn(0, labels.lastIndex)
    HiCappedFontScale {
        BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(HiShapeTokens.Full).background(cs.surfaceContainerHigh).padding(4.dp)) {
            val tabWidth = maxWidth / labels.size
            val indicatorOffset by animateDpAsState(tabWidth * safeIndex, HiMotion.spatialFast(), label = "MineSegmentIndicatorOffset")
            Box(Modifier.align(Alignment.CenterStart).offset { IntOffset(indicatorOffset.roundToPx(), 0) }.width(tabWidth).height(44.dp).clip(HiShapeTokens.Full).background(cs.secondaryContainer))
            Row(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { idx, label ->
                    val selected = idx == safeIndex
                    val interaction = remember(idx) { MutableInteractionSource() }
                    val textColor by animateColorAsState(if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant, HiMotion.effectsFast(), label = "MineSegmentTabColor")
                    Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(HiShapeTokens.Full).hiPressScale(interaction).selectable(selected, interaction, null, role = Role.Tab) { if (!selected) onSelect(idx) }, Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium), color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> MinePaginatedList(
    isSignedIn: Boolean, tabState: PagedTabState<T>, challengeState: ChallengeState, emptyMessage: String,
    listState: LazyListState, contentPadding: PaddingValues, itemKey: (T) -> Any,
    onRefresh: () -> Unit, onLoadMore: () -> Unit, onRetry: (AccessPriority) -> Unit, onOpenLogin: () -> Unit,
    itemContent: @Composable (T) -> Unit, modifier: Modifier = Modifier,
) {
    val listBottomPadding = rememberListBottomPadding(contentPadding)
    Box(modifier.fillMaxSize()) {
        val showChallenge = tabState.items.isEmpty() && (challengeState !is ChallengeState.Idle || tabState.challengeFailure != null)
        when {
            !isSignedIn -> Box(Modifier.fillMaxSize().padding(listBottomPadding), Alignment.Center) {
                HiErrorState(stringResource(R.string.common_error_login_required), action = HiStateAction(stringResource(R.string.common_action_login), onOpenLogin))
            }
            showChallenge -> Box(Modifier.fillMaxSize().padding(listBottomPadding), Alignment.Center) {
                ChallengeStatusView(challengeState, onRetry, failure = tabState.challengeFailure, errorDetails = tabState.error?.details)
            }
            (tabState.isLoading || !tabState.hasLoadedOnce) && tabState.items.isEmpty() && tabState.error == null -> MineListSkeleton(listBottomPadding)
            tabState.error != null && tabState.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(listBottomPadding), Alignment.Center) {
                HiErrorState(stringResource(tabState.error.messageRes), details = tabState.error.details, action = tabState.error.toStateAction(onRetry, onOpenLogin))
            }
            !tabState.isLoading && tabState.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(listBottomPadding), Alignment.Center) { HiEmptyState(emptyMessage) }
            else -> {
                MinePrefetchEffect(
                    listState = listState,
                    canLoadMore = { tabState.hasMore && !tabState.isLoading && !tabState.isRefreshing && !tabState.isLoadingMore && tabState.error == null },
                    onLoadMore = onLoadMore,
                )
                PullToRefreshBox(isRefreshing = tabState.isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = listBottomPadding) {
                        items(items = tabState.items, key = itemKey) { item ->
                            Column(Modifier.animateItem(HiMotion.effectsDefault(), HiMotion.spatialDefault(), HiMotion.exit(HiMotion.effectsDefault()))) {
                                itemContent(item)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                            }
                        }
                        item("mine_footer") { MineListFooterItem(tabState.isLoadingMore, tabState.error != null, tabState.hasMore, onLoadMore) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MinePrefetchEffect(listState: LazyListState, canLoadMore: () -> Boolean, onLoadMore: () -> Unit) {
    val latestCanLoad by rememberUpdatedState(canLoadMore)
    val latestLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState) {
        snapshotFlow {
            if (!latestCanLoad()) return@snapshotFlow false
            if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) return@snapshotFlow false
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - MINE_PREFETCH_THRESHOLD
        }.distinctUntilChanged().collect { if (it) latestLoadMore() }
    }
}

@Composable
internal fun MineThreadRow(title: String, preview: String? = null, forumName: String? = null, author: String? = null, time: String? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().clickable(interaction, hiListItemPressIndication(), onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = HiTheme.typography.threadTitle, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        preview?.trim()?.takeIf(String::isNotEmpty)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        MineMetaRow(forumName, author, time)
    }
}

@Composable
internal fun MineDismissibleRow(title: String, forumName: String?, author: String? = null, time: String? = null, dismissDesc: String, onClick: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme; val rowInt = remember { MutableInteractionSource() }; val delInt = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clickable(rowInt, hiListItemPressIndication(), onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = HiTheme.typography.threadTitle, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MineMetaRow(forumName, author, time)
        }
        IconButton(onClick = onDismiss, interactionSource = delInt, modifier = Modifier.defaultMinSize(48.dp, 48.dp).hiPressScale(delInt)) {
            Icon(Icons.Rounded.Close, dismissDesc, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun MineListFooterItem(isLoadingMore: Boolean, hasError: Boolean, hasMore: Boolean, onLoadMore: () -> Unit) = when {
    isLoadingMore -> HiListFooter(HiFooterState.Loading)
    hasError -> HiListFooter(HiFooterState.Failed(onLoadMore))
    hasMore -> Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), Alignment.Center) { TextButton(onClick = onLoadMore, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(stringResource(R.string.common_action_load_more)) } }
    else -> HiListFooter(HiFooterState.End)
}

@Composable
internal fun MineMetaRow(forumName: String?, author: String? = null, time: String? = null) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        forumName?.trim()?.takeIf(String::isNotEmpty)?.let { Box(Modifier.clip(HiShapeTokens.Small).background(cs.surfaceContainerHigh).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(it, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false) } }
        val cleanAuthor = author?.trim()?.takeIf(String::isNotEmpty)
        if (cleanAuthor != null) Text(cleanAuthor, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
        time?.trim()?.takeIf(String::isNotEmpty)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, softWrap = false) }
    }
}

@Composable
internal fun MineListSkeleton(contentPadding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        repeat(6) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HiSkeletonBlock(Dp.Unspecified, 18.dp, modifier = Modifier.fillMaxWidth(0.86f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { HiSkeletonBlock(68.dp, 14.dp); HiSkeletonBlock(54.dp, 14.dp) }
            }
        }
    }
}

internal fun formatHistoryTimestamp(timestampMs: Long): String =
    if (timestampMs <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestampMs))
