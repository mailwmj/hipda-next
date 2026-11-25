package net.jejer.hipda.ui.shell

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiFontSizeStep
import net.jejer.hipda.ui.designsystem.HiShapeTokens
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.HiGlassMarker
import net.jejer.hipda.ui.designsystem.glass.HiGlassTokens
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val QUALIFIER_PHONE_411_891 = "w411dp-h891dp-xhdpi"
private const val QUALIFIER_MEDIUM_700_900 = "w700dp-h900dp-xhdpi"
private const val QUALIFIER_EXPANDED_900_1120 = "w900dp-h1120dp-xhdpi"
private const val QUALIFIER_TABLET_1280_800 = "w1280dp-h800dp-xhdpi"
private const val QUALIFIER_COMPACT_891_411 = "w891dp-h411dp-xhdpi"
private const val QUALIFIER_NARROW_340_800 = "w340dp-h800dp-xhdpi"

/**
 * 自适应外壳 11 张 Roborazzi 截图基线（前缀 `shell_`）：
 * - 411×891、700×900、900×1120、1280×800 × 浅/深色（8 张）
 * - 891×411 紧凑胶囊（1 张）
 * - 340×800 窄窗仅选中项显示文字（1 张）
 * - 411×891 第 5 档字号 × 系统字体缩放 1.3（1 张）
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class HiShellScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = QUALIFIER_PHONE_411_891)
    fun shell_w411_h891_light() = captureShell(
        name = "shell_w411_h891_light",
        themeMode = HiThemeMode.LIGHT,
        expectedModeTag = HiShellTestTags.BottomPill,
        windowBadge = "411×891 · 浅色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_PHONE_411_891)
    fun shell_w411_h891_dark() = captureShell(
        name = "shell_w411_h891_dark",
        themeMode = HiThemeMode.DARK,
        expectedModeTag = HiShellTestTags.BottomPill,
        windowBadge = "411×891 · 深色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_MEDIUM_700_900)
    fun shell_w700_h900_light() = captureShell(
        name = "shell_w700_h900_light",
        themeMode = HiThemeMode.LIGHT,
        expectedModeTag = HiShellTestTags.BottomPill,
        windowBadge = "700×900 · 浅色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_MEDIUM_700_900)
    fun shell_w700_h900_dark() = captureShell(
        name = "shell_w700_h900_dark",
        themeMode = HiThemeMode.DARK,
        expectedModeTag = HiShellTestTags.BottomPill,
        windowBadge = "700×900 · 深色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_EXPANDED_900_1120)
    fun shell_w900_h1120_light() = captureShell(
        name = "shell_w900_h1120_light",
        themeMode = HiThemeMode.LIGHT,
        expectedModeTag = HiShellTestTags.NavigationRail,
        windowBadge = "900×1120 · 浅色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_EXPANDED_900_1120)
    fun shell_w900_h1120_dark() = captureShell(
        name = "shell_w900_h1120_dark",
        themeMode = HiThemeMode.DARK,
        expectedModeTag = HiShellTestTags.NavigationRail,
        windowBadge = "900×1120 · 深色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_TABLET_1280_800)
    fun shell_w1280_h800_light() = captureShell(
        name = "shell_w1280_h800_light",
        themeMode = HiThemeMode.LIGHT,
        expectedModeTag = HiShellTestTags.NavigationRail,
        windowBadge = "1280×800 · 浅色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_TABLET_1280_800)
    fun shell_w1280_h800_dark() = captureShell(
        name = "shell_w1280_h800_dark",
        themeMode = HiThemeMode.DARK,
        expectedModeTag = HiShellTestTags.NavigationRail,
        windowBadge = "1280×800 · 深色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_COMPACT_891_411)
    fun shell_w891_h411_compact_light() = captureShell(
        name = "shell_w891_h411_compact_light",
        themeMode = HiThemeMode.LIGHT,
        expectedModeTag = HiShellTestTags.CompactPill,
        windowBadge = "891×411 · 紧凑胶囊",
    )

    @Test
    @Config(qualifiers = QUALIFIER_NARROW_340_800)
    fun shell_w340_h800_narrow_light() = captureShell(
        name = "shell_w340_h800_narrow_light",
        themeMode = HiThemeMode.LIGHT,
        expectedModeTag = HiShellTestTags.BottomPill,
        windowBadge = "340×800 · 窄窗",
    )

    @Test
    @Config(qualifiers = QUALIFIER_PHONE_411_891, fontScale = 1.3f)
    fun shell_w411_h891_step5_font130_light() = captureShell(
        name = "shell_w411_h891_step5_font130_light",
        themeMode = HiThemeMode.LIGHT,
        fontSizeStep = HiFontSizeStep.STEP_5,
        expectedModeTag = HiShellTestTags.BottomPill,
        expectedOuterFontScale = 1.3f,
        windowBadge = "411×891 · 5档×1.3",
    )

    private fun captureShell(
        name: String,
        themeMode: HiThemeMode,
        expectedModeTag: String,
        windowBadge: String,
        fontSizeStep: HiFontSizeStep = HiFontSizeStep.DEFAULT,
        expectedOuterFontScale: Float = 1.0f,
    ) {
        var observedOuterFontScale = 0f
        composeRule.setContent {
            observedOuterFontScale = LocalDensity.current.fontScale
            HiTheme(
                themeMode = themeMode,
                seed = HiColorPresets.Brand.toSeed(),
                fontSizeStep = fontSizeStep,
            ) {
                HiAdaptiveShell(
                    selected = ShellDestination.Forum,
                    onSelect = {},
                ) { destination, _ ->
                    ShellSpecimenContent(
                        destination = destination,
                        windowBadge = windowBadge,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals(expectedOuterFontScale, observedOuterFontScale, 0.01f)
        composeRule.onNodeWithTag(expectedModeTag).assertIsDisplayed()
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        val glassCount = composeRule.onAllNodes(
            matcher = SemanticsMatcher.expectValue(HiGlassMarker, true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        assertEquals("外壳截图应恰好包含 1 块毛玻璃", 1, glassCount)

        composeRule.onRoot().captureHiSnapshot(name)
    }
}

@Composable
private fun ShellSpecimenContent(
    destination: ShellDestination,
    windowBadge: String,
) {
    val cs = MaterialTheme.colorScheme
    val sampleThreads = listOf(
        "Discovery · 折叠屏展开与合上时的导航形态自适应记录",
        "Geek · Android 16 渲染管线与毛玻璃降级对比度实测",
        "摄影版 · 秋日山脊风光样张与镜头色彩层次分享",
        "生活版 · 手冲咖啡豆烘焙曲线与水温分段萃取笔记",
        "数码版 · 机械键盘客制化轴体手感与静音棉改造心得",
        "旅行版 · 周末轻量化徒步路线与高海拔露营装备清单",
        "HomeLab · 低功耗静音 NAS 机箱风道改造与休眠调优",
        "读书版 · 长帖排版字号与行距阅读舒适度对比讨论",
        "影音版 · 客厅家庭影院低频驻波测量与声学处理记录",
        "汽车版 · 长途自驾充电补能规划与沿途路况汇总",
        "运动版 · 城市慢跑心率区间训练与恢复节奏复盘",
        "美食版 · 家庭铸铁锅慢炖红烧肉火候与收汁技巧",
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(cs.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(destination.labelRes),
                style = MaterialTheme.typography.titleLarge,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .clip(HiShapeTokens.Small)
                    .background(cs.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = windowBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onPrimaryContainer,
                    maxLines = 1,
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(sampleThreads) { index, title ->
                val accentBg = when (index % 3) {
                    0 -> cs.primaryContainer
                    1 -> cs.tertiaryContainer
                    else -> cs.secondaryContainer
                }
                val accentFg = when (index % 3) {
                    0 -> cs.onPrimaryContainer
                    1 -> cs.onTertiaryContainer
                    else -> cs.onSecondaryContainer
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(HiShapeTokens.Medium)
                        .background(cs.surfaceContainerLow)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(HiShapeTokens.Full)
                                .background(accentBg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = accentFg,
                            )
                        }
                        Text(
                            text = title,
                            style = HiTheme.typography.threadTitle,
                            color = cs.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .clip(HiShapeTokens.Small)
                            .background(accentBg.copy(alpha = 0.6f))
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = "内容延伸至毛玻璃胶囊下方滚动，验证居中、不裁切与高对比度。",
                            style = MaterialTheme.typography.bodySmall,
                            color = accentFg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
