package net.jejer.hipda.ui.designsystem

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** 浅色主题下在 API 26 无法渲染深色导航栏图标时的半透明深色护底，与 AndroidX EdgeToEdge 默认值一致。 */
internal val HI_LIGHT_NAV_BAR_DARK_SCRIM: Int = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

/**
 * 统一开启并实时同步全面屏 Edge-to-Edge 系统栏外观：
 * - 深色 / 纯黑 OLED / 全屏看图：透明底 + 浅色图标；
 * - 浅色主题：透明底 + 深色图标（API 26 自动回退半透明深色导航栏护底保证可读性）。
 */
fun ComponentActivity.applyHiEdgeToEdge(darkTheme: Boolean) {
    val statusBarStyle = if (darkTheme) {
        SystemBarStyle.dark(Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    }
    val navigationBarStyle = if (darkTheme) {
        SystemBarStyle.dark(Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(Color.TRANSPARENT, HI_LIGHT_NAV_BAR_DARK_SCRIM)
    }
    enableEdgeToEdge(
        statusBarStyle = statusBarStyle,
        navigationBarStyle = navigationBarStyle,
    )
}

/** 在首帧 Compose 组合前读取系统夜间模式，避免冷启动状态栏/导航栏闪白或闪黑。 */
fun Context.isSystemNightModeActive(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** 顶部安全区：合并状态栏与顶部挖孔/刘海，确保 Android 15/16 强制全面屏下顶栏与内容均不被挖孔遮挡。 */
val WindowInsets.Companion.hiTopSafeBar: WindowInsets
    @Composable get() = WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top)

internal tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/** 随 Compose [HiTheme]（及全屏看图覆盖态）实时同步宿主 Activity 的系统栏图标对比度。 */
@Composable
fun SyncHiEdgeToEdge(darkTheme: Boolean) {
    val activity = LocalContext.current.findComponentActivity() ?: return
    DisposableEffect(activity, darkTheme) {
        activity.applyHiEdgeToEdge(darkTheme)
        onDispose {}
    }
}
