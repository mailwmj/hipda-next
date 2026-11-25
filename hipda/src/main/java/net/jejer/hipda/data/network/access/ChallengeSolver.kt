package net.jejer.hipda.data.network.access

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import net.jejer.hipda.ui.challenge.WebViewChallengeSolver
import okhttp3.HttpUrl

/** Outcome of a single Cloudflare challenge solve attempt. */
sealed interface SolveResult {
    data object Cleared : SolveResult
    data object Cancelled : SolveResult
    data class Failed(val cause: Throwable? = null) : SolveResult
}

/** Solves a Cloudflare challenge triggered while accessing [triggerUrl]. */
fun interface ChallengeSolver {
    suspend fun solve(triggerUrl: HttpUrl): SolveResult
}

/** Default fallback solver until the WebView-backed implementation is bound. */
@Singleton
class DefaultChallengeSolver @Inject constructor() : ChallengeSolver {
    override suspend fun solve(triggerUrl: HttpUrl): SolveResult =
        SolveResult.Failed(IllegalStateException("ChallengeSolver is not configured"))
}

/** Isolated Hilt module so the [ChallengeSolver] binding can be replaced independently. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChallengeSolverModule {
    @Binds
    @Singleton
    abstract fun bindChallengeSolver(impl: WebViewChallengeSolver): ChallengeSolver
}
