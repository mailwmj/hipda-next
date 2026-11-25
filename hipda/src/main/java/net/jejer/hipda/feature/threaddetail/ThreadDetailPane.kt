package net.jejer.hipda.feature.threaddetail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.jejer.hipda.R
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.ui.challenge.ChallengeStatusView
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.state.*

/** 最薄可运行真实帖子详情面板（供 `E2a`/`E2c`/`E4` 原位加厚，顶层签名与 `floor:$postId` 主键在此锁死）。 */
@Composable
fun ThreadDetailPane(
    args: ThreadNavArgs,
    chromeSpec: DetailChromeSpec,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onOpenImageViewer: (ImageViewerLaunchArgs) -> Unit = {},
    onOpenReply: (ReplyRequest) -> Unit = {},
    onOpenUserProfile: (uid: String, username: String) -> Unit = { _, _ -> },
    viewModel: ThreadDetailViewModel = viewModel(key = "thread:${args.tid}"),
) {
    LaunchedEffect(args.tid) { viewModel.bindArgs(args) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (chromeSpec.showBack) {
                        IconButton(onClick = onBack, modifier = Modifier.defaultMinSize(48.dp, 48.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_action_back))
                        }
                    }
                    Text(uiState.title.ifBlank { args.title }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    chromeSpec.fullScreenState?.let { fs ->
                        TextButton(onClick = fs.onToggle, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                            Text(stringResource(if (fs.isFullScreen) R.string.shell_detail_show_list else R.string.shell_detail_fullscreen))
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val showChallenge = uiState.floors.isEmpty() && (uiState.challengeState !is ChallengeState.Idle || uiState.challengeFailure != null)
            when {
                showChallenge -> ChallengeStatusView(uiState.challengeState, viewModel::retry, failure = uiState.challengeFailure, errorDetails = uiState.error?.details)
                uiState.isLoading && uiState.floors.isEmpty() -> Column(Modifier.widthIn(max = 680.dp).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(4) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { HiSkeletonBlock(140.dp, 14.dp); HiSkeletonBlock(300.dp, 16.dp); HiSkeletonBlock(220.dp, 16.dp) } }
                }
                uiState.error != null && uiState.floors.isEmpty() -> uiState.error!!.let { err ->
                    HiErrorState(stringResource(err.messageRes), details = err.details, action = err.toStateAction(viewModel::retry, onOpenLogin))
                }
                !uiState.isLoading && uiState.floors.isEmpty() -> HiEmptyState(stringResource(R.string.common_empty_floors), action = HiStateAction(stringResource(R.string.common_action_refresh)) { viewModel.retry() })
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = if (uiState.lastPage > 1) PaddingValues(0.dp) else contentPadding,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(items = uiState.floors, key = { "floor:${it.postId}" }) { item ->
                        FloorPlainItem(item)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }

        if (uiState.lastPage > 1) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).padding(bottom = contentPadding.calculateBottomPadding()),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = viewModel::previousPage, enabled = uiState.page > 1 && !uiState.isLoading, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(stringResource(R.string.common_page_prev)) }
                Text(stringResource(R.string.common_page_indicator, uiState.page, uiState.lastPage), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = viewModel::nextPage, enabled = uiState.page < uiState.lastPage && !uiState.isLoading, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(stringResource(R.string.common_page_next)) }
            }
        }
    }
}

@Composable
private fun FloorPlainItem(item: ThreadDetailFloorItem) {
    val cs = MaterialTheme.colorScheme
    val floor = item.floor
    val bodyText = remember(floor.blocks) { extractPlainBodyText(floor.blocks) }
    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.common_floor_badge, floor.floor), style = MaterialTheme.typography.labelMedium, color = cs.primary)
            Text(floor.author, style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(floor.timePost, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
        }
        if (item.isAuthorBlocked) Text(stringResource(R.string.common_floor_blocked, floor.author), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        else if (bodyText.isNotEmpty()) Text(bodyText, style = HiTheme.typography.postBody, color = cs.onSurface)
    }
}

internal fun extractPlainBodyText(blocks: List<ContentBlock>): String = buildString {
    for (block in blocks) {
        val seg = when (block) {
            is ContentBlock.Text -> buildString {
                for (s in block.spans) when (s) {
                    is InlineSpan.Plain -> append(s.text)
                    is InlineSpan.Link -> append(s.label)
                    is InlineSpan.Email -> append(s.email)
                    is InlineSpan.LineBreak -> append('\n')
                    is InlineSpan.Emoji, is InlineSpan.SmallImage -> Unit
                }
            }.trim()
            is ContentBlock.Quote -> block.contentText.trim()
            is ContentBlock.GoToFloor -> block.label.trim()
            is ContentBlock.Notice -> block.message.trim()
            is ContentBlock.Image, is ContentBlock.Attachment -> ""
        }
        if (seg.isNotEmpty()) { if (isNotEmpty()) append("\n\n"); append(seg) }
    }
}
