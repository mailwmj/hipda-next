package net.jejer.hipda.data.post

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
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
}

internal object TestImageFactory {
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
