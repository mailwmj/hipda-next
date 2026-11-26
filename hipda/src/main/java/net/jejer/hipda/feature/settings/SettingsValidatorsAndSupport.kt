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
import net.jejer.hipda.feature.common.*
import net.jejer.hipda.feature.threaddetail.media.ImageSizeFormatter
import net.jejer.hipda.parser.sanitize.SanitizeSelectors
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val CLIENT_RELEASE_TID: String = "1579403"
internal val IMAGE_CACHE_LIMIT_OPTIONS_MB: List<Int> = listOf(200, 300, 500, 1024, 2048)

/** 设置页 URL 严格白名单校验：仅放行 `http`/`https` 且 host 非空，拦截 userinfo、非法 scheme 与控制字符。 */
object SettingsUrlValidator {
    private fun parseSafeHttpUrl(raw: String?): HttpUrl? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.any { it.isISOControl() || it.isWhitespace() }) return null
        val afterScheme = when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring(8)
            else -> return null
        }
        if (afterScheme.isEmpty() || afterScheme.startsWith('/') || afterScheme.startsWith('\\')) return null
        val parsed = trimmed.toHttpUrlOrNull() ?: return null
        if ((parsed.scheme != "http" && parsed.scheme != "https") || parsed.host.isBlank()) return null
        return if (parsed.username.isEmpty() && parsed.password.isEmpty()) parsed else null
    }

    fun validateHttpUrl(raw: String?): String? = parseSafeHttpUrl(raw)?.toString()

    fun validateForumServer(raw: String?): String? =
        parseSafeHttpUrl(raw)?.takeIf { it.query == null && it.fragment == null }?.toString()?.trimEnd('/')

    fun validateTailUrl(raw: String?): String? =
        raw?.trim().orEmpty().let { if (it.isEmpty()) "" else validateHttpUrl(it) }
}

/**
 * 宁可多删的错误日志脱敏器：复用 `core-parser` B1a [SanitizeSelectors] 共用规则并补充日志态严格脱敏。
 * 统一覆盖查看 / 复制 / 分享通道，剥离 UID、头像分段路径、密码/MD5、Cookie、令牌、JSON/引号/URL 编码键值与 `SavedAccount`。
 */
object ErrorLogSanitizer {
    private const val R = "[REDACTED]"
    private const val KEY =
        """(?:[a-z0-9_-]*uid|author_?id|user_?id|(?:new_?)?buddy_?id|password(?:_?md5)?|passwd|pwd|security_?answer|answer|cookie|cookies|set-cookie|authorization|proxy-authorization|cdb_[a-z0-9_]+|cf_clearance|__cf_bm|cf_chl_[a-z0-9_]*|phpsessid|m_formhash|formhash|hash|sid|auth|auth_?token|access_?token|refresh_?token|session_?id|seccode(?:verify|hash)?|token)"""

