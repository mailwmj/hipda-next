package net.jejer.hipda.ui.challenge

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import net.jejer.hipda.data.network.UserAgentProvider
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.SolveResult
import net.jejer.hipda.data.network.webview.WebViewCookieBridge
import okhttp3.HttpUrl

/**
 * Production [ChallengeSolver] backed by a transparent [ChallengeActivity] and hidden WebView that
 * attempts silent background clearance before escalating to [ChallengeInteractionPresenter].
 */
@Singleton
class WebViewChallengeSolver internal constructor(
    private val stateMachine: ChallengeSolveStateMachine,
) : ChallengeSolver {

    @Inject
    constructor(
        cookieBridge: WebViewCookieBridge,
        userAgentProvider: UserAgentProvider,
        webViewHost: ChallengeWebViewHost,
        interactionPresenter: ChallengeInteractionPresenter,
    ) : this(
        stateMachine = ChallengeSolveStateMachine(
            cookieBridge = cookieBridge,
            userAgentProvider = userAgentProvider,
            webViewHost = webViewHost,
            interactionPresenter = interactionPresenter,
        ),
    )

    internal constructor(
        cookieBridge: WebViewCookieBridge,
        userAgentProvider: UserAgentProvider,
        webViewHost: ChallengeWebViewHost,
        interactionPresenter: ChallengeInteractionPresenter,
        backgroundTimeout: Duration = ChallengeSolveStateMachine.DEFAULT_BACKGROUND_TIMEOUT,
        pollInterval: Duration = ChallengeSolveStateMachine.DEFAULT_POLL_INTERVAL,
        hostAcquireTimeout: Duration = ChallengeSolveStateMachine.DEFAULT_HOST_ACQUIRE_TIMEOUT,
    ) : this(
        stateMachine = ChallengeSolveStateMachine(
            cookieBridge = cookieBridge,
            userAgentProvider = userAgentProvider,
            webViewHost = webViewHost,
            interactionPresenter = interactionPresenter,
            backgroundTimeout = backgroundTimeout,
            pollInterval = pollInterval,
            hostAcquireTimeout = hostAcquireTimeout,
        ),
    )

    override suspend fun solve(triggerUrl: HttpUrl): SolveResult = stateMachine.solve(triggerUrl)
}

/** Hilt module binding [ChallengeWebViewHost] to [AndroidChallengeWebViewHost]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChallengeHostModule {
    @Binds
    @Singleton
    abstract fun bindChallengeWebViewHost(impl: AndroidChallengeWebViewHost): ChallengeWebViewHost
}
