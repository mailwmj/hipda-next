package net.jejer.hipda.feature.routing

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val DEFAULT_ATTACHMENT_BASENAME: String = "attachment"
private const val MAX_ATTACHMENT_FILENAME_LENGTH: Int = 120
private const val DEFAULT_BINARY_MIME: String = "application/octet-stream"
private val MULTI_DOT_REGEX = Regex("\\.{2,}")
private val FORBIDDEN_FILENAME_CHARS_REGEX = Regex("""[:*?"<>|]""")
private val DISPOSITION_EXT_FILENAME_REGEX = Regex("""(?i)\bfilename\*\s*=\s*(?:UTF-8|utf-8)?''([^;]+)""")
private val DISPOSITION_QUOTED_FILENAME_REGEX = Regex("""(?i)\bfilename\s*=\s*"([^"]+)"""")
private val DISPOSITION_PLAIN_FILENAME_REGEX = Regex("""(?i)\bfilename\s*=\s*([^;\s]+)""")

/** 仅在 Android 9（API 28）及以下写入公共下载目录需动态申请 `WRITE_EXTERNAL_STORAGE`。 */
fun requiresLegacyDownloadPermission(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt <= Build.VERSION_CODES.P

/**
 * 附件文件名防路径穿越净化：
 * - 剥离 NUL（`\u0000`）、ISO 控制字符、零宽字符与 BiDi 控制符；
 * - 仅保留按 `/`、`\` 切分后的最后一段非穿越组件（拦截 `../../etc/passwd`、`a\b.pdf`）；
 * - 消除任何残留 `..` 与非法文件系统字符，非法或空名回退安全默认名 [fallbackBaseName]。
 */
fun sanitizeAttachmentFileName(
    rawName: String?,
    mimeType: String? = null,
    fallbackBaseName: String = DEFAULT_ATTACHMENT_BASENAME,
): String {
    val cleaned = rawName
        ?.filterNot { ch ->
            ch == '\u0000' ||
                ch.isISOControl() ||
                ch == '\u200B' ||
                ch == '\uFEFF' ||
                ch in '\u202A'..'\u202E' ||
                ch in '\u2066'..'\u2069'
        }
        ?.trim()
        .orEmpty()

    val lastSegment = cleaned
        .split('/', '\\')
        .lastOrNull { seg ->
            val t = seg.trim()
            t.isNotEmpty() && t != "." && t != ".."
        }
        .orEmpty()

    var safe = lastSegment
        .replace(MULTI_DOT_REGEX, ".")
        .replace(FORBIDDEN_FILENAME_CHARS_REGEX, "_")
        .trim()
        .trim('.')
        .trim()

    if (safe.isEmpty() || safe == "." || safe == ".." || safe.all { it == '_' }) {
        safe = fallbackBaseName
    }

    if (safe.length > MAX_ATTACHMENT_FILENAME_LENGTH) {
        val dotIdx = safe.lastIndexOf('.')
        safe = if (dotIdx in 1 until safe.lastIndex && safe.length - dotIdx <= 12) {
            val ext = safe.substring(dotIdx)
            safe.substring(0, MAX_ATTACHMENT_FILENAME_LENGTH - ext.length).trimEnd('.', ' ') + ext
        } else {
            safe.take(MAX_ATTACHMENT_FILENAME_LENGTH).trimEnd('.', ' ')
        }
        if (safe.isEmpty()) safe = fallbackBaseName
    }

    if ('.' !in safe) {
        extensionForAttachmentMime(mimeType)?.let { ext -> safe = "$safe.$ext" }
    }
    return safe
}

/** 从 HTTP `Content-Disposition` 响应头提取原始文件名候选（后续仍必须经 [sanitizeAttachmentFileName] 净化）。 */
internal fun parseContentDispositionFileName(header: String?): String? {
    if (header.isNullOrBlank()) return null
    DISPOSITION_EXT_FILENAME_REGEX.find(header)?.groupValues?.getOrNull(1)?.trim()?.let { encoded ->
        runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    DISPOSITION_QUOTED_FILENAME_REGEX.find(header)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    return DISPOSITION_PLAIN_FILENAME_REGEX.find(header)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}

/** 结合响应 Content-Type 与安全文件名扩展名确定落盘 MIME 类型。 */
internal fun resolveAttachmentMimeType(contentType: String?, safeFileName: String): String {
    val cleanHeader = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        ?.takeIf { it.contains('/') && it != DEFAULT_BINARY_MIME }
    if (cleanHeader != null) return cleanHeader
    val ext = safeFileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (ext.isNotEmpty()) {
        KNOWN_EXTENSION_TO_MIME[ext]?.let { return it }
        runCatching { MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return DEFAULT_BINARY_MIME
}

private fun extensionForAttachmentMime(mimeType: String?): String? {
    val clean = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
    if (clean.isEmpty() || clean == DEFAULT_BINARY_MIME) return null
    return KNOWN_MIME_TO_EXTENSION[clean]
        ?: runCatching { MimeTypeMap.getSingleton().getExtensionFromMimeType(clean) }.getOrNull()?.takeIf { it.isNotBlank() }
}

private val KNOWN_EXTENSION_TO_MIME: Map<String, String> = mapOf(
    "pdf" to "application/pdf",
    "zip" to "application/zip",
    "rar" to "application/vnd.rar",
    "7z" to "application/x-7z-compressed",
    "txt" to "text/plain",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "mp4" to "video/mp4",
    "mp3" to "audio/mpeg",
    "apk" to "application/vnd.android.package-archive",
)

private val KNOWN_MIME_TO_EXTENSION: Map<String, String> = mapOf(
    "application/pdf" to "pdf",
    "application/zip" to "zip",
    "text/plain" to "txt",
    "image/png" to "png",
    "image/jpeg" to "jpg",
    "image/gif" to "gif",
    "image/webp" to "webp",
)

/** 附件落盘成功结果。 */
data class SavedAttachment(
    val fileName: String,
    val mimeType: String,
    val bytesWritten: Long,
    val uri: Uri?,
    val file: File? = null,
)

/** 附件流式保存抽象（API 29+ 写 `MediaStore.Downloads`，API ≤ 28 写兼容下载目录）。 */
interface AttachmentSaver {
    suspend fun save(
        fileName: String,
        mimeType: String,
        writeTo: suspend (OutputStream) -> Long,
    ): SavedAttachment?
}

@Singleton
class AndroidAttachmentMediaStoreSaver(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val hasLegacyStoragePermission: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    },
    private val legacyDownloadsDirProvider: () -> File = {
        @Suppress("DEPRECATION")
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AttachmentSaver {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context = context, sdkInt = Build.VERSION.SDK_INT)

    override suspend fun save(
        fileName: String,
        mimeType: String,
        writeTo: suspend (OutputStream) -> Long,
    ): SavedAttachment? = withContext(ioDispatcher) {
        val safeName = sanitizeAttachmentFileName(fileName, mimeType)
        val effectiveMime = resolveAttachmentMimeType(mimeType, safeName)
        if (sdkInt >= Build.VERSION_CODES.Q) {
            saveToMediaStoreQ(safeName, effectiveMime, writeTo)
        } else {
            saveToLegacyDownloadsDir(safeName, effectiveMime, writeTo)
        }
    }

    @SuppressLint("NewApi")
    private suspend fun saveToMediaStoreQ(
        safeName: String,
        mimeType: String,
        writeTo: suspend (OutputStream) -> Long,
    ): SavedAttachment? {
        val resolver = context.contentResolver ?: return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
        return try {
            val stream = resolver.openOutputStream(uri) ?: run {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }
            val written = stream.use { out -> writeTo(out) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            SavedAttachment(fileName = safeName, mimeType = mimeType, bytesWritten = written, uri = uri)
        } catch (cancelled: CancellationException) {
            runCatching { resolver.delete(uri, null, null) }
            throw cancelled
        } catch (_: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private suspend fun saveToLegacyDownloadsDir(
        safeName: String,
        mimeType: String,
        writeTo: suspend (OutputStream) -> Long,
    ): SavedAttachment? {
        if (!hasLegacyStoragePermission()) return null
        val dir = legacyDownloadsDirProvider()
        if (!dir.exists() && !dir.mkdirs()) return null
        val canonicalDir = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val target = resolveUniqueTargetFile(dir, safeName)
        val canonicalTarget = runCatching { target.canonicalFile }.getOrNull() ?: return null
        if (canonicalTarget.parentFile != canonicalDir) return null

        val partFile = File(dir, ".${target.name}.${System.nanoTime()}.part")
        return try {
            val written = FileOutputStream(partFile).use { out -> writeTo(out) }
            if (!partFile.renameTo(target)) {
                partFile.copyTo(target, overwrite = true)
                partFile.delete()
            }
            runCatching { MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null) }
            SavedAttachment(fileName = target.name, mimeType = mimeType, bytesWritten = written, uri = Uri.fromFile(target), file = target)
        } catch (cancelled: CancellationException) {
            partFile.delete()
            throw cancelled
        } catch (_: Throwable) {
            partFile.delete()
            null
        }
    }

    private fun resolveUniqueTargetFile(dir: File, safeName: String): File {
        var candidate = File(dir, safeName)
        if (!candidate.exists()) return candidate
        val dotIdx = safeName.lastIndexOf('.')
        val stem = if (dotIdx > 0) safeName.substring(0, dotIdx) else safeName
        val ext = if (dotIdx > 0) safeName.substring(dotIdx) else ""
        var index = 1
        while (candidate.exists() && index < 10_000) {
            candidate = File(dir, "$stem ($index)$ext")
            index++
        }
        return candidate
    }
}
