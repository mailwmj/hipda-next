package net.jejer.hipda.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.jejer.hipda.data.threadlist.DefaultThreadListRepository
import net.jejer.hipda.data.threadlist.ThreadListRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class ThreadListModule {

    @Binds
    @Singleton
    abstract fun bindThreadListRepository(
        impl: DefaultThreadListRepository,
    ): ThreadListRepository
}
