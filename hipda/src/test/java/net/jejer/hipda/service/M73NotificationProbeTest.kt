package net.jejer.hipda.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.data.account.CredentialsStore
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthState
import net.jejer.hipda.data.auth.LoginResult
import net.jejer.hipda.data.auth.SilentReloginResult
import net.jejer.hipda.data.message.MessageResult
import net.jejer.hipda.data.message.NotificationPollResult
import net.jejer.hipda.data.message.NotifyEntry
import net.jejer.hipda.data.message.NotifyFeed
import net.jejer.hipda.data.message.NotifyRepository
import net.jejer.hipda.data.message.UnreadSnapshot
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.LineSpacing
import net.jejer.hipda.data.settings.PaneSplit
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.settings.ThemeMode
import net.jejer.hipda.feature.common.HiThemeSettingsAdapter
import net.jejer.hipda.feature.common.SettingsGroup
import net.jejer.hipda.feature.settings.AndroidSystemNotificationSettingsOpener
import net.jejer.hipda.feature.settings.SettingsCacheManager
import net.jejer.hipda.feature.settings.SettingsDetailPane
import net.jejer.hipda.feature.settings.SettingsErrorLogStore
import net.jejer.hipda.feature.settings.SettingsViewModel
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.ui.designsystem.HiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.w3c.dom.Element

