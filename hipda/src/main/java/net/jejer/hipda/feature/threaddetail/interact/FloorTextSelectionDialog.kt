package net.jejer.hipda.feature.threaddetail.interact

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jejer.hipda.R
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme

private const val MODAL_SCRIM_ALPHA = 0.40f
private val ModalMaxWidth = 480.dp

/** 共用楼层弹窗纯色容器（零毛玻璃；仅打开时挂载，[BackHandler] 置顶于 LIFO 栈顶）。 */
@Composable
internal fun FloorModalDialogScaffold(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    titleMaxLines: Int = 1,
    content: @Composable BoxScope.() -> Unit,
) {
    BackHandler(enabled = true, onBack = onDismiss)
    val cs = MaterialTheme.colorScheme
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(cs.scrim.copy(alpha = MODAL_SCRIM_ALPHA))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.Center).systemBarsPadding().padding(24.dp)
                .widthIn(max = ModalMaxWidth).fillMaxWidth()
                .pointerInput(Unit) {}
                .semantics { paneTitle = title; isTraversalGroup = true },
            shape = HiShapeTokens.Large,
            color = cs.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface,
                    maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).semantics { heading() },
                )
                HorizontalDivider(color = cs.outlineVariant)
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                    content = content,
                )
                HorizontalDivider(color = cs.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, shape = HiShapeTokens.Small, modifier = Modifier.defaultMinSize(48.dp, 48.dp)) {
                        Text(stringResource(R.string.thread_detail_action_close))
                    }
                }
            }
        }
    }
}

/**
 * 单楼层局部文字选择面板（M7-G3 #5）：复用 [extractFloorPlainText] 并以 [SelectionContainer] 包裹支持自由拖选复制；
 * 默认非选择态不全局包裹，避免干扰楼层长按手势与列表滚动。
 */
@Composable
fun FloorTextSelectionDialog(
    floor: PostFloor?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (floor == null) return
    val cs = MaterialTheme.colorScheme
    val title = stringResource(R.string.thread_detail_floor_title, floor.floor, floor.author)
    val plainText = remember(floor.postId, floor.blocks) { extractFloorPlainText(floor) }
    FloorModalDialogScaffold(title = title, onDismiss = onDismiss, modifier = modifier) {
        if (plainText.isNotBlank()) {
            SelectionContainer {
                Text(text = plainText, style = HiTheme.typography.postBody, color = cs.onSurface, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Text(stringResource(R.string.thread_detail_select_text_empty), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        }
    }
}
