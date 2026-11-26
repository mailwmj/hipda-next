package net.jejer.hipda.feature.compose.emoji

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.jejer.hipda.feature.compose.QuickReplyEmojiPanelHost

private const val IME_HANDOFF_SETTLE_TIMEOUT_MS: Long = 450L
private val IME_HANDOFF_MATCH_TOLERANCE: Dp = 1.dp

/** 表情面板与软键盘互换 3 态模型，保证 `IME ↔ 表情` 全程底缘零位移。 */
enum class EmojiPanelMode { Closed, Emoji, SwitchingToIme }

/** 当帧系统窗口 inset 快照：三宿主共用同一 `IME − navigationBars` 采样，杜绝跨宿主缓存污染。 */
@Immutable
data class EmojiInsetsFrame(
    val imeBottomPx: Int,
    val navBarBottomPx: Int,
    val imeBottomDp: Dp,
    val navBarBottomDp: Dp,
    val windowHeightDp: Dp,
    val density: Density,
)

@Composable
fun rememberEmojiInsetsFrame(windowHeightDp: Dp): EmojiInsetsFrame {
    val density = LocalDensity.current
    val imePx = WindowInsets.ime.getBottom(density).coerceAtLeast(0)
    val navPx = WindowInsets.navigationBars.getBottom(density).coerceAtLeast(0)
    return EmojiInsetsFrame(
        imeBottomPx = imePx,
        navBarBottomPx = navPx,
        imeBottomDp = with(density) { imePx.toDp() },
        navBarBottomDp = with(density) { navPx.toDp() },
        windowHeightDp = windowHeightDp.coerceAtLeast(0.dp),
        density = density,
    )
}

/** 表情面板与软键盘等高原地互换控制器（`QuickReplySheet` / `FullEditorScreen` / `SmsConversationScreen` 共用）。 */
@Stable
class EmojiPanelController internal constructor(
    private val tracker: KeyboardHeightTracker,
    private val frameProvider: () -> EmojiInsetsFrame,
    private val onShowKeyboard: () -> Unit,
    private val onHideKeyboard: () -> Unit,
) {
    var mode: EmojiPanelMode by mutableStateOf(EmojiPanelMode.Closed)
        private set
    var backHandlerKey: Int by mutableIntStateOf(0)
        private set

    private var holdHeightDp: Dp by mutableStateOf(0.dp)
    private var awaitingImeHide: Boolean = false
    private var prevImeBottomPx: Int = 0

    private val frame: EmojiInsetsFrame get() = frameProvider()

    val rememberedNetImeDp: Dp get() = tracker.rememberedNetHeightDp
    val navBarBottomDp: Dp get() = frame.navBarBottomDp
    val isEmojiModeActive: Boolean get() = mode == EmojiPanelMode.Emoji
    val isBackInterceptActive: Boolean get() = mode != EmojiPanelMode.Closed
    val panelContentHeightDp: Dp get() = resolveEmojiPanelHeight(tracker.rememberedNetHeightDp, frame.windowHeightDp)
    val panelTotalHeightDp: Dp get() = resolveEmojiPanelTotalHeight(tracker.rememberedNetHeightDp, frame.navBarBottomDp, frame.windowHeightDp)

    val bottomSlotHeightDp: Dp
        get() = when (mode) {
            EmojiPanelMode.Closed -> frame.imeBottomDp
            EmojiPanelMode.Emoji -> maxOf(holdHeightDp.takeIf { it > 0.dp } ?: panelTotalHeightDp, frame.imeBottomDp)
            EmojiPanelMode.SwitchingToIme -> maxOf(holdHeightDp, frame.imeBottomDp)
        }

    val panelInnerNavBarPaddingDp: Dp
        get() = resolveEmojiPanelInnerNavPadding(
            totalPanelHeightDp = holdHeightDp.takeIf { it > 0.dp } ?: panelTotalHeightDp,
            rememberedNetImeHeightDp = tracker.rememberedNetHeightDp,
            navBarBottomDp = frame.navBarBottomDp,
        )

    internal fun onImeInsetFrame(current: EmojiInsetsFrame) {
        val safeImePx = current.imeBottomPx
        val prevPx = prevImeBottomPx
        prevImeBottomPx = safeImePx
        tracker.observeInsets(safeImePx, current.navBarBottomPx, current.density, freezeUpdates = mode != EmojiPanelMode.Closed)

        when (mode) {
            EmojiPanelMode.Closed -> Unit
            EmojiPanelMode.Emoji -> {
                if (safeImePx == 0 || safeImePx < prevPx) {
                    awaitingImeHide = false
                    holdHeightDp = 0.dp
                } else if (!awaitingImeHide && safeImePx > prevPx) {
                    val hold = holdHeightDp.takeIf { it > 0.dp } ?: panelTotalHeightDp
                    if (current.imeBottomDp >= hold - IME_HANDOFF_MATCH_TOLERANCE) resetToClosed()
                    else { holdHeightDp = hold; mode = EmojiPanelMode.SwitchingToIme }
                }
            }
            EmojiPanelMode.SwitchingToIme -> {
                if (holdHeightDp <= 0.dp || current.imeBottomDp >= holdHeightDp - IME_HANDOFF_MATCH_TOLERANCE) {
                    resetToClosed()
                    if (safeImePx > 0) tracker.captureCurrentIme(safeImePx, current.navBarBottomPx, current.density)
                }
            }
        }
    }

    internal fun onHandoffTimeout() {
        if (mode != EmojiPanelMode.SwitchingToIme) return
        val current = frame
        resetToClosed()
        if (current.imeBottomPx > 0) tracker.captureCurrentIme(current.imeBottomPx, current.navBarBottomPx, current.density)
    }

    fun openEmojiPanel() {
        val current = frame
        when (mode) {
            EmojiPanelMode.Emoji -> return
            EmojiPanelMode.Closed -> {
                val netDp = calculateNetKeyboardHeightDp(current.imeBottomPx, current.navBarBottomPx, current.density)
                if (current.imeBottomPx > 0 && netDp > 0.dp) {
                    tracker.captureCurrentIme(current.imeBottomPx, current.navBarBottomPx, current.density)
                    holdHeightDp = current.imeBottomDp
                    awaitingImeHide = true
                } else {
                    holdHeightDp = 0.dp
                    awaitingImeHide = current.imeBottomPx > 0
                }
            }
            EmojiPanelMode.SwitchingToIme -> {
                awaitingImeHide = current.imeBottomPx > 0
            }
        }
        mode = EmojiPanelMode.Emoji
        backHandlerKey += 1
        onHideKeyboard()
    }

    fun switchToKeyboard() {
        if (mode == EmojiPanelMode.Closed) { onShowKeyboard(); return }
        val current = frame
        val hold = holdHeightDp.takeIf { it > 0.dp } ?: panelTotalHeightDp
        awaitingImeHide = false
        if (hold <= 0.dp || current.imeBottomDp >= hold - IME_HANDOFF_MATCH_TOLERANCE) resetToClosed()
        else { holdHeightDp = hold; mode = EmojiPanelMode.SwitchingToIme }
        onShowKeyboard()
    }

    fun toggle() { if (mode == EmojiPanelMode.Emoji) switchToKeyboard() else openEmojiPanel() }
    fun close() = resetToClosed()

    private fun resetToClosed() {
        mode = EmojiPanelMode.Closed
        awaitingImeHide = false
        holdHeightDp = 0.dp
    }
}

