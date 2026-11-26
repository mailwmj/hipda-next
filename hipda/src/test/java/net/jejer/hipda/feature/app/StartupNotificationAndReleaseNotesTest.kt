package net.jejer.hipda.feature.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.common.FeatureScreenshotQualifiers
import net.jejer.hipda.feature.forum.ForumViewModel
import net.jejer.hipda.feature.threaddetail.ThreadDetailViewModel
import net.jejer.hipda.service.NotificationScheduler
import net.jejer.hipda.service.WorkManagerNotificationScheduler
import net.jejer.hipda.testing.withAnimatorDurationScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 覆盖 M7-G2 #14 冷启动通知调度与 #12 版本变化后首次启动弹更新记录（撤回即红）：
 * - #14：冷启动实例化 [NextAppHostViewModel] 精确调用 1 次 `syncFromSettings()`，开关 true 入队周期任务、false 取消任务，旋转不重复触发；
 * - #12：首次启动（`lastSeen = 0`, `current = 5005`）在 [NextAppRoot] 弹出可滚动真实 `assets/release-notes.txt` 更新记录 → 关闭后持久化 `5005` → 同版本重启与旋转不再弹出 → 版本升至 `5006` 再弹一次；
 * - 截图夹具默认无注入 [ReleaseNotesPromptStore] 时严格不弹出，保障既有 `multipane_*` 0 漂移。
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = FeatureScreenshotQualifiers.PHONE_411_891)
class StartupNotificationAndReleaseNotesTest {

    @Test
    fun coldStart_syncsNotificationSchedulerExactlyOnce_forEnabledAndDisabledSettings() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val enabledSettings = RecordingSettingsRepository(AppSettings(notificationsEnabled = true))
            val enabledScheduler = RecordingStartupNotificationScheduler(enabledSettings)
            val vmEnabled = NextAppHostViewModel(
                settingsRepository = enabledSettings,
                threadDetailRepository = null,
                notificationScheduler = enabledScheduler,
                releaseNotesPromptStore = null,
                currentVersionCode = 5005,
            )
            advanceUntilIdle()
            assertEquals("冷启动开启通知时 syncFromSettings 必须精确调用 1 次", 1, enabledScheduler.syncFromSettingsCalls)
            assertEquals(1, enabledScheduler.scheduleCalls)
            assertEquals(0, enabledScheduler.cancelCalls)

            val disabledSettings = RecordingSettingsRepository(AppSettings(notificationsEnabled = false))
            val disabledScheduler = RecordingStartupNotificationScheduler(disabledSettings)
            val vmDisabled = NextAppHostViewModel(
                settingsRepository = disabledSettings,
                threadDetailRepository = null,
                notificationScheduler = disabledScheduler,
                releaseNotesPromptStore = null,
                currentVersionCode = 5005,
            )
            advanceUntilIdle()
            assertEquals("冷启动关闭通知时 syncFromSettings 必须精确调用 1 次", 1, disabledScheduler.syncFromSettingsCalls)
            assertEquals(0, disabledScheduler.scheduleCalls)
            assertEquals(1, disabledScheduler.cancelCalls)

            assertNotNull(vmEnabled)
            assertNotNull(vmDisabled)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun coldStart_withRealWorkManagerNotificationScheduler_enqueuesWhenEnabledAndCancelsWhenDisabled() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        try {
            val workManager = WorkManager.getInstance(app)

            val settingsRepo = RecordingSettingsRepository(AppSettings(notificationsEnabled = true))
            val realScheduler = WorkManagerNotificationScheduler(context = app, settingsRepository = settingsRepo)

            // 1. 开启通知冷启动：入队 hipda_next_notification_polling 周期任务
            runTest {
                val dispatcher = StandardTestDispatcher(testScheduler)
                Dispatchers.setMain(dispatcher)
                try {
                    NextAppHostViewModel(
                        settingsRepository = settingsRepo,
                        threadDetailRepository = null,
                        notificationScheduler = realScheduler,
                        releaseNotesPromptStore = null,
                        currentVersionCode = 5005,
                    )
                    advanceUntilIdle()
                } finally {
                    Dispatchers.resetMain()
                }
            }
            val enqueued = workManager.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.WORK_NAME).get()
            assertEquals(1, enqueued.size)
            assertEquals(WorkInfo.State.ENQUEUED, enqueued.single().state)

