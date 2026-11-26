package net.jejer.hipda.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.jejer.hipda.feature.compose.FullEditorLauncher
import net.jejer.hipda.feature.compose.FullEditorRequestBus

/** 绑定 `E5` 预留的完整编辑器交接插槽 [FullEditorLauncher]，零耦合启用快速回复的「展开全屏」与「插入图片」。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ComposeEditorModule {

    @Binds
    @Singleton
    abstract fun bindFullEditorLauncher(impl: FullEditorRequestBus): FullEditorLauncher
}
