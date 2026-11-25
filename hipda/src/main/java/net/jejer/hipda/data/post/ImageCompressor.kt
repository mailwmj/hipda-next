package net.jejer.hipda.data.post

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Prepared image ready for multipart upload. Call [cleanup] / [close] in a `finally` block so
 * temporary files created during copying or compression are always deleted.
 */
data class PreparedUploadImage(
    val file: File,
    val mimeType: String,
    val byteSize: Long,
    val wasCompressed: Boolean,
    val isTempFile: Boolean,
) : Closeable {
    fun cleanup() {
        if (isTempFile && file.exists()) {
            file.delete()
        }
    }

    override fun close() = cleanup()
}

sealed interface ImageCompressionResult {
    data class Success(val image: PreparedUploadImage) : ImageCompressionResult
    data class Failure(val error: ImageUploadError) : ImageCompressionResult
}

/**
 * Pure Android image compressor using [BitmapFactory], [Matrix], and [ExifInterface].
 * Zero third-party image compression library dependency.
 */
@Singleton
class ImageCompressor internal constructor(
    private val context: Context,
    private val workDirProvider: () -> File,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        context = context,
        workDirProvider = { File(context.cacheDir, UPLOAD_TEMP_DIR_NAME) },
        ioDispatcher = Dispatchers.IO,
    )

    private data class SourceHandle(
        val file: File,
        val isTempFile: Boolean,
    )

    private data class ImageMetadata(
        val width: Int,
        val height: Int,
        val mimeType: String,
        val fileSize: Long,
        val isGif: Boolean,
    )

    suspend fun prepare(
        input: ImageUploadInput,
        maxUploadBytes: Int = DEFAULT_MAX_UPLOAD_BYTES,
    ): ImageCompressionResult {
        if (maxUploadBytes <= 0) {
            return ImageCompressionResult.Failure(
                ImageUploadError.InvalidInput("上传文件大小限制无效"),
            )
        }

        return withContext(ioDispatcher) {
            currentCoroutineContext().ensureActive()

            val workDir = workDirProvider().also { dir ->
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }

            var sourceHandle: SourceHandle? = null
            var handoffToCaller = false
            try {
                sourceHandle = materializeSource(input, workDir)
                    ?: return@withContext ImageCompressionResult.Failure(
                        ImageUploadError.DecodeFailed("无法读取选择的图片文件"),
                    )

                currentCoroutineContext().ensureActive()

                val metadata = inspectMetadata(sourceHandle.file)
                    ?: return@withContext ImageCompressionResult.Failure(
                        ImageUploadError.DecodeFailed("无法解析图片信息"),
                    )

                // 1) GIF handling: pass compliant GIFs untouched, reject oversized GIFs without static conversion
                if (metadata.isGif) {
                    return@withContext if (metadata.fileSize > maxUploadBytes) {
                        ImageCompressionResult.Failure(
                            ImageUploadError.GifTooLarge(
                                actualBytes = metadata.fileSize,
                                maxUploadBytes = maxUploadBytes,
                            ),
                        )
                    } else {
                        handoffToCaller = true
                        ImageCompressionResult.Success(
                            PreparedUploadImage(
                                file = sourceHandle.file,
                                mimeType = MIME_GIF,
                                byteSize = metadata.fileSize,
                                wasCompressed = false,
                                isTempFile = sourceHandle.isTempFile,
                            ),
                        )
                    }
                }

                // 2) EXIF orientation inspection
                val exifOrientation = readExifOrientation(sourceHandle.file)
                val needsExifTransform = requiresExifTransform(exifOrientation)

                // 3) Direct upload check (aligned with UploadImgHelper.java:250-259)
                val minSide = minOf(metadata.width, metadata.height)
                val maxSide = maxOf(metadata.width, metadata.height)
                val isLongImage = minSide > 0 &&
                    (maxSide.toDouble() / minSide.toDouble()) >= LONG_IMAGE_ASPECT_RATIO

                val canDirectUpload = (input.original || isLongImage) &&
                    !needsExifTransform &&
                    isMimeSupported(metadata.mimeType) &&
                    metadata.fileSize <= maxUploadBytes

                if (canDirectUpload) {
                    handoffToCaller = true
                    return@withContext ImageCompressionResult.Success(
                        PreparedUploadImage(
                            file = sourceHandle.file,
                            mimeType = metadata.mimeType,
                            byteSize = metadata.fileSize,
                            wasCompressed = false,
                            isTempFile = sourceHandle.isTempFile,
                        ),
                    )
                }

                // 4) Downsample + EXIF Matrix correction + progressive resize/quality JPEG compression
                val compressedFile = compressToJpegWithinBudget(
                    sourceFile = sourceHandle.file,
                    metadata = metadata,
                    exifOrientation = exifOrientation,
                    maxUploadBytes = maxUploadBytes,
                    workDir = workDir,
                )

                if (compressedFile == null) {
                    return@withContext ImageCompressionResult.Failure(
                        ImageUploadError.UnableToCompress(
                            originalBytes = metadata.fileSize,
                            maxUploadBytes = maxUploadBytes,
                        ),
                    )
                }

                ImageCompressionResult.Success(
                    PreparedUploadImage(
                        file = compressedFile,
                        mimeType = MIME_JPEG,
                        byteSize = compressedFile.length(),
                        wasCompressed = true,
                        isTempFile = true,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImageCompressionResult.Failure(
                    ImageUploadError.DecodeFailed(
                        message = "处理图片失败",
                        cause = e,
                    ),
                )
            } finally {
                if (!handoffToCaller && sourceHandle?.isTempFile == true) {
                    sourceHandle.file.delete()
                }
            }
        }
    }

    private suspend fun materializeSource(
        input: ImageUploadInput,
        workDir: File,
    ): SourceHandle? = when (input) {
        is ImageUploadInput.LocalFile -> {
            val file = input.file
            if (file.exists() && file.isFile && file.canRead() && file.length() > 0L) {
                SourceHandle(file = file, isTempFile = false)
            } else {
                null
            }
        }

        is ImageUploadInput.Bytes -> {
            if (input.bytes.isEmpty()) return null
            val tempFile = createTempFile(workDir, "src")
            try {
                FileOutputStream(tempFile).use { out ->
                    out.write(input.bytes)
                }
                SourceHandle(file = tempFile, isTempFile = true)
            } catch (t: Throwable) {
                tempFile.delete()
                throw t
            }
        }

        is ImageUploadInput.ContentUri -> {
            val tempFile = createTempFile(workDir, "uri")
            try {
                val stream = openUriInputStream(input.uri) ?: return null
                stream.use { inputStream ->
                    FileOutputStream(tempFile).use { out ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = inputStream.read(buffer)
                            if (read < 0) break
                            if (read > 0) out.write(buffer, 0, read)
                        }
                    }
                }
                if (tempFile.length() <= 0L) {
                    tempFile.delete()
                    null
                } else {
                    SourceHandle(file = tempFile, isTempFile = true)
                }
            } catch (t: Throwable) {
                tempFile.delete()
                throw t
            }
        }
    }

    private fun openUriInputStream(uri: Uri): InputStream? {
        runCatching { context.contentResolver.openInputStream(uri) }
            .getOrNull()
            ?.let { return it }
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            val path = uri.path ?: return null
            val file = File(path)
            if (file.exists() && file.isFile && file.canRead()) {
                return FileInputStream(file)
            }
        }
        return null
    }

    private fun inspectMetadata(file: File): ImageMetadata? {
        val fileSize = file.length()
        if (fileSize <= 0L) return null

        val gifDimensions = readGifDimensionsIfGif(file)
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)

        if (gifDimensions != null) {
            val width = if (options.outWidth > 0) options.outWidth else gifDimensions.first
            val height = if (options.outHeight > 0) options.outHeight else gifDimensions.second
            if (width <= 0 || height <= 0) return null
            return ImageMetadata(
                width = width,
                height = height,
                mimeType = MIME_GIF,
                fileSize = fileSize,
                isGif = true,
            )
        }

        val width = options.outWidth
        val height = options.outHeight
        val mime = options.outMimeType?.trim()?.lowercase(Locale.ROOT)
            ?.ifEmpty { null }
            ?: sniffMimeFromHeader(file)
            ?: ""
        if (width <= 0 || height <= 0 || mime.isEmpty()) {
            return null
        }
        return ImageMetadata(
            width = width,
            height = height,
            mimeType = mime,
            fileSize = fileSize,
            isGif = mime == MIME_GIF || mime.contains("gif"),
        )
    }

    private suspend fun compressToJpegWithinBudget(
        sourceFile: File,
        metadata: ImageMetadata,
        exifOrientation: Int,
        maxUploadBytes: Int,
        workDir: File,
    ): File? {
        val initialMaxDim = minOf(maxOf(metadata.width, metadata.height), MAX_DIMENSION)
        val decoded = decodeSampledBitmap(
            file = sourceFile,
            rawWidth = metadata.width,
            rawHeight = metadata.height,
            targetMaxDimension = initialMaxDim,
        ) ?: return null

        var orientedBitmap: Bitmap? = null
        var outputFile: File? = null
        var succeeded = false
        try {
            currentCoroutineContext().ensureActive()
            orientedBitmap = applyExifOrientation(decoded, exifOrientation)
            if (orientedBitmap !== decoded && !decoded.isRecycled) {
                decoded.recycle()
            }

            val baseWidth = orientedBitmap.width
            val baseHeight = orientedBitmap.height
            val baseMax = minOf(maxOf(baseWidth, baseHeight), MAX_DIMENSION)
            val candidateMaxDims = buildCandidateDimensions(baseMax)

            outputFile = createTempFile(workDir, "cmp", ".jpg")

            for (targetDim in candidateMaxDims) {
                currentCoroutineContext().ensureActive()
                val scaledBitmap = scaleBitmapToMaxDimension(orientedBitmap, targetDim)
                try {
                    for (quality in JPEG_QUALITY_STEPS) {
                        currentCoroutineContext().ensureActive()
                        FileOutputStream(outputFile).use { out ->
                            val ok = scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                            if (!ok) return null
                        }
                        val written = outputFile.length()
                        if (written in 1L..maxUploadBytes.toLong()) {
                            succeeded = true
                            return outputFile
                        }
                    }
                } finally {
                    if (scaledBitmap !== orientedBitmap && !scaledBitmap.isRecycled) {
                        scaledBitmap.recycle()
                    }
                }
            }
            return null
        } finally {
            if (!decoded.isRecycled) {
                decoded.recycle()
            }
            if (orientedBitmap != null && !orientedBitmap.isRecycled) {
                orientedBitmap.recycle()
            }
            if (!succeeded) {
                outputFile?.delete()
            }
        }
    }

    private fun decodeSampledBitmap(
        file: File,
        rawWidth: Int,
        rawHeight: Int,
        targetMaxDimension: Int,
    ): Bitmap? {
        var inSampleSize = calculateInSampleSize(rawWidth, rawHeight, targetMaxDimension)
        repeat(MAX_DECODE_ATTEMPTS) {
            val opts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            try {
                return BitmapFactory.decodeFile(file.absolutePath, opts)
            } catch (_: OutOfMemoryError) {
                inSampleSize *= 2
            }
        }
        return null
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleBitmapToMaxDimension(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val currentMax = maxOf(width, height)
        if (currentMax <= maxDimension) return bitmap

        val ratio = maxDimension.toDouble() / currentMax.toDouble()
        val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
        if (targetWidth == width && targetHeight == height) return bitmap
        return bitmap.scale(targetWidth, targetHeight)
    }

    private fun buildCandidateDimensions(baseMax: Int): List<Int> {
        val dims = LinkedHashSet<Int>()
        for (factor in DIMENSION_SCALE_STEPS) {
            val dim = (baseMax * factor).toInt().coerceAtLeast(MIN_DIMENSION_PX)
            dims.add(minOf(dim, baseMax))
        }
        dims.add(minOf(MIN_DIMENSION_PX, baseMax).coerceAtLeast(1))
        return dims.toList()
    }

    private fun readExifOrientation(file: File): Int = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun requiresExifTransform(orientation: Int): Boolean =
        orientation != ExifInterface.ORIENTATION_NORMAL &&
            orientation != ExifInterface.ORIENTATION_UNDEFINED

    private fun readGifDimensionsIfGif(file: File): Pair<Int, Int>? {
        val header = ByteArray(10)
        val read = runCatching {
            FileInputStream(file).use { input -> input.read(header) }
        }.getOrDefault(-1)
        if (read < 10) return null
        val magic = String(header, 0, 6, Charsets.US_ASCII)
        if (magic != "GIF87a" && magic != "GIF89a") return null
        val width = (header[6].toInt() and 0xFF) or ((header[7].toInt() and 0xFF) shl 8)
        val height = (header[8].toInt() and 0xFF) or ((header[9].toInt() and 0xFF) shl 8)
        return width to height
    }

    private fun sniffMimeFromHeader(file: File): String? {
        val header = ByteArray(12)
        val read = runCatching {
            FileInputStream(file).use { input -> input.read(header) }
        }.getOrDefault(-1)
        if (read < 3) return null
        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF
        val b2 = header[2].toInt() and 0xFF
        return when {
            b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF -> MIME_JPEG
            read >= 4 && b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && (header[3].toInt() and 0xFF) == 0x47 -> "image/png"
            b0 == 0x42 && b1 == 0x4D -> "image/bmp"
            read >= 6 && String(header, 0, 6, Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" } -> MIME_GIF
            read >= 12 &&
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }
    }

    private fun createTempFile(workDir: File, prefix: String, suffix: String = ".tmp"): File =
        File(workDir, "${prefix}_${UUID.randomUUID()}$suffix")

    companion object {
        const val DEFAULT_MAX_UPLOAD_BYTES: Int = 2 * 1024 * 1024
        const val MAX_DIMENSION: Int = 2560
        const val LONG_IMAGE_ASPECT_RATIO: Double = 3.0
        const val UPLOAD_TEMP_DIR_NAME: String = "post_image_upload"

        private const val MIME_JPEG = "image/jpeg"
        private const val MIME_GIF = "image/gif"
        private const val COPY_BUFFER_BYTES = 8 * 1024
        private const val MAX_DECODE_ATTEMPTS = 3
        private const val MIN_DIMENSION_PX = 64

        private val DIMENSION_SCALE_STEPS = doubleArrayOf(1.0, 0.85, 0.70, 0.55, 0.40, 0.28, 0.18, 0.12)
        private val JPEG_QUALITY_STEPS = intArrayOf(80, 70, 60, 50, 40)

        internal fun calculateInSampleSize(width: Int, height: Int, reqMaxDimension: Int): Int {
            var inSampleSize = 1
            var maxSide = maxOf(width, height)
            val safeTarget = reqMaxDimension.coerceAtLeast(1)
            while (maxSide / 2 >= safeTarget) {
                inSampleSize *= 2
                maxSide /= 2
            }
            return inSampleSize
        }

        internal fun isMimeSupported(mimeType: String?): Boolean {
            if (mimeType.isNullOrBlank()) return false
            val lower = mimeType.lowercase(Locale.ROOT)
            return lower.contains("jpg") ||
                lower.contains("jpeg") ||
                lower.contains("png") ||
                lower.contains("gif") ||
                lower.contains("bmp")
        }

        internal fun fileSuffixForMime(mimeType: String?): String {
            val lower = mimeType?.lowercase(Locale.ROOT).orEmpty()
            return when {
                lower.contains("gif") -> "gif"
                lower.contains("png") -> "png"
                lower.contains("bmp") -> "bmp"
                else -> "jpg"
            }
        }
    }
}
