package net.jejer.hipda.feature.compose.emoji

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

/** 表情面板与软键盘等高原地互换控制器（`QuickReplySheet` / `FullEditorScreen` / `SmsConversationScreen` 共用）。 */
@Stable
class EmojiPanelController internal constructor(
    private val tracker: KeyboardHeightTracker,
    private val consumeImeInSlot: Boolean,
    private val onShowKeyboard: () -> Unit,
    private val onHideKeyboard: () -> Unit,
) {
    var mode: EmojiPanelMode by mutableStateOf(EmojiPanelMode.Closed)
        private set
    var backHandlerKey: Int by mutableIntStateOf(0)
        private set

    private var lockedEmojiTotalHeightDp: Dp by mutableStateOf(0.dp)
    private var handoffHoldHeightDp: Dp by mutableStateOf(0.dp)
    private var awaitingImeHide: Boolean by mutableStateOf(false)
    private var liveImeBottomPx: Int = 0
    private var liveNavBarBottomPx: Int = 0
    private var liveImeBottomDp: Dp by mutableStateOf(0.dp)
    private var liveNavBarBottomDp: Dp by mutableStateOf(0.dp)
    private var liveWindowHeightDp: Dp by mutableStateOf(0.dp)
    private var liveDensity: Density = Density(1f)
    private var prevImeBottomPx: Int = 0

    val rememberedNetImeDp: Dp get() = tracker.rememberedNetHeightDp
    val isEmojiModeActive: Boolean get() = mode == EmojiPanelMode.Emoji
    val isBackInterceptActive: Boolean get() = mode != EmojiPanelMode.Closed
    val panelContentHeightDp: Dp get() = resolveEmojiPanelHeight(tracker.rememberedNetHeightDp, liveWindowHeightDp)
    val panelTotalHeightDp: Dp get() = resolveEmojiPanelTotalHeight(tracker.rememberedNetHeightDp, liveNavBarBottomDp, liveWindowHeightDp)

    val bottomSlotHeightDp: Dp
        get() = if (!consumeImeInSlot) {
            if (mode == EmojiPanelMode.Emoji) panelContentHeightDp else 0.dp
        } else when (mode) {
            EmojiPanelMode.Closed -> liveImeBottomDp
            EmojiPanelMode.Emoji -> maxOf(lockedEmojiTotalHeightDp.takeIf { it > 0.dp } ?: panelTotalHeightDp, liveImeBottomDp)
            EmojiPanelMode.SwitchingToIme -> maxOf(handoffHoldHeightDp, liveImeBottomDp)
        }

    val panelInnerNavBarPaddingDp: Dp
        get() = if (!consumeImeInSlot) 0.dp else resolveEmojiPanelInnerNavPadding(
            lockedEmojiTotalHeightDp.takeIf { it > 0.dp } ?: panelTotalHeightDp,
            tracker.rememberedNetHeightDp,
            liveNavBarBottomDp,
        )

    internal fun syncFrame(imeBottomPx: Int, navBarBottomPx: Int, navBarBottomDp: Dp, windowHeightDp: Dp, density: Density) {
        liveImeBottomPx = imeBottomPx.coerceAtLeast(0)
        liveNavBarBottomPx = navBarBottomPx.coerceAtLeast(0)
        liveImeBottomDp = with(density) { liveImeBottomPx.toDp() }
        liveNavBarBottomDp = navBarBottomDp.coerceAtLeast(0.dp)
        liveWindowHeightDp = windowHeightDp.coerceAtLeast(0.dp)
        liveDensity = density
        if (mode == EmojiPanelMode.Emoji && !awaitingImeHide) {
            val updated = resolveEmojiPanelTotalHeight(tracker.rememberedNetHeightDp, liveNavBarBottomDp, liveWindowHeightDp)
            if (updated != lockedEmojiTotalHeightDp) lockedEmojiTotalHeightDp = updated
        }
    }

    internal fun onImeInsetFrame(imeBottomPx: Int, navBarBottomPx: Int, density: Density) {
        val safeImePx = imeBottomPx.coerceAtLeast(0)
        val imeDp = with(density) { safeImePx.toDp() }
        val prevPx = prevImeBottomPx
        prevImeBottomPx = safeImePx
        tracker.observeInsets(safeImePx, navBarBottomPx, density, freezeUpdates = mode != EmojiPanelMode.Closed)

        when (mode) {
            EmojiPanelMode.Closed -> Unit
            EmojiPanelMode.Emoji -> {
                if (safeImePx == 0) awaitingImeHide = false
                else if (!awaitingImeHide && safeImePx > prevPx) {
                    val hold = lockedEmojiTotalHeightDp.takeIf { it > 0.dp } ?: panelTotalHeightDp
                    if (imeDp >= hold - IME_HANDOFF_MATCH_TOLERANCE) resetToClosed()
                    else { handoffHoldHeightDp = hold; mode = EmojiPanelMode.SwitchingToIme }
                }
            }
            EmojiPanelMode.SwitchingToIme -> {
                if (handoffHoldHeightDp <= 0.dp || imeDp >= handoffHoldHeightDp - IME_HANDOFF_MATCH_TOLERANCE) {
                    resetToClosed()
                    if (safeImePx > 0) tracker.captureCurrentIme(safeImePx, navBarBottomPx, density)
                }
            }
        }
    }

    internal fun onHandoffTimeout() {
        if (mode != EmojiPanelMode.SwitchingToIme) return
        resetToClosed()
        if (liveImeBottomPx > 0) tracker.captureCurrentIme(liveImeBottomPx, liveNavBarBottomPx, liveDensity)
    }

    fun openEmojiPanel() {
        when (mode) {
            EmojiPanelMode.Emoji -> return
            EmojiPanelMode.Closed -> {
                val netDp = calculateNetKeyboardHeightDp(liveImeBottomPx, liveNavBarBottomPx, liveDensity)
                if (liveImeBottomPx > 0 && netDp > 0.dp) {
                    tracker.captureCurrentIme(liveImeBottomPx, liveNavBarBottomPx, liveDensity)
                    lockedEmojiTotalHeightDp = liveImeBottomDp
                    awaitingImeHide = true
                } else {
                    lockedEmojiTotalHeightDp = resolveEmojiPanelTotalHeight(tracker.rememberedNetHeightDp, liveNavBarBottomDp, liveWindowHeightDp)
                    awaitingImeHide = liveImeBottomPx > 0
                }
            }
            EmojiPanelMode.SwitchingToIme -> {
                lockedEmojiTotalHeightDp = handoffHoldHeightDp.takeIf { it > 0.dp } ?: panelTotalHeightDp
                awaitingImeHide = liveImeBottomPx > 0
            }
        }
        handoffHoldHeightDp = 0.dp
        mode = EmojiPanelMode.Emoji
        backHandlerKey += 1
        onHideKeyboard()
    }

    fun switchToKeyboard() {
        if (mode == EmojiPanelMode.Closed) { onShowKeyboard(); return }
        val hold = lockedEmojiTotalHeightDp.takeIf { it > 0.dp } ?: panelTotalHeightDp
        awaitingImeHide = false
        if (!consumeImeInSlot || hold <= 0.dp || liveImeBottomDp >= hold - IME_HANDOFF_MATCH_TOLERANCE) resetToClosed()
        else { handoffHoldHeightDp = hold; mode = EmojiPanelMode.SwitchingToIme }
        onShowKeyboard()
    }

    fun toggle() { if (mode == EmojiPanelMode.Emoji) switchToKeyboard() else openEmojiPanel() }
    fun close() = resetToClosed()

    private fun resetToClosed() {
        mode = EmojiPanelMode.Closed
        awaitingImeHide = false
        handoffHoldHeightDp = 0.dp
        lockedEmojiTotalHeightDp = 0.dp
    }
}

