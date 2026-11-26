package net.jejer.hipda.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.jejer.hipda.data.account.CredentialsStore
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.message.NotificationPollResult
import net.jejer.hipda.data.message.NotifyRepository
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.settings.SettingsRepository

/**
 * Provides whether any app Activity is currently visible to the user.
 */
fun interface AppVisibilityProvider {
    fun isAppVisible(): Boolean
}

/**
 * Provides the current local minute of day in `[0, 1439]` for quiet-hours evaluation.
 */
fun interface PollingClock {
    fun currentMinutesOfDay(): Int
}

/**
 * Periodic background notification polling worker executed via WorkManager + HiltWorker.
 *
 * Enforces four zero-network pre-gates (app visible, notifications disabled, quiet hours,
 * signed-out/manually logged-out) before polling with [AccessPriority.BackgroundSilent].
 * Cloudflare challenges, auth errors, and transient network/site failures return
 * [Result.success] quietly without triggering WorkManager retry storms or challenge UI.
 */
@HiltWorker
class NotificationPollingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val credentialsStore: CredentialsStore,
    private val notifyRepository: NotifyRepository,
    private val notificationPoster: NotificationPoster,
    private val appVisibilityProvider: AppVisibilityProvider,
    private val pollingClock: PollingClock,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Gate 1: Skip silently when the app is visible in the foreground.
        if (appVisibilityProvider.isAppVisible()) {
            return Result.success()
        }

        // Gate 2: Skip silently when notifications are disabled.
        val settings = try {
            settingsRepository.settings.first()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.success()
        }
        if (!settings.notificationsEnabled) {
            return Result.success()
        }

        // Gate 3: Skip silently during configured quiet hours (same-day or cross-midnight).
        if (
            QuietHoursChecker.isInQuietHours(
                currentMinutesOfDay = pollingClock.currentMinutesOfDay(),
                silentModeEnabled = settings.silentModeEnabled,
                beginMinutes = settings.silentBeginMinutes,
                endMinutes = settings.silentEndMinutes,
            )
        ) {
            return Result.success()
        }

        // Gate 4: Skip silently when signed out or manually logged out (handling cold-start hydration).
        if (!hasActiveAccount()) {
            return Result.success()
        }

        val pollResult = try {
            notifyRepository.pollUnreadCounts(priority = AccessPriority.BackgroundSilent)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.success()
        }

        return when (pollResult) {
            is NotificationPollResult.Success -> {
                val snapshot = pollResult.snapshot
                if (snapshot.smsUnreadCount > 0 || snapshot.notifyUnreadCount > 0) {
                    if (!appVisibilityProvider.isAppVisible() && authRepository.currentAccount.value != null) {
                        notificationPoster.postUnread(snapshot)
                    }
                } else {
                    notificationPoster.cancel()
                }
                Result.success()
            }

            NotificationPollResult.ChallengeAbandoned,
            is NotificationPollResult.AuthRequired,
            is NotificationPollResult.Failed,
            -> Result.success()
        }
    }

    private suspend fun hasActiveAccount(): Boolean {
        if (authRepository.currentAccount.value != null) {
            return true
        }
        val activeUid = try {
            if (credentialsStore.manuallyLoggedOut.first()) return false
            val uid = credentialsStore.activeUid.first()
            if (uid.isNullOrBlank()) return false
            val accountExists = credentialsStore.accounts.first().any { it.uid == uid }
            if (!accountExists) return false
            uid
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }

        val hydrated = withTimeoutOrNull(AUTH_HYDRATION_TIMEOUT_MS) {
            authRepository.currentAccount.first { it?.uid == activeUid }
        }
        return hydrated != null
    }

    companion object {
        internal const val AUTH_HYDRATION_TIMEOUT_MS: Long = 2_000L
    }
}
