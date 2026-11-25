package net.jejer.hipda.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import net.jejer.hipda.data.settings.SettingsRepository

/**
 * Schedules and cancels the 15-minute periodic background notification polling worker.
 */
interface NotificationScheduler {

    /**
     * Enqueues or cancels periodic background polling according to [notificationsEnabled].
     */
    fun syncSchedule(notificationsEnabled: Boolean)

    /**
     * Reads [net.jejer.hipda.data.settings.AppSettings.notificationsEnabled] and reconciles the
     * periodic WorkManager schedule accordingly (for app startup or settings sync).
     */
    suspend fun syncFromSettings()

    /**
     * Enqueues the 15-minute unique periodic polling work with [ExistingPeriodicWorkPolicy.KEEP]
     * and [NetworkType.CONNECTED].
     */
    fun schedule()

    /**
     * Cancels the unique periodic polling work.
     */
    fun cancel()
}

@Singleton
class WorkManagerNotificationScheduler internal constructor(
    private val settingsRepository: SettingsRepository,
    private val workManagerProvider: () -> WorkManager,
) : NotificationScheduler {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
    ) : this(
        settingsRepository = settingsRepository,
        workManagerProvider = { WorkManager.getInstance(context) },
    )

    override fun syncSchedule(notificationsEnabled: Boolean) {
        if (notificationsEnabled) {
            schedule()
        } else {
            cancel()
        }
    }

    override suspend fun syncFromSettings() {
        val enabled = settingsRepository.settings.first().notificationsEnabled
        syncSchedule(enabled)
    }

    override fun schedule() {
        val request = buildPeriodicWorkRequest()
        workManagerProvider().enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancel() {
        workManagerProvider().cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME: String = "hipda_next_notification_polling"
        const val WORK_TAG: String = "hipda_next_notification_polling_tag"
        const val POLL_INTERVAL_MINUTES: Long = 15L

        internal fun buildPeriodicWorkRequest(): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            return PeriodicWorkRequestBuilder<NotificationPollingWorker>(
                repeatInterval = POLL_INTERVAL_MINUTES,
                repeatIntervalTimeUnit = TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .addTag(WORK_TAG)
                .build()
        }
    }
}