            // 2. 关闭通知冷启动：取消该周期任务
            settingsRepo.updateSettings { it.copy(notificationsEnabled = false) }
            runTest {
                val dispatcher = StandardTestDispatcher(testScheduler)
                Dispatchers.setMain(dispatcher)
                try {
                    NextAppHostViewModel(
                        settingsRepository = settingsRepo,
                        threadDetailRepository = null,
                        notificationScheduler = realScheduler,
                        releaseNotesPromptStore = null,
                        currentVersionCode = 5005,
                    )
                    advanceUntilIdle()
                } finally {
                    Dispatchers.resetMain()
                }
            }
            val cancelled = workManager.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.WORK_NAME).get()
            assertEquals(1, cancelled.size)
            assertEquals(WorkInfo.State.CANCELLED, cancelled.single().state)
        } finally {
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    @Test
    fun hiltInjectionMetadata_wiresNotificationSchedulerAndReleaseNotesPromptStore() {
        val injectCtor = NextAppHostViewModel::class.java.declaredConstructors.singleOrNull {
            it.isAnnotationPresent(Inject::class.java)
        }
        assertNotNull("NextAppHostViewModel 必须保留唯一 @Inject 构造函数", injectCtor)
        assertEquals(
            listOf(
                SettingsRepository::class.java,
                ThreadDetailRepository::class.java,
                NotificationScheduler::class.java,
                ReleaseNotesPromptStore::class.java,
            ),
            injectCtor!!.parameterTypes.toList(),
        )

        assertTrue(ReleaseNotesPromptBindingModule::class.java.isAnnotationPresent(Module::class.java))
        assertTrue(SharedPrefsReleaseNotesPromptStore::class.java.isAnnotationPresent(Singleton::class.java))
        val bindMethod = ReleaseNotesPromptBindingModule::class.java.getDeclaredMethod(
            "bindReleaseNotesPromptStore",
            SharedPrefsReleaseNotesPromptStore::class.java,
        )
        assertTrue(bindMethod.isAnnotationPresent(Binds::class.java))
        assertTrue(bindMethod.isAnnotationPresent(Singleton::class.java))
        assertEquals(ReleaseNotesPromptStore::class.java, bindMethod.returnType)
    }

    @Test
    fun sharedPrefsReleaseNotesPromptStore_persistsVersionCodeAcrossReads() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.getSharedPreferences(SharedPrefsReleaseNotesPromptStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val store = SharedPrefsReleaseNotesPromptStore(context = app, ioDispatcher = Dispatchers.Unconfined)

        assertEquals(0, store.getLastSeenVersionCode())
        store.setLastSeenVersionCode(5005)
        assertEquals(5005, store.getLastSeenVersionCode())
        store.setLastSeenVersionCode(5006)
        assertEquals(5006, store.getLastSeenVersionCode())
    }

    @Test
    fun nextAppRoot_showsReleaseNotesOncePerVersionCodeAndNeverReopensOnSameVersionOrRotation() = withAnimatorDurationScale(0f) {
        val promptStore = InMemoryReleaseNotesPromptStore(initialVersionCode = 0)
        val harness = MultiPaneTestHarness()
        val scheduler = RecordingStartupNotificationScheduler(harness.settingsRepo)

        // Launch 1: 首次启动 (lastSeen = 0, current = 5005) -> 弹出更新记录，通知同步 1 次
        var hostVm by mutableStateOf(
            NextAppHostViewModel(
                settingsRepository = harness.settingsRepo,
                threadDetailRepository = harness.threadDetailRepo,
                notificationScheduler = scheduler,
                releaseNotesPromptStore = promptStore,
                currentVersionCode = 5005,
            ),
        )
        var mounted by mutableStateOf(true)
        var registry by mutableStateOf(SaveableStateRegistry(null) { true })

        setContent {
            val owner = remember(hostVm) { ownerFor(hostVm, harness) }
            DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides owner,
                LocalSaveableStateRegistry provides registry,
            ) {
                if (mounted) {
                    NextAppRoot(
                        settingsFlow = harness.settingsRepo.settings,
                        currentAccountFlow = harness.authRepo.currentAccount,
                        smsUnreadCountFlow = harness.smsUnread.asStateFlow(),
                        notifyUnreadCountFlow = harness.notifyUnread.asStateFlow(),
                        hostViewModel = hostVm,
                        forumViewModel = harness.forumViewModel,
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 3_000L) {
            onAllNodes(hasText("v5.0.05", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("更新记录").assertIsDisplayed()
        onNode(hasText("v5.0.05", substring = true) and hasScrollAction()).assertIsDisplayed()
        assertEquals("冷启动应同步通知调度 1 次", 1, scheduler.syncFromSettingsCalls)

        // 点击关闭 -> 对话框消失并持久化 lastSeenVersionCode = 5005
        onNodeWithText("关闭").performClick()
        waitForIdle()
        onNodeWithText("更新记录").assertDoesNotExist()
        assertEquals(5005, promptStore.lastSeenVersionCode)

        // 模拟配置变更旋转（同一 ViewModel 存活、组合树重建）：不重弹更新记录，也不重复调度通知
        val saved = registry.performSave()
        mounted = false
        waitForIdle()
        registry = SaveableStateRegistry(saved) { true }
        mounted = true
        waitForIdle()
        onNodeWithText("更新记录").assertDoesNotExist()
        assertEquals("旋转重建不得重复调用 syncFromSettings", 1, scheduler.syncFromSettingsCalls)

        // Launch 2: 相同版本 5005 再次冷启动（全新 ViewModel）-> 绝不再弹
        hostVm = NextAppHostViewModel(
            settingsRepository = harness.settingsRepo,
            threadDetailRepository = harness.threadDetailRepo,
            notificationScheduler = scheduler,
            releaseNotesPromptStore = promptStore,
            currentVersionCode = 5005,
        )
        waitForIdle()
        onNodeWithText("更新记录").assertDoesNotExist()
        assertFalse(hostVm.showReleaseNotesPrompt.value)
        assertEquals(2, scheduler.syncFromSettingsCalls)

        // Launch 3: 版本升级至 5006 再次冷启动 -> 再次弹出更新记录一次
        hostVm = NextAppHostViewModel(
            settingsRepository = harness.settingsRepo,
            threadDetailRepository = harness.threadDetailRepo,
            notificationScheduler = scheduler,
            releaseNotesPromptStore = promptStore,
            currentVersionCode = 5006,
        )
        waitUntil(timeoutMillis = 3_000L) {
            onAllNodes(hasText("v5.0.05", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("更新记录").assertIsDisplayed()

        // 通过 dismiss（等同点击关闭/系统返回 onDismissRequest）持久化 5006
        hostVm.dismissReleaseNotesPrompt()
        waitForIdle()
        onNodeWithText("更新记录").assertDoesNotExist()
        assertEquals(5006, promptStore.lastSeenVersionCode)

        // Launch 4: 5006 再次冷启动 -> 不再弹出
        hostVm = NextAppHostViewModel(
            settingsRepository = harness.settingsRepo,
            threadDetailRepository = harness.threadDetailRepo,
            notificationScheduler = scheduler,
            releaseNotesPromptStore = promptStore,
            currentVersionCode = 5006,
        )
        waitForIdle()
        onNodeWithText("更新记录").assertDoesNotExist()
        assertFalse(hostVm.showReleaseNotesPrompt.value)
    }

    @Test
    fun defaultTestHarness_withoutPromptStore_neverShowsReleaseNotesDialog() = withAnimatorDurationScale(0f) {
        val harness = MultiPaneTestHarness()
        setContent { harness.RenderRoot() }
        waitForIdle()
        assertFalse(harness.hostViewModel.showReleaseNotesPrompt.value)
        onNodeWithText("更新记录").assertDoesNotExist()
        harness.parentOwner.viewModelStore.clear()
    }

    private fun ownerFor(hostVm: NextAppHostViewModel, harness: MultiPaneTestHarness): ViewModelStoreOwner {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(NextAppHostViewModel::class.java) -> hostVm as T
                modelClass.isAssignableFrom(ForumViewModel::class.java) -> harness.forumViewModel as T
                modelClass.isAssignableFrom(ThreadDetailViewModel::class.java) ->
                    ThreadDetailViewModel(harness.threadDetailRepo, harness.gateway) as T
                else -> throw IllegalArgumentException("Unexpected ViewModel: $modelClass")
            }
        }
        return object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
            override val viewModelStore: ViewModelStore = ViewModelStore()
            override val defaultViewModelProviderFactory: ViewModelProvider.Factory = factory
        }
    }

    private class InMemoryReleaseNotesPromptStore(initialVersionCode: Int) : ReleaseNotesPromptStore {
        var lastSeenVersionCode: Int = initialVersionCode
        override suspend fun getLastSeenVersionCode(): Int = lastSeenVersionCode
        override suspend fun setLastSeenVersionCode(versionCode: Int) {
            lastSeenVersionCode = versionCode
        }
    }

    private class RecordingStartupNotificationScheduler(
        private val settingsRepo: RecordingSettingsRepository,
    ) : NotificationScheduler {
        var syncFromSettingsCalls = 0
        var scheduleCalls = 0
        var cancelCalls = 0

        override fun syncSchedule(notificationsEnabled: Boolean) {
            if (notificationsEnabled) schedule() else cancel()
        }

        override suspend fun syncFromSettings() {
            syncFromSettingsCalls++
            syncSchedule(settingsRepo.settingsFlow.value.notificationsEnabled)
        }

        override fun schedule() {
            scheduleCalls++
        }

        override fun cancel() {
            cancelCalls++
        }
    }
}
