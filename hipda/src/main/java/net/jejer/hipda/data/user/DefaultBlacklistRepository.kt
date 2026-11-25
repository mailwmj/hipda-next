package net.jejer.hipda.data.user

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.ForumHtmlResponse
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.user.BlacklistParseResult
import net.jejer.hipda.parser.user.UserParser
import org.jsoup.Jsoup

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultBlacklistRepository internal constructor(
    private val forumHttpClient: ForumHttpClient,
    private val authRepository: AuthRepository,
    private val blacklistStore: BlacklistStore,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) : BlacklistRepository {

    @Inject
    constructor(
        forumHttpClient: ForumHttpClient,
        authRepository: AuthRepository,
        blacklistStore: BlacklistStore,
    ) : this(
        forumHttpClient = forumHttpClient,
        authRepository = authRepository,
        blacklistStore = blacklistStore,
        clock = System::currentTimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private val operationMutex = Mutex()
    private val stateLock = Any()
    private var mutationVersion: Long = 0L

    private val _activeSnapshot = MutableStateFlow(BlacklistSnapshot())
    private val _blockedUsernames = MutableStateFlow<Set<String>>(emptySet())
    private val _lastSyncedAtMs = MutableStateFlow<Long?>(null)

    override val blockedUsernames: StateFlow<Set<String>> = _blockedUsernames.asStateFlow()
    override val lastSyncedAtMs: StateFlow<Long?> = _lastSyncedAtMs.asStateFlow()

    init {
        scope.launch {
            authRepository.currentAccount
                .map { it?.uid?.trim()?.takeIf(String::isNotEmpty) }
                .distinctUntilChanged()
                .collectLatest { uid ->
                    if (uid == null) {
                        synchronized(stateLock) {
                            mutationVersion++
                            publishSnapshotLocked(BlacklistSnapshot())
                        }
                    } else {
                        synchronized(stateLock) {
                            if (_activeSnapshot.value.ownerUid != uid) {
                                mutationVersion++
                                publishSnapshotLocked(BlacklistSnapshot(ownerUid = uid))
                            }
                        }
                        blacklistStore.observe(uid)
                            .conflate()
                            .collect {
                                val versionBeforeRead = synchronized(stateLock) { mutationVersion }
                                val latest = blacklistStore.read(uid)
                                synchronized(stateLock) {
                                    if (mutationVersion == versionBeforeRead && currentActiveUid() == uid) {
                                        publishSnapshotLocked(latest)
                                    }
                                }
                            }
                    }
                }
        }
    }

    override fun isBlocked(username: String): Boolean {
        val normalized = normalizeBlacklistUsername(username) ?: return false
        val activeUid = currentActiveUid() ?: return false
        val snapshot = _activeSnapshot.value
        if (snapshot.ownerUid != activeUid) return false
        return normalized in snapshot.usernames
    }

    override suspend fun syncBlacklist(priority: AccessPriority): BlacklistSyncResult =
        operationMutex.withLock {
            val accountUidBefore = currentActiveUid()
            val response = try {
                forumHttpClient.getHtml(
                    relativePath = VIEW_BLACKLIST_PATH,
                    priority = priority,
                    requireAuth = true,
                )
            } catch (e: IOException) {
                return@withLock mapUserIoException(e)
            }

            val targetUid = when (val ownership = verifySessionOwnership(accountUidBefore, response)) {
                is SessionOwnership.Valid -> ownership.uid
                is SessionOwnership.Invalid -> return@withLock ownership.error
            }

            val siteError = response.meta.errorMessage
            if (siteError != null) {
                return@withLock UserError.SiteError(siteError.trim().ifEmpty { "黑名单同步失败" })
            }

            when (val parsed = UserParser.parseBlacklist(response.html)) {
                is BlacklistParseResult.InvalidPage -> UserError.InvalidPage(parsed.reason)

                is BlacklistParseResult.Success -> {
                    val updated = blacklistStore.replace(
                        ownerUid = targetUid,
                        usernames = parsed.usernames,
                        syncedAtMs = clock(),
                    )
                    synchronized(stateLock) {
                        mutationVersion++
                        if (currentActiveUid() == targetUid) {
                            publishSnapshotLocked(updated)
                        }
                    }
                    BlacklistSyncResult.Success(
                        snapshot = updated,
                        isEmptyConfirmed = parsed.isEmptyConfirmed,
                    )
                }
            }
        }

    override suspend fun addToBlacklist(
        username: String,
        formhash: String?,
        priority: AccessPriority,
    ): BlacklistMutationResult = mutateBlacklist(
        rawUsername = username,
        providedFormhash = formhash,
        actionPath = ADD_BLACKLIST_PATH,
        isAdd = true,
        priority = priority,
    )

    override suspend fun removeFromBlacklist(
        username: String,
        formhash: String?,
        priority: AccessPriority,
    ): BlacklistMutationResult = mutateBlacklist(
        rawUsername = username,
        providedFormhash = formhash,
        actionPath = REMOVE_BLACKLIST_PATH,
        isAdd = false,
        priority = priority,
    )

    private suspend fun mutateBlacklist(
        rawUsername: String,
        providedFormhash: String?,
        actionPath: String,
        isAdd: Boolean,
        priority: AccessPriority,
    ): BlacklistMutationResult {
        val normalizedUsername = normalizeBlacklistUsername(rawUsername)
            ?: return UserError.InvalidInput("用户名不能为空")

        return operationMutex.withLock {
            val accountUidBefore = currentActiveUid()
            var expectedOwnerUid = accountUidBefore
            val resolvedFormhash = providedFormhash?.trim()?.takeIf { it.isNotEmpty() }
                ?: when (val fetched = fetchFormhash(accountUidBefore, priority)) {
                    is FormhashFetchResult.Found -> {
                        expectedOwnerUid = expectedOwnerUid ?: fetched.ownerUid
                        fetched.formhash
                    }
                    is FormhashFetchResult.Failed -> return@withLock fetched.error
                }

            val response = try {
                forumHttpClient.postGbkForm(
                    relativePath = actionPath,
                    fields = listOf(
                        FIELD_FORMHASH to resolvedFormhash,
                        FIELD_USER to normalizedUsername,
                    ),
                    priority = priority,
                    requireAuth = true,
                )
            } catch (e: IOException) {
                return@withLock mapUserIoException(e)
            }

            val targetUid = when (val ownership = verifySessionOwnership(expectedOwnerUid, response)) {
                is SessionOwnership.Valid -> ownership.uid
                is SessionOwnership.Invalid -> return@withLock ownership.error
            }

            val siteError = response.meta.errorMessage
            if (siteError != null) {
                return@withLock UserError.SiteError(siteError.trim().ifEmpty { "黑名单操作失败" })
            }

            val updated = if (isAdd) {
                blacklistStore.add(ownerUid = targetUid, username = normalizedUsername)
            } else {
                blacklistStore.remove(ownerUid = targetUid, username = normalizedUsername)
            }
            synchronized(stateLock) {
                mutationVersion++
                if (currentActiveUid() == targetUid) {
                    publishSnapshotLocked(updated)
                }
            }

            BlacklistMutationResult.Success(
                username = normalizedUsername,
                blockedUsernames = updated.usernames,
            )
        }
    }

    private suspend fun fetchFormhash(
        accountUidBefore: String?,
        priority: AccessPriority,
    ): FormhashFetchResult {
        val response = try {
            forumHttpClient.getHtml(
                relativePath = VIEW_BLACKLIST_PATH,
                priority = priority,
                requireAuth = true,
            )
        } catch (e: IOException) {
            return FormhashFetchResult.Failed(mapUserIoException(e))
        }

        val ownerUid = when (val ownership = verifySessionOwnership(accountUidBefore, response)) {
            is SessionOwnership.Valid -> ownership.uid
            is SessionOwnership.Invalid -> return FormhashFetchResult.Failed(ownership.error)
        }

        val siteError = response.meta.errorMessage
        if (siteError != null) {
            return FormhashFetchResult.Failed(
                UserError.SiteError(siteError.trim().ifEmpty { "获取黑名单页面失败" }),
            )
        }

        val doc = Jsoup.parse(response.html)
        val formhash = DiscuzCommonParser.parseFormhash(doc)
            ?: DiscuzCommonParser.parseLogoutFormhash(doc)
        return if (formhash.isNullOrBlank()) {
            FormhashFetchResult.Failed(UserError.InvalidPage("无法获取黑名单操作令牌 formhash"))
        } else {
            FormhashFetchResult.Found(formhash = formhash, ownerUid = ownerUid)
        }
    }

    private fun verifySessionOwnership(
        accountUidBefore: String?,
        response: ForumHtmlResponse,
    ): SessionOwnership {
        val activeUidAfter = currentActiveUid()
            ?: return SessionOwnership.Invalid(
                UserError.AuthRequired(
                    reason = AuthRequiredReason.NOT_LOGGED_IN,
                    message = "当前未登录",
                ),
            )
        if (accountUidBefore != null && activeUidAfter != accountUidBefore) {
            return SessionOwnership.Invalid(
                UserError.AuthRequired(
                    reason = AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED,
                    message = "账号已切换，请重试",
                ),
            )
        }
        val responseUid = response.meta.session.uid?.trim()?.takeIf { it.isNotEmpty() }
        if (responseUid != null && responseUid != activeUidAfter) {
            return SessionOwnership.Invalid(
                UserError.AuthRequired(
                    reason = AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED,
                    message = "响应会话与当前账号不一致",
                ),
            )
        }
        return SessionOwnership.Valid(activeUidAfter)
    }

    private fun currentActiveUid(): String? =
        authRepository.currentAccount.value?.uid?.trim()?.takeIf { it.isNotEmpty() }

    private fun publishSnapshotLocked(snapshot: BlacklistSnapshot) {
        _activeSnapshot.value = snapshot
        _blockedUsernames.value = snapshot.usernames
        _lastSyncedAtMs.value = snapshot.lastSyncedAtMs
    }

    private sealed interface SessionOwnership {
        data class Valid(val uid: String) : SessionOwnership
        data class Invalid(val error: UserError) : SessionOwnership
    }

    private sealed interface FormhashFetchResult {
        data class Found(val formhash: String, val ownerUid: String) : FormhashFetchResult
        data class Failed(val error: UserError) : FormhashFetchResult
    }

    private companion object {
        private const val VIEW_BLACKLIST_PATH = "pm.php?action=viewblack"
        private const val ADD_BLACKLIST_PATH = "pm.php?action=addblack"
        private const val REMOVE_BLACKLIST_PATH = "pm.php?action=delblack"
        private const val FIELD_FORMHASH = "formhash"
        private const val FIELD_USER = "user"
    }
}
