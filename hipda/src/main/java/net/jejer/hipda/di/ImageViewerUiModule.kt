package net.jejer.hipda.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.jejer.hipda.feature.common.ImageViewerRenderer
import net.jejer.hipda.feature.imageviewer.AndroidImageViewerMediaActions
import net.jejer.hipda.feature.imageviewer.ImageViewerMediaActions
import net.jejer.hipda.feature.imageviewer.ImageViewerRendererImpl
import net.jejer.hipda.feature.imageviewer.OkHttpOriginalImageDownloader
import net.jejer.hipda.feature.imageviewer.OriginalImageDownloader

/** 绑定全屏看图浮层 [ImageViewerRenderer] 及原图下载、保存分享默认实现，零耦合接入 `AppShellBindingModule`。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ImageViewerUiModule {

    @Binds
    @Singleton
    abstract fun bindImageViewerRenderer(impl: ImageViewerRendererImpl): ImageViewerRenderer

    @Binds
    @Singleton
    abstract fun bindOriginalImageDownloader(impl: OkHttpOriginalImageDownloader): OriginalImageDownloader

    @Binds
    @Singleton
    abstract fun bindImageViewerMediaActions(impl: AndroidImageViewerMediaActions): ImageViewerMediaActions
}
