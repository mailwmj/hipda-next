package net.jejer.hipda.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.jejer.hipda.data.threaddetail.DefaultThreadDetailRepository
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class ThreadDetailModule {

    @Binds
    @Singleton
    abstract fun bindThreadDetailRepository(
        impl: DefaultThreadDetailRepository,
    ): ThreadDetailRepository
}
