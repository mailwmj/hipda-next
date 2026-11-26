package net.jejer.hipda.feature.imageviewer

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.withContext
import net.jejer.hipda.R

/** 看图“保存图片”与“分享”平台动作抽象（可注入与替身验证，不修改 AndroidManifest）。 */
interface ImageViewerMediaActions {
    suspend fun saveImage(url: String, cachedFile: File? = null): Boolean
    suspend fun shareImage(url: String, cachedFile: File? = null): Boolean
}

@Singleton
class AndroidImageViewerMediaActions(
    private val context: Context,
    private val originalDownloader: OriginalImageDownloader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : ImageViewerMediaActions {

    @Inject
    constructor(@ApplicationContext context: Context, originalDownloader: OriginalImageDownloader) :
        this(context, originalDownloader, Dispatchers.IO, Dispatchers.Main)

    override suspend fun saveImage(url: String, cachedFile: File?): Boolean {
        val safeUrl = sanitizeViewerImageUrl(url) ?: return false
        return withContext(ioDispatcher) {
            val resolved = resolveLocalSource(safeUrl, cachedFile) ?: return@withContext false
            val fileName = "HiPDA_${System.currentTimeMillis()}.${extensionForMime(resolved.mimeType)}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStoreQ(resolved.file, fileName, resolved.mimeType)
            } else {
                saveToLegacyPicturesDir(resolved.file, fileName, resolved.mimeType)
            }
        }
    }

    override suspend fun shareImage(url: String, cachedFile: File?): Boolean {
        val safeUrl = sanitizeViewerImageUrl(url) ?: return false
        val prepared = withContext(ioDispatcher) {
            val resolved = resolveLocalSource(safeUrl, cachedFile) ?: return@withContext null
            val shareDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: context.externalCacheDir ?: return@withContext null
            if (!shareDir.exists()) shareDir.mkdirs()
            val dest = File(shareDir, "share_${System.currentTimeMillis()}.${extensionForMime(resolved.mimeType)}")
            runCatching {
                resolved.file.copyTo(dest, overwrite = true)
                FileProvider.getUriForFile(context, "${context.packageName}.provider", dest) to resolved.mimeType
            }.getOrNull()
        } ?: return false

        return withContext(mainDispatcher) {
            runCatching {
                val (uri, mimeType) = prepared
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(sendIntent, context.getString(R.string.image_viewer_action_share))
                if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                true
            }.getOrDefault(false)
        }
    }

    private suspend fun resolveLocalSource(safeUrl: String, cachedFile: File?): OriginalDownloadState.Completed? {
        if (cachedFile != null && cachedFile.isFile && cachedFile.length() > 0L) {
            val header = ByteArray(12)
            val len = runCatching { cachedFile.inputStream().use { it.read(header) }.coerceAtLeast(0) }.getOrDefault(0)
            return OriginalDownloadState.Completed(cachedFile, detectImageMimeType(null, header, len, safeUrl))
        }
        return originalDownloader.download(safeUrl).lastOrNull() as? OriginalDownloadState.Completed
    }

    private fun saveToMediaStoreQ(source: File, fileName: String, mimeType: String): Boolean {
        val resolver = context.contentResolver ?: return false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/HiPDA")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val stream = resolver.openOutputStream(uri) ?: run { resolver.delete(uri, null, null); return false }
            stream.use { out -> source.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (_: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToLegacyPicturesDir(source: File, fileName: String, mimeType: String): Boolean = runCatching {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "HiPDA").apply {
            if (!exists()) mkdirs()
        }
        val target = File(dir, fileName)
        source.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
        true
    }.getOrDefault(false)
}
