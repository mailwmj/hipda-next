package net.jejer.hipda.feature.imageviewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.Disposable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import net.jejer.hipda.feature.common.PaneSnackbarController
import net.jejer.hipda.feature.common.PaneSnackbarTarget

/** 离线可控 Coil 3 测试加载器：记录所有请求并支持自定义成功/失败/延时/GIF 替身。 */
internal class ViewerTestImageLoader(
    private val handler: suspend (ImageRequest) -> ImageResult,
) : ImageLoader {
    private val _requests = CopyOnWriteArrayList<ImageRequest>()
    val requests: List<ImageRequest> get() = _requests.toList()
    val requestedUrls: List<String> get() = _requests.map { it.data.toString() }
    val requestCount: Int get() = _requests.size
    val cancelledCount = AtomicInteger(0)

    override val defaults: ImageRequest.Defaults = ImageRequest.Defaults.DEFAULT
    override val components: ComponentRegistry = ComponentRegistry()
    override val memoryCache: MemoryCache? = null
    override val diskCache: DiskCache? = null

    override fun enqueue(request: ImageRequest): Disposable {
        val deferred = CompletableDeferred<ImageResult>()
        return object : Disposable {
            override val job: Deferred<ImageResult> = deferred
            override val isDisposed: Boolean get() = deferred.isCancelled
            override fun dispose() {
                deferred.cancel()
            }
        }
    }

    override suspend fun execute(request: ImageRequest): ImageResult {
        _requests += request
        return try {
            handler(request)
        } catch (cancelled: CancellationException) {
            cancelledCount.incrementAndGet()
            throw cancelled
        }
    }

    override fun shutdown() = Unit

    override fun newBuilder(): ImageLoader.Builder = throw UnsupportedOperationException("Not needed in unit tests")

    companion object {
        fun solid(width: Int = 800, height: Int = 600, color: Int = 0xFF336699.toInt()): ViewerTestImageLoader =
            ViewerTestImageLoader { request ->
                SuccessResult(
                    image = createViewerPhotoBitmap(width, height, accentColor = color).asImage(),
                    request = request,
                    dataSource = DataSource.NETWORK,
                )
            }
    }
}

/** 可断言 `isRunning` 与启动/停止次数的 GIF [Animatable] Drawable 测试替身。 */
internal class FakeAnimatableDrawable(
    private val intrinsicW: Int = 480,
    private val intrinsicH: Int = 360,
    color: Int = 0xFF3A6EA5.toInt(),
) : ColorDrawable(color), Animatable {
    private var running = false
    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set

    override fun getIntrinsicWidth(): Int = intrinsicW
    override fun getIntrinsicHeight(): Int = intrinsicH
    override fun isRunning(): Boolean = running

    override fun start() {
        if (!running) {
            running = true
            startCount++
        }
    }

    override fun stop() {
        if (running) {
            running = false
            stopCount++
        }
    }
}

/** 可编排流式进度与取消计数的 [OriginalImageDownloader] 测试替身。 */
internal class FakeOriginalImageDownloader(
    private val flowFactory: (String) -> Flow<OriginalDownloadState> = { emptyFlow() },
) : OriginalImageDownloader {
    val requestedUrls = CopyOnWriteArrayList<String>()
    val cancelledCount = AtomicInteger(0)

    override fun download(url: String): Flow<OriginalDownloadState> = flow {
        requestedUrls += url
        try {
            flowFactory(url).collect { emit(it) }
        } catch (cancelled: CancellationException) {
            cancelledCount.incrementAndGet()
            throw cancelled
        }
    }
}

/** 保存/分享测试替身（不触发真实 MediaStore/Activity Intent）。 */
internal class FakeImageViewerMediaActions(
    var saveResult: Boolean = true,
    var shareResult: Boolean = true,
) : ImageViewerMediaActions {
    val savedUrls = CopyOnWriteArrayList<String>()
    val sharedUrls = CopyOnWriteArrayList<String>()

    override suspend fun saveImage(url: String, cachedFile: File?): Boolean {
        savedUrls += url
        return saveResult
    }

    override suspend fun shareImage(url: String, cachedFile: File?): Boolean {
        sharedUrls += url
        return shareResult
    }
}

/** 记录所有 Snackbar 消息的控制器替身。 */
internal class RecordingSnackbarController : PaneSnackbarController {
    val messages = CopyOnWriteArrayList<String>()

    override suspend fun show(
        message: String,
        target: PaneSnackbarTarget,
        actionLabel: String?,
        onAction: (() -> Unit)?,
    ) {
        messages += message
    }
}

/** 生成用于视觉截图与单测的高区分度合成摄影图。 */
internal fun createViewerPhotoBitmap(
    width: Int,
    height: Int,
    accentColor: Int = 0xFF3E7CB1.toInt(),
    secondaryColor: Int = 0xFF1D3557.toInt(),
): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(secondaryColor, accentColor, 0xFF81A4CD.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

    val hillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x550B132B }
    canvas.drawCircle(width * 0.28f, height * 0.85f, width * 0.45f, hillPaint)
    canvas.drawCircle(width * 0.78f, height * 0.90f, width * 0.42f, hillPaint)

    val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDDF4D35E.toInt() }
    canvas.drawCircle(width * 0.74f, height * 0.28f, minOf(width, height) * 0.11f, sunPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0x44FFFFFF
    }
    canvas.drawRect(1.5f, 1.5f, width - 1.5f, height - 1.5f, borderPaint)
    return bmp
}

/** 生成用于长图验证的分段竖向海报（高宽比 > 2.5，顶部与底部色带清晰可辨）。 */
internal fun createLongPosterBitmap(width: Int = 400, height: Int = 1600): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val bandColors = intArrayOf(
        0xFF264653.toInt(),
        0xFF2A9D8F.toInt(),
        0xFFE9C46A.toInt(),
        0xFFF4A261.toInt(),
        0xFFE76F51.toInt(),
        0xFF457B9D.toInt(),
        0xFF1D3557.toInt(),
        0xFF6D597A.toInt(),
    )
    val bandH = height.toFloat() / bandColors.size
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    bandColors.forEachIndexed { idx, c ->
        val top = idx * bandH
        paint.color = c
        canvas.drawRect(0f, top, width.toFloat(), top + bandH, paint)
        canvas.drawRoundRect(
            RectF(width * 0.08f, top + bandH * 0.2f, width * 0.92f, top + bandH * 0.8f),
            16f, 16f, cardPaint,
        )
    }
    return bmp
}

/** 生成有效 PNG 字节数组或落地临时文件，供 MockWebServer 原图下载测试使用。 */
internal fun createPngBytes(width: Int = 64, height: Int = 64, color: Int = Color.BLUE): ByteArray {
    val bmp = createViewerPhotoBitmap(width, height, accentColor = color)
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}

internal fun writeTempPngFile(dir: File, name: String = "orig.png", width: Int = 128, height: Int = 96): File {
    val file = File(dir, name)
    FileOutputStream(file).use { it.write(createPngBytes(width, height)) }
    return file
}
