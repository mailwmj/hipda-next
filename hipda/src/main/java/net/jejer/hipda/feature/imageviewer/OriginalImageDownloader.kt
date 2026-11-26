package net.jejer.hipda.feature.imageviewer

import android.content.Context
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** 原图字节级下载状态（收口 R-E2b S4）。 */
@Immutable
sealed interface OriginalDownloadState {
    data object Idle : OriginalDownloadState
    data class Downloading(val bytesRead: Long, val totalBytes: Long?, val progress: Float?) : OriginalDownloadState
    data class Completed(val file: File, val mimeType: String) : OriginalDownloadState
    data object Failed : OriginalDownloadState
}

/** 可注入/可测试的原图下载器抽象，流式上报字节级确定进度并支持取消同步中止底层 HTTP Call。 */
interface OriginalImageDownloader {
    fun download(url: String): Flow<OriginalDownloadState>
}

/** 基于项目 OkHttp [Call.Factory] 的默认原图下载器（不改 `ImageLoaderModule` 或 `okhttp/`）。 */
@Singleton
class OkHttpOriginalImageDownloader(
    private val callFactory: Call.Factory,
    private val cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OriginalImageDownloader {

    @Inject
    constructor(okHttpClient: OkHttpClient, @ApplicationContext context: Context) :
        this(okHttpClient, File(context.cacheDir, CACHE_DIR_NAME), Dispatchers.IO)

    override fun download(url: String): Flow<OriginalDownloadState> = flow {
        val safeUrl = sanitizeViewerImageUrl(url) ?: run { emit(OriginalDownloadState.Failed); return@flow }
        val call = callFactory.newCall(Request.Builder().url(safeUrl).get().build())
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        var tempFile: File? = null
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) { emit(OriginalDownloadState.Failed); return@flow }
                val body = response.body
                val totalBytes = body.contentLength().takeIf { it > 0L }
                emit(OriginalDownloadState.Downloading(0L, totalBytes, if (totalBytes != null) 0f else null))

                if (!cacheDir.exists()) cacheDir.mkdirs()
                val partFile = File.createTempFile("orig_", ".part", cacheDir).also { tempFile = it }
                val header = ByteArray(MAGIC_HEADER_SIZE)
                var headerRead = 0
                var bytesRead = 0L

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(STREAM_BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            if (headerRead < header.size) {
                                val copyLen = minOf(read, header.size - headerRead)
                                System.arraycopy(buffer, 0, header, headerRead, copyLen)
                                headerRead += copyLen
                            }
                            bytesRead += read
                            emit(OriginalDownloadState.Downloading(bytesRead, totalBytes, totalBytes?.let { (bytesRead.toFloat() / it).coerceIn(0f, 1f) }))
                        }
                    }
                }

                currentCoroutineContext().ensureActive()
                if (totalBytes != null && bytesRead < totalBytes) {
                    partFile.delete(); emit(OriginalDownloadState.Failed); return@flow
                }
                val mime = detectImageMimeType(body.contentType()?.let { "${it.type}/${it.subtype}" }, header, headerRead, safeUrl)
                val targetFile = File(cacheDir, "${sha256Prefix(safeUrl)}.${extensionForMime(mime)}")
                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) { partFile.copyTo(targetFile, overwrite = true); partFile.delete() }
                tempFile = null
                emit(OriginalDownloadState.Completed(targetFile, mime))
            }
        } catch (cancelled: CancellationException) {
            call.cancel(); tempFile?.delete(); throw cancelled
        } catch (_: Throwable) {
            tempFile?.delete()
            if (currentCoroutineContext()[Job]?.isActive == false) throw CancellationException("Original image download cancelled")
            emit(OriginalDownloadState.Failed)
        } finally {
            cancelHandle?.dispose()
        }
    }.flowOn(ioDispatcher)

    companion object {
        private const val CACHE_DIR_NAME = "image_viewer_originals"
        private const val STREAM_BUFFER_BYTES = 4096
        private const val MAGIC_HEADER_SIZE = 12
    }
}

private fun sha256Prefix(input: String): String =
    MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }

private val MAGIC_PREFIXES = listOf(
    "GIF87a".encodeToByteArray() to "image/gif",
    "GIF89a".encodeToByteArray() to "image/gif",
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) to "image/png",
    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) to "image/jpeg",
)
private val RIFF_BYTES = "RIFF".encodeToByteArray()
private val WEBP_BYTES = "WEBP".encodeToByteArray()

private fun ByteArray.matchesAt(len: Int, offset: Int, sig: ByteArray): Boolean =
    len >= offset + sig.size && sig.indices.all { this[offset + it] == sig[it] }

internal fun detectImageMimeType(contentType: String?, header: ByteArray, len: Int, url: String): String {
    contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.startsWith("image/") }?.let { return it }
    MAGIC_PREFIXES.firstOrNull { (sig, _) -> header.matchesAt(len, 0, sig) }?.let { return it.second }
    if (header.matchesAt(len, 0, RIFF_BYTES) && header.matchesAt(len, 8, WEBP_BYTES)) return "image/webp"
    val cleanPath = url.substringBefore('?')
    return when {
        isGifUrl(url) -> "image/gif"
        cleanPath.endsWith(".png", true) -> "image/png"
        cleanPath.endsWith(".webp", true) -> "image/webp"
        else -> "image/jpeg"
    }
}

internal fun extensionForMime(mimeType: String): String = when (mimeType.lowercase()) {
    "image/gif" -> "gif"
    "image/png" -> "png"
    "image/webp" -> "webp"
    else -> "jpg"
}
