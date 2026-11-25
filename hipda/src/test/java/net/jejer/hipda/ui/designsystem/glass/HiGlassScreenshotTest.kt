package net.jejer.hipda.ui.designsystem.glass

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jejer.hipda.ui.designsystem.HiCappedFontScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiScreenshotTestSupport
import net.jejer.hipda.ui.designsystem.HiShapeTokens
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
 * 毛玻璃表面 10 变体截图测试（浅色/深色 × {API 33+ 折射模糊、API 31 仅模糊、API 30 降级、省电降级、降低透明度降级}）。
 *
 * 说明：
 * - API 33+ 折射档用例在 Robolectric API 35 原生图形栈下执行真实 AGSL `lens()` 折射 + `blur()` + `vibrancy()`；
 * - API 31 仅模糊档用例在 Robolectric API 31 原生图形栈下执行 `blur()` + `vibrancy()`（不调用 `lens()`）；
 * - 6 张降级基线守住 94% 不透明纯色底 + `0.5dp outlineVariant` 描边及文字对比度 ≥ 4.5:1。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = Application::class,
    sdk = [34],
    qualifiers = HiScreenshotTestSupport.Qualifiers.PHONE_411,
)
class HiGlassScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(sdk = [35])
    fun glass_light_api33_refraction() = captureGlassVariant(
        name = "glass_specimen_light_api33_refraction",
        themeMode = HiThemeMode.LIGHT,
        environment = GlassEnvironmentState.RefractionAndBlur,
        statusLabel = "API 33+ 折射模糊",
    )

    @Test
    @Config(sdk = [31])
    fun glass_light_api31_blur() = captureGlassVariant(
        name = "glass_specimen_light_api31_blur",
        themeMode = HiThemeMode.LIGHT,
        environment = GlassEnvironmentState.BlurOnly,
        statusLabel = "API 31 仅模糊",
    )

    @Test
    @Config(sdk = [30])
    fun glass_light_api30_degraded() = captureGlassVariant(
        name = "glass_specimen_light_api30_degraded",
        themeMode = HiThemeMode.LIGHT,
        environment = GlassEnvironmentState(setOf(GlassDegradationReason.UNSUPPORTED_API_LEVEL)),
        statusLabel = "API 30 降级",
    )

    @Test
    fun glass_light_powerSave_degraded() = captureGlassVariant(
        name = "glass_specimen_light_power_save_degraded",
        themeMode = HiThemeMode.LIGHT,
        environment = GlassEnvironmentState(setOf(GlassDegradationReason.POWER_SAVE_MODE)),
        statusLabel = "省电降级",
    )

    @Test
    fun glass_light_reduceTransparency_degraded() = captureGlassVariant(
        name = "glass_specimen_light_reduce_transparency_degraded",
        themeMode = HiThemeMode.LIGHT,
        environment = GlassEnvironmentState(setOf(GlassDegradationReason.REDUCE_TRANSPARENCY)),
        statusLabel = "降低透明度降级",
    )

    @Test
    @Config(sdk = [35])
    fun glass_dark_api33_refraction() = captureGlassVariant(
        name = "glass_specimen_dark_api33_refraction",
        themeMode = HiThemeMode.DARK,
        environment = GlassEnvironmentState.RefractionAndBlur,
        statusLabel = "API 33+ 折射模糊",
    )

    @Test
    @Config(sdk = [31])
    fun glass_dark_api31_blur() = captureGlassVariant(
        name = "glass_specimen_dark_api31_blur",
        themeMode = HiThemeMode.DARK,
        environment = GlassEnvironmentState.BlurOnly,
        statusLabel = "API 31 仅模糊",
    )

    @Test
    @Config(sdk = [30])
    fun glass_dark_api30_degraded() = captureGlassVariant(
        name = "glass_specimen_dark_api30_degraded",
        themeMode = HiThemeMode.DARK,
        environment = GlassEnvironmentState(setOf(GlassDegradationReason.UNSUPPORTED_API_LEVEL)),
        statusLabel = "API 30 降级",
    )

    @Test
    fun glass_dark_powerSave_degraded() = captureGlassVariant(
        name = "glass_specimen_dark_power_save_degraded",
        themeMode = HiThemeMode.DARK,
        environment = GlassEnvironmentState(setOf(GlassDegradationReason.POWER_SAVE_MODE)),
        statusLabel = "省电降级",
    )

    @Test
    fun glass_dark_reduceTransparency_degraded() = captureGlassVariant(
        name = "glass_specimen_dark_reduce_transparency_degraded",
        themeMode = HiThemeMode.DARK,
        environment = GlassEnvironmentState(setOf(GlassDegradationReason.REDUCE_TRANSPARENCY)),
        statusLabel = "降低透明度降级",
    )

    @Test
    @Config(sdk = [35])
    fun glass_light_topbar_sheet() = captureTopBarSheetSpecimen(
        name = "glass_specimen_light_topbar_sheet",
        themeMode = HiThemeMode.LIGHT,
    )

    @Test
    @Config(sdk = [35])
    fun glass_dark_topbar_sheet() = captureTopBarSheetSpecimen(
        name = "glass_specimen_dark_topbar_sheet",
        themeMode = HiThemeMode.DARK,
    )

    private fun captureTopBarSheetSpecimen(
        name: String,
        themeMode: HiThemeMode,
    ) {
        composeRule.setContent {
            HiTheme(
                themeMode = themeMode,
                seed = HiColorPresets.Brand.toSeed(),
            ) {
                ProvideGlassEnvironment(state = GlassEnvironmentState.RefractionAndBlur) {
                    TopBarAndSheetGlassSpecimenScreen(darkTheme = themeMode == HiThemeMode.DARK)
                }
            }
        }
        composeRule.assertGlassSurfaceCountAtMost(2)
        composeRule.assertAllGlassSurfacesBlurActive(true)
        composeRule.onRoot().captureHiSnapshot(name)
    }

    private fun captureGlassVariant(
        name: String,
        themeMode: HiThemeMode,
        environment: GlassEnvironmentState,
        statusLabel: String,
    ) {
        var resolvedScheme: ColorScheme? = null
        val darkTheme = themeMode == HiThemeMode.DARK

        composeRule.setContent {
            HiTheme(
                themeMode = themeMode,
                seed = HiColorPresets.Brand.toSeed(),
            ) {
                resolvedScheme = MaterialTheme.colorScheme
                ProvideGlassEnvironment(state = environment) {
                    GlassScrollSpecimenScreen(statusLabel = statusLabel)
                }
            }
        }

        composeRule.assertGlassSurfaceCountAtMost(HiGlassTokens.MaxGlassSurfacesOnScreen)
        composeRule.assertAllGlassSurfacesBlurActive(environment.blurEnabled)

        if (!environment.blurEnabled) {
            val scheme = requireNotNull(resolvedScheme)
            val spec = resolveHiGlassStyleSpec(
                colorScheme = scheme,
                darkTheme = darkTheme,
                blurEnabled = false,
            )
            val worstBackdrop = if (darkTheme) Color.White else Color.Black
            listOf(scheme.background, worstBackdrop).forEach { backdrop ->
                assertWcagContrastAtLeast(
                    minRatio = 4.5f,
                    surface = spec.degradedSurfaceColor,
                    foreground = scheme.onSurface,
                    backdrop = backdrop,
                    label = "$name onSurface",
                )
                assertWcagContrastAtLeast(
                    minRatio = 4.5f,
                    surface = spec.degradedSurfaceColor,
                    foreground = scheme.onSurfaceVariant,
                    backdrop = backdrop,
                    label = "$name onSurfaceVariant",
                )
                assertWcagContrastAtLeast(
                    minRatio = 4.5f,
                    surface = spec.degradedSurfaceColor,
                    foreground = scheme.primary,
                    backdrop = backdrop,
                    label = "$name primary",
                )
            }
        }

        composeRule.onRoot().captureHiSnapshot(name)
    }
}

