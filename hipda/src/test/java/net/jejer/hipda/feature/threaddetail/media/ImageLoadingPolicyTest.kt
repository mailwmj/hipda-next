package net.jejer.hipda.feature.threaddetail.media

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import coil3.ComponentRegistry
import coil3.Image
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import net.jejer.hipda.parser.threaddetail.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * 覆盖 E2b 验收项 2、3 中的策略/白名单/格式化/比例缓存纯逻辑与 Robolectric `ConnectivityManager` 读值测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ImageLoadingPolicyTest {

    @Test
    fun resolveLoadMode_onlyEnablesClickToLoadWhenBothMeteredAndDataSaverEnabled() {
        assertEquals(ImageLoadMode.AutoLoad, ImageLoadingPolicy.resolveLoadMode(isMetered = false, isDataSaverEnabled = false))
        assertEquals(ImageLoadMode.AutoLoad, ImageLoadingPolicy.resolveLoadMode(isMetered = true, isDataSaverEnabled = false))
        assertEquals(ImageLoadMode.AutoLoad, ImageLoadingPolicy.resolveLoadMode(isMetered = false, isDataSaverEnabled = true))
        assertEquals(ImageLoadMode.ClickToLoad, ImageLoadingPolicy.resolveLoadMode(isMetered = true, isDataSaverEnabled = true))

        assertEquals(
            ImageLoadMode.ClickToLoad,
            ImageLoadingPolicy.resolveLoadMode(ImageNetworkState(isMetered = true, isDataSaverEnabled = true)),
        )
    }

    @Test
    fun readNetworkState_readsAllFourConnectivityManagerCombinationsAndWhitelistedStatus() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = captureTestNetworkState(context)
        try {
            // 1. 非计费 + 流量节省关闭 -> AutoLoad
            configureTestNetworkState(context, metered = false, restrictStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED)
            assertEquals(ImageNetworkState(isMetered = false, isDataSaverEnabled = false), ImageLoadingPolicy.readNetworkState(context))
            assertEquals(ImageLoadMode.AutoLoad, ImageLoadingPolicy.resolveForContext(context))

            // 2. 计费 + 流量节省关闭 -> AutoLoad
            configureTestNetworkState(context, metered = true, restrictStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED)
            assertEquals(ImageNetworkState(isMetered = true, isDataSaverEnabled = false), ImageLoadingPolicy.readNetworkState(context))
            assertEquals(ImageLoadMode.AutoLoad, ImageLoadingPolicy.resolveForContext(context))

            // 3. 非计费 + 流量节省开启 -> AutoLoad
            configureTestNetworkState(context, metered = false, restrictStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
            assertEquals(ImageNetworkState(isMetered = false, isDataSaverEnabled = true), ImageLoadingPolicy.readNetworkState(context))
            assertEquals(ImageLoadMode.AutoLoad, ImageLoadingPolicy.resolveForContext(context))

            // 4. 计费 + 流量节省开启 -> ClickToLoad
            configureTestNetworkState(context, metered = true, restrictStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
            assertEquals(ImageNetworkState(isMetered = true, isDataSaverEnabled = true), ImageLoadingPolicy.readNetworkState(context))
            assertEquals(ImageLoadMode.ClickToLoad, ImageLoadingPolicy.resolveForContext(context))

            // 5. 计费 + 应用在流量节省白名单（WHITELISTED）-> AutoLoad
            configureTestNetworkState(context, metered = true, restrictStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED)
            assertEquals(ImageNetworkState(isMetered = true, isDataSaverEnabled = false), ImageLoadingPolicy.readNetworkState(context))
            assertEquals(ImageLoadMode.AutoLoad, ImageLoadingPolicy.resolveForContext(context))
        } finally {
            restoreTestNetworkState(context, original)
        }
    }

    @Test
    fun imageSizeFormatter_formatsBytesKbMbGbAndReturnsNullForUnknownOrNonPositive() {
        assertNull(ImageSizeFormatter.formatOrNull(-1L))
        assertNull(ImageSizeFormatter.formatOrNull(0L))
        assertEquals("1 B", ImageSizeFormatter.formatOrNull(1L))
        assertEquals("512 B", ImageSizeFormatter.formatOrNull(512L))
        assertEquals("1023 B", ImageSizeFormatter.formatOrNull(1023L))
        assertEquals("1 KB", ImageSizeFormatter.formatOrNull(1024L))
        assertEquals("1.5 KB", ImageSizeFormatter.formatOrNull(1536L))
        assertEquals("2 KB", ImageSizeFormatter.formatOrNull(2048L))
        assertEquals("125 KB", ImageSizeFormatter.formatOrNull(128_000L))
        assertEquals("1 MB", ImageSizeFormatter.formatOrNull(1_048_500L))
        assertEquals("1 MB", ImageSizeFormatter.formatOrNull(1_048_576L))
        assertEquals("1.5 MB", ImageSizeFormatter.formatOrNull(1_572_864L))
        assertEquals("10 MB", ImageSizeFormatter.formatOrNull(10L * 1024L * 1024L))
        assertEquals("1.5 GB", ImageSizeFormatter.formatOrNull(1_610_612_736L))
    }

    @Test
    fun sanitizeImageUrl_allowsOnlyHttpAndHttpsWithHostAndRejectsAllDangerousSchemes() {
        assertEquals("http://example.com/photo.jpg", sanitizeImageUrl("http://example.com/photo.jpg"))
        assertEquals("https://www.4d4y.com/forum/attachments/a.png", sanitizeImageUrl("https://www.4d4y.com/forum/attachments/a.png"))
        assertEquals("http://example.com/A.JPG", sanitizeImageUrl("HTTP://EXAMPLE.COM/A.JPG"))
        assertEquals("https://example.com/img?w=800&h=600", sanitizeImageUrl("HTTPS://example.com/img?w=800&h=600"))

        val rejected = listOf(
            null,
            "",
            "   ",
            "file:///etc/hosts",
            "FILE:///etc/passwd",
            " file:///etc/hosts",
            "file:///etc/hosts\n",
            "content://media/external/images/1",
            "CONTENT://settings/system",
            "\tcontent://media/external/images/1",
            "javascript:alert(1)",
            "JavaScript:void(0)",
            " javascript:alert(1)",
            "java\nscript:alert(1)",
            "intent://scan/#Intent;end",
            "INTENT://evil/#Intent;end",
            "data:image/png;base64,AAA",
            "DATA:image/svg+xml,<svg/>",
            "mailto:a@b.com",
            "MAILTO:a@b.com",
            "ftp://example.com/a.jpg",
            "tel:10086",
            "//example.com/protocol-relative.jpg",
            "/forum/attachments/relative.jpg",
            "attachments/a.jpg",
            "http://",
            "https://",
            " https://example.com/leading.jpg",
            "https://example.com/trailing.jpg ",
            "https://example.com/null\u0000byte.jpg",
            "https://example.com/tab\tchar.jpg",
        )
        for (raw in rejected) {
            assertNull("非法图片 URL [$raw] 必须被白名单拦截", sanitizeImageUrl(raw))
        }
    }

    @Test
    fun resolveImageUrls_prefersSafeThumbForDisplayAndNeverLeaksDangerousFullOrThumb() {
        val normal = resolveImageUrls(
            ContentBlock.Image(
                fullUrl = "https://img.example.invalid/full.jpg",
                thumbUrl = "https://img.example.invalid/thumb.jpg",
                fileSizeBytes = 1024L,
            ),
        )
        assertEquals(
            ResolvedImageUrls(
                displayUrl = "https://img.example.invalid/thumb.jpg",
                viewerUrl = "https://img.example.invalid/full.jpg",
            ),
            normal,
        )

        val badThumb = resolveImageUrls(
            ContentBlock.Image(
                fullUrl = "https://img.example.invalid/full.jpg",
                thumbUrl = "file:///etc/hosts",
                fileSizeBytes = 1024L,
            ),
        )
        assertEquals(
            ResolvedImageUrls(
                displayUrl = "https://img.example.invalid/full.jpg",
                viewerUrl = "https://img.example.invalid/full.jpg",
            ),
            badThumb,
        )

        val badFull = resolveImageUrls(
            ContentBlock.Image(
                fullUrl = "content://media/external/images/1",
                thumbUrl = "https://img.example.invalid/thumb.jpg",
                fileSizeBytes = 1024L,
            ),
        )
        assertEquals(
            ResolvedImageUrls(
                displayUrl = "https://img.example.invalid/thumb.jpg",
                viewerUrl = "https://img.example.invalid/thumb.jpg",
            ),
            badFull,
        )

        assertNull(
            resolveImageUrls(
                ContentBlock.Image(
                    fullUrl = "file:///etc/hosts",
                    thumbUrl = "javascript:alert(1)",
                    fileSizeBytes = 0L,
                ),
            ),
        )
    }

    @Test
    fun imageAspectRatioCache_normalizesKeysClampsExtremesAndEvictsLru() {
        val cache = ImageAspectRatioCache(maxEntries = 2)
        assertEquals(ImageAspectRatioCache.DEFAULT_ASPECT_RATIO, cache.resolveInitialAspectRatio("https://example.com/miss.jpg"), 0.001f)

        val tallClamped = cache.put("HTTP://EXAMPLE.COM/tall.jpg", width = 100, height = 10_000)
        assertEquals(ImageAspectRatioCache.MIN_ASPECT_RATIO, tallClamped!!, 0.0001f)
        assertEquals(ImageAspectRatioCache.MIN_ASPECT_RATIO, cache.get("http://example.com/tall.jpg")!!, 0.0001f)

        val wideClamped = cache.put("https://example.com/wide.jpg", width = 10_000, height = 100)
        assertEquals(ImageAspectRatioCache.MAX_ASPECT_RATIO, wideClamped!!, 0.0001f)

        // Touch tall.jpg so wide.jpg becomes LRU
        assertTrue(cache.get("http://example.com/tall.jpg") != null)
        cache.put("https://example.com/normal.jpg", width = 400, height = 1200)

        assertEquals(2, cache.size)
        assertNull("LRU 条目 wide.jpg 应被淘汰", cache.get("https://example.com/wide.jpg"))
        assertEquals(ImageAspectRatioCache.MIN_ASPECT_RATIO, cache.get("http://example.com/tall.jpg")!!, 0.0001f)
        assertEquals(1f / 3f, cache.get("https://example.com/normal.jpg")!!, 0.0001f)

        assertNull(cache.put("https://example.com/invalid.jpg", width = 0, height = 100))
        assertNull(cache.put("https://example.com/nan.jpg", Float.NaN))
    }
}

/**
 * 100% 离线可控 `coil3.ImageLoader` 测试替身（不创建 `RealImageLoader`、`OkHttpClient` 或任何网络 Fetcher，
 * 结构上杜绝真实 DNS 与网络请求）。
 */
internal class OfflineTestImageLoader(
    private val handler: suspend (ImageRequest) -> ImageResult,
) : ImageLoader {
    val requests = CopyOnWriteArrayList<ImageRequest>()
    val requestedUrls: List<String>
        get() = requests.map { it.data.toString() }
    val requestCount: Int
        get() = requests.size
    val cancelledCount = AtomicInteger(0)
    val enqueueCount = AtomicInteger(0)

    override val defaults: ImageRequest.Defaults = ImageRequest.Defaults.DEFAULT
    override val components: ComponentRegistry = ComponentRegistry()
    override val memoryCache: MemoryCache? = null
    override val diskCache: DiskCache? = null

    override fun enqueue(request: ImageRequest): Disposable {
        enqueueCount.incrementAndGet()
        requests += request
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
        requests += request
        return try {
            handler(request)
        } catch (cancelled: CancellationException) {
            cancelledCount.incrementAndGet()
            throw cancelled
        }
    }

    override fun shutdown() = Unit

    override fun newBuilder(): ImageLoader.Builder =
        throw UnsupportedOperationException("OfflineTestImageLoader does not support newBuilder()")

    companion object {
        fun success(
            width: Int = 400,
            height: Int = 300,
            dataSource: DataSource = DataSource.NETWORK,
            dark: Boolean = false,
        ): OfflineTestImageLoader = OfflineTestImageLoader { request ->
            SuccessResult(
                image = createSyntheticBitmap(width, height, dark).asImage(),
                request = request,
                dataSource = dataSource,
            )
        }

        fun failure(message: String = "offline error"): OfflineTestImageLoader = OfflineTestImageLoader { request ->
            ErrorResult(
                image = null,
                request = request,
                throwable = IllegalStateException(message),
            )
        }
    }
}

/** 生成确定性离线合成位图（柔和风景卡片，零外部资源依赖），供截图与功能单测使用。 */
internal fun createSyntheticBitmap(
    width: Int = 400,
    height: Int = 300,
    dark: Boolean = false,
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    val skyColor = if (dark) AndroidColor.rgb(34, 48, 66) else AndroidColor.rgb(214, 232, 248)
    val farHillColor = if (dark) AndroidColor.rgb(52, 74, 96) else AndroidColor.rgb(154, 192, 224)
    val nearHillColor = if (dark) AndroidColor.rgb(38, 92, 114) else AndroidColor.rgb(98, 152, 194)
    val sunColor = if (dark) AndroidColor.rgb(232, 198, 134) else AndroidColor.rgb(252, 224, 154)

    paint.color = skyColor
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

    paint.color = sunColor
    canvas.drawCircle(width * 0.74f, height * 0.28f, minOf(width, height) * 0.11f, paint)

    paint.color = farHillColor
    val farPath = Path().apply {
        moveTo(0f, height * 0.72f)
        lineTo(width * 0.30f, height * 0.44f)
        lineTo(width * 0.62f, height * 0.68f)
        lineTo(width * 0.86f, height * 0.50f)
        lineTo(width.toFloat(), height * 0.66f)
        lineTo(width.toFloat(), height.toFloat())
        lineTo(0f, height.toFloat())
        close()
    }
    canvas.drawPath(farPath, paint)

    paint.color = nearHillColor
    val nearPath = Path().apply {
        moveTo(0f, height * 0.82f)
        lineTo(width * 0.24f, height * 0.64f)
        lineTo(width * 0.54f, height * 0.80f)
        lineTo(width * 0.82f, height * 0.62f)
        lineTo(width.toFloat(), height * 0.78f)
        lineTo(width.toFloat(), height.toFloat())
        lineTo(0f, height.toFloat())
        close()
    }
    canvas.drawPath(nearPath, paint)
    return bitmap
}

internal data class SavedTestNetworkState(
    @Suppress("DEPRECATION") val activeNetworkInfo: NetworkInfo?,
    val capabilities: NetworkCapabilities?,
    val restrictStatus: Int,
)

@Suppress("DEPRECATION")
internal fun captureTestNetworkState(context: Context): SavedTestNetworkState {
    val cm = context.getSystemService(ConnectivityManager::class.java)!!
    return SavedTestNetworkState(
        activeNetworkInfo = cm.activeNetworkInfo,
        capabilities = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) },
        restrictStatus = cm.restrictBackgroundStatus,
    )
}

