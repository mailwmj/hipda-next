package net.jejer.hipda.feature.imageviewer

import android.app.Application
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * E14 验收项 3（收口 R-E2b S4）：`MockWebServer` 节流响应验证 [OkHttpOriginalImageDownloader]
 * - 已知 `Content-Length` 逐块回调真实确定进度（`0 < p < 1`）并产出完整文件；
 * - 未知 `Content-Length`（chunked）回退不确定态（`progress == null`）；
 * - 中途取消协程立即调用底层 `Call.cancel()` 中止传输并清理临时文件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OriginalImageDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = createTempDirectory("e14-orig-dl").toFile()
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        tempDir.deleteRecursively()
    }

    @Test
    fun throttledKnownContentLength_emitsIncrementalDeterminateProgressAndCompletes() = runBlocking {
        val rawPng = createPngBytes(width = 160, height = 120)
        // 拼装足够大的体量（>= 48 KB）确保在 4 KB 缓冲 + 节流下产生多段中间确定进度
        val padding = ByteArray(48 * 1024) { (it % 251).toByte() }
        val payload = rawPng + padding

        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/png")
                .body(Buffer().write(payload))
                .throttleBody(bytesPerPeriod = 8192L, period = 15L, unit = TimeUnit.MILLISECONDS)
                .build(),
        )

        val downloader = OkHttpOriginalImageDownloader(
            callFactory = OkHttpClient(),
            cacheDir = tempDir,
        )
        val url = server.url("/original.png").toString()
        val states = downloader.download(url).toList()

        val downloading = states.filterIsInstance<OriginalDownloadState.Downloading>()
        val intermediate = downloading.mapNotNull { it.progress }.filter { it > 0f && it < 1f }
        assertTrue("节流响应必须产出至少 2 个真实中间确定进度 (0 < p < 1)，实际: $downloading", intermediate.size >= 2)
        // 进度严格单调不减
        for (i in 1 until intermediate.size) {
            assertTrue("进度必须单调不减: $intermediate", intermediate[i] >= intermediate[i - 1])
        }
        assertTrue(
            "每个 Downloading 状态都应携带真实 Content-Length",
            downloading.all { it.totalBytes == payload.size.toLong() },
        )

        val completed = states.last() as? OriginalDownloadState.Completed
        assertNotNull("最终状态必须为 Completed，实际: ${states.lastOrNull()}", completed)
        assertEquals("image/png", completed!!.mimeType)
        assertEquals(payload.size.toLong(), completed.file.length())
        assertTrue("下载完成后不应残留 .part 临时文件", tempDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun chunkedUnknownContentLength_fallsBackToIndeterminateProgressThenCompletes() = runBlocking {
        val pngBytes = createPngBytes(width = 64, height = 64)
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/png")
                .chunkedBody(Buffer().write(pngBytes), maxChunkSize = 512)
                .build(),
        )

        val downloader = OkHttpOriginalImageDownloader(
            callFactory = OkHttpClient(),
            cacheDir = tempDir,
        )
        val states = downloader.download(server.url("/chunked.png").toString()).toList()
        val downloading = states.filterIsInstance<OriginalDownloadState.Downloading>()
        assertTrue("未知长度必须至少发出一次 Downloading 态", downloading.isNotEmpty())
        downloading.forEach { state ->
            assertNull("未知 Content-Length 应回退 totalBytes == null", state.totalBytes)
            assertNull("未知 Content-Length 应回退不确定进度 progress == null", state.progress)
        }
        val completed = states.last() as? OriginalDownloadState.Completed
        assertNotNull(completed)
        assertEquals(pngBytes.size.toLong(), completed!!.file.length())
    }

    @Test
    fun cancellingFlow_cancelsUnderlyingHttpCallAndCleansUpTempFile() = runBlocking {
        val largePayload = ByteArray(160 * 1024) { 0x5A }
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/jpeg")
                .body(Buffer().write(largePayload))
                .throttleBody(bytesPerPeriod = 4096L, period = 120L, unit = TimeUnit.MILLISECONDS)
                .build(),
        )

        val baseClient = OkHttpClient()
        val capturedCall = AtomicReference<Call?>(null)
        val trackingFactory = Call.Factory { req: Request ->
            baseClient.newCall(req).also { capturedCall.set(it) }
        }
        val downloader = OkHttpOriginalImageDownloader(
            callFactory = trackingFactory,
            cacheDir = tempDir,
        )

        var sawIntermediateProgress = false
        val job = launch {
            try {
                downloader.download(server.url("/slow_large.jpg").toString())
                    .takeWhile { state ->
                        if (state is OriginalDownloadState.Downloading && (state.progress ?: 0f) > 0f) {
                            sawIntermediateProgress = true
                            false // 收到首个进度块后立即停止收集，触发上游 Flow 取消
                        } else {
                            true
                        }
                    }
                    .collect {}
            } catch (_: CancellationException) {
                // expected
            }
        }
        job.join()

        assertTrue("取消前应已收到真实中间进度块", sawIntermediateProgress)
        val call = capturedCall.get()
        assertNotNull("应已创建底层 HTTP Call", call)
        assertTrue("取消下载后必须立即中止底层 HTTP Call (call.isCanceled())", call!!.isCanceled())
        assertTrue("取消后不得残留 .part 临时文件", tempDir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }
}
