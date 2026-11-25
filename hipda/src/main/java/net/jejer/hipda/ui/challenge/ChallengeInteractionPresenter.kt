package net.jejer.hipda.ui.challenge

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import net.jejer.hipda.data.network.access.SolveResult
import okhttp3.HttpUrl

/** Reason why background auto-solving escalated to interactive presentation. */
enum class ChallengeInteractionReason {
    BackgroundTimeout,
    InteractiveBegin,
    InteractiveTimeout,
}

/**
 * Live Android Activity and WebView binding exposed to [ChallengeInteractionPresenter] so A6 can
 * reveal and reuse the existing [webView] inside a visible panel without reloading it.
 */
interface ChallengeActivityBinding {
    val activity: ComponentActivity
    val webView: WebView
    val rootContainer: FrameLayout

    /** Toggles window touch/focus flags and WebView visibility/interactivity on the main thread. */
    fun setInteractiveMode(interactive: Boolean)
}

/** Active challenge session handed to [ChallengeInteractionPresenter.present]. */
class ChallengeInteractionSession(
    val triggerUrl: HttpUrl,
    val reason: ChallengeInteractionReason,
    val webViewHandle: ChallengeWebViewHandle,
    val activityBinding: ChallengeActivityBinding?,
    private val clearanceChecker: suspend () -> Boolean,
) {
    /**
     * Checks whether the WebView has left the challenge state and synced a fresh `cf_clearance`
     * into `SiteCookieStore`.
     */
    suspend fun checkAndSyncClearance(): Boolean = clearanceChecker()
}

/**
 * Extension point invoked when background auto-solving cannot complete silently (8s background
 * budget exhausted or Turnstile emitted `interactiveBegin` / `interactiveTimeout`).
 *
 * A5b binds [DefaultChallengeInteractionPresenter] (cancels silently without displaying any UI);
 * A6 replaces the binding in [ChallengeInteractionModule] with a visible Compose verification panel
 * reusing the same WebView.
 */
fun interface ChallengeInteractionPresenter {
    suspend fun present(session: ChallengeInteractionSession): SolveResult
}

/** Default A5b presenter that cancels silently without displaying any UI. */
@Singleton
class DefaultChallengeInteractionPresenter @Inject constructor() : ChallengeInteractionPresenter {
    override suspend fun present(session: ChallengeInteractionSession): SolveResult =
        SolveResult.Cancelled
}

/** Isolated Hilt module so A6 can replace [ChallengeInteractionPresenter] independently. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChallengeInteractionModule {
    @Binds
    @Singleton
    abstract fun bindChallengeInteractionPresenter(
        impl: DefaultChallengeInteractionPresenter,
    ): ChallengeInteractionPresenter
}
