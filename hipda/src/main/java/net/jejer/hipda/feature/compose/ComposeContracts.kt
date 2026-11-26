package net.jejer.hipda.feature.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.util.Optional

/** 快速回复目标模式（普通回复主题 / 回复指定楼层 / 引用指定楼层）。 */
enum class QuickReplyMode { ReplyThread, ReplyPost, QuotePost }

/** 快速回复目标上下文；通过 [QuickReplyTargetSaver] 在折叠、旋转与进程恢复后保活。 */
@Immutable
data class QuickReplyTarget(
    val tid: String,
    val fid: Int = 0,
    val mode: QuickReplyMode = QuickReplyMode.ReplyThread,
    val targetPid: String? = null,
    val targetAuthor: String? = null,
    val targetFloor: Int? = null,
    val threadAuthor: String? = null,
)

val QuickReplyTargetSaver: Saver<QuickReplyTarget?, Any> = listSaver(
    save = { t ->
        if (t == null) emptyList()
        else listOf(t.tid, t.fid, t.mode.name, t.targetPid.orEmpty(), t.targetAuthor.orEmpty(), t.targetFloor ?: -1, t.threadAuthor.orEmpty())
    },
    restore = { s ->
        if (s.size < 7) null
        else QuickReplyTarget(
            tid = s[0] as String,
            fid = s[1] as Int,
            mode = runCatching { QuickReplyMode.valueOf(s[2] as String) }.getOrDefault(QuickReplyMode.ReplyThread),
            targetPid = (s[3] as String).ifEmpty { null },
            targetAuthor = (s[4] as String).ifEmpty { null },
            targetFloor = (s[5] as? Int)?.takeIf { it > 0 },
            threadAuthor = (s[6] as String).ifEmpty { null },
        )
    },
)

/** `E5`（快速回复面板）向 `E6`（完整编辑器）的无损交接载荷。 */
@Immutable
data class FullEditorHandoff(
    val tid: String,
    val fid: Int,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val quotePid: String? = null,
    val quoteAuthor: String? = null,
    val quoteFloor: Int? = null,
    val openImagePickerImmediately: Boolean = false,
)

/** 零耦合完整编辑器交接插槽：由 [ComposeSlotBindingModule] `@BindsOptionalOf` 暴露，`E6` 绑定后启用「展开全屏」与「插入图片」。 */
@Immutable
fun interface FullEditorLauncher {
    fun open(handoff: FullEditorHandoff)
}

/** 供 `E7` 表情面板在快速回复面板底部原地替换键盘并写入/回删当前输入框的宿主接口。 */
@Immutable
data class QuickReplyEmojiPanelHost(
    val keyboardHeightDp: Dp,
    val windowHeightDp: Dp,
    val onInsertEmoji: (code: String) -> Unit,
    val onBackspace: () -> Unit,
    val onClose: () -> Unit,
)

/** 零耦合表情面板插槽：由 [ComposeSlotBindingModule] `@BindsOptionalOf` 暴露，`E7` 绑定后启用「表情 / 键盘」按钮与等高表情面板。 */
@Immutable
interface QuickReplyEmojiPanelRenderer {
    @Composable
    fun Content(host: QuickReplyEmojiPanelHost, modifier: Modifier)
}

/** 可选交接插槽模块：未绑定时对应按钮与上拉展开全屏均不启用（`E6`/`E7` 各自 `@Binds @Singleton` 零改接入）。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ComposeSlotBindingModule {
    @BindsOptionalOf
    abstract fun bindOptionalFullEditorLauncher(): FullEditorLauncher

    @BindsOptionalOf
    abstract fun bindOptionalQuickReplyEmojiPanelRenderer(): QuickReplyEmojiPanelRenderer
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ComposeSlotsEntryPoint {
    fun fullEditorLauncher(): Optional<FullEditorLauncher>
    fun emojiPanelRenderer(): Optional<QuickReplyEmojiPanelRenderer>
}

@Immutable
internal data class ResolvedComposeSlots(
    val onExpandToFullEditor: ((FullEditorHandoff) -> Unit)?,
    val emojiPanelSlot: (@Composable (QuickReplyEmojiPanelHost) -> Unit)?,
)

@Composable
internal fun rememberResolvedComposeSlots(
    explicitExpandToFullEditor: ((FullEditorHandoff) -> Unit)?,
    explicitEmojiPanelSlot: (@Composable (QuickReplyEmojiPanelHost) -> Unit)?,
): ResolvedComposeSlots {
    val appContext = LocalContext.current.applicationContext
    val entryPoint = remember(appContext) {
        if (appContext is GeneratedComponentManagerHolder) {
            runCatching { EntryPointAccessors.fromApplication(appContext, ComposeSlotsEntryPoint::class.java) }.getOrNull()
        } else null
    }
    val expand = remember(explicitExpandToFullEditor, entryPoint) {
        explicitExpandToFullEditor ?: entryPoint?.fullEditorLauncher()?.orElse(null)?.let { l -> { h: FullEditorHandoff -> l.open(h) } }
    }
    val emoji = remember(explicitEmojiPanelSlot, entryPoint) {
        explicitEmojiPanelSlot ?: entryPoint?.emojiPanelRenderer()?.orElse(null)?.let { r -> { h: QuickReplyEmojiPanelHost -> r.Content(h, Modifier) } }
    }
    return remember(expand, emoji) { ResolvedComposeSlots(expand, emoji) }
}
