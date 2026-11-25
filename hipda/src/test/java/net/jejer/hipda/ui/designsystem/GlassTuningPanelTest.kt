package net.jejer.hipda.ui.designsystem

import android.app.Application
import android.content.ClipboardManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import net.jejer.hipda.ui.designsystem.glass.HiGlassStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class GlassTuningPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun formatGlassStyleParams_isSingleLineStableAndLocaleIndependent() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val defaultLine = formatGlassStyleParams(HiGlassStyle.Navigation)
            assertEquals(
                "blur=10.0dp refractH=16.0dp refractAmt=24.0dp depth=off ca=off vibrancy=on " +
                    "lightTint=0.45 darkTint=0.46 sat=1.18 border=1.00 indicator=0.12",
                defaultLine,
            )
            assertFalse("输出应为单行文本", defaultLine.contains('\n'))

            val custom = HiGlassStyle.Navigation.copy(
                blurRadius = 16.5.dp,
                refractionHeight = 20.0.dp,
                refractionAmount = 32.5.dp,
                depthEffect = true,
                chromaticAberration = true,
                vibrancyEnabled = false,
                lightTintAlpha = 0.52f,
                darkTintAlpha = 0.64f,
                saturation = 1.25f,
                borderHighlightStrength = 1.35f,
                indicatorAlpha = 0.18f,
            )
            assertEquals(
                "blur=16.5dp refractH=20.0dp refractAmt=32.5dp depth=on ca=on vibrancy=off " +
                    "lightTint=0.52 darkTint=0.64 sat=1.25 border=1.35 indicator=0.18",
                formatGlassStyleParams(custom),
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun draggingSlidersAndSwitches_updatesShellGlassStyleResetAndCopyClipboard() {
        var currentStyle by mutableStateOf(HiGlassStyle.Navigation)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)

        composeRule.setContent {
            HiTheme(
                themeMode = HiThemeMode.LIGHT,
                seed = HiColorPresets.Brand.toSeed(),
                glassStyle = currentStyle,
            ) {
                ShellPreviewScreen(
                    onBack = {},
                    glassStyle = currentStyle,
                    onGlassStyleChange = { currentStyle = it },
                )
            }
        }
        composeRule.waitForIdle()

        // 默认收起，展开调参面板
        composeRule.onNodeWithText("调参").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("收起调参").assertIsDisplayed()

        // 通过真实触摸拖动（从滑轨中部向右拖）调节模糊半径滑杆
        composeRule.onNodeWithContentDescription("模糊半径")
            .performScrollTo()
            .performTouchInput {
                val start = Offset(width * 0.42f, centerY)
                val end = Offset(width * 0.85f, centerY)
                down(start)
                moveTo(Offset(width * 0.65f, centerY))
                moveTo(end)
                up()
            }
        composeRule.waitForIdle()
        assertTrue(
            "向右拖动模糊滑杆后半径应大于默认 10dp，实际 ${currentStyle.blurRadius}",
            currentStyle.blurRadius > HiGlassStyle.Navigation.blurRadius,
        )

        // 通过 Slider 语义动作精确设定折射与其余滑杆参数
        composeRule.onNodeWithContentDescription("折射高度")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(24f) }
        composeRule.onNodeWithContentDescription("折射强度")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(36f) }
        composeRule.onNodeWithContentDescription("浅色着色透明度")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.55f) }
        composeRule.onNodeWithContentDescription("深色着色透明度")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.62f) }
        composeRule.onNodeWithContentDescription("饱和度")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.30f) }
        composeRule.onNodeWithContentDescription("描边高光强度")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.50f) }
        composeRule.onNodeWithContentDescription("指示器透明度")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }

        // 切换 vibrancy / depthEffect / chromaticAberration 三个开关
        composeRule.onNodeWithContentDescription("活色增强（Vibrancy）")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithContentDescription("景深折射（Depth）")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithContentDescription("边缘色散（Chromatic）")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(24f, currentStyle.refractionHeight.value, 0.5f)
        assertEquals(36f, currentStyle.refractionAmount.value, 0.5f)
        assertEquals(0.55f, currentStyle.lightTintAlpha, 0.01f)
        assertEquals(0.62f, currentStyle.darkTintAlpha, 0.01f)
        assertEquals(1.30f, currentStyle.saturation, 0.01f)
        assertEquals(1.50f, currentStyle.borderHighlightStrength, 0.02f)
        assertEquals(0.25f, currentStyle.indicatorAlpha, 0.01f)
        assertFalse(currentStyle.vibrancyEnabled)
        assertTrue(currentStyle.depthEffect)
        assertTrue(currentStyle.chromaticAberration)
        assertNotEquals(HiGlassStyle.Navigation, currentStyle)

        // 复制当前参数到剪贴板，校验单行稳定格式
        composeRule.onNodeWithText("复制当前参数").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("已复制参数").assertIsDisplayed()
        val copiedText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertEquals(formatGlassStyleParams(currentStyle), copiedText)

        // 恢复默认
        composeRule.onNodeWithText("恢复默认").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(HiGlassStyle.Navigation, currentStyle)
    }

    @Test
    fun previewRoot_shellDemo_togglesTuningPanelAndUpdatesDisplayedParams() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)

        composeRule.setContent {
            PreviewRootContent()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("外壳").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("调参").performClick()
        composeRule.waitForIdle()

        val initialFormatted = formatGlassStyleParams(HiGlassStyle.Navigation)
        composeRule.onNodeWithText(initialFormatted).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithContentDescription("模糊半径")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(18f) }
        composeRule.waitForIdle()

        val expectedUpdated = formatGlassStyleParams(HiGlassStyle.Navigation.copy(blurRadius = 18.dp))
        composeRule.onNodeWithText(expectedUpdated).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("复制当前参数").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(expectedUpdated, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }
}
