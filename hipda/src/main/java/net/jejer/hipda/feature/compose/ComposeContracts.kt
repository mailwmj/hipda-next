package net.jejer.hipda.feature.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.staticCompositionLocalOf
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.post.PostSubmitResult
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.feature.common.UiErrorMapper
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage

/** 快速回复目标模式：回复主题 / 回复指定楼层 / 引用指定楼层。 */
enum class QuickReplyMode { ReplyThread, ReplyPost, QuotePost }

/** 快速回复目标描述（支持进程重建 `rememberSaveable` 无损恢复）。 */
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
            tid = s[0] as String, fid = s[1] as Int,
            mode = runCatching { QuickReplyMode.valueOf(s[2] as String) }.getOrDefault(QuickReplyMode.ReplyThread),
            targetPid = (s[3] as String).ifEmpty { null }, targetAuthor = (s[4] as String).ifEmpty { null },
            targetFloor = (s[5] as? Int)?.takeIf { it > 0 }, threadAuthor = (s[6] as String).ifEmpty { null },
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
    val replyPid: String? = null,
    val threadAuthor: String? = null,
    val hadPersistedDraft: Boolean = false,
    val showDraftRestoredBanner: Boolean = false,
)

/** 零耦合完整编辑器交接插槽：由 [ComposeSlotBindingModule] `@BindsOptionalOf` 暴露，`E6` 绑定后启用「展开全屏」与「插入图片」。 */
@Immutable
fun interface FullEditorLauncher { fun open(handoff: FullEditorHandoff) }

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
    @Composable fun Content(host: QuickReplyEmojiPanelHost, modifier: Modifier)
}

/** 可选交接插槽模块：未绑定时对应按钮与上拉展开全屏均不启用（`E6`/`E7` 各自 `@Binds @Singleton` 零改接入）。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ComposeSlotBindingModule {
    @BindsOptionalOf abstract fun bindOptionalFullEditorLauncher(): FullEditorLauncher
    @BindsOptionalOf abstract fun bindOptionalQuickReplyEmojiPanelRenderer(): QuickReplyEmojiPanelRenderer
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ComposeSlotsEntryPoint {
    fun fullEditorLauncher(): Optional<FullEditorLauncher>
    fun emojiPanelRenderer(): Optional<QuickReplyEmojiPanelRenderer>
}

/** 完整编辑器回复成功后通知当前打开的 `ThreadDetailPane` 执行末页插楼或非末页 Snackbar 联动。 */
@Immutable
data class FullEditorReplySubmitted(val tid: String, val redirectedPage: ThreadDetailPage?)

/** 宿主向 `ThreadDetailPane` 下发的完整编辑器回复成功事件流（可选，未提供时由宿主兜底提示）。 */
val LocalFullEditorReplyResults = staticCompositionLocalOf<SharedFlow<FullEditorReplySubmitted>?> { null }

@Immutable
internal data class ResolvedComposeSlots(
    val onExpandToFullEditor: ((FullEditorHandoff) -> Unit)?,
    val emojiPanelSlot: (@Composable (QuickReplyEmojiPanelHost) -> Unit)?,
)

@Composable
internal fun rememberComposeSlotsEntryPoint(): ComposeSlotsEntryPoint? {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        if (appContext is GeneratedComponentManagerHolder) {
            runCatching { EntryPointAccessors.fromApplication(appContext, ComposeSlotsEntryPoint::class.java) }.getOrNull()
        } else null
    }
}

@Composable
internal fun rememberResolvedComposeSlots(
    explicitExpandToFullEditor: ((FullEditorHandoff) -> Unit)?,
    explicitEmojiPanelSlot: (@Composable (QuickReplyEmojiPanelHost) -> Unit)?,
): ResolvedComposeSlots {
    val entryPoint = rememberComposeSlotsEntryPoint()
    val expand = remember(explicitExpandToFullEditor, entryPoint) {
        explicitExpandToFullEditor ?: entryPoint?.fullEditorLauncher()?.orElse(null)?.let { l -> { h: FullEditorHandoff -> l.open(h) } }
    }
    val emoji = remember(explicitEmojiPanelSlot, entryPoint) {
        explicitEmojiPanelSlot ?: entryPoint?.emojiPanelRenderer()?.orElse(null)?.let { r -> { h: QuickReplyEmojiPanelHost -> r.Content(h, Modifier) } }
    }
    return remember(expand, emoji) { ResolvedComposeSlots(expand, emoji) }
}

@Composable
internal fun rememberResolvedEmojiPanelSlot(
    explicitEmojiPanelSlot: (@Composable (QuickReplyEmojiPanelHost) -> Unit)?,
): (@Composable (QuickReplyEmojiPanelHost) -> Unit)? =
    rememberResolvedComposeSlots(explicitExpandToFullEditor = null, explicitEmojiPanelSlot = explicitEmojiPanelSlot).emojiPanelSlot

internal fun safeReplyDraftKey(tid: String): DraftKey? = runCatching { DraftKey.reply(tid.trim()) }.getOrNull()

internal fun resolveComposeLoggedIn(account: SavedAccount?, state: AuthState): Boolean =
    account != null && state !is AuthState.SignedOut && state !is AuthState.SessionExpired

internal fun nextCooldownTickDelayMs(untilMs: Long, nowMs: Long): Long =
    (untilMs - nowMs).let { raw -> if (raw !in 1L..35_000L) 250L else (raw % 1_000L).let { if (it == 0L) 1_000L else it } }

internal suspend fun runCooldownTickerLoop(
    untilMs: Long,
    clockMs: () -> Long,
    remainingProvider: () -> Int,
    onTick: (Int) -> Unit,
) {
    while (true) {
        val rem = remainingProvider().coerceAtLeast(0)
        onTick(rem)
        if (rem <= 0) break
        delay(nextCooldownTickDelayMs(untilMs, clockMs()))
    }
}

internal fun CoroutineScope.enqueueSerialDraftMutation(previous: Job?, action: suspend () -> Unit): Job =
    launch(start = CoroutineStart.UNDISPATCHED) {
        withContext(NonCancellable) { runCatching { previous?.join(); action() }.onFailure { if (it is CancellationException) throw it } }
    }

internal fun mapPostSubmitFailureUiError(result: PostSubmitResult): UiError = when (result) {
    is PostSubmitResult.AuthRequired -> UiErrorMapper.mapAuthRequired(result.reason)
    is PostSubmitResult.ChallengeRequired -> UiError(
        if (result.rejected) R.string.challenge_status_rejected else R.string.challenge_status_incomplete,
        result.cooldownUntilMs?.let { "cooldownUntilMs=$it" }, UiErrorAction.Reverify,
    )
    PostSubmitResult.RealNameBindRequired -> UiError(R.string.compose_error_real_name_required)
    is PostSubmitResult.SiteError -> UiError(R.string.compose_error_send_failed, result.message.trim().ifEmpty { null }, UiErrorAction.Retry)
    is PostSubmitResult.NetworkError -> UiError(R.string.compose_error_send_failed, result.message.trim().ifEmpty { null }, UiErrorAction.Retry)
    is PostSubmitResult.InvalidInput -> UiError(R.string.compose_error_send_failed, result.message.trim().ifEmpty { null }, UiErrorAction.Retry)
    else -> UiError(R.string.compose_error_send_failed, action = UiErrorAction.Retry)
}
