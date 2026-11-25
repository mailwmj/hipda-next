package net.jejer.hipda.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.jejer.hipda.data.message.DefaultNotifyRepository
import net.jejer.hipda.data.message.DefaultSmsRepository
import net.jejer.hipda.data.message.NotifyRepository
import net.jejer.hipda.data.message.SmsRepository

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class NotifyReadStateDataStore

@Module
@InstallIn(SingletonComponent::class)
abstract class MessageModule {

    @Binds
    @Singleton
    abstract fun bindSmsRepository(impl: DefaultSmsRepository): SmsRepository

    @Binds
    @Singleton
    abstract fun bindNotifyRepository(impl: DefaultNotifyRepository): NotifyRepository

    companion object {
        const val NOTIFY_READ_STATE_STORE_NAME: String = "notify_read_state"

        internal fun notifyReadStateDataStoreFile(context: Context): File =
            context.preferencesDataStoreFile(NOTIFY_READ_STATE_STORE_NAME)

        @Provides
        @Singleton
        @NotifyReadStateDataStore
        fun provideNotifyReadStateDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler(
                produceNewData = { emptyPreferences() },
            ),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { notifyReadStateDataStoreFile(context) },
        )
    }
}
