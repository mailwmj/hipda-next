package net.jejer.hipda.data.post

import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthRequiredException
import net.jejer.hipda.data.auth.ForumHtmlResponse
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.auth.ForumHttpException
import net.jejer.hipda.data.local.DraftDao
import net.jejer.hipda.data.local.DraftEntity
import net.jejer.hipda.data.local.DraftKey
import net.jejer.hipda.data.local.delete
import net.jejer.hipda.data.local.observe
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.network.access.ChallengeAbandonedException
import net.jejer.hipda.data.network.access.ChallengeIncompleteException
import net.jejer.hipda.data.network.access.ChallengeRejectedException
import net.jejer.hipda.data.settings.AppSettings
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.common.DiscuzUrlHelper
import net.jejer.hipda.parser.common.firstMatch
import net.jejer.hipda.parser.post.PrePostInfo
import net.jejer.hipda.parser.post.PrePostParseResult
import net.jejer.hipda.parser.post.PrePostParser
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.parser.threaddetail.ThreadDetailParser
import org.jsoup.Jsoup

@Singleton
class DefaultPostRepository internal constructor(
    private val forumHttpClient: ForumHttpClient,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val draftDao: DraftDao,
    private val nowMs: () -> Long,
    private val scope: CoroutineScope,
) : PostRepository {

    @Inject
    constructor(
        forumHttpClient: ForumHttpClient,
        authRepository: AuthRepository,
        settingsRepository: SettingsRepository,
        draftDao: DraftDao,
    ) : this(
        forumHttpClient = forumHttpClient,
        authRepository = authRepository,
        settingsRepository = settingsRepository,
        draftDao = draftDao,
        nowMs = System::currentTimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private val submitMutex = Mutex()
    private val cooldownLock = Any()

    private var cooldownOwnerUid: String? = authRepository.currentAccount.value?.uid

    private val _cooldownUntilMs = MutableStateFlow(0L)
    override val cooldownUntilMs: StateFlow<Long> = _cooldownUntilMs.asStateFlow()

    init {
        scope.launch {
            authRepository.currentAccount
                .map { it?.uid }
                .distinctUntilChanged()
                .collect {
                    resetCooldownIfAccountChanged(authRepository.currentAccount.value?.uid)
                }
        }
    }

    override fun getRemainingCooldownSeconds(): Int {
        resetCooldownIfAccountChanged(authRepository.currentAccount.value?.uid)
        return computeRemainingCooldownSeconds(
            nowMs = nowMs(),
            cooldownUntilMs = _cooldownUntilMs.value,
        )
    }

    override suspend fun fetchPrePost(
        request: PrePostRequest,
        priority: AccessPriority,
    ): PrePostResult {
        val path = buildPrePostPath(request)
            ?: return PrePostResult.InvalidInput("无效的发帖表单参数")

        val response = try {
            forumHttpClient.getHtml(
                relativePath = path,
                priority = priority,
                requireAuth = true,
                isUnauthenticated = ::isPostResponseUnauthenticated,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return mapExceptionToPrePostResult(e)
        }

        if (isRealNameBindUrl(response.url)) {
            return PrePostResult.RealNameBindRequired
        }

        return when (val parsed = PrePostParser.parse(response.html, request.mode)) {
            is PrePostParseResult.Success -> {
                PrePostResult.Success(decodePrePostInfoForEditor(parsed.info))
            }

            is PrePostParseResult.Error -> {
                PrePostResult.SiteError(decodeNumericEntitiesForEditor(parsed.message))
            }
        }
    }

    override suspend fun submitPost(
        request: PostSubmitRequest,
        priority: AccessPriority,
    ): PostSubmitResult {
        val cleanedNewAttachments = sanitizeIds(request.newAttachments)
        val cleanedDeletedAttachments = sanitizeIds(request.deletedAttachments)

        validateSubmitRequest(request, cleanedNewAttachments)?.let { errorMessage ->
            return PostSubmitResult.InvalidInput(errorMessage)
        }

        return submitMutex.withLock {
            val activeUidAtStart = authRepository.currentAccount.value?.uid
            resetCooldownIfAccountChanged(activeUidAtStart)

            val enforcesCooldown = request !is PostSubmitRequest.EditPost
            if (enforcesCooldown) {
                val remaining = computeRemainingCooldownSeconds(
                    nowMs = nowMs(),
                    cooldownUntilMs = _cooldownUntilMs.value,
                )
                if (remaining > 0) {
                    return@withLock PostSubmitResult.CooldownActive(remaining)
                }
            }

            val prePostInfo = resolvePrePostInfo(request, priority)
                .let { resolved ->
                    when (resolved) {
                        is PrePostResolution.Ready -> resolved.info
                        is PrePostResolution.Failed -> return@withLock resolved.result
                    }
                }

            val settings = settingsRepository.settings.first()
            val message = buildFinalMessage(
                request = request,
                prePostInfo = prePostInfo,
                newAttachments = cleanedNewAttachments,
                settings = settings,
            )

            val postPath = buildSubmitPath(request, prePostInfo)
            val fields = buildSubmitFields(
                request = request,
                prePostInfo = prePostInfo,
                finalMessage = message,
                newAttachments = cleanedNewAttachments,
                deletedAttachments = cleanedDeletedAttachments,
                postTimeMs = nowMs(),
            )

            val response = try {
                forumHttpClient.postGbkForm(
                    relativePath = postPath,
                    fields = fields,
                    priority = priority,
                    requireAuth = true,
                    isUnauthenticated = ::isPostResponseUnauthenticated,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock mapExceptionToSubmitResult(e)
            }

            val submitResult = evaluateSubmitResponse(
                request = request,
                response = response,
                forumServer = settings.forumServer,
            )

            if (submitResult is PostSubmitResult.Success) {
                if (enforcesCooldown) {
                    val completedAt = nowMs()
                    val currentUid = authRepository.currentAccount.value?.uid
                    synchronized(cooldownLock) {
                        if (activeUidAtStart == null || currentUid == activeUidAtStart) {
                            cooldownOwnerUid = currentUid
                            _cooldownUntilMs.value = completedAt + POST_COOLDOWN_MS
                        }
                    }
                }
                val draftKey = request.draftKey
                if (draftKey != null) {
                    withContext(NonCancellable) {
                        draftDao.delete(draftKey)
                    }
                }
            }

            submitResult
        }
    }

    override fun observeDraft(key: DraftKey): Flow<DraftEntity?> = draftDao.observe(key)

    override suspend fun saveDraft(
        key: DraftKey,
        subject: String,
        typeId: String,
        content: String,
        quotePid: String?,
        attachments: List<String>,
    ) {
        val cleanedAttachments = attachments.map { it.trim() }.filter { it.isNotEmpty() }
        if (subject.isBlank() && content.isBlank() && cleanedAttachments.isEmpty()) {
            return
        }
        draftDao.upsert(
            DraftEntity(
                key = key.value,
                subject = subject,
                typeId = typeId.trim(),
                content = content,
                quotePid = quotePid?.trim()?.ifEmpty { null },
                attachments = cleanedAttachments,
                updatedAt = nowMs(),
            ),
        )
    }

    override suspend fun deleteDraft(key: DraftKey) {
        draftDao.delete(key)
    }

    private sealed interface PrePostResolution {
        data class Ready(val info: PrePostInfo) : PrePostResolution
        data class Failed(val result: PostSubmitResult) : PrePostResolution
    }

    private suspend fun resolvePrePostInfo(
        request: PostSubmitRequest,
        priority: AccessPriority,
    ): PrePostResolution {
        val provided = request.prePostInfo
        if (provided != null && provided.formhash.isNotBlank()) {
            return PrePostResolution.Ready(provided)
        }
        return when (val fetched = fetchPrePost(toPrePostRequest(request), priority)) {
            is PrePostResult.Success -> {
                if (fetched.info.formhash.isBlank()) {
                    PrePostResolution.Failed(PostSubmitResult.SiteError("发表失败，无法获取必要信息"))
                } else {
                    PrePostResolution.Ready(fetched.info)
                }
            }

            PrePostResult.RealNameBindRequired -> PrePostResolution.Failed(
                PostSubmitResult.RealNameBindRequired,
            )

            is PrePostResult.AuthRequired -> PrePostResolution.Failed(
                PostSubmitResult.AuthRequired(fetched.reason),
            )

            is PrePostResult.ChallengeRequired -> PrePostResolution.Failed(
                PostSubmitResult.ChallengeRequired(
                    cooldownUntilMs = fetched.cooldownUntilMs,
                    rejected = fetched.rejected,
                ),
            )

            is PrePostResult.SiteError -> PrePostResolution.Failed(
                PostSubmitResult.SiteError(fetched.message),
            )

            is PrePostResult.NetworkError -> PrePostResolution.Failed(
                PostSubmitResult.NetworkError(fetched.message, fetched.cause),
            )

            is PrePostResult.InvalidInput -> PrePostResolution.Failed(
                PostSubmitResult.InvalidInput(fetched.message),
            )
        }
    }

    private fun evaluateSubmitResponse(
        request: PostSubmitRequest,
        response: ForumHtmlResponse,
        forumServer: String,
    ): PostSubmitResult {
        if (isRealNameBindUrl(response.url)) {
            return PostSubmitResult.RealNameBindRequired
        }

        if (request is PostSubmitRequest.DeletePost &&
            response.url.contains("forumdisplay.php", ignoreCase = true)
        ) {
            return PostSubmitResult.Success(
                tid = request.tid.trim(),
                threadDeleted = true,
                redirectedPage = null,
            )
        }

        val isThreadRedirect = response.url.contains("viewthread.php", ignoreCase = true) ||
            response.url.contains("redirect.php", ignoreCase = true)
        val resolvedTid = DiscuzUrlHelper.extractQueryParam(response.url, "tid")
            ?: DiscuzUrlHelper.extractQueryParam(response.url, "ptid")

        if (isThreadRedirect && DiscuzUrlHelper.isValidId(resolvedTid)) {
            val tid = resolvedTid!!.trim()
            val parsedPage = parseRedirectedThreadPage(
                html = response.html,
                tid = tid,
                forumServer = forumServer,
            )
            return PostSubmitResult.Success(
                tid = tid,
                threadDeleted = false,
                redirectedPage = parsedPage,
            )
        }

        val siteMessage = extractSiteAlertMessage(
            html = response.html,
            isDelete = request is PostSubmitRequest.DeletePost,
        )
        return PostSubmitResult.SiteError(siteMessage)
    }

    private fun parseRedirectedThreadPage(
        html: String,
        tid: String,
        forumServer: String,
    ): ThreadDetailPage? = runCatching {
        val forumBaseUrl = deriveForumBaseUrl(forumServer)
        val avatarBaseUrl = "${forumBaseUrl}uc_server/data/avatar/"
        val page = ThreadDetailParser.parse(
            html = html,
            tid = tid,
            avatarBaseUrl = avatarBaseUrl,
            forumBaseUrl = forumBaseUrl,
        )
        page.takeIf { it.hasPostList && it.floors.isNotEmpty() }
    }.getOrNull()

    private fun resetCooldownIfAccountChanged(currentUid: String?) {
        synchronized(cooldownLock) {
            val previousUid = cooldownOwnerUid
            if (previousUid == null) {
                cooldownOwnerUid = currentUid
            } else if (previousUid != currentUid) {
                cooldownOwnerUid = currentUid
                _cooldownUntilMs.value = 0L
            }
        }
    }

    private fun mapExceptionToPrePostResult(e: Exception): PrePostResult = when (e) {
        is AuthRequiredException -> PrePostResult.AuthRequired(e.reason)
        is ChallengeIncompleteException -> PrePostResult.ChallengeRequired(
            cooldownUntilMs = e.cooldownUntilMs,
            rejected = false,
        )

        is ChallengeRejectedException -> PrePostResult.ChallengeRequired(
            cooldownUntilMs = e.cooldownUntilMs,
            rejected = true,
        )

        is ChallengeAbandonedException -> PrePostResult.NetworkError(
            message = e.message ?: "安全验证已放弃",
            cause = e,
        )

        is ForumHttpException -> PrePostResult.NetworkError(
            message = "获取发帖信息失败",
            cause = e,
        )

        is IOException -> PrePostResult.NetworkError(
            message = "网络连接失败",
            cause = e,
        )

        else -> PrePostResult.NetworkError(
            message = "获取发帖信息失败",
            cause = e,
        )
    }

    private fun mapExceptionToSubmitResult(e: Exception): PostSubmitResult = when (e) {
        is AuthRequiredException -> PostSubmitResult.AuthRequired(e.reason)
        is ChallengeIncompleteException -> PostSubmitResult.ChallengeRequired(
            cooldownUntilMs = e.cooldownUntilMs,
            rejected = false,
        )

        is ChallengeRejectedException -> PostSubmitResult.ChallengeRequired(
            cooldownUntilMs = e.cooldownUntilMs,
            rejected = true,
        )

        is ChallengeAbandonedException -> PostSubmitResult.NetworkError(
            message = e.message ?: "安全验证已放弃",
            cause = e,
        )

        is ForumHttpException -> PostSubmitResult.NetworkError(
            message = "提交失败，服务器返回异常",
            cause = e,
        )

        is IOException -> PostSubmitResult.NetworkError(
            message = "网络连接失败",
            cause = e,
        )

        else -> PostSubmitResult.NetworkError(
            message = "提交失败",
            cause = e,
        )
    }

    companion object {
        const val POST_COOLDOWN_MS: Long = 30_000L
        private const val REAL_NAME_BIND_MARKER = "memcp.php?action=bind"

        private val EDITOR_NUMERIC_ENTITY_REGEX =
            Regex("""(?:(?i:&amp;)|&)#(?:([0-9]+)|[xX]([0-9a-fA-F]+));""")

        private val BBCODE_TAG_NAME_REGEX = Regex("""^[A-Za-z][A-Za-z0-9]*$""")

        private val BARE_URL_REGEX = Regex(
            """(?i)(?:https?://[^\s<>"'\[\]\u0080-\uFFFF]+|\bwww\.[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+(?:/[^\s<>"'\[\]\u0080-\uFFFF]*)?)""",
        )

        internal fun computeRemainingCooldownSeconds(nowMs: Long, cooldownUntilMs: Long): Int {
            val remainingMs = cooldownUntilMs - nowMs
            if (remainingMs <= 0L) return 0
            return ((remainingMs + 999L) / 1_000L).toInt()
        }

        /**
         * R-B9b S3: Decodes numeric HTML character references (both double-escaped `&amp;#128512;` /
         * `&amp;#x1F600;` and single-escaped `&#128512;` / `&#x1F600;`) to real Unicode characters
         * for editor display without disturbing named entities (`&amp;`, `&lt;`, `&gt;`), BBCode,
         * or out-of-range/surrogate code points.
         */
        internal fun decodeNumericEntitiesForEditor(text: String): String {
            if (!text.contains('#')) return text
            return EDITOR_NUMERIC_ENTITY_REGEX.replace(text) { match ->
                val decimalPart = match.groupValues[1]
                val hexPart = match.groupValues[2]
                val codePoint = if (decimalPart.isNotEmpty()) {
                    decimalPart.toIntOrNull(10)
                } else {
                    hexPart.toIntOrNull(16)
                }
                if (codePoint != null && isValidDecodedCodePoint(codePoint)) {
                    String(Character.toChars(codePoint))
                } else {
                    match.value
                }
            }
        }

        private fun isValidDecodedCodePoint(codePoint: Int): Boolean =
            codePoint in 1..Character.MAX_CODE_POINT &&
                codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code

        internal fun decodePrePostInfoForEditor(info: PrePostInfo): PrePostInfo = info.copy(
            subject = info.subject?.let(::decodeNumericEntitiesForEditor),
            text = decodeNumericEntitiesForEditor(info.text),
            quoteText = decodeNumericEntitiesForEditor(info.quoteText),
        )

        internal fun isRealNameBindUrl(url: String): Boolean =
            url.contains(REAL_NAME_BIND_MARKER, ignoreCase = true)

        internal fun isPostResponseUnauthenticated(response: ForumHtmlResponse): Boolean {
            if (isRealNameBindUrl(response.url)) return false
            return ForumHttpClient.DEFAULT_UNAUTHENTICATED_PREDICATE(response)
        }

        internal fun buildTailBbcode(settings: AppSettings): String {
            if (!settings.tailEnabled) return ""
            val tailText = settings.tailText.trim()
            if (tailText.isEmpty()) return ""
            val rawUrl = settings.tailUrl.trim()
            return if (rawUrl.isNotEmpty()) {
                val normalizedUrl = if (
                    rawUrl.startsWith("http://", ignoreCase = true) ||
                    rawUrl.startsWith("https://", ignoreCase = true)
                ) {
                    rawUrl
                } else {
                    "https://$rawUrl"
                }
                "[url=$normalizedUrl][size=1]$tailText[/size][/url]"
            } else {
                "[size=1]$tailText[/size]"
            }
        }

        internal fun appendUninsertedImages(content: String, newAttachments: List<String>): String {
            if (newAttachments.isEmpty()) return content
            val uninserted = newAttachments.filter { imgId ->
                !content.contains("[attachimg]$imgId[/attachimg]")
            }
            if (uninserted.isEmpty()) return content

            val tags = uninserted.joinToString(separator = "\n") { "[attachimg]$it[/attachimg]" }
            return when {
                content.isEmpty() -> tags
                content.endsWith("\n") -> content + tags
                else -> "$content\n$tags"
            }
        }

        /**
         * Wraps bare URLs outside matched `[tag]...[/tag]` BBCode blocks with `[url]...[/url]`,
         * avoiding double-wrapping URLs already inside `[url]`, `[img]`, `[code]`, or `[attachimg]`.
         */
        internal fun wrapBareUrlsWithBbcode(content: String): String {
            if (content.isEmpty()) return content
            val sb = StringBuilder(content.length)
            var cursor = 0
            while (cursor < content.length) {
                val tagStart = content.indexOf('[', cursor)
                if (tagStart == -1) {
                    sb.append(replaceBareUrlsInPlainSegment(content.substring(cursor)))
                    break
                }
                val tagEnd = content.indexOf(']', tagStart)
                if (tagEnd == -1) {
                    sb.append(replaceBareUrlsInPlainSegment(content.substring(cursor)))
                    break
                }

                val rawTag = content.substring(tagStart + 1, tagEnd)
                val tagName = rawTag.substringBefore('=').trim()
                val closeTag = if (BBCODE_TAG_NAME_REGEX.matches(tagName)) "[/$tagName]" else null
                val closeIndex = if (closeTag != null) {
                    content.indexOf(closeTag, startIndex = tagEnd + 1, ignoreCase = true)
                } else {
                    -1
                }

                if (closeIndex != -1 && closeTag != null) {
                    val blockEnd = closeIndex + closeTag.length
                    sb.append(replaceBareUrlsInPlainSegment(content.substring(cursor, tagStart)))
                    sb.append(content, tagStart, blockEnd)
                    cursor = blockEnd
                } else {
                    sb.append(replaceBareUrlsInPlainSegment(content.substring(cursor, tagStart)))
                    sb.append(content, tagStart, tagEnd + 1)
                    cursor = tagEnd + 1
                }
            }
            return sb.toString()
        }

        private fun replaceBareUrlsInPlainSegment(segment: String): String {
            if (segment.isEmpty() || segment.contains("[/")) return segment
            return BARE_URL_REGEX.replace(segment) { match ->
                val raw = match.value
                val trimmed = trimTrailingUrlPunctuation(raw)
                val suffix = raw.substring(trimmed.length)
                "[url]$trimmed[/url]$suffix"
            }
        }

        private fun trimTrailingUrlPunctuation(url: String): String {
            var end = url.length
            while (end > 0) {
                val ch = url[end - 1]
                val shouldTrim = when (ch) {
                    '.', ',', ';', '!', '?' -> true
                    ')' -> {
                        val sub = url.substring(0, end)
                        sub.count { it == ')' } > sub.count { it == '(' }
                    }

                    else -> false
                }
                if (shouldTrim) {
                    end--
                } else {
                    break
                }
            }
            return url.substring(0, end)
        }

        private fun buildPrePostPath(request: PrePostRequest): String? = when (request) {
            is PrePostRequest.NewThread -> {
                if (request.fid <= 0) null else "post.php?action=newthread&fid=${request.fid}"
            }

            is PrePostRequest.ReplyThread -> {
                val tid = request.tid.trim()
                if (!DiscuzUrlHelper.isValidId(tid)) null else "post.php?action=reply&tid=$tid"
            }

            is PrePostRequest.ReplyPost -> {
                val tid = request.tid.trim()
                val pid = request.pid.trim()
                if (!DiscuzUrlHelper.isValidId(tid) || !DiscuzUrlHelper.isValidId(pid)) {
                    null
                } else {
                    "post.php?action=reply&tid=$tid&reppost=$pid"
                }
            }

            is PrePostRequest.QuotePost -> {
                val tid = request.tid.trim()
                val pid = request.pid.trim()
                if (!DiscuzUrlHelper.isValidId(tid) || !DiscuzUrlHelper.isValidId(pid)) {
                    null
                } else {
                    "post.php?action=reply&tid=$tid&repquote=$pid"
                }
            }

            is PrePostRequest.EditPost -> {
                val tid = request.tid.trim()
                val pid = request.pid.trim()
                if (request.fid < 0 || !DiscuzUrlHelper.isValidId(tid) || !DiscuzUrlHelper.isValidId(pid)) {
                    null
                } else {
                    val safePage = request.page.coerceAtLeast(1)
                    "post.php?action=edit&fid=${request.fid}&tid=$tid&pid=$pid&page=$safePage"
                }
            }
        }

        private fun toPrePostRequest(request: PostSubmitRequest): PrePostRequest = when (request) {
            is PostSubmitRequest.NewThread -> PrePostRequest.NewThread(fid = request.fid)
            is PostSubmitRequest.ReplyThread -> PrePostRequest.ReplyThread(
                tid = request.tid,
                quickReply = request.quickReply,
            )

            is PostSubmitRequest.ReplyPost -> PrePostRequest.ReplyPost(
                tid = request.tid,
                pid = request.pid,
            )

            is PostSubmitRequest.QuotePost -> PrePostRequest.QuotePost(
                tid = request.tid,
                pid = request.pid,
            )

            is PostSubmitRequest.EditPost -> PrePostRequest.EditPost(
                fid = request.fid,
                tid = request.tid,
                pid = request.pid,
                page = request.page,
            )

            is PostSubmitRequest.DeletePost -> PrePostRequest.EditPost(
                fid = request.fid,
                tid = request.tid,
                pid = request.pid,
                page = request.page,
            )
        }

        private fun validateSubmitRequest(
            request: PostSubmitRequest,
            cleanedNewAttachments: List<String>,
        ): String? = when (request) {
            is PostSubmitRequest.NewThread -> when {
                request.fid <= 0 -> "请选择有效的版块"
                request.subject.isBlank() -> "标题不能为空"
                request.content.isBlank() && cleanedNewAttachments.isEmpty() -> "正文不能为空"
                else -> null
            }

            is PostSubmitRequest.ReplyThread -> when {
                !DiscuzUrlHelper.isValidId(request.tid) -> "无效的帖子编号"
                request.content.isBlank() && cleanedNewAttachments.isEmpty() -> "回复内容不能为空"
                else -> null
            }

            is PostSubmitRequest.ReplyPost -> when {
                !DiscuzUrlHelper.isValidId(request.tid) -> "无效的帖子编号"
                !DiscuzUrlHelper.isValidId(request.pid) -> "无效的楼层编号"
                request.content.isBlank() && cleanedNewAttachments.isEmpty() -> "回复内容不能为空"
                else -> null
            }

            is PostSubmitRequest.QuotePost -> when {
                !DiscuzUrlHelper.isValidId(request.tid) -> "无效的帖子编号"
                !DiscuzUrlHelper.isValidId(request.pid) -> "无效的楼层编号"
                request.content.isBlank() && cleanedNewAttachments.isEmpty() -> "回复内容不能为空"
                else -> null
            }

            is PostSubmitRequest.EditPost -> when {
                request.fid < 0 -> "无效的版块编号"
                !DiscuzUrlHelper.isValidId(request.tid) -> "无效的帖子编号"
                !DiscuzUrlHelper.isValidId(request.pid) -> "无效的楼层编号"
                request.content.isBlank() && cleanedNewAttachments.isEmpty() -> "正文不能为空"
                else -> null
            }

            is PostSubmitRequest.DeletePost -> when {
                request.fid < 0 -> "无效的版块编号"
                !DiscuzUrlHelper.isValidId(request.tid) -> "无效的帖子编号"
                !DiscuzUrlHelper.isValidId(request.pid) -> "无效的楼层编号"
                else -> null
            }
        }

        private fun buildFinalMessage(
            request: PostSubmitRequest,
            prePostInfo: PrePostInfo,
            newAttachments: List<String>,
            settings: AppSettings,
        ): String {
            if (request is PostSubmitRequest.DeletePost) {
                return request.content
            }

            val rawContent = when (request) {
                is PostSubmitRequest.NewThread -> request.content
                is PostSubmitRequest.ReplyThread -> request.content
                is PostSubmitRequest.ReplyPost -> request.content
                is PostSubmitRequest.QuotePost -> request.content
                is PostSubmitRequest.EditPost -> request.content
                is PostSubmitRequest.DeletePost -> request.content
            }

            var body = appendUninsertedImages(rawContent, newAttachments)
            body = wrapBareUrlsWithBbcode(body)

            if (request !is PostSubmitRequest.EditPost) {
                val tail = buildTailBbcode(settings)
                if (tail.isNotEmpty() && !body.trim().endsWith(tail)) {
                    body = "$body  $tail"
                }
            }

            if (request is PostSubmitRequest.ReplyPost || request is PostSubmitRequest.QuotePost) {
                val decodedQuote = decodeNumericEntitiesForEditor(prePostInfo.quoteText)
                if (decodedQuote.isNotEmpty()) {
                    return "$decodedQuote\n\n    $body"
                }
            }
            return body
        }

        private fun buildSubmitPath(
            request: PostSubmitRequest,
            prePostInfo: PrePostInfo,
        ): String = when (request) {
            is PostSubmitRequest.NewThread -> {
                val effectiveTypeId = request.typeId.trim().ifEmpty {
                    prePostInfo.typeId?.trim().orEmpty().ifEmpty { "0" }
                }
                val encodedTypeId = URLEncoder.encode(effectiveTypeId, "UTF-8")
                "post.php?action=newthread&fid=${request.fid}&typeid=$encodedTypeId&topicsubmit=yes"
            }

            is PostSubmitRequest.ReplyThread ->
                "post.php?action=reply&tid=${request.tid.trim()}&replysubmit=yes"

            is PostSubmitRequest.ReplyPost ->
                "post.php?action=reply&tid=${request.tid.trim()}&replysubmit=yes"

            is PostSubmitRequest.QuotePost ->
                "post.php?action=reply&tid=${request.tid.trim()}&replysubmit=yes"

            is PostSubmitRequest.EditPost -> {
                val safePage = request.page.coerceAtLeast(1)
                "post.php?action=edit&extra=&editsubmit=yes&mod=&fid=${request.fid}" +
                    "&tid=${request.tid.trim()}&pid=${request.pid.trim()}&page=$safePage"
            }

            is PostSubmitRequest.DeletePost -> {
                val safePage = request.page.coerceAtLeast(1)
                "post.php?action=edit&extra=&editsubmit=yes&mod=&fid=${request.fid}" +
                    "&tid=${request.tid.trim()}&pid=${request.pid.trim()}&page=$safePage"
            }
        }

        private fun buildSubmitFields(
            request: PostSubmitRequest,
            prePostInfo: PrePostInfo,
            finalMessage: String,
            newAttachments: List<String>,
            deletedAttachments: List<String>,
            postTimeMs: Long,
        ): List<Pair<String, String>> = buildList {
            add("formhash" to prePostInfo.formhash)
            add("posttime" to postTimeMs.toString())
            add("wysiwyg" to "0")
            add("usesig" to "1")
            add("message" to finalMessage)

            if (request is PostSubmitRequest.DeletePost) {
                add("delete" to "1")
            }

            for (attachId in newAttachments) {
                add("attachnew[][description]" to attachId)
            }
            for (attachId in deletedAttachments) {
                add("attachdel[]" to attachId)
            }

            when (request) {
                is PostSubmitRequest.NewThread -> {
                    add("subject" to request.subject)
                    add("attention_add" to "1")
                }

                is PostSubmitRequest.EditPost -> {
                    val subject = request.subject
                    if (!subject.isNullOrEmpty()) {
                        add("subject" to subject)
                        val typeId = request.typeId
                        if (!typeId.isNullOrEmpty()) {
                            add("typeid" to typeId)
                        }
                    }
                }

                is PostSubmitRequest.DeletePost -> {
                    val subject = request.subject
                    if (!subject.isNullOrEmpty()) {
                        add("subject" to subject)
                        val typeId = request.typeId
                        if (!typeId.isNullOrEmpty()) {
                            add("typeid" to typeId)
                        }
                    }
                }

                is PostSubmitRequest.ReplyPost,
                is PostSubmitRequest.QuotePost,
                -> {
                    val noticeAuthor = prePostInfo.noticeAuthor
                    if (!noticeAuthor.isNullOrEmpty()) {
                        add("noticeauthor" to noticeAuthor)
                        add("noticeauthormsg" to prePostInfo.noticeAuthorMsg.orEmpty())
                        add("noticetrimstr" to prePostInfo.noticeTrimStr.orEmpty())
                    }
                }

                is PostSubmitRequest.ReplyThread -> Unit
            }
        }

        private fun extractSiteAlertMessage(html: String, isDelete: Boolean): String {
            val doc = Jsoup.parse(html)
            val alertError = DiscuzCommonParser.parseErrorMessage(doc)?.trim()?.ifEmpty { null }
            if (alertError != null) {
                return decodeNumericEntitiesForEditor(alertError)
            }
            val alertInfoEl = doc.firstMatch("div.alert_info")
            if (alertInfoEl != null) {
                val cloned = alertInfoEl.clone()
                cloned.select("a").remove()
                val cleaned = cloned.text().trim().ifEmpty { alertInfoEl.text().trim() }
                if (cleaned.isNotEmpty()) {
                    return decodeNumericEntitiesForEditor(cleaned)
                }
            }
            return if (isDelete) "删除失败" else "发表失败"
        }

        private fun sanitizeIds(rawIds: List<String>): List<String> {
            if (rawIds.isEmpty()) return emptyList()
            val result = LinkedHashSet<String>(rawIds.size)
            for (raw in rawIds) {
                val trimmed = raw.trim()
                if (trimmed.isNotEmpty()) {
                    result.add(trimmed)
                }
            }
            return result.toList()
        }

        private fun deriveForumBaseUrl(forumServer: String): String {
            val trimmed = forumServer.trim().trimEnd('/')
            return if (trimmed.endsWith("/forum", ignoreCase = true)) {
                "$trimmed/"
            } else {
                "$trimmed/forum/"
            }
        }
    }
}
