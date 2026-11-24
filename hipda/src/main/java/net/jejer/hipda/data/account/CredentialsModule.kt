package net.jejer.hipda.data.account

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CredentialsModule {

    @Binds
    @Singleton
    abstract fun bindCredentialsStore(impl: DataStoreCredentialsStore): CredentialsStore
}
