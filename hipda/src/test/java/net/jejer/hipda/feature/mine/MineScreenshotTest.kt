package net.jejer.hipda.feature.mine

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import net.jejer.hipda.R
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.favorite.FavoriteKind
import net.jejer.hipda.data.local.BrowseHistoryEntity
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.UiError
import net.jejer.hipda.feature.common.UiErrorAction
import net.jejer.hipda.parser.simplelist.FavoriteThreadItem
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
 * E10 「我的」聚合页与子页 Roborazzi 截图基线（前缀 `mine_`，共 6 张）：
 * - 单栏「我的」菜单 `411dp` 浅/深（2 张，覆盖已登录与未登录）
 * - 收藏主态 `411dp` 浅、历史主态 `933dp` 深（2 张）
 * - 收藏空态 `411dp` 浅、收藏错态 `933dp` 深（2 张）
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class MineScreenshotTest {

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun mine_menu_w411_light() = captureMine("mine_menu_w411_light", HiThemeMode.LIGHT) {
        MineMenuScreen(
            account = SavedAccount("10000", "HiPDA_User", "0".repeat(32), lastLoginAt = 1L),
            onAccountClick = {},
            onSelectSubEntry = {},
        )
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun mine_menu_w411_dark() = captureMine("mine_menu_w411_dark", HiThemeMode.DARK) {
        MineMenuScreen(
            account = null,
            onAccountClick = {},
            onSelectSubEntry = {},
        )
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun mine_favorites_w411_light() = captureMine("mine_favorites_w411_light", HiThemeMode.LIGHT) {
        FavoritesScreenContent(
            uiState = FavoritesUiState(
                selectedKind = FavoriteKind.FAVORITE,
                isSignedIn = true,
                tabs = mapOf(
                    FavoriteKind.FAVORITE to PagedTabState(
                        items = listOf(
                            FavoriteThreadItem("3468951", "2026 冬季版规更新与客户端常用技巧整理", "2026-11-26 08:30", "Discovery"),
                            FavoriteThreadItem("3468902", "低功耗 HomeLab 静音机箱风道改造与硬盘休眠功耗实测", "2026-11-25 21:15", "Geek Talks"),
                            FavoriteThreadItem("3468810", "今年读完最推荐的五本非虚构电子书与墨水屏排版心得", "2026-11-24 19:40", "E-INK"),
                            FavoriteThreadItem("3468740", "长途自驾秋季赏枫路线与沿途补能服务区实测攻略", "2026-11-23 14:10", "吃喝玩乐"),
                        ),
                        page = 1,
                        hasMore = false,
                        hasLoadedOnce = true,
                    ),
                ),
            ),
            showBack = true,
            onBack = {},
        )
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun mine_history_w933_dark() {
        val prevTz = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            captureMine("mine_history_w933_dark", HiThemeMode.DARK) {
                BrowseHistoryScreenContent(
                    uiState = BrowseHistoryUiState(
                        isLoading = false,
                        items = listOf(
                            BrowseHistoryEntity("1002", "Galaxy Z Fold8 展开态三栏阅读排版与 120Hz 流畅度实测记录", "2", "Discovery", "linus_cn", 1_764_115_200_000L),
                            BrowseHistoryEntity("1005", "秋日山脊徒步实拍：富士直出色彩与便携三脚架选购心得", "50", "DC,NB,MP3,Gadgets", "shutter_bug", 1_764_108_000_000L),
                            BrowseHistoryEntity("1007", "手冲耶加雪菲水温分段萃取笔记：浅烘豆风味平衡实践", "25", "吃喝玩乐", "coffee_note", 1_764_096_000_000L),
                            BrowseHistoryEntity("1008", "客制化机械键盘消音棉与卫星轴润脂对比记录（附实测录音）", "7", "Geek Talks", "keycap_fan", 1_764_080_000_000L),
                        ),
                    ),
                    showBack = false,
                    onBack = null,
                )
            }
        } finally {
            java.util.TimeZone.setDefault(prevTz)
        }
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun mine_favorites_empty_w411_light() = captureMine("mine_favorites_empty_w411_light", HiThemeMode.LIGHT) {
        FavoritesScreenContent(
            uiState = FavoritesUiState(
                selectedKind = FavoriteKind.FAVORITE,
                isSignedIn = true,
                tabs = mapOf(
                    FavoriteKind.FAVORITE to PagedTabState(items = emptyList(), page = 1, hasMore = false, hasLoadedOnce = true),
                ),
            ),
            showBack = true,
            onBack = {},
        )
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.EXPANDED_933_696)
    fun mine_favorites_error_w933_dark() = captureMine("mine_favorites_error_w933_dark", HiThemeMode.DARK) {
        FavoritesScreenContent(
            uiState = FavoritesUiState(
                selectedKind = FavoriteKind.FAVORITE,
                isSignedIn = true,
                tabs = mapOf(
                    FavoriteKind.FAVORITE to PagedTabState(
                        items = emptyList(),
                        hasLoadedOnce = true,
                        error = UiError(R.string.common_error_network, "SocketTimeoutException: connect timed out", UiErrorAction.Retry),
                    ),
                ),
            ),
            showBack = false,
            onBack = null,
        )
    }

    private fun captureMine(
        name: String,
        themeMode: HiThemeMode,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) = withAnimatorDurationScale(0f) {
        setContent {
            HiTheme(themeMode = themeMode, seed = HiColorPresets.Brand.toSeed()) {
                content()
            }
        }
        waitForIdle()
        assertGlassSurfaceCountAtMost(0)
        onRoot().captureHiSnapshot(name)
    }
}
