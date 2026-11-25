package net.jejer.hipda.data.message

import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthRequiredException
import net.jejer.hipda.data.auth.ForumHtmlResponse
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.ForumHttpException
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeAbandonedException
import net.jejer.hipda.data.network.access.ChallengeIncompleteException
import net.jejer.hipda.data.network.access.ChallengeRejectedException
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.message.SmsDetailPage
import net.jejer.hipda.parser.message.SmsListPage
import net.jejer.hipda.parser.message.SmsParser
import net.jejer.hipda.parser.message.SmsSendResult
import org.jsoup.Jsoup

@Singleton
class DefaultSmsRepository internal constructor(
    private val forumHttpClient: ForumHttpClient,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val notifyRepository: NotifyRepository,
    private val nowMs: () -> Long,
    private val scope: CoroutineScope,
) : SmsRepository {

    @Inject
    constructor(
        forumHttpClient: ForumHttpClient,
        settingsRepository: SettingsRepository,
        authRepository: AuthRepository,
        notifyRepository: NotifyRepository,
    ) : this(
        forumHttpClient = forumHttpClient,
        settingsRepository = settingsRepository,
        authRepository = authRepository,
        notifyRepository = notifyRepository,
        nowMs = System::currentTimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private val sendMutex = Mutex()
    private val cooldownLock = Any()

    @Volatile
    private var cooldownOwnerUid: String? = authRepository.currentAccount.value?.uid

    private val _cooldownUntilMs = MutableStateFlow(0L)
    override val cooldownUntilMs: StateFlow<Long> = _cooldownUntilMs.asStateFlow()

    init {
        scope.launch {
            authRepository.currentAccount
                .map { it?.uid }
                .distinctUntilChanged()
                .conflate()
                .collect {
                    syncCooldownAccount()
                }
        }
    }

    override fun getRemainingCooldownSeconds(): Int {
        syncCooldownAccount()
        return computeRemainingCooldownSeconds(
            nowMs = nowMs(),
            cooldownUntilMs = _cooldownUntilMs.value,
        )
    }

    override suspend fun getConversations(
        unreadOnly: Boolean,
        priority: AccessPriority,
    ): MessageResult<SmsListPage> {
        val accountUidAtStart = authRepository.currentAccount.value?.uid
        val path = if (unreadOnly) NEW_PM_PATH else PRIVATE_PM_PATH
        val response = try {
            forumHttpClient.getHtml(
                relativePath = path,
                priority = priority,
                requireAuth = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return mapExceptionToMessageResult(e)
        }

        val page = SmsParser.parseSmsList(response.html)
        if (!page.hasContainer && !page.meta.errorMessage.isNullOrBlank()) {
            return MessageResult.Error(page.meta.errorMessage!!)
        }

        if (shouldSyncForAccount(accountUidAtStart, page.meta.session.uid)) {
            val unreadItems = if (unreadOnly) page.items else page.items.filter { it.isNew }
            val unreadUids = unreadItems.mapNotNull { it.uid.trim().ifEmpty { null } }.toSet()
            notifyRepository.updateSmsUnreadState(unreadUids, unreadItems.size)
            notifyRepository.updateFromPageMeta(page.meta.promptCounts.copy(pm = 0))
        }
        return MessageResult.Success(page)
    }

    override suspend fun getConversationDetail(
        uid: String,
        priority: AccessPriority,
    ): MessageResult<SmsDetailPage> {
        val trimmedUid = uid.trim()
        if (trimmedUid.isEmpty()) {
            return MessageResult.Error("无效的用户编号")
        }

        val accountUidAtStart = authRepository.currentAccount.value?.uid
        val encodedUid = encodeQueryValue(trimmedUid)
        val avatarBaseUrl = deriveAvatarBaseUrl(settingsRepository.settings.first().forumServer)
        val response = try {
            forumHttpClient.getHtml(
                relativePath = "pm.php?daterange=5&uid=$encodedUid",
                priority = priority,
                requireAuth = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return mapExceptionToMessageResult(e)
        }

        val page = SmsParser.parseSmsDetail(response.html, avatarBaseUrl = avatarBaseUrl)
        if (!page.hasContainer && !page.meta.errorMessage.isNullOrBlank()) {
            return MessageResult.Error(page.meta.errorMessage!!)
        }

        if (shouldSyncForAccount(accountUidAtStart, page.meta.session.uid)) {
            notifyRepository.markSmsConversationRead(
                uid = trimmedUid,
                hadUnreadMessages = page.messages.any { it.isNew },
            )
            notifyRepository.updateFromPageMeta(page.meta.promptCounts.copy(pm = 0))
        }
        return MessageResult.Success(page)
    }

    override suspend fun sendSms(
        uid: String?,
        username: String?,
        message: String,
        priority: AccessPriority,
    ): SmsSendOutcome {
        val normalizedUid = uid?.trim()?.ifEmpty { null }
        val normalizedUsername = username?.trim()?.ifEmpty { null }
        if (message.isBlank()) {
            return SmsSendOutcome.Rejected("短消息内容不能为空")
        }
        if (normalizedUid == null && normalizedUsername == null) {
            return SmsSendOutcome.Rejected("收件人不能为空")
        }

        return sendMutex.withLock {
            val activeUidAtStart = syncCooldownAccount()

            val remainingSeconds = computeRemainingCooldownSeconds(
                nowMs = nowMs(),
                cooldownUntilMs = _cooldownUntilMs.value,
            )
            if (remainingSeconds > 0) {
                return@withLock SmsSendOutcome.CooldownActive(remainingSeconds)
            }

            val encodedUid = normalizedUid?.let(::encodeQueryValue).orEmpty()
            val prefetchResponse = try {
                forumHttpClient.getHtml(
                    relativePath = "pm.php?daterange=1&uid=$encodedUid",
                    priority = priority,
                    requireAuth = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock mapExceptionToSendOutcome(e)
            }

            val formhash = DiscuzCommonParser.parseFormhash(Jsoup.parse(prefetchResponse.html))
            if (formhash.isNullOrBlank()) {
                return@withLock SmsSendOutcome.FormhashMissing
            }

            val postPath = if (normalizedUid != null) {
                "pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1&uid=$encodedUid"
            } else {
                "pm.php?action=send&pmsubmit=yes&infloat=yes&inajax=1"
            }

            val fields = buildList {
                add("formhash" to formhash)
                add("lastdaterange" to nowMs().toString())
                add("handlekey" to HANDLE_KEY_PM_REPLY)
                add("message" to message)
                if (normalizedUid == null) {
                    add("msgto" to normalizedUsername!!)
                }
            }

            val postResponse = try {
                forumHttpClient.postGbkForm(
                    relativePath = postPath,
                    fields = fields,
                    priority = priority,
                    requireAuth = true,
                    isUnauthenticated = ::isSmsAjaxUnauthenticated,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock mapExceptionToSendOutcome(e)
            }

            when (val parsed = SmsParser.parseSmsSendResult(postResponse.html)) {
                SmsSendResult.Success -> {
                    val sentAt = nowMs()
                    val currentUid = authRepository.currentAccount.value?.uid
                    synchronized(cooldownLock) {
                        if (activeUidAtStart == null || currentUid == activeUidAtStart) {
                            cooldownOwnerUid = currentUid
                            _cooldownUntilMs.value = sentAt + SMS_COOLDOWN_MS
                        }
                    }
                    SmsSendOutcome.Sent
                }

                is SmsSendResult.Failed -> SmsSendOutcome.Rejected(parsed.message)
            }
        }
    }

    override suspend fun clearConversation(
        uid: String,
        priority: AccessPriority,
    ): SmsClearOutcome {
        val trimmedUid = uid.trim()
        if (trimmedUid.isEmpty()) {
            return SmsClearOutcome.Rejected("无效的用户编号")
        }

        val accountUidAtStart = authRepository.currentAccount.value?.uid
        val encodedUid = encodeQueryValue(trimmedUid)
        val response = try {
            forumHttpClient.getHtml(
                relativePath = "pm.php?action=del&uid=$encodedUid&filter=privatepm",
                priority = priority,
                requireAuth = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthRequiredException) {
            return SmsClearOutcome.AuthRequired(reason = e.reason)
        } catch (e: ChallengeIncompleteException) {
            return SmsClearOutcome.ChallengeRequired(cooldownUntilMs = e.cooldownUntilMs, rejected = false)
        } catch (e: ChallengeRejectedException) {
            return SmsClearOutcome.ChallengeRequired(cooldownUntilMs = e.cooldownUntilMs, rejected = true)
        } catch (e: ChallengeAbandonedException) {
            return SmsClearOutcome.Error(message = e.message ?: "安全验证已放弃", cause = e)
        } catch (e: ForumHttpException) {
            return SmsClearOutcome.Error(message = e.message ?: "清空对话失败", cause = e)
        } catch (e: IOException) {
            return SmsClearOutcome.Error(message = e.message ?: "网络请求失败", cause = e)
        }

        val errorMessage = response.meta.errorMessage
        if (!errorMessage.isNullOrBlank()) {
            return SmsClearOutcome.Rejected(errorMessage)
        }

        if (shouldSyncForAccount(accountUidAtStart, response.meta.session.uid)) {
            notifyRepository.markSmsConversationRead(uid = trimmedUid, hadUnreadMessages = false)
        }
        return SmsClearOutcome.Cleared
    }

    private fun shouldSyncForAccount(accountUidAtStart: String?, responseSessionUid: String?): Boolean {
        val currentUid = authRepository.currentAccount.value?.uid
        if (accountUidAtStart != null) {
            return currentUid == accountUidAtStart
        }
        return currentUid == null || responseSessionUid == null || responseSessionUid == currentUid
    }

    /**
     * Reads [AuthRepository.currentAccount] inside [cooldownLock] so a lagging collector frame
     * from cold start (`null`) can never wipe an active cooldown after the account has hydrated.
     */
    private fun syncCooldownAccount(): String? = synchronized(cooldownLock) {
        val currentUid = authRepository.currentAccount.value?.uid
        val previousUid = cooldownOwnerUid
        if (previousUid == null) {
            cooldownOwnerUid = currentUid
        } else if (previousUid != currentUid) {
            cooldownOwnerUid = currentUid
            _cooldownUntilMs.value = 0L
        }
        currentUid
    }

    private fun mapExceptionToSendOutcome(e: Exception): SmsSendOutcome = when (e) {
        is AuthRequiredException -> SmsSendOutcome.AuthRequired(reason = e.reason)
        is ChallengeIncompleteException -> SmsSendOutcome.ChallengeRequired(
            cooldownUntilMs = e.cooldownUntilMs,
            rejected = false,
        )

        is ChallengeRejectedException -> SmsSendOutcome.ChallengeRequired(
            cooldownUntilMs = e.cooldownUntilMs,
            rejected = true,
        )

        is ChallengeAbandonedException -> SmsSendOutcome.Error(
            message = e.message ?: "安全验证已放弃",
            cause = e,
        )

        is ForumHttpException -> SmsSendOutcome.Error(
            message = e.message ?: "短消息发送失败",
            cause = e,
        )

        is IOException -> SmsSendOutcome.Error(
            message = e.message ?: "网络请求失败",
            cause = e,
        )

        else -> SmsSendOutcome.Error(
            message = e.message ?: "短消息发送失败",
            cause = e,
        )
    }

    private fun encodeQueryValue(raw: String): String = URLEncoder.encode(raw, "UTF-8")

    companion object {
        const val SMS_COOLDOWN_MS: Long = 15_000L
        private const val PRIVATE_PM_PATH = "pm.php?filter=privatepm"
        private const val NEW_PM_PATH = "pm.php?filter=newpm"
        private const val HANDLE_KEY_PM_REPLY = "pmreply"

        private val UNAUTH_AJAX_MARKERS: List<String> = listOf(
            "您还未登录",
            "您尚未登录",
            "尚未登录",
            "请先登录",
            "您需要先登录",
            "无权进行当前操作",
        )

        internal fun computeRemainingCooldownSeconds(nowMs: Long, cooldownUntilMs: Long): Int {
            val remainingMs = cooldownUntilMs - nowMs
            if (remainingMs <= 0L) return 0
            return ((remainingMs + 999L) / 1_000L).toInt()
        }

        /**
         * Custom `isUnauthenticated` predicate for `pm.php?action=send...&inajax=1` XML responses.
         *
         * Returns `false` immediately for [SmsSendResult.Success] so user message bodies echoed
         * inside `div.summary` can never falsely trigger a duplicate send; returns `true` only on
         * explicit Discuz! unauthenticated XML errors or unexpected logged-out HTML fallbacks.
         */
        internal fun isSmsAjaxUnauthenticated(response: ForumHtmlResponse): Boolean {
            val parsed = SmsParser.parseSmsSendResult(response.html)
            if (parsed is SmsSendResult.Success) {
                return false
            }
            val trimmed = response.html.trimStart()
            if (!trimmed.startsWith("<?xml", ignoreCase = true) &&
                trimmed.contains("id=\"umenu\"") &&
                !response.meta.session.isLoggedIn
            ) {
                return true
            }
            val errorText = (parsed as SmsSendResult.Failed).message
            return UNAUTH_AJAX_MARKERS.any { marker -> errorText.contains(marker) }
        }
    }
}
