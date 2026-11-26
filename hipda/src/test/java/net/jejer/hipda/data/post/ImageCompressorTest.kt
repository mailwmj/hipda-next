package net.jejer.hipda.data.post

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageCompressorTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var workDir: File
    private lateinit var compressor: ImageCompressor

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        workDir = tempFolder.newFolder("compressor_work")
        compressor = ImageCompressor(
            context = context,
            workDirProvider = { workDir },
            ioDispatcher = Dispatchers.IO,
        )
    }

    @Test
    fun prepare_largeImageCompressesBelowMaxUploadBytesAndCapsDimensionAt2560() = runBlocking {
        val largePng = TestImageFactory.writeNoisyPng(
            file = File(tempFolder.root, "large_noisy.png"),
            width = 1600,
            height = 1200,
        )
        val maxBytes = 40 * 1024
        assertTrue("Source image must exceed budget: ${largePng.length()}", largePng.length() > maxBytes * 3)

        val result = compressor.prepare(
            input = ImageUploadInput.LocalFile(largePng, original = true),
            maxUploadBytes = maxBytes,
        )
        assertTrue("Expected Success but got $result", result is ImageCompressionResult.Success)
        val prepared = (result as ImageCompressionResult.Success).image
        try {
            assertTrue(prepared.wasCompressed)
            assertTrue(prepared.isTempFile)
            assertEquals("image/jpeg", prepared.mimeType)
            assertTrue("Compressed size ${prepared.byteSize} should be <= $maxBytes", prepared.byteSize <= maxBytes)
            assertTrue(prepared.byteSize > 0)
            assertEquals(prepared.file.length(), prepared.byteSize)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(prepared.file.absolutePath, bounds)
            assertTrue(bounds.outWidth in 1..ImageCompressor.MAX_DIMENSION)
            assertTrue(bounds.outHeight in 1..ImageCompressor.MAX_DIMENSION)
        } finally {
            prepared.cleanup()
        }
        assertEquals(0, workDir.listFiles()?.size ?: 0)
    }

    @Test
    fun prepare_smallOriginalImageAndLongImagePassThroughWithoutCompression() = runBlocking {
        val smallJpg = TestImageFactory.writeSolidJpeg(
            file = File(tempFolder.root, "small.jpg"),
            width = 120,
            height = 100,
        )
        val smallRes = compressor.prepare(
            input = ImageUploadInput.LocalFile(smallJpg, original = true),
            maxUploadBytes = 512 * 1024,
        )
        assertTrue(smallRes is ImageCompressionResult.Success)
        val smallPrepared = (smallRes as ImageCompressionResult.Success).image
        assertFalse(smallPrepared.wasCompressed)
        assertFalse(smallPrepared.isTempFile)
        assertEquals(smallJpg.absolutePath, smallPrepared.file.absolutePath)
        assertArrayEquals(smallJpg.readBytes(), smallPrepared.file.readBytes())
        // cleanup must not delete caller's original LocalFile
        smallPrepared.cleanup()
        assertTrue(smallJpg.exists())

        // Long image (aspect ratio 3.5 >= 3) with original=false must also pass through when within budget & unrotated
        val longPng = TestImageFactory.writeSolidPng(
            file = File(tempFolder.root, "long.png"),
            width = 100,
            height = 350,
        )
        val longRes = compressor.prepare(
            input = ImageUploadInput.LocalFile(longPng, original = false),
            maxUploadBytes = 512 * 1024,
        )
        assertTrue(longRes is ImageCompressionResult.Success)
        val longPrepared = (longRes as ImageCompressionResult.Success).image
        assertFalse(longPrepared.wasCompressed)
        assertEquals("image/png", longPrepared.mimeType)
        longPrepared.cleanup()
        assertTrue(longPng.exists())
    }

    @Test
    fun prepare_exifRotatedImageAppliesMatrixRotationAndDoesNotDirectUpload() = runBlocking {
        val rotatedJpg = TestImageFactory.writeSolidJpeg(
            file = File(tempFolder.root, "rotated.jpg"),
            width = 300,
            height = 100,
        )
        val exif = ExifInterface(rotatedJpg.absolutePath)
        exif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_ROTATE_90.toString(),
        )
        exif.saveAttributes()

        val result = compressor.prepare(
            input = ImageUploadInput.LocalFile(rotatedJpg, original = true),
            maxUploadBytes = 512 * 1024,
        )
        assertTrue("Expected Success but got $result", result is ImageCompressionResult.Success)
        val prepared = (result as ImageCompressionResult.Success).image
        try {
            assertTrue("Rotated image must be re-encoded after Matrix correction", prepared.wasCompressed)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(prepared.file.absolutePath, bounds)
            assertTrue(
                "Expected portrait dimensions after 90° rotation, got ${bounds.outWidth}x${bounds.outHeight}",
                bounds.outHeight > bounds.outWidth,
            )
        } finally {
            prepared.cleanup()
        }
        assertEquals(0, workDir.listFiles()?.size ?: 0)
    }

    @Test
    fun prepare_compliantGifPassesThroughAndOversizedGifIsRejectedWithoutStaticConversion() = runBlocking {
        val smallGifBytes = TestImageFactory.createValidGifBytes(width = 8, height = 8, paddingBytes = 64)
        val smallGifResult = compressor.prepare(
            input = ImageUploadInput.Bytes(smallGifBytes, mimeType = "image/gif"),
            maxUploadBytes = 1024,
        )
        assertTrue("Expected Success but got $smallGifResult", smallGifResult is ImageCompressionResult.Success)
        val prepared = (smallGifResult as ImageCompressionResult.Success).image
        try {
            assertFalse(prepared.wasCompressed)
            assertEquals("image/gif", prepared.mimeType)
            assertArrayEquals(smallGifBytes, prepared.file.readBytes())
        } finally {
            prepared.cleanup()
        }
        assertEquals(0, workDir.listFiles()?.size ?: 0)

        val oversizedGifBytes = TestImageFactory.createValidGifBytes(width = 8, height = 8, paddingBytes = 2048)
        val rejected = compressor.prepare(
            input = ImageUploadInput.Bytes(oversizedGifBytes, mimeType = "image/gif"),
            maxUploadBytes = 512,
        )
        assertTrue("Expected Failure(GifTooLarge) but got $rejected", rejected is ImageCompressionResult.Failure)
        val error = (rejected as ImageCompressionResult.Failure).error
        assertTrue(error is ImageUploadError.GifTooLarge)
        assertEquals(oversizedGifBytes.size.toLong(), (error as ImageUploadError.GifTooLarge).actualBytes)
        assertEquals(512, error.maxUploadBytes)
        assertEquals(0, workDir.listFiles()?.size ?: 0)
    }

    @Test
    fun prepare_impossibleTinyBudgetReturnsUnableToCompressAndCleansAllTempFiles() = runBlocking {
        val img = TestImageFactory.writeNoisyPng(
            file = File(tempFolder.root, "uncompressible.png"),
            width = 200,
            height = 200,
        )
        val result = compressor.prepare(
            input = ImageUploadInput.LocalFile(img, original = true),
            maxUploadBytes = 32,
        )
        assertTrue("Expected Failure(UnableToCompress) but got $result", result is ImageCompressionResult.Failure)
        val error = (result as ImageCompressionResult.Failure).error
        assertTrue(error is ImageUploadError.UnableToCompress)
        assertEquals(0, workDir.listFiles()?.size ?: 0)
    }

    @Test
    fun prepare_over2560PxImageDownsamplesAndCapsMaxDimensionAt2560() = runBlocking {
        val wideJpg = TestImageFactory.writeSolidJpeg(
            file = File(tempFolder.root, "ultra_wide.jpg"),
            width = 3200,
            height = 2000,
            quality = 90,
        )
        val result = compressor.prepare(
            input = ImageUploadInput.LocalFile(wideJpg, original = false),
            maxUploadBytes = 2 * 1024 * 1024,
        )
        assertTrue("Expected Success but got $result", result is ImageCompressionResult.Success)
        val prepared = (result as ImageCompressionResult.Success).image
        try {
            assertTrue(prepared.wasCompressed)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(prepared.file.absolutePath, bounds)
            assertTrue("Width ${bounds.outWidth} must be <= 2560", bounds.outWidth in 1..ImageCompressor.MAX_DIMENSION)
            assertTrue("Height ${bounds.outHeight} must be <= 2560", bounds.outHeight in 1..ImageCompressor.MAX_DIMENSION)
        } finally {
            prepared.cleanup()
        }
        assertEquals(0, workDir.listFiles()?.size ?: 0)
    }

    @Test
    fun prepare_corruptedBytesReturnDecodeFailedAndCleanTempFiles() = runBlocking {
        val result = compressor.prepare(
            input = ImageUploadInput.Bytes("not an image".toByteArray()),
            maxUploadBytes = 1024 * 1024,
        )
        assertTrue(result is ImageCompressionResult.Failure)
        assertTrue((result as ImageCompressionResult.Failure).error is ImageUploadError.DecodeFailed)
        assertEquals(0, workDir.listFiles()?.size ?: 0)
    }

    @Test
    fun prepare_contentUri_streamsViaContentResolverAppliesExifRotationAndCleansTempFiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val shadowResolver = shadowOf(context.contentResolver)

        // 1) Unrotated content:// URI with original=true -> direct pass-through copy via ContentResolver
        val rawDirectFile = TestImageFactory.writeQuadrantJpeg(
            file = File(tempFolder.root, "content_direct_src.jpg"),
            width = 240,
            height = 120,
        )
        val rawDirectBytes = rawDirectFile.readBytes()
        val directUri = Uri.parse("content://net.jejer.hipda.test/media/direct_1.jpg")
        shadowResolver.registerInputStream(directUri, ByteArrayInputStream(rawDirectBytes))

        val directResult = compressor.prepare(
            input = ImageUploadInput.ContentUri(directUri, original = true),
            maxUploadBytes = 512 * 1024,
        )
        assertTrue("Expected Success for content:// direct upload, got $directResult", directResult is ImageCompressionResult.Success)
        val directPrepared = (directResult as ImageCompressionResult.Success).image
        try {
            assertFalse("Unrotated small content:// original should not recompress", directPrepared.wasCompressed)
            assertTrue("content:// materialization must own a temp copy", directPrepared.isTempFile)
            assertEquals("image/jpeg", directPrepared.mimeType)
            assertArrayEquals(rawDirectBytes, directPrepared.file.readBytes())
        } finally {
            directPrepared.cleanup()
        }
        assertEquals("All temp files must be cleaned after content:// direct upload", 0, workDir.listFiles()?.size ?: 0)

        // 2) EXIF-rotated (270°) content:// URI -> streamed via ContentResolver, Matrix-corrected, source temp deleted
        val rotatedSrcFile = TestImageFactory.writeQuadrantJpeg(
            file = File(tempFolder.root, "content_rot270_src.jpg"),
            width = 240,
            height = 120,
        )
        ExifInterface(rotatedSrcFile.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_270.toString())
            saveAttributes()
        }
        val rotatedUri = Uri.parse("content://net.jejer.hipda.test/media/rot270.jpg")
        shadowResolver.registerInputStream(rotatedUri, ByteArrayInputStream(rotatedSrcFile.readBytes()))

        val rotatedResult = compressor.prepare(
            input = ImageUploadInput.ContentUri(rotatedUri, original = true),
            maxUploadBytes = 512 * 1024,
        )
        assertTrue("Expected Success for rotated content:// upload, got $rotatedResult", rotatedResult is ImageCompressionResult.Success)
        val rotatedPrepared = (rotatedResult as ImageCompressionResult.Success).image
        try {
            assertTrue("EXIF-rotated content:// image must be re-encoded", rotatedPrepared.wasCompressed)
            assertTrue(rotatedPrepared.isTempFile)
            assertEquals("image/jpeg", rotatedPrepared.mimeType)
            // Source uri_*.tmp must already have been cleaned by ImageCompressor's finally block; only output cmp_*.jpg remains
            assertEquals(1, workDir.listFiles()?.size ?: 0)
            val decoded = BitmapFactory.decodeFile(rotatedPrepared.file.absolutePath)
            try {
                assertTrue("Expected portrait after 270° rotation, got ${decoded.width}x${decoded.height}", decoded.height > decoded.width)
                assertQuadrantColors(
                    bitmap = decoded,
                    expectedTopLeft = QuadrantColor.GREEN,
                    expectedTopRight = QuadrantColor.YELLOW,
                    expectedBottomLeft = QuadrantColor.RED,
                    expectedBottomRight = QuadrantColor.BLUE,
                    label = "content:// ORIENTATION_ROTATE_270",
                )
            } finally {
                decoded.recycle()
            }
        } finally {
            rotatedPrepared.cleanup()
        }
        assertEquals(0, workDir.listFiles()?.size ?: 0)

        // 3) Unresolvable content:// URI -> DecodeFailed with zero leaked temp files
        val missingUri = Uri.parse("content://net.jejer.hipda.test/media/does_not_exist.jpg")
        val missingResult = compressor.prepare(
            input = ImageUploadInput.ContentUri(missingUri, original = true),
            maxUploadBytes = 512 * 1024,
        )
        assertTrue("Expected DecodeFailed for missing content:// URI, got $missingResult", missingResult is ImageCompressionResult.Failure)
        assertTrue((missingResult as ImageCompressionResult.Failure).error is ImageUploadError.DecodeFailed)
        assertEquals(0, workDir.listFiles()?.size ?: 0)
    }

    @Test
    fun prepare_defaultOriginalIsFalse_compressesNormalPhotoWhilePassingGifAndLongImageThrough() = runBlocking {
        val smallPng = TestImageFactory.writeSolidPng(
            file = File(tempFolder.root, "default_compress.png"),
            width = 240,
            height = 180,
        )
        val defaultFileInput = ImageUploadInput.LocalFile(smallPng)
        val defaultUriInput = ImageUploadInput.ContentUri(Uri.parse("content://net.jejer.hipda.test/media/default.png"))
        val defaultBytesInput = ImageUploadInput.Bytes(smallPng.readBytes(), mimeType = "image/png")
        assertFalse("R-D6 S1: ImageUploadInput.LocalFile.original must default to false", defaultFileInput.original)
        assertFalse("R-D6 S1: ImageUploadInput.ContentUri.original must default to false", defaultUriInput.original)
        assertFalse("R-D6 S1: ImageUploadInput.Bytes.original must default to false", defaultBytesInput.original)

        val photoRes = compressor.prepare(input = defaultFileInput, maxUploadBytes = 512 * 1024)
        assertTrue("Expected Success but got $photoRes", photoRes is ImageCompressionResult.Success)
        val photoPrepared = (photoRes as ImageCompressionResult.Success).image
        try {
            assertTrue("Default original=false must compress normal non-GIF photos", photoPrepared.wasCompressed)
            assertEquals("image/jpeg", photoPrepared.mimeType)
        } finally {
            photoPrepared.cleanup()
        }

        val longPng = TestImageFactory.writeSolidPng(
            file = File(tempFolder.root, "default_long.png"),
            width = 100,
            height = 360,
        )
        val longRes = compressor.prepare(input = ImageUploadInput.LocalFile(longPng), maxUploadBytes = 512 * 1024)
        val longPrepared = (longRes as ImageCompressionResult.Success).image
        try {
            assertFalse("Ultra-long image (AR >= 3.0) must still pass through when original defaults to false", longPrepared.wasCompressed)
            assertEquals("image/png", longPrepared.mimeType)
        } finally {
            longPrepared.cleanup()
        }

        val gifBytes = TestImageFactory.createValidGifBytes(width = 16, height = 16, paddingBytes = 64)
        val gifRes = compressor.prepare(input = ImageUploadInput.Bytes(gifBytes, mimeType = "image/gif"), maxUploadBytes = 4096)
        val gifPrepared = (gifRes as ImageCompressionResult.Success).image
        try {
            assertFalse("Compliant GIF must still pass through when original defaults to false", gifPrepared.wasCompressed)
            assertEquals("image/gif", gifPrepared.mimeType)
        } finally {
            gifPrepared.cleanup()
        }
        assertEquals(0, workDir.listFiles()?.size ?: 0)
    }

    @Test
    fun prepare_allExifOrientationTransformsProduceExactPixelOrientationAndDimensions() = runBlocking {
        // Source 240x120 landscape quadrants: TL=RED, TR=GREEN, BL=BLUE, BR=YELLOW
        data class ExifCase(
            val name: String,
            val orientation: Int,
            val expectPortrait: Boolean,
            val topLeft: QuadrantColor,
            val topRight: QuadrantColor,
            val bottomLeft: QuadrantColor,
            val bottomRight: QuadrantColor,
        )

        val cases = listOf(
            ExifCase("ROTATE_90", ExifInterface.ORIENTATION_ROTATE_90, true, QuadrantColor.BLUE, QuadrantColor.RED, QuadrantColor.YELLOW, QuadrantColor.GREEN),
            ExifCase("ROTATE_180", ExifInterface.ORIENTATION_ROTATE_180, false, QuadrantColor.YELLOW, QuadrantColor.BLUE, QuadrantColor.GREEN, QuadrantColor.RED),
            ExifCase("ROTATE_270", ExifInterface.ORIENTATION_ROTATE_270, true, QuadrantColor.GREEN, QuadrantColor.YELLOW, QuadrantColor.RED, QuadrantColor.BLUE),
            ExifCase("FLIP_HORIZONTAL", ExifInterface.ORIENTATION_FLIP_HORIZONTAL, false, QuadrantColor.GREEN, QuadrantColor.RED, QuadrantColor.YELLOW, QuadrantColor.BLUE),
            ExifCase("FLIP_VERTICAL", ExifInterface.ORIENTATION_FLIP_VERTICAL, false, QuadrantColor.BLUE, QuadrantColor.YELLOW, QuadrantColor.RED, QuadrantColor.GREEN),
            ExifCase("TRANSPOSE", ExifInterface.ORIENTATION_TRANSPOSE, true, QuadrantColor.RED, QuadrantColor.BLUE, QuadrantColor.GREEN, QuadrantColor.YELLOW),
            ExifCase("TRANSVERSE", ExifInterface.ORIENTATION_TRANSVERSE, true, QuadrantColor.YELLOW, QuadrantColor.GREEN, QuadrantColor.BLUE, QuadrantColor.RED),
        )

        for (case in cases) {
            val file = TestImageFactory.writeQuadrantJpeg(
                file = File(tempFolder.root, "exif_${case.name}.jpg"),
                width = 240,
                height = 120,
            )
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, case.orientation.toString())
                saveAttributes()
            }

            val result = compressor.prepare(
                input = ImageUploadInput.LocalFile(file, original = true),
                maxUploadBytes = 512 * 1024,
            )
            assertTrue("${case.name}: expected Success but got $result", result is ImageCompressionResult.Success)
            val prepared = (result as ImageCompressionResult.Success).image
            try {
                assertTrue("${case.name}: must re-encode instead of direct upload", prepared.wasCompressed)
                val decoded = BitmapFactory.decodeFile(prepared.file.absolutePath)
                try {
                    if (case.expectPortrait) {
                        assertTrue(
                            "${case.name}: expected portrait, got ${decoded.width}x${decoded.height}",
                            decoded.height > decoded.width,
                        )
                    } else {
                        assertTrue(
                            "${case.name}: expected landscape, got ${decoded.width}x${decoded.height}",
                            decoded.width > decoded.height,
                        )
                    }
                    assertQuadrantColors(
                        bitmap = decoded,
                        expectedTopLeft = case.topLeft,
                        expectedTopRight = case.topRight,
                        expectedBottomLeft = case.bottomLeft,
                        expectedBottomRight = case.bottomRight,
                        label = case.name,
                    )
                } finally {
                    decoded.recycle()
                }
            } finally {
                prepared.cleanup()
            }
            assertEquals("${case.name}: temp dir must be clean", 0, workDir.listFiles()?.size ?: 0)
        }
    }

    private enum class QuadrantColor { RED, GREEN, BLUE, YELLOW }

    private fun classifyQuadrantColor(pixel: Int): QuadrantColor {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return when {
            r > 170 && g < 85 && b < 85 -> QuadrantColor.RED
            r < 85 && g > 170 && b < 85 -> QuadrantColor.GREEN
            r < 85 && g < 85 && b > 170 -> QuadrantColor.BLUE
            r > 170 && g > 170 && b < 85 -> QuadrantColor.YELLOW
            else -> error("Unexpected pixel RGB=($r,$g,$b)")
        }
    }

    private fun assertQuadrantColors(
        bitmap: Bitmap,
        expectedTopLeft: QuadrantColor,
        expectedTopRight: QuadrantColor,
        expectedBottomLeft: QuadrantColor,
        expectedBottomRight: QuadrantColor,
        label: String,
    ) {
        val w = bitmap.width
        val h = bitmap.height
        assertEquals("$label TL", expectedTopLeft, classifyQuadrantColor(bitmap.getPixel(w / 4, h / 4)))
        assertEquals("$label TR", expectedTopRight, classifyQuadrantColor(bitmap.getPixel((w * 3) / 4, h / 4)))
        assertEquals("$label BL", expectedBottomLeft, classifyQuadrantColor(bitmap.getPixel(w / 4, (h * 3) / 4)))
        assertEquals("$label BR", expectedBottomRight, classifyQuadrantColor(bitmap.getPixel((w * 3) / 4, (h * 3) / 4)))
    }
}

