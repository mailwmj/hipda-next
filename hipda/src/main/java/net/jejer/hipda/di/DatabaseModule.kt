package net.jejer.hipda.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.jejer.hipda.data.local.BrowseHistoryDao
import net.jejer.hipda.data.local.DraftDao
import net.jejer.hipda.data.local.HiDatabase
import net.jejer.hipda.data.local.ReadingPositionDao

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideHiDatabase(
        @ApplicationContext context: Context,
    ): HiDatabase = Room.databaseBuilder(
        context,
        HiDatabase::class.java,
        HiDatabase.DATABASE_NAME,
    ).build()

    @Provides
    fun provideDraftDao(database: HiDatabase): DraftDao = database.draftDao()

    @Provides
    fun provideBrowseHistoryDao(database: HiDatabase): BrowseHistoryDao =
        database.browseHistoryDao()

    @Provides
    fun provideReadingPositionDao(database: HiDatabase): ReadingPositionDao =
        database.readingPositionDao()
}
