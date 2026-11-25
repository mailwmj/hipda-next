package net.jejer.hipda.data.network

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Provides the User-Agent header value shared across the application network layer. */
fun interface UserAgentProvider {
    fun userAgent(): String
}

/** Default mobile Chrome User-Agent provider until WebView-backed UA is wired in A5. */
@Singleton
class DefaultUserAgentProvider @Inject constructor() : UserAgentProvider {

    override fun userAgent(): String = DEFAULT_MOBILE_CHROME_UA

    companion object {
        const val DEFAULT_MOBILE_CHROME_UA: String =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}

/** Isolated Hilt module so A5 can replace the [UserAgentProvider] binding independently. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UserAgentModule {

    @Binds
    @Singleton
    abstract fun bindUserAgentProvider(impl: DefaultUserAgentProvider): UserAgentProvider
}
