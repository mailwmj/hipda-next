package net.jejer.hipda.data.network

import android.content.Context
import android.os.Build
import android.os.Looper
import com.google.android.gms.net.CronetProviderInstaller
import com.google.android.gms.tasks.Tasks
import com.google.net.cronet.okhttptransport.CronetInterceptor
import com.google.net.cronet.okhttptransport.RedirectStrategy
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Response
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider

/**
 * Terminal application interceptor that lazily initializes a Chrome-compatible Cronet transport
 * (Android 14+ system HttpEngine, then Google Play Services Cronet) on an OkHttp worker thread.
 *
 * Caches both available and unavailable outcomes so initialization runs at most once. Falls back
 * to OkHttp's own transport via `chain.proceed` when unavailable or invoked on the main thread
 * before initialization.
 */
class TransportInterceptor internal constructor(
    private val delegateFactory: () -> Interceptor?,
    private val isMainThread: () -> Boolean = ::isAndroidMainThread,
) : Interceptor {

    private val initLock = Any()

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var delegate: Interceptor? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.call().isCanceled()) throw IOException("Canceled")
        val transport = resolveDelegate()
        return transport?.intercept(chain) ?: chain.proceed(chain.request())
    }

    private fun resolveDelegate(): Interceptor? {
        if (initialized) return delegate
        if (isMainThread()) return null
        return synchronized(initLock) {
            if (initialized) return delegate
            val created = safeCall(delegateFactory)
            delegate = created
            initialized = true
            created
        }
    }

    companion object {
        const val PLAY_SERVICES_INSTALL_TIMEOUT_MS: Long = 2_000L

        fun create(context: Context): TransportInterceptor {
            val appContext = context.applicationContext ?: context
            return TransportInterceptor(
                delegateFactory = { createCronetEngine(appContext)?.let(::buildCronetInterceptor) },
            )
        }
    }
}

internal fun buildCronetInterceptor(engine: CronetEngine): CronetInterceptor =
    CronetInterceptor.newBuilder(engine)
        .setRedirectStrategy(RedirectStrategy.withoutRedirects())
        .build()

internal fun createCronetEngine(
    context: Context,
    sdkInt: Int = Build.VERSION.SDK_INT,
    installPlayServicesProvider: (Context) -> Boolean = ::installPlayServicesCronet,
    providersLoader: (Context) -> List<CronetProvider> = CronetProvider::getAllProviders,
): CronetEngine? {
    val providers = safeCall { providersLoader(context) }.orEmpty()
    if (providers.isEmpty()) return null

    // Check non-Play-Services providers first (HttpEngine on API 34+) to avoid triggering
    // PlayServicesCronetProvider.isEnabled() before bounded installProvider().
    val directEnabled = providers.filter {
        it.name != PROVIDER_NAME_PLAY_SERVICES && safeCall { it.isEnabled } == true
    }
    selectProvider(directEnabled.map { ProviderInfo(it.name, isEnabled = true) }, sdkInt)
        ?.let { selected -> directEnabled.firstOrNull { it.name == selected.name } }
        ?.let(::buildEngine)
        ?.let { return it }

    val playProviders = providers.filter { it.name == PROVIDER_NAME_PLAY_SERVICES }
    if (playProviders.isEmpty() || !installPlayServicesProvider(context)) return null
    val enabledPlay = playProviders.firstOrNull { safeCall { it.isEnabled } == true } ?: return null
    return buildEngine(enabledPlay)
}

internal fun installPlayServicesCronet(context: Context): Boolean = try {
    Tasks.await(
        CronetProviderInstaller.installProvider(context),
        TransportInterceptor.PLAY_SERVICES_INSTALL_TIMEOUT_MS,
        TimeUnit.MILLISECONDS,
    )
    true
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    false
} catch (_: Exception) {
    false
} catch (_: LinkageError) {
    false
}

private fun buildEngine(provider: CronetProvider): CronetEngine? = safeCall {
    provider.createBuilder()
        .enableHttp2(true)
        .enableQuic(true)
        .enableBrotli(true)
        .build()
}

internal fun isAndroidMainThread(): Boolean = try {
    Looper.getMainLooper()?.let { Looper.myLooper() === it } == true
} catch (_: Throwable) {
    false
}

private inline fun <T> safeCall(block: () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
} catch (_: LinkageError) {
    null
}
