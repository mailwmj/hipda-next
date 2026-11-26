package net.jejer.hipda.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.LocalTime
import javax.inject.Singleton
import net.jejer.hipda.service.AndroidNotificationPoster
import net.jejer.hipda.service.AppVisibilityProvider
import net.jejer.hipda.service.NotificationPoster
import net.jejer.hipda.service.NotificationScheduler
import net.jejer.hipda.service.PollingClock
import net.jejer.hipda.service.WorkManagerNotificationScheduler
import net.jejer.hipda.ui.HiNextApplication

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationWorkModule {

    @Binds
    @Singleton
    abstract fun bindNotificationPoster(impl: AndroidNotificationPoster): NotificationPoster

    @Binds
    @Singleton
    abstract fun bindNotificationScheduler(
        impl: WorkManagerNotificationScheduler,
    ): NotificationScheduler

    companion object {
        @Provides
        @Singleton
        fun provideAppVisibilityProvider(): AppVisibilityProvider =
            AppVisibilityProvider { HiNextApplication.isAppVisible() }

        @Provides
        @Singleton
        fun providePollingClock(): PollingClock = PollingClock {
            val now = LocalTime.now()
            now.hour * 60 + now.minute
        }
    }
}
