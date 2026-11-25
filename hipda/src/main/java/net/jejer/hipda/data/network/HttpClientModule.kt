package net.jejer.hipda.data.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import net.jejer.hipda.data.network.cookie.SiteCookieStore
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object HttpClientModule {

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L

    @Provides
    @Singleton
    fun provideOkHttpClient(
        siteCookieStore: SiteCookieStore,
        userAgentProvider: UserAgentProvider,
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
            .build()
}
