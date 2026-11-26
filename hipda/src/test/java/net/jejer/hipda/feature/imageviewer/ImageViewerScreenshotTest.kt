package net.jejer.hipda.feature.imageviewer

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import coil3.asImage
import coil3.decode.DataSource
import coil3.request.SuccessResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E14 验收项 5：11 张 Roborazzi 截图基线（前缀 `image_viewer_`）：
 * - `411`/`749`/`933`/`1280dp` × 浅/深（8 张，其中 `933dp` / `1280dp` 垫底三栏容器验证全屏覆盖全部三栏）；
 * - 长图贴宽顶对齐、GIF 角标态、原图确定进度态（3 张）；
 * - 全部截图断言 `assertGlassSurfaceCountAtMost(0)`（严格零 `HiGlassSurface`）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class ImageViewerScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val staticScale = object : MotionDurationScale {
        override val scaleFactor: Float = 0f
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun image_viewer_w411_light() = captureStandard("image_viewer_w411_light", HiThemeMode.LIGHT, threePaneHost = false)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun image_viewer_w411_dark() = captureStandard("image_viewer_w411_dark", HiThemeMode.DARK, threePaneHost = false)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun image_viewer_w749_light() = captureStandard("image_viewer_w749_light", HiThemeMode.LIGHT, threePaneHost = true)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun image_viewer_w749_dark() = captureStandard("image_viewer_w749_dark", HiThemeMode.DARK, threePaneHost = true)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun image_viewer_w933_light() = captureStandard("image_viewer_w933_light", HiThemeMode.LIGHT, threePaneHost = true)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun image_viewer_w933_dark() = captureStandard("image_viewer_w933_dark", HiThemeMode.DARK, threePaneHost = true)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun image_viewer_w1280_light() = captureStandard("image_viewer_w1280_light", HiThemeMode.LIGHT, threePaneHost = true)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun image_viewer_w1280_dark() = captureStandard("image_viewer_w1280_dark", HiThemeMode.DARK, threePaneHost = true)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun image_viewer_long_w411_dark() {
        val loader = ViewerTestImageLoader { request ->
            SuccessResult(
                image = createLongPosterBitmap(width = 400, height = 1600).asImage(),
                request = request,
                dataSource = DataSource.NETWORK,
            )
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(
                            urls = listOf("https://img.example.invalid/long_poster.png"),
                            floorNumber = 7,
                            author = "poster_author",
                        ),
                        onDismiss = {},
                        originalDownloader = FakeOriginalImageDownloader(),
                        mediaActions = FakeImageViewerMediaActions(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.assertGlassSurfaceCountAtMost(0)
        composeRule.onRoot().captureHiSnapshot("image_viewer_long_w411_dark")
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun image_viewer_gif_w411_dark() {
        val loader = ViewerTestImageLoader { request ->
            SuccessResult(
                image = createViewerPhotoBitmap(width = 640, height = 480, accentColor = 0xFF2A9D8F.toInt()).asImage(),
                request = request,
                dataSource = DataSource.NETWORK,
            )
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(
                            urls = listOf("https://img.example.invalid/demo.gif"),
                            floorNumber = 12,
                            author = "gif_fan",
                        ),
                        onDismiss = {},
                        originalDownloader = FakeOriginalImageDownloader(),
                        mediaActions = FakeImageViewerMediaActions(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.assertGlassSurfaceCountAtMost(0)
        composeRule.onRoot().captureHiSnapshot("image_viewer_gif_w411_dark")
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun image_viewer_original_progress_w411_dark() {
        val loader = ViewerTestImageLoader.solid(width = 800, height = 600)
        val progressFlow = MutableSharedFlow<OriginalDownloadState>(replay = 1)
        val downloader = FakeOriginalImageDownloader { progressFlow.asSharedFlow() }

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(
                            urls = listOf(
                                "https://img.example.invalid/orig_1.jpg",
                                "https://img.example.invalid/orig_2.jpg",
                            ),
                            floorNumber = 5,
                            author = "photo_master",
                        ),
                        onDismiss = {},
                        originalDownloader = downloader,
                        mediaActions = FakeImageViewerMediaActions(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("查看原图").performClick()
        composeRule.waitForIdle()
        progressFlow.tryEmit(OriginalDownloadState.Downloading(bytesRead = 620_000L, totalBytes = 1_000_000L, progress = 0.62f))
        composeRule.waitForIdle()

        composeRule.assertGlassSurfaceCountAtMost(0)
        composeRule.onRoot().captureHiSnapshot("image_viewer_original_progress_w411_dark")
    }

    private fun captureStandard(name: String, themeMode: HiThemeMode, threePaneHost: Boolean) {
        val loader = ViewerTestImageLoader.solid(width = 960, height = 640)
        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = themeMode, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                    Box(Modifier.fillMaxSize()) {
                        if (threePaneHost) {
                            ThreePaneUnderlayMock()
                        }
                        ImageViewerOverlay(
                            args = ImageViewerLaunchArgs(
                                urls = listOf(
                                    "https://img.example.invalid/shot_1.jpg",
                                    "https://img.example.invalid/shot_2.jpg",
                                    "https://img.example.invalid/shot_3.jpg",
                                ),
                                initialIndex = 0,
                                floorNumber = 3,
                                author = "linus_hi",
                            ),
                            onDismiss = {},
                            originalDownloader = FakeOriginalImageDownloader(),
                            mediaActions = FakeImageViewerMediaActions(),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.assertGlassSurfaceCountAtMost(0)
        composeRule.onRoot().captureHiSnapshot(name)
    }
}

/** 模拟宽屏三栏宿主背景（左栏/中栏/右栏），用以验证看图全屏浮层完整覆盖全部三栏零泄漏。 */
@Composable
private fun ThreePaneUnderlayMock() {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxSize().background(cs.surface)) {
        Column(Modifier.width(187.dp).fillMaxHeight().background(cs.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Discovery", color = cs.onSurface)
            Text("Geek Talks", color = cs.onSurfaceVariant)
        }
        VerticalDivider(color = cs.outlineVariant)
        Column(Modifier.width(298.dp).fillMaxHeight().background(cs.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("三栏中栏帖子列表", color = cs.onSurface)
        }
        VerticalDivider(color = cs.outlineVariant)
        Column(Modifier.weight(1f).fillMaxHeight().background(cs.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("三栏右栏帖子正文", color = cs.onSurface)
        }
    }
}
