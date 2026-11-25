package net.jejer.hipda.debug.sampleexport

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import java.io.File
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiScreenshotTestSupport
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * debug 样本导出工具页 Roborazzi 截图基线（前缀 `sample_export_`，共 2 张）：
 * - 411dp 浅色核心界面：已登录 + R-B1a 建议 8 隐私提示 + 列表含成功 / 抓取中 / 失败 HiErrorState 单项重试
 * - 411dp 深色核心界面：同上
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class SampleExportScreenshotTest {

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun sample_export_w411_light() = captureSampleExport(
        name = "sample_export_w411_light",
        themeMode = HiThemeMode.LIGHT,
    )

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun sample_export_w411_dark() = captureSampleExport(
        name = "sample_export_w411_dark",
        themeMode = HiThemeMode.DARK,
    )

    private fun captureSampleExport(
        name: String,
        themeMode: HiThemeMode,
    ) = withAnimatorDurationScale(0f) {
        val sampleState = buildCorePreviewState()
        setContent {
            HiTheme(
                themeMode = themeMode,
                seed = HiColorPresets.Brand.toSeed(),
            ) {
                SampleExportScreen(
                    state = sampleState,
                    onBack = {},
                    onOpenLogin = {},
                    onSyncCookies = {},
                    onIncludePersonalHistoryChange = {},
                    onCustomTidChange = {},
                    onStartExport = {},
                    onShareZip = {},
                    onRetryItem = {},
                    onConsumeSnackbar = {},
                )
            }
        }
        waitForIdle()

        // Verify key UI contracts are displayed above the fold
        onNodeWithText("已登录").assertIsDisplayed()
        onNodeWithText("隐私提示").assertIsDisplayed()
        onNodeWithText("可在论坛反查发帖人", substring = true).assertIsDisplayed()
        onNodeWithText("成功").assertIsDisplayed()
        onNodeWithText("抓取中").assertIsDisplayed()
        onNodeWithText("网络连接失败").assertIsDisplayed()
        onNodeWithText("重试").assertIsDisplayed()

        // Verify Retry button inside HiErrorState meets >= 48dp touch target
        val minTouchPx = with(density) { 48.dp.toPx() } - 0.5f
        val retryNode = onNodeWithText("重试").fetchSemanticsNode()
        assertTrue("重试按钮高度应 >= 48dp", retryNode.touchBoundsInRoot.height >= minTouchPx)
        assertTrue("重试按钮宽度应 >= 48dp", retryNode.touchBoundsInRoot.width >= minTouchPx)

        onRoot().captureHiSnapshot(name)
    }

    private fun buildCorePreviewState(): SampleExportUiState {
        val items = SampleExportManifest.entries.mapIndexed { idx, spec ->
            val status = when (idx) {
                0 -> SampleExportStatus.Success(spec.relativePath, byteSize = 48_320)
                1 -> SampleExportStatus.Fetching
                2 -> SampleExportStatus.Failed(
                    reason = "网络连接失败",
                    detail = "GET /forum/forumdisplay.php?fid=2&page=1&orderby=dateline -> HTTP 502",
                )
                7 -> SampleExportStatus.Skipped(reason = "无短消息对话，已跳过")
                else -> SampleExportStatus.Pending
            }
            SampleExportItemState(spec = spec, status = status)
        }
        return SampleExportUiState(
            isLoggedIn = true,
            includePersonalHistory = true,
            customTidInput = "",
            isRunning = false,
            items = items,
            exportedZipFile = File("hipda-samples-20261125-142000.zip"),
            resolvedTid = "88421",
        )
    }
}
