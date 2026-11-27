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
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
 * M7-3 验收 2（含 R-M7-3 B1 修复）：覆盖 API 28 / 33 / 35 / 36 五种场景下「接收提醒通知」开关显示值
 * （`用户设置 && 有通知权限`）与一次点击行为：
 * 1. API 28（恒有权限，默认开、一次点击关/开均 0 权限弹窗）；
 * 2. 新装未授权（默认 `AppSettings().notificationsEnabled = true` 但无权限 -> 显示关、一次点击即请求权限、授权后写为开）；
 * 3. 授权后在系统设置撤销权限（`ON_RESUME` 立即刷新为关并展开降级横幅、一次点击直接请求权限或跳系统设置恢复）；
 * 4. 首次拒绝与「不再询问」永久拒绝（保持关、展开降级横幅与 ≥48dp 跳转系统设置入口、回流授权后自动开启）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w411dp-h891dp-xhdpi")
class NotificationPermissionMatrixTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(sdk = [28])
    fun api28_defaultOn_andSingleClickTogglesDirectlyWithoutPermissionRequest() {
        val repo = TestSettingsRepo(AppSettings())
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

        // API 28 恒具备通知权限，默认 AppSettings().notificationsEnabled == true 直接显示为开
        composeRule.onNodeWithText("接收提醒通知").assertIsOn()
        composeRule.onNodeWithText("未开启通知权限").assertDoesNotExist()

        // 一次点击直接关闭，0 权限请求
        composeRule.onNodeWithText("接收提醒通知").performClick()
        composeRule.waitForIdle()
        assertFalse(repo.state.value.notificationsEnabled)
        composeRule.onNodeWithText("接收提醒通知").assertIsOff()
        assertTrue("API 28 关闭通知不得请求权限", permOwner.requestedPermissions.isEmpty())

        // 再一次点击直接开启，0 权限请求、无横幅
        composeRule.onNodeWithText("接收提醒通知").performClick()
        composeRule.waitForIdle()
        assertTrue("API 28 应直接开启提醒通知", repo.state.value.notificationsEnabled)
        composeRule.onNodeWithText("接收提醒通知").assertIsOn()
        assertTrue("API 28 开启通知不得请求 POST_NOTIFICATIONS", permOwner.requestedPermissions.isEmpty())
        assertEquals(listOf(false, true), scheduler.syncedValues)
        composeRule.onNodeWithText("未开启通知权限").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33, 35, 36])
    fun api33To36_freshInstallWithoutPermission_showsOffAndSingleClickRequestsPermissionThenWritesOnWhenGranted() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // 真实全新安装默认态：AppSettings().notificationsEnabled == true，但系统未授予 POST_NOTIFICATIONS
        val repo = TestSettingsRepo(AppSettings())
        val scheduler = TestScheduler()
        val vm = SettingsViewModel(repo, scheduler, NoOpCacheManager, SettingsErrorLogStore { "" })
        val permOwner = ControlledPermissionRegistryOwner(
            nextGrantResult = true,
            autoDispatch = false,
        )

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

        // 开关显示值 = 用户设置 && 有通知权限 -> 新装未授权必显示关
        composeRule.onNodeWithText("接收提醒通知").assertIsOff()
        composeRule.onNodeWithText("未开启通知权限").assertDoesNotExist()

        // 第一次点击必须直接发起 POST_NOTIFICATIONS 权限请求，且在授权回传前不先改写设置
        composeRule.onNodeWithText("接收提醒通知").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), permOwner.requestedPermissions)
        assertTrue("授权结果未返回前不应先写入设置或触发调度", scheduler.syncedValues.isEmpty())
        composeRule.onNodeWithText("接收提醒通知").assertIsOff()

        // 用户点击允许后，写入设置开并同步轮询调度，开关立即显示为开
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        composeRule.runOnIdle { permOwner.dispatchPending(true) }
        composeRule.waitForIdle()

        assertTrue(repo.state.value.notificationsEnabled)
        assertEquals(listOf(true), scheduler.syncedValues)
        composeRule.onNodeWithText("接收提醒通知").assertIsOn()
        composeRule.onNodeWithText("未开启通知权限").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33, 35, 36])
    fun api33To36_revokedInSystemSettings_onResumeShowsOffAndBanner_andSingleClickRequestsPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val repo = TestSettingsRepo(AppSettings(notificationsEnabled = true))
        val scheduler = TestScheduler()
        val vm = SettingsViewModel(repo, scheduler, NoOpCacheManager, SettingsErrorLogStore { "" })
        val permOwner = ControlledPermissionRegistryOwner(nextGrantResult = false)
        val lifecycleOwner = ControlledLifecycleOwner()
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
                        onOpenSystemNotificationSettings = { settingsOpenCount++; true },
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

        // 初始已授权且用户开启 -> 显示为开，无降级横幅
        composeRule.onNodeWithText("接收提醒通知").assertIsOn()
        composeRule.onNodeWithText("未开启通知权限").assertDoesNotExist()

        // 切至系统设置撤销通知权限后返回（ON_RESUME）-> 立即刷新为关并展开降级引导横幅
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        composeRule.runOnIdle {
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("接收提醒通知").assertIsOff()
        composeRule.onNodeWithText("未开启通知权限").assertIsDisplayed()
        composeRule.onNodeWithText("打开系统设置")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        // 撤权后第一次点开关直接发起权限请求（不先关开关）
        composeRule.onNodeWithText("接收提醒通知").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), permOwner.requestedPermissions)
        composeRule.onNodeWithText("接收提醒通知").assertIsOff()
        composeRule.onNodeWithText("未开启通知权限").assertIsDisplayed()

        // 通过引导按钮跳系统设置重新授权后 ON_RESUME -> 自动消除横幅并恢复开启
        composeRule.onNodeWithText("打开系统设置").performClick()
        composeRule.waitForIdle()
        assertEquals(1, settingsOpenCount)

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        composeRule.runOnIdle {
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        composeRule.waitForIdle()

        assertTrue(repo.state.value.notificationsEnabled)
        assertEquals(true, scheduler.syncedValues.last())
        composeRule.onNodeWithText("接收提醒通知").assertIsOn()
        composeRule.onNodeWithText("未开启通知权限").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33, 35, 36])
    fun api33To36_deniedAndDontAskAgain_showsBannerGuidesToSystemSettings_andRecoversOnResume() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val repo = TestSettingsRepo(AppSettings())
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
        composeRule.onNodeWithText("接收提醒通知").assertIsOff()

        // 1. 首次拒绝 -> 保持关、写 false、展开降级引导横幅
        composeRule.onNodeWithText("接收提醒通知").performClick()
        composeRule.waitForIdle()
        assertEquals(1, permOwner.requestedPermissions.size)
        assertFalse("拒权后通知开关必须保持关闭", repo.state.value.notificationsEnabled)
        assertEquals(listOf(false), scheduler.syncedValues)
        composeRule.onNodeWithText("接收提醒通知").assertIsOff()
        composeRule.onNodeWithText("未开启通知权限").assertIsDisplayed()
        composeRule.onNodeWithText("打开系统设置")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)

        // 2. “不再询问”（永久拒绝：系统直接回调 false 不弹框）-> 再次单击仍不崩溃、保持关与引导横幅
        composeRule.onNodeWithText("接收提醒通知").performClick()
        composeRule.waitForIdle()
        assertEquals(2, permOwner.requestedPermissions.size)
        assertFalse(repo.state.value.notificationsEnabled)
        composeRule.onNodeWithText("接收提醒通知").assertIsOff()
        composeRule.onNodeWithText("未开启通知权限").assertIsDisplayed()

        // 3. 系统设置无法打开（返回 false）-> 零崩溃且保持提示可见
        composeRule.onNodeWithText("打开系统设置").performClick()
        composeRule.waitForIdle()
        assertEquals(1, settingsOpenCount)
        composeRule.onNodeWithText("未开启通知权限").assertIsDisplayed()

        // 4. 跳系统设置授权后 ON_RESUME 回流 -> 自动清除横幅、写入开启并同步调度
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
        composeRule.onNodeWithText("接收提醒通知").assertIsOn()
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
        private val autoDispatch: Boolean = true,
    ) : ActivityResultRegistryOwner {
        val requestedPermissions = mutableListOf<String>()
        private var pendingRequestCode: Int? = null

        override val activityResultRegistry: ActivityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                if (input is String) requestedPermissions += input
                if (autoDispatch) {
                    @Suppress("UNCHECKED_CAST")
                    dispatchResult(requestCode, nextGrantResult as O)
                } else {
                    pendingRequestCode = requestCode
                }
            }
        }

        fun dispatchPending(granted: Boolean) {
            val code = requireNotNull(pendingRequestCode) { "No pending permission request" }
            pendingRequestCode = null
            activityResultRegistry.dispatchResult(code, granted)
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
