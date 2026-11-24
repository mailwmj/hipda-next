package net.jejer.hipda.ui.designsystem.motion

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope

private val DefaultMotionDurationScale: MotionDurationScale = object : MotionDurationScale {
    override val scaleFactor: Float = 1f
}

/**
 * 当前生效的 [MotionDurationScale]，由 [net.jejer.hipda.ui.designsystem.HiTheme] 根部注入。
 *
 * - 真实窗口中，Compose `WindowRecomposer` 已将系统 `ANIMATOR_DURATION_SCALE` 注入 Recomposer 协程上下文；
 * - 本 CompositionLocal 同步读取并监听系统 `ANIMATOR_DURATION_SCALE`，供需要根据“减少动态效果”调整行为
 *   （例如数字直接跳变、禁用视差、引用高亮改为静态停留 `HiMotion.highlightHold`）的组件与自定义动画协程读取。
 */
val LocalHiMotionDurationScale = compositionLocalOf<MotionDurationScale> {
    DefaultMotionDurationScale
}

/**
 * 读取系统 [Settings.Global.ANIMATOR_DURATION_SCALE]，缺省或非法（负值/NaN/Infinity）时回退为 `1f`。
 */
fun readAnimatorDurationScale(context: Context): Float {
    val raw = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    return if (raw.isFinite() && raw >= 0f) raw else 1f
}

/**
 * 实时读取系统 [Settings.Global.ANIMATOR_DURATION_SCALE] 的 [MotionDurationScale]，
 * 可直接用于 `CoroutineContext`、`Recomposer` 或测试环境的 `effectContext`。
 */
class HiSystemMotionDurationScale(
    context: Context,
) : MotionDurationScale {
    private val appContext: Context = context.applicationContext ?: context

    override val scaleFactor: Float
        get() = readAnimatorDurationScale(appContext)
}

@Stable
private class ObservableHiMotionDurationScale(
    initialScale: Float,
) : MotionDurationScale {
    var mutableScaleFactor: Float by mutableFloatStateOf(initialScale)

    override val scaleFactor: Float
        get() = mutableScaleFactor
}

/**
 * 读取并持续监听系统 [Settings.Global.ANIMATOR_DURATION_SCALE] 变化的 [MotionDurationScale]。
 */
@Composable
fun rememberSystemMotionDurationScale(): MotionDurationScale {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext ?: context }
    val scaleState = remember(appContext) {
        ObservableHiMotionDurationScale(readAnimatorDurationScale(appContext))
    }

    DisposableEffect(appContext) {
        val resolver = appContext.contentResolver
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        scaleState.mutableScaleFactor = readAnimatorDurationScale(appContext)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scaleState.mutableScaleFactor = readAnimatorDurationScale(appContext)
            }
        }
        resolver.registerContentObserver(uri, false, observer)
        onDispose {
            resolver.unregisterContentObserver(observer)
        }
    }

    return scaleState
}

/**
 * 在子树根部提供 [LocalHiMotionDurationScale]；未显式传入 [motionDurationScale] 时自动监听系统设置。
 */
@Composable
fun ProvideHiMotionDurationScale(
    motionDurationScale: MotionDurationScale? = null,
    content: @Composable () -> Unit,
) {
    val resolvedScale = motionDurationScale ?: rememberSystemMotionDurationScale()
    CompositionLocalProvider(
        LocalHiMotionDurationScale provides resolvedScale,
        content = content,
    )
}

/**
 * 返回绑定当前 [LocalHiMotionDurationScale] 的 [CoroutineScope]，供手动驱动 `Animatable.animateTo` 使用。
 */
@Composable
fun rememberHiMotionCoroutineScope(): CoroutineScope {
    val latestScale by rememberUpdatedState(LocalHiMotionDurationScale.current)
    return rememberCoroutineScope {
        object : MotionDurationScale {
            override val scaleFactor: Float
                get() = latestScale.scaleFactor
        }
    }
}
