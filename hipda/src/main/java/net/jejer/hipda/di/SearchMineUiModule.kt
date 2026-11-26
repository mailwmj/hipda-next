package net.jejer.hipda.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.jejer.hipda.feature.common.SearchMinePaneRenderer
import net.jejer.hipda.feature.mine.SearchMinePaneRendererImpl

/** 绑定 [SearchMinePaneRenderer] 的生产实现，供 `AppShellBindingModule` 的 `@BindsOptionalOf` 自动注入。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SearchMineUiModule {

    @Binds
    @Singleton
    abstract fun bindSearchMinePaneRenderer(impl: SearchMinePaneRendererImpl): SearchMinePaneRenderer
}
