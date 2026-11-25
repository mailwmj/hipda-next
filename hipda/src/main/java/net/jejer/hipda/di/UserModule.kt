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
import net.jejer.hipda.data.user.BlacklistRepository
import net.jejer.hipda.data.user.DefaultBlacklistRepository
import net.jejer.hipda.data.user.DefaultUserRepository
import net.jejer.hipda.data.user.UserRepository

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class BlacklistDataStore

@Module
@InstallIn(SingletonComponent::class)
abstract class UserModule {

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: DefaultUserRepository): UserRepository

    @Binds
    @Singleton
    abstract fun bindBlacklistRepository(impl: DefaultBlacklistRepository): BlacklistRepository

    companion object {
        const val BLACKLIST_STORE_NAME: String = "blacklist_cache"

        internal fun blacklistDataStoreFile(context: Context): File =
            context.preferencesDataStoreFile(BLACKLIST_STORE_NAME)

        @Provides
        @Singleton
        @BlacklistDataStore
        fun provideBlacklistDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler(
                produceNewData = { emptyPreferences() },
            ),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { blacklistDataStoreFile(context) },
        )
    }
}
