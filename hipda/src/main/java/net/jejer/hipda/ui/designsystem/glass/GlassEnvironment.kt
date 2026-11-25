package net.jejer.hipda.ui.designsystem.glass

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 触发毛玻璃降级为不透明度 94% 纯色表面的原因（product-design.md §5、§10.3）。 */
enum class GlassDegradationReason {
    /** 设备系统版本为 Android 11（API 30）及以下，不支持硬件 RenderEffect 实时模糊。 */
    UNSUPPORTED_API_LEVEL,
    /** 系统已开启省电模式（[PowerManager.isPowerSaveMode]）。 */
    POWER_SAVE_MODE,
    /** 系统已开启“降低透明度”或禁用窗口模糊（减少模糊效果）。 */
    REDUCE_TRANSPARENCY,
    /** 系统已开启高对比度文字或高色彩对比度。 */
    HIGH_CONTRAST_TEXT,
}

/** 毛玻璃环境不可变状态；仅当 [reasons] 为空时 [blurEnabled] 为 `true`。 */
@Immutable
data class GlassEnvironmentState(
    val reasons: Set<GlassDegradationReason> = emptySet(),
) {
    val blurEnabled: Boolean get() = reasons.isEmpty()

    companion object {
        val BlurEnabled: GlassEnvironmentState = GlassEnvironmentState(emptySet())
    }
}

/** 底层系统信号快照，由可替换的 [GlassEnvironmentSignalSource] 提供。 */
@Immutable
data class GlassEnvironmentSignals(
    val sdkInt: Int,
    val isPowerSaveMode: Boolean,
    val isReduceTransparencyEnabled: Boolean,
    val isHighContrastTextEnabled: Boolean,
) {
    fun toEnvironmentState(): GlassEnvironmentState = GlassEnvironmentState(
        buildSet {
            if (sdkInt < Build.VERSION_CODES.S) add(GlassDegradationReason.UNSUPPORTED_API_LEVEL)
            if (isPowerSaveMode) add(GlassDegradationReason.POWER_SAVE_MODE)
            if (isReduceTransparencyEnabled) add(GlassDegradationReason.REDUCE_TRANSPARENCY)
            if (isHighContrastTextEnabled) add(GlassDegradationReason.HIGH_CONTRAST_TEXT)
        },
    )
}

/** 可注入的系统信号源接口；测试可用内存替身驱动所有降级与恢复分支。 */
interface GlassEnvironmentSignalSource {
    fun currentSignals(): GlassEnvironmentSignals
    fun observeSignals(): Flow<GlassEnvironmentSignals>
}

/** 毛玻璃环境状态管理器，对外提供同步读取、[Flow] 与 [StateFlow]。 */
@Singleton
class GlassEnvironment @Inject constructor(
    private val signalSource: GlassEnvironmentSignalSource,
) {
    fun currentState(): GlassEnvironmentState = signalSource.currentSignals().toEnvironmentState()

    fun observeState(): Flow<GlassEnvironmentState> =
        signalSource.observeSignals().map { it.toEnvironmentState() }.distinctUntilChanged()

    fun stateIn(
        scope: CoroutineScope,
        started: SharingStarted = SharingStarted.WhileSubscribed(5_000),
    ): StateFlow<GlassEnvironmentState> =
        observeState().stateIn(scope, started, currentState())
}

internal interface SettingsIntReader {
    fun getGlobalInt(resolver: ContentResolver, key: String, default: Int): Int =
        Settings.Global.getInt(resolver, key, default)
    fun getSystemInt(resolver: ContentResolver, key: String, default: Int): Int =
        Settings.System.getInt(resolver, key, default)
    fun getSecureInt(resolver: ContentResolver, key: String, default: Int): Int =
        Settings.Secure.getInt(resolver, key, default)

    companion object {
        val Default: SettingsIntReader = object : SettingsIntReader {}
    }
}

