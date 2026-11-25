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
import net.jejer.hipda.data.favorite.DefaultFavoriteRepository
import net.jejer.hipda.data.favorite.FavoriteRepository
import net.jejer.hipda.data.search.DefaultSearchRepository
import net.jejer.hipda.data.search.SearchRepository

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class SearchHistoryDataStore

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class FavoriteCacheDataStore

@Module
@InstallIn(SingletonComponent::class)
abstract class SearchAndFavoriteModule {

    @Binds
    @Singleton
    abstract fun bindSearchRepository(impl: DefaultSearchRepository): SearchRepository

    @Binds
    @Singleton
    abstract fun bindFavoriteRepository(impl: DefaultFavoriteRepository): FavoriteRepository

    companion object {
        const val SEARCH_HISTORY_STORE_NAME: String = "search_history"
        const val FAVORITE_CACHE_STORE_NAME: String = "favorite_cache"

        internal fun searchHistoryDataStoreFile(context: Context): File =
            context.preferencesDataStoreFile(SEARCH_HISTORY_STORE_NAME)

        internal fun favoriteCacheDataStoreFile(context: Context): File =
            context.preferencesDataStoreFile(FAVORITE_CACHE_STORE_NAME)

        @Provides
        @Singleton
        @SearchHistoryDataStore
        fun provideSearchHistoryDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler(
                produceNewData = { emptyPreferences() },
            ),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { searchHistoryDataStoreFile(context) },
        )

        @Provides
        @Singleton
        @FavoriteCacheDataStore
        fun provideFavoriteCacheDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler(
                produceNewData = { emptyPreferences() },
            ),
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { favoriteCacheDataStoreFile(context) },
        )
    }
}
