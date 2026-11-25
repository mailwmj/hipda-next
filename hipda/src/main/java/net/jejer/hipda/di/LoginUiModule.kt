package net.jejer.hipda.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.login.LoginOverlayRendererImpl

/** 绑定 [LoginOverlayRenderer] 的生产实现，供 `AppShellBindingModule` 的 `@BindsOptionalOf` 自动注入。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LoginUiModule {

    @Binds
    @Singleton
    abstract fun bindLoginOverlayRenderer(impl: LoginOverlayRendererImpl): LoginOverlayRenderer
}
