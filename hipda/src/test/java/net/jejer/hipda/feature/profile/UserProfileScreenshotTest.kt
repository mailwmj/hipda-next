package net.jejer.hipda.feature.profile

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.parser.search.SearchItem
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import net.jejer.hipda.ui.designsystem.state.HiLoadState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E12 用户资料页 Roborazzi 截图基线（共 5 张）：
 * - `profile_w411_light`（资料）、`profile_w411_dark`（TA 的帖子）、`profile_w411_empty_threads`（空态）
 * - `profile_w749_light`（资料）、`profile_w749_dark`（TA 的帖子）
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class UserProfileScreenshotTest {

    private val parsedDetails = parseProfileDetails(
        """
        基本资料

        用户组: 初级会员
        注册日期: 2011-04-13
        积分: 1280
        帖子: 342
        签名: 保持简单，读得舒服 https://www.4d4y.com

        论坛统计

        最后访问: 2026-11-26 07:10
        """.trimIndent(),
    )

    private val sampleThreads = listOf(
        SearchItem("20001", null, "折叠屏三栏阅读体验分享与排版细节记录", null, "HiUser", "10000", null, "2026-11-25", "Geek Talks"),
        SearchItem("20002", null, "近期电子纸设备对比评测（墨水屏向）", null, "HiUser", "10000", null, "2026-11-18", "E-INK"),
        SearchItem("20003", null, "自用 Mechanical Keyboard 出给坛友", null, "HiUser", "10000", null, "2026-11-09", "Buy & Sell"),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun profile_w411_light() = captureProfile(
        name = "profile_w411_light",
        themeMode = HiThemeMode.LIGHT,
        uiState = sampleState(UserProfileTab.Info, HiLoadState.Content(sampleThreads)),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun profile_w411_dark() = captureProfile(
        name = "profile_w411_dark",
        themeMode = HiThemeMode.DARK,
        uiState = sampleState(UserProfileTab.Threads, HiLoadState.Content(sampleThreads), isBlocked = true),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun profile_w411_empty_threads() = captureProfile(
        name = "profile_w411_empty_threads",
        themeMode = HiThemeMode.LIGHT,
        uiState = sampleState(UserProfileTab.Threads, HiLoadState.Empty),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun profile_w749_light() = captureProfile(
        name = "profile_w749_light",
        themeMode = HiThemeMode.LIGHT,
        uiState = sampleState(UserProfileTab.Info, HiLoadState.Content(sampleThreads)),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun profile_w749_dark() = captureProfile(
        name = "profile_w749_dark",
        themeMode = HiThemeMode.DARK,
        uiState = sampleState(UserProfileTab.Threads, HiLoadState.Content(sampleThreads)),
    )

    private fun sampleState(
        tab: UserProfileTab,
        threadsState: HiLoadState<List<SearchItem>>,
        isBlocked: Boolean = false,
    ) = UserProfileUiState(
        uid = "10000",
        displayUsername = "HiUser",
        avatarUrl = null,
        isOnline = true,
        headerStats = parsedDetails.headerStats,
        selectedTab = tab,
        profileLoadState = HiLoadState.Content(parsedDetails),
        threadsLoadState = threadsState,
        hasLoadedThreads = true,
        isBlocked = isBlocked,
    )

    private fun captureProfile(name: String, themeMode: HiThemeMode, uiState: UserProfileUiState) =
        withAnimatorDurationScale(0f) {
            setContent {
                HiTheme(themeMode = themeMode, seed = HiColorPresets.Brand.toSeed()) {
                    UserProfileScreenContent(
                        uiState = uiState,
                        showBack = true,
                        onBack = {},
                        onSelectTab = {},
                        onRetryProfile = {},
                        onRetryThreads = {},
                        onLoadMoreThreads = {},
                        onOpenSms = {},
                        onToggleBlacklist = {},
                        onOpenThread = {},
                        onOpenLogin = {},
                        contentPadding = PaddingValues(0.dp),
                    )
                }
            }
            waitForIdle()
            assertGlassSurfaceCountAtMost(0)
            onRoot().captureHiSnapshot(name)
        }
}
