package net.jejer.hipda.feature.imageviewer

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Size as CoilSize
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.app.MultiPaneTestHarness
import net.jejer.hipda.feature.app.NextAppRoot
import net.jejer.hipda.feature.app.offlinePageMeta
import net.jejer.hipda.feature.common.ImageViewerBoundsRegistry
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.common.LocalImageViewerBoundsRegistry
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.feature.threaddetail.media.ImageAspectRatioCache
import net.jejer.hipda.feature.threaddetail.media.LocalThreadImageGallery
import net.jejer.hipda.feature.threaddetail.media.PostImageBlock
import net.jejer.hipda.feature.threaddetail.media.extractThreadGalleryItems
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M7-P1 验收测试套件（覆盖 A1 / A2 / A3 / A4 及同楼重复 URL、单图入口回归）：
 * - A1：`viewportW = 2560px` 直开 `1:6` 超长图时 HD 阶段请求宽高均 ≤ 4096；`12000×12000` 超大原图探针通过；
 * - A2：两楼各 2 张图组成图集，从 1 楼第 1 张横滑到 2 楼第 2 张时顶栏楼层与作者切换为 2 楼，划回恢复；
 * - A3：同一 URL 出现在 1 楼与 3 楼，从 3 楼进入并关闭时退场目标严格对齐 3 楼缩略图（非 1 楼）；
 * - A4：A2 与 A3 各有一条走 `NextAppRoot + ThreadDetailPane + ImageViewerRendererImpl` 真实全链路组合测试。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ImageViewerPolishTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        ImageAspectRatioCache.Default.clear()
        val cm = ApplicationProvider.getApplicationContext<Context>().getSystemService(ConnectivityManager::class.java)
        shadowOf(cm).setActiveNetworkInfo(null)
    }

    @After
    fun tearDown() {
        ImageAspectRatioCache.Default.clear()
    }

    // ==================== A1：HD 直开 4096px 硬上限 + 12000×12000 原有探针 ====================

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun a1_hdDirectOpenOn2560pxViewportWith1to6LongImage_capsRequestAndDecodeAt4096() {
        // 数学边界复用同一 computeOriginalDecodeBounds 与 MAX_ORIGINAL_DECODE_DIMENSION_PX = 4096
        assertEquals(
            IntSize(2560, 4096),
            computeOriginalDecodeBounds(2560, 1600, widthMultiplier = 1, heightMultiplier = 1, minHeightWidthMultiplier = 6),
        )
        assertEquals(
            IntSize(4096, 4096),
            computeOriginalDecodeBounds(5000, 5000, widthMultiplier = 1, heightMultiplier = 1, minHeightWidthMultiplier = 6),
        )

        val longPng = buildOversizedSolidPng(width = 1_000, height = 6_000)
        val rawBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
            BitmapFactory.decodeByteArray(longPng, 0, longPng.size, it)
        }
        assertEquals(1_000, rawBounds.outWidth)
        assertEquals(6_000, rawBounds.outHeight)

        val server = MockWebServer()
        server.start()
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val realCoil = ImageLoader.Builder(appContext)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { OkHttpClient() })) }
            .build()
        val recorded = CopyOnWriteArrayList<RecordedCoilExecution>()
        val spyCoil = SpyRealCoilImageLoader(realCoil, recorded)
        try {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "image/png")
                    .addHeader("Content-Length", longPng.size.toString())
                    .body(Buffer().write(longPng))
                    .build(),
            )
            val longUrl = server.url("/long_1to6_1000x6000.png").toString()

            composeRule.setContent {
                CompositionLocalProvider(LocalHiImageLoader provides spyCoil) {
                    HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                        ImageViewerOverlay(
                            args = ImageViewerLaunchArgs(urls = listOf(longUrl)),
                            onDismiss = {},
                            originalDownloader = FakeOriginalImageDownloader(),
                            mediaActions = FakeImageViewerMediaActions(),
                        )
                    }
                }
            }

            composeRule.waitUntil(10_000L) {
                recorded.any { it.data == longUrl && it.decodedWidth > 0 }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("1 / 1").assertIsDisplayed()

            val hdExec = recorded.last { it.data == longUrl }
            assertFalse("HD 直开严禁使用 Size.ORIGINAL", hdExec.resolvedSize == CoilSize.ORIGINAL)
            val reqW = (hdExec.resolvedSize.width as? Dimension.Pixels)?.px ?: error("HD 宽度非像素值: ${hdExec.resolvedSize}")
            val reqH = (hdExec.resolvedSize.height as? Dimension.Pixels)?.px ?: error("HD 高度非像素值: ${hdExec.resolvedSize}")
            assertEquals("w1280dp@xhdpi 视口宽应为 2560px", 2560, reqW)
            assertTrue("HD 阶段请求宽必须 ≤ 4096，实际=$reqW", reqW <= ImageViewerMotionSpec.MAX_ORIGINAL_DECODE_DIMENSION_PX)
            assertTrue("HD 阶段请求高必须 ≤ 4096（1:6 不得放任到 15360px），实际=$reqH", reqH <= ImageViewerMotionSpec.MAX_ORIGINAL_DECODE_DIMENSION_PX)
            assertTrue(
                "真实 Coil 解码后的 1:6 超长图双轴必须 ≤ 4096，实际=${hdExec.decodedWidth}x${hdExec.decodedHeight}",
                hdExec.decodedWidth in 1..ImageViewerMotionSpec.MAX_ORIGINAL_DECODE_DIMENSION_PX &&
                    hdExec.decodedHeight in 1..ImageViewerMotionSpec.MAX_ORIGINAL_DECODE_DIMENSION_PX,
            )
            composeRule.onRoot().captureToImage()
        } finally {
            realCoil.shutdown()
            server.close()
        }
    }

    @Test
    fun a1_oversized12000x12000Probe_hdAndOriginalDecodeWithinBoundsWithoutCrash() {
        val hugePng = buildOversizedSolidPng(12_000, 12_000)
        val rawBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
            BitmapFactory.decodeByteArray(hugePng, 0, hugePng.size, it)
        }
        assertEquals(12_000, rawBounds.outWidth)
        assertEquals(12_000, rawBounds.outHeight)

        val server = MockWebServer()
        server.start()
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val realCoil = ImageLoader.Builder(appContext)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { OkHttpClient() })) }
            .build()
        val recorded = CopyOnWriteArrayList<RecordedCoilExecution>()
        val spyCoil = SpyRealCoilImageLoader(realCoil, recorded)
        try {
            repeat(2) {
                server.enqueue(
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "image/png")
                        .addHeader("Content-Length", hugePng.size.toString())
                        .body(Buffer().write(hugePng))
                        .build(),
                )
            }
            val cacheDir = File(appContext.cacheDir, "m7p1_probe_12000").apply { deleteRecursively() }
            val downloader = OkHttpOriginalImageDownloader(OkHttpClient(), cacheDir, Dispatchers.IO)
            val url12k = server.url("/huge_12000x12000.png").toString()

            composeRule.setContent {
                CompositionLocalProvider(LocalHiImageLoader provides spyCoil) {
                    HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                        ImageViewerOverlay(
                            args = ImageViewerLaunchArgs(listOf(url12k)),
                            onDismiss = {},
                            originalDownloader = downloader,
                            mediaActions = FakeImageViewerMediaActions(),
                        )
                    }
                }
            }

            composeRule.waitUntil(10_000L) {
                recorded.any { it.data == url12k && it.decodedWidth > 0 }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("1 / 1").assertIsDisplayed()

            val density = composeRule.density.density
            val viewportW = (411f * density).roundToInt() // 822px
            val viewportH = (891f * density).roundToInt() // 1782px
            val maxOrigBounds = computeOriginalDecodeBounds(viewportW, viewportH) // 1644 x 3564

            val hdExec = recorded.last { it.data == url12k }
            assertFalse("直开 12000×12000 绝不能使用 Size.ORIGINAL", hdExec.resolvedSize == CoilSize.ORIGINAL)
            val hdReqW = (hdExec.resolvedSize.width as? Dimension.Pixels)?.px ?: -1
            val hdReqH = (hdExec.resolvedSize.height as? Dimension.Pixels)?.px ?: -1
            assertTrue("HD 宽度上限必须受视口宽度约束($viewportW)，实际=$hdReqW", hdReqW in 1..viewportW)
            assertTrue("HD 高度上限必须 ≤ 4096，实际=$hdReqH", hdReqH in 1..ImageViewerMotionSpec.MAX_ORIGINAL_DECODE_DIMENSION_PX)
            assertTrue(
                "HD 解码结果必须远小于 12000 并受安全边界约束，实际=${hdExec.decodedWidth}x${hdExec.decodedHeight}",
                hdExec.decodedWidth in 1..maxOrigBounds.width &&
                    hdExec.decodedHeight in 1..maxOrigBounds.height &&
                    hdExec.decodedWidth < 12_000 && hdExec.decodedHeight < 12_000,
            )

            composeRule.onNodeWithText("查看原图").performClick()
            composeRule.waitUntil(10_000L) {
                composeRule.onAllNodesWithText("已加载原图").fetchSemanticsNodes().isNotEmpty() &&
                    recorded.any { it.data is File && it.decodedWidth > 0 }
            }
            composeRule.waitForIdle()

            val origExec = recorded.last { it.data is File }
            val origReqW = (origExec.resolvedSize.width as? Dimension.Pixels)?.px ?: -1
            val origReqH = (origExec.resolvedSize.height as? Dimension.Pixels)?.px ?: -1
            assertTrue("原图请求尺寸必须受 $maxOrigBounds 限制，实际=${origReqW}x${origReqH}", origReqW <= maxOrigBounds.width && origReqH <= maxOrigBounds.height)
            assertTrue(
                "原图真实解码尺寸必须受 $maxOrigBounds 限制，实际=${origExec.decodedWidth}x${origExec.decodedHeight}",
                origExec.decodedWidth in 1..maxOrigBounds.width && origExec.decodedHeight in 1..maxOrigBounds.height,
            )

            composeRule.onRoot().captureToImage()
            composeRule.onRoot().performTouchInput { doubleClick(center) }
            composeRule.waitForIdle()
            composeRule.onRoot().captureToImage()
            composeRule.onNodeWithText("已加载原图").assertIsDisplayed()
        } finally {
            realCoil.shutdown()
            server.close()
        }
    }

    // ==================== A2：跨楼层图集横滑切换顶栏楼层号与作者 ====================

    @Test
    fun a2_twoFloorsTwoImagesEach_swipingUpdatesTopBarFloorAndAuthorAndRestores() {
        val floors = listOf(
            floorItem(
                postId = "p1", floor = 1, author = "alice",
                urls = listOf("https://img.example.invalid/f1_1.jpg", "https://img.example.invalid/f1_2.jpg"),
            ),
            floorItem(
                postId = "p2", floor = 2, author = "bob",
                urls = listOf("https://img.example.invalid/f2_1.jpg", "https://img.example.invalid/f2_2.jpg"),
            ),
        )
        val loader = lightweightLoader()
        val renderer = ImageViewerRendererImpl(FakeOriginalImageDownloader(), FakeImageViewerMediaActions())
        var activeArgs by mutableStateOf<ImageViewerLaunchArgs?>(null)

        composeRule.setContent {
            val registry = remember { ImageViewerBoundsRegistry() }
            val gallery = remember(floors) { extractThreadGalleryItems(floors) }
            CompositionLocalProvider(
                LocalHiImageLoader provides loader,
                LocalImageViewerBoundsRegistry provides registry,
                LocalThreadImageGallery provides gallery,
            ) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize()) {
                        val listState = rememberLazyListState()
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            floors.forEach { item ->
                                item.floor.blocks.filterIsInstance<ContentBlock.Image>().forEach { img ->
                                    PostImageBlock(
                                        image = img,
                                        floorPostId = item.postId,
                                        listState = listState,
                                        onOpenImageViewer = { activeArgs = it },
                                        modifier = Modifier.fillMaxWidth(0.4f),
                                    )
                                }
                            }
                        }
                        activeArgs?.let { args ->
                            renderer.Overlay(args = args, onDismiss = { activeArgs = null })
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 点击 1 楼第 1 张（1 / 4）
        composeRule.onAllNodes(postThumbMatcher(), useUnmergedTree = true)[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 4").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 楼 · alice").assertIsDisplayed()

        // 1 楼第 2 张（2 / 4）-> 仍为 1 楼 · alice
        swipeHorizontally(fromFraction = 0.85f, toFraction = 0.15f)
        composeRule.onNodeWithText("2 / 4").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 楼 · alice").assertIsDisplayed()

        // 2 楼第 1 张（3 / 4）-> 切换为 2 楼 · bob
        swipeHorizontally(fromFraction = 0.85f, toFraction = 0.15f)
        composeRule.onNodeWithText("3 / 4").assertIsDisplayed()
        composeRule.onNodeWithText("第 2 楼 · bob").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 楼 · alice").assertDoesNotExist()

        // 2 楼第 2 张（4 / 4）-> 仍为 2 楼 · bob
        swipeHorizontally(fromFraction = 0.85f, toFraction = 0.15f)
        composeRule.onNodeWithText("4 / 4").assertIsDisplayed()
        composeRule.onNodeWithText("第 2 楼 · bob").assertIsDisplayed()

        // 划回 1 楼第 1 张（4 -> 3 -> 2 -> 1）-> 恢复 1 楼 · alice
        swipeHorizontally(fromFraction = 0.15f, toFraction = 0.85f)
        composeRule.onNodeWithText("3 / 4").assertIsDisplayed()
        composeRule.onNodeWithText("第 2 楼 · bob").assertIsDisplayed()

        swipeHorizontally(fromFraction = 0.15f, toFraction = 0.85f)
        composeRule.onNodeWithText("2 / 4").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 楼 · alice").assertIsDisplayed()
        composeRule.onNodeWithText("第 2 楼 · bob").assertDoesNotExist()

        swipeHorizontally(fromFraction = 0.15f, toFraction = 0.85f)
        composeRule.onNodeWithText("1 / 4").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 楼 · alice").assertIsDisplayed()
    }

    // ==================== A3：跨楼层重复 URL 退场飞回被点楼层缩略图 ====================

    @Test
    fun a3_sameUrlInFloor1AndFloor3_openFloor3AndCloseExitsToFloor3Bounds() {
        val dupUrl = "https://img.example.invalid/shared_across_floors.jpg"
        val floors = listOf(
            floorItem(postId = "p1", floor = 1, author = "alice", urls = listOf(dupUrl)),
            floorItem(postId = "p2", floor = 2, author = "bob", urls = emptyList()),
            floorItem(postId = "p3", floor = 3, author = "charlie", urls = listOf(dupUrl)),
        )
        val loader = lightweightLoader(width = 800, height = 300)
        val renderer = ImageViewerRendererImpl(FakeOriginalImageDownloader(), FakeImageViewerMediaActions())
        var activeArgs by mutableStateOf<ImageViewerLaunchArgs?>(null)

        composeRule.setContent {
            val registry = remember { ImageViewerBoundsRegistry() }
            val gallery = remember(floors) { extractThreadGalleryItems(floors) }
            CompositionLocalProvider(
                LocalHiImageLoader provides loader,
                LocalImageViewerBoundsRegistry provides registry,
                LocalThreadImageGallery provides gallery,
            ) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize()) {
                        val listState = rememberLazyListState()
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(120.dp)) {
                            PostImageBlock(
                                image = ContentBlock.Image(dupUrl, dupUrl, 1024L),
                                floorPostId = "p1",
                                listState = listState,
                                onOpenImageViewer = { activeArgs = it },
                            )
                            PostImageBlock(
                                image = ContentBlock.Image(dupUrl, dupUrl, 1024L),
                                floorPostId = "p3",
                                listState = listState,
                                onOpenImageViewer = { activeArgs = it },
                            )
                        }
                        activeArgs?.let { args ->
                            renderer.Overlay(args = args, onDismiss = { activeArgs = null })
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density.density
        val thumbs = composeRule.onAllNodes(postThumbMatcher(), useUnmergedTree = true)
        val floor1Bounds = thumbs[0].fetchSemanticsNode().boundsInWindow
        val floor3Bounds = thumbs[1].fetchSemanticsNode().boundsInWindow
        assertTrue(
            "3 楼缩略图应明显低于 1 楼缩略图：f1=$floor1Bounds, f3=$floor3Bounds",
            floor3Bounds.top - floor1Bounds.top > 100f * density,
        )

        // 从 3 楼进入
        thumbs[1].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 / 2").assertIsDisplayed()
        composeRule.onNodeWithText("第 3 楼 · charlie").assertIsDisplayed()

        // 直接关闭并逐帧跟踪退场末帧坐标
        val finalExitRect = dismissAndCaptureFinalRect("2 / 2")
        assertRectNear("同 URL 从 3 楼进入直接关闭必须飞回 3 楼缩略图（绝非 1 楼 $floor1Bounds）", floor3Bounds, finalExitRect, density)
        assertTrue(
            "退场终态中心必须远离 1 楼缩略图",
            abs(finalExitRect.top - floor1Bounds.top) > 100f * density,
        )
    }

    @Test
    fun a3_sameUrlInFloor1AndFloor3_openFloor1AndCloseExitsToFloor1Bounds() {
        val dupUrl = "https://img.example.invalid/shared_across_floors_f1.jpg"
        val floors = listOf(
            floorItem(postId = "p1", floor = 1, author = "alice", urls = listOf(dupUrl)),
            floorItem(postId = "p2", floor = 2, author = "bob", urls = emptyList()),
            floorItem(postId = "p3", floor = 3, author = "charlie", urls = listOf(dupUrl)),
        )
        val loader = lightweightLoader(width = 800, height = 300)
        val renderer = ImageViewerRendererImpl(FakeOriginalImageDownloader(), FakeImageViewerMediaActions())
        var activeArgs by mutableStateOf<ImageViewerLaunchArgs?>(null)

        composeRule.setContent {
            val registry = remember { ImageViewerBoundsRegistry() }
            val gallery = remember(floors) { extractThreadGalleryItems(floors) }
            CompositionLocalProvider(
                LocalHiImageLoader provides loader,
                LocalImageViewerBoundsRegistry provides registry,
                LocalThreadImageGallery provides gallery,
            ) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize()) {
                        val listState = rememberLazyListState()
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(120.dp)) {
                            PostImageBlock(
                                image = ContentBlock.Image(dupUrl, dupUrl, 1024L),
                                floorPostId = "p1",
                                listState = listState,
                                onOpenImageViewer = { activeArgs = it },
                            )
                            PostImageBlock(
                                image = ContentBlock.Image(dupUrl, dupUrl, 1024L),
                                floorPostId = "p3",
                                listState = listState,
                                onOpenImageViewer = { activeArgs = it },
                            )
                        }
                        activeArgs?.let { args ->
                            renderer.Overlay(args = args, onDismiss = { activeArgs = null })
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density.density
        val thumbs = composeRule.onAllNodes(postThumbMatcher(), useUnmergedTree = true)
        val floor1Bounds = thumbs[0].fetchSemanticsNode().boundsInWindow
        val floor3Bounds = thumbs[1].fetchSemanticsNode().boundsInWindow
        assertTrue(
            "3 楼缩略图应明显低于 1 楼缩略图：f1=$floor1Bounds, f3=$floor3Bounds",
            floor3Bounds.top - floor1Bounds.top > 100f * density,
        )

        // 从 1 楼那张同 URL 图进入并关闭（锁死双向坐标键，防止后写覆盖漏检）
        thumbs[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 2").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 楼 · alice").assertIsDisplayed()

        val finalExitRect = dismissAndCaptureFinalRect("1 / 2")
        assertRectNear("同 URL 从 1 楼进入直接关闭必须飞回 1 楼缩略图（绝非 3 楼 $floor3Bounds）", floor1Bounds, finalExitRect, density)
        assertTrue(
            "退场终态中心必须远离 3 楼缩略图",
            abs(finalExitRect.top - floor3Bounds.top) > 100f * density,
        )
    }

    @Test
    fun a3_sameFloorDuplicateUrls_registerDistinctBoundsKeysAndExitToSecondThumbnail() {
        val dupUrl = "https://img.example.invalid/same_floor_dup.jpg"
        val floors = listOf(
            floorItem(postId = "p1", floor = 1, author = "alice", urls = listOf(dupUrl, dupUrl)),
        )
        val loader = lightweightLoader(width = 800, height = 300)
        val renderer = ImageViewerRendererImpl(FakeOriginalImageDownloader(), FakeImageViewerMediaActions())
        var activeArgs by mutableStateOf<ImageViewerLaunchArgs?>(null)

        composeRule.setContent {
            val registry = remember { ImageViewerBoundsRegistry() }
            val gallery = remember(floors) { extractThreadGalleryItems(floors) }
            CompositionLocalProvider(
                LocalHiImageLoader provides loader,
                LocalImageViewerBoundsRegistry provides registry,
                LocalThreadImageGallery provides gallery,
            ) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    Box(Modifier.fillMaxSize()) {
                        val listState = rememberLazyListState()
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(100.dp)) {
                            PostImageBlock(
                                image = ContentBlock.Image(dupUrl, dupUrl, 1024L),
                                floorPostId = "p1",
                                listState = listState,
                                onOpenImageViewer = { activeArgs = it },
                            )
                            PostImageBlock(
                                image = ContentBlock.Image(dupUrl, dupUrl, 1024L),
                                floorPostId = "p1",
                                listState = listState,
                                onOpenImageViewer = { activeArgs = it },
                            )
                        }
                        activeArgs?.let { args ->
                            renderer.Overlay(args = args, onDismiss = { activeArgs = null })
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density.density
        val thumbs = composeRule.onAllNodes(postThumbMatcher(), useUnmergedTree = true)
        val firstBounds = thumbs[0].fetchSemanticsNode().boundsInWindow
        val secondBounds = thumbs[1].fetchSemanticsNode().boundsInWindow
        assertTrue(secondBounds.top - firstBounds.top > 100f * density)

        thumbs[1].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 / 2").assertIsDisplayed()

        val finalExitRect = dismissAndCaptureFinalRect("2 / 2")
        assertRectNear("同楼层重复 URL 点击第 2 张必须飞回第 2 张缩略图", secondBounds, finalExitRect, density)

        composeRule.waitForIdle()
        thumbs[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 2").assertIsDisplayed()
        val firstExitRect = dismissAndCaptureFinalRect("1 / 2")
        assertRectNear("同楼层重复 URL 点击第 1 张必须飞回第 1 张缩略图", firstBounds, firstExitRect, density)
    }

    // ==================== A4：真实链路 NextAppRoot + ThreadDetailPane + ImageViewerRendererImpl ====================

    @Test
    fun a4_realNextAppRootAndThreadDetailPane_crossFloorSwipeUpdatesTopBarAndRestores() {
        val floors = listOf(
            floorItem(
                postId = "1001_p1", floor = 1, author = "楼主甲",
                urls = listOf("https://img.example.invalid/a4_f1_1.jpg", "https://img.example.invalid/a4_f1_2.jpg"),
            ),
            floorItem(
                postId = "1001_p2", floor = 2, author = "回复乙",
                urls = listOf("https://img.example.invalid/a4_f2_1.jpg", "https://img.example.invalid/a4_f2_2.jpg"),
            ),
        )
        val loader = lightweightLoader(width = 800, height = 300)
        val repo = StaticFloorsThreadDetailRepository(floors)
        val harness = MultiPaneTestHarness()
        val parentOwner = ownerWithThreadDetailRepo(harness, repo)
        val renderer = ImageViewerRendererImpl(FakeOriginalImageDownloader(), FakeImageViewerMediaActions())
        harness.hostViewModel.openThread(ThreadNavArgs("1001", "A4 跨楼层元数据真实链路帖", 2))

        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides parentOwner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread.asStateFlow(),
                    notifyUnreadCountFlow = harness.notifyUnread.asStateFlow(),
                    imageLoader = loader,
                    imageViewerRenderer = renderer,
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()

        // 点击 1 楼第 1 张图
        composeRule.onAllNodes(postThumbMatcher(), useUnmergedTree = true)[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 4").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 楼 · 楼主甲").assertIsDisplayed()

        // 横滑到 2 楼第 2 张（1/4 -> 2/4 -> 3/4 -> 4/4）
        swipeHorizontally(fromFraction = 0.85f, toFraction = 0.15f)
        composeRule.onNodeWithText("2 / 4").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 楼 · 楼主甲").assertIsDisplayed()

        swipeHorizontally(fromFraction = 0.85f, toFraction = 0.15f)
        composeRule.onNodeWithText("3 / 4").assertIsDisplayed()
        composeRule.onNodeWithText("第 2 楼 · 回复乙").assertIsDisplayed()

        swipeHorizontally(fromFraction = 0.85f, toFraction = 0.15f)
        composeRule.onNodeWithText("4 / 4").assertIsDisplayed()
        composeRule.onNodeWithText("第 2 楼 · 回复乙").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 楼 · 楼主甲").assertDoesNotExist()

        // 划回 1 楼（4/4 -> 3/4 -> 2/4 -> 1/4）恢复 1 楼楼层号与作者
        swipeHorizontally(fromFraction = 0.15f, toFraction = 0.85f)
        swipeHorizontally(fromFraction = 0.15f, toFraction = 0.85f)
        composeRule.onNodeWithText("2 / 4").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 楼 · 楼主甲").assertIsDisplayed()

        swipeHorizontally(fromFraction = 0.15f, toFraction = 0.85f)
        composeRule.onNodeWithText("1 / 4").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 楼 · 楼主甲").assertIsDisplayed()
        composeRule.onNodeWithText("第 2 楼 · 回复乙").assertDoesNotExist()
    }

    @Test
    fun a4_realNextAppRootAndThreadDetailPane_sameUrlInFloor1AndFloor3_exitsToFloor3Bounds() {
        val dupUrl = "https://img.example.invalid/a4_dup_f1_f3.jpg"
        val floors = listOf(
            floorItem(postId = "2002_p1", floor = 1, author = "一楼作者", urls = listOf(dupUrl)),
            floorItem(postId = "2002_p2", floor = 2, author = "二楼路人", urls = emptyList()),
            floorItem(postId = "2002_p3", floor = 3, author = "三楼作者", urls = listOf(dupUrl)),
        )
        val loader = lightweightLoader(width = 800, height = 260)
        val repo = StaticFloorsThreadDetailRepository(floors)
        val harness = MultiPaneTestHarness()
        val parentOwner = ownerWithThreadDetailRepo(harness, repo)
        val renderer = ImageViewerRendererImpl(FakeOriginalImageDownloader(), FakeImageViewerMediaActions())
        harness.hostViewModel.openThread(ThreadNavArgs("2002", "A4 重复 URL 坐标真实链路帖", 2))

        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides parentOwner) {
                NextAppRoot(
                    settingsFlow = harness.settingsRepo.settings,
                    currentAccountFlow = harness.authRepo.currentAccount,
                    smsUnreadCountFlow = harness.smsUnread.asStateFlow(),
                    notifyUnreadCountFlow = harness.notifyUnread.asStateFlow(),
                    imageLoader = loader,
                    imageViewerRenderer = renderer,
                    hostViewModel = harness.hostViewModel,
                    forumViewModel = harness.forumViewModel,
                )
            }
        }
        composeRule.waitForIdle()

        val density = composeRule.density.density
        val thumbs = composeRule.onAllNodes(postThumbMatcher(), useUnmergedTree = true)
        assertEquals("1 楼与 3 楼两张同 URL 缩略图应均加载可见", 2, thumbs.fetchSemanticsNodes().size)
        val floor1Bounds = thumbs[0].fetchSemanticsNode().boundsInWindow
        val floor3Bounds = thumbs[1].fetchSemanticsNode().boundsInWindow
        assertTrue(
            "3 楼缩略图应完整在视口内且明显低于 1 楼：f1=$floor1Bounds, f3=$floor3Bounds",
            floor3Bounds.top - floor1Bounds.top > 100f * density && floor3Bounds.bottom <= 891f * density,
        )

        // 点击 3 楼缩略图打开看图
        thumbs[1].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("2 / 2").assertIsDisplayed()
        composeRule.onNodeWithText("第 3 楼 · 三楼作者").assertIsDisplayed()

        // 直接关闭并验证退场目标对齐 3 楼缩略图（绝非 1 楼）
        val finalExitRect = dismissAndCaptureFinalRect("2 / 2")
        assertRectNear(
            "真实 NextAppRoot+ThreadDetailPane+看图浮层链路下，从 3 楼重复 URL 进入直接关闭必须飞回 3 楼缩略图（非 1 楼 $floor1Bounds）",
            floor3Bounds,
            finalExitRect,
            density,
        )
        assertTrue(
            "退场末帧不得偏向 1 楼缩略图",
            abs(finalExitRect.top - floor1Bounds.top) > 100f * density,
        )

        // 再从 1 楼缩略图进入并关闭，锁死真实全链路下双向重复 URL 坐标隔离
        composeRule.waitForIdle()
        thumbs[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 2").assertIsDisplayed()
        composeRule.onNodeWithText("第 1 楼 · 一楼作者").assertIsDisplayed()

        val floor1ExitRect = dismissAndCaptureFinalRect("1 / 2")
        assertRectNear(
            "真实 NextAppRoot+ThreadDetailPane+看图浮层链路下，从 1 楼重复 URL 进入直接关闭必须飞回 1 楼缩略图（非 3 楼 $floor3Bounds）",
            floor1Bounds,
            floor1ExitRect,
            density,
        )
        assertTrue(
            "1 楼退场末帧不得偏向 3 楼缩略图",
            abs(floor1ExitRect.top - floor3Bounds.top) > 100f * density,
        )
    }

    // ==================== 单图入口（非图集）行为不变回归 ====================

    @Test
    fun singleImageLaunchArgsWithoutPageMeta_preservesTopBarAndSourceBoundsExit() {
        val density = composeRule.density.density
        val thumbRect = Rect(48f * density, 160f * density, 248f * density, 310f * density)
        val renderer = ImageViewerRendererImpl(FakeOriginalImageDownloader(), FakeImageViewerMediaActions())
        var open by mutableStateOf(true)

        composeRule.setContent {
            val registry = remember { ImageViewerBoundsRegistry() }
            CompositionLocalProvider(
                LocalHiImageLoader provides lightweightLoader(800, 600),
                LocalImageViewerBoundsRegistry provides registry,
            ) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed()) {
                    if (open) {
                        renderer.Overlay(
                            args = ImageViewerLaunchArgs(
                                urls = listOf("https://img.example.invalid/solo.jpg"),
                                initialIndex = 0,
                                sourceBoundsInWindow = thumbRect,
                                floorNumber = 5,
                                author = "solo_author",
                            ),
                            onDismiss = { open = false },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 / 1").assertIsDisplayed()
        composeRule.onNodeWithText("第 5 楼 · solo_author").assertIsDisplayed()

        val finalExitRect = dismissAndCaptureFinalRect("1 / 1")
        assertRectNear("单图入口（无 pageMeta）关闭仍须飞回 sourceBoundsInWindow", thumbRect, finalExitRect, density)
    }

    // ==================== 辅助函数 ====================

    private fun lightweightLoader(width: Int = 800, height: Int = 600): ViewerTestImageLoader = ViewerTestImageLoader { req ->
        SuccessResult(ColorImage(0xFF336699.toInt(), width, height), req, DataSource.MEMORY)
    }

    private fun postThumbMatcher(): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and
            hasClickAction() and
            !hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "看图"))

    private fun viewerImageMatcher(desc: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and
            hasContentDescription(desc) and
            hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "看图"))

    private fun swipeHorizontally(fromFraction: Float, toFraction: Float) {
        composeRule.onRoot().performTouchInput {
            val startX = width * fromFraction
            val stepX = width * (toFraction - fromFraction) / 6f
            down(Offset(startX, height * 0.5f))
            repeat(6) { moveBy(Offset(stepX, 0f), delayMillis = 16L) }
            up()
        }
        composeRule.waitForIdle()
    }

    private fun dismissAndCaptureFinalRect(pageDesc: String): Rect {
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithContentDescription("关闭").performClick()
            var lastRect: Rect? = null
            for (f in 0..160) {
                val nodes = composeRule.onAllNodes(viewerImageMatcher(pageDesc), useUnmergedTree = true).fetchSemanticsNodes()
                if (nodes.isEmpty()) break
                lastRect = nodes.first().boundsInWindow
                composeRule.mainClock.advanceTimeByFrame()
            }
            return requireNotNull(lastRect) { "未采集到退场末帧几何边界 ($pageDesc)" }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun assertRectNear(msg: String, expected: Rect, actual: Rect, tolerancePx: Float) {
        assertTrue(
            "$msg: expected=$expected, actual=$actual, tol=${tolerancePx}px",
            abs(expected.left - actual.left) <= tolerancePx &&
                abs(expected.top - actual.top) <= tolerancePx &&
                abs(expected.right - actual.right) <= tolerancePx &&
                abs(expected.bottom - actual.bottom) <= tolerancePx,
        )
    }

    private fun floorItem(
        postId: String,
        floor: Int,
        author: String,
        urls: List<String>,
    ): ThreadDetailFloorItem = ThreadDetailFloorItem(
        floor = PostFloor(
            postId = postId,
            floor = floor,
            page = 1,
            uid = floor.toString(),
            author = author,
            avatarUrl = null,
            timePost = "2026-11-27 10:0$floor",
            isWarned = false,
            postStatus = null,
            signature = null,
            poll = null,
            blocks = buildList {
                add(ContentBlock.Text(listOf(InlineSpan.Plain("${floor}楼正文"))))
                urls.forEach { u -> add(ContentBlock.Image(fullUrl = u, thumbUrl = u, fileSizeBytes = 1024L)) }
            },
        ),
        isAuthorBlocked = false,
    )

    private fun ownerWithThreadDetailRepo(
        harness: MultiPaneTestHarness,
        repo: ThreadDetailRepository,
    ): ViewModelStoreOwner {
        val vmFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) -> ThreadDetailViewModel(repo, harness.gateway) as T
                else -> harness.vmFactory.create(modelClass)
            }
        }
        return object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore = ViewModelStore()
            override val defaultViewModelProviderFactory = vmFactory
        }
    }
}

private data class RecordedCoilExecution(
    val data: Any,
    val resolvedSize: CoilSize,
    val decodedWidth: Int,
    val decodedHeight: Int,
)

private class SpyRealCoilImageLoader(
    private val delegate: ImageLoader,
    private val recorded: CopyOnWriteArrayList<RecordedCoilExecution>,
) : ImageLoader {
    override val defaults: ImageRequest.Defaults get() = delegate.defaults
    override val components: ComponentRegistry get() = delegate.components
    override val memoryCache: MemoryCache? get() = delegate.memoryCache
    override val diskCache: DiskCache? get() = delegate.diskCache
    override fun enqueue(request: ImageRequest): Disposable = delegate.enqueue(request)
    override suspend fun execute(request: ImageRequest): ImageResult {
        val reqSize = request.sizeResolver.size()
        val result = delegate.execute(request)
        val success = result as? SuccessResult
        recorded.add(
            RecordedCoilExecution(
                data = request.data,
                resolvedSize = reqSize,
                decodedWidth = success?.image?.width ?: -1,
                decodedHeight = success?.image?.height ?: -1,
            ),
        )
        return result
    }
    override fun shutdown() = delegate.shutdown()
    override fun newBuilder(): ImageLoader.Builder = delegate.newBuilder()
}

private class StaticFloorsThreadDetailRepository(
    private val floorItems: List<ThreadDetailFloorItem>,
) : ThreadDetailRepository {
    override suspend fun loadPage(
        tid: String, page: Int, filter: ThreadDetailFilter, recordHistory: Boolean, forumName: String?, priority: AccessPriority,
    ): ThreadDetailLoadResult = ThreadDetailLoadResult.Success(
        page = ThreadDetailPage(
            tid = tid,
            fid = 2,
            title = "M7-P1 真实链路测试帖 $tid",
            page = page,
            lastPage = 1,
            floors = floorItems.map { it.floor },
            postsPerPageEstimate = floorItems.size,
            meta = offlinePageMeta(),
            hasPostList = true,
        ),
        filter = filter,
        forumName = forumName,
        threadAuthorId = floorItems.firstOrNull()?.floor?.uid ?: "1",
    )

    override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
        loadPage(tid, 1, ThreadDetailFilter.All, recordHistory, forumName, priority)
    override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority) =
        loadPage(tid ?: "1001", 1, ThreadDetailFilter.All, recordHistory, forumName, priority)
    override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
    override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
    override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> =
        floorsFlow.map { list -> list.map { ThreadDetailFloorItem(it, false) } }
    override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
    override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors.map { ThreadDetailFloorItem(it, false) }
    override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?) = floors
    override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
        throw UnsupportedOperationException()
}

