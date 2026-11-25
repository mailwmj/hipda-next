package net.jejer.hipda.data.network.webview

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executors
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/** Qualifier for the dedicated single-threaded [CoroutineDispatcher] used for WebView cookie IPC. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class WebViewCookieDispatcher

/** Hilt bindings for WebView cookie bridging and its dedicated single-threaded dispatcher. */
@Module
@InstallIn(SingletonComponent::class)
abstract class WebViewNetworkModule {

    @Binds
    @Singleton
    abstract fun bindWebViewCookieAccess(impl: AndroidWebViewCookieAccess): WebViewCookieAccess

    companion object {
        private const val COOKIE_THREAD_NAME = "webview-cookie-bridge"

        @Provides
        @Singleton
        @WebViewCookieDispatcher
        fun provideWebViewCookieDispatcher(): CoroutineDispatcher =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, COOKIE_THREAD_NAME).apply {
                    isDaemon = true
                }
            }.asCoroutineDispatcher()
    }
}
