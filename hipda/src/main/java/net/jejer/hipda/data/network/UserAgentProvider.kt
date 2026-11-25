package net.jejer.hipda.data.network

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import net.jejer.hipda.data.network.webview.WebViewUserAgentProvider

/** Provides the User-Agent header value shared across the application network layer. */
fun interface UserAgentProvider {
    fun userAgent(): String
}

/**
 * Default desktop Linux Chrome User-Agent provider used as fallback when system WebView UA
 * cannot be resolved or does not expose a Chrome major version.
 */
@Singleton
class DefaultUserAgentProvider @Inject constructor() : UserAgentProvider {

    override fun userAgent(): String = DEFAULT_DESKTOP_CHROME_UA

    companion object {
        const val DEFAULT_CHROME_MAJOR_VERSION: String = "131"

        const val DEFAULT_DESKTOP_CHROME_UA: String =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$DEFAULT_CHROME_MAJOR_VERSION.0.0.0 Safari/537.36"
    }
}

/** Isolated Hilt module binding [UserAgentProvider] to the normalized system WebView UA. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UserAgentModule {

    @Binds
    @Singleton
    abstract fun bindUserAgentProvider(impl: WebViewUserAgentProvider): UserAgentProvider
}
