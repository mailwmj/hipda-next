package net.jejer.hipda.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.jejer.hipda.feature.common.MessagesPaneRenderer
import net.jejer.hipda.feature.messages.MessagesPaneRendererImpl

/** 绑定 [MessagesPaneRenderer] 的生产实现，供 `AppShellBindingModule` 的 `@BindsOptionalOf` 自动注入。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MessagesUiModule {

    @Binds
    @Singleton
    abstract fun bindMessagesPaneRenderer(impl: MessagesPaneRendererImpl): MessagesPaneRenderer
}