/**
 * 独立验证探针 1（M7-3 Target SDK 36）：
 * - 清单与权限审计（targetSdk 36 / applicationId / maxSdkVersion=28 / enableOnBackInvokedCallback / queries / FileProvider）；
 * - 设置页通知开关在 API 28 / 33 / 35 / 36 矩阵下的权限申请、拒绝降级与 ON_RESUME 自动恢复；
 * - NotificationPollingWorker 与 AndroidNotificationPoster 在未授权（0 网络 0 通知 0 崩溃）/ 授权后 / API 28 免权路径的真实表现。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [28, 33, 35, 36], qualifiers = "w411dp-h891dp-xhdpi")
class M73NotificationProbeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(sdk = [36])
    fun manifestAudit_targetSdk36_applicationId_permissions_queries_andPredictiveBack() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("net.jejer.hipda.next", app.packageName)
        assertEquals(36, app.applicationInfo.targetSdkVersion)

        val pm = app.packageManager
        @Suppress("DEPRECATION")
        val pkgInfo = pm.getPackageInfo(
            app.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA,
        )
        val requested = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue("缺少 POST_NOTIFICATIONS", Manifest.permission.POST_NOTIFICATIONS in requested)
        // Android 10+（此处 SDK 36）包管理器在解析清单时会自动失效 maxSdkVersion=28 的 WRITE_EXTERNAL_STORAGE，
        // 若仍出现在 requestedPermissions 则说明 maxSdkVersion 未生效。
        assertFalse(
            "API 36 不应再实际持有/请求 maxSdkVersion=28 的 WRITE_EXTERNAL_STORAGE，实际: $requested",
            Manifest.permission.WRITE_EXTERNAL_STORAGE in requested,
        )
        assertFalse("禁止申请 MANAGE_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE" in requested)
        assertFalse("禁止申请 READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_IMAGES" in requested)
        assertFalse("禁止申请 READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VIDEO" in requested)
        assertFalse("禁止申请 READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" in requested)

        val activities = pkgInfo.activities?.associateBy { it.name }.orEmpty()
        assertTrue("主入口 MainActivity 必须 exported=true", activities["net.jejer.hipda.feature.app.MainActivity"]?.exported == true)
        assertTrue("ChallengeActivity 必须保持 exported=false", activities["net.jejer.hipda.ui.challenge.ChallengeActivity"]?.exported == false)

        // 旧包名入口 IntentActivity activity-alias 可正常解析外部线程链接
        val threadLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.4d4y.com/forum/viewthread.php?tid=123"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val resolvedAliases = pm.queryIntentActivities(threadLinkIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.name }
        assertTrue(
            "activity-alias .ng.IntentActivity 必须可解析 https 论坛链接，实际: $resolvedAliases",
            "net.jejer.hipda.ng.IntentActivity" in resolvedAliases,
        )

        val fileProvider = pkgInfo.providers?.firstOrNull { it.authority == "${app.packageName}.provider" }
        assertNotNull("FileProvider authority 必须为 ${app.packageName}.provider", fileProvider)
        assertFalse("FileProvider 必须 exported=false", fileProvider!!.exported)
        assertTrue("FileProvider 必须 grantUriPermissions=true", fileProvider.grantUriPermissions)

        // 校验源码 AndroidManifest.xml 声明属性（maxSdkVersion=28 / enableOnBackInvokedCallback / queries / 无方向锁）
        val manifestFile = resolveManifestFile()
        val xmlText = manifestFile.readText()
        assertFalse("禁止在 manifest 硬编码 screenOrientation", xmlText.contains("android:screenOrientation"))
        assertFalse("禁止在 manifest 硬编码 resizeableActivity=false", xmlText.contains("android:resizeableActivity=\"false\""))

        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifestFile)
        val appElement = doc.getElementsByTagName("application").item(0) as Element
        assertEquals(
            "true",
            appElement.getAttributeNS("http://schemas.android.com/apk/res/android", "enableOnBackInvokedCallback"),
        )
        assertEquals(
            "@xml/backup_config",
            appElement.getAttributeNS("http://schemas.android.com/apk/res/android", "fullBackupContent"),
        )
        assertEquals(
            "@xml/data_extraction_rules",
            appElement.getAttributeNS("http://schemas.android.com/apk/res/android", "dataExtractionRules"),
        )

        val permissions = (0 until doc.getElementsByTagName("uses-permission").length)
            .map { doc.getElementsByTagName("uses-permission").item(it) as Element }
        val writePerm = permissions.firstOrNull {
            it.getAttributeNS("http://schemas.android.com/apk/res/android", "name") == Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        assertNotNull("必须声明 WRITE_EXTERNAL_STORAGE", writePerm)
        assertEquals(
            "28",
            writePerm!!.getAttributeNS("http://schemas.android.com/apk/res/android", "maxSdkVersion"),
        )

        val queriesNodes = doc.getElementsByTagName("queries")
        assertTrue("Target SDK 30+ 必须声明 <queries> 以查询浏览器与 mailto", queriesNodes.length == 1)
        val queryXml = xmlText.substringAfter("<queries>").substringBefore("</queries>")
        assertTrue(queryXml.contains("android:scheme=\"https\""))
        assertTrue(queryXml.contains("android:scheme=\"http\""))
        assertTrue(queryXml.contains("android:scheme=\"mailto\""))
        assertTrue(queryXml.contains("android.intent.category.APP_BROWSER"))
    }

    @Test
    @Config(sdk = [28])
    fun helperMatrix_sdk28IncludesLegacyWriteStoragePermissionInRequestedPermissions() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        @Suppress("DEPRECATION")
        val pkgInfo = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        val requested = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue("API 28 必须保留 WRITE_EXTERNAL_STORAGE 请求权限", Manifest.permission.WRITE_EXTERNAL_STORAGE in requested)
    }

    @Test
    fun helperMatrix_matchesApi33ThresholdAcrossSdk28_33_35_36() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val expectedNeedsPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        assertEquals(expectedNeedsPerm, requiresNotificationPermission())

        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(!expectedNeedsPerm, hasNotificationPermission(app))

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(hasNotificationPermission(app))
    }

    @Test
    fun settingsNotificationToggle_permissionGrantDenyDegradeAndOnResumeRecovery() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val settingsRepo = ProbeSettingsRepository(AppSettings(notificationsEnabled = false))
        val scheduler = ProbeNotificationScheduler()
        val vm = SettingsViewModel(
            settingsRepository = settingsRepo,
            notificationScheduler = scheduler,
            cacheManager = ProbeCacheManager(),
            errorLogStore = SettingsErrorLogStore { "" },
        )

        val launchedPermissions = CopyOnWriteArrayList<String>()
        var nextPermissionGrantResult = false
        val openedSettingsIntents = CopyOnWriteArrayList<Intent>()

        val lifecycleOwner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
            override val lifecycle: Lifecycle get() = registry
        }

        val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                if (contract is ActivityResultContracts.RequestPermission) {
                    val permission = input as String
                    launchedPermissions += permission
                    if (nextPermissionGrantResult) {
                        shadowOf(app).grantPermissions(permission)
                    } else {
                        shadowOf(app).denyPermissions(permission)
                    }
                    @Suppress("UNCHECKED_CAST")
                    dispatchResult(requestCode, nextPermissionGrantResult)
                }
            }
        }
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = activityResultRegistry
        }
        val opener = AndroidSystemNotificationSettingsOpener(
            context = app,
            sdkInt = Build.VERSION.SDK_INT,
            launcher = { intent -> openedSettingsIntents += intent },
        )

        composeRule.setContent {
            val uiState by vm.uiState.collectAsStateWithLifecycle()
            val cfg = HiThemeSettingsAdapter.toThemeConfig(uiState.settings)
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides registryOwner,
                LocalLifecycleOwner provides lifecycleOwner,
            ) {
                HiTheme(cfg.themeMode, cfg.pureBlack, cfg.seed, cfg.fontSizeStep, cfg.lineHeightPreset) {
                    SettingsDetailPane(
                        group = SettingsGroup.Notification,
                        viewModel = vm,
                        onOpenSystemNotificationSettings = opener::open,
                        showBack = true,
                        onBack = {},
                        onOpenThread = {},
                        blacklistContent = { _, _ -> },
                        contentPadding = PaddingValues(0.dp),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // API 28：无需 POST_NOTIFICATIONS，点击直接开启且 0 权限弹窗
            composeRule.onNodeWithText("接收提醒通知").performClick()
            composeRule.waitForIdle()
            assertTrue("API 28 不得发起通知权限申请", launchedPermissions.isEmpty())
            assertTrue("API 28 必须直接启用通知", settingsRepo.state.value.notificationsEnabled)
            assertEquals(listOf(true), scheduler.syncedValues)
            assertEquals(0, composeRule.onAllNodesWithText("未开启通知权限").fetchSemanticsNodes().size)
            return
        }

        // API 33/35/36 场景 A：拒绝权限 -> 不崩溃、保持关闭并降级展示提示与系统设置入口
        nextPermissionGrantResult = false
        composeRule.onNodeWithText("接收提醒通知").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), launchedPermissions.toList())
        assertFalse("拒绝权限后通知开关不得被错误打开", settingsRepo.state.value.notificationsEnabled)
        composeRule.onNodeWithText("未开启通知权限").assertIsDisplayed()
        composeRule.onNodeWithText("打开系统设置").assertIsDisplayed()

        // 点击「打开系统设置」跳转包通知设置页；验证 primary 缺失时回退应用详情页
        composeRule.onNodeWithText("打开系统设置").performClick()
        composeRule.waitForIdle()
        assertEquals(1, openedSettingsIntents.size)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, openedSettingsIntents.single().action)
        assertEquals(app.packageName, openedSettingsIntents.single().getStringExtra(Settings.EXTRA_APP_PACKAGE))

        val fallbackOpened = CopyOnWriteArrayList<Intent>()
        val fallbackOpener = AndroidSystemNotificationSettingsOpener(
            context = app,
            sdkInt = Build.VERSION.SDK_INT,
            launcher = { intent ->
                if (intent.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                    throw ActivityNotFoundException("no notification settings")
                }
                fallbackOpened += intent
            },
        )
        assertTrue(fallbackOpener.open())
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, fallbackOpened.single().action)
        assertEquals("package:${app.packageName}", fallbackOpened.single().dataString)

        // 模拟用户在系统设置授予权限后回到界面（ON_RESUME）自动启用并消失降级条
        composeRule.runOnUiThread {
            lifecycleOwner.registry.currentState = Lifecycle.State.STARTED
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()
        assertTrue("系统设置授权返回 ON_RESUME 后必须自动启用通知", settingsRepo.state.value.notificationsEnabled)
        assertTrue("自动启用通知必须同步调度 Worker", scheduler.syncedValues.lastOrNull() == true)
        assertEquals(0, composeRule.onAllNodesWithText("未开启通知权限").fetchSemanticsNodes().size)

        // 场景 B：关掉再开启，系统权限弹窗直接授予 -> 立即启用
        composeRule.onNodeWithText("接收提醒通知").performClick()
        composeRule.waitForIdle()
        assertFalse(settingsRepo.state.value.notificationsEnabled)

        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        nextPermissionGrantResult = true
        composeRule.onNodeWithText("接收提醒通知").performClick()
        composeRule.waitForIdle()
        assertTrue(settingsRepo.state.value.notificationsEnabled)
        assertEquals(2, launchedPermissions.size)
        assertEquals(0, composeRule.onAllNodesWithText("未开启通知权限").fetchSemanticsNodes().size)
    }

    @Test
    @Config(sdk = [33, 35, 36])
    fun notificationSwitch_freshInstallUngranted_displaysOffAndSingleClickRequestsPermission() {
        val harness = mountNotificationSettings(
            app = ApplicationProvider.getApplicationContext(),
            initialEnabled = true, // 新装默认偏好为开启，但系统权限尚未授予
            systemGrantedInitially = false,
        )
        notificationSwitchNode().assertIsOff()
        assertEquals(0, composeRule.onAllNodesWithText("未开启通知权限").fetchSemanticsNodes().size)

        // 单击：因未授权不得误判为“关闭”，必须发起 POST_NOTIFICATIONS 申请
        harness.nextGrantResult = true
        notificationSwitchNode().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), harness.launchedPermissions.toList())
        notificationSwitchNode().assertIsOn()
        assertTrue(harness.settingsRepo.state.value.notificationsEnabled)
        assertTrue(harness.scheduler.syncedValues.lastOrNull() == true)
    }

    @Test
    @Config(sdk = [33, 35, 36])
    fun notificationSwitch_revokedInSettings_returnToSettingsSyncsOffAndShowsBanner() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val harness = mountNotificationSettings(
            app = app,
            initialEnabled = true,
            systemGrantedInitially = true,
        )
        notificationSwitchNode().assertIsOn()
        assertEquals(0, composeRule.onAllNodesWithText("未开启通知权限").fetchSemanticsNodes().size)

        // 切出设置页并在系统设置撤销通知权限后返回（ON_RESUME）
        composeRule.runOnUiThread {
            harness.lifecycleRegistry.currentState = Lifecycle.State.STARTED
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            harness.lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()

        // 开关视觉状态必须立即同步关闭并出现降级引导条，同时保留用户开启意图以便重授后自动恢复
        notificationSwitchNode().assertIsOff()
        composeRule.onNodeWithText("未开启通知权限").assertIsDisplayed()
        composeRule.onNodeWithText("打开系统设置").assertIsDisplayed()
        assertTrue(harness.settingsRepo.state.value.notificationsEnabled)

        // 情形 2a：撤权后单击开关 -> 仍发起系统权限申请而非关闭偏好；授予即恢复 ON
        harness.nextGrantResult = true
        notificationSwitchNode().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), harness.launchedPermissions.toList())
        notificationSwitchNode().assertIsOn()
        assertEquals(0, composeRule.onAllNodesWithText("未开启通知权限").fetchSemanticsNodes().size)

        // 情形 2b：再次撤权 -> 打开系统设置授予后 ON_RESUME 返回 -> 无需再点开关即自动恢复 ON
        composeRule.runOnUiThread {
            harness.lifecycleRegistry.currentState = Lifecycle.State.STARTED
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            harness.lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()
        notificationSwitchNode().assertIsOff()
        composeRule.onNodeWithText("打开系统设置").performClick()
        composeRule.waitForIdle()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, harness.openedSettingsIntents.last().action)

        composeRule.runOnUiThread {
            harness.lifecycleRegistry.currentState = Lifecycle.State.STARTED
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            harness.lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()
        notificationSwitchNode().assertIsOn()
        assertEquals(0, composeRule.onAllNodesWithText("未开启通知权限").fetchSemanticsNodes().size)
    }

    @Test
    @Config(sdk = [33, 35, 36])
    fun notificationSwitch_denied_displaysOffShowsBannerAndSingleClickPromptsAgain() {
        val harness = mountNotificationSettings(
            app = ApplicationProvider.getApplicationContext(),
            initialEnabled = false,
            systemGrantedInitially = false,
        )
        notificationSwitchNode().assertIsOff()

        // 首次拒绝 -> 开关维持 OFF、降级条与系统设置按钮可见
        harness.nextGrantResult = false
        notificationSwitchNode().performClick()
        composeRule.waitForIdle()
        assertEquals(1, harness.launchedPermissions.size)
        notificationSwitchNode().assertIsOff()
        assertFalse(harness.settingsRepo.state.value.notificationsEnabled)
        composeRule.onNodeWithText("未开启通知权限").assertIsDisplayed()
        composeRule.onNodeWithText("打开系统设置").assertIsDisplayed()

        // 拒绝后再次单击开关 -> 仍可正常发起申请，若此时授予即转 ON 并隐藏提示条
        harness.nextGrantResult = true
        notificationSwitchNode().performClick()
        composeRule.waitForIdle()
        assertEquals(2, harness.launchedPermissions.size)
        notificationSwitchNode().assertIsOn()
        assertTrue(harness.settingsRepo.state.value.notificationsEnabled)
        assertEquals(0, composeRule.onAllNodesWithText("未开启通知权限").fetchSemanticsNodes().size)
    }

    @Test
    @Config(sdk = [33, 35, 36])
    fun notificationSwitch_dontAskAgain_staysOffWithActionableBannerAndRecoversViaSettings() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val harness = mountNotificationSettings(
            app = app,
            initialEnabled = false,
            systemGrantedInitially = false,
        )
        // 模拟“不再询问”：每次 RequestPermission 都被系统零交互立即回调 false
        harness.nextGrantResult = false

        notificationSwitchNode().performClick()
        composeRule.waitForIdle()
        notificationSwitchNode().performClick()
        composeRule.waitForIdle()

        assertEquals(2, harness.launchedPermissions.size)
        notificationSwitchNode().assertIsOff()
        assertFalse(harness.settingsRepo.state.value.notificationsEnabled)
        composeRule.onNodeWithText("未开启通知权限").assertIsDisplayed()
        composeRule.onNodeWithText("打开系统设置").assertIsDisplayed()

        // 通过提示条直达系统设置授权 -> ON_RESUME 自动恢复 ON 且消失提示条
        composeRule.onNodeWithText("打开系统设置").performClick()
        composeRule.waitForIdle()
        assertEquals(1, harness.openedSettingsIntents.size)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, harness.openedSettingsIntents.single().action)

        composeRule.runOnUiThread {
            harness.lifecycleRegistry.currentState = Lifecycle.State.STARTED
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            harness.lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()
        notificationSwitchNode().assertIsOn()
        assertTrue(harness.settingsRepo.state.value.notificationsEnabled)
        assertEquals(0, composeRule.onAllNodesWithText("未开启通知权限").fetchSemanticsNodes().size)
    }

    private fun notificationSwitchNode() =
        composeRule.onNode(isToggleable() and hasText("接收提醒通知"))

    private class NotificationUiHarness(
        val settingsRepo: ProbeSettingsRepository,
        val scheduler: ProbeNotificationScheduler,
        val lifecycleRegistry: LifecycleRegistry,
        val launchedPermissions: CopyOnWriteArrayList<String>,
        val openedSettingsIntents: CopyOnWriteArrayList<Intent>,
        var nextGrantResult: Boolean = false,
    )

    private fun mountNotificationSettings(
        app: Application,
        initialEnabled: Boolean,
        systemGrantedInitially: Boolean,
    ): NotificationUiHarness {
        if (systemGrantedInitially) {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        val settingsRepo = ProbeSettingsRepository(AppSettings(notificationsEnabled = initialEnabled))
        val scheduler = ProbeNotificationScheduler()
        val vm = SettingsViewModel(
            settingsRepository = settingsRepo,
            notificationScheduler = scheduler,
            cacheManager = ProbeCacheManager(),
            errorLogStore = SettingsErrorLogStore { "" },
        )
        val launchedPermissions = CopyOnWriteArrayList<String>()
        val openedSettingsIntents = CopyOnWriteArrayList<Intent>()
        lateinit var harnessRef: NotificationUiHarness
        val lifecycleOwner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                if (contract is ActivityResultContracts.RequestPermission) {
                    val permission = input as String
                    launchedPermissions += permission
                    if (harnessRef.nextGrantResult) shadowOf(app).grantPermissions(permission)
                    else shadowOf(app).denyPermissions(permission)
                    @Suppress("UNCHECKED_CAST")
                    dispatchResult(requestCode, harnessRef.nextGrantResult)
                }
            }
        }
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = activityResultRegistry
        }
        val opener = AndroidSystemNotificationSettingsOpener(
            context = app,
            sdkInt = Build.VERSION.SDK_INT,
            launcher = { intent -> openedSettingsIntents += intent },
        )
        harnessRef = NotificationUiHarness(
            settingsRepo = settingsRepo,
            scheduler = scheduler,
            lifecycleRegistry = lifecycleOwner.registry,
            launchedPermissions = launchedPermissions,
            openedSettingsIntents = openedSettingsIntents,
        )

        composeRule.setContent {
            val uiState by vm.uiState.collectAsStateWithLifecycle()
            val cfg = HiThemeSettingsAdapter.toThemeConfig(uiState.settings)
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides registryOwner,
                LocalLifecycleOwner provides lifecycleOwner,
            ) {
                HiTheme(cfg.themeMode, cfg.pureBlack, cfg.seed, cfg.fontSizeStep, cfg.lineHeightPreset) {
                    SettingsDetailPane(
                        group = SettingsGroup.Notification,
                        viewModel = vm,
                        onOpenSystemNotificationSettings = opener::open,
                        showBack = true,
                        onBack = {},
                        onOpenThread = {},
                        blacklistContent = { _, _ -> },
                        contentPadding = PaddingValues(0.dp),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        return harnessRef
    }

    @Test
    fun workerAndPoster_skipsSilentlyWhenPermissionDenied_andPostsWhenAllowed() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val notificationManager = app.getSystemService(NotificationManager::class.java)
        val shadowNm = shadowOf(notificationManager)
        shadowNm.allNotifications.clear()

        val account = SavedAccount(uid = "10000", username = "HiProbeUser", passwordMd5 = "0".repeat(32), lastLoginAt = 1_000L)
        val settingsRepo = ProbeSettingsRepository(
            AppSettings(
                notificationsEnabled = true,
                silentModeEnabled = false,
            ),
        )
        val authRepo = ProbeAuthRepository(account)
        val credentialsStore = ProbeCredentialsStore(listOf(account), activeUid = account.uid)
        val notifyRepo = ProbeNotifyRepository(
            pollOutcome = NotificationPollResult.Success(
                UnreadSnapshot(
                    smsUnreadCount = 2,
                    notifyUnreadCount = 3,
                    promptCounts = PromptCounts(pm = 2, systemPm = 3),
                ),
            ),
        )
        val realPoster = AndroidNotificationPoster(app)

        fun buildWorker(): NotificationPollingWorker =
            TestListenableWorkerBuilder<NotificationPollingWorker>(app)
                .setWorkerFactory(
                    object : WorkerFactory() {
                        override fun createWorker(
                            appContext: android.content.Context,
                            workerClassName: String,
                            workerParameters: WorkerParameters,
                        ): ListenableWorker = NotificationPollingWorker(
                            appContext = appContext,
                            workerParams = workerParameters,
                            settingsRepository = settingsRepo,
                            authRepository = authRepo,
                            credentialsStore = credentialsStore,
                            notifyRepository = notifyRepo,
                            notificationPoster = realPoster,
                            appVisibilityProvider = AppVisibilityProvider { false },
                            pollingClock = PollingClock { 12 * 60 },
                        )
                    },
                )
                .build()

        // 1. 显式拒绝 POST_NOTIFICATIONS 时执行 Worker：
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val deniedResult = buildWorker().doWork()
        assertEquals(ListenableWorker.Result.success(), deniedResult)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ 未授权：0 网络请求、0 通知、0 崩溃、设置保持不变
            assertEquals("API 33+ 无权限时不得发起网络轮询", 0, notifyRepo.pollCallCount.get())
            assertTrue("API 33+ 无权限时不得发出系统通知", shadowNm.allNotifications.isEmpty())
            assertTrue("后台轮询不得擅自修改用户已开启的通知设置", settingsRepo.state.value.notificationsEnabled)

            // 直接调用 Poster 也必须保护性忽略而不崩
            realPoster.postUnread(UnreadSnapshot(smsUnreadCount = 1, notifyUnreadCount = 2))
            assertTrue(shadowNm.allNotifications.isEmpty())

            // 授予权限后再次运行 Worker -> 正常发通知，PendingIntent 携带 FLAG_IMMUTABLE
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val grantedResult = buildWorker().doWork()
            assertEquals(ListenableWorker.Result.success(), grantedResult)
            assertEquals(1, notifyRepo.pollCallCount.get())
            assertEquals(1, shadowNm.allNotifications.size)
            verifyPostedNotificationImmutableAndChannel(shadowNm.allNotifications.single(), shadowNm)
        } else {
            // API 28 无需 POST_NOTIFICATIONS：直接轮询并正常发出通知
            assertEquals("API 28 无需运行时权限应正常轮询", 1, notifyRepo.pollCallCount.get())
            assertEquals("API 28 无需运行时权限应正常发出系统通知", 1, shadowNm.allNotifications.size)
            verifyPostedNotificationImmutableAndChannel(shadowNm.allNotifications.single(), shadowNm)
        }
    }

    private fun verifyPostedNotificationImmutableAndChannel(
        notification: Notification,
        shadowNm: org.robolectric.shadows.ShadowNotificationManager,
    ) {
        val pending = notification.contentIntent
        assertNotNull("通知必须携带 PendingIntent", pending)
        val shadowPending = shadowOf(pending)
        assertTrue(
            "Target SDK 31+ PendingIntent 必须显式包含 FLAG_IMMUTABLE",
            (shadowPending.flags and PendingIntent.FLAG_IMMUTABLE) != 0,
        )
        val channelIds = shadowNm.notificationChannels.map { (it as android.app.NotificationChannel).id }
        assertTrue("通知渠道 HIPDA_NOTI 必须已创建", AndroidNotificationPoster.CHANNEL_ID in channelIds)
    }

    private fun resolveManifestFile(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(userDir, "src/main/AndroidManifest.xml"),
            File(userDir, "hipda/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 src/main/AndroidManifest.xml，当前 user.dir=$userDir")
    }

    private class ProbeSettingsRepository(initial: AppSettings) : SettingsRepository {
        val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state
        override suspend fun setThemeMode(themeMode: ThemeMode) = state.update { it.copy(themeMode = themeMode) }
        override suspend fun setPureBlack(enabled: Boolean) = state.update { it.copy(pureBlack = enabled) }
        override suspend fun setThemeSeed(themeSeed: String) = state.update { it.copy(themeSeed = themeSeed) }
        override suspend fun setFontStep(step: Int) = state.update { it.copy(fontStep = step) }
        override suspend fun setLineSpacing(lineSpacing: LineSpacing) = state.update { it.copy(lineSpacing = lineSpacing) }
        override suspend fun setFollowedForums(forumIds: List<Int>) = state.update { it.copy(followedForums = forumIds) }
        override suspend fun setShowStickyThreads(show: Boolean) = state.update { it.copy(showStickyThreads = show) }
        override suspend fun setSortByPostTime(fid: Int, sortByPostTime: Boolean) = state.update {
            it.copy(sortByPostTime = it.sortByPostTime + (fid to sortByPostTime))
        }
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

    private class ProbeNotificationScheduler : NotificationScheduler {
        val syncedValues = CopyOnWriteArrayList<Boolean>()
        override fun syncSchedule(notificationsEnabled: Boolean) {
            syncedValues += notificationsEnabled
        }
        override suspend fun syncFromSettings() = Unit
        override fun schedule() {
            syncedValues += true
        }
        override fun cancel() {
            syncedValues += false
        }
    }

    private class ProbeCacheManager : SettingsCacheManager {
        override suspend fun getImageCacheSizeBytes(): Long = 0L
        override suspend fun clearImageCache(): Long = 0L
        override suspend fun clearWebCache(): Long = 0L
    }

    private class ProbeAuthRepository(account: SavedAccount) : AuthRepository {
        private val _current = MutableStateFlow<SavedAccount?>(account)
        private val _saved = MutableStateFlow(listOf(account))
        private val _authState = MutableStateFlow<AuthState>(AuthState.SignedIn(account))
        override val currentAccount: StateFlow<SavedAccount?> = _current.asStateFlow()
        override val savedAccounts: StateFlow<List<SavedAccount>> = _saved.asStateFlow()
        override val authState: StateFlow<AuthState> = _authState.asStateFlow()
        override val sessionEpoch: Long = 1L
        override suspend fun login(username: String, passwordOrMd5: String, securityQuestionId: Int, securityAnswer: String): LoginResult =
            error("unused")
        override suspend fun switchAccount(uid: String): LoginResult = error("unused")
        override suspend fun logout() {
            _current.value = null
            _authState.value = AuthState.SignedOut
        }
        override suspend fun removeAccount(uid: String) = Unit
        override suspend fun trySilentRelogin(failedEpoch: Long, priority: AccessPriority): SilentReloginResult =
            SilentReloginResult.Suppressed
    }

    private class ProbeCredentialsStore(
        initialAccounts: List<SavedAccount>,
        activeUid: String?,
    ) : CredentialsStore {
        private val _accounts = MutableStateFlow(initialAccounts)
        private val _activeUid = MutableStateFlow(activeUid)
        private val _manualLogout = MutableStateFlow(false)
        override val accounts: Flow<List<SavedAccount>> = _accounts
        override val activeUid: Flow<String?> = _activeUid
        override val manuallyLoggedOut: Flow<Boolean> = _manualLogout
        override suspend fun upsert(account: SavedAccount) = Unit
        override suspend fun setActive(uid: String) = Unit
        override suspend fun markManualLogout() = Unit
        override suspend fun remove(uid: String) = Unit
    }

    private class ProbeNotifyRepository(
        private val pollOutcome: NotificationPollResult,
    ) : NotifyRepository {
        val pollCallCount = AtomicInteger(0)
        private val _smsUnread = MutableStateFlow(0)
        private val _notifyUnread = MutableStateFlow(0)
        private val _entries = MutableStateFlow<List<NotifyEntry>>(emptyList())
        override val smsUnreadCount: StateFlow<Int> = _smsUnread.asStateFlow()
        override val notifyUnreadCount: StateFlow<Int> = _notifyUnread.asStateFlow()
        override val notifications: StateFlow<List<NotifyEntry>> = _entries.asStateFlow()
        override suspend fun getNotifications(priority: AccessPriority): MessageResult<NotifyFeed> = error("unused")
        override suspend fun markNotificationRead(key: String) = Unit
        override suspend fun markAllNotificationsRead() = Unit
        override suspend fun pollUnreadCounts(priority: AccessPriority): NotificationPollResult {
            pollCallCount.incrementAndGet()
            return pollOutcome
        }
        override fun updateFromPageMeta(promptCounts: PromptCounts) = Unit
        override fun updateSmsUnreadState(unreadConversationUids: Set<String>, unreadCount: Int) = Unit
        override fun markSmsConversationRead(uid: String, hadUnreadMessages: Boolean) = Unit
    }
}