/**
 * Android 平台毛玻璃信号源，各版本可读能力说明：
 * - **API ≤ 30**：全版本（API 26+）由 `Build.VERSION.SDK_INT < 31` 精确判定。
 * - **省电模式**：全版本（API 26+）读 [PowerManager.isPowerSaveMode] 并监听 [PowerManager.ACTION_POWER_SAVE_MODE_CHANGED]。
 * - **降低透明度 / 禁用模糊**：
 *   - API 31+：读取并监听 `Settings.Global` 的 `@Readable` 键 `disable_window_blurs`（Android 12–15 开发者选项，Android 16+ 兼作无障碍“减少模糊效果”）与 `accessibility_reduce_transparency`（及旧版 OEM 的 `Settings.System` 同名键）；不将 `WindowManager.isCrossWindowBlurEnabled() == false` 直接判为降低透明度，避免仅关闭跨窗口模糊但支持应用内 RenderEffect 的设备误降级。
 *   - API 26–30：AOSP 无公开 API，**该版本无法可靠检测**（仅尽力读 OEM 键并捕获 [SecurityException]；该区间已因 `UNSUPPORTED_API_LEVEL` 必然降级）。
 * - **高对比度文字 / 高色彩对比度**：
 *   - API 36+：公开 API [AccessibilityManager.isHighContrastTextEnabled] 与状态监听，并叠加 API 34+ 色彩对比度。
 *   - API 34–35：公开 API [UiModeManager.getContrast]（`>= 0.5f`）与监听检测色彩对比度；高对比度文字**该版本无公开 API**，尽力读 `Settings.Secure` 的 `high_text_contrast_enabled`，受权限限制时捕获 [SecurityException] 回退 `false`（受限设备上无法检测）。
 *   - API 26–33：无公开 API，仅尽力读 `Settings.Secure` 的 `high_text_contrast_enabled`（受限设备上**该版本无法检测**；API 26–30 已因 `UNSUPPORTED_API_LEVEL` 必然降级）。
 */