internal object TestImageFactory {
    fun writeQuadrantJpeg(file: File, width: Int, height: Int, quality: Int = 95): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val halfW = width / 2
        val halfH = height / 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = when {
                    y < halfH && x < halfW -> Color.rgb(255, 0, 0)
                    y < halfH && x >= halfW -> Color.rgb(0, 255, 0)
                    y >= halfH && x < halfW -> Color.rgb(0, 0, 255)
                    else -> Color.rgb(255, 255, 0)
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        bitmap.recycle()
        return file
    }
    fun writeNoisyPng(file: File, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val random = Random(42L)
        for (i in pixels.indices) {
            pixels[i] = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    fun writeSolidJpeg(file: File, width: Int, height: Int, quality: Int = 90): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(64, 128, 192))
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        bitmap.recycle()
        return file
    }

    fun writeSolidPng(file: File, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(32, 160, 96))
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    /**
     * Generates a valid minimal GIF89a byte sequence with optional comment extension padding so
     * tests can precisely control GIF file sizes.
     */
    fun createValidGifBytes(width: Int = 1, height: Int = 1, paddingBytes: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        // Header: GIF89a
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical Screen Descriptor (width LE, height LE, packed GCT=1 2 colors, bg=0, aspect=0)
        out.write(width and 0xFF)
        out.write((width ushr 8) and 0xFF)
        out.write(height and 0xFF)
        out.write((height ushr 8) and 0xFF)
        out.write(0x80)
        out.write(0x00)
        out.write(0x00)
        // Global Color Table (2 entries * 3 bytes)
        out.write(byteArrayOf(0, 0, 0, 255.toByte(), 255.toByte(), 255.toByte()))
        // Optional Comment Extension blocks for padding
        if (paddingBytes > 0) {
            out.write(0x21)
            out.write(0xFE)
            var remaining = paddingBytes
            while (remaining > 0) {
                val chunk = minOf(255, remaining)
                out.write(chunk)
                repeat(chunk) { out.write('A'.code) }
                remaining -= chunk
            }
            out.write(0x00) // block terminator
        }
        // 1x1 Image Descriptor + LZW data
        out.write(0x2C)
        out.write(byteArrayOf(0, 0, 0, 0, 1, 0, 1, 0, 0))
        out.write(0x02) // LZW minimum code size
        out.write(0x02) // sub-block length
        out.write(byteArrayOf(0x4C, 0x01))
        out.write(0x00) // sub-block terminator
        out.write(0x3B) // GIF Trailer
        return out.toByteArray()
    }
}
