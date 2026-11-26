package net.jejer.hipda.service

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.Binds
import dagger.Module
import dagger.Provides
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.di.NotificationWorkModule
import net.jejer.hipda.ui.HiNextApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = HiNextApplication::class, sdk = [34])
class NotificationSchedulerTest {

    class PlainFallbackTestWorker(
        appContext: Context,
        workerParams: WorkerParameters,
    ) : Worker(appContext, workerParams) {
        override fun doWork(): Result = Result.success()
    }

    private lateinit var app: HiNextApplication
    private lateinit var workManager: WorkManager
    private lateinit var settingsRepository: ServiceTestSettingsRepository
    private lateinit var scheduler: WorkManagerNotificationScheduler

    @Before
    fun setUp() {
        val rawApp: Application = ApplicationProvider.getApplicationContext()
        assertTrue("Robolectric must instantiate HiNextApplication", rawApp is HiNextApplication)
        app = rawApp as HiNextApplication

        val configBuilder = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
        val provider = (app as Any) as? Configuration.Provider
        if (provider != null) {
            configBuilder.setWorkerFactory(provider.workManagerConfiguration.workerFactory)
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(app, configBuilder.build())
        workManager = WorkManager.getInstance(app)
        settingsRepository = ServiceTestSettingsRepository(AppSettings(notificationsEnabled = true))
        scheduler = WorkManagerNotificationScheduler(
            context = app,
            settingsRepository = settingsRepository,
        )
    }

    @Test
    fun hiNextApplication_implementsConfigurationProviderWithHiltWorkerFactoryAndManifestRemovesDefaultInitializer() {
        val provider = (app as Any) as? Configuration.Provider
        assertNotNull("HiNextApplication must implement Configuration.Provider", provider)
        val config = provider!!.workManagerConfiguration
        assertTrue(
            "HiNextApplication must supply HiltWorkerFactory, got ${config.workerFactory::class.java.name}",
            config.workerFactory is HiltWorkerFactory,
        )

        val providerComponent = ComponentName(app, InitializationProvider::class.java)
        val providerInfo = app.packageManager.getProviderInfo(
            providerComponent,
            PackageManager.GET_META_DATA,
        )
        assertNotNull("InitializationProvider must remain merged for other AndroidX initializers", providerInfo)
        assertNull(
            "WorkManagerInitializer meta-data must be removed in AndroidManifest.xml so custom Configuration.Provider takes effect",
            providerInfo.metaData?.getString("androidx.work.WorkManagerInitializer"),
        )
    }

    @Test
    fun hiltWorkerFactory_constructsNotificationPollingWorkerAndPlainWorkerFallback() {
        val provider = (app as Any) as? Configuration.Provider
        assertNotNull("HiNextApplication must implement Configuration.Provider", provider)
        val factory = provider!!.workManagerConfiguration.workerFactory
        assertTrue(factory is HiltWorkerFactory)

        // 1. Non-@HiltWorker workers still instantiate cleanly via default reflection fallback
        val fallbackWorker = TestListenableWorkerBuilder<PlainFallbackTestWorker>(app)
            .setWorkerFactory(factory)
            .build()
        assertNotNull(fallbackWorker)
        assertEquals(PlainFallbackTestWorker::class.java, fallbackWorker::class.java)

        // 2. @HiltWorker NotificationPollingWorker instantiates via HiltWorkerFactory
        val newWorker = TestListenableWorkerBuilder<NotificationPollingWorker>(app)
            .setWorkerFactory(factory)
            .build()
        assertNotNull(newWorker)
        assertEquals(NotificationPollingWorker::class.java, newWorker::class.java)
    }

    @Test
    fun schedule_enqueuesUnique15MinutePeriodicWorkWithConnectedNetworkAndKeepIdempotency() {
        assertNotEquals(
            "New unique work name must not collide with legacy 'noti_work'",
            WorkManagerNotificationScheduler.LEGACY_WORK_NAME,
            WorkManagerNotificationScheduler.WORK_NAME,
        )

        scheduler.schedule()
        val firstInfos = workManager.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.WORK_NAME).get()
        assertEquals(1, firstInfos.size)
        val firstInfo = firstInfos.single()
        assertEquals(WorkInfo.State.ENQUEUED, firstInfo.state)
        assertEquals(NetworkType.CONNECTED, firstInfo.constraints.requiredNetworkType)
        assertEquals(
            TimeUnit.MINUTES.toMillis(15L),
            firstInfo.periodicityInfo?.repeatIntervalMillis,
        )
        assertTrue(WorkManagerNotificationScheduler.WORK_TAG in firstInfo.tags)

        // Calling schedule() again uses ExistingPeriodicWorkPolicy.KEEP (same WorkInfo id, no timer reset)
        scheduler.schedule()
        val secondInfos = workManager.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.WORK_NAME).get()
        assertEquals(1, secondInfos.size)
        assertEquals(firstInfo.id, secondInfos.single().id)
        assertEquals(WorkInfo.State.ENQUEUED, secondInfos.single().state)
    }

    @Test
    fun rD9S3_schedulerInitAndSyncPaths_cancelLegacyNotiWorkRegardlessOfEnabledSetting() = runBlocking {
        // Case 1: Startup syncFromSettings() with notificationsEnabled = true cancels legacy "noti_work"
        enqueueResidualLegacyNotiWork()
        assertEquals(
            WorkInfo.State.ENQUEUED,
            workManager.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.LEGACY_WORK_NAME).get().single().state,
        )

        settingsRepository.setNotificationsEnabled(true)
        scheduler.syncFromSettings()
        assertEquals(
            "syncFromSettings(enabled=true) must cancel residual legacy 'noti_work' (R-D9 S3)",
            WorkInfo.State.CANCELLED,
            workManager.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.LEGACY_WORK_NAME).get().single().state,
        )
        assertEquals(
            WorkInfo.State.ENQUEUED,
            workManager.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.WORK_NAME).get().single().state,
        )

        // Case 2: Startup syncFromSettings() with notificationsEnabled = false also cancels legacy "noti_work"
        enqueueResidualLegacyNotiWork()
        settingsRepository.setNotificationsEnabled(false)
        scheduler.syncFromSettings()
        assertEquals(
            "syncFromSettings(enabled=false) must cancel residual legacy 'noti_work' (R-D9 S3)",
            WorkInfo.State.CANCELLED,
            workManager.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.LEGACY_WORK_NAME).get().single().state,
        )
        assertEquals(
            WorkInfo.State.CANCELLED,
            workManager.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.WORK_NAME).get().single().state,
        )

        // Case 3: Direct schedule() and cancel() also cancel legacy "noti_work"
        enqueueResidualLegacyNotiWork()
        scheduler.schedule()
        assertEquals(
            WorkInfo.State.CANCELLED,
            workManager.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.LEGACY_WORK_NAME).get().single().state,
        )

        enqueueResidualLegacyNotiWork()
        scheduler.cancel()
        assertEquals(
            WorkInfo.State.CANCELLED,
            workManager.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.LEGACY_WORK_NAME).get().single().state,
        )
    }

    @Test
    fun cancelSyncScheduleAndSyncFromSettings_controlUniqueWorkLifecycle() = runBlocking {
        scheduler.syncSchedule(notificationsEnabled = true)
        val enqueued = workManager.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.WORK_NAME).get()
        assertEquals(1, enqueued.size)
        assertEquals(WorkInfo.State.ENQUEUED, enqueued.single().state)

        scheduler.syncSchedule(notificationsEnabled = false)
        val cancelled = workManager.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.WORK_NAME).get()
        assertEquals(1, cancelled.size)
        assertEquals(WorkInfo.State.CANCELLED, cancelled.single().state)

        // syncFromSettings reads AppSettings.notificationsEnabled
        settingsRepository.setNotificationsEnabled(true)
        scheduler.syncFromSettings()
        val reEnqueued = workManager.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.WORK_NAME).get()
        assertEquals(1, reEnqueued.size)
        assertEquals(WorkInfo.State.ENQUEUED, reEnqueued.single().state)

        settingsRepository.setNotificationsEnabled(false)
        scheduler.syncFromSettings()
        val reCancelled = workManager.getWorkInfosForUniqueWork(WorkManagerNotificationScheduler.WORK_NAME).get()
        assertEquals(1, reCancelled.size)
        assertEquals(WorkInfo.State.CANCELLED, reCancelled.single().state)
    }

    @Test
    fun notificationWorkModule_hasExpectedHiltBindings() {
        assertTrue(NotificationWorkModule::class.java.isAnnotationPresent(Module::class.java))

        val bindPoster = NotificationWorkModule::class.java.getDeclaredMethod(
            "bindNotificationPoster",
            AndroidNotificationPoster::class.java,
        )
        assertTrue(bindPoster.isAnnotationPresent(Binds::class.java))
        assertTrue(bindPoster.isAnnotationPresent(Singleton::class.java))
        assertEquals(NotificationPoster::class.java, bindPoster.returnType)

        val bindScheduler = NotificationWorkModule::class.java.getDeclaredMethod(
            "bindNotificationScheduler",
            WorkManagerNotificationScheduler::class.java,
        )
        assertTrue(bindScheduler.isAnnotationPresent(Binds::class.java))
        assertTrue(bindScheduler.isAnnotationPresent(Singleton::class.java))
        assertEquals(NotificationScheduler::class.java, bindScheduler.returnType)

        val provideVisibility = NotificationWorkModule.Companion::class.java.getDeclaredMethod(
            "provideAppVisibilityProvider",
        )
        assertTrue(provideVisibility.isAnnotationPresent(Provides::class.java))
        assertTrue(provideVisibility.isAnnotationPresent(Singleton::class.java))
        assertEquals(AppVisibilityProvider::class.java, provideVisibility.returnType)

        val provideClock = NotificationWorkModule.Companion::class.java.getDeclaredMethod(
            "providePollingClock",
        )
        assertTrue(provideClock.isAnnotationPresent(Provides::class.java))
        assertTrue(provideClock.isAnnotationPresent(Singleton::class.java))
        assertEquals(PollingClock::class.java, provideClock.returnType)
    }

    private fun enqueueResidualLegacyNotiWork() {
        val legacyRequest = PeriodicWorkRequestBuilder<PlainFallbackTestWorker>(15L, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(
            WorkManagerNotificationScheduler.LEGACY_WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.REPLACE,
            legacyRequest,
        ).result.get()
    }
}
