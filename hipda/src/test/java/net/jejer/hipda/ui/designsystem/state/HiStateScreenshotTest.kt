package net.jejer.hipda.ui.designsystem.state

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiScreenshotTestSupport
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val SAMPLE_ERROR_DETAILS = """
    java.net.SocketTimeoutException: failed to connect to www.4d4y.com (port 443) after 15000ms
    at okhttp3.internal.connection.RealConnection.connectSocket(RealConnection.kt:297)
    GET /forum/forumdisplay.php?fid=2&page=1
""".trimIndent()

/**
 * 状态组件 Roborazzi 截图基线测试（前缀 `state_`）：
 * 覆盖 411dp 与 900dp 两种宽度 × 浅色与深色 × 5 个场景（空状态带按钮、错误状态收起、
 * 错误状态展开详情、骨架缩放为 0 的静止帧、列表尾部三种状态），共 20 张基线。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class HiStateScreenshotTest {

    // region 1. 空状态带按钮

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun state_empty_w411_light() = captureEmpty("state_empty_w411_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun state_empty_w411_dark() = captureEmpty("state_empty_w411_dark", HiThemeMode.DARK)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun state_empty_w900_light() = captureEmpty("state_empty_w900_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun state_empty_w900_dark() = captureEmpty("state_empty_w900_dark", HiThemeMode.DARK)

    // endregion

    // region 2. 错误状态收起

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun state_error_collapsed_w411_light() =
        captureError("state_error_collapsed_w411_light", HiThemeMode.LIGHT, expanded = false)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun state_error_collapsed_w411_dark() =
        captureError("state_error_collapsed_w411_dark", HiThemeMode.DARK, expanded = false)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun state_error_collapsed_w900_light() =
        captureError("state_error_collapsed_w900_light", HiThemeMode.LIGHT, expanded = false)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun state_error_collapsed_w900_dark() =
        captureError("state_error_collapsed_w900_dark", HiThemeMode.DARK, expanded = false)

    // endregion

    // region 3. 错误状态展开详情

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun state_error_expanded_w411_light() =
        captureError("state_error_expanded_w411_light", HiThemeMode.LIGHT, expanded = true)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun state_error_expanded_w411_dark() =
        captureError("state_error_expanded_w411_dark", HiThemeMode.DARK, expanded = true)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun state_error_expanded_w900_light() =
        captureError("state_error_expanded_w900_light", HiThemeMode.LIGHT, expanded = true)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun state_error_expanded_w900_dark() =
        captureError("state_error_expanded_w900_dark", HiThemeMode.DARK, expanded = true)

    // endregion

    // region 4. 骨架（缩放为 0 的静止帧）

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun state_skeleton_w411_light() = captureSkeleton("state_skeleton_w411_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun state_skeleton_w411_dark() = captureSkeleton("state_skeleton_w411_dark", HiThemeMode.DARK)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun state_skeleton_w900_light() = captureSkeleton("state_skeleton_w900_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun state_skeleton_w900_dark() = captureSkeleton("state_skeleton_w900_dark", HiThemeMode.DARK)

    // endregion

    // region 5. 列表尾部三种状态

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun state_list_footer_w411_light() = captureListFooter("state_list_footer_w411_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411)
    fun state_list_footer_w411_dark() = captureListFooter("state_list_footer_w411_dark", HiThemeMode.DARK)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun state_list_footer_w900_light() = captureListFooter("state_list_footer_w900_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = HiScreenshotTestSupport.Qualifiers.EXPANDED_900)
    fun state_list_footer_w900_dark() = captureListFooter("state_list_footer_w900_dark", HiThemeMode.DARK)

    // endregion

    private fun captureEmpty(name: String, themeMode: HiThemeMode) = withAnimatorDurationScale(0f) {
        setContent {
            StateScreenshotSurface(themeMode) {
                HiEmptyState(
                    message = "暂无帖子",
                    modifier = Modifier.fillMaxSize(),
                    action = HiStateAction(label = "刷新", onClick = {}),
                )
            }
        }
        waitForIdle()
        onRoot().captureHiSnapshot(name)
    }

    private fun captureError(name: String, themeMode: HiThemeMode, expanded: Boolean) =
        withAnimatorDurationScale(0f) {
            setContent {
                StateScreenshotSurface(themeMode) {
                    HiErrorState(
                        message = "网络连接失败",
                        modifier = Modifier.fillMaxSize(),
                        action = HiStateAction(label = "重试", onClick = {}),
                        details = SAMPLE_ERROR_DETAILS,
                    )
                }
            }
            waitForIdle()
            if (expanded) {
                onNodeWithText("查看详情").performClick()
                waitForIdle()
                onNodeWithText("收起详情").assertIsDisplayed()
                onNodeWithText("复制详情").assertIsDisplayed()
            }
            onRoot().captureHiSnapshot(name)
        }

    private fun captureSkeleton(name: String, themeMode: HiThemeMode) = withAnimatorDurationScale(0f) {
        setContent {
            StateScreenshotSurface(themeMode) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    repeat(5) { index ->
                        SkeletonThreadRowSpecimen(compactSubtitle = index % 2 == 1)
                    }
                }
            }
        }
        waitForIdle()
        onRoot().captureHiSnapshot(name)
    }

    private fun captureListFooter(name: String, themeMode: HiThemeMode) = withAnimatorDurationScale(0f) {
        setContent {
            StateScreenshotSurface(themeMode) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HiListFooter(state = HiFooterState.Loading)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    HiListFooter(state = HiFooterState.Failed(onRetry = {}))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    HiListFooter(state = HiFooterState.End)
                }
            }
        }
        waitForIdle()
        onRoot().captureHiSnapshot(name)
    }
}

@Composable
private fun StateScreenshotSurface(
    themeMode: HiThemeMode,
    content: @Composable () -> Unit,
) {
    HiTheme(
        themeMode = themeMode,
        seed = HiColorPresets.Brand.toSeed(),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}

@Composable
private fun SkeletonThreadRowSpecimen(compactSubtitle: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Spacer(
            modifier = Modifier
                .size(40.dp)
                .hiSkeleton(shape = HiShapeTokens.Full),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth(if (compactSubtitle) 0.72f else 0.88f)
                    .height(18.dp)
                    .hiSkeleton(shape = HiShapeTokens.Small),
            )
            HiSkeletonBlock(
                width = if (compactSubtitle) 160.dp else 220.dp,
                height = 14.dp,
                shape = HiShapeTokens.Small,
            )
            Spacer(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(12.dp)
                    .hiSkeleton(shape = HiShapeTokens.Small),
            )
        }
    }
}
