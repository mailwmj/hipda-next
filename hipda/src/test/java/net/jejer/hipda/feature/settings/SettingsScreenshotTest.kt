package net.jejer.hipda.feature.settings

import android.Manifest
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.settings.*
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.SettingsGroup
import net.jejer.hipda.service.NotificationScheduler
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiColorPresets
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.HiThemeMode
import net.jejer.hipda.ui.designsystem.captureHiSnapshot
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * E12 设置、黑名单与账号管理 Roborazzi 截图基线（共 9 张）：
 * - `settings_w411/w749/w1280` 浅/深（6 张）
 * - `settings_blacklist_w411_light`（1 张）
 * - `account_w411` 浅/深（2 张）
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class SettingsScreenshotTest {

    @Before
    fun grantNotificationPermission() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val sampleAccounts = listOf(
        SavedAccount("1001", "hipda_veteran", "0123456789abcdef0123456789abcdef", lastLoginAt = 2_000L),
        SavedAccount("1002", "geek_alt", "fedcba9876543210fedcba9876543210", lastLoginAt = 1_000L),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun settings_w411_light() = captureScreen("settings_w411_light", HiThemeMode.LIGHT) {
        SettingsGroupsPane(
            selectedGroup = SettingsGroup.Appearance,
            isSidebar = false,
            showBack = true,
            onBack = {},
            onSelectGroup = {},
            contentPadding = PaddingValues(0.dp),
            listState = LazyListState(),
        )
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun settings_w411_dark() = captureScreen("settings_w411_dark", HiThemeMode.DARK) {
        val vm = remember { previewVm(AppSettings(themeMode = ThemeMode.DARK, pureBlack = false, themeSeed = HiColorPresets.Brand.id)) }
        SettingsDetailPane(
            group = SettingsGroup.Appearance,
            viewModel = vm,
            onOpenSystemNotificationSettings = { false },
            showBack = true,
            onBack = {},
            onOpenThread = {},
            blacklistContent = { _, _ -> },
            contentPadding = PaddingValues(0.dp),
        )
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun settings_w749_light() = captureTwoPane(
        name = "settings_w749_light",
        themeMode = HiThemeMode.LIGHT,
        listWidth = 260.dp,
        group = SettingsGroup.Appearance,
        settings = AppSettings(themeMode = ThemeMode.LIGHT, themeSeed = AppSettings.THEME_SEED_WALLPAPER),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.FOLD8_LANDSCAPE_749_559)
    fun settings_w749_dark() = captureTwoPane(
        name = "settings_w749_dark",
        themeMode = HiThemeMode.DARK,
        listWidth = 260.dp,
        group = SettingsGroup.ForumAndPost,
        settings = AppSettings(tailEnabled = true, tailText = "来自 HiPDA Next", tailUrl = "https://www.4d4y.com"),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun settings_w1280_light() = captureTwoPane(
        name = "settings_w1280_light",
        themeMode = HiThemeMode.LIGHT,
        listWidth = 340.dp,
        group = SettingsGroup.NetworkAndCache,
        settings = AppSettings(),
        imageCacheBytes = 128L * 1024L * 1024L,
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.TABLET_1280_800)
    fun settings_w1280_dark() = captureTwoPane(
        name = "settings_w1280_dark",
        themeMode = HiThemeMode.DARK,
        listWidth = 340.dp,
        group = SettingsGroup.Notification,
        settings = AppSettings(notificationsEnabled = true, silentModeEnabled = true),
    )

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun settings_blacklist_w411_light() = captureScreen("settings_blacklist_w411_light", HiThemeMode.LIGHT) {
        BlacklistScreenContent(
            uiState = BlacklistUiState(
                isLoggedIn = true,
                hasSynced = true,
                orderedUsernames = listOf("noisy_bot", "spam_account", "offtopic_user"),
                activeBlockedSet = setOf("noisy_bot", "spam_account", "offtopic_user"),
            ),
            onBack = {},
            onOpenLogin = {},
            onRetry = {},
            onRemoveUser = {},
            contentPadding = PaddingValues(0.dp),
        )
    }

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun settings_about_w411_light() = captureAbout("settings_about_w411_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun settings_about_w411_dark() = captureAbout("settings_about_w411_dark", HiThemeMode.DARK)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun account_w411_light() = captureAccount("account_w411_light", HiThemeMode.LIGHT)

    @Test
    @Config(qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
    fun account_w411_dark() = captureAccount("account_w411_dark", HiThemeMode.DARK)

    private fun captureAbout(name: String, themeMode: HiThemeMode) = captureScreen(name, themeMode) {
        val vm = remember { previewVm(AppSettings()) }
        SettingsDetailPane(
            group = SettingsGroup.About,
            viewModel = vm,
            onOpenSystemNotificationSettings = { false },
            showBack = true,
            onBack = {},
            onOpenThread = {},
            blacklistContent = { _, _ -> },
            contentPadding = PaddingValues(0.dp),
            appVersionName = "5.0.05",
        )
    }

    private fun captureAccount(name: String, themeMode: HiThemeMode) = captureScreen(name, themeMode) {
        AccountManagementContent(
            uiState = AccountManagementUiState(currentAccount = sampleAccounts.first(), savedAccounts = sampleAccounts),
            onSwitchAccount = {},
            onAddAccount = {},
            onLogout = {},
            onClearCredentials = {},
            onDismiss = {},
        )
    }

    private fun captureTwoPane(
        name: String,
        themeMode: HiThemeMode,
        listWidth: Dp,
        group: SettingsGroup,
        settings: AppSettings,
        imageCacheBytes: Long = 0L,
    ) = captureScreen(name, themeMode) {
        val vm = remember { previewVm(settings, imageCacheBytes) }
        Row(Modifier.fillMaxSize()) {
            SettingsGroupsPane(
                selectedGroup = group,
                isSidebar = true,
                showBack = false,
                onBack = null,
                onSelectGroup = {},
                contentPadding = PaddingValues(0.dp),
                listState = LazyListState(),
                modifier = Modifier.width(listWidth).fillMaxHeight(),
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            SettingsDetailPane(
                group = group,
                viewModel = vm,
                onOpenSystemNotificationSettings = { false },
                showBack = false,
                onBack = null,
                onOpenThread = {},
                blacklistContent = { _, _ -> },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }

    private fun captureScreen(name: String, themeMode: HiThemeMode, content: @Composable () -> Unit) =
        withAnimatorDurationScale(0f) {
            setContent { HiTheme(themeMode = themeMode, seed = HiColorPresets.Brand.toSeed()) { content() } }
            waitForIdle()
            assertGlassSurfaceCountAtMost(0)
            onRoot().captureHiSnapshot(name)
        }

    private fun previewVm(settings: AppSettings, imageCacheBytes: Long = 0L): SettingsViewModel = SettingsViewModel(
        settingsRepository = object : SettingsRepository {
            override val settings: Flow<AppSettings> = MutableStateFlow(settings)
            override suspend fun setThemeMode(themeMode: ThemeMode) = Unit
            override suspend fun setPureBlack(enabled: Boolean) = Unit
            override suspend fun setThemeSeed(themeSeed: String) = Unit
            override suspend fun setFontStep(step: Int) = Unit
            override suspend fun setLineSpacing(lineSpacing: LineSpacing) = Unit
            override suspend fun setFollowedForums(forumIds: List<Int>) = Unit
            override suspend fun setShowStickyThreads(show: Boolean) = Unit
            override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) = Unit
            override suspend fun setTailEnabled(enabled: Boolean) = Unit
            override suspend fun setTailText(text: String) = Unit
            override suspend fun setTailUrl(url: String) = Unit
            override suspend fun setNotificationsEnabled(enabled: Boolean) = Unit
            override suspend fun setSilentModeEnabled(enabled: Boolean) = Unit
            override suspend fun setSilentBeginMinutes(minutes: Int) = Unit
            override suspend fun setSilentEndMinutes(minutes: Int) = Unit
            override suspend fun setForumServer(server: String) = Unit
            override suspend fun setImageCacheLimitMb(limitMb: Int) = Unit
            override suspend fun setPaneSplitPortrait(split: PaneSplit) = Unit
            override suspend fun setPaneSplitLandscape(split: PaneSplit) = Unit
            override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) = Unit
            override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) = Unit
        },
        notificationScheduler = object : NotificationScheduler {
            override fun syncSchedule(notificationsEnabled: Boolean) = Unit
            override suspend fun syncFromSettings() = Unit
            override fun schedule() = Unit
            override fun cancel() = Unit
        },
        cacheManager = object : SettingsCacheManager {
            override suspend fun getImageCacheSizeBytes(): Long = imageCacheBytes
            override suspend fun clearImageCache(): Long = 0L
            override suspend fun clearWebCache(): Long = 0L
        },
        errorLogStore = SettingsErrorLogStore { "" },
    )
}
