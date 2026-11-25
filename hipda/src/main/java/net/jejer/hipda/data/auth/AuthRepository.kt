package net.jejer.hipda.data.auth

import kotlinx.coroutines.flow.StateFlow
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.network.access.AccessPriority

/**
 * Coordinates interactive form login, multi-account switching, manual logout suppression, and
 * single-flight silent re-login over the isolated recovery HTTP channel.
 */
interface AuthRepository {

    /** Currently active saved account, or `null` when signed out. */
    val currentAccount: StateFlow<SavedAccount?>

    /** Saved accounts ordered by most recent login time descending. */
    val savedAccounts: StateFlow<List<SavedAccount>>

    /** Observable authentication lifecycle state. */
    val authState: StateFlow<AuthState>

    /** Monotonically increasing session epoch incremented after each successful login or re-login. */
    val sessionEpoch: Long

    /** Performs interactive form login using [passwordOrMd5] (plaintext or 32-char hex MD5). */
    suspend fun login(
        username: String,
        passwordOrMd5: String,
        securityQuestionId: Int = SavedAccount.NO_SECURITY_QUESTION,
        securityAnswer: String = "",
    ): LoginResult

    /** Switches the active session to a previously saved account identified by [uid]. */
    suspend fun switchAccount(uid: String): LoginResult

    /**
     * Marks manual logout in credentials storage, clears forum auth cookies (preserving
     * `cf_clearance`), cancels any in-flight silent re-login, and suppresses further silent
     * re-login until an explicit [login] or [switchAccount] succeeds.
     */
    suspend fun logout()

    /**
     * Removes saved credentials for [uid]. If [uid] is currently active, cancels any in-flight
     * silent re-login and clears forum auth cookies before removing.
     */
    suspend fun removeAccount(uid: String)

    /**
     * Coalesces concurrent silent re-login attempts into at most one in-flight recovery login over
     * `@RecoveryHttpClient`. Returns [SilentReloginResult.Suppressed] or
     * [SilentReloginResult.NotLoggedIn] with zero network requests when manual logout is active or
     * no active account exists.
     */
    suspend fun trySilentRelogin(
        failedEpoch: Long,
        priority: AccessPriority = AccessPriority.UserBlocking,
    ): SilentReloginResult
}
