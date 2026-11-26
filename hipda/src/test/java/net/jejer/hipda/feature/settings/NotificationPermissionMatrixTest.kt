package net.jejer.hipda.feature.settings

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.feature.common.SettingsGroup
import net.jejer.hipda.service.NotificationScheduler
import net.jejer.hipda.ui.designsystem.HiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * M7-3 验收 2：覆盖 API 28 / 33 / 35 / 36 下设置页「接收提醒通知」开关的运行时 `POST_NOTIFICATIONS`
 * 权限申请、授权开启、拒绝/永久拒绝降级引导跳转系统设置、返回自动恢复与系统设置 Intent 解析兜底。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class NotificationPermissionMatrixTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(sdk = [28])
    fun api28_enablingNotifications_succeedsDirectlyWithoutPermissionRequest() {
        val repo = TestSettingsRepo(AppSettings(notificationsEnabled = false))
        val scheduler = TestScheduler()
        val vm = SettingsViewModel(repo, scheduler, NoOpCacheManager, SettingsErrorLogStore { "" })
        val permOwner = ControlledPermissionRegistryOwner(nextGrantResult = false)

        composeRule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides permOwner) {
                HiTheme {
                    SettingsDetailPane(
                        group = SettingsGroup.Notification,
                        viewModel = vm,
                        onOpenSystemNotificationSettings = { true },
                        showBack = false,
                        onBack = null,
                        onOpenThread = {},
                        blacklistContent = { _, _ -> },
                        contentPadding = PaddingValues(0.dp),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("接收提醒通知").performClick()
        composeRule.waitForIdle()

        assertTrue("API 28 不应申请 POST_NOTIFICATIONS", permOwner.requestedPermissions.isEmpty())
        assertTrue("API 28 应直接开启提醒通知", repo.state.value.notificationsEnabled)
        assertEquals(listOf(true), scheduler.syncedValues)
        composeRule.onNodeWithText("未开启通知权限").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33, 35, 36])
    fun api33To36_notificationPermission_denyGuidesToSystemSettings_resumeRecovers_andGrantEnablesPolling() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val repo = TestSettingsRepo(AppSettings(notificationsEnabled = false))
        val scheduler = TestScheduler()
        val vm = SettingsViewModel(repo, scheduler, NoOpCacheManager, SettingsErrorLogStore { "" })
        val permOwner = ControlledPermissionRegistryOwner(nextGrantResult = false)
        val lifecycleOwner = ControlledLifecycleOwner()
        var settingsOpenerResult = false
        var settingsOpenCount = 0

        composeRule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides permOwner,
                LocalLifecycleOwner provides lifecycleOwner,
            ) {
                HiTheme {
                    SettingsDetailPane(
                        group = SettingsGroup.Notification,
                        viewModel = vm,
                        onOpenSystemNotificationSettings = {
                            settingsOpenCount++
                            settingsOpenerResult
                        },
                        showBack = false,
                        onBack = null,
                        onOpenThread = {},
                        blacklistContent = { _, _ -> },
                        contentPadding = PaddingValues(0.dp),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // 1. 首次拒绝（含系统“不再询问”永久拒绝直接返回 false）-> 不崩溃、保持关闭、原地展示引导卡片
        composeRule.onNodeWithText("接收提醒通知").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), permOwner.requestedPermissions)
        assertFalse("拒权后通知开关必须保持关闭", repo.state.value.notificationsEnabled)
        assertEquals(listOf(false), scheduler.syncedValues)
        composeRule.onNodeWithText("未开启通知权限").assertIsDisplayed()
        composeRule.onNodeWithText("打开系统设置")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        // 2. 系统设置无法打开（返回 false）-> 零崩溃且保持提示可见
        composeRule.onNodeWithText("打开系统设置").performClick()
        composeRule.waitForIdle()
        assertEquals(1, settingsOpenCount)
        composeRule.onNodeWithText("未开启通知权限").assertIsDisplayed()

        // 3. 跳转系统设置并在系统页授权后切回（ON_RESUME）-> 自动消除提示并开启提醒轮询
        settingsOpenerResult = true
        composeRule.onNodeWithText("打开系统设置").performClick()
        composeRule.waitForIdle()
        assertEquals(2, settingsOpenCount)

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        composeRule.runOnIdle {
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        composeRule.waitForIdle()

        assertTrue("从系统设置授权返回后应自动启用提醒通知", repo.state.value.notificationsEnabled)
        assertEquals(true, scheduler.syncedValues.last())
        composeRule.onNodeWithText("未开启通知权限").assertDoesNotExist()

        // 4. 手动关闭 -> 直接关闭，不触发权限弹窗
        val reqCountBeforeOff = permOwner.requestedPermissions.size
        composeRule.onNodeWithText("接收提醒通知").performClick()
        composeRule.waitForIdle()
        assertFalse(repo.state.value.notificationsEnabled)
        assertEquals(reqCountBeforeOff, permOwner.requestedPermissions.size)

        // 5. 权限撤回后重新点击开启并授予 -> 请求 POST_NOTIFICATIONS 并立即启用轮询
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        permOwner.nextGrantResult = true
        composeRule.onNodeWithText("接收提醒通知").performClick()
        composeRule.waitForIdle()

        assertEquals(reqCountBeforeOff + 1, permOwner.requestedPermissions.size)
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, permOwner.requestedPermissions.last())
        assertTrue("授权后应立即开启提醒通知", repo.state.value.notificationsEnabled)
        assertEquals(true, scheduler.syncedValues.last())
        composeRule.onNodeWithText("未开启通知权限").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [28, 33, 35, 36])
    fun systemNotificationSettingsOpener_handlesPrimaryFallbackAndMissingActivitySafely() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val launched = mutableListOf<Intent>()
        val primaryOpener = AndroidSystemNotificationSettingsOpener(app) { launched += it }
        assertTrue(primaryOpener.open())
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, launched.single().action)
        assertEquals(app.packageName, launched.single().getStringExtra(Settings.EXTRA_APP_PACKAGE))

        val fallbackLaunched = mutableListOf<Intent>()
        val fallbackOpener = AndroidSystemNotificationSettingsOpener(app) { intent ->
            if (intent.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) throw ActivityNotFoundException("no primary")
            fallbackLaunched += intent
        }
        assertTrue(fallbackOpener.open())
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, fallbackLaunched.single().action)
        assertEquals("package:${app.packageName}", fallbackLaunched.single().dataString)

        val brokenOpener = AndroidSystemNotificationSettingsOpener(app) { throw ActivityNotFoundException("none") }
        assertFalse("无系统设置入口时应安全返回 false 零崩溃", brokenOpener.open())
    }

    private class ControlledPermissionRegistryOwner(
        var nextGrantResult: Boolean,
    ) : ActivityResultRegistryOwner {
        val requestedPermissions = mutableListOf<String>()

        override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                if (input is String) requestedPermissions += input
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, nextGrantResult as O)
            }
        }
    }

    private class ControlledLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = registry

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }

    private class TestSettingsRepo(initial: AppSettings) : SettingsRepository {
        val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state
        override suspend fun setThemeMode(themeMode: ThemeMode) = state.update { it.copy(themeMode = themeMode) }
        override suspend fun setPureBlack(enabled: Boolean) = state.update { it.copy(pureBlack = enabled) }
        override suspend fun setThemeSeed(themeSeed: String) = state.update { it.copy(themeSeed = themeSeed) }
        override suspend fun setFontStep(step: Int) = state.update { it.copy(fontStep = step) }
        override suspend fun setLineSpacing(lineSpacing: LineSpacing) = state.update { it.copy(lineSpacing = lineSpacing) }
        override suspend fun setFollowedForums(forumIds: List<Int>) = state.update { it.copy(followedForums = forumIds) }
        override suspend fun setShowStickyThreads(show: Boolean) = state.update { it.copy(showStickyThreads = show) }
        override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) = state.update { it.copy(sortByPostTime = it.sortByPostTime + (fid to sortByPostTime)) }
        override suspend fun setTailEnabled(enabled: Boolean) = state.update { it.copy(tailEnabled = enabled) }
        override suspend fun setTailText(text: String) = state.update { it.copy(tailText = text) }
        override suspend fun setTailUrl(url: String) = state.update { it.copy(tailUrl = url) }
        override suspend fun setNotificationsEnabled(enabled: Boolean) = state.update { it.copy(notificationsEnabled = enabled) }
        override suspend fun setSilentModeEnabled(enabled: Boolean) = state.update { it.copy(silentModeEnabled = enabled) }
        override suspend fun setSilentBeginMinutes(minutes: Int) = state.update { it.copy(silentBeginMinutes = minutes) }
        override suspend fun setSilentEndMinutes(minutes: Int) = state.update { it.copy(silentEndMinutes = minutes) }
        override suspend fun setForumServer(server: String) = state.update { it.copy(forumServer = server) }
        override suspend fun setImageCacheLimitMb(limitMb: Int) = state.update { it.copy(imageCacheLimitMb = limitMb) }
        override suspend fun setPaneSplitPortrait(split: PaneSplit) = state.update { it.copy(paneSplitPortrait = split) }
        override suspend fun setPaneSplitLandscape(split: PaneSplit) = state.update { it.copy(paneSplitLandscape = split) }
        override suspend fun setSidebarCollapsedPortrait(collapsed: Boolean) = state.update { it.copy(sidebarCollapsedPortrait = collapsed) }
        override suspend fun setSidebarCollapsedLandscape(collapsed: Boolean) = state.update { it.copy(sidebarCollapsedLandscape = collapsed) }
    }

    private class TestScheduler : NotificationScheduler {
        val syncedValues = mutableListOf<Boolean>()
        override fun syncSchedule(notificationsEnabled: Boolean) { syncedValues += notificationsEnabled }
        override suspend fun syncFromSettings() = Unit
        override fun schedule() { syncedValues += true }
        override fun cancel() { syncedValues += false }
    }

    private object NoOpCacheManager : SettingsCacheManager {
        override suspend fun getImageCacheSizeBytes(): Long = 0L
        override suspend fun clearImageCache(): Long = 0L
        override suspend fun clearWebCache(): Long = 0L
    }
}
