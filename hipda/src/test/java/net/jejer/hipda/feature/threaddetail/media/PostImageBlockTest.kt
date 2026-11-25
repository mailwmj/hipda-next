package net.jejer.hipda.feature.threaddetail.media

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.asImage
import coil3.decode.DataSource
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Size
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 覆盖 E2b 验收项 2、3：
 * - 图片 URL `http`/`https` 白名单全反例（0 请求、0 看图回调、静态安全失败占位）；
 * - 按容器显示像素尺寸解码（严禁无界原图解码）与缩略图优先/原图看图坐标上报；
 * - 流量节省 + 计费网络点击加载（带大小与未知大小）、加载中点击取消协程、失败重试（≥ 48dp）及减动效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h2400dp-xhdpi")
class PostImageBlockTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var savedNetworkState: SavedTestNetworkState

    @Before
    fun setUp() {
        ImageAspectRatioCache.Default.clear()
        context = ApplicationProvider.getApplicationContext()
        savedNetworkState = captureTestNetworkState(context)
        configureTestNetworkState(
            context,
            metered = false,
            restrictStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
        )
    }

    @After
    fun tearDown() {
        restoreTestNetworkState(context, savedNetworkState)
        ImageAspectRatioCache.Default.clear()
    }

    @Test
    fun dangerousSchemes_neverInvokeImageLoaderOrViewerAndRenderStaticFailure() {
        val dangerousUrls = listOf(
            "file:///etc/hosts",
            "FILE:///etc/hosts",
            " file:///etc/hosts",
            "content://media/external/images/1",
            "CONTENT://media/external/images/1",
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "javascript:alert(1)\n",
            "intent://scan/#Intent;end",
            "INTENT://scan/#Intent;end",
            "data:image/png;base64,AAA",
            "DATA:image/png;base64,AAA",
            "mailto:a@b.com",
            "MAILTO:a@b.com",
            "//evil.example.invalid/a.jpg",
            "relative/path.jpg",
            "https://img.example.invalid/bad\u0000.jpg",
        )
        val loader = OfflineTestImageLoader.success()
        val opened = mutableListOf<ImageViewerLaunchArgs>()

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val listState = rememberLazyListState()
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((idx, badUrl) in dangerousUrls.withIndex()) {
                            PostImageBlock(
                                image = ContentBlock.Image(fullUrl = badUrl, thumbUrl = badUrl, fileSizeBytes = 1024L),
                                floorPostId = "sec_$idx",
                                listState = listState,
                                onOpenImageViewer = { opened += it },
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals("危险 scheme 图片严禁向 ImageLoader 发起任何请求", 0, loader.requestCount)
        assertEquals("全部危险图片应原地展示静态失败态", dangerousUrls.size, composeRule.onAllNodesWithText("图片加载失败").fetchSemanticsNodes().size)
        assertEquals("非法 URL 静态失败态不应展示重试按钮", 0, composeRule.onAllNodesWithText("重试").fetchSemanticsNodes().size)

        // 逐个点击静态失败占位，确认不会触发看图回调或 ImageLoader
        for (i in dangerousUrls.indices) {
            composeRule.onAllNodesWithText("图片加载失败")[i].performClick()
        }
        composeRule.waitForIdle()
        assertEquals("点击非法 URL 占位严禁触发 onOpenImageViewer", 0, opened.size)
        assertEquals("点击非法 URL 占位严禁触发 ImageLoader", 0, loader.requestCount)
    }

    @Test
    fun mixedValidAndDangerousUrls_blocksDangerousUrlInBothLoaderAndViewerExit() {
        val loader = OfflineTestImageLoader.success()
        val opened = mutableListOf<ImageViewerLaunchArgs>()

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val listState = rememberLazyListState()
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PostImageBlock(
                            image = ContentBlock.Image(
                                fullUrl = "https://img.example.invalid/safe_full.jpg",
                                thumbUrl = "file:///etc/hosts",
                                fileSizeBytes = 4096L,
                            ),
                            floorPostId = "mix_1",
                            listState = listState,
                            onOpenImageViewer = { opened += it },
                        )
                        PostImageBlock(
                            image = ContentBlock.Image(
                                fullUrl = "content://media/external/images/99",
                                thumbUrl = "http://img.example.invalid/safe_thumb.jpg",
                                fileSizeBytes = 4096L,
                            ),
                            floorPostId = "mix_2",
                            listState = listState,
                            onOpenImageViewer = { opened += it },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals(
            listOf(
                "https://img.example.invalid/safe_full.jpg",
                "http://img.example.invalid/safe_thumb.jpg",
            ),
            loader.requestedUrls,
        )

        val clickableImages = composeRule.onAllNodes(
            hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image),
        )
        clickableImages[0].performClick()
        clickableImages[1].performClick()
        composeRule.waitForIdle()

        assertEquals(2, opened.size)
        assertEquals(listOf("https://img.example.invalid/safe_full.jpg"), opened[0].urls)
        assertEquals(listOf("http://img.example.invalid/safe_thumb.jpg"), opened[1].urls)
    }

    @Test
    fun validHttpAndHttps_autoLoadByContainerDisplaySizeAndReportNonZeroBounds() {
        val loader = OfflineTestImageLoader.success(width = 800, height = 600)
        val opened = mutableListOf<ImageViewerLaunchArgs>()

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val listState = rememberLazyListState()
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PostImageBlock(
                            image = ContentBlock.Image(
                                fullUrl = "https://img.example.invalid/full_1.jpg",
                                thumbUrl = "https://img.example.invalid/thumb_1.jpg",
                                fileSizeBytes = 64_000L,
                            ),
                            floorPostId = "ok_1",
                            listState = listState,
                            onOpenImageViewer = { opened += it },
                        )
                        PostImageBlock(
                            image = ContentBlock.Image(
                                fullUrl = "http://img.example.invalid/http_only.jpg",
                                thumbUrl = null,
                                fileSizeBytes = 32_000L,
                            ),
                            floorPostId = "ok_2",
                            listState = listState,
                            onOpenImageViewer = { opened += it },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals(
            listOf(
                "https://img.example.invalid/thumb_1.jpg",
                "http://img.example.invalid/http_only.jpg",
            ),
            loader.requestedUrls,
        )

        // 验证按容器像素显示尺寸请求解码（严禁无界 Size.ORIGINAL / Dimension.Undefined）
        for (req in loader.requests) {
            val resolvedSize: Size = runBlocking { req.sizeResolver.size() }
            val widthDim = resolvedSize.width
            val heightDim = resolvedSize.height
            assertTrue("宽度必须为有界像素 Dimension.Pixels，实际：$widthDim", widthDim is Dimension.Pixels && widthDim.px > 0)
            assertTrue("高度必须为有界像素 Dimension.Pixels，实际：$heightDim", heightDim is Dimension.Pixels && heightDim.px > 0)
            assertTrue("宽度应与容器显示像素匹配（411dp-32dp @ xhdpi ≈ 758px），实际：${(widthDim as Dimension.Pixels).px}", widthDim.px in 700..820)
        }

        val clickableImages = composeRule.onAllNodes(
            hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image),
        )
        clickableImages[0].assertIsDisplayed().performClick()
        clickableImages[1].assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(2, opened.size)
        assertEquals(listOf("https://img.example.invalid/full_1.jpg"), opened[0].urls)
        assertEquals(listOf("http://img.example.invalid/http_only.jpg"), opened[1].urls)
        for (args in opened) {
            val bounds = args.sourceBoundsInWindow
            assertNotNull("点击已加载图片必须上报非空 sourceBoundsInWindow", bounds)
            assertTrue("sourceBoundsInWindow 宽必须 > 0，实际：$bounds", bounds!!.width > 0f)
            assertTrue("sourceBoundsInWindow 高必须 > 0，实际：$bounds", bounds.height > 0f)
        }
    }

    @Test
    fun dataSaverAndMetered_showsSizeAndUnknownPlaceholders_loadsAndCancelsOnSecondTap() {
        configureTestNetworkState(
            context,
            metered = true,
            restrictStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
        )
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        var attempt = 0
        val loader = OfflineTestImageLoader { request ->
            val currentAttempt = ++attempt
            if (currentAttempt == 1) {
                firstGate.await()
            } else {
                secondGate.await()
            }
            SuccessResult(
                image = createSyntheticBitmap(400, 300).asImage(),
                request = request,
                dataSource = DataSource.NETWORK,
            )
        }
        val opened = mutableListOf<ImageViewerLaunchArgs>()

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val listState = rememberLazyListState()
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PostImageBlock(
                            image = ContentBlock.Image(
                                fullUrl = "https://img.example.invalid/metered_sized.jpg",
                                thumbUrl = null,
                                fileSizeBytes = 256_000L,
                            ),
                            floorPostId = "saver_1",
                            listState = listState,
                            onOpenImageViewer = { opened += it },
                        )
                        PostImageBlock(
                            image = ContentBlock.Image(
                                fullUrl = "https://img.example.invalid/metered_unknown.jpg",
                                thumbUrl = null,
                                fileSizeBytes = 0L,
                            ),
                            floorPostId = "saver_2",
                            listState = listState,
                            onOpenImageViewer = { opened += it },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 流量节省 + 计费网络下默认不发请求，展示带大小与未知大小两种文案
        assertEquals("省流模式未点击前不得发起加载请求", 0, loader.requestCount)
        composeRule.onNodeWithText("点击加载图片（250 KB）").assertIsDisplayed()
        composeRule.onNodeWithText("点击加载图片").assertIsDisplayed()

        // 2. 点击进入加载中，展示“正在加载图片，点击取消”进度圈
        composeRule.onNodeWithText("点击加载图片（250 KB）").performClick()
        composeRule.waitForIdle()
        assertEquals(1, loader.requestCount)
        composeRule.onNodeWithContentDescription("正在加载图片，点击取消")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        // 3. 加载中再次点击：立即取消协程/Coil 请求并回到点击加载占位
        composeRule.onNodeWithContentDescription("正在加载图片，点击取消").performClick()
        composeRule.waitForIdle()
        assertEquals("点击取消必须中断在途 ImageLoader 协程", 1, loader.cancelledCount.get())
        composeRule.onNodeWithText("点击加载图片（250 KB）").assertIsDisplayed()

        // 4. 再次点击并放行结果：成功加载后可点击打开看图
        composeRule.onNodeWithText("点击加载图片（250 KB）").performClick()
        composeRule.waitForIdle()
        assertEquals(2, loader.requestCount)
        secondGate.complete(Unit)
        composeRule.waitForIdle()

        composeRule.onNode(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(1, opened.size)
        assertEquals(listOf("https://img.example.invalid/metered_sized.jpg"), opened.single().urls)
        assertNotNull(opened.single().sourceBoundsInWindow)
    }

    @Test
    fun meteredWithoutDataSaver_autoLoadsImmediately() {
        configureTestNetworkState(
            context,
            metered = true,
            restrictStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
        )
        val loader = OfflineTestImageLoader.success()
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val listState = rememberLazyListState()
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        PostImageBlock(
                            image = ContentBlock.Image("https://img.example.invalid/auto_a.jpg", null, 4096L),
                            floorPostId = "auto_a",
                            listState = listState,
                            onOpenImageViewer = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf("https://img.example.invalid/auto_a.jpg"), loader.requestedUrls)
        composeRule.onNodeWithText("点击加载图片（4 KB）").assertDoesNotExist()
    }

    @Test
    fun dataSaverOnUnmetered_autoLoadsImmediately() {
        configureTestNetworkState(
            context,
            metered = false,
            restrictStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
        )
        val loader = OfflineTestImageLoader.success()
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val listState = rememberLazyListState()
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        PostImageBlock(
                            image = ContentBlock.Image("https://img.example.invalid/auto_b.jpg", null, 8192L),
                            floorPostId = "auto_b",
                            listState = listState,
                            onOpenImageViewer = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf("https://img.example.invalid/auto_b.jpg"), loader.requestedUrls)
        composeRule.onNodeWithText("点击加载图片（8 KB）").assertDoesNotExist()
    }

    @Test
    fun loadFailure_showsErrorAnd48dpRetryButton_andRecoversOnRetryClick() {
        val shouldFail = AtomicBoolean(true)
        val loader = OfflineTestImageLoader { request ->
            if (shouldFail.get()) {
                ErrorResult(image = null, request = request, throwable = IllegalStateException("offline failure"))
            } else {
                SuccessResult(
                    image = createSyntheticBitmap(400, 300).asImage(),
                    request = request,
                    dataSource = DataSource.NETWORK,
                )
            }
        }
        val opened = mutableListOf<ImageViewerLaunchArgs>()

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val listState = rememberLazyListState()
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        PostImageBlock(
                            image = ContentBlock.Image("https://img.example.invalid/flaky.jpg", null, 12_000L),
                            floorPostId = "retry_1",
                            listState = listState,
                            onOpenImageViewer = { opened += it },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals(1, loader.requestCount)
        composeRule.onNodeWithText("图片加载失败").assertIsDisplayed()
        composeRule.onNodeWithText("重试")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        shouldFail.set(false)
        composeRule.onNodeWithText("重试").performClick()
        composeRule.waitForIdle()

        assertEquals(2, loader.requestCount)
        composeRule.onNodeWithText("图片加载失败").assertDoesNotExist()
        composeRule.onNode(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, opened.size)
        assertNotNull(opened.single().sourceBoundsInWindow)
    }

    @Test
    fun reducedMotion_loadedImageAppearsImmediatelyWithoutStuckTransition() = withAnimatorDurationScale(0f) {
        val loader = OfflineTestImageLoader.success(width = 400, height = 800)
        setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed()) {
                    val listState = rememberLazyListState()
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        PostImageBlock(
                            image = ContentBlock.Image("https://img.example.invalid/reduced.jpg", null, 8192L),
                            floorPostId = "rm_1",
                            listState = listState,
                            onOpenImageViewer = {},
                        )
                    }
                }
            }
        }
        waitForIdle()
        assertEquals(1, loader.requestCount)
        onNode(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
            .assertIsDisplayed()
    }
}
