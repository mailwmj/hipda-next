package net.jejer.hipda.feature.compose.emoji

import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.WeakHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val SMALLER_IME_SETTLE_DELAY_MS: Long = 180L

/** 窗口会话级软键盘净高缓存：按 `rootView` + 窗口高度桶隔离，同窗口内三处输入复用，旋转/分屏自动换桶且零泄漏。 */
internal object SessionKeyboardHeightStore {
    private val cache = WeakHashMap<View, MutableMap<Int, Dp>>()

    fun get(rootView: View?, windowHeightBucketDp: Int): Dp =
        if (rootView == null || windowHeightBucketDp <= 0) 0.dp
        else synchronized(cache) { cache[rootView]?.get(windowHeightBucketDp) ?: 0.dp }

    fun put(rootView: View?, windowHeightBucketDp: Int, netHeightDp: Dp) {
        if (rootView == null || windowHeightBucketDp <= 0 || netHeightDp <= 0.dp) return
        synchronized(cache) { cache.getOrPut(rootView) { HashMap() }[windowHeightBucketDp] = netHeightDp }
    }
}

/**
 * 帧安全软键盘高度记忆器：持续记忆当前会话扣除导航栏底部 inset 后的净键盘高；
 * 上升帧即时抬高，下降帧防退场中间帧污染（稳定 180ms 才提交），切入表情瞬间立刻捕获当前实测高度。
 */
@Stable
class KeyboardHeightTracker internal constructor(
    private val rootView: View?,
    initialWindowHeightBucketDp: Int,
) {
    private var windowHeightBucketDp: Int = initialWindowHeightBucketDp
    var rememberedNetHeightDp: Dp by mutableStateOf(SessionKeyboardHeightStore.get(rootView, initialWindowHeightBucketDp))
        private set
    internal var pendingSmallerNetHeightDp: Dp? by mutableStateOf(null)
        private set

    fun syncWindowHeight(windowHeightDp: Dp) {
        val bucket = windowHeightDp.value.roundToInt().coerceAtLeast(0)
        if (bucket == windowHeightBucketDp) return
        windowHeightBucketDp = bucket
        pendingSmallerNetHeightDp = null
        rememberedNetHeightDp = SessionKeyboardHeightStore.get(rootView, bucket)
    }

    fun observeInsets(imeBottomPx: Int, navBarBottomPx: Int, density: Density, freezeUpdates: Boolean) {
        val netDp = calculateNetKeyboardHeightDp(imeBottomPx, navBarBottomPx, density)
        if (freezeUpdates) { pendingSmallerNetHeightDp = null; return }
        when {
            netDp <= 0.dp -> pendingSmallerNetHeightDp = null
            netDp >= rememberedNetHeightDp -> {
                pendingSmallerNetHeightDp = null
                if (netDp != rememberedNetHeightDp) commitNetHeight(netDp)
            }
            else -> if (pendingSmallerNetHeightDp != netDp) pendingSmallerNetHeightDp = netDp
        }
    }

    fun captureCurrentIme(imeBottomPx: Int, navBarBottomPx: Int, density: Density): Dp {
        val netDp = calculateNetKeyboardHeightDp(imeBottomPx, navBarBottomPx, density)
        pendingSmallerNetHeightDp = null
        if (netDp > 0.dp) commitNetHeight(netDp)
        return rememberedNetHeightDp
    }

    internal fun commitPendingSmallerIfMatches(candidate: Dp) {
        if (pendingSmallerNetHeightDp == candidate && candidate > 0.dp) {
            pendingSmallerNetHeightDp = null
            commitNetHeight(candidate)
        }
    }

    private fun commitNetHeight(netDp: Dp) {
        rememberedNetHeightDp = netDp
        SessionKeyboardHeightStore.put(rootView, windowHeightBucketDp, netDp)
    }
}

@Composable
fun rememberKeyboardHeightTracker(windowHeightDp: Dp): KeyboardHeightTracker {
    val rootView = LocalView.current.rootView
    val tracker = remember(rootView) { KeyboardHeightTracker(rootView, windowHeightDp.value.roundToInt().coerceAtLeast(0)) }
    tracker.syncWindowHeight(windowHeightDp)
    val pending = tracker.pendingSmallerNetHeightDp
    LaunchedEffect(tracker, pending) {
        if (pending != null && pending > 0.dp) { delay(SMALLER_IME_SETTLE_DELAY_MS); tracker.commitPendingSmallerIfMatches(pending) }
    }
    return tracker
}
