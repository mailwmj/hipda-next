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
    constructor(okHttpClient: OkHttpClient, @ApplicationContext context: Context) : this(
        callFactory = okHttpClient,
        cacheDir = File(context.cacheDir, CACHE_DIR_NAME),
        ioDispatcher = Dispatchers.IO,
    )

    override fun download(url: String): Flow<OriginalDownloadState> = flow {
        val safeUrl = sanitizeViewerImageUrl(url)
        if (safeUrl == null) {
            emit(OriginalDownloadState.Failed)
            return@flow
        }
        val call = callFactory.newCall(Request.Builder().url(safeUrl).get().build())
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        var tempFile: File? = null
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    emit(OriginalDownloadState.Failed)
                    return@flow
                }
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
                            val progress = totalBytes?.let { (bytesRead.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
                            emit(OriginalDownloadState.Downloading(bytesRead, totalBytes, progress))
                        }
                    }
                }

                currentCoroutineContext().ensureActive()
                if (totalBytes != null && bytesRead < totalBytes) {
                    partFile.delete()
                    emit(OriginalDownloadState.Failed)
                    return@flow
                }
                val mime = detectImageMimeType(body.contentType()?.let { "${it.type}/${it.subtype}" }, header, headerRead, safeUrl)
                val targetFile = File(cacheDir, "${sha256Prefix(safeUrl)}.${extensionForMime(mime)}")
                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
                tempFile = null
                emit(OriginalDownloadState.Completed(file = targetFile, mimeType = mime))
            }
        } catch (cancelled: CancellationException) {
            call.cancel()
            tempFile?.delete()
            throw cancelled
        } catch (_: Throwable) {
            tempFile?.delete()
            if (currentCoroutineContext()[Job]?.isActive == false) {
                throw CancellationException("Original image download cancelled")
            }
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
    MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        .take(12).joinToString("") { "%02x".format(it) }

internal fun detectImageMimeType(contentType: String?, header: ByteArray, len: Int, url: String): String {
    val norm = contentType?.substringBefore(';')?.trim()?.lowercase()
    if (norm != null && norm.startsWith("image/")) return norm
    return when {
        len >= 6 && header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() &&
            header[3] == '8'.code.toByte() && (header[4] == '7'.code.toByte() || header[4] == '9'.code.toByte()) &&
            header[5] == 'a'.code.toByte() -> "image/gif"
        len >= 4 && header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte() &&
            header[2] == 'N'.code.toByte() && header[3] == 'G'.code.toByte() -> "image/png"
        len >= 12 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte() -> "image/webp"
        len >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> "image/jpeg"
        isGifUrl(url) -> "image/gif"
        url.substringBefore('?').endsWith(".png", ignoreCase = true) -> "image/png"
        url.substringBefore('?').endsWith(".webp", ignoreCase = true) -> "image/webp"
        else -> "image/jpeg"
    }
}

internal fun extensionForMime(mimeType: String): String = when (mimeType.lowercase()) {
    "image/gif" -> "gif"
    "image/png" -> "png"
    "image/webp" -> "webp"
    else -> "jpg"
}
