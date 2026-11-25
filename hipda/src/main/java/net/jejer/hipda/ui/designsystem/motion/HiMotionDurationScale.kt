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
import androidx.compose.ui.platform.LocalInspectionMode
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
 * 读取系统 [Settings.Global.ANIMATOR_DURATION_SCALE]；在无 `ContentResolver`、权限受限或值非法（负值/NaN/Infinity）时安全回退为 `1f`。
 */
fun readAnimatorDurationScale(context: Context): Float {
    val raw = runCatching {
        val resolver = context.contentResolver
        if (resolver == null) {
            1f
        } else {
            Settings.Global.getFloat(
                resolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }
    }.getOrDefault(1f)
    return if (raw.isFinite() && raw >= 0f) raw else 1f
}

private fun safeApplicationContext(context: Context): Context =
    runCatching { context.applicationContext }.getOrNull() ?: context

/**
 * 实时读取系统 [Settings.Global.ANIMATOR_DURATION_SCALE] 的 [MotionDurationScale]，
 * 可直接用于 `CoroutineContext`、`Recomposer` 或测试环境的 `effectContext`。
 */
class HiSystemMotionDurationScale(
    context: Context,
) : MotionDurationScale {
    private val appContext: Context = safeApplicationContext(context)

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
 * 在 Preview / [LocalInspectionMode] 下跳过系统监听直接返回默认 `1f`。
 */
@Composable
fun rememberSystemMotionDurationScale(): MotionDurationScale {
    if (LocalInspectionMode.current) {
        return DefaultMotionDurationScale
    }
    val context = LocalContext.current
    val appContext = remember(context) { safeApplicationContext(context) }
    val scaleState = remember(appContext) {
        ObservableHiMotionDurationScale(readAnimatorDurationScale(appContext))
    }

    DisposableEffect(appContext) {
        scaleState.mutableScaleFactor = readAnimatorDurationScale(appContext)
        val resolver = runCatching { appContext.contentResolver }.getOrNull()
        val uri = runCatching {
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        }.getOrNull()
        val observer = if (resolver != null && uri != null) {
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    scaleState.mutableScaleFactor = readAnimatorDurationScale(appContext)
                }
            }
        } else {
            null
        }
        val registered = if (resolver != null && uri != null && observer != null) {
            runCatching {
                resolver.registerContentObserver(uri, false, observer)
                true
            }.getOrDefault(false)
        } else {
            false
        }
        onDispose {
            if (registered && resolver != null && observer != null) {
                runCatching { resolver.unregisterContentObserver(observer) }
            }
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