/** 流式生成 1-bit Grayscale 合法大尺寸 PNG（低堆占用），用于验证超长图与 12000×12000 真实 Coil 解码。 */
private fun buildOversizedSolidPng(width: Int, height: Int): ByteArray {
    fun writeChunk(dos: DataOutputStream, type: String, payload: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        dos.writeInt(payload.size)
        dos.write(typeBytes)
        dos.write(payload)
        val crc = CRC32().apply {
            update(typeBytes)
            update(payload)
        }.value.toInt()
        dos.writeInt(crc)
    }

    val ihdr = ByteArrayOutputStream(13).apply {
        DataOutputStream(this).use { d ->
            d.writeInt(width)
            d.writeInt(height)
            d.writeByte(1) // bitDepth = 1
            d.writeByte(0) // colorType = 0 (Grayscale)
            d.writeByte(0)
            d.writeByte(0)
            d.writeByte(0)
        }
    }.toByteArray()

    val bytesPerRow = (width + 7) / 8
    val blackRow = ByteArray(1 + bytesPerRow)
    val whiteRow = ByteArray(1 + bytesPerRow) { idx -> if (idx == 0) 0 else 0xFF.toByte() }
    val idatBytes = ByteArrayOutputStream().use { rawOut ->
        DeflaterOutputStream(rawOut, Deflater(Deflater.BEST_SPEED)).use { def ->
            val half = height / 2
            for (y in 0 until height) {
                def.write(if (y < half) whiteRow else blackRow)
            }
        }
        rawOut.toByteArray()
    }

    return ByteArrayOutputStream().use { out ->
        DataOutputStream(out).use { dos ->
            dos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            writeChunk(dos, "IHDR", ihdr)
            writeChunk(dos, "IDAT", idatBytes)
            writeChunk(dos, "IEND", ByteArray(0))
        }
        out.toByteArray()
    }
}