@Composable
private fun GlassScrollSpecimenScreen(
    statusLabel: String,
) {
    val cs = MaterialTheme.colorScheme
    val glassState = rememberHiGlassState()
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = 1,
        initialFirstVisibleItemScrollOffset = 44,
    )
    val sampleFloors = listOf(
        "第 1 楼 · 折叠屏展开后双栏排版与阅读体验实测记录",
        "第 2 楼 · 摄影版秋日样张色彩层次与高光压制对比",
        "第 3 楼 · Android 12+ RenderEffect 模糊与降级策略说明",
        "第 4 楼 · 机械键盘客制化轴体手感与消音棉方案汇总",
        "第 5 楼 · 周末徒步路线记录：山脊线视野与轻量化装备清单",
        "第 6 楼 · 咖啡豆烘焙曲线与手冲水温分段萃取实验",
        "第 7 楼 · 家用 NAS 低功耗静音机箱改造与硬盘休眠调优",
        "第 8 楼 · 长帖连续加载零跳动与阅读位置记忆回放验证",
        "第 9 楼 · 深色模式与纯黑 OLED 表面对比度校准细节",
        "第 10 楼 · 导航胶囊与底部工具条共用滚动偏移设计",
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = cs.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 内容区：顶栏保持纯色（不用毛玻璃），列表延伸到毛玻璃下方滚动
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .hiGlassSource(glassState),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cs.surfaceContainer)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Discovery · 帖子详情样张",
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurface,
                    )
                    Box(
                        modifier = Modifier
                            .clip(HiShapeTokens.Small)
                            .background(cs.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onPrimaryContainer,
                        )
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 12.dp,
                        end = 16.dp,
                        bottom = 160.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(sampleFloors) { index, title ->
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
                                        text = "${index + 1}楼",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accentFg,
                                    )
                                }
                                Text(
                                    text = title,
                                    style = HiTheme.typography.threadTitle,
                                    color = cs.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(HiShapeTokens.Small)
                                    .background(accentBg.copy(alpha = 0.65f))
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text = "高反差色块内容延伸到胶囊与工具条下方，用以校验毛玻璃材质与降级遮蔽对比度。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = accentFg,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // 悬浮层：底部工具条（第 1 块毛玻璃）+ 8dp 间距 + 底部导航胶囊（第 2 块毛玻璃）
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HiCappedFontScale {
                    HiGlassSurface(
                        state = glassState,
                        shape = HiShapeTokens.Full,
                        floating = true,
                        style = HiGlassStyle.Toolbar,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "回复",
                                style = MaterialTheme.typography.labelLarge,
                                color = cs.primary,
                            )
                            Text(
                                text = "第 2 / 8 页",
                                style = MaterialTheme.typography.labelMedium,
                                color = cs.onSurface,
                            )
                            Text(
                                text = "更多",
                                style = MaterialTheme.typography.labelLarge,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
                HiCappedFontScale {
                    HiGlassSurface(
                        state = glassState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 420.dp)
                            .heightIn(min = 64.dp),
                        shape = HiShapeTokens.Full,
                        floating = true,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            listOf("论坛", "消息", "搜索", "我的").forEachIndexed { idx, label ->
                                val bg = if (idx == 0) cs.primary.copy(alpha = HiGlassTokens.IndicatorAlpha) else Color.Transparent
                                val fg = if (idx == 0) cs.primary else cs.onSurfaceVariant
                                Box(
                                    modifier = Modifier
                                        .clip(HiShapeTokens.Full)
                                        .background(bg)
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = fg,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBarAndSheetGlassSpecimenScreen(darkTheme: Boolean) {
    val cs = MaterialTheme.colorScheme
    val glassState = rememberHiGlassState()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 1, initialFirstVisibleItemScrollOffset = 32)
    val buttonAlpha = if (darkTheme) {
        HiGlassTokens.TopBarActionButtonDarkAlpha
    } else {
        HiGlassTokens.TopBarActionButtonLightAlpha
    }

    Surface(modifier = Modifier.fillMaxSize(), color = cs.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .hiGlassSource(glassState),
                contentPadding = PaddingValues(start = 16.dp, top = 76.dp, end = 16.dp, bottom = 220.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(10) { idx ->
                    val accentBg = when (idx % 3) {
                        0 -> cs.primaryContainer
                        1 -> cs.tertiaryContainer
                        else -> cs.secondaryContainer
                    }
                    val accentFg = when (idx % 3) {
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
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "第 ${idx + 1} 楼 · 透明 TopBar 与 Sheet 高隔离毛玻璃兄弟拓扑样张",
                            style = HiTheme.typography.threadTitle,
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .clip(HiShapeTokens.Small)
                                .background(accentBg.copy(alpha = 0.7f))
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = "正文从顶栏与回复面板下方滚过，验证兄弟节点零循环采样与护读对比度。",
                                style = MaterialTheme.typography.bodySmall,
                                color = accentFg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // 区域 1：TopBar 透明液态玻璃顶栏（内含非玻璃轻底圆形按钮）
            HiGlassSurface(
                state = glassState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RectangleShape,
                floating = false,
                style = HiGlassStyle.TopBar,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(HiShapeTokens.Full)
                            .background(cs.surfaceContainerHigh.copy(alpha = buttonAlpha))
                            .border(HiGlassTokens.TopBarActionButtonBorderWidth, cs.outlineVariant, HiShapeTokens.Full),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("返回", style = MaterialTheme.typography.labelSmall, color = cs.onSurface)
                    }
                    Text(
                        text = "Discovery · TopBar 档样张",
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurface,
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(HiShapeTokens.Full)
                            .background(cs.surfaceContainerHigh.copy(alpha = buttonAlpha))
                            .border(HiGlassTokens.TopBarActionButtonBorderWidth, cs.outlineVariant, HiShapeTokens.Full),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("楼主", style = MaterialTheme.typography.labelSmall, color = cs.primary)
                    }
                }
            }

            // 淡暗罩 + 区域 2：Sheet 高隔离毛玻璃快速回复面板
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(cs.scrim.copy(alpha = HiGlassTokens.SheetScrimAlpha))
                    .padding(top = 18.dp),
            ) {
                HiGlassSurface(
                    state = glassState,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    floating = true,
                    style = HiGlassStyle.Sheet,
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "回复 第 1 楼 · Sheet 高隔离护读档",
                            style = MaterialTheme.typography.titleSmall,
                            color = cs.onSurface,
                        )
                        Text(
                            text = "亮 18dp / 暗 12dp 模糊 · 浅 0.76 / 深 0.74 着色 · 1dp 描边 · 0.35 暗罩",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
