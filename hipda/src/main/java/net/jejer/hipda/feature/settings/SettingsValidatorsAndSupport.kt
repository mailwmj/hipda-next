package net.jejer.hipda.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.WebStorage
import coil3.ImageLoader
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jejer.hipda.R
import net.jejer.hipda.data.user.UserError
import net.jejer.hipda.di.ImageLoaderModule
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.feature.common.UiErrorMapper
import net.jejer.hipda.feature.threaddetail.media.ImageSizeFormatter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val CLIENT_RELEASE_TID: String = "1579403"
internal val IMAGE_CACHE_LIMIT_OPTIONS_MB: List<Int> = listOf(200, 300, 500, 1024, 2048)

/** 设置页 URL 严格白名单校验：仅放行 `http`/`https` 且 host 非空，拦截一切危险 scheme 与控制字符。 */
object SettingsUrlValidator {
    fun validateHttpUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.any { it.isISOControl() || it.isWhitespace() }) return null
        val afterScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
            else -> return null
        }
        if (afterScheme.isEmpty() || afterScheme.startsWith('/') || afterScheme.startsWith('\\')) return null
        val parsed = trimmed.toHttpUrlOrNull() ?: return null
        return if ((parsed.scheme == "http" || parsed.scheme == "https") && parsed.host.isNotBlank()) parsed.toString() else null
    }

    fun validateForumServer(raw: String?): String? = validateHttpUrl(raw)?.trimEnd('/')

    fun validateTailUrl(raw: String?): String? =
        raw?.trim().orEmpty().let { if (it.isEmpty()) "" else validateHttpUrl(it) }
}

/** 错误日志脱敏器：严格剥离密码、MD5 凭据、Cookie、Authorization 与会话令牌。 */
object ErrorLogSanitizer {
    private val HEADER_SENSITIVE = Regex("""(?im)(\b(?:set-cookie|cookie|authorization|proxy-authorization)\s*:\s*)[^\r\n]*""")
    private val KEY_VALUE_SENSITIVE = Regex("""(?i)(\b(?:password(?:md5)?|passwd|pwd|security_?answer|answer|cookie|set-cookie|cdb_auth|cdb_sid|cf_clearance|phpsessid|formhash|auth_?token|access_?token|session_?id)\b\s*[:=]\s*)([^\s&;,"'\)\]\}]+)""")
    private val JSON_SENSITIVE = Regex("""(?i)("(?:password(?:Md5)?|passwd|pwd|securityAnswer|answer|cookie|cookies|authorization|cf_clearance|cdb_auth|token)"\s*:\s*")([^"]*)(")""")

    fun sanitize(raw: String): String = if (raw.isEmpty()) "" else raw
        .replace(HEADER_SENSITIVE, "$1[REDACTED]")
        .replace(JSON_SENSITIVE, "$1[REDACTED]$3")
        .replace(KEY_VALUE_SENSITIVE, "$1[REDACTED]")
}

interface SettingsCacheManager {
    suspend fun getImageCacheSizeBytes(): Long
    suspend fun clearImageCache(): Long
    suspend fun clearWebCache(): Long
}

class AndroidSettingsCacheManager(private val context: Context, private val imageLoader: ImageLoader) : SettingsCacheManager {
    override suspend fun getImageCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        directorySizeBytes(File(context.cacheDir, ImageLoaderModule.IMAGE_CACHE_DIR_NAME))
    }

    override suspend fun clearImageCache(): Long = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, ImageLoaderModule.IMAGE_CACHE_DIR_NAME)
        val before = directorySizeBytes(dir)
        runCatching { imageLoader.memoryCache?.clear() }
        runCatching { imageLoader.diskCache?.clear() }
        deleteDirectoryContents(dir)
        before
    }

    override suspend fun clearWebCache(): Long = withContext(Dispatchers.IO) {
        val dirs = listOf(
            File(context.cacheDir, "WebView"),
            File(context.cacheDir, "okhttp_cache"),
            File(context.applicationInfo.dataDir, "app_webview/Cache"),
            File(context.applicationInfo.dataDir, "app_webview/Default/Cache"),
        )
        val before = dirs.sumOf(::directorySizeBytes)
        dirs.forEach(::deleteDirectoryContents)
        runCatching { WebStorage.getInstance().deleteAllData() }
        before
    }
}