@Suppress("DEPRECATION")
internal fun configureTestNetworkState(
    context: Context,
    metered: Boolean,
    restrictStatus: Int,
) {
    val cm = context.getSystemService(ConnectivityManager::class.java)!!
    val shadowCm = Shadows.shadowOf(cm)
    val networkInfo = ShadowNetworkInfo.newInstance(
        NetworkInfo.DetailedState.CONNECTED,
        if (metered) ConnectivityManager.TYPE_MOBILE else ConnectivityManager.TYPE_WIFI,
        0,
        true,
        NetworkInfo.State.CONNECTED,
    )
    shadowCm.setActiveNetworkInfo(networkInfo)
    val network = cm.activeNetwork
    val caps = ShadowNetworkCapabilities.newInstance()
    val shadowCaps = Shadows.shadowOf(caps)
    shadowCaps.addTransportType(
        if (metered) NetworkCapabilities.TRANSPORT_CELLULAR else NetworkCapabilities.TRANSPORT_WIFI,
    )
    if (!metered) {
        shadowCaps.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
    shadowCm.setNetworkCapabilities(network, caps)
    shadowCm.setRestrictBackgroundStatus(restrictStatus)
}

@Suppress("DEPRECATION")
internal fun restoreTestNetworkState(context: Context, saved: SavedTestNetworkState) {
    val cm = context.getSystemService(ConnectivityManager::class.java)!!
    val shadowCm = Shadows.shadowOf(cm)
    shadowCm.setActiveNetworkInfo(saved.activeNetworkInfo)
    shadowCm.setNetworkCapabilities(cm.activeNetwork, saved.capabilities)
    shadowCm.setRestrictBackgroundStatus(saved.restrictStatus)
}
