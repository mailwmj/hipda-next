package net.jejer.hipda.feature.search

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.mine.PagedTabState
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E10 搜索页 Roborazzi 截图基线（前缀 `search_`，共 8 张）：
 * - 搜索初始态 `411` / `749` / `1280dp` × 浅/深（6 张，覆盖最近搜索、筛选胶囊、展开筛选面板与站内链接首行）
 * - 搜索结果态 `411dp` × 浅/深（2 张）
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class SearchScreenshotTest {

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun search_initial_w411_light() = captureSearch(
        name = "search_initial_w411_light",
        themeMode = HiThemeMode.LIGHT,
        uiState = SearchUiState(
            recentHistory = listOf("Galaxy Z Fold8 三栏", "富士直出配色", "机械键盘消音棉", "墨水屏"),
        ),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun search_initial_w411_dark() = captureSearch(
        name = "search_initial_w411_dark",
        themeMode = HiThemeMode.DARK,
        uiState = SearchUiState(
            query = "https://www.4d4y.com/forum/viewthread.php?tid=3478055",
            resolvedLink = resolveAllowedForumLink("https://www.4d4y.com/forum/viewthread.php?tid=3478055"),
            recentHistory = listOf("Galaxy Z Fold8 三栏", "富士直出配色", "HomeLab"),
        ),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun search_initial_w749_light() = captureSearch(
        name = "search_initial_w749_light",
        themeMode = HiThemeMode.LIGHT,
        uiState = SearchUiState(
            selectedForumId = 2,
            expandedFilterSection = SearchFilterSection.Forum,
            recentHistory = listOf("Galaxy Z Fold8", "手冲咖啡", "降噪耳机"),
        ),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun search_initial_w749_dark() = captureSearch(
        name = "search_initial_w749_dark",
        themeMode = HiThemeMode.DARK,
        uiState = SearchUiState(
            fullText = true,
            expandedFilterSection = SearchFilterSection.Scope,
            recentHistory = listOf("Galaxy Z Fold8", "手冲咖啡"),
        ),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun search_initial_w1280_light() = captureSearch(
        name = "search_initial_w1280_light",
        themeMode = HiThemeMode.LIGHT,
        uiState = SearchUiState(
            author = "linus_cn",
            expandedFilterSection = SearchFilterSection.Author,
            recentHistory = listOf("三栏布局", "电子书", "自驾路线"),
        ),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun search_initial_w1280_dark() = captureSearch(
        name = "search_initial_w1280_dark",
        themeMode = HiThemeMode.DARK,
        uiState = SearchUiState(
            recentHistory = listOf("三栏布局", "电子书", "自驾路线", "低功耗 NAS"),
        ),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun search_results_w411_light() = captureSearch(
        name = "search_results_w411_light",
        themeMode = HiThemeMode.LIGHT,
        uiState = sampleResultsState(),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun search_results_w411_dark() = captureSearch(
        name = "search_results_w411_dark",
        themeMode = HiThemeMode.DARK,
        uiState = sampleResultsState(),
    )

    private fun captureSearch(name: String, themeMode: HiThemeMode, uiState: SearchUiState) =
        withAnimatorDurationScale(0f) {
            setContent {
                HiTheme(themeMode = themeMode, seed = HiColorPresets.Brand.toSeed()) {
                    SearchScreenContent(uiState = uiState)
                }
            }
            waitForIdle()
            assertGlassSurfaceCountAtMost(0)
            onRoot().captureHiSnapshot(name)
        }

    private fun sampleResultsState() = SearchUiState(
        query = "折叠屏",
        fullText = true,
        hasSubmitted = true,
        results = PagedTabState(
            items = listOf(
                SearchItem("3478055", "101", "Galaxy Z Fold8 展开态三栏阅读排版与 120Hz 流畅度实测记录", "展开后 749dp 紧凑三栏阅读体验稳定，引用块折行自然，单手切换版块顺手。", "linus_cn", "1", null, "10分钟前", "Discovery"),
                SearchItem("3478012", "102", "主流大折叠屏外屏与内屏握持重量对比（附通勤一周感受）", "外屏单手刷列表足够轻便，展开读长帖不用反复缩放页面。", "mira", "2", null, "2小时前", "Geek Talks"),
                SearchItem("3477980", "103", "折叠屏支架与无线充电底座选购汇总，桌面办公实测", null, "kane", "3", null, "昨天", "Buy & Sell"),
                SearchItem("3477901", "104", "墨水屏与折叠屏睡前阅读护眼对比笔记", "纯黑 OLED 深色模式配合暖色纸感，夜间看长帖不刺眼。", "eink_reader", "4", null, "2026-11-24", "E-INK"),
            ),
            page = 1,
            hasMore = true,
            hasLoadedOnce = true,
        ),
    )
}