@Composable
fun rememberEmojiPanelController(
    windowHeightDp: Dp,
    onShowKeyboard: () -> Unit,
    onHideKeyboard: () -> Unit,
): EmojiPanelController {
    val frame = rememberEmojiInsetsFrame(windowHeightDp)
    val latestFrame by rememberUpdatedState(frame)
    val tracker = rememberKeyboardHeightTracker(frame.windowHeightDp)
    val latestShow by rememberUpdatedState(onShowKeyboard)
    val latestHide by rememberUpdatedState(onHideKeyboard)
    val controller = remember(tracker) {
        EmojiPanelController(tracker, { latestFrame }, { latestShow() }, { latestHide() })
    }
    LaunchedEffect(controller, frame.imeBottomPx, frame.navBarBottomPx, frame.density) {
        controller.onImeInsetFrame(frame)
    }
    LaunchedEffect(controller, controller.mode, frame.imeBottomPx) {
        if (controller.mode == EmojiPanelMode.SwitchingToIme) {
            delay(IME_HANDOFF_SETTLE_TIMEOUT_MS)
            controller.onHandoffTimeout()
        }
    }
    return controller
}

/** 底部统一占位槽与条件 `BackHandler` 宿主（纯色表面，0 `HiGlassMarker`；三宿主唯一 IME 消费点）。 */
@Composable
fun EmojiPanelSlotBox(
    controller: EmojiPanelController,
    emojiPanelSlot: (@Composable (QuickReplyEmojiPanelHost) -> Unit)?,
    host: QuickReplyEmojiPanelHost?,
    modifier: Modifier = Modifier,
    registerBackHandler: Boolean = true,
) {
    if (registerBackHandler && controller.isBackInterceptActive) {
        key(controller.backHandlerKey) { BackHandler(enabled = true) { controller.close() } }
    }
    val heightDp = controller.bottomSlotHeightDp
    if (heightDp <= 0.dp) return
    val surfaceBg = MaterialTheme.colorScheme.surfaceContainerLow
    when {
        controller.isEmojiModeActive && emojiPanelSlot != null && host != null -> Box(
            modifier.fillMaxWidth().height(heightDp).background(surfaceBg).pointerInput(Unit) {}
                .padding(bottom = controller.panelInnerNavBarPaddingDp),
        ) { emojiPanelSlot(host) }
        controller.mode == EmojiPanelMode.SwitchingToIme -> Box(
            modifier.fillMaxWidth().height(heightDp).background(surfaceBg).pointerInput(Unit) {},
        )
        else -> Spacer(modifier.fillMaxWidth().height(heightDp))
    }
}
