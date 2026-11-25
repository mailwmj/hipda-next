package net.jejer.hipda.data.network

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Qualifier
import javax.inject.Singleton
import net.jejer.hipda.data.network.cookie.SiteCookieStore
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/** Qualifier for the recovery [OkHttpClient] with an independent [Dispatcher] thread pool. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class RecoveryHttpClient

@Module
@InstallIn(SingletonComponent::class)
object HttpClientModule {

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L

    @Provides
    @Singleton
    fun provideTransportInterceptor(
        @ApplicationContext context: Context,
    ): TransportInterceptor = TransportInterceptor.create(context)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        siteCookieStore: SiteCookieStore,
        userAgentProvider: UserAgentProvider,
        transportInterceptor: TransportInterceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(ManualRedirectInterceptor())
            .addInterceptor(BrowserHeaderInterceptor(userAgentProvider))
            .addInterceptor(AppCookieInterceptor(siteCookieStore))
            .addInterceptor(transportInterceptor)
            .build()

    @Provides
    @Singleton
    @RecoveryHttpClient
    fun provideRecoveryOkHttpClient(primaryClient: OkHttpClient): OkHttpClient {
        val threadId = AtomicInteger(1)
        val executor = ThreadPoolExecutor(0, Int.MAX_VALUE, 60L, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
            Thread(runnable, "OkHttp Recovery Dispatcher-${threadId.getAndIncrement()}").apply { isDaemon = true }
        }
        return primaryClient.newBuilder().dispatcher(Dispatcher(executor)).build()
    }
}
