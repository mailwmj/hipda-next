package net.jejer.hipda.feature.threaddetail.media

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.asImage
import coil3.decode.DataSource
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 帖子图片渲染 6 张 Roborazzi 截图基线（前缀 `thread_image_`，对齐 E2b 验收项 4）：
 * `411`/`749`/`1280dp` × 浅/深，同屏覆盖默认占位态、已加载态、失败重试态与省流点击加载态。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class PostImageScreenshotTest {

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
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun thread_image_w411_light() = captureGallery("thread_image_w411_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun thread_image_w411_dark() = captureGallery("thread_image_w411_dark", HiThemeMode.DARK)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun thread_image_w749_light() = captureGallery("thread_image_w749_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun thread_image_w749_dark() = captureGallery("thread_image_w749_dark", HiThemeMode.DARK)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun thread_image_w1280_light() = captureGallery("thread_image_w1280_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun thread_image_w1280_dark() = captureGallery("thread_image_w1280_dark", HiThemeMode.DARK)

    private fun captureGallery(name: String, themeMode: HiThemeMode) {
        val isDark = themeMode == HiThemeMode.DARK
        val holdPlaceholder = CompletableDeferred<Unit>()
        val loader = OfflineTestImageLoader { request ->
            when (request.data.toString()) {
                URL_LOADED -> SuccessResult(
                    image = createSyntheticBitmap(width = 400, height = 300, dark = isDark).asImage(),
                    request = request,
                    dataSource = DataSource.NETWORK,
                )
                URL_LOADING -> {
                    holdPlaceholder.await()
                    ErrorResult(null, request, IllegalStateException("unreachable"))
                }
                else -> ErrorResult(null, request, IllegalStateException("offline simulated error"))
            }
        }
        val staticScale = object : MotionDurationScale {
            override val scaleFactor: Float = 0f
        }
        var showDataSaverCard by mutableStateOf(false)

        composeRule.setContent {
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(
                    themeMode = themeMode,
                    seed = HiColorPresets.Brand.toSeed(),
                    motionDurationScale = staticScale,
                ) {
                    val listState = rememberLazyListState()
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ) {
                        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                            Column(
                                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    PostImageBlock(
                                        image = ContentBlock.Image(URL_LOADED, null, 128_000L),
                                        floorPostId = "shot_loaded",
                                        listState = listState,
                                        onOpenImageViewer = {},
                                        modifier = Modifier.weight(1f),
                                    )
                                    PostImageBlock(
                                        image = ContentBlock.Image(URL_LOADING, null, 64_000L),
                                        floorPostId = "shot_loading",
                                        listState = listState,
                                        onOpenImageViewer = {},
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    PostImageBlock(
                                        image = ContentBlock.Image(URL_FAILED, null, 96_000L),
                                        floorPostId = "shot_failed",
                                        listState = listState,
                                        onOpenImageViewer = {},
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (showDataSaverCard) {
                                        PostImageBlock(
                                            image = ContentBlock.Image(URL_DATA_SAVER, null, 256_000L),
                                            floorPostId = "shot_saver",
                                            listState = listState,
                                            onOpenImageViewer = {},
                                            modifier = Modifier.weight(1f),
                                        )
                                    } else {
                                        Box(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 切至真实系统流量节省 + 计费网络，使第 4 张图片通过生产 ConnectivityManager 路径自然渲染省流点击加载态
        configureTestNetworkState(
            context,
            metered = true,
            restrictStatus = ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
        )
        showDataSaverCard = true
        composeRule.waitForIdle()

        composeRule.onRoot().captureHiSnapshot(name)
    }

    private companion object {
        const val URL_LOADED = "https://img.example.invalid/screenshot_loaded.jpg"
        const val URL_LOADING = "https://img.example.invalid/screenshot_loading.jpg"
        const val URL_FAILED = "https://img.example.invalid/screenshot_failed.jpg"
        const val URL_DATA_SAVER = "https://img.example.invalid/screenshot_datasaver.jpg"
    }
}
