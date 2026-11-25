package net.jejer.hipda.data.auth

import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.jejer.hipda.data.account.CredentialsStore
import net.jejer.hipda.data.account.SavedAccount
import net.jejer.hipda.data.network.AuthCookieWriteGuard
import net.jejer.hipda.data.network.ChallengeDetector
import net.jejer.hipda.data.network.RecoveryHttpClient
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeAbandonedException
import net.jejer.hipda.data.network.access.ChallengeIncompleteException
import net.jejer.hipda.data.network.access.ChallengeRejectedException
import net.jejer.hipda.data.network.access.ChallengeSolver
import net.jejer.hipda.data.network.access.ChallengeState
import net.jejer.hipda.data.network.access.SiteAccessGateway
import net.jejer.hipda.data.network.cookie.SiteCookieStore
import net.jejer.hipda.data.network.decodeBody
import net.jejer.hipda.data.network.gbkFormBody
import net.jejer.hipda.data.network.webview.WebViewCookieAccess
import net.jejer.hipda.data.network.webview.WebViewCookieDispatcher
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.parser.auth.LoginFormParseResult
import net.jejer.hipda.parser.auth.LoginParser
import net.jejer.hipda.parser.auth.LoginSubmitResult
import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private object NoOpWebViewCookieAccess : WebViewCookieAccess {
    override fun isGetCookieInfoSupported(): Boolean = false
    override fun getCookieInfo(url: String): List<String> = emptyList()
    override fun getCookie(url: String): String? = null
    override fun setCookie(url: String, value: String) = Unit
    override fun flush() = Unit
}

