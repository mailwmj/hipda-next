package net.jejer.hipda.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.jejer.hipda.feature.common.SettingsProfilePaneRenderer
import net.jejer.hipda.feature.settings.SettingsProfilePaneRendererImpl

/** 绑定 [SettingsProfilePaneRenderer] 生产实现，供 `AppShellBindingModule` 的 `@BindsOptionalOf` 自动接入。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsProfileUiModule {

    @Binds
    @Singleton
    abstract fun bindSettingsProfilePaneRenderer(
        impl: SettingsProfilePaneRendererImpl,
    ): SettingsProfilePaneRenderer
}
