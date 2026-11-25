package net.jejer.hipda.di

import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import net.jejer.hipda.data.settings.AppSettings
import okhttp3.OkHttpClient

/** 极薄 Coil 3 DI：绑既有 `@Singleton OkHttpClient`、GIF 解码与静态默认 500MB 缓存（零 `runBlocking`）。 */
@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {
    const val IMAGE_CACHE_DIR_NAME: String = "image_cache"
    const val DEFAULT_DISK_CACHE_MAX_BYTES: Long = AppSettings.DEFAULT_IMAGE_CACHE_LIMIT_MB.toLong() * 1024L * 1024L

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context, okHttpClient: OkHttpClient): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
                add(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AnimatedImageDecoder.Factory() else GifDecoder.Factory())
            }
            .diskCache {
                DiskCache.Builder().directory(File(context.cacheDir, IMAGE_CACHE_DIR_NAME)).maxSizeBytes(DEFAULT_DISK_CACHE_MAX_BYTES).build()
            }
            .build()
}
