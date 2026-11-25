package net.jejer.hipda.data.threadlist

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.jejer.hipda.data.auth.AuthRepository
import net.jejer.hipda.data.auth.AuthRequiredReason
import net.jejer.hipda.data.auth.ForumHtmlResponse
import net.jejer.hipda.data.auth.ForumHttpClient
import net.jejer.hipda.data.local.BrowseHistoryDao
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.settings.SettingsRepository
import net.jejer.hipda.data.user.BlacklistRepository
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.DiscuzUrlHelper
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.threadlist.ThreadListPage
import net.jejer.hipda.parser.threadlist.ThreadListParser
import net.jejer.hipda.parser.threadlist.ThreadSummary

@Singleton
class DefaultThreadListRepository @Inject constructor(
    private val forumHttpClient: ForumHttpClient,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val blacklistRepository: BlacklistRepository,
    private val browseHistoryDao: BrowseHistoryDao,
) : ThreadListRepository {

    private val cacheEntries = ConcurrentHashMap<ThreadListQuery, QueryCacheEntry>()

    private val showStickyFlow: Flow<Boolean> = settingsRepository.settings
        .map { it.showStickyThreads }
        .distinctUntilChanged()

    private val visitedTidsFlow: Flow<Set<String>> = browseHistoryDao.observeVisitedTids()
        .map { tids ->
            tids.mapNotNullTo(HashSet(tids.size)) { it.trim().takeIf(String::isNotEmpty) }.toSet()
        }
        .distinctUntilChanged()

    override suspend fun loadPage(
        query: ThreadListQuery,
        startPage: Int,
        existingTids: Set<String>,
        minVisibleItems: Int,
        maxAutoBackfillPages: Int,
        priority: AccessPriority,
    ): ThreadListLoadResult {
        val validated = validateInputs(
            query = query,
            startPage = startPage,
            minVisibleItems = minVisibleItems,
            maxAutoBackfillPages = maxAutoBackfillPages,
        ) ?: return invalidInputError(query, startPage, minVisibleItems, maxAutoBackfillPages)

        return loadPageInternal(
            query = validated.canonicalQuery,
            normalizedTypeId = validated.normalizedTypeId,
            startPage = startPage,
            existingTids = existingTids,
            minVisibleItems = minVisibleItems,
            maxAutoBackfillPages = maxAutoBackfillPages,
            priority = priority,
        )
    }

    override suspend fun refresh(
        query: ThreadListQuery,
        minVisibleItems: Int,
        maxAutoBackfillPages: Int,
        priority: AccessPriority,
    ): ThreadListLoadResult {
        val validated = validateInputs(
            query = query,
            startPage = 1,
            minVisibleItems = minVisibleItems,
            maxAutoBackfillPages = maxAutoBackfillPages,
        ) ?: return invalidInputError(query, 1, minVisibleItems, maxAutoBackfillPages)

        val entry = entryFor(validated.canonicalQuery)
        return entry.mutex.withLock {
            entry.state.update { it.copy(isLoading = true, isLoadingMore = false, error = null) }
            try {
                val result = loadPageInternal(
                    query = validated.canonicalQuery,
                    normalizedTypeId = validated.normalizedTypeId,
                    startPage = 1,
                    existingTids = emptySet(),
                    minVisibleItems = minVisibleItems,
                    maxAutoBackfillPages = maxAutoBackfillPages,
                    priority = priority,
                )
                when (result) {
                    is ThreadListBatchResult -> {
                        entry.state.value = RawThreadListCache(
                            query = validated.canonicalQuery,
                            rawThreads = result.rawThreads,
                            fetchedPage = result.fetchedPage,
                            lastPage = result.lastPage,
                            hasMore = result.hasMore,
                            pagesFetchedInBatch = result.pagesFetchedInBatch,
                            meta = result.meta,
                            isLoading = false,
                            isLoadingMore = false,
                            error = result.partialError,
                            stopReason = result.stopReason,
                        )
                    }

                    is ThreadListError -> {
                        entry.state.update { it.copy(isLoading = false, error = result) }
                    }
                }
                result
            } finally {
                entry.state.update { if (it.isLoading) it.copy(isLoading = false) else it }
            }
        }
    }

    override suspend fun loadMore(
        query: ThreadListQuery,
        minVisibleItems: Int,
        maxAutoBackfillPages: Int,
        priority: AccessPriority,
    ): ThreadListLoadResult {
        val validated = validateInputs(
            query = query,
            startPage = 1,
            minVisibleItems = minVisibleItems,
            maxAutoBackfillPages = maxAutoBackfillPages,
        ) ?: return invalidInputError(query, 1, minVisibleItems, maxAutoBackfillPages)

        val entry = entryFor(validated.canonicalQuery)
        return entry.mutex.withLock {
            val current = entry.state.value
            if (current.fetchedPage > 0 && !current.hasMore) {
                val settings = settingsRepository.settings.first()
                val resolvedSort = validated.canonicalQuery.sortByPostTime
                    ?: settings.isSortByPostTime(validated.canonicalQuery.fid)
                return@withLock ThreadListBatchResult(
                    query = validated.canonicalQuery,
                    resolvedSortByPostTime = resolvedSort,
                    rawThreads = emptyList(),
                    items = emptyList(),
                    fetchedPage = current.fetchedPage,
                    lastPage = current.lastPage,
                    hasMore = false,
                    pagesFetchedInBatch = 0,
                    meta = current.meta ?: EMPTY_PAGE_META,
                    stopReason = current.stopReason ?: ThreadListBackfillStopReason.REACHED_LAST_PAGE,
                )
            }

            val startPage = if (current.fetchedPage <= 0) 1 else current.fetchedPage + 1
            val existingTids = if (startPage == 1) {
                emptySet()
            } else {
                current.rawThreads.mapTo(LinkedHashSet(current.rawThreads.size)) { it.tid }
            }

            entry.state.update {
                if (startPage == 1) {
                    it.copy(isLoading = true, isLoadingMore = false, error = null)
                } else {
                    it.copy(isLoadingMore = true, error = null)
                }
            }
            try {
                val result = loadPageInternal(
                    query = validated.canonicalQuery,
                    normalizedTypeId = validated.normalizedTypeId,
                    startPage = startPage,
                    existingTids = existingTids,
                    minVisibleItems = minVisibleItems,
                    maxAutoBackfillPages = maxAutoBackfillPages,
                    priority = priority,
                )
                when (result) {
                    is ThreadListBatchResult -> {
                        val mergedRaw = if (startPage == 1) {
                            result.rawThreads
                        } else {
                            current.rawThreads + result.rawThreads
                        }
                        entry.state.value = RawThreadListCache(
                            query = validated.canonicalQuery,
                            rawThreads = mergedRaw,
                            fetchedPage = result.fetchedPage,
                            lastPage = result.lastPage,
                            hasMore = result.hasMore,
                            pagesFetchedInBatch = result.pagesFetchedInBatch,
                            meta = result.meta,
                            isLoading = false,
                            isLoadingMore = false,
                            error = result.partialError,
                            stopReason = result.stopReason,
                        )
                    }

                    is ThreadListError -> {
                        entry.state.update {
                            it.copy(isLoading = false, isLoadingMore = false, error = result)
                        }
                    }
                }
                result
            } finally {
                entry.state.update {
                    if (it.isLoading || it.isLoadingMore) {
                        it.copy(isLoading = false, isLoadingMore = false)
                    } else {
                        it
                    }
                }
            }
        }
    }

    override fun observeThreadList(query: ThreadListQuery): Flow<ThreadListLoadState> {
        val canonical = canonicalizeQuery(query)
        return combine(
            entryFor(canonical).state,
            showStickyFlow,
            blacklistRepository.blockedUsernames,
            visitedTidsFlow,
        ) { cached, showSticky, blocked, visited ->
            ThreadListLoadState(
                query = canonical,
                items = filterAndMapThreads(
                    rawThreads = cached.rawThreads,
                    showStickyThreads = showSticky,
                    blockedUsernames = blocked,
                    visitedTids = visited,
                ),
                fetchedPage = cached.fetchedPage,
                lastPage = cached.lastPage,
                hasMore = cached.hasMore,
                pagesFetchedInBatch = cached.pagesFetchedInBatch,
                meta = cached.meta,
                isLoading = cached.isLoading,
                isLoadingMore = cached.isLoadingMore,
                error = cached.error,
                stopReason = cached.stopReason,
            )
        }.distinctUntilChanged()
    }

    override fun observeFiltered(rawThreads: Flow<List<ThreadSummary>>): Flow<List<ThreadListItem>> =
        combine(
            rawThreads,
            showStickyFlow,
            blacklistRepository.blockedUsernames,
            visitedTidsFlow,
        ) { threads, showSticky, blocked, visited ->
            filterAndMapThreads(
                rawThreads = threads,
                showStickyThreads = showSticky,
                blockedUsernames = blocked,
                visitedTids = visited,
            )
        }.distinctUntilChanged()

    private suspend fun loadPageInternal(
        query: ThreadListQuery,
        normalizedTypeId: String?,
        startPage: Int,
        existingTids: Set<String>,
        minVisibleItems: Int,
        maxAutoBackfillPages: Int,
        priority: AccessPriority,
    ): ThreadListLoadResult {
        val initialSettings = settingsRepository.settings.first()
        val resolvedSortByPostTime = query.sortByPostTime ?: initialSettings.isSortByPostTime(query.fid)
        val showStickyThreads = initialSettings.showStickyThreads
        val avatarBaseUrl = deriveAvatarBaseUrl(initialSettings.forumServer).ifEmpty { null }
        val blockedUsernames = blacklistRepository.blockedUsernames.value
        val visitedTids = browseHistoryDao.observeVisitedTids()
            .first()
            .mapNotNullTo(HashSet()) { it.trim().takeIf(String::isNotEmpty) }

        val seenTids = existingTids.mapNotNullTo(LinkedHashSet()) { it.trim().takeIf(String::isNotEmpty) }
        val collectedRawThreads = mutableListOf<ThreadSummary>()
        val collectedVisibleItems = mutableListOf<ThreadListItem>()

        var currentPage = startPage
        var fetchedPage = startPage - 1
        var lastPage = startPage
        var pagesFetchedInBatch = 0
        var lastMeta: DiscuzPageMeta? = null
        var stopReason = ThreadListBackfillStopReason.TARGET_REACHED
        var partialError: ThreadListError? = null

        while (pagesFetchedInBatch < maxAutoBackfillPages) {
            val relativePath = buildThreadListPath(
                fid = query.fid,
                page = currentPage,
                typeId = normalizedTypeId,
                sortByPostTime = resolvedSortByPostTime,
            )
            val requireAuth = authRepository.currentAccount.value != null
            val response = try {
                forumHttpClient.getHtml(
                    relativePath = relativePath,
                    priority = priority,
                    requireAuth = requireAuth,
                )
            } catch (e: IOException) {
                val error = mapThreadListIoException(e)
                if (pagesFetchedInBatch > 0 && collectedVisibleItems.isNotEmpty()) {
                    partialError = error
                    stopReason = ThreadListBackfillStopReason.PARTIAL_ERROR
                    break
                }
                return error
            }

            val parsed = ThreadListParser.parse(response.html, avatarBaseUrl)
            val pageError = classifyPageError(response, parsed)
            if (pageError != null) {
                if (pagesFetchedInBatch > 0 && collectedVisibleItems.isNotEmpty()) {
                    partialError = pageError
                    stopReason = ThreadListBackfillStopReason.PARTIAL_ERROR
                    break
                }
                return pageError
            }

            pagesFetchedInBatch++
            fetchedPage = currentPage
            lastPage = parsed.lastPage
            lastMeta = parsed.meta

            if (!parsed.hasThreadRows || parsed.threads.isEmpty()) {
                stopReason = ThreadListBackfillStopReason.EMPTY_PAGE
                break
            }

            for (raw in parsed.threads) {
                if (!isValidThreadId(raw.tid)) continue
                val canonicalTid = raw.tid.trim()
                if (!seenTids.add(canonicalTid)) continue
                val summary = if (canonicalTid == raw.tid) raw else raw.copy(tid = canonicalTid)
                collectedRawThreads.add(summary)
                if (shouldDisplayThread(summary, showStickyThreads, blockedUsernames)) {
                    collectedVisibleItems.add(
                        ThreadListItem(
                            summary = summary,
                            isVisited = canonicalTid in visitedTids,
                        ),
                    )
                }
            }

            if (collectedVisibleItems.size >= minVisibleItems) {
                stopReason = ThreadListBackfillStopReason.TARGET_REACHED
                break
            }

            if (currentPage >= parsed.lastPage) {
                stopReason = ThreadListBackfillStopReason.REACHED_LAST_PAGE
                break
            }

            if (pagesFetchedInBatch >= maxAutoBackfillPages) {
                stopReason = ThreadListBackfillStopReason.SAFETY_LIMIT_REACHED
                break
            }

            currentPage++
        }

        val hasMore = when (stopReason) {
            ThreadListBackfillStopReason.REACHED_LAST_PAGE,
            ThreadListBackfillStopReason.EMPTY_PAGE,
            -> false

            ThreadListBackfillStopReason.TARGET_REACHED,
            ThreadListBackfillStopReason.SAFETY_LIMIT_REACHED,
            ThreadListBackfillStopReason.PARTIAL_ERROR,
            -> fetchedPage < lastPage
        }

        return ThreadListBatchResult(
            query = query,
            resolvedSortByPostTime = resolvedSortByPostTime,
            rawThreads = collectedRawThreads,
            items = collectedVisibleItems,
            fetchedPage = fetchedPage,
            lastPage = lastPage,
            hasMore = hasMore,
            pagesFetchedInBatch = pagesFetchedInBatch,
            meta = lastMeta ?: EMPTY_PAGE_META,
            stopReason = stopReason,
            partialError = partialError,
        )
    }

    private fun entryFor(canonicalQuery: ThreadListQuery): QueryCacheEntry =
        cacheEntries.computeIfAbsent(canonicalQuery) { QueryCacheEntry(canonicalQuery) }

    private fun classifyPageError(
        response: ForumHtmlResponse,
        parsed: ThreadListPage,
    ): ThreadListError? {
        val siteError = parsed.meta.errorMessage
        if (siteError != null) {
            val trimmed = siteError.trim()
            if (!parsed.meta.session.isLoggedIn && isGuestAuthRequiredMessage(trimmed)) {
                return ThreadListError.AuthRequired(
                    reason = AuthRequiredReason.NOT_LOGGED_IN,
                    message = trimmed.ifEmpty { "需要登录后查看该版块" },
                )
            }
            return ThreadListError.SiteError(trimmed.ifEmpty { "加载帖子列表失败" })
        }

        if (!parsed.meta.session.isLoggedIn && !parsed.hasThreadRows && parsed.threads.isEmpty()) {
            if (isLoginRedirectOrFormPage(response)) {
                return ThreadListError.AuthRequired(
                    reason = AuthRequiredReason.NOT_LOGGED_IN,
                    message = "需要登录后查看该版块",
                )
            }
        }
        return null
    }

    private data class ValidatedInputs(
        val canonicalQuery: ThreadListQuery,
        val normalizedTypeId: String?,
    )

    private class QueryCacheEntry(query: ThreadListQuery) {
        val mutex = Mutex()
        val state = MutableStateFlow(RawThreadListCache(query = query))
    }

    private data class RawThreadListCache(
        val query: ThreadListQuery,
        val rawThreads: List<ThreadSummary> = emptyList(),
        val fetchedPage: Int = 0,
        val lastPage: Int = 0,
        val hasMore: Boolean = false,
        val pagesFetchedInBatch: Int = 0,
        val meta: DiscuzPageMeta? = null,
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val error: ThreadListError? = null,
        val stopReason: ThreadListBackfillStopReason? = null,
    )

    internal companion object {
        private const val THREAD_LIST_PATH_PREFIX = "forumdisplay.php?fid="
        private const val AVATAR_PATH = "uc_server/data/avatar/"

        private val EMPTY_PAGE_META = DiscuzPageMeta(
            session = DiscuzSessionInfo(isLoggedIn = false, uid = null, username = null),
            formhash = null,
            errorMessage = null,
            promptCounts = PromptCounts(),
        )

        private val GUEST_AUTH_REQUIRED_MARKERS = listOf(
            "登录",
            "登陆",
            "无权",
            "没有权限",
            "权限",
            "游客",
        )

        private fun canonicalizeQuery(query: ThreadListQuery): ThreadListQuery =
            query.copy(typeId = query.typeId?.trim()?.takeIf { it.isNotEmpty() })

        private fun validateInputs(
            query: ThreadListQuery,
            startPage: Int,
            minVisibleItems: Int,
            maxAutoBackfillPages: Int,
        ): ValidatedInputs? {
            if (query.fid <= 0 || startPage < 1 || minVisibleItems < 1 || maxAutoBackfillPages < 1) {
                return null
            }
            val rawTypeId = query.typeId?.trim()?.takeIf { it.isNotEmpty() }
            if (rawTypeId != null && !isValidThreadId(rawTypeId)) {
                return null
            }
            return ValidatedInputs(
                canonicalQuery = query.copy(typeId = rawTypeId),
                normalizedTypeId = rawTypeId,
            )
        }

        private fun invalidInputError(
            query: ThreadListQuery,
            startPage: Int,
            minVisibleItems: Int,
            maxAutoBackfillPages: Int,
        ): ThreadListError.InvalidInput = when {
            query.fid <= 0 -> ThreadListError.InvalidInput("版块 ID 无效")
            startPage < 1 -> ThreadListError.InvalidInput("页码必须大于等于 1")
            minVisibleItems < 1 -> ThreadListError.InvalidInput("每批最少可见条数必须大于等于 1")
            maxAutoBackfillPages < 1 -> ThreadListError.InvalidInput("自动补页上限必须大于等于 1")
            else -> ThreadListError.InvalidInput("主题分类 ID 无效")
        }

        internal fun isValidThreadId(id: String?): Boolean {
            if (!DiscuzUrlHelper.isValidId(id)) return false
            val trimmed = id!!.trim()
            return trimmed.all { it in '0'..'9' }
        }

        internal fun shouldDisplayThread(
            summary: ThreadSummary,
            showStickyThreads: Boolean,
            blockedUsernames: Set<String>,
        ): Boolean {
            if (!showStickyThreads && summary.isSticky) return false
            val normalizedAuthor = summary.author.trim()
            if (normalizedAuthor.isNotEmpty() && normalizedAuthor in blockedUsernames) return false
            return true
        }

        internal fun filterAndMapThreads(
            rawThreads: List<ThreadSummary>,
            showStickyThreads: Boolean,
            blockedUsernames: Set<String>,
            visitedTids: Set<String>,
        ): List<ThreadListItem> {
            if (rawThreads.isEmpty()) return emptyList()
            val seenTids = HashSet<String>(rawThreads.size)
            return buildList(rawThreads.size) {
                for (raw in rawThreads) {
                    if (!isValidThreadId(raw.tid)) continue
                    val canonicalTid = raw.tid.trim()
                    if (!seenTids.add(canonicalTid)) continue
                    val summary = if (canonicalTid == raw.tid) raw else raw.copy(tid = canonicalTid)
                    if (!shouldDisplayThread(summary, showStickyThreads, blockedUsernames)) continue
                    add(
                        ThreadListItem(
                            summary = summary,
                            isVisited = canonicalTid in visitedTids,
                        ),
                    )
                }
            }
        }

        internal fun buildThreadListPath(
            fid: Int,
            page: Int,
            typeId: String?,
            sortByPostTime: Boolean,
        ): String = buildString {
            append(THREAD_LIST_PATH_PREFIX)
            append(fid)
            append("&page=")
            append(page)
            if (!typeId.isNullOrBlank()) {
                append("&filter=type&typeid=")
                append(typeId.trim())
            }
            if (sortByPostTime) {
                append("&orderby=dateline")
            }
        }

        internal fun deriveAvatarBaseUrl(forumServer: String): String {
            val trimmed = forumServer.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            val forumBase = if (trimmed.endsWith("/forum", ignoreCase = true)) {
                "$trimmed/"
            } else {
                "$trimmed/forum/"
            }
            return "$forumBase$AVATAR_PATH"
        }

        private fun isGuestAuthRequiredMessage(message: String): Boolean =
            message.isNotEmpty() && GUEST_AUTH_REQUIRED_MARKERS.any { marker -> message.contains(marker) }

        private fun isLoginRedirectOrFormPage(response: ForumHtmlResponse): Boolean {
            if (response.url.contains("logging.php", ignoreCase = true)) return true
            val html = response.html
            return html.contains("name=\"login\"", ignoreCase = true) ||
                html.contains("id=\"loginform\"", ignoreCase = true) ||
                html.contains("action=\"logging.php?action=login", ignoreCase = true)
        }
    }
}
