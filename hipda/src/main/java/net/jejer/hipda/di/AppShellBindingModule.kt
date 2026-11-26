package net.jejer.hipda.di

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.jejer.hipda.feature.common.ImageViewerRenderer
import net.jejer.hipda.feature.common.LoginOverlayRenderer
import net.jejer.hipda.feature.common.MessagesPaneRenderer
import net.jejer.hipda.feature.common.SearchMinePaneRenderer
import net.jejer.hipda.feature.common.SettingsProfilePaneRenderer

/** 外壳可选功能插槽绑定：`E15` 与 M6（`E8`/`E10`/`E12`/`E14`）在各自 UiModule `@Binds @Singleton` 后零改 `feature/app/` 自动生效。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppShellBindingModule {

    @BindsOptionalOf
    abstract fun bindOptionalLoginOverlayRenderer(): LoginOverlayRenderer

    @BindsOptionalOf
    abstract fun bindOptionalMessagesPaneRenderer(): MessagesPaneRenderer

    @BindsOptionalOf
    abstract fun bindOptionalSearchMinePaneRenderer(): SearchMinePaneRenderer

    @BindsOptionalOf
    abstract fun bindOptionalSettingsProfilePaneRenderer(): SettingsProfilePaneRenderer

    @BindsOptionalOf
    abstract fun bindOptionalImageViewerRenderer(): ImageViewerRenderer
}
