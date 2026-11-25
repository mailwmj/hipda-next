package net.jejer.hipda.di

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.jejer.hipda.feature.common.LoginOverlayRenderer

/** 外壳可选功能插槽绑定：Wave 2 `E15` 绑定 [LoginOverlayRenderer] 后零改 `feature/app/` 自动生效。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppShellBindingModule {

    @BindsOptionalOf
    abstract fun bindOptionalLoginOverlayRenderer(): LoginOverlayRenderer
}
