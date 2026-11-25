package net.jejer.hipda.feature.app

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.flowOf
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.GlassEnvironmentState
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.shell.HiShellTestTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val QUALIFIER_HINGE_FALLBACK_701_841 = "w701dp-h841dp-xhdpi"

/**
 * M5 E3 多栏核心 Roborazzi 截图基线（前缀 `multipane_`，共 21 张）：
 * - 6 宽 × 浅/深真实列表 + 详情协同（12 张）：`411×891`、`559×749`、`640×1024`、`749×559`（Fold8 `64|274|411`）、`933×696`、`1280×800`；
 * - 特殊态（6 张）：`749×559` 240dp 浮层侧栏展开、`933×696` 左栏主动收起 64dp、`933×696` 全屏阅读态、
 *   `749×559` 空右栏占位、`933×696` 拖拽分隔线吸附均衡档、`411×891` 单栏二级页退场胶囊；
 * - `FoldingFeature` 铰链三态（3 张）：`749×559` 展平三栏、`749×559` 半折铰链双栏（左纯色顶栏 + 左紧凑胶囊 + 右详情）、`701×841` 铰链退单栏。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class MultiPaneScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ---- 6 宽 × 浅/深（12 张） ----

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun multipane_w411_h891_light() = captureMultiPane("multipane_w411_h891_light", ThemeMode.LIGHT, openThread = false)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun multipane_w411_h891_dark() = captureMultiPane("multipane_w411_h891_dark", ThemeMode.DARK, openThread = false)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_PORTRAIT_559_749)
    fun multipane_w559_h749_light() = captureMultiPane("multipane_w559_h749_light", ThemeMode.LIGHT, openThread = false)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_PORTRAIT_559_749)
    fun multipane_w559_h749_dark() = captureMultiPane("multipane_w559_h749_dark", ThemeMode.DARK, openThread = false)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.SIDEBAR_SINGLE_640_1024)
    fun multipane_w640_h1024_light() = captureMultiPane("multipane_w640_h1024_light", ThemeMode.LIGHT, openThread = false)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.SIDEBAR_SINGLE_640_1024)
    fun multipane_w640_h1024_dark() = captureMultiPane("multipane_w640_h1024_dark", ThemeMode.DARK, openThread = false)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun multipane_w749_h559_light() = captureMultiPane("multipane_w749_h559_light", ThemeMode.LIGHT, openThread = true)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun multipane_w749_h559_dark() = captureMultiPane("multipane_w749_h559_dark", ThemeMode.DARK, openThread = true)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun multipane_w933_h696_light() = captureMultiPane("multipane_w933_h696_light", ThemeMode.LIGHT, openThread = true)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun multipane_w933_h696_dark() = captureMultiPane("multipane_w933_h696_dark", ThemeMode.DARK, openThread = true)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun multipane_w1280_h800_light() = captureMultiPane("multipane_w1280_h800_light", ThemeMode.LIGHT, openThread = true)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun multipane_w1280_h800_dark() = captureMultiPane("multipane_w1280_h800_dark", ThemeMode.DARK, openThread = true)

    // ---- 特殊态：浮层展开 / 左栏收起 / 全屏阅读（3 张） ----

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun multipane_w749_h559_drawer_light() = captureMultiPane(
        name = "multipane_w749_h559_drawer_light",
        themeMode = ThemeMode.LIGHT,
        openThread = true,
        openModalDrawer = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun multipane_w933_h696_sidebar_collapsed_light() = captureMultiPane(
        name = "multipane_w933_h696_sidebar_collapsed_light",
        themeMode = ThemeMode.LIGHT,
        openThread = true,
        sidebarCollapsedLandscape = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun multipane_w933_h696_fullscreen_light() = captureMultiPane(
        name = "multipane_w933_h696_fullscreen_light",
        themeMode = ThemeMode.LIGHT,
        openThread = true,
        enterFullScreen = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun multipane_w749_h559_empty_detail_light() = captureMultiPane(
        name = "multipane_w749_h559_empty_detail_light",
        themeMode = ThemeMode.LIGHT,
        openThread = false,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun multipane_w933_h696_balanced_split_light() = captureMultiPane(
        name = "multipane_w933_h696_balanced_split_light",
        themeMode = ThemeMode.LIGHT,
        openThread = true,
        dragDividerToBalanced = true,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun multipane_w411_h891_detail_pill_hidden_light() = captureMultiPane(
        name = "multipane_w411_h891_detail_pill_hidden_light",
        themeMode = ThemeMode.LIGHT,
        openThread = true,
    )

    // ---- FoldingFeature 三态：展平三栏 / 铰链双栏 / 铰链退单栏（3 张） ----

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun multipane_hinge_flat_w749_h559_light() {
        val flat = verticalHingeLayoutInfo(371f, 378f, 559f, density = 2f, isSeparating = false)
        withWindowLayoutFlow(flowOf(flat)) {
            captureMultiPane("multipane_hinge_flat_w749_h559_light", ThemeMode.LIGHT, openThread = true)
        }
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun multipane_hinge_twopane_w749_h559_light() {
        val separating = verticalHingeLayoutInfo(371f, 378f, 559f, density = 2f, isSeparating = true)
        withWindowLayoutFlow(flowOf(separating)) {
            captureMultiPane("multipane_hinge_twopane_w749_h559_light", ThemeMode.LIGHT, openThread = true)
        }
    }

    @Test
    @Config(qualifiers = QUALIFIER_HINGE_FALLBACK_701_841)
    fun multipane_hinge_fallback_w701_h841_light() {
        val narrowSeparating = verticalHingeLayoutInfo(347f, 354f, 841f, density = 2f, isSeparating = true)
        withWindowLayoutFlow(flowOf(narrowSeparating)) {
            captureMultiPane("multipane_hinge_fallback_w701_h841_light", ThemeMode.LIGHT, openThread = false)
        }
    }

    private fun captureMultiPane(
        name: String,
        themeMode: ThemeMode,
        openThread: Boolean,
        sidebarCollapsedLandscape: Boolean = false,
        openModalDrawer: Boolean = false,
        enterFullScreen: Boolean = false,
        dragDividerToBalanced: Boolean = false,
    ) {
        val harness = MultiPaneTestHarness(
            initialSettings = AppSettings(
                themeMode = themeMode,
                sidebarCollapsedLandscape = sidebarCollapsedLandscape,
            ),
            richSampleContent = true,
        )
        if (openThread) {
            harness.hostViewModel.openThread(
                ThreadNavArgs(
                    tid = "1001",
                    title = "折腾折叠屏客户端：三栏阅读与实时液态玻璃深度体验",
                    fid = 2,
                    forumName = "Discovery",
                    author = "linus_hi",
                ),
            )
        }

        composeRule.setContent {
            harness.RenderRoot(glassEnvironment = GlassEnvironmentState.RefractionAndBlur)
        }
        composeRule.waitForIdle()

        if (openModalDrawer) {
            composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
            composeRule.waitForIdle()
        }
        if (enterFullScreen) {
            composeRule.onNodeWithContentDescription("全屏阅读").performClick()
            composeRule.waitForIdle()
        }
        if (dragDividerToBalanced) {
            val stepPx = with(composeRule.density) { 24.dp.toPx() }
            composeRule.onNodeWithTag(HiShellTestTags.PaneDivider).performTouchInput {
                down(center)
                repeat(5) {
                    moveBy(Offset(stepPx, 0f))
                    advanceEventTime(16L)
                }
                up()
            }
            composeRule.waitForIdle()
            assertEquals(PaneSplit.BALANCED, harness.settingsRepo.settingsFlow.value.paneSplitLandscape)
        }

        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        composeRule.onRoot().captureHiSnapshot(name)
    }
}
