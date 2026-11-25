package net.jejer.hipda.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.DefaultAuthRepository
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.settings.SettingsRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: DefaultAuthRepository): AuthRepository

    companion object {
        @Provides
        @Singleton
        fun provideForumHttpClient(
            siteAccessGateway: SiteAccessGateway,
            settingsRepository: SettingsRepository,
            authRepository: AuthRepository,
        ): ForumHttpClient = ForumHttpClient(
            siteAccessGateway = siteAccessGateway,
            settingsRepository = settingsRepository,
            authRepository = authRepository,
        )
    }
}