@Composable
fun rememberEmojiPanelController(
    imeBottomPx: Int, navBarBottomPx: Int, navBarBottomDp: Dp, windowHeightDp: Dp,
    onShowKeyboard: () -> Unit, onHideKeyboard: () -> Unit, consumeImeInSlot: Boolean = true,
): EmojiPanelController {
    val density = LocalDensity.current
    val tracker = rememberKeyboardHeightTracker(windowHeightDp)
    val latestShow by rememberUpdatedState(onShowKeyboard)
    val latestHide by rememberUpdatedState(onHideKeyboard)
    val controller = remember(tracker, consumeImeInSlot) {
        EmojiPanelController(tracker, consumeImeInSlot, { latestShow() }, { latestHide() })
    }
    controller.syncFrame(imeBottomPx, navBarBottomPx, navBarBottomDp, windowHeightDp, density)
    LaunchedEffect(controller, imeBottomPx, navBarBottomPx, density) { controller.onImeInsetFrame(imeBottomPx, navBarBottomPx, density) }
    LaunchedEffect(controller, controller.mode, imeBottomPx) {
        if (controller.mode == EmojiPanelMode.SwitchingToIme) { delay(IME_HANDOFF_SETTLE_TIMEOUT_MS); controller.onHandoffTimeout() }
    }
    return controller
}

/** 底部统一占位槽与条件 `BackHandler` 宿主（纯色表面，0 `HiGlassMarker`）。 */
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
            modifier.fillMaxWidth().height(heightDp).background(surfaceBg).padding(bottom = controller.panelInnerNavBarPaddingDp),
        ) { emojiPanelSlot(host) }
        controller.mode == EmojiPanelMode.SwitchingToIme -> Box(modifier.fillMaxWidth().height(heightDp).background(surfaceBg))
        else -> Spacer(modifier.fillMaxWidth().height(heightDp))
    }
}
