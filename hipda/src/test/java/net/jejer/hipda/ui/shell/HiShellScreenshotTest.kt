package net.jejer.hipda.ui.shell

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.test.performClick
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
private const val QUALIFIER_FOLD8_PORTRAIT_559_749 = "w559dp-h749dp-xhdpi"
private const val QUALIFIER_SIDEBAR_SINGLE_640_1024 = "w640dp-h1024dp-xhdpi"
private const val QUALIFIER_FOLD8_LANDSCAPE_749_559 = "w749dp-h559dp-xhdpi"
private const val QUALIFIER_EXPANDED_840_900 = "w840dp-h900dp-xhdpi"
private const val QUALIFIER_EXPANDED_933_696 = "w933dp-h696dp-xhdpi"
private const val QUALIFIER_TABLET_1280_800 = "w1280dp-h800dp-xhdpi"
private const val QUALIFIER_COMPACT_891_411 = "w891dp-h411dp-xhdpi"
private const val QUALIFIER_NARROW_340_800 = "w340dp-h800dp-xhdpi"

/**
 * 自适应外壳 18 张 Roborazzi 截图基线（前缀 `shell_`，对齐 product-design.md §10.1）：
 * - 6 种窗口尺寸 × 深浅色（12 张）：411×891、559×749、640×1024、749×559、933×696、1280×800
 * - 特殊窗口（6 张）：891×411 紧凑胶囊、340×800 窄窗、749×559 240dp 浮层展开态、933×696 左栏主动收起态、
 *   411×891 与 840×900 第 5 档字号 × 系统字体缩放 1.3
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
        expectedGlassCount = 1,
        windowBadge = "411×891 · 浅色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_PHONE_411_891)
    fun shell_w411_h891_dark() = captureShell(
        name = "shell_w411_h891_dark",
        themeMode = HiThemeMode.DARK,
        expectedModeTag = HiShellTestTags.BottomPill,
        expectedGlassCount = 1,
        windowBadge = "411×891 · 深色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_FOLD8_PORTRAIT_559_749)
    fun shell_w559_h749_light() = captureShell(
        name = "shell_w559_h749_light",
        themeMode = HiThemeMode.LIGHT,
        expectedModeTag = HiShellTestTags.BottomPill,
        expectedGlassCount = 1,
        windowBadge = "559×749 · 浅色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_FOLD8_PORTRAIT_559_749)
    fun shell_w559_h749_dark() = captureShell(
        name = "shell_w559_h749_dark",
        themeMode = HiThemeMode.DARK,
        expectedModeTag = HiShellTestTags.BottomPill,
        expectedGlassCount = 1,
        windowBadge = "559×749 · 深色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_SIDEBAR_SINGLE_640_1024)
    fun shell_w640_h1024_light() = captureShell(
        name = "shell_w640_h1024_light",
        themeMode = HiThemeMode.LIGHT,
        expectedModeTag = HiShellTestTags.NavigationSidebar,
        expectedGlassCount = 0,
        windowBadge = "640×1024 · 浅色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_SIDEBAR_SINGLE_640_1024)
    fun shell_w640_h1024_dark() = captureShell(
        name = "shell_w640_h1024_dark",
        themeMode = HiThemeMode.DARK,
        expectedModeTag = HiShellTestTags.NavigationSidebar,
        expectedGlassCount = 0,
        windowBadge = "640×1024 · 深色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_FOLD8_LANDSCAPE_749_559)
    fun shell_w749_h559_light() = captureShell(
        name = "shell_w749_h559_light",
        themeMode = HiThemeMode.LIGHT,
        expectedModeTag = HiShellTestTags.NavigationSidebar,
        expectedGlassCount = 0,
        windowBadge = "749×559 · 浅色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_FOLD8_LANDSCAPE_749_559)
    fun shell_w749_h559_dark() = captureShell(
        name = "shell_w749_h559_dark",
        themeMode = HiThemeMode.DARK,
        expectedModeTag = HiShellTestTags.NavigationSidebar,
        expectedGlassCount = 0,
        windowBadge = "749×559 · 深色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_EXPANDED_933_696)
    fun shell_w933_h696_light() = captureShell(
        name = "shell_w933_h696_light",
        themeMode = HiThemeMode.LIGHT,
        expectedModeTag = HiShellTestTags.NavigationSidebar,
        expectedGlassCount = 0,
        windowBadge = "933×696 · 浅色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_EXPANDED_933_696)
    fun shell_w933_h696_dark() = captureShell(
        name = "shell_w933_h696_dark",
        themeMode = HiThemeMode.DARK,
        expectedModeTag = HiShellTestTags.NavigationSidebar,
        expectedGlassCount = 0,
        windowBadge = "933×696 · 深色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_TABLET_1280_800)
    fun shell_w1280_h800_light() = captureShell(
        name = "shell_w1280_h800_light",
        themeMode = HiThemeMode.LIGHT,
        expectedModeTag = HiShellTestTags.NavigationSidebar,
        expectedGlassCount = 0,
        windowBadge = "1280×800 · 浅色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_TABLET_1280_800)
    fun shell_w1280_h800_dark() = captureShell(
        name = "shell_w1280_h800_dark",
        themeMode = HiThemeMode.DARK,
        expectedModeTag = HiShellTestTags.NavigationSidebar,
        expectedGlassCount = 0,
        windowBadge = "1280×800 · 深色",
    )

    @Test
    @Config(qualifiers = QUALIFIER_COMPACT_891_411)
    fun shell_w891_h411_compact_light() = captureShell(
        name = "shell_w891_h411_compact_light",
        themeMode = HiThemeMode.LIGHT,
        expectedModeTag = HiShellTestTags.CompactPill,
        expectedGlassCount = 1,
        windowBadge = "891×411 · 紧凑胶囊",
    )

    @Test
    @Config(qualifiers = QUALIFIER_NARROW_340_800)
    fun shell_w340_h800_narrow_light() = captureShell(
        name = "shell_w340_h800_narrow_light",
        themeMode = HiThemeMode.LIGHT,
        expectedModeTag = HiShellTestTags.BottomPill,
        expectedGlassCount = 1,
        windowBadge = "340×800 · 窄窗",
    )

    @Test
    @Config(qualifiers = QUALIFIER_FOLD8_LANDSCAPE_749_559)
    fun shell_w749_h559_drawer_light() = captureShell(
        name = "shell_w749_h559_drawer_light",
        themeMode = HiThemeMode.LIGHT,
        expectedModeTag = HiShellTestTags.SidebarModalDrawer,
        expectedGlassCount = 0,
        windowBadge = "749×559 · 浮层展开",
        openModalDrawer = true,
    )

    @Test
    @Config(qualifiers = QUALIFIER_EXPANDED_933_696)
    fun shell_w933_h696_collapsed_light() = captureShell(
        name = "shell_w933_h696_collapsed_light",
        themeMode = HiThemeMode.LIGHT,
        expectedModeTag = HiShellTestTags.NavigationSidebar,
        expectedGlassCount = 0,
        windowBadge = "933×696 · 侧栏收起",
        sidebarCollapsedPref = true,
    )

    @Test
    @Config(qualifiers = QUALIFIER_PHONE_411_891, fontScale = 1.3f)
    fun shell_w411_h891_step5_font130_light() = captureShell(
        name = "shell_w411_h891_step5_font130_light",
        themeMode = HiThemeMode.LIGHT,
        fontSizeStep = HiFontSizeStep.STEP_5,
        expectedModeTag = HiShellTestTags.BottomPill,
        expectedGlassCount = 1,
        expectedOuterFontScale = 1.3f,
        windowBadge = "411×891 · 5档×1.3",
    )

    @Test
    @Config(qualifiers = QUALIFIER_EXPANDED_840_900, fontScale = 1.3f)
    fun shell_w840_h900_step5_font130_light() = captureShell(
        name = "shell_w840_h900_step5_font130_light",
        themeMode = HiThemeMode.LIGHT,
        fontSizeStep = HiFontSizeStep.STEP_5,
        expectedModeTag = HiShellTestTags.NavigationSidebar,
        expectedGlassCount = 0,
        expectedOuterFontScale = 1.3f,
        windowBadge = "840×900 · 5档×1.3",
    )

    private fun captureShell(
        name: String,
        themeMode: HiThemeMode,
        expectedModeTag: String,
        expectedGlassCount: Int,
        windowBadge: String,
        fontSizeStep: HiFontSizeStep = HiFontSizeStep.DEFAULT,
        expectedOuterFontScale: Float = 1.0f,
        sidebarCollapsedPref: Boolean? = null,
        openModalDrawer: Boolean = false,
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
                    sidebarState = HiSidebarState(
                        selectedEntry = ShellSidebarEntry.Forum(2),
                        followedForumIds = ForumMetadataRegistry.DefaultFollowedForumIds,
                        unreadSmsCount = 3,
                        unreadNotifyCount = 12,
                        account = HiSidebarAccount(username = "HiPDA"),
                        isCollapsedPref = sidebarCollapsedPref,
                    ),
                ) { destination, _ ->
                    ShellSpecimenContent(
                        destination = destination,
                        paneLayout = paneLayout,
                        windowBadge = windowBadge,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        if (openModalDrawer) {
            composeRule.onNodeWithTag(HiShellTestTags.SidebarToggleButton).performClick()
            composeRule.waitForIdle()
        }

        assertEquals(expectedOuterFontScale, observedOuterFontScale, 0.01f)
        composeRule.onNodeWithTag(expectedModeTag).assertIsDisplayed()
        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        val glassCount = composeRule.onAllNodes(
            matcher = SemanticsMatcher.expectValue(HiGlassMarker, true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        assertEquals("外壳截图毛玻璃数不符合预期", expectedGlassCount, glassCount)

        composeRule.onRoot().captureHiSnapshot(name)
    }
}

@Composable
private fun ShellSpecimenContent(
    destination: ShellDestination,
    paneLayout: ShellPaneLayout,
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

    val listPane: @Composable (Modifier) -> Unit = { listModifier ->
        Column(modifier = listModifier) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (paneLayout.isSidebar) "Discovery" else stringResource(destination.labelRes),
                    style = MaterialTheme.typography.titleLarge,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
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
                        softWrap = false,
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
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
                            .background(if (paneLayout.isThreePane && index == 0) cs.secondaryContainer else cs.surfaceContainerLow)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!paneLayout.isThreePane || paneLayout.middlePaneWidthDp >= 300) {
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
                            }
                            Text(
                                text = title,
                                style = HiTheme.typography.threadTitle,
                                color = cs.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!paneLayout.isThreePane) {
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
    }

    if (paneLayout.isThreePane) {
        Row(modifier = Modifier.fillMaxSize()) {
            listPane(
                Modifier
                    .width(paneLayout.middlePaneWidthDp.dp)
                    .fillMaxHeight(),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(cs.outlineVariant),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(cs.surface),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cs.surfaceContainer)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = sampleThreads.first(),
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 680.dp)
                            .fillMaxWidth()
                            .clip(HiShapeTokens.Medium)
                            .background(cs.surfaceContainerLow)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "三栏正文占位 · 左 ${paneLayout.sidebarWidthDp}dp | 中 ${paneLayout.middlePaneWidthDp}dp | 右 ${paneLayout.rightPaneWidthDp}dp",
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.primary,
                        )
                        Text(
                            text = "正文阅读区遵循 360dp 硬底线与 680dp 最大行宽居中留白，左侧栏纯色 surfaceContainerLow 无毛玻璃。",
                            style = HiTheme.typography.postBody,
                            color = cs.onSurface,
                        )
                    }
                }
            }
        }
    } else {
        listPane(Modifier.fillMaxSize())
    }
}