@Singleton
class DefaultAuthRepository internal constructor(
    private val credentialsStore: CredentialsStore,
    private val siteCookieStore: SiteCookieStore,
    private val settingsRepository: SettingsRepository,
    private val siteAccessGateway: SiteAccessGateway,
    private val recoveryGateway: SiteAccessGateway,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val webViewCookieAccess: WebViewCookieAccess = NoOpWebViewCookieAccess,
    private val cookieDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AuthRepository {

    @Inject
    constructor(
        credentialsStore: CredentialsStore,
        siteCookieStore: SiteCookieStore,
        webViewCookieAccess: WebViewCookieAccess,
        @WebViewCookieDispatcher cookieDispatcher: CoroutineDispatcher,
        settingsRepository: SettingsRepository,
        siteAccessGateway: SiteAccessGateway,
        @RecoveryHttpClient recoveryHttpClient: OkHttpClient,
        challengeDetector: ChallengeDetector,
        challengeSolver: ChallengeSolver,
    ) : this(
        credentialsStore = credentialsStore,
        siteCookieStore = siteCookieStore,
        settingsRepository = settingsRepository,
        siteAccessGateway = siteAccessGateway,
        recoveryGateway = SiteAccessGateway(
            client = recoveryHttpClient,
            detector = challengeDetector,
            solver = challengeSolver,
        ),
        clock = System::currentTimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        webViewCookieAccess = webViewCookieAccess,
        cookieDispatcher = cookieDispatcher,
    )

    private data class PersistentSnapshot(
        val accounts: List<SavedAccount> = emptyList(),
        val activeUid: String? = null,
        val manuallyLoggedOut: Boolean = false,
    ) {
        val activeAccount: SavedAccount?
            get() = if (manuallyLoggedOut || activeUid == null) {
                null
            } else {
                accounts.firstOrNull { it.uid == activeUid }
            }
    }

    private sealed interface RuntimeOverlay {
        data object Normal : RuntimeOverlay
        data class Reauthenticating(val uid: String) : RuntimeOverlay
        data class SessionExpired(val uid: String, val reason: String? = null) : RuntimeOverlay
    }

    private class ActiveRelogin(
        val generation: Long,
        val account: SavedAccount,
        val priority: AccessPriority,
    ) {
        val deferred = CompletableDeferred<SilentReloginResult>()
        var completed = false
        var job: Job? = null
    }

    private sealed interface RawLoginOutcome {
        data class LoggedIn(
            val session: DiscuzSessionInfo?,
            val message: String,
        ) : RawLoginOutcome

        data class InvalidCredentials(
            val message: String,
            val remainingAttempts: Int?,
        ) : RawLoginOutcome

        data class ChallengeBlocked(
            val reason: SilentReloginFailureReason,
            val cooldownUntilMs: Long?,
            val rejected: Boolean,
            val cause: Throwable,
        ) : RawLoginOutcome

        data class NetworkFailure(
            val message: String,
            val cause: Throwable,
        ) : RawLoginOutcome

        data class ServerFailure(
            val message: String,
        ) : RawLoginOutcome
    }

    private val operationMutex = Mutex()
    // Lock hierarchy: always acquire cookieWriteLock before stateLock (never reverse).
    private val cookieWriteLock = Any()
    private val stateLock = Any()

    private var persistentSnapshot = PersistentSnapshot()
    private var runtimeOverlay: RuntimeOverlay = RuntimeOverlay.Normal
    private var authGeneration: Long = 0L
    private var explicitOperationCount: Int = 0
    private var inFlightForegroundRelogin: ActiveRelogin? = null
    private var inFlightBackgroundRelogin: ActiveRelogin? = null
    private var rejectedCredentialKey: Pair<String, String>? = null

    @Volatile
    private var currentSessionEpoch: Long = 0L

    private val _currentAccount = MutableStateFlow<SavedAccount?>(null)
    override val currentAccount: StateFlow<SavedAccount?> = _currentAccount.asStateFlow()

    private val _savedAccounts = MutableStateFlow<List<SavedAccount>>(emptyList())
    override val savedAccounts: StateFlow<List<SavedAccount>> = _savedAccounts.asStateFlow()

    private val _authState = MutableStateFlow<AuthState>(AuthState.SignedOut)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    override val sessionEpoch: Long
        get() = currentSessionEpoch

    init {
        scope.launch {
            combine(
                credentialsStore.accounts,
                credentialsStore.activeUid,
                credentialsStore.manuallyLoggedOut,
            ) { _, _, _ -> Unit }
                .conflate()
                .collect {
                    val genBeforeRead = synchronized(stateLock) {
                        if (explicitOperationCount > 0) return@collect
                        authGeneration
                    }
                    val latest = readPersistentSnapshotFromStore()
                    synchronized(stateLock) {
                        if (explicitOperationCount == 0 && authGeneration == genBeforeRead) {
                            applyPersistentSnapshotLocked(latest)
                        }
                    }
                }
        }
    }

    /**
     * Captures a per-request [AuthCookieWriteGuard] pinned to the current auth generation so
     * in-flight forum requests completing after logout / account switch / active account removal
     * cannot write stale authentication cookies back into [SiteCookieStore].
     */
    internal fun captureCookieWriteGuard(): AuthCookieWriteGuard {
        val capturedGeneration = synchronized(stateLock) { authGeneration }
        return createCookieWriteGuard(
            expectedGeneration = capturedGeneration,
            allowDuringManualLogout = false,
        )
    }

    override suspend fun login(
        username: String,
        passwordOrMd5: String,
        securityQuestionId: Int,
        securityAnswer: String,
    ): LoginResult {
        val trimmedUsername = username.trim()
        if (trimmedUsername.isEmpty()) {
            return LoginResult.InvalidCredentials("请输入用户名")
        }
        val passwordMd5 = try {
            computeLegacyPasswordMd5(passwordOrMd5)
        } catch (_: IllegalArgumentException) {
            return LoginResult.InvalidCredentials("请输入密码")
        }
        val normalizedAnswer = if (securityQuestionId == SavedAccount.NO_SECURITY_QUESTION) {
            ""
        } else {
            securityAnswer
        }

        return operationMutex.withLock {
            runExplicitOperation { opGeneration ->
                val forumServer = readForumServerOrNull()
                    ?: return@runExplicitOperation LoginResult.Failure("论坛服务器地址无效")

                // S1: Clear existing forum auth cookies (store + WebView) before interactive login
                // so a stale session for the same username cannot bypass password verification.
                clearAuthCookiesForServer(forumServer)

                val cookieGuard = createCookieWriteGuard(
                    expectedGeneration = opGeneration,
                    allowDuringManualLogout = true,
                )

                when (
                    val raw = executeLoginProtocol(
                        gateway = siteAccessGateway,
                        forumServer = forumServer,
                        username = trimmedUsername,
                        passwordMd5 = passwordMd5,
                        securityQuestionId = securityQuestionId,
                        securityAnswer = normalizedAnswer,
                        expectedUid = null,
                        priority = AccessPriority.UserExplicit,
                        requireSessionVerification = true,
                        cookieWriteGuard = cookieGuard,
                    )
                ) {
                    is RawLoginOutcome.LoggedIn -> {
                        val session = raw.session
                        val resolvedUid = session?.uid?.takeIf { it.isNotBlank() }
                            ?: return@runExplicitOperation LoginResult.Failure("登录成功但无法确认账号信息")
                        val canonicalUsername = session.username?.takeIf { it.isNotBlank() } ?: trimmedUsername
                        val saved = SavedAccount(
                            uid = resolvedUid,
                            username = canonicalUsername,
                            passwordMd5 = passwordMd5,
                            securityQuestionId = securityQuestionId,
                            securityAnswer = normalizedAnswer,
                            lastLoginAt = clock(),
                        )
                        credentialsStore.upsert(saved)
                        credentialsStore.setActive(saved.uid)
                        val refreshed = readPersistentSnapshotFromStore()
                        synchronized(stateLock) {
                            currentSessionEpoch++
                            rejectedCredentialKey = null
                            runtimeOverlay = RuntimeOverlay.Normal
                            applyPersistentSnapshotLocked(refreshed)
                        }
                        LoginResult.Success(account = saved, message = raw.message)
                    }

                    is RawLoginOutcome.InvalidCredentials -> LoginResult.InvalidCredentials(
                        message = raw.message,
                        remainingAttempts = raw.remainingAttempts,
                    )

                    is RawLoginOutcome.ChallengeBlocked -> LoginResult.ChallengeRequired(
                        cooldownUntilMs = raw.cooldownUntilMs,
                        rejected = raw.rejected,
                    )

                    is RawLoginOutcome.NetworkFailure -> LoginResult.NetworkError(
                        message = raw.message,
                        cause = raw.cause,
                    )

                    is RawLoginOutcome.ServerFailure -> LoginResult.Failure(raw.message)
                }
            }
        }
    }

    override suspend fun switchAccount(uid: String): LoginResult {
        val targetUid = uid.trim()
        if (targetUid.isEmpty()) {
            return LoginResult.Failure("账号标识不能为空")
        }

        return operationMutex.withLock {
            val initialSnapshot = readPersistentSnapshotFromStore()
            synchronized(stateLock) {
                applyPersistentSnapshotLocked(initialSnapshot)
            }
            val targetAccount = initialSnapshot.accounts.firstOrNull { it.uid == targetUid }
                ?: return@withLock LoginResult.Failure("未找到已保存的目标账号")

            runExplicitOperation { opGeneration ->
                val forumServer = readForumServerOrNull()
                    ?: return@runExplicitOperation LoginResult.Failure("论坛服务器地址无效")

                // Clear prior session cookies in both SiteCookieStore and WebView CookieManager
                // (preserving cf_clearance) before switching accounts.
                clearAuthCookiesForServer(forumServer)

                val cookieGuard = createCookieWriteGuard(
                    expectedGeneration = opGeneration,
                    allowDuringManualLogout = true,
                )

                val raw = executeLoginProtocol(
                    gateway = siteAccessGateway,
                    forumServer = forumServer,
                    username = targetAccount.username,
                    passwordMd5 = targetAccount.passwordMd5,
                    securityQuestionId = targetAccount.securityQuestionId,
                    securityAnswer = targetAccount.securityAnswer,
                    expectedUid = targetAccount.uid,
                    priority = AccessPriority.UserExplicit,
                    requireSessionVerification = false,
                    cookieWriteGuard = cookieGuard,
                )

                when (raw) {
                    is RawLoginOutcome.LoggedIn -> {
                        val canonicalUsername = raw.session?.username?.takeIf { it.isNotBlank() }
                            ?: targetAccount.username
                        val updatedAccount = targetAccount.copy(
                            username = canonicalUsername,
                            lastLoginAt = clock(),
                        )
                        credentialsStore.upsert(updatedAccount)
                        credentialsStore.setActive(updatedAccount.uid)
                        val refreshed = readPersistentSnapshotFromStore()
                        synchronized(stateLock) {
                            currentSessionEpoch++
                            rejectedCredentialKey = null
                            runtimeOverlay = RuntimeOverlay.Normal
                            applyPersistentSnapshotLocked(refreshed)
                        }
                        LoginResult.Success(account = updatedAccount, message = raw.message)
                    }

                    is RawLoginOutcome.InvalidCredentials -> {
                        markSwitchFallback(initialSnapshot.activeAccount, raw.message)
                        LoginResult.InvalidCredentials(raw.message, raw.remainingAttempts)
                    }

                    is RawLoginOutcome.ChallengeBlocked -> {
                        markSwitchFallback(initialSnapshot.activeAccount, "安全验证未完成")
                        LoginResult.ChallengeRequired(
                            cooldownUntilMs = raw.cooldownUntilMs,
                            rejected = raw.rejected,
                        )
                    }

                    is RawLoginOutcome.NetworkFailure -> {
                        markSwitchFallback(initialSnapshot.activeAccount, raw.message)
                        LoginResult.NetworkError(raw.message, raw.cause)
                    }

                    is RawLoginOutcome.ServerFailure -> {
                        markSwitchFallback(initialSnapshot.activeAccount, raw.message)
                        LoginResult.Failure(raw.message)
                    }
                }
            }
        }
    }

    override suspend fun logout() {
        operationMutex.withLock {
            runExplicitOperation { _ ->
                credentialsStore.markManualLogout()
                val refreshed = readPersistentSnapshotFromStore()
                synchronized(cookieWriteLock) {
                    synchronized(stateLock) {
                        authGeneration++
                        rejectedCredentialKey = null
                        runtimeOverlay = RuntimeOverlay.Normal
                        applyPersistentSnapshotLocked(refreshed)
                    }
                }
                readForumServerOrNull()?.let { clearAuthCookiesForServer(it) }
            }
        }
    }

    override suspend fun removeAccount(uid: String) {
        val targetUid = uid.trim()
        if (targetUid.isEmpty()) return

        operationMutex.withLock {
            val beforeSnapshot = readPersistentSnapshotFromStore()
            val removingActive = beforeSnapshot.activeUid == targetUid
            if (removingActive) {
                runExplicitOperation { _ ->
                    readForumServerOrNull()?.let { clearAuthCookiesForServer(it) }
                    credentialsStore.remove(targetUid)
                    val refreshed = readPersistentSnapshotFromStore()
                    synchronized(stateLock) {
                        if (rejectedCredentialKey?.first == targetUid) {
                            rejectedCredentialKey = null
                        }
                        runtimeOverlay = RuntimeOverlay.Normal
                        applyPersistentSnapshotLocked(refreshed)
                    }
                }
            } else {
                synchronized(cookieWriteLock) {
                    synchronized(stateLock) {
                        explicitOperationCount++
                        authGeneration++
                    }
                }
                try {
                    credentialsStore.remove(targetUid)
                    val refreshed = readPersistentSnapshotFromStore()
                    synchronized(stateLock) {
                        if (rejectedCredentialKey?.first == targetUid) {
                            rejectedCredentialKey = null
                        }
                        applyPersistentSnapshotLocked(refreshed)
                    }
                } finally {
                    synchronized(stateLock) {
                        explicitOperationCount = (explicitOperationCount - 1).coerceAtLeast(0)
                    }
                }
            }
        }
    }

    override suspend fun trySilentRelogin(
        failedEpoch: Long,
        priority: AccessPriority,
    ): SilentReloginResult {
        val genBeforeRead = synchronized(stateLock) { authGeneration }
        val freshSnapshot = readPersistentSnapshotFromStore()

        var shouldLaunch = false
        var immediateResult: SilentReloginResult? = null
        var awaitForegroundFromBackground = false
        val targetFlight: ActiveRelogin? = synchronized(stateLock) {
            if (explicitOperationCount > 0 || authGeneration != genBeforeRead) {
                immediateResult = SilentReloginResult.Failed(
                    reason = SilentReloginFailureReason.SESSION_INVALIDATED,
                    message = "登录状态正在变更",
                )
                return@synchronized null
            }
            applyPersistentSnapshotLocked(freshSnapshot)

            if (persistentSnapshot.manuallyLoggedOut) {
                immediateResult = SilentReloginResult.Suppressed
                return@synchronized null
            }
            val activeAccount = persistentSnapshot.activeAccount
            if (activeAccount == null) {
                immediateResult = SilentReloginResult.NotLoggedIn
                return@synchronized null
            }

            if (failedEpoch < currentSessionEpoch) {
                immediateResult = SilentReloginResult.Recovered(currentSessionEpoch)
                return@synchronized null
            }

            if (rejectedCredentialKey == (activeAccount.uid to activeAccount.passwordMd5)) {
                if (inFlightForegroundRelogin == null && priority != AccessPriority.BackgroundSilent) {
                    runtimeOverlay = RuntimeOverlay.SessionExpired(activeAccount.uid, "账号密码已变更或失效")
                    recomputeStateFlowsLocked()
                }
                immediateResult = SilentReloginResult.Failed(
                    reason = SilentReloginFailureReason.INVALID_CREDENTIALS,
                    message = "账号密码已变更或失效",
                )
                return@synchronized null
            }

            if (priority == AccessPriority.BackgroundSilent) {
                // BackgroundSilent abandons immediately if any challenge or cooldown is active,
                // WITHOUT mutating global authState (neither setting SessionExpired nor
                // overwriting an in-flight foreground Reauthenticating state).
                if (isAnyChallengeGatewayBlocked()) {
                    immediateResult = backgroundChallengeAbandonedResult()
                    return@synchronized null
                }

                val existingForeground = inFlightForegroundRelogin
                if (existingForeground != null) {
                    awaitForegroundFromBackground = true
                    existingForeground
                } else {
                    val existingBackground = inFlightBackgroundRelogin
                    if (existingBackground != null) {
                        existingBackground
                    } else {
                        val created = ActiveRelogin(
                            generation = authGeneration,
                            account = activeAccount,
                            priority = AccessPriority.BackgroundSilent,
                        )
                        inFlightBackgroundRelogin = created
                        shouldLaunch = true
                        created
                    }
                }
            } else {
                // Foreground (UserBlocking / UserExplicit) NEVER coalesces onto an in-flight
                // BackgroundSilent flight so a background challenge abandonment cannot short-circuit
                // the foreground Cloudflare challenge solver path.
                val existingForeground = inFlightForegroundRelogin
                if (existingForeground != null) {
                    existingForeground
                } else {
                    val created = ActiveRelogin(
                        generation = authGeneration,
                        account = activeAccount,
                        priority = priority,
                    )
                    inFlightForegroundRelogin = created
                    runtimeOverlay = RuntimeOverlay.Reauthenticating(activeAccount.uid)
                    recomputeStateFlowsLocked()
                    shouldLaunch = true
                    created
                }
            }
        }

        immediateResult?.let { return it }
        val flight = targetFlight ?: return SilentReloginResult.Failed(
            reason = SilentReloginFailureReason.SESSION_INVALIDATED,
            message = "登录状态已变更",
        )
        if (shouldLaunch) {
            launchSilentRelogin(flight)
        }
        return if (awaitForegroundFromBackground) {
            awaitForegroundForBackground(flight)
        } else {
            flight.deferred.await()
        }
    }

    private fun createCookieWriteGuard(
        expectedGeneration: Long,
        allowDuringManualLogout: Boolean,
    ): AuthCookieWriteGuard = AuthCookieWriteGuard { saveAction ->
        synchronized(cookieWriteLock) {
            val allowed = synchronized(stateLock) {
                authGeneration == expectedGeneration &&
                    (allowDuringManualLogout || !persistentSnapshot.manuallyLoggedOut)
            }
            saveAction(allowed)
        }
    }

    private fun isAnyChallengeGatewayBlocked(): Boolean =
        siteAccessGateway.challengeState.value !is ChallengeState.Idle ||
            recoveryGateway.challengeState.value !is ChallengeState.Idle

    private fun backgroundChallengeAbandonedResult(): SilentReloginResult.Failed =
        SilentReloginResult.Failed(
            reason = SilentReloginFailureReason.CHALLENGE_ABANDONED,
            message = "后台请求遇安全验证已放弃",
            cause = ChallengeAbandonedException(),
        )

    /**
     * Awaits an in-flight foreground relogin on behalf of a [AccessPriority.BackgroundSilent] caller,
     * immediately returning [SilentReloginFailureReason.CHALLENGE_ABANDONED] if either gateway enters
     * [ChallengeState.Solving] or [ChallengeState.Cooldown], without cancelling the foreground
     * flight or modifying global [authState].
     */
    private suspend fun awaitForegroundForBackground(flight: ActiveRelogin): SilentReloginResult =
        coroutineScope {
            if (isAnyChallengeGatewayBlocked()) {
                return@coroutineScope backgroundChallengeAbandonedResult()
            }
            val outcome = CompletableDeferred<SilentReloginResult>()
            val flightWatcher = launch {
                val res = flight.deferred.await()
                val mapped = if (
                    res is SilentReloginResult.Failed &&
                    (
                        res.reason == SilentReloginFailureReason.CHALLENGE_INCOMPLETE ||
                            res.reason == SilentReloginFailureReason.CHALLENGE_REJECTED
                        )
                ) {
                    backgroundChallengeAbandonedResult()
                } else {
                    res
                }
                outcome.complete(mapped)
            }
            val challengeWatcher = launch {
                combine(
                    siteAccessGateway.challengeState,
                    recoveryGateway.challengeState,
                ) { primary, recovery ->
                    primary !is ChallengeState.Idle || recovery !is ChallengeState.Idle
                }.first { blocked -> blocked }
                outcome.complete(backgroundChallengeAbandonedResult())
            }
            try {
                outcome.await()
            } finally {
                flightWatcher.cancel()
                challengeWatcher.cancel()
            }
        }

    private suspend fun <T> runExplicitOperation(block: suspend (opGeneration: Long) -> T): T {
        val (opGeneration, detachedFlights) = synchronized(cookieWriteLock) {
            synchronized(stateLock) {
                explicitOperationCount++
                authGeneration++
                authGeneration to detachAllInFlightReloginsLocked()
            }
        }
        try {
            if (detachedFlights.isNotEmpty()) {
                val invalidated = SilentReloginResult.Failed(
                    reason = SilentReloginFailureReason.SESSION_INVALIDATED,
                    message = "登录状态已变更",
                )
                for (flight in detachedFlights) {
                    flight.deferred.complete(invalidated)
                }
                withContext(NonCancellable) {
                    for (flight in detachedFlights) {
                        flight.job?.cancelAndJoin()
                    }
                }
            }
            return block(opGeneration)
        } finally {
            synchronized(stateLock) {
                explicitOperationCount = (explicitOperationCount - 1).coerceAtLeast(0)
            }
        }
    }

    private fun markSwitchFallback(previousActive: SavedAccount?, reason: String) {
        synchronized(stateLock) {
            runtimeOverlay = if (previousActive != null) {
                RuntimeOverlay.SessionExpired(previousActive.uid, reason)
            } else {
                RuntimeOverlay.Normal
            }
            recomputeStateFlowsLocked()
        }
    }

    private fun detachAllInFlightReloginsLocked(): List<ActiveRelogin> {
        val detached = ArrayList<ActiveRelogin>(2)
        inFlightForegroundRelogin?.let { old ->
            inFlightForegroundRelogin = null
            old.completed = true
            detached.add(old)
        }
        inFlightBackgroundRelogin?.let { old ->
            inFlightBackgroundRelogin = null
            old.completed = true
            detached.add(old)
        }
        return detached
    }

    private fun launchSilentRelogin(flight: ActiveRelogin) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runSilentRelogin(flight)
        }
        val alreadyCompleted = synchronized(stateLock) {
            if (flight.completed) {
                true
            } else {
                flight.job = job
                false
            }
        }
        if (alreadyCompleted) {
            job.cancel()
            return
        }
        if (!job.start() && job.isCancelled && !flight.deferred.isCompleted) {
            completeSilentRelogin(
                flight = flight,
                rawOutcome = SilentReloginResult.Failed(
                    reason = SilentReloginFailureReason.SESSION_INVALIDATED,
                    message = "认证作用域已停止",
                ),
            )
        }
    }

    private suspend fun runSilentRelogin(flight: ActiveRelogin) {
        var outcome: SilentReloginResult = SilentReloginResult.Failed(
            reason = SilentReloginFailureReason.NETWORK_ERROR,
            message = "静默重登异常",
        )
        try {
            val forumServer = readForumServerOrNull()
            outcome = if (forumServer == null) {
                SilentReloginResult.Failed(
                    reason = SilentReloginFailureReason.SERVER_ERROR,
                    message = "论坛服务器地址无效",
                )
            } else {
                val cookieGuard = createCookieWriteGuard(
                    expectedGeneration = flight.generation,
                    allowDuringManualLogout = false,
                )
                when (
                    val raw = executeLoginProtocol(
                        gateway = recoveryGateway,
                        forumServer = forumServer,
                        username = flight.account.username,
                        passwordMd5 = flight.account.passwordMd5,
                        securityQuestionId = flight.account.securityQuestionId,
                        securityAnswer = flight.account.securityAnswer,
                        expectedUid = flight.account.uid,
                        priority = flight.priority,
                        requireSessionVerification = false,
                        cookieWriteGuard = cookieGuard,
                    )
                ) {
                    is RawLoginOutcome.LoggedIn -> SilentReloginResult.Recovered(0L)
                    is RawLoginOutcome.InvalidCredentials -> SilentReloginResult.Failed(
                        reason = SilentReloginFailureReason.INVALID_CREDENTIALS,
                        message = raw.message,
                    )

                    is RawLoginOutcome.ChallengeBlocked -> SilentReloginResult.Failed(
                        reason = raw.reason,
                        message = "安全验证未通过",
                        cause = raw.cause,
                    )

                    is RawLoginOutcome.NetworkFailure -> SilentReloginResult.Failed(
                        reason = SilentReloginFailureReason.NETWORK_ERROR,
                        message = raw.message,
                        cause = raw.cause,
                    )

                    is RawLoginOutcome.ServerFailure -> SilentReloginResult.Failed(
                        reason = SilentReloginFailureReason.SERVER_ERROR,
                        message = raw.message,
                    )
                }
            }
        } catch (_: CancellationException) {
            outcome = SilentReloginResult.Failed(
                reason = SilentReloginFailureReason.SESSION_INVALIDATED,
                message = "静默重登已取消",
            )
        } catch (e: Exception) {
            outcome = SilentReloginResult.Failed(
                reason = SilentReloginFailureReason.NETWORK_ERROR,
                message = "静默重登异常",
                cause = e,
            )
        } finally {
            completeSilentRelogin(flight, outcome)
        }
    }

    private fun completeSilentRelogin(
        flight: ActiveRelogin,
        rawOutcome: SilentReloginResult,
    ) {
        val finalResult: SilentReloginResult = synchronized(stateLock) {
            if (flight.completed) {
                return
            }
            flight.completed = true
            if (inFlightForegroundRelogin === flight) {
                inFlightForegroundRelogin = null
            }
            if (inFlightBackgroundRelogin === flight) {
                inFlightBackgroundRelogin = null
            }

            val isCurrentSession =
                flight.generation == authGeneration &&
                    !persistentSnapshot.manuallyLoggedOut &&
                    persistentSnapshot.activeAccount?.uid == flight.account.uid

            if (!isCurrentSession) {
                SilentReloginResult.Failed(
                    reason = SilentReloginFailureReason.SESSION_INVALIDATED,
                    message = "登录状态已变更",
                )
            } else {
                when (rawOutcome) {
                    is SilentReloginResult.Recovered -> {
                        currentSessionEpoch++
                        rejectedCredentialKey = null
                        if (inFlightForegroundRelogin == null) {
                            runtimeOverlay = RuntimeOverlay.Normal
                            recomputeStateFlowsLocked()
                        }
                        SilentReloginResult.Recovered(currentSessionEpoch)
                    }

                    is SilentReloginResult.Failed -> {
                        if (rawOutcome.reason == SilentReloginFailureReason.CHALLENGE_ABANDONED) {
                            // B2: BackgroundSilent abandoning a Cloudflare challenge does NOT mean
                            // credentials/session failed; never set SessionExpired or clobber an
                            // in-flight foreground Reauthenticating state.
                            rawOutcome
                        } else {
                            if (rawOutcome.reason == SilentReloginFailureReason.INVALID_CREDENTIALS) {
                                rejectedCredentialKey = flight.account.uid to flight.account.passwordMd5
                            }
                            if (inFlightForegroundRelogin == null) {
                                runtimeOverlay = RuntimeOverlay.SessionExpired(
                                    uid = flight.account.uid,
                                    reason = rawOutcome.message.ifBlank { null },
                                )
                                recomputeStateFlowsLocked()
                            }
                            rawOutcome
                        }
                    }

                    SilentReloginResult.Suppressed,
                    SilentReloginResult.NotLoggedIn,
                    -> rawOutcome
                }
            }
        }
        flight.deferred.complete(finalResult)
    }

    private suspend fun executeLoginProtocol(
        gateway: SiteAccessGateway,
        forumServer: String,
        username: String,
        passwordMd5: String,
        securityQuestionId: Int,
        securityAnswer: String,
        expectedUid: String?,
        priority: AccessPriority,
        requireSessionVerification: Boolean,
        cookieWriteGuard: AuthCookieWriteGuard,
    ): RawLoginOutcome {
        return try {
            val loginPageUrl = buildForumUrl(forumServer, LOGIN_PAGE_PATH)
            val formhashOrSession = fetchLoginForm(
                gateway = gateway,
                forumServer = forumServer,
                loginPageUrl = loginPageUrl,
                expectedUsername = username,
                expectedUid = expectedUid,
                priority = priority,
                cookieWriteGuard = cookieWriteGuard,
            )

            val formhash = when (formhashOrSession) {
                is FormFetchStep.Ready -> formhashOrSession.formhash
                is FormFetchStep.AlreadyLoggedIn -> {
                    return RawLoginOutcome.LoggedIn(
                        session = formhashOrSession.session,
                        message = "",
                    )
                }

                is FormFetchStep.Failed -> return formhashOrSession.outcome
            }

            val submitUrl = buildForumUrl(forumServer, LOGIN_SUBMIT_PATH)
            val refererUrl = buildForumUrl(forumServer, INDEX_PATH).toString()
            val formFields = listOf(
                "m_formhash" to formhash,
                "referer" to refererUrl,
                "loginfield" to "username",
                "username" to username,
                "password" to passwordMd5,
                "questionid" to securityQuestionId.toString(),
                "answer" to securityAnswer,
                "cookietime" to COOKIE_TIME_SECONDS,
            )
            val postRequest = Request.Builder()
                .url(submitUrl)
                .tag(AuthCookieWriteGuard::class.java, cookieWriteGuard)
                .post(gbkFormBody(formFields))
                .build()

            val submitText = executeDecoded(gateway, postRequest, priority)
            when (val parsedSubmit = LoginParser.parseLoginSubmitResponse(submitText)) {
                is LoginSubmitResult.Success -> {
                    if (!requireSessionVerification) {
                        RawLoginOutcome.LoggedIn(session = null, message = parsedSubmit.message)
                    } else {
                        val verifyRequest = Request.Builder()
                            .url(loginPageUrl)
                            .tag(AuthCookieWriteGuard::class.java, cookieWriteGuard)
                            .get()
                            .build()
                        val verifyHtml = executeDecoded(
                            gateway = gateway,
                            request = verifyRequest,
                            priority = priority,
                        )
                        val session = DiscuzCommonParser.parsePageMeta(verifyHtml).session
                        if (session.isLoggedIn && !session.uid.isNullOrBlank()) {
                            RawLoginOutcome.LoggedIn(session = session, message = parsedSubmit.message)
                        } else {
                            RawLoginOutcome.ServerFailure("登录成功但未能确认账号会话")
                        }
                    }
                }

                is LoginSubmitResult.Failed -> RawLoginOutcome.InvalidCredentials(
                    message = parsedSubmit.message,
                    remainingAttempts = parsedSubmit.remainingAttempts,
                )

                is LoginSubmitResult.UnknownError -> RawLoginOutcome.ServerFailure("登录失败，服务器返回异常响应")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChallengeAbandonedException) {
            RawLoginOutcome.ChallengeBlocked(
                reason = SilentReloginFailureReason.CHALLENGE_ABANDONED,
                cooldownUntilMs = null,
                rejected = false,
                cause = e,
            )
        } catch (e: ChallengeRejectedException) {
            RawLoginOutcome.ChallengeBlocked(
                reason = SilentReloginFailureReason.CHALLENGE_REJECTED,
                cooldownUntilMs = e.cooldownUntilMs,
                rejected = true,
                cause = e,
            )
        } catch (e: ChallengeIncompleteException) {
            RawLoginOutcome.ChallengeBlocked(
                reason = SilentReloginFailureReason.CHALLENGE_INCOMPLETE,
                cooldownUntilMs = e.cooldownUntilMs,
                rejected = false,
                cause = e,
            )
        } catch (e: ForumHttpException) {
            RawLoginOutcome.ServerFailure("服务器返回异常状态 (${e.statusCode})")
        } catch (e: IOException) {
            RawLoginOutcome.NetworkFailure("网络连接失败", e)
        }
    }

    private sealed interface FormFetchStep {
        data class Ready(val formhash: String) : FormFetchStep
        data class AlreadyLoggedIn(val session: DiscuzSessionInfo) : FormFetchStep
        data class Failed(val outcome: RawLoginOutcome) : FormFetchStep
    }

    private suspend fun fetchLoginForm(
        gateway: SiteAccessGateway,
        forumServer: String,
        loginPageUrl: HttpUrl,
        expectedUsername: String,
        expectedUid: String?,
        priority: AccessPriority,
        cookieWriteGuard: AuthCookieWriteGuard,
    ): FormFetchStep {
        var clearedStaleSession = false
        while (true) {
            val request = Request.Builder()
                .url(loginPageUrl)
                .tag(AuthCookieWriteGuard::class.java, cookieWriteGuard)
                .get()
                .build()
            val html = executeDecoded(
                gateway = gateway,
                request = request,
                priority = priority,
            )
            when (val parsed = LoginParser.parseLoginPage(html)) {
                is LoginFormParseResult.Ready -> return FormFetchStep.Ready(parsed.formhash)
                is LoginFormParseResult.Error -> return FormFetchStep.Failed(
                    RawLoginOutcome.ServerFailure(parsed.message),
                )

                is LoginFormParseResult.AlreadyLoggedIn -> {
                    val session = parsed.session
                    val matchesExpected = if (expectedUid != null) {
                        session.uid == expectedUid
                    } else {
                        !session.uid.isNullOrBlank() &&
                            session.username.equals(expectedUsername, ignoreCase = true)
                    }
                    if (matchesExpected) {
                        return FormFetchStep.AlreadyLoggedIn(session)
                    }
                    if (clearedStaleSession) {
                        return FormFetchStep.Failed(RawLoginOutcome.ServerFailure("已有冲突会话，无法获取登录表单"))
                    }
                    clearedStaleSession = true
                    clearAuthCookiesForServer(forumServer)
                }
            }
        }
    }

    private suspend fun executeDecoded(
        gateway: SiteAccessGateway,
        request: Request,
        priority: AccessPriority,
    ): String {
        gateway.execute(request = request, priority = priority).use { response ->
            if (!response.isSuccessful) {
                throw ForumHttpException(response.code, response.request.url.toString())
            }
            val bytes = response.body.bytes()
            val contentType = response.header("Content-Type") ?: response.body.contentType()?.toString()
            return decodeBody(bytes, contentType)
        }
    }

    private suspend fun clearAuthCookiesForServer(forumServer: String) {
        val forumUrl = runCatching { buildForumUrl(forumServer, "") }.getOrNull() ?: return
        val host = forumUrl.host.lowercase(Locale.ROOT)
        val storeAuthCookieNames = LinkedHashSet<String>()

        // Perform SiteCookieStore clearing under cookieWriteLock so it is strictly serialized with
        // AppCookieInterceptor's AuthCookieWriteGuard check+save critical section, while never
        // clearing concurrently persisted cf_clearance / __cf_bm cookies.
        synchronized(cookieWriteLock) {
            val matchingStoreCookies = siteCookieStore.snapshot().filter { it.matchesHost(host) }
            val authStoreCookies = matchingStoreCookies.filterNot { isPreservedCloudflareCookie(it.name) }
            authStoreCookies.mapTo(storeAuthCookieNames) { it.name }
            if (authStoreCookies.isNotEmpty()) {
                siteCookieStore.saveFromResponse(forumUrl, authStoreCookies.map(::buildExpiredStoreCookie))
            }
        }

        // B1: Also clear forum login cookies from Android WebView CookieManager (preserving
        // cf_clearance / __cf_bm) so subsequent Cloudflare challenge syncs cannot resurrect an
        // old account's 30-day cdb_auth cookie.
        clearWebViewAuthCookies(forumUrl, host, storeAuthCookieNames)
    }

    private suspend fun clearWebViewAuthCookies(
        forumUrl: HttpUrl,
        canonicalHost: String,
        extraAuthCookieNames: Set<String>,
    ) {
        val rootUrl = forumUrl.newBuilder().encodedPath("/").build()
        val targetForumUrl = forumUrl.toString()
        val targetRootUrl = rootUrl.toString()

        withContext(cookieDispatcher) {
            val expireHeaders = LinkedHashSet<String>()
            if (webViewCookieAccess.isGetCookieInfoSupported()) {
                val rawInfos = LinkedHashSet<String>()
                rawInfos.addAll(webViewCookieAccess.getCookieInfo(targetForumUrl))
                rawInfos.addAll(webViewCookieAccess.getCookieInfo(targetRootUrl))
                for (raw in rawInfos) {
                    val parsed = Cookie.parse(forumUrl, raw) ?: Cookie.parse(rootUrl, raw) ?: continue
                    if (!parsed.matchesHost(canonicalHost)) continue
                    if (isPreservedCloudflareCookie(parsed.name)) continue
                    expireHeaders.add(
                        buildWebViewExpirationHeader(
                            cookieName = parsed.name,
                            rawSetCookie = raw,
                            fallbackPath = parsed.path,
                        ),
                    )
                }
            } else {
                val observedNames = LinkedHashSet<String>()
                observedNames.addAll(extractLegacyCookieNames(webViewCookieAccess.getCookie(targetForumUrl)))
                observedNames.addAll(extractLegacyCookieNames(webViewCookieAccess.getCookie(targetRootUrl)))
                observedNames.addAll(extraAuthCookieNames)
                val targetNames = observedNames.filterNot(::isPreservedCloudflareCookie)
                if (targetNames.isNotEmpty()) {
                    val domains = buildLegacyScopeDomains(forumUrl)
                    val secureVariants = if (forumUrl.isHttps) listOf(false, true) else listOf(false)
                    for (name in targetNames) {
                        for (domain in domains) {
                            for (path in LEGACY_AUTH_COOKIE_PATHS) {
                                for (secure in secureVariants) {
                                    expireHeaders.add(
                                        buildLegacyAuthExpirationHeader(
                                            cookieName = name,
                                            domain = domain,
                                            path = path,
                                            secure = secure,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (expireHeaders.isNotEmpty()) {
                for (header in expireHeaders) {
                    webViewCookieAccess.setCookie(targetForumUrl, header)
                }
                webViewCookieAccess.flush()
            }
        }
    }

    private fun extractLegacyCookieNames(rawHeader: String?): List<String> {
        if (rawHeader.isNullOrBlank()) return emptyList()
        return rawHeader.split(';').mapNotNull { segment ->
            val eqIndex = segment.indexOf('=')
            if (eqIndex <= 0) return@mapNotNull null
            segment.substring(0, eqIndex).trim().ifEmpty { null }
        }
    }

    private fun buildWebViewExpirationHeader(
        cookieName: String,
        rawSetCookie: String,
        fallbackPath: String,
    ): String {
        var domain: String? = null
        var path: String? = null
        var secure = false
        var httpOnly = false
        var sameSite: String? = null
        var partitioned = false

        for (segment in rawSetCookie.split(';').drop(1)) {
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) continue
            val eqIndex = trimmed.indexOf('=')
            val attrName = (if (eqIndex >= 0) trimmed.substring(0, eqIndex) else trimmed).trim()
            val attrValue = if (eqIndex >= 0) trimmed.substring(eqIndex + 1).trim() else ""
            when (attrName.lowercase(Locale.ROOT)) {
                "domain" -> if (attrValue.isNotEmpty()) domain = attrValue
                "path" -> if (attrValue.isNotEmpty()) path = attrValue
                "secure" -> secure = true
                "httponly" -> httpOnly = true
                "samesite" -> if (attrValue.isNotEmpty()) sameSite = attrValue
                "partitioned" -> partitioned = true
            }
        }

        return buildString {
            append(cookieName)
            append("=; Max-Age=0; Expires=")
            append(EPOCH_HTTP_DATE)
            if (domain != null) {
                append("; Domain=").append(domain)
            }
            append("; Path=").append(path ?: fallbackPath)
            if (secure) append("; Secure")
            if (httpOnly) append("; HttpOnly")
            if (sameSite != null) {
                append("; SameSite=").append(sameSite)
            }
            if (partitioned) append("; Partitioned")
        }
    }

    private fun buildLegacyScopeDomains(url: HttpUrl): List<String?> {
        val domains = LinkedHashSet<String?>()
        domains.add(null) // host-only
        if (!isIpAddress(url.host)) {
            domains.add(url.host)
            val topPrivateDomain = url.topPrivateDomain()
            if (!topPrivateDomain.isNullOrBlank()) {
                var current = url.host
                while (current.contains('.') && !current.equals(topPrivateDomain, ignoreCase = true)) {
                    current = current.substringAfter('.')
                    domains.add(".$current")
                }
                if (url.host.equals(topPrivateDomain, ignoreCase = true)) {
                    domains.add(".$topPrivateDomain")
                }
            } else if (url.host.count { it == '.' } >= 2) {
                domains.add(".${url.host.substringAfter('.')}")
            }
        }
        return domains.toList()
    }

    private fun buildLegacyAuthExpirationHeader(
        cookieName: String,
        domain: String?,
        path: String,
        secure: Boolean,
    ): String = buildString {
        append(cookieName)
        append("=; Max-Age=0; Expires=")
        append(EPOCH_HTTP_DATE)
        if (domain != null) {
            append("; Domain=").append(domain)
        }
        append("; Path=").append(path)
        if (secure) {
            append("; Secure")
        }
    }

    private fun buildExpiredStoreCookie(cookie: Cookie): Cookie {
        val builder = Cookie.Builder()
            .name(cookie.name)
            .value("")
            .path(cookie.path)
            .expiresAt(0L)
        if (cookie.hostOnly) {
            builder.hostOnlyDomain(cookie.domain)
        } else {
            builder.domain(cookie.domain)
        }
        if (cookie.secure) builder.secure()
        if (cookie.httpOnly) builder.httpOnly()
        return builder.build()
    }

    private fun isPreservedCloudflareCookie(name: String): Boolean =
        name.equals(COOKIE_CF_CLEARANCE, ignoreCase = true) ||
            name.equals(COOKIE_CF_BM, ignoreCase = true)

    private fun Cookie.matchesHost(canonicalHost: String): Boolean {
        val cookieDomain = domain.lowercase(Locale.ROOT)
        if (cookieDomain == canonicalHost) return true
        if (hostOnly) return false
        return canonicalHost.endsWith(".$cookieDomain") && !isIpAddress(canonicalHost)
    }

    private fun isIpAddress(host: String): Boolean {
        if (host.contains(':')) return true
        val lastLabel = host.substringAfterLast('.')
        return lastLabel.isNotEmpty() && lastLabel.all(Char::isDigit)
    }

    private suspend fun readPersistentSnapshotFromStore(): PersistentSnapshot = PersistentSnapshot(
        accounts = credentialsStore.accounts.first(),
        activeUid = credentialsStore.activeUid.first(),
        manuallyLoggedOut = credentialsStore.manuallyLoggedOut.first(),
    )

    private suspend fun readForumServerOrNull(): String? {
        val raw = settingsRepository.settings.first().forumServer.trim()
        return raw.takeIf { runCatching { buildForumUrl(it, "") }.isSuccess }
    }

    private fun applyPersistentSnapshotLocked(snapshot: PersistentSnapshot) {
        persistentSnapshot = snapshot
        val activeUid = snapshot.activeAccount?.uid
        val overlay = runtimeOverlay
        if (activeUid == null) {
            runtimeOverlay = RuntimeOverlay.Normal
        } else if (overlay is RuntimeOverlay.Reauthenticating && overlay.uid != activeUid) {
            runtimeOverlay = RuntimeOverlay.Normal
        } else if (overlay is RuntimeOverlay.SessionExpired && overlay.uid != activeUid) {
            runtimeOverlay = RuntimeOverlay.Normal
        }
        recomputeStateFlowsLocked()
    }

    private fun recomputeStateFlowsLocked() {
        val accounts = persistentSnapshot.accounts
        val active = persistentSnapshot.activeAccount
        _savedAccounts.value = accounts
        _currentAccount.value = active
        _authState.value = if (active == null) {
            AuthState.SignedOut
        } else {
            when (val overlay = runtimeOverlay) {
                is RuntimeOverlay.Reauthenticating -> {
                    if (overlay.uid == active.uid) {
                        AuthState.Reauthenticating(active)
                    } else {
                        AuthState.SignedIn(active)
                    }
                }

                is RuntimeOverlay.SessionExpired -> {
                    if (overlay.uid == active.uid) {
                        AuthState.SessionExpired(active, overlay.reason)
                    } else {
                        AuthState.SignedIn(active)
                    }
                }

                RuntimeOverlay.Normal -> AuthState.SignedIn(active)
            }
        }
    }

    companion object {
        private const val LOGIN_PAGE_PATH = "logging.php?action=login"
        private const val LOGIN_SUBMIT_PATH = "logging.php?action=login&loginsubmit=yes&inajax=1"
        private const val INDEX_PATH = "index.php"
        private const val COOKIE_TIME_SECONDS = "2592000"
        private const val COOKIE_CF_CLEARANCE = "cf_clearance"
        private const val COOKIE_CF_BM = "__cf_bm"
        private const val EPOCH_HTTP_DATE = "Thu, 01 Jan 1970 00:00:00 GMT"
        private val LEGACY_AUTH_COOKIE_PATHS: List<String> = listOf("/", "/forum", "/forum/")
    }
}
