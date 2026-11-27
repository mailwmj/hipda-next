package net.jejer.hipda.feature.imageviewer

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.disk.DiskCache
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

/** 仅在 Android 9（API 28）及以下写入公共相册需动态申请 `WRITE_EXTERNAL_STORAGE`（maxSdkVersion=28）。 */
internal fun requiresLegacyStoragePermission(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt <= Build.VERSION_CODES.P

/** 构造带 `ClipData` 与 `FLAG_GRANT_READ_URI_PERMISSION` 授权的系统分享 Chooser Intent（R-E14 S2）。 */
internal fun buildShareChooserIntent(context: Context, uri: Uri, mimeType: String): Intent {
    val clip = ClipData.newRawUri("", uri)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(sendIntent, context.getString(R.string.image_viewer_action_share)).apply {
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Singleton
class AndroidImageViewerMediaActions(
    private val context: Context,
    private val originalDownloader: OriginalImageDownloader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val uriProvider: (Context, File) -> Uri = { ctx, file ->
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
    },
    private val diskCacheProvider: () -> DiskCache? = { null },
) : ImageViewerMediaActions {

    @Inject
    constructor(@ApplicationContext context: Context, originalDownloader: OriginalImageDownloader, imageLoader: ImageLoader) :
        this(context, originalDownloader, Dispatchers.IO, Dispatchers.Main, diskCacheProvider = { imageLoader.diskCache })

    override suspend fun saveImage(url: String, cachedFile: File?): Boolean {
        val safeUrl = sanitizeViewerImageUrl(url) ?: return false
        return withContext(ioDispatcher) {
            withLocalSource(safeUrl, cachedFile) { file, mimeType ->
                val fileName = "HiPDA_${System.currentTimeMillis()}.${extensionForMime(mimeType)}"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveToMediaStoreQ(file, fileName, mimeType)
                else saveToLegacyPicturesDir(file, fileName, mimeType)
            } ?: false
        }
    }

    override suspend fun shareImage(url: String, cachedFile: File?): Boolean {
        val safeUrl = sanitizeViewerImageUrl(url) ?: return false
        val prepared = withContext(ioDispatcher) {
            withLocalSource(safeUrl, cachedFile) { file, mimeType ->
                // 优先使用 externalCacheDir（受 provider_paths external_storage_root 覆盖）并清理过期分享缓存
                val shareDir = context.externalCacheDir ?: context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return@withLocalSource null
                if (!shareDir.exists()) shareDir.mkdirs()
                shareDir.listFiles { _, name -> name.startsWith(SHARE_FILE_PREFIX) }?.forEach { it.delete() }
                val dest = File(shareDir, "${SHARE_FILE_PREFIX}${System.currentTimeMillis()}.${extensionForMime(mimeType)}")
                runCatching {
                    file.copyTo(dest, overwrite = true)
                    uriProvider(context, dest) to mimeType
                }.getOrNull()
            }
        } ?: return false
        return withContext(mainDispatcher) {
            runCatching { context.startActivity(buildShareChooserIntent(context, prepared.first, prepared.second)); true }.getOrDefault(false)
        }
    }

    private suspend fun <T> withLocalSource(safeUrl: String, cachedFile: File?, block: (File, String) -> T?): T? {
        if (cachedFile != null && cachedFile.isFile && cachedFile.length() > 0L) {
            return block(cachedFile, detectLocalFileMimeType(cachedFile, safeUrl))
        }
        runCatching { diskCacheProvider()?.openSnapshot(safeUrl) }.getOrNull()?.use { snap ->
            val file = snap.data.toFile()
            if (file.isFile && file.length() > 0L) return block(file, detectLocalFileMimeType(file, safeUrl))
        }
        val downloaded = originalDownloader.download(safeUrl).lastOrNull() as? OriginalDownloadState.Completed ?: return null
        return block(downloaded.file, downloaded.mimeType)
    }

    private fun detectLocalFileMimeType(file: File, safeUrl: String): String {
        val header = ByteArray(12)
        val len = runCatching { file.inputStream().use { it.read(header) }.coerceAtLeast(0) }.getOrDefault(0)
        return detectImageMimeType(null, header, len, safeUrl)
    }

    private fun saveToMediaStoreQ(source: File, fileName: String, mimeType: String): Boolean {
        val resolver = context.contentResolver ?: return false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/HiPDA")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return false
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
    private fun saveToLegacyPicturesDir(source: File, fileName: String, mimeType: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return runCatching {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "HiPDA").apply { if (!exists()) mkdirs() }
            val target = File(dir, fileName)
            source.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val SHARE_FILE_PREFIX = "share_"
    }
}
