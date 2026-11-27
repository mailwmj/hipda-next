package net.jejer.hipda.data.auth

import java.io.IOException
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.parser.common.DiscuzPageMeta
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Observable authentication lifecycle state for account UI and repositories. */
sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val account: SavedAccount) : AuthState
    data class Reauthenticating(val account: SavedAccount) : AuthState
    data class SessionExpired(
        val account: SavedAccount,
        val reason: String? = null,
    ) : AuthState
}

/** Categorized non-credential failure reason for [LoginResult.Failure]. */
sealed interface LoginFailureReason {
    data object UnrecognizedSubmitResponse : LoginFailureReason
    data object SessionNotConfirmed : LoginFailureReason
    data class HttpStatus(val code: Int) : LoginFailureReason
    data object Other : LoginFailureReason
}

/** Result of an explicit interactive login or account switch operation. */
sealed interface LoginResult {
    data class Success(
        val account: SavedAccount,
        val message: String = "",
    ) : LoginResult

    data class InvalidCredentials(
        val message: String,
        val remainingAttempts: Int? = null,
    ) : LoginResult

    data class ChallengeRequired(
        val cooldownUntilMs: Long? = null,
        val rejected: Boolean = false,
    ) : LoginResult

    data class NetworkError(
        val message: String,
        val cause: Throwable? = null,
    ) : LoginResult

    data class Failure(
        val message: String,
        val reason: LoginFailureReason = LoginFailureReason.Other,
    ) : LoginResult
}

/** Categorized failure reason for [SilentReloginResult.Failed]. */
enum class SilentReloginFailureReason {
    INVALID_CREDENTIALS,
    CHALLENGE_ABANDONED,
    CHALLENGE_INCOMPLETE,
    CHALLENGE_REJECTED,
    NETWORK_ERROR,
    SERVER_ERROR,
    SESSION_INVALIDATED,
}

/** Result of a single-flight silent re-login attempt. */
sealed interface SilentReloginResult {
    data class Recovered(val newSessionEpoch: Long) : SilentReloginResult
    data object Suppressed : SilentReloginResult
    data object NotLoggedIn : SilentReloginResult
    data class Failed(
        val reason: SilentReloginFailureReason,
        val message: String = "",
        val cause: Throwable? = null,
    ) : SilentReloginResult
}

/** Decoded forum HTML/XML response accompanied by parsed [DiscuzPageMeta]. */
data class ForumHtmlResponse(
    val url: String,
    val statusCode: Int,
    val html: String,
    val meta: DiscuzPageMeta,
)

/** Reason why an authenticated [ForumHttpClient] request could not proceed. */
enum class AuthRequiredReason {
    MANUALLY_LOGGED_OUT,
    NOT_LOGGED_IN,
    RELOGIN_FAILED,
    SESSION_STILL_UNAUTHENTICATED,
}

/** Thrown by [ForumHttpClient] when an authenticated request fails authorization after at most one silent re-login. */
class AuthRequiredException(
    val reason: AuthRequiredReason,
    val reloginResult: SilentReloginResult? = null,
    message: String = defaultMessage(reason),
    cause: Throwable? = null,
) : IOException(message, cause) {
    companion object {
        private fun defaultMessage(reason: AuthRequiredReason): String = when (reason) {
            AuthRequiredReason.MANUALLY_LOGGED_OUT -> "Authentication required: user manually logged out"
            AuthRequiredReason.NOT_LOGGED_IN -> "Authentication required: no active account"
            AuthRequiredReason.RELOGIN_FAILED -> "Authentication required: silent re-login failed"
            AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED ->
                "Authentication required: session remained unauthenticated after retry"
        }
    }
}

/** Thrown by [ForumHttpClient] on non-2xx HTTP status responses that are not Cloudflare challenges. */
class ForumHttpException(
    val statusCode: Int,
    val url: String,
    message: String = "Unexpected HTTP status $statusCode",
) : IOException(message)

internal fun buildForumUrl(forumServer: String, relativePath: String): HttpUrl {
    val trimmedServer = forumServer.trim().trimEnd('/')
    require(trimmedServer.isNotEmpty()) { "Forum server URL must not be blank" }
    val base = if (trimmedServer.endsWith("/forum", ignoreCase = true)) {
        "$trimmedServer/"
    } else {
        "$trimmedServer/forum/"
    }
    val cleanedPath = relativePath.trimStart('/')
    return (base + cleanedPath).toHttpUrl()
}
