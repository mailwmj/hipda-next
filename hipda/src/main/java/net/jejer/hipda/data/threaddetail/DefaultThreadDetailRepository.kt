package net.jejer.hipda.data.threaddetail

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.ForumHtmlResponse
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.local.BrowseHistoryDao
import net.jejer.hipda.data.local.BrowseHistoryEntity
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionDao
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.user.BlacklistRepository
import net.jejer.hipda.parser.common.DiscuzCommonParser
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzTextDecoder
import net.jejer.hipda.parser.common.DiscuzUrlHelper
import net.jejer.hipda.parser.common.firstMatch
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import net.jejer.hipda.parser.threaddetail.ThreadDetailParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

@Singleton
class DefaultThreadDetailRepository internal constructor(
    private val forumHttpClient: ForumHttpClient,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val blacklistRepository: BlacklistRepository,
    private val browseHistoryDao: BrowseHistoryDao,
    private val readingPositionDao: ReadingPositionDao,
    private val clock: () -> Long,
    private val maxBrowseHistory: Int,
) : ThreadDetailRepository {

    init {
        require(maxBrowseHistory >= 0) { "maxBrowseHistory must be >= 0" }
    }

    @Inject
    constructor(
        forumHttpClient: ForumHttpClient,
        authRepository: AuthRepository,
        settingsRepository: SettingsRepository,
        blacklistRepository: BlacklistRepository,
        browseHistoryDao: BrowseHistoryDao,
        readingPositionDao: ReadingPositionDao,
    ) : this(
        forumHttpClient = forumHttpClient,
        authRepository = authRepository,
        settingsRepository = settingsRepository,
        blacklistRepository = blacklistRepository,
        browseHistoryDao = browseHistoryDao,
        readingPositionDao = readingPositionDao,
        clock = System::currentTimeMillis,
        maxBrowseHistory = DEFAULT_MAX_BROWSE_HISTORY,
    )

    override suspend fun loadPage(
        tid: String,
        page: Int,
        filter: ThreadDetailFilter,
        recordHistory: Boolean,
        forumName: String?,
        priority: AccessPriority,
    ): ThreadDetailLoadResult {
        val normalizedTid = validatePositiveId(tid)
            ?: return ThreadDetailError.InvalidInput("主题 ID 无效")
        if (page < 1) {
            return ThreadDetailError.InvalidInput("页码必须大于等于 1")
        }

        val requireAuth = isSignedIn()
        var discoveredOpAuthor: String? = null
        val resolvedFilter: ThreadDetailFilter
        val authorIdParam: String?

        when (filter) {
            ThreadDetailFilter.All -> {
                resolvedFilter = ThreadDetailFilter.All
                authorIdParam = null
            }

            is ThreadDetailFilter.UserOnly -> {
                val normalizedUid = validatePositiveId(filter.uid)
                    ?: return ThreadDetailError.InvalidInput("作者用户 ID 无效")
                resolvedFilter = ThreadDetailFilter.UserOnly(uid = normalizedUid)
                authorIdParam = normalizedUid
            }

            is ThreadDetailFilter.AuthorOnly -> {
                val providedAuthorId = filter.authorId
                if (providedAuthorId != null) {
                    val normalizedAuthorId = validatePositiveId(providedAuthorId)
                        ?: return ThreadDetailError.InvalidInput("楼主用户 ID 无效")
                    resolvedFilter = ThreadDetailFilter.AuthorOnly(authorId = normalizedAuthorId)
                    authorIdParam = normalizedAuthorId
                } else {
                    when (val discovery = resolveThreadAuthorFromPage1(normalizedTid, requireAuth, priority)) {
                        is AuthorDiscoveryResult.Found -> {
                            resolvedFilter = ThreadDetailFilter.AuthorOnly(authorId = discovery.uid)
                            authorIdParam = discovery.uid
                            discoveredOpAuthor = discovery.authorName
                        }

                        is AuthorDiscoveryResult.Failed -> return discovery.error
                    }
                }
            }
        }

        return fetchAndParseThreadPage(
            relativePath = buildViewThreadPath(
                tid = normalizedTid,
                page = page,
                authorId = authorIdParam,
            ),
            tidHint = normalizedTid,
            filter = resolvedFilter,
            knownThreadAuthorId = (resolvedFilter as? ThreadDetailFilter.AuthorOnly)?.authorId,
            fallbackOpAuthor = discoveredOpAuthor,
            targetPostId = null,
            recordHistory = recordHistory,
            forumName = forumName,
            requireAuth = requireAuth,
            priority = priority,
        )
    }

    override suspend fun loadLastPage(
        tid: String,
        recordHistory: Boolean,
        forumName: String?,
        priority: AccessPriority,
    ): ThreadDetailLoadResult {
        val normalizedTid = validatePositiveId(tid)
            ?: return ThreadDetailError.InvalidInput("主题 ID 无效")

        return fetchAndParseThreadPage(
            relativePath = "$LAST_POST_PATH_PREFIX$normalizedTid",
            tidHint = normalizedTid,
            filter = ThreadDetailFilter.All,
            knownThreadAuthorId = null,
            fallbackOpAuthor = null,
            targetPostId = null,
            recordHistory = recordHistory,
            forumName = forumName,
            requireAuth = isSignedIn(),
            priority = priority,
        )
    }

    override suspend fun loadByPostId(
        pid: String,
        tid: String?,
        recordHistory: Boolean,
        forumName: String?,
        priority: AccessPriority,
    ): ThreadDetailLoadResult {
        val normalizedPid = validatePositiveId(pid)
            ?: return ThreadDetailError.InvalidInput("楼层 ID 无效")
        val normalizedTid = if (tid.isNullOrBlank()) {
            null
        } else {
            validatePositiveId(tid)
                ?: return ThreadDetailError.InvalidInput("主题 ID 无效")
        }

        val relativePath = if (normalizedTid != null) {
            "redirect.php?goto=findpost&pid=$normalizedPid&ptid=$normalizedTid"
        } else {
            "gotopost.php?pid=$normalizedPid"
        }

        return fetchAndParseThreadPage(
            relativePath = relativePath,
            tidHint = normalizedTid,
            filter = ThreadDetailFilter.All,
            knownThreadAuthorId = null,
            fallbackOpAuthor = null,
            targetPostId = normalizedPid,
            recordHistory = recordHistory,
            forumName = forumName,
            requireAuth = isSignedIn(),
            priority = priority,
        )
    }

    override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? {
        val normalizedTid = validatePositiveId(tid) ?: return null
        return readingPositionDao.get(normalizedTid)
    }

    override suspend fun saveReadingPosition(
        tid: String,
        page: Int,
        pid: String,
        offsetPx: Int,
        filterMode: ReadingFilterMode,
        filterUid: String?,
        filterUserName: String?,
    ) {
        val normalizedTid = validatePositiveId(tid) ?: return
        val normalizedFilterUid = if (filterMode == ReadingFilterMode.NONE) {
            null
        } else {
            filterUid?.trim()?.takeIf { it.isNotEmpty() }
        }
        val normalizedFilterUserName = if (filterMode == ReadingFilterMode.NONE) {
            null
        } else {
            filterUserName?.trim()?.takeIf { it.isNotEmpty() }
        }
        readingPositionDao.upsert(
            ReadingPositionEntity(
                tid = normalizedTid,
                page = page.coerceAtLeast(1),
                pid = pid.trim(),
                offsetPx = offsetPx.coerceAtLeast(0),
                filterMode = filterMode,
                filterUid = normalizedFilterUid,
                filterUserName = normalizedFilterUserName,
                updatedAt = clock(),
            ),
        )
    }

    override fun observeFloors(
        floorsFlow: Flow<List<PostFloor>>,
    ): Flow<List<ThreadDetailFloorItem>> = combine(
        floorsFlow,
        blacklistRepository.blockedUsernames,
    ) { floors, blocked ->
        mapFloorsInternal(floors, blocked)
    }.distinctUntilChanged()

    override fun observeVisibleFloors(
        floorsFlow: Flow<List<PostFloor>>,
    ): Flow<List<ThreadDetailFloorItem>> = observeFloors(floorsFlow)
        .map { items -> items.filterNot { it.isAuthorBlocked } }
        .distinctUntilChanged()

    override fun mapFloorsWithBlacklist(
        floors: List<PostFloor>,
        blockedUsernames: Set<String>?,
    ): List<ThreadDetailFloorItem> = mapFloorsInternal(
        floors = floors,
        blockedUsernames = blockedUsernames ?: blacklistRepository.blockedUsernames.value,
    )

    override fun filterBlockedFloors(
        floors: List<PostFloor>,
        blockedUsernames: Set<String>?,
    ): List<PostFloor> {
        val effectiveBlocked = blockedUsernames ?: blacklistRepository.blockedUsernames.value
        if (effectiveBlocked.isEmpty()) return floors
        return floors.filterNot { floor -> isAuthorBlocked(floor.author, effectiveBlocked) }
    }

    override suspend fun submitPollVote(
        fid: Int,
        tid: String,
        formhash: String,
        optionIds: List<String>,
        maxChoices: Int?,
        priority: AccessPriority,
    ): PollVoteResult {
        if (fid <= 0) {
            return ThreadDetailError.InvalidInput("版块 ID 无效")
        }
        val normalizedTid = validatePositiveId(tid)
            ?: return ThreadDetailError.InvalidInput("主题 ID 无效")
        val normalizedFormhash = formhash.trim()
        if (normalizedFormhash.isEmpty()) {
            return ThreadDetailError.InvalidInput("缺少投票令牌 formhash")
        }
        if (maxChoices != null && maxChoices < 1) {
            return ThreadDetailError.InvalidInput("最大可选数必须大于等于 1")
        }
        if (optionIds.isEmpty()) {
            return ThreadDetailError.InvalidInput("请至少选择一个投票选项")
        }
        val trimmedOptions = optionIds.map { it.trim() }
        if (trimmedOptions.any { it.isEmpty() }) {
            return ThreadDetailError.InvalidInput("投票选项 ID 无效")
        }
        val sortedOptionIds = trimmedOptions.distinct().sorted()
        if (maxChoices != null && sortedOptionIds.size > maxChoices) {
            return ThreadDetailError.InvalidInput("最多可选 $maxChoices 项")
        }
        if (!isSignedIn()) {
            return ThreadDetailError.AuthRequired(
                reason = AuthRequiredReason.NOT_LOGGED_IN,
                message = "请先登录后再投票",
            )
        }

        val fields = buildList(sortedOptionIds.size + 1) {
            add(FIELD_FORMHASH to normalizedFormhash)
            sortedOptionIds.forEach { optionId ->
                add(FIELD_POLL_ANSWERS to optionId)
            }
        }
        val forumServer = settingsRepository.settings.first().forumServer
        val response = try {
            forumHttpClient.postGbkForm(
                relativePath = buildVotePollPath(fid = fid, tid = normalizedTid),
                fields = fields,
                priority = priority,
                requireAuth = false,
            )
        } catch (e: IOException) {
            return mapThreadDetailIoException(e)
        }

        return when (val outcome = classifyPollVoteResponse(response)) {
            is PollVotePostOutcome.Failed -> outcome.error

            is PollVotePostOutcome.Succeeded -> {
                val landedDoc = outcome.landedPageDoc
                if (landedDoc != null) {
                    val forumBaseUrl = deriveForumBaseUrl(forumServer)
                    val avatarBaseUrl = deriveAvatarBaseUrl(forumBaseUrl)
                    val landedPage = ThreadDetailParser.parse(
                        doc = landedDoc,
                        tid = normalizedTid,
                        avatarBaseUrl = avatarBaseUrl,
                        forumBaseUrl = forumBaseUrl,
                    )
                    if (landedPage.hasPostList &&
                        landedPage.page == 1 &&
                        landedPage.meta.session.isLoggedIn &&
                        landedPage.meta.errorMessage == null
                    ) {
                        return PollVoteResult.Success(
                            refreshedPage = landedPage,
                            poll = landedPage.firstFloorPoll(),
                            message = outcome.message,
                        )
                    }
                }

                when (
                    val refreshed = loadPage(
                        tid = normalizedTid,
                        page = 1,
                        filter = ThreadDetailFilter.All,
                        recordHistory = false,
                        priority = priority,
                    )
                ) {
                    is ThreadDetailLoadResult.Success -> PollVoteResult.Success(
                        refreshedPage = refreshed.page,
                        poll = refreshed.poll,
                        message = outcome.message,
                    )

                    is ThreadDetailError -> refreshed
                }
            }
        }
    }

    private suspend fun resolveThreadAuthorFromPage1(
        tid: String,
        requireAuth: Boolean,
        priority: AccessPriority,
    ): AuthorDiscoveryResult {
        val response = try {
            forumHttpClient.getHtml(
                relativePath = buildViewThreadPath(tid = tid, page = 1, authorId = null),
                priority = priority,
                requireAuth = requireAuth,
            )
        } catch (e: IOException) {
            return AuthorDiscoveryResult.Failed(mapThreadDetailIoException(e))
        }

        val doc = Jsoup.parse(response.html)
        val failure = inspectPageFailure(
            doc = doc,
            meta = response.meta,
            hasPostList = doc.select(POST_LIST_SELECTOR).size == 1,
            requireAuth = requireAuth,
        )
        if (failure != null) {
            return AuthorDiscoveryResult.Failed(failure)
        }

        val authorId = ThreadDetailParser.parseThreadAuthorId(doc)?.let(::validatePositiveId)
            ?: return AuthorDiscoveryResult.Failed(ThreadDetailError.InvalidPage("无法识别楼主用户 ID"))
        val authorName = extractFirstAuthorName(doc)
        return AuthorDiscoveryResult.Found(uid = authorId, authorName = authorName)
    }

    private suspend fun fetchAndParseThreadPage(
        relativePath: String,
        tidHint: String?,
        filter: ThreadDetailFilter,
        knownThreadAuthorId: String?,
        fallbackOpAuthor: String?,
        targetPostId: String?,
        recordHistory: Boolean,
        forumName: String?,
        requireAuth: Boolean,
        priority: AccessPriority,
    ): ThreadDetailLoadResult {
        val forumServer = settingsRepository.settings.first().forumServer
        val response = try {
            forumHttpClient.getHtml(
                relativePath = relativePath,
                priority = priority,
                requireAuth = requireAuth,
            )
        } catch (e: IOException) {
            return mapThreadDetailIoException(e)
        }

        val doc = Jsoup.parse(response.html)
        val forumBaseUrl = deriveForumBaseUrl(forumServer)
        val avatarBaseUrl = deriveAvatarBaseUrl(forumBaseUrl)
        val effectiveTidHint = tidHint ?: extractTidFromUrl(response.url)
        val parsed = ThreadDetailParser.parse(
            doc = doc,
            tid = effectiveTidHint,
            avatarBaseUrl = avatarBaseUrl,
            forumBaseUrl = forumBaseUrl,
        )

        val failure = inspectPageFailure(
            doc = doc,
            meta = parsed.meta,
            hasPostList = parsed.hasPostList,
            requireAuth = requireAuth,
        )
        if (failure != null) {
            return failure
        }

        val resolvedForumName = forumName?.trim()?.takeIf { it.isNotEmpty() }
            ?: extractForumNameFromNav(doc)
        val resolvedThreadAuthorId = knownThreadAuthorId
            ?: parsed.floors.firstOrNull { it.floor == 1 }?.uid?.let(::validatePositiveId)

        if (recordHistory) {
            recordBrowseHistoryIfValid(
                parsed = parsed,
                forumName = resolvedForumName.orEmpty(),
                fallbackOpAuthor = fallbackOpAuthor,
            )
        }

        return ThreadDetailLoadResult.Success(
            page = parsed,
            filter = filter,
            threadAuthorId = resolvedThreadAuthorId,
            forumName = resolvedForumName,
            targetPostId = targetPostId,
        )
    }

    private suspend fun recordBrowseHistoryIfValid(
        parsed: ThreadDetailPage,
        forumName: String,
        fallbackOpAuthor: String?,
    ) {
        val validTid = parsed.tid?.let(::validatePositiveId) ?: return
        val normalizedTitle = parsed.title.trim()
        if (normalizedTitle.isEmpty()) return

        val author = parsed.floors.firstOrNull { it.floor == 1 }?.author?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackOpAuthor?.trim()?.takeIf { it.isNotEmpty() }
            ?: parsed.floors.firstOrNull()?.author?.trim().orEmpty()
        val fidString = if (parsed.fid > 0) parsed.fid.toString() else ""

        browseHistoryDao.upsert(
            BrowseHistoryEntity(
                tid = validTid,
                title = normalizedTitle,
                fid = fidString,
                forumName = forumName,
                author = author,
                lastVisitedAt = clock(),
            ),
        )
        browseHistoryDao.trimTo(maxBrowseHistory)
    }

    private fun inspectPageFailure(
        doc: Document,
        meta: DiscuzPageMeta,
        hasPostList: Boolean,
        requireAuth: Boolean,
    ): ThreadDetailError? {
        val alertError = meta.errorMessage?.trim()?.takeIf { it.isNotEmpty() }
        if (alertError != null) {
            return classifyPageMessageError(
                message = alertError,
                doc = doc,
                isLoggedIn = meta.session.isLoggedIn,
                requireAuth = requireAuth,
            )
        }

        if (!hasPostList) {
            val alertInfo = extractAlertInfo(doc)
            if (alertInfo != null) {
                return classifyPageMessageError(
                    message = alertInfo,
                    doc = doc,
                    isLoggedIn = meta.session.isLoggedIn,
                    requireAuth = requireAuth,
                )
            }
            if (!requireAuth && !meta.session.isLoggedIn && hasLoginForm(doc)) {
                return ThreadDetailError.AuthRequired(
                    reason = AuthRequiredReason.NOT_LOGGED_IN,
                    message = "请先登录后查看该主题",
                )
            }
            return ThreadDetailError.InvalidPage("帖子详情页面解析失败")
        }

        return null
    }

    private fun classifyPageMessageError(
        message: String,
        doc: Element,
        isLoggedIn: Boolean,
        requireAuth: Boolean,
    ): ThreadDetailError {
        if (!requireAuth && !isLoggedIn && (hasLoginForm(doc) || isAuthRequiredMessage(message))) {
            return ThreadDetailError.AuthRequired(
                reason = AuthRequiredReason.NOT_LOGGED_IN,
                message = message,
            )
        }
        return ThreadDetailError.SiteError(message)
    }

    private fun classifyPollVoteResponse(response: ForumHtmlResponse): PollVotePostOutcome {
        val rawBody = response.html
        if (rawBody.isBlank()) {
            return PollVotePostOutcome.Failed(ThreadDetailError.InvalidPage("投票响应为空"))
        }

        if (rawBody.contains("<root", ignoreCase = true)) {
            val xmlDoc = Jsoup.parse(rawBody, "", Parser.xmlParser())
            val rootEl = xmlDoc.select("root").lastOrNull()
            if (rootEl != null) {
                return classifyPollVoteXmlRoot(rootEl.text())
            }
        }

        val doc = Jsoup.parse(rawBody)
        val checkLoginForm = !response.meta.session.isLoggedIn
        val alertError = DiscuzCommonParser.parseErrorMessage(doc)?.trim()?.takeIf { it.isNotEmpty() }
        if (alertError != null) {
            return classifyPollErrorMessage(alertError, doc, checkLoginForm)
        }

        val alertInfo = extractAlertInfo(doc)
        if (alertInfo != null) {
            return if (isPollVoteSuccessMessage(alertInfo)) {
                PollVotePostOutcome.Succeeded(message = alertInfo)
            } else {
                classifyPollErrorMessage(alertInfo, doc, checkLoginForm)
            }
        }

        if (doc.select(POST_LIST_SELECTOR).size == 1) {
            return if (response.meta.session.isLoggedIn) {
                PollVotePostOutcome.Succeeded(
                    message = "",
                    landedPageDoc = doc,
                )
            } else {
                PollVotePostOutcome.Failed(
                    ThreadDetailError.AuthRequired(
                        reason = AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED,
                        message = "登录已失效，请重新登录后再投票",
                    ),
                )
            }
        }

        if (checkLoginForm && hasLoginForm(doc)) {
            return PollVotePostOutcome.Failed(
                ThreadDetailError.AuthRequired(
                    reason = AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED,
                    message = "请先登录后再投票",
                ),
            )
        }

        return PollVotePostOutcome.Failed(ThreadDetailError.InvalidPage("投票响应页面无法识别"))
    }

    private fun classifyPollVoteXmlRoot(rawInner: String): PollVotePostOutcome {
        val fragment = Jsoup.parseBodyFragment(rawInner)

        val alertError = fragment.firstMatch("div.alert_error")?.let(::cleanElementText)
        if (alertError != null) {
            return classifyPollErrorMessage(alertError, fragment, checkLoginForm = true)
        }

        val alertInfo = extractAlertInfo(fragment)
        if (alertInfo != null) {
            return if (isPollVoteSuccessMessage(alertInfo)) {
                PollVotePostOutcome.Succeeded(message = alertInfo)
            } else {
                classifyPollErrorMessage(alertInfo, fragment, checkLoginForm = true)
            }
        }

        val visibleText = cleanElementText(fragment.body()).orEmpty()
        if (isPollVoteSuccessMessage(visibleText)) {
            return PollVotePostOutcome.Succeeded(message = visibleText)
        }
        if (visibleText.isNotEmpty()) {
            return classifyPollErrorMessage(visibleText, fragment, checkLoginForm = true)
        }

        val hasQuickForwardRedirect =
            (rawInner.contains("viewthread.php", ignoreCase = true) && rawInner.contains("location", ignoreCase = true)) ||
                rawInner.contains("succeedhandle_", ignoreCase = true)
        return if (hasQuickForwardRedirect) {
            PollVotePostOutcome.Succeeded(message = "")
        } else {
            PollVotePostOutcome.Failed(ThreadDetailError.InvalidPage("投票响应内容为空"))
        }
    }

    private fun classifyPollErrorMessage(
        message: String,
        container: Element,
        checkLoginForm: Boolean,
    ): PollVotePostOutcome.Failed = if (isAuthRequiredMessage(message) || (checkLoginForm && hasLoginForm(container))) {
        PollVotePostOutcome.Failed(
            ThreadDetailError.AuthRequired(
                reason = AuthRequiredReason.SESSION_STILL_UNAUTHENTICATED,
                message = message,
            ),
        )
    } else {
        PollVotePostOutcome.Failed(ThreadDetailError.SiteError(message))
    }

    private fun isSignedIn(): Boolean = authRepository.currentAccount.value != null

    private fun mapFloorsInternal(
        floors: List<PostFloor>,
        blockedUsernames: Set<String>,
    ): List<ThreadDetailFloorItem> = floors.map { floor ->
        ThreadDetailFloorItem(
            floor = floor,
            isAuthorBlocked = isAuthorBlocked(floor.author, blockedUsernames),
        )
    }

    private fun isAuthorBlocked(
        author: String,
        blockedUsernames: Set<String>,
    ): Boolean {
        if (blockedUsernames.isEmpty()) return false
        val normalized = author.trim()
        return normalized.isNotEmpty() && normalized in blockedUsernames
    }

    private fun ThreadDetailPage.firstFloorPoll() =
        floors.firstOrNull { it.floor == 1 }?.poll

    private sealed interface AuthorDiscoveryResult {
        data class Found(val uid: String, val authorName: String?) : AuthorDiscoveryResult
        data class Failed(val error: ThreadDetailError) : AuthorDiscoveryResult
    }

    private sealed interface PollVotePostOutcome {
        data class Succeeded(
            val message: String,
            val landedPageDoc: Document? = null,
        ) : PollVotePostOutcome

        data class Failed(val error: ThreadDetailError) : PollVotePostOutcome
    }

    companion object {
        const val DEFAULT_MAX_BROWSE_HISTORY: Int = 500

        private const val POST_LIST_SELECTOR = "div#wrap div#postlist"
        private const val LAST_POST_PATH_PREFIX = "redirect.php?goto=lastpost&from=fastpost&tid="
        private const val AVATAR_PATH = "uc_server/data/avatar/"
        private const val FIELD_FORMHASH = "formhash"
        private const val FIELD_POLL_ANSWERS = "pollanswers[]"

        private val AUTH_REQUIRED_MARKERS = listOf(
            "尚未登录",
            "未登录",
            "请先登录",
            "请登录",
            "没有登录",
            "登录后",
            "用户组(游客)",
            "游客无法",
        )

        private val POLL_SUCCESS_MARKERS = listOf(
            "投票已经提交",
            "投票成功",
            "感谢您的参与",
            "非常感谢",
            "转入主题页面",
        )

        internal fun validatePositiveId(raw: String?): String? {
            val trimmed = raw?.trim() ?: return null
            if (trimmed.isEmpty() || !trimmed.all { it in '0'..'9' }) return null
            val numeric = trimmed.toLongOrNull() ?: return null
            return if (numeric > 0L) trimmed else null
        }

        internal fun buildViewThreadPath(
            tid: String,
            page: Int,
            authorId: String?,
        ): String = if (authorId != null) {
            "viewthread.php?tid=$tid&page=$page&authorid=$authorId"
        } else {
            "viewthread.php?tid=$tid&page=$page"
        }

        internal fun buildVotePollPath(fid: Int, tid: String): String =
            "misc.php?action=votepoll&fid=$fid&tid=$tid&pollsubmit=yes&quickforward=yes&inajax=1"

        internal fun deriveForumBaseUrl(forumServer: String): String? {
            val trimmed = forumServer.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            return if (trimmed.endsWith("/forum", ignoreCase = true)) {
                "$trimmed/"
            } else {
                "$trimmed/forum/"
            }
        }

        internal fun deriveAvatarBaseUrl(forumBaseUrl: String?): String? =
            forumBaseUrl?.let { "$it$AVATAR_PATH" }

        internal fun extractTidFromUrl(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val candidate = DiscuzUrlHelper.extractQueryParam(url, "tid")
                ?: DiscuzUrlHelper.extractQueryParam(url, "ptid")
            return validatePositiveId(candidate)
        }

        private fun extractForumNameFromNav(doc: Document): String? {
            val navEl = doc.firstMatch("div#nav") ?: return null
            for (link in navEl.select("a")) {
                val href = link.attr("href")
                if (href.indexOf("fid=") > 0) {
                    val text = DiscuzTextDecoder.decodeDoubleEscapedEntities(link.text()).trim()
                    if (text.isNotEmpty()) return text
                }
            }
            return null
        }

        private fun extractFirstAuthorName(doc: Document): String? {
            val anchor = doc.firstMatch("td.postauthor div.postinfo a") ?: return null
            return DiscuzTextDecoder.decodeDoubleEscapedEntities(anchor.text()).trim().ifEmpty { null }
        }

        private fun extractAlertInfo(container: Element): String? {
            val infoEl = container.firstMatch("div.alert_info") ?: return null
            return cleanElementText(infoEl)
        }

        private fun cleanElementText(element: Element): String? {
            val cloned = element.clone()
            cloned.select("script, style, a").remove()
            val decoded = DiscuzTextDecoder.decodeDoubleEscapedEntities(cloned.text()).trim()
            return decoded.ifEmpty { null }
        }

        private fun hasLoginForm(container: Element): Boolean =
            container.select("form[name=login], form#loginform, input[name=loginsubmit]").isNotEmpty()

        private fun isAuthRequiredMessage(message: String): Boolean =
            message.isNotEmpty() && AUTH_REQUIRED_MARKERS.any { marker -> message.contains(marker) }

        private fun isPollVoteSuccessMessage(message: String): Boolean =
            message.isNotEmpty() && POLL_SUCCESS_MARKERS.any { marker -> message.contains(marker) }
    }
}