@Singleton
class AndroidGlassEnvironmentSignalSource internal constructor(
    context: Context,
    private val sdkIntProvider: () -> Int,
    private val settingsReader: SettingsIntReader = SettingsIntReader.Default,
) : GlassEnvironmentSignalSource {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context = context,
        sdkIntProvider = { Build.VERSION.SDK_INT },
        settingsReader = SettingsIntReader.Default,
    )

    private val appContext: Context = context.applicationContext

    override fun currentSignals(): GlassEnvironmentSignals = GlassEnvironmentSignals(
        sdkInt = sdkIntProvider(),
        isPowerSaveMode = appContext.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true,
        isReduceTransparencyEnabled = readReduceTransparency(),
        isHighContrastTextEnabled = readHighContrastText(),
    )

    override fun observeSignals(): Flow<GlassEnvironmentSignals> = callbackFlow {
        val emitCurrent = { trySend(currentSignals()); Unit }
        emitCurrent()

        val powerSaveReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) emitCurrent()
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val resolver = appContext.contentResolver
        val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = emitCurrent()
        }
        listOf(
            Settings.Global.getUriFor(SETTING_DISABLE_WINDOW_BLURS),
            Settings.Global.getUriFor(SETTING_REDUCE_TRANSPARENCY),
            Settings.System.getUriFor(SETTING_REDUCE_TRANSPARENCY),
            Settings.Secure.getUriFor(SETTING_HIGH_TEXT_CONTRAST_ENABLED),
        ).forEach { uri -> runCatching { resolver.registerContentObserver(uri, false, settingsObserver) } }

        val mainExecutor = ContextCompat.getMainExecutor(appContext)
        val uiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            appContext.getSystemService(UiModeManager::class.java)
        } else null
        val contrastListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && uiMode != null) {
            runCatching { Api34ContrastBridge.add(uiMode, mainExecutor, emitCurrent) }.getOrNull()
        } else null

        val accessibility = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            appContext.getSystemService(AccessibilityManager::class.java)
        } else null
        val highContrastListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && accessibility != null) {
            runCatching { Api36HighContrastBridge.add(accessibility, mainExecutor, emitCurrent) }.getOrNull()
        } else null

        awaitClose {
            runCatching { appContext.unregisterReceiver(powerSaveReceiver) }
            runCatching { resolver.unregisterContentObserver(settingsObserver) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && uiMode != null && contrastListener != null) {
                runCatching { Api34ContrastBridge.remove(uiMode, contrastListener) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && accessibility != null && highContrastListener != null) {
                runCatching { Api36HighContrastBridge.remove(accessibility, highContrastListener) }
            }
        }
    }.conflate().distinctUntilChanged()

    private fun readReduceTransparency(): Boolean {
        val r = appContext.contentResolver
        return safeSettingFlag { settingsReader.getGlobalInt(r, SETTING_DISABLE_WINDOW_BLURS, 0) } ||
            safeSettingFlag { settingsReader.getGlobalInt(r, SETTING_REDUCE_TRANSPARENCY, 0) } ||
            safeSettingFlag { settingsReader.getSystemInt(r, SETTING_REDUCE_TRANSPARENCY, 0) }
    }

    private fun readHighContrastText(): Boolean {
        val sdk = sdkIntProvider()
        if (sdk >= Build.VERSION_CODES.BAKLAVA && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val am = appContext.getSystemService(AccessibilityManager::class.java)
            if (am != null && Api36HighContrastBridge.isEnabled(am)) return true
        }
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val um = appContext.getSystemService(UiModeManager::class.java)
            if (um != null && Api34ContrastBridge.isHighContrast(um)) return true
        }
        return safeSettingFlag {
            settingsReader.getSecureInt(appContext.contentResolver, SETTING_HIGH_TEXT_CONTRAST_ENABLED, 0)
        }
    }

    private inline fun safeSettingFlag(read: () -> Int): Boolean =
        try { read() == 1 } catch (_: SecurityException) { false }

    companion object {
        internal const val SETTING_DISABLE_WINDOW_BLURS: String = "disable_window_blurs"
        internal const val SETTING_REDUCE_TRANSPARENCY: String = "accessibility_reduce_transparency"
        internal const val SETTING_HIGH_TEXT_CONTRAST_ENABLED: String = "high_text_contrast_enabled"
        internal const val HIGH_COLOR_CONTRAST_THRESHOLD: Float = 0.5f
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private object Api34ContrastBridge {
    fun isHighContrast(um: UiModeManager): Boolean =
        um.contrast >= AndroidGlassEnvironmentSignalSource.HIGH_COLOR_CONTRAST_THRESHOLD
    fun add(um: UiModeManager, executor: Executor, onChange: () -> Unit): UiModeManager.ContrastChangeListener =
        UiModeManager.ContrastChangeListener { onChange() }.also { um.addContrastChangeListener(executor, it) }
    fun remove(um: UiModeManager, listener: UiModeManager.ContrastChangeListener) =
        um.removeContrastChangeListener(listener)
}

@RequiresApi(Build.VERSION_CODES.BAKLAVA)
private object Api36HighContrastBridge {
    fun isEnabled(am: AccessibilityManager): Boolean = am.isHighContrastTextEnabled
    fun add(
        am: AccessibilityManager,
        executor: Executor,
        onChange: () -> Unit,
    ): AccessibilityManager.HighContrastTextStateChangeListener =
        AccessibilityManager.HighContrastTextStateChangeListener { onChange() }
            .also { am.addHighContrastTextStateChangeListener(executor, it) }
    fun remove(am: AccessibilityManager, listener: AccessibilityManager.HighContrastTextStateChangeListener) =
        am.removeHighContrastTextStateChangeListener(listener)
}

val LocalGlassEnvironmentSignalSource: ProvidableCompositionLocal<GlassEnvironmentSignalSource?> =
    staticCompositionLocalOf { null }

val LocalGlassEnvironmentState: ProvidableCompositionLocal<GlassEnvironmentState?> =
    compositionLocalOf { null }

/** 记住并观察当前毛玻璃环境状态。 */
@Composable
fun rememberGlassEnvironmentState(
    signalSource: GlassEnvironmentSignalSource? = null,
): State<GlassEnvironmentState> {
    val context = LocalContext.current.applicationContext
    val resolved = signalSource
        ?: LocalGlassEnvironmentSignalSource.current
        ?: remember(context) { AndroidGlassEnvironmentSignalSource(context) }
    val environment = remember(resolved) { GlassEnvironment(resolved) }
    val initial = remember(environment) { environment.currentState() }
    val flow = remember(environment) { environment.observeState() }
    return flow.collectAsState(initial = initial)
}

/** 为子树提供动态观察的毛玻璃环境状态，避免同页多个 [HiGlassSurface] 重复注册系统监听。 */
@Composable
fun ProvideGlassEnvironment(
    signalSource: GlassEnvironmentSignalSource? = null,
    content: @Composable () -> Unit,
) {
    val state = rememberGlassEnvironmentState(signalSource = signalSource)
    CompositionLocalProvider(LocalGlassEnvironmentState provides state.value, content = content)
}

/** 为子树提供固定毛玻璃环境状态（便于截图测试与预览模拟）。 */
@Composable
fun ProvideGlassEnvironment(
    state: GlassEnvironmentState,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalGlassEnvironmentState provides state, content = content)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GlassModule {
    @Binds
    @Singleton
    abstract fun bindGlassEnvironmentSignalSource(
        impl: AndroidGlassEnvironmentSignalSource,
    ): GlassEnvironmentSignalSource
}