    private val SENSITIVE_INPUT_FIELD = Regex("""(?i)^$KEY$""")
    private val INPUT_VALUE_ANY = Regex("""(?i)(\bvalue\s*=\s*)(?:"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|[^\s'">]+)""")
    private val DIRECT_RULES: List<Pair<Regex, String>> = listOf(
        Regex("""(?im)(\b(?:set-cookie|cookie|authorization|proxy-authorization|x-auth-token|x-csrf-token)\s*:\s*)[^\r\n]*""") to "$1$R",
        Regex("""(?i)\bSavedAccount\s*\([^)]*\)""") to "SavedAccount($R)",
        SanitizeSelectors.AVATAR_PATH_REGEX to R,
        Regex("""(?i)(avatar/)(\d{3}/\d{2}/\d{2}/\d{2})""") to "$1$R",
        Regex("""(?i)(\bspace-uid-)(\d+)\b""") to "$1$R",
        Regex("""(?i)("$KEY"\s*:\s*)"(?:[^"\\]|\\.)*"""") to "$1\"$R\"",
        Regex("""(?i)("$KEY"\s*:\s*)(?!["\[{])([^\s,\}\]]+)""") to "$1$R",
        Regex("""(?i)((?:^|[?&;\s"'(\[]|%3[Ff]|%26|%3[Bb]|%20)$KEY\s*%(?:25)?3[Dd]\s*)(?:(?!%26|%23|%20|%3[Bb]|%0[AaDd]|%22|%27)[^&\s;#'"<>),\]\}])+""") to "$1$R",
        SanitizeSelectors.DISCUZ_UID_REGEX to "$1$3$R$3",
        SanitizeSelectors.COOKIE_ASSIGN_REGEX to "$1$2$R$2",
        SanitizeSelectors.COOKIE_OBJECT_PROP_REGEX to "$1$3$R$3",
        SanitizeSelectors.URL_TOKEN_REGEX to "$1$2=$R",
        SanitizeSelectors.JS_FORMHASH_ASSIGN_REGEX to "$1$3$R$3",
        SanitizeSelectors.JS_OTHER_TOKEN_ASSIGN_REGEX to "$1$3$R$3",
        SanitizeSelectors.JS_OBJECT_PROP_REGEX to "$1$4$R$4",
        SanitizeSelectors.UID_QUERY_PARAM_REGEX to "$1$2$R",
        SanitizeSelectors.SCRIPT_UID_ASSIGN_REGEX to "$1$2$R$2",
        SanitizeSelectors.SCRIPT_UID_PROP_REGEX to "$1$3$R$3",
        SanitizeSelectors.SCRIPT_UID_CALL_REGEX to "$1$2$R$2",
        Regex("""(?i)((?<![.\w-])['"]?$KEY['"]?\s*(?:=|:|：)\s*)(?:"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')""") to "$1$R",
        Regex("""(?i)((?<![.\w-])$KEY\s*(?:=|:|：)\s*)([^\s&;,"'\)\]\}<>\[]+)""") to "$1$R",
        SanitizeSelectors.TEXT_UID_LABEL_REGEX to "$1$R",
        Regex("""(?i)(\b[a-z0-9_]*uid\s*(?:[:：=]|\s)\s*)(\d+)(?!\d)""") to "$1$R",
    )

    fun sanitize(raw: String): String {
        if (raw.isEmpty()) return ""
        var out = SanitizeSelectors.EMBEDDED_INPUT_TAG_REGEX.replace(raw) { sanitizeInputTag(it.value) }
        out = SanitizeSelectors.ATTACHMENT_AID_URL_REGEX.replace(out) { "${it.groupValues[1]}${SanitizeSelectors.sanitizeAttachmentAid(it.groupValues[2])}" }
        out = DIRECT_RULES.fold(out) { acc, (regex, replacement) -> regex.replace(acc, replacement) }
        out = SanitizeSelectors.EMAIL_REGEX.replace(out) { if (SanitizeSelectors.isSafeEmail(it.groupValues[1])) it.value else R }
        out = SanitizeSelectors.IPV4_REGEX.replace(out) { if (SanitizeSelectors.isSafeIpv4(it.groupValues[1])) it.value else R }
        return SanitizeSelectors.IPV6_REGEX.replace(out) { if (SanitizeSelectors.isSafeIpv6(it.groupValues[1])) it.value else R }
    }

    private fun sanitizeInputTag(tagHtml: String): String {
        val sensitive = SanitizeSelectors.INPUT_NAME_OR_ID_ATTR_REGEX.findAll(tagHtml).any {
            val n = it.groupValues[2]
            SanitizeSelectors.isSensitiveFieldName(n) || SENSITIVE_INPUT_FIELD.matches(n)
        }
        return if (!sensitive) tagHtml else INPUT_VALUE_ANY.replace(tagHtml, "$1\"$R\"")
    }
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
        val dirs = listOf(File(context.cacheDir, "WebView"), File(context.cacheDir, "okhttp_cache"), File(context.applicationInfo.dataDir, "app_webview/Cache"), File(context.applicationInfo.dataDir, "app_webview/Default/Cache"))
        dirs.sumOf(::directorySizeBytes).also { dirs.forEach(::deleteDirectoryContents); runCatching { WebStorage.getInstance().deleteAllData() } }
    }
}

fun interface SettingsErrorLogStore { suspend fun readSanitizedLogs(): String }

class AndroidSettingsErrorLogStore(private val logsDir: File, private val maxFiles: Int = 5, private val maxChars: Int = 24_000) : SettingsErrorLogStore {
    constructor(context: Context) : this(File(context.filesDir, "logs"))

    override suspend fun readSanitizedLogs(): String = withContext(Dispatchers.IO) {
        val files = logsDir.takeIf { it.isDirectory }?.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.take(maxFiles).orEmpty()
        if (files.isEmpty()) return@withContext ""
        files.joinToString("\n\n") { "=== ${it.name} ===\n${ErrorLogSanitizer.sanitize(runCatching { it.readText() }.getOrDefault(""))}" }.take(maxChars)
    }
}

fun interface SystemNotificationSettingsOpener { fun open(): Boolean }

class AndroidSystemNotificationSettingsOpener(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val launcher: (Intent) -> Unit = { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
) : SystemNotificationSettingsOpener {
    override fun open(): Boolean {
        val primary = if (sdkInt >= Build.VERSION_CODES.O) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName) else null
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        for (intent in listOfNotNull(primary, fallback)) {
            try { launcher(intent); return true } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
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
    is UserError.AuthRequired -> UiErrorMapper.mapAuthRequired(error.reason, listOfNotNull(error.reason.name, error.message.takeIf(String::isNotBlank)).joinToString("\n"))
    is UserError.ChallengeRequired -> UiError(if (error.rejected) R.string.challenge_status_rejected else R.string.challenge_status_incomplete, error.cooldownUntilMs?.let { "cooldownUntilMs=$it" }, UiErrorAction.Reverify)
    is UserError.NetworkError -> UiError(if (error.statusCode != null) R.string.common_error_load_failed else R.string.common_error_network, listOfNotNull(error.statusCode?.let { "HTTP $it" }, error.message.takeIf(String::isNotBlank)).joinToString("\n").ifEmpty { null }, UiErrorAction.Retry)
    is UserError.SiteError -> UiError(R.string.common_error_load_failed, error.message.trim().ifEmpty { null }, UiErrorAction.Retry)
    is UserError.InvalidPage -> UiError(R.string.common_error_load_failed, error.message.trim().ifEmpty { null }, UiErrorAction.Retry)
    is UserError.InvalidInput -> UiError(R.string.common_error_load_failed, error.message.trim().ifEmpty { null }, UiErrorAction.Retry)
}
