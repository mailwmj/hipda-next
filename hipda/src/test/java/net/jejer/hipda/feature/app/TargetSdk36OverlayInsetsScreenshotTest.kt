package net.jejer.hipda.feature.app

import android.app.Application
import android.graphics.Rect
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.ImageViewerLaunchArgs
import net.jejer.hipda.feature.common.LocalHiImageLoader
import net.jejer.hipda.feature.imageviewer.ImageViewerMediaActions
import net.jejer.hipda.feature.imageviewer.ImageViewerOverlay
import net.jejer.hipda.feature.imageviewer.OriginalDownloadState
import net.jejer.hipda.feature.imageviewer.OriginalImageDownloader
import net.jejer.hipda.feature.imageviewer.createViewerPhotoBitmap
import net.jejer.hipda.feature.login.LoginScreenContent
import net.jejer.hipda.feature.login.LoginUiState
import net.jejer.hipda.feature.settings.AccountManagementContent
import net.jejer.hipda.feature.settings.AccountManagementUiState
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M7-3 修复轮 1（R-M7-3 B2）壳外全屏浮层安全区 Roborazzi 截图基线（共 5 张，前缀 `m7_3_`，不影响任何既有快照）：
 * - `m7_3_image_viewer_landscape_left_nav_dark`：手机横屏 891×411 左侧 48dp 三键导航 + 40dp 挖孔（关闭钮与 GIF 标避让、图片满屏）
 * - `m7_3_image_viewer_landscape_right_nav_dark`：手机横屏 891×411 右侧 48dp 三键导航（页码胶囊 `1 / 3` 与底栏避让）
 * - `m7_3_login_portrait_deep_cutout_light`：手机竖屏 411×891 顶部 44dp 深挖孔 + 48dp 三键导航
 * - `m7_3_login_landscape_cutout_nav_light`：`< 480dp` 手机横屏 891×411 左 44dp 挖孔 + 右 48dp 三键导航
 * - `m7_3_account_landscape_cutout_nav_dark`：`< 480dp` 手机横屏 891×411 账号管理页左 44dp 挖孔 + 右 48dp 三键导航
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class TargetSdk36OverlayInsetsScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val staticScale = object : MotionDurationScale {
        override val scaleFactor: Float = 0f
    }

    private val sampleAccounts = listOf(
        SavedAccount("1001", "hipda_veteran", "0123456789abcdef0123456789abcdef", lastLoginAt = 2_000L),
        SavedAccount("1002", "geek_alt", "fedcba9876543210fedcba9876543210", lastLoginAt = 1_000L),
    )

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun m7_3_image_viewer_landscape_left_nav_dark() {
        var hostView: View? = null
        val loader = staticViewerImageLoader()
        composeRule.setContent {
            hostView = LocalView.current
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(
                            urls = listOf("https://img.example.invalid/demo.gif", "https://img.example.invalid/2.jpg", "https://img.example.invalid/3.jpg"),
                            floorNumber = 3,
                            author = "linus_hi",
                        ),
                        onDismiss = {},
                        originalDownloader = NoOpOriginalDownloader,
                        mediaActions = NoOpViewerMediaActions,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        dispatchInsets(requireNotNull(hostView), statusTopDp = 24, navLeftDp = 48, cutoutLeftDp = 40)
        composeRule.waitForIdle()
        composeRule.onRoot().captureHiSnapshot("m7_3_image_viewer_landscape_left_nav_dark")
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun m7_3_image_viewer_landscape_right_nav_dark() {
        var hostView: View? = null
        val loader = staticViewerImageLoader()
        composeRule.setContent {
            hostView = LocalView.current
            CompositionLocalProvider(LocalHiImageLoader provides loader) {
                HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                    ImageViewerOverlay(
                        args = ImageViewerLaunchArgs(
                            urls = listOf("https://img.example.invalid/1.jpg", "https://img.example.invalid/2.jpg", "https://img.example.invalid/3.jpg"),
                            floorNumber = 3,
                            author = "linus_hi",
                        ),
                        onDismiss = {},
                        originalDownloader = NoOpOriginalDownloader,
                        mediaActions = NoOpViewerMediaActions,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        dispatchInsets(requireNotNull(hostView), statusTopDp = 24, navRightDp = 48, cutoutLeftDp = 40)
        composeRule.waitForIdle()
        composeRule.onRoot().captureHiSnapshot("m7_3_image_viewer_landscape_right_nav_dark")
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun m7_3_login_portrait_deep_cutout_light() {
        var hostView: View? = null
        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                LoginScreenContent(
                    uiState = LoginUiState(username = "hipda_user"),
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSecurityQuestionSelected = {},
                    onSecurityAnswerChange = {},
                    onSubmit = {},
                    onDismiss = {},
                    onConsumeFocusRequest = {},
                )
            }
        }
        composeRule.waitForIdle()
        dispatchInsets(requireNotNull(hostView), statusTopDp = 24, navBottomDp = 48, cutoutTopDp = 44)
        composeRule.waitForIdle()
        composeRule.onRoot().captureHiSnapshot("m7_3_login_portrait_deep_cutout_light")
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun m7_3_login_landscape_cutout_nav_light() {
        var hostView: View? = null
        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(themeMode = HiThemeMode.LIGHT, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                LoginScreenContent(
                    uiState = LoginUiState(username = "hipda_user"),
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSecurityQuestionSelected = {},
                    onSecurityAnswerChange = {},
                    onSubmit = {},
                    onDismiss = {},
                    onConsumeFocusRequest = {},
                )
            }
        }
        composeRule.waitForIdle()
        dispatchInsets(requireNotNull(hostView), statusTopDp = 24, navRightDp = 48, cutoutLeftDp = 44)
        composeRule.waitForIdle()
        composeRule.onRoot().captureHiSnapshot("m7_3_login_landscape_cutout_nav_light")
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun m7_3_account_landscape_cutout_nav_dark() {
        var hostView: View? = null
        composeRule.setContent {
            hostView = LocalView.current
            HiTheme(themeMode = HiThemeMode.DARK, seed = HiColorPresets.Brand.toSeed(), motionDurationScale = staticScale) {
                AccountManagementContent(
                    uiState = AccountManagementUiState(currentAccount = sampleAccounts.first(), savedAccounts = sampleAccounts),
                    onSwitchAccount = {},
                    onAddAccount = {},
                    onLogout = {},
                    onClearCredentials = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
        dispatchInsets(requireNotNull(hostView), statusTopDp = 24, navRightDp = 48, cutoutLeftDp = 44)
        composeRule.waitForIdle()
        composeRule.onRoot().captureHiSnapshot("m7_3_account_landscape_cutout_nav_dark")
    }

    private fun dispatchInsets(
        view: View,
        statusTopDp: Int = 0,
        navLeftDp: Int = 0,
        navBottomDp: Int = 0,
        navRightDp: Int = 0,
        cutoutLeftDp: Int = 0,
        cutoutTopDp: Int = 0,
    ) {
        val density = composeRule.density
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val w = root.width.toInt()
        val h = root.height.toInt()
        val statusTopPx = with(density) { statusTopDp.dp.roundToPx() }
        val navLeftPx = with(density) { navLeftDp.dp.roundToPx() }
        val navBottomPx = with(density) { navBottomDp.dp.roundToPx() }
        val navRightPx = with(density) { navRightDp.dp.roundToPx() }
        val cutoutLeftPx = with(density) { cutoutLeftDp.dp.roundToPx() }
        val cutoutTopPx = with(density) { cutoutTopDp.dp.roundToPx() }
        val builder = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusTopPx, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(navLeftPx, 0, navRightPx, navBottomPx))
        if (cutoutLeftPx > 0 || cutoutTopPx > 0) {
            builder.setDisplayCutout(
                DisplayCutoutCompat(
                    Insets.of(cutoutLeftPx, cutoutTopPx, 0, 0),
                    if (cutoutLeftPx > 0) Rect(0, h / 3, cutoutLeftPx, h * 2 / 3) else null,
                    if (cutoutTopPx > 0) Rect(w / 3, 0, w * 2 / 3, cutoutTopPx) else null,
                    null,
                    null,
                    Insets.NONE,
                ),
            )
        }
        val insets = builder.build()
        composeRule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, insets) }
    }

    private fun staticViewerImageLoader(): ImageLoader = object : ImageLoader {
        override val defaults = ImageRequest.Defaults.DEFAULT
        override val components = ComponentRegistry()
        override val memoryCache: MemoryCache? = null
        override val diskCache: DiskCache? = null
        override fun enqueue(request: ImageRequest): Disposable {
            val result = SuccessResult(createViewerPhotoBitmap(width = 960, height = 540, accentColor = 0xFF2A9D8F.toInt()).asImage(), request, DataSource.MEMORY)
            request.target?.onSuccess(result.image)
            return object : Disposable {
                override val job: Deferred<ImageResult> = CompletableDeferred(result)
                override val isDisposed: Boolean = true
                override fun dispose() = Unit
            }
        }
        override suspend fun execute(request: ImageRequest): ImageResult =
            SuccessResult(createViewerPhotoBitmap(width = 960, height = 540, accentColor = 0xFF2A9D8F.toInt()).asImage(), request, DataSource.MEMORY)
        override fun newBuilder(): ImageLoader.Builder = error("unused")
        override fun shutdown() = Unit
    }

    private object NoOpOriginalDownloader : OriginalImageDownloader {
        override fun download(url: String): Flow<OriginalDownloadState> = emptyFlow()
    }

    private object NoOpViewerMediaActions : ImageViewerMediaActions {
        override suspend fun saveImage(url: String, cachedFile: File?): Boolean = true
        override suspend fun shareImage(url: String, cachedFile: File?): Boolean = true
    }
}
