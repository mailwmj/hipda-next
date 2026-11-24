package net.jejer.hipda.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
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
import net.jejer.hipda.data.settings.DataStoreSettingsRepository
import net.jejer.hipda.data.settings.SettingsRepository

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsDataStore

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    const val SETTINGS_STORE_NAME: String = "settings"

    internal fun settingsDataStoreFile(context: Context): File =
        context.preferencesDataStoreFile(SETTINGS_STORE_NAME)

    @Provides
    @Singleton
    @SettingsDataStore
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { settingsDataStoreFile(context) },
    )

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @SettingsDataStore dataStore: DataStore<Preferences>,
    ): SettingsRepository = DataStoreSettingsRepository(dataStore)
}
