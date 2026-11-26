package net.jejer.hipda.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.common.SettingsGroup
import net.jejer.hipda.service.NotificationScheduler
import net.jejer.hipda.testing.withAnimatorDurationScale
import net.jejer.hipda.ui.designsystem.HiTheme
import net.jejer.hipda.ui.designsystem.glass.assertGlassSurfaceCountAtMost
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 覆盖 M7-G2 #12 设置「关于」分组 `assets/{release-notes,donors,license}.txt` 三项文档查看（撤回即红）：
 * - 点击 `更新记录`、`捐助名单`、`开源许可` 分别异步读取并渲染真实 `assets/` 文本且可滚动；
 * - `开源许可` 完整包含 Kyant `Backdrop` / `Apache License 2.0` 致谢；
 * - 资产读取失败安全兜底提示 `加载失败`，不崩溃且不泄漏异常类名。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
class AboutAssetDocumentsTest {

    @Test
    fun aboutGroup_opensScrollableReleaseNotesDonorsAndLicenseFromRealAssets() = withAnimatorDurationScale(0f) {
        val vm = SettingsViewModel(
            settingsRepository = StaticAboutSettingsRepository(),
            notificationScheduler = NoOpNotificationScheduler(),
            cacheManager = NoOpCacheManager(),
            errorLogStore = SettingsErrorLogStore { "" },
        )

        setContent {
            HiTheme {
                SettingsDetailPane(
                    group = SettingsGroup.About,
                    viewModel = vm,
                    onOpenSystemNotificationSettings = { true },
                    showBack = true,
                    onBack = {},
                    onOpenThread = {},
                    blacklistContent = { _, _ -> },
                    contentPadding = PaddingValues(0.dp),
                    appVersionName = "5.0.05",
                )
            }
        }
        waitForIdle()
        assertGlassSurfaceCountAtMost(0)

        // 1. 更新记录（assets/release-notes.txt）
        onNodeWithText("更新记录").assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 3_000L) {
            onAllNodes(hasText("v5.0.05", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        onNode(hasText("v5.0.05", substring = true) and hasScrollAction()).assertIsDisplayed()
        onNodeWithText("关闭").performClick()
        waitForIdle()
        onNode(hasText("v5.0.05", substring = true)).assertDoesNotExist()

        // 2. 捐助名单（assets/donors.txt）
        onNodeWithText("捐助名单").assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 3_000L) {
            onAllNodes(hasText("感谢网友的捐助", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        onNode(hasText("感谢网友的捐助", substring = true) and hasScrollAction()).assertIsDisplayed()
        onNodeWithText("关闭").performClick()
        waitForIdle()
        onNode(hasText("感谢网友的捐助", substring = true)).assertDoesNotExist()

        // 3. 开源许可（assets/license.txt，断言含 Kyant Backdrop Apache-2.0 致谢）
        onNodeWithText("开源许可").assertIsDisplayed().performClick()
        waitUntil(timeoutMillis = 3_000L) {
            onAllNodes(hasText("Backdrop", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        val licenseNode = onNode(hasText("Backdrop", substring = true) and hasScrollAction())
        licenseNode.assertIsDisplayed()
        onNode(hasText("Apache License 2.0", substring = true)).assertIsDisplayed()
        onNodeWithText("关闭").performClick()
        waitForIdle()
        onNode(hasText("Backdrop", substring = true)).assertDoesNotExist()
    }

    @Test
    fun aboutAssetDocumentDialog_loadFailureFallsBackSafelyWithoutCrashOrExceptionLeak() = withAnimatorDurationScale(0f) {
        var dismissed = false
        setContent {
            HiTheme {
                AboutAssetDocumentDialog(
                    document = AboutAssetDocument.License,
                    onDismiss = { dismissed = true },
                    assetLoader = { _, _ -> throw java.io.FileNotFoundException("missing-asset.txt") },
                )
            }
        }
        waitForIdle()
        onNodeWithText("开源许可").assertIsDisplayed()
        onNodeWithText("加载失败").assertIsDisplayed()
        onNode(hasText("Exception", substring = true)).assertDoesNotExist()
        onNodeWithText("关闭").performClick()
        waitForIdle()
        assertTrue(dismissed)
    }

    private class StaticAboutSettingsRepository : SettingsRepository {
        override val settings: Flow<AppSettings> = MutableStateFlow(AppSettings())
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
    }

    private class NoOpNotificationScheduler : NotificationScheduler {
        override fun syncSchedule(notificationsEnabled: Boolean) = Unit
        override suspend fun syncFromSettings() = Unit
        override fun schedule() = Unit
        override fun cancel() = Unit
    }

    private class NoOpCacheManager : SettingsCacheManager {
        override suspend fun getImageCacheSizeBytes(): Long = 0L
        override suspend fun clearImageCache(): Long = 0L
        override suspend fun clearWebCache(): Long = 0L
    }
}