interface SettingsErrorLogStore {
    suspend fun readSanitizedLogs(): String
}

class AndroidSettingsErrorLogStore(private val logsDir: File, private val maxFiles: Int = 5, private val maxChars: Int = 24_000) : SettingsErrorLogStore {
    constructor(context: Context) : this(File(context.filesDir, "logs"))

    override suspend fun readSanitizedLogs(): String = withContext(Dispatchers.IO) {
        val files = logsDir.takeIf { it.isDirectory }?.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.take(maxFiles).orEmpty()
        if (files.isEmpty()) return@withContext ""
        val combined = files.joinToString("\n\n") { file ->
            "=== ${file.name} ===\n${ErrorLogSanitizer.sanitize(runCatching { file.readText() }.getOrDefault(""))}"
        }
        if (combined.length > maxChars) combined.take(maxChars) else combined
    }
}

fun interface SystemNotificationSettingsOpener {
    fun open(): Boolean
}

class AndroidSystemNotificationSettingsOpener(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val launcher: (Intent) -> Unit = { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
) : SystemNotificationSettingsOpener {
    override fun open(): Boolean {
        val primary = if (sdkInt >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else null
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        for (intent in listOfNotNull(primary, fallback)) {
            try {
                launcher(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }
}

internal fun formatCacheSizeBytes(bytes: Long): String = ImageSizeFormatter.formatOrNull(bytes) ?: "0 B"
internal fun formatCacheLimitMb(limitMb: Int): String = if (limitMb >= 1024 && limitMb % 1024 == 0) "${limitMb / 1024} GB" else "$limitMb MB"
internal fun formatMinutesOfDay(minutesOfDay: Int): String = Math.floorMod(minutesOfDay, 24 * 60).let { String.format(Locale.US, "%02d:%02d", it / 60, it % 60) }
internal fun directorySizeBytes(dir: File): Long = if (!dir.exists()) 0L else if (dir.isFile) dir.length() else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
internal fun deleteDirectoryContents(dir: File) {
    if (!dir.exists()) return
    if (dir.isFile) { dir.delete(); return }
    dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
}

fun mapUserErrorToUiError(error: UserError): UiError = when (error) {
    is UserError.AuthRequired -> UiErrorMapper.mapAuthRequired(
        error.reason, listOfNotNull(error.reason.name, error.message.takeIf(String::isNotBlank)).joinToString("\n"),
    )
    is UserError.ChallengeRequired -> UiError(
        if (error.rejected) R.string.challenge_status_rejected else R.string.challenge_status_incomplete,
        error.cooldownUntilMs?.let { "cooldownUntilMs=$it" }, UiErrorAction.Reverify,
    )
    is UserError.NetworkError -> UiError(
        if (error.statusCode != null) R.string.common_error_load_failed else R.string.common_error_network,
        listOfNotNull(error.statusCode?.let { "HTTP $it" }, error.message.takeIf(String::isNotBlank)).joinToString("\n").ifEmpty { null },
        UiErrorAction.Retry,
    )
    is UserError.SiteError -> UiError(R.string.common_error_load_failed, error.message.trim().ifEmpty { null }, UiErrorAction.Retry)
    is UserError.InvalidPage -> UiError(R.string.common_error_load_failed, error.message.trim().ifEmpty { null }, UiErrorAction.Retry)
    is UserError.InvalidInput -> UiError(R.string.common_error_load_failed, error.message.trim().ifEmpty { null }, UiErrorAction.Retry)
}
