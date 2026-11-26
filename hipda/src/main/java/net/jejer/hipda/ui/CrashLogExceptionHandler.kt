package net.jejer.hipda.ui

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.jejer.hipda.BuildConfig
import net.jejer.hipda.feature.settings.ErrorLogSanitizer

/**
 * 未捕获异常日志记录器：将脱敏后的设备信息与堆栈写入 `filesDir/logs/crash-*.log`，
 * 与新版「设置 → 关于 → 错误日志」（`AndroidSettingsErrorLogStore`）保持同一目录契约。
 */
internal class CrashLogExceptionHandler(
    private val logsDirProvider: () -> File,
    private val deviceInfoProvider: () -> String,
    internal val delegate: Thread.UncaughtExceptionHandler?,
    private val nowProvider: () -> Date = { Date() },
    private val maxLogFiles: Int = DEFAULT_MAX_LOG_FILES,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching { writeCrashLog(throwable) }
        delegate?.uncaughtException(thread, throwable)
    }

    internal fun writeCrashLog(throwable: Throwable): File? {
        val logsDir = logsDirProvider()
        if (!logsDir.exists() && !logsDir.mkdirs() && !logsDir.isDirectory) {
            return null
        }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(nowProvider())
        val logFile = File(logsDir, "crash-$timestamp.log")
        val content = buildString {
            append(deviceInfoProvider().trimEnd())
            append(System.lineSeparator())
            append(ErrorLogSanitizer.sanitize(formatStackTrace(throwable)))
        }
        logFile.writeText(content, Charsets.UTF_8)
        pruneOldLogs(logsDir)
        return logFile
    }

    private fun pruneOldLogs(logsDir: File) {
        if (maxLogFiles <= 0) return
        val existing = logsDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash-") && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        existing.drop(maxLogFiles).forEach { file -> runCatching { file.delete() } }
    }

    companion object {
        const val LOGS_DIR_NAME: String = "logs"
        const val DEFAULT_MAX_LOG_FILES: Int = 10

        fun install(context: Context): CrashLogExceptionHandler {
            val appContext = context.applicationContext ?: context
            val current = Thread.getDefaultUncaughtExceptionHandler()
            val underlying = if (current is CrashLogExceptionHandler) current.delegate else current
            val handler = CrashLogExceptionHandler(
                logsDirProvider = { File(appContext.filesDir, LOGS_DIR_NAME) },
                deviceInfoProvider = { buildDeviceInfo(appContext) },
                delegate = underlying,
            )
            Thread.setDefaultUncaughtExceptionHandler(handler)
            return handler
        }

        internal fun buildDeviceInfo(context: Context): String {
            val manufacturer = Build.MANUFACTURER.orEmpty()
            val model = Build.MODEL.orEmpty()
            val deviceName = if (manufacturer.isBlank() || model.startsWith(manufacturer, ignoreCase = true)) {
                model.ifBlank { manufacturer }
            } else {
                "$manufacturer $model"
            }
            val versionName = runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().takeUnless { it.isNullOrBlank() } ?: BuildConfig.VERSION_NAME
            val maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
            return buildString {
                appendLine("设备名称 : $deviceName")
                appendLine("内存限制 : ${maxMemoryMb}MB")
                appendLine("系统版本 : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("客户端版本 : $versionName (${BuildConfig.VERSION_CODE})")
            }
        }

        private fun formatStackTrace(throwable: Throwable): String {
            val sw = StringWriter()
            PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
            return sw.toString()
        }
    }
}
